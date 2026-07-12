package eu.kanade.translation.inpainting

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
        require(source.size == sourceSize * sourceSize) {
            "source length=${source.size} does not match ${sourceSize}x$sourceSize"
        }
        val padded = IntArray(SIZE * SIZE) { background }
        val offset = centeredOffset(sourceSize)
        for (y in 0 until sourceSize) {
            source.copyInto(
                destination = padded,
                destinationOffset = (y + offset) * SIZE + offset,
                startIndex = y * sourceSize,
                endIndex = (y + 1) * sourceSize,
            )
        }
        return padded
    }

    fun cropSquare(padded: IntArray, sourceSize: Int): IntArray {
        require(padded.size == SIZE * SIZE) {
            "padded length=${padded.size} does not match ${SIZE}x$SIZE"
        }
        val offset = centeredOffset(sourceSize)
        val source = IntArray(sourceSize * sourceSize)
        for (y in 0 until sourceSize) {
            padded.copyInto(
                destination = source,
                destinationOffset = y * sourceSize,
                startIndex = (y + offset) * SIZE + offset,
                endIndex = (y + offset) * SIZE + offset + sourceSize,
            )
        }
        return source
    }
}
