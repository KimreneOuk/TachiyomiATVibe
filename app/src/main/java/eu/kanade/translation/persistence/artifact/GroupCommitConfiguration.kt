package eu.kanade.translation.persistence.artifact

/**
 * Controls whether page artifact commits are grouped. The default is off.
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
