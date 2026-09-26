package eu.kanade.translation.pipeline

import java.util.concurrent.ConcurrentLinkedQueue

/**
 *  Storage-tail actions that must run
 * OUTSIDE the native permit.
 *
 * The resume paths' cleaned-image persistence, their render tails, and the
 * page's `store.flush()` + stream-registry clear are enqueued here while the
 * ONNX phase still holds the native quarantine; the boundary drains the queue
 * AFTER [EngineLane.withNativeLane] returns, so a second page's native
 * admission is never blocked behind the first page's disk commit.
 *
 * Orphan safety: when the native phase times out, the boundary sets
 * [orphaned] BEFORE publishing the timeout — it no longer owns the queue (a
 * residual block invocation may still be running and exits later), so any
 * subsequent enqueuer runs its tail INLINE (pre- behavior). Nothing is
 * ever silently dropped: every enqueued action runs exactly once — either
 * drained by the boundary on the accepted path, or inline on the orphaned
 * path. The [NativeRunQuarantine] awaits the residual invocation's real exit
 * before `withNativeLane` returns, so a drain observed by the boundary is
 * ordered after the residual block's enqueues, and inline tails of an
 * orphaned residual are ordered before its exit. Enqueue order is preserved
 * (persist tail before flush tail), so publication ordering is identical to
 * the inline sequence.
 *
 * Drains are fail-closed: the boundary fails the page when a drained action
 * throws — storage publication must never silently succeed-or-vanish.
 */
internal class DeferredPagePublications {

    private val queue = ConcurrentLinkedQueue<suspend () -> Unit>()

    /** Set by the owning boundary when it stops owning the queue (timeout path). */
    @Volatile
    internal var orphaned = false

    /** Queues [action] for the boundary's drain, or runs it inline when orphaned. */
    suspend fun enqueue(action: suspend () -> Unit) {
        if (orphaned) {
            action()
            return
        }
        queue.add(action)
    }

    /** Runs every queued action in enqueue order; returns when the queue is empty. */
    suspend fun drainAll() {
        while (true) {
            val action = queue.poll() ?: return
            action()
        }
    }
}
