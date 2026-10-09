package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.model.Detection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PaddleGroupedRegion(
    val detection: Detection,
    val lines: List<TextLine>,
)

object PaddleLineGrouper {

    private const val MIN_CONTAINMENT_RATIO = 0.5f

    fun groupLinesIntoRegions(
        detections: List<Detection>,
        lines: List<TextLine>,
        imageWidth: Int,
        imageHeight: Int,
        isVerticalLanguage: Boolean,
        isRtl: Boolean,
    ): List<PaddleGroupedRegion> {
        if (lines.isEmpty()) {
            return detections.map { PaddleGroupedRegion(it, emptyList()) }
        }

        val bubbles = detections.filter { it.label == 0 }
        val assignedLines = mutableSetOf<TextLine>()
        val bubbleLineMap = LinkedHashMap<Detection, MutableList<TextLine>>()
        bubbles.forEach { bubbleLineMap[it] = mutableListOf() }

        // 1. Assign lines to speech bubbles
        for (line in lines) {
            val lbox = line.bbox
            val lineArea = max(1, (lbox[2] - lbox[0]) * (lbox[3] - lbox[1]))
            val cx = (lbox[0] + lbox[2]) / 2
            val cy = (lbox[1] + lbox[3]) / 2

            // Best matching bubble by center containment or largest overlap
            var bestBubble: Detection? = null
            var bestOverlap = 0

            for (bubble in bubbles) {
                val bbox = bubble.bbox
                if (cx in bbox[0]..bbox[2] && cy in bbox[1]..bbox[3]) {
                    bestBubble = bubble
                    break
                }
                val overlap = BoxGeometry.intersectionArea(lbox, bbox)
                if (overlap > bestOverlap && overlap >= lineArea * MIN_CONTAINMENT_RATIO) {
                    bestOverlap = overlap
                    bestBubble = bubble
                }
            }

            if (bestBubble != null) {
                bubbleLineMap[bestBubble]!!.add(line)
                assignedLines.add(line)
            }
        }

        // 2. Cluster unassigned lines into free-text regions
        val orphanLines = lines.filter { it !in assignedLines }
        val freeTextClusters = clusterOrphanLines(orphanLines)

        // 3. Build grouped regions and sort lines within each region
        val result = mutableListOf<PaddleGroupedRegion>()

        for ((bubble, bLines) in bubbleLineMap) {
            val sortedLines = sortLines(bLines, isVerticalLanguage)
            result.add(PaddleGroupedRegion(bubble.copy(lines = sortedLines), sortedLines))
        }

        for (cluster in freeTextClusters) {
            val sortedLines = sortLines(cluster, isVerticalLanguage)
            val minX = cluster.minOf { it.bbox[0] }
            val minY = cluster.minOf { it.bbox[1] }
            val maxX = cluster.maxOf { it.bbox[2] }
            val maxY = cluster.maxOf { it.bbox[3] }
            val syntheticDetection = Detection(
                bbox = intArrayOf(minX, minY, maxX, maxY),
                label = 2, // text_free
                score = cluster.map { it.score }.average().toFloat(),
                className = "text_free",
                lines = sortedLines,
            )
            result.add(PaddleGroupedRegion(syntheticDetection, sortedLines))
        }

        // 4. Sort regions by page reading order
        return sortRegions(result, imageWidth, isRtl)
    }

    private fun clusterOrphanLines(lines: List<TextLine>): List<List<TextLine>> {
        if (lines.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<TextLine>>()
        val unvisited = lines.toMutableList()

        while (unvisited.isNotEmpty()) {
            val current = unvisited.removeAt(0)
            val cluster = mutableListOf(current)
            var expanded = true
            while (expanded) {
                expanded = false
                val iter = unvisited.iterator()
                while (iter.hasNext()) {
                    val candidate = iter.next()
                    if (cluster.any { shouldClusterTogether(it.bbox, candidate.bbox) }) {
                        cluster.add(candidate)
                        iter.remove()
                        expanded = true
                    }
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    private fun shouldClusterTogether(b1: IntArray, b2: IntArray): Boolean {
        val h1 = b1[3] - b1[1]
        val h2 = b2[3] - b2[1]
        val avgH = (h1 + h2) / 2.0
        val dx = abs((b1[0] + b1[2]) / 2 - (b2[0] + b2[2]) / 2)
        val dy = abs((b1[1] + b1[3]) / 2 - (b2[1] + b2[3]) / 2)

        // Vertical text proximity: close horizontally, overlapping vertically
        val verticalMatch = dx < avgH * 1.8 && dy < avgH * 1.5
        // Horizontal text proximity: close vertically, overlapping horizontally
        val horizontalMatch = dy < avgH * 1.2 && dx < avgH * 2.5
        return verticalMatch || horizontalMatch
    }

    private fun sortLines(lines: List<TextLine>, isVerticalLanguage: Boolean): List<TextLine> {
        return lines.sortedBy { line ->
            val w = line.bbox[2] - line.bbox[0]
            val h = line.bbox[3] - line.bbox[1]
            val isVertical = if (isVerticalLanguage) h > w * 0.9f else h > w * 1.5f
            if (isVertical) {
                // Right-to-Left columns
                -(line.bbox[0] + line.bbox[2]) / 2
            } else {
                // Top-to-Bottom rows
                (line.bbox[1] + line.bbox[3]) / 2
            }
        }
    }

    private fun sortRegions(
        regions: List<PaddleGroupedRegion>,
        pageWidth: Int,
        isRtl: Boolean,
    ): List<PaddleGroupedRegion> {
        val bandHeight = max(100, pageWidth / 4)
        return regions.sortedWith(
            compareBy<PaddleGroupedRegion> {
                val cy = (it.detection.bbox[1] + it.detection.bbox[3]) / 2
                cy / bandHeight
            }.thenBy {
                val cx = (it.detection.bbox[0] + it.detection.bbox[2]) / 2
                if (isRtl) -cx else cx
            },
        )
    }
}
