package eu.kanade.translation.engines.vision.ocr

object OcrTextFilter {
    fun isUsable(text: String): Boolean = text.any { it.isLetter() }

    fun isUsable(text: String, language: TextRecognizerLanguage): Boolean {
        if (text.isEmpty()) return false
        val cjk = language == TextRecognizerLanguage.JAPANESE ||
            language == TextRecognizerLanguage.CHINESE ||
            language == TextRecognizerLanguage.KOREAN
        if (cjk) {
            return text.any { it.isCjk() }
        }
        return text.any { it.isLetter() }
    }

    fun pickUsable(a: String, b: String): String {
        val ua = isUsable(a)
        val ub = isUsable(b)
        return when {
            ua && ub -> if (a.length >= b.length) a else b
            ua -> a
            ub -> b
            else -> ""
        }
    }

    // Hiragana (U+3040–U+309F), Katakana (U+30A0–U+30FF),
    // CJK Unified Ideographs (U+4E00–U+9FFF), CJK Extension A (U+3400–U+4DBF),
    // Hangul Syllables (U+AC00–U+D7AF), CJK Compatibility (U+F900–U+FAFF)
    private fun Char.isCjk(): Boolean =
        this in '\u3040'..'\u309F' ||
            this in '\u30A0'..'\u30FF' ||
            this in '\u4E00'..'\u9FFF' ||
            this in '\u3400'..'\u4DBF' ||
            this in '\uAC00'..'\uD7AF' ||
            this in '\uF900'..'\uFAFF'
}
