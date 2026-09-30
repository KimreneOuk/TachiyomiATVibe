package eu.kanade.translation.persistence.journal

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterJournalCompactionTest {

    @Test
    fun `frontier merge keeps predecessor epochs and advances with reconstructed and live coverage`() {
        val predecessor = coverage(1, 1, acked = 5, closed = false)
        val reconstructed = coverage(1, 1, acked = 7, closed = false)
        val live = coverage(1, 1, acked = 6, closed = true)
        val otherEpoch = coverage(2, 2, acked = 0, closed = false)

        ChapterJournalCompaction.mergeFrontiers(
            listOf(predecessor),
            listOf(reconstructed, otherEpoch),
            listOf(live),
        ) shouldBe listOf(
            coverage(1, 1, acked = 7, closed = true),
            otherEpoch,
        )
    }

    @Test
    fun `defunct marker does not close a disk epoch while its writer remains live`() {
        val coverage = coverage(4, 9, acked = 12, closed = false)

        ChapterJournalCompaction.markDiskEpochsClosedOutsideLiveSet(
            scanned = listOf(coverage),
            liveWriterKeys = setOf(coverage.key),
        ) shouldBe listOf(coverage)
        ChapterJournalCompaction.markDiskEpochsClosedOutsideLiveSet(
            scanned = listOf(coverage),
            liveWriterKeys = emptySet(),
        ) shouldBe listOf(coverage.copy(terminallyClosed = true))
    }

    @Test
    fun `snapshot classification distinguishes writing valid and durable`() {
        ChapterJournalCompaction.classifySnapshot(trailerValid = false, durableMarkerValid = false) shouldBe
            ChapterJournalSnapshotState.WRITING
        ChapterJournalCompaction.classifySnapshot(trailerValid = false, durableMarkerValid = true) shouldBe
            ChapterJournalSnapshotState.WRITING
        ChapterJournalCompaction.classifySnapshot(trailerValid = true, durableMarkerValid = false) shouldBe
            ChapterJournalSnapshotState.VALID
        ChapterJournalCompaction.classifySnapshot(trailerValid = true, durableMarkerValid = true) shouldBe
            ChapterJournalSnapshotState.DURABLE
    }

    @Test
    fun `selection ignores writing snapshots and uses generation only for equal frontiers`() {
        val frontier = listOf(coverage(1, 4, acked = 7, closed = true))
        val selected = ChapterJournalCompaction.selectNewestUsable(
            listOf(
                snapshot(generation = 9, frontier = frontier, trailer = false, marker = false),
                snapshot(generation = 2, frontier = frontier, trailer = true, marker = false),
                snapshot(generation = 3, frontier = frontier, trailer = true, marker = true),
            ),
        )

        selected?.generation shouldBe 3L
        selected?.state shouldBe ChapterJournalSnapshotState.DURABLE
    }

    @Test
    fun `frontier extension dominates its prefix even before the new epoch has frames`() {
        val prefix = listOf(coverage(1, 4, acked = 7, closed = true))
        val extension = prefix + coverage(2, 5, acked = 0, closed = false)

        ChapterJournalCompaction.strictlyDominates(extension, prefix) shouldBe true
        ChapterJournalCompaction.strictlyDominates(prefix, extension) shouldBe false
        ChapterJournalCompaction.selectNewestUsable(
            listOf(
                snapshot(generation = 11, frontier = prefix, trailer = true, marker = true),
                snapshot(generation = 12, frontier = extension, trailer = true, marker = false),
            ),
        )?.generation shouldBe 12L
    }

    @Test
    fun `incomparable epoch vectors fail closed`() {
        val first = listOf(
            coverage(1, 4, acked = 8, closed = true),
            coverage(2, 5, acked = 3, closed = false),
        )
        val second = listOf(
            coverage(1, 4, acked = 7, closed = true),
            coverage(2, 5, acked = 4, closed = false),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChapterJournalCompaction.strictlyDominates(first, second)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterJournalCompaction.selectNewestUsable(
                listOf(
                    snapshot(generation = 1, frontier = first, trailer = true, marker = true),
                    snapshot(generation = 2, frontier = second, trailer = true, marker = true),
                ),
            )
        }
    }

    @Test
    fun `epoch gc requires durable coverage terminal closure and a covered watermark`() {
        val covered = coverage(7, 12, acked = 9, closed = true)
        val durable = snapshot(generation = 3, frontier = listOf(covered), trailer = true, marker = true)
        val validOnly = snapshot(generation = 4, frontier = listOf(covered), trailer = true, marker = false)

        ChapterJournalCompaction.mayDeleteEpoch(durable, epoch(7, 12, acked = 9, closed = true)) shouldBe true
        ChapterJournalCompaction.mayDeleteEpoch(
            durable,
            epoch(7, 12, acked = 9, closed = true, verified = false),
        ) shouldBe false
        ChapterJournalCompaction.mayDeleteEpoch(durable, epoch(7, 12, acked = 10, closed = true)) shouldBe false
        ChapterJournalCompaction.mayDeleteEpoch(durable, epoch(7, 12, acked = 9, closed = false)) shouldBe false
        ChapterJournalCompaction.mayDeleteEpoch(validOnly, epoch(7, 12, acked = 9, closed = true)) shouldBe false
        ChapterJournalCompaction.mayDeleteEpoch(durable, epoch(7, 13, acked = 1, closed = true)) shouldBe false
    }

    @Test
    fun `snapshot pruning only permits generations older than the newest durable snapshot`() {
        ChapterJournalCompaction.mayPruneSnapshot(
            snapshot(generation = 4, frontier = emptyList(), trailer = true, marker = false),
            newestDurableGeneration = 5,
        ) shouldBe true
        ChapterJournalCompaction.mayPruneSnapshot(
            snapshot(generation = 5, frontier = emptyList(), trailer = true, marker = false),
            newestDurableGeneration = 5,
        ) shouldBe false
        ChapterJournalCompaction.mayPruneSnapshot(
            snapshot(generation = 6, frontier = emptyList(), trailer = true, marker = false),
            newestDurableGeneration = 5,
        ) shouldBe false
    }

    private fun coverage(
        storeGeneration: Long,
        epochOrdinal: Long,
        acked: Long,
        closed: Boolean,
    ) = ChapterJournalEpochCoverage(storeGeneration, epochOrdinal, acked, closed)

    private fun snapshot(
        generation: Long,
        frontier: List<ChapterJournalEpochCoverage>,
        trailer: Boolean,
        marker: Boolean,
    ) = ChapterJournalSnapshotCandidate(generation, frontier, trailer, marker)

    private fun epoch(
        storeGeneration: Long,
        epochOrdinal: Long,
        acked: Long,
        closed: Boolean,
        verified: Boolean = true,
    ) = ChapterJournalEpochGcState(
        key = ChapterJournalEpochKey(storeGeneration, epochOrdinal),
        ackedFrameSeq = acked,
        terminallyClosed = closed,
        coverageVerified = verified,
    )
}
