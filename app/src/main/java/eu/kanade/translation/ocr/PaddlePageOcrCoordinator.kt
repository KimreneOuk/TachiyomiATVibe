package eu.kanade.translation.ocr

import android.graphics.Bitmap
import eu.kanade.translation.model.Detection
import eu.kanade.translation.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.ocr.PaddleOcrV6SmallEngine
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrBatch
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrBatchPlanner
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrCropOwnership
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrLeafIdentity
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrLeafWork
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrPageGeneration
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrParentRegion
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrWidthBucket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** The page-level scheduling surface used by the three translation modes. */
internal enum class PaddlePageOcrMode {
    MANUAL,
    AUTO,
    CHAPTER,
    ;

    /** Lower values are admitted first when a caller has multiple page jobs. */
    val priority: Int
        get() = when (this) {
            MANUAL -> 0
            AUTO -> 1
            CHAPTER -> 2
        }
}

/**
 * Rollout policy. B1 remains the default; the device gate can inject a larger
 * size without changing page ownership or mapping.
 */
internal data class PaddlePageOcrPolicy(
    val mode: PaddlePageOcrMode,
    val validatedBatchSize: PaddleOcrBatchSize = PaddleOcrBatchSize.B1,
)

internal data class PaddlePageRegionResult(
    val text: String,
    val rotatedForOcr: Boolean,
)

/** One bounded, page-scoped recognizer call for diagnostics and tests. */
internal data class PaddlePageBatchTrace(
    val sequence: Long,
    val pageGeneration: PaddleOcrPageGeneration,
    val widthBucket: PaddleOcrWidthBucket,
    val leafIdentities: List<PaddleOcrLeafIdentity>,
    val requestedBatchSize: PaddleOcrBatchSize,
    val queueWaitMs: Double,
    val admissionWaitMs: Double,
    val batchLatencyMs: Double,
)

/**
 * Generic page dispatcher. The planner stays single-thread-confined here; the
 * shared mutex is the explicit seam guard for the engine session's one output
 * buffer. It is intentionally independent of nativeGuard, which remains owned
 * by RoiPageRecognitionEngine around the complete page.
 */
internal class PaddlePageOcrBatchDispatcher<CROP, RESULT>(
    private val pageGeneration: PaddleOcrPageGeneration,
    private val policy: PaddlePageOcrPolicy,
    private val recognizeBatch: suspend (
        crops: List<CROP>,
        widthBucket: PaddleOcrWidthBucket,
        requestedBatchSize: PaddleOcrBatchSize,
    ) -> List<RESULT>,
    private val batchCallMutex: Mutex,
    private val nowNanos: () -> Long = System::nanoTime,
    private val traceSequence: () -> Long = { 0L },
    private val executorLatencyMs: () -> Double? = { null },
) {
    private val planner = PaddleOcrBatchPlanner<CROP>(pageGeneration, policy.validatedBatchSize)
    private val admittedAtNanos = LinkedHashMap<PaddleOcrLeafIdentity, Long>()
    private val resultsByIdentity = LinkedHashMap<PaddleOcrLeafIdentity, RESULT>()
    private val traces = ArrayList<PaddlePageBatchTrace>()

    val results: Map<PaddleOcrLeafIdentity, RESULT>
        get() = resultsByIdentity.toMap()

    val batchTraces: List<PaddlePageBatchTrace>
        get() = traces.toList()

    val resolvedLeafCount: Int
        get() = resultsByIdentity.size

    suspend fun submit(leaf: PaddleOcrLeafWork<CROP>) {
        val admittedAt = nowNanos()
        admittedAtNanos[leaf.identity] = admittedAt
        try {
            planner.admit(leaf)?.let { batch -> execute(batch) }
        } catch (failure: Throwable) {
            if (!leaf.cropOwnership.isReleased) leaf.releaseCropOwnership()
            admittedAtNanos.remove(leaf.identity)
            throw failure
        }
    }

    suspend fun finish() {
        planner.finishPage().forEach { batch -> execute(batch) }
        check(planner.isDrained) { "Paddle page planner did not drain $pageGeneration" }
    }

    fun cancel() {
        planner.cancel()
        admittedAtNanos.clear()
    }

    private suspend fun execute(batch: PaddleOcrBatch<CROP>) {
        val firstAdmission = batch.leaves.minOfOrNull { admittedAtNanos[it.identity] ?: nowNanos() } ?: nowNanos()
        val beforeMutex = nowNanos()
        try {
            val rows = batchCallMutex.withLock {
                recognizeBatch(batch.leaves.map { it.crop }, batch.widthBucket, policy.validatedBatchSize)
            }
            val afterMutex = nowNanos()
            planner.complete(batch, rows) { leaf, result ->
                check(resultsByIdentity.put(leaf.identity, result) == null) {
                    "duplicate Paddle page result for ${leaf.identity}"
                }
                admittedAtNanos.remove(leaf.identity)
            }
            traces += PaddlePageBatchTrace(
                sequence = traceSequence(),
                pageGeneration = pageGeneration,
                widthBucket = batch.widthBucket,
                leafIdentities = batch.leaves.map { it.identity },
                requestedBatchSize = policy.validatedBatchSize,
                queueWaitMs = nanosToMs(beforeMutex - firstAdmission),
                admissionWaitMs = nanosToMs(afterMutex - beforeMutex),
                batchLatencyMs = executorLatencyMs()?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: nanosToMs(afterMutex - beforeMutex),
            )
        } catch (cancelled: CancellationException) {
            if (!batch.isComplete) planner.release(batch)
            planner.cancel()
            admittedAtNanos.clear()
            throw cancelled
        } catch (failure: Throwable) {
            if (!batch.isComplete) planner.release(batch)
            planner.cancel()
            admittedAtNanos.clear()
            throw failure
        }
    }

    private fun nanosToMs(value: Long): Double = value.coerceAtLeast(0L) / 1_000_000.0
}

