package eu.kanade.translation.translator

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Unit tests for the revision adapter layer (Agent B follow-up).
 *
 * Tests [RevisionRequestBuilder] as the pure conversion layer, then exercises
 * [ContextualResponseParser] with isPass2=true to prove strict K/C/U behaviour
 * against every mandated edge case:
 *   - missing ids -> REJECTED (accounted via missingIds)
 *   - blank correction -> REJECTED
 *   - duplicate id -> second REJECTED, first wins
 *   - unknown id -> REJECTED (targetKey == null)
 *   - malformed line -> REJECTED
 *   - valid kept correction (same text) -> TRANSLATED (merge layer classifies as Kept)
 *   - valid changed correction -> TRANSLATED (merge layer classifies as Corrected)
 *   - empty request group -> EMPTY batch, no network call
 *
 * All tests are pure JVM -- no Android context, no network, no mocks.
 */
class RevisionAdapterTest {

    // ------------------------------------------------------------------ helpers

    private fun flaggedBlock(text: String, draft: String) = TranslationBlock(
        text = text,
        translation = draft,
        width = 10f, height = 10f, x = 0f, y = 0f,
        symHeight = 1f, symWidth = 1f, angle = 0f,
        needsRevision = true,
        userEditedAt = null,
    )

    private fun target(pageKey: String, blockIndex: Int, block: TranslationBlock) =
        RevisionPlanner.Target(pageKey, blockIndex, block)

    private fun group(
        targets: List<RevisionPlanner.Target>,
        nearby: List<RevisionPlanner.DialogueLine> = emptyList(),
        glossary: Map<String, String> = emptyMap(),
        maxOutputTokens: Int = 1024,
    ) = RevisionPlanner.RequestGroup(
        targets = targets,
        nearbyContext = nearby,
        chapterGlossary = glossary,
        estimatedPromptTokens = 100,
        maxOutputTokens = maxOutputTokens,
    )

    private fun parseRevision(
        idMap: Map<String, AnchoredTargetKey>,
        rawLines: List<String>,
    ): List<ContextualTranslationResult> =
        ContextualResponseParser.parse(rawLines, idMap, isPass2 = true)

    // ------------------------------------------------ RevisionRequestBuilder

    @Test
    fun `empty group yields no prompt lines and EMPTY-equivalent batch`() {
        val emptyGroup = group(targets = emptyList())
        val request = RevisionRequestBuilder.build(emptyGroup)
        request.promptLines.shouldBeEmpty()
        request.orderedIds.shouldBeEmpty()

        val batch = RevisionRequestBuilder.toRevisionBatch(request, emptyList())
        batch.idToBlockIndex.shouldBeEmpty()
        batch.results.shouldBeEmpty()
        batch.isPass2 shouldBe true
    }

    @Test
    fun `build assigns anchored ids in reading order`() {
        val b0 = flaggedBlock("source0", "draft0")
        val b1 = flaggedBlock("source1", "draft1")
        val grp = group(targets = listOf(target("p0", 0, b0), target("p0", 1, b1)))
        val request = RevisionRequestBuilder.build(grp)

        request.orderedIds shouldHaveSize 2
        AnchoredBlockId.isAnchored(request.orderedIds[0]) shouldBe true
        AnchoredBlockId.isAnchored(request.orderedIds[1]) shouldBe true
        // Ids must be distinct.
        (request.orderedIds[0] != request.orderedIds[1]) shouldBe true
    }

    @Test
    fun `build captures precondition snapshot from block at request time`() {
        val block = flaggedBlock("src", "myDraft")
        val grp = group(targets = listOf(target("p0", 0, block)))
        val request = RevisionRequestBuilder.build(grp)

        val id = request.orderedIds[0]
        val precond = request.preconditions[id]!!
        precond.draft shouldBe "myDraft"
        precond.fingerprint shouldBe block.stableFingerprint()
        precond.needsRevision shouldBe true
        precond.userEditedAt shouldBe null
    }

