package eu.kanade.translation.scheduling

import eu.kanade.translation.diagnostics.TelemetryTrace
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutoSmoothnessTelemetryTest {

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
    fun `tracks wait time if page is not ready on arrival`() {
        val arrivalTime = 1000L
        val readyTime = 6500L
        val waitMs = (readyTime - arrivalTime).toDouble()

        TelemetryTrace.log(
            "auto", "arrival_wait_recorded",
            "pageIndex" to 3,
            "pageKey" to "003.jpg",
            "waitMs" to waitMs,
            "hadToWait" to (waitMs > 50.0),
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=auto"))
        assertTrue(line.contains("event=arrival_wait_recorded"))
        assertTrue(line.contains("pageIndex=3"))
        assertTrue(line.contains("pageKey=003.jpg"))
        assertTrue(line.contains("waitMs=5500.0"))
        assertTrue(line.contains("hadToWait=true"))
    }

    @Test
    fun `tracks zero wait time if page was already ready on arrival`() {
        val arrivalTime = 2000L
        val readyTime = 2000L
        val waitMs = (readyTime - arrivalTime).toDouble()

        AutoSmoothnessTelemetry.logArrivalWaitRecorded(
            pageIndex = 2,
            pageKey = "002.jpg",
            waitMs = waitMs,
            hadToWait = (waitMs > 50.0),
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=auto"))
        assertTrue(line.contains("event=arrival_wait_recorded"))
        assertTrue(line.contains("pageIndex=2"))
        assertTrue(line.contains("pageKey=002.jpg"))
        assertTrue(line.contains("waitMs=0.0"))
        assertTrue(line.contains("hadToWait=false"))
    }

    @Test
    fun `user_arrival logs all required fields`() {
        AutoSmoothnessTelemetry.logUserArrival(
            pageIndex = 5,
            pageKey = "chapter1_005.jpg",
            isReady = false,
            arrivalEpochMs = 1728400000000L,
            autoEnabled = true,
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=auto"))
        assertTrue(line.contains("event=user_arrival"))
        assertTrue(line.contains("pageIndex=5"))
        assertTrue(line.contains("pageKey=chapter1_005.jpg"))
        assertTrue(line.contains("isReady=false"))
        assertTrue(line.contains("arrivalEpochMs=1728400000000"))
        assertTrue(line.contains("autoEnabled=true"))
    }

    @Test
    fun `debounce_lifecycle logs start, cancel, and fire actions with target page and queued duration`() {
        AutoSmoothnessTelemetry.logDebounceLifecycle(
            action = "start",
            targetPage = 4,
            queuedDurationMs = 0.0,
        )
        AutoSmoothnessTelemetry.logDebounceLifecycle(
            action = "cancel",
            targetPage = 4,
            queuedDurationMs = 45.5,
        )
        AutoSmoothnessTelemetry.logDebounceLifecycle(
            action = "fire",
            targetPage = 5,
            queuedDurationMs = 150.2,
        )

        assertEquals(3, capturedLines.size)

        val startLine = capturedLines[0]
        assertTrue(startLine.contains("domain=auto"))
        assertTrue(startLine.contains("event=debounce_lifecycle"))
        assertTrue(startLine.contains("action=start"))
        assertTrue(startLine.contains("targetPage=4"))
        assertTrue(startLine.contains("queuedDurationMs=0.0"))

        val cancelLine = capturedLines[1]
        assertTrue(cancelLine.contains("domain=auto"))
        assertTrue(cancelLine.contains("event=debounce_lifecycle"))
        assertTrue(cancelLine.contains("action=cancel"))
        assertTrue(cancelLine.contains("targetPage=4"))
        assertTrue(cancelLine.contains("queuedDurationMs=45.5"))

        val fireLine = capturedLines[2]
        assertTrue(fireLine.contains("domain=auto"))
        assertTrue(fireLine.contains("event=debounce_lifecycle"))
        assertTrue(fireLine.contains("action=fire"))
        assertTrue(fireLine.contains("targetPage=5"))
        assertTrue(fireLine.contains("queuedDurationMs=150.2"))
    }

    @Test
    fun `window_reconcile logs visible ahead desired unservicedPredecessors memoryOk and gen`() {
        AutoSmoothnessTelemetry.logWindowReconcile(
            visible = 4,
            ahead = 3,
            desired = "4,5,6",
            unservicedPredecessors = "2,3",
            memoryOk = true,
            gen = 42L,
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=auto"))
        assertTrue(line.contains("event=window_reconcile"))
        assertTrue(line.contains("visible=4"))
        assertTrue(line.contains("ahead=3"))
        assertTrue(line.contains("desired=4,5,6"))
        assertTrue(line.contains("unservicedPredecessors=2,3"))
        assertTrue(line.contains("memoryOk=true"))
        assertTrue(line.contains("gen=42"))
    }

    @Test
    fun `auto_admit logs idx pageKey foreground and gen`() {
        AutoSmoothnessTelemetry.logAutoAdmit(
            idx = 4,
            pageKey = "page_004.jpg",
            foreground = true,
            gen = 42L,
        )

        assertEquals(1, capturedLines.size)
        val line = capturedLines.first()
        assertTrue(line.contains("domain=auto"))
        assertTrue(line.contains("event=auto_admit"))
        assertTrue(line.contains("idx=4"))
        assertTrue(line.contains("pageKey=page_004.jpg"))
        assertTrue(line.contains("foreground=true"))
        assertTrue(line.contains("gen=42"))
    }
}
