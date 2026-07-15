package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChapterTranslationStoreRaceTest {
    @Test
    fun `returns and emissions are deeply detached`() = runTest {
        val input = PageTranslation(blocks = mutableListOf(block("source", "first")))
        val store = ChapterTranslationStore(null, null, mapOf("p1" to input))

        input.blocks[0].translation = "input mutation"
        val emitted = store.state.value["p1"]!!
        emitted.blocks[0].translation = "emission mutation"
        val snapshot = store.snapshot("p1")
        snapshot.page!!.blocks[0].translation = "snapshot mutation"

        store.snapshot("p1").page!!.blocks[0].translation shouldBe "first"
    }

    @Test
    fun `accepted updates have monotonic versions independent of wall clock`() = runTest {
        val store = ChapterTranslationStore(null, null)
        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        val first = store.snapshot("p1").pageVersion
        store.updatePage("p1") { it!!.apply { errorMessage = "same millisecond is irrelevant" } }
        val second = store.snapshot("p1").pageVersion

        second shouldNotBe first
        (second > first) shouldBe true
    }

    @Test
    fun `stale generation version fingerprint cancel timeout and edit patches reject`() = runTest {
        val store = ChapterTranslationStore(null, null)
        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        val initial = store.snapshot("p1")
        val expected = initial.precondition()

        store.updatePage("p1") { it!!.apply { errorMessage = "new version" } }
        store.patchPage("p1", expected, "late version") { it!! } shouldBe
            ChapterTranslationStore.PatchResult.Rejected("pageVersion expected=${initial.pageVersion} actual=${store.snapshot("p1").pageVersion}")

        val beforeCancel = store.snapshot("p1")
        store.invalidateGeneration("cancel")
        (store.patchPage("p1", beforeCancel.precondition(), "post-cancel") { it!! } is ChapterTranslationStore.PatchResult.Rejected) shouldBe true

        val beforeTimeout = store.snapshot("p1")
        store.invalidateGeneration("timeout")
        (store.patchPage("p1", beforeTimeout.precondition(), "post-timeout") { it!! } is ChapterTranslationStore.PatchResult.Rejected) shouldBe true

        val beforeEdit = store.snapshot("p1")
        store.updatePage("p1") { page -> page!!.apply { blocks[0].translation = "user edit"; blocks[0].userEditedAt = 9L } }
        (store.patchPage("p1", beforeEdit.precondition(), "post-edit") { it!! } is ChapterTranslationStore.PatchResult.Rejected) shouldBe true
    }

    @Test
    fun `generation scoped legacy write rejects after timeout invalidation`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val generation = store.beginGeneration("run")

        store.withGeneration(generation) {
            store.invalidateGeneration("timeout")
            store.updatePage("p1") { PageTranslation(errorMessage = "late") }
        }

        store.snapshot("p1").page shouldBe null
    }

    @Test
    fun `missing page block patch is a logged rejection`() = runTest {
        val store = ChapterTranslationStore(null, null)
        val snapshot = store.snapshot("missing")

        val result = store.patchBlock(
            pageKey = "missing",
            blockIndex = 0,
            expected = snapshot.precondition(),
            expectedBlockFingerprint = "absent",
            description = "late missing block",
        ) { it }

        result shouldBe ChapterTranslationStore.PatchResult.Rejected("page missing")
    }

    private fun ChapterTranslationStore.PageSnapshot.precondition() = ChapterTranslationStore.PatchPrecondition(
        generation = generation,
        pageVersion = pageVersion,
        blockFingerprints = blockFingerprints,
    )

    private fun block(text: String = "source", translation: String = "target") = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 1f,
        y = 2f,
        symHeight = 3f,
        symWidth = 4f,
        angle = 0f,
    )
}
