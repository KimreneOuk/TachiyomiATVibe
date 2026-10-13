package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InpaintPadOpsTest {

    @Test
    fun `supported sides use the same centered offset for image and mask`() {
        for (sourceSize in SUPPORTED_SIDES) {
            val expectedOffset = (InpaintPadOps.SIZE - sourceSize) / 2
            val image = IntArray(sourceSize * sourceSize) { IMAGE_PIXEL }
            val mask = IntArray(sourceSize * sourceSize) { MASK_PIXEL }
            val paddedImage = IntArray(PADDED_PIXELS)
            val paddedMask = IntArray(PADDED_PIXELS)

            InpaintPadOps.padSquareInto(image, sourceSize, BACKGROUND, paddedImage)
            InpaintPadOps.padSquareInto(mask, sourceSize, 0, paddedMask)

            InpaintPadOps.centeredOffset(sourceSize) shouldBe expectedOffset
            paddedImage[expectedOffset * InpaintPadOps.SIZE + expectedOffset] shouldBe IMAGE_PIXEL
            paddedMask[expectedOffset * InpaintPadOps.SIZE + expectedOffset] shouldBe MASK_PIXEL
        }
    }

    @Test
    fun `image border is background and mask border is zero`() {
        for (sourceSize in SUPPORTED_SIDES.filter { it < InpaintPadOps.SIZE }) {
            val image = IntArray(sourceSize * sourceSize) { IMAGE_PIXEL }
            val mask = IntArray(sourceSize * sourceSize) { MASK_PIXEL }
            val paddedImage = IntArray(PADDED_PIXELS) { GUARD_PIXEL }
            val paddedMask = IntArray(PADDED_PIXELS) { GUARD_PIXEL }

            InpaintPadOps.padSquareInto(image, sourceSize, BACKGROUND, paddedImage)
            InpaintPadOps.padSquareInto(mask, sourceSize, 0, paddedMask)

            val offset = InpaintPadOps.centeredOffset(sourceSize)
            val borderIndices = buildList {
                add((InpaintPadOps.SIZE - 1) * InpaintPadOps.SIZE)
                add(PADDED_PIXELS - 1)
                if (offset > 0) {
                    add(0)
                    add(InpaintPadOps.SIZE - 1)
                }
            }
            for (index in borderIndices) {
                paddedImage[index] shouldBe BACKGROUND
                paddedMask[index] shouldBe 0
            }
        }
    }

    @Test
    fun `replicate padding extends border pixels outward with the crop centered`() {
        val size = 4
        val source = IntArray(size * size) { it }
        val padded = IntArray(InpaintPadOps.SIZE * InpaintPadOps.SIZE)

        InpaintPadOps.padSquareReplicateInto(source, size, padded)
        val offset = InpaintPadOps.centeredOffset(size)

        padded[0] shouldBe source[0]
        padded[InpaintPadOps.SIZE - 1] shouldBe source[size - 1]
        padded[(InpaintPadOps.SIZE - 1) * InpaintPadOps.SIZE] shouldBe source[(size - 1) * size]
        padded[InpaintPadOps.SIZE * InpaintPadOps.SIZE - 1] shouldBe source[size * size - 1]

        for (y in 0 until size) {
            for (x in 0 until size) {
                padded[(offset + y) * InpaintPadOps.SIZE + offset + x] shouldBe source[y * size + x]
            }
        }

        padded[(offset - 1) * InpaintPadOps.SIZE + offset] shouldBe source[0]
        padded[offset * InpaintPadOps.SIZE + (offset - 1)] shouldBe source[0]
    }

    @Test
    fun `crop back recovers every supported square exactly`() {
        for (sourceSize in SUPPORTED_SIDES) {
            val source = IntArray(sourceSize * sourceSize) { index ->
                0xFF000000.toInt() or (index and 0x00FFFFFF)
            }
            val padded = IntArray(PADDED_PIXELS + 1) { GUARD_PIXEL }
            val cropped = IntArray(source.size + 1) { GUARD_PIXEL }

            InpaintPadOps.padSquareInto(source, sourceSize, BACKGROUND, padded)
            InpaintPadOps.cropSquareInto(padded, sourceSize, cropped)

            cropped.copyOf(source.size).toList() shouldBe source.toList()
            padded[PADDED_PIXELS] shouldBe GUARD_PIXEL
            cropped[source.size] shouldBe GUARD_PIXEL
        }
    }

    @Test
    fun `allocating wrappers preserve exact semantics`() {
        val sourceSize = 400
        val source = IntArray(sourceSize * sourceSize) { index -> index }

        val padded = InpaintPadOps.padSquare(source, sourceSize, BACKGROUND)

        padded.size shouldBe PADDED_PIXELS
        InpaintPadOps.cropSquare(padded, sourceSize).toList() shouldBe source.toList()
    }

    @Test
    fun `invalid source sides are rejected`() {
        for (sourceSize in listOf(Int.MIN_VALUE, -1, 0, 513, Int.MAX_VALUE)) {
            assertThrows<IllegalArgumentException> {
                InpaintPadOps.padSquareInto(IntArray(1), sourceSize, BACKGROUND, IntArray(PADDED_PIXELS))
            }
            assertThrows<IllegalArgumentException> {
                InpaintPadOps.cropSquareInto(IntArray(PADDED_PIXELS), sourceSize, IntArray(1))
            }
        }
    }

    @Test
    fun `undersized buffers are rejected while oversized buffers are accepted`() {
        val sourceSize = 300
        val sourcePixels = sourceSize * sourceSize

        assertThrows<IllegalArgumentException> {
            InpaintPadOps.padSquareInto(IntArray(sourcePixels - 1), sourceSize, BACKGROUND, IntArray(PADDED_PIXELS))
        }
        assertThrows<IllegalArgumentException> {
            InpaintPadOps.padSquareInto(IntArray(sourcePixels), sourceSize, BACKGROUND, IntArray(PADDED_PIXELS - 1))
        }
        assertThrows<IllegalArgumentException> {
            InpaintPadOps.cropSquareInto(IntArray(PADDED_PIXELS - 1), sourceSize, IntArray(sourcePixels))
        }
        assertThrows<IllegalArgumentException> {
            InpaintPadOps.cropSquareInto(IntArray(PADDED_PIXELS), sourceSize, IntArray(sourcePixels - 1))
        }

        InpaintPadOps.padSquareInto(
            IntArray(sourcePixels + 1),
            sourceSize,
            BACKGROUND,
            IntArray(PADDED_PIXELS + 1),
        )
    }

    private companion object {
        val SUPPORTED_SIDES = listOf(1, 300, 400, 480, 510, 511, 512)
        const val PADDED_PIXELS = InpaintPadOps.SIZE * InpaintPadOps.SIZE
        val IMAGE_PIXEL = 0xFF123456.toInt()
        val MASK_PIXEL = 0xFFFFFFFF.toInt()
        val BACKGROUND = 0xFFB0A090.toInt()
        const val GUARD_PIXEL = 0x7F55AA33
    }
}
