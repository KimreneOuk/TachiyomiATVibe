package eu.kanade.translation.pipeline.batch

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide cross-origin decoded-bitmap budget.
 *
 * Preserves the 6 GB bounded memory invariant across concurrent lookahead decode,
 * inpaint re-decode, manual reader taps, and chapter preflights.
 *
 * Tier ceiling: At most [DEFAULT_MAX_CONCURRENT_BITMAPS] batch bitmaps are held in
 * memory simultaneously. Interactive reader taps have guaranteed capacity.
 */
object CrossOriginBitmapBudget {
    internal const val DEFAULT_MAX_CONCURRENT_BITMAPS = 2

    private val activeBatchBitmaps = AtomicInteger(0)
    private val semaphore = Semaphore(DEFAULT_MAX_CONCURRENT_BITMAPS)

    val activeCount: Int
        get() = activeBatchBitmaps.get()

    /**
     * Acquires a batch bitmap slot. Suspends if all slots are currently held
     * by active decoded pages until one is recycled or released.
     */
    suspend fun acquireBatchPermit() {
        semaphore.acquire()
        activeBatchBitmaps.incrementAndGet()
    }

    /**
     * Releases a batch bitmap slot upon recycling / cleanup.
     */
    fun releaseBatchPermit() {
        if (activeBatchBitmaps.get() > 0) {
            activeBatchBitmaps.decrementAndGet()
            semaphore.release()
        }
    }

    /**
     * Resets the budget permits (useful in test harnesses and session restarts).
     */
    internal fun resetForTesting() {
        while (activeBatchBitmaps.get() > 0) {
            releaseBatchPermit()
        }
    }
}
