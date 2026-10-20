package eu.kanade.translation.processing.adaptive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Foreground/manual pages are ordered ahead of queued background pages. */
enum class DevicePermitPriority {
    FOREGROUND,
    BACKGROUND,
}

/** Handle that allows live promotion of an enqueued background permit to foreground. */
class DevicePriorityHandle(
    initial: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DevicePriorityHandle>

    private val current = AtomicReference(initial)
    internal val promotion = CompletableDeferred<Unit>()

    val priority: DevicePermitPriority get() = current.get()

    fun promote(): Boolean {
        while (true) {
            val existing = current.get()
            if (existing == DevicePermitPriority.FOREGROUND) return false
            if (current.compareAndSet(existing, DevicePermitPriority.FOREGROUND)) {
                promotion.complete(Unit)
                return true
            }
        }
    }
}

/**
 * Cancellation-safe coroutine permit gate for device-active page work.
 *
 * This gate supports the stage-aware protocol (release while a page is
 * parked on a remote provider, then reacquire before render) around the
 * existing serialized native lane, so its floor capacity of one preserves
 * today's native concurrency. Provider/governor limits are not inputs to
 * this gate.
 */
class DevicePagePermitGate(
    private val capacity: () -> Int = { DEFAULT_CAPACITY },
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
    suspend fun acquire(
        priority: DevicePermitPriority,
        handle: DevicePriorityHandle? = null,
    ): Permit? {
        val initialPriority = handle?.priority ?: priority
        val waiter = lock.withLock {
            val limit = capacityLimit
            if (activePermits < limit && waiters.isEmpty()) {
                activePermits++
                return Permit(this)
            }
            Waiter(initialPriority, nextSequence++).also {
                waiters += it
                queuedWaiterCount = waiters.size
            }
        }

        try {
            val grantedInTime = when (initialPriority) {
                DevicePermitPriority.FOREGROUND ->
                    withTimeoutOrNull(foregroundWaitCapMs) {
                        waiter.granted.await()
                        true
                    } ?: false

                DevicePermitPriority.BACKGROUND -> {
                    if (handle != null) {
                        val promoted = select<Boolean> {
                            waiter.granted.onAwait { false }
                            handle.promotion.onAwait { true }
                        }
                        if (promoted) {
                            lock.withLock {
                                if (waiter.state == WaiterState.WAITING) {
                                    waiter.priority = DevicePermitPriority.FOREGROUND
                                    if (activePermits < capacityLimit) {
                                        waiters.remove(waiter)
                                        queuedWaiterCount = waiters.size
                                        waiter.state = WaiterState.GRANTED
                                        activePermits++
                                        waiter.granted.complete(Unit)
                                    }
                                }
                            }
                            withTimeoutOrNull(foregroundWaitCapMs) {
                                waiter.granted.await()
                                true
                            } ?: false
                        } else {
                            true
                        }
                    } else {
                        waiter.granted.await()
                        true
                    }
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

    suspend fun <T> withPermit(
        priority: DevicePermitPriority,
        handle: DevicePriorityHandle? = null,
        block: suspend () -> T,
    ): T? {
        val permit = acquire(priority, handle) ?: return null
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
        var priority: DevicePermitPriority,
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
        /** Fixed device-active page capacity of the serialized native lane. */
        const val DEFAULT_CAPACITY = 1

        const val DEFAULT_FOREGROUND_WAIT_CAP_MS = 15_000L
    }
}
