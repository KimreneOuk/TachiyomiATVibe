package eu.kanade.translation.engines.vision.webtoon

import eu.kanade.translation.model.Detection
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebtoonSlidingDetectorTest {

    @Test
    fun `isTallImage correctly identifies aspect ratios`() {
        // Standard manga page (800x1200 -> aspect 1.5)
        WebtoonSlidingDetector.isTallImage(800, 1200) shouldBe false

        // Square or landscape
        WebtoonSlidingDetector.isTallImage(1000, 1000) shouldBe false
        WebtoonSlidingDetector.isTallImage(1200, 800) shouldBe false

        // Webtoon long strip (1000x2500 -> aspect 2.5)
        WebtoonSlidingDetector.isTallImage(1000, 2500) shouldBe true

        // Mega strip (1000x20000 -> aspect 20.0)
        WebtoonSlidingDetector.isTallImage(1000, 20000) shouldBe true
    }

    @Test
    fun `calculateWindows slices tall strip into overlapping 1 to 1_4 windows`() {
        val width = 1000
        val height = 4000
        val targetWindowHeight = 1400
        val overlap = 250

        val windows = WebtoonSlidingDetector.calculateWindows(
            width = width,
            height = height,
            targetWindowHeight = targetWindowHeight,
            overlapPx = overlap,
        )

        // Step = 1400 - 250 = 1150
        // Window 0: 0 .. 1400 (height 1400)
        // Window 1: 1150 .. 2550 (height 1400)
        // Window 2: 2300 .. 3700 (height 1400)
        // Window 3: 3450 .. 4000 (height 550)
        windows.size shouldBe 4

        windows[0].top shouldBe 0
        windows[0].bottom shouldBe 1400
        windows[0].height shouldBe 1400

        windows[1].top shouldBe 1150
        windows[1].bottom shouldBe 2550

        windows[2].top shouldBe 2300
        windows[2].bottom shouldBe 3700

        windows[3].top shouldBe 3450
        windows[3].bottom shouldBe 4000
    }

    @Test
    fun `calculateWindows returns single full-height window for non-tall images`() {
        val windows = WebtoonSlidingDetector.calculateWindows(800, 1200)
        windows.size shouldBe 1
        windows[0].top shouldBe 0
        windows[0].bottom shouldBe 1200
        windows[0].height shouldBe 1200
    }

    @Test
    fun `projectDetection accurately maps local window coordinates to global space`() {
        val localDetection = Detection(
            bbox = intArrayOf(100, 50, 400, 250),
            label = 0,
            score = 0.95f,
            className = "bubble",
        )

        val windowTop = 1150
        val projected = WebtoonSlidingDetector.projectDetection(localDetection, windowTop)

        projected.bbox[0] shouldBe 100
        projected.bbox[1] shouldBe 1200 // 50 + 1150
        projected.bbox[2] shouldBe 400
        projected.bbox[3] shouldBe 1400 // 250 + 1150
        projected.score shouldBe 0.95f
        projected.className shouldBe "bubble"
    }

    @Test
    fun `mergeDetections unifies overlapping detections across window seams`() {
        // Two overlapping detections of the same bubble detected in Window 0 and Window 1
        val det1 = Detection(
            bbox = intArrayOf(200, 1200, 500, 1380),
            label = 0,
            score = 0.88f,
            className = "bubble",
        )
        val det2 = Detection(
            bbox = intArrayOf(205, 1210, 495, 1390),
            label = 0,
            score = 0.92f,
            className = "bubble",
        )
        // Independent bubble further down
        val det3 = Detection(
            bbox = intArrayOf(100, 3000, 300, 3200),
            label = 0,
            score = 0.90f,
            className = "bubble",
        )

        val merged = WebtoonSlidingDetector.mergeDetections(listOf(det1, det2, det3))

        merged.size shouldBe 2

        // The overlapping pair is merged to union bounds with the maximum score
        val union = merged.first { it.bbox[1] < 2000 }
        union.bbox[0] shouldBe 200
        union.bbox[1] shouldBe 1200
        union.bbox[2] shouldBe 500
        union.bbox[3] shouldBe 1390
        union.score shouldBe 0.92f

        // The independent bubble is retained intact
        val separate = merged.first { it.bbox[1] > 2000 }
        separate.bbox[1] shouldBe 3000
    }

    @Test
    fun `mergeDetections merges collinear seam slice detections on sliding window boundaries`() {
        // Upper slice in Window 0 and lower slice in Window 1 touching at y=1300
        val topHalf = Detection(
            bbox = intArrayOf(150, 1180, 450, 1310),
            label = 0,
            score = 0.85f,
            className = "bubble",
        )
        val bottomHalf = Detection(
            bbox = intArrayOf(152, 1295, 448, 1420),
            label = 0,
            score = 0.89f,
            className = "bubble",
        )

        val merged = WebtoonSlidingDetector.mergeDetections(listOf(topHalf, bottomHalf))
        merged.size shouldBe 1
        val unified = merged[0]
        unified.bbox[0] shouldBe 150
        unified.bbox[1] shouldBe 1180
        unified.bbox[2] shouldBe 450
        unified.bbox[3] shouldBe 1420
        unified.score shouldBe 0.89f
    }
}
