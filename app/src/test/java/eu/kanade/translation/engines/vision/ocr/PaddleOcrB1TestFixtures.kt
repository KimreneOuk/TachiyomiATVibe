package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrCropOwnership
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrFallbackKind
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrLeafWork
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrPageGeneration
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrParentRegion
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRotation
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrWidthBucket
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException

internal val PADDLE_B1_DICTIONARY = listOf("a", "b", "c", "d", "e", "f", "g", "h")

internal data class PaddleB1Payload(
    val indices: IntArray,
    val probabilities: FloatArray,
    val expected: Pair<String, Float>,
) {
    init {
        require(indices.size == probabilities.size)
    }

    companion object {
        fun text(value: String, confidence: Float): PaddleB1Payload {
            require(value.all { it in 'a'..'h' })
            val indices = IntArray(TIME_STEPS)
            val probabilities = FloatArray(TIME_STEPS) { 0.97f }
            value.forEachIndexed { index, character ->
                require(index < TIME_STEPS - 1)
                indices[index] = PADDLE_B1_DICTIONARY.indexOf(character.toString()) + 1
                probabilities[index] = confidence + index * 0.01f
            }
            val expected = PaddleCtcDecoder.decodeWithConf(indices, probabilities, PADDLE_B1_DICTIONARY)
            return PaddleB1Payload(indices, probabilities, expected)
        }

        fun empty(): PaddleB1Payload {
            val indices = IntArray(TIME_STEPS)
            val probabilities = FloatArray(TIME_STEPS) { 0.97f }
            return PaddleB1Payload(
                indices = indices,
                probabilities = probabilities,
                expected = "" to 0.0f,
            )
        }

        private const val TIME_STEPS = 4
    }
}

internal data class PaddleB1Crop(
    val id: String,
    val marker: Int,
    val widthBucket: Int,
    val payload: PaddleB1Payload,
)

internal data class PaddleB1LeafSpec(
    val crop: PaddleB1Crop,
    val stage: String,
    val page: PaddleOcrPageGeneration,
    val parentRegion: PaddleOcrParentRegion,
    val lineIndex: Int?,
    val glyphIndex: Int?,
    val rotation: PaddleOcrRotation,
    val fallbackKind: PaddleOcrFallbackKind,
)

internal data class PaddleB1PageFixture(
    val page: PaddleOcrPageGeneration,
    val detectorPresent: Boolean,
    val leaves: List<PaddleOcrLeafWork<PaddleB1Crop>>,
    val specs: List<PaddleB1LeafSpec>,
    val filteredRegionIndexes: List<Int>,
    val releaseCounts: Map<String, Int>,
) {
    val expectedRows: List<Pair<String, Float>>
        get() = leaves.map { it.crop.payload.expected }

    val expectedRegionCount: Int
        get() = leaves.map { it.parentRegion }.distinct().size
}

internal object PaddleOcrB1FixtureFactory {

    fun detectorPresent(): PaddleB1PageFixture = create(detectorPresent = true)

    fun detectorAbsent(): PaddleB1PageFixture = create(detectorPresent = false)

    fun fallbackHeavy(): PaddleB1PageFixture = createFallbackHeavy()

    fun uniformEight(): PaddleB1PageFixture {
        val base = createFallbackHeavy()
        val leaves = base.leaves.map { leaf ->
            leaf.copy(
                crop = leaf.crop.copy(widthBucket = 640),
                widthBucket = PaddleOcrWidthBucket.WIDTH_640,
            )
        }
        return base.copy(
            leaves = leaves,
            specs = base.specs.map { it.copy(crop = it.crop.copy(widthBucket = 640)) },
        )
    }

