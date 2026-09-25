package eu.kanade.translation.segmentation

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pure YOLO11-seg decoder for the bundled manga109 bubble model.
 *
 * The verified graph contract is `output0=[1,37,8400]` and
 * `output1=[1,32,160,160]`: xywh, one balloon confidence, then 32 mask
 * coefficients. Masks are reconstructed as sigmoid(coefficients × prototypes),
 * thresholded in letterboxed input coordinates, then projected back to page space.
 */
object BubbleSegmentationDecoder {
    const val INPUT_SIZE = 640
    private const val PROTOTYPE_CHANNELS = 32
    private const val CONFIDENCE_INDEX = 4
    private const val COEFFICIENT_START = 5

    data class Letterbox(val ratio: Float, val padX: Int, val padY: Int)
    data class Mask(val pixels: ByteArray, val width: Int, val height: Int, val bounds: IntArray, val score: Float)
    private data class Candidate(val cx: Float, val cy: Float, val width: Float, val height: Float, val score: Float, val coefficients: FloatArray)

    fun letterboxFor(width: Int, height: Int): Letterbox {
        require(width > 0 && height > 0)
        val ratio = min(INPUT_SIZE.toFloat() / width, INPUT_SIZE.toFloat() / height)
        return Letterbox(ratio, (INPUT_SIZE - (width * ratio).toInt()) / 2, (INPUT_SIZE - (height * ratio).toInt()) / 2)
    }

    /** Decodes flattened channel-first tensors, keeping only class-0 balloon instances. */
    fun decode(
        predictions: FloatArray,
        predictionChannels: Int,
        predictionCount: Int,
        prototypes: FloatArray,
        prototypeWidth: Int,
        prototypeHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        confidenceThreshold: Float = 0.35f,
        nmsIouThreshold: Float = 0.5f,
        maskThreshold: Float = 0.5f,
    ): List<Mask> {
        require(predictionChannels >= COEFFICIENT_START + PROTOTYPE_CHANNELS) {
            "Bubble segmentation output0 must have at least 37 channels, got $predictionChannels"
        }
        require(prototypes.size >= PROTOTYPE_CHANNELS * prototypeWidth * prototypeHeight) {
            "Bubble segmentation output1 too small for 32x${prototypeHeight}x$prototypeWidth prototypes"
        }
        val letterbox = letterboxFor(pageWidth, pageHeight)
        return selectCandidates(predictions, predictionChannels, predictionCount, confidenceThreshold, nmsIouThreshold)
            .mapNotNull { candidate ->
                reconstructMask(candidate, prototypes, prototypeWidth, prototypeHeight, pageWidth, pageHeight, letterbox, maskThreshold)
            }
    }

    /**
     * Same selection and mask math as [decode], emitting [BubbleMaskRle] runs directly
     * from the row-major scan so no page-sized dense mask is ever materialized.
     */
    fun decodeRle(
        predictions: FloatArray,
        predictionChannels: Int,
        predictionCount: Int,
        prototypes: FloatArray,
        prototypeWidth: Int,
        prototypeHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        confidenceThreshold: Float = 0.35f,
        nmsIouThreshold: Float = 0.5f,
        maskThreshold: Float = 0.5f,
    ): List<BubbleMaskRle> {
        require(predictionChannels >= COEFFICIENT_START + PROTOTYPE_CHANNELS) {
            "Bubble segmentation output0 must have at least 37 channels, got $predictionChannels"
        }
        require(prototypes.size >= PROTOTYPE_CHANNELS * prototypeWidth * prototypeHeight) {
            "Bubble segmentation output1 too small for 32x${prototypeHeight}x$prototypeWidth prototypes"
        }
        val letterbox = letterboxFor(pageWidth, pageHeight)
        return selectCandidates(predictions, predictionChannels, predictionCount, confidenceThreshold, nmsIouThreshold)
            .mapNotNull { candidate ->
                reconstructRle(candidate, prototypes, prototypeWidth, prototypeHeight, pageWidth, pageHeight, letterbox, maskThreshold)
            }
    }

