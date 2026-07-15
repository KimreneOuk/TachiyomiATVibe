package eu.kanade.translation.translator

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards [ContextualResponseParser] and [AnchoredBlockId] — the structured
 * per-id result parsing that replaces in-place TranslationBlock mutation
 * (Checkpoint 2 §4).
 */
class ContextualResponseParserTest {

    @Test
    fun `anchored id recognizes p0_b3 form`() {
        AnchoredBlockId.isAnchored("p0_b3") shouldBe true
        AnchoredBlockId.isAnchored("p12_b0") shouldBe true
        AnchoredBlockId.isAnchored("b0") shouldBe false
        AnchoredBlockId.isAnchored("garbage") shouldBe false
        AnchoredBlockId.format(0, 3) shouldBe "p0_b3"
        AnchoredBlockId.format(2, 0) shouldBe "p2_b0"
    }

    @Test
    fun `complete pass1 response translates every known id`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val lines = listOf("b0|Hello.|[OK]", "b1|World!|[OK]")
        val results = ContextualResponseParser.parse(lines, idMap, isPass2 = false)

        results shouldHaveSize 2
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "Hello."
        results[0].qualityTag shouldBe ContextualTranslationResult.QualityTag.OK
        results[1].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[1].text shouldBe "World!"
    }

    @Test
    fun `tagless valid pass1 line is accepted but auto-flagged`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b0|No tag here."), idMap, isPass2 = false)
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].qualityTag shouldBe null
        ContextualResponseParser.shouldFlagForRevision(results[0], isPass2 = false) shouldBe true
    }

    @Test
    fun `explicit FLAG tags the block for revision`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b0|Unclear.|[FLAG]"), idMap, isPass2 = false)
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].qualityTag shouldBe ContextualTranslationResult.QualityTag.FLAG
        ContextualResponseParser.shouldFlagForRevision(results[0], isPass2 = false) shouldBe true
    }

    @Test
    fun `explicit OK clears the revision flag`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b0|Clear.|[OK]"), idMap, isPass2 = false)
        ContextualResponseParser.shouldFlagForRevision(results[0], isPass2 = false) shouldBe false
    }

    @Test
    fun `unknown id is rejected and never applied`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b9|Ghost."), idMap, isPass2 = false)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].targetKey shouldBe null
    }

    @Test
    fun `blank payload is rejected`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b0|   "), idMap, isPass2 = false)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
    }

    @Test
    fun `malformed line is rejected`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("this has no id prefix"), idMap, isPass2 = false)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].targetKey shouldBe null
    }

    @Test
    fun `duplicate id keeps first and rejects the second as ambiguous`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(
            listOf("b0|First.|[OK]", "b0|Second.|[OK]"),
            idMap,
            isPass2 = false,
        )
        results shouldHaveSize 2
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "First."
        // Second occurrence is rejected as ambiguous; the draft is NOT overwritten
        // by the competing translation.
        results[1].status shouldBe ContextualTranslationResult.Status.REJECTED
    }

    @Test
    fun `missing id in response leaves that block untouched`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        // Only b0 returned; b1 is missing entirely -> the caller treats b1 as untranslated.
        val results = ContextualResponseParser.parse(listOf("b0|Only.|[OK]"), idMap, isPass2 = false)
        results shouldHaveSize 1
        results[0].id shouldBe "b0"
    }

    @Test
    fun `pass2 anchored ids resolve and tagless line clears the flag`() {
        val idMap = linkedIdMap("p0_b0" to 0, "p0_b1" to 1)
        val results = ContextualResponseParser.parse(
            listOf("p0_b0|Corrected.", "p0_b1|Also."),
            idMap,
            isPass2 = true,
        )
        results shouldHaveSize 2
        results.forEach {
            it.status shouldBe ContextualTranslationResult.Status.TRANSLATED
            // Pass 2 corrections are authoritative: never flagged.
            ContextualResponseParser.shouldFlagForRevision(it, isPass2 = true) shouldBe false
        }
    }

    @Test
    fun `batch accepted and rejected partition correctly`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val results = ContextualResponseParser.parse(
            listOf("b0|Good.|[OK]", "b1|   ", "xx|garbage"),
            idMap,
            isPass2 = false,
        )
        results.filter { it.status == ContextualTranslationResult.Status.TRANSLATED } shouldHaveSize 1
        results.filter { it.status == ContextualTranslationResult.Status.REJECTED } shouldHaveSize 2
    }

    private fun linkedIdMap(vararg entries: Pair<String, Int>): LinkedHashMap<String, AnchoredTargetKey> {
        val map = LinkedHashMap<String, AnchoredTargetKey>()
        // All entries share page index 0 for the parser tests; block index from value.
        entries.forEach { (id, blockIdx) -> map[id] = AnchoredTargetKey(0, blockIdx) }
        return map
    }
}
