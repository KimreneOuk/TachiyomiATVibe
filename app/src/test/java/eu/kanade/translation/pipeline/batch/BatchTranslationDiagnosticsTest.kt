package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.diagnostics.TranslationIdentityKeys
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceIdGenerator
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * T922 Phase 4: the legacy TachiyomiAT.Batch timing/status surface is now a
 * compatibility facade over `translation_trace_v1` (tag TachiyomiAT.Translation).
 * These tests verify the migrated behavior contract:
 *
 * 1. delegated events carry ONLY bounded tokens (raw page keys, fingerprints,
 *    and error class names never survive);
 * 2. identity resolves to the active batch schedule when no page run is
 *    installed, and delegated calls fail open (emit nothing) without one;
 * 3. the one retained legacy format — JVM heap [BatchTranslationDiagnostics.memorySnapshot]
 *    on the legacy tag — is unchanged;
 * 4. envelope lifecycle events keep bounded correlation fields (opaque
 *    envelope token, attempt counter, typed reason).
 */
class BatchTranslationDiagnosticsTest {

    private val capturedLines = mutableListOf<String>()
    private var oldSink: TranslationTraceSink? = null
    private var oldGate: Boolean? = null
    private var oldIds: TranslationTraceIdGenerator? = null
    private var oldKeys: TranslationIdentityKeys? = null

    @AfterEach
    fun tearDown() {
        restoreCapture()
    }

    private fun setUpTraceCapture() {
        oldSink = TranslationPipelineDiagnostics.sink
        oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        oldIds = TranslationPipelineDiagnostics.idGenerator
        oldKeys = TranslationPipelineDiagnostics.identityKeys
        capturedLines.clear()
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> capturedLines.add(line) }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "t922d")
        TranslationPipelineDiagnostics.identityKeys = TranslationIdentityKeys(ByteArray(32) { 17 })
    }

    private fun restoreCapture() {
        oldSink?.let { TranslationPipelineDiagnostics.sink = it }
        oldGate?.let { TranslationPipelineDiagnostics.detailedTracingEnabled = it }
        oldIds?.let { TranslationPipelineDiagnostics.idGenerator = it }
        oldKeys?.let { TranslationPipelineDiagnostics.identityKeys = it }
        oldSink = null
        oldGate = null
        oldIds = null
        oldKeys = null
    }

    private fun lines(event: String): List<String> = capturedLines.filter { it.contains("event=$event ") }

    private fun field(line: String, key: String): String? =
        line.split(" ").firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")

    @Test
    fun `delegated diagnostic events carry only bounded tokens and no raw content`() {
        setUpTraceCapture()
        val malicious = "dialogue=<script>prompt=PROFILE_RELATIONSHIP mature scene"
        val schedule = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.BATCH,
            origin = TranslationTraceMode.BATCH,
        )
        BatchTranslationDiagnostics.noteActiveSchedule(schedule)
        try {
            // Legacy *Message(...) test inputs, routed through the facade.
            BatchTranslationDiagnostics.stageDecision(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = malicious,
                decision = BatchDiagnosticDecision.REUSE,
                reason = BatchDiagnosticReason.CACHE_HIT,
                fingerprint = malicious,
                itemCount = 2,
            )
            BatchTranslationDiagnostics.timing(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = malicious,
                durationMs = 12,
                itemCount = 2,
                success = true,
            )
            BatchTranslationDiagnostics.reuse(
                stage = BatchDiagnosticStage.CONTEXT,
                pageKey = malicious,
                reason = BatchDiagnosticReason.CANDIDATE_ACTIVE,
                fingerprint = malicious,
            )
            BatchTranslationDiagnostics.failure(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = malicious,
                errorClass = malicious,
                retryCount = 1,
                reason = BatchDiagnosticReason.TERMINAL_FAILURE,
            )
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = BatchEnvelopeLifecycle.FAILED,
                pageKeys = listOf(malicious),
                attempt = 2,
                expectedItemCount = 4,
                receivedItemCount = 0,
                reason = BatchDiagnosticReason.TERMINAL_FAILURE,
            )
        } finally {
            BatchTranslationDiagnostics.noteActiveSchedule(null)
            schedule.end(TranslationTraceOutcome.SUCCESS)
        }

        // Every emitted schema line is free of the raw page/fingerprint/error
        // content — the legacy privacy contract, preserved under the new tag.
        capturedLines.forEach { line ->
            line shouldNotContain malicious
            line shouldNotContain "dialogue="
            line shouldNotContain "PROFILE_RELATIONSHIP"
        }

        // Facade identity fell back to the active schedule (no fabricated sid);
        // unknown error classes collapse to the fixed `unknown` token.
        val failureEnd = lines("stage_end").filter { field(it, "errorType") == "unknown" }
        failureEnd.size shouldBe 1
        field(failureEnd.single(), "sid") shouldBe schedule.sid
        field(failureEnd.single(), "outcome") shouldBe "failure"
        capturedLines.none { it.contains("IllegalState") || it.contains("Exception") } shouldBe true
    }

    @Test
    fun `facade fails open with no active schedule and emits nothing`() {
        setUpTraceCapture()
        BatchTranslationDiagnostics.timing(
            stage = BatchDiagnosticStage.TRANSLATION,
            pageKey = "p0",
            durationMs = 5,
        )
        BatchTranslationDiagnostics.stageDecision(
            stage = BatchDiagnosticStage.TRANSLATION,
            pageKey = "p0",
            decision = BatchDiagnosticDecision.EXECUTE,
            reason = BatchDiagnosticReason.SUCCESS,
        )
        capturedLines.size shouldBe 0
    }

    @Test
    fun `legacy memory snapshot format is unchanged`() {
        val message = BatchTranslationDiagnostics.memoryMessage(
            stage = "pass1",
            usedBytes = -1,
            maxBytes = 1024,
            queueDepth = -5,
            activePages = 3,
        )

        message shouldBe "event=memory stage=pass1 usedBytes=0 maxBytes=1024 queueDepth=0 activePages=3"
    }

    @Test
    fun `envelope lifecycle event has bounded correlation fields`() {
        setUpTraceCapture()
        val schedule = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.BATCH,
            origin = TranslationTraceMode.BATCH,
        )
        BatchTranslationDiagnostics.noteActiveSchedule(schedule)
        try {
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = BatchEnvelopeLifecycle.RETRY,
                pageKeys = listOf("001.jpg", "002.jpg"),
                attempt = 3,
                expectedItemCount = 8,
                receivedItemCount = null,
                reason = BatchDiagnosticReason.TRANSIENT_FAILURE,
            )
        } finally {
            BatchTranslationDiagnostics.noteActiveSchedule(null)
            schedule.end(TranslationTraceOutcome.SUCCESS)
        }

        val states = lines("schedule_state")
        states.size shouldBe 1
        val line = states.single()
        field(line, "state") shouldBe "deferred"
        field(line, "reason") shouldBe "envelope_retry"
        field(line, "attempt") shouldBe "3"
        field(line, "envelope") shouldBe BatchTranslationDiagnostics.traceEnvelopeToken(listOf("001.jpg", "002.jpg"))
        field(line, "sid") shouldBe schedule.sid
    }
}
