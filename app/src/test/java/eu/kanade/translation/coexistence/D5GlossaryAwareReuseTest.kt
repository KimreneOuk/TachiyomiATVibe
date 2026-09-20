package eu.kanade.translation.coexistence

import eu.kanade.translation.artifact.loadArtifact

import com.hippo.unifile.UniFile
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.ArtifactSeed
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageDecision
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.EngineLane
import eu.kanade.translation.pipeline.PageDecode
import eu.kanade.translation.pipeline.batch.BatchContextFrontier
import eu.kanade.translation.pipeline.batch.BatchResumePlanner
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationReadingOrder
import java.io.ByteArrayInputStream

/**
 * T917 Phase 3 — D5 glossary-aware translation reuse gate (phase3-design §1, §5.1).
 *
 * Drives the REAL production planning seam: a real [ChapterTranslationStore]
 * with a real ARTIFACTS-authority manifest (production fresh-chapter recipe),
 * real glossary publication through the store, real stage fingerprints
 * ([PageDecode.batchExpectedFingerprints] over a real engine signature), and a
 * real [BatchResumePlanner] built exactly as `BatchChapterTranslator` builds
 * it. The batch re-run's planner decisions are the paid-call contract: RUN is
 * exactly one provider call for that page in the next pass, REUSE is zero
 * (transport-level exactly-once for RUN/REUSE lanes is pinned by the Phase-1/2
 * coexistence oracles; this suite pins the decision that bills or skips).
 *
 * RED (committed first, phase3-design §6 step 1): no glossary-aware gate
 * exists, so the batch re-run REUSEs pages whose persisted result predates the
 * glossary they were translated against — the H-07 defect (a page translated
 * while the glossary was empty is REUSEd forever). Each RED assertion below
 * documents its expected failure message shape.
 */
class D5GlossaryAwareReuseTest {

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private val fromLang = TextRecognizerLanguage.JAPANESE
    private val toLang = TextTranslatorLanguage.ENGLISH

    /** Same engine identity for plan and stamp — the reuse fingerprint input. */
    private fun translatorSignature() = EngineLane.EngineSignature(
        category = TranslationEngineCategory.AI_MODEL,
        standardEngine = StandardEngine.MLKIT,
        aiEngine = AiEngine.GEMINI,
        apiKeyHash = "d5-key-hash",
        baseUrl = "http://d5.test",
        modelName = "d5-model",
        temperature = "0.7",
        maxTokens = "1024",
        readingOrder = TranslationReadingOrder.AUTO,
        fromLang = fromLang,
        toLang = toLang,
    )

    private fun expectedFingerprints(): BatchExpectedFingerprints = PageDecode.batchExpectedFingerprints(
        translatorSignature(),
        OcrModel.MLKIT,
        TranslationReadingOrder.AUTO,
        InpaintingMode.FAST,
        fromLang,
        toLang,
    )

    /** Durable document set + layout, kept so a test can reload the manifest bytes. */
    private lateinit var d5Documents: AtomicChapterDocuments
    private lateinit var d5Layout: ChapterArtifactLayout

