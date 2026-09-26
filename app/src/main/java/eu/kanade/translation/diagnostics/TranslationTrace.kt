package eu.kanade.translation.diagnostics

import kotlinx.coroutines.ThreadContextElement
import java.security.SecureRandom
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

/*
 * Schedule/run identities, an immutable coroutine trace element, fixed-size
 * stage timers, an online overlap accumulator, and an injectable monotonic
 * clock.
 *
 * Bounded-memory contract: no type in this file retains an
 * event list, an interval list, or any chapter-length structure. A schedule is
 * O(1) state; a run owns exactly one fixed EnumMap keyed by stage. All
 * emission is synchronous, non-suspending, and fail-open (see
 * TranslationPipelineDiagnostics): no function here may suspend or throw into
 * a pipeline caller.
 */

/** Value tokens emitted in `key=value` schema fields. */
internal interface TraceToken {
    val token: String
}

/**
 * Trace mode and origin. `mode` is the pipeline that owns the schedule;
 * `origin` records which surface requested it.
 */
enum class TranslationTraceMode : TraceToken {
    MANUAL,
    AUTO,
    BATCH,
    ;

    override val token: String get() = name.lowercase(Locale.ROOT)
}

/** Canonical lane of a stage event. */
enum class TranslationTraceLane : TraceToken {
    SCHEDULER,
    NATIVE,
    PROVIDER,
    RENDER,
    STORAGE,
    ;

    override val token: String get() = name.lowercase(Locale.ROOT)
}

/**
 * Canonical stages. Exact tokens are part of the log contract;
 * do not rename.
 */
enum class TranslationTraceStage : TraceToken {
    LEASE_WAIT,
    NATIVE_QUEUE,
    ENGINE_SETUP,
    SOURCE_DECODE,

    /**
     * Batch source-fingerprint preflight — the I/O-only hash of
     * the downloaded page bytes before resume planning. Batch schedule scope.
     */
    SOURCE_FINGERPRINT,
    DETECT,
    SEGMENT,
    OCR,
    INPAINT,
    CLEANED_PERSIST,
    PREPARED_QUEUE,
    PROVIDER_GOVERNOR_WAIT,
    TRANSLATE,
    RENDER_JOIN,
    LAYOUT,
    RENDER,
    STORE_COMMIT,
    STORE_FLUSH,
    ;

    override val token: String get() = name.lowercase(Locale.ROOT)
}

/**
 * Execution-fact provider labels: what actually executed, not
 * what the device prefers.
 */
enum class TranslationTraceProvider : TraceToken {
    CPU,
    XNNPACK,
    QNN_HTP,
    QNN_GPU,
    NNAPI,
    ANDROID_CANVAS,
    REMOTE,
    LOCAL,
    NONE,
    ;

    override val token: String get() = name.lowercase(Locale.ROOT)
}

/** Bounded model identifiers. */
enum class TranslationTraceModel : TraceToken {
    BUBBLE_SEGMENTER,
    PAGE_DETECTOR,
    MANGA_OCR,
    PADDLE_OCR,
    AOT_GAN,
    NONE,
    ;

    override val token: String get() = name.lowercase(Locale.ROOT)
}

/**
 * Bounded terminal outcome tokens. Every started run or
 * schedule must be closed with exactly one of these; closing is idempotent.
 */
enum class TranslationTraceOutcome(val token: String) {
    STARTED("started"),
    SUCCESS("success"),
    FAILURE("failure"),
    PAUSE("pause"),
    CANCELLED("cancelled"),
    CANCELLED_BEFORE_DISPATCH("cancelled_before_dispatch"),
    CANCELLED_DURING_SEND("cancelled_during_send"),
    TIMEOUT("timeout"),
    EVICTED("evicted"),
    STALE_HANDOFF("stale_handoff"),
    COORDINATOR_REPLACED("coordinator_replaced"),
    ATTACHED("attached"),
    SKIP("skip"),
    RESUME("resume"),
    PERSISTENCE_REJECTED("persistence_rejected"),
    TEARDOWN_EXCEPTION("teardown_exception"),
}

/** Bounded scheduling-decision states for `schedule_state` events. */
enum class TranslationScheduleState : TraceToken {
    QUEUED,
    ADMITTED,
    DEFERRED,
    ATTACHED,
    EVICTED,
    CANCELLED,

    /**
     * The scheduled unit contributes no work — an OCR
     * skip (no reference / fully durable page) or an artifact reuse decision.
     */
    SKIP,
    ;

    override val token: String get() = name.lowercase(Locale.ROOT)
}

/**
 * Bounded reason-token vocabulary for the `reason=` field of `schedule_state`
 * and `route_change` events.
 *
 * Free-form reason strings are not accepted anywhere in the schema. Callers
 * must pass one of these tokens (via [token]); the formatter collapses
 * anything else — including hostile or accidentally-user-derived strings — to
 * the fixed `invalid` token through
 * [TranslationPipelineDiagnostics.resolveReason]. Fail-open: resolution never
 * throws and always yields a bounded, charset-safe token.
 */
