package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.AnalysisChunkCoverage
import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.AnalysisChunkStatus
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.ExtractedEntity
import eu.kanade.translation.artifact.ExtractedRelationship
import eu.kanade.translation.artifact.ExtractedTerm
import eu.kanade.translation.artifact.ExtractedTermKind
import eu.kanade.translation.artifact.FactConflictState
import eu.kanade.translation.artifact.FactProvenance
import eu.kanade.translation.artifact.FactScope
import eu.kanade.translation.artifact.FactType
import eu.kanade.translation.artifact.EvidenceStrength
import eu.kanade.translation.artifact.PageRange
import eu.kanade.translation.artifact.ProfileScene
import eu.kanade.translation.artifact.SceneRegister
import eu.kanade.translation.artifact.SidecarPointer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * T924 Stage 5 slice B (T924-ST-09): the pure deterministic reconciler.
 * Pins: determinism (structural + serialized bytes), MISSING_ONLY exclusion
 * (wave-4 F-W4-3), conflict retention into unresolvedFacts (§6.3, never
 * averaged), no series promotion (§6.4), the documented sort keys / fact-id
 * assignment / scene participant remap, and the validate-every-record gate.
 */
class ProfileReconcilerTest {

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun provenance(tag: String = "p") = AnalyzerProvenance(
        providerId = "fake",
        modelId = "fake-model-$tag",
        promptVersion = 1,
        analysisSchemaVersion = 1,
        credentialFingerprint = "sig-$tag",
    )

    private fun ref(pageKey: String, blockId: String, text: String) =
        EvidenceRef(pageKey, blockId, hex64("$pageKey|$blockId|$text"))

    private fun chunk(
        ordinal: Int,
        coverage: AnalysisChunkCoverage = AnalysisChunkCoverage.COMPLETE,
        entities: List<ExtractedEntity> = emptyList(),
        terms: List<ExtractedTerm> = emptyList(),
        relationships: List<ExtractedRelationship> = emptyList(),
        scenes: List<ProfileScene> = emptyList(),
        conflictNotes: List<String> = emptyList(),
        evidence: List<EvidenceRef> = listOf(ref("p1", "p1_b1", "text")),
        corePageKeys: List<String> = listOf("p1"),
        provenanceTag: String = "p",
    ): AnalysisChunkResult = AnalysisChunkResult(
        chunkId = "chunk-$ordinal-${hex64("corpus").take(8)}",
        chunkOrdinal = ordinal,
        analysisSchemaVersion = 1,
        corePageKeys = corePageKeys,
        contextOverlapPageKeys = emptyList(),
        contributingCorpusFingerprint = hex64("corpus-$ordinal"),
        ocrArtifactRefs = corePageKeys.map {
            SidecarPointer("artifacts/x/ocr/f-${hex64(it)}.json", 1, hex64("ocr-$it"))
        },
        terms = terms,
        entities = entities,
        relationships = relationships,
        scenes = scenes,
        narrativeSummary = null,
        conflictNotes = conflictNotes,
        evidenceRefs = evidence,
        analyzerProvenance = provenance(provenanceTag),
        coverage = coverage,
        status = AnalysisChunkStatus.VALID,
        createdAtEpochMs = 1L,
    ).also { it.validationError().shouldBeNull() }

    private fun entity(id: String, source: String, target: String) = ExtractedEntity(
        entityId = id,
        canonicalSourceName = source,
        proposedTargetName = target,
        sourceNames = listOf(source),
    )

