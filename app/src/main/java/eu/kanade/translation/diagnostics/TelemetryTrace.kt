package eu.kanade.translation.diagnostics

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unified, fail-open structured telemetry logging utility.
 *
 * Emits key-value breadcrumbs with ISO-8601 wall-clock timestamps and monotonic nanoseconds
 * without heavy object allocations on hot paths. Guarantees fail-open behavior so logging
 * never throws or disrupts caller operations.
 */
object TelemetryTrace {
    private const val TAG_PREFIX = "TachiyomiAT"

    @Volatile
    private var testSink: ((String) -> Unit)? = null

    @Volatile
    var enabled: Boolean = true

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    fun setTestSink(sink: ((String) -> Unit)?) {
        testSink = sink
    }

    fun formatEvent(
        domain: String,
        event: String,
        wallClockMs: Long = System.currentTimeMillis(),
        monoNs: Long = System.nanoTime(),
        pairs: Array<out Pair<String, Any?>>,
    ): String {
        val dateStr = dateFormat.get()?.format(Date(wallClockMs)) ?: wallClockMs.toString()
        val sb = StringBuilder(128)
        sb.append("ts=").append(dateStr)
            .append(" monoMs=").append(monoNs / 1_000_000)
            .append(" domain=").append(domain)
            .append(" event=").append(event)
        for (pair in pairs) {
            sb.append(' ').append(pair.first).append('=')
            try {
                sb.append(pair.second?.toString() ?: "null")
            } catch (t: Throwable) {
                sb.append("<error:")
                sb.append(t.javaClass.simpleName)
                sb.append('>')
            }
        }
        return sb.toString()
    }

    fun formatEvent(
        domain: String,
        event: String,
        vararg pairs: Pair<String, Any?>,
    ): String = formatEvent(domain, event, System.currentTimeMillis(), System.nanoTime(), pairs)

    fun log(domain: String, event: String, vararg pairs: Pair<String, Any?>) {
        if (!enabled) return
        try {
            val line = formatEvent(domain, event, pairs = pairs)
            try {
                testSink?.invoke(line)
            } catch (_: Throwable) {
                // Fail-open for test sink
            }
            val tag = "$TAG_PREFIX.${domain.replaceFirstChar { it.uppercase() }}"
            try {
                Log.v(tag, line)
            } catch (_: Throwable) {
                // Fail-open: android.util.Log might not be mocked in pure JVM tests
            }
        } catch (_: Throwable) {
            // Fail open: logging must never crash the caller
        }
    }

    inline fun <T> span(domain: String, name: String, vararg initialPairs: Pair<String, Any?>, block: () -> T): T {
        val startNanos = System.nanoTime()
        log(domain, "${name}_start", *initialPairs)
        try {
            return block()
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000.0
            log(domain, "${name}_end", *initialPairs, "durationMs" to String.format(Locale.US, "%.2f", durationMs))
        }
    }

    inline fun <T> span(domain: String, name: String, block: () -> T): T {
        return span(domain, name, initialPairs = emptyArray(), block = block)
    }
}
