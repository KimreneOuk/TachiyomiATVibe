package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.vision.ocr.PaddleB1Crop
import eu.kanade.translation.engines.vision.ocr.PaddleOcrB1FixtureFactory
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrCropOwnership
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrFallbackKind
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrLeafWork
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrPageGeneration
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrParentRegion
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRotation
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrWidthBucket
import eu.kanade.translation.engines.vision.ocr.paddleB1Executor
import eu.kanade.translation.engines.vision.ocr.paddleB1Pool
import eu.kanade.translation.engines.vision.ocr.writePaddleB1Marker
import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.TextRecognizerLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddlePageOcrCoordinatorTest {

    @Test
    fun `B1 fixture rows map back to every page leaf before the page result is published`() = runBlocking<Unit> {
        val fixture = PaddleOcrB1FixtureFactory.fallbackHeavy()
        val session = eu.kanade.translation.engines.vision.ocr.PaddleB1FakeSession(
            crops = fixture.leaves.map { it.crop },
        )
        val dispatcher = PaddlePageOcrBatchDispatcher<PaddleB1Crop, Pair<String, Float>>(
            pageGeneration = fixture.page,
            policy = PaddlePageOcrPolicy(PaddlePageOcrMode.CHAPTER, PaddleOcrBatchSize.B1),
            batchCallMutex = Mutex(),
            recognizeBatch = { crops, bucket, requested ->
                paddleB1Executor(session, paddleB1Pool()).execute(
                    crops = crops,
                    widthBucket = bucket.paddedWidth,
                    maxBatch = requested.value,
                    writeSample = ::writePaddleB1Marker,
                ).results
            },
        )

        fixture.leaves.forEach { dispatcher.submit(it) }
        dispatcher.finish()

        val mappedRows = fixture.leaves.map { leaf -> dispatcher.results.getValue(leaf.identity) }
        assertIterableEquals(fixture.expectedRows, mappedRows)
        assertEquals(fixture.leaves.size, dispatcher.resolvedLeafCount)
        assertTrue(dispatcher.batchTraces.all { it.pageGeneration == fixture.page })
        fixture.leaves.forEach { assertTrue(it.cropOwnership.isReleased) }
    }

    @Test
    fun `manual auto and chapter modes keep visible page leaves page scoped`() = runBlocking<Unit> {
        assertTrue(PaddlePageOcrMode.MANUAL.priority < PaddlePageOcrMode.AUTO.priority)
        assertTrue(PaddlePageOcrMode.AUTO.priority < PaddlePageOcrMode.CHAPTER.priority)

        PaddlePageOcrMode.entries.forEach { mode ->
            val page = PaddleOcrPageGeneration("chapter/page-${mode.name}", generation = 4L)
            val leaves = leaves(page, count = 5)
            val dispatcher = dispatcher(page, mode, PaddleOcrBatchSize.B4)

            leaves.forEach { dispatcher.submit(it) }
            dispatcher.finish()

            assertEquals(5, dispatcher.resolvedLeafCount)
            assertEquals(listOf(4, 1), dispatcher.batchTraces.map { it.leafIdentities.size })
            assertTrue(
                dispatcher.batchTraces.all { trace ->
                    trace.pageGeneration == page && trace.leafIdentities.all { it.pageGeneration == page }
                },
            )
            assertTrue(
                dispatcher.batchTraces.all {
                    it.queueWaitMs >= 0.0 && it.admissionWaitMs >= 0.0 && it.batchLatencyMs >= 0.0
                },
            )
            leaves.forEach { assertTrue(it.cropOwnership.isReleased) }
        }
    }

    @Test
    fun `different page generation cannot enter a visible page tensor`() = runBlocking<Unit> {
        val page = PaddleOcrPageGeneration("chapter/page-visible", generation = 9L)
        val otherPage = page.copy(pageId = "chapter/page-lookahead")
        val releases = linkedMapOf<String, Int>()
        val dispatcher = dispatcher(page, PaddlePageOcrMode.AUTO, PaddleOcrBatchSize.B1)
        val foreign = leaf(otherPage, region = 0, releases = releases)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dispatcher.submit(foreign) }
        }
        dispatcher.cancel()
        assertEquals(1, releases["${otherPage.pageId}:0"])
        assertFalse(dispatcher.batchTraces.any { it.pageGeneration != page })
    }

    @Test
    fun `cancellation releases submitted leaves and retry publishes a complete result`() = runBlocking<Unit> {
        val page = PaddleOcrPageGeneration("chapter/page-cancel", generation = 11L)
        val cancelledLeaves = leaves(page, count = 4)
        var shouldCancel = true
        val dispatcher = PaddlePageOcrBatchDispatcher<String, String>(
            pageGeneration = page,
            policy = PaddlePageOcrPolicy(PaddlePageOcrMode.MANUAL, PaddleOcrBatchSize.B4),
            batchCallMutex = Mutex(),
            recognizeBatch = { crops, _, _ ->
                if (shouldCancel) {
                    shouldCancel = false
                    throw CancellationException("cancelled at safe batch boundary")
                }
                crops.map { "recognized-$it" }
            },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancelledLeaves.forEach { dispatcher.submit(it) }
            }
        }
        dispatcher.cancel()
        cancelledLeaves.forEach { assertTrue(it.cropOwnership.isReleased) }
        assertEquals(0, dispatcher.resolvedLeafCount)

        val retryLeaves = leaves(page, count = 4)
        val retry = dispatcher(page, PaddlePageOcrMode.MANUAL, PaddleOcrBatchSize.B4)
        retryLeaves.forEach { retry.submit(it) }
        retry.finish()
        assertEquals(
            retryLeaves.associate { it.identity to "recognized-${it.crop}" },
            retry.results,
        )
        retryLeaves.forEach { assertTrue(it.cropOwnership.isReleased) }
    }

    @Test
    fun `recognizePage with groupedRegions uses precomputed lines without calling paddleDet detectLines`() = runBlocking<Unit> {
        val fakeBitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true) {
            io.mockk.every { width } returns 500
            io.mockk.every { height } returns 500
        }
        val fakeEngine = io.mockk.mockk<PaddleOcrV6SmallEngine>(relaxed = true) {
            io.mockk.coEvery { recognizeWithConf(any()) } returns Pair("Hello", 0.95f)
        }
        val fakePaddleDet = io.mockk.mockk<PaddleOcrV6DetEngine>(relaxed = true)

        io.mockk.mockkStatic(android.graphics.Bitmap::class)
        try {
            io.mockk.every {
                android.graphics.Bitmap.createBitmap(
                    any<android.graphics.Bitmap>(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns fakeBitmap
            io.mockk.every {
                android.graphics.Bitmap.createBitmap(
                    any<android.graphics.Bitmap>(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns fakeBitmap

            val coordinator = PaddlePageOcrCoordinator(fakeEngine)
            val detection = Detection(
                bbox = intArrayOf(100, 100, 200, 200),
                score = 0.9f,
                label = 1,
            )
            val line = TextLine(
                bbox = intArrayOf(110, 110, 190, 150),
                meanScore = 0.9f,
            )
            val groupedRegion = PaddleGroupedRegion(
                detection = detection,
                lines = listOf(line),
            )

            val results = coordinator.recognizePage(
                pageGeneration = PaddleOcrPageGeneration("test-page", 1L),
                bitmap = fakeBitmap,
                detections = listOf(detection),
                paddleDet = fakePaddleDet,
                language = TextRecognizerLanguage.ENGLISH,
                isVerticalLanguage = false,
                mode = PaddlePageOcrMode.MANUAL,
                groupedRegions = listOf(groupedRegion),
                isClosed = { false },
            )

            assertEquals(1, results.size)
            assertEquals("Hello", results[0].text)
            io.mockk.verify(exactly = 0) { fakePaddleDet.detectLines(any(), any(), any()) }
            io.mockk.verify(exactly = 0) { fakePaddleDet.detectPageLines(any(), any(), any(), any()) }
        } finally {
            io.mockk.unmockkAll()
        }
    }

    private fun dispatcher(
        page: PaddleOcrPageGeneration,
        mode: PaddlePageOcrMode,
        batchSize: PaddleOcrBatchSize,
    ): PaddlePageOcrBatchDispatcher<String, String> = PaddlePageOcrBatchDispatcher(
        pageGeneration = page,
        policy = PaddlePageOcrPolicy(mode, batchSize),
        batchCallMutex = Mutex(),
        recognizeBatch = { crops, _, _ -> crops.map { "recognized-$it" } },
    )

    private fun leaves(page: PaddleOcrPageGeneration, count: Int): List<PaddleOcrLeafWork<String>> {
        val releases = linkedMapOf<String, Int>()
        return (0 until count).map { region -> leaf(page, region, releases) }
    }

    private fun leaf(
        page: PaddleOcrPageGeneration,
        region: Int,
        releases: MutableMap<String, Int>,
    ): PaddleOcrLeafWork<String> {
        val crop = "${page.pageId}:$region"
        releases[crop] = 0
        return PaddleOcrLeafWork(
            pageGeneration = page,
            parentRegion = PaddleOcrParentRegion(region),
            lineIndex = 0,
            glyphIndex = null,
            crop = crop,
            cropOwnership = PaddleOcrCropOwnership(
                token = "${page.pageId}:${page.generation}:$region:initial:0",
                onRelease = {
                    releases[crop] = (releases[crop] ?: 0) + 1
                },
            ),
            rotation = PaddleOcrRotation.NONE,
            fallbackKind = PaddleOcrFallbackKind.DETECTOR_LINE,
            widthBucket = PaddleOcrWidthBucket.WIDTH_640,
        )
    }
}
