package eu.kanade.translation.engines.coloring

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow

/**
 * Kotlin port of sana-manga-coloring-v2's merge_lines: the model was trained
 * on Gaussian-blurred Lab lightness, so inference keeps the model's colour +
 * coarse lightness and re-imposes the B/W page's high-frequency lightness
 * (line art, text) scaled by [ink]: L' = L_color + ink * (L_bw - blur(L_bw,
 * sigma)), clamped to [0, 100]. Colour channels (a, b) pass through
 * untouched. Mirrors kornia sRGB<->Lab (D65) and gaussian_blur2d (normalized
 * kernel, reflect padding). Lab vectors pinned against scikit-image in
 * LineArtMergerTest.
 */
object LineArtMerger {

    /**
     * [coloredRgb] and [bwRgb] are row-major RGB in [0,1], 3 floats per pixel.
     * Returns the merged RGB in [0,1].
     */
    fun merge(
        coloredRgb: FloatArray,
        bwRgb: FloatArray,
        width: Int,
        height: Int,
        sigma: Float,
        ink: Float,
    ): FloatArray {
        require(coloredRgb.size == bwRgb.size) { "colored/BW buffer size mismatch" }
        require(width > 0 && height > 0) { "invalid merge dimensions" }
        val coloredLab = rgbToLab(coloredRgb)
        val bwL = labLightnessOf(bwRgb)
        val detail = FloatArray(width * height)
        gaussianBlur(bwL, width, height, sigma, detail)
        for (i in detail.indices) {
            detail[i] = ink * (bwL[i] - detail[i])
        }
        val mergedLab = FloatArray(coloredLab.size)
        System.arraycopy(coloredLab, 0, mergedLab, 0, coloredLab.size)
        for (i in 0 until width * height) {
            mergedLab[i * 3] = (coloredLab[i * 3] + detail[i]).coerceIn(0f, 100f)
        }
        return labToRgb(mergedLab)
    }

    /** kornia rgb_to_lab: sRGB D65 linearization -> XYZ -> CIELAB. */
    internal fun rgbToLab(rgb: FloatArray): FloatArray {
        val lab = FloatArray(rgb.size)
        for (p in 0 until rgb.size / 3) {
            val x = srgbToXyz(rgb[p * 3], rgb[p * 3 + 1], rgb[p * 3 + 2])
            lab[p * 3] = 116f * fRatio(x[1] / WHITE_Y) - 16f
            lab[p * 3 + 1] = 500f * (fRatio(x[0] / WHITE_X) - fRatio(x[1] / WHITE_Y))
            lab[p * 3 + 2] = 200f * (fRatio(x[1] / WHITE_Y) - fRatio(x[2] / WHITE_Z))
        }
        return lab
    }

    internal fun labToRgb(lab: FloatArray): FloatArray {
        val rgb = FloatArray(lab.size)
        for (p in 0 until lab.size / 3) {
            val fy = (lab[p * 3] + 16f) / 116f
            val fx = fy + lab[p * 3 + 1] / 500f
            val fz = fy - lab[p * 3 + 2] / 200f
            val x = WHITE_X * fInvRatio(fx)
            val y = WHITE_Y * fInvRatio(fy)
            val z = WHITE_Z * fInvRatio(fz)
            rgb[p * 3] = xyzToSrgbComponent(XYZ_TO_SRGB_R * x + XYZ_TO_SRGB_G2 * y + XYZ_TO_SRGB_B2 * z)
            rgb[p * 3 + 1] = xyzToSrgbComponent(XYZ_TO_SRGB_R2 * x + XYZ_TO_SRGB_G * y + XYZ_TO_SRGB_B * z)
            rgb[p * 3 + 2] = xyzToSrgbComponent(XYZ_TO_SRGB_R3 * x + XYZ_TO_SRGB_G3 * y + XYZ_TO_SRGB_B3 * z)
        }
        return rgb
    }

    private fun labLightnessOf(rgb: FloatArray): FloatArray {
        val lightness = FloatArray(rgb.size / 3)
        for (p in lightness.indices) {
            val x = srgbToXyz(rgb[p * 3], rgb[p * 3 + 1], rgb[p * 3 + 2])
            lightness[p] = 116f * fRatio(x[1] / WHITE_Y) - 16f
        }
        return lightness
    }

    // CIE condition tests the XYZ ratio itself, not its cube; testing the cube
    // here misclassifies mid-range ratios and scrambles a/b (kornia CIE_E).
    private fun fRatio(value: Float): Float {
        return if (value > LAB_EPSILON) value.pow(1f / 3f) else (LAB_KAPPA * value + 16f) / 116f
    }

