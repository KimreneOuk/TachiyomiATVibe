package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.translator.BatchRequestSublimitGate
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.engines.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.engines.translator.analysis.AnalysisCoverage
import eu.kanade.translation.engines.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.engines.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.engines.translator.analysis.AnalysisResponseValidator
import eu.kanade.translation.engines.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.engines.translator.analysis.ValidatedEntity
import eu.kanade.translation.engines.translator.analysis.ValidatedTerm
import eu.kanade.translation.engines.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.engines.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.providers.AiTranslator
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.EnvelopePolicySnapshot
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.OcrStagePatch
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.StagePatchResult
import eu.kanade.translation.persistence.chapter.ocrBlockFingerprints
import eu.kanade.translation.persistence.chapter.ocrFingerprint
import eu.kanade.translation.pipeline.batch.recovery.RecoveryWorker
import eu.kanade.translation.pipeline.batch.recovery.RecoveryWorkerContext
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 *  stranded-page fix: a page that is not envelope-done yet yields NO
 * dispatchable blocks used to be silently skipped EVERY planning round — the
 * dispatch-work build's `dispatchBlocks.isEmpty()` guard dropped it without a
 * trace, and FINALIZE then stamped a generic "translation left non-terminal"
 * stranded failure (device run: pages 002/206 of a 206-page chapter, two runs
 * in a row). Each deterministic class now gets an EXPLICIT terminal outcome:
 *  - a page whose text blocks all already carry valid translations (an earlier
 *    interrupted run wrote block-level translations the page-level commit
 *    never recorded) is adopted translation-READY — never re-paid, never
 *    stranded, never failed (the reader already draws those translations);
 *  - a page whose checkpoint adoption yielded no translatable text (block-less
 *    checkpoint) takes the finalizePostOcrStage textless terminal;
 *  - a page that cannot fit ANY legal envelope (block count over the policy
 *    cap) is durably FAILED_RETRYABLE with the planner's specific numbers.
 */
class StrandedPageTerminalRoutingTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (Stage7FinalizeCoordinatorTest idioms).
    // ------------------------------------------------------------------

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(blockId: String, text: String) = TranslationBlock(
        blockId = blockId,
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

    private fun ocrPage(
        pageKey: String,
        blocks: List<TranslationBlock>,
    ) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(*blocks.toTypedArray()),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = if (blocks.isEmpty()) {
            emptyList()
        } else {
            listOf(InpaintMaskBox(0, 0, 10, 10, 1))
        },
    )

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(chapterName: String = "Chapter 1"): ChapterTranslationStore {
        val artifactFileName = "$chapterName.json"
        return ChapterTranslationStore.lazy(
            artifactParentResolver = { root().createFile(artifactFileName)!! },
            artifactParent = root(),
            artifactFileName = artifactFileName,
        )
    }

    /**
     * Preflight OCR lane; `blocksFor` decides each page's OCR content so a
     * test can reproduce the store shapes a prior interrupted run left
     * behind (block translations without a page-level commit, or a
     * block-less checkpoint page).
     */
    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
        private val preTranslatedPagesWithKnownSourceSha: Set<String> = emptySet(),
        private val blocksFor: (String) -> List<TranslationBlock> = { pageKey ->
            listOf(block("b1", "source-$pageKey"))
        },
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrPages += pageKey
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrPage(pageKey, blocksFor(pageKey)),
                    expectedLeaseToken = lease.token,
                ),
                description = "t934 stranded-routing fake preflight ocr",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
            if (pageKey in preTranslatedPagesWithKnownSourceSha) {
                val merged = store.snapshot(pageKey)
                store.updatePageGuarded(
                    pageKey = pageKey,
                    expected = ChapterTranslationStore.PatchPrecondition(
                        generation = merged.generation,
                        pageVersion = merged.pageVersion,
                        leaseToken = lease.token,
                    ),
                    description = "fixture: interrupted translation carries observed source identity",
                ) { page ->
                    page!!.apply { sourceFingerprint = hex64("source-$pageKey") }
                }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            }
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

    /** Fake native inpaint lane through the scheduler's guarded identity path. */
    private inner class FakeOverlapInpaintLane(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
    ) : NativeLaneWorker {
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null

        override suspend fun runInpaintStage(pageKey: String) {
            runInpaintStage(pageKey, null)
        }

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            val identity = identities[pageKey]
                ?: error("overlap scheduler must register the write identity for $pageKey")
            store.updatePageGuarded(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = identity.generation,
                    pageVersion = identity.pageVersion,
                    leaseToken = identity.leaseToken,
                    candidateGenerationId = identity.candidateGenerationId,
                    dependencyFingerprint = identity.dependencyFingerprint,
                    artifactPageVersion = identity.artifactPageVersion,
                ),
                description = "t934 stranded-routing fake overlap inpaint",
            ) { page ->
                page!!.apply { inpaintStatus = StageStatus.READY }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
    }

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
                terms = listOf(ValidatedTerm("t001", "剣", "sword", emptyList(), "TERM")),
                entities = listOf(
                    ValidatedEntity("e001", listOf("カイル"), "カイル", "Kail", emptyList(), emptyList()),
                ),
                scenes = emptyList(),
                narrativeSummary = "An opening journey.",
                conflictNotes = emptyList(),
                evidenceRefs = listOf(EvidenceRef(wirePage, wireBlock, hex64(sourceText))),
            )
            return AnalysisChunkRunOutcome.Completed(
                response = response,
                coverage = AnalysisCoverage(AnalysisCoverageKind.COMPLETE, emptyList()),
                droppedAuthorityKeys = emptyList(),
                provenance = eu.kanade.translation.persistence.artifact.AnalyzerProvenance("fake", "fake-model", 1, 1, "sig"),
            )
        }
    }

    private class FakeTranslator(
        private val responder: suspend (callIndex: Int, chunk: TranslationContextChunk) -> ContextualTranslationBatch,
    ) : AiTranslator() {
        val requests = mutableListOf<TranslationContextChunk>()

        override val fromLang: TextRecognizerLanguage =
            TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch {
            requests += chunk
            return responder(requests.size, chunk)
        }

        override suspend fun promptText(prompt: String): String = ""
    }

    private fun responseFor(
        chunk: TranslationContextChunk,
        omit: Set<String> = emptySet(),
        translate: (TranslationBlock) -> String = { sourceBlock -> "translated-${sourceBlock.blockId}" },
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val results = request.orderedIds.filterNot(omit::contains).map { id ->
            val location = request.locations.getValue(id)
            val sourceBlock = chunk.pages.getValue(location.pageKey).blocks[location.blockIndex]
            ContextualTranslationResult(
                id = id,
                targetKey = request.idMap[id],
                text = translate(sourceBlock),
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        }
        return ContextualRequestBuilder.toBatch(request, results)
    }

    private fun runRecord(store: ChapterTranslationStore): eu.kanade.translation.persistence.artifact.ChapterRunRecord {
        val artifact = artifactStore()
        val pointer = artifact.readManifest().shouldNotBeNull().activeRun.shouldNotBeNull()
        return (artifact.readRunRecord(pointer) as ChapterArtifactEngine.RunRecordRead.Usable).record
    }

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: NativeLaneWorker,
        pages: List<PageKey>,
        translator: FakeTranslator,
        overlapScheduler: OverlapScheduler,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        ).copy(
            envelopePolicy = EnvelopePolicySnapshot(maxBlocks = 32, maxPages = 8),
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        freshSourceShaByPageKey = { pageKey -> hex64("source-$pageKey") },
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = FakeAnalyzer(),
        textTranslator = translator,
        translationSublimitGate = BatchRequestSublimitGate(),
        overlapScheduler = overlapScheduler,
        renderJoin = null,
    )

    private fun newScheduler(
        store: ChapterTranslationStore,
        pageKeys: List<String>,
    ): OverlapScheduler {
        // ONE shared identity map: the scheduler registers each page's write
        // identity and the fake lane reads it back (Stage7 test idiom).
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        return OverlapScheduler(
            store = store,
            nativeWorker = FakeOverlapInpaintLane(store, identities),
            orderedPageKeys = pageKeys,
            batchWriteIdentities = identities,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
    }

    // ------------------------------------------------------------------
    // Tests.
    // ------------------------------------------------------------------

    @Test
    fun `a page whose blocks all carry translations from an interrupted run is adopted READY, never stranded`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // p2 simulates the device deadlock: a prior run wrote the block-level
        // translation but the page-level commit never landed — the page is
        // translation-PENDING while its only block is no longer requestable.
        val worker = FakePreflightOcrWorker(
            store,
            blocksFor = { pageKey ->
                if (pageKey == "p2") {
                    listOf(block("b1", "OK!").apply { translation = "OK!" })
                } else {
                    listOf(block("b1", "source-$pageKey"))
                }
            },
            preTranslatedPagesWithKnownSourceSha = setOf("p2"),
        )
        val translator = FakeTranslator { _, chunk ->
            responseFor(chunk) { sourceBlock ->
                if (sourceBlock.text == "OK!") "OK!" else "translated-${sourceBlock.blockId}"
            }
        }

        val outcome = coordinator(store, worker, pages, translator, newScheduler(store, pageKeys))
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        val record = runRecord(store)
        record.state shouldBe ChapterRunState.COMPLETE

        // p1 commits through the envelope; p2 is ADOPTED translation-terminal:
        // READY (the reader already draws its block translation), never
        // FAILED, never charged a provider call, never stranded at FINALIZE.
        store.snapshot("p1").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        val p2 = store.snapshot("p2").page.shouldNotBeNull()
        p2.translationStatus shouldBe StageStatus.READY
        p2.blocks.single().translation shouldBe "OK!"
        store.durableFailure("p2").shouldBeNull()
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_STRANDED_RECONCILED] shouldBe 0

        // The Japanese→English scriptless invariant is adopted from the
        // interrupted checkpoint, so p2 is never sent to the translator.
        translator.requests.size shouldBe 1
        translator.requests.flatMap { chunk ->
            chunk.pages.values.flatMap { page -> page.blocks.map { it.text } }
        }.contains("OK!") shouldBe false
    }

    @Test
    fun `scriptless checkpoint equality stays unrequested and only sustained Japanese echo pauses the coordinator`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..17).map { "p$it" } // Three envelopes reach the existing zero-commit pause breaker.
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val worker = FakePreflightOcrWorker(store, blocksFor = {
            listOf(
                block("b1", "OK!").apply { translation = "OK!" },
                block("b2", "待て！").apply { translation = "待て！" },
            )
        })
        val translator = FakeTranslator { _, chunk ->
            responseFor(chunk) { sourceBlock -> sourceBlock.text }
        }

        val outcome = coordinator(store, worker, pages, translator, newScheduler(store, pageKeys))
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        val durableRun = runRecord(store)
        durableRun.frozenConfig.sourceLang shouldBe "ja"
        durableRun.frozenConfig.targetLang shouldBe "en"
        val requestedSourceTexts = translator.requests.flatMap { chunk ->
            chunk.pages.values.flatMap { page -> page.blocks.map { it.text } }
        }
        requestedSourceTexts.isNotEmpty() shouldBe true
        requestedSourceTexts.contains("OK!") shouldBe false
        requestedSourceTexts.all { it == "待て！" } shouldBe true
        (requestedSourceTexts.count { it == "待て！" } >= pageKeys.size) shouldBe true
        // This is the TRANSLATE pause path. EnvelopeDispatcher does not call
        // FINALIZE after this outcome, so the dedicated finalizer witness
        // below owns the stranded-reason assertion.
        pageKeys.all { store.snapshot(it).page.shouldNotBeNull().translationStatus != StageStatus.READY } shouldBe true
        outcome.status shouldBe BatchPass1Status.PAUSED
        // One zero-commit envelope drains normally; the retryable durable failure
        // keeps the meaningful Japanese echo eligible for a later request.
        val singleStore = lazyStore(chapterName = "Chapter 2")
        val singlePageKeys = listOf("p1")
        val singlePages: List<PageKey> = listOf("p1" to 0)
        singleStore.preRegisterPages(singlePageKeys)
        val singleWorker = FakePreflightOcrWorker(singleStore) {
            listOf(
                block("b1", "OK!").apply { translation = "OK!" },
                block("b2", "待て！").apply { translation = "待て！" },
            )
        }
        val singleTranslator = FakeTranslator { _, chunk ->
            responseFor(chunk) { sourceBlock -> sourceBlock.text }
        }
        val singleOutcome = coordinator(
            singleStore,
            singleWorker,
            singlePages,
            singleTranslator,
            newScheduler(singleStore, singlePageKeys),
        ).runPass1(singlePages, TranslatorComputeClass.REMOTE_IO)

        singleOutcome.status shouldBe BatchPass1Status.COMPLETED
        singleOutcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        singleTranslator.requests.size shouldBe 2
        val singleRequestedSourceTexts = singleTranslator.requests.flatMap { chunk ->
            chunk.pages.values.flatMap { page -> page.blocks.map { it.text } }
        }
        singleRequestedSourceTexts.isNotEmpty() shouldBe true
        singleRequestedSourceTexts.all { it == "待て！" } shouldBe true
        singleStore.snapshot("p1").page.shouldNotBeNull().translationStatus shouldNotBe StageStatus.READY
        singleStore.durableFailure("p1").shouldNotBeNull().status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
    }

    @Test
    fun `FINALIZE recovery counts only the meaningful Japanese echo as unfinished`() = runTest {
        val store = ChapterTranslationStore.openArtifactSuspend(root(), "Chapter 1.json")
        val pageKeys: List<PageKey> = listOf("p1" to 0)
        store.preRegisterPages(pageKeys.map { it.first })

        val blocks = listOf(
            block("b1", "OK!").apply { translation = "OK!" },
            block("b2", "待て！").apply { translation = "待て！" },
        )
        val lease = store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot("p1")
        store.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage("p1", blocks),
                expectedLeaseToken = lease.token,
            ),
            description = "t935 finalize language-aware recovery witness seed",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        store.releasePageStageLease("p1", PageWriteOrigin.BATCH)

        val seeded = store.snapshot("p1").page.shouldNotBeNull()
        seeded.translationStatus shouldBe StageStatus.PENDING
        seeded.blocks.map { it.text to it.translation } shouldBe listOf(
            "OK!" to "OK!",
            "待て！" to "待て！",
        )

        val frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        )
        val nowEpochMs = System.currentTimeMillis() + 1L
        val recovery = RecoveryWorker(
            RecoveryWorkerContext(
                store = store,
                nowEpochMs = { nowEpochMs },
                drainFinalize = { _, _, _, _, _ ->
                    error("the finalizer witness does not enter recovery resume")
                },
            ),
        )
        val artifact = store.withArtifactEngineLocked { it }.shouldNotBeNull()
        val reasonCalls = mutableListOf<String>()
        val finalizer = FinalizeWorker(
            FinalizeWorkerContext(
                store = store,
                frozenConfig = frozenConfig,
                freshSourcePairsForPages = { pages ->
                    pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") }
                },
                overlapScheduler = null,
                renderJoin = null,
                publishRecord = { artifact, record ->
                    val serialized = ArtifactDocumentJson.encodeToString(record)
                    val manifest = artifact.readManifest().shouldNotBeNull()
                    artifact.publishActiveRun(
                        manifest = manifest,
                        record = record,
                        contentFingerprint = ChapterProfileBatchCoordinator.sha256Hex(serialized.encodeToByteArray()),
                        nowEpochMs = nowEpochMs,
                    ).also { outcome ->
                        if (outcome is ChapterArtifactEngine.TransactionOutcome.Committed) {
                            store.artifactManifest = outcome.manifest
                        }
                    }
                },
                record = { runId, state, frozenFingerprint, sourceDigest, counters, corpusFingerprint ->
                    ChapterRunRecord(
                        runId = runId,
                        state = state,
                        frozenConfig = frozenConfig,
                        frozenRunConfigFingerprint = frozenFingerprint,
                        orderedSourceDigest = sourceDigest,
                        ocrCorpusFingerprint = corpusFingerprint,
                        analysisPolicyFingerprint = ChapterProfileBatchCoordinator.policyFingerprint(
                            "analysis-policy-v1",
                            frozenConfig.analysisPolicy.overlapPages,
                        ),
                        envelopePolicyFingerprint = ChapterProfileBatchCoordinator.policyFingerprint(
                            "envelope-policy-v1",
                            frozenConfig.envelopePolicy.maxBlocks,
                            frozenConfig.envelopePolicy.maxPages,
                        ),
                        phaseCounters = counters,
                        createdAtEpochMs = nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    )
                },
                drainDisplayTailBeforeComplete = {
                    RecoveryWorker.DisplayTailDrain(drained = 0, failed = emptyList())
                },
                t924PageTerminalAtFinalize = { _, _ -> false },
                strandedPageReason = { page ->
                    val persistedRun = store.readActiveRunRecord().shouldNotBeNull()
                    persistedRun.state shouldBe ChapterRunState.FINALIZE
                    val persistedConfig = persistedRun.frozenConfig
                    persistedConfig.sourceLang shouldBe "ja"
                    persistedConfig.targetLang shouldBe "en"
                    recovery.strandedPageReason(page).also { reasonCalls += it }
                },
                persistEnvelopeStructuralFailure = { pageKey, reason, carrier ->
                    recovery.persistEnvelopeStructuralFailure(pageKey, reason, carrier)
                },
            ),
        )

        val outcome = finalizer.runPhase(
            artifact = artifact,
            runId = "run-language-aware-finalize",
            orderedPages = pageKeys,
            corpusFingerprint = hex64("corpus-p1"),
            baseCounters = emptyMap(),
        )

        outcome.status shouldBe BatchPass1Status.COMPLETED
        reasonCalls.size shouldBe 1
        reasonCalls.single() shouldContain "unfinished=1"
        val durableFailure = store.durableFailure("p1").shouldNotBeNull()
        durableFailure.lastFailureMessage shouldContain "unfinished=1"
        store.readActiveRunRecord()?.state shouldBe ChapterRunState.COMPLETE
    }

    @Test
    fun `an adopted block-less checkpoint page takes the textless terminal, never stranded`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // p2's checkpoint carries NO text (textless page adopted from the
        // artifact store at resume): the planning round used to skip it
        // silently and FINALIZE used to strand it as PENDING.
        val worker = FakePreflightOcrWorker(store, blocksFor = { pageKey ->
            if (pageKey == "p2") emptyList() else listOf(block("b1", "source-$pageKey"))
        })
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val outcome = coordinator(store, worker, pages, translator, newScheduler(store, pageKeys))
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        val record = runRecord(store)
        record.state shouldBe ChapterRunState.COMPLETE

        store.snapshot("p1").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        val p2 = store.snapshot("p2").page.shouldNotBeNull()
        p2.translationStatus shouldBe StageStatus.SKIPPED
        p2.renderStatus shouldBe StageStatus.SKIPPED
        p2.inpaintStatus shouldBe StageStatus.SKIPPED
        p2.isTextlessTerminal shouldBe true
        store.durableFailure("p2").shouldBeNull()
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_STRANDED_RECONCILED] shouldBe 0
    }

    @Test
    fun `a page over the envelope block cap fails with the planner's specific numbers, not a generic error`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // p2 has 33 text blocks — one over the 32-block policy cap. Page
        // atomicity means it can never fit any legal envelope; the plan is
        // rejected whole and the page takes a durable, SPECIFIC failure.
        val worker = FakePreflightOcrWorker(store, blocksFor = { pageKey ->
            if (pageKey == "p2") {
                (1..33).map { index -> block("b$index", "source-p2-b$index") }
            } else {
                listOf(block("b1", "source-$pageKey"))
            }
        })
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val outcome = coordinator(store, worker, pages, translator, newScheduler(store, pageKeys))
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.reason shouldContain "page p2 oversized: 33 blocks > 32"

        val p2 = store.snapshot("p2").page.shouldNotBeNull()
        p2.translationStatus shouldBe StageStatus.FAILED
        val parked = store.durableFailure("p2").shouldNotBeNull()
        parked.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        parked.lastFailureMessage shouldContain "envelope planner rejected the page"
        parked.lastFailureMessage shouldContain "page p2 oversized: 33 blocks > 32"

        // The healthy page is NOT collateral damage: still pending, never
        // silently failed by the oversized page's rejection.
        store.snapshot("p1").page.shouldNotBeNull().translationStatus shouldBe StageStatus.PENDING
        store.durableFailure("p1").shouldBeNull()
        translator.requests.isEmpty() shouldBe true
    }
}
