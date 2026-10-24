package eu.kanade.translation.engines.inpainting.litert

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The degenerate-output rule behind GPU→CPU recovery: a delegate that no-ops
 * leaves the untouched zero tensor, which would composite as flat 0.5 gray.
 */
class LiteRTMangaInpaintingEngineDegenerateOutputTest {

    @Test
    fun `an untouched zero tensor is degenerate`() {
        LiteRTMangaInpaintingEngine.isDegenerateOutput(FloatArray(512 * 512 * 3)) shouldBe true
    }

    @Test
    fun `any nonzero sample marks the pass healthy`() {
        val healthy = FloatArray(512 * 512 * 3)
        healthy[512 * 512 * 3 - 1] = -1f
        LiteRTMangaInpaintingEngine.isDegenerateOutput(healthy) shouldBe false
    }

    @Test
    fun `an empty output counts as degenerate`() {
        LiteRTMangaInpaintingEngine.isDegenerateOutput(FloatArray(0)) shouldBe true
    }
}
