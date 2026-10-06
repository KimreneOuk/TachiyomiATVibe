package eu.kanade.translation.diagnostics

/** Debug-only session state; callbacks keep marker formatting and device reads in the runtime. */
internal class TranslationMeasurementSessionLifecycle<T : Any> {
    private var active: T? = null

    @Synchronized
    fun start(session: T, onRestart: (T) -> Unit) {
        active?.let(onRestart)
        active = session
    }

    @Synchronized
    fun end(onComplete: (T) -> Unit): Boolean {
        val session = active ?: return false
        onComplete(session)
        active = null
        return true
    }
}
