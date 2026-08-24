package eu.kanade.translation.batch

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class BatchTranslationDiagnosticsTest {

    @Test
    fun `diagnostic messages hash page and fingerprint inputs`() {
        val malicious = "dialogue=<script>prompt=PROFILE_RELATIONSHIP mature scene"

        val messages = listOf(
            BatchTranslationDiagnostics.stageDecisionMessage(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = malicious,
                decision = BatchDiagnosticDecision.REUSE,
                reason = BatchDiagnosticReason.CACHE_HIT,
                fingerprint = malicious,
                itemCount = 2,
            ),
            BatchTranslationDiagnostics.timingMessage(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = malicious,
                durationMs = 12,
                itemCount = 2,
                success = true,
            ),
            BatchTranslationDiagnostics.reuseMessage(
                stage = BatchDiagnosticStage.CONTEXT,
                pageKey = malicious,
                reason = BatchDiagnosticReason.CANDIDATE_ACTIVE,
                fingerprint = malicious,
            ),
            BatchTranslationDiagnostics.failureMessage(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = malicious,
                errorClass = malicious,
                retryCount = 1,
                reason = BatchDiagnosticReason.TERMINAL_FAILURE,
            ),
            BatchTranslationDiagnostics.envelopeLifecycleMessage(
                phase = BatchEnvelopeLifecycle.FAILED,
                pageKeys = listOf(malicious),
                attempt = 2,
                expectedItemCount = 4,
                receivedItemCount = 0,
                reason = BatchDiagnosticReason.TERMINAL_FAILURE,
            ),
        )

        messages.forEach { message ->
            message shouldNotContain malicious
            message shouldNotContain "dialogue="
            message shouldNotContain "PROFILE_RELATIONSHIP"
        }
    }

    @Test
    fun `diagnostic message schema contains only bounded counters and reason codes`() {
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
    fun `envelope lifecycle message has stable bounded fields`() {
        BatchTranslationDiagnostics.envelopeLifecycleMessage(
            phase = BatchEnvelopeLifecycle.RETRY,
            pageKeys = listOf("001.jpg", "002.jpg"),
            attempt = 3,
            expectedItemCount = 8,
            receivedItemCount = null,
            reason = BatchDiagnosticReason.TRANSIENT_FAILURE,
        ) shouldBe "event=envelope_lifecycle phase=retry envelope=${
            BatchTranslationDiagnostics.envelopeId(listOf("001.jpg", "002.jpg"))
        } pages=2 attempt=3 expectedItems=8 receivedItems=none reason=transient_failure"
    }
}
