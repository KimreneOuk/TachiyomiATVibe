package eu.kanade.translation.inpainting

import eu.kanade.translation.detection.Detection
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognition.BoxGeometry
import kotlin.math.max

/**
 * TachiyomiAT: builds the list of regions the inpainter must erase for one page.
 *
 * Two entry points, one source of truth:
 *  - [computeMask] is called ONCE at the end of [PageRecognitionEngine.analyze]
 *    (i.e. at OCR time, BEFORE translation/watermark filtering) to capture a
 *    durable mask. It is persisted on [PageTranslation.inpaintMaskBoxes].
 *  - [build] is called at inpaint time. It returns the persisted mask when
 *    present (the resume / reopened-store path, where the transient
 *    `allTextDetections` has been lost) and otherwise recomputes from the live
 *    `blocks` + `allTextDetections` (the fresh single-page path).
 *
 * Why a durable mask matters: the inpainter must erase detector-only regions
 * (filtered out of OCR by dedupe/suppression) and watermark regions (removed
 * from `blocks` by [TranslationBlockFilters] after translate). Both classes of
 * region vanish from `blocks` before inpaint on the resume path, so deriving
 * the mask from `blocks` alone at inpaint time leaves source text / watermarks
 * visible. Capturing the full erase set at OCR time and persisting it closes
 * that hole.
 */
object PageInpaintingPlanner {

    data class InpaintingInput(
        val boxes: List<IntArray>,
        val labels: List<Int>,
        val extraDetectorCount: Int,
        val source: MaskSource,
    ) {
        val isEmpty: Boolean
            get() = boxes.isEmpty()
    }

    /** Where [InpaintingInput.boxes] came from — surfaced for diagnostics. */
    enum class MaskSource {
        /** Mask was loaded from the persisted [PageTranslation.inpaintMaskBoxes]. */
        PERSISTED,

        /** Mask was recomputed from live blocks + allTextDetections (fresh path). */
        RECOMPUTED,
    }

    /**
     * Builds the inpaint input at inpaint time. Prefers the persisted mask
     * ([PageTranslation.inpaintMaskBoxes]); only recomputes when no persisted
     * mask exists (the fresh single-page path, where `allTextDetections` is
     * still populated in memory).
     */
    fun build(pageTranslation: PageTranslation): InpaintingInput {
        val persisted = pageTranslation.inpaintMaskBoxes
        if (persisted.isNotEmpty()) {
            val boxes = persisted.map { it.toIntArray() }
            val extraDetectorCount = persisted.count { it.label == DETECTOR_TEXT_LABEL }
            return InpaintingInput(
                boxes = boxes,
                labels = persisted.map { it.label },
                extraDetectorCount = extraDetectorCount,
                source = MaskSource.PERSISTED,
            )
        }
        return recomputeFromLiveDetections(pageTranslation)
    }

    /**
     * Captures the durable erase mask from the recognition result. Call this
     * exactly once, at the end of [PageRecognitionEngine.analyze], and persist
     * the result onto [PageTranslation.inpaintMaskBoxes]. Computing here (not
     * at inpaint time) is what makes the mask survive translation-stage block
     * removal and process death.
     *
     * Render-aware erase (contract #14a preserved): the mask is built only from
     * blocks whose OCR text was READ (non-blank). An unread region — low-conf-
     * blanked or organically-empty OCR — contributes NEITHER its text box nor
     * its bubble box, so its ORIGINAL pixels stay visible instead of being
     * erased to an empty void. Watermark blocks still carry text here (they are
     * removed from `blocks` only later, after translate), so they are erased as
     * before; detector-only boxes (`allTextDetections`) are disjoint from any
     * OCR block and are erased as before. It deliberately does NOT consult
     * `block.translation` — translation has not run yet at OCR time, so this is
     * naturally immune to the watermark-block-removal ordering.
     */
    fun computeMask(pageTranslation: PageTranslation): List<InpaintMaskBox> {
        val blocks = pageTranslation.blocks
        // Only readable blocks contribute to the erase set.
        val readable = blocks.filter { it.text.isNotBlank() }

        val bubbleBoxes = readable
            .filter { it.parentWidth > 0f && it.parentHeight > 0f }
            .map { block ->
                intArrayOf(
                    block.parentX.toInt(),
                    block.parentY.toInt(),
                    (block.parentX + block.parentWidth).toInt(),
                    (block.parentY + block.parentHeight).toInt(),
                )
            }
            .filterValid()
            .distinctBy { it.toList() }

        val textBoxLabels = readable
            .mapNotNull { block -> blockTextBox(block)?.let { it to block.label } }
        val textBoxes = textBoxLabels.map { it.first }

        // Overlap reference = ALL OCR block boxes (readable AND blank). A
        // detector box overlapping a blank-text block must NOT be re-added as an
        // extra-detector entry, or it would re-erase exactly the region we are
        // deliberately preserving above.
        val allOcrBoxes = blocks.mapNotNull { blockTextBox(it) }
        val extraDetectorBoxes = pageTranslation.allTextDetections
            .map { it.bbox }
            .filterValid()
            .filter { detBox ->
                val expanded = intArrayOf(
                    max(0, detBox[0] - DETECTOR_OVERLAP_PAD),
                    max(0, detBox[1] - DETECTOR_OVERLAP_PAD),
                    detBox[2] + DETECTOR_OVERLAP_PAD,
                    detBox[3] + DETECTOR_OVERLAP_PAD,
                )
                allOcrBoxes.none { ocrBox ->
                    BoxGeometry.iou(expanded, ocrBox) > DETECTOR_OCR_IOU_THRESHOLD
                }
            }
            .distinctBy { it.toList() }

        val mask = mutableListOf<InpaintMaskBox>()
        bubbleBoxes.forEach { box ->
            mask.add(InpaintMaskBox(box[0], box[1], box[2], box[3], BUBBLE_LABEL))
        }
        textBoxLabels.forEach { (box, label) ->
            mask.add(InpaintMaskBox(box[0], box[1], box[2], box[3], label))
        }
        extraDetectorBoxes.forEach { box ->
            mask.add(InpaintMaskBox(box[0], box[1], box[2], box[3], DETECTOR_TEXT_LABEL))
        }
        return mask
    }

