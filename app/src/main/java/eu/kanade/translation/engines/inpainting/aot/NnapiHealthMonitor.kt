package eu.kanade.translation.engines.inpainting.aot

/**
 * Engine-scoped NNAPI runtime safety state. Only a runtime exception can disable
 * this engine's session; pixel appearance never contributes to backend health.
 * A new AOT engine owns a new monitor, so one failed session cannot poison its
 * successor.
 */
internal class NnapiHealthMonitor {
    enum class DisableReason { NATIVE_EXCEPTION }

    data class Snapshot(
        val enabled: Boolean,
        val disableReason: DisableReason?,
    )

    private var disableReason: DisableReason? = null

    @Synchronized
    fun isHealthy(): Boolean = disableReason == null

    @Synchronized
    fun disableForNativeException() {
        if (disableReason == null) disableReason = DisableReason.NATIVE_EXCEPTION
    }

    @Synchronized
    fun resetForNewSession() {
        disableReason = null
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        enabled = disableReason == null,
        disableReason = disableReason,
    )
}
