package eu.kanade.translation.engines.inpainting.aot

import kotlin.math.max

/** Conservative system-RAM gate applied before creating any reader QNN session. */
internal object QnnSessionMemoryPrecheck {
    private const val MIB = 1024L * 1024L
    private const val MINIMUM_HEADROOM_BYTES = 1536L * MIB
    private const val MODEL_SIZE_MARGIN_BYTES = 500L * MIB

    data class Decision(
        val allowed: Boolean,
        val requiredHeadroomBytes: Long,
        val availableHeadroomBytes: Long?,
        val reason: String?,
    )

    fun requiredHeadroomBytes(modelSizeBytes: Long): Long {
        val nonNegativeSize = modelSizeBytes.coerceAtLeast(0L)
        val maxSizeForSafeScale = Long.MAX_VALUE / 3L * 2L
        if (nonNegativeSize > maxSizeForSafeScale) return Long.MAX_VALUE

        val scaledModelSize = nonNegativeSize / 2L * 3L + (nonNegativeSize % 2L) * 3L / 2L
        val modelPlusMargin = if (scaledModelSize > Long.MAX_VALUE - MODEL_SIZE_MARGIN_BYTES) {
            Long.MAX_VALUE
        } else {
            scaledModelSize + MODEL_SIZE_MARGIN_BYTES
        }
        return max(MINIMUM_HEADROOM_BYTES, modelPlusMargin)
    }

    fun decide(
        modelSizeBytes: Long,
        availableHeadroomBytes: Long?,
        lowMemory: Boolean,
    ): Decision {
        val required = requiredHeadroomBytes(modelSizeBytes)
        val reason = when {
            lowMemory -> "system_low_memory"
            modelSizeBytes <= 0L -> "model_size_unavailable"
            availableHeadroomBytes == null -> "headroom_unavailable"
            availableHeadroomBytes < required -> "insufficient_headroom"
            else -> null
        }
        return Decision(
            allowed = reason == null,
            requiredHeadroomBytes = required,
            availableHeadroomBytes = availableHeadroomBytes,
            reason = reason,
        )
    }
}
