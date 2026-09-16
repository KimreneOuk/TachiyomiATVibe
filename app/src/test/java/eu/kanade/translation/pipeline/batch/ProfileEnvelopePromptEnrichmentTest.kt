package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.EnvelopePolicySnapshot
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocrFingerprint
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisCoverage
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.analysis.GlossaryEntry
import eu.kanade.translation.translator.analysis.GlossaryEntryKind
import eu.kanade.translation.translator.analysis.GlossarySynthesizer
import eu.kanade.translation.translator.analysis.GlossarySynthesisOutcome
import eu.kanade.translation.translator.analysis.ValidatedEntity
import eu.kanade.translation.translator.analysis.ValidatedTerm
import eu.kanade.translation.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.translator.contextual.EnvelopePlannerPolicy
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.providers.AiTranslator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * T924 Stage-6 slice B (design §7 + §8 tail; gates 5.6/5.8 in-repo portion):
 * profile-aware PROMPT ENRICHMENT of the serial envelope executor —
 * enriched chunk shape (profile subset sheet + scene fence + gap-free
 * rolling history with the pronoun-marking rule), execution-time token
 * recompute with WHOLE-PAGE splits, single-oversized-page rejection,
 * one-in-flight with enriched payloads, and the legacy-shape fallback when
 * no frozen profile is present.
 */
class ProfileEnvelopePromptEnrichmentTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (slice-A dispatch-test idioms).
    // ------------------------------------------------------------------

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

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactStore =
        ChapterArtifactStore(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        fileCreator = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    /** Summary-glossary seam (Director redesign): an empty sheet freezes fine. */
    private val emptyGlossarySynthesizer = GlossarySynthesizer { _, _, _ ->
        GlossarySynthesisOutcome.Glossary(emptyList())
    }

    private fun coordinator(
        store: ChapterTranslationStore,
        pages: List<PageKey>,
        runner: AnalysisChunkRunner,
        translator: FakeTranslator?,
        maxPagesPerEnvelope: Int = 8,
        synthesizer: GlossarySynthesizer = emptyGlossarySynthesizer,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = FakePreflightOcrWorker(store),
        frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        ).copy(
            envelopePolicy = EnvelopePolicySnapshot(
                maxBlocks = 32,
                maxPages = maxPagesPerEnvelope,
            ),
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = runner,
        glossarySynthesizer = synthesizer,
        textTranslator = translator,
        translationSublimitGate = BatchRequestSublimitGate(),
    )

    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
    ) : NativeLaneWorker {
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrPage(pageKey, pageText(pageKey)),
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 fake preflight ocr",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
            val after = store.snapshot(pageKey)
            return OcrReadyPageRef(
                pageKey = pageKey,
                pageIndex = pageIndex,
                generation = after.generation,
                blockFingerprints = emptyList(),
                leaseToken = lease.token,
                candidateGenerationId = after.candidateGenerationId,
                dependencyFingerprint = after.dependencyFingerprint,
                artifactPageVersion = after.artifactPageVersion,
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {}
    }

    /** Default small page text; the matcher hits "source" in every page. */
    private fun pageText(pageKey: String): String = "source-$pageKey dialogue"

    /** Typed fake analyzer: one entity + one term whose source form is "source". */
    private inner class FakeAnalyzer : AnalysisChunkRunner {
        override suspend fun executeChunk(
            chunk: PlannedAnalysisChunk,
            identity: AnalysisRunIdentity,
            evidence: AnalysisEvidenceTexts,
        ): AnalysisChunkRunOutcome {
            val firstStoragePage = chunk.contributingPageKeys.first()
            val wirePage = evidence.wirePageKeyByStorageKey[firstStoragePage].shouldNotBeNull()
            val wireBlock = evidence.blockIdsByPage[wirePage].shouldNotBeNull().first()
            val sourceText = evidence.textByBlockId[wireBlock].shouldNotBeNull()
            val response = AnalysisResponseValidator.ValidatedAnalysisResponse(
                chunkId = chunk.chunkId,
                terms = listOf(ValidatedTerm("t001", "source", "Source", emptyList(), "TERM")),
                entities = listOf(
                    ValidatedEntity("e001", listOf("source"), "source", "Source", emptyList(), emptyList()),
                ),
                scenes = emptyList(),
                narrativeSummary = null,
                conflictNotes = emptyList(),
                evidenceRefs = listOf(EvidenceRef(wirePage, wireBlock, hex64(sourceText))),
            )
            return AnalysisChunkRunOutcome.Completed(
                response = response,
                coverage = AnalysisCoverage(AnalysisCoverageKind.COMPLETE, emptyList()),
                droppedAuthorityKeys = emptyList(),
                provenance = AnalyzerProvenance("fake", "fake-model", 1, 1, "sig"),
            )
        }
    }

    private class FakeTranslator(
        private val responder: suspend (callIndex: Int, chunk: TranslationContextChunk) -> ContextualTranslationBatch,
    ) : AiTranslator() {
        val requests = mutableListOf<TranslationContextChunk>()
        private val inFlight = AtomicInteger()
        var maxObservedInFlight = 0
            private set

        override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch {
            requests += chunk
            val now = inFlight.incrementAndGet()
            if (now > maxObservedInFlight) maxObservedInFlight = now
            try {
                return responder(requests.size, chunk)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun promptText(prompt: String): String = ""
    }

    private fun responseFor(
        chunk: TranslationContextChunk,
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val results = request.orderedIds.map { id ->
            ContextualTranslationResult(
                id = id,
                targetKey = request.idMap[id],
                text = "translated-$id",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        }
        return ContextualRequestBuilder.toBatch(request, results)
    }

    private fun runCounters(store: ChapterTranslationStore): Pair<ChapterRunState, Map<String, Int>> {
        val artifact = artifactStore()
        val pointer = artifact.readManifest().shouldNotBeNull().activeRun.shouldNotBeNull()
        val record = (artifact.readRunRecord(pointer) as ChapterArtifactStore.RunRecordRead.Usable).record
        return record.state to record.phaseCounters
    }

    /**
     * Repeats `unit` until the planner's OWN estimator lands near
     * [targetTokens] ACTUAL tokens. Kana runs tokenize at roughly
     * 0.6-1.1 tokens per char in cl100k, so the rate is MEASURED on a
     * sample, never assumed.
     */
    private fun textOfApproxTokens(targetTokens: Int, unit: String = "あ"): String {
        fun estimate(n: Int) = TranslationContextChunkPlanner.estimateTokens(unit.repeat(n))
        val sampleUnits = 100
        val perUnit = (estimate(sampleUnits).toDouble() / sampleUnits).coerceAtLeast(0.05)
        var units = (targetTokens / perUnit).toInt().coerceAtLeast(sampleUnits)
        var tokens = estimate(units)
        var guard = 0
        while (tokens < targetTokens - 100 && guard++ < 32) {
            units += (((targetTokens - tokens) / perUnit).toInt().coerceAtLeast(1))
            tokens = estimate(units)
        }
        guard = 0
        while (tokens > targetTokens + 100 && units > sampleUnits && guard++ < 32) {
            units -= (((tokens - targetTokens) / perUnit).toInt().coerceAtLeast(1))
            tokens = estimate(units)
        }
        return unit.repeat(units)
    }

    // ------------------------------------------------------------------
    // Tests.
    // ------------------------------------------------------------------

    @Test
    fun `enriched prompt carries the frozen profile subset and the legacy counter stays zero`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }
        // The identity sheet comes from the one-shot synthesis (Director
        // redesign): one CHARACTER entry whose source form matches the page
        // text, so the subset matcher includes it in the sheet.
        val synthesizer = GlossarySynthesizer { _, _, _ ->
            GlossarySynthesisOutcome.Glossary(
                listOf(GlossaryEntry(GlossaryEntryKind.CHARACTER, "source", "Source")),
            )
        }

        val outcome = coordinator(store, pages, FakeAnalyzer(), translator, synthesizer = synthesizer)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        translator.requests.size shouldBe 1

        val chunk = translator.requests.single()
        // Glossary slot: synthesized fact id + matched source form + canonical
        // target + the identity/gender decision rules + the range fence. (The
        // frozen profile's fact ids are the synthesis-assigned `e001`/`t001`.)
        chunk.glossary shouldContain "[e001]"
        chunk.glossary shouldContain "source"
        chunk.glossary shouldContain "Source"
        chunk.glossary shouldContain "Resolve the referent first"
        chunk.glossary shouldContain "never a global replacement rule"
        // First envelope: gap-free frontier is empty, so the rolling slot is empty.
        chunk.rollingContext shouldBe ""

        // All pages committed; the pipeline outcome is unchanged.
        pageKeys.forEach { key ->
            store.snapshot(key).page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        }

        val (_, counters) = runCounters(store)
        counters["promptShapeEnriched"] shouldBe 1
        counters["promptShapeLegacy"] shouldBe 0
        counters["profileSubsetFactsMax"] shouldBe 1 // the one synthesized character fact
        counters["envelopeSplits"] shouldBe 0
        counters["pagesTranslated"] shouldBe 3
    }

    @Test
    fun `enriched rolling history advances gap-free across planned envelopes with the pronoun-marking rule`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..2).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        coordinator(store, pages, FakeAnalyzer(), translator, maxPagesPerEnvelope = 1)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        translator.requests.size shouldBe 2
        translator.requests[0].rollingContext shouldBe ""
        val rolling = translator.requests[1].rollingContext
        // The committed page-1 pair carried forward through the frontier...
        rolling shouldContain "Recent pairs"
        rolling shouldContain "translated-p0_b1"
        // ...with the pronoun-marking rule stated verbatim.
        rolling shouldContain "NOT canonical gender evidence"

        val (_, counters) = runCounters(store)
        counters["promptShapeEnriched"] shouldBe 2
        counters["rollingContextPagesMax"] shouldBe 1
    }

    @Test
    fun `execution-time recompute splits an oversized envelope at whole-page boundaries`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // ~4.2k ACTUAL tokens per page: plans well under the structural
        // ceilings (never a plan-time rejection) but two pages together
        // exceed the provider prompt window, forcing the whole-page split.
        val bigText = textOfApproxTokens(4_200)
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }
        // Substitute the big text: OCR pages must carry it so the wire lines
        // are actually oversized.
        val oversizedWorker = object : NativeLaneWorker by FakePreflightOcrWorker(store) {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? =
                this@ProfileEnvelopePromptEnrichmentTest.runFakeOcrWithText(store, pageKey, pageIndex, bigText)
        }

        val outcome = ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = oversizedWorker,
            frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
                sourceLang = "ja",
                targetLang = "en",
                ocrEngine = "FakeOcrEngine",
                inpaintMode = "OFF",
                providerKey = "fake:provider",
            ).copy(
                envelopePolicy = EnvelopePolicySnapshot(maxBlocks = 32, maxPages = 8),
            ),
            envelopePlannerPolicy = EnvelopePlannerPolicy(
                maxBlocksPerEnvelope = 32,
                maxContributingPages = 8,
                maxEstimatedInputTokens = 16_384,
                maxEstimatedOutputTokens = 8_192,
            ),
            orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
            analysisChunkRunner = FakeAnalyzer(),
            glossarySynthesizer = emptyGlossarySynthesizer,
            textTranslator = translator,
            translationSublimitGate = BatchRequestSublimitGate(),
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON

        // ONE planned envelope became THREE whole-page provider requests —
        // never a block split.
        translator.requests.size shouldBe 3
        translator.requests.forEach { request -> request.pages.size shouldBe 1 }
        translator.requests.forEach { request -> request.rollingContext shouldBe "" }
        translator.maxObservedInFlight shouldBe 1

        pageKeys.forEach { key ->
            store.snapshot(key).page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        }

        val (_, counters) = runCounters(store)
        // At least one execution-time whole-page split happened (the exact
        // count depends on how the planner grouped the oversized pages into
        // envelopes; every PROVIDER request is exactly one whole page).
        counters["envelopeSplits"].shouldNotBeNull()
        ((counters["envelopeSplits"] ?: 0) >= 1) shouldBe true
        counters["promptShapeEnriched"] shouldBe 3
        counters["pagesTranslated"] shouldBe 3
        counters["envelopeFailures"] shouldBe 0
    }

    private suspend fun runFakeOcrWithText(
        store: ChapterTranslationStore,
        pageKey: String,
        pageIndex: Int,
        text: String,
    ): OcrReadyPageRef {
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
            description = "t924 fake preflight ocr (oversized)",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot(pageKey)
        return OcrReadyPageRef(
            pageKey = pageKey,
            pageIndex = pageIndex,
            generation = after.generation,
            blockFingerprints = emptyList(),
            leaseToken = lease.token,
            candidateGenerationId = after.candidateGenerationId,
            dependencyFingerprint = after.dependencyFingerprint,
            artifactPageVersion = after.artifactPageVersion,
        )
    }

    @Test
    fun `single token-oversized page is rejected with a typed pause and zero provider calls`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // ~9k ACTUAL tokens: plans under the structural ceilings (never a
        // plan-time rejection) but exceeds the provider prompt window alone.
        val bigText = textOfApproxTokens(9_000)
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }
        val oversizedWorker = object : NativeLaneWorker by FakePreflightOcrWorker(store) {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? =
                this@ProfileEnvelopePromptEnrichmentTest.runFakeOcrWithText(store, pageKey, pageIndex, bigText)
        }

        val outcome = ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = oversizedWorker,
            frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
                sourceLang = "ja",
                targetLang = "en",
                ocrEngine = "FakeOcrEngine",
                inpaintMode = "OFF",
                providerKey = "fake:provider",
            ).copy(
                envelopePolicy = EnvelopePolicySnapshot(maxBlocks = 32, maxPages = 8),
            ),
            envelopePlannerPolicy = EnvelopePlannerPolicy(
                maxBlocksPerEnvelope = 32,
                maxContributingPages = 8,
                maxEstimatedInputTokens = 16_384,
                maxEstimatedOutputTokens = 8_192,
            ),
            orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
            analysisChunkRunner = FakeAnalyzer(),
            glossarySynthesizer = emptyGlossarySynthesizer,
            textTranslator = translator,
            translationSublimitGate = BatchRequestSublimitGate(),
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Page atomicity: the page is NEVER sent, NEVER partially translated.
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.reason shouldContain "token-oversized"
        translator.requests.size shouldBe 0
        store.snapshot("p1").page.shouldNotBeNull().translationStatus shouldBe StageStatus.PENDING

        val (_, counters) = runCounters(store)
        counters["envelopeFailures"] shouldBe 1
        counters["pagesTranslated"] shouldBe 0
        (counters["promptShapeEnriched"] ?: 0) shouldBe 0
        (counters["promptShapeLegacy"] ?: 0) shouldBe 0
    }

    @Test
    fun `without a frozen profile the executor keeps the legacy chunk shape`() = runTest {
        val store = lazyStore()
        val pageKey = "p1"
        store.preRegisterPages(listOf(pageKey))
        runFakeOcrWithText(store, pageKey, 0, "source-p1 dialogue")

        val captured = mutableListOf<TranslationContextChunk>()
        val translator = FakeTranslator { _, chunk ->
            captured += chunk
            responseFor(chunk)
        }

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
        val envelope = eu.kanade.translation.artifact.PlannedEnvelope(
            envelopeId = "e-0",
            orderedPageKeys = listOf(pageKey),
            blockIds = listOf("p0_b1"),
            contributingCorpusFingerprint = hex64("corpus"),
            estimatedInputTokens = 64,
            estimatedOutputTokens = 256,
            structuralBlockCount = 1,
            contributingPageCount = 1,
        )
        // The SC-10 plan fingerprint, computed exactly like the publication.
        val hashingView = eu.kanade.translation.artifact.EnvelopePlan(
            planFingerprint = "",
            planInputFingerprint = hex64("plan-input"),
            plannerVersion = 1,
            envelopes = listOf(envelope),
            createdAtEpochMs = 0L,
        )
        val canonical = ArtifactDocumentJson.encodeToString(
            eu.kanade.translation.artifact.EnvelopePlan.serializer(),
            hashingView,
        )
        val planFingerprint = StageFingerprints.envelopePlanContentFingerprint(canonical)
        val plan = eu.kanade.translation.artifact.EnvelopePlan(
            planFingerprint = planFingerprint,
            planInputFingerprint = hex64("plan-input"),
            plannerVersion = 1,
            envelopes = listOf(envelope),
            createdAtEpochMs = 1L,
        )

        // Durable TX-20 anchors: a frozen profile pointer + the envelope-plan
        // pointer, published through the STORE's own artifact store and
        // pushed into the store's manifest view, exactly as the coordinator
        // does in production.
        val artifact = store.artifactStore.shouldNotBeNull()
        if (artifact.readManifest() == null) {
            artifact.publishManifest(eu.kanade.translation.artifact.ChapterArtifactManifest(chapterKey = "Chapter 1"))
        }
        val draft = eu.kanade.translation.artifact.ChapterTranslationProfile(
            version = 1,
            contentFingerprint = "",
            profileInputFingerprint = hex64("fp04"),
            sourceRunId = "run-1",
            analyzerProvenance = eu.kanade.translation.artifact.AnalyzerProvenance("fake", "fake-model", 1, 1, "sig"),
            entities = listOf(
                eu.kanade.translation.artifact.ProfileFact(
                    factId = "f-1",
                    type = eu.kanade.translation.artifact.FactType.ENTITY_IDENTITY,
                    canonicalSourceForm = "カイル",
                    canonicalTargetForm = "Kyle",
                    evidenceStrength = eu.kanade.translation.artifact.EvidenceStrength.STRONG_CONTEXTUAL,
                    evidenceRefs = listOf(EvidenceRef("p1", "p1_b1", hex64("excerpt"))),
                    scope = eu.kanade.translation.artifact.FactScope.CANONICAL_CHAPTER_WIDE,
                    provenance = eu.kanade.translation.artifact.FactProvenance.CHAPTER_ANALYSIS,
                    conflictState = eu.kanade.translation.artifact.FactConflictState.RESOLVED,
                ),
            ),
            frozenAtEpochMs = 42L,
        )
        val profile = draft.copy(contentFingerprint = StageFingerprints.profileContentFingerprint(draft))
        val profileCommit = ProfileFreezePublication.publish(
            artifact,
            artifact.readManifest().shouldNotBeNull(),
            profile,
            7L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        store.artifactManifest = profileCommit.manifest
        val planCommit = EnvelopePlanPublication.publish(
            artifact,
            profileCommit.manifest,
            plan,
            7L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        store.artifactManifest = planCommit.manifest

        val executor = ProfileEnvelopeExecutor(
            store = store,
            textTranslator = translator,
            profileContentFingerprint = profile.contentFingerprint,
            frozenProfile = null, // unreadable sidecar simulation -> LEGACY shape
            replan = { ReplanResult.NothingPending },
            sublimitGate = BatchRequestSublimitGate(),
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
        outcome.shouldBeInstanceOf<ProfileEnvelopeExecutor.PhaseOutcome.Drained>()

        // Legacy shape: NO enriched sheet, and the slice-A rolling-context
        // path (empty frontier -> empty rolling context).
        captured.size shouldBe 1
        captured.single().glossary shouldBe ""
        captured.single().rollingContext shouldBe ""
        captured.single().estimatedPromptTokens shouldBe 64

        outcome.counters.promptShapeLegacy shouldBe 1
        outcome.counters.promptShapeEnriched shouldBe 0
        outcome.counters.envelopeSplits shouldBe 0
        outcome.counters.pagesTranslated shouldBe 1
        store.snapshot(pageKey).page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
    }
}
