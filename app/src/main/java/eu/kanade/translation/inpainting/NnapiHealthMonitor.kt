package eu.kanade.translation.inpainting

/**
 * Engine-lifetime NNAPI health state. Only NNAPI-specific disagreements count;
 * a rejection shared with XNNPACK says nothing about the NNAPI driver.
 */
internal class NnapiHealthMonitor(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val minimumSamples: Int = DEFAULT_MINIMUM_SAMPLES,
    private val mismatchLimit: Int = DEFAULT_MISMATCH_LIMIT,
) {
    enum class DisableReason { NATIVE_EXCEPTION, GUARD_MISMATCH_RATE }

    data class Snapshot(
        val enabled: Boolean,
        val samples: Int,
        val mismatches: Int,
        val disableReason: DisableReason?,
    )

    private val observations = ArrayDeque<Boolean>()
    private var mismatchCount = 0
    private var disableReason: DisableReason? = null

    @Synchronized
    fun isHealthy(): Boolean = disableReason == null

    @Synchronized
    fun recordNnapiAccepted() = record(mismatch = false)

    @Synchronized
    fun recordNnapiOnlyGuardMismatch() = record(mismatch = true)

    /** Shared rejection is deliberately not an observation. */
    @Synchronized
    fun recordSharedRejection() = Unit

    @Synchronized
    fun disableForNativeException() {
        if (disableReason == null) disableReason = DisableReason.NATIVE_EXCEPTION
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        enabled = disableReason == null,
        samples = observations.size,
        mismatches = mismatchCount,
        disableReason = disableReason,
    )

    private fun record(mismatch: Boolean) {
        if (disableReason != null) return
        observations.addLast(mismatch)
        if (mismatch) mismatchCount++
        if (observations.size > windowSize && observations.removeFirst()) mismatchCount--
        if (observations.size >= minimumSamples && mismatchCount >= mismatchLimit) {
            disableReason = DisableReason.GUARD_MISMATCH_RATE
        }
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 20
        const val DEFAULT_MINIMUM_SAMPLES = 10
        const val DEFAULT_MISMATCH_LIMIT = 3
    }
}
