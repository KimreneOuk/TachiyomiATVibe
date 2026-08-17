package eu.kanade.translation.inpainting

/** Pure eligibility gate for the optional strict-NNAPI fixed AOT candidate. */
internal object NnapiCapabilityGate {
    const val MIN_SDK = 29
    const val MIN_AVAILABLE_HEAP_BYTES = 64L * 1024L * 1024L
    const val MIN_SYSTEM_HEADROOM_BYTES = 160L * 1024L * 1024L

    data class Snapshot(
        val sdk: Int,
        val supportedAbis: List<String>,
        val emulator: Boolean,
        val nnapiProviderCompiled: Boolean,
        val availableHeapBytes: Long,
        val systemHeadroomBytes: Long?,
        val lowMemory: Boolean,
        val healthy: Boolean,
    )

    data class Decision(val eligible: Boolean, val reason: String) {
        companion object {
            fun eligible() = Decision(true, "eligible")
            fun rejected(reason: String) = Decision(false, reason)
        }
    }

    fun decide(snapshot: Snapshot): Decision = when {
        snapshot.sdk < MIN_SDK -> Decision.rejected("sdk_below_29")
        snapshot.supportedAbis.none { it.equals("arm64-v8a", ignoreCase = true) } ->
            Decision.rejected("abi_not_arm64")
        snapshot.emulator -> Decision.rejected("emulator")
        !snapshot.nnapiProviderCompiled -> Decision.rejected("nnapi_provider_not_compiled")
        snapshot.lowMemory -> Decision.rejected("system_low_memory")
        snapshot.availableHeapBytes < MIN_AVAILABLE_HEAP_BYTES -> Decision.rejected("heap_headroom")
        snapshot.systemHeadroomBytes != null &&
            snapshot.systemHeadroomBytes < MIN_SYSTEM_HEADROOM_BYTES -> Decision.rejected("system_headroom")
        !snapshot.healthy -> Decision.rejected("health_disabled")
        else -> Decision.eligible()
    }
}
