package eu.kanade.translation.translator.contextual
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.contextual.TranslationPrompts.ParsedLine
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
    fun `id-mapped source lines contain only the id and source text`() {
        with(TranslationPrompts) {
            idMappedSourceLine("b0", block("こんにちは", inBubble = true)) shouldBe "b0|こんにちは"
            idMappedSourceLine("b1", block("三年後", inBubble = false)) shouldBe "b1|三年後"
            idMappedSourceLine("b2", block("第一行\n第二行", inBubble = false)) shouldBe "b2|第一行 第二行"
            idMappedSourceLine("b3", block("第一行\r\n第二行\r第三行", inBubble = false)) shouldBe
                "b3|第一行 第二行 第三行"
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
    fun `system prompt carries relevant instructions`() {
        val from = TextRecognizerLanguage.JAPANESE
        val to = TextTranslatorLanguage.ENGLISH
        val prompt = TranslationPrompts.pass1SystemPrompt(from, to)

        prompt shouldContain "Japanese"
        prompt shouldContain "English"
        prompt shouldContain "pro-drop"
        prompt shouldContain "ID|Translated Text"
    }

    @Test
    fun `prompt requires pipe-delimited output`() {
        val prompt = TranslationPrompts.pass1SystemPrompt(
            TextRecognizerLanguage.CHINESE,
            TextTranslatorLanguage.ENGLISH,
        )
        prompt shouldContain "ID|Translated Text"
        prompt shouldContain "Output ONLY the `ID|Translated Text` lines"
        prompt shouldNotContain "[SPEECH]"
        prompt shouldNotContain "[FLAG]"
        prompt shouldNotContain "[OK]"
    }

    @Test
    fun `parseLine parses different format variations correctly`() {
        with(TranslationPrompts) {
            parseLine("b0|I'm going.") shouldBe ParsedLine("b0", "I'm going.")
            parseLine("b1| That day, I met him. ") shouldBe ParsedLine("b1", "That day, I met him.")
            parseLine("b2|Three years later — Tokyo.|") shouldBe ParsedLine("b2", "Three years later — Tokyo.")
            parseLine("b3 | He said he wouldn't come.") shouldBe ParsedLine("b3", "He said he wouldn't come.")
            parseLine("invalid") shouldBe null
        }
    }
}
