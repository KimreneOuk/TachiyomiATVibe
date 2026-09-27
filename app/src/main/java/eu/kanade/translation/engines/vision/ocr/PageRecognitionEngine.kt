package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import java.io.Closeable

interface PageRecognitionEngine : Closeable {
    suspend fun analyze(bitmap: Bitmap): PageTranslation

    suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? = null

    /**
     * release engine-owned off-heap/pooled memory that persists
     * across pages but that [close] would also free. Leaves the engine usable.
     *
     * Called by OOM recovery: ONNX engines (notably MangaOcr's KV-cache buffers)
     * hold native allocations that survive GC and BitmapPool.releaseAll(), so
     * without an explicit reclaim the pressure that OOM'd page N persists into
     * page N+1 (the chronic-OOM loop). No-op for engines without pooled memory.
     */
    fun reclaimPooledMemory() {}

    /**
     * force release native buffers and pools when memory pressure is high.
     */
    fun forceReleaseNativeBuffers() {}

    suspend fun recognize(bitmap: Bitmap): PageTranslation {
        val pageTranslation = analyze(bitmap)
        pageTranslation.cleanedBitmap = inpaint(bitmap, pageTranslation)
        return pageTranslation
    }
}
