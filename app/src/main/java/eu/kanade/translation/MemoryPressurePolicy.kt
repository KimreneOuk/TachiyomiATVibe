package eu.kanade.translation

import eu.kanade.translation.orchestration.*

/**
 * Coarse classification of an Android [android.content.ComponentCallbacks2] trim-memory
 * level into the two ownership classes the translation subsystem cares about.
 *
 * The raw level arrives from [App.onTrimMemory] via [forwardTranslationMemoryPressure]
 * and historically branched on `level >= TRIM_MEMORY_RUNNING_LOW` at three call sites
 * (TranslationManager, ChapterTranslator, ReaderViewModel). Because the trim levels are
 * NOT monotonic by severity — `TRIM_MEMORY_RUNNING_LOW` (15) is lower than
 * `TRIM_MEMORY_UI_HIDDEN` (20) / `BACKGROUND` (40) / `MODERATE` (60) — that comparison
 * treated a benign app-background trim as if it were a foreground memory crunch,
 * cancelling in-flight page work and resetting display state on every background trip.
 *
 * This object consolidates the thresholds into one pure, JVM-testable classifier
 * (no `ComponentCallbacks2` statics are referenced here: those Android framework
 * constants do not resolve in plain-JVM unit tests, which is how every other policy
 * object — [eu.kanade.translation.scheduling.TranslationLifecyclePolicy] — is
 * structured). The numeric values mirror `ComponentCallbacks2` exactly.
 */
sealed interface MemoryPressureClass {

    /**
     * The process is going background-visible or releasing discretionary caches.
     * The OS is NOT under foreground memory pressure. Translation must release
     * pooled caches/buffers but must NOT cancel, pause, or reset batch work.
     */
    data object Benign : MemoryPressureClass

    /**
     * The OS is actively reclaiming memory to stay alive (foreground running low,
     * running critical, or background-complete where the process may be killed).
     * Translation must requeue in-flight batch pages back to QUEUE (not ERROR),
     * and signal that a single foreground restart is expected.
     */
    data object Critical : MemoryPressureClass
}

/**
 * Pure mapping from a raw trim-memory level to a [MemoryPressureClass].
 *
 * Critical thresholds:
 *  - `TRIM_MEMORY_RUNNING_CRITICAL` (10) .. `TRIM_MEMORY_RUNNING_LOW` (15):
 *    foreground memory crunch — reclaim aggressively, requeue batch.
 *  - `>= TRIM_MEMORY_COMPLETE` (80): the process may be killed — wind down terminally.
 *
 * Benign thresholds (return [MemoryPressureClass.Benign]):
 *  - `TRIM_MEMORY_UI_HIDDEN` (20), `TRIM_MEMORY_BACKGROUND` (40),
 *    `TRIM_MEMORY_MODERATE` (60).
 *  - Levels below `TRIM_MEMORY_RUNNING_CRITICAL` (0..9) and the gap between
 *    `TRIM_MEMORY_RUNNING_LOW` and `TRIM_MEMORY_COMPLETE` (16..79): release
 *    caches and leave owned work alone.
 *
 * Note on `>= COMPLETE` (80): the critical band is `level >= 80`, not "only the
 * exact COMPLETE constant". Android may deliver vendor-specific or future levels
 * at or above 80; treating any such level as Critical (terminal wind-down) is the
 * safe choice because the OS is signalling the process may be killed.
 */
object MemoryPressurePolicy {

    const val LEVEL_RUNNING_CRITICAL = 10
    const val LEVEL_RUNNING_LOW = 15
    const val LEVEL_UI_HIDDEN = 20
    const val LEVEL_BACKGROUND = 40
    const val LEVEL_MODERATE = 60
    const val LEVEL_COMPLETE = 80

    fun classify(level: Int): MemoryPressureClass =
        if ((level in LEVEL_RUNNING_CRITICAL..LEVEL_RUNNING_LOW) || level >= LEVEL_COMPLETE) {
            MemoryPressureClass.Critical
        } else {
            MemoryPressureClass.Benign
        }
}
