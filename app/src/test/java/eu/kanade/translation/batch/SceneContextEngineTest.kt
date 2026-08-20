package eu.kanade.translation.batch

import eu.kanade.translation.translator.applyBatchToChunk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Phase 6 fixture coverage for the deterministic scene context engine, delta
 * parser, page-prefix planner, and faithfulness checks. Assertions target
 * semantic-role state transitions, not subjective fluency.
 */
class SceneContextEngineTest {

    private fun delta(vararg lines: String): SceneContextDelta? =
        SceneContextDeltaParser.parse(lines.joinToString("\n"), knownIds(lines.asSequence())).delta

    /** Every block token referenced by FACT/LINK/ROLE lines in these fixtures. */
    private fun knownIds(lines: Sequence<String>): Set<String> =
        lines.flatMap { line ->
            line.split('|').drop(1).flatMap { part -> part.split(',') }
                .map(String::trim)
                .filter { Regex("p\\d{4}_b\\d{4}").matches(it) }
        }.toSet()

    private fun knownIds(text: String): Set<String> = knownIds(text.lineSequence())

    private fun pageBlocks(vararg texts: String): List<Pair<String, String>> =
        texts.mapIndexed { index, text -> "p0001_b${index.toString().padStart(4, '0')}" to text }

    @Test
    fun `unnamed speakers record turns with unknown labels and no invented facts`() {
        val state = SceneContextEngine.commitPage(
            state = SceneCardState.genesis(),
            pageIndex = 0,
            pageId = "p0000",
            blocks = listOf("p0000_b0000" to "ねえ、聞いて。", "p0000_b0001" to "何だよ。"),
            delta = null,
        ).state

        state.recentTurns.size shouldBe 2
        state.recentTurns.all { it.speakerLabel.isEmpty() } shouldBe true
        state.profiles.isEmpty() shouldBe true
        state.summary shouldContain "page 1"
    }

    @Test
    fun `role lines assign turn speakers and activate profiles`() {
        val state = SceneContextEngine.commitPage(
            state = SceneCardState.genesis(),
            pageIndex = 0,
            pageId = "p0000",
            blocks = listOf("p0000_b0000" to "おはよう。", "p0000_b0001" to "遅いぞ。"),
            delta = delta(
                "ROLE|p0000_b0000|speaker=Speaker A|addressee=Speaker B",
                "ROLE|p0000_b0001|speaker=Speaker B|addressee=Speaker A",
            ),
        ).state

        state.recentTurns.map { it.speakerLabel } shouldBe listOf("Speaker A", "Speaker B")
        state.profiles.map { it.temporaryLabel }.toSet() shouldBe setOf("Speaker A", "Speaker B")
    }

    @Test
    fun `anonymous speaker links to a name only after two citing chunks`() {
        val first = SceneContextEngine.commitPage(
            state = SceneCardState.genesis(),
            pageIndex = 0,
            pageId = "p0000",
            blocks = listOf("p0000_b0000" to "今日は勝つ。"),
            delta = delta("LINK|Speaker A|Yuki|p0000_b0000"),
        ).state
        // First citation: tentative only, still unnamed.
        first.profiles.single().displayName shouldBe null
        first.profiles.single().linkCandidates["Yuki"] shouldBe 1

        val second = SceneContextEngine.commitPage(
            state = first,
            pageIndex = 1,
            pageId = "p0001",
            blocks = listOf("p0001_b0000" to "言ったろう。"),
            delta = delta("LINK|Speaker A|Yuki|p0001_b0000"),
        ).state
        // Second independent chunk: promoted to the named profile.
        second.profiles.single().displayName shouldBe "Yuki"
        second.profiles.single().label shouldBe "Yuki"
    }

    @Test
    fun `named vocative never becomes the speaker link`() {
        // The model proposes Speaker B == Mei, but its own ROLE for the cited
        // block says Speaker A spoke it: the name addresses Speaker B.
        val state = SceneContextEngine.commitPage(
            state = SceneCardState.genesis(),
            pageIndex = 0,
            pageId = "p0000",
            blocks = listOf("p0000_b0000" to " mei 、聞いてるか", "p0000_b0001" to "うるさい。"),
            delta = delta(
                "ROLE|p0000_b0000|speaker=Speaker A|addressee=Speaker B",
                "LINK|Speaker B|Mei|p0000_b0000",
            ),
        ).state

        val speakerB = state.profiles.first { it.temporaryLabel == "Speaker B" }
        speakerB.displayName shouldBe null
        speakerB.linkCandidates["Mei"] shouldBe -1
        state.unresolved.any { it.contains("Mei") } shouldBe true
    }

