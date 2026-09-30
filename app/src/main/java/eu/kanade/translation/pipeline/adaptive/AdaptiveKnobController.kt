package eu.kanade.translation.pipeline.adaptive

import java.util.ArrayDeque
import java.util.EnumMap

/** Device stages whose active time can inform the page-concurrency knob. */
enum class DeviceActiveStage {
    SOURCE_DECODE,
    DETECT,
    OCR,
    TRANSLATE,
    INPAINT,
    RENDER,
}

/** One completed, already-attributed device-stage observation. */
data class DeviceStageObservation(
    val stage: DeviceActiveStage,
    val durationNanos: Long,
    val normalizationUnits: Long,
    val activeDevicePages: Int,
    val successful: Boolean,
    val thermalThrottleOnset: Boolean = false,
    val durabilityInterference: Boolean = false,
    val memoryHeadroomAvailable: Boolean = true,
) {
    val normalizedNanosPerUnit: Double
        get() = durationNanos.coerceAtLeast(0L).toDouble() / normalizationUnits.coerceAtLeast(1L)
}

/** The live signals consumed by the controller; deliberately has no provider input. */
interface KnobSignals {
    fun snapshot(): KnobSignalSnapshot
}

/**
 * Snapshot of device and E16a durability signals used for sample validity.
 * Future E18 wiring can extend this port with capture-barrier and compaction
 * activity, mapping those intervals to [activeDurabilityStalls] / [durabilityEventSequence].
 * The current build does not synthesize or stub those not-yet-merged events.
 */
data class KnobSignalSnapshot(
    val hasMemoryHeadroom: Boolean,
    val thermalStatus: Int,
    val activeDurabilityStalls: Int,
    val durabilityEventSequence: Long,
)

/**
 * Process-local, deliberately non-persistent adaptive controller.
 *
 * It computes a desired level for E20a's correctness proof, while
 * [appliedConcurrency] remains pinned at the floor until E20b. Controller
 * state is not persisted: startup provisional samples and a per-stage quorum
 * make a fresh process safe and inexpensive to re-anchor.
 */
class AdaptiveKnobController(
    val floor: Int = DEFAULT_FLOOR,
    val ceiling: Int = DEFAULT_CEILING,
    private val anchorWindowSize: Int = DEFAULT_ANCHOR_WINDOW_SIZE,
    private val minimumAnchorSamples: Int = DEFAULT_MINIMUM_ANCHOR_SAMPLES,
    private val coldStartProvisionalSamples: Int = DEFAULT_COLD_START_PROVISIONAL_SAMPLES,
) {
    private val lock = Any()
    private var floorSamplesRemaining = coldStartProvisionalSamples
    private val samplesByStage = EnumMap<DeviceActiveStage, ArrayDeque<Double>>(DeviceActiveStage::class.java)
    private val anchorsByStage = EnumMap<DeviceActiveStage, Double>(DeviceActiveStage::class.java)

    private var computed = floor

    init {
        require(floor >= 1)
        require(ceiling >= floor)
        require(anchorWindowSize >= 1)
        require(minimumAnchorSamples in 1..anchorWindowSize)
        require(coldStartProvisionalSamples >= 0)
    }

    /** Applied E20a output. The E20b flip will deliberately remove this pin. */
    val appliedConcurrency: Int get() = floor

    /** Candidate output, maintained and tested while the applied output stays safe. */
    val computedConcurrency: Int get() = synchronized(lock) { computed }

    fun anchorFor(stage: DeviceActiveStage): Double? = synchronized(lock) { anchorsByStage[stage] }

    fun validAnchorSampleCount(stage: DeviceActiveStage): Int = synchronized(lock) {
        samplesByStage[stage]?.size ?: 0
    }

    /**
     * Feeds one stage result. Only successful minimum-concurrency samples can
     * revise an anchor. A non-floor observation may request backoff, but it is
     * never admitted into the baseline window.
     */
    fun observe(observation: DeviceStageObservation): Int = synchronized(lock) {
        val isFloorSample = observation.activeDevicePages == floor
        val provisionalFloorSample = isFloorSample && floorSamplesRemaining > 0
        if (provisionalFloorSample) floorSamplesRemaining--

        if (observation.thermalThrottleOnset || !observation.memoryHeadroomAvailable) {
            computed = (computed - 1).coerceAtLeast(floor)
        }

        val uncontaminatedSuccess = observation.successful &&
            !observation.thermalThrottleOnset &&
            !observation.durabilityInterference &&
            observation.memoryHeadroomAvailable &&
            observation.normalizationUnits > 0
        if (!isFloorSample) {
            val anchor = anchorsByStage[observation.stage]
            if (uncontaminatedSuccess && anchor != null && observation.normalizedNanosPerUnit > anchor) {
                computed = (computed - 1).coerceAtLeast(floor)
            }
            return@synchronized computed
        }

        val valid = isFloorSample &&
            observation.successful &&
            observation.normalizationUnits > 0 &&
            !observation.thermalThrottleOnset &&
            !observation.durabilityInterference &&
            observation.memoryHeadroomAvailable

        if (!valid || provisionalFloorSample) return@synchronized computed

        val samples = samplesByStage.getOrPut(observation.stage) { ArrayDeque() }
        samples.addLast(observation.normalizedNanosPerUnit)
        while (samples.size > anchorWindowSize) samples.removeFirst()

        if (samples.size < minimumAnchorSamples) return@synchronized computed

        val anchor = median(samples)
        anchorsByStage[observation.stage] = anchor
        computed = if (observation.normalizedNanosPerUnit > anchor) {
            (computed - 1).coerceAtLeast(floor)
        } else {
            (computed + 1).coerceAtMost(ceiling)
        }
        computed
    }

    /** OOM is the device emergency signal; callers keep owning reclamation. */
    fun onOutOfMemory(): Int = synchronized(lock) {
        computed = floor
        computed
    }

    private fun median(values: Collection<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    companion object {
        const val DEFAULT_FLOOR = 1

        /** Conservative machinery ceiling; E21 measures the device-specific limit. */
        const val DEFAULT_CEILING = 4

        /** E20a selects the low end of the planned 8–16-sample range. */
        const val DEFAULT_ANCHOR_WINDOW_SIZE = 8
        const val DEFAULT_MINIMUM_ANCHOR_SAMPLES = 5
        const val DEFAULT_COLD_START_PROVISIONAL_SAMPLES = 3
    }
}
