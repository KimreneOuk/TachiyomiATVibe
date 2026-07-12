package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import java.util.PriorityQueue
import kotlin.math.*

/**
 * Pure Kotlin implementation of the Fast Marching Method (Telea 2004) and
 * Adaptive Thresholding, bypassing the OpenCV Android SDK dependency.
 */
object FastMarchingMethod {

    /**
     * Adaptive Gaussian Thresholding
     * Mimics cv::adaptiveThreshold(src, dst, 255, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY_INV, 15, 10)
     */
    fun adaptiveThresholdGaussian(
        pixels: IntArray, width: Int, height: Int,
        blockSize: Int = 15, C: Int = 10,
        startX: Int = 0, startY: Int = 0, endX: Int = width, endY: Int = height
    ): ByteArray {
        val w = endX - startX
        val h = endY - startY
        if (w <= 0 || h <= 0) return ByteArray(0)

        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = pixels[(startY + y) * width + (startX + x)]
                val g = ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000f
                gray[y * w + x] = g
            }
        }

        val sigma = 0.3 * ((blockSize - 1) * 0.5 - 1.0) + 0.8
        val kernel = FloatArray(blockSize)
        val half = blockSize / 2
        var sum = 0f
        for (i in 0 until blockSize) {
            val d = i - half
            val v = exp(-(d * d) / (2 * sigma * sigma)).toFloat()
            kernel[i] = v
            sum += v
        }
        for (i in 0 until blockSize) kernel[i] /= sum

        // Separable Gaussian blur (horizontal then vertical).
        val blurredH = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = 0f
                for (i in 0 until blockSize) {
                    val nx = (x + i - half).coerceIn(0, w - 1)
                    v += gray[y * w + nx] * kernel[i]
                }
                blurredH[y * w + x] = v
            }
        }

        val blurred = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = 0f
                for (i in 0 until blockSize) {
                    val ny = (y + i - half).coerceIn(0, h - 1)
                    v += blurredH[ny * w + x] * kernel[i]
                }
                blurred[y * w + x] = v
            }
        }

        // Threshold (THRESH_BINARY_INV).
        val mask = ByteArray(w * h)
        for (i in 0 until w * h) {
            val thresholdValue = blurred[i] - C
            mask[i] = if (gray[i] < thresholdValue) 1.toByte() else 0.toByte()
        }

        return mask
    }

    /**
     * Dilate with circular/elliptical kernel.
     */
    fun dilate(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        if (radius <= 0) return mask.clone()
        val out = ByteArray(width * height)
        val r2 = radius * radius

        val offsets = mutableListOf<Int>()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= r2) {
                    offsets.add(dy * width + dx)
                }
            }
        }

        for (y in 0 until height) {
            val yBoundLow = -y
            val yBoundHigh = height - 1 - y
            for (x in 0 until width) {
                if (mask[y * width + x] != 0.toByte()) {
                    val xBoundLow = -x
                    val xBoundHigh = width - 1 - x
                    for (dy in -radius..radius) {
                        if (dy < yBoundLow || dy > yBoundHigh) continue
                        val rowOffset = dy * width
                        for (dx in -radius..radius) {
                            if (dx < xBoundLow || dx > xBoundHigh) continue
                            if (dx * dx + dy * dy <= r2) {
                                out[(y + dy) * width + (x + dx)] = 1
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * Erode with circular/elliptical kernel.
     */
    fun erode(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        if (radius <= 0) return mask.clone()
        val out = ByteArray(width * height)
        val r2 = radius * radius
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                var keep = true
                for (dy in -radius..radius) {
                    if (!keep) break
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy <= r2) {
                            val ny = y + dy
                            val nx = x + dx
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height || mask[ny * width + nx] == 0.toByte()) {
                                keep = false
                                break
                            }
                        }
                    }
                }
                if (keep) out[y * width + x] = 1
            }
        }
        return out
    }

    /**
     * Morphological Close (Dilate then Erode)
     */
    fun morphologyClose(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val dilated = dilate(mask, width, height, radius)
        return erode(dilated, width, height, radius)
    }

    private const val KNOWN: Byte = 0
    private const val BAND: Byte = 1
    private const val INSIDE: Byte = 2

    private class PixelNode(val x: Int, val y: Int, var dist: Float) : Comparable<PixelNode> {
        override fun compareTo(other: PixelNode): Int = dist.compareTo(other.dist)
    }

    /**
     * Inpaint using Fast Marching Method (Telea)
     */
    fun inpaintTelea(
        pixels: IntArray, mask: ByteArray, width: Int, height: Int, radius: Int = 3
    ) {
        val flag = ByteArray(width * height)
        val dist = FloatArray(width * height) { 1e6f }
        val pq = PriorityQueue<PixelNode>()

        for (i in 0 until width * height) {
            if (mask[i] != 0.toByte()) {
                flag[i] = INSIDE
            } else {
                flag[i] = KNOWN
                dist[i] = 0f
            }
        }

        val dx = intArrayOf(-1, 1, 0, 0)
        val dy = intArrayOf(0, 0, -1, 1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (flag[idx] == INSIDE) {
                    var isBand = false
                    for (i in 0..3) {
                        val nx = x + dx[i]
                        val ny = y + dy[i]
                        if (nx in 0 until width && ny in 0 until height) {
                            if (flag[ny * width + nx] == KNOWN) {
                                isBand = true
                                break
                            }
                        }
                    }
                    if (isBand) {
                        flag[idx] = BAND
                        dist[idx] = 1f
                        pq.add(PixelNode(x, y, 1f))
                    }
                }
            }
        }

        val r = FloatArray(width * height)
        val g = FloatArray(width * height)
        val b = FloatArray(width * height)
        for (i in 0 until width * height) {
            val p = pixels[i]
            r[i] = (p shr 16 and 0xFF).toFloat()
            g[i] = (p shr 8 and 0xFF).toFloat()
            b[i] = (p and 0xFF).toFloat()
        }

        while (pq.isNotEmpty()) {
            val node = pq.poll()
            val x = node.x
            val y = node.y
            val idx = y * width + x

            if (flag[idx] == KNOWN) continue

            flag[idx] = KNOWN

            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var sumW = 0f

            val r2 = radius * radius
            for (dyk in -radius..radius) {
                for (dxk in -radius..radius) {
                    if (dxk * dxk + dyk * dyk <= r2) {
                        val nx = x + dxk
                        val ny = y + dyk
                        if (nx in 0 until width && ny in 0 until height) {
                            val nidx = ny * width + nx
                            if (flag[nidx] == KNOWN) {
                                // Inverse-square distance weight.
                                val distSq = (dxk * dxk + dyk * dyk).toFloat()
                                if (distSq > 0) {
                                    val w = 1f / distSq
                                    sumR += r[nidx] * w
                                    sumG += g[nidx] * w
                                    sumB += b[nidx] * w
                                    sumW += w
                                }
                            }
                        }
                    }
                }
            }

            if (sumW > 0) {
                r[idx] = sumR / sumW
                g[idx] = sumG / sumW
                b[idx] = sumB / sumW
            }

            for (i in 0..3) {
                val nx = x + dx[i]
                val ny = y + dy[i]
                if (nx in 0 until width && ny in 0 until height) {
                    val nidx = ny * width + nx
                    if (flag[nidx] != KNOWN) {
                        // Eikonal solver approximation
                        var dist1 = 1e6f
                        var dist2 = 1e6f

                        if (nx - 1 >= 0 && flag[ny * width + nx - 1] == KNOWN) dist1 = min(dist1, dist[ny * width + nx - 1])
                        if (nx + 1 < width && flag[ny * width + nx + 1] == KNOWN) dist1 = min(dist1, dist[ny * width + nx + 1])
                        
                        if (ny - 1 >= 0 && flag[(ny - 1) * width + nx] == KNOWN) dist2 = min(dist2, dist[(ny - 1) * width + nx])
                        if (ny + 1 < height && flag[(ny + 1) * width + nx] == KNOWN) dist2 = min(dist2, dist[(ny + 1) * width + nx])

                        val d: Float
                        if (dist1 < 1e5f && dist2 < 1e5f) {
                            val diff = abs(dist1 - dist2)
                            if (diff < 1f) {
                                d = (dist1 + dist2 + sqrt(2f - diff * diff)) / 2f
                            } else {
                                d = min(dist1, dist2) + 1f
                            }
                        } else {
                            d = min(dist1, dist2) + 1f
                        }

                        if (d < dist[nidx]) {
                            dist[nidx] = d
                            flag[nidx] = BAND
                            pq.add(PixelNode(nx, ny, d))
                        }
                    }
                }
            }
        }

        for (i in 0 until width * height) {
            if (mask[i] != 0.toByte()) {
                val pR = r[i].toInt().coerceIn(0, 255)
                val pG = g[i].toInt().coerceIn(0, 255)
                val pB = b[i].toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (pR shl 16) or (pG shl 8) or pB
            }
        }
    }
}