    private fun create(detectorPresent: Boolean): PaddleB1PageFixture {
        val page = PaddleOcrPageGeneration(
            pageId = if (detectorPresent) "chapter-b1/page-det-present" else "chapter-b1/page-det-absent",
            generation = 7L,
        )
        val releaseCounts = linkedMapOf<String, Int>()
        val specs = mutableListOf<PaddleB1LeafSpec>()
        val leaves = mutableListOf<PaddleOcrLeafWork<PaddleB1Crop>>()
        var marker = 1

        fun add(
            id: String,
            stage: String,
            region: Int,
            line: Int?,
            glyph: Int? = null,
            bucket: Int,
            text: String,
            confidence: Float,
            fallback: PaddleOcrFallbackKind,
            rotation: PaddleOcrRotation,
        ) {
            val crop = PaddleB1Crop(
                id = id,
                marker = marker++,
                widthBucket = bucket,
                payload = if (text.isEmpty()) PaddleB1Payload.empty() else PaddleB1Payload.text(text, confidence),
            )
            releaseCounts[id] = 0
            val leaf = PaddleOcrLeafWork(
                pageGeneration = page,
                parentRegion = PaddleOcrParentRegion(region),
                lineIndex = line,
                glyphIndex = glyph,
                crop = crop,
                cropOwnership = PaddleOcrCropOwnership(id) {
                    releaseCounts[id] = (releaseCounts[id] ?: 0) + 1
                },
                rotation = rotation,
                fallbackKind = fallback,
                widthBucket = if (bucket == 640) {
                    PaddleOcrWidthBucket.WIDTH_640
                } else {
                    PaddleOcrWidthBucket.WIDTH_1600
                },
            )
            leaves += leaf
            specs += PaddleB1LeafSpec(
                crop = crop,
                stage = stage,
                page = page,
                parentRegion = leaf.parentRegion,
                lineIndex = line,
                glyphIndex = glyph,
                rotation = rotation,
                fallbackKind = fallback,
            )
        }

        add(
            id = "r0-line0",
            stage = if (detectorPresent) "detector-line" else "heuristic-line",
            region = 0,
            line = 0,
            bucket = 640,
            text = "ab",
            confidence = 0.81f,
            fallback = if (detectorPresent) PaddleOcrFallbackKind.DETECTOR_LINE else PaddleOcrFallbackKind.HEURISTIC_LINE,
            rotation = PaddleOcrRotation.NONE,
        )
        add(
            id = "r1-glyph0",
            stage = "glyph-split",
            region = 1,
            line = 0,
            glyph = 0,
            bucket = 640,
            text = "c",
            confidence = 0.72f,
            fallback = PaddleOcrFallbackKind.GLYPH,
            rotation = PaddleOcrRotation.CCW_90,
        )
        add(
            id = "r1-glyph1",
            stage = "glyph-split",
            region = 1,
            line = 0,
            glyph = 1,
            bucket = 640,
            text = "d",
            confidence = 0.74f,
            fallback = PaddleOcrFallbackKind.GLYPH,
            rotation = PaddleOcrRotation.CCW_90,
        )
        add(
            id = "r2-empty",
            stage = "empty-join",
            region = 2,
            line = null,
            bucket = 640,
            text = "",
            confidence = 0.0f,
            fallback = PaddleOcrFallbackKind.WHOLE_REGION,
            rotation = PaddleOcrRotation.NONE,
        )
        add(
            id = "r4-detector-empty",
            stage = "detector-empty-heuristic-fallback",
            region = 4,
            line = 0,
            bucket = 640,
            text = "e",
            confidence = 0.68f,
            fallback = PaddleOcrFallbackKind.HEURISTIC_LINE,
            rotation = PaddleOcrRotation.NONE,
        )
        add(
            id = "r5-unpadded-reread",
            stage = "unpad-reread",
            region = 5,
            line = null,
            bucket = 1600,
            text = "f",
            confidence = 0.66f,
            fallback = PaddleOcrFallbackKind.WHOLE_REGION,
            rotation = PaddleOcrRotation.NONE,
        )

        return PaddleB1PageFixture(
            page = page,
            detectorPresent = detectorPresent,
            leaves = leaves,
            specs = specs,
            filteredRegionIndexes = listOf(3),
            releaseCounts = releaseCounts,
        )
    }

