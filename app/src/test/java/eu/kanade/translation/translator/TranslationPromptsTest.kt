package eu.kanade.translation.translator

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class TranslationPromptsTest {

    private fun block(text: String, inBubble: Boolean): TranslationBlock = TranslationBlock(
        text = text,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        parentWidth = if (inBubble) 50f else 0f,
        parentHeight = if (inBubble) 50f else 0f,
    )

    @Test
    fun `in-bubble blocks are tagged SPEECH, free-text blocks are not`() {
        with(TranslationPrompts) {
            block("こんにちは", inBubble = true).isInsideBubble() shouldBe true
            block("三年後", inBubble = false).isInsideBubble() shouldBe false

            numberedSourceLine(0, block("こんにちは", inBubble = true)) shouldBe "[0] [SPEECH] こんにちは"
            numberedSourceLine(1, block("三年後", inBubble = false)) shouldBe "[1] 三年後"

            jsonSourceValue(block("こんにちは", inBubble = true)) shouldBe "[SPEECH] こんにちは"
            jsonSourceValue(block("三年後", inBubble = false)) shouldBe "三年後"
        }
    }

    @Test
    fun `reading direction is RTL for Japanese, LTR otherwise`() {
        TranslationPrompts.readingDirectionHint(TextRecognizerLanguage.JAPANESE) shouldBe
            "right-to-left, top-to-bottom"
        TranslationPrompts.readingDirectionHint(TextRecognizerLanguage.KOREAN) shouldBe
            "left-to-right, top-to-bottom"
        TranslationPrompts.readingDirectionHint(TextRecognizerLanguage.CHINESE) shouldBe
            "left-to-right, top-to-bottom"
    }

    @Test
    fun `isProDrop is true for CJK and Romance pro-drop languages, false otherwise`() {
        // CJK sources habitually drop subjects.
        TranslationPrompts.isProDrop(TextRecognizerLanguage.JAPANESE) shouldBe true
        TranslationPrompts.isProDrop(TextRecognizerLanguage.CHINESE) shouldBe true
        TranslationPrompts.isProDrop(TextRecognizerLanguage.KOREAN) shouldBe true
        // Romance pro-drop languages.
        TranslationPrompts.isProDrop(TextRecognizerLanguage.SPANISH) shouldBe true
        TranslationPrompts.isProDrop(TextRecognizerLanguage.PORTUGUESE) shouldBe true
        TranslationPrompts.isProDrop(TextRecognizerLanguage.ITALIAN) shouldBe true
        // Non-pro-drop: subject inference guidance should NOT be emitted for these.
        TranslationPrompts.isProDrop(TextRecognizerLanguage.ENGLISH) shouldBe false
        TranslationPrompts.isProDrop(TextRecognizerLanguage.FRENCH) shouldBe false
        TranslationPrompts.isProDrop(TextRecognizerLanguage.GERMAN) shouldBe false
        TranslationPrompts.isProDrop(TextRecognizerLanguage.INDONESIAN) shouldBe false
        TranslationPrompts.isProDrop(TextRecognizerLanguage.VIETNAMESE) shouldBe false
        TranslationPrompts.isProDrop(TextRecognizerLanguage.RUSSIAN) shouldBe false
    }

    @Test
    fun `pro-drop guidance is emitted only for pro-drop source languages`() {
        // Japanese (pro-drop) -> guidance present.
        val jaPrompt = TranslationPrompts.numberedSystemPrompt(
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        jaPrompt shouldContain "pro-drop"

        // German (non-pro-drop) -> guidance absent, so the model doesn't invent
        // omitted subjects that aren't there in the source.
        val dePrompt = TranslationPrompts.numberedSystemPrompt(
            TextRecognizerLanguage.GERMAN,
            TextTranslatorLanguage.ENGLISH,
        )
        dePrompt shouldNotContain "pro-drop"
    }

    @Test
    fun `contextPrefix is empty when both inputs are blank`() {
        TranslationPrompts.contextPrefix(rollingContext = "   ", glossary = "") shouldBe ""
        TranslationPrompts.contextPrefix(rollingContext = "", glossary = "   ") shouldBe ""
    }

    @Test
    fun `contextPrefix emits a glossary section and a pairs section`() {
        val out = TranslationPrompts.contextPrefix(
            rollingContext = "源 => source",
            glossary = "太郎 => Taro",
        )
        out shouldContain "Established terms"
        out shouldContain "太郎 => Taro"
        out shouldContain "Previous context"
        out shouldContain "源 => source"
    }

    @Test
    fun `numbered and json system prompts carry the POV, pro-drop, and SPEECH guidance`() {
        val from = TextRecognizerLanguage.JAPANESE
        val to = TextTranslatorLanguage.ENGLISH
        val numbered = TranslationPrompts.numberedSystemPrompt(from, to)
        val json = TranslationPrompts.jsonSystemPrompt(from, to)

        for (prompt in listOf(numbered, json)) {
            prompt shouldContain "Japanese"
            prompt shouldContain "English"
            prompt shouldContain "POINT OF VIEW"
            prompt shouldContain "pro-drop"
            prompt shouldContain "first-person"
            prompt shouldContain "[SPEECH]"
            prompt shouldContain "METADATA, NOT TEXT"
            prompt shouldContain "ONLY"
        }
        numbered shouldContain "[index] translation"
        json shouldContain "JSON object"
    }

    @Test
    fun `prompts never instruct the model to echo the SPEECH tag`() {
        val prompt = TranslationPrompts.numberedSystemPrompt(
            TextRecognizerLanguage.CHINESE,
            TextTranslatorLanguage.ENGLISH,
        )
        // The few-shot source side shows the tag, but the instruction forbids
        // including it in output — guard against an accidental flip of that rule.
        prompt shouldContain "NEVER include"
    }
}
