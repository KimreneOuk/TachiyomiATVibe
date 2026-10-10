package eu.kanade.translation.engines.inpainting.aot

import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** Tensor packing and output restoration for the single-input LaMa Manga model. */
internal object LamaMangaTensorContract : NeuralInpaintModelContract {
    private const val SIZE = AotPadPath.SIZE
    private const val PIXELS = SIZE * SIZE

    override val modelId: String = "lama-manga"
    override val isSingleInputTensor: Boolean = true
    override val inputTensorName: String = "input"

    fun inputShape(): LongArray = longArrayOf(1, 4, SIZE.toLong(), SIZE.toLong())

    fun outputShape(): LongArray = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())

    /** Writes planar RGB with masked pixels zeroed, followed by the binary mask channel. */
    fun writeInput(
        paddedImage: IntArray,
        paddedMask: IntArray,
        sourceSize: Int,
        inputBuffer: FloatBuffer,
    ): Int {
        require(sourceSize in 1..SIZE)
        require(paddedImage.size >= PIXELS && paddedMask.size >= PIXELS)
        require(inputBuffer.capacity() >= 4 * PIXELS)

        inputBuffer.clear()
        inputBuffer.limit(4 * PIXELS)
        for (index in 0 until PIXELS) {
            val pixel = paddedImage[index]
            val mask = if (AotPixelOps.maskValue(paddedMask[index]) > 0) 1.0f else 0.0f
            val keep = 1.0f - mask
            inputBuffer.put(index, ((pixel shr 16) and 0xFF) / 255.0f * keep)
            inputBuffer.put(PIXELS + index, ((pixel shr 8) and 0xFF) / 255.0f * keep)
            inputBuffer.put(2 * PIXELS + index, (pixel and 0xFF) / 255.0f * keep)
            inputBuffer.put(3 * PIXELS + index, mask)
        }
        return AotPadPath.centeredOffset(sourceSize)
    }

    /** Validates the model output tensor and restores the centered source crop. */
    fun decodeOutput(
        output: FloatBuffer,
        outputShape: LongArray,
        sourceSize: Int,
        offset: Int,
        grayscale: Boolean,
    ): IntArray {
        require(outputShape.contentEquals(longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))) {
            "LaMa Manga output shape changed: ${outputShape.contentToString()}"
        }
        require(sourceSize in 1..SIZE && offset == AotPadPath.centeredOffset(sourceSize))
        require(output.capacity() >= 3 * PIXELS)

        val candidate = IntArray(sourceSize * sourceSize)
        for (y in 0 until sourceSize) {
            for (x in 0 until sourceSize) {
                val outputIndex = (offset + y) * SIZE + offset + x
                val red = decodeChannel(output.get(outputIndex))
                val green = decodeChannel(output.get(PIXELS + outputIndex))
                val blue = decodeChannel(output.get(2 * PIXELS + outputIndex))
                candidate[y * sourceSize + x] = if (grayscale) {
                    val luma = (0.299f * red + 0.587f * green + 0.114f * blue).roundToInt().coerceIn(0, 255)
                    0xFF000000.toInt() or (luma shl 16) or (luma shl 8) or luma
                } else {
                    0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        return candidate
    }

    private fun decodeChannel(value: Float): Int = (value * 255.0f).roundToInt().coerceIn(0, 255)
}
