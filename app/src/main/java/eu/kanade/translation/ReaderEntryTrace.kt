package eu.kanade.translation

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

    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ReaderEntryTrace-Watchdog").apply { isDaemon = true }
    }

    fun begin(stage: String, chapterId: Long?): TracedStage {
        val startedAtMs = SystemClock.elapsedRealtime()
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
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}"
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

        fun end() {
            if (!completed.compareAndSet(false, true)) return
            watchdogFuture.cancel(false)
            logcat(LogPriority.INFO) {
                "[reader_entry] end stage=$stage chapterId=$chapterId " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}"
            }
        }
    }
}
