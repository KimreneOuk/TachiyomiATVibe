package eu.kanade.translation.ocr

import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PaddleOcrV6SmallEngineTest {

    private val engine = PaddleOcrV6SmallEngine()

    @Test
    fun `alignWidth buckets small inputs to 640`() {
        engine.alignWidth(1) shouldBe 640
        engine.alignWidth(100) shouldBe 640
        engine.alignWidth(320) shouldBe 640
        engine.alignWidth(500) shouldBe 640
        engine.alignWidth(640) shouldBe 640
    }

    @Test
    fun `alignWidth buckets large inputs exceeding 640 to 1600`() {
        engine.alignWidth(641) shouldBe 1600
        engine.alignWidth(800) shouldBe 1600
        engine.alignWidth(1200) shouldBe 1600
        engine.alignWidth(1600) shouldBe 1600
        engine.alignWidth(2000) shouldBe 1600
    }

    @Test
    fun `pad gray color normalizes to near zero for blank CTC tokens`() {
        // PAD_GRAY is 0xFF808080 (128 for R, G, B).
        PaddleOcrV6SmallEngine.PAD_GRAY shouldBe 0xFF808080.toInt()
        val normalized = engine.normalize(128)
        // (128 / 255.0f - 0.5f) / 0.5f = 0.0039215684
        abs(normalized) shouldBeLessThan 0.01f
    }

    @Test
    fun `normalize maps 0 to -1 and 255 to +1`() {
        engine.normalize(0) shouldBe -1.0f
        engine.normalize(255) shouldBe 1.0f
    }

    @Test
    fun `prefersHorizontalText is true for PP-OCRv6`() {
        engine.prefersHorizontalText shouldBe true
    }
}
