package eu.kanade.translation.model

import eu.kanade.tachiyomi.ui.reader.viewer.selectReaderTranslationOverlayBinding
import eu.kanade.translation.persistence.artifact.ArtifactOrigin
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.CommittedBundleMetadata
import eu.kanade.translation.persistence.artifact.DisplayBaseKind
import eu.kanade.translation.persistence.artifact.DisplayBaseReference
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.StageArtifactRecord
import eu.kanade.translation.pipeline.batch.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.markOriginalImageFallback
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageDisplayProjectionTest {

    @Test
    fun `ocr and translated text without cleaned display base is not ready`() {
        val page = readyPage().copy(
            cleanedImageName = null,
            inpaintStatus = StageStatus.PENDING,
            renderStatus = StageStatus.READY,
        )

        page.toPageDisplayProjection().displayReady shouldBe false
        page.toPageDisplayProjection().state shouldBe PageDisplayState.ORIGINAL_ONLY
    }

    @Test
    fun `candidate refresh keeps committed display and exposes refresh state`() {
        val committed = readyPage().copy(
            pageVersion = 3,
            translationOrigin = ArtifactOrigin.BATCH.name,
        )
        val candidate = committed.copy(
            pageVersion = 4,
            cleanedImageName = null,
            inpaintStatus = StageStatus.PENDING,
            translationStatus = StageStatus.RUNNING,
            renderStatus = StageStatus.PENDING,
        )

        val projection = candidate.toPageDisplayProjection(committed)

        projection.state shouldBe PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
        projection.displayReady shouldBe true
        projection.processed shouldBe false
    }

    @Test
    fun `failed candidate keeps committed display but no-result failure does not`() {
        val committed = readyPage().copy(pageVersion = 10)
        val failedWithCommitted = committed.copy(
            pageVersion = 11,
            translationStatus = StageStatus.FAILED,
            cleanedImageName = null,
        )
        val failedWithoutCommitted = PageTranslation(ocrStatus = StageStatus.FAILED)

        failedWithCommitted.toPageDisplayProjection(committed).let {
            it.state shouldBe PageDisplayState.FAILED_WITH_COMMITTED_RESULT
            it.displayReady shouldBe true
        }
        failedWithoutCommitted.toPageDisplayProjection().let {
            it.state shouldBe PageDisplayState.FAILED_NO_RESULT
            it.displayReady shouldBe false
        }
    }

    @Test
    fun `reader adhoc result is display-ready`() {
        val page = readyPage().copy(
            translationOrigin = ArtifactOrigin.READER_ADHOC.name,
        )

        page.toPageDisplayProjection().displayReady shouldBe true
    }

    @Test
    fun `cleaned publication failure keeps translated overlay on the original image`() {
        val fallback = markOriginalImageFallback(readyPage())

        fallback.cleanedImageName shouldBe null
        fallback.originalImageFallback shouldBe true
        fallback.inpaintStatus shouldBe StageStatus.READY
        fallback.renderStatus shouldBe StageStatus.READY
        fallback.toPageDisplayProjection().let {
            it.state shouldBe PageDisplayState.DISPLAY_READY
            it.displayReady shouldBe true
        }
        fallback.displayImageName shouldBe null
        fallback.shouldSurfaceError shouldBe false
        selectReaderTranslationOverlayBinding(true, fallback).blocks shouldBe fallback.blocks
    }

    @Test
    fun `textless page is processed but not translated-ready`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
        )

        page.toPageDisplayProjection().let {
            it.state shouldBe PageDisplayState.TEXTLESS_COMPLETE
            it.processed shouldBe true
            it.displayReady shouldBe false
        }
    }

    @Test
    fun `drawer ready count matches committed reader-visible pages and natural numbers`() {
        val committedReady = readyPage().copy(pageVersion = 1)
        val candidate = committedReady.copy(
            pageVersion = 2,
            cleanedImageName = null,
            inpaintStatus = StageStatus.PENDING,
            translationStatus = StageStatus.RUNNING,
            renderStatus = StageStatus.PENDING,
        )
        val tierOneOnly = readyPage().copy(
            cleanedImageName = null,
            inpaintStatus = StageStatus.PENDING,
        )
        val pages = linkedMapOf("page-20.jpg" to candidate, "page-7.jpg" to tierOneOnly)
        val committed = mapOf("page-20.jpg" to committedReady)

        val snapshot = TranslationBatchProgressTracker.computeSnapshot(
            pageMap = pages,
            chapterState = Translation.State.TRANSLATING,
            indexResolver = mapOf("page-20.jpg" to 20, "page-7.jpg" to 7),
            displayPageMap = committed,
        )

        val committedReadyCount = committed.values.count {
            it.toPageDisplayProjection().displayReady
        }
        snapshot.displayReadyPages shouldBe committedReadyCount
        snapshot.perStage.getValue(BatchPhase.DISPLAY).succeeded shouldBe 1
        snapshot.canReadTranslated shouldBe true
        snapshot.pages.map { it.index } shouldBe listOf(7, 20)
        snapshot.pages.first { it.index == 7 }.displayReady shouldBe false
    }

    @Test
    fun `read action is disabled when no committed display exists`() {
        val snapshot = TranslationProgressSnapshot(
            chapterId = 1,
            state = Translation.State.TRANSLATING,
            donePages = 0,
            totalPages = 1,
            activePage = 1,
            activePageKey = "page-1.jpg",
            queuedCount = 1,
            failedCount = 0,
            pages = listOf(
                TranslationProgressSnapshot.Page(
                    pageKey = "page-1.jpg",
                    index = 1,
                    stage = TranslationProgressStage.OCR,
                ),
            ),
        )

        snapshot.displayReadyPages shouldBe 0
        snapshot.canReadTranslated shouldBe false
    }

    @Test
    fun `artifact projection requires validated committed base`() {
        val record = PageArtifactRecord(
            pageKey = "page-1.jpg",
            displayState = PageDisplayState.DISPLAY_READY,
            committed = CommittedBundleMetadata(
                generationId = "g1",
                displayBase = DisplayBaseReference(
                    kind = DisplayBaseKind.CLEANED_IMAGE,
                    fileName = "page-1.cleaned.jpg",
                    validated = true,
                ),
                origin = ArtifactOrigin.READER_ADHOC,
            ),
            inpaint = StageArtifactRecord(ArtifactStageStatus.READY, artifactFileName = "cleaned.png"),
            translation = StageArtifactRecord(ArtifactStageStatus.READY, artifactFileName = "translation.json"),
            layout = StageArtifactRecord(ArtifactStageStatus.READY, artifactFileName = "layout.json"),
        )

        PageDisplayProjection.from(record).displayReady shouldBe true
        PageDisplayProjection.from(
            record.copy(layout = StageArtifactRecord(ArtifactStageStatus.ABSENT)),
        ).displayReady shouldBe false
    }

    private fun readyPage() = PageTranslation(
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "translated",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        cleanedImageName = "page.cleaned.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        renderStatus = StageStatus.READY,
    )
}
