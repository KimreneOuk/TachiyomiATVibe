package eu.kanade.translation.ocr

import android.graphics.Bitmap
import eu.kanade.translation.ocr.DbPostProcess
import eu.kanade.translation.ocr.OcrTextFilter
import eu.kanade.translation.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.ocr.RoiOcrEngine
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrFallbackKind
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrRotation
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Vertical / multi-line OCR machinery moved verbatim from
 * `RoiPageRecognitionEngine` (T909 Phase 5a). Pure over
 * `(RoiOcrEngine, Bitmap, flags, language)` inputs; the engine's `closed`
 * cancellation checkpoint is injected as [isClosed] getters.
 */
internal object VerticalLineOcr {

    // TachiyomiAT: ink-gap column-split thresholds (tuned against the Python
    // repro: whole-box 1/6 vs per-column 6/6 on multi-column cases).
    // INK_LUMINANCE_THRESHOLD: pixels darker than this count as text ink.
    // COLUMN_GAP_INK_FRACTION: x-column below this ink fraction is a gap candidate.
    // MIN_COLUMN_GAP_PX: only an ink-free run this wide splits columns; narrower
    //   inter-character gaps are merged.
    // MIN_COLUMN_WIDTH_PX: ink-runs narrower than this are discarded as noise.
    private const val INK_LUMINANCE_THRESHOLD = 110
    private const val COLUMN_GAP_INK_FRACTION = 0.02f
    private const val MIN_COLUMN_GAP_PX = 10
    private const val MIN_COLUMN_WIDTH_PX = 12

    // TachiyomiAT: min det-line dimension (see recognizeDetColumns). Distinct
    // from MIN_COLUMN_WIDTH_PX: det already filtered noise, so 4px only rejects
    // fragments while 12px wrongly dropped legitimate small ROI text lines.
    private const val MIN_DET_LINE_PX = 4

    // TachiyomiAT: det-line size guard for [recognizeDetColumns]. Rejects
    // sub-glyph noise (det sometimes emits tiny fragments) and false merges
    // (huge regions spanning multiple bubbles). Sized in crop pixel coords.
    private const val MAX_COLUMN_WIDTH_PX = 400
    private const val MAX_COLUMN_HEIGHT_PX = 800

    // TachiyomiAT: minimum confidence for PaddleOCR CTC output. PaddleOCR
    // reports meaningful confidence values; reads below this threshold are
    // likely garbage and discarded before translation. MangaOcr returns
    // default 1.0 (no real score) and is exempt via the conf < 1f guard.
    private const val OCR_MIN_CONFIDENCE = 0.5f

    internal fun cropBitmap(source: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        val clampedX1 = x1.coerceIn(0, source.width)
        val clampedY1 = y1.coerceIn(0, source.height)
        val clampedX2 = x2.coerceIn(clampedX1, source.width)
        val clampedY2 = y2.coerceIn(clampedY1, source.height)
        if (clampedX2 <= clampedX1 || clampedY2 <= clampedY1) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(source, clampedX1, clampedY1, clampedX2 - clampedX1, clampedY2 - clampedY1)
    }

    /**
     * TachiyomiAT: recognize a (possibly multi-line) bubble by first running the
     * PaddleOCR det model over the whole crop to recover individual text lines,
     * then OCR-ing each line with the correct orientation via [recognizeDetColumns].
     * Handles both stacked horizontal lines (English/Korean paragraphs) and
     * vertical columns (CJK), so it replaces the old vertical-only split.
     *
     * [verticalFallback] selects the empty/failed-det fallback: a tall CJK bubble
     * uses the ink-gap column heuristic; anything else (e.g. an English bubble the
     * det model missed) falls back to a single horizontal rec read instead of
     * being force-rotated.
     */
    internal suspend fun recognizeMultiLine(
        engine: RoiOcrEngine,
        crop: Bitmap,
        paddleDet: PaddleOcrV6DetEngine?,
        verticalFallback: Boolean,
        language: TextRecognizerLanguage,
        isClosed: () -> Boolean,
    ): String {
        if (paddleDet != null) {
            try {
                // Use the standard DB BOX_THRESH (0.45f) for the rec path; 0.34 admitted noise.
                val lines = paddleDet.detectLines(crop, thresh = 0.2f, boxThresh = DbPostProcess.Defaults.BOX_THRESH)
                if (lines.isNotEmpty()) {
                    return recognizeDetColumns(engine, crop, lines, language, isClosed)
                }
                // Det returned nothing -> fall through to the fallback. A true
                // negative on a real text region is preferable to empty output.
                logcat(LogPriority.INFO) {
                    "[paddle_det] returned 0 lines; falling back to " +
                        if (verticalFallback) "ink-gap heuristic" else "single read"
                }
            } catch (e: Exception) {
                // Never let a det-model failure abort OCR — degrade to the fallback
                // for this ROI and log loudly (AGENT.md: never suppress errors).
                logcat(LogPriority.WARN, e) {
                    "[paddle_det] failed; falling back to " +
                        if (verticalFallback) "ink-gap heuristic" else "single read" + " for this ROI"
                }
            }
        }
        return if (verticalFallback) recognizeHeuristicColumns(engine, crop, language, isClosed) else recognizeSingleLine(engine, crop, language)
    }

