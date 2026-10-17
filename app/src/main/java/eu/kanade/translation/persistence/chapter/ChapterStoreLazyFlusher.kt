package eu.kanade.translation.persistence.chapter

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lazy-queue mutation flush collaborator (Phase 7 S5). Owns the drain of
 * memory-first queued page publications into the publication bridge and the
 * journal-barrier interleave around it: credits are reserved before the
 * capture permit, the store mutex is acquired inside the barrier so lazy
 * frames cannot cross a capture barrier, and one accepted-work batch gets one
 * final force. The publication bridge remains the sole owner of atomic
 * sidecar/manifest writes; this collaborator only decides when that bridge is
 * reached. A generation mismatch or defunct store discards work without
 * touching disk.
 */
internal class ChapterStoreLazyFlusher(private val store: ChapterTranslationStore) {

    /**
     * Retry mutations that were already durably captured before a later promotion
     * rejection. Their prior credit is consumed, so acquire a fresh nonblocking
     * reservation outside the store mutex before the next artifact attempt.
     */
    internal suspend fun reservePendingLazyJournalCredits() {
        val pending = store.mutex.withLock {
            store.writeDrain.pendingLazyMutations.values
                .filter { it.journalCredit == null && store.isLazyGenerationCurrent(it.generation) }
                .map { it.pageKey to it.generation }
        }
        val reservations = pending.map { (pageKey, expectedGeneration) ->
            Triple(pageKey, expectedGeneration, store.reserveJournalCredit(pageKey))
        }
        try {
            store.mutex.withLock {
                reservations.forEach { (pageKey, expectedGeneration, credit) ->
                    val current = store.writeDrain.pendingLazyMutations[pageKey]
                    if (current != null &&
                        current.generation == expectedGeneration &&
                        current.journalCredit == null &&
                        store.isLazyGenerationCurrent(expectedGeneration) &&
                        credit?.retain() == true
                    ) {
                        store.writeDrain.pendingLazyMutations[pageKey] = current.copy(journalCredit = credit)
                    } else {
                        credit?.releaseIfUnqueued()
                    }
                }
            }
        } catch (failure: Throwable) {
            reservations.forEach { (_, _, credit) -> credit?.releaseIfUnqueued() }
            throw failure
        }
    }

    /**
     * Owns the journal-capture interleave for queued lazy mutations. Reserve
     * credits before taking the capture permit, then acquire the store mutex
     * inside that permit so lazy frames cannot cross a capture barrier. The
     * batch gets one final force, plus required pre-close segment forces at
     * rollover and a segment-header force whenever a new segment is created
     * (epoch start or rollover).
     */
    internal suspend fun flushPendingLazyMutationsWithJournalCapture() {
        reservePendingLazyJournalCredits()
        val journalFlushBatch = JournalFlushBatch()
        var flushFailure: Throwable? = null
        store.withJournalCaptureBarrier {
            try {
                store.mutex.withLock {
                    flushLazyMutationsLocked(journalFlushBatch)
                }
            } catch (failure: Throwable) {
                flushFailure = failure
                throw failure
            } finally {
                if (journalFlushBatch.hasJournalWork) {
                    val writer = store.journalWriter
                    if (writer != null) {
                        try {
                            withContext(NonCancellable) {
                                writer.flushToCaptureBarrier()
                            }
                        } catch (barrierFailure: Throwable) {
                            val mutationFailure = flushFailure
                            if (mutationFailure == null) {
                                throw barrierFailure
                            } else {
                                mutationFailure.addSuppressed(barrierFailure)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Drains memory-first page publications into the existing artifact bridge.
     * The bridge remains the sole owner of atomic sidecar/manifest writes; this
     * method only changes when that bridge is reached.  A generation mismatch
     * or defunct store discards the work without touching disk.
     *
     * Caller holds [ChapterTranslationStore.mutex].
     */
    private suspend fun flushLazyMutationsLocked(journalFlushBatch: JournalFlushBatch? = null): Boolean {
        val pendingLazyMutations = store.writeDrain.pendingLazyMutations
        if (pendingLazyMutations.isEmpty()) return true
        val pending = pendingLazyMutations.values.toList()
        pendingLazyMutations.clear()
        var allAccepted = true
        pending.forEach { mutation ->
            if (!store.isLazyGenerationCurrent(mutation.generation)) {
                mutation.journalCredit?.releaseIfRetained()
                return@forEach
            }
            try {
                if (!store.persistArtifactMutation(
                        pageKey = mutation.pageKey,
                        previous = mutation.previous,
                        updated = mutation.updated,
                        expected = mutation.expected,
                        journalCredit = mutation.journalCredit,
                        journalFlushBatch = journalFlushBatch,
                    )
                ) {
                    allAccepted = false
                    if (store.isLazyGenerationCurrent(mutation.generation)) {
                        val stillOwnedCredit = mutation.journalCredit?.takeIf { it.retain() }
                        pendingLazyMutations[mutation.pageKey] = mutation.copy(journalCredit = stillOwnedCredit)
                    } else {
                        mutation.journalCredit?.releaseIfRetained()
                    }
                } else {
                    mutation.artifactLease?.stamp(store, mutation.pageKey, store.snapshotLocked(mutation.pageKey))
                }
            } catch (failure: Throwable) {
                mutation.journalCredit?.releaseIfUnqueued()
                pending.dropWhile { it !== mutation }.drop(1).forEach { remaining ->
                    remaining.journalCredit?.releaseIfRetained()
                }
                throw failure
            }
        }
        return allAccepted
    }
}
