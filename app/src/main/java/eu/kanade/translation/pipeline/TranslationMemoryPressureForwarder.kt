package eu.kanade.translation.pipeline

import eu.kanade.translation.*
import eu.kanade.translation.orchestration.*
import eu.kanade.translation.storage.*

/** JVM-testable bridge used by the application callback. */
fun forwardTranslationMemoryPressure(level: Int, receiver: (Int) -> Unit) {
    receiver(level)
}