    @Test
    fun `explicit strong gender evidence corrects a probable fact and invalidates dependents`() {
        val first = SceneContextEngine.commitPage(
            state = SceneCardState.genesis(),
            pageIndex = 0,
            pageId = "p0000",
            blocks = listOf("p0000_b0000" to "私、行かなきゃ。"),
            delta = delta("FACT|Haru|gender=FEMALE|STRONG|p0000_b0000"),
        ).state
        first.profiles.single().let {
            it.gender shouldBe SceneGender.FEMALE
            it.genderConfidence shouldBe SceneConfidence.PROBABLE
        }
        // Page 1 committed on top of that fact.
        val second = SceneContextEngine.commitPage(
            state = first,
            pageIndex = 1,
            pageId = "p0001",
            blocks = listOf("p0001_b0000" to "あいつは来ない。"),
            delta = null,
        ).state
        second.factPages.entries.any { it.key.startsWith("gender:") && it.value.contains(0) } shouldBe true

        // Explicit counterevidence on page 2: strong male self-identification.
        val third = SceneContextEngine.commitPage(
            state = second,
            pageIndex = 2,
            pageId = "p0002",
            blocks = listOf("p0002_b0000" to "俺がハルだ。"),
            delta = delta("FACT|Haru|gender=MALE|STRONG|p0002_b0000"),
        )
        third.state.profiles.single().gender shouldBe SceneGender.MALE
        third.correction shouldNotBe null
        third.correction!!.invalidatedPages.toList() shouldBe listOf(0, 1)
    }

    @Test
    fun `weak name-only evidence stays tentative and never confirms`() {
        val state = SceneContextEngine.commitPage(
            state = SceneCardState.genesis(),
            pageIndex = 0,
            pageId = "p0000",
            blocks = listOf("p0000_b0000" to "yuki と呼べ。"),
            delta = delta("FACT|Yuki|gender=MALE|WEAK|p0000_b0000"),
        ).state
        state.profiles.single().let {
            it.gender shouldBe SceneGender.MALE
            it.genderConfidence shouldBe SceneConfidence.TENTATIVE
        }

        SceneContextEngine.confidenceFor(SceneEvidenceWeight.WEAK, 5, true) shouldBe SceneConfidence.TENTATIVE
        SceneContextEngine.confidenceFor(SceneEvidenceWeight.MEDIUM, 1, true) shouldBe SceneConfidence.TENTATIVE
        SceneContextEngine.confidenceFor(SceneEvidenceWeight.MEDIUM, 2, true) shouldBe SceneConfidence.PROBABLE
        SceneContextEngine.confidenceFor(SceneEvidenceWeight.STRONG, 1, true) shouldBe SceneConfidence.PROBABLE
        SceneContextEngine.confidenceFor(SceneEvidenceWeight.STRONG, 2, true) shouldBe SceneConfidence.CONFIRMED
        SceneContextEngine.confidenceFor(SceneEvidenceWeight.STRONG, 2, false) shouldBe SceneConfidence.TENTATIVE
    }

    @Test
    fun `low ocr confidence caps gender at tentative`() {
        val parsed = SceneContextDeltaParser.parse(
            "FACT|Rin|gender=FEMALE|STRONG|p0000_b0000",
            setOf("p0000_b0000"),
        ).delta!!
        val lowOcr = parsed.copy(
            citedConfidences = mapOf("p0000_b0000" to 0.3f),
        )
        val state = SceneContextEngine.commitPage(
            state = SceneCardState.genesis(),
            pageIndex = 0,
            pageId = "p0000",
            blocks = listOf("p0000_b0000" to "私だ。"),
            delta = lowOcr,
        ).state
        state.profiles.single().genderConfidence shouldBe SceneConfidence.TENTATIVE
    }

