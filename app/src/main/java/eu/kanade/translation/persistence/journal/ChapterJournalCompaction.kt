package eu.kanade.translation.persistence.journal

import kotlinx.serialization.Serializable

/** Durable coverage for one writer epoch. Frame numbers are local to that epoch. */
@Serializable
internal data class ChapterJournalEpochCoverage(
    val storeGeneration: Long,
    val epochOrdinal: Long,
    val ackedFrameSeq: Long,
    val terminallyClosed: Boolean,
) {
    init {
        require(storeGeneration >= 0L) { "storeGeneration must be non-negative" }
        require(epochOrdinal >= 0L) { "epochOrdinal must be non-negative" }
        require(ackedFrameSeq >= 0L) { "ackedFrameSeq must be non-negative" }
    }

    val key: ChapterJournalEpochKey get() = ChapterJournalEpochKey(storeGeneration, epochOrdinal)
}

internal data class ChapterJournalEpochKey(
    val storeGeneration: Long,
    val epochOrdinal: Long,
)

private val EPOCH_ORDER = compareBy<ChapterJournalEpochCoverage>({ it.storeGeneration }, { it.epochOrdinal })

internal enum class ChapterJournalSnapshotState {
    WRITING,
    VALID,
    DURABLE,
}

/** File-derived snapshot metadata used by selection and GC authorization. */
internal data class ChapterJournalSnapshotCandidate(
    val generation: Long,
    val frontier: List<ChapterJournalEpochCoverage>,
    val trailerValid: Boolean,
    val durableMarkerValid: Boolean,
) {
    init {
        require(generation >= 0L) { "snapshot generation must be non-negative" }
        require(frontier.map { it.key }.distinct().size == frontier.size) {
            "snapshot frontier cannot contain duplicate epochs"
        }
        require(frontier == frontier.sortedWith(EPOCH_ORDER)) {
            "snapshot frontier must be sorted by store generation and epoch ordinal"
        }
    }

    val state: ChapterJournalSnapshotState
        get() = ChapterJournalCompaction.classifySnapshot(trailerValid, durableMarkerValid)
}

/** Current epoch state for GC; the watermark must be fresh before it can authorize deletion. */
internal data class ChapterJournalEpochGcState(
    val key: ChapterJournalEpochKey,
    val ackedFrameSeq: Long,
    val terminallyClosed: Boolean,
    /** The current watermark came from a CRC scan or this process's writer registry. */
    val coverageVerified: Boolean = false,
) {
    init {
        require(ackedFrameSeq >= 0L) { "ackedFrameSeq must be non-negative" }
    }
}

/** Stateless snapshot ordering and garbage-collection rules shared by writer and tests. */
internal object ChapterJournalCompaction {
    /**
     * Combines durable predecessor metadata, a read-only disk scan, and current writer watermarks.
     * Watermarks only advance; a terminal-close fact is monotonic for one epoch.
     */
    fun mergeFrontiers(vararg frontiers: Collection<ChapterJournalEpochCoverage>): List<ChapterJournalEpochCoverage> {
        val merged = linkedMapOf<ChapterJournalEpochKey, ChapterJournalEpochCoverage>()
        frontiers.asList().flatten().forEach { coverage ->
            val prior = merged[coverage.key]
            merged[coverage.key] = if (prior == null) {
                coverage
            } else {
                coverage.copy(
                    ackedFrameSeq = maxOf(prior.ackedFrameSeq, coverage.ackedFrameSeq),
                    terminallyClosed = prior.terminallyClosed || coverage.terminallyClosed,
                )
            }
        }
        return merged.values.sortedWith(EPOCH_ORDER)
    }

    /**
     * A disk epoch absent from the strong live-writer set belongs to a completed or dead session:
     * epoch-per-session forbids reattachment. A live epoch stays open even if its prefix contains
     * DEFUNCT, because already-credited appends may follow that boundary.
     */
    fun markDiskEpochsClosedOutsideLiveSet(
        scanned: Collection<ChapterJournalEpochCoverage>,
        liveWriterKeys: Set<ChapterJournalEpochKey>,
    ): List<ChapterJournalEpochCoverage> = scanned.map { coverage ->
        if (coverage.key in liveWriterKeys) {
            coverage.copy(terminallyClosed = false)
        } else {
            coverage.copy(terminallyClosed = true)
        }
    }

