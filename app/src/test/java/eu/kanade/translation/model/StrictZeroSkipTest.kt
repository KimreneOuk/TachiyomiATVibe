package eu.kanade.translation.model

import eu.kanade.tachiyomi.ui.reader.viewer.selectReaderTranslationOverlayBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrictZeroSkipTest {

    @Test
    fun `text overlay is only bound when image is cleaned and translated`() {
        val dirtyPage = PageTranslation(
            sourceFileName = "page_1.jpg",
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.PENDING, // Not cleaned yet!
            cleanedImageName = null,
            renderStatus = StageStatus.PENDING,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "Hello",
                    translation = "Bonjour",
                    x = 0f,
                    y = 0f,
                    width = 10f,
                    height = 10f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        )

        // Overlay MUST NOT be bound over dirty uncleaned image (showTranslatedImage = true or false)
        val dirtyBindingWhenShowingTranslated = selectReaderTranslationOverlayBinding(showTranslatedImage = true, dirtyPage)
        assertTrue(dirtyBindingWhenShowingTranslated.blocks.isEmpty())

        val dirtyBindingWhenShowingOriginal = selectReaderTranslationOverlayBinding(showTranslatedImage = false, dirtyPage)
        assertTrue(dirtyBindingWhenShowingOriginal.blocks.isEmpty())

        val cleanPage = dirtyPage.copy(
            inpaintStatus = StageStatus.READY,
            cleanedImageName = "page_1.cleaned.jpg",
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            renderStatus = StageStatus.READY,
            imgWidth = 1000f,
            imgHeight = 1500f,
        )

        val cleanBinding = selectReaderTranslationOverlayBinding(showTranslatedImage = true, cleanPage)
        assertEquals(1, cleanBinding.blocks.size)
        assertEquals("Bonjour", cleanBinding.blocks[0].translation)
        assertEquals(1000, cleanBinding.pageWidth)
        assertEquals(1500, cleanBinding.pageHeight)
    }

    @Test
    fun `overlay returns empty binding when inpaint revision is stale`() {
        val staleInpaintPage = PageTranslation(
            sourceFileName = "page_2.jpg",
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            cleanedImageName = "page_2.cleaned.jpg",
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION - 1,
            renderStatus = StageStatus.READY,
            imgWidth = 1000f,
            imgHeight = 1500f,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "Hello",
                    translation = "Bonjour",
                    x = 0f,
                    y = 0f,
                    width = 10f,
                    height = 10f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        )

        val binding = selectReaderTranslationOverlayBinding(showTranslatedImage = true, staleInpaintPage)
        assertTrue(binding.blocks.isEmpty())
    }
}