    @Test
    fun `buildUserMessage includes prompt lines and omits empty sections`() {
        val b0 = flaggedBlock("Hello", "Hola")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)
        val msg = RevisionRequestBuilder.buildUserMessage(request)

        msg shouldContain "Source: Hello"
        msg shouldContain "Draft: Hola"
        msg.contains("Established terms") shouldBe false
        msg.contains("Surrounding context") shouldBe false
    }

    @Test
    fun `buildUserMessage includes glossary section when non-empty`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(
            targets = listOf(target("p0", 0, b0)),
            glossary = mapOf("太郎" to "Taro"),
        )
        val request = RevisionRequestBuilder.build(grp)
        val msg = RevisionRequestBuilder.buildUserMessage(request)

        msg shouldContain "Established terms"
        msg shouldContain "太郎"
        msg shouldContain "Taro"
    }

    @Test
    fun `buildUserMessage includes context section when nearby is non-empty`() {
        val b0 = flaggedBlock("src", "draft")
        val nearby = listOf(RevisionPlanner.DialogueLine("p0", "ctxSrc", "ctxDraft"))
        val grp = group(targets = listOf(target("p0", 0, b0)), nearby = nearby)
        val request = RevisionRequestBuilder.build(grp)
        val msg = RevisionRequestBuilder.buildUserMessage(request)

        msg shouldContain "Surrounding context"
        msg shouldContain "ctxSrc"
        msg shouldContain "ctxDraft"
    }

    @Test
    fun `toRevisionBatch always sets isPass2 = true`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)
        val batch = RevisionRequestBuilder.toRevisionBatch(request, emptyList())
        batch.isPass2 shouldBe true
    }

    // ------------------------------------------------ strict K/C/U parsing

    @Test
    fun `valid corrected line is TRANSLATED with corrected text`() {
        val b0 = flaggedBlock("src", "old draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)
        val id = request.orderedIds[0]

        val results = parseRevision(request.idMap, listOf("$id|New corrected text"))
        results shouldHaveSize 1
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "New corrected text"
        results[0].id shouldBe id
    }

    @Test
    fun `same text as draft is TRANSLATED -- merge layer classifies as Kept`() {
        val b0 = flaggedBlock("src", "same draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)
        val id = request.orderedIds[0]

        val results = parseRevision(request.idMap, listOf("$id|same draft"))
        results shouldHaveSize 1
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "same draft"
        // No qualityTag expected in Pass-2 (no [OK]/[FLAG] tags).
        results[0].qualityTag shouldBe null
    }

    @Test
    fun `missing id has no result -- missingIds accounts it`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)

        // Respond with empty lines (id omitted entirely).
        val results = parseRevision(request.idMap, emptyList())
        results.shouldBeEmpty()

        val batch = RevisionRequestBuilder.toRevisionBatch(request, results)
        batch.missingIds shouldHaveSize 1
        batch.missingIds[0] shouldBe request.orderedIds[0]
    }

    @Test
    fun `blank correction is REJECTED`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)
        val id = request.orderedIds[0]

        val results = parseRevision(request.idMap, listOf("$id|   "))
        results shouldHaveSize 1
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].id shouldBe id
    }

    @Test
    fun `duplicate id -- first wins, second is REJECTED`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)
        val id = request.orderedIds[0]

        val results = parseRevision(
            request.idMap,
            listOf("$id|First correction", "$id|Second correction"),
        )
        results shouldHaveSize 2
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "First correction"
        results[1].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[1].text shouldBe "Second correction"

        val batch = RevisionRequestBuilder.toRevisionBatch(request, results)
        batch.duplicateIds shouldHaveSize 1
        batch.duplicateIds[0] shouldBe id
    }

    @Test
    fun `unknown id is REJECTED with null targetKey`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)

        val results = parseRevision(request.idMap, listOf("p99_b99|Some text"))
        results shouldHaveSize 1
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].targetKey shouldBe null

        val batch = RevisionRequestBuilder.toRevisionBatch(request, results)
        batch.unknownIds shouldHaveSize 1
        batch.unknownIds[0] shouldBe "p99_b99"
        // Original target is still missing.
        batch.missingIds shouldHaveSize 1
    }

    @Test
    fun `malformed line with no anchored id is REJECTED`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)

        val results = parseRevision(request.idMap, listOf("this is just garbage text"))
        results shouldHaveSize 1
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED

        val batch = RevisionRequestBuilder.toRevisionBatch(request, results)
        // Target was not resolved so it is missing.
        batch.missingIds shouldHaveSize 1
    }

    @Test
    fun `mixed outcomes across three targets are all correctly accounted`() {
        val b0 = flaggedBlock("src0", "draft0")
        val b1 = flaggedBlock("src1", "draft1")
        val b2 = flaggedBlock("src2", "draft2")
        val grp = group(targets = listOf(target("p0", 0, b0), target("p0", 1, b1), target("p0", 2, b2)))
        val request = RevisionRequestBuilder.build(grp)
        val (id0, id1, id2) = request.orderedIds

        val rawLines = listOf(
            "$id0|Corrected 0",   // Corrected -> TRANSLATED
            "$id1|   ",            // Blank -> REJECTED
            // id2 omitted -> MISSING
        )
        val results = parseRevision(request.idMap, rawLines)

        val translated = results.filter { it.status == ContextualTranslationResult.Status.TRANSLATED }
        val rejected = results.filter { it.status == ContextualTranslationResult.Status.REJECTED }
        translated shouldHaveSize 1
        translated[0].text shouldBe "Corrected 0"
        rejected shouldHaveSize 1
        rejected[0].id shouldBe id1

        val batch = RevisionRequestBuilder.toRevisionBatch(request, results)
        batch.missingIds shouldHaveSize 1
        batch.missingIds[0] shouldBe id2
    }

    @Test
    fun `shouldFlagForRevision always returns false for Pass-2 TRANSLATED results`() {
        val result = ContextualTranslationResult(
            id = "p0_b0",
            targetKey = AnchoredTargetKey(0, 0),
            text = "Corrected",
            status = ContextualTranslationResult.Status.TRANSLATED,
            qualityTag = null,
        )
        ContextualResponseParser.shouldFlagForRevision(result, isPass2 = true) shouldBe false

        // Even if the model incorrectly emits [FLAG], Pass-2 still returns false.
        val withFlag = result.copy(qualityTag = ContextualTranslationResult.QualityTag.FLAG)
        ContextualResponseParser.shouldFlagForRevision(withFlag, isPass2 = true) shouldBe false
    }

    @Test
    fun `tolerated OK tag in Pass-2 does not affect TRANSLATED status`() {
        val b0 = flaggedBlock("src", "draft")
        val grp = group(targets = listOf(target("p0", 0, b0)))
        val request = RevisionRequestBuilder.build(grp)
        val id = request.orderedIds[0]

        // Provider incorrectly emits [OK]; parser should still accept the line
        // and strip the tag from the text.
        val results = parseRevision(request.idMap, listOf("$id|Corrected text|[OK]"))
        results shouldHaveSize 1
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "Corrected text"
        // qualityTag is captured but merge layer ignores it for isPass2=true.
        results[0].qualityTag shouldBe ContextualTranslationResult.QualityTag.OK
        ContextualResponseParser.shouldFlagForRevision(results[0], isPass2 = true) shouldBe false
    }

    @Test
    fun `two-page group uses distinct page indices in anchored ids`() {
        val bA = flaggedBlock("srcA", "draftA")
        val bB = flaggedBlock("srcB", "draftB")
        val grp = group(targets = listOf(target("page-1", 0, bA), target("page-2", 0, bB)))
        val request = RevisionRequestBuilder.build(grp)

        val id0 = request.orderedIds[0]
        val id1 = request.orderedIds[1]
        AnchoredBlockId.isAnchored(id0) shouldBe true
        AnchoredBlockId.isAnchored(id1) shouldBe true
        (id0 != id1) shouldBe true
        // The locations map them to distinct pages.
        request.locations[id0]!!.pageKey shouldBe "page-1"
        request.locations[id1]!!.pageKey shouldBe "page-2"
    }
}
