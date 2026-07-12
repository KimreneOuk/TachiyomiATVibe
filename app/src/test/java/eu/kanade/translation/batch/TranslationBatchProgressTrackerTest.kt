package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.TranslationProgressStage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Guards [TranslationBatchProgressTracker] store-write amplification fix.
 *
 * The tracker's per-stage [transition] previously recomputed a full
 * [TranslationProgressSnapshot] on every call — O(n) per transition,
 * compounding to O(chapter²) in long batches. These tests verify that:
 *
 * 1. Transient (RUNNING) transitions no longer emit snapshots immediately.
 * 2. Terminal transitions are still reconciled via [rebuildFromStore].
 * 3. [finish] stops the conflated tick coroutine.
 * 4. [rebuildFromStore] produces the same snapshot as [computeSnapshot].
 */
class TranslationBatchProgressTrackerTest {

    @Test
    fun `transition_runningStage_doesNotPersistTrackerOnlyNoise`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val tracker = TranslationBatchProgressTracker(
            chapterId = 1L,
            store = store,
            orderedPageKeys = listOf("001.jpg"),
            scope = this,
        )

        store.preRegisterPages(listOf("001.jpg"))
        tracker.rebuildFromStore()
        tracker.snapshot.value.pages.firstOrNull()?.stage shouldBe TranslationProgressStage.QUEUED

        // Act: mark a transient (RUNNING) stage
        tracker.markOcrRunning("001.jpg")

        // The store was updated (updatePage always propagates to StateFlow)
        store.state.value["001.jpg"]?.ocrStatus shouldBe StageStatus.RUNNING

        // But the tracker snapshot was NOT recomputed by transition —
        // the page still shows as QUEUED (the stale baseline).
        tracker.snapshot.value.pages.firstOrNull()?.stage shouldBe TranslationProgressStage.QUEUED

        // The persistence gate would reject this transient update
        val updatedPage = store.state.value["001.jpg"]!!
        store.shouldPersistUpdate(previous = null, updated = updatedPage) shouldBe false

        tracker.close()
    }

    @Test
    fun `transition_terminalStage_updatesProgressSnapshot`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val tracker = TranslationBatchProgressTracker(
            chapterId = 1L,
            store = store,
            orderedPageKeys = listOf("001.jpg"),
            scope = this,
        )

        store.preRegisterPages(listOf("001.jpg"))
        tracker.rebuildFromStore()

        // Act: mark a terminal (FAILED) stage
        tracker.markOcrFailed("001.jpg", "test failure")

        // Snapshot was NOT recomputed by the transition alone
        tracker.snapshot.value.pages.firstOrNull()?.stage shouldBe TranslationProgressStage.QUEUED

        // After explicit reconciliation the snapshot reflects the durable change
        tracker.rebuildFromStore()
        val page = tracker.snapshot.value.pages.firstOrNull()
        page?.stage shouldBe TranslationProgressStage.FAILED
        page?.errorMessage shouldBe "test failure"

        tracker.close()
    }

    @Test
    fun `tracker_finish_disposesOrStopsTicking`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val tracker = TranslationBatchProgressTracker(
            chapterId = 1L,
            store = store,
            orderedPageKeys = listOf("001.jpg"),
            scope = this,
        )

        store.preRegisterPages(listOf("001.jpg"))
        tracker.rebuildFromStore()

        val doneResult = ReconciliationResult(
            chapterStatus = Translation.State.TRANSLATED,
            strandedPages = emptyMap(),
            doneCount = 1,
            failedCount = 0,
            partialCount = 0,
        )
        tracker.finish(doneResult)

        val snapshotAtFinish = tracker.snapshot.value

        // Mutate store state after finish
        store.updatePage("001.jpg") { existing ->
            (existing ?: PageTranslation(sourceFileName = "001.jpg")).copy(
                ocrStatus = StageStatus.FAILED,
                errorMessage = "late failure",
            )
        }

        // Advance virtual time past one tick interval (500 ms)
        advanceTimeBy(1000)

        // Snapshot must remain unchanged — the tick was cancelled
        tracker.snapshot.value.pages shouldBe snapshotAtFinish.pages

        // Further transitions must be no-ops
        tracker.markOcrRunning("001.jpg")
        tracker.snapshot.value.pages shouldBe snapshotAtFinish.pages

        tracker.close()
    }

    @Test
    fun `tracker_reconcileFromStore_matchesExistingSnapshotSemantics`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val tracker = TranslationBatchProgressTracker(
            chapterId = 42L,
            store = store,
            orderedPageKeys = listOf("001.jpg", "002.jpg"),
            scope = this,
        )

        // Build a realistic mixed-state page map in the store
        store.updatePage("001.jpg") {
            PageTranslation(
                sourceFileName = "001.jpg",
                ocrStatus = StageStatus.READY,
                blocks = mutableListOf(block()),
            )
        }
        store.updatePage("002.jpg") {
            PageTranslation(sourceFileName = "002.jpg", ocrStatus = StageStatus.RUNNING)
        }

        tracker.rebuildFromStore()
        val reconciled = tracker.snapshot.value

        val pageMap = store.state.value
        val expected = TranslationBatchProgressTracker.computeSnapshot(
            pageMap = pageMap,
            chapterState = Translation.State.TRANSLATING,
        )

        reconciled.pages shouldBe expected.pages
        reconciled.donePages shouldBe expected.donePages
        reconciled.totalPages shouldBe expected.totalPages
        reconciled.failedCount shouldBe expected.failedCount
        reconciled.queuedCount shouldBe expected.queuedCount
        reconciled.activeStage shouldBe expected.activeStage

        tracker.close()
    }

    private fun block() = TranslationBlock(
        text = "\u6E90",
        translation = "source",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
