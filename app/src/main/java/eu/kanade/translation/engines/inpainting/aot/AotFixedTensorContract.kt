package eu.kanade.translation.engines.inpainting.aot

import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** Pure tensor preparation and output restoration for the fixed 512 AOT model. */
internal object AotFixedTensorContract : NeuralInpaintModelContract {
    private const val SIZE = AotPadPath.SIZE
    private const val PIXELS = SIZE * SIZE

    override val modelId: String = "aot-512"
    override val isSingleInputTensor: Boolean = false
    override val inputTensorName: String = "image"

    fun imageShape(): LongArray = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())

    fun maskShape(): LongArray = longArrayOf(1, 1, SIZE.toLong(), SIZE.toLong())

    /** Writes planar RGB [0,1] and binary mask values into caller-owned buffers. */
    fun writeInputs(
        paddedImage: IntArray,
        paddedMask: IntArray,
        sourceSize: Int,
        imageBuffer: FloatBuffer,
        maskBuffer: FloatBuffer,
    ): Int {
        require(sourceSize in 1..SIZE)
        require(paddedImage.size >= PIXELS && paddedMask.size >= PIXELS)
        require(imageBuffer.capacity() >= 3 * PIXELS && maskBuffer.capacity() >= PIXELS)

        imageBuffer.clear()
        maskBuffer.clear()
        imageBuffer.limit(3 * PIXELS)
        maskBuffer.limit(PIXELS)
        for (index in 0 until PIXELS) {
            val pixel = paddedImage[index]
            maskBuffer.put(index, if (AotPixelOps.maskValue(paddedMask[index]) > 127) 1.0f else 0.0f)
            imageBuffer.put(index, AotPixelOps.encodeFixedImageChannel(pixel shr 16 and 0xFF))
            imageBuffer.put(PIXELS + index, AotPixelOps.encodeFixedImageChannel(pixel shr 8 and 0xFF))
            imageBuffer.put(2 * PIXELS + index, AotPixelOps.encodeFixedImageChannel(pixel and 0xFF))
        }
        return AotPadPath.centeredOffset(sourceSize)
    }

    /** Validates the model output tensor and restores its centered source crop. */
    fun decodeOutput(
        output: FloatBuffer,
        outputShape: LongArray,
        sourceSize: Int,
        offset: Int,
        grayscale: Boolean,
    ): IntArray {
        require(outputShape.contentEquals(imageShape())) {
            "fixed AOT output shape changed: ${outputShape.contentToString()}"
        }
        require(sourceSize in 1..SIZE && offset == AotPadPath.centeredOffset(sourceSize))
        require(output.capacity() >= 3 * PIXELS)

        val candidate = IntArray(sourceSize * sourceSize)
        for (y in 0 until sourceSize) {
            for (x in 0 until sourceSize) {
                val outputIndex = (offset + y) * SIZE + offset + x
                val red = AotPixelOps.decodeFixedChannel(output.get(outputIndex))
                val green = AotPixelOps.decodeFixedChannel(output.get(PIXELS + outputIndex))
                val blue = AotPixelOps.decodeFixedChannel(output.get(2 * PIXELS + outputIndex))
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
}
