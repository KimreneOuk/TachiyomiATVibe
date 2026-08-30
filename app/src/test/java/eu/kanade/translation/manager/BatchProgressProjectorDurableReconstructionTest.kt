package eu.kanade.translation.manager

import eu.kanade.translation.ActiveChapterStoreRegistry
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistry
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * T911 slice 3 (contract item 4): when the bounded tracker registry misses
 * (process death / 20-entry eviction) and no queue owner exists, the
 * projection reconstructs a completed/failed chapter's terminal detail from
 * the durable store/artifacts — read-through only: no store is created and no
 * new cache is added.
 */
class BatchProgressProjectorDurableReconstructionTest {

    private val chapterId = 5L

    private fun reconstructedTerminal() = TranslationProgressSnapshot.empty(chapterId, Translation.State.TRANSLATED)
        .copy(
            totalPages = 3,
            donePages = 3,
            batchPhase = TranslationBatchPhase.FINISHED,
        )

    private fun projector(
        durableSnapshot: suspend (Long) -> TranslationProgressSnapshot?,
        openOrCreateInvocations: MutableList<Long>,
        registry: TranslationBatchTrackerRegistry = TranslationBatchTrackerRegistry(),
    ): BatchProgressProjector = BatchProgressProjector(
        activeStoresProvider = { ActiveChapterStoreRegistry() },
        batchTrackerRegistryProvider = { registry },
        queueStateProvider = { MutableStateFlow(emptyList()) },
        pendingTranslationRequestsProvider = { MutableStateFlow(emptyMap()) },
        pipelineProvider = { mockk<TranslationPipeline>(relaxed = true) },
        getQueuedTranslationOrNull = { null },
        persistedChapterStatus = { _, _, _, _, _ -> null },
        openOrCreateStoreSuspend = { id, _, _, _, _, _ ->
            openOrCreateInvocations.add(id)
            null
        },
        observeActiveDisplayStore = { null },
        reconstructDurableTerminalSnapshot = durableSnapshot,
    )

    @Test
    fun `registry miss with no queue owner reconstructs the terminal snapshot from the durable store`() = runTest {
        val openOrCreateInvocations = mutableListOf<Long>()
        val projector = projector(
            durableSnapshot = { id -> if (id == chapterId) reconstructedTerminal() else null },
            openOrCreateInvocations,
        )

        val snapshot = projector.observeBatchProgress(chapterId).first()

        // Row/drawer see the reconstructed terminal detail, not an empty 0/0.
        snapshot.totalPages shouldBe 3
        snapshot.donePages shouldBe 3
        snapshot.state shouldBe Translation.State.TRANSLATED
        snapshot.batchPhase shouldBe TranslationBatchPhase.FINISHED
        // Read-through is read-only: the creating open-or-create path never ran.
        openOrCreateInvocations shouldBe emptyList()
    }

    @Test
    fun `falls back to the empty snapshot when nothing durable is reconstructible`() = runTest {
        val openOrCreateInvocations = mutableListOf<Long>()
        val projector = projector(
            durableSnapshot = { null },
            openOrCreateInvocations,
        )

        val snapshot = projector.observeBatchProgress(chapterId).first()

        snapshot.totalPages shouldBe 0
        snapshot.state shouldBe Translation.State.NOT_TRANSLATED
        openOrCreateInvocations shouldBe emptyList()
    }

    @Test
    fun `a live tracker still wins over durable reconstruction`() = runTest {
        val openOrCreateInvocations = mutableListOf<Long>()
        val registry = TranslationBatchTrackerRegistry()
        val store = ChapterTranslationStore(null, null)
        registry.createTracker(
            chapterId = chapterId,
            store = store,
            orderedPageKeys = listOf("001.jpg", "002.jpg"),
            scope = backgroundScope,
        )
        var reconstructionAttempted = false
        val projector = projector(
            durableSnapshot = {
                reconstructionAttempted = true
                null
            },
            openOrCreateInvocations,
            registry,
        )

        val snapshot = projector.observeBatchProgress(chapterId).first()

        snapshot.totalPages shouldBe 2
        reconstructionAttempted shouldBe false
        openOrCreateInvocations shouldBe emptyList()
    }

    @Test
    fun `only completed failed warned and paused durable states are reconstructible`() {
        isReconstructibleDurableState(Translation.State.TRANSLATED) shouldBe true
        isReconstructibleDurableState(Translation.State.READY_WITH_WARNINGS) shouldBe true
        isReconstructibleDurableState(Translation.State.ERROR) shouldBe true
        isReconstructibleDurableState(Translation.State.PAUSED) shouldBe true
        // Live-looking states must never be reconstructed as if they were running.
        isReconstructibleDurableState(Translation.State.QUEUE) shouldBe false
        isReconstructibleDurableState(Translation.State.TRANSLATING) shouldBe false
        isReconstructibleDurableState(Translation.State.NOT_TRANSLATED) shouldBe false
        isReconstructibleDurableState(null) shouldBe false
    }

    // ------------------------------------- durable resolver read-through seam --

    private fun resolver(
        activeStores: ActiveChapterStoreRegistry,
        source: Source?,
        translationFileExists: Boolean,
    ): DurableChapterStatusResolver {
        val sourceManager = mockk<tachiyomi.domain.source.service.SourceManager>()
        every { sourceManager.get(any()) } returns source
        return DurableChapterStatusResolver(
            providerProvider = {
                mockk(relaxed = true) {
                    every { findTranslationFile(any(), any(), any(), any()) } returns mockk {
                        every { parentFile } returns mockk(relaxed = true)
                        every { name } returns "chapter.json"
                        every { exists() } returns translationFileExists
                    }
                }
            },
            sourceManagerProvider = { sourceManager },
            activeStoresProvider = { activeStores },
            durableStatusCacheProvider = { ConcurrentHashMap() },
        )
    }

    @Test
    fun `withDurableStore returns null when the source cannot be resolved`() = runTest {
        val resolver = resolver(activeStores = ActiveChapterStoreRegistry(), source = null, translationFileExists = false)

        val result = resolver.withDurableStore(chapterId, "Chapter 1", null, "fixture", 1L) { it }

        result shouldBe null
    }

    @Test
    fun `withDurableStore prefers the active store and never opens a probe`() = runTest {
        val activeStores = ActiveChapterStoreRegistry()
        val activeStore = ChapterTranslationStore(null, null)
        activeStores.register(chapterId, activeStore)
        val resolver = resolver(activeStores, source = mockk<Source>(relaxed = true), translationFileExists = true)

        val result = resolver.withDurableStore(chapterId, "Chapter 1", null, "fixture", 1L) { store -> store }

        result shouldBe activeStore
    }
}
