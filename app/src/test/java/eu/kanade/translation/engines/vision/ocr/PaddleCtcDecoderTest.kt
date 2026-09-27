package eu.kanade.translation.engines.vision.ocr

import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer
import kotlin.math.abs

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

    @Test
    fun `decodeWithConf returns mean prob of emitted chars`() {
        // dictionary size=3 => spaceIndex=4
        // indices: [0, 1, 1, 0, 2]
        //   timestep 1: idx=1 (class 'a'), prob=0.8  → emit 'a', accumulate 0.8
        //   timestep 2: idx=1 (dup of previous) → skip
        //   timestep 4: idx=2 (class 'b'), prob=0.6  → emit 'b', accumulate 0.6
        // text: "ab", confidence: mean(0.8, 0.6) = 0.7
        val (text, conf) = PaddleCtcDecoder.decodeWithConf(
            intArrayOf(0, 1, 1, 0, 2),
            floatArrayOf(0.99f, 0.8f, 0.7f, 0.95f, 0.6f),
            listOf("a", "b", "c"),
        )
        text shouldBe "ab"
        // mean(0.8f, 0.6f) drifts to 0.70000005f in IEEE-754 single precision.
        abs(conf - 0.7f) shouldBeLessThan 1e-5f
    }

    @Test
    fun `confidence is 0 when only blanks or spaces emit`() {
        // dictionary size=2 => spaceIndex=3
        // indices: [0, 0, 3] → blank, blank, space → trimmed text ""
        // No non-blank, non-space character emitted → confidence = 0
        val (text, conf) = PaddleCtcDecoder.decodeWithConf(
            intArrayOf(0, 0, 3),
            floatArrayOf(0.9f, 0.9f, 0.9f),
            listOf("a", "b"),
        )
        text shouldBe ""
        conf shouldBe 0.0f
    }
}