enum class TranslationTraceReason(val token: String) {
    // schedule_state — window lifecycle
    WINDOW_UPDATE("window_update"),
    WINDOW_PENDING("window_pending"),
    GENERATION_CHANGED("generation_changed"),
    CHAPTER_CHANGED("chapter_changed"),
    OUT_OF_WINDOW("out_of_window"),

    // schedule_state — admission and deferral
    ADMITTED("admitted"),
    LEASE_GRANTED("lease_granted"),
    LEASE_UNAVAILABLE("lease_unavailable"),
    SOURCE_UNAVAILABLE("source_unavailable"),
    MEMORY_PRESSURE("memory_pressure"),
    PROVIDER_PAUSE("provider_pause"),
    RETRY_DEFERRED("retry_deferred"),
    STALE_GENERATION("stale_generation"),

    // schedule_state — cancellation and teardown
    CANCEL_REQUESTED("cancel_requested"),
    MANUAL_PREEMPT("manual_preempt"),
    TIMEOUT("timeout"),
    COORDINATOR_REPLACED("coordinator_replaced"),
    TEARDOWN("teardown"),

    // route_change
    RUNTIME_FAILURE("runtime_failure"),
    SESSION_CREATION_FAILURE("session_creation_failure"),
    REGISTRATION_FAILURE("registration_failure"),
    CPU_FALLBACK("cpu_fallback"),
    DEVICE_PREFERENCE("device_preference"),

    // Bounded reason tokens used by batch stage decisions, artifact reuse,
    // and provider envelope lifecycle events.
    REFERENCE_READY("reference_ready"),
    NO_REFERENCE("no_reference"),
    CACHE_HIT("cache_hit"),
    CANDIDATE_ACTIVE("candidate_active"),
    STAGE_FAILURE("stage_failure"),
    TRANSIENT_FAILURE("transient_failure"),
    TERMINAL_FAILURE("terminal_failure"),
    ARTIFACT_REUSE("artifact_reuse"),
    ENVELOPE_ADMITTED("envelope_admitted"),
    ENVELOPE_REQUEST("envelope_request"),
    ENVELOPE_RETRY("envelope_retry"),
    ENVELOPE_PARSED("envelope_parsed"),
    ENVELOPE_SUCCEEDED("envelope_succeeded"),
    ENVELOPE_FAILED("envelope_failed"),
    ENVELOPE_CANCELLED("envelope_cancelled"),
}

/** Bounded plan tokens for `run_start`/`run_end`. */
enum class TranslationTracePlan : TraceToken {
    FRESH,
    RESUME,
    RENDER_ONLY,
    SKIP,
    ;

    override val token: String get() = name.lowercase(Locale.ROOT)
}

/**
 * Injectable monotonic clock. All durations in the trace foundation derive
 * from this interface; production uses [TranslationTraceClock.SYSTEM]
 * (System.nanoTime). Tests substitute a fake to exercise negative-delta and
 * budget behavior deterministically.
 */
fun interface TranslationTraceClock {
    fun nowNanos(): Long

    companion object {
        val SYSTEM: TranslationTraceClock = TranslationTraceClock { System.nanoTime() }
    }
}

/**
 * Process-local opaque trace identifiers: one random prefix per process plus
 * monotonic counters. Identifiers carry no
 * content: they correlate events within one process only and are never
 * stable across launches.
 */
class TranslationTraceIdGenerator(
    processPrefix: String = newProcessPrefix(),
) {
    val processPrefix: String = processPrefix
    private val scheduleCounter = AtomicLong()
    private val runCounter = AtomicLong()

    fun nextScheduleId(): String = "${processPrefix}s${scheduleCounter.incrementAndGet()}"

    fun nextRunId(): String = "${processPrefix}r${runCounter.incrementAndGet()}"

    companion object {
        fun newProcessPrefix(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(PROCESS_PREFIX_BYTES)
            random.nextBytes(bytes)
            val builder = StringBuilder(PROCESS_PREFIX_BYTES * 2 + 1)
            for (b in bytes) builder.append(String.format(Locale.ROOT, "%02x", b))
            builder.append('-')
            return builder.toString()
        }

        private const val PROCESS_PREFIX_BYTES = 4
    }
}

/**
 * Per-process random-keyed digest for chapter/page tokens.
 *
 * The deterministic `eu.kanade.translation.util.ShortHash` (unsalted FNV-1a)
 * is deliberately NOT used here: enumerable page/chapter names could be
 * dictionary-matched across launches. This class digests raw content through
 * HMAC-SHA256 with a key generated once per process instance, truncated to
 * 64 bits and namespace-prefixed. Two identical inputs yield equal tokens
 * within one process (required for correlation) and uncorrelatable tokens
 * across process launches (privacy boundary). Tokens are lowercase hex plus
 * the one-char namespace prefix, so they can never inject separators into the
 * `key=value` schema.
 */
