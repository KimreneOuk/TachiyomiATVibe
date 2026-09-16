package eu.kanade.translation.store

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.PageStageLease
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.model.PageStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// T909 Phase 17a: the page-stage lease table moved from
// `ChapterTranslationStore` (record + backing map + the five lease members).
// The DUAL locking discipline is load-bearing and moved verbatim: the store
// mutex guards lease/patch paths (via [store]'s `mutex`) while
// `synchronized(pageLeases)` guards the lock-free readers (`markDefunct`,
// `pageLeaseOwner`, `clearTransientQueuePages`); the `NonCancellable` wrappers
// on release/cancel/releaseAll preserve cancellation behavior. The map is
// shared with the store through [pageLeases] — never copied — and the store keeps
// same-signature delegating stubs at the old qualified names (the pipeline,
// ReaderViewModel, and the lease tests resolve them there).
internal class PageStageLeaseTable(private val store: ChapterTranslationStore) {

    // Same-name dependency reads the moved bodies use; resolved through the
    // owning store at each call.
    private val mutex get() = store.mutex

    private val defunct get() = store.isDefunct

    private val generation get() = store.currentGeneration

    private val pages get() = store.pages

    private fun snapshotLocked(pageKey: String) = store.snapshotLocked(pageKey)

    private fun cancelArtifactCandidateLocked(pageKey: String): Boolean =
        store.cancelArtifactCandidateLocked(pageKey)

    /** Writer leases per page: one origin owns a page until it releases it. */
    val pageLeases = ConcurrentHashMap<String, PageLeaseRecord>()
    private var nextLeaseToken = 0L

    /**
     * T917 D3: per-page waiters parked until the page's lease is released.
     * Registration happens under `synchronized(pageLeases)` and every release
     * path removes its lease AND completes this page's waiters inside the same
     * `synchronized(pageLeases)` critical section (nested in the store mutex),
     * so a release racing a registration can never strand a waiter: either the
     * waiter observes the empty lease first, or the releasing path finds and
     * completes it in the same monitor. Entries are removed on completion and
     * in the waiter's `finally`, so the registry never grows unbounded.
     */
    private val leaseReleaseWaiters =
        ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableDeferred<Unit>>>()

    internal data class PageLeaseRecord(
        val token: Long,
        val origin: PageWriteOrigin,
        val stage: PageStage,
        val generation: Long,
        /**
         * T934 R1.2: same-origin attach re-grants since this record was minted
         * (the overlap inpaint riding the envelope's token). Read ONLY by
         * [releasePageStageLeaseIfUnattached]; the plain release keeps its
         * Phase-3 contract — any matching release removes the record.
         */
        val attaches: Int = 0,
    )

    // ------------------------------------------------------------------
    // Phase 3 page/stage leases (lifecycle contract §12): one origin owns a
    // page at a time. A reader request on a batch-owned page attaches to the
    // batch result (observes store emissions) instead of opening a competing
    // writer, and vice versa. The lease binds the store generation and page
    // version the holder must present on every write.
    // ------------------------------------------------------------------

