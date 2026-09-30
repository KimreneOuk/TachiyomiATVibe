package eu.kanade.translation.pipeline.adaptive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground/manual pages are ordered ahead of queued background pages. */
enum class DevicePermitPriority {
    FOREGROUND,
    BACKGROUND,
}

/**
 * Cancellation-safe coroutine permit gate for device-active page work.
 *
 * This gate supports the future stage-aware protocol (release while a page is
 * parked on a remote provider, then reacquire before render). E20a wires it
 * only around the existing serialized native lane, so its floor capacity of
 * one preserves today's native concurrency. Provider/governor limits are not
 * inputs to this gate.
 */
class DevicePagePermitGate(
    private val capacity: () -> Int = { AdaptiveKnobController.DEFAULT_FLOOR },
    private val foregroundWaitCapMs: Long = DEFAULT_FOREGROUND_WAIT_CAP_MS,
) {
    private val lock = Mutex()
    private val waiters = mutableListOf<Waiter>()

    @Volatile
    private var activePermits = 0

    @Volatile
    private var queuedWaiterCount = 0
    private var nextSequence = 0L

    internal val capacityLimit: Int get() = capacity().coerceAtLeast(1)

    init {
        require(foregroundWaitCapMs > 0)
    }

    val activeCount: Int get() = activePermits
    val queuedCount: Int get() = queuedWaiterCount

    /** Returns null only when a foreground wait reaches its boundary. */
    suspend fun acquire(priority: DevicePermitPriority): Permit? {
        val waiter = lock.withLock {
            val limit = capacityLimit
            if (activePermits < limit && waiters.isEmpty()) {
                activePermits++
                return Permit(this)
            }
            Waiter(priority, nextSequence++).also {
                waiters += it
                queuedWaiterCount = waiters.size
            }
        }

        try {
            val grantedInTime = when (priority) {
                DevicePermitPriority.FOREGROUND ->
                    withTimeoutOrNull(foregroundWaitCapMs) {
                        waiter.granted.await()
                        true
                    } ?: false

                DevicePermitPriority.BACKGROUND -> {
                    waiter.granted.await()
                    true
                }
            }
            if (grantedInTime) return Permit(this)

            return lock.withLock {
                when (waiter.state) {
                    WaiterState.GRANTED -> Permit(this)
                    WaiterState.WAITING -> {
                        waiters.remove(waiter)
                        queuedWaiterCount = waiters.size
                        waiter.state = WaiterState.CANCELLED
                        null
                    }
                    WaiterState.CANCELLED -> null
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock {
                    when (waiter.state) {
                        WaiterState.WAITING -> {
                            waiters.remove(waiter)
                            queuedWaiterCount = waiters.size
                            waiter.state = WaiterState.CANCELLED
                        }
                        WaiterState.GRANTED -> releaseLocked()
                        WaiterState.CANCELLED -> Unit
                    }
                }
            }
            throw cancelled
        }
    }

    suspend fun <T> withPermit(priority: DevicePermitPriority, block: suspend () -> T): T? {
        val permit = acquire(priority) ?: return null
        try {
            return block()
        } finally {
            permit.release()
        }
    }

    private suspend fun release(permit: Permit) {
        if (!permit.markReleased()) return
        withContext(NonCancellable) {
            lock.withLock { releaseLocked() }
        }
    }

    /** Must be called with [lock] held. */
    private fun releaseLocked() {
        activePermits = (activePermits - 1).coerceAtLeast(0)
        val limit = capacityLimit
        if (activePermits >= limit) return

        val next = waiters
            .asSequence()
            .filter { it.state == WaiterState.WAITING }
            .minWithOrNull(compareBy<Waiter> { it.priority.ordinal }.thenBy { it.sequence })
        if (next != null) {
            waiters.remove(next)
            queuedWaiterCount = waiters.size
            next.state = WaiterState.GRANTED
            activePermits++
            next.granted.complete(Unit)
        }
    }

    private enum class WaiterState {
        WAITING,
        GRANTED,
        CANCELLED,
    }

    private class Waiter(
        val priority: DevicePermitPriority,
        val sequence: Long,
    ) {
        val granted = CompletableDeferred<Unit>()
        var state: WaiterState = WaiterState.WAITING
    }

    class Permit internal constructor(private val gate: DevicePagePermitGate) {
        private val released = AtomicBoolean(false)

        internal fun markReleased(): Boolean = released.compareAndSet(false, true)

        suspend fun release() = gate.release(this)
    }

    companion object {
        const val DEFAULT_FOREGROUND_WAIT_CAP_MS = 15_000L
    }
}