    @Test
    fun `ensemble scenes bound profiles to six by activity`() {
        // Build 8 distinct speaking profiles across two pages; the most recent win.
        val roles = (0 until 8).map { "Speaker ${'A' + it}" }
        val blocks0 = (0 until 4).map { "p0000_b000$it" to "line $it" }
        val blocks1 = (4 until 8).map { "p0001_b000$it" to "line $it" }
        val delta0 = SceneContextDelta(
            links = emptyList(),
            facts = emptyList(),
            roles = (0 until 4).associate { index ->
                "p0000_b000$index" to SceneRole("p0000_b000$index", roles[index], "")
            },
            unresolved = emptyList(),
        )
        val delta1 = SceneContextDelta(
            links = emptyList(),
            facts = emptyList(),
            roles = (4 until 8).associate { index ->
                "p0001_b000$index" to SceneRole("p0001_b000$index", roles[index], "")
            },
            unresolved = emptyList(),
        )
        val page0 = SceneContextEngine.commitPage(SceneCardState.genesis(), 0, "p0000", blocks0, delta0).state
        val page1 = SceneContextEngine.commitPage(page0, 1, "p0001", blocks1, delta1).state

        page1.profiles.size shouldBe SceneContextEngine.MAX_PROFILES
        page1.profiles.map { it.temporaryLabel }.toSet() shouldBe
            roles.drop(2).toSet() // Speakers C..H (most recently active) survive.
    }

    @Test
    fun `bounds hold turns summary and unresolved items`() {
        val blocks = (0 until 15).map { "p0000_b${it.toString().padStart(4, '0')}" to "turn $it" }
        val unresolvedDelta = SceneContextDelta(
            links = emptyList(),
            facts = emptyList(),
            roles = emptyMap(),
            unresolved = (0 until 12).map { "question $it" },
        )
        val state = SceneContextEngine.commitPage(
            SceneCardState.genesis(),
            0,
            "p0000",
            blocks,
            unresolvedDelta,
        ).state

        state.recentTurns.size shouldBe SceneContextEngine.MAX_TURNS
        state.unresolved.size shouldBe SceneContextEngine.MAX_UNRESOLVED
        (state.summary.split(' ').size <= SceneContextEngine.MAX_SUMMARY_WORDS) shouldBe true
    }

    @Test
    fun `explicit same-sex evidence overrides the male-female prior`() {
        fun couple(genderA: SceneGender, genderB: SceneGender): SceneCardState {
            var state = SceneCardState.genesis()
            state = SceneContextEngine.commitPage(
                state,
                0,
                "p0000",
                listOf("p0000_b0000" to "好きだ。"),
                SceneContextDelta(
                    links = emptyList(),
                    facts = listOf(
                        SceneFact("A", "gender", genderA.name, SceneEvidenceWeight.STRONG, listOf("p0000_b0000")),
                        SceneFact("A", "relationship", "loves B", SceneEvidenceWeight.STRONG, listOf("p0000_b0000")),
                        SceneFact("B", "gender", genderB.name, SceneEvidenceWeight.STRONG, listOf("p0000_b0000")),
                        SceneFact("B", "relationship", "loves A", SceneEvidenceWeight.STRONG, listOf("p0000_b0000")),
                    ),
                    roles = emptyMap(),
                    unresolved = emptyList(),
                ),
            ).state
            return state
        }

        val sameSex = couple(SceneGender.FEMALE, SceneGender.FEMALE)
        SceneContextEngine.relationshipPriorHint(sameSex, SceneContextEngine.PriorSetting.MALE_FEMALE) shouldContain "same-sex"

        val hetero = couple(SceneGender.FEMALE, SceneGender.MALE)
        SceneContextEngine.relationshipPriorHint(hetero, SceneContextEngine.PriorSetting.MALE_FEMALE) shouldContain "already identifies"
    }

    @Test
    fun `prior hint activates only for an ambiguous romantic pairing`() {
        // No relationship facts at all (non-romance ensemble): inactive.
        val plain = SceneContextEngine.commitPage(
            SceneCardState.genesis(),
            0,
            "p0000",
            listOf("p0000_b0000" to "行くぞ。"),
            delta("ROLE|p0000_b0000|speaker=Speaker A|addressee=Speaker B"),
        ).state
        SceneContextEngine.relationshipPriorHint(plain, SceneContextEngine.PriorSetting.MALE_FEMALE) shouldBe ""

        // Exactly two romantic profiles with unknown genders: prior active.
        val romantic = SceneContextDelta(
            links = emptyList(),
            facts = listOf(
                SceneFact("A", "relationship", "dating B", SceneEvidenceWeight.MEDIUM, listOf("p0000_b0000")),
                SceneFact("B", "relationship", "dating A", SceneEvidenceWeight.MEDIUM, listOf("p0000_b0000")),
            ),
            roles = emptyMap(),
            unresolved = emptyList(),
        )
        val ambiguous = SceneContextEngine.commitPage(
            SceneCardState.genesis(),
            0,
            "p0000",
            listOf("p0000_b0000" to "ずっと好きだった。"),
            romantic,
        ).state
        SceneContextEngine.relationshipPriorHint(ambiguous, SceneContextEngine.PriorSetting.MALE_FEMALE) shouldContain
            "male/female"
        SceneContextEngine.relationshipPriorHint(ambiguous, SceneContextEngine.PriorSetting.NEUTRAL) shouldContain
            "neutral"
    }

