package eu.kanade.translation.manager

import eu.kanade.translation.ChapterTranslator
import eu.kanade.translation.TranslationPendingRequestStore
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.TranslationUiProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val translateChapter: (Manga, Chapter) -> Unit,
) {

    private val pendingRequestStore get() = pendingRequestStoreProvider()
    private val pendingTranslationRequestsState get() = pendingTranslationRequestsStateProvider()
    private val pendingRequestWriteVersions get() = pendingRequestWriteVersionsProvider()
    private val pendingRequestMutationLock get() = pendingRequestMutationLockProvider()
    private val storeScope get() = storeScopeProvider()
    private val queueState get() = queueStateProvider()
    private val translator get() = translatorProvider()

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        chapter.id?.let { chapterId ->
            setPendingTranslationRequest(chapterId, TranslationRequestPhase.WAITING_FOR_DOWNLOAD)
        }
    }

    /** Publishes the acknowledgement shown while the live download probe runs. */
    fun acknowledgeTranslationRequests(chapters: List<Chapter>) {
        val chapterIds = chapters.mapNotNull { it.id }.distinct()
        if (chapterIds.isEmpty()) return
        val versions = synchronized(pendingRequestMutationLock) {
            chapterIds.associateWith(::nextPendingRequestVersion).also {
                // Publish the in-memory acknowledgement before any disk
                // operation so the confirmation row and its protection fence
                // are immediate. The lock keeps this publication atomic with
                // every later synchronous phase/cancel mutation.
                pendingTranslationRequestsState.update { current ->
                    acknowledgePendingTranslationState(current, chapterIds)
                }
            }
        }
        storeScope.launch(Dispatchers.IO) {
            versions.forEach { (chapterId, version) ->
                persistPendingStartingAcknowledgement(chapterId, version)
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
    fun markTranslationDownloadFailed(chapterId: Long, reason: String? = null) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId) ||
            pendingRequestStore.load().contains(chapterId)
        ) {
            setPendingTranslationRequest(chapterId, TranslationRequestPhase.DOWNLOAD_FAILED, reason)
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
     * Drops a stale DOWNLOAD_FAILED request at manual/auto reader entry so the
     * failed projection cannot outlive the batch that produced it and a later
     * download completion cannot fire an unrequested batch. Live phases are
     * left untouched.
     */
    fun clearStaleDownloadFailedRequest(chapterId: Long) {
        val phase = pendingTranslationRequestsState.value[chapterId]?.phase
            ?: pendingRequestStore.phase(chapterId)
        if (phase == TranslationRequestPhase.DOWNLOAD_FAILED) {
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
    ) {
        synchronized(pendingRequestMutationLock) {
            nextPendingRequestVersion(chapterId)
            pendingRequestStore.add(chapterId, phase, reason)
            pendingTranslationRequestsState.update {
                it + (chapterId to TranslationRequestState(chapterId, phase, reason))
            }
        }
    }

    fun clearPendingTranslationRequest(chapterId: Long) {
        synchronized(pendingRequestMutationLock) {
            nextPendingRequestVersion(chapterId)
            pendingRequestStore.remove(chapterId)
            pendingTranslationRequestsState.update { it - chapterId }
        }
    }

    fun clearAllPendingTranslationRequests() {
        synchronized(pendingRequestMutationLock) {
            (pendingTranslationRequestsState.value.keys + pendingRequestStore.load()).distinct()
                .forEach(::nextPendingRequestVersion)
            pendingRequestStore.clear()
            pendingTranslationRequestsState.value = emptyMap()
        }
    }

    private fun nextPendingRequestVersion(chapterId: Long): Long =
        pendingRequestWriteVersions.computeIfAbsent(chapterId) { AtomicLong() }.incrementAndGet()

    /**
     * Persists the immediate STARTING acknowledgement without allowing it to
     * overwrite a newer phase. The same lock covers normal phase mutations, so
     * the version check and commit cannot be separated by a state publication.
     */
    private fun persistPendingStartingAcknowledgement(chapterId: Long, initialVersion: Long) {
        synchronized(pendingRequestMutationLock) {
            val currentVersion = pendingRequestWriteVersions[chapterId]?.get() ?: return
            val current = pendingTranslationRequestsState.value[chapterId]
            if (current == null) {
                // A cancellation may have removed the request before this IO
                // task acquired the lock. Keep the durable store cleared.
                pendingRequestStore.remove(chapterId)
                return
            }
            if (currentVersion != initialVersion || current.phase != TranslationRequestPhase.STARTING) return
            pendingRequestStore.add(chapterId, TranslationRequestPhase.STARTING, null)
        }
    }

    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) {
        val chapterId = chapter.id ?: return
        if (!pendingRequestStore.load().contains(chapterId) &&
            pendingTranslationRequestsState.value[chapterId] == null
        ) {
            return
        }
        setPendingTranslationRequest(chapterId, TranslationRequestPhase.PREPARING)
        translateChapter(manga, chapter)
    }
}
