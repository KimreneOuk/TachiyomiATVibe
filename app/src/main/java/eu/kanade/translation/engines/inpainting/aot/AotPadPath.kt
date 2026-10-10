package eu.kanade.translation.engines.inpainting.aot

/**
 * Pixel-only math for the fixed-shape AOT path.
 *
 * The static model always receives a 512x512 tensor. Smaller square crops are
 * centered in that tensor and the unused area is filled with a sampled page
 * background. Keeping this independent from Bitmap/ORT makes the placement
 * contract testable without an Android device.
 */
internal object AotPadPath {

    const val SIZE = 512

    fun centeredOffset(sourceSize: Int): Int {
        require(sourceSize in 1..SIZE) { "sourceSize=$sourceSize must be in 1..$SIZE" }
        return (SIZE - sourceSize) / 2
    }

    fun padSquare(source: IntArray, sourceSize: Int, background: Int): IntArray {
        val padded = IntArray(SIZE * SIZE)
        padSquareInto(source, sourceSize, background, padded)
        return padded
    }

    /**
     * Pads the first [sourceSize] square pixels of [source] into the first
     * [SIZE] square pixels of [destination]. Any capacity after those bounded
     * regions is left untouched so pooled scratch arrays can be reused safely.
     */
    fun padSquareInto(
        source: IntArray,
        sourceSize: Int,
        background: Int,
        destination: IntArray,
    ) {
        val sourcePixelCount = checkedPixelCount(sourceSize)
        require(source.size >= sourcePixelCount) {
            "source length=${source.size} is smaller than ${sourceSize}x$sourceSize"
        }
        require(destination.size >= PADDED_PIXEL_COUNT) {
            "destination length=${destination.size} is smaller than ${SIZE}x$SIZE"
        }

        destination.fill(background, 0, PADDED_PIXEL_COUNT)
        val offset = centeredOffset(sourceSize)
        for (y in 0 until sourceSize) {
            source.copyInto(
                destination = destination,
                destinationOffset = (y + offset) * SIZE + offset,
                startIndex = y * sourceSize,
                endIndex = (y + 1) * sourceSize,
            )
        }
    }

    /**
     * Pads [sourceSize]² pixels into [destination] centered, extending the
     * crop's border pixels outward (clamp-to-edge). Only the centered source
     * square is decoded back from the model output.
     */
    fun padSquareReplicateInto(source: IntArray, sourceSize: Int, destination: IntArray) {
        val sourcePixelCount = checkedPixelCount(sourceSize)
        require(source.size >= sourcePixelCount) {
            "source length=${source.size} is smaller than ${sourceSize}x$sourceSize"
        }
        require(destination.size >= PADDED_PIXEL_COUNT) {
            "destination length=${destination.size} is smaller than ${SIZE}x$SIZE"
        }

        val offset = centeredOffset(sourceSize)
        val last = sourceSize - 1
        for (y in 0 until SIZE) {
            val sourceY = (y - offset).coerceIn(0, last)
            val sourceRow = sourceY * sourceSize
            val destinationRow = y * SIZE
            for (x in 0 until SIZE) {
                destination[destinationRow + x] = source[sourceRow + (x - offset).coerceIn(0, last)]
            }
        }
    }

    fun cropSquare(padded: IntArray, sourceSize: Int): IntArray {
        val source = IntArray(checkedPixelCount(sourceSize))
        cropSquareInto(padded, sourceSize, source)
        return source
    }

    /**
     * Crops the centered [sourceSize] square from the first [SIZE] square pixels
     * of [padded] into the first pixels of [destination]. Extra capacity in
     * either array is not read or written.
     */
    fun cropSquareInto(
        padded: IntArray,
        sourceSize: Int,
        destination: IntArray,
    ) {
        val sourcePixelCount = checkedPixelCount(sourceSize)
        require(padded.size >= PADDED_PIXEL_COUNT) {
            "padded length=${padded.size} is smaller than ${SIZE}x$SIZE"
        }
        require(destination.size >= sourcePixelCount) {
            "destination length=${destination.size} is smaller than ${sourceSize}x$sourceSize"
        }

        val offset = centeredOffset(sourceSize)
        for (y in 0 until sourceSize) {
            padded.copyInto(
                destination = destination,
                destinationOffset = y * sourceSize,
                startIndex = (y + offset) * SIZE + offset,
                endIndex = (y + offset) * SIZE + offset + sourceSize,
            )
        }
    }

    private fun checkedPixelCount(sourceSize: Int): Int {
        centeredOffset(sourceSize)
        return sourceSize * sourceSize
    }

    private const val PADDED_PIXEL_COUNT = SIZE * SIZE
}
