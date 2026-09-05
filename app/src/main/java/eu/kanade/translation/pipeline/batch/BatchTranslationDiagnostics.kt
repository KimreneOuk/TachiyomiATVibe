package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationRunIdentity
import eu.kanade.translation.diagnostics.TranslationScheduleState
import eu.kanade.translation.diagnostics.TranslationScheduleTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceReason
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.util.ShortHash
import java.util.Locale
import logcat.LogPriority
import logcat.logcat

/**
 * T922 Phase 4: compatibility facade over `translation_trace_v1`
 * ([TranslationPipelineDiagnostics], tag `TachiyomiAT.Translation`).
 *
 * The legacy `TachiyomiAT.Batch` timing/status lines are migrated: every
 * method below now delegates to a schema event under the unified tag. The
 * public surface is unchanged for out-of-scope callers (artifact store,
 * single-page render phase, AI retry controller). Delegated events correlate
 * with the caller's current [TranslationTrace] run when one is installed,
 * else with the active batch schedule registered by
 * [BatchChapterTranslator]; with neither, they fail open (no identity, no
 * emission — a fabricated sid is never invented).
 *
 * Delegated events are standalone: they never record into a run stage map or
 * the schedule overlap accumulator, so they can never double-count against
 * the trace-native spans the coordinator/workers emit.
 *
 * Exception: [memorySnapshot] keeps the legacy tag and format — JVM heap
 * telemetry is not a translation_trace_v1 event family and contains no raw
 * content.
 */
object BatchTranslationDiagnostics {

    private const val TAG = "TachiyomiAT.Batch"

    /**
     * The batch schedule owned by the in-flight [BatchChapterTranslator]
     * invocation, registered for correlation by facade callers that hold no
     * page run (AI retry controller, artifact store). One @Volatile
     * reference; cleared at schedule end.
     */
    @Volatile
    internal var activeSchedule: TranslationScheduleTrace? = null

    fun noteActiveSchedule(schedule: TranslationScheduleTrace?) {
        activeSchedule = schedule
    }

    fun stageDecision(
        stage: BatchDiagnosticStage,
        pageKey: String,
        decision: BatchDiagnosticDecision,
        reason: BatchDiagnosticReason,
        fingerprint: String? = null,
        itemCount: Int = 0,
    ) {
        val identity = resolveIdentity() ?: return
        TranslationPipelineDiagnostics.recordBatchScheduleState(
            identity = identity,
            state = when (decision) {
                BatchDiagnosticDecision.EXECUTE -> TranslationScheduleState.ADMITTED
                BatchDiagnosticDecision.REUSE, BatchDiagnosticDecision.SKIP -> TranslationScheduleState.SKIP
                BatchDiagnosticDecision.RETRY, BatchDiagnosticDecision.FAIL -> TranslationScheduleState.DEFERRED
            },
            reason = reason.toTraceToken().token,
        )
    }

    fun timing(
        stage: BatchDiagnosticStage,
        pageKey: String,
        durationMs: Long,
        itemCount: Int = 0,
        success: Boolean = true,
    ) {
        val identity = resolveIdentity() ?: return
        TranslationPipelineDiagnostics.recordBatchStageEnd(
            identity = identity,
            stage = stage.toTraceStage(),
            lane = stage.toTraceLane(),
            durationMs = durationMs,
            items = itemCount,
            outcome = if (success) TranslationTraceOutcome.SUCCESS else TranslationTraceOutcome.FAILURE,
        )
    }

    fun reuse(
        stage: BatchDiagnosticStage,
        pageKey: String,
        reason: BatchDiagnosticReason,
        fingerprint: String? = null,
    ) {
        val identity = resolveIdentity() ?: return
        TranslationPipelineDiagnostics.recordBatchScheduleState(
            identity = identity,
            state = TranslationScheduleState.SKIP,
            reason = reason.toTraceToken().token,
        )
    }

    fun failure(
        stage: BatchDiagnosticStage,
        pageKey: String,
        errorClass: String,
        retryCount: Int = 0,
        reason: BatchDiagnosticReason = BatchDiagnosticReason.STAGE_FAILURE,
    ) {
        val identity = resolveIdentity() ?: return
        TranslationPipelineDiagnostics.recordBatchStageEnd(
            identity = identity,
            stage = stage.toTraceStage(),
            lane = stage.toTraceLane(),
            durationMs = 0,
            outcome = TranslationTraceOutcome.FAILURE,
            errorType = boundedErrorType(errorClass),
        )
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
        val identity = resolveIdentity() ?: return
        TranslationPipelineDiagnostics.recordBatchScheduleState(
            identity = identity,
            state = when (phase) {
                BatchEnvelopeLifecycle.ADMITTED -> TranslationScheduleState.QUEUED
                BatchEnvelopeLifecycle.PROVIDER_REQUEST, BatchEnvelopeLifecycle.PARSED,
                BatchEnvelopeLifecycle.SUCCEEDED,
                -> TranslationScheduleState.ADMITTED
                BatchEnvelopeLifecycle.RETRY, BatchEnvelopeLifecycle.FAILED ->
                    TranslationScheduleState.DEFERRED
            },
            reason = when (phase) {
                BatchEnvelopeLifecycle.ADMITTED -> TranslationTraceReason.ENVELOPE_ADMITTED
                BatchEnvelopeLifecycle.PROVIDER_REQUEST -> TranslationTraceReason.ENVELOPE_REQUEST
                BatchEnvelopeLifecycle.RETRY -> TranslationTraceReason.ENVELOPE_RETRY
                BatchEnvelopeLifecycle.PARSED -> TranslationTraceReason.ENVELOPE_PARSED
                BatchEnvelopeLifecycle.SUCCEEDED -> TranslationTraceReason.ENVELOPE_SUCCEEDED
                BatchEnvelopeLifecycle.FAILED -> TranslationTraceReason.ENVELOPE_FAILED
            }.token,
            envelope = traceEnvelopeToken(pageKeys),
            attempt = attempt,
        )
    }

