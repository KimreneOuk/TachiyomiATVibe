package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.util.ShortHash
import logcat.LogPriority
import logcat.logcat

/**
 * Privacy-safe observability for the ordered batch path.
 *
 * Event fields deliberately accept only opaque identifiers, fingerprints,
 * counts, timings, and reason codes. Source/OCR text, translations, prompts,
 * scene cards, and profile labels never cross this boundary.
 */
object BatchTranslationDiagnostics {

    private const val TAG = "TachiyomiAT.Batch"

    fun stageDecision(
        stage: BatchDiagnosticStage,
        pageKey: String,
        decision: BatchDiagnosticDecision,
        reason: BatchDiagnosticReason,
        fingerprint: String? = null,
        itemCount: Int = 0,
    ) {
        logcat(tag = TAG, priority = LogPriority.INFO) {
            stageDecisionMessage(stage, pageKey, decision, reason, fingerprint, itemCount)
        }
    }

    fun timing(
        stage: BatchDiagnosticStage,
        pageKey: String,
        durationMs: Long,
        itemCount: Int = 0,
        success: Boolean = true,
    ) {
        logcat(tag = TAG, priority = if (success) LogPriority.INFO else LogPriority.WARN) {
            timingMessage(stage, pageKey, durationMs, itemCount, success)
        }
    }

    fun reuse(
        stage: BatchDiagnosticStage,
        pageKey: String,
        reason: BatchDiagnosticReason,
        fingerprint: String? = null,
    ) {
        logcat(tag = TAG, priority = LogPriority.INFO) {
            reuseMessage(stage, pageKey, reason, fingerprint)
        }
    }

    fun failure(
        stage: BatchDiagnosticStage,
        pageKey: String,
        errorClass: String,
        retryCount: Int = 0,
        reason: BatchDiagnosticReason = BatchDiagnosticReason.STAGE_FAILURE,
    ) {
        logcat(tag = TAG, priority = LogPriority.ERROR) {
            failureMessage(stage, pageKey, errorClass, retryCount, reason)
        }
    }

    fun memorySnapshot(
        stage: String,
        queueDepth: Int,
        activePages: Int,
    ) {
        val runtime = Runtime.getRuntime()
        val usedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        logcat(tag = TAG, priority = LogPriority.INFO) {
            memoryMessage(stage, usedBytes, runtime.maxMemory(), queueDepth, activePages)
        }
    }

    /** Emits a bounded lifecycle event for one provider envelope. */
    fun envelopeLifecycle(
        phase: BatchEnvelopeLifecycle,
        pageKeys: Collection<String>,
        attempt: Int = 0,
        expectedItemCount: Int? = null,
        receivedItemCount: Int? = null,
        reason: BatchDiagnosticReason? = null,
    ) {
        val priority = when (phase) {
            BatchEnvelopeLifecycle.FAILED -> LogPriority.ERROR
            BatchEnvelopeLifecycle.RETRY -> LogPriority.WARN
            else -> LogPriority.INFO
        }
        logcat(tag = TAG, priority = priority) {
            envelopeLifecycleMessage(
                phase = phase,
                pageKeys = pageKeys,
                attempt = attempt,
                expectedItemCount = expectedItemCount,
                receivedItemCount = receivedItemCount,
                reason = reason,
            )
        }
    }

    /** Stable opaque id for an envelope; page keys never appear in diagnostics. */
    fun envelopeId(pageKeys: Collection<String>): String = opaque(pageKeys.joinToString("\u0000"))

    internal fun stageDecisionMessage(
        stage: BatchDiagnosticStage,
        pageKey: String,
        decision: BatchDiagnosticDecision,
        reason: BatchDiagnosticReason,
        fingerprint: String?,
        itemCount: Int,
    ): String =
        "event=stage_decision stage=${stage.name.lowercase()} page=${opaque(pageKey)} " +
            "decision=${decision.name.lowercase()} reason=${reason.name.lowercase()} " +
            "fingerprint=${opaque(fingerprint)} items=${itemCount.coerceAtLeast(0)}"