class TranslationIdentityKeys(
    keyBytes: ByteArray = newRandomKey(),
) {
    private val mac: Mac

    init {
        val instance = Mac.getInstance(MAC_ALGORITHM)
        instance.init(SecretKeySpec(normalize(keyBytes), MAC_ALGORITHM))
        mac = instance
    }

    /**
     * Returns `none` for absent input, otherwise `<namespace><16 hex>` where
     * the hex is the first 8 bytes of HMAC-SHA256(key, namespace || 0x00 ||
     * raw). The only consumers are [TranslationPipelineDiagnostics] start
     * APIs, which call this once per chapter/schedule and once per page/run.
     */
    fun token(namespace: Char, raw: String?): String {
        if (raw.isNullOrEmpty()) return TranslationPipelineDiagnostics.NONE
        val input = "$namespace\u0000$raw".toByteArray(Charsets.UTF_8)
        val digest = synchronized(mac) { mac.doFinal(input) }
        val builder = StringBuilder(TOKEN_CHARS + 1).append(namespace)
        for (i in 0 until TOKEN_BYTES) {
            builder.append(String.format(Locale.ROOT, "%02x", digest[i]))
        }
        return builder.toString()
    }

    companion object {
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val TOKEN_BYTES = 8
        private const val TOKEN_CHARS = TOKEN_BYTES * 2

        fun newRandomKey(random: SecureRandom = SecureRandom()): ByteArray =
            ByteArray(KEY_BYTES).also(random::nextBytes)

        private fun normalize(bytes: ByteArray): ByteArray {
            val out = ByteArray(KEY_BYTES)
            bytes.copyInto(out, 0, 0, minOf(bytes.size, KEY_BYTES))
            return out
        }

        private const val KEY_BYTES = 32
    }
}

/**
 * Immutable identity attached to every emitted event. `rid`,
 * `page`, and `pageIndex` are `none` for schedule-scoped events.
 */
class TranslationRunIdentity(
    val sid: String,
    val rid: String,
    val mode: TranslationTraceMode,
    val origin: TranslationTraceMode,
    val chapter: String,
    val page: String,
    val pageIndex: Int?,
)

/**
 * Online, O(1) cross-lane overlap accumulator.
 *
 * Tracks active counts for the NATIVE, PROVIDER, and RENDER lanes only. On
 * every count transition the elapsed time since the previous transition is
 * settled into: per-lane busy totals, union-active time (any lane active),
 * overlap-union time (at least two lanes active), and
 * concurrencySavingsMs = sum(max(activeLaneCount - 1, 0) * delta).
 * No intervals are retained. Negative clock deltas clamp to zero. All
 * methods are internally synchronized because lanes execute on different
 * coroutines and threads.
 */
