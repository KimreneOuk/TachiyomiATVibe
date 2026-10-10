package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

class LamaMangaTensorContractTest {

    @Test
    fun `single input contains masked RGB zeroed and a binary mask channel`() {
        val size = AotPadPath.SIZE
        val image = IntArray(size * size) { 0xFF808080.toInt() }
        val mask = IntArray(size * size) { if (it < 10) 255 else 0 }
        val buffer = FloatBuffer.allocate(4 * size * size)

        LamaMangaTensorContract.writeInput(image, mask, size, buffer)

        val channelPixels = size * size
        buffer.get(0) shouldBe 0.0f
        buffer.get(channelPixels) shouldBe 0.0f
        buffer.get(2 * channelPixels) shouldBe 0.0f
        buffer.get(3 * channelPixels) shouldBe 1.0f

        val unmaskedIndex = 10
        val expectedValue = 128f / 255f
        buffer.get(unmaskedIndex) shouldBe expectedValue
        buffer.get(channelPixels + unmaskedIndex) shouldBe expectedValue
        buffer.get(2 * channelPixels + unmaskedIndex) shouldBe expectedValue
        buffer.get(3 * channelPixels + unmaskedIndex) shouldBe 0.0f
    }

    @Test
    fun `decoding white RGB output produces opaque white pixels`() {
        val size = AotPadPath.SIZE
        val buffer = FloatBuffer.allocate(3 * size * size)
        repeat(buffer.capacity()) { buffer.put(it, 1.0f) }

        val decoded = LamaMangaTensorContract.decodeOutput(
            output = buffer,
            outputShape = longArrayOf(1, 3, size.toLong(), size.toLong()),
            sourceSize = size,
            offset = 0,
            grayscale = false,
        )

        decoded[0] shouldBe -1
    }
}
