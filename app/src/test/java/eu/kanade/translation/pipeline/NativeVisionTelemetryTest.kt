package eu.kanade.translation.pipeline

import eu.kanade.translation.diagnostics.TelemetryTrace
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NativeVisionTelemetryTest {

    private val capturedLines = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        capturedLines.clear()
        TelemetryTrace.setTestSink { capturedLines.add(it) }
        TelemetryTrace.enabled = true
    }

    @AfterEach
    fun tearDown() {
        TelemetryTrace.setTestSink(null)
    }

    @Test
    fun `logBudgetSummary logs all required fields and budgetMet true when within budget`() {
        NativeVisionTelemetry.logBudgetSummary(
            pageKey = "page_001.jpg",
            targetMs = 2000,
            detMs = 210.5,
            segMs = 15.2,
            ocrMs = 450.3,
            inpaintMs = 620.0,
            leafCount = 12,
            boxCount = 8,
            provider = "NNAPI",
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=native_vision"))
        assertTrue(line.contains("event=budget_summary"))
        assertTrue(line.contains("pageKey=page_001.jpg"))
        assertTrue(line.contains("targetMs=2000"))
        assertTrue(line.contains("totalNativeMs=1296.00"))
        assertTrue(line.contains("budgetMet=true"))
        assertTrue(line.contains("detMs=210.50"))
        assertTrue(line.contains("segMs=15.20"))
        assertTrue(line.contains("ocrMs=450.30"))
        assertTrue(line.contains("inpaintMs=620.00"))
        assertTrue(line.contains("leafCount=12"))
        assertTrue(line.contains("boxCount=8"))
        assertTrue(line.contains("provider=NNAPI"))
    }

    @Test
    fun `logBudgetSummary logs budgetMet false when exceeding target budget`() {
        NativeVisionTelemetry.logBudgetSummary(
            pageKey = "page_002.jpg",
            targetMs = 2000,
            detMs = 500.0,
            segMs = 100.0,
            ocrMs = 900.0,
            inpaintMs = 800.0,
            leafCount = 30,
            boxCount = 25,
            provider = "CPU",
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=native_vision"))
        assertTrue(line.contains("event=budget_summary"))
        assertTrue(line.contains("pageKey=page_002.jpg"))
        assertTrue(line.contains("targetMs=2000"))
        assertTrue(line.contains("totalNativeMs=2300.00"))
        assertTrue(line.contains("budgetMet=false"))
        assertTrue(line.contains("provider=CPU"))
    }

    @Test
    fun `logCropSummary logs leaf crop metrics`() {
        NativeVisionTelemetry.logCropSummary(
            pageKey = "page_003.jpg",
            leafCount = 14,
            batchSize = 8,
            avgInferMs = 32.45,
            provider = "NNAPI",
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=ocr"))
        assertTrue(line.contains("event=crop_summary"))
        assertTrue(line.contains("pageKey=page_003.jpg"))
        assertTrue(line.contains("leafCount=14"))
        assertTrue(line.contains("batchSize=8"))
        assertTrue(line.contains("avgInferMs=32.45"))
        assertTrue(line.contains("provider=NNAPI"))
    }

    @Test
    fun `logRenderTailBreakdown logs permit wait color estimate and commit breakdown`() {
        NativeVisionTelemetry.logRenderTailBreakdown(
            pageKey = "page_004.jpg",
            permitWaitMs = 120.34,
            colorEstimateMs = 45.67,
            commitMs = 80.12,
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=render_tail"))
        assertTrue(line.contains("event=render_tail_breakdown"))
        assertTrue(line.contains("pageKey=page_004.jpg"))
        assertTrue(line.contains("permitWaitMs=120.34"))
        assertTrue(line.contains("colorEstimateMs=45.67"))
        assertTrue(line.contains("commitMs=80.12"))
        assertTrue(line.contains("totalTailMs=246.13"))
    }

    @Test
    fun `logTapToDispatch logs delay from user tap to pipeline launch`() {
        NativeVisionTelemetry.logTapToDispatch(
            pageKey = "page_005.jpg",
            delayMs = 15.60,
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=manual"))
        assertTrue(line.contains("event=tap_to_dispatch"))
        assertTrue(line.contains("pageKey=page_005.jpg"))
        assertTrue(line.contains("delayMs=15.60"))
    }

    @Test
    fun `logDisplayAttach logs reader stream and visibility flags`() {
        NativeVisionTelemetry.logDisplayAttach(
            pageKey = "page_006.jpg",
            streamAttached = true,
            showTranslatedImage = true,
            displayImageName = false,
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=manual"))
        assertTrue(line.contains("event=display_attach"))
        assertTrue(line.contains("pageKey=page_006.jpg"))
        assertTrue(line.contains("streamAttached=true"))
        assertTrue(line.contains("showTranslatedImage=true"))
        assertTrue(line.contains("displayImageName=false"))
    }
}
