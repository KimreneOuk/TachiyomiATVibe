package eu.kanade.translation.util

/**
 * Legacy page-order helpers. Chapter batches use [naturalOrder]; the reader's
 * last-read position is not a batch scheduling input.
 *
 * Strategy (forward-first, then backfill): pages from [resumeIndex] to the end
 * are translated first (the user is about to read these), then pages before the
 * resume index are backfilled. This is the opposite of naive file-order, which
 * makes a user mid-chapter (e.g. page 50 of 200) wait on pages 1–49 they've
 * already read before the page they want is ready.
 *
 * Pure and side-effect-free so it is unit-testable without any engine/store.
 */
object ResumeOrdering {

    /** Stable natural order projection used by chapter batch entry points. */
    fun <T> naturalOrder(items: List<T>): List<T> = items.toList()

    /**
     * Reorder [items] so that the element at [resumeIndex] comes first, followed
     * by the rest forward to the end, then the elements before [resumeIndex].
     *
     * [resumeIndex] is clamped to [0, items.size]; out-of-range values degrade
     * gracefully:
     *   - resumeIndex <= 0  -> unchanged (start of chapter)
     *   - resumeIndex >= size -> unchanged (fully read; whole chapter in order)
     *   - empty list -> empty list
     */
    fun <T> forwardFirstThenBackfill(items: List<T>, resumeIndex: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1) return items.toList()
        val safeIndex = resumeIndex.coerceIn(0, items.size)
        if (safeIndex == 0 || safeIndex >= items.size) return items.toList()
        // subList views are backed by the original; toList to detach so callers
        // can freely mutate the result without aliasing the input.
        return items.subList(safeIndex, items.size).toList() +
            items.subList(0, safeIndex).toList()
    }
}
