package eu.kanade.translation.engines.runtime.litert

/** Pure layout math shared by the LiteRT vision engines (NHWC exports). */
internal object LiteRtVisionLayouts {

    /** Writes one ARGB pixel as interleaved NHWC RGB (0..1) at [pixelIndex]. */
    fun writeNhwcPixel(pixel: Int, dst: FloatArray, pixelIndex: Int) {
        dst[3 * pixelIndex] = (pixel shr 16 and 0xFF) / 255f
        dst[3 * pixelIndex + 1] = (pixel shr 8 and 0xFF) / 255f
        dst[3 * pixelIndex + 2] = (pixel and 0xFF) / 255f
    }

    /** Transposes a plane-major CHW [3, plane] source into interleaved NHWC. */
    fun chwToNhwc(src: FloatArray, dst: FloatArray, plane: Int) {
        for (i in 0 until plane) {
            dst[3 * i] = src[i]
            dst[3 * i + 1] = src[plane + i]
            dst[3 * i + 2] = src[2 * plane + i]
        }
    }
}