class TranslationLaneOverlapAccumulator(
    startNanos: Long,
) {
    private val lock = Any()
    private var lastNanos: Long = startNanos
    private val active = IntArray(LANE_SLOTS)
    private var nativeBusyNanos: Long = 0
    private var providerBusyNanos: Long = 0
    private var renderBusyNanos: Long = 0
    private var unionActiveNanos: Long = 0
    private var overlapUnionNanos: Long = 0
    private var concurrencySavingsNanos: Long = 0

    /** Enters the lane at [nowNanos]. Lane tokens ensure one exit per enter. */
    fun enter(lane: TranslationTraceLane, nowNanos: Long) {
        if (!isOverlappable(lane)) return
        synchronized(lock) {
            settle(nowNanos)
            active[lane.ordinal] += 1
        }
    }

    /**
     * Exits the lane at [nowNanos]. Exit without a matching enter clamps the
     * count at zero (fail-open diagnostics, never an exception).
     */
    fun exit(lane: TranslationTraceLane, nowNanos: Long) {
        if (!isOverlappable(lane)) return
        synchronized(lock) {
            settle(nowNanos)
            if (active[lane.ordinal] > 0) active[lane.ordinal] -= 1
        }
    }

    /** Settles pending time and returns a point-in-time snapshot. */
    fun snapshot(nowNanos: Long): Snapshot = synchronized(lock) {
        settle(nowNanos)
        Snapshot(
            nativeBusyNanos = nativeBusyNanos,
            providerBusyNanos = providerBusyNanos,
            renderBusyNanos = renderBusyNanos,
            unionActiveNanos = unionActiveNanos,
            overlapUnionNanos = overlapUnionNanos,
            concurrencySavingsNanos = concurrencySavingsNanos,
        )
    }

    /** Requires [lock]. Consumes elapsed time into all totals. */
    private fun settle(nowNanos: Long) {
        val delta = (nowNanos - lastNanos).coerceAtLeast(0)
        lastNanos = nowNanos
        if (delta <= 0) return
        if (active[TranslationTraceLane.NATIVE.ordinal] > 0) nativeBusyNanos += delta
        if (active[TranslationTraceLane.PROVIDER.ordinal] > 0) providerBusyNanos += delta
        if (active[TranslationTraceLane.RENDER.ordinal] > 0) renderBusyNanos += delta
        val count = active[TranslationTraceLane.NATIVE.ordinal] +
            active[TranslationTraceLane.PROVIDER.ordinal] +
            active[TranslationTraceLane.RENDER.ordinal]
        if (count > 0) unionActiveNanos += delta
        if (count >= 2) overlapUnionNanos += delta
        concurrencySavingsNanos += max(count - 1, 0).toLong() * delta
    }

    private fun isOverlappable(lane: TranslationTraceLane): Boolean =
        lane == TranslationTraceLane.NATIVE ||
            lane == TranslationTraceLane.PROVIDER ||
            lane == TranslationTraceLane.RENDER

    /** Immutable point-in-time totals; all ms values are nanos/1_000_000. */
    class Snapshot(
        val nativeBusyNanos: Long,
        val providerBusyNanos: Long,
        val renderBusyNanos: Long,
        val unionActiveNanos: Long,
        val overlapUnionNanos: Long,
        val concurrencySavingsNanos: Long,
    ) {
        val nativeBusyMs: Long get() = nativeBusyNanos / NANOS_PER_MS
        val providerBusyMs: Long get() = providerBusyNanos / NANOS_PER_MS
        val renderBusyMs: Long get() = renderBusyNanos / NANOS_PER_MS
        val unionActiveMs: Long get() = unionActiveNanos / NANOS_PER_MS
        val overlapMs: Long get() = overlapUnionNanos / NANOS_PER_MS
        val concurrencySavingsMs: Long get() = concurrencySavingsNanos / NANOS_PER_MS
        val workMs: Long get() = (nativeBusyNanos + providerBusyNanos + renderBusyNanos) / NANOS_PER_MS
    }

    private companion object {
        private val LANE_SLOTS = TranslationTraceLane.entries.size
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * Balanced lane-enter token. Created by
 * [TranslationScheduleTrace.enterLane]; must be closed exactly once.
 * Double-close is a no-op; closing never throws, so `use { }` and `finally`
 * are always safe.
 */
class TranslationLaneToken internal constructor(
    private val schedule: TranslationScheduleTrace,
    private val lane: TranslationTraceLane,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        schedule.exitLane(lane)
    }
}

/**
 * One manual intent / rolling-Auto generation / batch invocation.
 *
 * Constant-size state: an overlap accumulator, a max queue wait, the slowest
 * page token, and the last emitted schedule_state key for coalescing.
 * [end] is idempotent: the first call emits the terminal
 * `schedule_end` summary, every later call is a no-op. Safe from `finally`
 * blocks and cancellation paths; never suspends.
 */
class TranslationScheduleTrace internal constructor(
    val sid: String,
    val mode: TranslationTraceMode,
    val origin: TranslationTraceMode,
    val chapter: String,
    private val pages: Int?,
    private val clock: TranslationTraceClock,
    startNanos: Long,
) {
    /** Schedule-scoped identity: rid/page/pageIndex are `none`. */
    val identity = TranslationRunIdentity(
        sid = sid,
        rid = TranslationPipelineDiagnostics.NONE,
        mode = mode,
        origin = origin,
        chapter = chapter,
        page = TranslationPipelineDiagnostics.NONE,
        pageIndex = null,
    )

    private val closed = AtomicBoolean(false)
    private val startNanos = startNanos
    private val accumulator = TranslationLaneOverlapAccumulator(startNanos)
    private val stateLock = Any()
    private var maxQueueMs: Long = 0
    private var slowestPageToken: String = TranslationPipelineDiagnostics.NONE
    private var slowestRunMs: Long = 0
    private var lastStateKey: String? = null

    // Runs that terminated with a non-success outcome under this
    // schedule. Lets a natural shutdown after a fully successful window emit
    // schedule_end success instead of a misleading cancelled.
    private val nonSuccessRuns = AtomicInteger(0)

    val isClosed: Boolean get() = closed.get()

    /**
     * Marks a lane active until the returned token is closed exactly once.
     * Entering after schedule close is tolerated (fail-open): the time is
     * accounted in the accumulator but the terminal summary was already
     * emitted.
     */
    fun enterLane(lane: TranslationTraceLane): TranslationLaneToken {
        accumulator.enter(lane, clock.nowNanos())
        return TranslationLaneToken(this, lane)
    }

    internal fun exitLane(lane: TranslationTraceLane) {
        accumulator.exit(lane, clock.nowNanos())
    }

    /** Records a bounded scheduling decision. Identical consecutive
     * (state, reason) pairs are coalesced. */
    fun reportState(
        state: TranslationScheduleState,
        reason: String,
        queueDepth: Int,
        nativeActive: Int,
        providerActive: Int,
    ) {
        if (!TranslationPipelineDiagnostics.detailedTracingEnabled) return
        val key = "${state.name}|$reason"
        synchronized(stateLock) {
            if (key == lastStateKey) return
            lastStateKey = key
        }
        TranslationPipelineDiagnostics.emitScheduleState(
            identity = identity,
            state = state,
            reason = reason,
            queueDepth = queueDepth,
            nativeActive = nativeActive,
            providerActive = providerActive,
        )
    }

    /**
     * Emits a bounded scheduling decision that bypasses the identical-key
     * coalescer: envelope lifecycle and stage
     * decisions repeat legitimately and must not be collapsed. Detailed-gated
     * and fail-open like [reportState].
     */
    fun reportUngroupedState(
        state: TranslationScheduleState,
        reason: String,
        queueDepth: Int = 0,
        nativeActive: Int = 0,
        providerActive: Int = 0,
        envelope: String? = null,
        attempt: Int = 0,
    ) {
        if (!TranslationPipelineDiagnostics.detailedTracingEnabled) return
        TranslationPipelineDiagnostics.emitScheduleState(
            identity = identity,
            state = state,
            reason = reason,
            queueDepth = queueDepth,
            nativeActive = nativeActive,
            providerActive = providerActive,
            envelope = envelope,
            attempt = attempt,
        )
    }

    /** Elapsed schedule wall time in ms at [nowNanos]. */
    internal fun totalMsAt(nowNanos: Long): Long =
        (nowNanos - startNanos).coerceAtLeast(0) / 1_000_000

    /**
     * Starts a SCHEDULE-scoped stage interval — batch work that
     * belongs to the whole invocation rather than one page (source-fingerprint
     * preflight, engine setup, one provider translation envelope). Emits
     * `stage_start` with the schedule identity (rid=none, page=none) and
     * records into no per-run map, so envelope time can never be multiplied
     * into page runs. The returned span must be ended exactly once.
     */
    fun beginStage(
        stage: TranslationTraceStage,
        lane: TranslationTraceLane = TranslationPipelineDiagnostics.defaultLane(stage),
        provider: TranslationTraceProvider = TranslationTraceProvider.NONE,
        model: TranslationTraceModel = TranslationTraceModel.NONE,
        items: Int = 0,
    ): TranslationScheduleStageSpan {
        val span = TranslationScheduleStageSpan(
            schedule = this,
            stage = stage,
            lane = lane,
            provider = provider,
            model = model,
            items = items,
            clock = clock,
            startNanos = clock.nowNanos(),
        )
        TranslationPipelineDiagnostics.emitStageStart(
            identity = identity,
            lane = lane,
            stage = stage,
            totalMs = totalMsAt(span.startNanos),
            provider = provider,
            model = model,
            items = items,
        )
        return span
    }

    /** Online max queue wait, updated by runs when queue stages settle. */
    internal fun noteQueueWait(queueMs: Long) {
        synchronized(stateLock) {
            if (queueMs > maxQueueMs) maxQueueMs = queueMs
        }
    }

    /** Online slowest-page tracking by opaque page token. */
    internal fun noteRunFinished(pageToken: String, totalMs: Long) {
        synchronized(stateLock) {
            if (totalMs > slowestRunMs) {
                slowestRunMs = totalMs
                slowestPageToken = pageToken
            }
        }
    }

    /**
     * Records a run terminal outcome so the schedule sweep can
     * distinguish a fully-successful window's teardown from a cancellation
     * that cut runs short. Bounded: one counter.
     */
    internal fun noteRunTerminal(outcome: TranslationTraceOutcome) {
        if (outcome != TranslationTraceOutcome.SUCCESS &&
            outcome != TranslationTraceOutcome.SKIP &&
            outcome != TranslationTraceOutcome.RESUME
        ) {
            nonSuccessRuns.incrementAndGet()
        }
    }

    /** True when no run under this schedule terminated non-successfully. */
    internal fun hasNoFailedRuns(): Boolean = nonSuccessRuns.get() == 0

    /**
     * Emits the terminal `schedule_end` exactly once and is idempotent.
     * Returns true on the first call, false afterwards.
     */
    fun end(outcome: TranslationTraceOutcome): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        val now = clock.nowNanos()
        val snapshot = accumulator.snapshot(now)
        val (maxQueue, slowestPage) = synchronized(stateLock) { maxQueueMs to slowestPageToken }
        TranslationPipelineDiagnostics.emitScheduleEnd(
            identity = identity,
            pages = pages,
            wallMs = elapsedMs(startNanos, now),
            snapshot = snapshot,
            maxQueueMs = maxQueue,
            slowestPage = slowestPage,
            outcome = outcome,
        )
        return true
    }

    private companion object {
        fun elapsedMs(startNanos: Long, nowNanos: Long): Long =
            (nowNanos - startNanos).coerceAtLeast(0) / 1_000_000
    }
}

