package eu.kanade.translation.engines.translator.retry
import eu.kanade.translation.diagnostics.BatchDiagnosticReason
import eu.kanade.translation.diagnostics.BatchEnvelopeLifecycle
import eu.kanade.translation.diagnostics.BatchTranslationDiagnostics
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderRequestClock
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.ProviderRequestPausedException
import eu.kanade.translation.engines.translator.SharedProviderRequestGovernor
import eu.kanade.translation.engines.translator.SystemProviderRequestClock
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Hard request ceiling shared by one semantic envelope.
 *
 * The budget is deliberately owned by the caller and carried through a
 * coroutine context. It is not process-global state: each batch envelope gets
 * one instance, while ordinary/manual callers may omit it and retain the
 * existing transport-only retry behavior. Real HTTP boundaries consume the
 * budget in [ProviderRequestGovernor]; legacy/test callbacks without that
 * boundary are charged once per retry-wrapper attempt through a
 * coroutine-local marker.
 */
class RequestRetryBudget(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val consumedAttempts = AtomicInteger(0)

    val attemptsUsed: Int
        get() = consumedAttempts.get()

    val remainingAttempts: Int
        get() = (maxAttempts - attemptsUsed).coerceAtLeast(0)

    val isExhausted: Boolean
        get() = attemptsUsed >= maxAttempts

    init {
        require(maxAttempts > 0) { "maxAttempts must be > 0" }
    }

    /** Claims one actual (or compatibility logical) request attempt. */
    internal fun tryConsumeAttempt(): Boolean {
        while (true) {
            val current = consumedAttempts.get()
            if (current >= maxAttempts) return false
            if (consumedAttempts.compareAndSet(current, current + 1)) return true
        }
    }

    internal fun consumeAttemptOrThrow() {
        if (!tryConsumeAttempt()) throw RequestRetryBudgetExhaustedException(this)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
    }
}

internal class RequestRetryBudgetContext(
    val budget: RequestRetryBudget,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestRetryBudgetContext>
}

/**
 * Per-[withTranslationRetry] invocation marker used to avoid charging one
 * logical request twice when a provider reaches the governor below the retry
 * wrapper. Nested wrappers share observations through [parent].
 */
internal class RequestRetryAttemptContext(
    private val parent: RequestRetryAttemptContext? = null,
) : AbstractCoroutineContextElement(Key) {
    private val observed = AtomicBoolean(false)

    internal val wasObserved: Boolean
        get() = observed.get()

    internal fun recordAttempt() {
        observed.set(true)
        parent?.recordAttempt()
    }

    companion object Key : CoroutineContext.Key<RequestRetryAttemptContext>
}

suspend fun <T> withRequestRetryBudget(
    budget: RequestRetryBudget,
    block: suspend () -> T,
): T = withContext(RequestRetryBudgetContext(budget)) { block() }

internal suspend fun currentRequestRetryBudget(): RequestRetryBudget? =
    currentCoroutineContext()[RequestRetryBudgetContext]?.budget

internal suspend fun currentRequestRetryAttempt(): RequestRetryAttemptContext? =
    currentCoroutineContext()[RequestRetryAttemptContext]

/** Typed pause raised before an HTTP attempt when an envelope has no budget left. */
class RequestRetryBudgetExhaustedException(
    val budget: RequestRetryBudget,
) : ProviderFailureException(
    ProviderFailure(
        kind = ProviderFailureKind.NETWORK,
        retryability = ProviderFailureRetryability.PAUSE,
        safeSummary = "Semantic envelope request budget exhausted",
        attempt = budget.attemptsUsed,
    ),
)

/**
 * Bounded transport retry for transient provider failures.
 *
 * The retry budget is deliberately finite and is independent from semantic
 * retries. Provider implementations admit each actual HTTP attempt at their
 * network boundary; [requestMetadata] is available for small adapters/tests
 * whose attempt callback is itself the HTTP operation. When [retryBudget] is
 * present (or inherited from the coroutine context), the governor consumes
 * one shared envelope attempt for every admitted HTTP operation.
 */
