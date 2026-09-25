package eu.kanade.translation.model

import eu.kanade.translation.artifact.ArtifactOrigin
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * R012: forced translation must independently reuse valid detection/OCR
 * evidence based on current source + configuration evidence, decoupled from
 * inpaint readiness.
 *
 * Hard constraints pinned here alongside the new behavior:
 *  - non-force planning decisions are byte-identical to the pre-R012
 *    delegation into [PageWorkPlanner.planPage];
 *  - callers that supply no expected fingerprints keep the historical
 *    status/payload-only pass-through;
 *  - force inputs whose OCR evidence is stale keep re-running OCR (and drag
 *    inpaint with them, since OCR changes invalidate downstream stages).
 */
class PageWorkPlannerForceReuseTest {

    // ------------------------------------------------------------------
    // (a) R012: force + valid OCR evidence + inpaint NOT ready ⇒ reuse OCR
    // ------------------------------------------------------------------

    @Test
    fun `force reuses valid ocr evidence while inpaint is pending`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected).apply {
            inpaintStatus = StageStatus.PENDING
            cleanedImageName = null
        }

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe false
        plan.runInpaint shouldBe true
        plan.runTranslation shouldBe true
        plan.runRender shouldBe true
    }

    @Test
    fun `force reuses valid ocr evidence while cleaned image is metadata-only`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected).apply {
            inpaintStatus = StageStatus.READY
            cleanedImageName = null
        }

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe false
        plan.runInpaint shouldBe true
    }

    @Test
    fun `force still reuses ocr and inpaint when both evidence sets are valid`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected).apply {
            inpaintStatus = StageStatus.READY
            cleanedImageName = "page-1.cleaned.jpg"
        }

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe false
        plan.runInpaint shouldBe false
        plan.runTranslation shouldBe true
        plan.runRender shouldBe true
    }

    // ------------------------------------------------------------------
    // (b) R012: force + evidence mismatch ⇒ no reuse
    // ------------------------------------------------------------------

    @Test
    fun `force reruns ocr when the ocr fingerprint mismatches`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected).apply {
            inpaintStatus = StageStatus.PENDING
            cleanedImageName = null
        }

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected.copy(ocr = "ocr-new"),
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe true
        plan.runInpaint shouldBe true
    }

    @Test
    fun `force reruns ocr when the detection fingerprint mismatches`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected)

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected.copy(detection = "detection-new"),
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe true
        plan.runInpaint shouldBe true
    }

    @Test
    fun `force reruns ocr when the source fingerprint mismatches`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected)

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
            sourceFingerprint = "source-new",
        )

        plan.runOcr shouldBe true
        plan.runInpaint shouldBe true
    }

    @Test
    fun `force reruns ocr for a legacy snapshot when provenance is required`() {
        val expected = fingerprints().copy(provenanceRequired = true)
        val page = ocrReadyPage(expected).apply {
            detectionFingerprint = null
            ocrFingerprint = null
        }

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe true
    }

    // ------------------------------------------------------------------
    // (d) missing expectations ⇒ pass-through unchanged
    // ------------------------------------------------------------------

    @Test
    fun `force without supplied expectations keeps status-only ocr reuse`() {
        val page = ocrReadyPage(fingerprints()).apply {
            inpaintStatus = StageStatus.PENDING
            cleanedImageName = null
        }

        val plan = PageWorkPlanner.plan(page, force = true)

        plan.runOcr shouldBe false
        plan.runInpaint shouldBe true
        plan.runTranslation shouldBe true
        plan.runRender shouldBe true
    }

    @Test
    fun `force reuses ocr for a legacy snapshot when provenance is not required`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected).apply {
            detectionFingerprint = null
            ocrFingerprint = null
            sourceFingerprint = null
        }

        // No current source hash observable (the batch pass-through case):
        // config expectations fall through for provenance-less legacy pages.
        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
        )

        plan.runOcr shouldBe false
    }

    @Test
    fun `force reruns ocr for a legacy snapshot when a current source hash is known`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected).apply {
            sourceFingerprint = null
        }

        // Mirrors the batch UNKNOWN_PROVENANCE rule: a known current source
        // hash with no recorded source provenance does not reuse.
        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe true
    }

    @Test
    fun `null page force plan reruns everything`() {
        val plan = PageWorkPlanner.plan(null, force = true)

        plan.runOcr shouldBe true
        plan.runTranslation shouldBe true
        plan.runInpaint shouldBe true
        plan.runRender shouldBe true
    }

    // ------------------------------------------------------------------
    // Unchanged force behaviors that must not regress
    // ------------------------------------------------------------------

    @Test
    fun `force with stale ocr reruns ocr and inpaint even when inpaint is ready`() {
        val expected = fingerprints()
        val page = ocrReadyPage(expected).apply {
            ocrStatus = StageStatus.PENDING
            blocks.clear()
            inpaintStatus = StageStatus.READY
            cleanedImageName = "page-1.cleaned.jpg"
        }

        val plan = PageWorkPlanner.plan(
            page,
            force = true,
            expectedFingerprints = expected,
            sourceFingerprint = SOURCE_HASH,
        )

        plan.runOcr shouldBe true
        plan.runInpaint shouldBe true
    }

    // ------------------------------------------------------------------
    // (c) non-force characterization: identical decisions before/after R012
    // ------------------------------------------------------------------

    @Test
    fun `non-force plan of a fresh page reruns detection and waits downstream`() {
        val page = PageTranslation(sourceFileName = KEY)

        val plan = PageWorkPlanner.plan(page, force = false)

        plan.runOcr shouldBe false
        plan.runTranslation shouldBe false
        plan.runInpaint shouldBe false
        plan.runRender shouldBe false
        plan.stage(BatchStage.DETECTION).decision shouldBe StageDecision.RUN
        plan.stage(BatchStage.OCR).decision shouldBe StageDecision.WAIT_FOR_DEPENDENCY
        plan.displayReady shouldBe false
    }

    @Test
    fun `non-force plan of an ocr-ready page reuses ocr and runs translation`() {
        val page = ocrReadyPage(fingerprints())

        val plan = PageWorkPlanner.plan(page, force = false)

        plan.runOcr shouldBe false
        plan.runTranslation shouldBe true
        plan.runInpaint shouldBe true
        plan.runRender shouldBe false
        plan.displayReady shouldBe false
    }

    @Test
    fun `non-force plan of a complete page reuses every stage`() {
        val page = completePage(fingerprints())

        val plan = PageWorkPlanner.plan(page, force = false)

        plan.runOcr shouldBe false
        plan.runTranslation shouldBe false
        plan.runInpaint shouldBe false
        plan.runRender shouldBe false
        plan.displayReady shouldBe true
    }

    @Test
    fun `non-force plan stays an exact delegation into planPage`() {
        val pages = listOf(
            PageTranslation(sourceFileName = KEY),
            ocrReadyPage(fingerprints()),
            ocrReadyPage(fingerprints()).apply {
                inpaintStatus = StageStatus.PENDING
                cleanedImageName = null
            },
            completePage(fingerprints()),
            completePage(fingerprints()).apply { translationStatus = StageStatus.PENDING },
        )

        pages.forEach { page ->
            val legacy = PageWorkPlanner.plan(page, force = false)
            val batch = PageWorkPlanner.planPage(
                BatchPlannerInput(pageKey = page.sourceFileName.orEmpty(), page = page),
            )
            legacy shouldBe PageWorkPlan(
                runOcr = batch.shouldRun(BatchStage.OCR),
                runTranslation = batch.shouldRun(BatchStage.TRANSLATION),
                runInpaint = batch.shouldRun(BatchStage.INPAINT),
                runRender = batch.shouldRun(BatchStage.LAYOUT),
                stageDecisions = batch.stages,
                displayReady = batch.displayReady,
            )
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun PageWorkPlan.stage(stage: BatchStage): StageWorkDecision =
        stageDecisions.first { it.stage == stage }

    private fun BatchPageWorkPlan.shouldRun(stage: BatchStage): Boolean =
        stages.first { it.stage == stage }.decision in setOf(
            StageDecision.RUN,
            StageDecision.FAILED,
            StageDecision.FAILED_RETRYABLE,
        )

    private fun fingerprints() = BatchExpectedFingerprints(
        detection = "detection",
        ocr = "ocr",
        inpaint = "inpaint",
        translation = "translation",
        layout = "layout",
    )

    /** OCR/detection committed with current evidence; downstream untouched. */
    private fun ocrReadyPage(expected: BatchExpectedFingerprints) = PageTranslation(
        sourceFileName = KEY,
        ocrStatus = StageStatus.READY,
        detectionFingerprint = expected.detection,
        ocrFingerprint = expected.ocr,
        sourceFingerprint = SOURCE_HASH,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "",
                width = 10f,
                height = 10f,
                x = 1f,
                y = 1f,
                symHeight = 10f,
                symWidth = 10f,
                angle = 0f,
            ),
        ),
    )

    private fun completePage(expected: BatchExpectedFingerprints) = PageTranslation(
        sourceFileName = KEY,
        cleanedImageName = "$KEY.cleaned.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
        detectionFingerprint = expected.detection,
        ocrFingerprint = expected.ocr,
        inpaintFingerprint = expected.inpaint,
        translationFingerprint = expected.translation,
        layoutFingerprint = expected.layout,
        translationOrigin = ArtifactOrigin.BATCH.name,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "translated",
                width = 10f,
                height = 10f,
                x = 1f,
                y = 1f,
                symHeight = 10f,
                symWidth = 10f,
                angle = 0f,
            ),
        ),
    )

    private companion object {
        const val KEY = "page-1"
        const val SOURCE_HASH = "source-current"
    }
}
