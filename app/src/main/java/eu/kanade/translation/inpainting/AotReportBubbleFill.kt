package eu.kanade.translation.inpainting

internal object AotReportBubbleFill {

    /**
     * Single-source variant of the report bubble pass: fills [pixels] in place and
     * feathers the result against a copy of the pre-fill values, so callers read
     * the page bitmap once instead of twice.
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
        val alpha = BubbleMaskBuilder.featherAlphaField(mask, width, height, featherRampPx)
        for (i in pixels.indices) {
            val a = alpha[i]
            if (a > 0.0f) pixels[i] = AotPixelOps.blendPixel(original[i], pixels[i], a)
        }
    }

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
        val insetPx = 5 // Do not overwrite pixels within 5px of the mask boundary

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
                val r = AotPixelOps.histogramMedian(histR, count)
                val g = AotPixelOps.histogramMedian(histG, count)
                val b = AotPixelOps.histogramMedian(histB, count)

                // Snap near-gray to pure white/black to match bubble paper/ink.
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
                median = (0xFF shl 24) or (fR shl 16) or (fG shl 8) or fB
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
                scratch[i] = AotPixelOps.avg4(pixels[up], pixels[down], pixels[left], pixels[right])
            }
            for (i in component.indices) {
                val idx = component[i]
                if (distances[idx] >= insetPx) {
                    pixels[idx] = scratch[i]
                }
            }
        }
    }
}
