package eu.kanade.translation.scheduling

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Store-level fast-flip regression coverage retained from B4 step 1.
 *
 * The scheduler-level sync/auto-cancel flip paths were retired with the
 * rolling coordinator in Phase 4 S4: queue cancellation publishes typed
 * terminal outcomes the reader observes immediately (S1 queue coverage), and
 * destructive control paths gate store eviction on the queue's physical drain
 * handles. What remains load-bearing at the store level is the in-memory fast
 * flip itself: RUNNING stages become CANCELLED immediately, rendered results
 * are preserved, and the flip is a no-op after the store is marked defunct
 * (covered by [eu.kanade.translation.persistence.ChapterTranslationStoreDefunctTest]).
 */
class CancelSyncStoreWriteTest {

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore(
        artifactParentResolver = null,
        initialPages = emptyMap(),
    )

    @Test
    fun `fastCancelInFlightStagesInMemory flips RUNNING pages to CANCELLED in memory immediately`() = runTest {
        val store = newStore()
        store.updatePage("p0") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.updatePage("p1") {
            PageTranslation(
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.RUNNING,
            )
        }
        store.updatePage("p2") {
            PageTranslation(
                cleanedImageName = "p2.cleaned.png",
                ocrStatus = StageStatus.READY,
            )
        }

        val flipped = store.fastCancelInFlightStagesInMemory()

        flipped shouldBe 2
        store.state.value["p0"]?.ocrStatus shouldBe StageStatus.CANCELLED
        store.state.value["p1"]?.translationStatus shouldBe StageStatus.CANCELLED
        store.state.value["p2"]?.ocrStatus shouldBe StageStatus.READY
    }
}
