package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards [TranslationProgress.compute] — the per-chapter batch progress
 * (done/total) the manga-screen chapter-list indicator displays during
 * pre-translation.
 */
class TranslationProgressTest {

    @Test
    fun `null or empty page map is zero progress`() {
        TranslationProgress.compute(null) shouldBe (0 to 0)
        TranslationProgress.compute(emptyMap()) shouldBe (0 to 0)
    }

    @Test
    fun `rendered page counts as done`() {
        val pages = mapOf(
            "1" to page(rendered = true),
        )
        TranslationProgress.compute(pages) shouldBe (1 to 1)
    }

    @Test
    fun `textless terminal page counts as done`() {
        // ocrStatus=READY, no blocks, inpaint terminal (not pending/running).
        val pages = mapOf(
            "1" to PageTranslation(
                ocrStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
            ),
        )
        TranslationProgress.compute(pages) shouldBe (1 to 1)
    }

    @Test
    fun `failed page below retry cap is NOT done`() {
        val pages = mapOf(
            "1" to PageTranslation(ocrStatus = StageStatus.FAILED).apply { attemptCount = 1 },
        )
        TranslationProgress.compute(pages) shouldBe (0 to 1)
    }

    @Test
    fun `failed page at retry cap IS done`() {
        val pages = mapOf(
            "1" to PageTranslation(
                ocrStatus = StageStatus.FAILED,
            ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES },
        )
        TranslationProgress.compute(pages) shouldBe (1 to 1)
    }

    @Test
    fun `pending and running pages are not done`() {
        val pages = mapOf(
            "1" to PageTranslation(ocrStatus = StageStatus.PENDING),
            "2" to PageTranslation(ocrStatus = StageStatus.RUNNING),
            "3" to PageTranslation(ocrStatus = StageStatus.RUNNING),
        )
        TranslationProgress.compute(pages) shouldBe (0 to 3)
    }

    @Test
    fun `mixed chapter counts rendered plus retry-exhausted as done`() {
        val pages = mapOf(
            "1" to page(rendered = true),
            "2" to page(rendered = true),
            "3" to PageTranslation(ocrStatus = StageStatus.PENDING), // in flight
            "4" to PageTranslation( // permanently failed
                ocrStatus = StageStatus.FAILED,
            ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES },
            "5" to PageTranslation(ocrStatus = StageStatus.READY), // analyzed, not done
        )
        // done = 1, 2, 4 => 3 of 5
        TranslationProgress.compute(pages) shouldBe (3 to 5)
    }

    private fun page(rendered: Boolean): PageTranslation {
        val p = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(text = "源", translation = "src", width = 10f, height = 10f, x = 0f, y = 0f, symHeight = 1f, symWidth = 1f, angle = 0f),
            ),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        )
        if (rendered) {
            p.renderedImageName = "1.rendered.png"
            p.renderQuality = RenderQuality.FULL
            p.cleanedImageName = "1.cleaned.png"
        }
        return p
    }
}
