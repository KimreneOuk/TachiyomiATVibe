package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.StagePatchResult
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.pipeline.ocrBlockFingerprints
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
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
 *  zero-legacy  — surviving coverage from the former
 * `OcrPreflightFlagOffMidRunTest`.
 *
 * The  flag completed its A/B lifecycle and was removed: there is no
 * flag-OFF state, no DropToLegacy/TreatAsFinished decision tree, and no
 * legacy SequentialBatchCoordinator — those tests pinned deleted behavior
 * and were deleted with it. What survives here:
 *
 *  - the dispatch mapping anchor: engine category alone picks the lane
 *    (STANDARD → STANDARD_PIPELINE; everything else → PROFILE_PIPELINE);
 *  - the  queue-restore obligation, which was ALWAYS orthogonal to the
 *    flag: constructing the coordinator (the restore-level lookup) never
 *    auto-starts a run — no OCR, no durable run pointer, no checkpoints, no
 *    lease held. The restored chapter waits for explicit user admission.
 */
class OcrPreflightQueueRestoreTest {

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
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

    /** Recording OCR worker — every page it touches is visible to the oracles. */
    private inner class RecordingOcrWorker(
        private val store: ChapterTranslationStore,
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
                    ocrResult = ocrPage(pageKey),
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 queue-restore harness ocr",
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

    @Test
    fun `dispatch maps the engine category to the two surviving lanes`() {
        // STANDARD → STANDARD_PIPELINE; everything else (AI_MODEL, contextual
        // or not) → PROFILE_PIPELINE. No flag, no legacy escape hatch.
        ChapterProfileBatchCoordinator.dispatchKind(engineCategoryIsStandard = true) shouldBe
            ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE
        ChapterProfileBatchCoordinator.dispatchKind(engineCategoryIsStandard = false) shouldBe
            ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
    }

    @Test
    fun `queue restore never auto starts a run - construction alone runs nothing`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        val worker = RecordingOcrWorker(store)
        // Construction + decision only: the restore path builds the
        // coordinator, it never runs a pass ( no auto-start).
        ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = worker,
            frozenConfig = frozenConfig(),
            orderedSourcePairs = listOf("p1" to hex64("source-p1")),
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )

        // Nothing ran: no OCR, no durable run pointer, no checkpoints, no
        // lease held — the restored chapter waits for explicit user admission.
        worker.ocrPages shouldContainExactly emptyList()
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.activeRun.shouldBeNull()
        manifest.ocrCheckpoints shouldBe emptyMap()
        store.pageLeaseOwner("p1").shouldBeNull()
    }
}
