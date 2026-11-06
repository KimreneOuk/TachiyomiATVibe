package eu.kanade.translation.engines.translator.fumeto

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the SentencePiece decode path on a synthetic piece table: metaspace
 * handling, the stripped dummy-prefix space, byte-piece runs (including a
 * multi-byte UTF-8 character), invisible control pieces, and the unknown
 * surface.
 */
class SentencePieceDecoderTest {

    private val decoder = SentencePieceDecoder.fromTsv(
        """
        <pad>	3
        <s>	3
        </s>	3
        <unk>	2
        <0x41>	6
        <0xC3>	6
        <0xA9>	6
        ▁Hello	1
        ▁world	1
        !	1
        """.trimIndent(),
    )

    @Test
    fun `decode joins pieces and strips the dummy prefix space`() {
        // <s> ▁Hello ▁world !
        decoder.decode(intArrayOf(1, 7, 8, 9)) shouldBe "Hello world!"
    }

    @Test
    fun `byte pieces decode to their raw utf-8 bytes`() {
        // ▁Hello <0xC3> <0xA9> ! → "Hello" + "é" + "!"
        decoder.decode(intArrayOf(7, 5, 6, 9)) shouldBe "Helloé!"
    }

    @Test
    fun `control pieces are invisible`() {
        decoder.decode(intArrayOf(0)) shouldBe ""
        decoder.decode(intArrayOf(1, 2)) shouldBe ""
    }

    @Test
    fun `unknown pieces decode to the sentencepiece surface`() {
        decoder.decode(intArrayOf(3)) shouldBe " ⁇ "
    }

    @Test
    fun `empty ids decode to empty text`() {
        decoder.decode(intArrayOf()) shouldBe ""
    }
}
