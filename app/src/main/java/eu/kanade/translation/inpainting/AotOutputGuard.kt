package eu.kanade.translation.inpainting

object AotOutputGuard {

    fun isSuspiciousGrayFill(
        inpaintedPixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
    ): Boolean {
        val n = minOf(inpaintedPixels.size, maskPixels.size, width * height)
        if (n <= 0) return false

        var count = 0
        var sumLuma = 0.0
        var sumLumaSquared = 0.0
        var sumChannelDelta = 0.0

        for (i in 0 until n) {
            if (maskValue(maskPixels[i]) <= MASK_THRESHOLD) continue
            val px = inpaintedPixels[i]
            val r = px shr 16 and 0xFF
            val g = px shr 8 and 0xFF
            val b = px and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            sumLuma += luma
            sumLumaSquared += luma * luma
            sumChannelDelta += kotlin.math.abs(r - g) + kotlin.math.abs(g - b)
            count++
        }

        if (count < MIN_MASKED_PIXELS) return false
        val mean = sumLuma / count
        if (mean < MID_GRAY_MIN || mean > MID_GRAY_MAX) return false

        val variance = (sumLumaSquared / count) - (mean * mean)
        val channelDelta = sumChannelDelta / count
        return variance < MAX_LUMA_VARIANCE && channelDelta < MAX_CHANNEL_DELTA
    }

    private fun maskValue(pixel: Int): Int = maxOf(pixel and 0xFF, pixel ushr 24)

    private const val MASK_THRESHOLD = 127
    private const val MIN_MASKED_PIXELS = 16
    private const val MID_GRAY_MIN = 96.0
    private const val MID_GRAY_MAX = 160.0
    private const val MAX_LUMA_VARIANCE = 36.0
    private const val MAX_CHANNEL_DELTA = 8.0
}
