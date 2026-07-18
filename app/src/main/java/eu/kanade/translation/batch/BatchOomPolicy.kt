package eu.kanade.translation.batch

data class AbortDecision(
    val abort: Boolean,
    val consecutiveCount: Int,
    val reason: String? = null,
)

object BatchOomPolicy {
    const val DEFAULT_ABORT_THRESHOLD = 3

    fun shouldAbort(
        consecutiveOomCount: Int,
        threshold: Int = DEFAULT_ABORT_THRESHOLD,
    ): AbortDecision {
        if (consecutiveOomCount <= 0) return AbortDecision(false, consecutiveOomCount)
        val reason = if (consecutiveOomCount >= threshold) {
            "Memory exhausted after $consecutiveOomCount consecutive OOMs — retry after restart"
        } else {
            null
        }
        return AbortDecision(
            abort = consecutiveOomCount >= threshold,
            consecutiveCount = consecutiveOomCount,
            reason = reason,
        )
    }
}
