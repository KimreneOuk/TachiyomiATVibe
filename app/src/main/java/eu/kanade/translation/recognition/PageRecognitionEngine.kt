package eu.kanade.translation.recognition

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import java.io.Closeable

interface PageRecognitionEngine : Closeable {
    suspend fun analyze(bitmap: Bitmap): PageTranslation

    suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? = null

    suspend fun recognize(bitmap: Bitmap): PageTranslation {
        val pageTranslation = analyze(bitmap)
        pageTranslation.cleanedBitmap = inpaint(bitmap, pageTranslation)
        return pageTranslation
    }
}
