package eu.kanade.translation.scheduling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicLong

/** A timed-out native call keeps exclusive ownership until its real exit. */
class NativeRunQuarantine(
    private val scope: CoroutineScope,
    private val occupancyObserver: OccupancyObserver? = null,
) {
    interface OccupancyObserver {
        fun onLaneOccupied(token: Long, pageKey: String, startedAtEpochMs: Long)
        fun onLaneReleased(token: Long)
    }

    /** Test/production bridge for observer implementations without adding locks. */
    constructor(
        scope: CoroutineScope,
        onLaneOccupied: ((token: Long, pageKey: String, startedAtEpochMs: Long) -> Unit)?,
        onLaneReleased: ((token: Long) -> Unit)?,
    ) : this(scope, object : OccupancyObserver {
        override fun onLaneOccupied(token: Long, pageKey: String, startedAtEpochMs: Long) {
            onLaneOccupied?.invoke(token, pageKey, startedAtEpochMs)
        }

        override fun onLaneReleased(token: Long) {
            onLaneReleased?.invoke(token)
        }
    })
    private val admission = Mutex()
    private val generation = AtomicLong(0L)

    /**
     * Runs a synchronous lifecycle action only when no native invocation owns the lane.
     * Callers can mark their lifecycle state dirty when this returns false and rebuild
     * on the next admitted invocation.
     */
    fun tryRunExclusive(block: () -> Unit): Boolean {
        if (!admission.tryLock()) return false
        return try {
            block()
            true
        } finally {
            admission.unlock()
        }
    }

    suspend fun <T> run(
        chapter: String,
        pageKey: String,
        timeoutMs: Long,
        onTimeout: suspend (runGeneration: Long) -> Unit = {},
        block: suspend () -> T,
    ): Outcome<T> = admission.withLock {
        val runGeneration = generation.incrementAndGet()
        val callerContext = currentCoroutineContext().minusKey(Job)
        val exited = CompletableDeferred<Unit>()
        val invocation = scope.async(callerContext) {
            try {
                block()
            } finally {
                exited.complete(Unit)
            }
        }
        occupancyObserver?.onLaneOccupied(
            token = runGeneration,
            pageKey = pageKey,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        try {
            val outcome = select<Outcome<T>> {
                invocation.onAwait { value -> Outcome.Accepted(value, runGeneration) }
                onTimeout(timeoutMs) {
                    generation.incrementAndGet()
                    logcat(LogPriority.ERROR) {
                        "TachiyomiAT native timeout quarantined: chapter=$chapter pageKey=$pageKey " +
                            "runGeneration=$runGeneration timeoutMs=$timeoutMs"
                    }
                    onTimeout(runGeneration)
                    Outcome.TimedOut(runGeneration)
                }
            }
            if (outcome is Outcome.TimedOut) {
                awaitExitAndLogLate(invocation, exited, chapter, pageKey, runGeneration, "timeout")
            }
            outcome
        } catch (cancelled: CancellationException) {
            generation.incrementAndGet()
            awaitExitAndLogLate(invocation, exited, chapter, pageKey, runGeneration, "cancellation")
            throw cancelled
        } finally {
            occupancyObserver?.onLaneReleased(runGeneration)
        }
    }

    private suspend fun <T> awaitExitAndLogLate(
        invocation: kotlinx.coroutines.Deferred<T>,
        exited: CompletableDeferred<Unit>,
        chapter: String,
        pageKey: String,
        runGeneration: Long,
        reason: String,
    ) = withContext(NonCancellable) {
        exited.await()
        runCatching { invocation.await() }
        logcat(LogPriority.WARN) {
            "TachiyomiAT native late result rejected: chapter=$chapter pageKey=$pageKey " +
                "runGeneration=$runGeneration currentGeneration=${generation.get()} reason=$reason"
        }
    }

    sealed interface Outcome<out T> {
        data class Accepted<T>(val value: T, val generation: Long) : Outcome<T>
        data class TimedOut(val generation: Long) : Outcome<Nothing>
    }
}
