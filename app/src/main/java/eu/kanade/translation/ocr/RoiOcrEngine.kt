package eu.kanade.translation.ocr

import android.graphics.Bitmap
import java.io.Closeable

interface RoiOcrEngine : Closeable {
    suspend fun recognize(crop: Bitmap): String
}
