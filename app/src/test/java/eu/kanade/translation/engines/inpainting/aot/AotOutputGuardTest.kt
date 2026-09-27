package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Characterizes diagnostic statistics only; these values do not validate AOT quality or select fallback. */
class AotOutputGuardTest {

    @Test
    fun `diagnostic classifies a uniform masked mid-gray crop`() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(128, 128, 128) }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe true
    }

    @Test
    fun `diagnostic leaves a textured masked crop unclassified`() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            val value = 80 + (index % width) * 16
            argb(value, value, value)
        }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe false
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
