package eu.kanade.translation.diagnostics

import ai.onnxruntime.OrtException
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import logcat.logcat
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/*
 * Formats bounded `translation_trace_v1` records and owns the privacy
 * sanitizer, injectable log sink, stage budgets, and schedule/run entry points.
 *
 * Hard rules enforced here:
 *  - One log tag; every line starts `schema=translation_trace_v1` with fixed
 *    key order and space-separated `key=value` tokens.
 *  - No raw content ever crosses this boundary: manga/chapter/page names,
 *    source/OCR text, translations, prompts, URLs, API material, and
 *    exception messages are structurally impossible to emit.
 *    pageIndex is the only accepted non-content positional field (§10.3).
 *  - Formatting and sink calls are synchronous, non-suspending, and
 *    fail-open: any failure is swallowed and never reaches the pipeline.
 *  - No state is retained: this object owns only the gate, the sink, the ID
 *    generator, and the identity keys. Completed traces are owned (and
 *    dropped) by callers; nothing registered here grows.
 */

/** Destination for formatted trace lines; injectable for JVM tests. */
fun interface TranslationTraceSink {
    fun log(priority: Int, line: String)
}

/** android.util.Log-compatible priorities without an Android compile dep. */
object TranslationTraceLogPriority {
    const val INFO = 3
    const val WARN = 4
    const val ERROR = 6
}

/** Default production sink: the shared `TachiyomiAT.Translation` logcat tag. */
object LogcatTranslationTraceSink : TranslationTraceSink {
    override fun log(priority: Int, line: String) {
        val logPriority = when (priority) {
            TranslationTraceLogPriority.WARN -> LogPriority.WARN
            TranslationTraceLogPriority.ERROR -> LogPriority.ERROR
            else -> LogPriority.INFO
        }
        logcat(tag = TranslationPipelineDiagnostics.TAG, priority = logPriority) { line }
    }
}

/**
 * Bounded, privacy-safe error surface: a whitelisted
 * `errorType` token plus an optional numeric provider code. Raw exception
 * messages are never carried; the single numeric extraction allowed anywhere
 * in this module pulls only the digit group from OrtException provider
 * messages (e.g. QNN `error code 1100` -> 1100).
 */
class TranslationTraceError internal constructor(
    val type: String,
    val code: Long?,
)

/**
 * Conservative lag budgets. They are diagnostic only and never consulted by
 * scheduling decisions.
 */
object TranslationTraceBudgets {
    /** Queue-type waits: lease, native queue, prepared queue, governor, join. */
    const val QUEUE_WAIT_MS = 1_000L
    const val SOURCE_DECODE_MS = 750L

    /** Batch source-fingerprint preflight (I/O-only hash). */
    const val SOURCE_FINGERPRINT_MS = 750L
    const val DETECT_MS = 750L
    const val SEGMENT_MS = 750L
    const val OCR_MS = 2_000L
    const val INPAINT_QNN_HTP_MS = 1_000L
    const val INPAINT_FALLBACK_MS = 6_000L
    const val TRANSLATE_REMOTE_MS = 8_000L
    const val TRANSLATE_LOCAL_MS = 5_000L
    const val RENDER_MS = 1_000L
    const val STORAGE_MS = 1_000L

    val QUEUE_STAGES: Set<TranslationTraceStage> = setOf(
        TranslationTraceStage.LEASE_WAIT,
        TranslationTraceStage.NATIVE_QUEUE,
        TranslationTraceStage.PREPARED_QUEUE,
        TranslationTraceStage.PREPARED_SEND_WAIT,
        TranslationTraceStage.PROVIDER_WINDOW_WAIT,
        TranslationTraceStage.PROVIDER_GOVERNOR_WAIT,
        TranslationTraceStage.RENDER_PERMIT_WAIT,
        TranslationTraceStage.JOURNAL_CREDIT_WAIT,
    )

    fun isQueueStage(stage: TranslationTraceStage): Boolean = stage in QUEUE_STAGES

    /**
     * Deterministic budget for a stage given the provider that executed it.
     * AOT-GAN on QNN HTP is the qualified 1000 ms route; every other inpaint
     * route uses the conservative CPU budget. Remote translation (network)
     * gets a wider budget than local translation.
     */
    fun budgetMsFor(
        stage: TranslationTraceStage,
        provider: TranslationTraceProvider,
    ): Long = when (stage) {
        TranslationTraceStage.LEASE_WAIT,
        TranslationTraceStage.NATIVE_QUEUE,
        TranslationTraceStage.PREPARED_QUEUE,
        TranslationTraceStage.PREPARED_SEND_WAIT,
        TranslationTraceStage.PROVIDER_WINDOW_WAIT,
        TranslationTraceStage.PROVIDER_GOVERNOR_WAIT,
        TranslationTraceStage.RENDER_PERMIT_WAIT,
        TranslationTraceStage.JOURNAL_CREDIT_WAIT,
        TranslationTraceStage.RENDER_JOIN,
        -> QUEUE_WAIT_MS

        TranslationTraceStage.SOURCE_DECODE -> SOURCE_DECODE_MS
        TranslationTraceStage.SOURCE_FINGERPRINT -> SOURCE_FINGERPRINT_MS
        TranslationTraceStage.DETECT -> DETECT_MS
        TranslationTraceStage.SEGMENT -> SEGMENT_MS
        TranslationTraceStage.OCR -> OCR_MS

        TranslationTraceStage.INPAINT ->
            if (provider == TranslationTraceProvider.QNN_HTP) INPAINT_QNN_HTP_MS else INPAINT_FALLBACK_MS

        TranslationTraceStage.TRANSLATE ->
            if (provider == TranslationTraceProvider.REMOTE) TRANSLATE_REMOTE_MS else TRANSLATE_LOCAL_MS

        TranslationTraceStage.ENGINE_SETUP,
        TranslationTraceStage.LAYOUT,
        TranslationTraceStage.RENDER,
        -> RENDER_MS

        TranslationTraceStage.CLEANED_PERSIST,
        TranslationTraceStage.DEFERRED_STORAGE_DRAIN,
        TranslationTraceStage.STORE_COMMIT,
        TranslationTraceStage.STORE_FLUSH,
        TranslationTraceStage.JOURNAL_TERMINAL_LAG,
        TranslationTraceStage.JOURNAL_TERMINAL_PAYLOAD,
        -> STORAGE_MS
    }
}

