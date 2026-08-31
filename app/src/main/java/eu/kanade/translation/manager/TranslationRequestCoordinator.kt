package eu.kanade.translation.manager

import eu.kanade.translation.ChapterTranslator
import eu.kanade.translation.TranslationPendingRequestRecord
import eu.kanade.translation.TranslationPendingRequestStore
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.TranslationUiProjection
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

internal fun acknowledgePendingTranslationState(
    current: Map<Long, TranslationRequestState>,
    chapterIds: Iterable<Long>,
): Map<Long, TranslationRequestState> =
    current + chapterIds.distinct().associateWith { chapterId ->
        TranslationRequestState(chapterId, TranslationRequestPhase.STARTING)
    }

/**
 * Pending-request subsystem moved from `TranslationManager` (T909 Phase 9).
 * Owns the store + live state + write-versions + mutation-lock protocol
 * (version fence moves intact). Manager state arrives as providers and is
 * re-read on every access — the uninitialized-manager test fixtures
 * reflection-write these fields after construction and leave the rest null,
 * so reads must stay as lazy as they were before the move.
 *
 * T911 slice 2: records carry a monotonic per-chapter request generation,
 * an optional group id, timestamps, and a typed last failure. Download-side
 * lifecycle events cancel/fail the attached request explicitly, and the
 * downloader completion callback is fenced by the generation captured when
 * the request was attached to the download (R7/R5).
 */
internal class TranslationRequestCoordinator(
    private val pendingRequestStoreProvider: () -> TranslationPendingRequestStore,
    private val pendingTranslationRequestsStateProvider: () -> MutableStateFlow<Map<Long, TranslationRequestState>>,
    private val pendingRequestWriteVersionsProvider: () -> ConcurrentHashMap<Long, AtomicLong>,
    private val pendingRequestMutationLockProvider: () -> Any,
    private val storeScopeProvider: () -> CoroutineScope,
    private val queueStateProvider: () -> StateFlow<List<Translation>>,
    private val translatorProvider: () -> ChapterTranslator,
    private val getQueuedTranslationOrNull: (Long) -> Translation?,
    private val translateChapter: (Manga, Chapter, Long?) -> Unit,
    private val pendingRequestGenerationCountersProvider: () -> ConcurrentHashMap<Long, AtomicLong>,
    private val downloadAttachGenerationsProvider: () -> ConcurrentHashMap<Long, Long>,
    private val groupIdSequenceProvider: () -> AtomicLong,
) {

    private val pendingRequestStore get() = pendingRequestStoreProvider()
    private val pendingTranslationRequestsState get() = pendingTranslationRequestsStateProvider()
    private val pendingRequestWriteVersions get() = pendingRequestWriteVersionsProvider()
    private val pendingRequestMutationLock get() = pendingRequestMutationLockProvider()
    private val storeScope get() = storeScopeProvider()
    private val queueState get() = queueStateProvider()
    private val translator get() = translatorProvider()
    private val generationCounters get() = pendingRequestGenerationCountersProvider()
    private val downloadAttachGenerations get() = downloadAttachGenerationsProvider()
    private val groupSequence get() = groupIdSequenceProvider()

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

    /**
     * T911 slice 2 (R7): fenced WAITING write. The request must still exist
     * with [expectedGeneration] when the write happens — a cancel that lands
     * before the write (under the same lock) wins and the write is dropped.
     */
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

    /** T911 slice 2 (R7): fenced PREPARING write, same protocol as above. */
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

    /**
     * T911 slice 3 (R8): the chapter's files finalized successfully, but the
     * translation start after the download failed (artifact rekey, handoff or
     * admission threw). The download stays `DOWNLOADED`; the request is failed
     * with the R10 admission-failure typing — never a download failure.
     */
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

    // T911 slice 2 (R5): download-side lifecycle notifications. Each is a
    // no-op when no pending request exists for the chapter, so ordinary
    // downloads are unaffected.

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
            generationCounters.computeIfAbsent(chapterId) {
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
                generationCounters.computeIfAbsent(chapterId) {
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

    /**
     * T911 slice 2: monotonically increasing per-chapter generation, seeded
     * from the durable counter so values never repeat across re-requests.
     */
    private fun allocateGeneration(chapterId: Long): Long =
        generationCounters.computeIfAbsent(chapterId) {
            AtomicLong(
                maxOf(
                    pendingRequestStore.generation(chapterId),
                    pendingTranslationRequestsState.value[chapterId]?.generation ?: 0L,
                ),
            )
        }.incrementAndGet()

    private fun nextGroupId(): String = "batch-${groupSequence.incrementAndGet()}"

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

    /**
     * T911 slice 2: the downloader completion callback, fenced by the request
     * generation captured when the request was attached to the download. A
     * stale callback (request cancelled, re-requested, or cleared) is dropped
     * with a log line — it never admits, and never recreates a request.
     */
    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) {
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
        translateChapter(manga, chapter, generation)
    }

    private data class PendingAcknowledgement(
        val version: Long,
        val generation: Long,
        val groupId: String,
    )
}