    private fun fInvRatio(value: Float): Float {
        val cube = value * value * value
        return if (cube > LAB_EPSILON_CUBE) cube else (value * 116f - 16f) / LAB_KAPPA
    }

    private fun srgbToXyz(r: Float, g: Float, b: Float): FloatArray {
        val lr = srgbLinearize(r)
        val lg = srgbLinearize(g)
        val lb = srgbLinearize(b)
        return floatArrayOf(
            SRGB_TO_XYZ_X * lr + SRGB_TO_XYZ_X2 * lg + SRGB_TO_XYZ_X3 * lb,
            SRGB_TO_XYZ_Y * lr + SRGB_TO_XYZ_Y2 * lg + SRGB_TO_XYZ_Y3 * lb,
            SRGB_TO_XYZ_Z * lr + SRGB_TO_XYZ_Z2 * lg + SRGB_TO_XYZ_Z3 * lb,
        )
    }

    private fun srgbLinearize(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c > SRGB_THRESHOLD) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f
    }

    private fun xyzToSrgbComponent(linear: Float): Float {
        val c = linear.coerceIn(0f, 1f)
        val encoded = if (c > SRGB_LINEAR_THRESHOLD) 1.055f * c.pow(1f / 2.4f) - 0.055f else 12.92f * c
        return encoded.coerceIn(0f, 1f)
    }

    /**
     * Separable Gaussian (kernel size 2*ceil(3*sigma)+1, normalized,
     * reflect-padded) matching kornia gaussian_blur2d; result written to [out].
     */
    private fun gaussianBlur(input: FloatArray, width: Int, height: Int, sigma: Float, out: FloatArray) {
        require(sigma > 0f) { "blur sigma must be positive" }
        val radius = ceil(3.0 * sigma).toInt()
        val size = 2 * radius + 1
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in kernel.indices) {
            val offset = (i - radius).toFloat()
            kernel[i] = exp(-(offset * offset) / (2f * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum

        val horizontal = FloatArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in kernel.indices) {
                    acc += kernel[k] * input[y * width + reflect(x + k - radius, width)]
                }
                horizontal[y * width + x] = acc
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in kernel.indices) {
                    acc += kernel[k] * horizontal[reflect(y + k - radius, height) * width + x]
                }
                out[y * width + x] = acc
            }
        }
    }

    private fun reflect(index: Int, size: Int): Int {
        if (size == 1) return 0
        val period = 2 * size - 2
        var value = index % period
        if (value < 0) value += period
        return if (value >= size) period - value else value
    }

    // D65 reference white and the sRGB<->XYZ matrices (IEC 61966-2-1).
    private const val WHITE_X = 0.95047f
    private const val WHITE_Y = 1f
    private const val WHITE_Z = 1.08883f
    private const val LAB_KAPPA = 903.3f
    private const val LAB_EPSILON = 0.008856f
    private const val LAB_EPSILON_CUBE = 0.008856f
    private const val SRGB_THRESHOLD = 0.04045f
    private const val SRGB_LINEAR_THRESHOLD = 0.0031308f

    private const val SRGB_TO_XYZ_X = 0.412453f
    private const val SRGB_TO_XYZ_X2 = 0.357580f
    private const val SRGB_TO_XYZ_X3 = 0.180423f
    private const val SRGB_TO_XYZ_Y = 0.212671f
    private const val SRGB_TO_XYZ_Y2 = 0.715160f
    private const val SRGB_TO_XYZ_Y3 = 0.072169f
    private const val SRGB_TO_XYZ_Z = 0.019334f
    private const val SRGB_TO_XYZ_Z2 = 0.119193f
    private const val SRGB_TO_XYZ_Z3 = 0.950227f

    private const val XYZ_TO_SRGB_R = 3.2404542f
    private const val XYZ_TO_SRGB_G2 = -1.5371385f
    private const val XYZ_TO_SRGB_B2 = -0.4985314f
    private const val XYZ_TO_SRGB_R2 = -0.9692660f
    private const val XYZ_TO_SRGB_G = 1.8760108f
    private const val XYZ_TO_SRGB_B = 0.0415560f
    private const val XYZ_TO_SRGB_R3 = 0.0556434f
    private const val XYZ_TO_SRGB_G3 = -0.2040259f
    private const val XYZ_TO_SRGB_B3 = 1.0572252f
}
