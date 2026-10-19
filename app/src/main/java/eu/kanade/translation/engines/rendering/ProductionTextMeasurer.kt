package eu.kanade.translation.engines.rendering

import android.graphics.Paint
import android.graphics.Typeface

/**
 * Production [TextMeasurer] used by the reader's background planner. Its
 * measurement must be IDENTICAL to the
 * overlay's planning measurer (`TranslationOverlayView.planningMeasurer`):
 * same typeface (the bundled animeace forced to bold), same paint flags,
 * and same mutation-confined single-Paint pattern.
 *
 * Each [create] call owns a fresh Paint; callers must confine one instance to
 * one thread (Paint textSize mutation is not safe for concurrent use), the
 * same discipline as the overlay's planning executor.
 */
object ProductionTextMeasurer {

    fun create(typeface: Typeface): TextMeasurer = object : TextMeasurer {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
        }

        override fun measureTextWidth(text: String, fontSizePx: Float): Float {
            paint.textSize = fontSizePx
            return paint.measureText(text)
        }

        override fun lineHeight(fontSizePx: Float): Float {
            paint.textSize = fontSizePx
            val metrics = paint.fontMetrics
            return metrics.descent - metrics.ascent
        }
    }
}
