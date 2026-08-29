package eu.kanade.translation.pipeline.batch

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * T909 Phase 20.1: bounded in-memory cleaned-bitmap registry moved verbatim
 * from `TranslationPipeline.translateBatch` (T909 phase 20).
 *
 * Held-cleaned-bitmap registry: render reuses the in-memory bitmap instead of
 * reloading from disk. Bounded by BOTH a byte ceiling and a count cap; a page
 * exceeding either spills (its .cleaned.jpg is already durable, so the bitmap
 * recycles immediately and render reloads it).
 */
internal class HeldBitmapRegistry {

    companion object {
        /**
         * Hard cap on the number of in-memory cleaned bitmaps the 3-lane batch
         * pipeline holds for render reuse at once. A pure count cap is unsafe for
         * memory (3 tiny pages say nothing about 3x a 48MB webtoon strip), so it
         * is enforced together with [HELD_BITMAP_BYTE_CEILING]; the byte ceiling is
         * the real bound and this count just prevents runaway concurrency on many
         * tiny pages.
         */
        const val HELD_BITMAP_MAX_COUNT = 4

        /**
         * Approximate byte ceiling for the held cleaned-bitmap registry in the
         * 3-lane batch pipeline (~48 MB; a worst-case webtoon long-strip page).
         * Pages that would push the registry past this spill to disk (their
         * versioned .cleaned.jpg is already durable at inpaint) and reload on render —
         * today's behavior. Keeps peak held memory provable against the ceiling
         * regardless of page or chunk size.
         */
        const val HELD_BITMAP_BYTE_CEILING = 48L * 1024L * 1024L
    }

    val heldBitmapBytes = AtomicLong(0L)
    val countSlots = Semaphore(HELD_BITMAP_MAX_COUNT)
    val bitmapRegistry = ConcurrentHashMap<String, Bitmap>()

    fun holdCleaned(pageKey: String, cleaned: Bitmap?) {
        if (cleaned == null) return
        val acquired = countSlots.tryAcquire()
        val fits = acquired && heldBitmapBytes.get() + cleaned.byteCount <= HELD_BITMAP_BYTE_CEILING
        if (fits) {
            heldBitmapBytes.addAndGet(cleaned.byteCount.toLong())
            bitmapRegistry[pageKey] = cleaned
        } else {
            if (acquired) countSlots.release()
            try {
                cleaned.recycle()
            } catch (_: Exception) {}
        }
    }

    fun recycleHeld(pageKey: String) {
        val b = bitmapRegistry.remove(pageKey) ?: return
        heldBitmapBytes.addAndGet(-b.byteCount.toLong())
        try {
            b.recycle()
        } catch (_: Exception) {}
        countSlots.release()
    }
}