    private fun term(id: String, source: String, target: String, kind: String = "TERM") =
        ExtractedTerm(
            termId = id,
            sourceForm = source,
            canonicalTarget = target,
            aliases = emptyList(),
            kind = ExtractedTermKind.entries.firstOrNull { it.name == kind }
                ?: ExtractedTermKind.TERM,
        )

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    fun `same inputs reconcile to byte-identical profile content twice`() {
        val chunks = listOf(
            chunk(
                0,
                entities = listOf(entity("e2", "カイル", "Kyle"), entity("e1", "レイナ", "Reina")),
                terms = listOf(term("t1", "剣", "sword")),
                scenes = listOf(
                    ProfileScene(
                        sceneId = "whatever",
                        pageRange = PageRange(0, 0),
                        participants = listOf("e1"),
                        register = SceneRegister.CASUAL,
                    ),
                ),
                conflictNotes = listOf("tone cue unclear on p1"),
            ),
            chunk(
                1,
                corePageKeys = listOf("p2"),
                evidence = listOf(ref("p2", "p2_b0", "more")),
                entities = listOf(entity("e1", "レイナ", "Reina")),
            ),
        )
        val first = ProfileReconciler.reconcile(chunks)
            .shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content
        val second = ProfileReconciler.reconcile(chunks.toList())
            .shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content

        first shouldBe second
        // The full profile DTO assembled from the content serializes to the
        // same bytes both times — the determinism the freeze relies on.
        fun bytes(content: ProfileReconciler.ReconciledProfileContent): ByteArray =
            ArtifactDocumentJson.encodeToString(
                ChapterTranslationProfile(
                    version = 1,
                    contentFingerprint = "",
                    profileInputFingerprint = hex64("input"),
                    sourceRunId = "run-x",
                    analyzerProvenance = content.analyzerProvenance,
                    entities = content.entities,
                    terms = content.terms,
                    scenes = content.scenes,
                    unresolvedFacts = content.unresolvedFacts,
                    frozenAtEpochMs = 1L,
                ),
            ).encodeToByteArray()
        (bytes(first) contentEquals bytes(second)) shouldBe true
    }

    // ------------------------------------------------------------------
    // MISSING_ONLY exclusion (wave-4 F-W4-3)
    // ------------------------------------------------------------------

    @Test
    fun `missing_only chunks contribute nothing to canon and count as pending`() {
        val canon = chunk(
            0,
            entities = listOf(entity("e1", "カイル", "Kyle")),
            terms = listOf(term("t1", "剣", "sword")),
        )
        val pending = chunk(
            1,
            coverage = AnalysisChunkCoverage.MISSING_ONLY,
            corePageKeys = listOf("p2"),
            entities = listOf(entity("e9", "幽霊", "Ghost")),
            terms = listOf(term("t9", "魔法", "magic")),
            conflictNotes = listOf("pending note"),
            provenanceTag = "other",
        )

        val content = ProfileReconciler.reconcile(listOf(canon, pending))
            .shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content

        content.pendingChunkCount shouldBe 1
        content.reconciledChunkCount shouldBe 1
        content.entities.map { it.canonicalSourceForm } shouldContainExactly listOf("カイル")
        content.terms.map { it.canonicalSourceForm } shouldContainExactly listOf("剣")
        content.scenes.shouldBeEmpty()
        content.unresolvedFacts.shouldBeEmpty()
        // Provenance comes from a CANON-contributing chunk, never a pending one.
        content.analyzerProvenance.modelId shouldBe "fake-model-p"
    }

