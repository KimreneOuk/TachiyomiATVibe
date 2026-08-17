package eu.kanade.translation.webtoon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Cross-page speech bubble seam stitcher for multi-strip webtoons.
 *
 * Slices along consecutive page boundaries often cut a speech bubble in half.
 * This stitcher identifies edge-intersecting candidate bubbles on Page N, pairs
 * the bottom of Page N with the top of Page N+1 into a virtual seam window, and
 * reconstructs a unified bubble and complete sentence for OCR and AI translation.
 */
object WebtoonSeamStitcher {

    const val DEFAULT_SEAM_MARGIN_PX = 300
    const val EDGE_PROXIMITY_THRESHOLD_PX = 15

    data class SeamWindowConfig(
        val seamWidth: Int,
        val pageNBottomHeight: Int,
        val pageNPlus1TopHeight: Int,
        val totalSeamHeight: Int,
    )

    data class PartitionedInpaintBoxes(
        val pageNInpaintBox: IntArray?,
        val pageNPlus1InpaintBox: IntArray?,
    )

    /**
     * Determines if a detection or bounding box intersects or is immediately adjacent
     * to the bottom boundary of Page N.
     */
    fun isBottomEdgeCandidate(
        bbox: IntArray,
        pageHeight: Int,
        proximityThresholdPx: Int = EDGE_PROXIMITY_THRESHOLD_PX,
    ): Boolean {
        if (pageHeight <= 0 || bbox.size < 4) return false
        val boxBottom = bbox[3]
        return boxBottom >= (pageHeight - proximityThresholdPx)
    }

    /**
     * Determines if a detection or bounding box intersects or is immediately adjacent
     * to the top boundary of Page N+1.
     */
    fun isTopEdgeCandidate(
        bbox: IntArray,
        proximityThresholdPx: Int = EDGE_PROXIMITY_THRESHOLD_PX,
    ): Boolean {
        if (bbox.size < 4) return false
        val boxTop = bbox[1]
        return boxTop <= proximityThresholdPx
    }

    /**
     * Computes the dimensions for a virtual seam window between Page N and Page N+1.
     */
    fun computeSeamConfig(
        pageNWidth: Int,
        pageNHeight: Int,
        pageNPlus1Width: Int,
        pageNPlus1Height: Int,
        maxSeamMarginPx: Int = DEFAULT_SEAM_MARGIN_PX,
    ): SeamWindowConfig {
        val seamWidth = min(pageNWidth, pageNPlus1Width)
        val hBottom = min(maxSeamMarginPx, pageNHeight)
        val hTop = min(maxSeamMarginPx, pageNPlus1Height)
        return SeamWindowConfig(
            seamWidth = seamWidth,
            pageNBottomHeight = hBottom,
            pageNPlus1TopHeight = hTop,
            totalSeamHeight = hBottom + hTop,
        )
    }

