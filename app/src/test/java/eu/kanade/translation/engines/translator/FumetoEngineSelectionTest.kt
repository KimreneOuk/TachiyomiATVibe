package eu.kanade.translation.engines.translator

import eu.kanade.translation.engines.translator.fumeto.FumetoTranslateEngine
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.translation.TranslationEngineCategory

/**
 * Pins FumetoTranslate's Standard-engine contract: it is a non-AI engine whose
 * bundled model is Japanese→English only, so a mismatched language selection
 * must fail fast (surfaces as a FAILED page) instead of emitting garbage.
 */
class FumetoEngineSelectionTest {

    @Test
    fun `FUMETO builds an on-device engine for Japanese to English`() {
        val translator = StandardTranslatorKind.FUMETO.build(
            pref = mockPreferences(),
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
        )

        translator.shouldBeInstanceOf<FumetoTranslateEngine>()
        translator.fromLang shouldBe TextRecognizerLanguage.JAPANESE
        translator.toLang shouldBe TextTranslatorLanguage.ENGLISH
        translator.close()
    }

    @Test
    fun `FUMETO rejects a non-Japanese source language`() {
        val ex = assertThrows<IllegalArgumentException> {
            StandardTranslatorKind.FUMETO.build(
                pref = mockPreferences(),
                fromLang = TextRecognizerLanguage.ENGLISH,
                toLang = TextTranslatorLanguage.ENGLISH,
            )
        }
        ex.message shouldContain "Japanese as the source language"
    }

    @Test
    fun `FUMETO rejects a non-English target language`() {
        val ex = assertThrows<IllegalArgumentException> {
            StandardTranslatorKind.FUMETO.build(
                pref = mockPreferences(),
                fromLang = TextRecognizerLanguage.JAPANESE,
                toLang = TextTranslatorLanguage.FRENCH,
            )
        }
        ex.message shouldContain "English as the target language"
    }

    @Test
    fun `FUMETO classifies as local compute`() {
        TranslatorComputeClass.forConfiguration(
            category = TranslationEngineCategory.STANDARD,
            standardEngineName = "FUMETO",
            aiEngineName = null,
        ) shouldBe TranslatorComputeClass.LOCAL_COMPUTE
    }

    /**
     * The FUMETO build path reads no preferences (no credentials), so a
     * permissive stub is enough; the strict engines keep their own key checks.
     */
    private fun mockPreferences(): tachiyomi.domain.translation.TranslationPreferences =
        io.mockk.mockk(relaxed = true)
}
