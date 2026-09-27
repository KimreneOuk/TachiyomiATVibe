package eu.kanade.translation.pipeline.batch.progress

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationBatchTrackerRegistryTest {
    @Test
    fun `normal completion caches terminal snapshot before disposing live tracker`() = runTest {
        val registry = TranslationBatchTrackerRegistry()
        val tracker = registry.createTracker(1, ChapterTranslationStore(null, null), emptyList(), this)

        tracker.finish(ReconciliationResult(Translation.State.TRANSLATED, emptyMap(), emptySet(), 0, 0, 0))
        runCurrent()

        registry.terminal.value.getValue(1).batchPhase shouldBe TranslationBatchPhase.FINISHED
        registry.getLive(1) shouldBe null
        registry.terminalSnapshotCacheSize() shouldBe 1
    }

    @Test
    fun `cancellation caches terminal snapshot before disposing live tracker`() = runTest {
        val registry = TranslationBatchTrackerRegistry()
        val tracker = registry.createTracker(2, ChapterTranslationStore(null, null), emptyList(), this)

        tracker.abort(emptySet(), "Cancelled")
        runCurrent()

        registry.terminal.value.getValue(2).aborted shouldBe true
        registry.terminal.value.getValue(2).abortedReason shouldBe "Cancelled"
        registry.getLive(2) shouldBe null
    }

    @Test
    fun `terminal cache is access ordered bounded and detached from source snapshot`() = runTest {
        val registry = TranslationBatchTrackerRegistry()
        (1L..20L).forEach { registry.complete(it, snapshot(it)) }
        // Touch chapter 1, making chapter 2 the least recently used entry.
        registry.terminalSnapshot(1)
        registry.complete(21, snapshot(21))

        registry.terminalSnapshotCacheSize() shouldBe 20
        registry.terminal.value.containsKey(1) shouldBe true
        registry.terminal.value.containsKey(2) shouldBe false
        registry.terminal.value.containsKey(21) shouldBe true

        val sourcePages = mutableListOf(
            TranslationProgressSnapshot.Page("page-1", 1, TranslationProgressStage.DONE),
        )
        val sourceFailures = mutableMapOf("failure" to mutableListOf("page-1"))
        val source = snapshot(99).copy(pages = sourcePages, groupedFailures = sourceFailures)
        registry.complete(99, source)
        sourcePages += TranslationProgressSnapshot.Page("page-2", 2, TranslationProgressStage.FAILED)
        sourceFailures.getValue("failure") += "page-2"

        registry.terminalSnapshot(99)!!.pages.size shouldBe 1
        registry.terminalSnapshot(99)!!.groupedFailures.getValue("failure") shouldBe listOf("page-1")
    }

    @Test
    fun `late terminal from replaced tracker cannot close or cache over newer owner`() = runTest {
        val registry = TranslationBatchTrackerRegistry()
        val old = registry.createTracker(7, ChapterTranslationStore(null, null), emptyList(), this)
        // Queue a real terminal event before replacement. The tracker keeps
        // that accepted event drainable after close so the production callback
        // runs against the newer registry owner and is identity-rejected.
        old.finish(ReconciliationResult(Translation.State.TRANSLATED, emptyMap(), emptySet(), 0, 0, 0))
        val newer = registry.createTracker(7, ChapterTranslationStore(null, null), emptyList(), this)
        runCurrent()

        registry.getLive(7) shouldBe newer
        registry.terminalSnapshot(7) shouldBe null

        newer.finish(ReconciliationResult(Translation.State.TRANSLATED, emptyMap(), emptySet(), 0, 0, 0))
        runCurrent()
        registry.getLive(7) shouldBe null
        registry.terminalSnapshot(7)!!.batchPhase shouldBe TranslationBatchPhase.FINISHED
    }

    private fun snapshot(chapterId: Long, state: Translation.State = Translation.State.TRANSLATED) =
        TranslationProgressSnapshot.empty(chapterId, state).copy(batchPhase = TranslationBatchPhase.FINISHED)
}
