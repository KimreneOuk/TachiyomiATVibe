package eu.kanade.translation.coordinator

import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationPendingRequestRecord
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.translationQueueAdmissionFailureKind
import eu.kanade.translation.presentation.TranslationUiProjection
import eu.kanade.translation.processing.batch.BatchAdmissionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val REGISTERED_READER_STORE_WAIT_REASON =
    "Waiting for registered reader translation store to close before download re-key"

/**
 * Coordinates pending translation requests from acknowledgement through download handoff.
 * Every asynchronous durable write is fenced by per-chapter version and request generation,
 * so a later phase change, cancel, or retry cannot be overwritten by a stale write.
 */
internal class TranslationRequestCoordinator(
    internal val pendingRequestStore: TranslationPendingRequestStore,
    initialPendingRequests: MutableStateFlow<Map<Long, TranslationRequestState>>? = null,
    private val storeScopeProvider: () -> CoroutineScope,
    private val queueStateProvider: (() -> StateFlow<List<Translation>>)? = null,
    private val batchCoordinatorProvider: () -> BatchTranslationCoordinator,
    private val getQueuedTranslationOverride: ((Long) -> Translation?)? = null,
    private val translateChapterOverride: ((Manga, Chapter, Long?, Boolean) -> Unit)? = null,
    private val startBatchTranslation: () -> Unit = {},
    private val sessionCoordinator: TranslationSessionCoordinator = TranslationSessionCoordinator(),
    private val cancelReaderChapterAsync: (Long) -> Unit = {},
    private val sourceIsHttp: (Manga) -> Boolean = { false },
    private val resolveTranslation: suspend (Long) -> Translation? = { resolveTranslationFromChapterId(it) },
    private val hasDownloadedFiles: (Translation) -> Boolean = { false },
    private val hasPendingDownloadRekeyFn: (Long) -> Boolean = { false },
    private val reconcilePendingDownloadRekeyFn: suspend (Long) -> Boolean = { true },
) {

    enum class AdmissionResult {
        STALE_GENERATION,
        QUEUED,
        QUEUE_FAILED,
    }

    private val pendingTranslationRequestsState = initialPendingRequests ?: MutableStateFlow(
        pendingRequestStore.load().associateWith { chapterId ->
            val record = pendingRequestStore.record(chapterId)
            TranslationRequestState(
                chapterId = chapterId,
                phase = record?.phase ?: TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                reason = pendingRequestStore.reason(chapterId),
                generation = record?.generation ?: 0L,
                failureKind = record?.failureKind ?: TranslationRequestFailureKind.NONE,
            )
        },
    )
    val pendingTranslationRequests: StateFlow<Map<Long, TranslationRequestState>> =
        pendingTranslationRequestsState.asStateFlow()
    internal val pendingRequestMutationLock = Any()
    private val pendingRequestWriteVersions = ConcurrentHashMap<Long, AtomicLong>()
    private val pendingRequestGenerationCounters = ConcurrentHashMap<Long, AtomicLong>()
    private val downloadAttachGenerations = ConcurrentHashMap<Long, Long>()
    private val downloadRekeyingChapterIds = ConcurrentHashMap.newKeySet<Long>()
    private val pendingGroupIdSequence = AtomicLong(0)
    private val storeScope get() = storeScopeProvider()
    private val batchCoordinator get() = batchCoordinatorProvider()
    private val queueState get() = queueStateProvider?.invoke() ?: batchCoordinator.queueState
    private fun queuedTranslationOrNull(chapterId: Long): Translation? =
        getQueuedTranslationOverride?.invoke(chapterId) ?: batchCoordinator.queuedTranslationOrNull(chapterId)

    private fun submitTranslation(manga: Manga, chapter: Chapter, generation: Long?, autoStart: Boolean) {
        val override = translateChapterOverride
        if (override != null) {
            override(manga, chapter, generation, autoStart)
            return
        }
        val result = translateChapter(manga, chapter, generation, autoStart = autoStart)
        if (autoStart && result != AdmissionResult.STALE_GENERATION) startBatchTranslation()
    }
    private val restoreAdmittedChapterIds = ConcurrentHashMap.newKeySet<Long>()
    private val startupReconciliationOnce = AtomicBoolean(false)

    @Volatile private var downloadQueueRestoredChapterIds: Set<Long>? = null

    @Volatile private var translationQueueRestoreJob: Job? = null

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        chapter.id?.let { chapterId ->
            restoreAdmittedChapterIds.remove(chapterId)
            synchronized(pendingRequestMutationLock) {
                setPendingTranslationRequest(chapterId, TranslationRequestPhase.WAITING_FOR_DOWNLOAD)
                // Attach: capture the request generation the download will be
                // fenced against when its completion callback arrives.
                pendingTranslationRequestsState.value[chapterId]?.generation?.let { generation ->
                    downloadAttachGenerations[chapterId] = generation
                }
            }
        }
    }

    /** Writes WAITING only while the request still has [expectedGeneration]; cancellation wins the lock race. */
    fun queueTranslationAfterDownloadIfCurrent(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long,
    ): Boolean {
        val chapterId = chapter.id ?: return false
        synchronized(pendingRequestMutationLock) {
            if (!isRequestCurrent(chapterId, expectedGeneration)) return false
            queueTranslationAfterDownload(manga, chapter)
            return true
        }
    }

    /** Writes PREPARING only while the request still has [expectedGeneration]. */
    fun markTranslationRequestPreparingIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean {
        synchronized(pendingRequestMutationLock) {
            if (!isRequestCurrent(chapterId, expectedGeneration)) return false
            markTranslationRequestPreparing(chapterId)
            return true
        }
    }

    /** Keeps this attached download request live while a reader owns its chapter store. */
    fun markRegisteredReaderStoreWaitIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean {
        synchronized(pendingRequestMutationLock) {
            if (!isRequestCurrent(chapterId, expectedGeneration)) return false
            setPendingTranslationRequest(
                chapterId = chapterId,
                phase = TranslationRequestPhase.PREPARING,
                reason = REGISTERED_READER_STORE_WAIT_REASON,
            )
            return true
        }
    }

    /**
     * True when the live request for [chapterId] is still a live (non-terminal)
     * request carrying [generation]. A request that reached an explicit
     * terminal phase (cancel/failure notifications) never satisfies a fence —
     * a user retry allocates a new generation.
     */
    fun isTranslationRequestCurrent(chapterId: Long, generation: Long): Boolean =
        currentRequest(chapterId)?.let { request ->
            !request.isTerminal && request.generation == generation
        } == true

    /** Current in-process request generation, or null when no request exists. */
    fun pendingRequestGeneration(chapterId: Long): Long? =
        pendingTranslationRequestsState.value[chapterId]?.generation

    /** Reserve a completed-download page-key migration against request and batch admission. */
    fun tryBeginDownloadRekey(chapterId: Long, isBatchActive: () -> Boolean): Boolean =
        synchronized(pendingRequestMutationLock) {
            if (isBatchActive()) return@synchronized false
            downloadRekeyingChapterIds.add(chapterId)
        }

    fun finishDownloadRekey(chapterId: Long) {
        downloadRekeyingChapterIds.remove(chapterId)
    }

    fun isChapterDownloadRekeying(chapterId: Long): Boolean =
        chapterId in downloadRekeyingChapterIds

    /** Captures only the currently attached, live request for this download. */
    fun downloadAttachGeneration(chapterId: Long): Long? = synchronized(pendingRequestMutationLock) {
        val current = currentRequest(chapterId) ?: return@synchronized null
        current.generation.takeIf {
            !current.isTerminal && downloadAttachGenerations[chapterId] == current.generation
        }
    }

    /**
     * Retries only the request that failed because its completed-download
     * re-key was deferred/rejected. The original generation and download
     * attachment must still match; cancel/replacement/pause or existing queue
     * ownership leaves the request visible for explicit user action.
     */
    fun retryRekeyFailedAdmissionIfCurrent(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long,
        autoStart: Boolean,
    ): Boolean {
        val chapterId = chapter.id ?: return false
        val retryGeneration = synchronized(pendingRequestMutationLock) {
            val current = currentRequest(chapterId) ?: return false
            val retryableFailure =
                current.phase == TranslationRequestPhase.ADMISSION_FAILED &&
                    current.failureKind == TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED &&
                    current.isDownloadRekeyFailure()
            val waitingForReaderStore =
                current.phase == TranslationRequestPhase.PREPARING &&
                    current.reason == REGISTERED_READER_STORE_WAIT_REASON
            if (
                current.generation != expectedGeneration ||
                (!retryableFailure && !waitingForReaderStore) ||
                downloadAttachGenerations[chapterId] != expectedGeneration ||
                batchCoordinator.isPaused ||
                queueState.value.any { it.chapter.id == chapterId }
            ) {
                return false
            }
            setPendingTranslationRequest(
                chapterId = chapterId,
                phase = TranslationRequestPhase.PREPARING,
                reason = "Retrying translation after completed-download reconciliation",
            )
            currentRequest(chapterId)?.generation?.also { downloadAttachGenerations[chapterId] = it }
        } ?: return false
        submitTranslation(manga, chapter, retryGeneration, autoStart)
        if (queueState.value.any { it.chapter.id == chapterId }) return true

        // translateChapter can decline before queue admission (for example a
        // reader session won a race after the full leave). Keep the request
        // visible and retryable instead of leaving a false PREPARING state.
        synchronized(pendingRequestMutationLock) {
            val current = currentRequest(chapterId)
            if (current?.generation == retryGeneration && current.phase == TranslationRequestPhase.PREPARING) {
                setPendingTranslationRequest(
                    chapterId = chapterId,
                    phase = TranslationRequestPhase.ADMISSION_FAILED,
                    reason = "Download re-key reconciliation completed but batch admission was deferred",
                    failureKind = TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED,
                )
            }
        }
        return false
    }

    private fun TranslationRequestState.isDownloadRekeyFailure(): Boolean =
        reason?.startsWith("Rekey deferred:") == true ||
            reason?.startsWith("Rekey rejected:") == true ||
            reason?.startsWith("Rekey partial:") == true ||
            reason?.startsWith("Download re-key reconciliation ") == true

    /** Lock-internal fence check used by the fenced mutation helpers. */
    private fun isRequestCurrent(chapterId: Long, generation: Long): Boolean =
        isTranslationRequestCurrent(chapterId, generation)

    /**
     * The current request, preferring the live state and falling back to the
     * durable record (e.g. a request restored after a process restart). Both
     * views are normalized onto [TranslationRequestState].
     */
    private fun currentRequest(chapterId: Long): TranslationRequestState? =
        pendingTranslationRequestsState.value[chapterId]
            ?: pendingRequestStore.record(chapterId)?.let { record ->
                TranslationRequestState(
                    chapterId = record.chapterId,
                    phase = record.phase,
                    reason = record.reason,
                    generation = record.generation,
                    failureKind = record.failureKind,
                )
            }

    /** Publishes the acknowledgement shown while the live download probe runs. */
    fun acknowledgeTranslationRequests(chapters: List<Chapter>) {
        val chapterIds = chapters.mapNotNull { it.id }.distinct()
        if (chapterIds.isEmpty()) return
        chapterIds.forEach(restoreAdmittedChapterIds::remove)
        val versions = synchronized(pendingRequestMutationLock) {
            // One generation bump per new request; one group id per batch.
            val generations = chapterIds.associateWith(::allocateGeneration)
            val groupId = nextGroupId()
            pendingTranslationRequestsState.update { current ->
                current + chapterIds.associateWith { chapterId ->
                    TranslationRequestState(
                        chapterId = chapterId,
                        phase = TranslationRequestPhase.STARTING,
                        generation = generations.getValue(chapterId),
                    )
                }
            }
            chapterIds.associateWith { chapterId ->
                PendingAcknowledgement(
                    version = nextPendingRequestVersion(chapterId),
                    generation = generations.getValue(chapterId),
                    groupId = groupId,
                )
            }
        }
        versions.forEach { (chapterId, acknowledgement) ->
            BatchDownloadDiagnostics.requestPhase(
                chapterId = chapterId,
                generation = acknowledgement.generation,
                from = null,
                to = TranslationRequestPhase.STARTING,
                failureKind = TranslationRequestFailureKind.NONE,
            )
        }
        storeScope.launch(Dispatchers.IO) {
            versions.forEach { (chapterId, acknowledgement) ->
                persistPendingStartingAcknowledgement(chapterId, acknowledgement)
            }
        }
    }

    /** Advances an acknowledged request into archive/store/engine preparation. */
    fun markTranslationRequestPreparing(chapterId: Long) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId)) {
            setPendingTranslationRequest(chapterId, TranslationRequestPhase.PREPARING)
        }
    }

    fun admitChapterThroughQueue(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long?,
        admissionContext: BatchAdmissionContext?,
        evictStaleQueuedEntry: Boolean,
    ): AdmissionResult {
        val chapterId = chapter.id ?: return AdmissionResult.QUEUE_FAILED
        if (expectedGeneration != null) {
            val current = pendingTranslationRequestsState.value[chapterId]
            if (current == null || current.generation != expectedGeneration) {
                logcat(LogPriority.INFO) {
                    "T911 dropped stale translate admission for chapter $chapterId " +
                        "(expectedGeneration=$expectedGeneration currentGeneration=${current?.generation})"
                }
                return AdmissionResult.STALE_GENERATION
            }
        }
        cancelReaderChapterAsync(chapterId)
        if (evictStaleQueuedEntry) batchCoordinator.evictStaleQueuedChapters(chapterId, manga.source)
        markTranslationRequestPreparing(chapterId)
        batchCoordinator.queueChapter(
            manga,
            chapter,
            admissionContext?.probedSourcePageCount,
            admissionContext?.sourceCountKnown ?: false,
        )
        return if (batchCoordinator.queuedTranslationOrNull(chapterId) != null) {
            clearPendingTranslationRequest(chapterId)
            AdmissionResult.QUEUED
        } else {
            markTranslationQueueFailureIfAcknowledged(manga, chapterId)
            AdmissionResult.QUEUE_FAILED
        }
    }

    fun translateChapter(
        manga: Manga,
        chapter: Chapter,
        expectedRequestGeneration: Long? = null,
        admissionContext: BatchAdmissionContext? = null,
        autoStart: Boolean = true,
    ): AdmissionResult {
        val chapterId = chapter.id ?: return AdmissionResult.QUEUE_FAILED
        if (!sessionCoordinator.requestBatchSession(setOf(chapterId))) return AdmissionResult.QUEUE_FAILED
        val result = synchronized(pendingRequestMutationLock) {
            admitChapterThroughQueue(
                manga,
                chapter,
                expectedRequestGeneration,
                admissionContext,
                evictStaleQueuedEntry = true,
            )
        }
        if (result == AdmissionResult.STALE_GENERATION) return result
        val queued = batchCoordinator.queuedTranslationOrNull(chapterId)
        if (autoStart) {
            batchCoordinator.rearmQueuedChapter(chapterId)
        } else if (queued?.status == Translation.State.QUEUE) {
            queued.status = Translation.State.PAUSED
        }
        return result
    }

    fun translateChapterAndStart(
        manga: Manga,
        chapter: Chapter,
        expectedRequestGeneration: Long? = null,
        admissionContext: BatchAdmissionContext? = null,
        autoStart: Boolean = true,
    ): AdmissionResult {
        val result = translateChapter(manga, chapter, expectedRequestGeneration, admissionContext, autoStart)
        if (autoStart && result != AdmissionResult.STALE_GENERATION) startBatchTranslation()
        return result
    }

    fun translateChaptersInternal(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>?,
        admissionContexts: Map<Long, BatchAdmissionContext> = emptyMap(),
    ): List<Chapter> {
        if (chapters.isEmpty()) return emptyList()
        if (!sessionCoordinator.requestBatchSession(chapters.mapNotNull { it.id }.toSet())) return emptyList()
        val admitted = mutableListOf<Chapter>()
        synchronized(pendingRequestMutationLock) {
            chapters.forEach { chapter ->
                val chapterId = chapter.id ?: return@forEach
                val expectedGeneration = expectedGenerations?.get(chapterId)
                if (expectedGenerations != null && expectedGeneration == null) return@forEach
                val result = admitChapterThroughQueue(
                    manga,
                    chapter,
                    expectedGeneration,
                    admissionContexts[chapterId],
                    evictStaleQueuedEntry = false,
                )
                if (result == AdmissionResult.QUEUED) {
                    batchCoordinator.rearmQueuedChapter(chapterId)
                    admitted += chapter
                }
            }
        }
        return admitted
    }

    fun translateChaptersAndStart(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>? = null,
        admissionContexts: Map<Long, BatchAdmissionContext> = emptyMap(),
    ): List<Chapter> {
        val admitted = translateChaptersInternal(manga, chapters, expectedGenerations, admissionContexts)
        if (admitted.isNotEmpty()) startBatchTranslation()
        return admitted
    }

    fun markTranslationQueueFailureIfAcknowledged(manga: Manga, chapterId: Long) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId)) {
            val failureKind = translationQueueAdmissionFailureKind(
                sourceIsHttp = sourceIsHttp(manga),
                configValid = batchCoordinator.isQueueConfigValid(),
            )
            setPendingTranslationRequest(
                chapterId,
                TranslationRequestPhase.ADMISSION_FAILED,
                "Translation could not be queued",
                failureKind,
            )
        }
    }

    fun onDownloadQueueRestored(chapterIds: Set<Long>) {
        downloadQueueRestoredChapterIds = chapterIds
        storeScope.launch { runStartupReconciliationIfReady() }
    }

    fun isRestoreAdmitted(chapterId: Long): Boolean = chapterId in restoreAdmittedChapterIds

    fun consumeRestoreAdmission(chapterId: Long): Boolean = restoreAdmittedChapterIds.remove(chapterId)

    suspend fun startTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        val chapterId = chapter.id
        val autoStart = chapterId == null || !restoreAdmittedChapterIds.remove(chapterId)
        startTranslationAfterDownloadIfRequested(manga, chapter, autoStart)
    }

    fun translationQueueRestoreStarted(job: Job) {
        translationQueueRestoreJob = job
    }

    fun runStartupReconciliationIfReady() {
        val downloadSnapshot = downloadQueueRestoredChapterIds ?: return
        if (!startupReconciliationOnce.compareAndSet(false, true)) return
        storeScope.launch {
            translationQueueRestoreJob?.join()
            reconcilePendingRequestsForStartup(downloadQueueChapterIds = downloadSnapshot)
        }
    }

    suspend fun reconcilePendingRequestsForStartup(
        downloadQueueChapterIds: Set<Long>,
        resolveTranslation: suspend (Long) -> Translation? = this.resolveTranslation,
        hasDownloadedFiles: (Translation) -> Boolean = this.hasDownloadedFiles,
    ) {
        val pendingIds = (pendingRequestStore.load() + pendingTranslationRequestsState.value.keys).distinct()
        if (pendingIds.isEmpty()) return
        val translationQueueIds = queueState.value.mapNotNull { it.chapter.id }.toSet()
        pendingIds.forEach { chapterId ->
            val current = pendingTranslationRequestsState.value[chapterId]
                ?: pendingRequestStore.record(chapterId)?.let { record ->
                    TranslationRequestState(
                        chapterId = record.chapterId,
                        phase = record.phase,
                        reason = record.reason,
                        generation = record.generation,
                        failureKind = record.failureKind,
                    )
                }
            if (current == null || current.isTerminal) return@forEach
            val translation = resolveTranslation(chapterId)
            when {
                translation == null -> clearPendingTranslationRequest(chapterId)
                chapterId in translationQueueIds -> clearPendingTranslationRequest(chapterId)
                chapterId in downloadQueueChapterIds -> {
                    restoreAdmittedChapterIds.add(chapterId)
                    if (current.phase != TranslationRequestPhase.WAITING_FOR_DOWNLOAD) {
                        setPendingTranslationRequest(chapterId, TranslationRequestPhase.WAITING_FOR_DOWNLOAD)
                    }
                }
                hasDownloadedFiles(translation) -> admitRestoredPendingTranslation(translation, current.generation)
                else -> markTranslationDownloadFailed(
                    chapterId,
                    "Interrupted before the chapter download or translation could run",
                    TranslationRequestFailureKind.INTERRUPTED,
                )
            }
        }
    }

    fun admitRestoredPendingTranslation(translation: Translation, expectedGeneration: Long) {
        val chapterId = translation.chapter.id ?: return
        if (!sessionCoordinator.requestBatchSession(setOf(chapterId))) return
        synchronized(pendingRequestMutationLock) {
            val current = pendingTranslationRequestsState.value[chapterId]
            val currentGeneration = current?.generation ?: pendingRequestStore.record(chapterId)?.generation
            if (currentGeneration != expectedGeneration) return
            cancelReaderChapterAsync(chapterId)
            markTranslationRequestPreparing(chapterId)
            batchCoordinator.queueChapter(translation.manga, translation.chapter)
            if (batchCoordinator.queuedTranslationOrNull(chapterId) != null) {
                restoreAdmittedChapterIds.add(chapterId)
                clearPendingTranslationRequest(chapterId)
            } else {
                markTranslationQueueFailureIfAcknowledged(translation.manga, chapterId)
            }
        }
    }

    /** Keeps a failed download request visible and retryable from the batch details UI. */
    fun markTranslationDownloadFailed(
        chapterId: Long,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.DOWNLOAD_FAILED,
    ) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId) ||
            pendingRequestStore.load().contains(chapterId)
        ) {
            setPendingTranslationRequest(
                chapterId,
                TranslationRequestPhase.DOWNLOAD_FAILED,
                reason,
                failureKind,
            )
        }
    }

    /** Records a translation start failure after download; the download remains `DOWNLOADED`. */
    fun markTranslationHandoffFailed(
        chapterId: Long,
        reason: String? = null,
    ) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId) ||
            pendingRequestStore.load().contains(chapterId)
        ) {
            setPendingTranslationRequest(
                chapterId,
                TranslationRequestPhase.ADMISSION_FAILED,
                reason ?: "Translation could not start after the chapter download",
                TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED,
            )
        }
    }

    // Download lifecycle notifications are no-ops when a chapter has no pending request.

    /** The chapter's download was cancelled or removed from the queue. */
    fun onDownloadCancelled(chapterId: Long) {
        transitionAttachedRequest(chapterId, TranslationRequestPhase.CANCELLED, null) {
            TranslationRequestFailureKind.CANCELLED
        }
    }

    /** The whole download queue was cleared. */
    fun onDownloadQueueCleared(chapterId: Long) {
        transitionAttachedRequest(chapterId, TranslationRequestPhase.CANCELLED, null) {
            TranslationRequestFailureKind.QUEUE_CLEARED
        }
    }

    /** The downloader stopped (offline, Wi-Fi policy, generic stop) with the chapter unfinished. */
    fun onDownloadStopped(chapterId: Long, reason: String?) {
        transitionAttachedRequest(chapterId, TranslationRequestPhase.DOWNLOAD_FAILED, reason) {
            TranslationRequestFailureKind.DOWNLOADER_STOPPED
        }
    }

    /**
     * Transitions a still-attached request into an explicit terminal phase.
     * Existence-checked: without a pending request this is a no-op, and a
     * cancelled/re-requested chapter is only touched when a request actually
     * exists (its generation changes again, so fences stay correct).
     */
    private fun transitionAttachedRequest(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String?,
        kind: () -> TranslationRequestFailureKind,
    ) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId) ||
            pendingRequestStore.load().contains(chapterId)
        ) {
            setPendingTranslationRequest(chapterId, phase, reason, kind())
        }
    }

    /** Removes only the pending request; downloaded files and queue membership are untouched. */
    fun cancelTranslationRequest(chapterId: Long): Boolean {
        val existed = pendingTranslationRequestsState.value.containsKey(chapterId) ||
            pendingRequestStore.load().contains(chapterId)
        clearPendingTranslationRequest(chapterId)
        return existed
    }

    /**
     * Drops a stale terminal request at manual/auto reader entry so a failed,
     * cancelled, or admission-failed projection cannot outlive the batch that
     * produced it and a later download completion cannot fire an unrequested
     * batch. Live phases are left untouched.
     */
    fun clearStaleDownloadFailedRequest(chapterId: Long) {
        val phase = pendingTranslationRequestsState.value[chapterId]?.phase
            ?: pendingRequestStore.phase(chapterId)
        if (phase == TranslationRequestPhase.DOWNLOAD_FAILED ||
            phase == TranslationRequestPhase.CANCELLED ||
            phase == TranslationRequestPhase.ADMISSION_FAILED
        ) {
            clearPendingTranslationRequest(chapterId)
        }
    }

    /** Rearms a failed download request so an explicit download retry can complete it. */
    fun rearmDownloadFailedRequest(chapterId: Long): Boolean {
        val currentPhase = pendingTranslationRequestsState.value[chapterId]?.phase
            ?: pendingRequestStore.phase(chapterId)
            ?: return false
        if (currentPhase == TranslationRequestPhase.DOWNLOAD_FAILED) {
            setPendingTranslationRequest(
                chapterId = chapterId,
                phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                reason = "re-armed after download failure",
                failureKind = TranslationRequestFailureKind.NONE,
            )
            return true
        }
        return false
    }

    fun hasPendingTranslationRequest(chapterId: Long): Boolean =
        pendingTranslationRequestsState.value.containsKey(chapterId) ||
            pendingRequestStore.load().contains(chapterId)

    /**
     * A chapter remains protected from reader auto-delete while either its
     * queued batch or its durable post-download intent may still need files.
     */
    fun isChapterTranslationProtected(chapterId: Long): Boolean {
        val queuedState = queuedTranslationOrNull(chapterId)?.status
        return TranslationUiProjection.protectsChapterFromDeletion(
            queuedState = queuedState,
            hasPendingRequest = pendingTranslationRequestsState.value.containsKey(chapterId) ||
                pendingRequestStore.load().contains(chapterId),
        ) ||
            chapterId in batchCoordinator.persistedQueueChapterIds()
    }

    /** Protected ids are passed to the pending chapter deleter at reader finish. */
    fun protectedChapterIds(): Set<Long> = buildSet {
        queueState.value
            .filter { translation ->
                translation.status == Translation.State.QUEUE ||
                    translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.PAUSED
            }
            .mapNotNullTo(this) { it.chapter.id }
        addAll(pendingTranslationRequestsState.value.keys)
        addAll(pendingRequestStore.load())
        addAll(batchCoordinator.persistedQueueChapterIds())
    }

    fun setPendingTranslationRequest(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.NONE,
    ) {
        synchronized(pendingRequestMutationLock) {
            nextPendingRequestVersion(chapterId)
            val current = pendingTranslationRequestsState.value[chapterId]
            val durable = pendingRequestStore.record(chapterId)
            // The generation is stable across the request's live phase
            // transitions so a fence taken at attach time stays valid through
            // PREPARING. It advances when a live phase is written over a
            // terminal record (a retry is a NEW request) and when a brand-new
            // record is created outside an acknowledgement, so fences never
            // compare against 0 or against a cancelled generation.
            val wasTerminal = current?.isTerminal == true ||
                (current == null && durable?.phase?.isTerminalPhase() == true)
            val generation = when {
                wasTerminal && !phase.isTerminalPhase() -> allocateGeneration(chapterId)
                current != null -> current.generation
                durable != null -> durable.generation
                else -> allocateGeneration(chapterId)
            }
            val groupId = durable?.groupId
            val kind = if (phase.isTerminalPhase()) failureKind else TranslationRequestFailureKind.NONE
            pendingRequestStore.add(
                TranslationPendingRequestRecord(
                    chapterId = chapterId,
                    phase = phase,
                    reason = reason,
                    generation = generation,
                    groupId = groupId,
                    failureKind = kind,
                    createdAtEpochMs = durable?.createdAtEpochMs ?: System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            pendingTranslationRequestsState.update {
                it + (
                    chapterId to TranslationRequestState(
                        chapterId = chapterId,
                        phase = phase,
                        reason = reason,
                        generation = generation,
                        failureKind = kind,
                    )
                    )
            }
            BatchDownloadDiagnostics.requestPhase(
                chapterId = chapterId,
                generation = generation,
                from = current?.phase ?: durable?.phase,
                to = phase,
                failureKind = kind,
            )
        }
    }

    fun clearPendingTranslationRequest(chapterId: Long) {
        synchronized(pendingRequestMutationLock) {
            val current = currentRequest(chapterId)
            nextPendingRequestVersion(chapterId)
            // Generation bump on cancel/removal: a later request for the same
            // chapter must never reuse the removed request's generation.
            pendingRequestGenerationCounters.computeIfAbsent(chapterId) {
                AtomicLong(pendingRequestStore.generation(chapterId))
            }.incrementAndGet()
            downloadAttachGenerations.remove(chapterId)
            pendingRequestStore.remove(chapterId)
            pendingTranslationRequestsState.update { it - chapterId }
            if (current != null) {
                BatchDownloadDiagnostics.requestCleared(chapterId, current.generation, current.phase)
            }
        }
    }

    fun clearAllPendingTranslationRequests() {
        synchronized(pendingRequestMutationLock) {
            val requests = (pendingTranslationRequestsState.value.keys + pendingRequestStore.load())
                .distinct()
                .mapNotNull(::currentRequest)
            requests.forEach { request ->
                val chapterId = request.chapterId
                nextPendingRequestVersion(chapterId)
                pendingRequestGenerationCounters.computeIfAbsent(chapterId) {
                    AtomicLong(pendingRequestStore.generation(chapterId))
                }.incrementAndGet()
                downloadAttachGenerations.remove(chapterId)
            }
            pendingRequestStore.clear()
            pendingTranslationRequestsState.value = emptyMap()
            requests.forEach { request ->
                BatchDownloadDiagnostics.requestCleared(request.chapterId, request.generation, request.phase)
            }
        }
    }

    /** Clears all queued work and request acknowledgements as one batch lifecycle operation. */
    fun clearTranslationQueue() {
        batchCoordinator.clearQueue()
        batchCoordinator.stop()
        clearAllPendingTranslationRequests()
        sessionCoordinator.finishSession()
    }

    private fun nextPendingRequestVersion(chapterId: Long): Long =
        pendingRequestWriteVersions.computeIfAbsent(chapterId) { AtomicLong() }.incrementAndGet()

    /** Continues per-chapter generations from durable state so retries cannot reuse an old generation. */
    private fun allocateGeneration(chapterId: Long): Long =
        pendingRequestGenerationCounters.computeIfAbsent(chapterId) {
            AtomicLong(
                maxOf(
                    pendingRequestStore.generation(chapterId),
                    pendingTranslationRequestsState.value[chapterId]?.generation ?: 0L,
                ),
            )
        }.incrementAndGet()

    private fun nextGroupId(): String = "batch-${pendingGroupIdSequence.incrementAndGet()}"

    private fun TranslationRequestPhase.isTerminalPhase(): Boolean =
        this == TranslationRequestPhase.DOWNLOAD_FAILED ||
            this == TranslationRequestPhase.CANCELLED ||
            this == TranslationRequestPhase.ADMISSION_FAILED

    /**
     * Persists the immediate STARTING acknowledgement without allowing it to
     * overwrite a newer phase. The same lock covers normal phase mutations, so
     * the version check and commit cannot be separated by a state publication.
     */
    private fun persistPendingStartingAcknowledgement(
        chapterId: Long,
        acknowledgement: PendingAcknowledgement,
    ) {
        synchronized(pendingRequestMutationLock) {
            val currentVersion = pendingRequestWriteVersions[chapterId]?.get() ?: return
            val current = pendingTranslationRequestsState.value[chapterId]
            if (current == null) {
                // A cancellation may have removed the request before this IO
                // task acquired the lock. Keep the durable store cleared.
                pendingRequestStore.remove(chapterId)
                return
            }
            if (currentVersion != acknowledgement.version ||
                current.phase != TranslationRequestPhase.STARTING
            ) {
                return
            }
            pendingRequestStore.add(
                TranslationPendingRequestRecord(
                    chapterId = chapterId,
                    phase = TranslationRequestPhase.STARTING,
                    reason = null,
                    generation = acknowledgement.generation,
                    groupId = acknowledgement.groupId,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Fences download completion with the attached request generation; stale callbacks cannot admit or recreate it. */
    suspend fun startTranslationAfterDownloadIfRequested(
        manga: Manga,
        chapter: Chapter,
        // Requests admitted during startup recovery remain queued until explicitly resumed.
        autoStart: Boolean = true,
    ) {
        val chapterId = chapter.id ?: return
        // A retained download re-key mapping MUST be reconciled (or honestly
        // absent) before the request may advance to PREPARING/batch admission.
        // The reconcile runs OUTSIDE the request mutation lock (store-level
        // synchronization only) so no nested lock can deadlock; the fenced
        // block below re-validates the request afterwards, so a cancel/pause
        // racing the reconcile still wins.
        if (hasPendingDownloadRekeyFn(chapterId) && !reconcilePendingDownloadRekeyFn(chapterId)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT download re-key reconciliation not clean for chapter $chapterId; " +
                    "admission refused without a PREPARING request"
            }
            markTranslationHandoffFailed(
                chapterId,
                "Download re-key reconciliation deferred or rejected; retry translation after the reader fully closes",
            )
            return
        }
        // Atomic fence (post-review fix): the generation check and the
        // PREPARING write happen under the SAME mutation lock, so a cancel
        // landing between check and write is serialized (either the cancel
        // runs first and the callback is dropped, or the callback's PREPARING
        // write runs first and the cancel removes the request) — the request
        // can never be resurrected. Admission then re-validates the captured
        // generation under the same lock inside translateChapter.
        val generation = synchronized(pendingRequestMutationLock) {
            val current = currentRequest(chapterId) ?: return
            // A request that reached an explicit terminal phase (cancelled or
            // failed by the download side) can never be satisfied by a
            // completion callback.
            if (current.isTerminal) return
            val attachedGeneration = downloadAttachGenerations[chapterId]
            if (attachedGeneration == null || attachedGeneration != current.generation) {
                logcat(LogPriority.INFO) {
                    "T911 dropped stale download-completion callback for chapter $chapterId " +
                        "(attachedGeneration=$attachedGeneration currentGeneration=${current.generation})"
                }
                return
            }
            setPendingTranslationRequest(chapterId, TranslationRequestPhase.PREPARING)
            current.generation
        }
        submitTranslation(manga, chapter, generation, autoStart)
    }

    private data class PendingAcknowledgement(
        val version: Long,
        val generation: Long,
        val groupId: String,
    )
}
