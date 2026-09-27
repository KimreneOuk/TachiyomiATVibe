package eu.kanade.translation.workflow

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.translator.TranslationEngineBuilder
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.persistence.queue.TranslationQueueStore
import eu.kanade.translation.pipeline.MemoryPressureClass
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.batch.progress.ReconciliationResult
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal fun <T> mergeRestoredQueueEntries(
    durableIds: List<Long>,
    restoredById: Map<Long, T>,
    liveById: Map<Long, T>,
): List<T> = durableIds.distinct().mapNotNull { chapterId ->
    // A live entry may have been requeued after the restore lookup. Prefer it
    // over the stale status captured before that mutation.
    liveById[chapterId] ?: restoredById[chapterId]
}

class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationFileProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
    private val queueStore: TranslationQueueStore = TranslationQueueStore(context),
    private val pipeline: TranslationPipeline = TranslationPipeline(
        context,
        provider,
        downloadProvider,
        translationPreferences,
        streamRegistry,
    ),
) {

    companion object {
        // Bound the join during chapter deletion.
        // The batch worker can be mid-uncancellable native ONNX (OrtSession.run)
        // when cancel() is requested; coroutine cancellation only lands at the
        // next suspension point. Bounding the join prevents a delete from hanging
        // on a job that won't unwind promptly (matches TranslationScheduler.JOIN_TIMEOUT_MS).
        // On timeout the file delete proceeds and the defunct-store guard
        // (ChapterTranslationStore.markDefunct) neutralizes any late write.
        const val BATCH_JOIN_TIMEOUT_MS = 2_000L

        // Retry the chapter claim while the previous job finishes unwinding.
        const val IN_FLIGHT_CLAIM_RETRY_MS = 100L
    }

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /** Serializes queue membership and restore publication with persistence. */
    private val queueMutationLock = Any()

    /**
     * Returns the queue ids currently durable on disk. Startup restoration is
     * intentionally asynchronous, so lifecycle deletion must consult this
     * snapshot before [restoreQueue] has repopulated [queueState].
     */
    fun persistedQueueChapterIds(): Set<Long> = synchronized(queueMutationLock) {
        queueStore.load().toSet()
    }

    /**
     * Reader-position queue priority. Moves the specified queued chapter to the
     * head of pending candidates (immediately following any active chapter, or
     * index 0) and persists the updated order.
     * Never interrupts or preempts an in-flight TRANSLATING chapter.
     */
    fun prioritizeChapter(chapterId: Long) {
        synchronized(queueMutationLock) {
            val current = _queueState.value
            val target = current.find { it.chapter.id == chapterId } ?: return
            if (target.status == Translation.State.TRANSLATING) return
            val activeIndex = current.indexOfFirst { it.status == Translation.State.TRANSLATING }
            val insertIndex = if (activeIndex >= 0) activeIndex + 1 else 0
            val targetIndex = current.indexOf(target)
            if (targetIndex <= insertIndex) return

            val reordered = current.toMutableList().apply {
                removeAt(targetIndex)
                add(insertIndex, target)
            }
            _queueState.value = reordered
            persistQueue()
            logcat(LogPriority.INFO) {
                "TachiyomiAT S7: steered chapter $chapterId to queue index $insertIndex"
            }
        }
    }

    /**
     * Persists the queue (ordered chapter IDs) to disk after every
     * mutation so a crash mid-batch no longer loses it. One SharedPreferences
     * editor batch; idempotent.
     */
    private fun persistQueue() {
        synchronized(queueMutationLock) {
            queueStore.save(_queueState.value.mapNotNull { it.chapter.id })
        }
    }

    /**
     * Rehydrates the queue from disk on launch via
     * [Translation.fromChapterId]. Deleted chapters self-heal (fromChapterId
     * returns null, so stale ids drop). Rehydrated entries get status QUEUE:
     * rehydrate but require Start — never auto-start background OCR/LLM work on
     * launch. Must run in a coroutine (suspend lookups).
     */
    suspend fun restoreQueue() {
        val ids = synchronized(queueMutationLock) { queueStore.load() }
        if (ids.isEmpty()) return
        val restored = mutableListOf<Translation>()
        for (id in ids) {
            val translation = Translation.fromChapterId(id) ?: continue
            val durable = durableQueueState(translation)
            translation.status = when (durable?.status) {
                Translation.State.ERROR -> Translation.State.ERROR
                else -> Translation.State.PAUSED
            }
            restored += translation
        }
        isPaused = true
        synchronized(queueMutationLock) {
            // Lookups above suspend. Re-read the durable membership and merge
            // any queue additions that landed during that window instead of
            // replacing them with the startup snapshot.
            val durableIds = queueStore.load()
            val merged = mergeRestoredQueueEntries(
                durableIds = durableIds,
                restoredById = restored.mapNotNull { translation ->
                    translation.chapter.id?.let { it to translation }
                }.toMap(),
                liveById = _queueState.value.mapNotNull { translation ->
                    translation.chapter.id?.let { it to translation }
                }.toMap(),
            )
            if (merged.isNotEmpty()) {
                _queueState.value = merged
                // Re-save so self-healed stale ids are removed while concurrent
                // additions retained by the merge remain durable.
                queueStore.save(merged.mapNotNull { it.chapter.id })
            } else {
                _queueState.value = emptyList()
                queueStore.clear()
            }
            if (merged.isNotEmpty()) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT restored ${merged.size}/${ids.size} queued translations from disk"
                }
            } else {
                // All persisted ids were stale (chapters deleted) and no
                // concurrent queue entry survived the merge.
                logcat(LogPriority.INFO) {
                    "TachiyomiAT queue restore: all ${ids.size} persisted ids were stale; cleared store"
                }
            }
        }
    }

    private data class DurableQueueState(
        val status: Translation.State?,
        val nextEligibleRetryAtEpochMs: Long?,
    )

    /** Reads only durable status; it never starts work or creates a directory. */
    private suspend fun durableQueueState(translation: Translation): DurableQueueState? =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = pipeline.activeStoreResolver?.invoke(translation)
                val store = active ?: provider.findTranslationFile(
                    translation.chapter.name,
                    translation.chapter.scanlator,
                    translation.manga.title,
                    translation.source,
                )?.takeIf { it.exists() }?.let(ChapterTranslationStore::open)
                    ?: provider.findMangaDir(translation.manga.title, translation.source)?.let { parent ->
                        ChapterTranslationStore.openArtifact(
                            parent,
                            provider.getTranslationFileName(
                                translation.chapter.name,
                                translation.chapter.scanlator,
                            ),
                        )
                    }
                store ?: return@withContext null
                DurableQueueState(
                    status = store.artifactStatus(),
                    nextEligibleRetryAtEpochMs = store.durableFailuresSnapshot().values
                        .firstOrNull { it.stage == ArtifactStage.TRANSLATION }
                        ?.nextEligibleRetryAtEpochMs,
                )
            }.getOrNull()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var translationJob: Job? = null

    /**
     * Parent job captured by the quiescent batch-to-reader transition. A
     * bounded join can time out while native work is still unwinding; keeping
     * this reference lets the coordinator retry the same job instead of
     * accidentally treating a timeout as quiescence.
     */
    private val sessionSwitchJobs = CopyOnWriteArrayList<Job>()

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    // Serialize job admission so concurrent requests cannot both observe an
    // idle translator and launch duplicate work for the queue head.
    private val translatorLaunchLock = Any()

    // Keep a claim while a chapter job is running or unwinding native work
    // after cancellation. New admissions wait for that claim to release rather
    // than starting a second run that would advance the store generation and
    // fence the first run.
    private val inFlightChapterIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val inFlightClaimReleasedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun start(): Boolean {
        if (queueState.value.isEmpty()) {
            return false
        }

        synchronized(translatorLaunchLock) {
            if (isRunning) {
                return false
            }

            // A generic queue start does not resurrect failed entries after a
            // restart. An ERROR chapter re-enters work only through an explicit
            // per-chapter request or requeueExisting.
            val pending = queueState.value.filter {
                it.status != Translation.State.TRANSLATED &&
                    it.status != Translation.State.PAUSED &&
                    it.status != Translation.State.ERROR
            }
            if (pending.isEmpty()) {
                return false
            }
            pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
            isPaused = false
            launchTranslatorJob()
            return pending.isNotEmpty()
        }
    }

    fun stop(reason: String? = null, closeEngines: Boolean = false) {
        cancelTranslatorJob()
        // Interrupted chapters stay resumable: their per-page artifacts are
        // durable, so a later Start continues from the planner's reuse scan.
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }

        if (reason == "reader backgrounded") {
            try {
                pipeline.forceReleaseNativeBuffers()
            } catch (_: Exception) {}
        }

        // Background and memory-pressure stops keep engines open. Callers that
        // request engine closure tear them down so the next run can rebuild
        // after a provider, model, language, or OCR configuration change.
        if (reason != null && !closeEngines) return
        isPaused = false
        pipeline.closeEngines()
    }

    /** Releases transient native and bitmap memory in response to OS pressure. */
    fun onMemoryPressure(level: Int, pressureClass: MemoryPressureClass) {
        tachiyomi.domain.translation.pools.BitmapPool.releaseAll()
        try {
            pipeline.forceReleaseNativeBuffers()
        } catch (_: Exception) {}
        // Keep the translator job alive under memory pressure. Cancelling it
        // can drop an active provider request and publish an aborted state while
        // the model is still generating; process death resumes through restoreQueue().
    }

    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    /**
     * Non-blocking pause used before a session switch. The cancelled parent
     * job is retained by [cancelTranslatorJobAndJoinForSession] when native
     * work needs more than one bounded join attempt to unwind.
     */
    fun pauseForSessionSwitch() {
        val job = translationJob
        if (job != null) {
            job.cancel()
            translationJob = null
            retainSessionSwitchJob(job)
        }
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    /**
     * Idempotently re-admits a paused chapter. A cooldown is honored unless
     * the caller explicitly requests a force retry; rearming while another
     * chapter is active only nudges the queue flow and never starts a second
     * source worker.
     */
    suspend fun requeueExisting(chapterId: Long, force: Boolean = false): Boolean {
        val translation = queueState.value.firstOrNull { it.chapter.id == chapterId } ?: return false
        val durable = durableQueueState(translation)
        val next = durable?.nextEligibleRetryAtEpochMs
        if (!force && next != null && next > System.currentTimeMillis()) return false
        val rearmed = synchronized(queueMutationLock) {
            val current = _queueState.value.firstOrNull { it.chapter.id == chapterId } ?: return@synchronized false
            if (current.status != Translation.State.PAUSED && durable?.status != Translation.State.PAUSED) {
                return@synchronized current.status == Translation.State.QUEUE
            }
            current.status = Translation.State.QUEUE
            isPaused = false
            _queueState.update { it.toList() }
            persistQueue()
            true
        }
        if (!rearmed) return false
        if (!isRunning) launchTranslatorJob()
        return true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

    private fun launchTranslatorJob() {
        synchronized(translatorLaunchLock) {
            if (isRunning) return

            translationJob = scope.launch {
                val activeTranslationFlow = queueState.transformLatest { queue ->
                    if (queue.isEmpty()) return@transformLatest
                    // Translation.status is mutable state inside each queue item, so observing only
                    // queueState misses a transition to PAUSED/TRANSLATED and leaves the scheduler
                    // waiting forever for the old ERROR-only wake-up. Recompute active membership
                    // whenever any queued item's status changes; a paused item therefore releases
                    // its source lane while another source/chapter may continue.
                    combine(queue.map(Translation::statusFlow)) {
                        val candidates = queue.asSequence().filter {
                            it.status == Translation.State.QUEUE ||
                                it.status == Translation.State.TRANSLATING
                        }.toList()
                        // A rearmed paused entry must not preempt a chapter that is
                        // already translating. The queue still serializes by source;
                        // this preference only keeps a live worker stable while the
                        // rearm is idempotently recorded.
                        val active = candidates.filter { it.status == Translation.State.TRANSLATING }
                        val activeSource = active.firstOrNull()?.source
                        val selected = if (active.isNotEmpty()) {
                            // Allow the next queued chapter from the active
                            // source to run native OCR/inpaint while provider
                            // waits are in flight for the active chapter.
                            val nextQueue = candidates.firstOrNull {
                                it.status == Translation.State.QUEUE &&
                                    it !in active &&
                                    (activeSource == null || it.source == activeSource)
                            }
                            if (nextQueue != null) {
                                active.take(1) + listOf(nextQueue)
                            } else {
                                active.take(1)
                            }
                        } else {
                            candidates.take(1)
                        }
                        selected.asSequence()
                            .groupBy { it.source }
                            .toList()
                            .take(1)
                            .flatMap { (_, translations) -> translations.take(2) }
                    }.distinctUntilChanged().collect { emit(it) }
                }.distinctUntilChanged()
                supervisorScope {
                    val translationJobs = mutableMapOf<Translation, Job>()

                    activeTranslationFlow.collectLatest { activeTranslations ->
                        val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                        translationJobsToStop.forEach { (download, job) ->
                            job.cancel()
                            translationJobs.remove(download)
                        }

                        val translationsToStart = activeTranslations.filter { it !in translationJobs }
                        translationsToStart.forEach { translation ->
                            translationJobs[translation] = launchTranslationJob(translation)
                        }
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        val chapterId = translation.chapter.id
        var claimed = false
        try {
            if (chapterId != null) {
                // Admit one live schedule per chapter. Set.add atomically claims
                // ownership. A prior run can still be unwinding native work
                // after cancellation, so wait for it rather than starting a
                // second run that advances the store generation. The claim is
                // released in finally after this coroutine fully unwinds.
                while (!inFlightChapterIds.add(chapterId)) {
                    withTimeoutOrNull(IN_FLIGHT_CLAIM_RETRY_MS) {
                        inFlightClaimReleasedSignal.first()
                    }
                }
                claimed = true
            }
            val reconciliation = translateChapterInternal(translation)
            if (translation.status == Translation.State.TRANSLATED ||
                (
                    translation.status == Translation.State.READY_WITH_WARNINGS &&
                        reconciliation?.nonDurableFailure != true
                    )
            ) {
                removeFromQueue(translation)
            } else if (translation.status == Translation.State.PAUSED) {
                isPaused = true
            }
            if (translation.status != Translation.State.PAUSED &&
                reconciliation?.nonDurableFailure != true &&
                areAllTranslationsFinished()
            ) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // One chapter's unexpected failure must not kill the rest of the
            // queue: mark the chapter failed so the queue loop advances.
            logcat(LogPriority.ERROR, e)
            translation.status = Translation.State.ERROR
            if (areAllTranslationsFinished()) {
                stop()
            }
        } finally {
            // Release the claim only after the batch coroutine fully unwinds,
            // so another admission cannot overlap its remaining work.
            if (claimed) {
                inFlightChapterIds.remove(chapterId)
                inFlightClaimReleasedSignal.tryEmit(Unit)
            }
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    /**
     * Cancels the batch translator job AND waits for it to unwind, bounded by
     * [BATCH_JOIN_TIMEOUT_MS]. Used only by the delete path: a chapter's on-disk
     * translation file + images must not be deleted while the batch coroutine
     * still holds the old [ChapterTranslationStore] reference and may write
     * RUNNING/rendered results into it. Joining guarantees the job reaches a
     * suspension point (finally blocks run, permit released) before
     * [TranslationManager.deleteTranslation] deletes files. Plain
     * [cancelTranslatorJob] stays non-blocking so pause/stop/clearQueue keep
     * their behaviour. On timeout this returns anyway; the defunct-store guard
     * rejects subsequent writes and NativeRunQuarantine invalidates the timed-out
     * native generation.
     */
    suspend fun cancelTranslatorJobAndJoin(timeoutMs: Long = BATCH_JOIN_TIMEOUT_MS) {
        cancelTranslatorJobAndJoinResult(timeoutMs)
    }

    /** Joined counterpart that reports whether the old batch fully unwound. */
    suspend fun cancelTranslatorJobAndJoinResult(timeoutMs: Long = BATCH_JOIN_TIMEOUT_MS): Boolean {
        val job = translationJob ?: return true
        translationJob = null
        val joined = withTimeoutOrNull(timeoutMs) { job.cancelAndJoin() } != null
        if (!joined) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT batch translator job did not unwind within " +
                    "$timeoutMs ms; proceeding (defunct guard + " +
                    "native-run quarantine will neutralize any late write)"
            }
        }
        return joined
    }

    /**
     * Join the job captured by [pauseForSessionSwitch], retrying the same
     * parent after a timeout. This is intentionally separate from the delete
     * path above: existing delete/reset callers retain their historical
     * timeout behavior, while a session switch must never lose the old job
     * between attempts.
     */
    suspend fun cancelTranslatorJobAndJoinForSession(timeoutMs: Long): Boolean {
        val job = translationJob
        if (job != null) {
            translationJob = null
            job.cancel()
            retainSessionSwitchJob(job)
        }

        for (pendingJob in sessionSwitchJobs.toList()) {
            if (withTimeoutOrNull(timeoutMs) { pendingJob.join() } == null) {
                return false
            }
            sessionSwitchJobs.remove(pendingJob)
        }
        return true
    }

    private fun retainSessionSwitchJob(job: Job) {
        sessionSwitchJobs += job
        job.invokeOnCompletion { sessionSwitchJobs.remove(job) }
    }

    /**
     * Mirrors [queueChapter]'s config preflight so callers can classify an
     * admission rejection (invalid config or unsupported source) without
     * duplicating preference parsing. This check does not mutate the queue.
     */
    fun isQueueConfigValid(): Boolean = runCatching {
        if (
            TranslationEngineBuilder.isMlKitActive(translationPreferences) &&
            !TextTranslatorLanguage.mlkitSupportedLanguages()
                .contains(TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage()))
        ) {
            return false
        }
        TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        true
    }.getOrDefault(false)

    fun queueChapter(
        manga: Manga,
        chapter: Chapter,
        // Optional source-count evidence from the trigger's admission probe.
        // Defaults keep callers without that probe on the same path.
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        // Invalid persisted configuration must not crash this UI action or
        // queue a batch that would fail every page with the same error. Catch
        // conversion failures and surface them as a toast.
        val fromLang: TextRecognizerLanguage
        val toLang: TextTranslatorLanguage
        try {
            fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
            toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        } catch (e: IllegalArgumentException) {
            logcat(LogPriority.ERROR, e) { "TachiyomiAT queueChapter aborted: invalid translation config" }
            scope.launchUI {
                context.toast(e.message ?: "Invalid translation configuration")
            }
            return
        }
        if (TranslationEngineBuilder.isMlKitActive(translationPreferences) &&
            !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)
        ) {
            scope.launchUI {
                context.toast(ATMR.strings.error_mlkit_language_unsupported)
            }
            return
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang)
        if (sourceCountKnown) {
            translation.probedSourcePageCount = probedSourcePageCount
            translation.sourceCountKnown = true
        }
        addToQueue(translation)
    }

    // Internal so focused tests can drive the exact
    // exceptional exits (missing files, unexpected exceptions) without the
    // async queue worker.
    internal suspend fun translateChapterInternal(translation: Translation): ReconciliationResult? {
        var store: ChapterTranslationStore? = null
        var tracker: eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker? = null
        var batchReconciliation: ReconciliationResult? = null
        var batchOrderedPageKeys: List<String> = emptyList()
        try {
            // Prefer the shared active store from TranslationManager so the reader
            // observes the same instance the pipeline writes to.
            store = pipeline.activeStoreResolver?.invoke(translation)
            if (store == null) {
                val existingFile = provider.findTranslationFile(
                    translation.chapter.name,
                    translation.chapter.scanlator,
                    translation.manga.title,
                    translation.source,
                )
                val translationFile = if (existingFile != null && existingFile.exists()) {
                    existingFile
                } else {
                    val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
                    val saveFile = provider.getTranslationFileName(
                        translation.chapter.name,
                        translation.chapter.scanlator,
                    )
                    if (translationMangaDir == null) {
                        logcat(LogPriority.ERROR) {
                            "TachiyomiAT cannot resolve artifact directory for ${translation.chapter.name}"
                        }
                        translation.status = Translation.State.ERROR
                        return null
                    }
                    store = ChapterTranslationStore.openArtifact(translationMangaDir, saveFile)
                    null
                }
                if (translationFile == null && store == null) {
                    logcat(LogPriority.ERROR) {
                        "TachiyomiAT cannot open translation artifact for ${translation.chapter.name}"
                    }
                    translation.status = Translation.State.ERROR
                    return null
                }
                if (store == null) store = ChapterTranslationStore.open(translationFile!!)
            }

            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )
            // Chapter files may be gone (deleted mid-translation) or never
            // existed for a streamed chapter; fail the chapter cleanly rather
            // than NPE'ing.
            if (chapterPath == null) {
                logcat(LogPriority.ERROR) {
                    "TachiyomiAT chapter files not found for ${translation.chapter.name}"
                }
                // Emit the typed terminal snapshot so the drawer
                // shows the real reason instead of a bare/living 0/0.
                failBeforePipeline(
                    translation,
                    store,
                    batchOrderedPageKeys,
                    reason = "Chapter files not found — the download may have been deleted",
                )
                return null
            }
            translation.status = Translation.State.TRANSLATING

            // Keep one ArchiveReader open for the batch. Its entry streams use
            // seek-based reads, avoiding repeated decompression; directory chapters
            // use cheap direct file opens.
            val streams: List<Pair<String, () -> InputStream>>
            val sharedArchive: mihon.core.archive.ArchiveReader?
            if (chapterPath.isFile) {
                sharedArchive = chapterPath.archiveReader(context)
                // Each closure reads from the shared reader; it's mmap'd so
                // reads are seek-based, not re-decompressing.
                streams = sharedArchive.useEntries { entries ->
                    entries.filter { it.isFile && ImageUtil.isImage(it.name) }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                        .map { entry ->
                            Pair(entry.name) {
                                sharedArchive.getInputStream(entry.name)
                                    ?: throw java.io.IOException(
                                        "Archive entry '${entry.name}' could not be opened (mmap)",
                                    )
                            }
                        }.toList()
                }
            } else {
                sharedArchive = null
                streams = getChapterPages(chapterPath)
            }
            val naturalPageIndexes = streams.mapIndexed { index, (pageKey, _) -> pageKey to index }.toMap()

            try {
                // Chapter batches are deterministic natural-order traversals.
                // Reader viewport/last-read position is intentionally not a
                // batch scheduling input; resume is decided per stage by the
                // pipeline planner while pages remain 1..N.
                val orderedStreams = eu.kanade.translation.pipeline.planning.ResumeOrdering.naturalOrder(streams)
                batchOrderedPageKeys = orderedStreams.map { it.first }
                // A rejected pre-registration is an explicit
                // pipeline error. Fail the chapter with a typed terminal tracker
                // snapshot instead of running a live tracker whose totals would
                // silently stay zero.
                // The queued source-count cross-check is carried through
                // pre-registration so the manifest's trusted total is the
                // SOURCE total (or honestly unknown), never the found count.
                val preRegistration = store.preRegisterPages(
                    batchOrderedPageKeys,
                    translation.probedSourcePageCount,
                    translation.sourceCountKnown,
                )
                if (preRegistration is ChapterTranslationStore.PagePreRegistration.Rejected) {
                    failBeforePipeline(
                        translation,
                        store,
                        batchOrderedPageKeys,
                        reason = "Page pre-registration was rejected: ${preRegistration.reason}",
                    )
                    return null
                }
                val chapterId = translation.chapter.id
                tracker = if (chapterId != null) {
                    pipeline.batchTrackerFactory?.invoke(chapterId, store, orderedStreams.map { it.first })
                } else {
                    null
                }
                // Publish durable page progress before resumed work can emit
                // new phase events. Reuse-only pages may otherwise leave the
                // live tracker at its empty 0/0 snapshot until batch finish.
                tracker?.rebuildFromStore()
                if (translationJob?.isActive != true) {
                    logcat(LogPriority.INFO) { "TachiyomiAT batch cancelled before start: ${translation.chapter.name}" }
                } else {
                    batchReconciliation = pipeline.translateBatch(
                        translation.manga,
                        translation.chapter,
                        translation.source,
                        store,
                        orderedStreams,
                        tracker,
                        naturalPageIndexes,
                    )
                }
            } finally {
                try {
                    sharedArchive?.close()
                } catch (_: Exception) {}
            }

            // A durable pause/terminal outcome already owns its unresolved
            // anchor. Running the ordinary reconciler here would synthesize
            // tail failures and destroy the retry boundary.
            when {
                batchReconciliation?.nonDurableFailure == true -> {
                    // A guarded publication rejection was reconciled in memory
                    // only. Preserve that explicit warning instead of deriving a
                    // terminal ERROR from a snapshot that was not durably owned.
                    translation.status = batchReconciliation!!.chapterStatus
                }
                store.artifactStatus() == Translation.State.PAUSED -> {
                    translation.status = Translation.State.PAUSED
                }
                store.artifactStatus() == Translation.State.ERROR -> {
                    translation.status = Translation.State.ERROR
                }
                else -> {
                    val pageStates = store.state.value
                    //  : both surviving batch lanes end
                    // runs translation-terminal WITHOUT an in-pass render, so
                    // the post-batch queue-status projection must use the
                    // flagged COMPLETED projection — the legacy done-predicate
                    // would project every healthy page as stranded and mark
                    // the queue entry ERROR (same  class as the pass-1
                    // post-pass projection).
                    val reconciliation =
                        eu.kanade.translation.pipeline.batch.progress.BatchProgressReconciler
                            .reconcileFlaggedCompleted(
                                pageMap = pageStates,
                                orderedKeys = batchOrderedPageKeys,
                                activeGeneration = store.currentGeneration,
                            )
                    translation.status = reconciliation.chapterStatus
                }
            }
            return batchReconciliation
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // If it's no longer in the queue, it was explicitly removed (cancelled).
                // If it's still in the queue, it was merely paused or memory-requeued; do not abort the tracker so it can be resumed.
                if (!queueState.value.contains(translation)) {
                    tracker?.abort(batchOrderedPageKeys.toSet(), "Batch cancelled")
                }
                store?.flush()
                throw error
            }
            BitmapPool.releaseAll()
            translation.status = Translation.State.ERROR
            // An unexpected exception must leave a terminal
            // snapshot with the reason, never a live nonterminal tracker. Safe
            // when the batch already finished: a late event is ignored.
            tracker?.abort(
                batchOrderedPageKeys.toSet(),
                reason = "Translation failed unexpectedly: ${error.message ?: error::class.java.simpleName}",
            )
            logcat(LogPriority.ERROR, error)
            return null
        }
    }

    /**
     * Typed terminal exit for a batch that failed before the
     * pipeline could run (pre-registration rejected, chapter files missing).
     * Marks the queue entry ERROR and emits an aborted terminal tracker
     * snapshot carrying [reason] through the registry, so the UI shows the
     * real cause and no live nonterminal `0/0` tracker survives the exit.
     * No-op when the chapter has no id or no tracker factory (nothing to
     * project through).
     */
    private fun failBeforePipeline(
        translation: Translation,
        store: ChapterTranslationStore,
        orderedPageKeys: List<String>,
        reason: String,
    ) {
        translation.status = Translation.State.ERROR
        val chapterId = translation.chapter.id ?: return
        val tracker = pipeline.batchTrackerFactory?.invoke(chapterId, store, orderedPageKeys) ?: return
        // Publish the known ordered-key totals before the terminal event so
        // the aborted snapshot carries the real work set, not 0/0.
        tracker.rebuildFromStore()
        tracker.abort(orderedPageKeys.toSet(), reason)
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> =
        eu.kanade.translation.util.getChapterPages(context, chapterPath)

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none {
            it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING
        }
    }

    private fun addToQueue(translation: Translation) {
        synchronized(queueMutationLock) {
            // queueChapter performs a best-effort preflight before resolving
            // translation configuration, but restore/queue actions can race;
            // the mutation itself owns the authoritative duplicate check.
            if (_queueState.value.any { it.chapter.id == translation.chapter.id }) return
            translation.status = Translation.State.QUEUE
            _queueState.update {
                it + translation
            }
            persistQueue()
        }
    }

    private fun removeFromQueue(translation: Translation) {
        synchronized(queueMutationLock) {
            _queueState.update {
                if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
                it - translation
            }
            persistQueue()
        }
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        synchronized(queueMutationLock) {
            _queueState.update { queue ->
                val translations = queue.filter { predicate(it) }
                translations.forEach { translation ->
                    if (translation.status == Translation.State.TRANSLATING ||
                        translation.status == Translation.State.QUEUE
                    ) {
                        translation.status = Translation.State.NOT_TRANSLATED
                    }
                }
                queue - translations
            }
            persistQueue()
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        synchronized(queueMutationLock) {
            _queueState.update {
                it.forEach { translation ->
                    if (translation.status == Translation.State.TRANSLATING ||
                        translation.status == Translation.State.QUEUE
                    ) {
                        translation.status = Translation.State.NOT_TRANSLATED
                    }
                }
                emptyList()
            }
            persistQueue()
        }
    }
}
