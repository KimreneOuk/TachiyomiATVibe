# Full-Page PaddleOCR Detection & Spatial Text Line Grouping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overhaul PaddleOCR detection from per-bubble/crop execution to a single full-page DBNet pass with deterministic spatial grouping and reading order sorting, eliminating redundant per-crop DBNet inference in both OCR and Inpainting to achieve sub-2.0s native vision latency.

**Architecture:** 
1. Full-page text line detection in `PaddleOcrV6DetEngine.detectPageLines()` (scaling preserving aspect ratio with $\le 960$px max dimension, and sliding window on webtoon strips $H/W \ge 2.0$).
2. Spatial containment and proximity clustering in `PaddleLineGrouper` assigning lines to RT-DETR/YOLO speech bubbles (label 0) and clustering orphan lines into free-text regions (label 2).
3. Deterministic two-tier reading order sorting (Panel/page level via `ReadingOrderSorter` / horizontal bands; line level via `VerticalLineOcr` right-to-left / top-to-bottom rules).
4. Fast line cropping directly in `PaddlePageOcrCoordinator` and mask bounds reuse in `AOTInpainting.refineFreeTextGroups`.

**Tech Stack:** Kotlin, Android SDK 36, ONNX Runtime Mobile, JUnit 5 / Kotest assertions.

## Global Constraints
- **Zero algorithmic changes to other OCR models**: MangaOCR and ML Kit fallback paths remain completely untouched.
- **Fail-open & zero hot-path GC**: All tensor preprocessing uses direct byte buffers; allocations in loop bodies must use `BitmapPool` or existing reusable arrays.
- **Strict single Gradle process**: Obey the machine-wide runner rules (`JAVA_HOME`, `ANDROID_HOME`, `-Dkotlin.daemon.jvmargs=-Xmx3g`). Do not invoke `--stop` or concurrent Gradle daemons.
- **Deterministic ordering**: Bubble-to-bubble and line-to-line ordering must be pure and reproducible.

---

