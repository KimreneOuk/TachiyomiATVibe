package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotPadPathTest {

    @Test
    fun `background padding preserves centered source pixels`() {
        val sourceSize = 4
        val background = 0xFFB0A090.toInt()
        val source = IntArray(sourceSize * sourceSize) { index -> 0xFF000000.toInt() or index }

        val padded = AotPadPath.padSquare(source, sourceSize, background)
        val offset = AotPadPath.centeredOffset(sourceSize)

        padded[0] shouldBe background
        padded[offset * AotPadPath.SIZE + offset] shouldBe source[0]
        padded[(offset + sourceSize - 1) * AotPadPath.SIZE + offset + sourceSize - 1] shouldBe source.last()
    }

    @Test
    fun `crop back recovers the original square exactly`() {
        val sourceSize = 400
        val source = IntArray(sourceSize * sourceSize) { index -> 0xFF000000.toInt() or (index and 0x00FFFFFF) }

        AotPadPath.cropSquare(
            AotPadPath.padSquare(source, sourceSize, background = 0xFFFFFFFF.toInt()),
            sourceSize,
        ).toList() shouldBe source.toList()
    }

    @Test
    fun `fixed shape is always 512 square`() {
        AotPadPath.padSquare(IntArray(300 * 300), 300, 0).size shouldBe 512 * 512
    }
}
