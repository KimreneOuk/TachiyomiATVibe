package eu.kanade.translation.coordinator

import android.content.Context
import android.os.BatteryManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.engines.translator.TranslationEngineBuilder
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ArtifactStage
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.BatchPageOperation
import eu.kanade.translation.processing.MemoryPressureClass
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.batch.progress.BatchProgressReconciler
import eu.kanade.translation.processing.batch.progress.ReconciliationResult
import eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class BatchTranslationCoordinator(
    private val context: Context,
    private val provider: TranslationFileProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val queueStore: TranslationQueueStore = TranslationQueueStore(context),
    private val processor: TranslationProcessor,
    private val pageOperation: BatchPageOperation,
    private val resolveStore: suspend (Translation) -> ChapterTranslationStore? = { null },
    private val batchTrackerFactory: (Long, ChapterTranslationStore, List<String>) -> eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker? = { _, _, _ -> null },
    private val onBatchClosed: suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit = { _, _, _, _ -> },
    private val sessionCoordinator: TranslationSessionCoordinator? = null,
) : Closeable {

    companion object {
        // Bound the join during chapter deletion.
        // The batch worker can be mid-uncancellable native ONNX (OrtSession.run)
        // when cancel() is requested; coroutine cancellation only lands at the
        // next suspension point. Bounding the join prevents a delete from hanging
        // on a job that won't unwind promptly (matches the reader drain policy).
        // On timeout the file delete proceeds and the defunct-store guard
        // (ChapterTranslationStore.markDefunct) neutralizes any late write.
        const val BATCH_JOIN_TIMEOUT_MS = 2_000L

        // Retry the chapter claim while the previous job finishes unwinding.
        const val IN_FLIGHT_CLAIM_RETRY_MS = 100L
    }

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    fun statusFlow(): Flow<Translation> = translationQueueStatusFlow(queueState)

    private val activeChapters = ConcurrentHashMap.newKeySet<String>()
    private val progressFlows = ConcurrentHashMap<String, MutableStateFlow<BatchPageOperation.Progress>>()

    fun isChapterActive(chapterKey: String): Boolean = activeChapters.contains(chapterKey)

    fun getProgressFlow(chapterKey: String): StateFlow<BatchPageOperation.Progress>? =
        progressFlows[chapterKey]?.asStateFlow()

    fun isChapterBatchActive(chapterId: Long, includePaused: Boolean): Boolean =
        queueState.value.any { translation ->
            translation.chapter.id == chapterId &&
                (
                    translation.status == Translation.State.QUEUE ||
                        translation.status == Translation.State.TRANSLATING ||
                        (includePaused && translation.status == Translation.State.PAUSED)
                    )
        }

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any {
            it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING
        }

    fun queuedTranslationOrNull(chapterId: Long): Translation? =
        queueState.value.firstOrNull { it.chapter.id == chapterId }

    fun rearmQueuedChapter(chapterId: Long): Boolean {
        synchronized(queueMutationLock) {
            val translation = _queueState.value.firstOrNull { it.chapter.id == chapterId } ?: return false
            // A crash can persist any status into the queue; leaving a stale
            // terminal entry here wedges it out of selectChapterWave forever.
            if (translation.status == Translation.State.QUEUE ||
                translation.status == Translation.State.TRANSLATING
            ) {
                return true
            }
            translation.status = Translation.State.QUEUE
            isPaused = false
            _queueState.update { it.toList() }
            persistQueue()
            return true
        }
    }

    fun queuePreflight(chapterId: Long?, sourceId: Long): ChapterQueuePreflight {
        if (chapterId == null) return ChapterQueuePreflight.NoConflict
        val conflict = findRunningSameSourceConflict(
            queueState.value.map { it.toQueuedChapterView() },
            chapterId,
            sourceId,
        ) ?: return ChapterQueuePreflight.NoConflict
        return ChapterQueuePreflight.RunningConflict(conflict.chapterId, conflict.chapterName)
    }

    fun evictStaleQueuedChapters(keepChapterId: Long, sourceId: Long) {
        val staleIds = staleQueuedChaptersToEvict(
            queueState.value.map { it.toQueuedChapterView() },
            keepChapterId,
            sourceId,
        ).map { it.chapterId }.toSet()
        if (staleIds.isEmpty()) return
        queueState.value.asSequence()
            .filter { it.chapter.id in staleIds }
            .map { it.chapter }
            .forEach(::removeFromQueue)
    }

    private val queueMutationLock = Any()

    fun persistedQueueChapterIds(): Set<Long> = synchronized(queueMutationLock) {
        queueStore.load().toSet()
    }

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

    private fun persistQueue() {
        synchronized(queueMutationLock) {
            queueStore.save(_queueState.value.mapNotNull { it.chapter.id })
        }
    }

    suspend fun restoreQueue() {
        val ids = synchronized(queueMutationLock) { queueStore.load() }
        if (ids.isEmpty()) return
        val restored = mutableListOf<Translation>()
        for (id in ids) {
            val translation = resolveTranslationFromChapterId(id) ?: continue
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
                val active = resolveStore(translation)
                val store = active ?: provider.findMangaDir(translation.manga.title, translation.source)?.let { parent ->
                    val fileName = provider.getTranslationFileName(
                        translation.chapter.name,
                        translation.chapter.scanlator,
                    )
                    ChapterTranslationStore.openArtifactSuspend(
                        parent,
                        fileName,
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
                processor.forceReleaseNativeBuffers()
            } catch (_: Exception) {}
        }

        // Background and memory-pressure stops keep engines open. Callers that
        // request engine closure tear them down so the next run can rebuild
        // after a provider, model, language, or OCR configuration change.
        if (reason != null && !closeEngines) return
        isPaused = false
        finishSessionIfQueueIdle()
        processor.closeEngines()
    }

    fun onMemoryPressure(level: Int, pressureClass: MemoryPressureClass) {
        tachiyomi.domain.translation.pools.BitmapPool.releaseAll()
        try {
            processor.forceReleaseNativeBuffers()
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

    suspend fun requeueExisting(chapterId: Long, force: Boolean = false): Boolean {
        val translation = queueState.value.firstOrNull { it.chapter.id == chapterId }
        if (translation == null) {
            logcat(LogPriority.INFO) { "TachiyomiAT requeue: chapter $chapterId is not in the queue" }
            return false
        }
        val durable = durableQueueState(translation)
        val next = durable?.nextEligibleRetryAtEpochMs
        if (!force && next != null && next > System.currentTimeMillis()) {
            logcat(LogPriority.INFO) { "TachiyomiAT requeue: chapter $chapterId backoff until $next" }
            return false
        }
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
        if (!rearmed) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT requeue: chapter $chapterId not rearmed (status=${translation.status})"
            }
            return false
        }
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
                    if (queue.isEmpty()) {
                        emit(emptyList())
                        return@transformLatest
                    }
                    // Recompute active wave on queue or status change.
                    combine(queue.map(Translation::statusFlow)) {
                        selectChapterWave(queue)
                    }.distinctUntilChanged().collect { emit(it) }
                }.distinctUntilChanged()
                supervisorScope {
                    val translationJobs = mutableMapOf<Translation, Job>()

                    activeTranslationFlow.collectLatest { activeTranslations ->
                        val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                        translationJobsToStop.forEach { (translation, job) ->
                            job.cancel()
                            translationJobs.remove(translation)
                        }

                        val translationsToStart = activeTranslations.filter {
                            translationJobs[it]?.isActive != true
                        }
                        translationsToStart.forEach { translation ->
                            logcat(LogPriority.INFO) { "BatchTranslationCoordinator: launching job for chapter ${translation.chapter.id}" }
                            val job = launchTranslationJob(translation)
                            translationJobs[translation] = job
                            job.invokeOnCompletion {
                                translationJobs.remove(translation)
                            }
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
                // Admit one live schedule per chapter; releases on coroutine unwind.
                var retries = 0
                while (!inFlightChapterIds.add(chapterId)) {
                    val waitResult = withTimeoutOrNull(IN_FLIGHT_CLAIM_RETRY_MS) {
                        inFlightClaimReleasedSignal.first()
                    }
                    if (waitResult == null && ++retries >= 3) {
                        logcat(LogPriority.WARN) { "Breaking stale inFlightChapterId claim for chapter $chapterId" }
                        inFlightChapterIds.remove(chapterId)
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

    internal fun isChapterTranslationInFlight(chapterId: Long?): Boolean =
        chapterId != null && inFlightChapterIds.contains(chapterId)

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

    fun isQueueConfigValid(): Boolean = isTranslationQueueConfigValid(translationPreferences)

    fun queueChapter(
        manga: Manga,
        chapter: Chapter,
        // Optional source-count evidence from the trigger's admission probe.
        // Defaults keep callers without that probe on the same path.
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val existing = queueState.value.firstOrNull { it.chapter.id == chapter.id }
        if (existing != null) {
            if (existing.status != Translation.State.TRANSLATING) {
                existing.chapter.id?.let(::rearmQueuedChapter)
            }
            return
        }
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
        val executionMode = if (translationPreferences.translationRequireCharging().get()) {
            eu.kanade.translation.model.BatchExecutionMode.ONLY_WHILE_CHARGING
        } else {
            eu.kanade.translation.model.BatchExecutionMode.STANDARD
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang, executionMode)
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
        logcat(LogPriority.INFO) {
            "BatchTranslationCoordinator: translateChapterInternal starting for chapter ${translation.chapter.id} (${translation.chapter.name})"
        }
        if (translation.executionMode == eu.kanade.translation.model.BatchExecutionMode.ONLY_WHILE_CHARGING) {
            val batteryManager = runCatching { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }.getOrNull()
            if (batteryManager != null && !batteryManager.isCharging) {
                logcat(LogPriority.INFO) { "Chapter ${translation.chapter.id} paused: waiting for charging" }
                translation.status = Translation.State.PAUSED
                return null
            }
        }
        var store: ChapterTranslationStore? = null
        var tracker: eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker? = null
        var batchReconciliation: ReconciliationResult? = null
        var batchOrderedPageKeys: List<String> = emptyList()
        try {
            // Prefer the shared active store from TranslationManager so the reader
            // observes the same instance the pipeline writes to.
            store = resolveStore(translation)
            if (store == null) {
                val parent = provider.getMangaDir(translation.manga.title, translation.source)
                val fileName = provider.getTranslationFileName(
                    translation.chapter.name,
                    translation.chapter.scanlator,
                )
                store = ChapterTranslationStore.openArtifactSuspend(
                    parent,
                    fileName,
                )
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
                    entries.filter {
                        it.isFile &&
                            ImageUtil.isImage(it.name) {
                                sharedArchive.getInputStream(it.name)
                                    ?: throw java.io.IOException("Archive entry '${it.name}' could not be opened (mmap)")
                            }
                    }
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
            try {
                // Chapter batches are deterministic natural-order traversals.
                // Reader viewport/last-read position is intentionally not a
                // batch scheduling input; resume is decided per stage by the
                // pipeline planner while pages remain 1..N.
                val orderedStreams = streams.toList()
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
                    batchTrackerFactory(chapterId, store, orderedStreams.map { it.first })
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
                    if (orderedStreams.isEmpty()) {
                        translation.status = Translation.State.ERROR
                        tracker?.abort(emptySet(), "Chapter has no readable pages to translate")
                    } else {
                        val chapterKey = translation.chapter.id?.toString() ?: translation.chapter.name
                        val progress = MutableStateFlow(
                            BatchPageOperation.Progress(
                                chapterKey = chapterKey,
                                completedPages = 0,
                                totalPages = orderedStreams.size,
                                currentStage = "INITIALIZING",
                            ),
                        )
                        progressFlows[chapterKey] = progress
                        activeChapters.add(chapterKey)
                        try {
                            val streamsByKey = orderedStreams.associate { it.first to it.second }
                            val result = pageOperation.execute(
                                chapterKey = chapterKey,
                                orderedPageKeys = batchOrderedPageKeys,
                                streamProvider = { pageKey ->
                                    streamsByKey[pageKey]?.invoke()
                                        ?: throw java.io.IOException("Page stream is missing for $pageKey")
                                },
                                store = store,
                                progress = progress,
                                companionDir = provider.getCompanionImageDir(
                                    translation.manga.title,
                                    translation.source,
                                    translation.chapter.name,
                                    translation.chapter.scanlator,
                                ),
                                onProgress = { tracker?.rebuildFromStore() },
                            )
                            if (result.error != null) {
                                tracker?.abort(batchOrderedPageKeys.toSet(), result.error)
                                translation.status = Translation.State.ERROR
                            } else {
                                batchReconciliation = BatchProgressReconciler.reconcileFlaggedCompleted(
                                    pageMap = store.state.value,
                                    orderedKeys = batchOrderedPageKeys,
                                    activeGeneration = store.currentGeneration,
                                )
                                tracker?.finish(batchReconciliation!!)
                            }
                        } finally {
                            activeChapters.remove(chapterKey)
                        }
                    }
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
                        eu.kanade.translation.processing.batch.progress.BatchProgressReconciler
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
        } finally {
            store?.let { closedStore ->
                runCatching {
                    onBatchClosed(
                        translation.manga,
                        translation.chapter,
                        translation.source,
                        closedStore,
                    )
                }.onFailure { closeError ->
                    logcat(LogPriority.WARN, closeError) {
                        "Batch coordinator teardown callback failed for ${translation.chapter.name}"
                    }
                }
            }
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
        batchOrderedPageKeys: List<String>,
        reason: String,
    ) {
        translation.status = Translation.State.ERROR
        val chapterId = translation.chapter.id ?: return
        val tracker = batchTrackerFactory(chapterId, store, batchOrderedPageKeys) ?: return
        // Publish the known ordered-key totals before the terminal event so
        // the aborted snapshot carries the real work set, not 0/0.
        tracker.rebuildFromStore()
        tracker.abort(batchOrderedPageKeys.toSet(), reason)
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
            val existing = _queueState.value.firstOrNull { it.chapter.id == translation.chapter.id }
            if (existing != null) {
                if (existing.status != Translation.State.TRANSLATING) {
                    existing.status = Translation.State.QUEUE
                    isPaused = false
                    _queueState.update { it.toList() }
                    persistQueue()
                }
                return
            }
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

    /** Public batch entry: acquire session ownership, start queued work, and expose foreground work. */
    fun startWithSessionAdmission(): Boolean {
        val chapterIds = queueState.value.mapNotNull { it.chapter.id }.toSet()
        if (sessionCoordinator?.requestBatchSession(chapterIds) == false) {
            logcat(LogPriority.WARN) { "BatchTranslationCoordinator: batch session rejected for $chapterIds" }
            return false
        }
        if (!isRunning) start()
        if (isAnyBatchTranslationActive) TranslationForegroundService.start(context)
        return isAnyBatchTranslationActive
    }

    /** Requeue a chapter only after the batch lane has acquired its session. */
    suspend fun requeueWithSessionAdmission(chapterId: Long, force: Boolean = false): Boolean {
        if (sessionCoordinator?.requestBatchSession(setOf(chapterId)) == false) {
            logcat(LogPriority.WARN) { "TachiyomiAT requeue: batch session rejected for chapter $chapterId" }
            return false
        }
        return requeueExisting(chapterId, force)
    }

    /** Removes one queued chapter and resumes remaining work under this queue's ownership. */
    fun removeChapterAndResume(chapter: Chapter) {
        val wasRunning = isRunning
        if (wasRunning) pause()
        removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) stop() else start()
        }
        finishSessionIfQueueIdle()
    }

    private fun finishSessionIfQueueIdle() {
        if (!isAnyBatchTranslationActive && queueState.value.none { it.status == Translation.State.PAUSED }) {
            sessionCoordinator?.finishSession(TranslationSessionState.BATCH_SESSION)
        }
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

    override fun close() {
        cancelTranslatorJob()
        activeChapters.clear()
    }
}