    /**
     * Live (non-resume) path: derive the mask from the in-memory
     * [PageTranslation.blocks] + [PageTranslation.allTextDetections]. This is
     * byte-equivalent to [computeMask]; it exists only because [build] must
     * return [InpaintingInput] (IntArrays for the inpainter API) rather than
     * the serializable [InpaintMaskBox] list, and the fresh path skips the
     * intermediate persisted form.
     */
    private fun recomputeFromLiveDetections(pageTranslation: PageTranslation): InpaintingInput {
        val blocks = pageTranslation.blocks
        // Render-aware: mirror computeMask exactly (byte-equivalent semantics).
        val readable = blocks.filter { it.text.isNotBlank() }

        val bubbleBoxes = readable
            .filter { it.parentWidth > 0f && it.parentHeight > 0f }
            .map { block ->
                intArrayOf(
                    block.parentX.toInt(),
                    block.parentY.toInt(),
                    (block.parentX + block.parentWidth).toInt(),
                    (block.parentY + block.parentHeight).toInt(),
                )
            }
            .filterValid()
            .distinctBy { it.toList() }

        val textBoxLabels = readable
            .mapNotNull { block -> blockTextBox(block)?.let { it to block.label } }
        val textBoxes = textBoxLabels.map { it.first }

        val allOcrBoxes = blocks.mapNotNull { blockTextBox(it) }
        val extraDetectorBoxes = pageTranslation.allTextDetections
            .map { it.bbox }
            .filterValid()
            .filter { detBox ->
                val expanded = intArrayOf(
                    max(0, detBox[0] - DETECTOR_OVERLAP_PAD),
                    max(0, detBox[1] - DETECTOR_OVERLAP_PAD),
                    detBox[2] + DETECTOR_OVERLAP_PAD,
                    detBox[3] + DETECTOR_OVERLAP_PAD,
                )
                allOcrBoxes.none { ocrBox ->
                    BoxGeometry.iou(expanded, ocrBox) > DETECTOR_OCR_IOU_THRESHOLD
                }
            }
            .distinctBy { it.toList() }

        val boxes = bubbleBoxes + textBoxes + extraDetectorBoxes
        val labels = List(bubbleBoxes.size) { BUBBLE_LABEL } +
            textBoxLabels.map { it.second } +
            List(extraDetectorBoxes.size) { DETECTOR_TEXT_LABEL }

        return InpaintingInput(
            boxes = boxes,
            labels = labels,
            extraDetectorCount = extraDetectorBoxes.size,
            source = MaskSource.RECOMPUTED,
        )
    }

    private fun blockTextBox(block: TranslationBlock): IntArray? {
        val box = intArrayOf(
            block.x.toInt(),
            block.y.toInt(),
            (block.x + block.width).toInt(),
            (block.y + block.height).toInt(),
        )
        return if (box.isValid()) box else null
    }

    private fun List<IntArray>.filterValid(): List<IntArray> =
        filter { it.isValid() }

    private fun IntArray.isValid(): Boolean =
        size >= 4 && this[2] > this[0] && this[3] > this[1]

    private const val BUBBLE_LABEL = 0
    private const val DETECTOR_TEXT_LABEL = 2
    private const val DETECTOR_OVERLAP_PAD = 3
    private const val DETECTOR_OCR_IOU_THRESHOLD = 0.4f
}