    private fun createFallbackHeavy(): PaddleB1PageFixture {
        val page = PaddleOcrPageGeneration("chapter-b1/page-fallback-heavy", 8L)
        val releaseCounts = linkedMapOf<String, Int>()
        val specs = mutableListOf<PaddleB1LeafSpec>()
        val leaves = mutableListOf<PaddleOcrLeafWork<PaddleB1Crop>>()
        var marker = 100

        fun add(
            id: String,
            region: Int,
            line: Int?,
            glyph: Int? = null,
            bucket: Int,
            text: String,
            fallback: PaddleOcrFallbackKind,
            rotation: PaddleOcrRotation = PaddleOcrRotation.NONE,
        ) {
            val crop = PaddleB1Crop(
                id = id,
                marker = marker++,
                widthBucket = bucket,
                payload = if (text.isEmpty()) PaddleB1Payload.empty() else PaddleB1Payload.text(text, 0.61f + (region * 0.01f)),
            )
            releaseCounts[id] = 0
            val leaf = PaddleOcrLeafWork(
                pageGeneration = page,
                parentRegion = PaddleOcrParentRegion(region),
                lineIndex = line,
                glyphIndex = glyph,
                crop = crop,
                cropOwnership = PaddleOcrCropOwnership(id) {
                    releaseCounts[id] = (releaseCounts[id] ?: 0) + 1
                },
                rotation = rotation,
                fallbackKind = fallback,
                widthBucket = if (bucket == 640) PaddleOcrWidthBucket.WIDTH_640 else PaddleOcrWidthBucket.WIDTH_1600,
            )
            leaves += leaf
            specs += PaddleB1LeafSpec(
                crop = crop,
                stage = fallback.name.lowercase(),
                page = page,
                parentRegion = leaf.parentRegion,
                lineIndex = line,
                glyphIndex = glyph,
                rotation = rotation,
                fallbackKind = fallback,
            )
        }

        add("heavy-0", 0, 0, bucket = 640, text = "ab", fallback = PaddleOcrFallbackKind.DETECTOR_LINE)
        add("heavy-1", 1, null, bucket = 640, text = "", fallback = PaddleOcrFallbackKind.WHOLE_REGION)
        add("heavy-2", 2, 0, bucket = 1600, text = "cd", fallback = PaddleOcrFallbackKind.HEURISTIC_LINE)
        add("heavy-3", 3, 0, glyph = 0, bucket = 640, text = "e", fallback = PaddleOcrFallbackKind.GLYPH, rotation = PaddleOcrRotation.CCW_90)
        add("heavy-4", 3, 0, glyph = 1, bucket = 640, text = "f", fallback = PaddleOcrFallbackKind.GLYPH, rotation = PaddleOcrRotation.CCW_90)
        add("heavy-5", 4, null, bucket = 1600, text = "g", fallback = PaddleOcrFallbackKind.WHOLE_REGION)
        add("heavy-6", 5, 0, bucket = 640, text = "h", fallback = PaddleOcrFallbackKind.HEURISTIC_LINE)
        add("heavy-7", 6, null, bucket = 640, text = "a", fallback = PaddleOcrFallbackKind.WHOLE_REGION)

        return PaddleB1PageFixture(
            page = page,
            detectorPresent = true,
            leaves = leaves,
            specs = specs,
            filteredRegionIndexes = emptyList(),
            releaseCounts = releaseCounts,
        )
    }
}

internal data class PaddleB1SessionCall(
    val inputShape: LongArray,
    val markers: List<Int>,
    val outputShape: LongArray,
)

internal class PaddleB1FakeSession(
    crops: List<PaddleB1Crop>,
    override val providerLabel: String = "qnn_htp",
    private val failuresByBatch: MutableMap<Int, Int> = mutableMapOf(),
    private val cancelOnMarker: Int? = null,
    private val cancelAfterExecutionMarker: Int? = null,
) : PaddleOcrV6BatchSession {

    private val cropsByMarker = crops.associateBy { it.marker }
    val calls = mutableListOf<PaddleB1SessionCall>()

    override fun run(input: FloatBuffer, shape: LongArray): PaddleOcrV6BatchOutput {
        val batchSize = shape[0].toInt()
        val widthBucket = shape[3].toInt()
        val sampleElements = 3 * PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT * widthBucket
        val markers = (0 until batchSize).map { input.get(it * sampleElements).toInt() }
        calls += PaddleB1SessionCall(
            inputShape = shape.copyOf(),
            markers = markers,
            outputShape = longArrayOf(batchSize.toLong(), TIME_STEPS.toLong(), CLASS_COUNT.toLong()),
        )
        val remainingFailures = failuresByBatch[batchSize] ?: 0
        if (remainingFailures > 0) {
            failuresByBatch[batchSize] = remainingFailures - 1
            throw IllegalStateException("fake provider failure for B$batchSize")
        }
        if (cancelOnMarker != null && cancelOnMarker in markers) {
            throw CancellationException("fake cancellation during ORT for marker=$cancelOnMarker")
        }
        val logits = FloatArray(batchSize * TIME_STEPS * CLASS_COUNT) { -1f }
        markers.forEachIndexed { batchIndex, marker ->
            val payload = cropsByMarker.getValue(marker).payload
            payload.indices.forEachIndexed { timeStep, classIndex ->
                val offset = (batchIndex * TIME_STEPS + timeStep) * CLASS_COUNT
                logits[offset + classIndex] = payload.probabilities[timeStep]
            }
        }
        val outputShape = longArrayOf(batchSize.toLong(), TIME_STEPS.toLong(), CLASS_COUNT.toLong())
        return PaddleB1FakeOutput(
            shapeValue = outputShape,
            logits = FloatBuffer.wrap(logits),
            cancelOnShape = cancelAfterExecutionMarker != null && cancelAfterExecutionMarker in markers,
        )
    }

    companion object {
        const val TIME_STEPS = 4
        val CLASS_COUNT = PADDLE_B1_DICTIONARY.size + 2
    }
}

