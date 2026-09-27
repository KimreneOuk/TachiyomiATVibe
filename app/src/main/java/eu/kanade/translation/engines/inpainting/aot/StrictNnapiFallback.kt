package eu.kanade.translation.engines.inpainting.aot

/** Pure, non-recursive decision coordinator for strict NNAPI -> XNNPACK. */
internal object StrictNnapiFallback {
    enum class Route { NNAPI, XNNPACK, PUSH_PULL }

    sealed interface Candidate<out T> {
        data class Accepted<T>(val value: T) : Candidate<T>
        data class Rejected(val stats: AotOutputGuard.GuardStats) : Candidate<Nothing>
        data class Failed(val error: Throwable) : Candidate<Nothing>
    }

    data class Result<T>(
        val route: Route,
        val value: T? = null,
        val nnapi: Candidate<T>? = null,
        val xnnpack: Candidate<T>? = null,
    )

    fun <T> run(
        useNnapi: Boolean,
        nnapi: () -> Candidate<T>,
        xnnpack: () -> Candidate<T>,
        onNnapiNativeFailure: (Throwable) -> Unit = {},
        onNnapiOnlyMismatch: () -> Unit = {},
        onSharedRejection: () -> Unit = {},
        onNnapiAccepted: () -> Unit = {},
    ): Result<T> {
        if (!useNnapi) return fromXnnpack(null, xnnpack())

        val nnapiResult = try {
            nnapi()
        } catch (error: Throwable) {
            Candidate.Failed(error)
        }
        if (nnapiResult is Candidate.Accepted) {
            onNnapiAccepted()
            return Result(Route.NNAPI, nnapiResult.value, nnapiResult, null)
        }
        if (nnapiResult is Candidate.Failed) onNnapiNativeFailure(nnapiResult.error)

        val xnnpackResult = xnnpack()
        if (nnapiResult is Candidate.Rejected) {
            if (xnnpackResult is Candidate.Rejected) onSharedRejection() else onNnapiOnlyMismatch()
        }
        return fromXnnpack(nnapiResult, xnnpackResult)
    }

    private fun <T> fromXnnpack(
        nnapi: Candidate<T>?,
        xnnpack: Candidate<T>,
    ): Result<T> = when (xnnpack) {
        is Candidate.Accepted -> Result(Route.XNNPACK, xnnpack.value, nnapi, xnnpack)
        is Candidate.Failed, is Candidate.Rejected -> Result(Route.PUSH_PULL, null, nnapi, xnnpack)
    }
}
