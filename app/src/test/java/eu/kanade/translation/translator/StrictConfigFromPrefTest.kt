package eu.kanade.translation.translator

import eu.kanade.translation.ocr.TextRecognizerLanguage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * TachiyomiAT: pins the STRICT no-fallback contract for the language config
 * resolvers.
 *
 * The old `fromPref` helpers silently rewrote an unknown stored value to a
 * default (Chinese for the OCR source, English for the translator target), so
 * a corrupted or migrated preference quietly picked the wrong language with
 * no signal. Under the strict policy an invalid value must THROW so the
 * pipeline's try/catch surfaces it as a FAILED page (or the chapter-queue
 * path surfaces it as a toast). These tests lock that throw so the silent
 * fallback can't be re-introduced.
 *
 * Note: [StandardTranslatorKind.fromPref] has the same strict-throw shape, but
 * its input type (`StandardEngine`) is a closed two-value enum identical to
 * [StandardTranslatorKind], so the throw branch is unreachable from real
 * preferences and is not unit-testable here — it exists as a guard against a
 * future enum divergence.
 */
class StrictConfigFromPrefTest {

    @Test
    fun `TextRecognizerLanguage fromPref returns the configured language`() {
        val pref = stringPref("JAPANESE")

        TextRecognizerLanguage.fromPref(pref) shouldBe TextRecognizerLanguage.JAPANESE
    }

    @Test
    fun `TextRecognizerLanguage fromPref matches case-insensitively`() {
        val pref = stringPref("english")

        TextRecognizerLanguage.fromPref(pref) shouldBe TextRecognizerLanguage.ENGLISH
    }

    @Test
    fun `TextRecognizerLanguage fromPref throws on unknown value instead of defaulting to Chinese`() {
        // The exact regression: an unknown/corrupted value used to silently
        // become Chinese. Under strict no-fallback it must throw.
        val pref = stringPref("Klingon")

        val ex = assertThrows<IllegalArgumentException> {
            TextRecognizerLanguage.fromPref(pref)
        }
        ex.message shouldContain "Unknown OCR source language"
        ex.message shouldContain "Klingon"
    }

    @Test
    fun `TextTranslatorLanguage fromPref returns the configured language`() {
        val pref = stringPref("FRENCH")

        TextTranslatorLanguage.fromPref(pref) shouldBe TextTranslatorLanguage.FRENCH
    }

    @Test
    fun `TextTranslatorLanguage fromPref throws on unknown value instead of defaulting to English`() {
        val pref = stringPref("Elvish")

        val ex = assertThrows<IllegalArgumentException> {
            TextTranslatorLanguage.fromPref(pref)
        }
        ex.message shouldContain "Unknown translator target language"
        ex.message shouldContain "Elvish"
    }

    private fun stringPref(value: String) =
        InMemoryPreferenceStore.InMemoryPreference("key", value, value)
}
