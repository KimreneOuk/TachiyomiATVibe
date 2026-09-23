package eu.kanade.translation.inpainting.opencv

import android.graphics.Bitmap
import eu.kanade.translation.inpainting.aot.PushPullGradient
import logcat.LogPriority
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.photo.Photo
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool

/**
 * Native OpenCV C++ inpainting engine for TachiyomiAT.
 *
 * Replaces pure-Kotlin min-heap FMM and multi-pass CPU diffusion loops with
 * OpenCV's hardware-accelerated SIMD implementation ([Photo.inpaint]).
 *
 * Lifecycle and memory contract:
 *  - Native allocations ([Mat]) are strictly released in `finally` blocks.
 *  - JVM host unit tests without native ELF binaries safely fall back to
 *    [PushPullGradient.pushPullFill] when [isAvailable] is false.
 *  - Unmasked pixels are never mutated, guaranteeing zero numerical drift on background artwork.
 */
object OpenCvInpaintEngine {

    const val INPAINT_TELEA = Photo.INPAINT_TELEA
    const val INPAINT_NS = Photo.INPAINT_NS

    val isAvailable: Boolean by lazy {
        try {
            val loaded = OpenCVLoader.initLocal()
            if (loaded) {
                logcat(LogPriority.INFO) { "[OpenCvInpaintEngine] OpenCV native library loaded successfully" }
            } else {
                logcat(LogPriority.WARN) { "[OpenCvInpaintEngine] OpenCVLoader.initLocal() returned false" }
            }
            loaded
        } catch (e: UnsatisfiedLinkError) {
            logcat(LogPriority.DEBUG) { "[OpenCvInpaintEngine] OpenCV native library unavailable on host JVM: ${e.message}" }
            false
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "[OpenCvInpaintEngine] Error initializing OpenCV" }
            false
        }
    }

    /**
     * Replaces the legacy `FastMarchingMethod.inpaintTelea` API with native OpenCV Telea inpainting.
     *
     * @param pixels   Packed ARGB crop or image buffer, mutated in place.
     * @param mask     1 (or non-zero) for hole to inpaint, 0 for clean background.
     * @param width    Width of the pixel grid.
     * @param height   Height of the pixel grid.
     * @param radius   Inpainting neighbourhood radius (defaults to 3px).
     */
    fun inpaintTelea(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        radius: Int = 3,
    ) {
        inpaintPixels(
            pixels = pixels,
            mask = mask,
            width = width,
            height = height,
            radius = radius.toDouble(),
            method = INPAINT_TELEA,
        )
    }

    /**
     * Inpaints a masked region in-place within [pixels] using OpenCV native C++ [Photo.inpaint].
     *
     * Converts ARGB to 3-channel CV_8UC3, executes SIMD inpainting, and copies inpainted RGB back
     * only into masked pixels, preserving original alpha and unmasked colors.
     */
    fun inpaintPixels(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        radius: Double = 3.0,
        method: Int = INPAINT_TELEA,
    ) {
        val total = width * height
        if (width <= 0 || height <= 0 || pixels.size < total || mask.size < total) return
        if (mask.none { it != 0.toByte() }) return

        if (isAvailable) {
            inpaintNative(pixels, mask, width, height, radius, method)
        } else {
            // Pure-JVM fallback for host unit tests
            val bg = PushPullGradient.localRingMedian(pixels, width, height, mask, PushPullGradient.DEFAULT_RING)
            PushPullGradient.pushPullFill(pixels, width, height, mask, bg)
        }
    }

    private fun inpaintNative(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        radius: Double,
        method: Int,
    ) {
        val total = width * height
        var srcMat: Mat? = null
        var maskMat: Mat? = null
        var dstMat: Mat? = null

        try {
            srcMat = Mat(height, width, CvType.CV_8UC3)
            maskMat = Mat(height, width, CvType.CV_8UC1)
            dstMat = Mat(height, width, CvType.CV_8UC3)

            val rgbBytes = ByteArray(total * 3)
            for (i in 0 until total) {
                val p = pixels[i]
                val offset = i * 3
                rgbBytes[offset] = ((p shr 16) and 0xFF).toByte()
                rgbBytes[offset + 1] = ((p shr 8) and 0xFF).toByte()
                rgbBytes[offset + 2] = (p and 0xFF).toByte()
            }
            srcMat.put(0, 0, rgbBytes)

            val maskBytes = ByteArray(total)
            for (i in 0 until total) {
                maskBytes[i] = if (mask[i] != 0.toByte()) 255.toByte() else 0.toByte()
            }
            maskMat.put(0, 0, maskBytes)

            Photo.inpaint(srcMat, maskMat, dstMat, radius, method)

            dstMat.get(0, 0, rgbBytes)
            for (i in 0 until total) {
                if (mask[i] != 0.toByte()) {
                    val offset = i * 3
                    val r = rgbBytes[offset].toInt() and 0xFF
                    val g = rgbBytes[offset + 1].toInt() and 0xFF
                    val b = rgbBytes[offset + 2].toInt() and 0xFF
                    val a = (pixels[i] ushr 24) and 0xFF
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        } finally {
            srcMat?.release()
            maskMat?.release()
            dstMat?.release()
        }
    }

    /**
     * Bitmap-level convenience wrapper that allocates an output bitmap from [BitmapPool].
     */
    fun inpaintBitmap(
        source: Bitmap,
        mask: ByteArray,
        radius: Double = 3.0,
        method: Int = INPAINT_TELEA,
    ): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        inpaintPixels(pixels, mask, w, h, radius, method)
        val out = BitmapPool.getARGB8888(w, h)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
