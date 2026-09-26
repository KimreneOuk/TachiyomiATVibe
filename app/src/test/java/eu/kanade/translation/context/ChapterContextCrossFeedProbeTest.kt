package eu.kanade.translation.context

import eu.kanade.translation.engines.translator.contextual.ProfileSubsetMatcher
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.EvidenceStrength
import eu.kanade.translation.persistence.artifact.FactConflictState
import eu.kanade.translation.persistence.artifact.FactProvenance
import eu.kanade.translation.persistence.artifact.FactScope
import eu.kanade.translation.persistence.artifact.FactType
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageRange
import eu.kanade.translation.persistence.artifact.ProfileFact
import eu.kanade.translation.persistence.artifact.ProfileScene
import eu.kanade.translation.persistence.artifact.SceneRegister
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.ProfileFreezePublication
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class ChapterContextCrossFeedProbeTest {

    private val io = FakeChapterDocumentIo()
    private val layout = ChapterArtifactLayout("Chapter 1")
    private val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    private fun sha256Hex(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun createProfile(version: Int, target: String = "Kyle"): ChapterTranslationProfile {
        val draft = ChapterTranslationProfile(
            version = version,
            contentFingerprint = "",
            profileInputFingerprint = sha256Hex("input-test"),
            sourceRunId = "run-1",
            analyzerProvenance = AnalyzerProvenance("test", "model-1", 1, 1, "sig"),
            entities = listOf(
                ProfileFact(
                    factId = "f-1",
                    type = FactType.ENTITY_IDENTITY,
                    canonicalSourceForm = "カイル",
                    canonicalTargetForm = target,
                    evidenceStrength = EvidenceStrength.STRONG_CONTEXTUAL,
                    evidenceRefs = listOf(EvidenceRef("p1", "b1", sha256Hex("カイル"))),
                    scope = FactScope.CANONICAL_CHAPTER_WIDE,
                    provenance = FactProvenance.CHAPTER_ANALYSIS,
                    conflictState = FactConflictState.RESOLVED,
                ),
            ),
            terms = listOf(
                ProfileFact(
                    factId = "f-2",
                    type = FactType.TERM,
                    canonicalSourceForm = "聖剣",
                    canonicalTargetForm = "Holy Sword",
                    evidenceStrength = EvidenceStrength.STRONG_CONTEXTUAL,
                    evidenceRefs = listOf(EvidenceRef("p1", "b2", sha256Hex("聖剣"))),
                    scope = FactScope.CANONICAL_CHAPTER_WIDE,
                    provenance = FactProvenance.CHAPTER_ANALYSIS,
                    conflictState = FactConflictState.RESOLVED,
                ),
            ),
            scenes = listOf(
                ProfileScene(
                    sceneId = "s001",
                    pageRange = PageRange(0, 2),
                    participants = listOf("f-1"),
                    register = SceneRegister.CASUAL,
                    narrativeContext = "battle at the ruined shrine",
                ),
            ),
            frozenAtEpochMs = 1000L,
        )
        return draft.copy(contentFingerprint = StageFingerprints.profileContentFingerprint(draft))
    }

    @Test
    fun `batch to manual cross-feed - manual tap receives frozen profile character and term sheet`() = runBlocking<Unit> {
        artifact.publishManifest(ChapterArtifactManifest(chapterKey = "Chapter 1"))
        val manifest = artifact.readManifest().shouldNotBeNull()
        val profile = createProfile(1, "Kyle")
        ProfileFreezePublication.publish(
            ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                artifactStore = artifact,
            ),
            manifest,
            profile,
            nowEpochMs = 1000L,
        )

        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
            artifactStore = artifact,
        )

        // Manual tap on page 1 requesting context
        val request = ContextRequest(
            pageKeys = listOf("p1"),
            targetLang = "en",
            sourceLang = "ja",
            requestedOutputTokens = 2048,
            profile = TranslationContextChunkPlanner.Profile.DEFAULT,
            laneCapability = LaneCapability.MANUAL,
            envelopeSources = listOf(
                ProfileSubsetMatcher.EnvelopeSource(
                    naturalPageIndex = 0,
                    sourceText = "カイルが聖剣を抜いた",
                ),
            ),
        )

        val prepared = store.contextService.prepare(request)

        prepared.characterAndTermSheet shouldContain "CHARACTER & TERM SHEET"
        prepared.characterAndTermSheet shouldContain "[f-1]"
        prepared.characterAndTermSheet shouldContain "カイル -> Kyle"
        prepared.characterAndTermSheet shouldContain "聖剣"
        prepared.characterAndTermSheet shouldContain "Holy Sword"
        prepared.characterAndTermSheet shouldContain "battle at the ruined shrine"
        prepared.selectedTerms.map { it.first } shouldContain "カイル"
    }

    @Test
    fun `manual to batch cross-feed - batch lane receives terms folded by manual translation`() = runBlocking<Unit> {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        // Manual tap translates 3 pages and folds its terms into the store
        store.foldPageContribution(
            pageKey = "p1",
            pairs = listOf(
                "魔王" to "Demon Lord",
                "勇者" to "Hero",
            ),
        )
        store.foldPageContribution(
            pageKey = "p2",
            pairs = listOf(
                "魔王" to "Demon Lord",
                "勇者" to "Hero",
            ),
        )
        store.foldPageContribution(
            pageKey = "p3",
            pairs = listOf(
                "魔王" to "Demon Lord",
                "勇者" to "Hero",
            ),
        )

        // Batch lane prepares context for page 4
        val batchRequest = ContextRequest(
            pageKeys = listOf("p4"),
            targetLang = "en",
            sourceLang = "ja",
            requestedOutputTokens = 2048,
            profile = TranslationContextChunkPlanner.Profile.DEFAULT,
            laneCapability = LaneCapability.PROFILE_BATCH,
            rollingPairs = "勇者 => Hero",
        )

        val prepared = store.contextService.prepare(batchRequest)

        prepared.characterAndTermSheet shouldContain "魔王"
        prepared.characterAndTermSheet shouldContain "Lord"
        prepared.characterAndTermSheet shouldContain "勇者"
        prepared.characterAndTermSheet shouldContain "Hero"
        prepared.selectedTerms shouldContain ("魔王" to "Lord")
        prepared.selectedTerms shouldContain ("勇者" to "Hero")
    }

    @Test
    fun `allocator order targets per T933 and reverse trimming priority`() {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )
        val service = ChapterContextService(store)

        // Target allocations per  terms 320 -> safeguards 96 -> pairs 288 -> scene 96
        val allocation = service.computeBudgetAllocation(
            maxBudget = 512,
            termsUsed = 100,
            safeguardsUsed = 50,
            pairsUsed = 150,
        )

        allocation.termsTarget shouldBe 320
        allocation.safeguardsTarget shouldBe 96
        allocation.pairsTarget shouldBe 288
        allocation.sceneTarget shouldBe 96

        // Trimming priority under tight budget constraint:
        // Scene dropped first -> pairs dropped second -> safeguards dropped third -> terms kept last
        val profile = createProfile(1, "Kyle")
        val tightBudgetProfile = TranslationContextChunkPlanner.Profile.DEFAULT

        // Request with large rolling context and profile scenes
        val longPairs = (1..20).joinToString("\n") { "ソース $it => Translation $it" }
        val request = ContextRequest(
            pageKeys = listOf("p1"),
            targetLang = "en",
            sourceLang = "ja",
            requestedOutputTokens = 2048,
            profile = tightBudgetProfile,
            laneCapability = LaneCapability.PROFILE_BATCH,
            frozenProfile = profile,
            rollingPairs = longPairs,
            envelopeSources = listOf(
                ProfileSubsetMatcher.EnvelopeSource(
                    naturalPageIndex = 0,
                    sourceText = "カイルが聖剣を抜いた",
                ),
            ),
        )

        val prepared = service.prepare(request)

        // Terms must remain preserved
        prepared.characterAndTermSheet shouldContain "[f-1]"
        prepared.characterAndTermSheet shouldContain "カイル -> Kyle"
    }

    @Test
    fun `standard batch keeps no-context adapter but submits outputs to term producer`() = runBlocking<Unit> {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        // Standard batch requesting context returns empty adapter
        val request = ContextRequest(
            pageKeys = listOf("p1"),
            targetLang = "en",
            sourceLang = "ja",
            requestedOutputTokens = 2048,
            profile = TranslationContextChunkPlanner.Profile.DEFAULT,
            laneCapability = LaneCapability.STANDARD_BATCH,
        )

        val prepared = store.contextService.prepare(request)
        prepared shouldBe PreparedContext.EMPTY
        prepared.estimatedContextTokens shouldBe 0
        prepared.characterAndTermSheet shouldBe ""
        prepared.rollingContext shouldBe ""

        // Standard batch commits translated output to term producer across 3 pages
        store.contextService.submitCommittedOutput(
            pageKey = "p1",
            pairs = listOf("黒崎" to "Kurosaki", "戦士" to "Warrior"),
        )
        store.contextService.submitCommittedOutput(
            pageKey = "p2",
            pairs = listOf("黒崎" to "Kurosaki", "戦士" to "Warrior"),
        )
        store.contextService.submitCommittedOutput(
            pageKey = "p3",
            pairs = listOf("黒崎" to "Kurosaki", "戦士" to "Warrior"),
        )

        store.glossarySnapshot()["黒崎"] shouldBe "Kurosaki"
        store.glossarySnapshot()["戦士"] shouldBe "Warrior"
    }

    @Test
    fun `zero durable storage changes - manifest schema remains v3 and no new pointers`() {
        artifact.publishManifest(ChapterArtifactManifest(chapterKey = "Chapter 1"))
        val manifest = artifact.readManifest().shouldNotBeNull()

        manifest.schemaVersion shouldBe ChapterArtifactManifest.SCHEMA_VERSION
        // No new durable storage pointer is written in Increment 1
    }
}
