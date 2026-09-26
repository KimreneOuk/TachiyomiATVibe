package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrFallbackKind
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRotation

/**
 * One final Paddle crop produced after the existing line/column/glyph geometry
 * has completed. The source bitmap is owned by this plan until the coordinator
 * wraps it in a [eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrCropOwnership]
 * token.
 */
internal data class PaddleVerticalLeafPlan(
    val crop: Bitmap,
    val lineIndex: Int?,
    val glyphIndex: Int?,
    val rotation: PaddleOcrRotation,
    val fallbackKind: PaddleOcrFallbackKind,
)

/**
 * Ordered recognition leaves plus the groups that reconstruct one region's
 * text. A group is one detected line/heuristic column; glyphs within it are
 * joined with the language separator before the groups are joined in reading
 * order. Empty groups are intentionally retained so filtering has the same
 * semantics as the pre-batch path.
 */
internal data class PaddleVerticalRecognitionPlan(
    val leaves: List<PaddleVerticalLeafPlan>,
    val groups: List<List<Int>>,
    val separator: String,
    val filterConfidence: Boolean = true,
)
