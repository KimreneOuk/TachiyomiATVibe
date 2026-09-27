package eu.kanade.translation.coexistence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deterministic interleaving barrier for concurrency tests.
 *
 * Gates are [CompletableDeferred]s hosted INSIDE the fakes (fake decode,
 * fake provider transport, fake render reload), so every wait/park in the
 * production graph is a real suspension point — there are no sleeps and no
 * polling anywhere. Arrivals are logged into a StateFlow BEFORE parking so a
 * test-side [awaitArrival] can never miss an arrival that is already parked.
 *
 * Arm/release protocol (all event-driven):
 *  - [arm] parks the NEXT matching arrival.
 *  - [release] unparks one parked arrival (one-shot).
 *  - [disarm] makes the point pass-through from now on and unparks any
 *    waiter parked on it.
 *  - [awaitArrival] observes an arrival without parking the producer.
 *
 * COMMIT and RECONCILE have no fake injection point (the store and the batch
 * deferred are real); tests await them with `store.state.first { … }` /
 * the reconciliation [CompletableDeferred].
 */
class CoexistenceBarrier {

    enum class BarrierPoint {
        NATIVE_ACQUIRE,
        NATIVE_RELEASE,
        PROVIDER_START,
        PROVIDER_END,
        RENDER,
        COMMIT,
        ENGINE_CLOSE,
        RECONCILE,
    }

    /** Wildcard page key used where a fake has no page identity (single-page engine lane). */
    companion object {
        const val WILDCARD_PAGE = "*"
    }

    private class Gate(
        val point: BarrierPoint,
        val pageKey: String?,
        val deferred: CompletableDeferred<Unit>,
    ) {
        /** Claimed by the first arrival; a claimed gate stays listed so [release]/[disarm] can unpark it. */
        val taken = AtomicBoolean(false)
    }

    private val gates = CopyOnWriteArrayList<Gate>()
    private val _arrivals = MutableStateFlow<List<Pair<BarrierPoint, String>>>(emptyList())

    /** (point, pageKey) event log — doubles as the fake-decode/paid-call counter oracles. */
    val arrivals: StateFlow<List<Pair<BarrierPoint, String>>> = _arrivals.asStateFlow()

    /** Parks the next arrival matching (point[, pageKey=null → any page]). */
    fun arm(point: BarrierPoint, pageKey: String? = null) {
        gates += Gate(point, pageKey, CompletableDeferred())
    }

    /** Unparks one waiter matching (point[, pageKey]). Returns false when nothing was parked. */
    fun release(point: BarrierPoint, pageKey: String? = null): Boolean {
        val gate = gates.firstOrNull {
            it.point == point &&
                (pageKey == null || it.pageKey == null || it.pageKey == pageKey) &&
                !it.deferred.isCompleted
        } ?: return false
        gates.remove(gate)
        gate.deferred.complete(Unit)
        return true
    }

    /** Pass-through from now on: removes matching gates and unparks their waiters. */
    fun disarm(point: BarrierPoint, pageKey: String? = null) {
        gates.removeAll { gate ->
            val matches = gate.point == point && (pageKey == null || gate.pageKey == null || gate.pageKey == pageKey)
            if (matches) gate.deferred.complete(Unit)
            matches
        }
    }

    /** Event-driven, poll-free wait for the first arrival matching (point[, pageKey]). */
    suspend fun awaitArrival(point: BarrierPoint, pageKey: String? = null) {
        _arrivals.first { list ->
            list.any { (p, k) -> p == point && (pageKey == null || k == pageKey) }
        }
        Unit
    }

    /**
     * [awaitArrival] with a hard bound so a mis-wired choreography surfaces as
     * a timeout failure instead of hanging the suite.
     */
    suspend fun awaitArrivalWithin(
        point: BarrierPoint,
        pageKey: String? = null,
        timeoutMs: Long,
    ) {
        withTimeout(timeoutMs) { awaitArrival(point, pageKey) }
    }

    /** Count of logged arrivals matching (point[, pageKey]) — the paid-call / decode oracle. */
    fun arrivalsOf(point: BarrierPoint, pageKey: String? = null): Int =
        _arrivals.value.count { (p, k) -> p == point && (pageKey == null || k == pageKey) }

    /**
     * Called by the fakes at their suspension points: records the arrival,
     * then parks when a matching gate is armed (one-shot). The first arrival
     * claims the gate; a second concurrent arrival passes through instead of
     * queuing behind the same one-shot gate. A claimed gate remains listed
     * (marked taken) so a later [release]/[disarm] can still find and unpark
     * the parked producer.
     */
    suspend fun arrive(point: BarrierPoint, pageKey: String) {
        _arrivals.update { list -> list + (point to pageKey) }
        val gate = gates.firstOrNull {
            it.point == point && (it.pageKey == null || it.pageKey == pageKey)
        } ?: return
        if (gate.taken.compareAndSet(false, true)) {
            gate.deferred.await()
        }
    }
}
