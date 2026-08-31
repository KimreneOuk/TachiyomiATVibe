package eu.kanade.translation.diagnostics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchDownloadDiagnosticsTest {

    @Test
    fun `validation record has stable one-line schema and caps page indexes`() {
        val record = BatchDownloadDiagnostics.validationRecord(
            chapterId = 42L,
            generation = 7L,
            expected = 12,
            ready = 10,
            onDisk = 10,
            errorPages = (2..10).toList(),
        )

        record shouldBe
            "schema=1 event=validation chapter_id=42 generation=7 expected=12 ready=10 " +
            "on_disk=10 error_count=9 error_pages=2,3,4,5,6,7,8,9 error_pages_truncated=true"
        record.lineSequence().toList().shouldContainExactly(record)
    }

    @Test
    fun `throwable messages and urls cannot enter error class field`() {
        val secret = "https://example.invalid/page?token=secret"
        val error = IllegalStateException("failed at $secret")

        BatchDownloadDiagnostics.errorClass(error) shouldBe "IllegalStateException"
        BatchDownloadDiagnostics.errorClass(error).contains(secret) shouldBe false
        BatchDownloadDiagnostics.safeToken(secret) shouldBe "invalid"
    }

    @Test
    fun `stage and cause tokens are controlled enums`() {
        BatchDownloadStage.entries.map { it.name.lowercase() }.all {
            BatchDownloadDiagnostics.safeToken(it) == it
        } shouldBe true
        BatchDownloadCause.entries.map { it.name.lowercase() }.all {
            BatchDownloadDiagnostics.safeToken(it) == it
        } shouldBe true
    }
}
