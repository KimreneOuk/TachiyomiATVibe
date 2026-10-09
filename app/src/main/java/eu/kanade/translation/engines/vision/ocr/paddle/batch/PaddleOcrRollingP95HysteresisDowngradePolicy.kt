package eu.kanade.translation.engines.vision.ocr.paddle.batch

data class PaddleOcrRollingP95Config(
    val windowSize: Int = 20,
    val downgradeP95Ms: Double = 1_000.0,
    val recoveryP95Ms: Double = 750.0,
    val highWindowsBeforeDowngrade: Int = 2,
    val lowWindowsBeforeRecovery: Int = 3,
) {
    init {
        require(windowSize > 0)
        require(downgradeP95Ms > 0.0)
        require(recoveryP95Ms > 0.0)
        require(recoveryP95Ms <= downgradeP95Ms)
        require(highWindowsBeforeDowngrade > 0)
        require(lowWindowsBeforeRecovery > 0)
    }
}

enum class PaddleOcrP95Action {
    HOLD,
    DOWNGRADE,
    RECOVER,
}

data class PaddleOcrP95Decision(
    val action: PaddleOcrP95Action,
    val activeBatchSize: PaddleOcrBatchSize,
    val rollingP95Ms: Double?,
    val reason: String,
)

/**
 * Normalizes batch latency against the governor target batch size so multi-leaf
 * batches (e.g. 16 leaves in dynamic batching) do not falsely trigger downgrades.
 */
object PaddleOcrBatchLatencyNormalizer {
    fun normalize(
        batchLatencyMs: Double,
        leafCount: Int,
        targetBatchSize: PaddleOcrBatchSize,
    ): Double {
        val safeCount = leafCount.coerceAtLeast(1)
        return (batchLatencyMs / safeCount) * targetBatchSize.value.toDouble()
    }
}

/**
 * Sliding-window p95 governor with hysteresis. A single slow inference cannot
 * oscillate the page policy: two high windows are required to downgrade, and
 * three healthy windows below the recovery threshold are required to recover.
 */
class PaddleOcrRollingP95HysteresisDowngradePolicy(
    initialBatchSize: PaddleOcrBatchSize,
    private val config: PaddleOcrRollingP95Config = PaddleOcrRollingP95Config(),
    val maximumBatchSize: PaddleOcrBatchSize = initialBatchSize,
) {
    private val samples = ArrayDeque<Double>()
    private val initialBatchSize = initialBatchSize
    private var highWindows = 0
    private var lowWindows = 0

    init {
        require(initialBatchSize.value <= maximumBatchSize.value) {
            "initial batch size must not exceed maximum batch size"
        }
    }

    var activeBatchSize: PaddleOcrBatchSize = initialBatchSize
        private set

    fun record(latencyMs: Double): PaddleOcrP95Decision {
        require(latencyMs.isFinite() && latencyMs >= 0.0) { "latencyMs must be finite and non-negative" }
        samples += latencyMs
        while (samples.size > config.windowSize) samples.removeFirst()
        if (samples.size < config.windowSize) {
            return decision(PaddleOcrP95Action.HOLD, null, "window_warming")
        }

        val p95 = percentile(samples, 0.95)
        if (p95 > config.downgradeP95Ms) {
            highWindows++
            lowWindows = 0
            if (highWindows >= config.highWindowsBeforeDowngrade) {
                val next = lower(activeBatchSize)
                highWindows = 0
                if (next != activeBatchSize) {
                    activeBatchSize = next
                    return decision(PaddleOcrP95Action.DOWNGRADE, p95, "rolling_p95_high")
                }
            }
        } else if (p95 <= config.recoveryP95Ms) {
            lowWindows++
            highWindows = 0
            if (lowWindows >= config.lowWindowsBeforeRecovery) {
                val next = recover(activeBatchSize)
                lowWindows = 0
                if (next != activeBatchSize) {
                    activeBatchSize = next
                    return decision(PaddleOcrP95Action.RECOVER, p95, "rolling_p95_recovered")
                }
            }
        } else {
            highWindows = 0
            lowWindows = 0
        }
        return decision(PaddleOcrP95Action.HOLD, p95, "rolling_p95_hold")
    }

    fun reset() {
        samples.clear()
        highWindows = 0
        lowWindows = 0
        activeBatchSize = initialBatchSize
    }

    private fun decision(
        action: PaddleOcrP95Action,
        p95: Double?,
        reason: String,
    ): PaddleOcrP95Decision = PaddleOcrP95Decision(action, activeBatchSize, p95, reason)

    private fun lower(batchSize: PaddleOcrBatchSize): PaddleOcrBatchSize = when (batchSize) {
        PaddleOcrBatchSize.B8 -> PaddleOcrBatchSize.B4
        PaddleOcrBatchSize.B4 -> PaddleOcrBatchSize.B2
        PaddleOcrBatchSize.B2 -> PaddleOcrBatchSize.B1
        PaddleOcrBatchSize.B1 -> PaddleOcrBatchSize.B1
    }

    private fun recover(batchSize: PaddleOcrBatchSize): PaddleOcrBatchSize = when (batchSize) {
        PaddleOcrBatchSize.B1 -> PaddleOcrBatchSize.B2
        PaddleOcrBatchSize.B2 -> PaddleOcrBatchSize.B4
        PaddleOcrBatchSize.B4 -> PaddleOcrBatchSize.B8
        PaddleOcrBatchSize.B8 -> PaddleOcrBatchSize.B8
    }.let { next -> if (next.value <= maximumBatchSize.value) next else maximumBatchSize }

    private fun percentile(values: Collection<Double>, percentile: Double): Double {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
