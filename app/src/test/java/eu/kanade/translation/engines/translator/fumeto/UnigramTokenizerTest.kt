package eu.kanade.translation.engines.translator.fumeto

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the Unigram Viterbi encoder on a synthetic vocabulary that forces the
 * interesting paths: multi-piece vs single-piece competition, metaspace
 * handling, and byte-fallback emission for uncovered characters.
 */
class UnigramTokenizerTest {

    @Test
    fun `prefers the longer higher-scoring piece over a split`() {
        val tokenizer = tokenizerFromScores(
            "<unk>" to -20.0,
            "abc" to -0.5,
            "ab" to -1.0,
            "a" to -2.0,
            "b" to -2.0,
            "c" to -2.0,
        )

        // "abc" as one piece scores -0.5; any split scores ≤ -3.
        tokenizer.encode("abc") shouldBe intArrayOf(1)
        // "ab" scores -1.0; "a"+"b" scores -4.0.
        tokenizer.encode("ab") shouldBe intArrayOf(2)
    }

    @Test
    fun `splits through the best-scoring lattice path`() {
        val tokenizer = tokenizerFromScores(
            "<unk>" to -20.0,
            "ab" to -1.0,
            "bcd" to -0.5,
            "a" to -2.0,
            "bc" to -3.0,
            "d" to -2.0,
            "cd" to -1.6,
        )

        // "a"+"bcd" = -2.5 strictly beats "ab"+"cd" = -2.6 and "a"+"bc"+"d" = -7.
        tokenizer.encode("abcd") shouldBe intArrayOf(3, 2) // a(-2.0) + bcd(-0.5)
    }

    @Test
    fun `spaces become metaspace pieces`() {
        val tokenizer = tokenizerFromScores(
            "<unk>" to -20.0,
            "▁" to -3.0,
            "hi" to -1.0,
            "▁yo" to -1.5,
        )

        // "hi yo" → "hi▁yo": "hi"(-1.0) + "▁yo"(-1.5); a metaspace alone is not needed.
        tokenizer.encode("hi yo") shouldBe intArrayOf(2, 3)
    }

    @Test
    fun `uncovered characters fall back to one byte token per utf-8 byte`() {
        val tokenizer = tokenizerFromScores(
            "<unk>" to -20.0,
            "a" to -2.0,
            "<0x78>" to -4.0, // 'x'
            "<0xC3>" to -5.0,
            "<0xA9>" to -5.0, // é = C3 A9
        )

        // 'x' has no string piece → its single UTF-8 byte 0x78.
        tokenizer.encode("xa") shouldBe intArrayOf(2, 1)
        // 'é' is one character covered by two byte tokens (C3, A9).
        tokenizer.encode("éa") shouldBe intArrayOf(3, 4, 1)
    }

    @Test
    fun `missing byte pieces degrade to the unk id`() {
        val tokenizer = tokenizerFromScores(
            "<unk>" to -20.0,
            "a" to -2.0,
            "<0x78>" to -4.0,
        )

        // 'é' bytes (C3, A9) have no pieces → both fall back to unk id 0.
        tokenizer.encode("é") shouldBe intArrayOf(0, 0)
    }

    @Test
    fun `empty input encodes to nothing`() {
        tokenizerFromScores("<unk>" to -20.0).encode("") shouldBe intArrayOf()
    }

    /** Vocab ids follow TSV line order; id 0 is the unk. */
    private fun tokenizerFromScores(vararg pieces: Pair<String, Double>): UnigramTokenizer {
        val tsv = pieces.joinToString("\n") { (piece, score) -> "$piece\t$score" }
        return UnigramTokenizer.fromTsv(tsv)
    }
}