    @Test
    fun `all pending chunks still reconcile to a valid empty canon`() {
        val content = ProfileReconciler.reconcile(
            listOf(
                chunk(0, coverage = AnalysisChunkCoverage.MISSING_ONLY),
                chunk(1, coverage = AnalysisChunkCoverage.MISSING_ONLY, corePageKeys = listOf("p2")),
            ),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content
        content.pendingChunkCount shouldBe 2
        content.entities.shouldBeEmpty()
        content.terms.shouldBeEmpty()
        content.analyzerProvenance.modelId shouldBe "fake-model-p"
    }

    // ------------------------------------------------------------------
    // Conflict retention (§6.3) — never averaged
    // ------------------------------------------------------------------

    @Test
    fun `conflicting entity targets are retained as CONFLICTING unresolved facts`() {
        val content = ProfileReconciler.reconcile(
            listOf(
                chunk(0, entities = listOf(entity("e1", "カイル", "Kyle"))),
                chunk(
                    1,
                    corePageKeys = listOf("p2"),
                    entities = listOf(entity("e1", "カイル", "Kaijl")),
                ),
            ),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content

        // Nothing enters canon for the disputed identity.
        content.entities.shouldBeEmpty()
        content.unresolvedFacts.shouldHaveSize(2)
        content.unresolvedFacts.map { it.canonicalTargetForm } shouldContainExactly
            listOf("Kaijl", "Kyle")
        content.unresolvedFacts.forEach { fact ->
            fact.type shouldBe FactType.ENTITY_IDENTITY
            fact.conflictState shouldBe FactConflictState.CONFLICTING
            fact.canonicalSourceForm shouldBe "カイル"
            fact.evidenceRefs.shouldNotBeNull()
        }
    }

    @Test
    fun `conflicting term targets are retained and agreeing ones merge`() {
        val content = ProfileReconciler.reconcile(
            listOf(
                chunk(0, terms = listOf(term("t1", "剣", "sword"), term("t2", "魔法", "magic"))),
                chunk(
                    1,
                    corePageKeys = listOf("p2"),
                    terms = listOf(term("t1", "剣", "sword"), term("t2", "魔法", "magick")),
                ),
            ),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content

        content.terms.map { it.canonicalTargetForm } shouldContainExactly listOf("sword")
        content.unresolvedFacts.map { it.canonicalTargetForm } shouldContainExactly
            listOf("magic", "magick")
        content.unresolvedFacts.forEach { fact ->
            fact.conflictState shouldBe FactConflictState.CONFLICTING
            fact.type shouldBe FactType.TERM
        }
    }

    @Test
    fun `model conflict notes persist as WEAK unresolved facts`() {
        val content = ProfileReconciler.reconcile(
            listOf(chunk(0, conflictNotes = listOf("weak male cue on p1", "weak male cue on p1"))),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content

        val note = content.unresolvedFacts.single()
        note.type shouldBe FactType.NARRATIVE_STATE
        note.evidenceStrength shouldBe EvidenceStrength.WEAK
        note.conflictState shouldBe FactConflictState.UNRESOLVED
        note.note shouldBe "weak male cue on p1"
    }

    // ------------------------------------------------------------------
    // No series promotion (§6.4)
    // ------------------------------------------------------------------

    @Test
    fun `nothing auto-promotes to series scope and candidates stay empty`() {
        val content = ProfileReconciler.reconcile(
            listOf(
                chunk(
                    0,
                    entities = listOf(entity("e1", "カイル", "Kyle")),
                    conflictNotes = listOf("note"),
                ),
            ),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content

        content.seriesUpdateCandidates.shouldBeEmpty()
        content.correctionCandidates.shouldBeEmpty()
        (content.entities + content.terms + content.unresolvedFacts).forEach { fact ->
            fact.scope shouldBe FactScope.CANONICAL_CHAPTER_WIDE
            fact.provenance shouldBe FactProvenance.CHAPTER_ANALYSIS
        }
    }

    // ------------------------------------------------------------------
    // Ordering pins + id assignment + scene participant remap
    // ------------------------------------------------------------------

    @Test
    fun `ordering is pinned and fact ids scene ids and participants are remapped`() {
        val content = ProfileReconciler.reconcile(
            listOf(
                chunk(
                    0,
                    entities = listOf(entity("e1", "レイナ", "Reina")),
                    scenes = listOf(
                        ProfileScene(
                            sceneId = "chunk-scene-9",
                            pageRange = PageRange(4, 6),
                            participants = listOf("e1", "e404"),
                            register = SceneRegister.ROUGH,
                        ),
                        ProfileScene(
                            sceneId = "chunk-scene-1",
                            pageRange = PageRange(0, 2),
                            participants = listOf("e1"),
                            register = SceneRegister.CASUAL,
                        ),
                    ),
                ),
                chunk(
                    1,
                    corePageKeys = listOf("p2"),
                    evidence = listOf(ref("p2", "p2_b0", "more")),
                    entities = listOf(
                        entity("e1", "カイル", "Kyle"),
                        entity("e2", "レイナ", "Reina"), // merges with chunk 0's レイナ
                    ),
                ),
            ),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content

        // Entities sorted by (type, source, target); relationships none.
        content.entities.map { it.canonicalSourceForm } shouldContainExactly
            listOf("カイル", "レイナ")
        content.entities.map { it.factId } shouldContainExactly listOf("f-1", "f-2")
        // Merged evidence union deduped; sorted by page then block.
        content.entities[1].evidenceRefs.map { it.pageKey } shouldContainExactly
            listOf("p1", "p2")

        // Scenes sorted by (firstPage, lastPage, chunkOrdinal, in-chunk index);
        // ids reassigned s-1..; participants remapped to final fact ids and the
        // unknown e404 dropped (never guessed).
        content.scenes.map { it.pageRange.firstNaturalPageIndex } shouldContainExactly
            listOf(0, 4)
        content.scenes.map { it.sceneId } shouldContainExactly listOf("s-1", "s-2")
        content.scenes[0].participants shouldContainExactly listOf("f-2")
        content.scenes[1].participants shouldContainExactly listOf("f-2")
    }

    @Test
    fun `scene participants pointing at a conflicted entity are dropped`() {
        val first = chunk(0, entities = listOf(entity("e1", "カイル", "Kyle")))
        val conflicting = chunk(
            1,
            corePageKeys = listOf("p2"),
            entities = listOf(entity("e1", "カイル", "Kaijl")),
            scenes = listOf(
                ProfileScene(
                    sceneId = "s",
                    pageRange = PageRange(1, 2),
                    participants = listOf("e1"),
                    register = SceneRegister.POLITE,
                ),
            ),
        )
        val content = ProfileReconciler.reconcile(listOf(first, conflicting))
            .shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content
        content.entities.shouldBeEmpty()
        content.scenes.single().participants.shouldBeEmpty()
    }

    @Test
    fun `non-term kinds are retained as notes instead of being dropped silently`() {
        val content = ProfileReconciler.reconcile(
            listOf(chunk(0, terms = listOf(term("t1", "王都", "Royal Capital", kind = "PLACE")))),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Reconciled>().content
        content.terms.single().note shouldBe "termKind=PLACE"
    }

    // ------------------------------------------------------------------
    // Validate-every-record gate (INV-04/22)
    // ------------------------------------------------------------------

    @Test
    fun `a semantically invalid chunk record rejects the reconcile`() {
        val broken = chunk(0).copy(chunkId = "")
        ProfileReconciler.reconcile(listOf(broken)).shouldBeInstanceOf<
            ProfileReconciler.ReconcileOutcome.Rejected,
        >()
    }

    @Test
    fun `a non-contiguous ordinal sequence rejects the reconcile`() {
        ProfileReconciler.reconcile(
            listOf(chunk(0), chunk(2, corePageKeys = listOf("p2"))),
        ).shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Rejected>()
    }

    @Test
    fun `an INVALID persisted chunk rejects the reconcile instead of advancing`() {
        val invalid = chunk(0).copy(
            status = AnalysisChunkStatus.INVALID,
            validationFailureReason = "test injection",
        )
        invalid.validationError().shouldBeNull()
        ProfileReconciler.reconcile(listOf(invalid)).shouldBeInstanceOf<
            ProfileReconciler.ReconcileOutcome.Rejected,
        >()
    }

    @Test
    fun `reconciling zero chunks rejects`() {
        ProfileReconciler.reconcile(emptyList())
            .shouldBeInstanceOf<ProfileReconciler.ReconcileOutcome.Rejected>()
    }
}