    /**
     * Build GC inputs from the merged snapshot frontier without mistaking inherited predecessor
     * watermarks for fresh knowledge of an epoch that still exists on disk. Unknown epochs fail
     * closed: they remain in the frontier for monotonicity, but cannot authorize deletion.
     */
    fun gcStatesAtCapture(
        frontier: Collection<ChapterJournalEpochCoverage>,
        verifiedCoverageKeys: Set<ChapterJournalEpochKey>,
    ): List<ChapterJournalEpochGcState> = frontier.map { coverage ->
        ChapterJournalEpochGcState(
            key = coverage.key,
            ackedFrameSeq = coverage.ackedFrameSeq,
            terminallyClosed = coverage.terminallyClosed,
            coverageVerified = coverage.key in verifiedCoverageKeys,
        )
    }

    fun classifySnapshot(trailerValid: Boolean, durableMarkerValid: Boolean): ChapterJournalSnapshotState =
        when {
            !trailerValid -> ChapterJournalSnapshotState.WRITING
            durableMarkerValid -> ChapterJournalSnapshotState.DURABLE
            else -> ChapterJournalSnapshotState.VALID
        }

    /**
     * Pick the greatest usable epoch-vector frontier, using generation only for equal vectors.
     * Incomparable frontiers cannot be produced by the serialized chapter barrier and fail closed.
     */
    fun selectNewestUsable(candidates: Collection<ChapterJournalSnapshotCandidate>): ChapterJournalSnapshotCandidate? {
        val usable = candidates.filter { it.state != ChapterJournalSnapshotState.WRITING }
        if (usable.isEmpty()) return null

        var selected = usable.first()
        for (candidate in usable.drop(1)) {
            when (compareFrontiers(candidate.frontier, selected.frontier)) {
                1 -> selected = candidate
                0 -> if (candidate.generation > selected.generation) selected = candidate
                -1 -> Unit
                else -> error("unreachable frontier ordering")
            }
        }
        return selected
    }

    /** Strict vector monotonicity required before publishing the next snapshot generation. */
    fun strictlyDominates(newer: List<ChapterJournalEpochCoverage>, older: List<ChapterJournalEpochCoverage>): Boolean =
        compareFrontiers(newer, older) > 0

    /**
     * A snapshot may authorize deletion only after its durable marker is valid, the epoch writer is
     * terminally closed, and the snapshot covers every frame acknowledged by that epoch.
     */
    fun mayDeleteEpoch(
        snapshot: ChapterJournalSnapshotCandidate,
        epoch: ChapterJournalEpochGcState,
    ): Boolean {
        if (snapshot.state != ChapterJournalSnapshotState.DURABLE || !epoch.terminallyClosed || !epoch.coverageVerified) {
            return false
        }
        val covered = snapshot.frontier.firstOrNull { it.key == epoch.key } ?: return false
        return epoch.ackedFrameSeq <= covered.ackedFrameSeq
    }

    /** An older snapshot may be pruned only after a newer durable snapshot exists. */
    fun mayPruneSnapshot(snapshot: ChapterJournalSnapshotCandidate, newestDurableGeneration: Long): Boolean =
        snapshot.generation < newestDurableGeneration

    private fun compareFrontiers(
        first: List<ChapterJournalEpochCoverage>,
        second: List<ChapterJournalEpochCoverage>,
    ): Int {
        val firstByEpoch = first.associateBy { it.key }
        val secondByEpoch = second.associateBy { it.key }
        require(firstByEpoch.size == first.size && secondByEpoch.size == second.size) {
            "frontiers cannot contain duplicate epochs"
        }

        var firstAtLeast = true
        var secondAtLeast = true
        var equal = true
        for (key in firstByEpoch.keys + secondByEpoch.keys) {
            val left = firstByEpoch[key]
            val right = secondByEpoch[key]
            // A newly-created epoch is a vector extension, even before its first frame. Do not
            // collapse it to a synthetic zero: E18 defines extension as later than its prefix.
            if (left == null || right == null) {
                if (left == null) firstAtLeast = false
                if (right == null) secondAtLeast = false
                equal = false
                continue
            }
            val leftDominates = left.ackedFrameSeq >= right.ackedFrameSeq &&
                (left.terminallyClosed || !right.terminallyClosed)
            val rightDominates = right.ackedFrameSeq >= left.ackedFrameSeq &&
                (right.terminallyClosed || !left.terminallyClosed)
            firstAtLeast = firstAtLeast && leftDominates
            secondAtLeast = secondAtLeast && rightDominates
            equal = equal &&
                left.ackedFrameSeq == right.ackedFrameSeq &&
                left.terminallyClosed == right.terminallyClosed
        }

        return when {
            equal -> 0
            firstAtLeast -> 1
            secondAtLeast -> -1
            else -> throw IllegalArgumentException("snapshot frontiers are incomparable")
        }
    }
}
