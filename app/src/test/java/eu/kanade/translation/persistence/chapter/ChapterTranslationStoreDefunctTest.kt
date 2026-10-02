package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Guards the defunct-store lifecycle flag added to close the
 * delete-then-retranslate race.
 *
 * [TranslationManager.deleteTranslation] now joins the batch worker before
 * deleting files, but the worker can be mid-uncancellable ONNX at cancel() time
 * and reach a suspension point AFTER its store was evicted and the on-disk file
 * deleted. Without a guard it would recreate the JSON or strand a page at
 * RUNNING on a store the reader no longer observes — the "original image + stuck
 * spinner" symptom. [markDefunct] (called by unregisterActiveTranslationStore)
 * flips a flag that turns every mutator into a logged no-op.
 *
 * The store is constructed with no file/creator: the defunct guard returns
 * BEFORE any mutex/persist, so no disk I/O is reachable on the defunct path.
 */
class ChapterTranslationStoreDefunctTest {

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = emptyMap(),
    )

    private fun block() = TranslationBlock(
        text = "源",
        translation = "source",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    @Test
    fun `markDefunct flips isDefunct`() = runTest {
        val store = newStore()
        store.isDefunct shouldBe false
        store.markDefunct()
        store.isDefunct shouldBe true
    }

    @Test
    fun `updatePage is a no-op after markDefunct and leaves state untouched`() = runTest {
        val store = newStore()
        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        val before = store.state.value["p1"]
        before?.blocks shouldBe listOf(block())
        val persistsBefore = store.persistCount

        store.markDefunct()

        // A late write from an unwound worker — e.g. flipping a stage to RUNNING.
        store.updatePage("p1") {
            it!!.copy(ocrStatus = StageStatus.RUNNING)
        }

        store.state.value["p1"] shouldBe before
        store.state.value["p1"]?.ocrStatus shouldBe StageStatus.PENDING
        store.persistCount shouldBe persistsBefore
    }

    @Test
    fun `preRegisterPages is a no-op after markDefunct`() = runTest {
        val store = newStore()
        store.preRegisterPages(listOf("p1"))
        store.state.value.keys.size shouldBe 1

        store.markDefunct()
        store.preRegisterPages(listOf("p2", "p3"))

        store.state.value.keys.size shouldBe 1
        store.state.value.containsKey("p2") shouldBe false
    }

    @Test
    fun `replaceAll is a no-op after markDefunct`() = runTest {
        val store = newStore()
        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        val original = store.state.value

        store.markDefunct()
        store.replaceAll(
            mapOf(
                "p1" to PageTranslation(cleanedImageName = "p1.cleaned.png"),
                "p2" to PageTranslation(cleanedImageName = "p2.cleaned.png"),
            ),
        )

        // State is byte-for-byte the pre-defunct snapshot.
        store.state.value shouldBe original
        store.state.value["p2"] shouldBe null
    }

    @Test
    fun `clearTransientQueuePages is a no-op after markDefunct`() = runTest {
        val store = newStore()
        // A RUNNING page that a healthy clearTransientQueuePages WOULD reset to
        // CANCELLED — proving the defunct path skipped the reset entirely.
        store.updatePage("p1") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.state.value["p1"]?.ocrStatus shouldBe StageStatus.RUNNING

        store.markDefunct()
        store.clearTransientQueuePages("late cancel from unwound worker")

        store.state.value["p1"]?.ocrStatus shouldBe StageStatus.RUNNING
    }

    @Test
    fun `mutators still work on a fresh non-defunct store`() = runTest {
        // Regression guard: the defunct flag is opt-in. A freshly opened store
        // must keep behaving exactly as before so normal translation is unaffected.
        val store = newStore()
        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        store.flush()

        store.state.value["p1"]?.blocks shouldBe listOf(block())
        store.persistCount shouldBe 1
        store.isDefunct shouldBe false
    }
}
