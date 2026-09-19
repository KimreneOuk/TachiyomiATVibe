package eu.kanade.translation.ocr.paddle.batch

import java.util.ArrayDeque
import java.util.LinkedHashMap

enum class PaddleOcrBatchSize(val value: Int) {
    B1(1),
    B2(2),
    B4(4),
    B8(8),
}

class PaddleOcrBatchWindowFullException(
    val bucket: PaddleOcrWidthBucket,
    val maximumLeaves: Int,
) : IllegalStateException(
    "${bucket.name} leaf window is full (maximum=$maximumLeaves)",
)

/** One same-page, one-width recognizer invocation worth of final leaves. */
class PaddleOcrBatch<CROP> internal constructor(
    val pageGeneration: PaddleOcrPageGeneration,
    val widthBucket: PaddleOcrWidthBucket,
    val sequence: Long,
    leaves: List<PaddleOcrLeafWork<CROP>>,
) {
    val leaves: List<PaddleOcrLeafWork<CROP>> = leaves.toList()
    val size: Int
        get() = leaves.size

    @Volatile
    private var completed = false

    init {
        require(leaves.isNotEmpty()) { "a batch must contain at least one leaf" }
        require(leaves.all { it.pageGeneration == pageGeneration }) {
            "a batch cannot contain leaves from another page or generation"
        }
        require(leaves.all { it.widthBucket == widthBucket }) {
            "a batch cannot contain more than one width bucket"
        }
    }

    val isComplete: Boolean
        get() = completed

    /** Maps results in input order, then releases every crop ownership token. */
    fun <RESULT> mapResults(
        results: List<RESULT>,
        mapper: (PaddleOcrLeafWork<CROP>, RESULT) -> Unit,
    ) {
        check(!completed) { "batch $sequence has already been completed" }
        require(results.size == leaves.size) {
            "batch $sequence expected ${leaves.size} results, got ${results.size}"
        }
        try {
            leaves.indices.forEach { index ->
                mapper(leaves[index], results[index])
            }
        } finally {
            try {
                leaves.forEach { it.releaseCropOwnership() }
            } finally {
                completed = true
            }
        }
    }

    /** Releases crops when execution is cancelled before result mapping. */
    fun releaseWithoutMapping() {
        check(!completed) { "batch $sequence has already been completed" }
        try {
            leaves.forEach { it.releaseCropOwnership() }
        } finally {
            completed = true
        }
    }
}

/**
 * Page-scoped streaming planner. Full batches are returned as soon as a bucket fills;
 * [finishPage] emits each remaining partial batch without waiting for another page.
 */
class PaddleOcrBatchPlanner<CROP>(
    val pageGeneration: PaddleOcrPageGeneration,
    val batchSize: PaddleOcrBatchSize,
) {
    val maxInFlightLeavesPerBucket: Int = batchSize.value * 2

    private val pending = PaddleOcrWidthBucket.values().associateWith {
        ArrayDeque<PaddleOcrLeafWork<CROP>>()
    }
    private val inFlight = LinkedHashMap<Long, PaddleOcrBatch<CROP>>()
    private val admittedIdentities = HashSet<PaddleOcrLeafIdentity>()
    private var nextSequence = 0L
    private var pageFinished = false

    /**
     * Admits one final leaf. A non-null return means that bucket just reached B and
     * can be submitted to the Paddle adapter immediately.
     */
    fun admit(leaf: PaddleOcrLeafWork<CROP>): PaddleOcrBatch<CROP>? {
        check(!pageFinished) { "page $pageGeneration has already been finished" }
        require(leaf.pageGeneration == pageGeneration) {
            "leaf ${leaf.identity} does not belong to planner page $pageGeneration"
        }
        require(admittedIdentities.add(leaf.identity)) {
            "duplicate leaf identity ${leaf.identity}"
        }

        val bucket = leaf.widthBucket
        if (inFlightLeafCount(bucket) >= maxInFlightLeavesPerBucket) {
            admittedIdentities.remove(leaf.identity)
            throw PaddleOcrBatchWindowFullException(bucket, maxInFlightLeavesPerBucket)
        }

        val queue = pending.getValue(bucket)
        queue.addLast(leaf)
        return if (queue.size == batchSize.value) {
            emit(bucket)
        } else {
            null
        }
    }

    /** Emits the not-yet-full bucket queues and prevents any later-page admission. */
    fun finishPage(): List<PaddleOcrBatch<CROP>> {
        check(!pageFinished) { "page $pageGeneration has already been finished" }
        pageFinished = true
        return PaddleOcrWidthBucket.values().flatMap { bucket ->
            if (pending.getValue(bucket).isEmpty()) {
                emptyList()
            } else {
                listOf(emit(bucket))
            }
        }
    }

    /** Completes a batch after its result rows have been mapped back to leaves. */
    fun <RESULT> complete(
        batch: PaddleOcrBatch<CROP>,
        results: List<RESULT>,
        mapper: (PaddleOcrLeafWork<CROP>, RESULT) -> Unit,
    ) {
        require(inFlight[batch.sequence] === batch) {
            "batch ${batch.sequence} is not in flight for planner page $pageGeneration"
        }
        try {
            batch.mapResults(results, mapper)
        } finally {
            if (batch.isComplete) {
                inFlight.remove(batch.sequence)
            }
        }
    }

    /** Releases a submitted batch when cancellation or execution failure skips mapping. */
    fun release(batch: PaddleOcrBatch<CROP>) {
        require(inFlight[batch.sequence] === batch) {
            "batch ${batch.sequence} is not in flight for planner page $pageGeneration"
        }
        try {
            batch.releaseWithoutMapping()
        } finally {
            if (batch.isComplete) {
                inFlight.remove(batch.sequence)
            }
        }
    }

    /** Releases all pending and submitted crops and closes this page generation. */
    fun cancel() {
        pending.values.forEach { queue ->
            queue.forEach { it.releaseCropOwnership() }
            queue.clear()
        }
        inFlight.values.toList().forEach { batch ->
            if (!batch.isComplete) batch.releaseWithoutMapping()
        }
        inFlight.clear()
        pageFinished = true
    }

    fun pendingLeafCount(bucket: PaddleOcrWidthBucket): Int = pending.getValue(bucket).size

    fun inFlightLeafCount(bucket: PaddleOcrWidthBucket): Int {
        var count = pending.getValue(bucket).size
        inFlight.values.forEach { batch ->
            if (batch.widthBucket == bucket) count += batch.size
        }
        return count
    }

    val activeBatchCount: Int
        get() = inFlight.size

    val isFinished: Boolean
        get() = pageFinished

    val isDrained: Boolean
        get() = pageFinished && inFlight.isEmpty() && pending.values.all { it.isEmpty() }

    private fun emit(bucket: PaddleOcrWidthBucket): PaddleOcrBatch<CROP> {
        val queue = pending.getValue(bucket)
        val leaves = ArrayList<PaddleOcrLeafWork<CROP>>(batchSize.value)
        while (queue.isNotEmpty() && leaves.size < batchSize.value) {
            leaves += queue.removeFirst()
        }
        return PaddleOcrBatch(
            pageGeneration = pageGeneration,
            widthBucket = bucket,
            sequence = nextSequence++,
            leaves = leaves,
        ).also { inFlight[it.sequence] = it }
    }
}
