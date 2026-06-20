package eu.kanade.translation.inpainting

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognition.BoxGeometry
import kotlin.math.max

object PageInpaintingPlanner {

    data class InpaintingInput(
        val boxes: List<IntArray>,
        val labels: List<Int>,
        val extraDetectorCount: Int,
    ) {
        val isEmpty: Boolean
            get() = boxes.isEmpty()
    }

    fun build(pageTranslation: PageTranslation): InpaintingInput {
        val bubbleBoxes = pageTranslation.blocks
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

        val textBoxLabels = pageTranslation.blocks
            .mapNotNull { block ->
                val box = intArrayOf(
                    block.x.toInt(),
                    block.y.toInt(),
                    (block.x + block.width).toInt(),
                    (block.y + block.height).toInt(),
                )
                if (box.isValid()) box to block.label else null
            }
        val textBoxes = textBoxLabels.map { it.first }

        val ocrBlockBoxes = textBoxes.map { it.copyOf() }
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
                ocrBlockBoxes.none { ocrBox ->
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
        )
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
