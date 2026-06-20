package eu.kanade.translation.model

import android.graphics.Bitmap
import eu.kanade.translation.detection.Detection
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
    var cleanedImageName: String? = null,
    var renderedImageName: String? = null,
    var renderQuality: String = RenderQuality.UNKNOWN,
    var renderedWidth: Int = 0,
    var renderedHeight: Int = 0,
    var recognitionEngine: String? = null,
    var detectionCount: Int = 0,
    var ocrBlockCount: Int = 0,
    var decodeSampleSize: Int = 1,
    var originalImgWidth: Float = 0f,
    var originalImgHeight: Float = 0f,
    var ocrStatus: String = StageStatus.PENDING,
    var translationStatus: String = StageStatus.PENDING,
    var inpaintStatus: String = StageStatus.PENDING,
    var renderStatus: String = StageStatus.PENDING,
    var errorMessage: String? = null,
    var updatedAt: Long = 0L,
    var sourceFileName: String? = null,
    // TachiyomiAT: counts how many times a stage on this page has been retried
    // after a failure. Persisted (serialized) so it survives a chapter reopen —
    // auto-translate uses it to bound retries (see MAX_STAGE_RETRIES) instead of
    // skipping a FAILED page forever or retrying it in an infinite loop.
    var retryCount: Int = 0,
    // Incremented when the rendered file's bytes are rewritten. The file name is
    // stable (<page>.rendered.png for new renders), so UI dedup must not key on
    // the name alone.
    var inpaintRevision: Int = 0,
    var renderRevision: Long = 0L,
) {
    @Transient
    var cleanedBitmap: Bitmap? = null

    /**
     * TachiyomiAT: all text detections from the recognition stage, carried
     * per-page so they survive from analyze() into inpaint() without relying
     * on shared mutable state on the (singleton) recognition engine. This
     * removes a race where concurrent pages overwrote each other's detections.
     * Not serialized.
     */
    @Transient
    var allTextDetections: List<Detection> = emptyList()

    companion object {
        const val CURRENT_INPAINT_REVISION = 8
        val EMPTY = PageTranslation()
    }
}

object StageStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val READY = "READY"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    /**
     * TachiyomiAT: maximum number of times auto-translate will re-attempt a page
     * whose stage(s) failed. Bounds the retry loop so a genuinely broken page
     * (corrupt image, persistent OOM) can't spin forever holding the singleton
     * translator permit — while still recovering transient failures (e.g. an OOM
     * right after a chapter switch). The manual per-page translate button is
     * NOT bound by this (it always retries), so a user can force more attempts.
     */
    const val MAX_STAGE_RETRIES = 2
}

object RenderQuality {
    const val UNKNOWN = "UNKNOWN"
    const val FULL = "FULL"
    const val SIZE_LIMITED = "SIZE_LIMITED"
}

@Serializable
data class TranslationBlock(
    var text: String,
    var translation: String = "",
    var width: Float,
    var height: Float,
    var x: Float,
    var y: Float,
    var symHeight: Float,
    var symWidth: Float,
    val angle: Float,
    val label: Int = 1,
    val score: Float = 1f,
    val parentX: Float = 0f,
    val parentY: Float = 0f,
    val parentWidth: Float = 0f,
    val parentHeight: Float = 0f,
    // TachiyomiAT: textColor/strokeColor/strokeWidth are `var` (not `val`) so they
    // can be RE-DERIVED after inpainting against the cleaned bitmap (see
    // RenderColorEstimator + ChapterTranslator). Colors sampled against the
    // original bitmap at recognition time can be wrong once inpainting replaces
    // the background with a different median color — so the renderer needs the
    // post-inpaint colors to keep "dark inpaint → light text" legible.
    // Serialization is field-name based, so val→var is backward compatible.
    var textColor: Long = 0xFF000000,
    var strokeColor: Long = 0xFFFFFFFF,
    var strokeWidth: Float = 0f,
    val direction: String = "LTR",
)
