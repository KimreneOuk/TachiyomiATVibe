package eu.kanade.translation.engines.rendering

import android.graphics.Paint
import android.graphics.Typeface

/**
 * Production [TextMeasurer] used by batch
 * LAYOUT_PREPARE publication. Its measurement must be IDENTICAL to the
 * overlay's planning measurer (`TranslationOverlayView.planningMeasurer`):
 * same typeface (the bundled animeace forced to bold — see
 * [DrawPlanFingerprint.TYPEFACE_STYLE]), same paint flags
 * ([DrawPlanFingerprint.PAINT_MEASUREMENT_FLAGS]), same mutation-confined
 * single-Paint pattern. Any drift here changes produced geometry, which is
 * exactly what the persisted-layout compatibility fingerprint
 * is supposed to catch — so both sides construct this object over the same
 * typeface and flags, and the constants in [DrawPlanFingerprint] pin the
 * identity.
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
