package eu.kanade.translation.util

/** Stable page ordering used at batch entry points. */
object ResumeOrdering {

    /** Stable natural order projection used by chapter batch entry points. */
    fun <T> naturalOrder(items: List<T>): List<T> = items.toList()
}
