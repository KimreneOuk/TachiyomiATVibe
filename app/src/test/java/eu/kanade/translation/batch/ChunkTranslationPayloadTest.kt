package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.AITranslator
import eu.kanade.translation.translator.AITranslatorResponseParser
import eu.kanade.translation.translator.BaseTranslator
import eu.kanade.translation.translator.ContextualRequestBuilder
import eu.kanade.translation.translator.TextTranslatorLanguage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkTranslationPayloadTest {

    @Test
    fun `ai prompt includes rolling context glossary and scene summary`() {
        val packet = RollingContextPacket(
            glossary = mapOf("Jin-Woo" to "Protagonist"),
            microSummary = "Jin-Woo levels up.",
        )
        val promptContext = packet.toPromptContext()
        assertTrue(promptContext.contains("Jin-Woo: Protagonist"))
        assertTrue(promptContext.contains("Previous Scene Summary: Jin-Woo levels up."))
    }

    @Test
    fun `toPromptContext formats multiple glossary entries and micro summary accurately`() {
        val packet = RollingContextPacket(
            glossary = mapOf(
                "Sung Jin-Woo" to "Shadow Monarch",
                "Igris" to "Blood-Red Knight",
            ),
            microSummary = "Jin-Woo extracts the shadow of the fallen commander.",
        )
        val prompt = packet.toPromptContext()
        prompt shouldContain "Previous Scene Summary: Jin-Woo extracts the shadow of the fallen commander."
        prompt shouldContain "Established Glossary:"
        prompt shouldContain "- Sung Jin-Woo: Shadow Monarch"
        prompt shouldContain "- Igris: Blood-Red Knight"
    }

    @Test
    fun `toPromptContext returns empty string when glossary and micro summary are empty`() {
        val emptyPacket = RollingContextPacket(glossary = emptyMap(), microSummary = "")
        emptyPacket.toPromptContext() shouldBe ""

        val blankPacket = RollingContextPacket(glossary = emptyMap(), microSummary = "   ")
        blankPacket.toPromptContext() shouldBe ""
    }

    @Test
    fun `ai prompt serialization injects rolling context and source lines`() {
        val packet = RollingContextPacket(
            glossary = mapOf("Hunter" to "Ranker"),
            microSummary = "The party entered the dungeon.",
        )
        val request = ContextualRequestBuilder.Request(
            idMap = mapOf("b0" to eu.kanade.translation.translator.AnchoredTargetKey(0, 0)),
            orderedIds = listOf("b0"),
            locations = mapOf("b0" to eu.kanade.translation.translator.TargetLocation("p1", 0)),
            promptLines = listOf("b0|おはよう"),
        )

        val prompt = AITranslator.buildPromptWithRollingContext(request, packet)
        prompt shouldContain "Previous Scene Summary: The party entered the dungeon."
        prompt shouldContain "- Hunter: Ranker"
        prompt shouldContain "b0|おはよう"
    }

    @Test
    fun `parsing ai batch response extracts translation blocks along with updated glossary entries and micro-summary`() {
        val rawResponse = """
            [GLOSSARY]
            Sung Jin-Woo: Shadow Monarch
            Igris: Blood-Red Commander
            [END GLOSSARY]

            [SUMMARY]
            Jin-Woo successfully defeats the red knight and extracts his shadow.
            [END SUMMARY]

            b0|Arise.
            b1|You are now my shadow soldier.
        """.trimIndent()

        val parsed = AITranslator.parseChunkResponse(rawResponse)

        parsed.translations shouldContainExactly mapOf(
            "b0" to "Arise.",
            "b1" to "You are now my shadow soldier.",
        )
        parsed.updatedGlossary shouldContainExactly mapOf(
            "Sung Jin-Woo" to "Shadow Monarch",
            "Igris" to "Blood-Red Commander",
        )
        parsed.microSummary shouldBe "Jin-Woo successfully defeats the red knight and extracts his shadow."

        val packet = parsed.toRollingContextPacket()
        packet.glossary shouldContainExactly mapOf(
            "Sung Jin-Woo" to "Shadow Monarch",
            "Igris" to "Blood-Red Commander",
        )
        packet.microSummary shouldBe "Jin-Woo successfully defeats the red knight and extracts his shadow."
    }

    @Test
    fun `parsing ai batch response with header format extracts translations and context`() {
        val rawResponse = """
            Established Glossary:
            - Cha Hae-In: S-Rank Hunter
            - Kaisel: Wyvern

            Previous Scene Summary: The raid team arrives at the Jeju Island gate.

            b0|Let's begin the subjugation.
            b1|Stay on guard!
        """.trimIndent()

        val parsed = AITranslatorResponseParser.parse(rawResponse)

        parsed.translations shouldContainExactly mapOf(
            "b0" to "Let's begin the subjugation.",
            "b1" to "Stay on guard!",
        )
        parsed.updatedGlossary shouldContainExactly mapOf(
            "Cha Hae-In" to "S-Rank Hunter",
            "Kaisel" to "Wyvern",
        )
        parsed.microSummary shouldBe "The raid team arrives at the Jeju Island gate."
    }

    @Test
    fun `parsing ai batch response with thinking tags strips thinking block cleanly`() {
        val rawResponse = """
            <think>
            Analyzing Japanese dialogue...
            b0 means 'Let's go'
            Glossary term detected: 'Jinwoo' -> 'Protagonist'
            </think>
            [GLOSSARY]
            Jinwoo: Protagonist
            [END GLOSSARY]
            [SUMMARY]
            Jinwoo steps forward into the dungeon.
            [END SUMMARY]
            b0|Let's go.
        """.trimIndent()

        val parsed = AITranslator.parseChunkResponse(rawResponse)

        parsed.translations shouldContainExactly mapOf("b0" to "Let's go.")
        parsed.updatedGlossary shouldContainExactly mapOf("Jinwoo" to "Protagonist")
        parsed.microSummary shouldBe "Jinwoo steps forward into the dungeon."
    }

    @Test
    fun `parsing ai response with only translations extracts blocks and empty context`() {
        val rawResponse = """
            b0|Hello world.
            b1|How are you today?
        """.trimIndent()

        val parsed = AITranslator.parseChunkResponse(rawResponse)

        parsed.translations shouldContainExactly mapOf(
            "b0" to "Hello world.",
            "b1" to "How are you today?",
        )
        parsed.updatedGlossary shouldBe emptyMap()
        parsed.microSummary shouldBe ""
    }

    @Test
    fun `standard engine translation pathway correctly maps flat string lists without prompt template overhead`() = runTest {
        val mockStandardEngine = object : BaseTranslator() {
            override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
            override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

            override suspend fun translateFlat(texts: List<String>): List<String> {
                return texts.map { text ->
                    when (text) {
                        "こんにちは" -> "Hello"
                        "さようなら" -> "Goodbye"
                        "ありがとう" -> "Thank you"
                        else -> "Translated: $text"
                    }
                }
            }
        }

        // Direct flat string mapping without LLM formatting/prompt overhead
        val input = listOf("こんにちは", "さようなら", "ありがとう")
        val output = mockStandardEngine.translateFlat(input)

        output shouldContainExactly listOf("Hello", "Goodbye", "Thank you")
    }

    @Test
    fun `standard engine translate applies flat translations to page blocks in place`() = runTest {
        val mockStandardEngine = object : BaseTranslator() {
            override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
            override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

            override suspend fun translateSingleText(text: String): String = when (text) {
                "こんにちは" -> "Hello"
                "世界" -> "World"
                else -> text
            }
        }

        val page = PageTranslation(
            sourceFileName = "page_01.jpg",
            blocks = mutableListOf(
                createBlock("こんにちは"),
                createBlock(""),
                createBlock("世界"),
            ),
        )

        val pages = mutableMapOf("page_01.jpg" to page)
        mockStandardEngine.translate(pages)

        page.blocks[0].translation shouldBe "Hello"
        page.blocks[1].translation shouldBe ""
        page.blocks[2].translation shouldBe "World"
    }

    private fun createBlock(text: String): TranslationBlock = TranslationBlock(
        text = text,
        width = 100f,
        height = 50f,
        x = 10f,
        y = 10f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )
}
