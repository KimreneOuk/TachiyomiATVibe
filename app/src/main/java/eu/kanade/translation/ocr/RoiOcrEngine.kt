package eu.kanade.translation.ocr

import android.graphics.Bitmap
import java.io.Closeable

interface RoiOcrEngine : Closeable {
    suspend fun recognize(crop: Bitmap): String

    /**
     * TachiyomiAT: whether this engine reads HORIZONTAL text lines only.
     *
     * Manga speech is often laid out VERTICALLY (top-to-bottom columns). Some
     * recognizers handle vertical layout natively (ML Kit's CJK recognizers,
     * MangaOcr — both trained on and tolerant of vertical text), while others
     * are strictly horizontal-line models (PaddleOCR's CTC recognition head).
     *
     * [RoiPageRecognitionEngine] rotates a tall (vertical) crop 90° clockwise
     * BEFORE handing it to the engine ONLY when this property is true. Rotating
     * for a native-vertical engine (the previous behavior) actively degraded it:
     * ML Kit expects top-to-bottom text, so feeding it pre-rotated horizontal
     * text produced truncated/garbled output. Defaulting to false means a
     * native-vertical engine gets the crop as-is; horizontal-only engines
     * (PaddleOCR) override this to true so each vertical column becomes a line.
     */
    val prefersHorizontalText: Boolean
        get() = false
}
