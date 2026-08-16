package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.ChapterTranslationSummary
import eu.kanade.translation.batch.BatchProgressReconciler
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.batch.TranslationBatchTrackerRegistry
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.ChapterQueuePreflight
import eu.kanade.translation.model.ChapterRevisionEligibility
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.RevisionConfirmation
import eu.kanade.translation.model.RevisionPreflightOutcome
import eu.kanade.translation.model.RevisionPreflightResult
import eu.kanade.translation.model.RevisionPreflightToken
import eu.kanade.translation.model.RevisionRejectionReason
import eu.kanade.translation.model.RevisionReport
import eu.kanade.translation.model.RevisionReviewerOption
import eu.kanade.translation.model.RevisionScope
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.findRunningSameSourceConflict
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.staleQueuedChaptersToEvict
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.model.toQueuedChapterView
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.translator.RevisionDriver
import eu.kanade.translation.translator.RevisionPlanner
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {
    private val pipeline = TranslationPipeline(context, provider)
    private val translator = ChapterTranslator(context, provider, pipeline = pipeline)

    // Held here (DI singleton) so deleteTranslation can evict stale reader page-stream closures pointing at the deleted rendered/cleaned PNGs.
    private val streamRegistry: TranslationStreamRegistry = Injekt.get()

    /**
     * Application-lifetime scope for one-off init work (queue rehydration). SupervisorJob so a
     * failure in restoreQueue does not cancel unrelated work; IO dispatcher because restoreQueue does DB reads.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Owns single-page + auto-prefetch job scheduling, dedup, and cancellation. This manager
     * keeps the store lifecycle (open/evict/observe), chapter queue, and translation-file I/O.
     * The scheduler resolves the per-chapter store back through this manager via [storeResolver]
     * — store instances are shared between reader and translator, so they must not be owned by the scheduler.
     */
    val scheduler = eu.kanade.translation.scheduling.TranslationScheduler(
        executor = pipeline,
        storeResolver = eu.kanade.translation.scheduling.TranslationStoreResolver { chapterId ->
            activeStores.get(chapterId)
        },
        immediateStoreResolver = { chapterId -> activeStores.get(chapterId) },
    )

    init {
        // Share the store instance between reader and translator so live updates do not need a chapter reload.
        pipeline.activeStoreResolver = { translation ->
            openOrCreateActiveChapterTranslationStore(
                translation.chapter.id!!,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )
        }
        // NOTE: activeStoreUnregister is intentionally NOT wired. Evicting after
        // each single-page translation broke live updates (reader captured the
        // StateFlow once; next translate got a fresh unobserved store). Eviction
        // now happens only on chapter change / reader exit (cancelPageTranslations /
        // cancelAllPageTranslations callers).

        // Native quarantine reports timeout only after the underlying call exits;
        // evict the stale job so a subsequent request can be admitted safely.
        pipeline.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                scheduler.markPageJobStuck(chapterId, pageKey)
            }
        }

        // Batch tracker factory: lets the pipeline create and register a tracker per active batch (observable via observeBatchProgress).
        pipeline.batchTrackerFactory = { chapterId, store, orderedPageKeys ->
            createBatchTracker(chapterId, store, orderedPageKeys)
        }

        // Rehydrate persisted batch queue on IO so a crash mid-batch no longer loses it.
        // Entries get status QUEUE; user taps Start to resume — never auto-starts OCR/LLM on launch.
        applicationScope.launch { translator.restoreQueue() }
    }

    /**
     * Evicts a single-page translation job whose worker is stuck in uncancellable native
     * code past its deadline. Delegates to the scheduler, which owns the [activePageJobs] map.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        scheduler.markPageJobStuck(chapterId, pageKey)
    }

    private val activeStores = ActiveChapterStoreRegistry()
    private val batchTrackerRegistry = TranslationBatchTrackerRegistry()
    private val translateAfterDownload = ConcurrentHashMap<Long, TranslationRequest>()
    private val workMetadata = ConcurrentHashMap<Long, TranslationWorkMetadata>()

    private data class TranslationRequest(
        val manga: Manga,
        val chapter: Chapter,
    )

    private data class TranslationWorkMetadata(
        val mangaId: Long,
        val mangaTitle: String,
        val chapterName: String,
    )

    /** Owns tracker reducer jobs; reader flows observe the selected store directly. */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any { it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING }

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        chapter.id?.let { translateAfterDownload[it] = TranslationRequest(manga, chapter) }
    }

    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) {
        val chapterId = chapter.id ?: return
        val request = translateAfterDownload.remove(chapterId) ?: return
        if (request.manga.id != manga.id) return
        translateChapter(request.manga, request.chapter)
    }

    fun stopReaderTranslations(reason: String) {
        // CP8: the reader going background/close is a UI-lifecycle event, NOT process
        // ownership. Batch queue and standalone revisions must survive it (they are
        // owned by the application-scope translator/storeScope, not the reader). So
        // cancel only the reader-owned in-flight single-page jobs; leave the batch
        // queue and revision ownership intact. Foreground-resume / a fresh reader
        // session re-attaches; user explicit Stop/Disable still cancels everything
        // (those paths call cancelAllPageTranslations with cancelRevisions = true).
        cancelAllPageTranslations(cancelBatchQueue = false, cancelRevisions = false)
        if (!isAnyBatchTranslationActive) {
            translatorStop(reason, closeEngines = false)
        }
    }

    /**
     * Starts reader-owned teardown on the manager lifetime rather than the
     * ReaderViewModel scope. The deferred completes only after scheduler
     * coordinator/native/page jobs have joined and reader stores are evicted.
     */
    fun requestReaderStop(reason: String): Deferred<Unit> =
        applicationScope.async(start = CoroutineStart.UNDISPATCHED) {
            awaitReaderStop(reason)
        }

    /** Joined counterpart for callers that already own a non-cancelled scope. */
    suspend fun awaitReaderStop(reason: String) {
        scheduler.awaitReaderStop()
        val chapterIdsToEvict = activeStores.chapterIds()
            .filter { !isBatchTranslationActive(it) }
        chapterIdsToEvict.forEach { chapterId ->
            activeStores.get(chapterId)?.clearTransientQueuePages(reason)
            unregisterActiveTranslationStore(chapterId)
        }
        if (!isAnyBatchTranslationActive) {
            translatorStop(reason, closeEngines = false)
        }
    }

    fun translatorStart() = translator.start()
    fun translatorStop(reason: String? = null, closeEngines: Boolean = false) = translator.stop(reason, closeEngines)

    fun onMemoryPressure(level: Int) {
        // CP8: classify the trim level once and act on the ownership class, not the
        // raw numeric level. The previous `level >= TRIM_MEMORY_RUNNING_LOW` check
        // over-matched: TRIM_MEMORY_UI_HIDDEN(20)/BACKGROUND(40)/MODERATE(60) all
        // exceed RUNNING_LOW(15) numerically, so a benign app-background trim
        // cancelled in-flight page work on every background trip. Now only a true
        // foreground memory crunch (Critical) cancels page jobs and stops a
        // standalone revision terminally; Benign just releases pooled caches.
        val pressureClass = MemoryPressurePolicy.classify(level)
        translator.onMemoryPressure(level, pressureClass)
        // TachiyomiAT: Do NOT proactively cancel page translations or revisions here.
        // Aggressive vendor OSes (e.g. Oplus) send TRIM_MEMORY_COMPLETE (80, Critical)
        // simply when the app is backgrounded. Cancelling jobs drops the HTTP connection
        // to the AI engine, making it seem like the UI "lagged behind" while the engine
        // keeps translating. If the OS actually needs memory, it will kill the process.
    }

    fun startTranslation() {
        if (translator.isRunning) return
        translator.start()
    }

    fun pauseTranslation() {
        translator.pause()
        translator.stop()
    }

    fun clearQueue() {
        translator.clearQueue()
        translator.stop()
    }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    fun isBatchTranslationActive(chapterId: Long): Boolean {
        return queueState.value.any { translation ->
            translation.chapter.id == chapterId &&
                (translation.status == Translation.State.QUEUE || translation.status == Translation.State.TRANSLATING)
        }
    }

    private val revisionOwnership = RevisionOwnershipGate()

    fun isRevisionActive(chapterId: Long): Boolean {
        return revisionOwnership.isActive(chapterId)
    }

    private fun releaseRevisionOwnership(chapterId: Long, job: Job? = null) {
        revisionOwnership.release(chapterId, job)
    }

    fun getRevisionPreflight(
        chapterId: Long,
        scope: RevisionScope,
        reviewerEngine: AiEngine,
        reviewerModel: String,
    ): RevisionPreflightResult? {
        val store = activeStores.get(chapterId) ?: return null
        val liveState = store.state.value
        val orderedPageKeys = liveState.keys.sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
        val orderedPages = LinkedHashMap<String, PageTranslation>()
        orderedPageKeys.forEach { pk -> liveState[pk]?.let { orderedPages[pk] = it.detachedCopy() } }
        val glossary = store.glossarySnapshot()
        val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
            ?: 512
        val plan = RevisionPlanner.plan(
            orderedPages = orderedPages,
            chapterGlossary = glossary,
            requestedOutputTokens = requestedOutputTokens,
            scope = scope,
        )
        val token = RevisionPreflightToken(
            chapterId = chapterId,
            storeGeneration = store.state.value.values.sumOf { it.updatedAt },
            scope = scope,
            orderedTargetFingerprints = plan.allTargets.map { "${it.pageKey}:${it.blockIndex}" },
            sourceLanguage = translationPreferences.translateFromLanguage().get(),
            targetLanguage = translationPreferences.translateToLanguage().get(),
            reviewerConfigFingerprint = "${reviewerEngine.name}/$reviewerModel",
        )
        return RevisionPreflightResult(
            token = token,
            eligibleTargetCount = plan.allTargets.size,
            estimatedRequestGroups = plan.groups.size,
        )
    }

    fun startRevision(
        manga: Manga,
        chapter: Chapter,
        scope: RevisionScope,
        reviewerEngine: AiEngine,
        reviewerModel: String,
    ): Flow<TranslationProgressSnapshot>? {
        val chapterId = chapter.id ?: return null
        if (isBatchTranslationActive(chapterId)) {
            logcat(LogPriority.WARN) { "Cannot start revision for chapter $chapterId: batch translation is active" }
            return null
        }

        // Reserve revision ownership before cancelling Auto. updateAutoWindow
        // uses the same lock, so it cannot observe the gap between reservation
        // and job registration and re-arm Auto in that interval. The cleanup
        // transaction starts immediately after this reservation decision below;
        // a cancellation/resolver/store failure therefore cannot strand the
        // pending reservation.
        val revisionReserved = revisionOwnership.withLock {
            if (isBatchTranslationActive(chapterId) || !revisionOwnership.reserveLocked(chapterId)) {
                return@withLock false
            }
            true
        }
        if (!revisionReserved) {
            if (isBatchTranslationActive(chapterId)) return null
            return getBatchTracker(chapterId)?.snapshot
        }

        var tracker: TranslationBatchProgressTracker? = null
        var transaction: RevisionOwnershipTransaction? = null
        try {
            // Keep Auto cancellation inside the reservation transaction while
            // retaining the gate across the cancellation call. A pending
            // reservation suppresses concurrent Auto/revision admission; the
            // finally below releases it if this call or any later setup step
            // throws before a job is registered.
            revisionOwnership.withLock {
                scheduler.cancelAutoTranslations(chapterId)
            }
            // All setup belongs to the reservation transaction. Any resolver,
            // store, preference, or tracker failure below must release the
            // reservation rather than suppressing future revisions/Auto.
            val source = sourceManager.get(manga.source) as? HttpSource
                ?: return null
            val store = openOrCreateActiveChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            ) ?: return null

            val orderedPageKeys = store.state.value.keys.sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
            val createdTracker = createBatchTracker(chapterId, store, orderedPageKeys)
            tracker = createdTracker
            val ownedTracker = createdTracker
            val ownership = RevisionOwnershipTransaction(
                gate = revisionOwnership,
                chapterId = chapterId,
                disposeTracker = {
                    batchTrackerRegistry.disposeIfCurrent(chapterId, ownedTracker)
                },
            )
            transaction = ownership
            val job = storeScope.launch(start = CoroutineStart.LAZY) {
                ownership.markBodyStarted()
                try {
                    val translatorEngine = pipeline.getContextualTranslator(reviewerEngine, reviewerModel)
                        ?: throw IllegalStateException("Reviewer model not available: ${reviewerEngine.name}/$reviewerModel")

                    val listener = object : RevisionDriver.ProgressListener {
                        override fun onBegin(totalBlocks: Int, skippedBlocks: Int, userEditedBlocks: Int, groupsCount: Int) {
                            ownedTracker.beginRevision(totalBlocks, skippedBlocks, userEditedBlocks)
                        }
                        override fun onGroupStart(pageKeys: Set<String>, groupTargetsCount: Int) {
                            ownedTracker.markRevisionChunkRunning(pageKeys, groupTargetsCount)
                        }
                        override fun onGroupComplete(appliedCount: Int, failedCount: Int) {
                            ownedTracker.markRevisionChunkCompleted(appliedCount)
                            if (failedCount > 0) ownedTracker.markRevisionChunkFailed(failedCount)
                        }
                        override fun onOverBudget(pageKey: String, blockIndex: Int, estimatedTokens: Int, maxPromptTokens: Int) {
                            ownedTracker.markRevisionChunkFailed(1)
                        }
                        override fun onFinished() {
                            ownedTracker.markRevisionFinished()
                        }
                    }

                    val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
                        ?: 512

                    val report = RevisionDriver.runRevision(
                        store = store,
                        contextualTranslator = translatorEngine,
                        scope = scope,
                        requestedOutputTokens = requestedOutputTokens,
                        chapterId = chapterId,
                        chapterName = chapter.name,
                        listener = listener,
                        onPageUpdated = { pageKey ->
                            pipeline.tryRenderStandalone(manga, chapter, source, pageKey, store)
                        },
                    )

                    val reconciliation = BatchProgressReconciler.reconcile(
                        pageMap = store.state.value,
                        orderedKeys = orderedPageKeys,
                        activeGeneration = store.currentGeneration,
                    )

                    store.publishSummary(
                        ChapterTranslationSummary(
                            expectedPageCount = orderedPageKeys.size,
                            terminalOutcome = reconciliation.chapterStatus.value,
                            unresolvedRevisionCount = reconciliation.unresolvedRevisionCount,
                            updatedAtMillis = System.currentTimeMillis(),
                            latestRevisionReport = report,
                        ),
                    )
                    store.flush()
                    ownership.markTerminalRequested()
                    ownedTracker.finish(reconciliation)
                } catch (e: CancellationException) {
                    ownership.markTerminalRequested()
                    ownedTracker.abort(emptySet(), "Revision cancelled")
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Revision job failed: chapter=${chapter.name}" }
                    ownership.markTerminalRequested()
                    ownedTracker.abort(emptySet(), e.message ?: "Unknown error")
                }
            }

            if (!ownership.register(job)) return null
            return ownedTracker.snapshot
        } catch (error: Throwable) {
            transaction?.abortSetup()
            if (transaction == null) {
                tracker?.let { batchTrackerRegistry.disposeIfCurrent(chapterId, it) }
            }
            throw error
        } finally {
            if (transaction == null) {
                releaseRevisionOwnership(chapterId)
            } else {
                transaction?.abortSetup()
            }
        }
    }

    fun cancelRevision(chapterId: Long) {
        revisionOwnership.cancel(chapterId)
    }

    /**
     * Latest bounded revision report for a chapter, read from the durable summary
     * sidecar without any image decode. Null when no run completed yet, or when
     * the store was deleted/purged. UI consumes this for the terminal result sheet
     * and "View last review"; it carries only K/C/U totals and accepted changes.
     */
    suspend fun getLatestRevisionReport(chapterId: Long): RevisionReport? {
        return activeStores.get(chapterId)?.readSummary()?.latestRevisionReport
    }

    /**
     * CP7 pure UI communication: the configured contextual reviewers the user may
     * pick for standalone revision. A provider appears only when it has a
     * non-blank credential (API key, or LM Studio base URL) AND a non-blank model.
     * The list is the single source of truth for the reviewer picker; the UI never
     * infers capability or reads credentials.
     */
    fun revisionReviewerOptions(): List<RevisionReviewerOption> {
        return eu.kanade.translation.translator.AiTranslatorKind.entries.mapNotNull { kind ->
            val engine = kind.engine
            val model = translationPreferences.translationAiModel(engine).get()
            if (model.isBlank()) return@mapNotNull null
            val configured = if (engine == tachiyomi.domain.translation.AiEngine.LMSTUDIO) {
                translationPreferences.translationAiBaseUrlLmStudio().get().isNotBlank()
            } else {
                translationPreferences.translationAiApiKey(engine).get().isNotBlank()
            }
            if (!configured) return@mapNotNull null
            RevisionReviewerOption(
                engine = engine,
                model = model,
                displayLabel = "${kind.label} · $model",
            )
        }
    }

    /**
     * CP7 pure UI communication: manager-derived revision eligibility for one
     * chapter, computed from durable store state without image decode. Returns
     * null when the chapter has no store, so the UI hides the REVIEW action.
     *
     * Counts are backend-owned: flaggedTargets, allTranslatedTargets, and
     * userEditedExclusions are computed here so the UI never computes them.
     */
    suspend fun snapshotRevisionEligibility(chapterId: Long): ChapterRevisionEligibility? {
        val store = activeStores.get(chapterId) ?: return null
        val summary = store.readSummary()
        val liveState = store.state.value
        val translatedPages = liveState.values.count { it.hasRecognizedTranslation }
        val expectedPages = summary?.expectedPageCount

        var flagged = 0
        var allTranslated = 0
        var userEdited = 0
        liveState.values.forEach { page ->
            page.blocks.forEach { block ->
                val userTouched = block.userEditedAt != null
                val nonBlank = block.text.isNotBlank() && block.translation.isNotBlank()
                if (userTouched && nonBlank) userEdited++
                if (nonBlank) {
                    allTranslated++
                    if (block.needsRevision) flagged++
                }
            }
        }

        return ChapterRevisionEligibility(
            chapterId = chapterId,
            translatedPages = translatedPages,
            expectedPages = expectedPages,
            flaggedTargets = flagged,
            allTranslatedTargets = allTranslated,
            userEditedExclusions = userEdited,
            reviewerOptions = revisionReviewerOptions().toImmutableList(),
            persistedSourceLanguage = translationPreferences.translateFromLanguage().get(),
            persistedTargetLanguage = translationPreferences.translateToLanguage().get(),
        )
    }

    /**
     * CP7 pure UI communication: runs preflight for a candidate reviewer/scope and
     * returns a typed [RevisionPreflightOutcome]. Re-validates active-batch and
     * active-revision admission so the UI can show a rejection reason without
     * dispatching a start. The opaque token lives backend-side and is re-checked
     * at [startRevision]; this method never trusts displayed state for approval.
     */
    suspend fun runRevisionPreflight(
        chapterId: Long,
        chapterName: String,
        scope: RevisionScope,
        reviewerEngine: tachiyomi.domain.translation.AiEngine,
        reviewerModel: String,
    ): RevisionPreflightOutcome {
        if (isBatchTranslationActive(chapterId)) {
            logcat(LogPriority.WARN) { "Revision preflight rejected: ACTIVE_BATCH (chapter=$chapterId)" }
            return RevisionPreflightOutcome.Rejected(chapterId, RevisionRejectionReason.ACTIVE_BATCH)
        }
        if (isRevisionActive(chapterId)) {
            logcat(LogPriority.WARN) { "Revision preflight rejected: REVISION_ACTIVE (chapter=$chapterId)" }
            return RevisionPreflightOutcome.Rejected(chapterId, RevisionRejectionReason.REVISION_ACTIVE)
        }
        val options = revisionReviewerOptions()
        if (options.none { it.engine == reviewerEngine && it.model == reviewerModel }) {
            logcat(LogPriority.WARN) {
                "Revision preflight rejected: NO_REVIEWER_CONFIGURED (chapter=$chapterId, " +
                    "reviewer=$reviewerEngine/$reviewerModel, configuredOptions=${options.map { "${it.engine}/${it.model}" }})"
            }
            return RevisionPreflightOutcome.Rejected(chapterId, RevisionRejectionReason.NO_REVIEWER_CONFIGURED)
        }
        val preflight = getRevisionPreflight(chapterId, scope, reviewerEngine, reviewerModel)
            ?: run {
                logcat(LogPriority.WARN) { "Revision preflight rejected: CHAPTER_DELETED/no store (chapter=$chapterId)" }
                return RevisionPreflightOutcome.Rejected(chapterId, RevisionRejectionReason.CHAPTER_DELETED)
            }
        if (preflight.eligibleTargetCount == 0) {
            logcat(LogPriority.WARN) { "Revision preflight rejected: NO_TARGETS (chapter=$chapterId, scope=$scope)" }
            return RevisionPreflightOutcome.Rejected(chapterId, RevisionRejectionReason.NO_TARGETS)
        }
        // Chapter summaries do not carry a language pair. Both fresh and legacy
        // stores therefore use the current translation preferences, which are
        // also captured in the preflight token. A missing sidecar must not make
        // standalone review impossible when the store still has targets.
        val eligibility = snapshotRevisionEligibility(chapterId)
        val kind = eu.kanade.translation.translator.AiTranslatorKind.entries.first { it.engine == reviewerEngine }
        val confirmation = RevisionConfirmation(
            chapterName = chapterName,
            scope = scope,
            reviewerLabel = "${kind.label} · $reviewerModel",
            reviewerEngine = reviewerEngine,
            reviewerModel = reviewerModel,
            sourceLanguage = eligibility?.persistedSourceLanguage ?: translationPreferences.translateFromLanguage().get(),
            targetLanguage = eligibility?.persistedTargetLanguage ?: translationPreferences.translateToLanguage().get(),
            translatedPages = eligibility?.translatedPages ?: 0,
            expectedPages = eligibility?.expectedPages,
            targetCount = preflight.eligibleTargetCount,
            exclusionCount = eligibility?.userEditedExclusions ?: 0,
            estimatedRequestGroups = preflight.estimatedRequestGroups,
            partialWarning = eligibility?.isPartial ?: false,
        )
        return RevisionPreflightOutcome.Ready(chapterId, confirmation)
    }

    fun translateChapter(manga: Manga, chapters: Chapter) {
        val chapterId = chapters.id ?: return
        revisionOwnership.withLock {
            if (revisionOwnership.isActiveLocked(chapterId)) {
                logcat(LogPriority.WARN) { "Cannot queue batch translation for chapter $chapterId: revision is active" }
                return@withLock
            }
            // Batch acquisition and Auto shutdown are one ownership transition.
            // A recovery reconcile takes the same gate, so it cannot re-admit
            // this chapter between the shutdown and queue insertion.
            scheduler.shutdownAutoCoordinator(chapterId)
            // TachiyomiAT bug 3 fix: an explicit Start Batch on a different chapter
            // must not silently resume a previously queued/running chapter of the
            // same source. The translator's launchTranslatorJob picks queue entries
            // "first per source" in insertion order, so a stale queued chapter would
            // run before the one the user just requested. Evict stale QUEUE entries
            // (artifacts preserved via the store) before queuing the new one.
            // Running conflicts are reported separately via [translateChapterPreflight]
            // so the UI can confirm cancellation of in-flight work.
            evictStaleQueuedChapters(chapterId, manga.source)
            translator.queueChapter(manga, chapters)
            startTranslation()
        }
    }

    fun translateChapters(manga: Manga, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        revisionOwnership.withLock {
            chapters.forEach { chapter ->
                val chapterId = chapter.id ?: return@forEach
                if (revisionOwnership.isActiveLocked(chapterId)) return@forEach
                scheduler.shutdownAutoCoordinator(chapterId)
                translator.queueChapter(manga, chapter)
            }
            startTranslation()
        }
    }

    /**
     * TachiyomiAT bug 3 fix: preflight check for an explicit Start Batch action.
     * Returns the running conflict (if any) so the UI can ask the user before
     * cancelling in-flight work on a different chapter of the same source.
     *
     * Stale QUEUE entries are NOT reported here; they are evicted automatically
     * by [translateChapter] since dropping a not-yet-started queue entry never
     * loses accepted artifacts. Only an actively TRANSLATING chapter needs user
     * confirmation because cancelling it mid-OCR/inpaint discards the in-flight
     * page's native work.
     */
    fun translateChapterPreflight(manga: Manga, chapter: Chapter): ChapterQueuePreflight {
        val chapterId = chapter.id
            ?: return ChapterQueuePreflight.NoConflict
        if (isRevisionActive(chapterId)) {
            return ChapterQueuePreflight.RevisionBlocked(chapterId)
        }
        val view = queueState.value.map { it.toQueuedChapterView() }
        val conflict = findRunningSameSourceConflict(view, chapterId, manga.source)
            ?: return ChapterQueuePreflight.NoConflict
        return ChapterQueuePreflight.RunningConflict(
            chapterId = conflict.chapterId,
            chapterName = conflict.chapterName,
        )
    }

    /**
     * Evicts every queued (status == QUEUE) chapter of [sourceId] other than
     * [keepChapterId] from the batch queue. Preserves accepted artifacts:
     * [removeFromTranslationQueue] only drops the queue entry; the chapter's
     * ChapterTranslationStore and its persisted OCR/inpaint/translation data
     * stay intact, so a later Start Batch on that chapter resumes via the
     * BatchResumeGateDecider's artifact scan without redoing completed work.
     */
    private fun evictStaleQueuedChapters(keepChapterId: Long, sourceId: Long) {
        val staleIds = staleQueuedChaptersToEvict(
            queueState.value.map { it.toQueuedChapterView() },
            keepChapterId,
            sourceId,
        ).map { it.chapterId }.toSet()
        if (staleIds.isEmpty()) return
        val stale = queueState.value
            .filter { it.chapter.id != null && it.chapter.id in staleIds }
            .map { it.chapter }
        stale.forEach { chapter ->
            logcat(LogPriority.INFO) {
                "TachiyomiAT evicting stale queued chapter ${chapter.id} (source=$sourceId) in favor of $keepChapterId; artifacts preserved"
            }
            removeFromTranslationQueue(chapter)
        }
    }

    /**
     * TachiyomiAT bug 3 fix: cancels an actively running translation of
     * [chapterId] (same source) so a subsequent [translateChapter] can start on
     * a different chapter. Used after the UI confirms a [ChapterQueuePreflight.RunningConflict].
     * Cancels in-flight page jobs, durably clears transient queue pages
     * (preserving rendered/terminal artifacts), and drops the queue entry.
     */
    fun cancelRunningChapterForReplace(chapterId: Long) {
        val chapter = queueState.value
            .firstOrNull { it.chapter.id == chapterId }
            ?.chapter
            ?: return
        kotlinx.coroutines.runBlocking {
            scheduler.cancelPageTranslations(chapterId)
        }
        activeStores.get(chapterId)?.let { store ->
            kotlinx.coroutines.runBlocking {
                store.clearTransientQueuePages("Replaced by another chapter's batch")
            }
        }
        removeFromTranslationQueue(chapter)
    }

    fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State {
        val translation = getQueuedTranslationOrNull(chapterId)
        if (translation != null) return translation.status
        activeStores.get(chapterId)?.let { store ->
            val pages = store.state.value
            if (pages.values.any { it.hasRenderedResult || it.hasRecognizedTranslation }) {
                val summary = kotlinx.coroutines.runBlocking(Dispatchers.IO) { store.readSummary() }
                return when {
                    summary == null || summary.expectedPageCount != pages.size -> Translation.State.READY_WITH_WARNINGS
                    summary.outcome() == Translation.State.TRANSLATED && summary.unresolvedRevisionCount == 0 -> Translation.State.TRANSLATED
                    summary.outcome() == Translation.State.ERROR -> Translation.State.ERROR
                    else -> Translation.State.READY_WITH_WARNINGS
                }
            }
        }
        return persistedChapterStatus(chapterName, scanlator, title, sourceId)
            ?: Translation.State.NOT_TRANSLATED
    }

    fun observeChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Flow<Translation.State> {
        val queueStatusFlow = queueState.map { queue ->
            queue.find { it.chapter.id == chapterId }?.status
        }.distinctUntilChanged()

        val activeStoreStateFlow = activeStores.snapshots.flatMapLatest { map ->
            val store = map[chapterId]
            if (store != null) {
                store.state.map {
                    getChapterTranslationStatus(chapterId, chapterName, scanlator, title, sourceId)
                }
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }.distinctUntilChanged()

        return kotlinx.coroutines.flow.combine(queueStatusFlow, activeStoreStateFlow) { qStatus, diskStatus ->
            qStatus ?: diskStatus ?: getChapterTranslationStatus(chapterId, chapterName, scanlator, title, sourceId)
        }.distinctUntilChanged()
    }

    /** True when persisted output is readable, including a retry/review-ready warning outcome. */
    fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean = persistedChapterStatus(chapterName, chapterScanlator, mangaTitle, sourceId)
        .let { it == Translation.State.TRANSLATED || it == Translation.State.READY_WITH_WARNINGS }

    private fun persistedChapterStatus(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
        val source = sourceManager.get(sourceId) ?: return@runBlocking null
        val file = provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source)
            ?: return@runBlocking null
        if (!file.exists() || file.length() <= 2L) return@runBlocking null
        try {
            val pages = Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
            val readable = pages.values.any { it.hasRenderedResult || it.hasRecognizedTranslation }
            if (!readable) return@runBlocking null

            val summary = ChapterTranslationSummaryStore(file).read()
            // Page JSON predates the sidecar. It remains reader-available but can never
            // prove full completion until a full batch creates a compatible summary.
            if (summary == null) return@runBlocking Translation.State.READY_WITH_WARNINGS
            if (summary.expectedPageCount != pages.size) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT chapter summary cannot certify completion: chapter=$chapterName " +
                        "reason=expected-count mismatch expected=${summary.expectedPageCount} actual=${pages.size}"
                }
                return@runBlocking Translation.State.READY_WITH_WARNINGS
            }
            when (summary.outcome()) {
                Translation.State.TRANSLATED -> if (summary.unresolvedRevisionCount == 0) {
                    Translation.State.TRANSLATED
                } else {
                    Translation.State.READY_WITH_WARNINGS
                }
                Translation.State.READY_WITH_WARNINGS -> Translation.State.READY_WITH_WARNINGS
                Translation.State.ERROR -> Translation.State.ERROR
                else -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT chapter summary cannot certify completion: chapter=$chapterName reason=invalid terminal outcome"
                    }
                    Translation.State.READY_WITH_WARNINGS
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Translation file for $chapterName unreadable; treating as not translated" }
            null
        }
    }
    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslation> {
        try {
            val file = provider.findTranslationFile(
                chapterName,
                scanlator,
                title,
                source,
            ) ?: return emptyMap()
            return getChapterTranslation(file)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to read chapter translation for $chapterName"
            }
        }
        return emptyMap()
    }

    fun getChapterTranslationForReader(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): Map<String, PageTranslation> {
        activeStores.get(chapterId)?.state?.value?.takeIf { it.isNotEmpty() }?.let { return it }
        return getChapterTranslation(chapterName, scanlator, mangaTitle, source)
    }

    fun getChapterTranslation(
        file: UniFile,
    ): Map<String, PageTranslation> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        try {
            return@runBlocking Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
        } catch (e: Exception) {
            file.delete()
        }
        return@runBlocking emptyMap()
    }

    /** Returns whether this chapter has an existing or active translation store. */
    fun hasTranslationStore(chapter: Chapter, manga: Manga, source: Source): Boolean {
        chapter.id?.let { activeStores.get(it) }?.let { return it.state.value.isNotEmpty() }
        return provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            ?.exists() == true
    }

    /** Re-keys source URL pages to the names written by a completed download. */
    suspend fun rekeyTranslationForCompletedDownload(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        onlineKeyByPageIndex: List<String>,
        onDiskKeyByPageIndex: List<String>,
    ) {
        val chapterId = chapter.id ?: return
        if (onlineKeyByPageIndex.size != onDiskKeyByPageIndex.size) return

        val activeStore = activeStores.get(chapterId)
        val store = activeStore ?: run {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
                ?: return
            if (!file.exists()) return
            ChapterTranslationStore.open(file)
        }
        val pages = store.state.value
        if (pages.size != onlineKeyByPageIndex.size) return
        if (pages.keys.none { it in onlineKeyByPageIndex }) return
        if (pages.keys.all { it in onDiskKeyByPageIndex }) return

        // Match deleteTranslation's cancellation ordering while retaining the
        // active store instance so an open reader observes the new snapshot.
        revisionOwnership.withLock {
            cancelRevision(chapterId)
            // The gate is held for batch setup only, so joining here cannot
            // deadlock with a running batch. This method is called from the
            // downloader's IO coroutine, making the bounded blocking bridge safe.
            kotlinx.coroutines.runBlocking { translator.cancelTranslatorJobAndJoin() }
        }
        scheduler.cancelAutoTranslations(chapterId)
        scheduler.cancelPageTranslations(chapterId)
        store.beginGeneration("completed download re-key")
        val moves = store.rekeyPages(onlineKeyByPageIndex, onDiskKeyByPageIndex)
        if (moves.isEmpty()) return

        store.flush()
        val companionDir = provider.findCompanionImageDir(
            manga.title,
            source,
            chapter.name,
            chapter.scanlator,
        )
        moves.forEach { (oldKey, newKey) ->
            val oldImage = provider.companionImageNameForPage(oldKey)
            val newImage = provider.companionImageNameForPage(newKey)
            companionDir?.findFile(oldImage)?.renameTo(newImage)
        }
    }

    fun openChapterTranslationStore(file: UniFile): StateFlow<Map<String, PageTranslation>> {
        return ChapterTranslationStore.open(file).state
    }

    fun registerActiveTranslationStore(chapterId: Long, store: ChapterTranslationStore) {
        // Keep the existing instance if already registered so a reader keeps observing the same object.
        activeStores.register(chapterId, store)
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        // Mark the evicted store defunct BEFORE removing it from the registry. A worker still
        // holding a reference has late writes rejected rather than recreating deleted output.
        activeStores.remove(chapterId)?.markDefunct()
    }

    /**
     * Returns the existing active [ChapterTranslationStore] for [chapterId], or
     * opens one from disk if it is not yet registered. If neither an in-memory
     * store nor an on-disk translation file exists, this creates a fresh store
     * tied to the expected translation path and registers it, so a translator
     * starting now and a reader observing now share the same instance.
     */
    fun openOrCreateActiveChapterTranslationStore(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): ChapterTranslationStore? = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        synchronized(activeStores) {
            activeStores.get(chapterId)?.let { return@runBlocking it }
            val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
            val store = if (file != null && file.exists()) {
                ChapterTranslationStore.open(file)
            } else {
                // Create a LAZY store: the on-disk file materializes only on the first real write
                // (persistLocked), so merely opening a chapter never leaves an empty file behind that
                // would make isChapterTranslated report a false TRANSLATED state.
                val saveFile = provider.getTranslationFileName(chapterName, scanlator)
                ChapterTranslationStore.lazy {
                    provider.getMangaDir(mangaTitle, source)?.createFile(saveFile)
                        ?: throw java.io.IOException("Cannot create translation file for $chapterName")
                }
            }
            registerActiveTranslationStore(chapterId, store)
            return@runBlocking store
        }
    }

    fun openActiveChapterTranslationStore(chapterId: Long, chapterName: String, scanlator: String?, mangaTitle: String, sourceId: Long): StateFlow<Map<String, PageTranslation>>? {
        val source = sourceManager.get(sourceId) ?: return null
        return openOrCreateActiveChapterTranslationStore(chapterId, chapterName, scanlator, mangaTitle, source)?.state
    }

    fun openTranslationSession(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): TranslationSession? {
        val chapterId = chapter.id ?: return null
        val store = openOrCreateActiveChapterTranslationStore(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
        ) ?: return null
        val key = "${source.id}:${manga.id}:$chapterId"
        return TranslationSession(key, manga, chapter, source, store)
    }

    fun requestAutoWindow(
        session: TranslationSession,
        requests: List<TranslationPageRequest>,
    ) = scheduler.requestAutoWindow(session, requests)

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean =
        scheduler.cancelAutoTranslations(chapterId)

    /**
     * Ticket 03: live auto-translation snapshot from the rolling coordinator.
     * Null when no coordinator is active. The reader observes this to render
     * the compact ready-ahead status without polling the durable store.
     */
    val autoSnapshot: kotlinx.coroutines.flow.StateFlow<eu.kanade.translation.scheduling.AutoTranslationSnapshot?> =
        scheduler.autoSnapshot

    /**
     * Ticket 03: rolling coordinator window update. Accepts a desired-window
     * spec and delegates to the scheduler, which owns the coordinator.
     *
     * Batch arbitration: batch chapter translation owns the chapter and
     * suppresses auto (same rule the legacy path enforces). While a batch job
     * is queued or translating for this chapter, the rolling coordinator is
     * shut down and this call is a no-op; it re-arms on the next window update
     * once batch is no longer active. An active revision is treated the same
     * way: revision owns the chapter's translated output, so auto is suppressed
     * while [isRevisionActive] holds.
     */
    fun updateAutoWindow(
        identity: eu.kanade.translation.scheduling.AutoChapterIdentity,
        visiblePageIndex: Int,
        configuredAheadTarget: Int,
        pageCount: Int,
        session: TranslationSession,
        pageResolver: (Int) -> eu.kanade.translation.scheduling.RollingAutoCoordinator.PageWorkItem?,
        computeClass: eu.kanade.translation.translator.TranslatorComputeClass,
    ) {
        val chapterId = session.chapter.id
        revisionOwnership.withLock {
            if (chapterId != null &&
                (isBatchTranslationActive(chapterId) || revisionOwnership.isActiveLocked(chapterId))
            ) {
                scheduler.shutdownAutoCoordinator(chapterId)
                return@withLock
            }
            scheduler.updateAutoWindow(
                identity,
                visiblePageIndex,
                configuredAheadTarget,
                pageCount,
                session,
                pageResolver,
                computeClass,
            )
        }
    }

    fun shutdownAutoCoordinator() = scheduler.shutdownAutoCoordinator()

    /** Reconciles the active rolling window after a reader lifecycle/memory signal. */
    fun reconcileAutoWindow() = revisionOwnership.withLock {
        scheduler.reconcileAutoWindow { chapterId ->
            !isBatchTranslationActive(chapterId) && !revisionOwnership.isActiveLocked(chapterId)
        }
    }

    fun observeActiveStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? = activeStores.observe(chapterId)

    /**
     * Chapter-keyed active page source for reader state. Unlike a global active-store stream,
     * this never emits another chapter's pages and becomes empty when this chapter is removed.
     */
    fun selectActiveStore(chapterId: Long): Flow<Map<String, PageTranslation>> = activeStores.select(chapterId)

    fun createBatchTracker(
        chapterId: Long,
        store: ChapterTranslationStore,
        orderedPageKeys: List<String>,
    ): TranslationBatchProgressTracker = batchTrackerRegistry.createTracker(
        chapterId = chapterId,
        store = store,
        orderedPageKeys = orderedPageKeys,
        scope = storeScope,
        permitHolderResolver = { pipeline.permitHolderPageKeySnapshot() },
    )

    fun disposeBatchTracker(chapterId: Long) {
        batchTrackerRegistry.dispose(chapterId)
    }

    internal fun terminalSnapshotCacheSize(): Int = batchTrackerRegistry.terminalSnapshotCacheSize()

    fun getBatchTracker(chapterId: Long): TranslationBatchProgressTracker? = batchTrackerRegistry.getLive(chapterId)

    /**
     * Live batch progress for [chapterId]: emits from the tracker's snapshot StateFlow when a
     * tracker is active, else falls back to store-derived progress. Switches reactively when a
     * tracker is created or disposed.
     */
    fun observeBatchProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
        return batchTrackerRegistry.live
            .flatMapLatest { trackers ->
                val tracker = trackers[chapterId]
                if (tracker != null) {
                    tracker.snapshot
                } else {
                    val terminal = batchTrackerRegistry.terminal.value[chapterId]
                    if (terminal != null) {
                        flowOf(terminal)
                    } else {
                        val state = getQueuedTranslationOrNull(chapterId)?.status
                            ?: Translation.State.NOT_TRANSLATED
                        flowOf(
                            TranslationProgressSnapshot.compute(
                                chapterId = chapterId,
                                state = state,
                                pageMap = activeStores.get(chapterId)?.state?.value,
                                permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
                            ),
                        )
                    }
                }
            }
            .distinctUntilChanged()
    }

    fun observeAllTranslationWork(): Flow<List<TranslationWorkItem>> {
        val queueChanges = queueState.flatMapLatest { queue ->
            queue.forEach { translation ->
                translation.chapter.id?.let { chapterId ->
                    workMetadata[chapterId] = TranslationWorkMetadata(
                        mangaId = translation.manga.id,
                        mangaTitle = translation.manga.title,
                        chapterName = translation.chapter.name,
                    )
                }
            }
            val statusChanges = queue.map { translation ->
                translation.statusFlow.map { queue }
            }
            if (statusChanges.isEmpty()) flowOf(queue) else statusChanges.merge().onStart { emit(queue) }
        }
        return combine(queueChanges, batchTrackerRegistry.live, batchTrackerRegistry.terminal) { queue, live, terminal ->
            Triple(queue, live, terminal)
        }.flatMapLatest { (queue, live, terminal) ->
            val trackerFlows = live.map { (chapterId, tracker) ->
                tracker.snapshot.map { chapterId to it }.onStart {
                    emit(chapterId to tracker.snapshot.value)
                }
            }
            val snapshots = if (trackerFlows.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(trackerFlows) { entries -> entries.toMap() }
            }
            snapshots.map { liveSnapshots ->
                val queuedIds = queue.mapNotNull { it.chapter.id }.toSet()
                val queuedItems = queue.mapNotNull { translation ->
                    val chapterId = translation.chapter.id ?: return@mapNotNull null
                    val snapshot = liveSnapshots[chapterId]
                    TranslationWorkItem(
                        mangaId = translation.manga.id,
                        mangaTitle = translation.manga.title,
                        chapterId = chapterId,
                        chapterName = translation.chapter.name,
                        state = when (translation.status) {
                            Translation.State.QUEUE -> TranslationWorkState.Queued
                            Translation.State.ERROR -> TranslationWorkState.Failed
                            else -> TranslationWorkState.InProgress
                        },
                        progress = snapshot?.progressFraction() ?: 0f,
                    )
                }
                val recentItems = terminal.values
                    .filter { it.chapterId !in queuedIds }
                    .map { snapshot ->
                        val metadata = workMetadata[snapshot.chapterId]
                        TranslationWorkItem(
                            mangaId = metadata?.mangaId ?: 0L,
                            mangaTitle = metadata?.mangaTitle.orEmpty(),
                            chapterId = snapshot.chapterId,
                            chapterName = metadata?.chapterName ?: snapshot.chapterId.toString(),
                            state = if (snapshot.state == Translation.State.ERROR || snapshot.aborted) {
                                TranslationWorkState.Failed
                            } else {
                                TranslationWorkState.Recent
                            },
                            progress = snapshot.progressFraction(),
                        )
                    }
                queuedItems + recentItems
            }
        }.distinctUntilChanged()
    }

    private fun TranslationProgressSnapshot.progressFraction(): Float = when {
        totalStages > 0 -> doneStages.toFloat() / totalStages
        totalPages > 0 -> donePages.toFloat() / totalPages
        else -> 0f
    }.coerceIn(0f, 1f)

    /**
     * Per-chapter batch progress (done/total) for the manga-screen chapter-list indicator, so
     * the user can watch pre-translation advance without opening the reader. Emits the active
     * store's page-count progress; empty when no active store exists (no batch in flight).
     */
    fun observeTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
        return activeStores.snapshots
            .flatMapLatest { stores ->
                val state = getQueuedTranslationOrNull(chapterId)?.status ?: Translation.State.NOT_TRANSLATED
                val store = stores[chapterId]
                if (store == null) {
                    flowOf(TranslationProgressSnapshot.empty(chapterId, state))
                } else {
                    store.state.map { pages ->
                        TranslationProgressSnapshot.compute(
                            chapterId = chapterId,
                            state = getQueuedTranslationOrNull(chapterId)?.status ?: state,
                            pageMap = pages,
                            permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
                        )
                    }
                }
            }
            .distinctUntilChanged()
    }

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? {
        return observeActiveStore(chapterId)
            ?.map { pages -> pages[pageKey].toPageView() }
            ?.distinctUntilChanged()
    }

    suspend fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        val chapterId = chapter.id ?: return
        // SYNCHRONOUS teardown (was fire-and-forget): a delete-then-retranslate let the reader
        // re-bind to the about-to-be-evicted store while the translator wrote to a fresh instance,
        // and a cancelled-but-not-joined batch worker kept writing into the old store after its
        // file/PNGs were deleted (recreating the JSON or stranding pages at RUNNING). Suspending
        // guarantees callers land on clean state.
        //
        // Ordering is load-bearing and strictly sequenced:
        //   1. cancelAutoTranslations bumps the auto generation so the window stops dispatching new pages.
        //   2. cancelPageTranslations cancels + JOINs each auto/single-page job so native work unwinds.
        //   3. removeFromTranslationQueue + cancelTranslatorJobAndJoin drop the batch entry and JOIN the
        //      batch worker (plain removeFrom only cancel()s) so it releases the translator permit before deletion.
        //   4. unregisterActiveTranslationStore marks the store defunct so a still-unwinding worker's late writes no-op.
        //   5. streamRegistry.clearChapter drops stale reader closures pointing at the about-to-be-deleted PNGs.
        //   6. Only once all work is wound down is it safe to delete the on-disk file + companion images.
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        disposeBatchTracker(chapterId)
        unregisterActiveTranslationStore(chapterId)
        streamRegistry.clearChapter(source.id, manga.id, chapterId)
        val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
        file?.delete()
        // CP9: purge the bounded summary sidecar alongside the translation JSON. The summary
        // carries latestRevisionReport (CP5 bounded report); leaving it orphaned on disk would
        // let a future store open for the same chapter read a stale report from a deleted run.
        // deleteManga already removes the whole manga directory so the sidecar goes with it;
        // this per-chapter path previously only deleted the translation JSON + companion images.
        // Same lookup ChapterTranslationSummaryStore.findSummaryFile() uses (parent + summaryFileName).
        file?.let { nonNullFile ->
            val name = nonNullFile.name ?: return@let
            nonNullFile.parentFile?.findFile(ChapterTranslationSummaryStore.summaryFileName(name))?.delete()
        }
        provider.deleteCompanionImages(manga.title, source, chapter.name, chapter.scanlator)
    }

    suspend fun deletePageTranslation(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        resetOcrData(chapter, manga, source, pageKey)
    }

    suspend fun chapterResetPreflight(
        chapter: Chapter,
        manga: Manga,
        source: Source,
    ): ChapterResetPreflight {
        val chapterId = chapter.id
        val activeStore = chapterId?.let(activeStores::get)
        if (activeStore != null) return activeStore.resetPreflight()

        val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
        if (file?.exists() != true) return ChapterResetPreflight(0, 0, 0, 0)
        return ChapterTranslationStore.open(file).resetPreflight()
    }

    suspend fun resetChapterTranslationData(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        preserveEdits: Boolean,
    ) {
        resetChapterData(chapter, manga, source) { page ->
            val blocks = page.blocks.map { block ->
                if (preserveEdits && block.userEditedAt != null) {
                    block
                } else {
                    block.copy(
                        translation = "",
                        needsRevision = false,
                        textColor = 0xFF000000,
                        strokeColor = 0xFFFFFFFF,
                        strokeWidth = 0f,
                    )
                }
            }.toMutableList()
            page.copy(
                blocks = blocks,
                translationStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            ).also {
                it.translationError = null
                it.renderError = null
            }
        }
    }

    suspend fun resetChapterInpaintData(chapter: Chapter, manga: Manga, source: Source) {
        resetChapterData(chapter, manga, source) { page ->
            page.copy(
                cleanedImageName = null,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            ).also {
                it.inpaintError = null
                it.renderError = null
            }
        }
        provider.deleteCompanionImages(manga.title, source, chapter.name, chapter.scanlator)
    }

    suspend fun resetChapterOcrData(chapter: Chapter, manga: Manga, source: Source) {
        deleteTranslation(chapter, manga, source)
    }

    private suspend fun resetChapterData(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        transform: (PageTranslation) -> PageTranslation,
    ) {
        val chapterId = chapter.id ?: return
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        streamRegistry.clearChapter(source.id, manga.id, chapterId)

        val activeStore = activeStores.get(chapterId)
        if (activeStore != null) {
            activeStore.state.value.keys.forEach { pageKey ->
                activeStore.updatePage(pageKey) { page -> page?.let(transform) ?: PageTranslation.EMPTY }
            }
            activeStore.flush()
        } else {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            if (file?.exists() == true) {
                val store = ChapterTranslationStore.open(file)
                store.state.value.keys.forEach { pageKey ->
                    store.updatePage(pageKey) { page -> page?.let(transform) ?: PageTranslation.EMPTY }
                }
                store.flush()
            }
        }
        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetTranslationData(chapter: Chapter, manga: Manga, source: Source, pageKey: String, preserveEdits: Boolean) {
        val chapterId = chapter.id ?: return
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val store = activeStores.get(chapterId)
        if (store != null) {
            store.updatePage(pageKey) { page ->
                page ?: return@updatePage eu.kanade.translation.model.PageTranslation.EMPTY
                val newBlocks = page.blocks.map { block ->
                    if (preserveEdits && block.userEditedAt != null) {
                        block
                    } else {
                        block.copy(
                            translation = "",
                            needsRevision = false,
                            textColor = 0xFF000000,
                            strokeColor = 0xFFFFFFFF,
                            strokeWidth = 0f,
                        )
                    }
                }.toMutableList()

                page.copy(
                    blocks = newBlocks,
                    translationStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                ).also {
                    it.translationError = null
                    it.renderError = null
                }
            }
            store.flush()
        } else {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            if (file?.exists() == true) {
                val s = ChapterTranslationStore.open(file)
                s.updatePage(pageKey) { page ->
                    page ?: return@updatePage eu.kanade.translation.model.PageTranslation.EMPTY
                    val newBlocks = page.blocks.map { block ->
                        if (preserveEdits && block.userEditedAt != null) {
                            block
                        } else {
                            block.copy(
                                translation = "",
                                needsRevision = false,
                                textColor = 0xFF000000,
                                strokeColor = 0xFFFFFFFF,
                                strokeWidth = 0f,
                            )
                        }
                    }.toMutableList()

                    page.copy(
                        blocks = newBlocks,
                        translationStatus = eu.kanade.translation.model.StageStatus.PENDING,
                        renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    ).also {
                        it.translationError = null
                        it.renderError = null
                    }
                }
                s.flush()
            }
        }

        // Reconcile batch progress so summary drops cleared data
        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetInpaintData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val store = activeStores.get(chapterId)
        val persistedCleanedName = store?.state?.value?.get(pageKey)?.cleanedImageName
        if (store != null) {
            store.updatePage(pageKey) { page ->
                page ?: return@updatePage eu.kanade.translation.model.PageTranslation.EMPTY
                page.copy(
                    cleanedImageName = null,
                    inpaintStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                ).also {
                    it.inpaintError = null
                    it.renderError = null
                }
            }
            store.flush()
        } else {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            if (file?.exists() == true) {
                val s = ChapterTranslationStore.open(file)
                s.updatePage(pageKey) { page ->
                    page ?: return@updatePage eu.kanade.translation.model.PageTranslation.EMPTY
                    page.copy(
                        cleanedImageName = null,
                        inpaintStatus = eu.kanade.translation.model.StageStatus.PENDING,
                        renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    ).also {
                        it.inpaintError = null
                        it.renderError = null
                    }
                }
                s.flush()
            }
        }

        val companionDir = provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
        if (companionDir != null) {
            persistedCleanedName?.let { companionDir.findFile(it)?.delete() }
            val safePageKey = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            companionDir.findFile("$safePageKey.cleaned.png")?.delete()
            companionDir.findFile("$safePageKey.cleaned.jpg")?.delete()
            companionDir.findFile("$safePageKey.rendered.png")?.delete()
            companionDir.listFiles()?.asSequence().orEmpty()
                .filter { it.name?.startsWith("$safePageKey.cleaned.") == true }
                .forEach { it.delete() }
        }

        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetOcrData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return

        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val activeStore = activeStores.get(chapterId)
        val persistedCleanedName = activeStore?.state?.value?.get(pageKey)?.cleanedImageName
        if (activeStore != null) {
            activeStore.deletePage(pageKey)
        } else {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            if (file?.exists() == true) {
                val store = ChapterTranslationStore.open(file)
                store.deletePage(pageKey)
                store.flush()
            }
        }

        val companionDir = provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
        if (companionDir != null) {
            persistedCleanedName?.let { companionDir.findFile(it)?.delete() }
            val safePageKey = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            companionDir.findFile("$safePageKey.cleaned.png")?.delete()
            companionDir.findFile("$safePageKey.cleaned.jpg")?.delete()
            companionDir.findFile("$safePageKey.rendered.png")?.delete()
            // Versioned publication names are unique per replacement attempt;
            // remove any orphaned versions left after a deleted store entry.
            companionDir.listFiles()?.asSequence().orEmpty()
                .filter { it.name?.startsWith("$safePageKey.cleaned.") == true }
                .forEach { it.delete() }
        }
    }

    private suspend fun reconcileBatchProgress(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ) {
        // Refresh the active-store summary after a stage reset so the chapter
        // list can drop stale progress data. Only runs when the store is open
        // (i.e. the reader is active for this chapter); persisted-only chapters
        // are unaffected because their summary is rebuilt on the next open.
        val store = activeStores.get(chapterId) ?: return
        store.flush()
    }

    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                translator.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
            }
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else if (queueState.value.isNotEmpty()) {
                translator.start()
            }
        }
    }

    fun getCleanedImageStream(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, cleanedImageName: String): (() -> java.io.InputStream)? {
        return {
            val file = provider.findPageCleanedImage(mangaTitle, source, chapterName, chapterScanlator, cleanedImageName)
            if (file?.exists() == true) {
                file.openInputStream()
            } else {
                throw java.io.FileNotFoundException("Cleaned image not found: $cleanedImageName")
            }
        }
    }

    fun getCompanionImageDirForChapter(chapterName: String, scanlator: String?, title: String, source: Source): UniFile? {
        return provider.findCompanionImageDir(title, source, chapterName, scanlator)
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) =
        scheduler.translatePage(manga, chapter, source, pageKey)

    /**
     * Cancels the in-flight single-page translation job for one [pageKey] within [chapterId] —
     * the per-page granularity [cancelPageTranslations] (chapter-scoped) is too coarse for.
     * Returns true if a job was actually cancelled, false if none was running for that page.
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        scheduler.cancelPageTranslation(chapterId, pageKey)

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and evicts the shared
     * [ChapterTranslationStore] so it does not leak across chapter navigations. Call this on
     * reader navigate-away so the previous chapter's work can no longer hold the executor's
     * single permit. Job cancellation is delegated to the scheduler; store eviction is manager-owned.
     */
    suspend fun cancelPageTranslations(chapterId: Long) {
        cancelRevision(chapterId)
        scheduler.cancelPageTranslations(chapterId)
        if (isBatchTranslationActive(chapterId)) {
            return
        }
        disposeBatchTracker(chapterId)
        activeStores.get(chapterId)?.clearTransientQueuePages("Translation cancelled")
        // Evict the store on chapter exit; the reader re-opens it via observeLiveTranslationStore on the next loadChapter.
        unregisterActiveTranslationStore(chapterId)
    }

    /**
     * Cancels every in-flight single-page translation job and drops all shared stores. Call this
     * when the reader is destroyed or the master toggle is switched off, so no orphaned work
     * keeps running and no collector outlives the session. Job cancellation is delegated to the
     * scheduler; store eviction + chapter queue clearing are manager-owned.
     *
     * CP8: [cancelRevisions] gates whether standalone revision jobs are also cancelled.
     * User-initiated Stop-all / translation-disable pass `cancelRevisions = true` (default):
     * the user explicitly wants all work gone. The reader background/close path
     * ([stopReaderTranslations]) passes `cancelRevisions = false` so a standalone revision
     * survives UI lifecycle events — it is process-owned, not reader-owned.
     */
    fun cancelAllPageTranslations(cancelBatchQueue: Boolean = false, cancelRevisions: Boolean = true) {
        scheduler.cancelAllPageTranslations()
        if (cancelRevisions) {
            val revisionChapterIds = revisionOwnership.ownedChapterIds()
            revisionChapterIds.forEach { cancelRevision(it) }
        }
        val chapterIdsToEvict = activeStores.chapterIds()
            .filter { cancelBatchQueue || !isBatchTranslationActive(it) }
        val stores = chapterIdsToEvict.mapNotNull { activeStores.get(it) }
        if (stores.isNotEmpty()) {
            // TachiyomiAT bug 4 fix: the durable CANCELLED write MUST land before
            // unregisterActiveTranslationStore marks these stores defunct below.
            // The previous code launched clearTransientQueuePages on storeScope
            // and then synchronously called markDefunct in the same pass; the
            // async clear was rejected as defunct and the durable state was
            // silently dropped, leaving pages RUNNING in the next session's
            // rehydrated snapshot. Run the clear to completion here (bounded by
            // the small number of active chapter stores) before eviction.
            kotlinx.coroutines.runBlocking {
                stores.forEach { store ->
                    store.clearTransientQueuePages("All translation cancelled")
                }
            }
        }
        chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
        if (cancelBatchQueue) {
            translator.clearQueue()
        }
    }

    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation ->
                    translation.statusFlow.drop(1).map { translation }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { translation -> translation.status == Translation.State.TRANSLATING }.asFlow(),
            )
        }
}
