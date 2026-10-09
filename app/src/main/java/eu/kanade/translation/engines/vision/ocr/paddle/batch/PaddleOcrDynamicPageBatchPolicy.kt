package eu.kanade.translation.engines.vision.ocr.paddle.batch

/**
 * Memory-derived per-bucket ceiling for page-dynamic recognition batching.
 *
 * One ORT call carries up to `ceilingFor(bucket)` same-page crops: the input
 * NCHW float32 lease plus ORT's natively-allocated `B x T x C` output must
 * both fit their byte budgets. Output bytes dominate for the PP-OCRv6
 * dictionary (18,710 classes): the ≥5 GiB RAM tier yields 16 at WIDTH_640 and
 * 6 at WIDTH_1600. The output tensor is transient (freed after CTC decode)
 * and the vision lane is permit-serialized, so the tier budget is a peak
 * transient, not resident. Runtime failure/latency ladders in the executor
 * still halve below this ceiling, and the rolling-P95 governor can cap it
 * further.
 */
class PaddleOcrDynamicPageBatchPolicy(
    private val dictionarySize: Int,
    private val inputBudgetBytes: Long = DEFAULT_INPUT_BUDGET_BYTES,
    private val outputBudgetBytes: Long = DEFAULT_OUTPUT_BUDGET_BYTES,
    private val hardMaxBatch: Int = HARD_MAX_BATCH,
) {
    init {
        require(dictionarySize > 0) { "dictionarySize must be positive" }
        require(inputBudgetBytes > 0) { "inputBudgetBytes must be positive" }
        require(outputBudgetBytes > 0) { "outputBudgetBytes must be positive" }
        require(hardMaxBatch > 0) { "hardMaxBatch must be positive" }
    }

    fun ceilingFor(bucket: PaddleOcrWidthBucket): Int {
        val perSampleInputBytes = CHANNELS * RECOGNITION_HEIGHT *
            bucket.paddedWidth * FLOAT_BYTES
        val perSampleOutputBytes = (bucket.paddedWidth / OUTPUT_TIME_STEP_STRIDE).toLong() *
            (dictionarySize + OUTPUT_EXTRA_CLASSES) * FLOAT_BYTES
        val inputFit = (inputBudgetBytes / perSampleInputBytes).coerceAtLeast(1L)
        val outputFit = (outputBudgetBytes / perSampleOutputBytes).coerceAtLeast(1L)
        return minOf(hardMaxBatch.toLong(), inputFit, outputFit).toInt().coerceAtLeast(1)
    }

    companion object {
        /** Absolute batch-dimension bound for any dynamic chunk. */
        const val HARD_MAX_BATCH = 32

        /** Checked-in PP-OCRv6 small emits `width / 8` CTC steps. */
        const val OUTPUT_TIME_STEP_STRIDE = 8

        /** CTC blank plus the dictionary's explicit space token. */
        const val OUTPUT_EXTRA_CLASSES = 2

        /**
         * Peak-transient output budget by total device RAM. The output tensor
         * is ORT-native and freed right after decode; tiers keep ~90 MiB peak
         * on ≥5 GiB flagships, ~45 MiB on mid devices, ~30 MiB below that.
         */
        fun defaultOutputBudgetBytesFor(totalRamBytes: Long): Long = when {
            totalRamBytes >= 5L shl 30 -> 96L * 1024 * 1024
            totalRamBytes >= 3_758_096_384L -> 48L * 1024 * 1024 // 3.5 GiB
            else -> 32L * 1024 * 1024
        }

        private const val DEFAULT_INPUT_BUDGET_BYTES = 16L * 1024 * 1024
        private const val DEFAULT_OUTPUT_BUDGET_BYTES = 32L * 1024 * 1024
        private const val CHANNELS = 3L
        private const val RECOGNITION_HEIGHT = 48L
        private const val FLOAT_BYTES = 4L
    }
}
