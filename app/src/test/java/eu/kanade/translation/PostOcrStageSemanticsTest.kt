package eu.kanade.translation

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.pipeline.finalizePostOcrStage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PostOcrStageSemanticsTest {

    @Test
    fun `zero block zero mask OCR result remains terminal after stage handoff`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.READY,
            inpaintStatus = StageStatus.RUNNING,
        )

        finalizePostOcrStage(page, inpaintAlreadyRan = false)

        page.translationStatus shouldBe StageStatus.SKIPPED
        page.inpaintStatus shouldBe StageStatus.SKIPPED
        page.renderStatus shouldBe StageStatus.SKIPPED
    }

    @Test
    fun `detector-only mask remains pending for sequential inpaint barrier`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.READY,
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, label = 2)),
        )

        finalizePostOcrStage(page, inpaintAlreadyRan = false)

        page.translationStatus shouldBe StageStatus.SKIPPED
        page.inpaintStatus shouldBe StageStatus.PENDING
        page.renderStatus shouldBe StageStatus.SKIPPED
    }
}
