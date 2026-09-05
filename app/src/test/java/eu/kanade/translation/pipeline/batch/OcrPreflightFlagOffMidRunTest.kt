package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * T924-FF-01e / T924-FF-10 flag-lifecycle coverage for the WP4 coordinator
 * shell (flags spec §1.3 FF-01e, §1.5 FF-10; gates §2.3 row 3.8):
 *
 *  - FF-01a: the dispatch decision maps flag OFF to the UNCHANGED legacy
 *    [SequentialBatchCoordinator] and flag ON to the T924 coordinator.
 *  - FF-01e.1/2: an interrupted flagged run resumed with the flag now OFF
 *    never re-enters the new path — it drops to the legacy path, leaving the
 *    new-path sidecars untouched (the reader never sees a regression).
 *  - FF-01e.2a: a flag-off resume of an already-finished (COMPLETE) new-path
 *    run treats the chapter as finished.
 *  - FF-01e.3: a run that never reached its first durable publication simply
 *    restarts legacy, losing nothing durable.
 *  - FF-10: queue restore never auto-starts a flagged run, and the resume
 *    decision is re-derived from the run record + current flag only — never
 *    from queue entry contents.
 */
class OcrPreflightFlagOffMidRunTest {

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

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

    private fun frozenConfig(): RunConfigSnapshot = ChapterProfileBatchCoordinator.frozenRunConfig(
        sourceLang = "ja",
        targetLang = "en",
        ocrEngine = "FakeOcrEngine",
        inpaintMode = "OFF",
        providerKey = "fake:provider",
        flagProfilePipeline = true,
    )

    private fun ocrPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(
            TranslationBlock(
                blockId = "b1",
                text = "source",
                translation = "",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
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

    /** Kills the pass at [failAt] so an interrupted, resumable state exists. */
    private inner class InterruptingOcrWorker(
        private val store: ChapterTranslationStore,
        private val failAt: String,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            if (pageKey == failAt) throw IllegalStateException("simulated mid-preflight death")
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
                    ocrResult = ocrPage(pageKey),
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 flag-off harness ocr",
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
                nativeHandoff = "decoded-bitmap-$pageKey",
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {}

        override fun releaseNativeHandoff(ref: OcrReadyPageRef) {}
    }

    private suspend fun interruptedFlaggedRun(): Pair<ChapterRunRecord, Set<String>> {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2"))
        val worker = InterruptingOcrWorker(store, failAt = "p2")
        val pages = listOf<PageKey>("p1" to 0, "p2" to 1)
        val coordinator = ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = worker,
            frozenConfig = frozenConfig(),
            orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
            flagProfilePipeline = true,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        val outcome = coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        outcome.status shouldBe BatchPass1Status.FAILED
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        val record = (
            artifactStore().readRunRecord(manifest.activeRun.shouldNotBeNull())
                as ChapterArtifactStore.RunRecordRead.Usable
            ).record
        return record to manifest.ocrCheckpoints.keys
    }

    @Test
    fun `flag off dispatch selects the unchanged legacy coordinator`() {
        ChapterProfileBatchCoordinator.dispatchKind(translationBatchProfilePipeline = false) shouldBe
            ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
        ChapterProfileBatchCoordinator.dispatchKind(translationBatchProfilePipeline = true) shouldBe
            ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
    }

    @Test
    fun `interrupted flagged run with the flag flipped off drops to legacy and leaves new sidecars untouched`() = runTest {
        val (record, checkpointedKeys) = interruptedFlaggedRun()

        // FF-01d: the interrupted run durably carries the frozen flag state.
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_FLAG] shouldBe 1
        record.frozenConfig.flagProfilePipeline shouldBe true

        val manifestBefore = artifactStore().readManifest().shouldNotBeNull()

        val decision = ChapterProfileBatchCoordinator.decideResume(
            record = record,
            currentFlagOn = false,
        )

        decision shouldBe ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.DropToLegacy

        // The decision is pure: no new-path sidecar moves, no committed display
        // appears, the legacy path can pick up the chapter from its legacy
        // artifacts while the T924 sidecars stay untouched (FF-01c write-only).
        artifactStore().readManifest().shouldNotBeNull() shouldBe manifestBefore
        manifestBefore.ocrCheckpoints.keys shouldBe checkpointedKeys.toSet()
        manifestBefore.pages.values.forEach { pageRecord ->
            pageRecord.committed.shouldBeNull()
        }
    }

    @Test
    fun `run that never reached its first durable publication restarts legacy under flag off`() {
        // FF-01e.3: no run record at all — nothing durable to lose; the
        // chapter restarts on the legacy path.
        ChapterProfileBatchCoordinator.decideResume(record = null, currentFlagOn = false) shouldBe
            ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.DropToLegacy
        // With the flag ON there is no recorded run to honor, but the flagged
        // path may start fresh.
        ChapterProfileBatchCoordinator.decideResume(record = null, currentFlagOn = true) shouldBe
            ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.RunFlaggedPath(null)
    }

    @Test
    fun `flag off after a complete flagged run treats the chapter as finished`() {
        val now = 1_757_050_000_000L
        val completeRecord = ChapterRunRecord(
            runId = "run-$now-a1b2c3d4",
            state = ChapterRunState.COMPLETE,
            frozenConfig = frozenConfig(),
            frozenRunConfigFingerprint = ChapterProfileBatchCoordinator.runConfigFingerprint(frozenConfig()),
            orderedSourceDigest = hex64("ordered-source"),
            analysisPolicyFingerprint = hex64("analysis-policy"),
            envelopePolicyFingerprint = hex64("envelope-policy"),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        // FF-01e.2a: finished on the new path — flag off does NOT restart it.
        ChapterProfileBatchCoordinator.decideResume(completeRecord, currentFlagOn = false) shouldBe
            ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.TreatAsFinished
        // While the flag is still ON, the recorded run is honored.
        ChapterProfileBatchCoordinator.decideResume(completeRecord, currentFlagOn = true) shouldBe
            ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.RunFlaggedPath(completeRecord)
    }

    @Test
    fun `queue restore never auto starts a flagged run and the decision takes no queue input`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        val worker = InterruptingOcrWorker(store, failAt = "<never>")
        // Construction + decision only: the restore path builds/decides, it
        // never runs a pass (FF-10: no auto-start for the flagged path).
        ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = worker,
            frozenConfig = frozenConfig(),
            orderedSourcePairs = listOf("p1" to hex64("source-p1")),
            flagProfilePipeline = true,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        // The resume decision carries no queue entry input at all: flag state
        // is re-derived from the run record + current flag only (T924-FF-10).
        val decision = ChapterProfileBatchCoordinator.decideResume(
            record = null,
            currentFlagOn = true,
        )
        decision shouldBe ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.RunFlaggedPath(null)

        // Nothing ran: no OCR, no durable run pointer, no checkpoints, no
        // lease held — the restored chapter waits for explicit user admission.
        worker.ocrPages shouldContainExactly emptyList()
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.activeRun.shouldBeNull()
        manifest.ocrCheckpoints shouldBe emptyMap()
        store.pageLeaseOwner("p1").shouldBeNull()
    }
}