/**
 * One SCHEDULE-scoped stage interval: batch work owned by the
 * whole invocation — source-fingerprint preflight, engine setup, and one
 * provider translation envelope. Emits
 * `stage_start`/`stage_end` with the schedule identity (rid=none, page=none)
 * and records into no per-run stage map. Ended exactly once; double-end is a
 * no-op; never throws, never suspends.
 */
class TranslationScheduleStageSpan internal constructor(
    private val schedule: TranslationScheduleTrace,
    val stage: TranslationTraceStage,
    val lane: TranslationTraceLane,
    val provider: TranslationTraceProvider,
    val model: TranslationTraceModel,
    val items: Int,
    private val clock: TranslationTraceClock,
    internal val startNanos: Long,
) : AutoCloseable {
    private val done = AtomicBoolean(false)

    val isFinished: Boolean get() = done.get()

    /**
     * Settles the interval and emits `stage_end` at schedule scope.
     * [envelope] is the optional opaque provider-envelope identifier
     * (existing batch envelope IDs, charset-sanitized). Returns true on the
     * first call.
     */
    fun end(
        outcome: TranslationTraceOutcome = TranslationTraceOutcome.SUCCESS,
        error: Throwable? = null,
        items: Int = this.items,
        errorType: String? = null,
        errorCode: Long? = null,
        queueMs: Long = 0,
        envelope: String? = null,
    ): Boolean {
        if (!done.compareAndSet(false, true)) return false
        val now = clock.nowNanos()
        val durationMs = (now - startNanos).coerceAtLeast(0) / 1_000_000
        TranslationPipelineDiagnostics.emitStageEnd(
            identity = schedule.identity,
            lane = lane,
            stage = stage,
            queueMs = if (TranslationPipelineDiagnostics.isQueueStage(stage)) durationMs else queueMs.coerceAtLeast(0),
            durationMs = durationMs,
            totalMs = schedule.totalMsAt(now),
            provider = provider,
            model = model,
            items = items,
            outcome = outcome,
            error = TranslationPipelineDiagnostics.resolveError(error, errorType, errorCode),
            envelope = envelope,
        )
        return true
    }

    /** `use { }`/finally-safe alias for [end] with the success outcome. */
    override fun close() {
        end()
    }
}