    suspend fun tryAcquirePageStageLease(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = mutex.withLock {
        if (defunct) return@withLock LeaseAcquisition.Denied("store is defunct", null)
        val existing = pageLeases[pageKey]
        // T917 D1 priority matrix: a MANUAL (reader tap) request is the one
        // cross-origin preemption — it evicts an in-flight AUTO lease and takes
        // a fresh record + token. It is safe by the existing fencing: the
        // evicted AUTO holder's guarded writes fail closed on
        // `expected.leaseToken != pageLeases[pageKey].token`, and the AUTO side
        // already treats a lost/stale page as "try again". MANUAL-vs-BATCH is
        // never a preemption (the caller attaches instead), and AUTO/BATCH
        // requests never preempt anything.
        val evictsAuto = existing != null &&
            existing.origin == PageWriteOrigin.AUTO &&
            origin == PageWriteOrigin.MANUAL
        if (existing != null && existing.origin != origin && !evictsAuto) {
            return@withLock LeaseAcquisition.Denied(
                "page owned by ${existing.origin} at stage ${existing.stage}",
                existing.origin,
            )
        }
        if (existing != null && existing.origin == origin) {
            // T934 R1.2: a same-origin re-acquire is a SIBLING ATTACH to the
            // same slot (the overlap inpaint riding the envelope's token).
            // Count it so the envelope's attach-aware release can leave the
            // record — and every identity fenced on its token — intact for
            // the sibling. The plain release below is untouched.
            pageLeases[pageKey] = existing.copy(attaches = existing.attaches + 1)
            val currentSnapshot = snapshotLocked(pageKey)
            return@withLock LeaseAcquisition.Granted(
                PageStageLease(
                    pageKey = pageKey,
                    stage = existing.stage,
                    origin = existing.origin,
                    generation = existing.generation,
                    pageVersion = currentSnapshot.pageVersion,
                    token = existing.token,
                    candidateGenerationId = currentSnapshot.candidateGenerationId,
                    dependencyFingerprint = currentSnapshot.dependencyFingerprint,
                    artifactPageVersion = currentSnapshot.artifactPageVersion,
                ),
            )
        }
        val current = pages[pageKey]
        val currentSnapshot = snapshotLocked(pageKey)
        val token = ++nextLeaseToken
        pageLeases[pageKey] = PageLeaseRecord(
            token = token,
            origin = origin,
            stage = stage,
            generation = generation,
        )
        LeaseAcquisition.Granted(
            PageStageLease(
                pageKey = pageKey,
                stage = stage,
                origin = origin,
                generation = generation,
                pageVersion = current?.pageVersion ?: 0L,
                token = token,
                candidateGenerationId = currentSnapshot.candidateGenerationId,
                dependencyFingerprint = currentSnapshot.dependencyFingerprint,
                artifactPageVersion = currentSnapshot.artifactPageVersion,
            ),
        )
    }

    suspend fun releasePageStageLease(pageKey: String, origin: PageWriteOrigin) {
        withContext(NonCancellable) {
            mutex.withLock {
                synchronized(pageLeases) {
                    if (pageLeases[pageKey]?.origin == origin) {
                        pageLeases.remove(pageKey)
                    }
                    completeLeaseReleaseWaitersLocked(pageKey)
                }
            }
        }
    }

    /**
     * T934 R1.2: releases the ENVELOPE's hold on its BATCH lease WITHOUT
     * invalidating a live sibling attach. Envelope completion used to plainly
     * release pages the overlap inpaint had re-attached to (same token): the
     * removal let the next acquire mint a fresh token and fail-close every
     * write identity still fenced on the old one ("page lease token changed" →
     * candidate abort + full page re-work). The record is removed only when it
     * is still [origin]'s [expectedToken] with NO same-origin attach since
     * mint; a re-attached record is left for the attached sibling's own (plain)
     * release. A record that moved on (re-minted token, other origin, gone)
     * is a no-op — the sibling owns that slot now.
     */
    suspend fun releasePageStageLeaseIfUnattached(
        pageKey: String,
        origin: PageWriteOrigin,
        expectedToken: Long,
    ): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            synchronized(pageLeases) {
                val record = pageLeases[pageKey]
                val removed = record?.origin == origin &&
                    record.token == expectedToken &&
                    record.attaches == 0
                if (removed) {
                    pageLeases.remove(pageKey)
                    completeLeaseReleaseWaitersLocked(pageKey)
                }
                removed
            }
        }
    }

    /** Cancels the active artifact candidate and releases its matching writer lease. */
    suspend fun cancelPageStageWork(pageKey: String, origin: PageWriteOrigin): Boolean =
        withContext(NonCancellable) {
            mutex.withLock {
                synchronized(pageLeases) {
                    val lease = pageLeases[pageKey]
                    if (lease?.origin == origin) {
                        val cancelled = cancelArtifactCandidateLocked(pageKey)
                        if (cancelled && pageLeases[pageKey]?.origin == origin) {
                            pageLeases.remove(pageKey)
                        }
                        completeLeaseReleaseWaitersLocked(pageKey)
                        cancelled
                    } else {
                        false
                    }
                }
            }
        }

    /** Releases every lease held by [origin]; used at batch teardown so no lease outlives its run. */
    suspend fun releaseAllPageLeases(origin: PageWriteOrigin) {
        withContext(NonCancellable) {
            mutex.withLock {
                synchronized(pageLeases) {
                    val releasedKeys = pageLeases.entries
                        .filter { it.value.origin == origin }
                        .map { it.key }
                    releasedKeys.forEach { pageLeases.remove(it) }
                    releasedKeys.forEach { completeLeaseReleaseWaitersLocked(it) }
                }
            }
        }
    }

    fun pageLeaseOwner(pageKey: String): PageWriteOrigin? = synchronized(pageLeases) {
        pageLeases[pageKey]?.origin
    }

    /**
     * T917 D3 defer-and-rescan remnant: completes any lease-release waiters.
     * The only waiter producer (the deleted [awaitPageLeaseRelease] used by
     * the deleted in-pass re-scan consumer) is gone, so this currently
     * completes nothing; retained because the release paths still call it
     * (T924 D2 follow-up: retire together with the defer-and-rescan wiring).
     * Caller MUST hold `synchronized(pageLeases)` and the page's lease must already be removed.
     */
    private fun completeLeaseReleaseWaitersLocked(pageKey: String) {
        if (pageLeases[pageKey] != null) return
        leaseReleaseWaiters.remove(pageKey)?.forEach { it.complete(Unit) }
    }
}