    /** Stable opaque id for an envelope; page keys never appear in diagnostics. */
    fun envelopeId(pageKeys: Collection<String>): String = opaque(pageKeys.joinToString("\u0000"))

    /**
     * Trace-safe envelope token for schema fields: the same opaque digest as
     * [envelopeId] without the `h#` prefix (the `#` separator is not part of
     * the schema's safe token charset).
     */
    internal fun traceEnvelopeToken(pageKeys: Collection<String>): String =
        envelopeId(pageKeys).removePrefix("h#")

    /** Current page run first, else the active batch schedule; else null. */
    private fun resolveIdentity(): TranslationRunIdentity? =
        TranslationTrace.currentRun()?.identity
            ?: activeSchedule?.takeIf { !it.isClosed }?.identity

    /** Bounded stage token per legacy diagnostic stage. */
    private fun BatchDiagnosticStage.toTraceStage(): TranslationTraceStage = when (this) {
        BatchDiagnosticStage.OCR -> TranslationTraceStage.OCR
        BatchDiagnosticStage.INPAINT -> TranslationTraceStage.INPAINT
        BatchDiagnosticStage.TRANSLATION, BatchDiagnosticStage.CONTEXT -> TranslationTraceStage.TRANSLATE
        BatchDiagnosticStage.RENDER -> TranslationTraceStage.RENDER
        BatchDiagnosticStage.ARTIFACT -> TranslationTraceStage.STORE_COMMIT
    }

    private fun BatchDiagnosticStage.toTraceLane(): TranslationTraceLane = when (this) {
        BatchDiagnosticStage.OCR, BatchDiagnosticStage.INPAINT -> TranslationTraceLane.NATIVE
        BatchDiagnosticStage.TRANSLATION, BatchDiagnosticStage.CONTEXT -> TranslationTraceLane.PROVIDER
        BatchDiagnosticStage.RENDER -> TranslationTraceLane.RENDER
        BatchDiagnosticStage.ARTIFACT -> TranslationTraceLane.STORAGE
    }

    /** Legacy reason vocabulary -> bounded schema reason tokens. */
    private fun BatchDiagnosticReason.toTraceToken(): TranslationTraceReason = when (this) {
        BatchDiagnosticReason.SUCCESS -> TranslationTraceReason.ADMITTED
        BatchDiagnosticReason.REFERENCE_READY -> TranslationTraceReason.REFERENCE_READY
        BatchDiagnosticReason.NO_REFERENCE -> TranslationTraceReason.NO_REFERENCE
        BatchDiagnosticReason.CACHE_HIT -> TranslationTraceReason.CACHE_HIT
        BatchDiagnosticReason.CANDIDATE_ACTIVE -> TranslationTraceReason.CANDIDATE_ACTIVE
        BatchDiagnosticReason.STAGE_FAILURE -> TranslationTraceReason.STAGE_FAILURE
        BatchDiagnosticReason.TRANSIENT_FAILURE -> TranslationTraceReason.TRANSIENT_FAILURE
        BatchDiagnosticReason.TERMINAL_FAILURE -> TranslationTraceReason.TERMINAL_FAILURE
        BatchDiagnosticReason.CANCELLED -> TranslationTraceReason.CANCEL_REQUESTED
        BatchDiagnosticReason.CORRUPT_ARTIFACT, BatchDiagnosticReason.ORPHAN_ARTIFACT ->
            TranslationTraceReason.TERMINAL_FAILURE
    }

    /**
     * Bounded errorType mapping for legacy `errorClass` simple names. Only
     * recognized classes map onto the diagnostics vocabulary; everything else
     * collapses to the fixed `unknown` token (never the raw class name).
     */
    private fun boundedErrorType(errorClass: String): String = when (
        errorClass.lowercase(Locale.ROOT)
    ) {
        "ortexception" -> "ort"
        "cancellationexception" -> "cancel"
        "outofmemoryerror" -> "oom"
        "sockettimeoutexception" -> "http"
        "ioexception" -> "io"
        "illegalstateexception", "illegalargumentexception", "batchpersistencerejectedexception" -> "contract"
        else -> "unknown"
    }

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

    private fun opaque(value: String?): String =
        value?.takeIf { it.isNotEmpty() }?.let { "h#${ShortHash.hash(it)}" } ?: "none"

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
