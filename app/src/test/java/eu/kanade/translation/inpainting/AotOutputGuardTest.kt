package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotOutputGuardTest {

    @Test
    fun `uniform mid gray masked output is rejected`() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(128, 128, 128) }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe true
    }

    @Test
    fun `varied grayscale masked output is accepted`() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            val value = 80 + (index % width) * 16
            argb(value, value, value)
        }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe false
    }

    @Test
    fun `uniform colored output is accepted`() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(140, 112, 96) }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe false
    }

    @Test
    fun `empty mask is accepted`() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(128, 128, 128) }
        val mask = IntArray(width * height)

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe false
    }

    @Test
    fun `uniform near white masked output is rejected`() {
        // The neural white-block artefact: a flat white patch where text was.
        // Previously this slipped past the mid-gray-only guard.
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(250, 250, 250) }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe true
    }

    @Test
    fun `faintly textured white paper is not rejected`() {
        // Genuine manga paper with faint screentone has real variance and must
        // NOT be flagged (would be a false-positive fallback). Mean is high
        // (near white) but variance exceeds the uniform threshold.
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            // Alternating ~240/~255 gives variance well above MAX_LUMA_VARIANCE.
            val value = if (index % 2 == 0) 240 else 255
            argb(value, value, value)
        }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe false
    }

    @Test
    fun `uniform near black masked output is rejected`() {
        // The neural black-block artefact: an oversized/long mask (box wider or
        // taller than NEURAL_CROP_MAX) collapses cropMargin to 0, AOT sees ~no
        // context and returns ~0, and featherBlend paints it at alpha 1.0 — the
        // "entire bounding box black" symptom. Previously this slipped past both
        // the mid-gray and near-white checks (mean ~0 is neither).
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(0, 0, 0) }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe true
    }

    @Test
    fun `uniform dark gray below mid gray threshold is rejected`() {
        // Pins the NEAR_BLACK_MAX=24 upper boundary of the near-black band:
        // a uniform luma-20 fill is dark enough to be a black-collapse artefact,
        // not a legitimate reconstruction.
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(20, 20, 20) }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe true
    }

    @Test
    fun `dark but textured output is accepted`() {
        // Guards against over-rejection: a genuinely reconstructed DARK region
        // (shaded panel, dark background behind erased text) has real variance
        // even though its mean is near 0. The uniformity check must let it pass.
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            // Step 0..28 across the 8-wide rows → variance well above
            // MAX_LUMA_VARIANCE (9.0), so it is NOT uniform despite being dark.
            val value = (index % width) * 4
            argb(value, value, value)
        }
        val mask = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        AotOutputGuard.isSuspiciousUniformFill(pixels, mask, width, height) shouldBe false
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