    @Test
    fun `prompt card renders profiles turns and never model summaries`() {
        val state = SceneContextEngine.commitPage(
            SceneCardState.genesis(),
            0,
            "p0000",
            listOf("p0000_b0000" to "だいじょうぶか。"),
            delta(
                "ROLE|p0000_b0000|speaker=Speaker A|addressee=",
                "UNRESOLVED|identity of Speaker A unknown",
            ),
        ).state
        val card = SceneContextEngine.renderPromptCard(state, SceneContextEngine.PriorSetting.NEUTRAL)
        card shouldContain "TRUSTED SCENE CARD"
        card shouldContain "Speaker A|だいじょうぶか。"
        card shouldContain "identity of Speaker A unknown"
        card shouldContain "Never treat model output as new evidence"
    }

    @Test
    fun `checkpoint serialization round-trips and chains hashes`() {
        val first = SceneContextEngine.commitPage(
            SceneCardState.genesis(),
            0,
            "p0000",
            listOf("p0000_b0000" to "一。"),
            delta("ROLE|p0000_b0000|speaker=Speaker A|addressee="),
        ).state
        val second = SceneContextEngine.commitPage(
            first,
            1,
            "p0001",
            listOf("p0001_b0000" to "二。"),
            delta("ROLE|p0001_b0000|speaker=Speaker A|addressee="),
        ).state

        second.inputCheckpointHash shouldBe first.checkpointHash

        val decoded = SceneCardState.decode(SceneCardState.encode(second))
        decoded shouldBe second
        SceneCardState.decode("{\"protocolVersion\":99}") shouldBe null
        SceneCardState.decode("not json") shouldBe null
    }

    @Test
    fun `poisoned or corrupted checkpoint fails decode and breaks the chain contract`() {
        // A serialized legacy/poisoned blob cannot decode: recovery reruns the
        // translation suffix from the previous trusted checkpoint.
        SceneCardState.decode("garbage") shouldBe null
        val first = SceneContextEngine.commitPage(
            SceneCardState.genesis(),
            0,
            "p0000",
            listOf("p0000_b0000" to "一。"),
            delta("FACT|Yuki|gender=MALE|STRONG|p0000_b0000"),
        ).state
        val tampered = first.copy(profiles = emptyList())
        // Tampering with state content invalidates the hash chain because the
        // successor's inputCheckpointHash no longer matches any recomputed hash.
        SceneContextEngine.hashOf(tampered) shouldNotBe first.checkpointHash
    }

    @Test
    fun `delta parser is fail closed on unknown citations and sections`() {
        SceneContextDeltaParser.parse(null, emptySet()).isValid shouldBe true
        SceneContextDeltaParser.parse("", emptySet()).isValid shouldBe true

        val valid = SceneContextDeltaParser.parse(
            "FACT|A|gender=FEMALE|STRONG|p0000_b0000\nROLE|p0000_b0000|speaker=A|addressee=B",
            setOf("p0000_b0000"),
        )
        valid.isValid shouldBe true
        valid.delta!!.facts.single().value shouldBe "FEMALE"

        SceneContextDeltaParser.parse("FACT|A|gender=FEMALE|STRONG|p0000_b0000", emptySet()).isValid shouldBe false
        SceneContextDeltaParser.parse("GENDER|A|FEMALE", emptySet()).isValid shouldBe false
        SceneContextDeltaParser.parse("FACT|A|gender=FEMALE|HUGE|p0000_b0000", setOf("p0000_b0000")).isValid shouldBe false
        SceneContextDeltaParser.parse("FACT|A|species=elf|MEDIUM|p0000_b0000", setOf("p0000_b0000")).isValid shouldBe false
        SceneContextDeltaParser.parse("LINK|A||p0000_b0000", setOf("p0000_b0000")).isValid shouldBe false
        SceneContextDeltaParser.parse("ROLE|p0000_b0000|speaker=|addressee=", setOf("p0000_b0000")).isValid shouldBe false
        SceneContextDeltaParser.parse(
            "FACT|A|gender=FEMALE|STRONG|p0000_b0000",
            setOf("p0000_b0001"),
        ).isValid shouldBe false
    }