/**
 * Unified privacy-safe translation tracing. Entry points:
 *
 *  - [startSchedule] / [startRun]: create the bounded trace objects owned by
 *    pipeline code.
 *  - [TranslationTrace.currentRun] / [TranslationTrace.beginStage]: deep
 *    synchronous emission correlated through [TranslationTraceElement].
 *  - [routeChange]: model route demotion events.
 *  - [classifyError]: the only Throwable -> token mapping.
 *
 * [detailedTracingEnabled] controls detailed events
 * (stage_start bodies, success stage_end bodies, schedule_state,
 * route_change). Terminal summaries (run_end, schedule_end) plus lag/failure
 * stage variants are always emitted. The default is debug builds.
 */
object TranslationPipelineDiagnostics {
    const val TAG = "TachiyomiAT.Translation"
    const val SCHEMA = "translation_trace_v1"

    /** Placeholder for absent optional fields (`rid`, `page`, `errorCode`, ...). */
    const val NONE = "none"

    /** Mutable so JVM tests can capture lines without Robolectric. */
    @Volatile
    var sink: TranslationTraceSink = LogcatTranslationTraceSink

    /**
     * Detailed-event gate; defaults to debug builds.
     */
    @Volatile
    var detailedTracingEnabled: Boolean = BuildConfig.DEBUG

    /** Injectable so tests can pin deterministic schedule/run IDs. */
    @Volatile
    var idGenerator: TranslationTraceIdGenerator = TranslationTraceIdGenerator()

    /** Injectable so tests can pin deterministic chapter/page tokens. */
    @Volatile
    var identityKeys: TranslationIdentityKeys = TranslationIdentityKeys()

    private val ortErrorCodePattern = Regex("""error code ([0-9]{3,5})""")
    private val safeTokenPattern = Regex("""[A-Za-z0-9_.-]+""")
    private val reasonVocabulary: Set<String> =
        TranslationTraceReason.entries.mapTo(java.util.HashSet()) { it.token }
    private val storageMetrics = ConcurrentHashMap<String, StorageMetrics>()

    private class StorageMetrics {
        val physicalWriteCalls = AtomicLong()
        val physicalWriteBytes = AtomicLong()
        val failedWriteAttempts = AtomicLong()
        val failedAttemptBytes = AtomicLong()
        val flushCount = AtomicLong()
        val syncCount = AtomicLong()
        val flushDurationMs = AtomicLong()

        @Volatile var flushFailed: Boolean = false
    }

    // ------------------------------------------------------------------
    // Start/end APIs
    // ------------------------------------------------------------------

    /**
     * Creates one schedule trace (manual intent, rolling-Auto generation, or
     * batch invocation) and emits detailed `schedule_start`.
     */
    fun startSchedule(
        mode: TranslationTraceMode,
        origin: TranslationTraceMode = mode,
        chapterRaw: String? = null,
        pages: Int? = null,
        clock: TranslationTraceClock = TranslationTraceClock.SYSTEM,
    ): TranslationScheduleTrace {
        val startNanos = clock.nowNanos()
        val trace = TranslationScheduleTrace(
            sid = idGenerator.nextScheduleId(),
            mode = mode,
            origin = origin,
            chapter = identityKeys.token(CHAPTER_NAMESPACE, chapterRaw),
            pages = pages,
            clock = clock,
            startNanos = startNanos,
        )
        emitScheduleStart(trace.identity, pages)
        return trace
    }

    /**
     * Creates one concrete page attempt under [schedule] and emits detailed
     * `run_start`. Page identity is an opaque keyed token; [pageIndex] is the
     * accepted non-content positional field.
     */
    fun startRun(
        schedule: TranslationScheduleTrace,
        pageRaw: String? = null,
        pageIndex: Int? = null,
        plan: TranslationTracePlan = TranslationTracePlan.FRESH,
        clock: TranslationTraceClock = TranslationTraceClock.SYSTEM,
    ): TranslationRunTrace {
        val startNanos = clock.nowNanos()
        val run = TranslationRunTrace(
            identity = TranslationRunIdentity(
                sid = schedule.sid,
                rid = idGenerator.nextRunId(),
                mode = schedule.mode,
                origin = schedule.origin,
                chapter = schedule.chapter,
                page = identityKeys.token(PAGE_NAMESPACE, pageRaw),
                pageIndex = pageIndex,
            ),
            schedule = schedule,
            clock = clock,
            startNanos = startNanos,
            plan = plan,
        )
        emitRunStart(run.identity, plan)
        return run
    }

    /** Emits a bounded route demotion/switch event (detailed gate). */
    fun routeChange(
        run: TranslationRunTrace?,
        schedule: TranslationScheduleTrace?,
        stage: TranslationTraceStage,
        model: TranslationTraceModel,
        from: TranslationTraceProvider,
        to: TranslationTraceProvider,
        reason: String,
        error: Throwable? = null,
        retry: Int = 0,
    ) {
        val identity = run?.identity ?: schedule?.identity ?: return
        val resolvedError = resolveError(error, null, null)
        emitRouteChange(
            identity = identity,
            stage = stage,
            model = model,
            from = from,
            to = to,
            reason = reason,
            error = resolvedError,
            retry = retry,
        )
    }

    // ------------------------------------------------------------------
    // Sanitizer: Throwable -> bounded (errorType, errorCode)
    // ------------------------------------------------------------------

