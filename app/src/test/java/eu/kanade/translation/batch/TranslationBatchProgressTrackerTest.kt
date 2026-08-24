package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.TranslationProgressStage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationBatchProgressTrackerTest {
    private fun readyPage() = PageTranslation(
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "target",
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
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    @Test
    fun `rebuild from store publishes durable ready page progress`() = runTest {
        val page = readyPage()
        val store = ChapterTranslationStore(
            null,
            null,
            initialPages = mapOf("001.jpg" to page),
        )
        val tracker = TranslationBatchProgressTracker(1, store, listOf("001.jpg"), this)

        tracker.snapshot.value.totalPages shouldBe 0

        tracker.rebuildFromStore()
        val snapshot = tracker.snapshot.value

        snapshot.totalPages shouldBe 1
        snapshot.donePages shouldBe 1
        snapshot.pages.single().pageKey shouldBe "001.jpg"
        snapshot.pages.single().processed shouldBe true
        snapshot.pages.single().displayReady shouldBe true
        BatchPhase.entries.forEach { phase ->
            snapshot.perStage.getValue(phase).succeeded shouldBe 1
        }
        tracker.close()
    }

    @Test
    fun `phase events are projection only and never mutate store`() = runTest {
        val store = ChapterTranslationStore(null, null)
        store.preRegisterPages(listOf("001.jpg"))
        val tracker = TranslationBatchProgressTracker(1, store, listOf("001.jpg"), this)

        tracker.markOcrRunning("001.jpg")
        runCurrent()

        store.state.value.getValue("001.jpg").ocrStatus shouldBe StageStatus.PENDING
        tracker.snapshot.value.pages.single().stage shouldBe TranslationProgressStage.OCR
        tracker.close()
    }

    @Test
    fun `terminal event retains failure in projection`() = runTest {
        val store = ChapterTranslationStore(null, null)
        store.preRegisterPages(listOf("001.jpg"))
        val tracker = TranslationBatchProgressTracker(1, store, listOf("001.jpg"), this)

        tracker.markOcrFailed("001.jpg", "test failure")
        runCurrent()

        tracker.snapshot.value.pages.single().stage shouldBe TranslationProgressStage.FAILED
        tracker.snapshot.value.perStage.getValue(BatchPhase.OCR).failed shouldBe 1
        tracker.close()
    }

    @Test
    fun `AI progress distinguishes pending buffered running succeeded and failed pages`() = runTest {
        val pageKeys = listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg")
        val store = ChapterTranslationStore(null, null)
        store.preRegisterPages(pageKeys)
        val tracker = TranslationBatchProgressTracker(1, store, pageKeys, this)

        tracker.markAiBuffered("002.jpg")
        tracker.markAiRunning("003.jpg")
        tracker.markAiSucceeded("004.jpg")
        tracker.markAiFailed("005.jpg", "provider refused")
        runCurrent()

        tracker.snapshot.value.aiProgress.pending shouldBe 1
        tracker.snapshot.value.aiProgress.buffered shouldBe 1
        tracker.snapshot.value.aiProgress.running shouldBe 1
        tracker.snapshot.value.aiProgress.succeeded shouldBe 1
        tracker.snapshot.value.aiProgress.failed shouldBe 1
        tracker.snapshot.value.aiProgress.processed shouldBe 2
        tracker.snapshot.value.pages.map { it.aiState } shouldBe listOf(
            AiPageProgressState.PENDING,
            AiPageProgressState.BUFFERED,
            AiPageProgressState.RUNNING,
            AiPageProgressState.SUCCEEDED,
            AiPageProgressState.FAILED,
        )
        tracker.close()
    }

    @Test
    fun `AI progress rebuild derives durable success and failure without claiming buffered work`() = runTest {
        val store = ChapterTranslationStore(
            null,
            null,
            initialPages = mapOf(
                "001.jpg" to PageTranslation(translationStatus = StageStatus.READY),
                "002.jpg" to PageTranslation(translationStatus = StageStatus.FAILED),
                "003.jpg" to PageTranslation(),
            ),
        )
        val tracker = TranslationBatchProgressTracker(1, store, listOf("001.jpg", "002.jpg", "003.jpg"), this)

        tracker.rebuildFromStore()

        tracker.snapshot.value.aiSucceededPages shouldBe 1
        tracker.snapshot.value.aiFailedPages shouldBe 1
        tracker.snapshot.value.aiPendingPages shouldBe 1
        tracker.snapshot.value.aiBufferedPages shouldBe 0
        tracker.close()
    }

    @Test
    fun `finish emits immutable terminal snapshot`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val tracker = TranslationBatchProgressTracker(1, store, emptyList(), this)
        tracker.finish(
            ReconciliationResult(
                chapterStatus = Translation.State.TRANSLATED,
                strandedPages = emptyMap(),
                unexpectedPageKeys = emptySet(),
                doneCount = 0,
                failedCount = 0,
                partialCount = 0,
            ),
        )
        runCurrent()
        tracker.snapshot.value.batchPhase shouldBe TranslationBatchPhase.FINISHED
        tracker.snapshot.value.state shouldBe Translation.State.TRANSLATED
    }

    @Test
    fun `terminal snapshot is available after terminal event without waiting for later emissions`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val tracker = TranslationBatchProgressTracker(1, store, emptyList(), this)

        tracker.finish(
            ReconciliationResult(Translation.State.TRANSLATED, emptyMap(), emptySet(), 0, 0, 0),
        )

        tracker.awaitTerminalSnapshot().batchPhase shouldBe TranslationBatchPhase.FINISHED
    }
}