    internal suspend fun recognizeSingleLine(engine: RoiOcrEngine, crop: Bitmap, language: TextRecognizerLanguage): String {
        val (text, conf) = engine.recognizeWithConf(crop)
        return filterRecognizedText(text, conf, language)
    }

    /**
     * Plans the same final line/column/glyph leaves as [recognizeMultiLine], but
     * stops before recognition so a page coordinator can bucket the leaves.
     * Geometry and ordering deliberately mirror the existing sequential path.
     */
    internal suspend fun planMultiLine(
        crop: Bitmap,
        paddleDet: PaddleOcrV6DetEngine?,
        verticalFallback: Boolean,
        language: TextRecognizerLanguage,
        isClosed: () -> Boolean,
    ): PaddleVerticalRecognitionPlan {
        if (paddleDet != null) {
            val lines = try {
                paddleDet.detectLines(crop, thresh = 0.2f, boxThresh = DbPostProcess.Defaults.BOX_THRESH)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "[paddle_det] failed while planning; falling back to " +
                        if (verticalFallback) "ink-gap heuristic" else "single read"
                }
                emptyList()
            }
            if (lines.isNotEmpty()) {
                return planDetColumns(crop, lines, language, prefersHorizontalText = true, isClosed)
            }
            logcat(LogPriority.INFO) {
                "[paddle_det] returned 0 usable lines while planning; falling back to " +
                    if (verticalFallback) "ink-gap heuristic" else "single read"
            }
        }
        return if (verticalFallback) {
            planHeuristicColumns(crop, language, isClosed)
        } else {
            planWholeRegion(crop)
        }
    }

    /** Reconstructs one region's text from the ordered batch rows. */
    internal fun composePlan(
        plan: PaddleVerticalRecognitionPlan,
        results: List<Pair<String, Float>>,
        language: TextRecognizerLanguage,
    ): String {
        require(results.size == plan.leaves.size) {
            "Paddle page plan expected ${plan.leaves.size} results, got ${results.size}"
        }
        return plan.groups.map { group ->
            group.mapNotNull { index ->
                val (text, confidence) = results[index]
                filterRecognizedText(text, confidence, language, plan.filterConfidence).takeIf { it.isNotEmpty() }
            }.joinToString(plan.separator)
        }.filter { it.isNotEmpty() }.joinToString(plan.separator)
    }

    /** Shared confidence/text filtering for sequential and page-batch paths. */
    internal fun filterRecognizedText(
        text: String,
        confidence: Float,
        language: TextRecognizerLanguage,
        applyConfidence: Boolean = true,
    ): String {
        // PaddleOCR's CTC decoder reports a meaningful confidence; MangaOcr returns
        // default 1.0 (no real score). The conf < 1f guard keeps MangaOcr's default
        // from being filtered here.
        if (applyConfidence && confidence < OCR_MIN_CONFIDENCE && confidence < 1f) return ""
        return if (text.isNotEmpty() && OcrTextFilter.isUsable(text, language)) text else ""
    }

    private fun planWholeRegion(crop: Bitmap): PaddleVerticalRecognitionPlan {
        val leaf = PaddleVerticalLeafPlan(
            crop = cropBitmap(crop, 0, 0, crop.width, crop.height),
            lineIndex = null,
            glyphIndex = null,
            rotation = PaddleOcrRotation.NONE,
            fallbackKind = PaddleOcrFallbackKind.WHOLE_REGION,
        )
        return PaddleVerticalRecognitionPlan(
            leaves = listOf(leaf),
            groups = listOf(listOf(0)),
            separator = "",
            // This is the det-missing, non-tall degraded branch. The legacy
            // inline RoiPageRecognitionEngine path applied only isUsable() and
            // deliberately did not apply Paddle's 0.5 confidence threshold.
            // Keep that behavior exact while the page coordinator batches it.
            filterConfidence = false,
        )
    }

    private suspend fun planDetColumns(
        crop: Bitmap,
        lines: List<eu.kanade.translation.ocr.TextLine>,
        language: TextRecognizerLanguage,
        prefersHorizontalText: Boolean,
        isClosed: () -> Boolean,
    ): PaddleVerticalRecognitionPlan {
        data class Item(val bbox: IntArray, val vertical: Boolean, val sortKey: Int)
        val items = lines.mapNotNull { line ->
            val bbox = line.bbox
            if (bbox.size < 4 || bbox[2] <= bbox[0] || bbox[3] <= bbox[1]) return@mapNotNull null
            val width = bbox[2] - bbox[0]
            val height = bbox[3] - bbox[1]
            if (width < MIN_DET_LINE_PX || height < MIN_DET_LINE_PX) return@mapNotNull null
            if (width > MAX_COLUMN_WIDTH_PX || height > MAX_COLUMN_HEIGHT_PX) return@mapNotNull null
            val vertical = height > width * 1.5f
            val sortKey = if (vertical) -(bbox[0] + bbox[2]) / 2 else (bbox[1] + bbox[3]) / 2
            Item(bbox, vertical, sortKey)
        }.sortedBy { it.sortKey }

        if (items.isEmpty()) {
            val rotated = rotateCcw(crop)
            return PaddleVerticalRecognitionPlan(
                leaves = listOf(
                    PaddleVerticalLeafPlan(
                        crop = rotated,
                        lineIndex = null,
                        glyphIndex = null,
                        rotation = PaddleOcrRotation.CCW_90,
                        fallbackKind = PaddleOcrFallbackKind.WHOLE_REGION,
                    ),
                ),
                groups = listOf(listOf(0)),
                separator = language.joinSeparator(),
                filterConfidence = false,
            )
        }

        val verticalCjk = language == TextRecognizerLanguage.JAPANESE ||
            language == TextRecognizerLanguage.CHINESE ||
            language == TextRecognizerLanguage.KOREAN
        val leaves = ArrayList<PaddleVerticalLeafPlan>()
        val groups = ArrayList<List<Int>>(items.size)
        try {
            items.forEachIndexed { lineIndex, item ->
                check(!isClosed()) { "ONNX recognition engine closed during OCR geometry planning" }
                val lineGroup = ArrayList<Int>()
                val columnCrop = cropBitmap(crop, item.bbox[0], item.bbox[1], item.bbox[2], item.bbox[3])
                try {
                    when {
                        item.vertical && verticalCjk && prefersHorizontalText -> {
                            addVerticalGlyphLeaves(
                                columnCrop = columnCrop,
                                lineIndex = lineIndex,
                                fallbackKind = PaddleOcrFallbackKind.GLYPH,
                                leaves = leaves,
                                group = lineGroup,
                                isClosed = isClosed,
                            )
                        }

                        item.vertical && prefersHorizontalText -> {
                            val rotated = rotateCcw(columnCrop)
                            leaves += PaddleVerticalLeafPlan(
                                crop = rotated,
                                lineIndex = lineIndex,
                                glyphIndex = null,
                                rotation = PaddleOcrRotation.CCW_90,
                                fallbackKind = PaddleOcrFallbackKind.DETECTOR_LINE,
                            )
                            lineGroup += leaves.lastIndex
                        }

                        else -> {
                            val horizontal = cropBitmap(columnCrop, 0, 0, columnCrop.width, columnCrop.height)
                            leaves += PaddleVerticalLeafPlan(
                                crop = horizontal,
                                lineIndex = lineIndex,
                                glyphIndex = null,
                                rotation = PaddleOcrRotation.NONE,
                                fallbackKind = PaddleOcrFallbackKind.DETECTOR_LINE,
                            )
                            lineGroup += leaves.lastIndex
                        }
                    }
                } finally {
                    columnCrop.recycle()
                }
                groups += lineGroup
            }
            return PaddleVerticalRecognitionPlan(leaves, groups, language.joinSeparator())
        } catch (failure: Throwable) {
            leaves.forEach { it.crop.recycle() }
            throw failure
        }
    }

    private suspend fun planHeuristicColumns(
        crop: Bitmap,
        language: TextRecognizerLanguage,
        isClosed: () -> Boolean,
    ): PaddleVerticalRecognitionPlan {
        val columns = detectVerticalColumns(crop)
        val verticalCjk = language == TextRecognizerLanguage.JAPANESE ||
            language == TextRecognizerLanguage.CHINESE ||
            language == TextRecognizerLanguage.KOREAN
        if (columns.size <= 1) {
            val rotated = rotateCcw(crop)
            return PaddleVerticalRecognitionPlan(
                leaves = listOf(
                    PaddleVerticalLeafPlan(
                        crop = rotated,
                        lineIndex = 0,
                        glyphIndex = null,
                        rotation = PaddleOcrRotation.CCW_90,
                        fallbackKind = PaddleOcrFallbackKind.HEURISTIC_LINE,
                    ),
                ),
                groups = listOf(listOf(0)),
                separator = language.joinSeparator(),
                filterConfidence = false,
            )
        }

        val leaves = ArrayList<PaddleVerticalLeafPlan>()
        val groups = ArrayList<List<Int>>(columns.size)
        try {
            for (columnIndex in columns.indices.reversed()) {
                check(!isClosed()) { "ONNX recognition engine closed during OCR geometry planning" }
                val (x0, x1) = columns[columnIndex]
                if (x1 - x0 < MIN_COLUMN_WIDTH_PX) continue
                val lineIndex = groups.size
                val lineGroup = ArrayList<Int>()
                val columnCrop = Bitmap.createBitmap(crop, x0, 0, x1 - x0, crop.height)
                try {
                    if (verticalCjk) {
                        addVerticalGlyphLeaves(
                            columnCrop = columnCrop,
                            lineIndex = lineIndex,
                            fallbackKind = PaddleOcrFallbackKind.HEURISTIC_LINE,
                            leaves = leaves,
                            group = lineGroup,
                            isClosed = isClosed,
                        )
                    } else {
                        val rotated = rotateCcw(columnCrop)
                        leaves += PaddleVerticalLeafPlan(
                            crop = rotated,
                            lineIndex = lineIndex,
                            glyphIndex = null,
                            rotation = PaddleOcrRotation.CCW_90,
                            fallbackKind = PaddleOcrFallbackKind.HEURISTIC_LINE,
                        )
                        lineGroup += leaves.lastIndex
                    }
                } finally {
                    columnCrop.recycle()
                }
                groups += lineGroup
            }
            return PaddleVerticalRecognitionPlan(leaves, groups, language.joinSeparator())
        } catch (failure: Throwable) {
            leaves.forEach { it.crop.recycle() }
            throw failure
        }
    }

    private suspend fun addVerticalGlyphLeaves(
        columnCrop: Bitmap,
        lineIndex: Int,
        fallbackKind: PaddleOcrFallbackKind,
        leaves: MutableList<PaddleVerticalLeafPlan>,
        group: MutableList<Int>,
        isClosed: () -> Boolean,
    ) {
        val rows = detectVerticalGlyphRows(columnCrop)
        if (rows.size <= 1) {
            val rotated = rotateCcw(columnCrop)
            leaves += PaddleVerticalLeafPlan(
                crop = rotated,
                lineIndex = lineIndex,
                glyphIndex = null,
                rotation = PaddleOcrRotation.CCW_90,
                fallbackKind = if (fallbackKind == PaddleOcrFallbackKind.GLYPH) {
                    PaddleOcrFallbackKind.DETECTOR_LINE
                } else {
                    fallbackKind
                },
            )
            group += leaves.lastIndex
            return
        }
        var glyphIndex = 0
        for ((y0, y1) in rows) {
            check(!isClosed()) { "ONNX recognition engine closed during OCR geometry planning" }
            if (y1 - y0 < MIN_COLUMN_WIDTH_PX) continue
            val glyphCrop = Bitmap.createBitmap(columnCrop, 0, y0, columnCrop.width, y1 - y0)
            try {
                val rotated = rotateCcw(glyphCrop)
                leaves += PaddleVerticalLeafPlan(
                    crop = rotated,
                    lineIndex = lineIndex,
                    glyphIndex = glyphIndex++,
                    rotation = PaddleOcrRotation.CCW_90,
                    fallbackKind = PaddleOcrFallbackKind.GLYPH,
                )
                group += leaves.lastIndex
            } finally {
                glyphCrop.recycle()
            }
        }
    }

    /**
     * Recognize using PP-OCRv6 det-detected text lines. Each [TextLine.bbox] is in
     * crop pixel coords; sort in manga reading order (vertical: right-to-left by
     * x-center; horizontal: top-to-bottom), crop, rotate CCW if vertical, OCR.
     */
    internal suspend fun recognizeDetColumns(
        engine: RoiOcrEngine,
        crop: Bitmap,
        lines: List<eu.kanade.translation.ocr.TextLine>,
        language: TextRecognizerLanguage,
        isClosed: () -> Boolean,
    ): String {
        // Classify + order. Vertical columns sort right-to-left (manga); horizontal
        // lines sort top-to-bottom. Vertical first, then horizontal, mirroring the
        // validated Python prototype (det_rec_any.py).
        data class Item(val bbox: IntArray, val vertical: Boolean, val sortKey: Int)
        val items = lines.mapNotNull { l ->
            val b = l.bbox
            if (b.size < 4 || b[2] <= b[0] || b[3] <= b[1]) return@mapNotNull null
            val w = b[2] - b[0]
            val h = b[3] - b[1]
            // TachiyomiAT: det boxes are real text lines (not raw pixel runs),
            // so the floor is far smaller than the ink-gap heuristic's 12px.
            // Back-projected det lines in small ROIs are legitimately 6-10px;
            // 12px dropped them all and fell through to a whole-crop read that
            // squashed a tall bubble into garbage. 4px only rejects fragments.
            if (w < MIN_DET_LINE_PX || h < MIN_DET_LINE_PX) return@mapNotNull null
            if (w > MAX_COLUMN_WIDTH_PX || h > MAX_COLUMN_HEIGHT_PX) return@mapNotNull null
            val vertical = h > w * 1.5f
            // Vertical: sort by x-center DESC (right-to-left). Horizontal: by y ASC.
            val key = if (vertical) -(b[0] + b[2]) / 2 else (b[1] + b[3]) / 2
            Item(b, vertical, key)
        }.sortedBy { it.sortKey }

        if (items.isEmpty()) {
            val rotated = rotateCcw(crop)
            return try {
                val part = engine.recognizeWithConf(rotated).first
                if (part.isNotEmpty() && OcrTextFilter.isUsable(part, language)) part else ""
            } finally {
                rotated.recycle()
            }
        }

        val verticalCjk = language == TextRecognizerLanguage.JAPANESE ||
            language == TextRecognizerLanguage.CHINESE ||
            language == TextRecognizerLanguage.KOREAN
        val parts = ArrayList<String>(items.size)
        for (it in items) {
            if (isClosed()) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
            val b = it.bbox
            val columnCrop = cropBitmap(crop, b[0], b[1], b[2], b[3])
            try {
                // CJK vertical columns are split into individual glyphs before rec
                // (the rec CTC head misreads a rotated whole column); other vertical
                // boxes are rotated whole. Horizontal boxes are read as-is.
                val part = when {
                    it.vertical && verticalCjk && engine.prefersHorizontalText -> recognizeVerticalColumnPerChar(engine, columnCrop, language, isClosed)
                    it.vertical && engine.prefersHorizontalText -> {
                        val rotated = rotateCcw(columnCrop)
                        try {
                            recognizeSingleLine(engine, rotated, language)
                        } finally {
                            rotated.recycle()
                        }
                    }
                    else -> recognizeSingleLine(engine, columnCrop, language)
                }
                if (part.isNotEmpty()) parts.add(part)
            } finally {
                columnCrop.recycle()
            }
        }
        return parts.joinToString(language.joinSeparator())
    }

    /**
     * Original ink-gap-heuristic column splitter + per-column OCR. Kept as the
     * fallback path when the det model is unavailable or returns no lines.
     */
    internal suspend fun recognizeHeuristicColumns(
        engine: RoiOcrEngine,
        crop: Bitmap,
        language: TextRecognizerLanguage,
        isClosed: () -> Boolean,
    ): String {
        val columns = detectVerticalColumns(crop)
        if (columns.size <= 1) {
            val rotated = rotateCcw(crop)
            return try {
                val part = engine.recognizeWithConf(rotated).first
                if (part.isNotEmpty() && OcrTextFilter.isUsable(part, language)) part else ""
            } finally {
                rotated.recycle()
            }
        }
        val verticalCjk = language == TextRecognizerLanguage.JAPANESE ||
            language == TextRecognizerLanguage.CHINESE ||
            language == TextRecognizerLanguage.KOREAN
        val parts = ArrayList<String>(columns.size)
        // Manga vertical text reads right-to-left. [detectVerticalColumns] returns
        // columns in left-to-right pixel order, so iterate them in reverse.
        for (i in columns.indices.reversed()) {
            val (x0, x1) = columns[i]
            if (x1 - x0 < MIN_COLUMN_WIDTH_PX) continue
            val columnCrop = Bitmap.createBitmap(crop, x0, 0, x1 - x0, crop.height)
            try {
                val part = if (verticalCjk && engine.prefersHorizontalText) {
                    recognizeVerticalColumnPerChar(engine, columnCrop, language, isClosed)
                } else if (engine.prefersHorizontalText) {
                    val rotated = rotateCcw(columnCrop)
                    try {
                        recognizeSingleLine(engine, rotated, language)
                    } finally {
                        rotated.recycle()
                    }
                } else {
                    recognizeSingleLine(engine, columnCrop, language)
                }
                if (part.isNotEmpty()) parts.add(part)
            } finally {
                columnCrop.recycle()
            }
        }
        return parts.joinToString(language.joinSeparator())
    }

    internal fun rotateCcw(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(-90f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * TachiyomiAT: split a single vertical text column into individual glyph
     * cells by row ink-gap analysis (the transpose of [detectVerticalColumns]),
     * then recognize each glyph on its own after a 90° CCW rotation. The rec
     * CTC head is trained on horizontal lines and misreads a rotated whole
     * multi-glyph column; recognizing one glyph at a time is the maintainer-
     * validated workaround for vertical CJK (see docs/ocr-engine-notes.md).
     * Returns glyphs in top-to-bottom reading order joined with the language
     * separator. A single-glyph (or un-splittable) column degrades to one
     * rotated whole-column read, so this never does worse than the whole path.
     */
    internal suspend fun recognizeVerticalColumnPerChar(
        engine: RoiOcrEngine,
        columnCrop: Bitmap,
        language: TextRecognizerLanguage,
        isClosed: () -> Boolean,
    ): String {
        val rows = detectVerticalGlyphRows(columnCrop)
        if (rows.size <= 1) {
            val rotated = rotateCcw(columnCrop)
            return try {
                recognizeSingleLine(engine, rotated, language)
            } finally {
                rotated.recycle()
            }
        }
        val parts = ArrayList<String>(rows.size)
        for ((y0, y1) in rows) {
            if (isClosed()) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
            if (y1 - y0 < MIN_COLUMN_WIDTH_PX) continue
            val glyphCrop = Bitmap.createBitmap(columnCrop, 0, y0, columnCrop.width, y1 - y0)
            try {
                val rotated = rotateCcw(glyphCrop)
                try {
                    val part = recognizeSingleLine(engine, rotated, language)
                    if (part.isNotEmpty()) parts.add(part)
                } finally {
                    rotated.recycle()
                }
            } finally {
                glyphCrop.recycle()
            }
        }
        return parts.joinToString(language.joinSeparator())
    }

    /**
     * TachiyomiAT: detect horizontal glyph bands within a single vertical column
     * by row ink-gap analysis. Returns glyph y-ranges [(y0,y1), ...] in
     * top-to-bottom order. Mirror of [detectVerticalColumns] transposed to the
     * row axis (ink fraction per row, ink-runs merged across narrow gaps). The
     * same thresholds are reused since they are generic ink-band size floors.
     */
    internal fun detectVerticalGlyphRows(crop: Bitmap): List<Pair<Int, Int>> {
        val w = crop.width
        val h = crop.height
        if (w < 2 || h < 2) return listOf(0 to h)
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        val inkFraction = FloatArray(h)
        for (y in 0 until h) {
            var dark = 0
            for (x in 0 until w) {
                val px = pixels[y * w + x]
                val lum = (
                    0.299f * ((px shr 16) and 0xFF) +
                        0.587f * ((px shr 8) and 0xFF) +
                        0.114f * (px and 0xFF)
                    ).toInt()
                if (lum < INK_LUMINANCE_THRESHOLD) dark++
            }
            inkFraction[y] = dark.toFloat() / w.toFloat()
        }
        val rows = ArrayList<Pair<Int, Int>>()
        var inRun = false
        var runStart = 0
        var gapSinceInk = 0
        for (y in 0 until h) {
            val hasInk = inkFraction[y] >= COLUMN_GAP_INK_FRACTION
            if (hasInk) {
                if (!inRun) {
                    runStart = y
                    inRun = true
                }
                gapSinceInk = 0
            } else if (inRun) {
                gapSinceInk++
                if (gapSinceInk >= MIN_COLUMN_GAP_PX) {
                    val end = y - gapSinceInk
                    if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                        rows.add(runStart to end)
                    }
                    inRun = false
                    gapSinceInk = 0
                }
            }
        }
        if (inRun) {
            val end = if (gapSinceInk > 0) h - gapSinceInk else h
            if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                rows.add(runStart to end)
            }
        }
        return rows
    }

    /**
     * TachiyomiAT: detect vertical text columns in a TALL crop by ink-gap analysis.
     * Returns column x-ranges [(x0,x1), ...] in left-to-right pixel order.
     *
     * Algorithm: a column is a horizontal x-band containing ink (dark text strokes);
     * a wide-enough ink-free x-band separates columns. Within-column inter-character
     * gaps are NARROW (typical glyph spacing) and are merged so a single column of
     * several stacked glyphs is not split apart; only wider inter-COLUMN gaps split.
     */
    internal fun detectVerticalColumns(crop: Bitmap): List<Pair<Int, Int>> {
        val w = crop.width
        val h = crop.height
        if (w < 2 || h < 2) return listOf(0 to w)
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        val inkFraction = FloatArray(w)
        for (x in 0 until w) {
            var dark = 0
            for (y in 0 until h) {
                val px = pixels[y * w + x]
                val lum = (
                    0.299f * ((px shr 16) and 0xFF) +
                        0.587f * ((px shr 8) and 0xFF) +
                        0.114f * (px and 0xFF)
                    ).toInt()
                if (lum < INK_LUMINANCE_THRESHOLD) dark++
            }
            inkFraction[x] = dark.toFloat() / h.toFloat()
        }
        val columns = ArrayList<Pair<Int, Int>>()
        var inRun = false
        var runStart = 0
        var gapSinceInk = 0
        for (x in 0 until w) {
            val hasInk = inkFraction[x] >= COLUMN_GAP_INK_FRACTION
            if (hasInk) {
                if (!inRun) {
                    runStart = x
                    inRun = true
                }
                gapSinceInk = 0
            } else if (inRun) {
                gapSinceInk++
                if (gapSinceInk >= MIN_COLUMN_GAP_PX) {
                    val end = x - gapSinceInk
                    if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                        columns.add(runStart to end)
                    }
                    inRun = false
                    gapSinceInk = 0
                }
            }
        }
        if (inRun) {
            val end = if (gapSinceInk > 0) w - gapSinceInk else w
            if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                columns.add(runStart to end)
            }
        }
        return columns
    }
}