    internal fun timingMessage(
        stage: BatchDiagnosticStage,
        pageKey: String,
        durationMs: Long,
        itemCount: Int,
        success: Boolean,
    ): String =
        "event=stage_timing stage=${stage.name.lowercase()} page=${opaque(pageKey)} " +
            "durationMs=${durationMs.coerceAtLeast(0L)} success=$success " +
            "items=${itemCount.coerceAtLeast(0)}"

    internal fun reuseMessage(
        stage: BatchDiagnosticStage,
        pageKey: String,
        reason: BatchDiagnosticReason,
        fingerprint: String?,
    ): String =
        "event=reuse stage=${stage.name.lowercase()} page=${opaque(pageKey)} " +
            "reason=${reason.name.lowercase()} fingerprint=${opaque(fingerprint)}"

    internal fun failureMessage(
        stage: BatchDiagnosticStage,
        pageKey: String,
        errorClass: String,
        retryCount: Int,
        reason: BatchDiagnosticReason,
    ): String =
        "event=stage_failure stage=${stage.name.lowercase()} page=${opaque(pageKey)} " +
            "reason=${reason.name.lowercase()} error=${safeClass(errorClass)} " +
            "retries=${retryCount.coerceAtLeast(0)}"

    internal fun memoryMessage(
        stage: String,
        usedBytes: Long,
        maxBytes: Long,
        queueDepth: Int,
        activePages: Int,
    ): String =
        "event=memory stage=${safeStage(stage)} usedBytes=${usedBytes.coerceAtLeast(0L)} " +
            "maxBytes=${maxBytes.coerceAtLeast(0L)} queueDepth=${queueDepth.coerceAtLeast(0)} " +
            "activePages=${activePages.coerceAtLeast(0)}"

    internal fun envelopeLifecycleMessage(
        phase: BatchEnvelopeLifecycle,
        pageKeys: Collection<String>,
        attempt: Int,
        expectedItemCount: Int?,
        receivedItemCount: Int?,
        reason: BatchDiagnosticReason?,
    ): String =
        "event=envelope_lifecycle phase=${phase.name.lowercase()} " +
            "envelope=${envelopeId(pageKeys)} pages=${pageKeys.size.coerceAtLeast(0)} " +
            "attempt=${attempt.coerceAtLeast(0)} " +
            "expectedItems=${expectedItemCount?.coerceAtLeast(0) ?: "none"} " +
            "receivedItems=${receivedItemCount?.coerceAtLeast(0) ?: "none"} " +
            "reason=${reason?.name?.lowercase() ?: "none"}"

    private fun opaque(value: String?): String =
        value?.takeIf { it.isNotEmpty() }?.let { "h#${ShortHash.hash(it)}" } ?: "none"

    private fun safeClass(value: String): String = opaque(value)

    private fun safeStage(value: String): String = when (value) {
        "pass1", "pass2", "chapter" -> value
        else -> opaque(value)
    }
}

enum class BatchDiagnosticStage {
    OCR,
    INPAINT,
    TRANSLATION,
    RENDER,
    ARTIFACT,
    CONTEXT,
}

enum class BatchDiagnosticDecision {
    EXECUTE,
    REUSE,
    SKIP,
    RETRY,
    FAIL,
}

enum class BatchDiagnosticReason {
    SUCCESS,
    REFERENCE_READY,
    NO_REFERENCE,
    CACHE_HIT,
    CANDIDATE_ACTIVE,
    STAGE_FAILURE,
    TRANSIENT_FAILURE,
    TERMINAL_FAILURE,
    CANCELLED,
    CORRUPT_ARTIFACT,
    ORPHAN_ARTIFACT,
}

enum class BatchEnvelopeLifecycle {
    ADMITTED,
    PROVIDER_REQUEST,
    RETRY,
    PARSED,
    SUCCEEDED,
    FAILED,
}
