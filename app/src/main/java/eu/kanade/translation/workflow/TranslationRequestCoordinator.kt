package eu.kanade.translation.workflow

import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.queue.TranslationPendingRequestRecord
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import eu.kanade.translation.presentation.TranslationUiProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates pending translation requests from acknowledgement through download handoff.
 * Every asynchronous durable write is fenced by per-chapter version and request generation,
 * so a later phase change, cancel, or retry cannot be overwritten by a stale write.
 */
internal class TranslationRequestCoordinator(
    private val pendingRequestStoreProvider: () -> TranslationPendingRequestStore,
    private val pendingTranslationRequestsStateProvider: () -> MutableStateFlow<Map<Long, TranslationRequestState>>,
    private val pendingRequestMutationLockProvider: () -> Any,
    private val storeScopeProvider: () -> CoroutineScope,
    private val queueStateProvider: () -> StateFlow<List<Translation>>,
    private val translatorProvider: () -> ChapterTranslator,
    private val getQueuedTranslationOrNull: (Long) -> Translation?,
    private val translateChapter: (Manga, Chapter, Long?, Boolean) -> Unit,
) {

    private val pendingRequestStore get() = pendingRequestStoreProvider()
    private val pendingTranslationRequestsState get() = pendingTranslationRequestsStateProvider()
    private val pendingRequestWriteVersions = ConcurrentHashMap<Long, AtomicLong>()
    private val pendingRequestGenerationCounters = ConcurrentHashMap<Long, AtomicLong>()
    private val downloadAttachGenerations = ConcurrentHashMap<Long, Long>()
    private val pendingGroupIdSequence = AtomicLong(0)
    private val pendingRequestMutationLock get() = pendingRequestMutationLockProvider()
    private val storeScope get() = storeScopeProvider()
    private val queueState get() = queueStateProvider()
    private val translator get() = translatorProvider()

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        chapter.id?.let { chapterId ->
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
        val queuedState = getQueuedTranslationOrNull(chapterId)?.status
        return TranslationUiProjection.protectsChapterFromDeletion(
            queuedState = queuedState,
            hasPendingRequest = pendingTranslationRequestsState.value.containsKey(chapterId) ||
                pendingRequestStore.load().contains(chapterId),
        ) ||
            chapterId in translator.persistedQueueChapterIds()
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
        addAll(translator.persistedQueueChapterIds())
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
        translateChapter(manga, chapter, generation, autoStart)
    }

    private data class PendingAcknowledgement(
        val version: Long,
        val generation: Long,
        val groupId: String,
    )
}
