package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressStage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationBatchProgressTrackerTest {
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
    fun `revision lifecycle serializes complete and failed progress`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val tracker = TranslationBatchProgressTracker(7, store, emptyList(), this)
        tracker.beginRevision(totalBlocks = 3, skippedBlocks = 1, userEditedBlocks = 1)
        tracker.markRevisionChunkRunning(listOf("001.jpg"), 2)
        tracker.markRevisionChunkCompleted(2)
        tracker.markRevisionChunkFailed(1)
        tracker.markRevisionFinished()
        runCurrent()

        tracker.snapshot.value.batchPhase shouldBe TranslationBatchPhase.FINALIZING
        tracker.snapshot.value.revision.completedBlocks shouldBe 2
        tracker.snapshot.value.revision.failedBlocks shouldBe 0
        tracker.snapshot.value.revision.processedBlocks shouldBe 3
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
                unresolvedRevisionCount = 0,
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
            ReconciliationResult(Translation.State.TRANSLATED, emptyMap(), emptySet(), 0, 0, 0, 0),
        )

        tracker.awaitTerminalSnapshot().batchPhase shouldBe TranslationBatchPhase.FINISHED
    }
}