    /**
     * Whitelisted classification. Messages are read ONLY
     * for [OrtException] and ONLY to extract a bare `error code <digits>`
     * group (QNN provider codes such as 1100); everything else reduces to a
     * type token with no text. Unknown throwables never leak class-specific
     * detail beyond the fixed `unknown` token.
     */
    fun classifyError(error: Throwable?): TranslationTraceError = when (error) {
        null -> TranslationTraceError(NONE, null)
        is OrtException -> TranslationTraceError(ERROR_TYPE_ORT, ortProviderCode(error))
        is CancellationException -> TranslationTraceError(ERROR_TYPE_CANCEL, null)
        is OutOfMemoryError -> TranslationTraceError(ERROR_TYPE_OOM, null)
        is SocketTimeoutException -> TranslationTraceError(ERROR_TYPE_HTTP, null)
        is IOException -> TranslationTraceError(ERROR_TYPE_IO, null)
        is IllegalStateException, is IllegalArgumentException ->
            TranslationTraceError(ERROR_TYPE_CONTRACT, null)
        else -> TranslationTraceError(ERROR_TYPE_UNKNOWN, null)
    }

    /**
     * Resolves the terminal error surface: explicit overrides win (for
     * callers that already hold typed codes, e.g. HTTP status), otherwise
     * [classifyError]. Override tokens pass the same safe-token sanitizer.
     */
    internal fun resolveError(
        error: Throwable?,
        errorTypeOverride: String?,
        errorCodeOverride: Long?,
    ): TranslationTraceError {
        if (errorTypeOverride != null) {
            return TranslationTraceError(safeToken(errorTypeOverride), errorCodeOverride)
        }
        if (errorCodeOverride != null) {
            val base = classifyError(error)
            return TranslationTraceError(base.type, errorCodeOverride)
        }
        return classifyError(error)
    }

    private fun ortProviderCode(error: OrtException): Long? =
        error.message
            ?.let { ortErrorCodePattern.find(it)?.groupValues?.get(1) }
            ?.toLongOrNull()

    // ------------------------------------------------------------------
    // Stage helpers
    // ------------------------------------------------------------------

    internal fun defaultLane(stage: TranslationTraceStage): TranslationTraceLane = when (stage) {
        TranslationTraceStage.LEASE_WAIT,
        TranslationTraceStage.NATIVE_QUEUE,
        TranslationTraceStage.PREPARED_QUEUE,
        TranslationTraceStage.PREPARED_SEND_WAIT,
        TranslationTraceStage.PROVIDER_WINDOW_WAIT,
        TranslationTraceStage.PROVIDER_GOVERNOR_WAIT,
        TranslationTraceStage.RENDER_PERMIT_WAIT,
        TranslationTraceStage.JOURNAL_CREDIT_WAIT,
        TranslationTraceStage.RENDER_JOIN,
        -> TranslationTraceLane.SCHEDULER

        TranslationTraceStage.ENGINE_SETUP,
        TranslationTraceStage.SOURCE_DECODE,
        TranslationTraceStage.SOURCE_FINGERPRINT,
        TranslationTraceStage.DETECT,
        TranslationTraceStage.SEGMENT,
        TranslationTraceStage.OCR,
        TranslationTraceStage.INPAINT,
        -> TranslationTraceLane.NATIVE

        TranslationTraceStage.TRANSLATE -> TranslationTraceLane.PROVIDER
        TranslationTraceStage.CLEANED_PERSIST,
        TranslationTraceStage.DEFERRED_STORAGE_DRAIN,
        TranslationTraceStage.STORE_COMMIT,
        TranslationTraceStage.STORE_FLUSH,
        TranslationTraceStage.JOURNAL_TERMINAL_LAG,
        TranslationTraceStage.JOURNAL_TERMINAL_PAYLOAD,
        -> TranslationTraceLane.STORAGE

        TranslationTraceStage.LAYOUT,
        TranslationTraceStage.RENDER,
        -> TranslationTraceLane.RENDER
    }

    internal fun isQueueStage(stage: TranslationTraceStage): Boolean =
        TranslationTraceBudgets.isQueueStage(stage)

    // ------------------------------------------------------------------
    // Emission (gate + fail-open). Called by trace objects only.
    // ------------------------------------------------------------------

    internal fun emitScheduleStart(identity: TranslationRunIdentity, pages: Int?) {
        if (!detailedTracingEnabled) return
        try {
            emit(scheduleStartRecord(identity, pages), TranslationTraceLogPriority.INFO)
        } catch (_: Throwable) {
            // Fail-open: formatting/sink failures never affect the pipeline.
        }
    }

    internal fun emitScheduleEnd(
        identity: TranslationRunIdentity,
        pages: Int?,
        wallMs: Long,
        snapshot: TranslationLaneOverlapAccumulator.Snapshot,
        maxQueueMs: Long,
        slowestPage: String,
        stages: TranslationScheduleStageTotals,
        outcome: TranslationTraceOutcome,
    ) {
        try {
            emit(
                scheduleEndRecord(
                    identity = identity,
                    pages = pages,
                    wallMs = wallMs,
                    nativeBusyMs = snapshot.nativeBusyMs,
                    providerBusyMs = snapshot.providerBusyMs,
                    renderBusyMs = snapshot.renderBusyMs,
                    overlapMs = snapshot.overlapMs,
                    unionActiveMs = snapshot.unionActiveMs,
                    concurrencySavingsMs = snapshot.concurrencySavingsMs,
                    maxQueueMs = maxQueueMs,
                    slowestPage = slowestPage,
                    bottleneck = bottleneckLaneToken(snapshot),
                    stages = stages,
                    outcome = outcome,
                ),
                terminalPriority(outcome),
            )
        } catch (_: Throwable) {
        }
    }

    internal fun emitRunStart(identity: TranslationRunIdentity, plan: TranslationTracePlan) {
        if (!detailedTracingEnabled) return
        try {
            emit(runStartRecord(identity, plan), TranslationTraceLogPriority.INFO)
        } catch (_: Throwable) {
        }
    }

