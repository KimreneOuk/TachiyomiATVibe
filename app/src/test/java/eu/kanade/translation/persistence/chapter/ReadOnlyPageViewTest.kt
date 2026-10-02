package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReadOnlyPageViewTest {

    @Test
    fun renders_share_published_page_data_for_small_and_large_pages() = runTest {
        val largePage = page("large", blockCount = 96, rendered = true)
        val smallPage = page("small", blockCount = 2, rendered = true)
        val candidatePage = page("candidate", blockCount = 96, rendered = false)
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf(
                "large" to largePage,
                "small" to smallPage,
                "candidate" to candidatePage,
            ),
        )

        val largeCommitted: PageTranslationView = store.display.value.getValue("large")
        val smallCommitted: PageTranslationView = store.display.value.getValue("small")
        val candidate: PageTranslationView = store.state.value.getValue("candidate")
        largeCommitted.shouldBeInstanceOf<PublishedPageTranslation>()
        smallCommitted.shouldBeInstanceOf<PublishedPageTranslation>()
        candidate.shouldBeInstanceOf<PublishedPageTranslation>()
        store.committedDisplayPage("candidate") shouldBe null
        val publicationCount = store.publishedPageCopyCount

        var pageDataCopies = 0
        repeat(128) {
            val resolvedLarge: PageTranslationView = store.resolveDisplayPage("large")!!
            val committedLargeBundle: PageTranslationView = store.committedDisplayPage("large")!!
            val resolvedSmall: PageTranslationView = store.resolveDisplayPage("small")!!
            val committedSmallBundle: PageTranslationView = store.committedDisplayPage("small")!!
            val resolvedCandidate: PageTranslationView = store.resolveDisplayPage("candidate")!!

            assertSame(largeCommitted, resolvedLarge)
            assertSame(largeCommitted, committedLargeBundle)
            assertSame(smallCommitted, resolvedSmall)
            assertSame(smallCommitted, committedSmallBundle)
            assertSame(candidate, resolvedCandidate)
            pageDataCopies += copiedPayloadParts(largeCommitted, resolvedLarge)
            pageDataCopies += copiedPayloadParts(largeCommitted, committedLargeBundle)
            pageDataCopies += copiedPayloadParts(smallCommitted, resolvedSmall)
            pageDataCopies += copiedPayloadParts(smallCommitted, committedSmallBundle)
            pageDataCopies += copiedPayloadParts(candidate, resolvedCandidate)
        }

        pageDataCopies shouldBe 0
        store.publishedPageCopyCount shouldBe publicationCount
    }

    private fun page(pageKey: String, blockCount: Int, rendered: Boolean): PageTranslation {
        val blocks = (0 until blockCount).map { index ->
            TranslationBlock(
                blockId = "$pageKey-block-$index",
                text = "source-$index",
                translation = "target-$index",
                width = 10f,
                height = 12f,
                x = index.toFloat(),
                y = 0f,
                symHeight = 4f,
                symWidth = 4f,
                angle = 0f,
            )
        }.toMutableList()
        return PageTranslation(
            blocks = blocks,
            imgWidth = 1200f,
            imgHeight = 1800f,
            sourceFileName = pageKey,
            ocrStatus = if (rendered) StageStatus.READY else StageStatus.RUNNING,
            translationStatus = if (rendered) StageStatus.READY else StageStatus.PENDING,
            inpaintStatus = if (rendered) StageStatus.READY else StageStatus.PENDING,
            renderStatus = if (rendered) StageStatus.READY else StageStatus.PENDING,
            cleanedImageName = if (rendered) "$pageKey.cleaned.jpg" else null,
            inpaintRevision = if (rendered) PageTranslation.CURRENT_INPAINT_REVISION else 0,
            inpaintMaskBoxes = listOf(InpaintMaskBox(1, 2, 3, 4, 0)),
        ).apply {
            allTextDetections = (0 until blockCount).map { index ->
                Detection(
                    bbox = intArrayOf(index, 2, index + 3, 4),
                    label = 7,
                    score = 0.75f,
                    className = "text",
                )
            }
        }
    }

    private fun copiedPayloadParts(expected: PageTranslationView, actual: PageTranslationView): Int {
        var copies = 0
        if (expected !== actual) copies++
        if (expected.blocks !== actual.blocks) copies++
        if (expected.blocks.size != actual.blocks.size) {
            copies++
        } else {
            expected.blocks.indices.forEach { index ->
                if (expected.blocks[index] !== actual.blocks[index]) copies++
            }
        }
        if (expected.inpaintMaskBoxes !== actual.inpaintMaskBoxes) copies++
        if (expected.allTextDetections !== actual.allTextDetections) copies++
        if (expected.allTextDetections.size != actual.allTextDetections.size) {
            copies++
        } else {
            expected.allTextDetections.indices.forEach { index ->
                if (expected.allTextDetections[index] !== actual.allTextDetections[index]) copies++
            }
        }
        return copies
    }
}
