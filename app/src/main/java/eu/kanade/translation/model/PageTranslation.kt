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
        val EMPTY = PageTranslation()
    }

}

object StageStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val READY = "READY"
    const val FAILED = "FAILED"
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
    val textColor: Long = 0xFF000000,
    val strokeColor: Long = 0xFFFFFFFF,
    val strokeWidth: Float = 0f,
    val direction: String = "LTR",
)
