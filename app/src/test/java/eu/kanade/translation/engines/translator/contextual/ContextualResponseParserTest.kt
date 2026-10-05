package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
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

        request.orderedIds shouldBe listOf("1", "2")
        request.locations["1"] shouldBe TargetLocation("page-7.jpg", 0)
        request.locations["2"] shouldBe TargetLocation("page-2.jpg", 0)
        request.idMap["1"] shouldBe AnchoredTargetKey(2, 0)
        request.idMap["2"] shouldBe AnchoredTargetKey(7, 0)
    }

    @Test
    fun `batch builder rejects missing plan page indexes instead of falling back to map order`() {
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

        shouldThrow<IllegalArgumentException> {
            ContextualRequestBuilder.build(
                chunk,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
        }

        listOf(
            mapOf("missing.jpg" to 0, "natural.jpg" to 0),
            mapOf("missing.jpg" to -1, "natural.jpg" to 0),
        ).forEach { invalidPlanIndexes ->
            shouldThrow<IllegalArgumentException> {
                ContextualRequestBuilder.build(
                    chunk.copy(pageIndexes = invalidPlanIndexes),
                    TextRecognizerLanguage.JAPANESE,
                    TextTranslatorLanguage.ENGLISH,
                )
            }
        }
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

        request.orderedIds shouldBe listOf("1", "2")
        request.idMap["1"] shouldBe AnchoredTargetKey(3, 1)
        request.idMap["2"] shouldBe AnchoredTargetKey(3, 0)
        request.promptLines shouldBe listOf("1|right", "2|left")
    }

    @Test
    fun `strict malformed batch response salvages unique rows and leaves conflicting ids unresolved`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block("first"),
                block("second", x = 20f),
                block("third", x = 40f),
            ),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 3,
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
            1|First translation
            1|Conflicting translation
            2|Second translation
            3|${" ".repeat(3)}
            4|Unknown translation
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.duplicateIds.contains("1"))
        assertTrue(batch.unknownIds.contains("4"))
        assertTrue(batch.validationErrors.any { it.contains("Blank required output") })

        applyBatchToChunk(chunk, batch)
        page.blocks[0].translation shouldBe ""
        page.blocks[1].translation shouldBe "Second translation"
        page.blocks[2].translation shouldBe ""

        val refusedBatch = ContextualResponseParser.parseBatch(
            "I can't assist with that request.\n1|Must not be applied",
            request,
        )
        applyBatchToChunk(chunk, refusedBatch)
        page.blocks[0].translation shouldBe ""
        assertTrue(refusedBatch.validationErrors.contains(STRUCTURAL_REFUSAL_DIAGNOSTIC))
        assertFalse(refusedBatch.validationErrors.any { it.contains("can't assist") })

        val (duplicateChunk, duplicateRequest) = singlePageRequest("first", "second")
        val identicalAndBlankDuplicates = ContextualResponseParser.parseBatch(
            "01|First translation\n" +
                "1:First translation\n" +
                "2|   \n" +
                "02>Second translation\n" +
                "99|unrequested extra",
            duplicateRequest,
        )
        identicalAndBlankDuplicates.accepted.map { it.id to it.text } shouldContainExactly listOf(
            "1" to "First translation",
            "2" to "Second translation",
        )
        applyBatchToChunk(duplicateChunk, identicalAndBlankDuplicates)
        duplicateChunk.pages.getValue("page.jpg").blocks.map { it.translation } shouldContainExactly listOf(
            "First translation",
            "Second translation",
        )

        val (unicodeChunk, unicodeRequest) = singlePageRequest("first", "second")
        val canonicalDuplicate = ContextualResponseParser.parseBatch(
            "1|caf\u00e9\n01:cafe\u0301\n2|Second translation",
            unicodeRequest,
        )
        canonicalDuplicate.accepted.map { it.id to it.text } shouldContainExactly listOf(
            "1" to "caf\u00e9",
            "2" to "Second translation",
        )
        applyBatchToChunk(unicodeChunk, canonicalDuplicate)
        unicodeChunk.pages.getValue("page.jpg").blocks.map { it.translation } shouldContainExactly listOf(
            "caf\u00e9",
            "Second translation",
        )
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
            99|Unknown id
        """.trimIndent()

        val batch = ContextualResponseParser.parseBatch(rawResponse, request)
        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.missingIds.contains("1"))
        assertTrue(batch.missingIds.contains("2"))
        assertTrue(batch.unknownIds.contains("99"))
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
            1|First translation
            2|Second translation
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
            1|Translated target${"   \t"}
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
            1|First
        """.trimIndent()
        val completeResponse = """
            1|First
            2|Second
        """.trimIndent()

        val missingBatch = ContextualResponseParser.parseBatch(missingSecondPage, request)
        val validBatch = ContextualResponseParser.parseBatch(completeResponse, request)

        assertFalse(missingBatch.isStructurallyValid)
        assertTrue(missingBatch.missingIds.contains("2"))
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
            "1|First translation\n2|Second translation",
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

        val missing = ContextualResponseParser.parseBatch("1|Only one", request)
        val duplicate = ContextualResponseParser.parseBatch(
            "1|First\n1|Again\n2|Second",
            request,
        )
        val unknown = ContextualResponseParser.parseBatch(
            "1|First\n2|Second\n99|Unknown",
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

    // region  separator-salvage (corrupted `ID|text` frame on short emphatic last-page lines)

    @Test
    fun `salvage accepts corrupted separator with exact requested id`() {
        val (chunk, request) = singlePageRequest("first", "second")
        val batch = ContextualResponseParser.parseBatch(
            "1|First translation\n2>Hnn...!",
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
            "1:First translation\n2：Second translation\n3|Third translation",
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
        val batch = ContextualResponseParser.parseBatch("9>Hnn...!", request)

        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.validationErrors.any { it.startsWith("Unknown id") })
        applyBatchToChunk(chunk, batch)
        chunk.pages.getValue("page.jpg").blocks.single().translation shouldBe ""
    }

    @Test
    fun `salvage rejects blank remainder and digit-extended ids`() {
        val (chunk, request) = singlePageRequest(*Array(7) { "gasp$it" }, naturalPageIndex = 12)
        val batch = ContextualResponseParser.parseBatch(
            "1|Hnn...!\n" +
                "7>\n" +
                "67>Ku...!\n" +
                "7",
            request,
        )

        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.validationErrors.any { it.startsWith("Blank required output") })
        assertTrue(batch.validationErrors.any { it.startsWith("Unknown id") })
        assertTrue(batch.validationErrors.any { it.startsWith("Malformed line") })
        applyBatchToChunk(chunk, batch)
        val page = chunk.pages.getValue("page.jpg")
        page.blocks[0].translation shouldBe "Hnn...!"
        page.blocks[6].translation shouldBe ""
    }

    @Test
    fun `duplicate handling unchanged with mixed valid and salvaged lines`() {
        val (chunk, request) = singlePageRequest("first", "second")
        val batch = ContextualResponseParser.parseBatch(
            "1|First.\n" +
                "1>Conflicting duplicate.\n" +
                "2>Salvaged second.\n" +
                "2|Conflicting duplicate.",
            request,
        )

        assertFalse(batch.isStructurallyValid)
        assertTrue(batch.duplicateIds.contains("1"))
        assertTrue(batch.duplicateIds.contains("2"))
        applyBatchToChunk(chunk, batch)
        val page = chunk.pages.getValue("page.jpg")
        page.blocks[0].translation shouldBe ""
        page.blocks[1].translation shouldBe ""
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
            "1|He stepped closer.\n" +
                "2>Ku...!\n" +
                "3>Hnn...!\n" +
                "4>Ahh...!",
            request,
        )

        assertTrue(batch.isStructurallyValid)
        assertTrue(batch.framingRecovered)
        applyBatchToChunk(chunk, batch)
        lastPage.blocks.map { it.translation } shouldBe listOf("Ku...!", "Hnn...!", "Ahh...!")
        earlyPage.blocks.single().translation shouldBe "He stepped closer."
    }

    // endregion

    @Test
    fun numericCandidatesRecoverUniqueItemsAndLeaveConflictsUnresolved() {
        val request = ContextualRequestBuilder.Request(
            idMap = linkedMapOf(
                "1" to AnchoredTargetKey(1, 0),
                "2" to AnchoredTargetKey(2, 2),
                "3" to AnchoredTargetKey(2, 10),
            ),
            orderedIds = listOf("1", "2", "3"),
            locations = linkedMapOf(
                "1" to TargetLocation("001.jpg", 0),
                "2" to TargetLocation("002.jpg", 1),
                "3" to TargetLocation("002.jpg", 0),
            ),
            promptLines = listOf("1|one", "2|two", "3|three"),
            protocol = ContextualRequestProtocol.BATCH_V1,
        )

        val recovered = ContextualResponseParser.parseBatch(
            """
            Here are the translations:
            ```text
              - 01: one
            * 1→ one
            • 1; one
            1 - one
            1 one
            2 > two
            2：two
            3 | three
            3	three
            ```
            """.trimIndent(),
            request,
        )
        recovered.accepted.map { it.id to it.text } shouldContainExactly listOf(
            "1" to "one",
            "2" to "two",
            "3" to "three",
        )

        val conflict = ContextualResponseParser.parseBatch(
            """1|first
            1|different
            2|kept
            4|unknown
            """.trimIndent(),
            request,
        )
        conflict.accepted.map { it.id to it.text } shouldContainExactly listOf("2" to "kept")
        conflict.rejected.any { it.id == "4" } shouldBe true

        val ambiguous = ContextualResponseParser.parseBatch(
            """maybe the first line is item one
            blank|
            |no id
            p1_b0|durable ID is not a batch request ID
            2|two
            """.trimIndent(),
            request,
        )
        // Malformed prose/rows are diagnostic, but do not erase an independently unique item.
        assertFalse(ambiguous.isStructurallyValid)
        ambiguous.accepted.map { it.id to it.text } shouldContainExactly listOf("2" to "two")
        ambiguous.validationErrors.count { it.startsWith("Malformed line") } shouldBe 4

        val ordinalWithoutExplicitId = ContextualResponseParser.parseBatch(
            "1. translated text\n2|explicit item",
            request,
        )
        ordinalWithoutExplicitId.accepted.map { it.id to it.text } shouldContainExactly
            listOf("2" to "explicit item")
        ordinalWithoutExplicitId.validationErrors.any { it.startsWith("Malformed line") } shouldBe true

        val explicitlyNumberedWrapper = ContextualResponseParser.parseBatch("1. 2|wrapped item", request)
        explicitlyNumberedWrapper.accepted.map { it.id to it.text } shouldContainExactly
            listOf("2" to "wrapped item")

        val explicitlyParenthesizedWrapper = ContextualResponseParser.parseBatch("1) 2|wrapped item", request)
        explicitlyParenthesizedWrapper.accepted.map { it.id to it.text } shouldContainExactly
            listOf("2" to "wrapped item")
    }

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
