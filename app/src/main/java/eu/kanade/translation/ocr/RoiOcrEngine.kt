package eu.kanade.translation.ocr

import android.graphics.Bitmap
import java.io.Closeable

interface RoiOcrEngine : Closeable {
    suspend fun recognize(crop: Bitmap): String

    /**
     * Provider that actually serves this engine ("qnn_htp"/"nnapi"/"cpu"), for
     * honest perf logging. Defaults to CPU; accelerator-backed engines
     * override it after session creation.
     */
    val executionProviderLabel: String get() = "cpu"

    suspend fun recognizeWithConf(crop: Bitmap): Pair<String, Float> = recognize(crop) to 1f

    suspend fun recognizeBatch(crops: List<Bitmap>): List<String> = crops.map { recognize(it) }

    suspend fun recognizeBatchWithConf(crops: List<Bitmap>): List<Pair<String, Float>> =
        recognizeBatch(crops).map { it to 1f }

    /**
     * TachiyomiAT: cooperative hint to release engine-owned off-heap/pooled memory
     * that [close] would free but that can otherwise persist across calls.
     *
     * Unlike [close], this leaves the engine usable: the next [recognize]
     * re-acquires whatever it needs. The motivating consumer is per-page memory
     * relief — [MangaOcrEngine] holds direct (off-heap) KV-cache buffer pools
     * that survive every ROI call; `BitmapPool.releaseAll()` + a Java GC cannot
     * reclaim them. For MangaOcrEngine this is intentionally a no-op because each
     * buffer is returned to its pool in the `recognize` `finally` block; the
     * pools themselves are drained only by [forceReleaseNativeBuffers] (the OOM
     * recovery path). Engines without pooled native memory do nothing.
     */
    fun reclaimPooledMemory() {}

    fun forceReleaseNativeBuffers() {}

    /**
     * TachiyomiAT: whether this engine reads HORIZONTAL text lines only.
     *
     * Manga speech is often laid out VERTICALLY (top-to-bottom columns). Some
     * recognizers handle vertical layout natively (ML Kit's CJK recognizers,
     * MangaOcr — both trained on and tolerant of vertical text), while others
     * are strictly horizontal-line models (PaddleOCR's CTC recognition head).
     *
     * [RoiPageRecognitionEngine] rotates a tall (vertical) crop 90° counter-clockwise
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
