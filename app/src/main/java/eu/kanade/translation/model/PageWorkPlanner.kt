package eu.kanade.translation.model

object PageWorkPlanner {
    fun plan(page: PageTranslation?, force: Boolean = false): PageWorkPlan {
        if (page == null || force) {
            return PageWorkPlan(
                runOcr = true,
                runTranslation = true,
                runInpaint = true,
                runRender = true,
            )
        }

        val hasValidOcr = page.ocrStatus == StageStatus.READY || page.ocrStatus == StageStatus.TEXTLESS
        val ocrFailed = page.ocrStatus == StageStatus.FAILED
        val textless = page.ocrStatus == StageStatus.TEXTLESS

        if (ocrFailed) {
            return PageWorkPlan(
                runOcr = true,
                runTranslation = false,
                runInpaint = false,
                runRender = false,
            )
        }

        if (!hasValidOcr) {
            return PageWorkPlan(
                runOcr = true,
                runTranslation = true,
                runInpaint = true,
                runRender = true,
            )
        }

        if (textless) {
            return PageWorkPlan(
                runOcr = false,
                runTranslation = false,
                runInpaint = false,
                runRender = false,
            )
        }

        val hasValidTranslation =
            page.translationStatus == StageStatus.READY || page.translationStatus == StageStatus.PARTIAL
        val hasValidInpaint = page.inpaintStatus == StageStatus.READY && page.cleanedImageName != null
        val hasValidRender = page.renderStatus == StageStatus.READY

        return PageWorkPlan(
            runOcr = false,
            runTranslation = !hasValidTranslation,
            runInpaint = !hasValidInpaint,
            runRender = !hasValidTranslation || !hasValidInpaint || !hasValidRender,
        )
    }
}
