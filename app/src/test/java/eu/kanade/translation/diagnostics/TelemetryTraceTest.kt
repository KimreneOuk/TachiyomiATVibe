package eu.kanade.translation.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryTraceTest {

    @Test
    fun `formatEvent formats key-values with wallclock and monotonic tags`() {
        val line = TelemetryTrace.formatEvent(
            domain = "pipeline",
            event = "test_event",
            wallClockMs = 1728400000000L,
            monoNs = 123456789L,
            pairs = arrayOf("pageKey" to "p1.jpg", "durationMs" to 42),
        )
        assertTrue(line.startsWith("ts=2024-10-08T"))
        assertTrue(line.contains("monoMs=123"))
        assertTrue(line.contains("domain=pipeline"))
        assertTrue(line.contains("event=test_event"))
        assertTrue(line.contains("pageKey=p1.jpg"))
        assertTrue(line.contains("durationMs=42"))
    }

    @Test
    fun `log fails open when an argument throws during evaluation`() {
        var sinkCalled = false
        var capturedLine: String? = null
        TelemetryTrace.setTestSink {
            sinkCalled = true
            capturedLine = it
        }
        try {
            TelemetryTrace.log(
                "test",
                "throw_event",
                "bad" to object {
                    override fun toString(): String = throw RuntimeException("Simulated crash")
                },
            )
            assertTrue(sinkCalled)
            assertTrue(capturedLine?.contains("bad=<error:RuntimeException>") == true)
        } finally {
            TelemetryTrace.setTestSink(null)
        }
    }

    @Test
    fun `span records duration and initial pairs`() {
        val captured = mutableListOf<String>()
        TelemetryTrace.setTestSink { captured.add(it) }
        try {
            val result = TelemetryTrace.span("vision", "detect", "page" to 1) {
                42
            }
            assertEquals(42, result)
            assertEquals(2, captured.size)
            assertTrue(captured[0].contains("domain=vision"))
            assertTrue(captured[0].contains("event=detect_start"))
            assertTrue(captured[0].contains("page=1"))
            assertTrue(captured[1].contains("domain=vision"))
            assertTrue(captured[1].contains("event=detect_end"))
            assertTrue(captured[1].contains("page=1"))
            assertTrue(captured[1].contains("durationMs="))
        } finally {
            TelemetryTrace.setTestSink(null)
        }
    }

    @Test
    fun `span without initial pairs works`() {
        val captured = mutableListOf<String>()
        TelemetryTrace.setTestSink { captured.add(it) }
        try {
            val result = TelemetryTrace.span("pipeline", "step") {
                "ok"
            }
            assertEquals("ok", result)
            assertEquals(2, captured.size)
            assertTrue(captured[0].contains("event=step_start"))
            assertTrue(captured[1].contains("event=step_end"))
            assertTrue(captured[1].contains("durationMs="))
        } finally {
            TelemetryTrace.setTestSink(null)
        }
    }

    @Test
    fun `log does not emit when disabled`() {
        var sinkCalled = false
        TelemetryTrace.setTestSink { sinkCalled = true }
        TelemetryTrace.enabled = false
        try {
            TelemetryTrace.log("pipeline", "disabled_event")
            assertFalse(sinkCalled)
        } finally {
            TelemetryTrace.enabled = true
            TelemetryTrace.setTestSink(null)
        }
    }
}