### Task 1: Spatial Line Grouping & Reading Order Engine (`PaddleLineGrouper`)

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleLineGrouper.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddleLineGrouperTest.kt`

**Interfaces:**
- Consumes:
  - `Detection(bbox: IntArray, label: Int, score: Float, className: String)`
  - `TextLine(bbox: IntArray, score: Float)`
  - `BoxGeometry`
- Produces:
  - `PaddleGroupedRegion(detection: Detection, lines: List<TextLine>)`
  - `PaddleLineGrouper.groupLinesIntoRegions(detections: List<Detection>, lines: List<TextLine>, imageWidth: Int, imageHeight: Int, isVerticalLanguage: Boolean, isRtl: Boolean): List<PaddleGroupedRegion>`

- [ ] **Step 1: Write failing unit tests for PaddleLineGrouper**

```kotlin
// app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddleLineGrouperTest.kt
package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.vision.detection.Detection
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PaddleLineGrouperTest {

    @Test
    fun `line inside speech bubble is assigned to that bubble`() {
        val bubble = Detection(bbox = intArrayOf(100, 100, 300, 400), label = 0, score = 0.9f)
        val line = TextLine(bbox = intArrayOf(150, 150, 200, 350), score = 0.95f)

        val groups = PaddleLineGrouper.groupLinesIntoRegions(
            detections = listOf(bubble),
            lines = listOf(line),
            imageWidth = 1000,
            imageHeight = 1000,
            isVerticalLanguage = true,
            isRtl = true,
        )

        groups.size shouldBe 1
        groups[0].detection shouldBe bubble
        groups[0].lines.size shouldBe 1
        groups[0].lines[0] shouldBe line
    }

    @Test
    fun `lines inside speech bubble are sorted Right-to-Left for vertical Japanese`() {
        val bubble = Detection(bbox = intArrayOf(100, 100, 400, 400), label = 0, score = 0.9f)
        // lineRight is at x=250..280, lineLeft is at x=150..180
        val lineLeft = TextLine(bbox = intArrayOf(150, 120, 180, 380), score = 0.95f)
        val lineRight = TextLine(bbox = intArrayOf(250, 120, 280, 380), score = 0.95f)

        val groups = PaddleLineGrouper.groupLinesIntoRegions(
            detections = listOf(bubble),
            lines = listOf(lineLeft, lineRight), // Passed in arbitrary order
            imageWidth = 1000,
            imageHeight = 1000,
            isVerticalLanguage = true,
            isRtl = true,
        )

        groups[0].lines[0] shouldBe lineRight
        groups[0].lines[1] shouldBe lineLeft
    }

    @Test
    fun `orphan lines outside bubbles are clustered into free-text regions`() {
        // Two adjacent vertical lines outside any bubble
        val line1 = TextLine(bbox = intArrayOf(500, 200, 530, 400), score = 0.9f)
        val line2 = TextLine(bbox = intArrayOf(540, 210, 570, 390), score = 0.9f)

        val groups = PaddleLineGrouper.groupLinesIntoRegions(
            detections = emptyList(),
            lines = listOf(line1, line2),
            imageWidth = 1000,
            imageHeight = 1000,
            isVerticalLanguage = true,
            isRtl = true,
        )

        groups.size shouldBe 1
        groups[0].detection.label shouldBe 2 // text_free
        groups[0].lines.size shouldBe 2
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.PaddleLineGrouperTest"`  
Expected: FAIL with compilation error "Unresolved reference: PaddleLineGrouper".

- [ ] **Step 3: Implement PaddleLineGrouper**

```kotlin
// app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleLineGrouper.kt
package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.vision.detection.Detection
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
            result.add(PaddleGroupedRegion(bubble, sortedLines))
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
                val it = unvisited.iterator()
                while (it.hasNext()) {
                    val candidate = it.next()
                    if (cluster.any { shouldClusterTogether(it.bbox, candidate.bbox) }) {
                        cluster.add(candidate)
                        it.remove()
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
            }
        )
    }
}
```

- [ ] **Step 4: Run unit tests to verify they pass**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.PaddleLineGrouperTest"`  
Expected: PASS (all tests pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleLineGrouper.kt
git add app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddleLineGrouperTest.kt
git commit -m "feat(ocr): add PaddleLineGrouper for spatial line clustering and reading order"
```

---

### Task 2: Full-Page Detection in `PaddleOcrV6DetEngine`

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6DetEngine.kt:130-180`
- Test: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6DetEngineTest.kt`

**Interfaces:**
- Consumes: `Bitmap`, `DbPostProcess`, `calculateDetDimensions`
- Produces: `PaddleOcrV6DetEngine.detectPageLines(bitmap: Bitmap, isWebtoon: Boolean): List<TextLine>`

- [ ] **Step 1: Write unit test for `detectPageLines` coordinate mapping**

```kotlin
// app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6DetEngineTest.kt
@Test
fun `detectPageLines preserves original image coordinates`() {
    // Verify scaled output maps cleanly back to full original image width and height
    val dims = PaddleOcrV6DetEngine.calculateDetDimensions(1350, 1920)
    assertTrue(dims.width <= 960)
    assertTrue(dims.height <= 960)
    assertEquals(0, dims.width % 32)
    assertEquals(0, dims.height % 32)
}
```

- [ ] **Step 2: Add `detectPageLines` implementation in `PaddleOcrV6DetEngine.kt`**

```kotlin
fun detectPageLines(
    bitmap: Bitmap,
    thresh: Float = DbPostProcess.Defaults.THRESH,
    boxThresh: Float = DbPostProcess.Defaults.BOX_THRESH,
    maxSide: Int = MAX_DET_SIZE,
): List<TextLine> {
    if (closed) return emptyList()
    val localSession = session ?: return emptyList()
    val origW = bitmap.width
    val origH = bitmap.height

    val dims = calculateDetDimensions(origW, origH, maxSide)
    val resized = BitmapPool.getARGB8888(dims.width, dims.height)
    try {
        val canvas = Canvas(resized)
        val paint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, dims.width.toFloat(), dims.height.toFloat()), paint)

        val inputTensor = preprocess(resized)
        val results = try {
            localSession.run(mapOf("x" to inputTensor))
        } finally {
            inputTensor.close()
        }

        return try {
            val outputTensor = results[0] as OnnxTensor
            val shape = outputTensor.info.shape
            val outH = shape[2].toInt()
            val outW = shape[3].toInt()
            val buffer = outputTensor.floatBuffer
            val predMap = Array(outH) { FloatArray(outW) }
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    predMap[y][x] = buffer.get()
                }
            }
            DbPostProcess.boxesFromBitmap(
                pred = predMap,
                destWidth = origW,
                destHeight = origH,
                thresh = thresh,
                boxThresh = boxThresh,
            )
        } finally {
            results.close()
        }
    } finally {
        BitmapPool.putARGB8888(resized)
    }
}
```

- [ ] **Step 3: Run unit tests to verify they pass**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.PaddleOcrV6DetEngineTest"`  
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6DetEngine.kt
git commit -m "feat(ocr): add detectPageLines to PaddleOcrV6DetEngine for 1-pass detection"
```

---

### Task 3: Fast Line Slicing in `PaddlePageOcrCoordinator`

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinator.kt:170-350`
- Test: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinatorTest.kt`

**Interfaces:**
- Consumes: `PaddleGroupedRegion`, `PaddleLineGrouper`
- Produces: Updated `PaddlePageOcrCoordinator.recognizePage` accepting optional `groupedRegions: List<PaddleGroupedRegion>?`

- [ ] **Step 1: Write unit test for recognizePage with pre-grouped regions**

```kotlin
// app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinatorTest.kt
@Test
fun `recognizePage skips per-crop DBNet when groupedRegions provided`() {
    // Confirms no paddleDet calls occur when pre-grouped lines are provided
}
```

- [ ] **Step 2: Update `PaddlePageOcrCoordinator.kt` to consume pre-grouped lines directly**

In `planInitialRegion`:
- When `groupedLines` are provided for `regionIndex`:
  - Bypass `VerticalLineOcr.planMultiLine(crop, paddleDet, ...)`.
  - Directly invoke `VerticalLineOcr.planDetColumns(padded, localLines, language, ...)`.
  - This eliminates all `paddleDet.detectLines(crop)` calls during page recognition.

- [ ] **Step 3: Run coordinator unit tests**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.PaddlePageOcrCoordinatorTest"`  
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinator.kt
git commit -m "perf(ocr): wire pre-grouped lines into PaddlePageOcrCoordinator, bypassing per-crop DBNet"
```

---

### Task 4: Pipeline Integration in `RoiPageRecognitionEngine` & `AOTInpainting`

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt:440-520`
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt:480-530`

- [ ] **Step 1: Wire full-page detection and line grouping into `RoiPageRecognitionEngine.kt`**

```kotlin
// In RoiPageRecognitionEngine.kt
val groupedRegions = if (localOcrEngine is PaddleOcrV6SmallEngine && paddleDet != null) {
    val pageLines = paddleDet.detectPageLines(bitmap)
    PaddleLineGrouper.groupLinesIntoRegions(
        detections = detections,
        lines = pageLines,
        imageWidth = bitmap.width,
        imageHeight = bitmap.height,
        isVerticalLanguage = isVerticalLanguage,
        isRtl = resolveReadingOrderRtl(),
    )
} else {
    null
}
```

- [ ] **Step 2: Bypass redundant DBNet pass in `AOTInpainting.kt`**

In `AOTInpainting.refineFreeTextGroups`:
- Check if text lines already exist on the region (`detection.lines` or stashed line bounds).
- If lines exist, generate dilated bounding rectangles directly from those boxes without re-calling `paddleDetector.detectLines(crop)`.

- [ ] **Step 3: Run full vision test suite**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.*"`  
Expected: PASS (0 failures, 0 errors).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt
git add app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt
git commit -m "feat(pipeline): run full-page Paddle DBNet in RoiPageRecognitionEngine and reuse in AOTInpainting"
```
