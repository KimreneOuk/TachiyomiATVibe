package eu.kanade.translation.translator.contextual
import eu.kanade.translation.translator.TextTranslatorLanguage

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

        request.orderedIds shouldBe listOf("p7_b0", "p2_b0")
        request.locations["p7_b0"] shouldBe TargetLocation("page-2.jpg", 0)
        request.locations["p2_b0"] shouldBe TargetLocation("page-7.jpg", 0)
        request.idMap["p7_b0"] shouldBe AnchoredTargetKey(7, 0)
        request.idMap["p2_b0"] shouldBe AnchoredTargetKey(2, 0)
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

        request.orderedIds shouldBe listOf("p1_b0", "p0_b0")
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
    fun `strict malformed batch response flags errors but applies valid blocks`() {
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
            p0_b0|First translation
            p0_b0|Duplicate translation
            p0_b1|${" ".repeat(3)}
            p0_b2|Unknown translation
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.duplicateIds.contains("p0_b0"))
        assertTrue(batch.unknownIds.contains("p0_b2"))
        assertTrue(batch.validationErrors.any { it.contains("Blank required output") })

        applyBatchToChunk(chunk, batch)
        page.blocks[0].translation shouldBe "First translation"
        page.blocks[1].translation shouldBe ""
    }

    @Test
    fun `strict batch response rejects missing and unknown ids`() {
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
            p99_b99|Unknown id
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.missingIds.contains("p0_b0"))
        assertTrue(batch.missingIds.contains("p0_b1"))
        assertTrue(batch.unknownIds.contains("p99_b99"))
    }

    @Test
    fun `valid batch response applies every page block`() {
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
            p0000_b0000|First translation
            p0000_b0001|Second translation
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertTrue(batch.isStructurallyValid)
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
            p0000_b0000|Translated target${"   \t"}
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)

        assertTrue(batch.isStructurallyValid)
        applyBatchToChunk(chunk, batch)
        page.blocks.single().translation shouldBe "Translated target"
    }

    @Test
    fun `strict batch requires all requested ids across pages`() {
        val first = PageTranslation(blocks = mutableListOf(block("first")))
        val second = PageTranslation(blocks = mutableListOf(block("second")))
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("first.jpg" to first, "second.jpg" to second),
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
        val missingSecondPage = """
            p0000_b0000|First
        """.trimIndent()
        val completeResponse = """
            p0000_b0000|First
            p0001_b0000|Second
        """.trimIndent()

        val missingBatch = ContextualResponseParser.parseBatch(missingSecondPage, request)
        val validBatch = ContextualResponseParser.parseBatch(completeResponse, request)

        assertFalse(missingBatch.isStructurallyValid)
        assertTrue(missingBatch.missingIds.contains("p1_b0"))
        assertTrue(validBatch.isStructurallyValid)
    }

    @Test
    fun `exact requested ids recover without protocol framing`() {
        val page = PageTranslation(blocks = mutableListOf(block("first"), block("second", x = 20f)))
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

        val batch = ContextualResponseParser.parseBatch(
            "p0000_b0000|First translation\np0000_b0001|Second translation",
            request,
        )

        assertTrue(batch.isStructurallyValid)
        assertTrue(batch.framingRecovered)
        applyBatchToChunk(chunk, batch)
        page.blocks.map { it.translation } shouldBe listOf("First translation", "Second translation")
    }

    @Test
    fun `framing recovery remains fail closed for missing duplicate and unknown ids`() {
        val page = PageTranslation(blocks = mutableListOf(block("first"), block("second", x = 20f)))
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

        val missing = ContextualResponseParser.parseBatch("p0000_b0000|Only one", request)
        val duplicate = ContextualResponseParser.parseBatch(
            "p0000_b0000|First\np0000_b0000|Again\np0000_b0001|Second",
            request,
        )
        val unknown = ContextualResponseParser.parseBatch(
            "p0000_b0000|First\np0000_b0001|Second\np0000_b9999|Unknown",
            request,
        )

        assertFalse(missing.isStructurallyValid)
        assertFalse(duplicate.isStructurallyValid)
        assertFalse(unknown.isStructurallyValid)
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

    // region T934 separator-salvage (corrupted `ID|text` frame on short emphatic last-page lines)

    @Test
    fun `salvage accepts corrupted separator with exact requested id`() {
        val (chunk, request) = singlePageRequest("first", "second")
        val batch = ContextualResponseParser.parseBatch(
            "p0_b0|First translation\np0_b1>Hnn...!",
            request,
        )

        assertTrue(batch.isStructurallyValid)
        assertTrue(batch.framingRecovered)
        applyBatchToChunk(chunk, batch)
        val page = chunk.pages.getValue("page.jpg")
        page.blocks[0].translation shouldBe "First translation"
        page.blocks[1].translation shouldBe "Hnn...!"
    }

    @Test
    fun `salvage accepts colon and full-width colon separators`() {
        val (chunk, request) = singlePageRequest("first", "second", "third")
        val batch = ContextualResponseParser.parseBatch(
            "p0_b0:First translation\np0_b1：Second translation\np0_b2|Third translation",
            request,
        )

        assertTrue(batch.isStructurallyValid)
        applyBatchToChunk(chunk, batch)
        chunk.pages.getValue("page.jpg").blocks.map { it.translation } shouldBe
            listOf("First translation", "Second translation", "Third translation")
    }

    @Test
    fun `salvage rejects unknown block id with corrupted separator`() {
        val (chunk, request) = singlePageRequest("first")
        val batch = ContextualResponseParser.parseBatch("p0_b9>Hnn...!", request)

        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.validationErrors.any { it.contains("Malformed line") })
        applyBatchToChunk(chunk, batch)
        chunk.pages.getValue("page.jpg").blocks.single().translation shouldBe ""
    }

    @Test
    fun `salvage rejects blank remainder and digit-extended ids`() {
        val (chunk, request) = singlePageRequest(*Array(7) { "gasp$it" }, naturalPageIndex = 12)
        val batch = ContextualResponseParser.parseBatch(
            "p12_b0|Hnn...!\n" +
                "p12_b6>\n" +
                "p12_b67>Ku...!\n" +
                "p12_b6",
            request,
        )

        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.validationErrors.all { it.contains("Malformed line") || it.startsWith("Missing") })
        applyBatchToChunk(chunk, batch)
        val page = chunk.pages.getValue("page.jpg")
        page.blocks[0].translation shouldBe "Hnn...!"
        page.blocks[6].translation shouldBe ""
    }

    @Test
    fun `duplicate handling unchanged with mixed valid and salvaged lines`() {
        val (chunk, request) = singlePageRequest("first", "second")
        val batch = ContextualResponseParser.parseBatch(
            "p0_b0|First.\n" +
                "p0_b0>Salvaged duplicate.\n" +
                "p0_b1>Salvaged second.\n" +
                "p0_b1|Valid duplicate.",
            request,
        )

        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.duplicateIds.contains("p0_b0"))
        assertTrue(batch.duplicateIds.contains("p0_b1"))
        applyBatchToChunk(chunk, batch)
        val page = chunk.pages.getValue("page.jpg")
        page.blocks[0].translation shouldBe "First."
        page.blocks[1].translation shouldBe "Salvaged second."
    }

    @Test
    fun `salvage rescues t934 last-page separator corruption envelope`() {
        // Mirror of the 2026-09-17 on-device failure (envelope e-0): every line of
        // the envelope's LAST page comes back with `>` instead of `|`; the strict
        // parser dropped them, retries re-corrupted, budget exhausted. The whole
        // envelope must now parse and complete on the first attempt.
        val earlyPage = PageTranslation(blocks = mutableListOf(block("He stepped closer.", x = 0f)))
        val lastPage = PageTranslation(
            blocks = mutableListOf(
                block("く...ッ!", x = 0f),
                block("ん...っ", x = 10f),
                block("あ...ッ", x = 20f),
            ),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("p009.jpg" to earlyPage, "p013.jpg" to lastPage),
            blockCount = 4,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("p009.jpg" to 8, "p013.jpg" to 12),
        )
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )

        val batch = ContextualResponseParser.parseBatch(
            "p8_b0|He stepped closer.\n" +
                "p12_b0>Ku...!\n" +
                "p12_b1>Hnn...!\n" +
                "p12_b2>Ahh...!",
            request,
        )

        assertTrue(batch.isStructurallyValid)
        assertTrue(batch.framingRecovered)
        applyBatchToChunk(chunk, batch)
        lastPage.blocks.map { it.translation } shouldBe listOf("Ku...!", "Hnn...!", "Ahh...!")
        earlyPage.blocks.single().translation shouldBe "He stepped closer."
    }

    // endregion

    private fun singlePageRequest(
        vararg blocks: String,
        naturalPageIndex: Int = 0,
    ): Pair<TranslationContextChunk, ContextualRequestBuilder.Request> {
        val page = PageTranslation(
            blocks = blocks.mapIndexed { index, text -> block(text, x = index * 10f) }.toMutableList(),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = blocks.size,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            pageIndexes = mapOf("page.jpg" to naturalPageIndex),
        )
        return chunk to ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
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
