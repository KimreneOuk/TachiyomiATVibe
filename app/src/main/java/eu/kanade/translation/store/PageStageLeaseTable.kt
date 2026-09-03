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
import kotlinx.coroutines.withTimeoutOrNull
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
     * T917 D3 defer-and-rescan: suspends until the page is lease-free, bounded
     * by [timeoutMs]. Returns true when the page has no lease at resume time,
     * false on timeout or if a newer lease was taken between the release and
     * the wake-up (the caller then simply re-defers the page). Completing the
     * waiter and removing the lease happen in the same monitor, so a wake-up
     * always corresponds to a real release.
     */
    suspend fun awaitPageLeaseRelease(pageKey: String, timeoutMs: Long): Boolean {
        val waiter = CompletableDeferred<Unit>()
        val free = synchronized(pageLeases) {
            val isFree = pageLeases[pageKey] == null
            if (!isFree) {
                leaseReleaseWaiters.computeIfAbsent(pageKey) { CopyOnWriteArrayList() }.add(waiter)
            }
            isFree
        }
        if (free) return true
        try {
            withTimeoutOrNull(timeoutMs) { waiter.await() } ?: return false
        } finally {
            leaseReleaseWaiters[pageKey]?.remove(waiter)
        }
        return synchronized(pageLeases) { pageLeases[pageKey] == null }
    }

    /** Caller MUST hold `synchronized(pageLeases)` and the page's lease must already be removed. */
    private fun completeLeaseReleaseWaitersLocked(pageKey: String) {
        if (pageLeases[pageKey] != null) return
        leaseReleaseWaiters.remove(pageKey)?.forEach { it.complete(Unit) }
    }
}
