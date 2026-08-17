package eu.kanade.translation.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

class MangaOcrPreprocessTest {

    @Test
    fun `normalized pixels are written in CHW order`() {
        val pixels = IntArray(224 * 224) { 0xFFFFFFFF.toInt() }
        pixels[0] = 0xFF000000.toInt()
        pixels[1] = 0xFFFF0000.toInt()
        val output = FloatBuffer.allocate(3 * 224 * 224)

        MangaOcrEngine.writeNormalizedChw(pixels, output)

        output.get(0) shouldBe -1f
        output.get(1) shouldBe 1f
        output.get(224 * 224) shouldBe -1f
        output.get(2 * 224 * 224) shouldBe -1f
        output.get(2 * 224 * 224 + 1) shouldBe -1f
    }
}