    /**
     * Real store with ARTIFACTS authority over in-memory artifact documents —
     * the minimal production state in which a glossary version exists at all
     * (memory-only stores never publish a manifest glossary pointer).
     */
    private fun artifactBackedStore(pageKeys: List<String>): ChapterTranslationStore {
        d5Documents = AtomicChapterDocuments(FakeChapterDocumentIo())
        d5Layout = ChapterArtifactLayout("D5 Chapter")
        val artifactStore = ChapterArtifactEngine(d5Documents, d5Layout)
        // Production fresh-chapter recipe (ChapterTranslationStore.ensureArtifactStoreLocked):
        // migrate the empty legacy snapshot, then flip the authority.
        val manifest = artifactStore
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        return ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) },
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    /**
     * A page translated EARLY (while the chapter glossary was still empty),
     * committed through the real manual-path commit ([patchPage]). Provenance
     * matches the current engine exactly, so TODAY — and for every stage except
     * the glossary gate — the batch re-run has every reason to REUSE it.
     * renderStatus stays PENDING so the commit is not display-ready and the
     * manifest candidate stays live (keeps this suite independent of the §4
     * patchPage candidate-grace alignment, which has its own regression test).
     */
    private fun translatedEarlyPage(key: String, expected: BatchExpectedFingerprints): PageTranslation =
        PageTranslation(sourceFileName = key).apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.READY
            inpaintStatus = StageStatus.READY
            renderStatus = StageStatus.PENDING
            sourceFingerprint = sourceFingerprintOf(key)
            detectionFingerprint = expected.detection
            ocrFingerprint = expected.ocr
            inpaintFingerprint = expected.inpaint
            translationFingerprint = expected.translation
            blocks += TranslationBlock(
                text = "text-$key",
                translation = "tr-text-$key",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            )
        }

    /** OCR recognized no text: translation/render skip terminally (gate-exempt shape). */
    private fun textlessPage(key: String, expected: BatchExpectedFingerprints): PageTranslation =
        PageTranslation(sourceFileName = key).apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.SKIPPED
            inpaintStatus = StageStatus.SKIPPED
            renderStatus = StageStatus.SKIPPED
            sourceFingerprint = sourceFingerprintOf(key)
            detectionFingerprint = expected.detection
            ocrFingerprint = expected.ocr
            inpaintFingerprint = expected.inpaint
        }

    private suspend fun commitPage(store: ChapterTranslationStore, page: PageTranslation) {
        val key = checkNotNull(page.sourceFileName)
        // The guarded writer (pageWriteRejection) — NOT patchPage, whose
        // dependency-fingerprint clause still lacks the candidate-grace until
        // the §4 alignment lands. Keeping this suite on the graceful writer
        // isolates D5 from the §4 fix, which has its own regression test
        // (ChapterTranslationStorePatchPageGraceTest).
        val result = store.updatePageGuarded(
            pageKey = key,
            expected = store.snapshot(key).toPrecondition(),
            description = "D5 fixture commit",
        ) { page }
        check(result is ChapterTranslationStore.PatchResult.Accepted) {
            "D5 fixture commit rejected: ${(result as? ChapterTranslationStore.PatchResult.Rejected)?.reason}"
        }
    }

    /** The exact production planning construction (BatchChapterTranslator). */
    private fun planner(store: ChapterTranslationStore, pageKeys: List<String>, isAi: Boolean): BatchResumePlanner =
        BatchResumePlanner(
            store = store,
            provider = mockk(relaxed = true),
            manga = mockk {
                every { id } returns 2L
                every { title } returns "d5-fixture"
                every { source } returns 1L
            },
            source = mockk<HttpSource> {
                every { id } returns 1L
            },
            chapter = mockk {
                every { id } returns 10L
                every { name } returns "d5-chapter"
                every { scanlator } returns null
            },
            orderedStreams = pageKeys.map { key -> key to { ByteArrayInputStream(ByteArray(0)) } },
            isAi = isAi,
            sourceFingerprints = pageKeys.associateWith(::sourceFingerprintOf),
            expectedBatchFingerprints = expectedFingerprints(),
            contextFrontier = BatchContextFrontier(pageKeys.mapIndexed { index, key -> key to index }.toMap()),
            inpaintingModeFromPref = { InpaintingMode.FAST },
        )

    private fun translationDecisions(planner: BatchResumePlanner): Map<String, StageDecision> =
        planner.batchPagePlans.mapValues { (_, plan) ->
            plan.stages.first { it.stage == BatchStage.TRANSLATION }.decision
        }

    private fun sourceFingerprintOf(key: String) = "sha-$key"

    /**
     * Shared scenario: p0 translated early, then the fold publishes the first
     * glossary content (version 1); p2 is the textless exemption witness.
     */
    private fun maturedGlossaryChapter(): ChapterTranslationStore = runBlocking {
        val store = artifactBackedStore(listOf("p0", "p2"))
        val expected = expectedFingerprints()
        commitPage(store, translatedEarlyPage("p0", expected))
        commitPage(store, textlessPage("p2", expected))
        // The manual path folds the page's pairs into the chapter glossary
        // AFTER the page translated (while it was still empty) — version 1.
        store.updateGlossary(mapOf("太郎" to "Taro"))
        store
    }

    // ------------------------------------------------------------------
    // (a) repair: glossary maturation must downgrade the stale page's REUSE
    // ------------------------------------------------------------------

    @Test
    fun `glossary maturation plans RUN repair for the early page`() = runBlocking<Unit> {
        val store = maturedGlossaryChapter()
        withClue("D5 fixture: the fold must publish glossary version 1") {
            store.artifactManifest?.glossary?.version shouldBe 1
        }

        val decisions = translationDecisions(planner(store, listOf("p0", "p2"), isAi = true))

        withClue(
            "D5 (a): the page translated while the glossary was empty must be repaired — " +
                "planner must downgrade its translation REUSE to RUN. RED right reason: no " +
                "glossary-aware gate exists, so REUSE stays where the defect is " +
                "(expected:<RUN> but was:<REUSE>)",
        ) {
            decisions.getValue("p0") shouldBe StageDecision.RUN
        }
        withClue(
            "D5 (a): exactly one extra paid call — the repair is targeted at the stale page, " +
                "never chapter-wide",
        ) {
            decisions.values.count { it == StageDecision.RUN } shouldBe 1
        }
        withClue("D5 (a): TERMINAL_COMPLETE (textless) pages are exempt from the gate") {
            decisions.getValue("p2") shouldBe StageDecision.TERMINAL_COMPLETE
        }
    }

    // ------------------------------------------------------------------
    // (b) convergence: the pass after the repair must be free (no oscillation)
    // ------------------------------------------------------------------

    @Test
    fun `second batch re-run with unchanged glossary plans REUSE with zero paid calls`() = runBlocking<Unit> {
        val store = maturedGlossaryChapter()
        val pass1 = planner(store, listOf("p0", "p2"), isAi = true)

        // The repair pass commits through the real batch write path: the
        // TRANSLATION provenance stamp (BatchResumePlanner.stampBatchProvenance,
        // applied by BatchWriteGate.guardedBatchUpdate) rides the commit payload.
        val repaired = pass1.stampBatchProvenance(
            checkNotNull(store.snapshot("p0").page),
            BatchStage.TRANSLATION,
        )
        commitPage(store, repaired)
        // The repaired page re-folds the SAME pairs: the glossary equality gate
        // (ChapterGlossaryStore.updateGlossary) must keep the version at 1.
        store.updateGlossary(mapOf("太郎" to "Taro"))
        withClue("D5 (b): re-folding identical pairs must not bump the glossary version") {
            store.artifactManifest?.glossary?.version shouldBe 1
        }

        val decisions = translationDecisions(planner(store, listOf("p0", "p2"), isAi = true))

        withClue(
            "D5 (b): convergence — the re-run after a repair plans zero paid work " +
                "(guards the mass re-translation regression: version misreads would show as RUN here)",
        ) {
            decisions.values.filter { it == StageDecision.RUN } shouldBe emptyList()
        }
        withClue("D5 (b): the repaired page is reusable again") {
            decisions.getValue("p0") shouldBe StageDecision.REUSE
        }
    }

    // ------------------------------------------------------------------
    // (b2) T924 gate 1.5: glossary-repair reuse survives a manifest v3 rewrite
    // ------------------------------------------------------------------

    @Test
    fun `glossary repair reuse stays authoritative after a manifest v3 rewrite cycle`() = runBlocking<Unit> {
        val store = maturedGlossaryChapter()
        // The repair pass, exactly as in (b): one paid RUN, then convergence.
        val pass1 = planner(store, listOf("p0", "p2"), isAi = true)
        val repaired = pass1.stampBatchProvenance(
            checkNotNull(store.snapshot("p0").page),
            BatchStage.TRANSLATION,
        )
        commitPage(store, repaired)
        store.updateGlossary(mapOf("太郎" to "Taro"))

        // The manifest v3 rewrite cycle: every publication rewrites the
        // manifest as current-version bytes (T924-SC-04), and a restart
        // reloads them through a fresh artifact store. Republish the loaded
        // manifest (a real publication primitive) and reload it.
        val reloadedArtifactStore = ChapterArtifactEngine(d5Documents, d5Layout)
        val manifestBeforeRewrite = reloadedArtifactStore.readManifest().shouldNotBeNull()
        check(
            reloadedArtifactStore.publishManifest(
                manifestBeforeRewrite.copy(updatedAtEpochMs = manifestBeforeRewrite.updatedAtEpochMs + 1),
            ),
        ) { "D5 (b2): manifest v3 rewrite publication failed" }
        val manifest = reloadedArtifactStore.readManifest().shouldNotBeNull()
        withClue("D5 (b2): the rewrite cycle must preserve the glossary version") {
            manifest.glossary?.version shouldBe 1
        }

        // The reloaded durable state still plans zero paid work: the repaired
        // page's translation provenance stayed authoritative, so the batch
        // re-run REUSEs it instead of re-billing.
        val reloadedStore = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = listOf("p0", "p2").associateWith { key ->
                checkNotNull(store.snapshot(key).page) { "D5 (b2): live page $key missing" }
            },
            artifactStore = reloadedArtifactStore,
            initialArtifactManifest = manifest,
        )
        val decisions = translationDecisions(planner(reloadedStore, listOf("p0", "p2"), isAi = true))
        withClue("D5 (b2): zero paid work after the manifest v3 rewrite cycle") {
            decisions.values.filter { it == StageDecision.RUN } shouldBe emptyList()
        }
        decisions.getValue("p0") shouldBe StageDecision.REUSE
        decisions.getValue("p2") shouldBe StageDecision.TERMINAL_COMPLETE
    }

    // ------------------------------------------------------------------
    // (c) grandfather rule: a glossary-less chapter stays cost-flat
    // ------------------------------------------------------------------

    @Test
    fun `glossary-less chapter re-run plans REUSE with zero paid calls`() = runBlocking<Unit> {
        val store = artifactBackedStore(listOf("p0", "p1"))
        val expected = expectedFingerprints()
        commitPage(store, translatedEarlyPage("p0", expected))
        commitPage(store, translatedEarlyPage("p1", expected))
        // No fold ever ran: no glossary pointer exists.
        withClue("D5 fixture: the chapter has no glossary pointer") {
            store.artifactManifest?.glossary shouldBe null
        }

        val decisions = translationDecisions(planner(store, listOf("p0", "p1"), isAi = true))

        withClue(
            "D5 (c): absence of a glossary pointer keeps REUSE unchanged — zero extra paid " +
                "calls for glossary-less chapters (grandfather rule; also the guard against the " +
                "declined hash-embedding variant that would blanket-stale every chapter once)",
        ) {
            decisions.values.filter { it == StageDecision.RUN } shouldBe emptyList()
        }
        decisions.getValue("p0") shouldBe StageDecision.REUSE
        decisions.getValue("p1") shouldBe StageDecision.REUSE
    }

    // ------------------------------------------------------------------
    // (d) isolation: the standard-engine lane's decisions are unchanged
    // ------------------------------------------------------------------

    @Test
    fun `standard-engine chapter plans byte-identical decisions even with a matured glossary`() = runBlocking<Unit> {
        // Worst case for a leak: the glossary HAS matured past every recorded page.
        val store = maturedGlossaryChapter()

        val plan = planner(store, listOf("p0", "p2"), isAi = false).batchPagePlans

        withClue(
            "D5 (d): the standard lane must be untouched — translation decisions identical to " +
                "pre-D5 (decision AND reason), with a matured glossary present in the store",
        ) {
            plan.getValue("p0").stages.first { it.stage == BatchStage.TRANSLATION } shouldBe
                eu.kanade.translation.model.StageWorkDecision(
                    stage = BatchStage.TRANSLATION,
                    decision = StageDecision.REUSE,
                    reason = eu.kanade.translation.model.StageReasonCode.VALID_ARTIFACT,
                )
        }
        withClue("D5 (d): the standard lane's textless page stays TERMINAL_COMPLETE") {
            plan.getValue("p2").stages.first { it.stage == BatchStage.TRANSLATION }.decision shouldBe
                StageDecision.TERMINAL_COMPLETE
        }
        withClue("D5 (d): zero PAID work planned on the standard lane (translation stage)") {
            plan.values
                .map { it.stages.first { stage -> stage.stage == BatchStage.TRANSLATION } }
                .count { it.decision == StageDecision.RUN } shouldBe 0
        }
    }
}
