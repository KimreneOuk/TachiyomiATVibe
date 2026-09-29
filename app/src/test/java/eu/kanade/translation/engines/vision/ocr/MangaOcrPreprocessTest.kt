package eu.kanade.translation.engines.vision.ocr

import io.kotest.matchers.shouldBe
import io.mockk.mockk
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

    @Test
    fun `normalized pixels are written with offset for multi-crop batching`() {
        val crop0Pixels = IntArray(224 * 224) { 0xFFFFFFFF.toInt() }.also {
            it[0] = 0xFF000000.toInt()
        }
        val crop1Pixels = IntArray(224 * 224) { 0xFF000000.toInt() }.also {
            it[0] = 0xFFFFFFFF.toInt()
        }
        val batchBuffer = FloatBuffer.allocate(2 * 3 * 224 * 224)

        MangaOcrEngine.writeNormalizedChw(crop0Pixels, batchBuffer, offset = 0)
        MangaOcrEngine.writeNormalizedChw(crop1Pixels, batchBuffer, offset = 3 * 224 * 224)

        // Crop 0 checks
        batchBuffer.get(0) shouldBe -1f
        batchBuffer.get(1) shouldBe 1f
        batchBuffer.get(224 * 224) shouldBe -1f

        // Crop 1 checks (offset by 3 * 224 * 224)
        val crop1Offset = 3 * 224 * 224
        batchBuffer.get(crop1Offset + 0) shouldBe 1f
        batchBuffer.get(crop1Offset + 1) shouldBe -1f
        batchBuffer.get(crop1Offset + 224 * 224) shouldBe 1f
    }

    @Test
    fun `RoiOcrEngine default batch delegates to recognize`() {
        kotlinx.coroutines.runBlocking {
            val calls = mutableListOf<String>()
            val engine = object : RoiOcrEngine {
                override suspend fun recognize(crop: android.graphics.Bitmap): String {
                    val name = "crop_${calls.size}"
                    calls.add(name)
                    return name
                }

                override fun close() {}
            }

            val dummyBmp1 = mockk<android.graphics.Bitmap>(relaxed = true)
            val dummyBmp2 = mockk<android.graphics.Bitmap>(relaxed = true)

            val results = engine.recognizeBatch(listOf(dummyBmp1, dummyBmp2))
            results shouldBe listOf("crop_0", "crop_1")

            val resultsWithConf = engine.recognizeBatchWithConf(listOf(dummyBmp1))
            resultsWithConf shouldBe listOf("crop_2" to 1f)
        }
    }
}
