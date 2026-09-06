package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.SidecarRead
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisCoverage
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.analysis.ValidatedEntity
import eu.kanade.translation.translator.analysis.ValidatedTerm
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * T924 Stage 5 slice B: coordinator freeze behavior (T924-ST-09/10 +
 * T924-TX-22 + the ST-05/OCR_PLAN skip rule). Pins:
 *
 *  - a full pass reconciles the durable chunks, freezes the profile in one
 *    transaction and ends PAUSED (never COMPLETED);
 *  - resume after freeze SKIPS the entire run through analysis: zero re-OCR,
 *    zero chunk executions (the T924 fast-feedback core);
 *  - an FP-04 input change (target language) invalidates reuse and re-freezes
 *    the NEXT version without re-OCR or re-sent chunks;
 *  - a pointer whose sidecar turned corrupt is treated as UNFROZEN and the
 *    resume heals it by re-freezing (idempotent content-addressed name).
 */
class ChapterProfileFreezeCoordinatorTest {

    @TempDir
    lateinit var mangaDir: File

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

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: FakePreflightOcrWorker,
        pages: List<PageKey>,
        runner: AnalysisChunkRunner?,
        targetLang: String = "en",
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = targetLang,
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
            flagProfilePipeline = true,
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        flagProfilePipeline = true,
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = runner,
    )

    /** M1-idiom OCR lane: lease, merge under the token, hand the identity back. */
    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrPages += pageKey
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            val resultPage = ocrPage(pageKey, "source-$pageKey")
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = resultPage,
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

    /** Typed fake analyzer: one entity + one term per chunk, COMPLETE. */
    private inner class FakeAnalyzer : AnalysisChunkRunner {
        val executedOrdinals = mutableListOf<Int>()

        override suspend fun executeChunk(
            chunk: PlannedAnalysisChunk,
            identity: AnalysisRunIdentity,
            evidence: AnalysisEvidenceTexts,
        ): AnalysisChunkRunOutcome {
            executedOrdinals += chunk.chunkOrdinal
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
                provenance = AnalyzerProvenance(
                    providerId = "fake",
                    modelId = "fake-model",
                    promptVersion = 1,
                    analysisSchemaVersion = 1,
                    credentialFingerprint = "sig",
                ),
            )
        }
    }

    private fun runRecord(store: ChapterTranslationStore) =
        artifactStore().let { artifact ->
            val pointer = artifact.readManifest().shouldNotBeNull().activeRun.shouldNotBeNull()
            (artifact.readRunRecord(pointer) as ChapterArtifactStore.RunRecordRead.Usable).record
        }

    private fun readProfile(): Pair<ChapterArtifactStore, ChapterTranslationProfile> {
        val artifact = artifactStore()
        val manifest = artifact.readManifest().shouldNotBeNull()
        val pointer = manifest.profile.shouldNotBeNull()
        val profile = when (
            val read = artifact.readSidecarDocument(
                pointer = pointer.toSidecarPointer(),
                serializer = ChapterTranslationProfile.serializer(),
                currentSchemaVersion = ChapterTranslationProfile.SCHEMA_VERSION,
                expectedKind = ChapterTranslationProfile.KIND,
                schemaVersionOf = { it.schemaVersion },
                kindOf = { it.kind },
                isValid = { it.isSemanticallyValid },
            )
        ) {
            is SidecarRead.Usable<*> -> read.document as ChapterTranslationProfile
            else -> throw IllegalStateException("profile sidecar unreadable: $read")
        }
        return artifact to profile
    }

    private fun profileSidecarFile(): File {
        val pointer = artifactStore().readManifest().shouldNotBeNull().profile.shouldNotBeNull()
        return File(mangaDir, pointer.fileName)
    }

    @Test
    fun `full pass freezes the profile and pauses`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val worker = FakePreflightOcrWorker(store)
        val analyzer = FakeAnalyzer()
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

        val outcome = coordinator(store, worker, pages, analyzer)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Stage-6 slice A: freeze now CONTINUES into the envelope phase;
        // without a wired text translator the run pauses at the typed
        // TRANSLATE CONFIGURATION gate — never COMPLETED.
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON
        outcome.needsTranslation shouldBe emptyList()
        worker.ocrPages shouldContainExactly pageKeys
        analyzer.executedOrdinals shouldContainExactly listOf(0)

        // The frozen profile carries the reconciled canon with provenance.
        val (artifact, profile) = readProfile()
        profile.version shouldBe 1
        profile.validationError().shouldBeNull()
        profile.entities.single().canonicalTargetForm shouldBe "Kail"
        profile.terms.single().canonicalTargetForm shouldBe "sword"
        profile.seriesUpdateCandidates shouldBe emptyList()
        profile.correctionCandidates shouldBe emptyList()
        profile.analyzerProvenance.providerId shouldBe "fake"
        // The pointer identity matches the DTO and the record carries it.
        val manifest = artifact.readManifest().shouldNotBeNull()
        val pointer = manifest.profile.shouldNotBeNull()
        pointer.profileInputFingerprint shouldBe profile.profileInputFingerprint
        pointer.contentFingerprint shouldBe profile.contentFingerprint

        val record = runRecord(store)
        record.state shouldBe ChapterRunState.TRANSLATE
        record.profilePointer.shouldNotBeNull() shouldBe pointer
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_RECONCILED] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_PENDING] shouldBe 0
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_TRANSPORT] shouldBe 1
        // The envelope plan was published (SC-20) and its pointer is durable.
        manifest.envelopePlan.shouldNotBeNull()
    }

    @Test
    fun `resume after freeze skips the entire run through analysis`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val firstAnalyzer = FakeAnalyzer()
        coordinator(store, FakePreflightOcrWorker(store), pages, firstAnalyzer)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        val frozenRecord = runRecord(store)
        val frozenPointer = artifactStore().readManifest().shouldNotBeNull().profile.shouldNotBeNull()

        // Simulated process death: fresh stores over the SAME documents.
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val resumedAnalyzer = FakeAnalyzer()

        val resumed = coordinator(resumedStore, resumedWorker, pages, resumedAnalyzer)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // ST-05 skip rule: compatible frozen profile — zero OCR, zero chunk
        // executions, and the SAME frozen pointer is reused untouched.
        resumedWorker.ocrPages shouldBe emptyList()
        resumedAnalyzer.executedOrdinals shouldBe emptyList()
        resumed.status shouldBe BatchPass1Status.PAUSED
        // Stage-6 slice A: the reuse path CONTINUES into the envelope phase
        // (zero re-OCR, zero re-analysis still hold) and pauses at TRANSLATE.
        resumed.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON

        val record = runRecord(resumedStore)
        record.runId shouldBe frozenRecord.runId
        record.state shouldBe ChapterRunState.TRANSLATE
        record.profilePointer.shouldNotBeNull() shouldBe frozenPointer
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_REUSED] shouldBe 1
        // The reuse run did NOT re-freeze: no profileFrozen counter.
        record.phaseCounters.containsKey(ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN) shouldBe false
    }

    @Test
    fun `fp04 input change invalidates reuse and re-freezes the next version`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        coordinator(store, FakePreflightOcrWorker(store), pages, FakeAnalyzer())
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        val v1Pointer = artifactStore().readManifest().shouldNotBeNull().profile.shouldNotBeNull()
        val v1Profile = readProfile().second

        // Resume with a changed TARGET language: a new frozen config (new run
        // id), a new FP-04 input fingerprint, and therefore NO reuse.
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val resumedAnalyzer = FakeAnalyzer()
        val resumed = coordinator(resumedStore, resumedWorker, pages, resumedAnalyzer, targetLang = "es")
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Checkpoints are still reused (ST-06 identity — no re-OCR) and the
        // persisted chunk prefix is still valid (chunks are language
        // independent evidence) — but the profile RE-FREEZES under the new
        // input identity as version 2.
        resumedWorker.ocrPages shouldBe emptyList()
        resumedAnalyzer.executedOrdinals shouldBe emptyList()
        resumed.status shouldBe BatchPass1Status.PAUSED
        resumed.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON

        val v2Pointer = artifactStore().readManifest().shouldNotBeNull().profile.shouldNotBeNull()
        v2Pointer.version shouldBe 2
        v2Pointer.profileInputFingerprint shouldNotBe v1Pointer.profileInputFingerprint
        val record = runRecord(resumedStore)
        record.state shouldBe ChapterRunState.TRANSLATE
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN] shouldBe 1
        record.phaseCounters.containsKey(ChapterProfileBatchCoordinator.COUNTER_PROFILE_REUSED) shouldBe false
        // The v1 file is untouched; v2 content differs (input fingerprint is
        // a hashed field of the DTO).
        val v2Profile = readProfile().second
        v2Profile.contentFingerprint shouldNotBe v1Profile.contentFingerprint
    }

    @Test
    fun `corrupt frozen sidecar is unfrozen and the resume heals it by re-freeze`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        coordinator(store, FakePreflightOcrWorker(store), pages, FakeAnalyzer())
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        val sidecar = profileSidecarFile()
        sidecar.exists() shouldBe true

        // Corrupt the frozen profile bytes (crash/torn write simulation).
        sidecar.writeBytes("{corrupt".encodeToByteArray())

        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val resumedAnalyzer = FakeAnalyzer()
        val resumed = coordinator(resumedStore, resumedWorker, pages, resumedAnalyzer)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // ST-30: unreadable target = absent, never partially trusted — so the
        // run re-enters the normal path (checkpoints + persisted chunks are
        // still reusable: zero re-OCR, zero chunk executions) and RE-FREEZES.
        resumedWorker.ocrPages shouldBe emptyList()
        resumedAnalyzer.executedOrdinals shouldBe emptyList()
        resumed.status shouldBe BatchPass1Status.PAUSED
        resumed.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON

        val (_, profile) = readProfile()
        profile.version shouldBe 2
        // The content-addressed name is content-derived: the byte-identical
        // re-publication rewrote the SAME file with valid bytes (ST-10 (b)).
        val pointer = artifactStore().readManifest().shouldNotBeNull().profile.shouldNotBeNull()
        val healed = File(mangaDir, pointer.fileName)
        healed.exists() shouldBe true
        (healed.length() > 0L) shouldBe true
    }
}
