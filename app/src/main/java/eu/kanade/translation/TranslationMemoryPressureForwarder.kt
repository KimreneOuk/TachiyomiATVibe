package eu.kanade.translation

/** JVM-testable bridge used by the application callback. */
fun forwardTranslationMemoryPressure(level: Int, receiver: (Int) -> Unit) {
    receiver(level)
}