/**
 * One concrete page attempt (plan §4.1). Owns exactly one fixed
 * EnumMap&lt;Stage, Long&gt; of summed stage durations — repeated intervals for
 * the same stage (retries, re-entrant substages) accumulate, never overwrite
 * [end] is idempotent and never
 * suspends.
 */
class TranslationRunTrace internal constructor(
    val identity: TranslationRunIdentity,
    internal val schedule: TranslationScheduleTrace?,
    private val clock: TranslationTraceClock,
    private val startNanos: Long,
    private val plan: TranslationTracePlan,
) {
    private val closed = AtomicBoolean(false)
    private val stageLock = Any()
    private val stageNanos = EnumMap<TranslationTraceStage, Long>(TranslationTraceStage::class.java)
    private val retries = AtomicInteger(0)

    // The scheduler starts the run before the resume plan is known. The ONNX
    // phase resolves PageWorkPlanner.plan and installs it here for the
    // terminal run_end summary.
    private val planRef = AtomicReference(plan)

    val isClosed: Boolean get() = closed.get()

    /**
     * Installs the resolved resume plan (fresh/resume/render_only/skip).
     * Non-suspending, idempotent-last-write; the plan observed by [end] wins.
     */
    internal fun updatePlan(plan: TranslationTracePlan) {
        planRef.set(plan)
    }

    /**
     * Starts a stage span. Emits a detailed `stage_start` (suppressed when
     * detailed tracing is off). The returned span must be ended exactly once;
     * double-end is a no-op. Ending after the run closed records nothing.
     */
    fun beginStage(
        stage: TranslationTraceStage,
        lane: TranslationTraceLane = TranslationPipelineDiagnostics.defaultLane(stage),
        provider: TranslationTraceProvider = TranslationTraceProvider.NONE,
        model: TranslationTraceModel = TranslationTraceModel.NONE,
        items: Int = 0,
    ): TranslationStageSpan {
        val span = TranslationStageSpan(
            run = this,
            stage = stage,
            lane = lane,
            provider = provider,
            model = model,
            items = items,
            startNanos = clock.nowNanos(),
        )
        TranslationPipelineDiagnostics.emitStageStart(
            identity = identity,
            lane = lane,
            stage = stage,
            totalMs = elapsedMsLocked(startNanos, span.startNanos),
            provider = provider,
            model = model,
            items = items,
        )
        return span
    }

    /** Counts a retry round; surfaced as `run_end retries`. */
    fun recordRetry(): Int = retries.incrementAndGet()

    internal fun recordStage(stage: TranslationTraceStage, durationNanos: Long) {
        synchronized(stageLock) {
            stageNanos[stage] = (stageNanos[stage] ?: 0L) + durationNanos
        }
        if (TranslationPipelineDiagnostics.isQueueStage(stage)) {
            schedule?.noteQueueWait(durationNanos / 1_000_000)
        }
    }

    /**
     * Settles one stage interval (called exactly once by the span). Repeated
     * intervals for the same stage sum in the stage map. Stage ends after the
     * run terminal are dropped: the terminal
     * summary already fired.
     */
    internal fun finishStage(
        span: TranslationStageSpan,
        outcome: TranslationTraceOutcome,
        error: Throwable?,
        items: Int,
        errorType: String?,
        errorCode: Long?,
        queueMs: Long,
        registeredProvider: TranslationTraceProvider? = null,
        provenProvider: TranslationTraceProvider? = null,
    ) {
        val now = clock.nowNanos()
        val durationNanos = (now - span.startNanos).coerceAtLeast(0)
        recordStage(span.stage, durationNanos)
        if (closed.get()) return
        val durationMs = durationNanos / 1_000_000
        val effectiveQueueMs = if (TranslationPipelineDiagnostics.isQueueStage(span.stage)) {
            durationMs
        } else {
            queueMs.coerceAtLeast(0)
        }
        TranslationPipelineDiagnostics.emitStageEnd(
            identity = identity,
            lane = span.lane,
            stage = span.stage,
            queueMs = effectiveQueueMs,
            durationMs = durationMs,
            totalMs = elapsedMsLocked(startNanos, now),
            provider = span.provider,
            model = span.model,
            items = items,
            outcome = outcome,
            error = TranslationPipelineDiagnostics.resolveError(error, errorType, errorCode),
            registeredProvider = registeredProvider,
            provenProvider = provenProvider,
        )
    }

    internal fun totalMsAt(nowNanos: Long): Long = elapsedMsLocked(startNanos, nowNanos)

    /**
     * Emits the terminal `run_end` exactly once and is idempotent.
     * `errorType`/`errorCode` come from the whitelisted classifier
     * unless explicitly overridden. Queue waits are eligible bottlenecks and
     * can dominate. Returns true on the first call.
     */
    fun end(
        outcome: TranslationTraceOutcome,
        error: Throwable? = null,
        errorType: String? = null,
        errorCode: Long? = null,
    ): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        val now = clock.nowNanos()
        val summary = synchronized(stageLock) { summarizeLocked(now) }
        val resolvedError = TranslationPipelineDiagnostics.resolveError(error, errorType, errorCode)
        schedule?.noteRunFinished(identity.page, summary.totalMs)
        schedule?.noteRunTerminal(outcome)
        TranslationPipelineDiagnostics.emitRunEnd(
            identity = identity,
            plan = planRef.get(),
            queuedMs = summary.queuedMs,
            totalMs = summary.totalMs,
            stageSumMs = summary.stageSumMs,
            bottleneck = summary.bottleneck,
            bottleneckMs = summary.bottleneckMs,
            retries = retries.get(),
            outcome = outcome,
            error = resolvedError,
        )
        return true
    }

    /** Requires [stageLock]. Deterministic tie-break: lowest stage ordinal. */
    private fun summarizeLocked(nowNanos: Long): RunSummary {
        var sumNanos = 0L
        var queuedNanos = 0L
        var bottleneck: TranslationTraceStage? = null
        var bottleneckNanos = 0L
        for (stage in TranslationTraceStage.entries) {
            val nanos = stageNanos[stage] ?: continue
            sumNanos += nanos
            if (TranslationPipelineDiagnostics.isQueueStage(stage)) queuedNanos += nanos
            if (nanos > bottleneckNanos) {
                bottleneckNanos = nanos
                bottleneck = stage
            }
        }
        return RunSummary(
            totalMs = elapsedMsLocked(startNanos, nowNanos),
            queuedMs = queuedNanos / 1_000_000,
            stageSumMs = sumNanos / 1_000_000,
            bottleneck = bottleneck,
            bottleneckMs = bottleneckNanos / 1_000_000,
        )
    }

    private fun elapsedMsLocked(startNanos: Long, nowNanos: Long): Long =
        (nowNanos - startNanos).coerceAtLeast(0) / 1_000_000

    private class RunSummary(
        val totalMs: Long,
        val queuedMs: Long,
        val stageSumMs: Long,
        val bottleneck: TranslationTraceStage?,
        val bottleneckMs: Long,
    )
}