internal class PaddleB1FakeOutput(
    private val shapeValue: LongArray,
    val logits: FloatBuffer,
    private val cancelOnShape: Boolean,
) : PaddleOcrV6BatchOutput {

    override val shape: LongArray
        get() {
            if (cancelOnShape) throw CancellationException("fake cancellation after ORT execution")
            return shapeValue.copyOf()
        }

    override fun copyTo(destination: FloatBuffer) {
        val source = logits.duplicate()
        source.clear()
        destination.clear()
        destination.put(source)
        destination.flip()
    }

    override fun close() = Unit
}

internal data class PaddleB1SingleResult(
    val value: Pair<String, Float>,
    val inputShape: LongArray,
    val outputShape: LongArray,
)

internal fun paddleB1Pool(
    allocator: PaddleOcrV6BatchBufferPool.FloatBufferAllocator =
        PaddleOcrV6BatchBufferPool.FloatBufferAllocator { capacity -> FloatBuffer.allocate(capacity) },
): PaddleOcrV6BatchBufferPool = PaddleOcrV6BatchBufferPool(
    maxBatchSize = 8,
    maxWidth = PaddleOcrV6SmallEngine.MAX_RECOGNITION_WIDTH,
    dictionarySize = PADDLE_B1_DICTIONARY.size,
    allocator = allocator,
)

internal fun paddleB1Executor(
    session: PaddleOcrV6BatchSession,
    pool: PaddleOcrV6BatchBufferPool = paddleB1Pool(),
    strictProviderMode: Boolean = false,
): PaddleOcrV6BatchExecutor = PaddleOcrV6BatchExecutor(
    session = session,
    bufferPool = pool,
    dictionary = PADDLE_B1_DICTIONARY,
    strictProviderMode = strictProviderMode,
)

internal fun writePaddleB1Marker(
    crop: PaddleB1Crop,
    destination: FloatBuffer,
    baseOffset: Int,
    @Suppress("UNUSED_PARAMETER") widthBucket: Int,
) {
    destination.put(baseOffset, crop.marker.toFloat())
}

internal fun executePaddleB1(
    crops: List<PaddleB1Crop>,
    widthBucket: Int,
    session: PaddleB1FakeSession,
    maxBatch: Int = 1,
    pool: PaddleOcrV6BatchBufferPool = paddleB1Pool(),
): PaddleOcrV6BatchExecution = paddleB1Executor(session, pool).execute(
    crops = crops,
    widthBucket = widthBucket,
    maxBatch = maxBatch,
    writeSample = ::writePaddleB1Marker,
)

internal fun recognizePaddleWithConfPath(
    crop: PaddleB1Crop,
    session: PaddleB1FakeSession,
): PaddleB1SingleResult {
    val widthBucket = crop.widthBucket
    val inputShape = longArrayOf(
        1L,
        3L,
        PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT.toLong(),
        widthBucket.toLong(),
    )
    val input = FloatBuffer.allocate(3 * PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT * widthBucket)
    writePaddleB1Marker(crop, input, 0, widthBucket)
    input.limit(input.capacity())
    input.position(0)
    val output = session.run(input, inputShape)
    val outputShape = output.shape
    val fakeOutput = output as PaddleB1FakeOutput
    val row = fakeOutput.logits.duplicate()
    row.position(0)
    row.limit((outputShape[1] * outputShape[2]).toInt())
    val (indices, maxProbs) = PaddleCtcDecoder.argmaxWithProbs(
        row.slice(),
        timeSteps = outputShape[1].toInt(),
        classCount = outputShape[2].toInt(),
    )
    val value = PaddleCtcDecoder.decodeWithConf(indices, maxProbs, PADDLE_B1_DICTIONARY)
    output.close()
    return PaddleB1SingleResult(value, inputShape, outputShape)
}

internal fun PaddleB1PageFixture.assertOwnershipReleased() {
    check(releaseCounts.values.all { it == 1 }) {
        "crop ownership releases=${releaseCounts.filterValues { it != 1 }}"
    }
}
