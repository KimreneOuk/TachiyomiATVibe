package eu.kanade.translation.translator

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.TranslationPrompts.ParsedLine
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

            idMappedSourceLine("b0", block("こんにちは", inBubble = true)) shouldBe "b0|[SPEECH] こんにちは"
            idMappedSourceLine("b1", block("三年後", inBubble = false)) shouldBe "b1|三年後"
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
        val jaPrompt = TranslationPrompts.pass1SystemPrompt(
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        jaPrompt shouldContain "pro-drop"

        // German (non-pro-drop) -> guidance absent, so the model doesn't invent
        // omitted subjects that aren't there in the source.
        val dePrompt = TranslationPrompts.pass1SystemPrompt(
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
    fun `pass 1 and pass 2 system prompts carry relevant instructions`() {
        val from = TextRecognizerLanguage.JAPANESE
        val to = TextTranslatorLanguage.ENGLISH
        val pass1 = TranslationPrompts.pass1SystemPrompt(from, to)
        val pass2 = TranslationPrompts.pass2SystemPrompt(from, to)

        pass1 shouldContain "Japanese"
        pass1 shouldContain "English"
        pass1 shouldContain "POINT OF VIEW"
        pass1 shouldContain "pro-drop"
        pass1 shouldContain "first-person"
        pass1 shouldContain "[SPEECH]"
        pass1 shouldContain "METADATA, NOT TEXT"
        pass1 shouldContain "ONLY"
        pass1 shouldContain "ID|Translated Text|[STATUS]"

        pass2 shouldContain "Japanese"
        pass2 shouldContain "English"
        pass2 shouldContain "Source:"
        pass2 shouldContain "Draft:"
        pass2 shouldContain "ID|Corrected Text"
    }

    @Test
    fun `prompts never instruct the model to echo the SPEECH tag`() {
        val prompt = TranslationPrompts.pass1SystemPrompt(
            TextRecognizerLanguage.CHINESE,
            TextTranslatorLanguage.ENGLISH,
        )
        // The few-shot source side shows the tag, but the instruction forbids
        // including it in output — guard against an accidental flip of that rule.
        prompt shouldContain "NEVER include"
    }

    @Test
    fun `parseLine parses different format variations correctly`() {
        with(TranslationPrompts) {
            parseLine("b0|I'm going.|[OK]") shouldBe ParsedLine("b0", "I'm going.", false)
            parseLine("b1| That day, I met him. | [FLAG] ") shouldBe ParsedLine("b1", "That day, I met him.", true)
            parseLine("b2|Three years later — Tokyo.|") shouldBe ParsedLine("b2", "Three years later — Tokyo.", null)
            parseLine("b3 | He said he wouldn't come.") shouldBe ParsedLine("b3", "He said he wouldn't come.", null)
            parseLine("b4|Who is it?|[FLAG]") shouldBe ParsedLine("b4", "Who is it?", true)
            parseLine("invalid") shouldBe null
        }
    }
}
