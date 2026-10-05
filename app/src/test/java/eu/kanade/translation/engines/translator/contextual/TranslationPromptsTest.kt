package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.engines.translator.contextual.TranslationPrompts.ParsedLine
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
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
        val jaPrompt = TranslationPrompts.pass1SystemPrompt(
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        jaPrompt shouldContain "from Japanese into natural English."
        jaPrompt shouldNotContain "pro-drop"

        val dePrompt = TranslationPrompts.pass1SystemPrompt(
            TextRecognizerLanguage.GERMAN,
            TextTranslatorLanguage.FRENCH,
        )
        dePrompt shouldContain "from German into natural French."
        dePrompt shouldNotContain "pro-drop"
    }

    @Test
    fun `contextPrefix is empty when both inputs are blank`() {
        TranslationPrompts.contextPrefix(rollingContext = "   ") shouldBe ""
    }

    @Test
    fun `contextPrefix emits only rolling history`() {
        val out = TranslationPrompts.contextPrefix(
            rollingContext = "源 => source",
        )
        out shouldContain "BACKGROUND:"
        out shouldContain "源 => source"
        out.contains("Established terms") shouldBe false
        out.contains("太郎 => Taro") shouldBe false
    }

    @Test
    fun `system prompt carries relevant instructions`() {
        val from = TextRecognizerLanguage.JAPANESE
        val to = TextTranslatorLanguage.ENGLISH
        val prompt = TranslationPrompts.pass1SystemPrompt(from, to)

        prompt shouldContain "from Japanese into natural English."
        prompt shouldContain "Copy each item's ID unchanged before \"|\""
        prompt shouldContain "Return one line per source item only"
        prompt shouldNotContain "pro-drop"
        prompt shouldNotContain "comic English"
    }

    @Test
    fun `prompt uses selected languages and only the simple numeric item contract`() {
        val from = TextRecognizerLanguage.CHINESE
        val to = TextTranslatorLanguage.ENGLISH
        val prompt = TranslationPrompts.pass1SystemPrompt(from, to, batchProtocol = true)

        prompt shouldBe """
            The BACKGROUND section is context only; do not translate or reproduce it.
            Translate each item in SOURCE ITEMS from ${from.label} into natural ${to.label}.
            Preserve meaning and tone, including sound effects.
            Copy each item's numeric ID unchanged before "|" and write its translation after it.
            Return one line per source item only, with no additional text.
        """.trimIndent()
        prompt shouldNotContain "English comic"
        prompt shouldNotContain "p0_b0"
        prompt shouldNotContain "ID|Translated Text"
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
