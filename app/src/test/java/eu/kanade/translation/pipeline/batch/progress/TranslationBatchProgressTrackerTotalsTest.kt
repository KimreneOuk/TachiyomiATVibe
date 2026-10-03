package eu.kanade.translation.pipeline.batch.progress

import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The batch's ordered work keys define the
 * tracker total — even when store placeholder writes are rejected or delayed.
 * Completed counts still come only from the store intersection.
 */
class TranslationBatchProgressTrackerTotalsTest {

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
        cleanedImageName = "001.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    @Test
    fun `total is nonzero immediately from ordered keys with an empty store`() = runTest {
        val store = ChapterTranslationStore()
        val tracker = TranslationBatchProgressTracker(
            1,
            store,
            listOf("001.jpg", "002.jpg", "003.jpg"),
            this,
        )

        // No rebuildFromStore, no store placeholders: the ordered keys alone
        // produce the real total (previously 0/0 until a rebuild).
        val snapshot = tracker.snapshot.value
        snapshot.totalPages shouldBe 3
        snapshot.donePages shouldBe 0
        snapshot.failedCount shouldBe 0
        snapshot.pages.map { it.pageKey } shouldBe listOf("001.jpg", "002.jpg", "003.jpg")
        snapshot.pages.all { it.stage == TranslationProgressStage.QUEUED } shouldBe true
        tracker.close()
    }

    @Test
    fun `completed counts come only from the store intersection`() = runTest {
        val store = ChapterTranslationStore(
            initialPages = mapOf("001.jpg" to readyPage()),
        )
        val tracker = TranslationBatchProgressTracker(
            1,
            store,
            listOf("001.jpg", "002.jpg"),
            this,
        )

        // 002.jpg is known work but has no store entry: it counts toward the
        // total and toward queued, never toward completed or failed.
        val snapshot = tracker.snapshot.value
        snapshot.totalPages shouldBe 2
        snapshot.donePages shouldBe 1
        snapshot.queuedCount shouldBe 1
        snapshot.failedCount shouldBe 0
        snapshot.pages.first { it.pageKey == "002.jpg" }.stage shouldBe TranslationProgressStage.QUEUED
        tracker.close()
    }

    @Test
    fun `placeholder registration does not inflate completed counts`() = runTest {
        val store = ChapterTranslationStore()
        val tracker = TranslationBatchProgressTracker(
            1,
            store,
            listOf("001.jpg", "002.jpg"),
            this,
        )

        store.preRegisterPages(listOf("001.jpg", "002.jpg"))
        tracker.rebuildFromStore()
        val snapshot = tracker.snapshot.value

        snapshot.totalPages shouldBe 2
        snapshot.donePages shouldBe 0
        snapshot.queuedCount shouldBe 2
        snapshot.failedCount shouldBe 0
        tracker.close()
    }

    @Test
    fun `phase events apply to placeholders so a rejected store still shows failures`() = runTest {
        val store = ChapterTranslationStore()
        val tracker = TranslationBatchProgressTracker(
            1,
            store,
            listOf("001.jpg", "002.jpg"),
            this,
        )

        tracker.markOcrFailed("001.jpg", "provider refused")
        runCurrent()
        val snapshot = tracker.snapshot.value

        snapshot.totalPages shouldBe 2
        snapshot.failedCount shouldBe 1
        snapshot.pages.first { it.pageKey == "001.jpg" }.stage shouldBe TranslationProgressStage.FAILED
        tracker.close()
    }

    @Test
    fun `aborted terminal snapshot keeps the known totals with the typed reason`() = runTest {
        val store = ChapterTranslationStore()
        val tracker = TranslationBatchProgressTracker(
            1,
            store,
            listOf("001.jpg", "002.jpg", "003.jpg"),
            this,
        )

        tracker.abort(setOf("001.jpg", "002.jpg", "003.jpg"), "Page pre-registration was rejected: store is defunct")
        runCurrent()
        val snapshot = tracker.snapshot.value

        snapshot.totalPages shouldBe 3
        snapshot.donePages shouldBe 0
        snapshot.state shouldBe Translation.State.ERROR
        snapshot.batchPhase shouldBe TranslationBatchPhase.FINISHED
        snapshot.aborted shouldBe true
        snapshot.abortedReason shouldBe "Page pre-registration was rejected: store is defunct"
        tracker.awaitTerminalSnapshot().totalPages shouldBe 3
    }

    /**
     *  : no lane renders in-pass, so without the shell's
     * COMPLETED-path settle the tracker's RENDER arm stays unprocessed forever
     * and the processed/total fraction tops out at 4/5 per display-ready page.
     * The shell emits [TranslationBatchProgressTracker.markRenderSkipped] per
     * expected page before [TranslationBatchProgressTracker.finish]; this test
     * pins that this sequence completes the 5/5 terminal fraction.
     */
    @Test
    fun `completion render-skip settle completes the 5 of 5 terminal fraction`() = runTest {
        val store = ChapterTranslationStore(
            initialPages = mapOf("p0" to zeroLegacyTerminalPage("p0")),
        )
        // The reader re-derived the display: a committed display bundle exists
        // (promoted from the render-ready update) while the live page keeps the
        //  translation-terminal shape (render PENDING) — flipping the
        // live page back never demotes the committed display.
        store.updatePage("p0") { current -> current!!.apply { renderStatus = StageStatus.READY } }
        store.updatePage("p0") { current -> current!!.apply { renderStatus = StageStatus.PENDING } }

        val tracker = TranslationBatchProgressTracker(1, store, listOf("p0"), this)
        runCurrent()

        // Pre-settle (the -observed gap): OCR/INPAINT/TRANSLATE/DISPLAY are
        // processed, RENDER is the only arm left — the fraction tops out at 4/5.
        tracker.snapshot.value.perStage.getValue(BatchPhase.RENDER).processed shouldBe 0
        tracker.snapshot.value.doneStages shouldBe 4
        tracker.snapshot.value.totalStages shouldBe 5
        tracker.snapshot.value.fraction shouldBe 0.8f

        // The  COMPLETED-path sequence: markRenderSkipped per
        // expected page, then the terminal finish.
        tracker.markRenderSkipped("p0")
        tracker.finish(
            ReconciliationResult(
                chapterStatus = Translation.State.TRANSLATED,
                strandedPages = emptyMap(),
                unexpectedPageKeys = emptySet(),
                doneCount = 1,
                failedCount = 0,
                partialCount = 0,
            ),
        )
        runCurrent()

        val terminal = tracker.awaitTerminalSnapshot()
        terminal.perStage.getValue(BatchPhase.RENDER).processed shouldBe 1
        terminal.perStage.getValue(BatchPhase.RENDER).skipped shouldBe 1
        terminal.doneStages shouldBe 5
        terminal.totalStages shouldBe 5
        terminal.fraction shouldBe 1f
        tracker.close()
    }

    /**  terminal page: committed translation, NO in-pass render. */
    private fun zeroLegacyTerminalPage(key: String) = PageTranslation(
        sourceFileName = key,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.PENDING,
        cleanedImageName = "$key.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        imgWidth = 100f,
        imgHeight = 100f,
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
    )
}
