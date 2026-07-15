package eu.kanade.translation.translator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TachiyomiAT: deterministic inactivity-driven flush for the streaming AI chunk
 * planner. After [inactivityMs] with no new page accepted, the currently
 * buffered (incomplete) chunk is flushed so a trailing partial page does not
 * wait forever for the next page to arrive.
 *
 * Design invariants (Checkpoint 2 §3):
 *  - Deterministic & virtual-time testable: all time reads go through
 *    [TimeSource] and all sleeps through [Sleeper], so a test using a fake can
 *    advance time and observe flushes without real delays.
 *  - Existing token/page limits preserved: flushing delegates to the planner's
 *    [StreamingChunkPlanner.flushRemaining], which already enforces the budget.
 *  - One provider request at a time: the flush holds [translateMutex] across
 *    the translate callback so an inactivity flush and a size-driven flush can
 *    never issue two concurrent provider requests for the same planner.
 */
class InactivityFlusher(
    private val inactivityMs: Long = DEFAULT_INACTIVITY_MS,
    private val timeSource: TimeSource = SystemTimeSource,
    private val sleeper: Sleeper = CoroutineSleeper,
    /**
     * Invoked under [translateMutex] when an inactivity flush fires. Receives
     * the chunk built from the planner's remaining buffer (may be null if the
     * buffer was emptied concurrently). The callback owns the provider request.
     */
    private val onFlush: suspend (TranslationContextChunk?) -> Unit,
) {
    /**
     * Minimal clock abstraction so tests can drive virtual time. Returns a
     * monotonic millis reading.
     */
    fun interface TimeSource {
        fun elapsedMillis(): Long
    }

    /** Minimal sleep abstraction so tests can advance virtual time on flush. */
    fun interface Sleeper {
        suspend fun sleepMillis(ms: Long)
    }

    /** Flushes in a dedicated scope; cancelled by [stop]. */
    private val translateMutex = Mutex()
    private var timerJob: Job? = null
    @Volatile
    private var lastActivityMs: Long = timeSource.elapsedMillis()
    @Volatile
    private var stopped: Boolean = false

    /**
     * Record input activity (a page was accepted) and (re)arm the inactivity
     * timer. Safe to call repeatedly; each call resets the deadline.
     */
    fun recordActivity() {
        lastActivityMs = timeSource.elapsedMillis()
    }

    /**
     * Start the inactivity watcher in [scope]. After [inactivityMs] of no
     * [recordActivity], invoke [onFlush] with the planner's remaining chunk.
     * Re-checks the deadline after each wake so a late activity record that
     * lands during the wake extends the wait instead of falsely firing.
     */
    fun start(scope: CoroutineScope, buildRemainingChunk: suspend () -> TranslationContextChunk?) {
        if (stopped) return
        cancelTimer()
        timerJob = scope.launch {
            while (!stopped) {
                sleeper.sleepMillis(inactivityMs)
                if (stopped) return@launch
                val now = timeSource.elapsedMillis()
                val idle = now - lastActivityMs
                if (idle >= inactivityMs) {
                    // Hold the translate mutex so an inactivity flush cannot run
                    // concurrently with a size-driven flush (one provider request
                    // at a time). buildRemainingChunk also runs under the lock so
                    // the planner buffer is observed consistently.
                    translateMutex.withLock {
                        if (stopped) return@launch
                        val recheckIdle = timeSource.elapsedMillis() - lastActivityMs
                        if (recheckIdle >= inactivityMs) {
                            val chunk = buildRemainingChunk()
                            onFlush(chunk)
                            // A flush consumes the idle window: re-arm the deadline
                            // so the loop waits a full inactivity window before
                            // flushing again. Without this, a frozen clock (or a
                            // slow-producing caller) would re-flush every loop
                            // iteration, draining the buffer repeatedly.
                            lastActivityMs = timeSource.elapsedMillis()
                        }
                    }
                }
            }
        }
    }

    /** Serialize provider requests: a size-driven flush acquires the same mutex. */
    suspend fun <T> withTranslateLock(block: suspend () -> T): T = translateMutex.withLock { block() }

    /** Cancel the inactivity timer. Further [start] calls re-arm it. */
    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    /** Permanently stop: cancels the timer and rejects future [start]/flush. */
    fun stop() {
        stopped = true
        cancelTimer()
    }

    companion object {
        const val DEFAULT_INACTIVITY_MS: Long = 250L
    }
}

/** Real monotonic clock. */
object SystemTimeSource : InactivityFlusher.TimeSource {
    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L
}

/** Real coroutine delay sleeper. */
object CoroutineSleeper : InactivityFlusher.Sleeper {
    override suspend fun sleepMillis(ms: Long) {
        delay(ms)
    }
}