/**
 * Adapts page detections and the existing Paddle geometry into final leaves,
 * drains same-page buckets, and restores region text before the caller commits
 * the PageTranslation. No durable state is touched here.
 */
internal class PaddlePageOcrCoordinator(
    private val engine: PaddleOcrV6SmallEngine,
    internal val validatedBatchSize: PaddleOcrBatchSize = PaddleOcrBatchSize.B1,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    /** One guard per engine session; no output-buffer downgrade is concurrency design. */
    private val batchCallMutex = Mutex()
    private var nextTraceSequence = 0L

    @Volatile
    var lastBatchTrace: List<PaddlePageBatchTrace> = emptyList()
        private set

    @Volatile
    var lastResolvedLeafCount: Int = 0
        private set

    suspend fun recognizePage(
        pageGeneration: PaddleOcrPageGeneration,
        bitmap: Bitmap,
        detections: List<Detection>,
        paddleDet: PaddleOcrV6DetEngine?,
        language: TextRecognizerLanguage,
        isVerticalLanguage: Boolean,
        mode: PaddlePageOcrMode,
        isClosed: () -> Boolean,
    ): List<PaddlePageRegionResult> {
        val policy = PaddlePageOcrPolicy(mode, validatedBatchSize)
        val initialDispatcher = dispatcher(pageGeneration, policy)
        val states = ArrayList<RegionState>(detections.size)
        var fallbackDispatcher: PaddlePageOcrBatchDispatcher<Bitmap, Pair<String, Float>>? = null
        try {
            detections.forEachIndexed { regionIndex, detection ->
                check(!isClosed()) { "ONNX recognition engine closed during OCR geometry planning" }
                val state = planInitialRegion(
                    bitmap = bitmap,
                    regionIndex = regionIndex,
                    detection = detection,
                    paddleDet = paddleDet,
                    language = language,
                    isVerticalLanguage = isVerticalLanguage,
                    isClosed = isClosed,
                )
                submitPlan(
                    pageGeneration = pageGeneration,
                    regionIndex = regionIndex,
                    phase = "initial",
                    plan = state.initialPlan,
                    dispatcher = initialDispatcher,
                    isClosed = isClosed,
                ).also { state.initialBoundPlan = it }
                states += state
            }
            initialDispatcher.finish()
            val initialLeafCount = states.sumOf { it.initialBoundPlan?.identities?.size ?: 0 }
            check(initialDispatcher.resolvedLeafCount == initialLeafCount) {
                "Paddle initial page leaves unresolved: expected=$initialLeafCount " +
                    "resolved=${initialDispatcher.resolvedLeafCount} page=$pageGeneration"
            }
            val initialResults = initialDispatcher.results
            states.forEach { state ->
                state.text = compose(state.initialBoundPlan!!, initialResults, language)
            }

            val fallbackStates = states.filter { it.needsUnpaddedReread && it.text.isEmpty() }
            if (fallbackStates.isNotEmpty()) {
                fallbackDispatcher = dispatcher(pageGeneration, policy)
                fallbackStates.forEach { state ->
                    check(!isClosed()) { "ONNX recognition engine closed during Paddle fallback planning" }
                    // Match the existing sequential path: entering the
                    // unpadded reread marks the region as rotated even when
                    // the fallback still produces an empty string.
                    state.rotatedForOcr = true
                    val unpadded = VerticalLineOcr.cropBitmap(
                        bitmap,
                        state.bbox[0],
                        state.bbox[1],
                        state.bbox[2],
                        state.bbox[3],
                    )
                    val fallbackPlan = try {
                        VerticalLineOcr.planMultiLine(
                            crop = unpadded,
                            paddleDet = null,
                            verticalFallback = true,
                            language = language,
                            isClosed = isClosed,
                        )
                    } finally {
                        unpadded.recycle()
                    }
                    val bound = submitPlan(
                        pageGeneration = pageGeneration,
                        regionIndex = state.regionIndex,
                        phase = "unpad-reread",
                        plan = fallbackPlan,
                        dispatcher = fallbackDispatcher,
                        isClosed = isClosed,
                    )
                    state.fallbackBoundPlan = bound
                }
                fallbackDispatcher.finish()
                val fallbackLeafCount = fallbackStates.sumOf { it.fallbackBoundPlan?.identities?.size ?: 0 }
                check(fallbackDispatcher.resolvedLeafCount == fallbackLeafCount) {
                    "Paddle fallback page leaves unresolved: expected=$fallbackLeafCount " +
                        "resolved=${fallbackDispatcher.resolvedLeafCount} page=$pageGeneration"
                }
                val fallbackResults = fallbackDispatcher.results
                fallbackStates.forEach { state ->
                    val fallbackText = compose(state.fallbackBoundPlan!!, fallbackResults, language)
                    if (fallbackText.isNotEmpty()) {
                        state.text = fallbackText
                    }
                }
            }

            val traces = initialDispatcher.batchTraces + (fallbackDispatcher?.batchTraces ?: emptyList())
            lastBatchTrace = traces
            lastResolvedLeafCount = initialDispatcher.resolvedLeafCount + (fallbackDispatcher?.resolvedLeafCount ?: 0)
            return states.map { PaddlePageRegionResult(it.text, it.rotatedForOcr) }
        } catch (failure: Throwable) {
            initialDispatcher.cancel()
            fallbackDispatcher?.cancel()
            lastBatchTrace = emptyList()
            lastResolvedLeafCount = 0
            throw failure
        }
    }

    private fun dispatcher(
        pageGeneration: PaddleOcrPageGeneration,
        policy: PaddlePageOcrPolicy,
    ): PaddlePageOcrBatchDispatcher<Bitmap, Pair<String, Float>> = PaddlePageOcrBatchDispatcher(
        pageGeneration = pageGeneration,
        policy = policy,
        batchCallMutex = batchCallMutex,
        nowNanos = nowNanos,
        executorLatencyMs = { engine.lastBatchTelemetry?.latencyMs },
        traceSequence = {
            nextTraceSequence++
        },
        recognizeBatch = { crops, widthBucket, requestedBatchSize ->
            engine.recognizeBucketBatch(crops, widthBucket.paddedWidth, requestedBatchSize.value)
        },
    )

    private suspend fun planInitialRegion(
        bitmap: Bitmap,
        regionIndex: Int,
        detection: Detection,
        paddleDet: PaddleOcrV6DetEngine?,
        language: TextRecognizerLanguage,
        isVerticalLanguage: Boolean,
        isClosed: () -> Boolean,
    ): RegionState {
        val bbox = detection.bbox
        val pad = 12
        val padded = VerticalLineOcr.cropBitmap(bitmap, bbox[0] - pad, bbox[1] - pad, bbox[2] + pad, bbox[3] + pad)
        val boxWidth = (bbox[2] - bbox[0]).toFloat()
        val boxHeight = (bbox[3] - bbox[1]).toFloat()
        val tallVertical = isVerticalLanguage && boxHeight > boxWidth * 1.5f
        val paddleMultiLine = paddleDet != null
        val paddleVerticalHeuristic = isVerticalLanguage && paddleDet == null && boxHeight > boxWidth * 1.5f
        val plan = try {
            when {
                paddleMultiLine -> VerticalLineOcr.planMultiLine(
                    crop = padded,
                    paddleDet = paddleDet,
                    verticalFallback = tallVertical,
                    language = language,
                    isClosed = isClosed,
                )

                paddleVerticalHeuristic -> {
                    val unpadded = VerticalLineOcr.cropBitmap(bitmap, bbox[0], bbox[1], bbox[2], bbox[3])
                    try {
                        VerticalLineOcr.planMultiLine(
                            crop = unpadded,
                            paddleDet = null,
                            verticalFallback = true,
                            language = language,
                            isClosed = isClosed,
                        )
                    } finally {
                        unpadded.recycle()
                    }
                }

                else -> VerticalLineOcr.planMultiLine(
                    crop = padded,
                    paddleDet = null,
                    verticalFallback = false,
                    language = language,
                    isClosed = isClosed,
                )
            }
        } finally {
            padded.recycle()
        }
        return RegionState(
            regionIndex = regionIndex,
            bbox = bbox.copyOf(),
            initialPlan = plan,
            needsUnpaddedReread = paddleMultiLine && isVerticalLanguage && boxHeight > boxWidth,
            rotatedForOcr = paddleVerticalHeuristic || tallVertical,
        )
    }

    private suspend fun submitPlan(
        pageGeneration: PaddleOcrPageGeneration,
        regionIndex: Int,
        phase: String,
        plan: PaddleVerticalRecognitionPlan,
        dispatcher: PaddlePageOcrBatchDispatcher<Bitmap, Pair<String, Float>>,
        isClosed: () -> Boolean,
    ): BoundPlan {
        val identities = ArrayList<PaddleOcrLeafIdentity>(plan.leaves.size)
        var submitted = 0
        try {
            plan.leaves.forEachIndexed { leafIndex, planned ->
                check(!isClosed()) { "ONNX recognition engine closed before Paddle leaf submission" }
                val bucket = widthBucket(planned.crop)
                val work = PaddleOcrLeafWork(
                    pageGeneration = pageGeneration,
                    parentRegion = PaddleOcrParentRegion(regionIndex),
                    lineIndex = planned.lineIndex,
                    glyphIndex = planned.glyphIndex,
                    crop = planned.crop,
                    cropOwnership = PaddleOcrCropOwnership(
                        token = "${pageGeneration.pageId}:${pageGeneration.generation}:$regionIndex:$phase:$leafIndex",
                        onRelease = { planned.crop.recycle() },
                    ),
                    rotation = planned.rotation,
                    fallbackKind = planned.fallbackKind,
                    widthBucket = bucket,
                )
                try {
                    dispatcher.submit(work)
                    identities += work.identity
                    submitted++
                } catch (failure: Throwable) {
                    if (!work.cropOwnership.isReleased) work.releaseCropOwnership()
                    submitted = leafIndex + 1
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            plan.leaves.drop(submitted).forEach { it.crop.recycle() }
            throw failure
        }
        return BoundPlan(
            identities = identities,
            groups = plan.groups,
            separator = plan.separator,
            filterConfidence = plan.filterConfidence,
        )
    }

    private fun compose(
        plan: BoundPlan,
        results: Map<PaddleOcrLeafIdentity, Pair<String, Float>>,
        language: TextRecognizerLanguage,
    ): String = plan.groups.map { group ->
        group.mapNotNull { localIndex ->
            val result = results[plan.identities[localIndex]]
                ?: error("Paddle leaf result missing for ${plan.identities[localIndex]}")
            VerticalLineOcr.filterRecognizedText(result.first, result.second, language, plan.filterConfidence)
                .takeIf { it.isNotEmpty() }
        }.joinToString(plan.separator)
    }.filter { it.isNotEmpty() }.joinToString(plan.separator)

    private fun widthBucket(crop: Bitmap): PaddleOcrWidthBucket {
        val safeWidth = crop.width.coerceAtLeast(1)
        val safeHeight = crop.height.coerceAtLeast(1)
        val scaledWidth = ceil(safeWidth * (PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT.toFloat() / safeHeight))
            .toInt()
            .coerceIn(1, PaddleOcrV6SmallEngine.MAX_RECOGNITION_WIDTH)
        return PaddleOcrWidthBucket.forScaledWidth(scaledWidth)
    }

    private data class RegionState(
        val regionIndex: Int,
        val bbox: IntArray,
        val initialPlan: PaddleVerticalRecognitionPlan,
        val needsUnpaddedReread: Boolean,
        var rotatedForOcr: Boolean,
        var initialBoundPlan: BoundPlan? = null,
        var fallbackBoundPlan: BoundPlan? = null,
        var text: String = "",
    )

    private data class BoundPlan(
        val identities: List<PaddleOcrLeafIdentity>,
        val groups: List<List<Int>>,
        val separator: String,
        val filterConfidence: Boolean,
    )
}
