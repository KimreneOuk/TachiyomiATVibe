package eu.kanade.translation.translator.routing

import eu.kanade.translation.translator.ProviderRequestKey
import java.util.Locale

/**
 * Milestone M5 (Provider 5 / 6): Multi-backend router and per-backend execution policy.
 *
 * Directs different pipeline operations (e.g. analysis chunks vs envelope translation)
 * to dedicated provider backends, allowing concurrent execution across distinct quota buckets.
 */
class MultiBackendRouter(
    val defaultBackend: String,
    val operationRoutes: Map<String, String> = emptyMap(),
) {
    fun routeOperation(operation: String): String {
        return operationRoutes[operation.lowercase(Locale.ROOT)] ?: defaultBackend
    }

    fun requestKeyFor(
        operation: String,
        model: String? = null,
        credentialScope: String? = null,
    ): ProviderRequestKey {
        val targetBackend = routeOperation(operation)
        return ProviderRequestKey(
            backend = targetBackend,
            model = model,
            credentialScope = credentialScope,
        )
    }

    companion object {
        /** Creates a dual-backend router routing analysis to [analysisBackend] and translation to [translationBackend]. */
        fun dual(
            translationBackend: String,
            analysisBackend: String,
        ): MultiBackendRouter = MultiBackendRouter(
            defaultBackend = translationBackend,
            operationRoutes = mapOf(
                "analysis" to analysisBackend,
                "translation" to translationBackend,
            ),
        )
    }
}