    internal fun emitRunEnd(
        identity: TranslationRunIdentity,
        plan: TranslationTracePlan,
        queuedMs: Long,
        totalMs: Long,
        stageSumMs: Long,
        bottleneck: TranslationTraceStage?,
        bottleneckMs: Long,
        retries: Int,
        outcome: TranslationTraceOutcome,
        error: TranslationTraceError,
        reason: TranslationTraceReason?,
    ) {
        try {
            emit(
                runEndRecord(
                    identity = identity,
                    plan = plan,
                    queuedMs = queuedMs,
                    totalMs = totalMs,
                    stageSumMs = stageSumMs,
                    bottleneck = bottleneck?.token ?: NONE,
                    bottleneckMs = bottleneckMs,
                    retries = retries,
                    outcome = outcome,
                    errorType = error.type,
                    errorCode = error.code,
                    reason = reason,
                ),
                terminalPriority(outcome),
            )
        } catch (_: Throwable) {
        }
    }

    internal fun emitStageStart(
        identity: TranslationRunIdentity,
        lane: TranslationTraceLane,
        stage: TranslationTraceStage,
        totalMs: Long,
        provider: TranslationTraceProvider,
        model: TranslationTraceModel,
        items: Int,
        site: TranslationTraceSite? = null,
        leaseKind: TranslationTraceLeaseKind? = null,
    ) {
        if (!detailedTracingEnabled) return
        try {
            emit(
                stageStartRecord(
                    identity = identity,
                    lane = lane,
                    stage = stage,
                    totalMs = totalMs,
                    provider = provider,
                    model = model,
                    items = items,
                    budgetMs = TranslationTraceBudgets.budgetMsFor(stage, provider),
                    site = site,
                    leaseKind = leaseKind,
                ),
                TranslationTraceLogPriority.INFO,
            )
        } catch (_: Throwable) {
        }
    }

    internal fun emitStageEnd(
        identity: TranslationRunIdentity,
        lane: TranslationTraceLane,
        stage: TranslationTraceStage,
        queueMs: Long,
        durationMs: Long,
        totalMs: Long,
        provider: TranslationTraceProvider,
        model: TranslationTraceModel,
        items: Int,
        outcome: TranslationTraceOutcome,
        error: TranslationTraceError,
        registeredProvider: TranslationTraceProvider? = null,
        provenProvider: TranslationTraceProvider? = null,
        envelope: String? = null,
        site: TranslationTraceSite? = null,
        leaseKind: TranslationTraceLeaseKind? = null,
    ) {
        val budgetMs = TranslationTraceBudgets.budgetMsFor(stage, provider)
        val lag = durationMs > budgetMs || queueMs > TranslationTraceBudgets.QUEUE_WAIT_MS
        // Terminal-level stage events (lag/failure) survive
        // the gate; plain success bodies are detailed-only.
        if (!detailedTracingEnabled && !lag && outcome == TranslationTraceOutcome.SUCCESS) return
        try {
            emit(
                stageEndRecord(
                    identity = identity,
                    lane = lane,
                    stage = stage,
                    queueMs = queueMs,
                    durationMs = durationMs,
                    totalMs = totalMs,
                    provider = provider,
                    model = model,
                    items = items,
                    outcome = outcome,
                    lag = lag,
                    budgetMs = budgetMs,
                    errorType = error.type,
                    errorCode = error.code,
                    registeredProvider = registeredProvider,
                    provenProvider = provenProvider,
                    envelope = envelope,
                    site = site,
                    leaseKind = leaseKind,
                ),
                if (lag || outcome != TranslationTraceOutcome.SUCCESS) {
                    TranslationTraceLogPriority.WARN
                } else {
                    TranslationTraceLogPriority.INFO
                },
            )
        } catch (_: Throwable) {
        }
    }

    internal fun emitRouteChange(
        identity: TranslationRunIdentity,
        stage: TranslationTraceStage,
        model: TranslationTraceModel,
        from: TranslationTraceProvider,
        to: TranslationTraceProvider,
        reason: String,
        error: TranslationTraceError,
        retry: Int,
    ) {
        if (!detailedTracingEnabled) return
        try {
            emit(
                routeChangeRecord(
                    identity = identity,
                    stage = stage,
                    model = model,
                    from = from,
                    to = to,
                    reason = reason,
                    errorType = error.type,
                    errorCode = error.code,
                    retry = retry,
                ),
                TranslationTraceLogPriority.WARN,
            )
        } catch (_: Throwable) {
        }
    }

