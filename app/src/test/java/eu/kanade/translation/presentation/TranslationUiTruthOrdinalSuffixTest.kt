package eu.kanade.translation.presentation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Ordinal rules behind queue position copy: teens take "th", never "1st"-style. */
class TranslationUiTruthOrdinalSuffixTest {

    @Test
    fun `ordinal suffix covers the standard english rules`() {
        TranslationUiTruth.ordinalSuffixOf(1) shouldBe "1st"
        TranslationUiTruth.ordinalSuffixOf(2) shouldBe "2nd"
        TranslationUiTruth.ordinalSuffixOf(3) shouldBe "3rd"
        TranslationUiTruth.ordinalSuffixOf(4) shouldBe "4th"
    }

    @Test
    fun `ordinal suffix covers the teen and round-number edge cases`() {
        TranslationUiTruth.ordinalSuffixOf(11) shouldBe "11th"
        TranslationUiTruth.ordinalSuffixOf(12) shouldBe "12th"
        TranslationUiTruth.ordinalSuffixOf(13) shouldBe "13th"
        TranslationUiTruth.ordinalSuffixOf(21) shouldBe "21st"
        TranslationUiTruth.ordinalSuffixOf(100) shouldBe "100th"
    }
}
