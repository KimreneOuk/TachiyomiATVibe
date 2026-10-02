package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PublishedPageStoreStateTest {

    @Test
    fun `published wrappers preserve draft value equality`() {
        val draft = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    blockId = "b1",
                    text = "source",
                    translation = "target",
                    width = 10f,
                    height = 12f,
                    x = 3f,
                    y = 4f,
                    symHeight = 5f,
                    symWidth = 6f,
                    angle = 0f,
                ),
            ),
        ).apply {
            allTextDetections = listOf(
                Detection(
                    bbox = intArrayOf(1, 2, 3, 4),
                    label = 7,
                    score = 0.75f,
                    className = "text",
                ),
            )
        }
        val published = draft.toPublishedPage()

        published shouldBe draft
        draft shouldBe published
        published.hashCode() shouldBe draft.hashCode()
        published.toString() shouldBe draft.toString()
        published.blocks shouldBe draft.blocks
        draft.blocks shouldBe published.blocks
        published.blocks.single().toString() shouldBe draft.blocks.single().toString()
        published.allTextDetections shouldBe draft.allTextDetections
        draft.allTextDetections shouldBe published.allTextDetections
        published.allTextDetections.single().toString() shouldBe draft.allTextDetections.single().toString()
        assertEquals(published.allTextDetections.hashCode(), draft.allTextDetections.hashCode())
    }

    @Test
    fun `draft materialization preserves stale error text without re-routing it`() {
        val recovered = PageTranslation().apply {
            ocrStatus = StageStatus.FAILED
            errorMessage = "old OCR error"
            ocrStatus = StageStatus.READY
            ocrError = null
        }

        val materialized = recovered.toPublishedPage().toDraft()

        materialized.errorMessage shouldBe "old OCR error"
        materialized.ocrError shouldBe null
    }

    @Test
    fun `store emits immutable pages and a single-page update only publishes the changed value`() {
        runTest {
            val initialPages = (0 until 32).associate { index ->
                "page-$index" to PageTranslation(sourceFileName = "page-$index")
            }
            val store = ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = initialPages,
            )
            val emissions = mutableListOf<List<PageTranslationView>>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.state.collect { pages -> emissions += pages.values.toList() }
            }
            runCurrent()

            val beforeCount = store.publishedPageCopyCount
            val before = store.state.value
            repeat(3) {
                store.state.value
                store.display.value
            }
            store.publishedPageCopyCount shouldBe beforeCount

            store.updatePageFromCurrentSnapshot("page-0", "immutable publication test") { page ->
                page!!.copy(translationStatus = StageStatus.READY)
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            runCurrent()

            store.publishedPageCopyCount shouldBe beforeCount + 1
            val after = store.state.value
            assertSame(before.getValue("page-1"), after.getValue("page-1"))
            assertSame(before.getValue("page-31"), after.getValue("page-31"))
            store.state.value.values.forEach { it.shouldBeInstanceOf<PublishedPageTranslation>() }
            store.display.value.values.forEach { it.shouldBeInstanceOf<PublishedPageTranslation>() }
            emissions.forEach { snapshot ->
                snapshot.forEach { it.shouldBeInstanceOf<PublishedPageTranslation>() }
            }

            collector.cancel()
        }
    }
}
