package eu.kanade.translation.inpainting

object AotOutputGuard {

    /**
     * Statistics over the masked-region pixels of an AOT inpaint candidate.
     * Computed once by [inspect]; [isSuspiciousUniformFill] applies the verdict
     * via [classify], and the caller (AOTInpainting) logs the values so a neural
     * failure mode is diagnosable from logcat without a rebuild.
     */
    data class GuardStats(
        val mean: Double,
        val variance: Double,
        val channelDelta: Double,
        val maskedCount: Int,
        val unmaskedMean: Double = 0.0,
        val unmaskedCount: Int = 0,
    )

    fun isSuspiciousUniformFill(
        inpaintedPixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
    ): Boolean = classify(inspect(inpaintedPixels, maskPixels, width, height))

    /**
     * Returns the masked-region statistics without applying a verdict, for
     * diagnostic logging. Exposed publicly so the caller can log mean/variance/
     * channelDelta alongside the rejection decision instead of recomputing them.
     */
    fun inspect(
        inpaintedPixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
    ): GuardStats {
        val n = minOf(inpaintedPixels.size, maskPixels.size, width * height)
        if (n <= 0) return EMPTY_STATS

        var count = 0
        var sumLuma = 0.0
        var sumLumaSquared = 0.0
        var sumChannelDelta = 0.0

        var unmaskedCount = 0
        var sumUnmaskedLuma = 0.0

        for (i in 0 until n) {
            val isMasked = maskValue(maskPixels[i]) > MASK_THRESHOLD
            val px = inpaintedPixels[i]
            val r = px shr 16 and 0xFF
            val g = px shr 8 and 0xFF
            val b = px and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            if (isMasked) {
                sumLuma += luma
                sumLumaSquared += luma * luma
                sumChannelDelta += kotlin.math.abs(r - g) + kotlin.math.abs(g - b)
                count++
            } else {
                sumUnmaskedLuma += luma
                unmaskedCount++
            }
        }

        if (count < MIN_MASKED_PIXELS) return GuardStats(0.0, 0.0, 0.0, count)
        val mean = sumLuma / count
        val variance = (sumLumaSquared / count) - (mean * mean)
        val channelDelta = sumChannelDelta / count
        val unmaskedMean = if (unmaskedCount > 0) sumUnmaskedLuma / unmaskedCount else 0.0
        return GuardStats(mean, variance, channelDelta, count, unmaskedMean, unmaskedCount)
    }

    /**
     * Applies the rejection verdict to stats produced by [inspect]. A genuinely
     * reconstructed region is never a flat uniform block when the surrounding area
     * is textured/contrasting.
     *
     * However, inside a legitimate white speech bubble (where unmasked surroundings are white),
     * a clean white inpaint is expected and valid.
     */
    fun classify(stats: GuardStats): Boolean {
        if (stats.maskedCount < MIN_MASKED_PIXELS) return false
        val uniform = stats.variance < MAX_LUMA_VARIANCE && stats.channelDelta < MAX_CHANNEL_DELTA
        if (!uniform) return false

        // If surrounding unmasked pixels are already light/white (e.g. speech bubble interior),
        // outputting near-white is the ground truth, not a flat-patch artifact.
        val unmaskedIsWhite = stats.unmaskedCount > 0 && stats.unmaskedMean >= 200.0
        val uniformNearWhite = stats.mean >= NEAR_WHITE_MIN && !unmaskedIsWhite

        // If surrounding unmasked pixels are already black, outputting near-black is consistent.
        val unmaskedIsBlack = stats.unmaskedCount > 0 && stats.unmaskedMean <= 40.0
        val uniformNearBlack = stats.mean <= NEAR_BLACK_MAX && !unmaskedIsBlack

        val uniformMidGray = stats.mean in MID_GRAY_MIN..MID_GRAY_MAX
        return uniformNearBlack || uniformMidGray || uniformNearWhite
    }

    private fun maskValue(pixel: Int): Int = maxOf(pixel and 0xFF, pixel ushr 24)

    private val EMPTY_STATS = GuardStats(0.0, 0.0, 0.0, 0)

    private const val MASK_THRESHOLD = 127
    private const val MIN_MASKED_PIXELS = 16
    private const val NEAR_BLACK_MAX = 24.0
    private const val MID_GRAY_MIN = 96.0
    private const val MID_GRAY_MAX = 160.0
    private const val NEAR_WHITE_MIN = 238.0
    private const val MAX_LUMA_VARIANCE = 9.0
    private const val MAX_CHANNEL_DELTA = 8.0
}
