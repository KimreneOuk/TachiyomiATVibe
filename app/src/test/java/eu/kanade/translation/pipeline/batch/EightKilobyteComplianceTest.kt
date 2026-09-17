package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.PlannedEnvelope
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.ocrFingerprint
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.analysis.AnalysisEngineTransport
import eu.kanade.translation.translator.contextual.AnalysisChunkPolicy
import eu.kanade.translation.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.translator.contextual.EnvelopePlannerPolicy
import eu.kanade.translation.translator.contextual.StreamingChunkPlanner
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.providers.AiTranslator
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EightKilobyteComplianceTest {

    @TempDir
    lateinit var mangaDir: File

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        fileCreator = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String) = TranslationBlock(
        blockId = "b1",
        text = text,
        translation = "",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(pageKey: String, text: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(block(text)),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private suspend fun runFakeOcrWithText(
        store: ChapterTranslationStore,
        pageKey: String,
        text: String,
    ) {
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot(pageKey)
        store.mergeOcr(
            OcrStagePatch(
                pageKey = pageKey,
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage(pageKey, text),
                expectedLeaseToken = lease.token,
            ),
            description = "8k compliance fake ocr",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
    }

    private class CountingTranslator : AiTranslator() {
        val requests = mutableListOf<TranslationContextChunk>()
        override val fromLang = TextRecognizerLanguage.JAPANESE
        override val toLang = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch {
            requests += chunk
            return ContextualTranslationBatch(
                idToBlockIndex = emptyMap(),
                results = emptyList(),
            )
        }

        override suspend fun promptText(prompt: String): String = ""
    }

    @Test
    fun `translation profile envelopes comply with 8k`() {
        val policy = EnvelopePlannerPolicy()
        policy.maxBlocksPerEnvelope shouldBe 12
        policy.maxContributingPages shouldBe 3
        policy.maxEstimatedInputTokens shouldBe 4_096
        policy.maxEstimatedOutputTokens shouldBe 3_584

        val totalBudget = policy.maxEstimatedInputTokens + policy.maxEstimatedOutputTokens + 512
        totalBudget shouldBe 8_192
        (totalBudget <= 8_192) shouldBe true
    }

    @Test
    fun `analysis chunks comply with 8k`() {
        val policy = AnalysisChunkPolicy()
        policy.maxEstimatedInputTokens shouldBe 4_096
        AnalysisEngineTransport.ANALYSIS_MAX_OUTPUT_TOKENS shouldBe 512
        ChapterProfileBatchCoordinator.ANALYSIS_MAX_OUTPUT_TOKENS shouldBe 512

        // The per-page estimates exclude the chunk-level fixed framing
        // (system+user prompts + envelope scaffolding, ~400 tokens); the
        // input cap reserves that framing room, and the free-form summary
        // output budget is small by design, so the pinned arithmetic sums
        // well under the window.
        val totalAnalysisBudget = policy.maxEstimatedInputTokens +
            AnalysisEngineTransport.ANALYSIS_MAX_OUTPUT_TOKENS + 512
        totalAnalysisBudget shouldBe 5_120
        (totalAnalysisBudget <= 8_192) shouldBe true
    }

    @Test
    fun `LM_STUDIO and DEFAULT profile constraints comply with 8k`() {
        val lmStudio = TranslationContextChunkPlanner.constraintsFor(
            TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        lmStudio.maxContextTokens shouldBe 8_192
        lmStudio.safetyMargin shouldBe 512
        lmStudio.minOutputTokens shouldBe 256
        lmStudio.maxRollingContextTokens shouldBe 512

        val defaultProfile = TranslationContextChunkPlanner.constraintsFor(
            TranslationContextChunkPlanner.Profile.DEFAULT,
        )
        defaultProfile.maxContextTokens shouldBe 8_192
        defaultProfile.safetyMargin shouldBe 512
    }

    @Test
    fun `effectiveOutputCap bans the 256 token floor fit hack and returns minus one when budget unfulfillable`() {
        val constraints = TranslationContextChunkPlanner.constraintsFor(
            TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        // maxContextTokens = 8192, safetyMargin = 512, minOutputTokens = 256.
        // Prompt tokens = 7500 -> available = 8192 - 512 - 7500 = 180 (< 256 minOutputTokens).
        val insufficient = StreamingChunkPlanner.effectiveOutputCap(
            promptTokens = 7_500,
            requestedOutputTokens = 1_024,
            constraints = constraints,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        insufficient shouldBe -1

        // Available output = 8192 - 512 - 7000 = 680 (>= 256).
        val sufficient = StreamingChunkPlanner.effectiveOutputCap(
            promptTokens = 7_000,
            requestedOutputTokens = 1_024,
            constraints = constraints,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        sufficient shouldBe 680
    }

    @Test
    fun `batch_v1 output cap must cover the response scaffolding the model emits`() {
        val constraints = TranslationContextChunkPlanner.constraintsFor(
            TranslationContextChunkPlanner.Profile.DEFAULT,
        )
        val reserve = TranslationContextChunkPlanner.batchResponseOverheadTokens(
            blockCount = 44,
            pageCount = 16,
        )
        // Text-only estimate of a 44-block envelope: far below the JSON
        // scaffolding needed to emit the whole BATCH_V1 response. Before the
        // fix the cap equaled the text estimate alone, guaranteeing truncated
        // JSON and a protocol pause on every multi-block envelope.
        val textEstimate = 256
        val cap = StreamingChunkPlanner.effectiveOutputCap(
            promptTokens = 1_000,
            requestedOutputTokens = textEstimate,
            constraints = constraints,
            protocol = ContextualRequestProtocol.BATCH_V1,
            blockCount = 44,
            pageCount = 16,
        )

        (cap >= textEstimate + reserve) shouldBe true
        (
            1_000 + cap + constraints.safetyMargin + reserve <=
                constraints.maxContextTokens
            ) shouldBe true
    }

    @Test
    fun `legacy envelope shape is subjected to execution-time token check and pauses when oversized`() = runTest {
        val store = lazyStore()
        val pageKey = "p1"
        store.preRegisterPages(listOf(pageKey))
        // 9000 characters in Japanese generates ~9000 tokens, which exceeds the 8192 ceiling alone.
        val oversizedText = "あ".repeat(9_000)
        runFakeOcrWithText(store, pageKey, oversizedText)

        val translator = CountingTranslator()
        val snapshot = store.snapshot(pageKey)
        val livePage = snapshot.page.shouldNotBeNull()

        val plannedBlock = PlannedBlock(
            stableBlockId = "p0_b1",
            sourceText = livePage.blocks.single().text,
            ocrFingerprint = livePage.blocks.single().ocrFingerprint(),
            blockIndex = 0,
        )
        val pageWork = PageDispatchWork(
            pageKey = pageKey,
            naturalPageIndex = 0,
            ocrContentFingerprint = hex64("ocr-page"),
            sourceFingerprint = livePage.sourceFingerprint,
            planPageVersion = snapshot.pageVersion,
            planCandidateGenerationId = snapshot.candidateGenerationId,
            planDependencyFingerprint = snapshot.dependencyFingerprint,
            planArtifactPageVersion = snapshot.artifactPageVersion,
            blocks = listOf(plannedBlock),
        )
        val envelope = PlannedEnvelope(
            envelopeId = "e-0",
            orderedPageKeys = listOf(pageKey),
            blockIds = listOf("p0_b1"),
            contributingCorpusFingerprint = hex64("corpus"),
            estimatedInputTokens = 64,
            estimatedOutputTokens = 256,
            structuralBlockCount = 1,
            contributingPageCount = 1,
        )
        val hashingView = EnvelopePlan(
            planFingerprint = "",
            planInputFingerprint = hex64("plan-input"),
            plannerVersion = 1,
            envelopes = listOf(envelope),
            createdAtEpochMs = 0L,
        )
        val canonical = ArtifactDocumentJson.encodeToString(
            EnvelopePlan.serializer(),
            hashingView,
        )
        val planFingerprint = StageFingerprints.envelopePlanContentFingerprint(canonical)
        val plan = EnvelopePlan(
            planFingerprint = planFingerprint,
            planInputFingerprint = hex64("plan-input"),
            plannerVersion = 1,
            envelopes = listOf(envelope),
            createdAtEpochMs = 1L,
        )

        val artifact = store.artifactStore.shouldNotBeNull()
        if (artifact.readManifest() == null) {
            artifact.publishManifest(ChapterArtifactManifest(chapterKey = "Chapter 1"))
        }

        val executor = ProfileEnvelopeExecutor(
            store = store,
            textTranslator = translator,
            profileContentFingerprint = hex64("profile"),
            frozenProfile = null, // Legacy envelope shape!
            replan = { ReplanResult.NothingPending },
            sublimitGate = BatchRequestSublimitGate(),
            providerProfile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        val work = EnvelopeDispatchWork(
            plan = plan,
            planFingerprint = planFingerprint,
            pages = mapOf(pageKey to pageWork),
            providerBackend = "fake",
            providerModel = null,
            credentialScope = null,
        )

        val outcome = executor.run(work)

        // Verifies execution-time token check executed on legacy shape and paused rather than shipping.
        outcome.shouldBeInstanceOf<ProfileEnvelopeExecutor.PhaseOutcome.Paused>()
        outcome.reason shouldContain "token-oversized"
        translator.requests.size shouldBe 0
    }

    @Test
    fun `unfulfillable budget triggers PAUSE rather than shipping prompt`() = runTest {
        val store = lazyStore()
        val pageKey = "p1"
        store.preRegisterPages(listOf(pageKey))
        // Text sized so that available output tokens is less than 256
        // e.g. ~7450 tokens.
        val largeText = "あ".repeat(7_450)
        runFakeOcrWithText(store, pageKey, largeText)

        val translator = CountingTranslator()
        val snapshot = store.snapshot(pageKey)
        val livePage = snapshot.page.shouldNotBeNull()

        val plannedBlock = PlannedBlock(
            stableBlockId = "p0_b1",
            sourceText = livePage.blocks.single().text,
            ocrFingerprint = livePage.blocks.single().ocrFingerprint(),
            blockIndex = 0,
        )
        val pageWork = PageDispatchWork(
            pageKey = pageKey,
            naturalPageIndex = 0,
            ocrContentFingerprint = hex64("ocr-page"),
            sourceFingerprint = livePage.sourceFingerprint,
            planPageVersion = snapshot.pageVersion,
            planCandidateGenerationId = snapshot.candidateGenerationId,
            planDependencyFingerprint = snapshot.dependencyFingerprint,
            planArtifactPageVersion = snapshot.artifactPageVersion,
            blocks = listOf(plannedBlock),
        )
        val envelope = PlannedEnvelope(
            envelopeId = "e-0",
            orderedPageKeys = listOf(pageKey),
            blockIds = listOf("p0_b1"),
            contributingCorpusFingerprint = hex64("corpus"),
            estimatedInputTokens = 64,
            estimatedOutputTokens = 256,
            structuralBlockCount = 1,
            contributingPageCount = 1,
        )
        val hashingView = EnvelopePlan(
            planFingerprint = "",
            planInputFingerprint = hex64("plan-input"),
            plannerVersion = 1,
            envelopes = listOf(envelope),
            createdAtEpochMs = 0L,
        )
        val canonical = ArtifactDocumentJson.encodeToString(
            EnvelopePlan.serializer(),
            hashingView,
        )
        val planFingerprint = StageFingerprints.envelopePlanContentFingerprint(canonical)
        val plan = EnvelopePlan(
            planFingerprint = planFingerprint,
            planInputFingerprint = hex64("plan-input"),
            plannerVersion = 1,
            envelopes = listOf(envelope),
            createdAtEpochMs = 1L,
        )

        val artifact = store.artifactStore.shouldNotBeNull()
        if (artifact.readManifest() == null) {
            artifact.publishManifest(ChapterArtifactManifest(chapterKey = "Chapter 1"))
        }

        val executor = ProfileEnvelopeExecutor(
            store = store,
            textTranslator = translator,
            profileContentFingerprint = hex64("profile"),
            frozenProfile = null,
            replan = { ReplanResult.NothingPending },
            sublimitGate = BatchRequestSublimitGate(),
            providerProfile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        val work = EnvelopeDispatchWork(
            plan = plan,
            planFingerprint = planFingerprint,
            pages = mapOf(pageKey to pageWork),
            providerBackend = "fake",
            providerModel = null,
            credentialScope = null,
        )

        val outcome = executor.run(work)

        outcome.shouldBeInstanceOf<ProfileEnvelopeExecutor.PhaseOutcome.Paused>()
        translator.requests.size shouldBe 0
    }
}
