package eu.kanade.translation.persistence.artifact

/**
 *  Slice B: Group commit configuration and feature flag.
 * Default is FALSE (OFF) during Phase 2.1 - 2.3; flipped to TRUE in 2.4 after soak.
 */
object GroupCommitConfiguration {
    @Volatile
    var enabled: Boolean = false

    const val DEBOUNCE_MS: Long = 250L
    const val MAX_STAGED_PAGES: Int = 5

    /**
     * Executes [block] with group commit flag set to [flagValue], restoring previous value after.
     */
    inline fun <T> withFlag(flagValue: Boolean, block: () -> T): T {
        val prev = enabled
        enabled = flagValue
        return try {
            block()
        } finally {
            enabled = prev
        }
    }
}
