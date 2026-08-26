package eu.kanade.translation.remote

import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability

class RemotePageTranslationException(
    message: String,
    cause: Throwable? = null,
    failure: ProviderFailure? = null,
) : ProviderFailureException(
    failure = failure ?: ProviderFailure(
        kind = if (cause is java.io.IOException) ProviderFailureKind.NETWORK else ProviderFailureKind.PROTOCOL,
        retryability = if (cause is java.io.IOException) {
            ProviderFailureRetryability.RETRY_NOW
        } else {
            ProviderFailureRetryability.TERMINAL
        },
        safeSummary = message,
    ),
    cause = cause,
) {
    internal constructor(failure: ProviderFailure) : this(
        message = failure.safeSummary,
        cause = null,
        failure = failure,
    ) {
    }

    companion object {
        const val DESKTOP_UNREACHABLE = "Desktop Server Unreachable"
    }
}
