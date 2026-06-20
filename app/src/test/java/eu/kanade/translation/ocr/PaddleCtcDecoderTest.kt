package eu.kanade.translation.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

class PaddleCtcDecoderTest {

    @Test
    fun `decode skips blanks and collapses repeated characters`() {
        PaddleCtcDecoder.decode(
            intArrayOf(0, 1, 1, 0, 2, 2, 0, 3),
            listOf("a", "b", "c"),
        ) shouldBe "abc"
    }

    @Test
    fun `decode maps the class one past the dictionary to space`() {
        // PaddleOCR reserves the index (dictionary.size + 1) as the space token.
        // For a 2-entry dictionary that is index 3.
        PaddleCtcDecoder.decode(
            intArrayOf(1, 3, 2),
            listOf("a", "b"),
        ) shouldBe "a b"
    }

    @Test
    fun `argmax returns best class per timestep`() {
        val logits = FloatBuffer.wrap(
            floatArrayOf(
                0.1f, 0.8f, 0.1f,
                0.7f, 0.2f, 0.1f,
                0.0f, 0.2f, 0.9f,
            ),
        )

        PaddleCtcDecoder.argmaxIndices(logits, timeSteps = 3, classCount = 3).toList() shouldBe listOf(1, 0, 2)
    }
}
