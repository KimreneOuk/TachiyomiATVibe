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

        if (count < MIN_MASKED_PIXELS) return GuardStats(0.0, 0.0, 0.0, count)
        val mean = sumLuma / count
        val variance = (sumLumaSquared / count) - (mean * mean)
        val channelDelta = sumChannelDelta / count
        return GuardStats(mean, variance, channelDelta, count)
    }

    /**
     * Applies the rejection verdict to stats produced by [inspect]. A genuinely
     * reconstructed region is never a perfectly uniform block, so a uniform
     * (low-variance, low-chroma) fill is treated as a neural failure and routed
     * to the cleaner fallback by the caller.
     *
     * Three documented AOT failure modes all present as uniform fills:
     *  - uniform NEAR-BLACK (mean <= [NEAR_BLACK_MAX]): the large/oversized-mask
     *    collapse, where the model has too little surrounding context (cropMargin
     *    collapses to 0 for boxes wider/taller than [BubbleMaskBuilder.NEURAL_CROP_MAX])
     *    and returns ~0. This is the "entire bounding box black" symptom on
     *    long/large boxes (~70% page width/height).
     *  - uniform MID-gray (mean in [MID_GRAY_MIN]..[MID_GRAY_MAX]): the classic
     *    NPU/dynamic-shape failure mode.
     *  - uniform NEAR-WHITE (mean >= [NEAR_WHITE_MIN]): the flat-white-patch
     *    artefact where text was.
     *
     * [NEAR_BLACK_MAX] is a small ceiling (not a floor) because pure black is
     * luma 0. The uniformity check (variance/channelDelta below their thresholds)
     * still protects legitimately dark-but-textured regions — dark panels,
     * screentone, shaded backgrounds have real variance and pass even when their
     * mean is near 0.
     */
    fun classify(stats: GuardStats): Boolean {
        if (stats.maskedCount < MIN_MASKED_PIXELS) return false
        val uniform = stats.variance < MAX_LUMA_VARIANCE && stats.channelDelta < MAX_CHANNEL_DELTA
        if (!uniform) return false
        val uniformNearBlack = stats.mean <= NEAR_BLACK_MAX
        val uniformMidGray = stats.mean in MID_GRAY_MIN..MID_GRAY_MAX
        val uniformNearWhite = stats.mean >= NEAR_WHITE_MIN
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
