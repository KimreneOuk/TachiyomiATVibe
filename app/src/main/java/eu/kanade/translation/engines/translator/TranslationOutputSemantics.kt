package eu.kanade.translation.engines.translator

import java.text.Normalizer

/**
 * Shared, deterministic interpretation of translation text at acceptance and
 * re-request boundaries. This uses only configured language codes and Unicode
 * script facts; it never guesses from model/provider identity.
 */
internal object TranslationOutputSemantics {
    enum class UnresolvedReason {
        BLANK,
        SOURCE_ECHO,
        WRONG_TARGET_LANGUAGE,
    }

    /**
     * Only source scripts that distinguish one of the configured OCR source
     * languages are evidence. Latin text is deliberately not evidence because
     * Latin-language invariants and names (for example "OK!" and "Alice") are
     * common across targets. Han-only text cannot distinguish Japanese from
     * Chinese, so that pair is explicitly ambiguous.
     */
    fun hasMeaningfulSourceScript(
        source: String,
        sourceLanguageCode: String?,
        targetLanguageCode: String?,
    ): Boolean {
        val sourceLanguage = languageCode(sourceLanguageCode)
        val targetLanguage = languageCode(targetLanguageCode)
        if (source.isBlank() || sourceLanguage.isEmpty() || targetLanguage.isEmpty()) return false
        if (sourceLanguage == targetLanguage) return false
        val scripts = scriptsIn(source)
        return when (sourceLanguage) {
            "ja" ->
                Character.UnicodeScript.HIRAGANA in scripts ||
                    Character.UnicodeScript.KATAKANA in scripts ||
                    (targetLanguage != "zh" && Character.UnicodeScript.HAN in scripts)
            "zh" -> targetLanguage != "ja" && Character.UnicodeScript.HAN in scripts
            "ko" -> Character.UnicodeScript.HANGUL in scripts
            "ru" -> {
                val targetScripts = expectedTargetScripts(targetLanguage)
                Character.UnicodeScript.CYRILLIC in scripts &&
                    targetScripts != null &&
                    Character.UnicodeScript.CYRILLIC !in targetScripts
            }
            else -> false
        }
    }

    fun normalizedForComparison(text: String): String {
        val normalizedLineEndings = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        return Normalizer.normalize(normalizedLineEndings, Normalizer.Form.NFC)
    }

    fun unresolvedReason(
        source: String,
        output: String,
        sourceLanguageCode: String?,
        targetLanguageCode: String?,
    ): UnresolvedReason? {
        if (output.isBlank()) return UnresolvedReason.BLANK

        if (normalizedForComparison(source) == normalizedForComparison(output)) {
            val sourceLanguage = languageCode(sourceLanguageCode)
            val targetLanguage = languageCode(targetLanguageCode)
            // Callers without a configured pair keep the legacy conservative
            // behavior; a known same-language pair is explicitly accepted.
            if (sourceLanguage.isEmpty() || targetLanguage.isEmpty()) {
                return UnresolvedReason.SOURCE_ECHO
            }
            if (sourceLanguage != targetLanguage &&
                hasMeaningfulSourceScript(source, sourceLanguage, targetLanguage)
            ) {
                return UnresolvedReason.SOURCE_ECHO
            }
        }

        if (isDefinitelyWrongTargetScript(output, targetLanguageCode)) {
            return UnresolvedReason.WRONG_TARGET_LANGUAGE
        }
        return null
    }

    fun isResolved(
        source: String,
        output: String,
        sourceLanguageCode: String?,
        targetLanguageCode: String?,
    ): Boolean = unresolvedReason(source, output, sourceLanguageCode, targetLanguageCode) == null

    private fun isDefinitelyWrongTargetScript(output: String, targetLanguageCode: String?): Boolean {
        val expectedTargetScripts = expectedTargetScripts(languageCode(targetLanguageCode)) ?: return false
        val actualScripts = scriptsIn(output)
        if (actualScripts.any(expectedTargetScripts::contains)) return false
        // Han alone is shared by Japanese and Chinese, and Latin is shared by
        // many configured languages. Neither can establish a wrong target.
        return actualScripts.any { it in distinctiveNonLatinScripts }
    }

    private fun expectedTargetScripts(language: String): Set<Character.UnicodeScript>? = when (language) {
        "ja" -> setOf(
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HAN,
        )
        "zh" -> setOf(Character.UnicodeScript.HAN)
        "ko" -> setOf(Character.UnicodeScript.HANGUL)
        "ru" -> setOf(Character.UnicodeScript.CYRILLIC)
        "el" -> setOf(Character.UnicodeScript.GREEK)
        "ar", "fa", "ur" -> setOf(Character.UnicodeScript.ARABIC)
        "he", "yi" -> setOf(Character.UnicodeScript.HEBREW)
        "hi", "mr", "ne" -> setOf(Character.UnicodeScript.DEVANAGARI)
        "th" -> setOf(Character.UnicodeScript.THAI)
        "en", "es", "pt", "id", "fr", "de", "it", "vi" -> setOf(Character.UnicodeScript.LATIN)
        else -> null
    }

    private fun scriptsIn(text: String): Set<Character.UnicodeScript> {
        val scripts = mutableSetOf<Character.UnicodeScript>()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val script = Character.UnicodeScript.of(codePoint)
            if (script != Character.UnicodeScript.COMMON &&
                script != Character.UnicodeScript.INHERITED &&
                script != Character.UnicodeScript.UNKNOWN
            ) {
                scripts += script
            }
            offset += Character.charCount(codePoint)
        }
        return scripts
    }

    private fun languageCode(code: String?): String =
        code.orEmpty().trim().substringBefore('-').lowercase()

    private val distinctiveNonLatinScripts = setOf(
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.ARABIC,
        Character.UnicodeScript.HEBREW,
        Character.UnicodeScript.DEVANAGARI,
        Character.UnicodeScript.BENGALI,
        Character.UnicodeScript.GURMUKHI,
        Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.ORIYA,
        Character.UnicodeScript.TAMIL,
        Character.UnicodeScript.TELUGU,
        Character.UnicodeScript.KANNADA,
        Character.UnicodeScript.MALAYALAM,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.ETHIOPIC,
        Character.UnicodeScript.GEORGIAN,
        Character.UnicodeScript.ARMENIAN,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.SINHALA,
    )
}
