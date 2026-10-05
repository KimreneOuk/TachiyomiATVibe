package eu.kanade.translation.engines.translator

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationOutputSemanticsTest {

    @Test
    fun `echo and wrong-target decisions follow the conservative script table`() {
        data class Case(
            val sourceLanguage: String,
            val targetLanguage: String,
            val source: String,
            val output: String,
            val expected: TranslationOutputSemantics.UnresolvedReason?,
        )

        val cases = listOf(
            Case("ja", "en", "待て！", "待て！", TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO),
            Case("ja", "en", "猫", "猫", TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO),
            Case("ru", "en", "так", "так", TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO),
            Case("ru", "uk", "так", "так", null),
            Case("zh", "en", "漢字", "漢字", TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO),
            Case("ko", "en", "안녕", "안녕", TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO),
            Case("ja", "en", "待て Alice", "待て Alice", TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO),
            Case("ja", "en", "OK!", "OK!", null),
            Case("ja", "en", "Alice", "Alice", null),
            Case("ja", "ja", "待て！", "待て！", null),
            Case("ja", "zh", "猫", "猫", null),
            Case("zh", "ja", "漢字", "漢字", null),
            Case("es", "fr", "Hola, Alice!", "Hola, Alice!", null),
            Case("en", "es", "hello", "hello", null),
            Case("ja", "en", "！？…", "！？…", null),
            Case("ja", "en", "待て！", "안녕하세요", TranslationOutputSemantics.UnresolvedReason.WRONG_TARGET_LANGUAGE),
            Case("ja", "en", "待て！", "猫", null),
            Case("ja", "en", "か\u3099", "が", TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO),
        )

        cases.forEach { case ->
            withClue(
                "source=${case.sourceLanguage}, target=${case.targetLanguage}, " +
                    "sourceText=${case.source}, output=${case.output}",
            ) {
                TranslationOutputSemantics.unresolvedReason(
                    case.source,
                    case.output,
                    case.sourceLanguage,
                    case.targetLanguage,
                ) shouldBe case.expected
            }
        }

        TranslationOutputSemantics.normalizedForComparison(" \r\nか\u3099 \r\n") shouldBe "が"
        TranslationOutputSemantics.normalizedForComparison("OK!") shouldBe "OK!"
        (
            TranslationOutputSemantics.normalizedForComparison("OK!") ==
                TranslationOutputSemantics.normalizedForComparison("ok!")
            ) shouldBe false
        (
            TranslationOutputSemantics.normalizedForComparison("待て！") ==
                TranslationOutputSemantics.normalizedForComparison("待て?")
            ) shouldBe false
    }
}
