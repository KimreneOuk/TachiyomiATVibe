package eu.kanade.translation.pipeline.planning

/** Creates a stable snapshot of the already ordered pages at chapter entry. */
object ResumeOrdering {

    /** Preserves the caller's natural page order while taking a list snapshot. */
    fun <T> naturalOrder(items: List<T>): List<T> = items.toList()
}
