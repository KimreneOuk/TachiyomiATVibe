package eu.kanade.translation.engines.translator

import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.translator.retry.RequestRetryBudgetExhaustedException
import eu.kanade.translation.engines.translator.retry.classifyProviderFailure
import eu.kanade.translation.engines.translator.retry.currentRequestRetryAttempt
import eu.kanade.translation.engines.translator.retry.currentRequestRetryBudget
import eu.kanade.translation.engines.translator.retry.safeAdd
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay as coroutineDelay

/** Stable identity for one provider quota bucket. Never put a raw secret here. */
data class ProviderRequestKey(
    val backend: String,
    val model: String? = null,
    val credentialScope: String? = null,
) {
    init {
        require(backend.isNotBlank()) { "Provider backend must not be blank" }
    }

    internal fun normalized(): ProviderRequestKey = copy(
        backend = backend.trim().lowercase(Locale.ROOT),
        model = model?.trim()?.takeIf { it.isNotEmpty() },
        credentialScope = credentialScope?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** Privacy-safe bucket label for diagnostics. */
    fun diagnosticHash(): String = ShortHash.hash(
        listOf(backend, model.orEmpty(), credentialScope.orEmpty()).joinToString("|"),
    )
}

enum class AdmissionPriority {
    INTERACTIVE,
    BACKGROUND,
}

/** Metadata charged to one actual HTTP attempt. */
data class ProviderRequestMetadata(
    val key: ProviderRequestKey,
    val estimatedInputTokens: Int = 0,
    val reservedOutputTokens: Int = 0,
    val operation: String = "translation",
    val envelopeId: String? = null,
    val priority: AdmissionPriority = AdmissionPriority.BACKGROUND,
    val attempt: Int = 1,
    /** Explicit page runs keep envelope admissions attributable when no single run is current. */
    val traceRuns: List<TranslationRunTrace> = emptyList(),
    val traceProvider: TranslationTraceProvider = TranslationTraceProvider.REMOTE,
) {
    val estimatedTokens: Int
        get() = estimatedInputTokens.coerceAtLeast(0).toLong()
            .plus(reservedOutputTokens.coerceAtLeast(0).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}

/** Provider quota policy. Values are configuration data, not provider assumptions. */
data class ProviderQuotaPolicy(
    val requestsPerMinute: Int = 60,
    val tokensPerMinute: Int = 60_000,
    val minimumSpacingMs: Long = 0L,
    val maxInFlight: Int = 1,
    val maxForegroundWaitMs: Long = 15_000L,
    val interactiveMaxAgeMs: Long = 30_000L,
    val pollIntervalMs: Long = 50L,
    val quotaCooldownMs: Long = 60_000L,
    val windowMs: Long = 60_000L,
    /**
     * Fraction of the token window held back for
     * INTERACTIVE (reader) requests while one waits. While the bucket holds a
     * waiting INTERACTIVE request, a BACKGROUND request's effective token
     * limit shrinks to `tokensPerMinute * (1 - fraction)` and its request
     * limit to `requestsPerMinute - 1`, so a draining batch cannot consume
     * the reader's headroom. Interactive requests always see the full window;
     * an admitted reservation is never revoked.
     */
    val interactiveTokenReserveFraction: Double = 0.2,
) {
    init {
        require(requestsPerMinute > 0) { "requestsPerMinute must be > 0" }
        require(tokensPerMinute > 0) { "tokensPerMinute must be > 0" }
        require(minimumSpacingMs >= 0) { "minimumSpacingMs must be >= 0" }
        require(maxInFlight > 0) { "maxInFlight must be > 0" }
        require(maxForegroundWaitMs >= 0) { "maxForegroundWaitMs must be >= 0" }
        require(interactiveMaxAgeMs >= 0) { "interactiveMaxAgeMs must be >= 0" }
        require(pollIntervalMs > 0) { "pollIntervalMs must be > 0" }
        require(quotaCooldownMs >= 0) { "quotaCooldownMs must be >= 0" }
        require(windowMs > 0) { "windowMs must be > 0" }
        require(interactiveTokenReserveFraction > 0.0 && interactiveTokenReserveFraction <= 1.0) {
            "interactiveTokenReserveFraction must be in (0.0, 1.0]"
        }
    }
}

/** Clock/delay seam used by the governor and transport tests. */
interface ProviderRequestClock {
    fun nowEpochMs(): Long

    suspend fun delay(millis: Long)
}

object SystemProviderRequestClock : ProviderRequestClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()

    override suspend fun delay(millis: Long) {
        if (millis > 0) coroutineDelay(millis)
    }
}

/** Makes reader/manual priority explicit without creating a second quota lane. */
class ProviderRequestPriorityContext(
    val priority: AdmissionPriority,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderRequestPriorityContext>
}

suspend fun <T> withProviderRequestPriority(
    priority: AdmissionPriority,
    block: suspend () -> T,
): T = withContext(ProviderRequestPriorityContext(priority)) { block() }

suspend fun currentProviderRequestPriority(): AdmissionPriority =
    currentCoroutineContext()[ProviderRequestPriorityContext]?.priority ?: AdmissionPriority.BACKGROUND

data class ProviderUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    val totalTokens: Int?
        get() = if (inputTokens == null && outputTokens == null) {
            null
        } else {
            (inputTokens ?: 0).coerceAtLeast(0).toLong()
                .plus((outputTokens ?: 0).coerceAtLeast(0).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
}

/** Optional result wrapper for callers that have provider-reported token usage. */
data class ProviderHttpResult<T>(
    val value: T,
    val usage: ProviderUsage? = null,
    val retryAfterMillis: Long? = null,
)

enum class ProviderFailureKind {
    NETWORK,
    RATE_LIMIT,
    QUOTA_EXHAUSTED,
    SERVER,
    AUTHENTICATION,
    REFUSAL,
    PROTOCOL,
    CONFIGURATION,
    SOURCE,
}

enum class ProviderFailureRetryability {
    RETRY_NOW,
    RETRY_AFTER,
    PAUSE,
    TERMINAL,
}

/** Safe, provider-neutral failure metadata. It never carries prompts or response bodies. */
data class ProviderFailure(
    val kind: ProviderFailureKind,
    val retryability: ProviderFailureRetryability,
    val statusCode: Int? = null,
    val providerCode: String? = null,
    val retryAfterMillis: Long? = null,
    val retryAfterAtEpochMs: Long? = null,
    val safeSummary: String,
    val requestId: String? = null,
    val attempt: Int? = null,
)

open class ProviderFailureException(
    val failure: ProviderFailure,
    cause: Throwable? = null,
) : Exception(failure.safeSummary, cause)

/** Admission could not safely wait within the foreground budget. */
class ProviderRequestPausedException(
    val key: ProviderRequestKey,
    val nextEligibleRetryAtEpochMs: Long?,
    val reason: String,
) : ProviderFailureException(
    ProviderFailure(
        kind = ProviderFailureKind.QUOTA_EXHAUSTED,
        retryability = ProviderFailureRetryability.PAUSE,
        retryAfterAtEpochMs = nextEligibleRetryAtEpochMs,
        safeSummary = reason,
    ),
)

sealed interface ProviderAdmissionDecision {
    data class Admitted(val permit: ProviderRequestPermit) : ProviderAdmissionDecision

    data class Deferred(
        val nextEligibleRetryAtEpochMs: Long?,
        val reason: String,
    ) : ProviderAdmissionDecision
}

/** Opaque permit held only while one HTTP operation is in flight. */
class ProviderRequestPermit internal constructor(
    internal val key: ProviderRequestKey,
    internal val reservation: Any,
)

fun interface ProviderRequestDiagnostics {
    fun onEvent(event: ProviderAdmissionEvent)
}

data class ProviderAdmissionEvent(
    val keyHash: String,
    val operation: String,
    val priority: AdmissionPriority,
    val attempt: Int,
    val waitMs: Long,
    val estimatedTokens: Int,
    val actualTokens: Int?,
    val cooldownSource: String?,
    val outcome: String,
)

/**
 * Process-wide quota-aware admission for all remote translation requests.
 *
 * Each key has a rolling request/token window, a minimum spacing, an in-flight
 * cap, and a shared cooldown. Waiters are selected with bounded interactive
 * priority: an interactive waiter goes first until an older background waiter
 * reaches [ProviderQuotaPolicy.interactiveMaxAgeMs], at which point FIFO age
 * wins. Waiting is cancellation-safe and never holds the state mutex.
 */
class ProviderRequestGovernor(
    private val policy: (ProviderRequestKey) -> ProviderQuotaPolicy = { ProviderQuotaPolicy() },
    private val clock: ProviderRequestClock = SystemProviderRequestClock,
    private val diagnostics: ProviderRequestDiagnostics? = null,
) {
    private data class Reservation(
        val admittedAtEpochMs: Long,
        val estimatedTokens: Int,
        var actualTokens: Int? = null,
    )

    private data class Waiter(
        val sequence: Long,
        val metadata: ProviderRequestMetadata,
        val enqueuedAtEpochMs: Long,
    )

    private data class Bucket(
        val reservations: ArrayDeque<Reservation> = ArrayDeque(),
        val waiters: MutableList<Waiter> = mutableListOf(),
        var inFlight: Int = 0,
        var lastAdmissionAtEpochMs: Long? = null,
        var cooldownUntilEpochMs: Long = 0L,
        var cooldownSource: String? = null,
    )

    private sealed interface WaitResult {
        data class Granted(val permit: ProviderRequestPermit) : WaitResult

        data class Wait(val millis: Long) : WaitResult

        data class Defer(val decision: ProviderAdmissionDecision.Deferred) : WaitResult
    }

    private val mutex = Mutex()
    private val buckets = mutableMapOf<ProviderRequestKey, Bucket>()
    private var nextSequence = 0L
    private val releaseWakeupSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun traceRuns(metadata: ProviderRequestMetadata): List<TranslationRunTrace> =
        (metadata.traceRuns + TranslationTrace.currentRuns()).distinct()

    suspend fun admit(metadata: ProviderRequestMetadata): ProviderAdmissionDecision {
        val waitSpans = traceRuns(metadata).map { run ->
            run.beginStage(
                stage = TranslationTraceStage.PROVIDER_GOVERNOR_WAIT,
                lane = TranslationTraceLane.SCHEDULER,
                provider = metadata.traceProvider,
            )
        }
        fun finishWait(outcome: TranslationTraceOutcome, failure: Throwable? = null) {
            waitSpans.forEach { it.end(outcome, error = failure) }
        }
        val normalizedMetadata = metadata.copy(
            key = metadata.key.normalized(),
            estimatedInputTokens = metadata.estimatedInputTokens.coerceAtLeast(0),
            reservedOutputTokens = metadata.reservedOutputTokens.coerceAtLeast(0),
            attempt = metadata.attempt.coerceAtLeast(1),
        )
        val key = normalizedMetadata.key
        val quota = policy(key)
        val waiter = try {
            mutex.withLock {
                val created = Waiter(
                    sequence = nextSequence++,
                    metadata = normalizedMetadata,
                    enqueuedAtEpochMs = clock.nowEpochMs(),
                )
                buckets.getOrPut(key) { Bucket() }.waiters += created
                created
            }
        } catch (cancelled: CancellationException) {
            finishWait(TranslationTraceOutcome.CANCELLED, cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            finishWait(TranslationTraceOutcome.FAILURE, failure)
            throw failure
        }

        try {
            while (true) {
                val result = evaluate(waiter, quota)
                when (result) {
                    is WaitResult.Granted -> {
                        emit(
                            waiter.metadata,
                            waitMs = (clock.nowEpochMs() - waiter.enqueuedAtEpochMs).coerceAtLeast(0),
                            actualTokens = null,
                            cooldownSource = null,
                            outcome = "admitted",
                        )
                        finishWait(TranslationTraceOutcome.SUCCESS)
                        return ProviderAdmissionDecision.Admitted(result.permit)
                    }

                    is WaitResult.Defer -> {
                        emit(
                            waiter.metadata,
                            waitMs = (clock.nowEpochMs() - waiter.enqueuedAtEpochMs).coerceAtLeast(0),
                            actualTokens = null,
                            cooldownSource = null,
                            outcome = "deferred",
                        )
                        finishWait(TranslationTraceOutcome.PAUSE)
                        return result.decision
                    }

                    is WaitResult.Wait -> {
                        if (clock is SystemProviderRequestClock) {
                            withTimeoutOrNull(result.millis.coerceAtLeast(1L)) {
                                releaseWakeupSignal.first()
                            }
                        } else {
                            clock.delay(result.millis.coerceAtLeast(1L))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            mutex.withLock { removeWaiter(key, waiter) }
            finishWait(TranslationTraceOutcome.CANCELLED, e)
            throw e
        } catch (failure: Throwable) {
            finishWait(TranslationTraceOutcome.FAILURE, failure)
            throw failure
        }
    }

    suspend fun <T> execute(
        metadata: ProviderRequestMetadata,
        block: suspend () -> ProviderHttpResult<T>,
    ): T = executeResult(metadata, block).value

    /** Convenience boundary for operations that do not expose provider usage. */
    suspend fun <T> executeValue(
        metadata: ProviderRequestMetadata,
        block: suspend () -> T,
    ): T = executeResult(metadata) { ProviderHttpResult(block()) }.value

    private suspend fun <T> executeResult(
        metadata: ProviderRequestMetadata,
        block: suspend () -> ProviderHttpResult<T>,
    ): ProviderHttpResult<T> {
        val decision = admit(metadata)
        val permit = when (decision) {
            is ProviderAdmissionDecision.Admitted -> decision.permit
            is ProviderAdmissionDecision.Deferred -> throw ProviderRequestPausedException(
                key = metadata.key,
                nextEligibleRetryAtEpochMs = decision.nextEligibleRetryAtEpochMs,
                reason = decision.reason,
            )
        }
        // A semantic envelope owns one hard request budget. Charge it only
        // after admission succeeds so waiting/deferred requests do not burn a
        // network attempt. Releasing the permit here is essential: a budget
        // exhaustion is a typed pause, not an in-flight provider operation.
        currentRequestRetryBudget()?.let { budget ->
            if (!budget.tryConsumeAttempt()) {
                release(permit, usage = null, metadata = metadata, outcome = "budget_exhausted")
                throw RequestRetryBudgetExhaustedException(budget)
            }
            currentRequestRetryAttempt()?.recordAttempt()
        }
        var result: ProviderHttpResult<T>? = null
        val requestRuns = traceRuns(metadata)
        val translationSpans = if (
            metadata.operation.contains("translation", ignoreCase = true) ||
            metadata.operation.contains("contextual", ignoreCase = true)
        ) {
            requestRuns.map { run ->
                run.beginStage(
                    stage = TranslationTraceStage.TRANSLATE,
                    lane = TranslationTraceLane.PROVIDER,
                    provider = metadata.traceProvider,
                    items = 1,
                )
            }
        } else {
            emptyList()
        }
        val providerLaneTokens = requestRuns.mapNotNull { run ->
            run.schedule?.enterLane(TranslationTraceLane.PROVIDER)
        }
        var traceOutcome = TranslationTraceOutcome.FAILURE
        val requestStartedAt = clock.nowEpochMs()
        val requestId = TranslationPipelineDiagnostics.idGenerator.nextHttpRequestId()
        return try {
            result = block()
            result!!.retryAfterMillis?.let { retryAfter ->
                applyCooldown(metadata.key, retryAfter, "response")
            }
            traceOutcome = TranslationTraceOutcome.SUCCESS
            result!!
        } catch (e: CancellationException) {
            traceOutcome = TranslationTraceOutcome.CANCELLED
            throw e
        } catch (e: ProviderFailureException) {
            traceOutcome = if (e.failure.retryability == ProviderFailureRetryability.PAUSE) {
                TranslationTraceOutcome.PAUSE
            } else {
                TranslationTraceOutcome.FAILURE
            }
            recordFailure(metadata, e.failure)
            throw e
        } catch (e: Exception) {
            traceOutcome = if (e is ProviderRequestPausedException) {
                TranslationTraceOutcome.PAUSE
            } else {
                TranslationTraceOutcome.FAILURE
            }
            recordFailure(metadata, classifyProviderFailure(e, metadata.key.backend, clock.nowEpochMs()))
            throw e
        } finally {
            try {
                release(permit, result?.usage, metadata)
            } finally {
                TranslationPipelineDiagnostics.recordHttpRequest(
                    identity = requestRuns.firstOrNull()?.identity,
                    requestId = requestId,
                    envelopeRaw = metadata.envelopeId,
                    operation = metadata.operation,
                    attempt = metadata.attempt,
                    durationMs = (clock.nowEpochMs() - requestStartedAt).coerceAtLeast(0L),
                    outcome = traceOutcome,
                    estimatedInputTokens = metadata.estimatedInputTokens,
                    reservedOutputTokens = metadata.reservedOutputTokens,
                    inputTokens = result?.usage?.inputTokens,
                    outputTokens = result?.usage?.outputTokens,
                )
                providerLaneTokens.forEach { it.close() }
                translationSpans.forEach { it.end(traceOutcome) }
            }
        }
    }

    /** Compatibility alias for callers that prefer to make usage explicit. */
    suspend fun <T> executeWithUsage(
        metadata: ProviderRequestMetadata,
        block: suspend () -> ProviderHttpResult<T>,
    ): ProviderHttpResult<T> = executeResult(metadata, block)

    /** Records a response failure parsed after the HTTP permit is released. */
    suspend fun recordFailure(
        metadata: ProviderRequestMetadata,
        failure: ProviderFailure,
    ) {
        val key = metadata.key.normalized()
        val now = clock.nowEpochMs()
        val quota = policy(key)
        val cooldownMs = when {
            failure.retryAfterMillis != null -> failure.retryAfterMillis
            failure.retryAfterAtEpochMs != null -> (failure.retryAfterAtEpochMs - now).coerceAtLeast(0L)
            failure.kind == ProviderFailureKind.QUOTA_EXHAUSTED -> quota.quotaCooldownMs
            else -> null
        }
        if (cooldownMs != null) applyCooldown(key, cooldownMs, "${failure.kind.name.lowercase(Locale.ROOT)}")
    }

    suspend fun clearCooldown(key: ProviderRequestKey) {
        mutex.withLock {
            buckets[key.normalized()]?.apply {
                cooldownUntilEpochMs = 0L
                cooldownSource = null
            }
        }
    }

    private suspend fun evaluate(waiter: Waiter, quota: ProviderQuotaPolicy): WaitResult = mutex.withLock {
        val key = waiter.metadata.key
        val bucket = buckets.getOrPut(key) { Bucket() }
        if (waiter !in bucket.waiters) {
            return@withLock WaitResult.Defer(
                ProviderAdmissionDecision.Deferred(null, "Provider request waiter was cancelled"),
            )
        }
        val now = clock.nowEpochMs()
        prune(bucket, quota, now)
        val selected = selectWaiter(bucket.waiters, now, quota)
        if (selected !== waiter) {
            return@withLock waitOrDefer(waiter, now, now + quota.pollIntervalMs, quota)
        }

        val tokenCost = waiter.metadata.estimatedTokens
        // While the bucket holds at least one waiting
        // INTERACTIVE request, a BACKGROUND request sees reduced limits so a
        // draining batch cannot consume the reader's headroom. Interactive
        // requests always ride the full window, and the reduced token limit
        // never falls below this request's own cost (a single oversized
        // envelope stays admissible). Admitted reservations are never revoked.
        val interactiveWaiterWaiting = bucket.waiters.any {
            it.metadata.priority == AdmissionPriority.INTERACTIVE
        }
        val backgroundReserveApplies =
            waiter.metadata.priority == AdmissionPriority.BACKGROUND && interactiveWaiterWaiting
        val effectiveRequestsPerMinute =
            if (backgroundReserveApplies) quota.requestsPerMinute - 1 else quota.requestsPerMinute
        val effectiveTokensPerMinute = if (backgroundReserveApplies) {
            (quota.tokensPerMinute * (1.0 - quota.interactiveTokenReserveFraction)).toLong().coerceAtLeast(0L)
        } else {
            quota.tokensPerMinute.toLong()
        }
        val tokenLimit = maxOf(effectiveTokensPerMinute, tokenCost.toLong())
        val requestsReady = bucket.reservations.size < effectiveRequestsPerMinute
        val tokensReady = bucket.reservations.sumOf { (it.actualTokens ?: it.estimatedTokens).toLong() } +
            tokenCost.toLong() <= tokenLimit
        val inFlightReady = bucket.inFlight < quota.maxInFlight
        val cooldownReady = now >= bucket.cooldownUntilEpochMs
        val spacingReady = bucket.lastAdmissionAtEpochMs == null ||
            quota.minimumSpacingMs == 0L ||
            now >= safeAdd(bucket.lastAdmissionAtEpochMs!!, quota.minimumSpacingMs)
        if (requestsReady && tokensReady && inFlightReady && cooldownReady && spacingReady) {
            val reservation = Reservation(now, tokenCost)
            bucket.reservations.addLast(reservation)
            bucket.inFlight++
            bucket.lastAdmissionAtEpochMs = now
            bucket.waiters.remove(waiter)
            return@withLock WaitResult.Granted(
                ProviderRequestPermit(key = key, reservation = reservation),
            )
        }

        val next = nextEligibleAt(bucket, quota, now, tokenCost, tokenLimit)
        waitOrDefer(waiter, now, next, quota)
    }

    private fun waitOrDefer(
        waiter: Waiter,
        now: Long,
        nextEligibleAt: Long,
        quota: ProviderQuotaPolicy,
    ): WaitResult {
        val waitMs = (nextEligibleAt - now).coerceAtLeast(quota.pollIntervalMs)
        val elapsed = (now - waiter.enqueuedAtEpochMs).coerceAtLeast(0L)
        val remaining = (quota.maxForegroundWaitMs - elapsed).coerceAtLeast(0L)
        if (waitMs > remaining) {
            buckets[waiter.metadata.key]?.waiters?.remove(waiter)
            return WaitResult.Defer(
                ProviderAdmissionDecision.Deferred(
                    nextEligibleRetryAtEpochMs = nextEligibleAt.takeUnless { it == Long.MAX_VALUE },
                    reason = "Provider admission deferred beyond the foreground wait budget",
                ),
            )
        }
        return WaitResult.Wait(waitMs.coerceAtMost(quota.pollIntervalMs))
    }

    private fun nextEligibleAt(
        bucket: Bucket,
        quota: ProviderQuotaPolicy,
        now: Long,
        tokenCost: Int,
        tokenLimit: Long,
    ): Long {
        var next = now + quota.pollIntervalMs
        next = maxOf(next, bucket.cooldownUntilEpochMs)
        if (quota.minimumSpacingMs > 0L) {
            bucket.lastAdmissionAtEpochMs?.let { next = maxOf(next, safeAdd(it, quota.minimumSpacingMs)) }
        }
        if (bucket.inFlight >= quota.maxInFlight) next = maxOf(next, safeAdd(now, quota.pollIntervalMs))
        if (bucket.reservations.size >= quota.requestsPerMinute) {
            bucket.reservations.firstOrNull()?.let { next = maxOf(next, safeAdd(it.admittedAtEpochMs, quota.windowMs)) }
        }
        val tokenTotal = bucket.reservations.sumOf { (it.actualTokens ?: it.estimatedTokens).toLong() }
        if (tokenTotal + tokenCost.toLong() > tokenLimit.toLong()) {
            var remaining = tokenTotal + tokenCost.toLong() - tokenLimit.toLong()
            for (reservation in bucket.reservations) {
                remaining -= reservation.actualTokens ?: reservation.estimatedTokens
                if (remaining <= 0) {
                    next = maxOf(next, safeAdd(reservation.admittedAtEpochMs, quota.windowMs))
                    break
                }
            }
        }
        return next
    }

    private fun selectWaiter(waiters: List<Waiter>, now: Long, quota: ProviderQuotaPolicy): Waiter? {
        val starvingBackground = waiters
            .asSequence()
            .filter { it.metadata.priority == AdmissionPriority.BACKGROUND }
            .filter { now - it.enqueuedAtEpochMs >= quota.interactiveMaxAgeMs }
            .minByOrNull { it.sequence }
        if (starvingBackground != null) return starvingBackground
        return waiters.minWithOrNull(
            compareBy<Waiter> {
                val age = (now - it.enqueuedAtEpochMs).coerceAtLeast(0L)
                if (it.metadata.priority == AdmissionPriority.INTERACTIVE && age < quota.interactiveMaxAgeMs) 0 else 1
            }.thenBy { it.sequence },
        )
    }

    private fun prune(bucket: Bucket, quota: ProviderQuotaPolicy, now: Long) {
        while (bucket.reservations.firstOrNull()?.let { now - it.admittedAtEpochMs >= quota.windowMs } == true) {
            bucket.reservations.removeFirst()
        }
    }

    /**
     *  DR-D all-or-nothing nested admission: releases a permit obtained
     * via [admit] WITHOUT executing the request. Used by the Batch sub-limit
     * gate when the inner (provider) bucket defers after the outer (sub-limit)
     * bucket already granted — the outer permit is given back before waiting,
     * never held across a defer.
     */
    suspend fun releaseAdmitted(
        permit: ProviderRequestPermit,
        metadata: ProviderRequestMetadata,
        outcome: String = "released_unexecuted",
    ) {
        release(permit, usage = null, metadata = metadata.copy(key = permit.key), outcome = outcome)
    }

    private suspend fun release(
        permit: ProviderRequestPermit,
        usage: ProviderUsage?,
        metadata: ProviderRequestMetadata,
        outcome: String = "completed",
    ) {
        mutex.withLock {
            val bucket = buckets[permit.key] ?: return@withLock
            val reservation = permit.reservation as? Reservation ?: return@withLock
            reservation.actualTokens = usage?.totalTokens
            bucket.inFlight = (bucket.inFlight - 1).coerceAtLeast(0)
            emitUnsafe(
                metadata,
                waitMs = 0L,
                actualTokens = usage?.totalTokens,
                cooldownSource = bucket.cooldownSource,
                outcome = outcome,
            )
        }
        releaseWakeupSignal.tryEmit(Unit)
    }

    private suspend fun applyCooldown(key: ProviderRequestKey, durationMs: Long, source: String) {
        val now = clock.nowEpochMs()
        val safeDuration = durationMs.coerceAtLeast(0L)
        val until = safeAdd(now, safeDuration)
        mutex.withLock {
            val bucket = buckets.getOrPut(key.normalized()) { Bucket() }
            if (until > bucket.cooldownUntilEpochMs) {
                bucket.cooldownUntilEpochMs = until
                bucket.cooldownSource = source
            }
        }
    }

    private fun removeWaiter(key: ProviderRequestKey, waiter: Waiter) {
        buckets[key]?.waiters?.remove(waiter)
    }

    private fun emit(
        metadata: ProviderRequestMetadata,
        waitMs: Long,
        actualTokens: Int?,
        cooldownSource: String?,
        outcome: String,
    ) {
        diagnostics?.onEvent(
            ProviderAdmissionEvent(
                keyHash = metadata.key.diagnosticHash(),
                operation = metadata.operation,
                priority = metadata.priority,
                attempt = metadata.attempt,
                waitMs = waitMs,
                estimatedTokens = metadata.estimatedTokens,
                actualTokens = actualTokens,
                cooldownSource = cooldownSource,
                outcome = outcome,
            ),
        )
        emitLog(metadata, waitMs, actualTokens, cooldownSource, outcome)
    }

    private fun emitUnsafe(
        metadata: ProviderRequestMetadata,
        waitMs: Long,
        actualTokens: Int?,
        cooldownSource: String?,
        outcome: String,
    ) {
        diagnostics?.onEvent(
            ProviderAdmissionEvent(
                keyHash = metadata.key.diagnosticHash(),
                operation = metadata.operation,
                priority = metadata.priority,
                attempt = metadata.attempt,
                waitMs = waitMs,
                estimatedTokens = metadata.estimatedTokens,
                actualTokens = actualTokens,
                cooldownSource = cooldownSource,
                outcome = outcome,
            ),
        )
        emitLog(metadata, waitMs, actualTokens, cooldownSource, outcome)
    }

    private fun emitLog(
        metadata: ProviderRequestMetadata,
        waitMs: Long,
        actualTokens: Int?,
        cooldownSource: String?,
        outcome: String,
    ) {
        if (outcome == "admitted" && waitMs == 0L && actualTokens == null) return
        logcat(tag = "ProviderRequestGovernor", priority = LogPriority.DEBUG) {
            "event=provider_admission key=${metadata.key.diagnosticHash()} " +
                "operation=${metadata.operation} priority=${metadata.priority.name.lowercase(Locale.ROOT)} " +
                "attempt=${metadata.attempt} waitMs=$waitMs estimatedTokens=${metadata.estimatedTokens} " +
                "actualTokens=${actualTokens ?: 0} cooldownSource=${cooldownSource ?: "none"} outcome=$outcome"
        }
    }

    companion object {
        fun defaultPolicy(key: ProviderRequestKey): ProviderQuotaPolicy = when {
            key.backend in setOf("desktop", "local", "mlkit", "builtin", "offline") -> ProviderQuotaPolicy(
                requestsPerMinute = 1_000,
                tokensPerMinute = Int.MAX_VALUE,
                minimumSpacingMs = 0L,
                maxInFlight = 2,
                maxForegroundWaitMs = 15_000,
            )
            else -> ProviderQuotaPolicy(
                minimumSpacingMs = 0L,
            )
        }
    }
}

/** Application-lifetime governor shared by batch, reader, auto, and settings calls. */
object SharedProviderRequestGovernor {
    val instance: ProviderRequestGovernor = ProviderRequestGovernor(
        policy = { key -> ProviderRequestGovernor.defaultPolicy(key) },
    )
}

/**
 * The Batch aggregate sub-limit allows one request per credential, shared by
 * all Batch traffic (analysis chunks and translation envelopes) — never two
 * separate pools. A measured constant,
 * not a flag; Manual/Auto/reader INTERACTIVE requests never enter this bucket.
 *
 * Mechanism (DR-D, accepted recommendation): a SECOND rolling-window
 * governor bucket nested BENEATH the shared provider bucket.
 *  - Bucket 1 = the existing per-provider bucket ([SharedProviderRequestGovernor]),
 *    untouched: interactive reserve, starvation guard, cooldowns all hold.
 *  - Bucket 2 = the sub-limit bucket keyed credential-wide
 *    (`model = null`, [ProviderRequestKey.credentialScope] preserved), 15
 *    requests per any rolling 60 s window — burst tolerance from the window,
 *    never a forced 4-second sleep. Tokens are not re-counted here (bucket 1
 *    already enforces the TPM window); the DR-C per-provider TPM values are
 *    deferred to measurement, so the sub-limit is RPM-only in v1.
 */
object BatchProviderSublimit {

    /** The aggregate Batch allowance (requests per rolling 60 s window). */
    const val BATCH_REQUESTS_PER_MINUTE = 15

    /**
     * Credential-wide, model-agnostic sub-limit bucket key (DR-D): same
     * backend + credential scope as the real request, no model dimension, so
     * every Batch request to one credential competes for the same 15.
     */
    fun batchSublimitKey(key: ProviderRequestKey): ProviderRequestKey =
        ProviderRequestKey(
            backend = key.backend,
            model = null,
            credentialScope = key.credentialScope,
        )

    /**
     * Sub-limit bucket policy: pacing comes from the rolling window
     * (`minimumSpacingMs = 0` — no forced spacing), `maxInFlight = 1`
     * serializes Batch traffic so the all-or-nothing nested admission never
     * holds a permit while another Batch request is in flight.
     */
    val policy: ProviderQuotaPolicy = ProviderQuotaPolicy(
        requestsPerMinute = BATCH_REQUESTS_PER_MINUTE,
        tokensPerMinute = Int.MAX_VALUE,
        minimumSpacingMs = 0L,
        maxInFlight = 1,
        maxForegroundWaitMs = 15_000L,
        windowMs = 60_000L,
    )
}

/**
 * Nested bucket-2 admission gate (DR-D): admits the Batch sub-limit FIRST,
 * then runs [block] which admits through the shared provider bucket
 * (bucket 1) itself. All-or-nothing: if bucket 1 defers after bucket 2
 * granted, bucket 2's permit is released BEFORE the pause propagates —
 * permits are short-lived because `maxInFlight = 1` serializes Batch traffic.
 * Interactive requests skip this gate entirely, so reader latency is
 * structurally unaffected; the bucket-1 interactive reserve and starvation
 * guard are untouched.
 */
// `open` so the coordinator can wrap the gate with the overlap scheduler's
// remote-window signalling subclass. Admission semantics remain unchanged;
// default construction uses this implementation directly.
open class BatchRequestSublimitGate(
    private val clock: ProviderRequestClock = SystemProviderRequestClock,
) {

    private val sublimitGovernor = ProviderRequestGovernor(
        policy = { BatchProviderSublimit.policy },
        clock = clock,
    )

    open suspend fun <T> executeBatch(
        metadata: ProviderRequestMetadata,
        block: suspend () -> T,
    ): T {
        val sublimitMetadata = metadata.copy(key = BatchProviderSublimit.batchSublimitKey(metadata.key))
        val decision = sublimitGovernor.admit(sublimitMetadata)
        val permit = when (decision) {
            is ProviderAdmissionDecision.Admitted -> decision.permit
            is ProviderAdmissionDecision.Deferred -> throw ProviderRequestPausedException(
                key = metadata.key,
                nextEligibleRetryAtEpochMs = decision.nextEligibleRetryAtEpochMs,
                reason = "Batch sub-limit deferred: ${decision.reason}",
            )
        }
        // The permit is ALWAYS given back when this call site exits — success,
        // pause (bucket 1 deferred after bucket 2 granted: released BEFORE the
        // pause propagates), or cancellation. `maxInFlight = 1` keeps permits
        // short-lived; the sub-limit's quota accounting lives entirely in the
        // rolling-window reservation.
        try {
            return block()
        } finally {
            sublimitGovernor.releaseAdmitted(permit, sublimitMetadata)
        }
    }
}

/**
 *  the process-wide Batch sub-limit gate. The sub-limit is
 * ONE allowance per credential (DR-C) — every production Batch executor must
 * share THIS gate exactly like [SharedProviderRequestGovernor]; a per-instance
 * default would create independent 15-RPM pools ("two pools of 15"). Test
 * code may still construct private [BatchRequestSublimitGate]s for isolation.
 */
object SharedBatchRequestSublimitGate {
    val instance: BatchRequestSublimitGate = BatchRequestSublimitGate()
}
