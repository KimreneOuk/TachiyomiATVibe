package eu.kanade.translation.engines.inpainting.bubble

import eu.kanade.translation.engines.inpainting.InpaintPixelOps

internal object BubbleMedianDiffusionFill {

    /** Local ring margin (px) sampled around one component's bounding box. */
    private const val RING_PX = 8

    /**
     * Single-source variant of the report bubble pass: fills [pixels] in place and
     * feathers the result against a copy of the pre-fill values, so callers read
     * the page bitmap once instead of twice.
     *
     * The feather is INWARD-only ([BubbleMaskBuilder.featherAlphaFieldInward]):
     * every pixel outside [mask] stays pixel-identical.
     */
    internal fun fillAndBlend(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        smoothPasses: Int,
        featherRampPx: Int,
    ) {
        val original = pixels.copyOf()
        reportBubbleFill(pixels, mask, width, height, smoothPasses)
        val alpha = BubbleMaskBuilder.featherAlphaFieldInward(mask, width, height, featherRampPx)
        for (i in pixels.indices) {
            val a = alpha[i]
            if (a > 0.0f) pixels[i] = InpaintPixelOps.blendPixel(original[i], pixels[i], a)
        }
    }

    /**
     * Solid median diffusion fill for speech-bubble segmentation masks.
     *
     * Fill-color estimation is hybrid, evaluated PER COMPONENT:
     *  - Paper-dominated interiors (the normal whole-balloon mask, where white
     *    paper vastly outnumbers ink) keep the interior median sampled at
     *    distance >= 2 from the component boundary.
     *  - Ink-dominated interiors (tight glyph masks where the interior sample
     *    IS the text ink, which previously collapsed to a black blob) sample
     *    the UNMASKED ring inside the component's own local bounding box
     *    expanded by [RING_PX]. Sampling excludes every masked pixel, so two
     *    disconnected glyphs on different paper each get their own local
     *    paper color — never one page-global estimate.
     *
     * Near-gray results snap to pure white/black. Limitation: when a
     * component's local ring itself is dark (dense panels, heavy screentone
     * right up to the bubble border), the ring estimate degrades with the
     * data available; no fixture guarantees white in every case.
     */
    internal fun reportBubbleFill(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        smoothPasses: Int,
    ) {
        val visited = BooleanArray(mask.size)
        val distances = IntArray(mask.size)
        val queue = ArrayDeque<Int>()
        val distQueue = ArrayDeque<Int>()

        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue
            visited[start] = true
            queue.clear()
            queue.add(start)
            val component = ArrayList<Int>()

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                component.add(idx)
                val x = idx % width
                val y = idx / width
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val ni = ny * width + nx
                        if (mask[ni] != 0.toByte() && !visited[ni]) {
                            visited[ni] = true
                            queue.add(ni)
                        }
                    }
                }
            }
            if (component.isEmpty()) continue

            for (idx in component) {
                distances[idx] = -1
            }

            distQueue.clear()

            // Boundary pixels: touch background or image edge.
            for (idx in component) {
                val x = idx % width
                val y = idx / width
                var isBoundary = false
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                    isBoundary = true
                } else {
                    val up = idx - width
                    val down = idx + width
                    val left = idx - 1
                    val right = idx + 1
                    if (mask[up] == 0.toByte() ||
                        mask[down] == 0.toByte() ||
                        mask[left] == 0.toByte() ||
                        mask[right] == 0.toByte()
                    ) {
                        isBoundary = true
                    }
                }
                if (isBoundary) {
                    distances[idx] = 0
                    distQueue.add(idx)
                }
            }

            // BFS: Manhattan distance to nearest boundary.
            while (distQueue.isNotEmpty()) {
                val idx = distQueue.removeFirst()
                val d = distances[idx]
                val x = idx % width
                val y = idx / width

                val neighbors = intArrayOf(
                    if (y > 0) idx - width else -1,
                    if (y < height - 1) idx + width else -1,
                    if (x > 0) idx - 1 else -1,
                    if (x < width - 1) idx + 1 else -1,
                )

                for (ni in neighbors) {
                    if (ni != -1 && distances[ni] == -1 && mask[ni] != 0.toByte()) {
                        distances[ni] = d + 1
                        distQueue.add(ni)
                    }
                }
            }

            // Collect histogram for median sampling (distance >= 2 avoids stroke outlines)
            val histR = IntArray(256)
            val histG = IntArray(256)
            val histB = IntArray(256)
            var count = 0

            for (idx in component) {
                if (distances[idx] >= 2) {
                    val p = pixels[idx]
                    histR[(p shr 16) and 0xFF]++
                    histG[(p shr 8) and 0xFF]++
                    histB[p and 0xFF]++
                    count++
                }
            }

            val median: Int
            if (count > 0) {
                val r = InpaintPixelOps.histogramMedian(histR, count)
                val g = InpaintPixelOps.histogramMedian(histG, count)
                val b = InpaintPixelOps.histogramMedian(histB, count)

                median = if (isInkDominated(r, g, b)) {
                    // The interior sample IS the text ink (glyph-mask failure
                    // mode): estimate this component's paper color from its
                    // OWN local unmasked ring, excluding every masked pixel.
                    componentRingPaper(pixels, mask, width, height, component)
                } else {
                    // Paper-dominated interior: keep the interior median and
                    // snap near-gray to pure bubble paper/ink.
                    snapNearGray((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
                }
            } else {
                // Fallback to average of component if it's too small to erode
                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (idx in component) {
                    val p = pixels[idx]
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                }
                val sz = component.size
                median = (0xFF shl 24) or ((sr / sz).toInt() shl 16) or ((sg / sz).toInt() shl 8) or (sb / sz).toInt()
            }

            val maxDist = component.maxOfOrNull { distances[it] } ?: 0
            val effectiveInset = when {
                maxDist >= 16 -> 4
                maxDist >= 8 -> 2
                else -> 0
            }

            val fillComponent = ArrayList<Int>()
            for (idx in component) {
                if (distances[idx] >= effectiveInset) {
                    pixels[idx] = median
                    fillComponent.add(idx)
                }
            }

            if (fillComponent.isNotEmpty() && smoothPasses > 0) {
                smoothMaskedComponent(pixels, distances, fillComponent, width, height, smoothPasses, effectiveInset)
            }
        }
    }

    private fun smoothMaskedComponent(
        pixels: IntArray,
        distances: IntArray,
        component: List<Int>,
        width: Int,
        height: Int,
        passes: Int,
        insetPx: Int,
    ) {
        val scratch = IntArray(component.size)
        for (pass in 0 until passes) {
            for (i in component.indices) {
                val idx = component[i]
                val x = idx % width
                val y = idx / width
                val up = if (y > 0) idx - width else idx
                val down = if (y < height - 1) idx + width else idx
                val left = if (x > 0) idx - 1 else idx
                val right = if (x < width - 1) idx + 1 else idx
                scratch[i] = InpaintPixelOps.avg4(pixels[up], pixels[down], pixels[left], pixels[right])
            }
            for (i in component.indices) {
                val idx = component[i]
                if (distances[idx] >= insetPx) {
                    pixels[idx] = scratch[i]
                }
            }
        }
    }

    /**
     * Median color of the UNMASKED pixels inside [component]'s own bounding
     * box expanded by [RING_PX]. All masked pixels on the page are excluded
     * from the sample, so neighboring components never pollute each other's
     * paper estimate and disconnected glyphs keep independent colors.
     */
    private fun componentRingPaper(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        component: List<Int>,
    ): Int {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = 0
        var maxY = 0
        for (idx in component) {
            val x = idx % width
            val y = idx / width
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val x1 = (minX - RING_PX).coerceAtLeast(0)
        val y1 = (minY - RING_PX).coerceAtLeast(0)
        val x2 = (maxX + 1 + RING_PX).coerceAtMost(width)
        val y2 = (maxY + 1 + RING_PX).coerceAtMost(height)
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var count = 0
        for (y in y1 until y2) {
            val row = y * width
            for (x in x1 until x2) {
                val idx = row + x
                if (mask[idx] != 0.toByte()) continue
                val p = pixels[idx]
                histR[(p shr 16) and 0xFF]++
                histG[(p shr 8) and 0xFF]++
                histB[p and 0xFF]++
                count++
            }
        }
        if (count == 0) return 0xFFFFFFFF.toInt()
        val r = InpaintPixelOps.histogramMedian(histR, count)
        val g = InpaintPixelOps.histogramMedian(histG, count)
        val b = InpaintPixelOps.histogramMedian(histB, count)
        return snapNearGray((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
    }

    /**
     * True when the interior sample is dominated by text ink: a near-gray
     * median darker than the ink-snap threshold means the mask covered the
     * strokes themselves, so the fill color must come from the outer paper
     * ring instead.
     */
    private fun isInkDominated(r: Int, g: Int, b: Int): Boolean {
        val cmax = maxOf(r, g, b)
        val cmin = minOf(r, g, b)
        return cmax - cmin < 30 && (cmax + cmin) / 2 < 60
    }

    /** Near-gray colors snap to pure bubble paper/ink; colored values pass through. */
    private fun snapNearGray(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val cmax = maxOf(r, g, b)
        val cmin = minOf(r, g, b)
        var fR = r
        var fG = g
        var fB = b
        if (cmax - cmin < 30) {
            val luma = (cmax + cmin) / 2
            if (luma > 220) {
                fR = 255
                fG = 255
                fB = 255
            } else if (luma < 60) {
                fR = 0
                fG = 0
                fB = 0
            }
        }
        return (0xFF shl 24) or (fR shl 16) or (fG shl 8) or fB
    }
}
