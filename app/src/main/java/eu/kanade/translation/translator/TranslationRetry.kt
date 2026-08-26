package eu.kanade.translation.translator

import eu.kanade.translation.batch.BatchDiagnosticReason
import eu.kanade.translation.batch.BatchEnvelopeLifecycle
import eu.kanade.translation.batch.BatchTranslationDiagnostics
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.logcat
import java.io.IOException

/**
 * Bounded transport retry for transient provider failures.
 *
 * The retry budget is deliberately finite and is independent from semantic
 * retries. Provider implementations admit each actual HTTP attempt at their
 * network boundary; [requestMetadata] is available for small adapters/tests
 * whose attempt callback is itself the HTTP operation.
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
    block: suspend () -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be > 0" }
    require(baseDelayMs >= 0) { "baseDelayMs must be >= 0" }
    require(maxRetryDelayMs >= 0) { "maxRetryDelayMs must be >= 0" }

    var attempt = 0
    var lastError: Throwable? = null
    while (attempt < maxAttempts) {
        attempt++
        envelopePageKeys?.let { pageKeys ->
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = BatchEnvelopeLifecycle.PROVIDER_REQUEST,
                pageKeys = pageKeys,
                attempt = attempt,
                reason = null,
            )
        }
        try {
            val metadata = requestMetadata?.copy(attempt = attempt)
            return if (metadata == null) {
                block()
            } else {
                governor.executeValue(metadata, block)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderRequestPausedException) {
            throw e
        } catch (e: Exception) {
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

private fun exponentialDelay(baseDelayMs: Long, attempt: Int): Long {
    val shift = (attempt - 1).coerceIn(0, 62)
    val multiplier = 1L shl shift
    if (baseDelayMs == 0L || baseDelayMs > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE
    return baseDelayMs * multiplier
}

/** Compatibility classifier for callers that still need a boolean retry check. */
internal fun Throwable.isTransientRateOrServerError(): Boolean {
    val failure = classifyProviderFailure(this)
    return failure.retryability == ProviderFailureRetryability.RETRY_NOW ||
        failure.retryability == ProviderFailureRetryability.RETRY_AFTER
}

private fun safeAdd(left: Long, right: Long): Long {
    if (right <= 0) return left
    if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
}