    private fun selectCandidates(
        predictions: FloatArray,
        predictionChannels: Int,
        predictionCount: Int,
        confidenceThreshold: Float,
        nmsIouThreshold: Float,
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        for (i in 0 until predictionCount) {
            val score = predictions[CONFIDENCE_INDEX * predictionCount + i]
            if (!score.isFinite() || score < confidenceThreshold) continue
            val w = predictions[2 * predictionCount + i]
            val h = predictions[3 * predictionCount + i]
            if (!w.isFinite() || !h.isFinite() || w <= 1f || h <= 1f) continue
            val coefficients = FloatArray(PROTOTYPE_CHANNELS) { channel ->
                predictions[(COEFFICIENT_START + channel) * predictionCount + i]
            }
            candidates += Candidate(predictions[i], predictions[predictionCount + i], w, h, score, coefficients)
        }
        val kept = ArrayList<Candidate>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (kept.none { iou(candidate, it) > nmsIouThreshold }) kept += candidate
        }
        return kept
    }

    private fun reconstructMask(
        candidate: Candidate,
        prototypes: FloatArray,
        prototypeWidth: Int,
        prototypeHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        letterbox: Letterbox,
        threshold: Float,
    ): Mask? {
        val pixels = ByteArray(pageWidth * pageHeight)
        val x1 = ((candidate.cx - candidate.width / 2f - letterbox.padX) / letterbox.ratio).toInt().coerceIn(0, pageWidth)
        val y1 = ((candidate.cy - candidate.height / 2f - letterbox.padY) / letterbox.ratio).toInt().coerceIn(0, pageHeight)
        val x2 = ((candidate.cx + candidate.width / 2f - letterbox.padX) / letterbox.ratio).toInt().coerceIn(0, pageWidth)
        val y2 = ((candidate.cy + candidate.height / 2f - letterbox.padY) / letterbox.ratio).toInt().coerceIn(0, pageHeight)
        if (x2 <= x1 || y2 <= y1) return null
        var minX = pageWidth
        var minY = pageHeight
        var maxX = -1
        var maxY = -1
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                if (isMasked(candidate, x, y, prototypes, prototypeWidth, prototypeHeight, letterbox, threshold)) {
                    pixels[y * pageWidth + x] = 1
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }
        return if (maxX >= minX && maxY >= minY) Mask(pixels, pageWidth, pageHeight, intArrayOf(minX, minY, maxX + 1, maxY + 1), candidate.score) else null
    }

    private fun reconstructRle(
        candidate: Candidate,
        prototypes: FloatArray,
        prototypeWidth: Int,
        prototypeHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        letterbox: Letterbox,
        threshold: Float,
    ): BubbleMaskRle? {
        val x1 = ((candidate.cx - candidate.width / 2f - letterbox.padX) / letterbox.ratio).toInt().coerceIn(0, pageWidth)
        val y1 = ((candidate.cy - candidate.height / 2f - letterbox.padY) / letterbox.ratio).toInt().coerceIn(0, pageHeight)
        val x2 = ((candidate.cx + candidate.width / 2f - letterbox.padX) / letterbox.ratio).toInt().coerceIn(0, pageWidth)
        val y2 = ((candidate.cy + candidate.height / 2f - letterbox.padY) / letterbox.ratio).toInt().coerceIn(0, pageHeight)
        if (x2 <= x1 || y2 <= y1) return null
        var minX = pageWidth
        var minY = pageHeight
        var maxX = -1
        var maxY = -1
        val runs = ArrayList<Int>()
        var runStart = -1
        // A dense-encoded run can span a row boundary only when the box covers
        // the full page width (the last column of row y and column 0 of row y+1
        // are adjacent indices); otherwise every run closes at its row's end.
        val maySpanRows = x1 == 0 && x2 == pageWidth
        for (y in y1 until y2) {
            val rowBase = y * pageWidth
            for (x in x1 until x2) {
                if (isMasked(candidate, x, y, prototypes, prototypeWidth, prototypeHeight, letterbox, threshold)) {
                    if (runStart < 0) runStart = rowBase + x
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                } else if (runStart >= 0) {
                    runs += runStart
                    runs += rowBase + x - runStart
                    runStart = -1
                }
            }
            if (runStart >= 0 && !maySpanRows) {
                runs += runStart
                runs += rowBase + x2 - runStart
                runStart = -1
            }
        }
        if (runStart >= 0) {
            runs += runStart
            runs += y2 * pageWidth - runStart
        }
        if (maxX < minX || maxY < minY) return null
        return BubbleMaskRle(pageWidth, pageHeight, listOf(minX, minY, maxX + 1, maxY + 1), runs, candidate.score)
    }

    private fun isMasked(
        candidate: Candidate,
        x: Int,
        y: Int,
        prototypes: FloatArray,
        prototypeWidth: Int,
        prototypeHeight: Int,
        letterbox: Letterbox,
        threshold: Float,
    ): Boolean {
        val inputX = x * letterbox.ratio + letterbox.padX
        val inputY = y * letterbox.ratio + letterbox.padY
        val protoX = (inputX / INPUT_SIZE * prototypeWidth).toInt().coerceIn(0, prototypeWidth - 1)
        val protoY = (inputY / INPUT_SIZE * prototypeHeight).toInt().coerceIn(0, prototypeHeight - 1)
        val protoIndex = protoY * prototypeWidth + protoX
        var sum = 0f
        for (channel in 0 until PROTOTYPE_CHANNELS) sum += candidate.coefficients[channel] * prototypes[channel * prototypeWidth * prototypeHeight + protoIndex]
        return sigmoid(sum) >= threshold
    }

    private fun sigmoid(value: Float): Float = (1f / (1f + exp(-value))).toFloat()
    private fun iou(a: Candidate, b: Candidate): Float {
        val ax1 = a.cx - a.width / 2f
        val ay1 = a.cy - a.height / 2f
        val ax2 = a.cx + a.width / 2f
        val ay2 = a.cy + a.height / 2f
        val bx1 = b.cx - b.width / 2f
        val by1 = b.cy - b.height / 2f
        val bx2 = b.cx + b.width / 2f
        val by2 = b.cy + b.height / 2f
        val iw = min(ax2, bx2) - max(ax1, bx1)
        val ih = min(ay2, by2) - max(ay1, by1)
        if (iw <= 0f || ih <= 0f) return 0f
        return iw * ih / (a.width * a.height + b.width * b.height - iw * ih)
    }
}
