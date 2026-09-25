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
            errorCount = 9,
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

    @Test
    fun `false final rename and null directory list never render success`() {
        val records = captureRecords {
            BatchDownloadDiagnostics.finalization(
                chapterId = 42L,
                generation = 7L,
                stage = BatchDownloadStage.RENAME,
                result = BatchDownloadResult.FALSE,
            )
            BatchDownloadDiagnostics.pathOperation(
                chapterId = 42L,
                generation = 7L,
                pageIndex = 11,
                pageNumber = 12,
                stage = BatchDownloadStage.DIRECTORY_LIST,
                result = BatchDownloadResult.NULL,
            )
        }

        records[0].contains("event=finalization") shouldBe true
        records[0].contains("stage=rename result=false") shouldBe true
        records[0].contains("result=success") shouldBe false
        records[1].contains("event=path_operation") shouldBe true
        records[1].contains("page_index=11 page_number=12 stage=directory_list result=null") shouldBe true
    }

    @Test
    fun `late request attachment is related to the next bounded page event`() {
        var generation: Long? = null
        val records = captureRecords {
            val context = BatchDownloadTraceContext(chapterId = 42L) { generation }
            context.generation(BatchDownloadTraceBoundary.CHAPTER_START) shouldBe null
            generation = 9L
            context.generation(BatchDownloadTraceBoundary.PAGE) shouldBe 9L
        }

        records.shouldContainExactly(
            "schema=1 event=attachment chapter_id=42 generation=9 from_generation=none " +
                "to_generation=9 boundary=page",
        )
    }

    @Test
    fun `false page publication produces bounded failure validation terminal sequence`() {
        val records = captureRecords {
            BatchDownloadDiagnostics.pageAttemptFailed(
                chapterId = 42L,
                generation = 7L,
                pageIndex = 11,
                pageNumber = 12,
                attempt = 1,
                stage = BatchDownloadStage.RENAME_TEMP,
                cause = BatchDownloadCause.STORAGE,
                error = null,
            )
            BatchDownloadDiagnostics.pageTerminalFailed(
                chapterId = 42L,
                generation = 7L,
                pageIndex = 11,
                pageNumber = 12,
                stage = BatchDownloadStage.RENAME_TEMP,
                cause = BatchDownloadCause.STORAGE,
                error = null,
            )
            BatchDownloadDiagnostics.validation(
                chapterId = 42L,
                generation = 7L,
                expected = 12,
                ready = 12,
                onDisk = 11,
                errorCount = 0,
                errorPages = emptyList(),
            )
            BatchDownloadDiagnostics.downloadTerminal(
                chapterId = 42L,
                generation = 7L,
                state = BatchDownloadTerminalState.ERROR,
                cause = BatchDownloadCause.STORAGE,
                expected = 12,
                ready = 12,
                onDisk = 11,
            )
        }

        records.map { record -> record.substringAfter("event=").substringBefore(' ') }
            .shouldContainExactly(
                "page_attempt_failed",
                "page_terminal_failed",
                "validation",
                "download_terminal",
            )
        records[0].contains("stage=rename_temp cause=storage error_class=none") shouldBe true
    }

    private fun captureRecords(block: () -> Unit): List<String> = synchronized(BatchDownloadDiagnostics) {
        val records = mutableListOf<String>()
        BatchDownloadDiagnostics.recordObserver = records::add
        try {
            block()
        } finally {
            BatchDownloadDiagnostics.recordObserver = null
        }
        records
    }
}