/**
 * One timed stage interval. Ended exactly once; double-end is a no-op.
 * Queue-class stages auto-report their duration as queueMs; non-queue stages
 * accept an explicit [end] `queueMs` when the caller measured a preceding
 * wait separately.
 */
class TranslationStageSpan internal constructor(
    private val run: TranslationRunTrace?,
    val stage: TranslationTraceStage,
    val lane: TranslationTraceLane,
    val provider: TranslationTraceProvider,
    val model: TranslationTraceModel,
    val items: Int,
    internal val startNanos: Long,
) : AutoCloseable {
    private val done = AtomicBoolean(false)

    val isFinished: Boolean get() = done.get()

    /**
     * Settles the interval: sums into the run's stage map, emits
     * `stage_end` (detailed gate, or always when lagged/failed). Returns
     * true on the first call. Never throws, never suspends.
     *
     * Provider provenance: the span's `provider`
     * field is the best execution label the caller held at stage start;
     * [registeredProvider] (session-creation fact) and [provenProvider]
     * (post-inference proof) are appended as explicit trailing fields when
     * the caller can distinguish levels. Absent provenance emits nothing, so
     * existing schema lines stay byte-identical.
     */
    fun end(
        outcome: TranslationTraceOutcome = TranslationTraceOutcome.SUCCESS,
        error: Throwable? = null,
        items: Int = this.items,
        errorType: String? = null,
        errorCode: Long? = null,
        queueMs: Long = 0,
        registeredProvider: TranslationTraceProvider? = null,
        provenProvider: TranslationTraceProvider? = null,
    ): Boolean {
        if (!done.compareAndSet(false, true)) return false
        if (run != null) {
            run.finishStage(
                span = this,
                outcome = outcome,
                error = error,
                items = items,
                errorType = errorType,
                errorCode = errorCode,
                queueMs = queueMs,
                registeredProvider = registeredProvider,
                provenProvider = provenProvider,
            )
        }
        return true
    }

    /** `use { }`/finally-safe alias for [end] with the success outcome. */
    override fun close() {
        end()
    }

    internal companion object {
        /** Fail-open span for stage calls made outside any trace context. */
        val NO_OP: TranslationStageSpan = TranslationStageSpan(
            run = null,
            stage = TranslationTraceStage.ENGINE_SETUP,
            lane = TranslationTraceLane.NATIVE,
            provider = TranslationTraceProvider.NONE,
            model = TranslationTraceModel.NONE,
            items = 0,
            startNanos = 0,
        )

        /**
         * Fresh no-op span factory: same fail-open semantics as [NO_OP] without
         * sharing mutable close-state across unrelated callers.
         */
        fun createNoOp(
            stage: TranslationTraceStage,
            lane: TranslationTraceLane,
            provider: TranslationTraceProvider,
            model: TranslationTraceModel,
            items: Int,
        ): TranslationStageSpan = TranslationStageSpan(
            run = null,
            stage = stage,
            lane = lane,
            provider = provider,
            model = model,
            items = items,
            startNanos = 0,
        )
    }
}

