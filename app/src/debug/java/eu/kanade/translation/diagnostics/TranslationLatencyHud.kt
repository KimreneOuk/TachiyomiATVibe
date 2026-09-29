package eu.kanade.translation.diagnostics

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/** Debug-only, read-only overlay on the reader activity. */
internal class TranslationLatencyHud(
    private val buffer: TranslationTraceBuffer,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null
    private var label: TextView? = null

    private val refresh = object : Runnable {
        override fun run() {
            val currentLabel = label ?: return
            currentLabel.text = renderSnapshot(buffer.snapshot(), SystemClock.elapsedRealtimeNanos())
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is ReaderActivity) return
        currentActivity = activity
        handler.post {
            val root = activity.window.decorView as? ViewGroup ?: return@post
            val density = activity.resources.displayMetrics.density
            val textView = TextView(activity).apply {
                tag = HUD_TAG
                setTextColor(Color.WHITE)
                textSize = 10f
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(0xCC101820.toInt())
                    cornerRadius = 6 * density
                    setStroke((1 * density).toInt(), 0xFF5D7889.toInt())
                }
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            label?.let { existing -> (existing.parent as? ViewGroup)?.removeView(existing) }
            label = textView
            root.addView(
                textView,
                FrameLayout.LayoutParams(
                    (320 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    topMargin = (8 * density).toInt()
                    marginEnd = (8 * density).toInt()
                },
            )
            handler.removeCallbacks(refresh)
            refresh.run()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity !== activity) return
        detach()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) detach()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

    private fun detach() {
        handler.removeCallbacks(refresh)
        label?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        label = null
        currentActivity = null
    }

    private fun renderSnapshot(records: List<BufferedTraceRecord>, nowNanos: Long): String {
        val activeSpans = LinkedHashMap<String, MutableList<ActiveSpan>>()
        val recentBreaches = ArrayDeque<String>()
        var latestLaneState: Map<String, String> = emptyMap()

        records.forEach { record ->
            val fields = record.line.split(' ').mapNotNull { field ->
                val equals = field.indexOf('=')
                if (equals <= 0) null else field.substring(0, equals) to field.substring(equals + 1)
            }.toMap()
            when (fields["event"]) {
                "stage_start" -> {
                    val key = spanKey(fields)
                    activeSpans.getOrPut(key) { mutableListOf() }.add(
                        ActiveSpan(
                            fields = fields,
                            startedAtNanos = record.receivedAtNanos,
                            budgetMs = fields["budgetMs"]?.toLongOrNull(),
                        ),
                    )
                }

                "stage_end" -> {
                    activeSpans[spanKey(fields)]?.let { spans ->
                        if (spans.isNotEmpty()) spans.removeAt(0)
                        if (spans.isEmpty()) activeSpans.remove(spanKey(fields))
                    }
                    if (fields["lag"] == "true") {
                        recentBreaches.addLast(
                            "${fields["stage"] ?: "stage"} p${fields["pageIndex"] ?: "?"} " +
                                "${fields["durationMs"] ?: "?"}ms/${fields["budgetMs"] ?: "?"}ms",
                        )
                        while (recentBreaches.size > MAX_BREACHES) recentBreaches.removeFirst()
                    }
                }

                "schedule_state" -> latestLaneState = fields
            }
        }

        val spans = activeSpans.values.flatten()
        val laneCounts = spans.groupingBy { it.fields["lane"] ?: "unknown" }.eachCount()
        val lines = mutableListOf(
            "TRANSLATION LATENCY  •  DEBUG",
            "LANES  S ${laneCounts["scheduler"] ?: 0}   N ${laneCounts["native"] ?: 0}   " +
                "P ${laneCounts["provider"] ?: 0}   R ${laneCounts["render"] ?: 0}   " +
                "Q ${latestLaneState["queueDepth"] ?: "?"}",
        )

        if (spans.isEmpty()) {
            lines += "No active stage spans"
        } else {
            spans.take(MAX_ACTIVE_SPANS).forEach { span ->
                val fields = span.fields
                val elapsedMs = (nowNanos - span.startedAtNanos).coerceAtLeast(0) / NANOS_PER_MS
                val overBudget = span.budgetMs?.let { elapsedMs > it } == true
                val marker = if (overBudget) "  BUDGET" else ""
                lines += "p${fields["pageIndex"] ?: "?"} ${fields["stage"] ?: "stage"} " +
                    "${elapsedMs}ms${span.budgetMs?.let { "/${it}ms" }.orEmpty()}$marker"
            }
            if (spans.size > MAX_ACTIVE_SPANS) lines += "… ${spans.size - MAX_ACTIVE_SPANS} more active spans"
        }

        if (recentBreaches.isNotEmpty()) {
            lines += "BUDGET BREACHES"
            recentBreaches.forEach { lines += "! $it" }
        }
        return lines.joinToString("\n")
    }

    private fun spanKey(fields: Map<String, String>): String =
        listOf("sid", "rid", "page", "stage").joinToString("|") { fields[it] ?: "none" }

    private data class ActiveSpan(
        val fields: Map<String, String>,
        val startedAtNanos: Long,
        val budgetMs: Long?,
    )

    private companion object {
        const val HUD_TAG = "translation_latency_hud"
        const val REFRESH_INTERVAL_MS = 400L
        const val MAX_ACTIVE_SPANS = 8
        const val MAX_BREACHES = 3
        const val NANOS_PER_MS = 1_000_000L
    }
}
