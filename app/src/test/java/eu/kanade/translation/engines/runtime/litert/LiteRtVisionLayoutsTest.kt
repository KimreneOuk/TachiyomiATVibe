package eu.kanade.translation.engines.runtime.litert

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LiteRtVisionLayoutsTest {

    @Test
    fun writeNhwcPixelInterleavesRgbChannels() {
        val dst = FloatArray(6)
        LiteRtVisionLayouts.writeNhwcPixel(0xFF_80_40_20.toInt(), dst, 1)
        dst[0] shouldBe 0f
        dst[1] shouldBe 0f
        dst[2] shouldBe 0f
        dst[3] shouldBe 0x80 / 255f
        dst[4] shouldBe 0x40 / 255f
        dst[5] shouldBe 0x20 / 255f
    }

    @Test
    fun writeNhwcPixelNormalizesToUnitRange() {
        val dst = FloatArray(3)
        LiteRtVisionLayouts.writeNhwcPixel(0xFF_FF_FF_FF.toInt(), dst, 0)
        dst shouldBe floatArrayOf(1f, 1f, 1f)
    }

    @Test
    fun chwToNhwcTransposesPlaneMajorSource() {
        // plane = 2 pixels; channels plane-major: R0 R1 G0 G1 B0 B1.
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val dst = FloatArray(6)
        LiteRtVisionLayouts.chwToNhwc(src, dst, 2)
        dst shouldBe floatArrayOf(1f, 3f, 5f, 2f, 4f, 6f)
    }
}
