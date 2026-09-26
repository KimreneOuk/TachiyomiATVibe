package eu.kanade.translation.engines.translator.routing

import eu.kanade.translation.engines.translator.ProviderRequestKey
import java.util.Locale

/**
 * Routes translation operations to provider backends and their request policies.
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