/**
 * Process-wide access to the current run identity for deep synchronous code
 * Deep detector/segmenter/OCR/inpaint code calls
 * [currentRun]/[beginStage] without new parameters; the identity crosses
 * dispatcher hops via [TranslationTraceElement].
 */
object TranslationTrace {
    private val threadLocal = ThreadLocal<TranslationRunTrace?>()

    /** The run installed on the current thread, or null outside a trace. */
    fun currentRun(): TranslationRunTrace? = threadLocal.get()

    /**
     * Begins a stage on the current run, or returns a fresh fail-open no-op
     * span when no run is installed (callers outside a traced coroutine keep
     * working with zero effect). A fresh span is deliberately allocated per
     * call instead of sharing the [TranslationStageSpan.NO_OP] singleton: the
     * shared instance would be permanently consumed by the first `end()`.
     */
    fun beginStage(
        stage: TranslationTraceStage,
        lane: TranslationTraceLane = TranslationPipelineDiagnostics.defaultLane(stage),
        provider: TranslationTraceProvider = TranslationTraceProvider.NONE,
        model: TranslationTraceModel = TranslationTraceModel.NONE,
        items: Int = 0,
    ): TranslationStageSpan {
        val run = currentRun() ?: return TranslationStageSpan.createNoOp(
            stage = stage,
            lane = lane,
            provider = provider,
            model = model,
            items = items,
        )
        return run.beginStage(stage, lane, provider, model, items)
    }

    /** A coroutine context element carrying [run] across dispatcher hops. */
    fun elementFor(run: TranslationRunTrace): TranslationTraceElement = TranslationTraceElement(run)

    internal fun install(run: TranslationRunTrace?) {
        threadLocal.set(run)
    }

    internal fun restore(previous: TranslationRunTrace?) {
        threadLocal.set(previous)
    }
}

/**
 * Immutable ThreadContextElement: carries the current
 * [TranslationRunTrace] across dispatcher hops so synchronous deep code can
 * emit correlated events without new parameters. Nesting is supported: the
 * previous thread value is restored on exit.
 */
class TranslationTraceElement internal constructor(
    private val run: TranslationRunTrace,
) : AbstractCoroutineContextElement(TranslationTraceElement), ThreadContextElement<TranslationRunTrace?> {

    companion object Key : CoroutineContext.Key<TranslationTraceElement>

    override fun updateThreadContext(context: CoroutineContext): TranslationRunTrace? {
        val previous = TranslationTrace.currentRun()
        TranslationTrace.install(run)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: TranslationRunTrace?) {
        TranslationTrace.restore(oldState)
    }
}
