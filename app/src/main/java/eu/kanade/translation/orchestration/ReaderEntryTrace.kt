package eu.kanade.translation.orchestration

import eu.kanade.translation.*
import eu.kanade.translation.storage.*

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage-entry instrumentation for the reader-open pipeline.
 *
 * Existing `[reader_entry]` summaries only log when a stage completes, so a
 * stage that hangs mid-way is completely silent. Each traced stage logs a
 * `begin` line on entry, an `end` line with elapsed time on completion, and a
 * `still-running` watchdog line every 15 seconds while it has not finished,
 * naming the hung stage directly.
 *
 * The watchdog self-cancels after [MAX_WATCHDOG_TICKS] ticks so a stage that
 * fails without reaching `end()` cannot tick indefinitely. Aggregate INFO
 * only — no manga titles, chapter names, filenames, or URIs. All shared
 * state runs on the single watchdog thread, so plain vars suffice.
 */
object ReaderEntryTrace {

    private const val WATCHDOG_PERIOD_MS = 15_000L
    private const val MAX_WATCHDOG_TICKS = 20
    private const val MAIN_HEARTBEAT_PERIOD_MS = 500L
    private const val MAIN_HEARTBEAT_WARN_MS = 1_000L

    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ReaderEntryTrace-Watchdog").apply { isDaemon = true }
    }

    // JVM unit tests have no Android looper; constructing the handler there
    // would throw and fail the whole object initializer, so resolve it lazily
    // and treat "no looper" as heartbeat-disabled.
    private val mainHandler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }

    /**
     * Monotonic milliseconds. JVM unit tests run against the android.jar
     * stubs where [SystemClock.elapsedRealtime] throws "not mocked"; fall
     * back to [System.nanoTime] there so tracing stays usable in tests.
     */
    private fun nowMs(): Long =
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }

    fun begin(stage: String, chapterId: Long?): TracedStage {
        val startedAtMs = nowMs()
        logcat(LogPriority.INFO) { "[reader_entry] begin stage=$stage chapterId=$chapterId" }
        var ticks = 0
        var future: ScheduledFuture<*>? = null
        future = watchdogExecutor.scheduleWithFixedDelay(
            {
                ticks++
                if (ticks > MAX_WATCHDOG_TICKS) {
                    future?.cancel(false)
                } else {
                    logcat(LogPriority.INFO) {
                        "[reader_entry] still-running stage=$stage chapterId=$chapterId " +
                            "elapsedMs=${nowMs() - startedAtMs}"
                    }
                }
            },
            WATCHDOG_PERIOD_MS,
            WATCHDOG_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
        return TracedStage(stage, chapterId, startedAtMs, future)
    }

    class TracedStage internal constructor(
        private val stage: String,
        private val chapterId: Long?,
        private val startedAtMs: Long,
        private val watchdogFuture: ScheduledFuture<*>,
    ) {
        private val completed = AtomicBoolean(false)

        // Main-thread wedge probe: a runnable re-posted to the main looper for
        // the duration of the stage. The gap between consecutive runs is how
        // long main went without processing — the direct signal for a wedged
        // main thread (black screen with zero frames) versus a background
        // park. Only late runs and the max delay are logged, so a healthy
        // main is silent. Written from main, read from any — volatile.
        @Volatile
        private var heartbeatMarkMs = 0L

        @Volatile
        private var maxMainDelayMs = 0L

        init {
            heartbeatMarkMs = nowMs()
            val runnable = object : Runnable {
                override fun run() {
                    if (completed.get()) return
                    val now = nowMs()
                    val delay = now - heartbeatMarkMs
                    heartbeatMarkMs = now
                    if (delay > maxMainDelayMs) maxMainDelayMs = delay
                    if (delay > MAIN_HEARTBEAT_WARN_MS) {
                        logcat(LogPriority.INFO) {
                            "[reader_entry] main-heartbeat stage=$stage chapterId=$chapterId delayMs=$delay"
                        }
                    }
                    mainHandler?.postDelayed(this, MAIN_HEARTBEAT_PERIOD_MS)
                }
            }
            mainHandler?.postDelayed(runnable, MAIN_HEARTBEAT_PERIOD_MS)
        }

        fun end() {
            if (!completed.compareAndSet(false, true)) return
            watchdogFuture.cancel(false)
            logcat(LogPriority.INFO) {
                "[reader_entry] end stage=$stage chapterId=$chapterId " +
                    "elapsedMs=${nowMs() - startedAtMs} " +
                    "maxMainDelayMs=$maxMainDelayMs"
            }
        }
    }
}
