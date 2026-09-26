package eu.kanade.translation.workflow

import eu.kanade.translation.model.Translation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Covers same-source conflicts and stale queue eviction used by
 * [TranslationManager.translateChapterPreflight].
 *
 * Artifact reuse for re-queued work is covered by
 * [eu.kanade.translation.pipeline.batch.BatchResumeGateDeciderTest].
 */
class ChapterQueueConflictDetectionTest {

    private fun view(chapterId: Long, sourceId: Long, status: Translation.State, name: String = "c$chapterId") =
        QueuedChapterView(chapterId, name, sourceId, status)

    @Test
    fun `findRunningSameSourceConflict returns the actively translating chapter of the same source`() {
        val queue = listOf(
            view(chapterId = 1, sourceId = 10, status = Translation.State.TRANSLATING),
            view(chapterId = 2, sourceId = 10, status = Translation.State.QUEUE),
            view(chapterId = 3, sourceId = 11, status = Translation.State.TRANSLATING),
        )
        val conflict = findRunningSameSourceConflict(queue, requestedChapterId = 2, sourceId = 10)
        conflict?.chapterId shouldBe 1L
    }

    @Test
    fun `findRunningSameSourceConflict ignores stale QUEUE entries`() {
        // A queued (not running) chapter of the same source is NOT a conflict:
        // it is evicted automatically without user confirmation.
        val queue = listOf(
            view(chapterId = 1, sourceId = 10, status = Translation.State.QUEUE),
        )
        findRunningSameSourceConflict(queue, requestedChapterId = 2, sourceId = 10) shouldBe null
    }

    @Test
    fun `findRunningSameSourceConflict ignores the requested chapter itself`() {
        val queue = listOf(
            view(chapterId = 1, sourceId = 10, status = Translation.State.TRANSLATING),
        )
        findRunningSameSourceConflict(queue, requestedChapterId = 1, sourceId = 10) shouldBe null
    }

    @Test
    fun `findRunningSameSourceConflict ignores other sources`() {
        val queue = listOf(
            view(chapterId = 1, sourceId = 99, status = Translation.State.TRANSLATING),
        )
        findRunningSameSourceConflict(queue, requestedChapterId = 2, sourceId = 10) shouldBe null
    }

    @Test
    fun `staleQueuedChaptersToEvict returns every QUEUE entry of the same source except the keeper`() {
        val queue = listOf(
            view(chapterId = 1, sourceId = 10, status = Translation.State.QUEUE),
            view(chapterId = 2, sourceId = 10, status = Translation.State.QUEUE),
            view(chapterId = 3, sourceId = 10, status = Translation.State.TRANSLATING),
            view(chapterId = 4, sourceId = 11, status = Translation.State.QUEUE),
        )
        val stale = staleQueuedChaptersToEvict(queue, keepChapterId = 99, sourceId = 10)
        stale.map { it.chapterId } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `staleQueuedChaptersToEvict keeps the keeper chapter`() {
        val queue = listOf(
            view(chapterId = 1, sourceId = 10, status = Translation.State.QUEUE),
        )
        val stale = staleQueuedChaptersToEvict(queue, keepChapterId = 1, sourceId = 10)
        stale shouldHaveSize 0
    }

    @Test
    fun `staleQueuedChaptersToEvict ignores running chapters of the same source`() {
        // A running chapter is reported by findRunningSameSourceConflict and
        // handled via cancelRunningChapterForReplace; it must NOT also be
        // silently evicted, because evicting it would lose its in-flight work
        // without confirmation.
        val queue = listOf(
            view(chapterId = 1, sourceId = 10, status = Translation.State.TRANSLATING),
        )
        val stale = staleQueuedChaptersToEvict(queue, keepChapterId = 2, sourceId = 10)
        stale shouldHaveSize 0
    }
}