    internal fun emitScheduleState(
        identity: TranslationRunIdentity,
        state: TranslationScheduleState,
        reason: String,
        queueDepth: Int,
        nativeActive: Int,
        providerActive: Int,
        envelope: String? = null,
        attempt: Int = 0,
        expectedItemCount: Int? = null,
        receivedItemCount: Int? = null,
        detailReason: String? = null,
    ) {
        if (!detailedTracingEnabled) return
        try {
            emit(
                scheduleStateRecord(
                    identity = identity,
                    state = state,
                    reason = reason,
                    queueDepth = queueDepth,
                    nativeActive = nativeActive,
                    providerActive = providerActive,
                    envelope = envelope,
                    attempt = attempt,
                    expectedItemCount = expectedItemCount,
                    receivedItemCount = receivedItemCount,
                    detailReason = detailReason,
                ),
                TranslationTraceLogPriority.INFO,
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // Standalone, already-measured emissions for batch diagnostic callers.
    // They correlate with the resolved identity but record into no
    // run/schedule state machine (no stage map, no accumulator), so legacy
    // events can never double-count against the trace-native spans.
    // ------------------------------------------------------------------

    /**
     * Emits one settled `stage_end` for an already-measured batch duration
     * (legacy `timing`/`failure` parity). [identity] is resolved by the
     * caller (current run first, then the active batch schedule). Fail-open;
     * suppressed under the detailed gate unless failed/lagged (same rule as
     * native stage ends).
     */
    fun recordBatchStageEnd(
        identity: TranslationRunIdentity,
        stage: TranslationTraceStage,
        lane: TranslationTraceLane,
        durationMs: Long,
        items: Int = 0,
        outcome: TranslationTraceOutcome = TranslationTraceOutcome.SUCCESS,
        errorType: String? = null,
        errorCode: Long? = null,
        envelope: String? = null,
    ) {
        try {
            emitStageEnd(
                identity = identity,
                lane = lane,
                stage = stage,
                queueMs = 0,
                durationMs = durationMs.coerceAtLeast(0),
                totalMs = durationMs.coerceAtLeast(0),
                provider = TranslationTraceProvider.NONE,
                model = TranslationTraceModel.NONE,
                items = items.coerceAtLeast(0),
                outcome = outcome,
                error = resolveError(null, errorType, errorCode),
                envelope = envelope,
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Emits one `schedule_state` decision for the batch facade (legacy stage
     * decision / artifact reuse / envelope lifecycle parity). [envelope]
     * carries the existing opaque envelope ID; [attempt] the provider attempt
     * counter when the caller holds one.
     */
    fun recordBatchScheduleState(
        identity: TranslationRunIdentity,
        state: TranslationScheduleState,
        reason: String,
        envelope: String? = null,
        attempt: Int = 0,
        expectedItemCount: Int? = null,
        receivedItemCount: Int? = null,
        detailReason: String? = null,
    ) {
        try {
            emitScheduleState(
                identity = identity,
                state = state,
                reason = reason,
                queueDepth = 0,
                nativeActive = 0,
                providerActive = 0,
                envelope = envelope,
                attempt = attempt,
                expectedItemCount = expectedItemCount,
                receivedItemCount = receivedItemCount,
                detailReason = detailReason,
            )
        } catch (_: Throwable) {
        }
    }

    /** Bounded counters for envelope quality; inputs are closed-vocabulary or numeric tokens. */
    fun recordBatchMetrics(identity: TranslationRunIdentity, fields: Map<String, String>) {
        if (!detailedTracingEnabled) return
        val allowed = listOf(
            "phase", "requested", "accepted", "resolvedPct", "exact", "salvaged", "unresolved",
            "unresolvedReasons", "conflictCount", "malformedCount", "parseAmbiguity",
            "followUpUsed", "correctionLineUsed",
        )
        val safeFields = allowed.mapNotNull { key ->
            fields[key]?.let { value -> "$key=${batchMetricToken(key, value)}" }
        }
        try {
            emit(
                identityPrefix("batch_metric", identity) + safeFields.joinToString(separator = " ", prefix = " "),
                TranslationTraceLogPriority.INFO,
            )
        } catch (_: Throwable) {
        }
    }

    /** Records one admitted HTTP attempt without retaining request or response content. */
    fun recordHttpRequest(
        identity: TranslationRunIdentity?,
        requestId: String,
        envelopeRaw: String?,
        operation: String,
        attempt: Int,
        durationMs: Long,
        outcome: TranslationTraceOutcome,
        estimatedInputTokens: Int,
        reservedOutputTokens: Int,
        inputTokens: Int?,
        outputTokens: Int?,
    ) {
        if (!detailedTracingEnabled || identity == null) return
        val operationToken = when {
            operation.contains("contextual", ignoreCase = true) -> "contextual"
            operation.contains("translation", ignoreCase = true) -> "translation"
            operation.contains("analysis", ignoreCase = true) -> "analysis"
            else -> "other"
        }
        val envelope = identityKeys.token('e', envelopeRaw)
        val line = identityPrefix("http_request", identity) +
            " requestId=$requestId" +
            " envelope=$envelope" +
            " operation=$operationToken" +
            " attempt=${attempt.coerceAtLeast(1)}" +
            " durationMs=${durationMs.coerceAtLeast(0)}" +
            " outcome=${outcome.token}" +
            " estimatedInputTokens=${estimatedInputTokens.coerceAtLeast(0)}" +
            " reservedOutputTokens=${reservedOutputTokens.coerceAtLeast(0)}" +
            " inputTokens=${inputTokens?.coerceAtLeast(0) ?: NONE}" +
            " outputTokens=${outputTokens?.coerceAtLeast(0) ?: NONE}" +
            " usageSource=${if (inputTokens != null || outputTokens != null) "provider" else "unknown"}"
        try {
            emit(line, if (outcome == TranslationTraceOutcome.SUCCESS) TranslationTraceLogPriority.INFO else TranslationTraceLogPriority.WARN)
        } catch (_: Throwable) {
        }
    }

    /** Adds physical write/flush observations to a run-bounded aggregation. */
    fun recordStorageIo(
        identity: TranslationRunIdentity?,
        storage: String,
        physicalWriteCalls: Long = 0,
        physicalWriteBytes: Long = 0,
        failedWriteAttempts: Long = 0,
        failedAttemptBytes: Long = 0,
        flushCount: Long = 0,
        syncCount: Long = 0,
        flushDurationMs: Long = 0,
        flushFailed: Boolean = false,
    ) {
        if (!detailedTracingEnabled || identity == null || storage !in setOf("journal", "artifact")) return
        val key = "${identity.sid}|${identity.rid}|$storage"
        val metrics = storageMetrics.computeIfAbsent(key) { StorageMetrics() }
        metrics.physicalWriteCalls.addAndGet(physicalWriteCalls.coerceAtLeast(0))
        metrics.physicalWriteBytes.addAndGet(physicalWriteBytes.coerceAtLeast(0))
        metrics.failedWriteAttempts.addAndGet(failedWriteAttempts.coerceAtLeast(0))
        metrics.failedAttemptBytes.addAndGet(failedAttemptBytes.coerceAtLeast(0))
        metrics.flushCount.addAndGet(flushCount.coerceAtLeast(0))
        metrics.syncCount.addAndGet(syncCount.coerceAtLeast(0))
        metrics.flushDurationMs.addAndGet(flushDurationMs.coerceAtLeast(0))
        if (flushFailed) metrics.flushFailed = true
    }

    /** Flushes and removes every bounded storage aggregate for the completed run. */
    internal fun flushStorageIo(identity: TranslationRunIdentity) {
        val prefix = "${identity.sid}|${identity.rid}|"
        storageMetrics.entries.toList().forEach { (key, metrics) ->
            if (!key.startsWith(prefix) || !storageMetrics.remove(key, metrics)) return@forEach
            val storage = key.removePrefix(prefix)
            try {
                emit(
                    identityPrefix("storage_io", identity) +
                        " storage=$storage" +
                        " physicalWriteCalls=${metrics.physicalWriteCalls.get()}" +
                        " physicalWriteBytes=${metrics.physicalWriteBytes.get()}" +
                        " failedWriteAttempts=${metrics.failedWriteAttempts.get()}" +
                        " failedAttemptBytes=${metrics.failedAttemptBytes.get()}" +
                        " flushCount=${metrics.flushCount.get()}" +
                        " syncCount=${metrics.syncCount.get()}" +
                        " flushDurationMs=${metrics.flushDurationMs.get()}" +
                        " flushOutcome=${if (metrics.flushFailed) "failure" else "success"}",
                    if (metrics.flushFailed || metrics.failedWriteAttempts.get() > 0) {
                        TranslationTraceLogPriority.WARN
                    } else {
                        TranslationTraceLogPriority.INFO
                    },
                )
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Pure formatters (fixed key order, exact tokens). Internal so tests
    // can assert exact strings without the sink.
    // ------------------------------------------------------------------

    internal fun identityPrefix(event: String, identity: TranslationRunIdentity): String =
        "$SCHEMA_KEY event=$event" +
            " sid=${identity.sid}" +
            " rid=${identity.rid}" +
            " mode=${identity.mode.token}" +
            " origin=${identity.origin.token}" +
            " chapter=${identity.chapter}" +
            " page=${identity.page}" +
            " pageIndex=${identity.pageIndex ?: NONE}"

    internal fun scheduleStartRecord(identity: TranslationRunIdentity, pages: Int?): String =
        identityPrefix(EVENT_SCHEDULE_START, identity) +
            " pages=${pages ?: NONE}" +
            " wallMs=0 nativeBusyMs=0 providerBusyMs=0 renderBusyMs=0" +
            " overlapMs=0 unionActiveMs=0 concurrencySavingsMs=0 workMs=0 criticalPathMs=0" +
            " leaseWaitMs=0 nativeQueueMs=0 preparedQueueMs=0 preparedSendWaitMs=0" +
            " providerWindowWaitMs=0 providerGovernorWaitMs=0 translateMs=0 renderPermitWaitMs=0" +
            " deferredStorageDrainMs=0 cleanedPersistMs=0 storeCommitMs=0 storeFlushMs=0" +
            " maxQueueMs=0 slowestPage=$NONE bottleneck=$NONE" +
            " outcome=${TranslationTraceOutcome.STARTED.token}"

    internal fun scheduleEndRecord(
        identity: TranslationRunIdentity,
        pages: Int?,
        wallMs: Long,
        nativeBusyMs: Long,
        providerBusyMs: Long,
        renderBusyMs: Long,
        overlapMs: Long,
        unionActiveMs: Long,
        concurrencySavingsMs: Long,
        maxQueueMs: Long,
        slowestPage: String,
        bottleneck: String,
        stages: TranslationScheduleStageTotals,
        outcome: TranslationTraceOutcome,
    ): String =
        identityPrefix(EVENT_SCHEDULE_END, identity) +
            " pages=${pages ?: NONE}" +
            " wallMs=$wallMs" +
            " nativeBusyMs=$nativeBusyMs" +
            " providerBusyMs=$providerBusyMs" +
            " renderBusyMs=$renderBusyMs" +
            " overlapMs=$overlapMs" +
            " unionActiveMs=$unionActiveMs" +
            " concurrencySavingsMs=$concurrencySavingsMs" +
            " workMs=${nativeBusyMs + providerBusyMs + renderBusyMs}" +
            " leaseWaitMs=${stages.leaseWaitMs}" +
            " nativeQueueMs=${stages.nativeQueueMs}" +
            " preparedQueueMs=${stages.preparedQueueMs}" +
            " preparedSendWaitMs=${stages.preparedSendWaitMs}" +
            " providerWindowWaitMs=${stages.providerWindowWaitMs}" +
            " providerGovernorWaitMs=${stages.providerGovernorWaitMs}" +
            " translateMs=${stages.translateMs}" +
            " renderPermitWaitMs=${stages.renderPermitWaitMs}" +
            " deferredStorageDrainMs=${stages.deferredStorageDrainMs}" +
            " cleanedPersistMs=${stages.cleanedPersistMs}" +
            " storeCommitMs=${stages.storeCommitMs}" +
            " storeFlushMs=${stages.storeFlushMs}" +
            " criticalPathMs=$wallMs" +
            " maxQueueMs=$maxQueueMs" +
            " slowestPage=$slowestPage" +
            " bottleneck=$bottleneck" +
            " outcome=${outcome.token}"

    internal fun runStartRecord(identity: TranslationRunIdentity, plan: TranslationTracePlan): String =
        identityPrefix(EVENT_RUN_START, identity) +
            " plan=${plan.token}" +
            " queuedMs=0 totalMs=0 stageSumMs=0" +
            " bottleneck=$NONE bottleneckMs=0 retries=0" +
            " outcome=${TranslationTraceOutcome.STARTED.token}" +
            " errorType=$NONE errorCode=$NONE"

    internal fun runEndRecord(
        identity: TranslationRunIdentity,
        plan: TranslationTracePlan,
        queuedMs: Long,
        totalMs: Long,
        stageSumMs: Long,
        bottleneck: String,
        bottleneckMs: Long,
        retries: Int,
        outcome: TranslationTraceOutcome,
        errorType: String,
        errorCode: Long?,
        reason: TranslationTraceReason? = null,
    ): String =
        identityPrefix(EVENT_RUN_END, identity) +
            " plan=${plan.token}" +
            " queuedMs=$queuedMs" +
            " totalMs=$totalMs" +
            " stageSumMs=$stageSumMs" +
            " bottleneck=$bottleneck" +
            " bottleneckMs=$bottleneckMs" +
            " retries=$retries" +
            " outcome=${outcome.token}" +
            " errorType=$errorType" +
            " errorCode=${errorCode ?: NONE}" +
            reasonSuffix(reason)

    private fun reasonSuffix(reason: TranslationTraceReason?): String =
        reason?.let { " reason=${it.token}" } ?: ""

    internal fun stageStartRecord(
        identity: TranslationRunIdentity,
        lane: TranslationTraceLane,
        stage: TranslationTraceStage,
        totalMs: Long,
        provider: TranslationTraceProvider,
        model: TranslationTraceModel,
        items: Int,
        budgetMs: Long,
        site: TranslationTraceSite? = null,
        leaseKind: TranslationTraceLeaseKind? = null,
    ): String =
        identityPrefix(EVENT_STAGE_START, identity) +
            " lane=${lane.token}" +
            " stage=${stage.token}" +
            " queueMs=0 durationMs=0" +
            " totalMs=$totalMs" +
            " provider=${provider.token}" +
            " model=${model.token}" +
            " items=$items" +
            " outcome=${TranslationTraceOutcome.STARTED.token}" +
            " lag=false" +
            " budgetMs=$budgetMs" +
            " errorType=$NONE errorCode=$NONE" +
            leaseSuffix(site, leaseKind)

    internal fun stageEndRecord(
        identity: TranslationRunIdentity,
        lane: TranslationTraceLane,
        stage: TranslationTraceStage,
        queueMs: Long,
        durationMs: Long,
        totalMs: Long,
        provider: TranslationTraceProvider,
        model: TranslationTraceModel,
        items: Int,
        outcome: TranslationTraceOutcome,
        lag: Boolean,
        budgetMs: Long,
        errorType: String,
        errorCode: Long?,
        registeredProvider: TranslationTraceProvider? = null,
        provenProvider: TranslationTraceProvider? = null,
        envelope: String? = null,
        site: TranslationTraceSite? = null,
        leaseKind: TranslationTraceLeaseKind? = null,
    ): String =
        identityPrefix(EVENT_STAGE_END, identity) +
            " lane=${lane.token}" +
            " stage=${stage.token}" +
            " queueMs=$queueMs" +
            " durationMs=$durationMs" +
            " totalMs=$totalMs" +
            " provider=${provider.token}" +
            " model=${model.token}" +
            " items=$items" +
            " outcome=${outcome.token}" +
            " lag=$lag" +
            " budgetMs=$budgetMs" +
            " errorType=$errorType" +
            " errorCode=${errorCode ?: NONE}" +
            provenanceSuffix(registeredProvider, provenProvider) +
            envelopeSuffix(envelope) +
            leaseSuffix(site, leaseKind)

    /**
     * Optional trailing provenance fields:
     * appended only when the caller distinguishes the level, so pre-existing
     * schema lines remain byte-identical. `requestedProvider` is omitted
     * entirely until an engine exposes it.
     */
    private fun provenanceSuffix(
        registeredProvider: TranslationTraceProvider?,
        provenProvider: TranslationTraceProvider?,
    ): String = buildString {
        registeredProvider?.let { append(" registeredProvider=${it.token}") }
        provenProvider?.let { append(" provenProvider=${it.token}") }
    }

    /**
     * Optional trailing envelope field: the existing
     * opaque provider-envelope ID, charset-sanitized, appended only when the
     * caller supplies one — pre-existing schema lines stay byte-identical.
     */
    private fun envelopeSuffix(envelope: String?): String =
        envelope?.let { " envelope=${safeToken(it)}" } ?: ""

    /** Optional trailing page-lease context; omitted from every other event. */
    private fun leaseSuffix(
        site: TranslationTraceSite?,
        leaseKind: TranslationTraceLeaseKind?,
    ): String = buildString {
        site?.let { append(" site=${it.token}") }
        leaseKind?.let { append(" leaseKind=${it.token}") }
    }

    /** Optional trailing provider-attempt counter, emitted only when > 0. */
    private fun attemptSuffix(attempt: Int): String =
        if (attempt > 0) " attempt=$attempt" else ""

    internal fun routeChangeRecord(
        identity: TranslationRunIdentity,
        stage: TranslationTraceStage,
        model: TranslationTraceModel,
        from: TranslationTraceProvider,
        to: TranslationTraceProvider,
        reason: String,
        errorType: String,
        errorCode: Long?,
        retry: Int,
    ): String =
        identityPrefix(EVENT_ROUTE_CHANGE, identity) +
            " stage=${stage.token}" +
            " model=${model.token}" +
            " from=${from.token}" +
            " to=${to.token}" +
            " reason=${resolveReason(reason)}" +
            " errorType=$errorType" +
            " errorCode=${errorCode ?: NONE}" +
            " retry=$retry"

    internal fun scheduleStateRecord(
        identity: TranslationRunIdentity,
        state: TranslationScheduleState,
        reason: String,
        queueDepth: Int,
        nativeActive: Int,
        providerActive: Int,
        envelope: String? = null,
        attempt: Int = 0,
        expectedItemCount: Int? = null,
        receivedItemCount: Int? = null,
        detailReason: String? = null,
    ): String =
        identityPrefix(EVENT_SCHEDULE_STATE, identity) +
            " state=${state.token}" +
            " reason=${resolveReason(reason)}" +
            " queueDepth=$queueDepth" +
            " nativeActive=$nativeActive" +
            " providerActive=$providerActive" +
            attemptSuffix(attempt) +
            envelopeSuffix(envelope) +
            (expectedItemCount?.let { " expected=${it.coerceAtLeast(0)}" } ?: "") +
            (receivedItemCount?.let { " received=${it.coerceAtLeast(0)}" } ?: "") +
            (detailReason?.let { " detailReason=${safeToken(it)}" } ?: "")

    private fun batchMetricToken(key: String, value: String): String = when (key) {
        "phase" -> value.takeIf { it in setOf("first_pass", "semantic_follow_up") } ?: INVALID_TOKEN
        "resolvedPct" -> value.takeIf { it == "na" || Regex("""\d{1,3}\.\d""").matches(it) } ?: INVALID_TOKEN
        "unresolvedReasons" -> value.takeIf {
            it == "none" || Regex("""(?:missing|blank|conflict|echo|wrong_target|format)(?:,(?:missing|blank|conflict|echo|wrong_target|format))*""").matches(it)
        } ?: INVALID_TOKEN
        "followUpUsed", "correctionLineUsed", "requested", "accepted", "exact", "salvaged",
        "unresolved", "conflictCount", "malformedCount", "parseAmbiguity",
        -> value.takeIf { it.toIntOrNull()?.let { number -> number >= 0 } == true } ?: INVALID_TOKEN
        else -> INVALID_TOKEN
    }

    // ------------------------------------------------------------------
    // Token sanitization
    // ------------------------------------------------------------------

    /**
     * Developer-controlled free-form tokens (errorType overrides) are reduced
     * to a fixed charset; anything else collapses to `invalid`. User content
     * must not be passed here by contract; this is defense in depth, not the
     * privacy boundary (which is the keyed identity in
     * [TranslationIdentityKeys]).
     */
    internal fun safeToken(value: String?): String =
        value
            ?.takeIf { it.isNotEmpty() && safeTokenPattern.matches(it) }
            ?: INVALID_TOKEN

    /**
     * Bounded reason sanitizer: the `reason=` field accepts
     * ONLY tokens from the [TranslationTraceReason] vocabulary. Anything else —
     * unknown, empty, hostile, or charset-safe-but-unregistered — collapses to
     * the fixed `invalid` token. Fail-open: never throws, always returns a
     * bounded schema-safe token.
     */
    internal fun resolveReason(raw: String?): String =
        raw?.takeIf { reasonVocabulary.contains(it) } ?: INVALID_TOKEN

    /**
     * Maps an engine-reported execution-provider label (the `String` labels
     * ONNX session owners publish through their `providerSink`, e.g. `cpu`,
     * `qnn_htp`, or AOT's route strings such as `fixed_qnn_htp`) onto the
     * bounded provider token. Unrecognized/uninitialized labels collapse to
     * [TranslationTraceProvider.NONE]. Pure function, fail-open.
     */
    fun providerFromLabel(label: String?): TranslationTraceProvider {
        if (label.isNullOrBlank()) return TranslationTraceProvider.NONE
        val normalized = label.lowercase(Locale.ROOT)
        return when {
            normalized == "cpu" -> TranslationTraceProvider.CPU
            normalized == "xnnpack" -> TranslationTraceProvider.XNNPACK
            normalized.contains("qnn_htp") -> TranslationTraceProvider.QNN_HTP
            normalized.contains("qnn_gpu") -> TranslationTraceProvider.QNN_GPU
            normalized.contains("nnapi") -> TranslationTraceProvider.NNAPI
            normalized == "remote" -> TranslationTraceProvider.REMOTE
            normalized == "local" -> TranslationTraceProvider.LOCAL
            else -> TranslationTraceProvider.NONE
        }
    }

    private fun bottleneckLaneToken(snapshot: TranslationLaneOverlapAccumulator.Snapshot): String {
        var best: TranslationTraceLane? = null
        var bestNanos = 0L
        for (lane in BUSY_LANES) {
            val nanos = when (lane) {
                TranslationTraceLane.NATIVE -> snapshot.nativeBusyNanos
                TranslationTraceLane.PROVIDER -> snapshot.providerBusyNanos
                else -> snapshot.renderBusyNanos
            }
            if (nanos > bestNanos) {
                bestNanos = nanos
                best = lane
            }
        }
        return best?.token ?: NONE
    }

    private fun terminalPriority(outcome: TranslationTraceOutcome): Int = when (outcome) {
        TranslationTraceOutcome.SUCCESS,
        TranslationTraceOutcome.ATTACHED,
        TranslationTraceOutcome.SKIP,
        TranslationTraceOutcome.RESUME,
        -> TranslationTraceLogPriority.INFO

        else -> TranslationTraceLogPriority.WARN
    }

    private fun emit(line: String, priority: Int) {
        if (line.isEmpty()) return
        val target = sink
        try {
            target.log(priority, line)
        } catch (_: Throwable) {
            // Fail-open: a broken sink must never propagate into the caller.
        }
    }

    // ------------------------------------------------------------------
    // Constants (declared in the object body: a standalone object cannot
    // host a companion object).
    // ------------------------------------------------------------------

    internal const val SCHEMA_KEY = "schema=$SCHEMA"
    private const val EVENT_SCHEDULE_START = "schedule_start"
    private const val EVENT_SCHEDULE_END = "schedule_end"
    private const val EVENT_RUN_START = "run_start"
    private const val EVENT_RUN_END = "run_end"
    private const val EVENT_STAGE_START = "stage_start"
    private const val EVENT_STAGE_END = "stage_end"
    private const val EVENT_ROUTE_CHANGE = "route_change"
    private const val EVENT_SCHEDULE_STATE = "schedule_state"

    private const val CHAPTER_NAMESPACE = 'c'
    private const val PAGE_NAMESPACE = 'p'

    private const val INVALID_TOKEN = "invalid"

    private const val ERROR_TYPE_ORT = "ort"
    private const val ERROR_TYPE_CANCEL = "cancel"
    private const val ERROR_TYPE_OOM = "oom"
    private const val ERROR_TYPE_HTTP = "http"
    private const val ERROR_TYPE_IO = "io"
    private const val ERROR_TYPE_CONTRACT = "contract"
    private const val ERROR_TYPE_UNKNOWN = "unknown"

    private val BUSY_LANES = listOf(
        TranslationTraceLane.NATIVE,
        TranslationTraceLane.PROVIDER,
        TranslationTraceLane.RENDER,
    )
}
