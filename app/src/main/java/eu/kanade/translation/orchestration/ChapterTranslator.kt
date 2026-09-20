package eu.kanade.translation.orchestration

import eu.kanade.translation.pipeline.*

import eu.kanade.translation.*
import eu.kanade.translation.storage.*

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.pipeline.batch.ReconciliationResult
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.Translation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationEngineBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import java.util.concurrent.CopyOnWriteArrayList
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
    private val provider: TranslationProvider,
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
        // TachiyomiAT: bounded join for the batch translator job during delete.
        // The batch worker can be mid-uncancellable native ONNX (OrtSession.run)
        // when cancel() is requested; coroutine cancellation only lands at the
        // next suspension point. Bounding the join prevents a delete from hanging
        // on a job that won't unwind promptly (matches TranslationScheduler.JOIN_TIMEOUT_MS).
        // On timeout the file delete proceeds and the defunct-store guard
        // (ChapterTranslationStore.markDefunct) neutralizes any late write.
        const val BATCH_JOIN_TIMEOUT_MS = 2_000L

        // T924 hotfix: poll interval while a per-chapter batch coroutine waits
        // for the chapter's previous batch to finish unwinding (see
        // [inFlightChapterIds]).
        const val IN_FLIGHT_CLAIM_RETRY_MS = 100L

        // TachiyomiAT: reader page streams now live in [TranslationStreamRegistry]
        // (a dedicated DI singleton). These companions are thin delegates kept so
        // existing static call sites compile during the incremental migration.
        private val streamRegistry: TranslationStreamRegistry
            get() = Injekt.get()

        fun registerReaderPageStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: () -> InputStream,
        ) {
            streamRegistry.register(manga, chapter, source, pageKey, streamFn)
        }

        /**
         * Returns the registered reader stream for this page WITHOUT removing it.
         * The stream is a `() -> InputStream` factory (one invocation per retry),
         * so evicting on first use (the old `readerPageStreams.remove(...)` path)
         * meant a failed translation could never be retried — it silently wrote a
         * FAILED placeholder. The stream is dropped only on chapter cleanup via
         * [clearReaderPageStreams].
         */
        private fun peekReaderPageStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
        ): (() -> InputStream)? = streamRegistry.peek(manga, chapter, source, pageKey)

        /**
         * Evicts every reader page stream registered for [mangaId]/[sourceId] in
         * [chapterId]. Each entry holds a `() -> InputStream` closure over a
         * [eu.kanade.tachiyomi.ui.reader.model.ReaderPage] that can keep page
         * bitmaps/sources alive, so this must run on chapter change to avoid
         * leaking memory and stale streams across chapters.
         */
        fun clearReaderPageStreams(sourceId: Long, mangaId: Long, chapterId: Long) {
            streamRegistry.clearChapter(sourceId, mangaId, chapterId)
        }

        /**
         * TachiyomiAT: evicts EVERY registered reader page stream. Each entry
         * holds a `() -> InputStream` closure over a
         * [eu.kanade.tachiyomi.ui.reader.model.ReaderPage] (and, on the eager
         * prefetch path, a captured downloaded [ByteArray]); leaving them in the
         * process-lifetime map on reader background / "stop all translation"
         * pins those bytes/pages until the process dies. Call on reader
         * background / stop-all so memory is released.
         */
        fun clearAllReaderPageStreams() {
            streamRegistry.clearAll()
        }
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
     * S7 / B1 (Milestone M2): Reader-position queue priority. Moves the specified
     * queued chapter to the head of pending candidates (immediately following any
     * actively translating chapter, or index 0) and persists the updated order.
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
     * TachiyomiAT: persists the queue (ordered chapter ids) to disk after every
     * mutation so a crash mid-batch no longer loses it. One SharedPreferences
     * editor batch; idempotent.
     */
    private fun persistQueue() {
        synchronized(queueMutationLock) {
            queueStore.save(_queueState.value.mapNotNull { it.chapter.id })
        }
    }

    /**
     * TachiyomiAT: rehydrates the queue from disk on launch via
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

    // T924 hotfix: serializes the check-and-launch of the translator job so
    // concurrent admissions cannot each observe isRunning == false and launch
    // a second translator job over the same queue head.
    private val translatorLaunchLock = Any()

    // T924 hotfix: chapters whose batch is still in flight — launched, and
    // possibly still unwinding uncancellable native work after a cancel. An
    // admission for one of these chapters must be a no-op for the running
    // work (no cancel, no restart, no second schedule): two live schedules
    // for one chapter make each store generation advance cancel the prior run.
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

            // T924 hotfix: ERROR entries are excluded too — a generic queue
            // start must not resurrect failed/restored work after a restart.
            // An ERROR chapter re-enters work only via an explicit per-chapter
            // request (translateChapter re-arms it) or requeueExisting.
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

        // TachiyomiAT: the historical `if (reason != null) return` skipped
        // closeEngines() for EVERY non-null-reason stop — including the user's
        // explicit "Stop all translation". That left the cached textTranslator /
        // recognitionEngine alive (enginesClosed stayed false), so a later config
        // change (engine, provider, API key, model, language, OCR model) was
        // ignored on the next run: the rebuild gate never fired.
        // closeEngines now tears down + rearms when a caller explicitly asks for
        // it; background/memory-pressure stops leave it false to stay lightweight.
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
        // TachiyomiAT: Do NOT cancel the translator job even under Critical memory pressure.
        // If we cancel the job, we prematurely drop the HTTP connection to the AI engine while
        // it is still generating, causing the UI to "lag behind" (showing Aborted while the engine translates).
        // If the OS truly needs memory, it will kill the process and we will resume via restoreQueue() on restart.
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
                        // S11 wave lookahead (Milestone M4): allow chapter N (active translating)
                        // + chapter N+1 (preflight queue) on the same source to pipeline native OCR/inpaint
                        // while provider waits are in flight.
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
                // T924 hotfix: one live schedule per chapter. Set.add is the
                // atomic check-and-claim; a previous batch for this chapter may
                // still be unwinding uncancellable native work after a
                // pause/stop cancel, so wait for it to release the chapter
                // instead of scheduling a second concurrent batch whose store
                // generation advance cancels the prior run. The claim is
                // released only in the finally below, after this coroutine has
                // fully unwound.
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
            // T924 hotfix: release the in-flight claim only once this batch
            // coroutine has fully unwound (the claim held off overlapping
            // admissions for the same chapter while it was still running).
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
     * T911 slice 2 (R10): mirrors [queueChapter]'s config preflight so callers
     * can classify an admission rejection (config invalid vs source unsupported)
     * without duplicating preference parsing. Add-only; queueChapter is untouched.
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
        // T917 Phase 4 (D10): the trigger's admission-probe cross-check; the
        // defaults keep every legacy caller byte-identical.
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ) {        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        // TachiyomiAT: STRICT no-fallback. fromPref now throws on invalid config
        // (corrupted/migrated pref). This runs on a UI action, so a thrown
        // exception would crash the UI thread; catch it and surface as a toast
        // instead of queuing a translation that fails every page with the same error.
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

    // T911 slice 3: internal so focused unit tests can drive the exact
    // exceptional exits (missing files, unexpected exceptions) without the
    // async queue worker.
    internal suspend fun translateChapterInternal(translation: Translation): ReconciliationResult? {
        var store: ChapterTranslationStore? = null
        var tracker: eu.kanade.translation.pipeline.batch.TranslationBatchProgressTracker? = null
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
                // T911 slice 3: emit the typed terminal snapshot so the drawer
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

            // TachiyomiAT: for archive chapters, share one ArchiveReader across
            // the whole batch instead of reopening + full decompression per page
            // (the old getChapterPages closures did archiveReader().use {} on every
            // streamFn() — O(pages) re-decompressions). Directory chapters use
            // cheap direct file opens.
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
                val orderedStreams = eu.kanade.translation.util.ResumeOrdering.naturalOrder(streams)
                batchOrderedPageKeys = orderedStreams.map { it.first }
                // T911 slice 3: a rejected pre-registration is an explicit
                // pipeline error. Fail the chapter with a typed terminal tracker
                // snapshot instead of running a live tracker whose totals would
                // silently stay zero.
                // T917 Phase 4 (D10): the queued cross-check rides the
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
                    // T924 zero-legacy (D1): both surviving batch lanes end
                    // runs translation-terminal WITHOUT an in-pass render, so
                    // the post-batch queue-status projection must use the
                    // flagged COMPLETED projection — the legacy done-predicate
                    // would project every healthy page as stranded and mark
                    // the queue entry ERROR (same LI-1 class as the pass-1
                    // post-pass projection).
                    val reconciliation =
                        eu.kanade.translation.pipeline.batch.BatchProgressReconciler
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
            // T911 slice 3: an unexpected exception must leave a terminal
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
     * T911 slice 3: typed terminal exit for a batch that failed before the
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
