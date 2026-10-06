package eu.kanade.translation.diagnostics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class TranslationDebugMeasurementTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `file sink preserves queue order and sanitizes embedded line breaks`() {
        val sink = TranslationTraceFileSink(temporaryDirectory.toFile())
        try {
            sink.log(3, "event=run_start rid=r1")
            sink.writeSessionMarker("event=measurement_session_start session=s1")
            sink.log(3, "event=stage_end stage=ocr\nforged=true")
            sink.flushForTesting()

            File(temporaryDirectory.toFile(), "translation-trace.log").readLines() shouldBe listOf(
                "event=run_start rid=r1",
                "event=measurement_session_start session=s1",
                "event=stage_end stage=ocr forged=true",
            )
        } finally {
            sink.close()
        }
    }

    @Test
    fun `session restart closes previous session and completion is idempotent`() {
        val lifecycle = TranslationMeasurementSessionLifecycle<String>()
        val events = mutableListOf<String>()

        lifecycle.start("first") { events += "end:$it:restarted" }
        events += "start:first"
        lifecycle.start("second") { events += "end:$it:restarted" }
        events += "start:second"
        lifecycle.end { events += "end:$it:complete" } shouldBe true
        lifecycle.end { events += "unexpected:$it" } shouldBe false

        events shouldBe listOf(
            "start:first",
            "end:first:restarted",
            "start:second",
            "end:second:complete",
        )
    }
}
