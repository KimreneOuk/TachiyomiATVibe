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
    fun `normalized pixels are written interleaved in NHWC order`() {
        val pixels = IntArray(224 * 224) { 0xFFFFFFFF.toInt() }
        pixels[0] = 0xFF000000.toInt()
        pixels[1] = 0xFFFF0000.toInt()
        val output = FloatArray(3 * 224 * 224)

        MangaOcrEngine.writeNormalizedNhwc(pixels, output)

        output[0] shouldBe -1f
        output[1] shouldBe -1f
        output[2] shouldBe -1f
        output[3] shouldBe 1f
        output[4] shouldBe -1f
        output[5] shouldBe -1f
        output[6] shouldBe 1f
        output[7] shouldBe 1f
        output[8] shouldBe 1f
    }

    @Test
    fun `hidden state is transposed from seq-major to dim-major layout`() {
        val hidden = FloatArray(196 * 256) { it.toFloat() }
        val destination = FloatArray(196 * 256)

        MangaOcrEngine.transposeHiddenToStepLayout(hidden, destination)

        // hidden[s, d] = s * 256 + d must land at destination[d, s] = d * 196 + s
        destination[0] shouldBe 0f
        destination[7 * 196 + 3] shouldBe (3 * 256 + 7).toFloat()
        destination[255 * 196 + 195] shouldBe (195 * 256 + 255).toFloat()
        destination[3 * 196 + 7] shouldBe (7 * 256 + 3).toFloat()
    }

    @Test
    fun `init self cache seeds slot 0 of every host cache slice`() {
        val seed = FloatArray(4 * 4 * 64) { it.toFloat() }
        val cache = FloatArray(4 * 1 * 4 * 256 * 64)

        MangaOcrEngine.seedSelfCacheSlot0(seed, cache)

        for (slice in intArrayOf(0, 5, 15)) {
            cache[slice * 256 * 64] shouldBe seed[slice * 64]
            cache[slice * 256 * 64 + 63] shouldBe seed[slice * 64 + 63]
        }
        cache[64] shouldBe 0f
        cache[5 * 256 * 64 + 64] shouldBe 0f
    }

    @Test
    fun `step self cache slice lands at requested slot`() {
        val slice = FloatArray(4 * 4 * 64) { it.toFloat() }
        val cache = FloatArray(4 * 1 * 4 * 256 * 64) { 1f }

        MangaOcrEngine.writeCacheSliceAtSlot(slice, cache, slot = 5)

        for (s in intArrayOf(0, 9, 15)) {
            cache[s * 256 * 64 + 5 * 64] shouldBe slice[s * 64]
            cache[s * 256 * 64 + 5 * 64 + 63] shouldBe slice[s * 64 + 63]
        }
        cache[319] shouldBe 1f
        cache[320 + 64] shouldBe 1f
    }

    @Test
    fun `cache slice rejects out-of-range slots`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            MangaOcrEngine.writeCacheSliceAtSlot(FloatArray(4 * 4 * 64), FloatArray(4 * 1 * 4 * 256 * 64), slot = 256)
        }
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
