package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationBatchTrackerRegistryTest {
    @Test
    fun `normal completion caches terminal snapshot before disposing live tracker`() = runTest {
        val registry = TranslationBatchTrackerRegistry()
        val tracker = tracker(chapterId = 1, registry = registry)
        registry.replace(1, tracker)

        tracker.finish(ReconciliationResult(Translation.State.TRANSLATED, emptyMap(), emptySet(), 0, 0, 0, 0))
        runCurrent()

        registry.terminal.value.getValue(1).batchPhase shouldBe TranslationBatchPhase.FINISHED
        registry.getLive(1) shouldBe null
        registry.terminalSnapshotCacheSize() shouldBe 1
    }

    @Test
    fun `cancellation caches terminal snapshot before disposing live tracker`() = runTest {
        val registry = TranslationBatchTrackerRegistry()
        val tracker = tracker(chapterId = 2, registry = registry)
        registry.replace(2, tracker)

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

    private fun CoroutineScope.tracker(
        chapterId: Long,
        registry: TranslationBatchTrackerRegistry,
    ): TranslationBatchProgressTracker = TranslationBatchProgressTracker(
        chapterId,
        ChapterTranslationStore(null, null),
        emptyList(),
        this,
        onTerminalSnapshot = { snapshot -> registry.complete(chapterId, snapshot) },
    )

    private fun snapshot(chapterId: Long, state: Translation.State = Translation.State.TRANSLATED) =
        TranslationProgressSnapshot.empty(chapterId, state).copy(batchPhase = TranslationBatchPhase.FINISHED)
}
