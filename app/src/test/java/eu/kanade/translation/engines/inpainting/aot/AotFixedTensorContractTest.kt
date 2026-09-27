package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

class AotFixedTensorContractTest {

    @Test
    fun `fixed input tensors run at 512 and decode back to source crop dimensions`() {
        val sourceSize = 3
        val source = IntArray(sourceSize * sourceSize) { index -> argb(20 + index, 40 + index, 60 + index) }
        val sourceMask = IntArray(sourceSize * sourceSize) { index -> if (index == 4) 0xFFFFFFFF.toInt() else 0 }
        val imageTensor = FloatBuffer.allocate(CHANNEL_PIXELS * 3)
        val maskTensor = FloatBuffer.allocate(CHANNEL_PIXELS)
        val imageShape = AotFixedTensorContract.imageShape()
        val maskShape = AotFixedTensorContract.maskShape()
        val imagePadded = AotPadPath.padSquare(source, sourceSize, BACKGROUND)
        val maskPadded = AotPadPath.padSquare(sourceMask, sourceSize, 0)
        val offset = AotFixedTensorContract.writeInputs(
            paddedImage = imagePadded,
            paddedMask = maskPadded,
            sourceSize = sourceSize,
            imageBuffer = imageTensor,
            maskBuffer = maskTensor,
        )
        val fakeSession = FakeFixedSession()

        val output = fakeSession.run(
            image = FakeTensor(imageTensor, imageShape),
            mask = FakeTensor(maskTensor, maskShape),
        )
        val restored = AotFixedTensorContract.decodeOutput(
            output = output.values,
            outputShape = output.shape,
            sourceSize = sourceSize,
            offset = offset,
            grayscale = false,
        )

        fakeSession.seenImageShape shouldBe longArrayOf(1, 3, 512, 512).toList()
        fakeSession.seenMaskShape shouldBe longArrayOf(1, 1, 512, 512).toList()
        fakeSession.seenImageRed shouldBe AotPixelOps.encodeFixedImageChannel(24)
        fakeSession.seenMaskedPixel shouldBe 1.0f
        restored.size shouldBe sourceSize * sourceSize
        restored.toList() shouldBe List(sourceSize * sourceSize) { argb(64, 128, 230) }
    }

    private data class FakeTensor(val values: FloatBuffer, val shape: LongArray)

    private class FakeFixedSession {
        var seenImageShape: List<Long> = emptyList()
        var seenMaskShape: List<Long> = emptyList()
        var seenImageRed: Float = Float.NaN
        var seenMaskedPixel: Float = Float.NaN

        fun run(image: FakeTensor, mask: FakeTensor): FakeTensor {
            seenImageShape = image.shape.toList()
            seenMaskShape = mask.shape.toList()
            val offset = AotPadPath.centeredOffset(sourceSize = 3)
            val centerIndex = (offset + 1) * AotPadPath.SIZE + offset + 1
            seenImageRed = image.values.get(centerIndex)
            seenMaskedPixel = mask.values.get(centerIndex)
            val output = FloatBuffer.allocate(CHANNEL_PIXELS * 3)
            for (index in 0 until CHANNEL_PIXELS) {
                output.put(index, 0.25f)
                output.put(CHANNEL_PIXELS + index, 0.5f)
                output.put(2 * CHANNEL_PIXELS + index, 0.9f)
            }
            return FakeTensor(output, AotFixedTensorContract.imageShape())
        }
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private companion object {
        const val CHANNEL_PIXELS = AotPadPath.SIZE * AotPadPath.SIZE
        val BACKGROUND = 0xFFB0A090.toInt()
    }
}
