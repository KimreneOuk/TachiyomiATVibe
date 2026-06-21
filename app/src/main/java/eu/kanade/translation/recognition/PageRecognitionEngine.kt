package eu.kanade.translation.recognition

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import java.io.Closeable

interface PageRecognitionEngine : Closeable {
    suspend fun analyze(bitmap: Bitmap): PageTranslation

    suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? = null

    /**
     * TachiyomiAT: release engine-owned off-heap/pooled memory that persists
     * across pages but that [close] would also free. Leaves the engine usable.
     *
     * Used by the OOM-recovery path: the ONNX engines (notably MangaOcr's
     * direct KV-cache buffers) hold native allocations that survive a Java GC
     * and BitmapPool.releaseAll(), so without an explicit reclaim the heap
     * pressure that triggered an OOM on page N persists into page N+1 and the
     * OOM recurs — the chronic-OOM feedback loop behind "image keeps getting
     * worse / eventually stops". Engines without pooled native memory do nothing.
     */
    fun reclaimPooledMemory() {}

    /**
     * TachiyomiAT: force release native buffers and pools when memory pressure is high.
     */
    fun forceReleaseNativeBuffers() {}

    suspend fun recognize(bitmap: Bitmap): PageTranslation {
        val pageTranslation = analyze(bitmap)
        pageTranslation.cleanedBitmap = inpaint(bitmap, pageTranslation)
        return pageTranslation
    }
}
