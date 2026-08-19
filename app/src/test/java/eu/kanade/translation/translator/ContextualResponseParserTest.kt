package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextualResponseParserTest {

    @Test
    fun `complete response translates every known id`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val lines = listOf("b0|Hello.", "b1|World!")
        val results = ContextualResponseParser.parse(lines, idMap)

        results shouldHaveSize 2
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "Hello."
        results[1].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[1].text shouldBe "World!"
    }

    @Test
    fun `unknown id is rejected and never applied`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b9|Ghost."), idMap)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].targetKey shouldBe null
    }

    @Test
    fun `blank payload is rejected`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b0|   "), idMap)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
    }

    @Test
    fun `malformed line is rejected`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("this has no id prefix"), idMap)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].targetKey shouldBe null
    }

    @Test
    fun `duplicate id keeps first and rejects the second as ambiguous`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(
            listOf("b0|First.", "b0|Second."),
            idMap,
        )
        results shouldHaveSize 2
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "First."
        results[1].status shouldBe ContextualTranslationResult.Status.REJECTED
    }

    @Test
    fun `missing id in response leaves that block untouched`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val results = ContextualResponseParser.parse(listOf("b0|Only."), idMap)
        results shouldHaveSize 1
        results[0].id shouldBe "b0"
    }

    @Test
    fun `batch accepted and rejected partition correctly`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val results = ContextualResponseParser.parse(
            listOf("b0|Good.", "b1|   ", "xx|garbage"),
            idMap,
        )
        results.filter { it.status == ContextualTranslationResult.Status.TRANSLATED } shouldHaveSize 1
        results.filter { it.status == ContextualTranslationResult.Status.REJECTED } shouldHaveSize 2
    }

    @Test
    fun `batch ids include natural page and stable block indexes`() {
        val first = PageTranslation(
            blocks = mutableListOf(block("first", x = 10f)),
        )
        val second = PageTranslation(
            blocks = mutableListOf(block("second", x = 10f)),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page-2.jpg" to second, "page-7.jpg" to first),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page-2.jpg" to 7, "page-7.jpg" to 2),
        )

        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )

        request.orderedIds shouldBe listOf("p0007_b0000", "p0002_b0000")
        request.locations["p0007_b0000"] shouldBe TargetLocation("page-2.jpg", 0)
        request.locations["p0002_b0000"] shouldBe TargetLocation("page-7.jpg", 0)
        request.idMap["p0007_b0000"] shouldBe AnchoredTargetKey(7, 0)
        request.idMap["p0002_b0000"] shouldBe AnchoredTargetKey(2, 0)
    }

    @Test
    fun `missing builder page indexes avoid supplied natural index collisions`() {
        val chunk = TranslationContextChunk(
            pages = linkedMapOf(
                "missing.jpg" to PageTranslation(blocks = mutableListOf(block("missing"))),
                "natural.jpg" to PageTranslation(blocks = mutableListOf(block("natural", x = 20f))),
            ),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("natural.jpg" to 0),
        )

        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )

        request.orderedIds shouldBe listOf("p0001_b0000", "p0000_b0000")
    }

    @Test
    fun `stable block ids survive a changed reading order`() {
        val left = block("left", x = 10f)
        val right = block("right", x = 20f)
        val page = PageTranslation(blocks = mutableListOf(left, right))
        StableBlockIds.assign(page, naturalPageIndex = 3)
        val idsByText = page.blocks.associate { it.text to it.blockId }

        page.blocks.reverse()
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 3),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )

        request.orderedIds shouldBe listOf(idsByText.getValue("right"), idsByText.getValue("left"))
    }

    @Test
    fun `strict malformed batch response promotes nothing`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block("first"),
                block("second", x = 20f),
            ),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 0),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val rawResponse = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First translation
            p0000_b0000|Duplicate translation
            p0000_b0001|${" ".repeat(3)}
            p0000_b0002|Unknown translation
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.duplicateIds.contains("p0000_b0000"))
        assertTrue(batch.unknownIds.contains("p0000_b0002"))
        assertTrue(batch.validationErrors.any { it.contains("Blank required output") })

        applyBatchToChunk(chunk, batch)
        page.blocks[0].translation shouldBe ""
        page.blocks[1].translation shouldBe ""
    }

    @Test
    fun `strict batch response rejects missing and normalized ids`() {
        val page = PageTranslation(
            blocks = mutableListOf(block("first"), block("second", x = 20f)),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 0),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val rawResponse = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0_b0|Normalized id
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.missingIds.contains("p0000_b0000"))
        assertTrue(batch.missingIds.contains("p0000_b0001"))
        assertTrue(batch.validationErrors.any { it.contains("Normalized") })
    }

    @Test
    fun `valid batch response applies every page block and keeps page scoped delta`() {
        val page = PageTranslation(
            blocks = mutableListOf(block("first"), block("second", x = 20f)),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 0),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val rawResponse = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First translation
            p0000_b0001|Second translation
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.CONTEXT_DELTA_START} p0000
            future delta data
            ${BatchTranslationProtocol.CONTEXT_DELTA_END} p0000
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertTrue(batch.isStructurallyValid)
        batch.contextDeltas["p0000"] shouldBe "future delta data"
        applyBatchToChunk(chunk, batch)
        page.blocks[0].translation shouldBe "First translation"
        page.blocks[1].translation shouldBe "Second translation"
    }

    @Test
    fun `strict batch tolerates trailing target whitespace but keeps id exact`() {
        val page = PageTranslation(blocks = mutableListOf(block("first")))
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 0),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val rawResponse = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|Translated target${"   \t"}
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)

        assertTrue(batch.isStructurallyValid)
        applyBatchToChunk(chunk, batch)
        page.blocks.single().translation shouldBe "Translated target"
    }

    @Test
    fun `strict batch requires every requested page section`() {
        val first = PageTranslation(blocks = mutableListOf(block("first")))
        val empty = PageTranslation(blocks = mutableListOf())
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("first.jpg" to first, "empty.jpg" to empty),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("first.jpg" to 0, "empty.jpg" to 1),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val missingEmptyPage = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()
        val validEmptyPage = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.PAGE_START} p0001
            ${BatchTranslationProtocol.PAGE_END} p0001
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val missingBatch = ContextualResponseParser.parseBatch(missingEmptyPage, request)
        val validBatch = ContextualResponseParser.parseBatch(validEmptyPage, request)

        assertFalse(missingBatch.isStructurallyValid)
        assertTrue(missingBatch.validationErrors.any { it.contains("Missing page section 'p0001'") })
        assertTrue(validBatch.isStructurallyValid)
    }

    @Test
    fun `strict batch rejects wrong page ids`() {
        val chunk = TranslationContextChunk(
            pages = linkedMapOf(
                "first.jpg" to PageTranslation(blocks = mutableListOf(block("first"))),
                "second.jpg" to PageTranslation(blocks = mutableListOf(block("second"))),
            ),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("first.jpg" to 0, "second.jpg" to 1),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val rawResponse = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0001_b0000|Wrong page
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.PAGE_START} p0001
            p0001_b0000|Second
            ${BatchTranslationProtocol.PAGE_END} p0001
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)

        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.validationErrors.any { it.contains("outside page section") })
    }

    @Test
    fun `strict batch rejects footer trailing content and missing header`() {
        val page = PageTranslation(blocks = mutableListOf(block("first")))
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 0),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val afterFooter = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.RESPONSE_END}
            extra content
        """.trimIndent()
        val wrongHeader = """
            WRONG_HEADER
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val footerBatch = ContextualResponseParser.parseBatch(afterFooter, request)
        val headerBatch = ContextualResponseParser.parseBatch(wrongHeader, request)

        assertFalse(footerBatch.isStructurallyValid)
        assertTrue(footerBatch.validationErrors.any { it.contains("after response footer") })
        assertFalse(headerBatch.isStructurallyValid)
        assertTrue(headerBatch.validationErrors.any { it.contains("Missing batch response header") })
    }

    @Test
    fun `strict batch rejects unclosed page and delta sections`() {
        val page = PageTranslation(blocks = mutableListOf(block("first")))
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 0),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val unclosedPage = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()
        val unclosedDelta = """
            ${BatchTranslationProtocol.RESPONSE_HEADER}
            ${BatchTranslationProtocol.PAGE_START} p0000
            p0000_b0000|First
            ${BatchTranslationProtocol.PAGE_END} p0000
            ${BatchTranslationProtocol.CONTEXT_DELTA_START} p0000
            delta
            ${BatchTranslationProtocol.RESPONSE_END}
        """.trimIndent()

        val pageBatch = ContextualResponseParser.parseBatch(unclosedPage, request)
        val deltaBatch = ContextualResponseParser.parseBatch(unclosedDelta, request)

        assertFalse(pageBatch.isStructurallyValid)
        assertTrue(pageBatch.validationErrors.any { it.contains("Unclosed page section") })
        assertFalse(deltaBatch.isStructurallyValid)
        assertTrue(deltaBatch.validationErrors.any { it.contains("Unclosed context delta") })
    }

    @Test
    fun `structural failure exposes only bounded reason counts`() {
        val page = PageTranslation(blocks = mutableListOf(block("private source")))
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to 0),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val batch = ContextualResponseParser.parseBatch("not a protocol response", request)

        val failure = batch.structuralFailure
        assertTrue(failure != null)
        assertTrue(failure!!.safeSummary().contains("expected=1"))
        assertFalse(failure.safeSummary().contains("private source"))
    }

    private fun linkedIdMap(vararg entries: Pair<String, Int>): LinkedHashMap<String, AnchoredTargetKey> {
        val map = LinkedHashMap<String, AnchoredTargetKey>()
        entries.forEach { (id, blockIdx) -> map[id] = AnchoredTargetKey(0, blockIdx) }
        return map
    }

    private fun block(text: String, x: Float = 0f): TranslationBlock = TranslationBlock(
        text = text,
        width = 10f,
        height = 10f,
        x = x,
        y = 0f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )
}