    @Test
    fun `page prefix planner commits the longest valid prefix and never crosses a failed page`() {
        val plan = ScenePrefixPlanner.plan(
            orderedPages = listOf("p1", "p2", "p3"),
            deltas = mapOf("p0001" to "UNRESOLVED|ok", "p0002" to "FACT|A|gender=FEMALE|STRONG|p0009_b0000"),
            pageIds = mapOf("p1" to "p0001", "p2" to "p0002", "p3" to "p0003"),
            knownBlockIds = setOf("p0001_b0000"),
            refusalPages = emptySet(),
        )
        plan.commits.map { it.pageKey } shouldBe listOf("p1")
        plan.stopPageKey shouldBe "p2"
        plan.stopReason shouldContain "invalid context delta"

        val refusalPlan = ScenePrefixPlanner.plan(
            orderedPages = listOf("p1", "p2"),
            deltas = emptyMap(),
            pageIds = mapOf("p1" to "p0001", "p2" to "p0002"),
            knownBlockIds = emptySet(),
            refusalPages = setOf("p1"),
        )
        refusalPlan.commits.isEmpty() shouldBe true
        refusalPlan.stopReason shouldBe "provider refusal"

        val allValid = ScenePrefixPlanner.plan(
            orderedPages = listOf("p1", "p2"),
            deltas = emptyMap(),
            pageIds = mapOf("p1" to "p0001", "p2" to "p0002"),
            knownBlockIds = emptySet(),
            refusalPages = emptySet(),
        )
        allValid.commits.map { it.pageKey } shouldBe listOf("p1", "p2")
        allValid.stop shouldBe null
    }

    @Test
    fun `refusal lexicon and coverage concern are best effort and honest`() {
        eu.kanade.translation.translator.TranslationResponseFaithfulness
            .isStructuralRefusal("I'm sorry, but I can't help with that request.") shouldBe true
        eu.kanade.translation.translator.TranslationResponseFaithfulness
            .isStructuralRefusal("As an AI language model, I cannot translate this.") shouldBe true
        eu.kanade.translation.translator.TranslationResponseFaithfulness
            .isStructuralRefusal("I can't believe you did that!") shouldBe false
        eu.kanade.translation.translator.TranslationResponseFaithfulness
            .isStructuralRefusal("待って！") shouldBe false

        eu.kanade.translation.translator.TranslationResponseFaithfulness
            .coverageConcern("彼は東京大学の医学部を卒業して六年目の春だった。", "Hmm.") shouldBe true
        eu.kanade.translation.translator.TranslationResponseFaithfulness
            .coverageConcern("うん。", "Yeah.") shouldBe false
    }

    @Test
    fun `manual target edits survive retranslation`() {
        val block = eu.kanade.translation.model.TranslationBlock(
            text = "元気か",
            width = 10f,
            height = 10f,
            x = 0f,
            y = 0f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
        )
        block.translation = "my hand edit"
        block.userEditedAt = 1234L
        val page = eu.kanade.translation.model.PageTranslation(blocks = mutableListOf(block))
        val chunk = eu.kanade.translation.translator.TranslationContextChunk(
            pages = linkedMapOf("p1" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 100,
            maxOutputTokens = 256,
        )
        val request = eu.kanade.translation.translator.ContextualRequestBuilder.buildLegacy(
            chunk,
            eu.kanade.translation.ocr.TextRecognizerLanguage.JAPANESE,
            eu.kanade.translation.translator.TextTranslatorLanguage.ENGLISH,
        )
        val batch = eu.kanade.translation.translator.ContextualRequestBuilder.toBatch(
            request,
            listOf(
                eu.kanade.translation.translator.ContextualTranslationResult(
                    id = "b0",
                    targetKey = eu.kanade.translation.translator.AnchoredTargetKey(0, 0),
                    text = "model replacement",
                    status = eu.kanade.translation.translator.ContextualTranslationResult.Status.TRANSLATED,
                ),
            ),
        )
        applyBatchToChunk(chunk, batch)
        block.translation shouldBe "my hand edit"
    }
}
