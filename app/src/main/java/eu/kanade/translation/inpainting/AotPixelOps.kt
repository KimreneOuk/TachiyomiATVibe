package eu.kanade.translation.inpainting

import kotlin.math.max
import kotlin.math.roundToInt

internal object AotPixelOps {

    internal fun blendPixel(original: Int, inpainted: Int, alpha: Float): Int {
        val inv = 1.0f - alpha
        val r = ((original shr 16 and 0xFF) * inv + (inpainted shr 16 and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        val g = ((original shr 8 and 0xFF) * inv + (inpainted shr 8 and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        val b = ((original and 0xFF) * inv + (inpainted and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    internal fun maskValue(pixel: Int): Int = max(pixel and 0xFF, pixel ushr 24)

    internal fun histogramMedian(hist: IntArray, count: Int): Int {
        val half = count / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc > half) return v
        }
        return 255
    }

    internal fun avg4(a: Int, b: Int, c: Int, d: Int): Int {
        val r = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (c shr 16 and 0xFF) + (d shr 16 and 0xFF) + 2) shr 2
        val g = ((a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (c shr 8 and 0xFF) + (d shr 8 and 0xFF) + 2) shr 2
        val bb = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF) + 2) shr 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
    }
}
