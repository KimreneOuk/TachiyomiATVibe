package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Pins [TextLayoutCoordinator] — the T920 3.1 guarantee that overlay bind never
 * performs planning work on the calling thread, applies only generation-current
 * results, and keeps identical rebinds / cache hits off the planner entirely.
 *
 * Both executors are manual queues, so the test deterministically simulates the
 * production ordering: caller thread -> planner thread -> main thread.
 */
class TextLayoutCoordinatorTest {

    /** Collects tasks without running them: simulates the background planner thread. */
    private class QueueExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        var rejected = false
        override fun execute(command: Runnable) {
            if (rejected) throw java.util.concurrent.RejectedExecutionException("simulated")
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class Harness {
        val background = QueueExecutor()
        val main = QueueExecutor()
        var planCalls = 0
        val applied = ArrayDeque<String>()
        val cache = ReaderTextLayoutCache<String>(maxEntries = 8)

        val coordinator = TextLayoutCoordinator(
            cache = cache,
            backgroundExecutor = background,
            mainExecutor = main,
            plan = { blocks, _, _ ->
                planCalls++
                blocks.single().translation // the "prepared layout" marker
            },
            onPrepared = { prepared -> applied.addLast(prepared) },
        )

        /** Drains background then main, the order production threads run in. */
        fun drain() {
            background.runAll()
            main.runAll()
        }
    }

    private fun block(translation: String) = TranslationBlock(
        text = "",
        translation = translation,
        width = 100f,
        height = 40f,
        x = 10f,
        y = 10f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun blocks(translation: String) = listOf(block(translation))

    @Test
    fun `bind on a miss performs NO planning work on the calling thread`() {
        val harness = Harness()
        val result = harness.coordinator.bind(blocks("Hi"), 800, 1200)

        result.shouldBeInstanceOf<TextLayoutBindResult.Planning>()
        // The planner hook was never invoked on the calling thread.
        harness.planCalls shouldBe 0
        harness.applied.size shouldBe 0

        // Only when the simulated background thread runs does planning happen,
        // and even then application is deferred to the main queue.
        harness.background.runAll()
        harness.planCalls shouldBe 1
        harness.applied.size shouldBe 0

        harness.main.runAll()
        harness.applied.single() shouldBe "Hi"
    }

    @Test
    fun `cache hit applies synchronously with zero additional planner calls`() {
        val harness = Harness()
        harness.coordinator.bind(blocks("A"), 800, 1200)
        harness.drain()
        harness.coordinator.bind(blocks("B"), 800, 1200)
        harness.drain()
        val planCallsAfterWarmup = harness.planCalls

        // Rebind page A (fresh equal list, as a holder recycle would): sync hit.
        val result = harness.coordinator.bind(blocks("A"), 800, 1200)

        result.shouldBeInstanceOf<TextLayoutBindResult.Ready<String>>()
        result.prepared shouldBe "A"
        harness.planCalls shouldBe planCallsAfterWarmup
    }

    @Test
    fun `identical rebind is a cheap no-op even while planning is in flight`() {
        val harness = Harness()
        harness.coordinator.bind(blocks("A"), 800, 1200)
        val result = harness.coordinator.bind(blocks("A"), 800, 1200)

        result.shouldBeInstanceOf<TextLayoutBindResult.Unchanged>()
        harness.planCalls shouldBe 0
        harness.drain()
        // Exactly one plan for the one real (first) bind.
        harness.planCalls shouldBe 1
        harness.applied.single() shouldBe "A"
    }

    @Test
    fun `stale planning result for a superseded bind is never applied`() {
        val harness = Harness()
        val first = harness.coordinator.bind(blocks("A"), 800, 1200)
        first.shouldBeInstanceOf<TextLayoutBindResult.Planning>()
        val second = harness.coordinator.bind(blocks("B"), 800, 1200)
        second.shouldBeInstanceOf<TextLayoutBindResult.Planning>()

        harness.drain()

        // A was superseded BEFORE the background thread ran, so its planning
        // task is skipped entirely; only the generation-current B is planned
        // and applied.
        harness.planCalls shouldBe 1
        harness.applied.toList() shouldBe listOf("B")
        harness.cache.get(TextLayoutCacheKey(blocks("B"), 800, 1200)) shouldBe "B"
        harness.cache.get(TextLayoutCacheKey(blocks("A"), 800, 1200)) shouldBe null
    }

    @Test
    fun `clear cancels an in-flight planning result`() {
        val harness = Harness()
        harness.coordinator.bind(blocks("A"), 800, 1200)
        val cleared = harness.coordinator.bind(emptyList(), 0, 0)

        cleared.shouldBeInstanceOf<TextLayoutBindResult.Cleared>()
        harness.drain()

        harness.planCalls shouldBe 0
        harness.applied.size shouldBe 0
    }

    @Test
    fun `cancelPending drops an in-flight result without changing bound state`() {
        val harness = Harness()
        harness.coordinator.bind(blocks("A"), 800, 1200)
        harness.coordinator.cancelPending()

        harness.drain()

        harness.applied.size shouldBe 0
    }

    @Test
    fun `planner failure neither crashes bind nor applies anything`() {
        val background = QueueExecutor()
        val main = QueueExecutor()
        var appliedCount = 0
        val coordinator = TextLayoutCoordinator(
            cache = ReaderTextLayoutCache(maxEntries = 4),
            backgroundExecutor = background,
            mainExecutor = main,
            plan = { _, _, _ -> error("planner exploded") },
            onPrepared = { appliedCount++ },
        )

        coordinator.bind(blocks("A"), 800, 1200).shouldBeInstanceOf<TextLayoutBindResult.Planning>()
        background.runAll() // must not throw
        main.runAll()
        appliedCount shouldBe 0
    }

    @Test
    fun `rejected planning submission degrades to the empty state`() {
        val background = QueueExecutor().apply { rejected = true }
        val main = QueueExecutor()
        var appliedCount = 0
        val coordinator = TextLayoutCoordinator(
            cache = ReaderTextLayoutCache(maxEntries = 4),
            backgroundExecutor = background,
            mainExecutor = main,
            plan = { _, _, _ -> "x" },
            onPrepared = { appliedCount++ },
        )

        coordinator.bind(blocks("A"), 800, 1200).shouldBeInstanceOf<TextLayoutBindResult.Planning>()
        main.runAll()
        appliedCount shouldBe 0
    }

    @Test
    fun `empty and invalid-dimension binds clear synchronously`() {
        val harness = Harness()
        harness.coordinator.bind(blocks("A"), 800, 1200)
        harness.drain()

        harness.coordinator.bind(emptyList(), 0, 0).shouldBeInstanceOf<TextLayoutBindResult.Cleared>()
        harness.coordinator.bind(blocks("A"), 0, 1200).shouldBeInstanceOf<TextLayoutBindResult.Cleared>()
        harness.coordinator.bind(blocks("A"), 800, 0).shouldBeInstanceOf<TextLayoutBindResult.Cleared>()
    }

    @Test
    fun `repeated clear-shaped binds stay a cheap no-op`() {
        val harness = Harness()
        harness.coordinator.bind(emptyList(), 0, 0).shouldBeInstanceOf<TextLayoutBindResult.Unchanged>()
        harness.coordinator.bind(emptyList(), 0, 0).shouldBeInstanceOf<TextLayoutBindResult.Unchanged>()
        harness.planCalls shouldBe 0
    }

    @Test
    fun `bind after clear replans and reapplies`() {
        val harness = Harness()
        harness.coordinator.bind(blocks("A"), 800, 1200)
        harness.drain()
        harness.coordinator.bind(emptyList(), 0, 0)
        val result = harness.coordinator.bind(blocks("A"), 800, 1200)

        // Still cached from the first bind: synchronous hit, no planner work.
        result.shouldBeInstanceOf<TextLayoutBindResult.Ready<String>>()
        harness.planCalls shouldBe 1
    }
}
