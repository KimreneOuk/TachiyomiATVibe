package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Regression guard for the batch translate block-merge.
 *
 * The batch path merges translated blocks from the in-memory registry page onto
 * the store page. A prior version matched blocks with
 * `associateBy { it.blockId }`, but [TranslationBlock.blockId] is never
 * populated by any OCR engine in production (always null), so every block
 * collapsed into a single map entry and every region received the LAST block's
 * translation (or "" when the last block was untranslated) — the "same text in
 * every bubble" / "regions with no text" symptom. The live reader path was
 * unaffected because it mutates the page in place and persists the same instance.
 *
 * The fix copies the registry's authoritative translated blocks wholesale. These
 * tests pin the invariant (each block keeps its own translation) and document
 * why a blockId-keyed merge is unsafe while blockId stays null.
 */
class BatchTranslateBlockMergeTest {

    private fun store() = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = emptyMap(),
    )

    private fun block(text: String, translation: String = "") = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(vararg texts: String) = PageTranslation(
        blocks = texts.map { block(it) }.toMutableList(),
    )

    @Test
    fun `wholesale block copy preserves each block translation in the store`() = runTest {
        val store = store()
        store.updatePage("p1") { ocrPage("one", "two", "three") }

        val p = PageTranslation(
            blocks = mutableListOf(
                block("one", "ONE"),
                block("two", "TWO"),
                block("three", "THREE"),
            ),
        )

        store.updatePage("p1") {
            (it ?: p).apply {
                if (it != null && it !== p) {
                    blocks = p.blocks.toMutableList()
                }
            }
        }

        val merged = store.state.value["p1"]!!.blocks
        merged.map { it.translation } shouldContainExactly listOf("ONE", "TWO", "THREE")
    }

    @Test
    fun `legacy blockId-keyed merge collapses every region to the last translation`() = runTest {
        val store = store()
        store.updatePage("p1") { ocrPage("one", "two", "three") }

        val p = PageTranslation(
            blocks = mutableListOf(
                block("one", "ONE"),
                block("two", "TWO"),
                block("three", "THREE"),
            ),
        )

        store.updatePage("p1") {
            (it ?: p).apply {
                if (it != null && it !== p) {
                    val byId = p.blocks.associateBy { b -> b.blockId }
                    blocks = blocks.map { b ->
                        byId[b.blockId]?.let { tb -> b.copy(translation = tb.translation) } ?: b
                    }.toMutableList()
                }
            }
        }

        p.blocks.forEach { it.blockId shouldBe null }
        val corrupted = store.state.value["p1"]!!.blocks
        corrupted.map { it.translation } shouldContainExactly listOf("THREE", "THREE", "THREE")
    }
}
