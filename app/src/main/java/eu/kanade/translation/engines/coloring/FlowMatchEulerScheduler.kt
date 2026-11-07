package eu.kanade.translation.engines.coloring

/**
 * Kotlin port of diffusers' FlowMatchEulerDiscreteScheduler as used by
 * sana-manga-coloring's sample(): shift-warped flow-match sigmas with 20 Euler
 * steps. sigma_i = shift*s_i / (1 + (shift-1)*s_i) for s_i = linspace(0,1,steps)
 * (sigma_min 0, sigma_max 1), timesteps = sigma * trainTimesteps, and a final
 * sigma of 0 so the last step lands on clean data. Velocity prediction:
 * prev = sample + (sigma_next - sigma) * velocity. Pinned against
 * scheduler_reference.json dumped from the Python scheduler.
 */
class FlowMatchEulerScheduler(
    steps: Int,
    shift: Float,
    private val trainTimesteps: Int = DEFAULT_TRAIN_TIMESTEPS,
) {

    /** Descending model-call timesteps in [0, trainTimesteps], length [steps]. */
    val timesteps: FloatArray

    /** length [steps]+1; the trailing 0 drives the final step onto clean data. */
    private val sigmas: FloatArray

    init {
        require(steps > 0) { "Euler scheduler needs at least one step" }
        require(shift > 0f) { "Flow-match shift must be positive" }
        sigmas = FloatArray(steps + 1)
        timesteps = FloatArray(steps)
        for (i in 0 until steps) {
            val linear = i.toFloat() / steps
            sigmas[i] = shift * linear / (1f + (shift - 1f) * linear)
            timesteps[i] = sigmas[i] * trainTimesteps
        }
        sigmas[steps] = 0f
    }

    /**
     * One Euler update for a single latent row. [velocity] and [sample] are
     * row-major latent floats of identical length; returns the new sample.
     */
    fun step(velocity: FloatArray, sample: FloatArray, stepIndex: Int): FloatArray {
        require(velocity.size == sample.size) {
            "velocity/sample length mismatch: ${velocity.size} vs ${sample.size}"
        }
        require(stepIndex in 0 until steps) { "stepIndex $stepIndex outside 0..${steps - 1}" }
        val delta = sigmas[stepIndex + 1] - sigmas[stepIndex]
        val result = FloatArray(sample.size)
        for (i in sample.indices) {
            result[i] = sample[i] + delta * velocity[i]
        }
        return result
    }

    val steps: Int get() = timesteps.size

    companion object {
        const val DEFAULT_TRAIN_TIMESTEPS = 1000

        /** sana-manga-coloring sample() defaults: 20 steps, shift 3.0. */
        fun forColoring(): FlowMatchEulerScheduler = FlowMatchEulerScheduler(steps = 20, shift = 3.0f)
    }
}
