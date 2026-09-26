package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.BlockRange
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.FactConflictState
import eu.kanade.translation.persistence.artifact.FactProvenance
import eu.kanade.translation.persistence.artifact.FactScope
import eu.kanade.translation.persistence.artifact.FactType
import eu.kanade.translation.persistence.artifact.PageBlockRef
import eu.kanade.translation.persistence.artifact.PageRange
import eu.kanade.translation.persistence.artifact.ProfileFact
import eu.kanade.translation.persistence.artifact.ProfileGender
import eu.kanade.translation.persistence.artifact.ProfileScene
import eu.kanade.translation.persistence.artifact.SceneRegister
import eu.kanade.translation.persistence.artifact.ToneFlag
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 *  Stage-6 slice B (design §7.1/§7.2): the pure frozen-profile subset
 * matcher — matching, alias/title linking, entity-id inclusion, caps,
 * determinism, and RANGE SAFETY of the scene context.
 */
class ProfileSubsetMatcherTest {

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    private fun hex64(seed: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun evidence(page: String = "p0") =
        EvidenceRef(pageKey = page, stableBlockId = "${page}_b0", sourceExcerptHash = hex64("e-$page"))

    private fun fact(
        id: String,
        type: FactType,
        source: String?,
        target: String?,
        aliases: List<String> = emptyList(),
        scope: FactScope = FactScope.CANONICAL_CHAPTER_WIDE,
        range: PageRange? = null,
        availableFrom: Int? = null,
        gender: ProfileGender? = null,
        note: String? = null,
        evidenceStrength: eu.kanade.translation.persistence.artifact.EvidenceStrength =
            eu.kanade.translation.persistence.artifact.EvidenceStrength.EXPLICIT,
    ) = ProfileFact(
        factId = id,
        type = type,
        canonicalSourceForm = source,
        canonicalTargetForm = target,
        aliases = aliases,
        evidenceStrength = evidenceStrength,
        evidenceRefs = if (evidenceStrength == eu.kanade.translation.persistence.artifact.EvidenceStrength.WEAK) {
            emptyList()
        } else {
            listOf(evidence())
        },
        scope = scope,
        applicableRange = range,
        availableFrom = availableFrom?.let { PageBlockRef(it) },
        gender = gender,
        provenance = FactProvenance.CHAPTER_ANALYSIS,
        conflictState = FactConflictState.RESOLVED,
        note = note,
    )

    private fun profile(
        entities: List<ProfileFact> = emptyList(),
        terms: List<ProfileFact> = emptyList(),
        scenes: List<ProfileScene> = emptyList(),
        unresolved: List<ProfileFact> = emptyList(),
    ) = ChapterTranslationProfile(
        version = 1,
        contentFingerprint = hex64("content"),
        profileInputFingerprint = hex64("input"),
        sourceRunId = "run-1",
        analyzerProvenance = AnalyzerProvenance("fake", "fake-model", 1, 1),
        entities = entities,
        terms = terms,
        scenes = scenes,
        unresolvedFacts = unresolved,
        frozenAtEpochMs = 1L,
    )

    private fun scene(
        id: String,
        firstPage: Int,
        lastPage: Int,
        participants: List<String>,
        narrative: String? = null,
    ) = ProfileScene(
        sceneId = id,
        pageRange = PageRange(firstPage, lastPage),
        blockRanges = listOf(BlockRange(firstPage, 0, 1)),
        participants = participants,
        toneFlags = setOf(ToneFlag.ACTION),
        register = SceneRegister.CASUAL,
        narrativeContext = narrative,
    )

    private fun sources(vararg pages: Pair<Int, String>) =
        pages.map { ProfileSubsetMatcher.EnvelopeSource(it.first, it.second) }

    // ------------------------------------------------------------------
    //  matching, aliases/titles, entity ids, cap, determinism.
    // ------------------------------------------------------------------

    @Test
    fun `matches canonical form, alias, and title and carries entity ids`() {
        val p = profile(
            entities = listOf(
                fact("e001", FactType.ENTITY_IDENTITY, "カイル", "Kail", aliases = listOf("カイル様", "Kyle")),
            ),
            terms = listOf(
                fact("t001", FactType.TERM, "剣", "sword", aliases = listOf("つるぎ")),
            ),
        )
        val subset = ProfileSubsetMatcher.match(p, sources(0 to "カイル様はつるぎを抜いた。"))

        subset.truncated shouldBe false
        val byId = subset.entries.associateBy { it.factId }
        byId.keys shouldContainExactlyInAnyOrder setOf("e001", "t001")
        // Alias-only hit still resolves the ENTITY with its id (alias linking).
        byId.getValue("e001").kind shouldBe ProfileSubsetMatcher.EntryKind.ENTITY
        byId.getValue("e001").aliases shouldContainExactly listOf("カイル様", "Kyle")
        byId.getValue("t001").kind shouldBe ProfileSubsetMatcher.EntryKind.TERM
        // Title-only hit for a Latin alias (case-insensitive path).
        val latin = ProfileSubsetMatcher.match(
            profile(entities = listOf(fact("e001", FactType.ENTITY_IDENTITY, "Kyle", "Kail", aliases = listOf("Sir Kyle")))),
            sources(0 to "sir kyle draws his sword"),
        )
        latin.entries.single().factId shouldBe "e001"
    }

    @Test
    fun `subset is deterministic and capped at the bounded constant`() {
        val entities = (0 until 30).map { i ->
            fact("e%03d".format(i), FactType.ENTITY_IDENTITY, "Name$i", "Target$i")
        }
        val p = profile(entities = entities)
        val text = (0 until 30).joinToString(" ") { "Name$it appears" }
        val first = ProfileSubsetMatcher.match(p, sources(0 to text))
        val second = ProfileSubsetMatcher.match(p, sources(0 to text))

        first shouldBe second // determinism
        first.truncated shouldBe true
        first.entries.size shouldBe ProfileSubsetMatcher.MAX_SUBSET_FACTS
        // Deterministic order: the profile's own frozen fact order, not discovery order.
        first.entries.map { it.factId } shouldContainExactly
            (0 until ProfileSubsetMatcher.MAX_SUBSET_FACTS).map { "e%03d".format(it) }
    }

    // ------------------------------------------------------------------
    //  range-safe scene context.
    // ------------------------------------------------------------------

    @Test
    fun `scene context is range-safe - only overlapping scenes, in-range and chapter-wide facts`() {
        val p = profile(
            entities = listOf(
                fact("e001", FactType.ENTITY_IDENTITY, "ヒーロー", "Hero"),
                fact("e002", FactType.ENTITY_IDENTITY, "相方", "Partner"),
                // GENDER fact linked to the entity by source form.
                fact("g001", FactType.GENDER, "ヒーロー", "Hero", gender = ProfileGender.FEMALE),
                // In-range RANGE_SCOPED fact.
                fact(
                    "r001",
                    FactType.NARRATIVE_STATE,
                    "ヒーロー",
                    "wounded",
                    scope = FactScope.RANGE_SCOPED,
                    range = PageRange(0, 1),
                ),
                // OUT-of-range RANGE_SCOPED fact.
                fact(
                    "r002",
                    FactType.NARRATIVE_STATE,
                    "相方",
                    "angry",
                    scope = FactScope.RANGE_SCOPED,
                    range = PageRange(5, 6),
                ),
                // AVAILABLE_FROM later than the envelope start.
                fact(
                    "a001",
                    FactType.ENTITY_IDENTITY,
                    "賊",
                    "Bandit",
                    scope = FactScope.AVAILABLE_FROM,
                    availableFrom = 3,
                ),
            ),
            scenes = listOf(
                scene("s001", 0, 1, participants = listOf("e001"), narrative = "opening fight"),
                scene("s002", 5, 6, participants = listOf("e002"), narrative = "later talk"),
            ),
        )
        val subset = ProfileSubsetMatcher.match(p, sources(0 to "ヒーローは叫ぶ。", 1 to "彼は走る。", 2 to "終わった。"))

        val ids = subset.entries.map { it.factId }
        ids shouldContainExactlyInAnyOrder listOf("e001", "g001", "r001")
        // Out-of-range scene NEVER contributes its participants or narrative.
        subset.scenes.map { it.sceneId } shouldContainExactly listOf("s001")
        subset.scenes.single().narrativeContext shouldBe "opening fight"
        // Gender + range-scoped context ride along with the matched entity.
        subset.entries.first { it.factId == "g001" }.gender shouldBe ProfileGender.FEMALE
    }

    @Test
    fun `availableFrom at the envelope first page is usable`() {
        val p = profile(
            entities = listOf(
                fact("a001", FactType.ENTITY_IDENTITY, "賊", "Bandit", scope = FactScope.AVAILABLE_FROM, availableFrom = 0),
            ),
        )
        val subset = ProfileSubsetMatcher.match(p, sources(2 to "賊が現れる。"))
        subset.entries.map { it.factId } shouldContainExactly listOf("a001")
    }

    @Test
    fun `scoped facts with missing range payloads default-deny (wave-7a F-W7-2)`() {
        val p = profile(
            entities = listOf(
                // Text-matching fact whose scope payload is absent — the
                // permissive default would have ridden it ahead of its range.
                fact("r001", FactType.ENTITY_IDENTITY, "賊", "Bandit", scope = FactScope.RANGE_SCOPED),
                fact("a001", FactType.ENTITY_IDENTITY, "盗人", "Thief", scope = FactScope.AVAILABLE_FROM),
                fact("w001", FactType.ENTITY_IDENTITY, "勇者", "Hero"),
            ),
        )
        val subset = ProfileSubsetMatcher.match(p, sources(2 to "賊と盗人と勇者。"))
        // Only the chapter-wide fact survives; both malformed scoped facts
        // default-DENY even though their forms match the source text.
        subset.entries.map { it.factId } shouldContainExactly listOf("w001")
    }

    @Test
    fun `scene participant is added even without a text hit`() {
        val p = profile(
            entities = listOf(
                fact("e001", FactType.ENTITY_IDENTITY, "ヒーロー", "Hero"),
                fact("e002", FactType.ENTITY_IDENTITY, "相方", "Partner"),
            ),
            scenes = listOf(scene("s001", 0, 2, participants = listOf("e001", "e002"))),
        )
        // Only ヒーロー appears in the text; 相方 rides in via scene participation.
        val subset = ProfileSubsetMatcher.match(p, sources(1 to "ヒーローのみ。"))
        subset.entries.map { it.factId } shouldContainExactlyInAnyOrder listOf("e001", "e002")
    }

    // ------------------------------------------------------------------
    //  helpers: resolved entity lines + compact unresolved state.
    // ------------------------------------------------------------------

    @Test
    fun `resolvedEntityLines scans committed rolling text and unresolvedReferenceLines caps notes`() {
        val p = profile(
            entities = listOf(fact("e001", FactType.ENTITY_IDENTITY, "カイル", "Kail")),
            unresolved = listOf(
                fact("u001", FactType.ENTITY_IDENTITY, "謎の男", null, note = "identity unresolved"),
            ),
        )
        val lines = ProfileSubsetMatcher.resolvedEntityLines(p, "カイルは走った => Kail ran")
        lines shouldContainExactly listOf("[e001] カイル -> Kail")

        val unresolved = ProfileSubsetMatcher.unresolvedReferenceLines(p)
        unresolved.single() shouldContain "u001"
        unresolved.single() shouldContain "identity unresolved"
        ProfileSubsetMatcher.resolvedEntityLines(p, "") shouldBe emptyList()
    }
}