    /**
     * Composites the bottom margin of Page N and top margin of Page N+1 into a single
     * virtual seam bitmap for unified OCR and detection.
     */
    fun createSeamBitmap(
        pageNBitmap: Bitmap,
        pageNPlus1Bitmap: Bitmap,
        config: SeamWindowConfig,
    ): Bitmap {
        val seamBitmap = Bitmap.createBitmap(config.seamWidth, config.totalSeamHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(seamBitmap)

        // Draw bottom of Page N
        val srcRectN = Rect(0, pageNBitmap.height - config.pageNBottomHeight, config.seamWidth, pageNBitmap.height)
        val dstRectN = Rect(0, 0, config.seamWidth, config.pageNBottomHeight)
        canvas.drawBitmap(pageNBitmap, srcRectN, dstRectN, null)

        // Draw top of Page N+1
        val srcRectNPlus1 = Rect(0, 0, config.seamWidth, config.pageNPlus1TopHeight)
        val dstRectNPlus1 = Rect(0, config.pageNBottomHeight, config.seamWidth, config.totalSeamHeight)
        canvas.drawBitmap(pageNPlus1Bitmap, srcRectNPlus1, dstRectNPlus1, null)

        return seamBitmap
    }

    /**
     * Partitions a unified seam bounding box across the seam boundary into inpaint/render boxes
     * for Page N and Page N+1 respectively.
     *
     * @param seamBbox Bounding box in seam canvas coordinates [x1, y1, x2, y2]
     * @param pageNBottomHeight Height of Page N's contribution to the seam canvas
     * @param pageNTotalHeight Total height of Page N in full original coordinates
     */
    fun partitionSeamBox(
        seamBbox: IntArray,
        pageNBottomHeight: Int,
        pageNTotalHeight: Int,
    ): PartitionedInpaintBoxes {
        val x1 = seamBbox[0]
        val y1 = seamBbox[1]
        val x2 = seamBbox[2]
        val y2 = seamBbox[3]

        val spansPageN = y1 < pageNBottomHeight
        val spansPageNPlus1 = y2 > pageNBottomHeight

        val pageNBox = if (spansPageN) {
            val pageNTop = pageNTotalHeight - pageNBottomHeight + y1
            val pageNBottom = min(pageNTotalHeight, pageNTotalHeight - pageNBottomHeight + min(y2, pageNBottomHeight))
            intArrayOf(x1, pageNTop, x2, pageNBottom)
        } else {
            null
        }

        val pageNPlus1Box = if (spansPageNPlus1) {
            val pageNPlus1Top = max(0, y1 - pageNBottomHeight)
            val pageNPlus1Bottom = y2 - pageNBottomHeight
            intArrayOf(x1, pageNPlus1Top, x2, pageNPlus1Bottom)
        } else {
            null
        }

        return PartitionedInpaintBoxes(pageNBox, pageNPlus1Box)
    }

    /**
     * Splits multi-line translated text across a seam boundary based on line count and layout heights.
     */
    fun partitionTextLines(
        lines: List<String>,
        fractionOnPageN: Float,
    ): Pair<List<String>, List<String>> {
        if (lines.isEmpty()) return Pair(emptyList(), emptyList())
        if (lines.size == 1) {
            return if (fractionOnPageN >= 0.5f) Pair(lines, emptyList()) else Pair(emptyList(), lines)
        }

        val splitIdx = (lines.size * fractionOnPageN).toInt().coerceIn(1, lines.size - 1)
        val linesN = lines.subList(0, splitIdx)
        val linesNPlus1 = lines.subList(splitIdx, lines.size)
        return Pair(linesN, linesNPlus1)
    }

    data class StitchedBoundaryResult(
        val pageNUpdatedBlocks: List<TranslationBlock>,
        val pageNPlus1UpdatedBlocks: List<TranslationBlock>,
        val stitchedCount: Int,
    )

    /**
     * Executes cross-page seam stitching between consecutive pages [pageN] and [pageNPlus1].
     * Returns updated block lists with unified speech bubbles, or null if no boundary seam bubbles exist.
     */
    fun stitchPageBoundary(
        pageN: eu.kanade.translation.model.PageTranslation,
        pageNPlus1: eu.kanade.translation.model.PageTranslation,
        pageNBitmap: Bitmap,
        pageNPlus1Bitmap: Bitmap,
        analyzeFn: (Bitmap) -> eu.kanade.translation.model.PageTranslation,
    ): StitchedBoundaryResult? {
        // Gating: Only active for tall images (webtoons/manhwa). Standard manga pages bypass this entirely.
        if (!WebtoonSlidingDetector.isTallImage(pageNBitmap.width, pageNBitmap.height) &&
            !WebtoonSlidingDetector.isTallImage(pageNPlus1Bitmap.width, pageNPlus1Bitmap.height)
        ) {
            return null
        }

        val hN = pageNBitmap.height
        val hasBottomEdge = pageN.blocks.any { block ->
            isBottomEdgeCandidate(
                intArrayOf(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt()),
                hN,
            )
        }
        val hasTopEdge = pageNPlus1.blocks.any { block ->
            isTopEdgeCandidate(
                intArrayOf(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt()),
            )
        }

        if (!hasBottomEdge && !hasTopEdge) {
            return null
        }

        val config = computeSeamConfig(pageNBitmap.width, pageNBitmap.height, pageNPlus1Bitmap.width, pageNPlus1Bitmap.height, DEFAULT_SEAM_MARGIN_PX)
        val seamBitmap = createSeamBitmap(pageNBitmap, pageNPlus1Bitmap, config)

        try {
            val seamTranslation = analyzeFn(seamBitmap)
            val seamSplitY = config.pageNBottomHeight

            // Find unified blocks on the seam that cross the page boundary
            val crossingBlocks = seamTranslation.blocks.filter { block ->
                val top = block.y
                val bottom = block.y + block.height
                top < seamSplitY && bottom > seamSplitY
            }

            if (crossingBlocks.isEmpty()) {
                return null
            }

            val newBlocksN = pageN.blocks.filterNot { block ->
                isBottomEdgeCandidate(
                    intArrayOf(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt()),
                    hN,
                )
            }.toMutableList()

            val newBlocksNPlus1 = pageNPlus1.blocks.filterNot { block ->
                isTopEdgeCandidate(
                    intArrayOf(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt()),
                )
            }.toMutableList()

            for (unified in crossingBlocks) {
                val seamBox = intArrayOf(
                    unified.x.toInt(),
                    unified.y.toInt(),
                    (unified.x + unified.width).toInt(),
                    (unified.y + unified.height).toInt(),
                )
                val partitioned = partitionSeamBox(seamBox, config.pageNBottomHeight, hN)

                val fractionN = (seamSplitY - unified.y) / unified.height
                val rawLines = unified.text.split("\n")
                val (linesN, linesNPlus1) = partitionTextLines(rawLines, fractionN)

                if (partitioned.pageNInpaintBox != null) {
                    val pBox = partitioned.pageNInpaintBox
                    val blockN = unified.copy(
                        x = pBox[0].toFloat(),
                        y = pBox[1].toFloat(),
                        width = (pBox[2] - pBox[0]).toFloat(),
                        height = (pBox[3] - pBox[1]).toFloat(),
                        text = if (linesN.isNotEmpty()) linesN.joinToString("\n") else unified.text,
                    )
                    newBlocksN.add(blockN)
                }

                if (partitioned.pageNPlus1InpaintBox != null) {
                    val pBox = partitioned.pageNPlus1InpaintBox
                    val blockNPlus1 = unified.copy(
                        x = pBox[0].toFloat(),
                        y = pBox[1].toFloat(),
                        width = (pBox[2] - pBox[0]).toFloat(),
                        height = (pBox[3] - pBox[1]).toFloat(),
                        text = if (linesNPlus1.isNotEmpty()) linesNPlus1.joinToString("\n") else unified.text,
                    )
                    newBlocksNPlus1.add(blockNPlus1)
                }
            }

            return StitchedBoundaryResult(
                pageNUpdatedBlocks = newBlocksN,
                pageNPlus1UpdatedBlocks = newBlocksNPlus1,
                stitchedCount = crossingBlocks.size,
            )
        } finally {
            seamBitmap.recycle()
        }
    }
}
