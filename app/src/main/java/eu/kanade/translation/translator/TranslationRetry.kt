package eu.kanade.translation.translator

import eu.kanade.translation.batch.BatchDiagnosticReason
import eu.kanade.translation.batch.BatchEnvelopeLifecycle
import eu.kanade.translation.batch.BatchTranslationDiagnostics
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import logcat.LogPriority
import logcat.logcat
import java.io.IOException
import kotlin.random.Random

/**
 * Bounded retry with exponential backoff for transient translator failures
 * (HTTP 429/5xx, rate-limit, timeouts, connection drops). This is NOT a
 * fallback: a non-transient exception is rethrown immediately, and a
 * transient exception that exhausts [maxAttempts] is rethrown so the batch
 * driver's failure handling still runs. Every retry and the terminal failure
 * are logged so failures stay visible (no silent suppression).
 *
 * Centralised here so all four translator backends (DeepSeek/OpenRouter/
 * LmStudio and Gemini via OkHttp) get uniform retry behaviour without
 * per-translator duplication.
 */
internal suspend inline fun <T> withTranslationRetry(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 1000L,
    logTag: String,
    envelopePageKeys: Collection<String>? = null,
    crossinline block: suspend () -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be > 0" }
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
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            val backend = ShortHash.hash(logTag).ifEmpty { "unknown" }
            if (attempt >= maxAttempts) {
                envelopePageKeys?.let { pageKeys ->
                    BatchTranslationDiagnostics.envelopeLifecycle(
                        phase = BatchEnvelopeLifecycle.FAILED,
                        pageKeys = pageKeys,
                        attempt = attempt,
                        reason = BatchDiagnosticReason.TERMINAL_FAILURE,
                    )
                }
                logcat(tag = "TranslationRetry", priority = LogPriority.ERROR) {
                    "backend=$backend event=translation_failure reason=retry_exhausted " +
                        "attempt=$attempt error=${e::class.java.simpleName}"
                }
                throw e
            }
            if (!e.isTransientRateOrServerError()) {
                envelopePageKeys?.let { pageKeys ->
                    BatchTranslationDiagnostics.envelopeLifecycle(
                        phase = BatchEnvelopeLifecycle.FAILED,
                        pageKeys = pageKeys,
                        attempt = attempt,
                        reason = BatchDiagnosticReason.TERMINAL_FAILURE,
                    )
                }
                logcat(tag = "TranslationRetry", priority = LogPriority.ERROR) {
                    "backend=$backend event=translation_failure reason=terminal " +
                        "attempt=$attempt error=${e::class.java.simpleName}"
                }
                throw e
            }
            val backoff = (baseDelayMs * (1L shl (attempt - 1))) + Random.nextLong(0, 500)
            val retryAfter = (e as? GeminiApiException)?.retryAfterMillis
            val capped = (retryAfter ?: backoff).coerceIn(0L, 30_000L)
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
                    "backoffMs=$capped"
            }
            delay(capped)
        }
    }
    throw lastError ?: IOException("$logTag retry exhausted with no captured error")
}

/**
 * Classifies an exception as transient (safe to retry) vs terminal (rethrow
 * immediately). Detection is string-based because translator backends wrap
 * HTTP/SDK errors in their own exception types without a shared status-code
 * field; the message is the only universal signal. IOException (network
 * drops, sockets, timeouts) is always transient.
 */
internal fun Throwable.isTransientRateOrServerError(): Boolean {
    if (this is IOException) return true
    val msg = message?.lowercase() ?: return false
    return msg.contains("429") ||
        msg.contains("rate limit") ||
        msg.contains("rate_limit") ||
        msg.contains("ratelimit") ||
        msg.contains("too many requests") ||
        msg.contains("502") ||
        msg.contains("503") ||
        msg.contains("504") ||
        msg.contains("service unavailable") ||
        msg.contains("bad gateway") ||
        msg.contains("gateway timeout") ||
        msg.contains("timeout") ||
        msg.contains("timed out") ||
        msg.contains("temporarily unavailable") ||
        msg.contains("server error") ||
        msg.contains("overloaded")
}
