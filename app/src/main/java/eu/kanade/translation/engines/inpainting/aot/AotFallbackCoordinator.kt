package eu.kanade.translation.engines.inpainting.aot

/**
 * Pure control flow for the fixed-512 neural fallback cascade.
 *
 * Inference remains supplied by callers, which keeps provider selection and
 * ORT lifecycle out of this model until the production integration phase.
 */
internal object AotFallbackCoordinator {

    enum class Attempt {
        PRIMARY,
        FALLBACK,
    }

    sealed interface CandidateResult {
        data class Accepted(val pixels: IntArray) : CandidateResult
        data class Rejected(val stats: AotOutputGuard.GuardStats) : CandidateResult
        data class Failed(val error: Throwable) : CandidateResult
        data class Skipped(val reason: String) : CandidateResult
    }

    sealed interface Result {
        data class Accepted(
            val pixels: IntArray,
            val attempt: Attempt,
        ) : Result

        data class Exhausted(
            val primary: CandidateResult,
            val fallback: CandidateResult,
        ) : Result
    }

    inline fun run(
        primary: () -> CandidateResult,
        fallback: () -> CandidateResult,
    ): Result {
        val primaryResult = primary()
        if (primaryResult is CandidateResult.Accepted) {
            return Result.Accepted(primaryResult.pixels, Attempt.PRIMARY)
        }

        val fallbackResult = if (shouldAttemptFallback(primaryResult)) {
            fallback()
        } else {
            CandidateResult.Skipped("primary_oom")
        }
        return if (fallbackResult is CandidateResult.Accepted) {
            Result.Accepted(fallbackResult.pixels, Attempt.FALLBACK)
        } else {
            Result.Exhausted(primaryResult, fallbackResult)
        }
    }

    fun shouldAttemptFallback(primary: CandidateResult): Boolean =
        primary !is CandidateResult.Failed || primary.error !is OutOfMemoryError

    fun inspectCroppedCandidate(
        paddedCandidate: IntArray,
        paddedMask: IntArray,
        sourceSize: Int,
        candidateDestination: IntArray,
        maskDestination: IntArray,
    ): CandidateResult {
        AotPadPath.cropSquareInto(paddedCandidate, sourceSize, candidateDestination)
        AotPadPath.cropSquareInto(paddedMask, sourceSize, maskDestination)
        val stats = AotOutputGuard.inspect(
            candidateDestination,
            maskDestination,
            sourceSize,
            sourceSize,
        )
        return if (AotOutputGuard.classify(stats)) {
            CandidateResult.Rejected(stats)
        } else {
            CandidateResult.Accepted(candidateDestination)
        }
    }
}
