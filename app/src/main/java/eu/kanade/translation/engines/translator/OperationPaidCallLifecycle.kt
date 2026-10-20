package eu.kanade.translation.engines.translator

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One demand's admission/stop state; its queue retains ownership of the exact worker Job. */
internal class OperationPaidCallLifecycle(
    private val drainGraceMs: Long = PROVIDER_DRAIN_GRACE_MS,
) : AbstractCoroutineContextElement(Key) {
    init {
        require(drainGraceMs >= 0)
    }

    data class StopDecision(val retainWorker: Boolean, val deadlineEpochMs: Long?)

    private val monitor = Any()
    private var entered = false
    private var stopped = false
    private var exited = false
    private var deadline: Long? = null
    private var expiryRequested = false

    fun shouldDenyEntry(): Boolean = synchronized(monitor) { stopped || exited }

    /** Called after provider grant, before budget charge/network, outside the governor mutex. */
    fun tryEnterPaidCall(): Boolean = synchronized(monitor) {
        if (stopped || exited) return@synchronized false
        entered = true
        true
    }

    /** Caller captures time outside queue/operation locks and cancels only unretained workers. */
    fun requestStop(nowEpochMs: Long): StopDecision = synchronized(monitor) {
        if (!stopped) {
            stopped = true
            if (entered && !exited) {
                deadline = if (nowEpochMs > Long.MAX_VALUE - drainGraceMs) Long.MAX_VALUE else nowEpochMs + drainGraceMs
            }
        }
        StopDecision(entered && !exited, deadline)
    }

    /** True once at expiry; caller cancels its exact worker outside all locks, without declaring exit. */
    fun claimExpiryCancellation(nowEpochMs: Long): Boolean = synchronized(monitor) {
        val bound = deadline ?: return@synchronized false
        if (exited || expiryRequested || nowEpochMs < bound) return@synchronized false
        expiryRequested = true
        true
    }

    /** Invoked only by the queue worker's physical completion handler. */
    fun recordPhysicalExit() {
        synchronized(monitor) { exited = true }
    }

    fun isPhysicallyExited(): Boolean = synchronized(monitor) { exited }

    companion object Key : CoroutineContext.Key<OperationPaidCallLifecycle> {
        /**
         * Pins the drained paid-call chain's legitimate budget —
         * [eu.kanade.translation.processing.TranslationProcessingTimeouts.ONNX_PHASE_TIMEOUT_MS] +
         * [eu.kanade.translation.processing.TranslationProcessingTimeouts.SINGLE_PAGE_TIMEOUT_MS]
         * (ONNX 90 s + HTTP/render 120 s, sequential = 210 s) — so a healthy
         * long call is never cut cancellation-class mid-chain.
         */
        const val PROVIDER_DRAIN_GRACE_MS = 210_000L
    }
}
