package eu.kanade.translation.translator

import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

object RetryAfterParser {
    /** Parses delta-seconds (including fractions) or an RFC-1123 HTTP date. */
    fun parseMillis(value: String?, nowEpochMs: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toDoubleOrNull()?.let { seconds ->
            if (!seconds.isFinite()) return null
            if (seconds <= 0.0) return 0L
            val millis = ceil(seconds * 1_000.0)
            return if (millis >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else millis.toLong()
        }
        return runCatching {
            val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (target - nowEpochMs).coerceAtLeast(0L)
        }.getOrNull()
    }
}

internal fun parseRetryAfterMillis(value: String?, nowEpochMs: Long = System.currentTimeMillis()): Long? =
    RetryAfterParser.parseMillis(value, nowEpochMs)

fun classifyHttpFailure(
    backend: String,
    statusCode: Int,
    retryAfterHeader: String? = null,
    providerCode: String? = null,
    providerStatus: String? = null,
    safeSummary: String? = null,
    nowEpochMs: Long = System.currentTimeMillis(),
): ProviderFailure {
    val retryAfter = parseRetryAfterMillis(retryAfterHeader, nowEpochMs)
    val normalizedStatus = providerStatus?.uppercase(Locale.ROOT).orEmpty()
    val quotaSignal = normalizedStatus.contains("QUOTA") ||
        normalizedStatus.contains("RESOURCE_EXHAUSTED") ||
        safeSummary?.lowercase(Locale.ROOT)?.contains("quota") == true
    val kind: ProviderFailureKind
    val retryability: ProviderFailureRetryability
    when {
        statusCode == 401 || statusCode == 403 -> {
            kind = ProviderFailureKind.AUTHENTICATION
            retryability = ProviderFailureRetryability.TERMINAL
        }
        statusCode == 429 && quotaSignal -> {
            kind = ProviderFailureKind.QUOTA_EXHAUSTED
            retryability = if (retryAfter == 0L) {
                ProviderFailureRetryability.RETRY_AFTER
            } else {
                ProviderFailureRetryability.PAUSE
            }
        }
        statusCode == 408 || statusCode == 425 || statusCode == 429 -> {
            kind = ProviderFailureKind.RATE_LIMIT
            retryability = if (retryAfter != null) {
                ProviderFailureRetryability.RETRY_AFTER
            } else {
                ProviderFailureRetryability.RETRY_NOW
            }
        }
        statusCode in 500..599 -> {
            kind = ProviderFailureKind.SERVER
            retryability = if (retryAfter != null) {
                ProviderFailureRetryability.RETRY_AFTER
            } else {
                ProviderFailureRetryability.RETRY_NOW
            }
        }
        statusCode in 400..499 -> {
            kind = ProviderFailureKind.CONFIGURATION
            retryability = ProviderFailureRetryability.TERMINAL
        }
        else -> {
            kind = ProviderFailureKind.PROTOCOL
            retryability = ProviderFailureRetryability.TERMINAL
        }
    }
    return ProviderFailure(
        kind = kind,
        retryability = retryability,
        statusCode = statusCode,
        providerCode = providerCode,
        retryAfterMillis = retryAfter,
        retryAfterAtEpochMs = retryAfter?.let { safeAdd(nowEpochMs, it) },
        safeSummary = safeSummary ?: "$backend HTTP $statusCode",
    )
}

internal fun classifyHttpFailureWithRetryAfterMillis(
    backend: String,
    statusCode: Int,
    retryAfterMillis: Long?,
    providerCode: String? = null,
    providerStatus: String? = null,
    nowEpochMs: Long = System.currentTimeMillis(),
): ProviderFailure {
    val base = classifyHttpFailure(
        backend = backend,
        statusCode = statusCode,
        providerCode = providerCode,
        providerStatus = providerStatus,
        nowEpochMs = nowEpochMs,
    )
    return if (retryAfterMillis == null) {
        base
    } else {
        base.copy(
            retryAfterMillis = retryAfterMillis.coerceAtLeast(0L),
            retryAfterAtEpochMs = safeAdd(nowEpochMs, retryAfterMillis.coerceAtLeast(0L)),
            retryability = if (
                base.retryability == ProviderFailureRetryability.RETRY_NOW ||
                (base.retryability == ProviderFailureRetryability.PAUSE && retryAfterMillis <= 0L)
            ) {
                ProviderFailureRetryability.RETRY_AFTER
            } else {
                base.retryability
            },
        )
    }
}

fun classifyProviderFailure(
    error: Throwable,
    backend: String = "unknown",
    nowEpochMs: Long = System.currentTimeMillis(),
): ProviderFailure {
    if (error is ProviderFailureException) return error.failure
    if (error is CancellationException) throw error
    val message = error.message?.lowercase(Locale.ROOT).orEmpty()
    val kind: ProviderFailureKind
    val retryability: ProviderFailureRetryability
    when {
        error is java.io.IOException || message.contains("timeout") || message.contains("timed out") -> {
            kind = ProviderFailureKind.NETWORK
            retryability = ProviderFailureRetryability.RETRY_NOW
        }
        message.contains("quota") || message.contains("resource_exhausted") -> {
            kind = ProviderFailureKind.QUOTA_EXHAUSTED
            retryability = ProviderFailureRetryability.PAUSE
        }
        message.contains("429") ||
            message.contains("rate limit") ||
            message.contains("ratelimit") ||
            message.contains("too many requests") -> {
            kind = ProviderFailureKind.RATE_LIMIT
            retryability = ProviderFailureRetryability.RETRY_NOW
        }
        message.contains("502") ||
            message.contains("503") ||
            message.contains("504") ||
            message.contains("service unavailable") ||
            message.contains("bad gateway") ||
            message.contains("gateway timeout") ||
            message.contains("overloaded") ||
            message.contains("server error") ||
            message.contains("temporarily unavailable") -> {
            kind = ProviderFailureKind.SERVER
            retryability = ProviderFailureRetryability.RETRY_NOW
        }
        message.contains("refus") || message.contains("safety") || message.contains("policy") -> {
            kind = ProviderFailureKind.REFUSAL
            retryability = ProviderFailureRetryability.TERMINAL
        }
        error is IllegalArgumentException -> {
            kind = ProviderFailureKind.CONFIGURATION
            retryability = ProviderFailureRetryability.TERMINAL
        }
        else -> {
            kind = ProviderFailureKind.PROTOCOL
            retryability = ProviderFailureRetryability.TERMINAL
        }
    }
    return ProviderFailure(
        kind = kind,
        retryability = retryability,
        safeSummary = "$backend ${error::class.java.simpleName}",
        retryAfterAtEpochMs = null,
        attempt = null,
    )
}

/** Saturating epoch-ms addition shared with [TranslationRetry] (single canonical copy). */
internal fun safeAdd(left: Long, right: Long): Long {
    if (right <= 0) return left
    if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
}
