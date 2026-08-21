package eu.kanade.translation

import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.isTextlessTerminal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChapterTranslationStorePhase3Test {

    private fun block(translation: String = "target") = TranslationBlock(
        text = "source",
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun readyPage(cleanedImageName: String = "old.jpg") = PageTranslation(
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = cleanedImageName,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun store() = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = mapOf("p1" to readyPage()),
    )

    @Test
    fun `candidate mutations stay out of reader display until promotion shape is ready`() = runTest {
        val store = store()

        store.updatePage("p1") { page ->
            page!!.copy(
                cleanedImageName = null,
                translationStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            )
        }

        store.state.value.getValue("p1").cleanedImageName shouldBe null
        store.display.value.getValue("p1").cleanedImageName shouldBe "old.jpg"

        store.updatePage("p1") { page ->
            page!!.copy(
                blocks = mutableListOf(block("new-target")),
                cleanedImageName = "new.jpg",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                renderStatus = StageStatus.READY,
            )
        }

        store.display.value.getValue("p1").cleanedImageName shouldBe "new.jpg"
        store.display.value.getValue("p1").blocks.single().translation shouldBe "new-target"
        store.committedDisplayPage("p1")?.cleanedImageName shouldBe "new.jpg"
        store.drainRetiredCleanedImage("p1") shouldBe "old.jpg"
    }

    @Test
    fun `rapid committed promotions retain every superseded cleaned file until drain`() = runTest {
        val store = store()

        store.updatePage("p1") { readyPage("middle.jpg") }
        store.updatePage("p1") { readyPage("latest.jpg") }

        store.mayDeleteCleanedImage("p1", "old.jpg") shouldBe false
        store.mayDeleteCleanedImage("p1", "middle.jpg") shouldBe false
        store.drainRetiredCleanedImages("p1").toSet() shouldBe setOf("old.jpg", "middle.jpg")
        store.mayDeleteCleanedImage("p1", "old.jpg") shouldBe true
        store.mayDeleteCleanedImage("p1", "middle.jpg") shouldBe true
    }

    @Test
    fun `reader and batch leases serialize ownership and fence late writes`() = runTest {
        val store = store()
        val concurrent = coroutineScope {
            listOf(
                async { store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH) },
                async { store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.READER_ADHOC) },
            ).awaitAll()
        }
        concurrent.count { it is LeaseAcquisition.Granted } shouldBe 1
        concurrent.count { it is LeaseAcquisition.Denied } shouldBe 1
        val granted = concurrent.filterIsInstance<LeaseAcquisition.Granted>().single().lease
        val sameOwner = store.tryAcquirePageStageLease(
            "p1",
            PageStage.Inpaint,
            granted.origin,
        ).shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        sameOwner.token shouldBe granted.token

        val beforeRelease = store.snapshot("p1")
        store.releasePageStageLease("p1", granted.origin)
        val late = store.patchPage(
            "p1",
            ChapterTranslationStore.PatchPrecondition(
                generation = beforeRelease.generation,
                pageVersion = beforeRelease.pageVersion,
                blockFingerprints = beforeRelease.blockFingerprints,
                leaseToken = beforeRelease.leaseToken,
            ),
            "late lease write",
        ) { page -> page!!.apply { errorMessage = "late" } }
        late.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        store.snapshot("p1").page?.errorMessage shouldBe null

        val reacquired = store.tryAcquirePageStageLease(
            "p1",
            PageStage.Ocr,
            granted.origin,
        )
        reacquired.shouldBeInstanceOf<LeaseAcquisition.Granted>()
    }

    @Test
    fun `ocr result with a released lease is rejected through the stage merge`() = runTest {
        val store = store()
        val lease = store.tryAcquirePageStageLease(
            "p1",
            PageStage.Ocr,
            PageWriteOrigin.BATCH,
        ).shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot("p1")
        store.releasePageStageLease("p1", PageWriteOrigin.BATCH)

        val result = store.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = readyPage("late.jpg"),
                expectedLeaseToken = lease.token,
            ),
        )
        result.shouldBeInstanceOf<StagePatchResult.Rejected>()
        store.snapshot("p1").page?.cleanedImageName shouldBe "old.jpg"
    }

    @Test
    fun `aborting batch work releases only the matching page lease`() = runTest {
        val store = store()
        store.tryAcquirePageStageLease(
            "p1",
            PageStage.Ocr,
            PageWriteOrigin.BATCH,
        ).shouldBeInstanceOf<LeaseAcquisition.Granted>()

        store.cancelPageStageWork("p1", PageWriteOrigin.READER_ADHOC) shouldBe false
        store.pageLeaseOwner("p1") shouldBe PageWriteOrigin.BATCH

        store.cancelPageStageWork("p1", PageWriteOrigin.BATCH) shouldBe true
        store.pageLeaseOwner("p1") shouldBe null
    }

    @Test
    fun `ocr-only merge preserves reusable cleaned artifact`() = runTest {
        val store = store()
        store.updatePage("p1") { page ->
            page!!.apply {
                sourceFingerprint = "source-v1"
                detectionFingerprint = "detector-v1"
                ocrFingerprint = "ocr-v1"
                inpaintFingerprint = "inpaint-v1"
            }
        }
        val lease = store.tryAcquirePageStageLease(
            "p1",
            PageStage.Ocr,
            PageWriteOrigin.BATCH,
        ).shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot("p1")

        val result = store.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = readyPage().copy(
                    cleanedImageName = null,
                    sourceFingerprint = "source-v1",
                    inpaintStatus = StageStatus.PENDING,
                    detectionFingerprint = "detector-v1",
                    ocrFingerprint = "ocr-v2",
                ),
                expectedLeaseToken = lease.token,
            ),
        )

        result.shouldBeInstanceOf<StagePatchResult.Accepted>()
        val merged = store.snapshot("p1").page!!
        merged.cleanedImageName shouldBe "old.jpg"
        merged.inpaintStatus shouldBe StageStatus.READY
        merged.inpaintRevision shouldBe PageTranslation.CURRENT_INPAINT_REVISION
        merged.inpaintFingerprint shouldBe "inpaint-v1"
    }

    @Test
    fun `cancelled transient candidate cannot hide committed display`() = runTest {
        val store = store()
        store.updatePage("p1") { page ->
            page!!.copy(
                cleanedImageName = null,
                ocrStatus = StageStatus.RUNNING,
                translationStatus = StageStatus.PENDING,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            )
        }

        store.clearTransientQueuePages("user cancelled")

        store.state.value.getValue("p1").ocrStatus shouldBe StageStatus.CANCELLED
        store.display.value.getValue("p1").cleanedImageName shouldBe "old.jpg"
        store.committedDisplayPage("p1")?.cleanedImageName shouldBe "old.jpg"
    }

    @Test
    fun `textless terminal promotion replaces the previous committed display`() = runTest {
        val store = store()

        store.updatePage("p1") {
            PageTranslation(
                sourceFileName = "p1",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.SKIPPED,
                inpaintStatus = StageStatus.SKIPPED,
                renderStatus = StageStatus.SKIPPED,
            )
        }

        store.display.value.getValue("p1").isTextlessTerminal shouldBe true
        store.committedDisplayPage("p1")?.cleanedImageName shouldBe null
        store.drainRetiredCleanedImage("p1") shouldBe "old.jpg"
    }
}