internal suspend fun <T> withTranslationRetry(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 1_000L,
    logTag: String,
    envelopePageKeys: Collection<String>? = null,
    clock: ProviderRequestClock = SystemProviderRequestClock,
    maxRetryDelayMs: Long = 30_000L,
    requestMetadata: ProviderRequestMetadata? = null,
    governor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
    retryBudget: RequestRetryBudget? = null,
    block: suspend () -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be > 0" }
    require(baseDelayMs >= 0) { "baseDelayMs must be >= 0" }
    require(maxRetryDelayMs >= 0) { "maxRetryDelayMs must be >= 0" }

    val activeBudget = retryBudget ?: currentRequestRetryBudget()

    suspend fun runLoop(): T {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            activeBudget?.let { budget ->
                if (budget.isExhausted) throw RequestRetryBudgetExhaustedException(budget)
            }
            attempt++
            envelopePageKeys?.let { pageKeys ->
                BatchTranslationDiagnostics.envelopeLifecycle(
                    phase = BatchEnvelopeLifecycle.PROVIDER_REQUEST,
                    pageKeys = pageKeys,
                    attempt = attempt,
                    reason = null,
                )
            }
            val parentAttemptContext = currentRequestRetryAttempt()
            val attemptContext = activeBudget?.let {
                RequestRetryAttemptContext(parent = parentAttemptContext)
            }
            try {
                val metadata = requestMetadata?.copy(attempt = attempt)
                val invokeRequest: suspend () -> T = {
                    if (metadata == null) {
                        block()
                    } else {
                        governor.executeValue(metadata, block)
                    }
                }
                val value = if (attemptContext == null) {
                    invokeRequest()
                } else {
                    withContext(attemptContext) { invokeRequest() }
                }
                if (activeBudget != null && attemptContext?.wasObserved != true) {
                    // A legacy/test callback with no nested governor still
                    // represents one actual request attempt.
                    activeBudget.consumeAttemptOrThrow()
                    attemptContext?.recordAttempt()
                }
                return value
            } catch (e: CancellationException) {
                throw e
            } catch (e: RequestRetryBudgetExhaustedException) {
                throw e
            } catch (e: ProviderRequestPausedException) {
                throw e
            } catch (e: Exception) {
                // A legacy/test transport callback may throw before reaching
                // the governor. Charge that logical request so nested
                // transport retries still share the envelope hard ceiling.
                if (activeBudget != null && attemptContext?.wasObserved != true) {
                    activeBudget.consumeAttemptOrThrow()
                    attemptContext?.recordAttempt()
                }
                lastError = e
                val failure = classifyProviderFailure(e, backend = logTag, nowEpochMs = clock.nowEpochMs())
                val backend = ShortHash.hash(logTag).ifEmpty { "unknown" }
                if (attempt >= maxAttempts || failure.retryability == ProviderFailureRetryability.TERMINAL) {
                    envelopePageKeys?.let { pageKeys ->
                        BatchTranslationDiagnostics.envelopeLifecycle(
                            phase = BatchEnvelopeLifecycle.FAILED,
                            pageKeys = pageKeys,
                            attempt = attempt,
                            reason = BatchDiagnosticReason.TERMINAL_FAILURE,
                        )
                    }
                    logcat(tag = "TranslationRetry", priority = LogPriority.ERROR) {
                        "backend=$backend event=translation_failure reason=" +
                            "${if (attempt >= maxAttempts) "retry_exhausted" else "terminal"} " +
                            "attempt=$attempt error=${e::class.java.simpleName} kind=${failure.kind}"
                    }
                    throw e
                }

                activeBudget?.let { budget ->
                    if (budget.isExhausted) throw RequestRetryBudgetExhaustedException(budget)
                }
                val now = clock.nowEpochMs()
                val hintedDelay = failure.retryAfterMillis
                    ?: failure.retryAfterAtEpochMs?.let { (it - now).coerceAtLeast(0L) }
                val retryDelay = hintedDelay ?: exponentialDelay(baseDelayMs, attempt)
                val nextRetryAt = failure.retryAfterAtEpochMs ?: safeAdd(now, retryDelay)
                if (failure.retryability == ProviderFailureRetryability.PAUSE ||
                    retryDelay > maxRetryDelayMs
                ) {
                    envelopePageKeys?.let { pageKeys ->
                        BatchTranslationDiagnostics.envelopeLifecycle(
                            phase = BatchEnvelopeLifecycle.FAILED,
                            pageKeys = pageKeys,
                            attempt = attempt,
                            reason = BatchDiagnosticReason.TRANSIENT_FAILURE,
                        )
                    }
                    throw ProviderRequestPausedException(
                        key = requestMetadata?.key ?: ProviderRequestKey(logTag),
                        nextEligibleRetryAtEpochMs = nextRetryAt,
                        reason = "Provider retry deferred beyond the configured transport wait budget",
                    )
                }

                envelopePageKeys?.let { pageKeys ->
                    BatchTranslationDiagnostics.envelopeLifecycle(
                        phase = BatchEnvelopeLifecycle.RETRY,
                        pageKeys = pageKeys,
                        attempt = attempt,
                        reason = BatchDiagnosticReason.TRANSIENT_FAILURE,
                    )
                }
                logcat(tag = "TranslationRetry", priority = LogPriority.WARN) {
                    "backend=$backend event=translation_retry reason=transient " +
                        "attempt=$attempt maxAttempts=$maxAttempts error=${e::class.java.simpleName} " +
                        "backoffMs=$retryDelay"
                }
                clock.delay(retryDelay.coerceAtLeast(0L))
            }
        }
        throw lastError ?: IOException("$logTag retry exhausted with no captured error")
    }

    return if (activeBudget == null) runLoop() else withRequestRetryBudget(activeBudget) { runLoop() }
}

private fun exponentialDelay(baseDelayMs: Long, attempt: Int): Long {
    val shift = (attempt - 1).coerceIn(0, 62)
    val multiplier = 1L shl shift
    if (baseDelayMs == 0L) return 0L
    if (baseDelayMs > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE
    return baseDelayMs * multiplier
}

/** Compatibility classifier for callers that still need a boolean retry check. */
internal fun Throwable.isTransientRateOrServerError(): Boolean {
    val failure = classifyProviderFailure(this)
    return failure.retryability == ProviderFailureRetryability.RETRY_NOW ||
        failure.retryability == ProviderFailureRetryability.RETRY_AFTER
}
