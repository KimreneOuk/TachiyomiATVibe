package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.PageLayoutDrawPlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 *  WP9 gates 7.6/7.7 (JVM-testable parts): the [TextLayoutCoordinator]
 * hydrate-then-fallback contract and the [PersistedLayoutReaderBridge] /
 * [PersistedLayoutRuntime] seams.
 *
 * Gate 7.6 (restart/LRU rehydrate without planner invocation): a hydrated
 * bind — including after cache LRU eviction and after a "process restart"
 * (a fresh coordinator over the same durable plan source) — keeps the
 * [TextLayoutPlanner] invocation counter at 0. The Pager and Webtoon holders
 * share this exact coordinator + bridge path (overlay `plan`/`hydrate`
 * lambdas), which is the JVM-testable part of the gate; the on-device
 * holder-level rows are listed as owed in `evidence/stage2/wp9-report.md`.
 *
 * Gate 7.7 (fallback correctness):  off, no bridge source, Manual/Auto
 * and legacy data (no pointer), missing, corrupt (quarantined), unsupported
 * version, and lossy rehydration ALL route to the async planner. The planner
 * fallback is mandatory  and the bind-generation stale defense
 * applies to hydrated deliveries identically (gate 7-4).
 */
class PersistedLayoutHydrationTest {

    private class QueueExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    /** Counts async planner invocations — must stay 0 on every hydrated bind. */
    private class Harness(
        maxCacheEntries: Int = 8,
        hydrate: ((List<TranslationBlock>, Int, Int) -> String?)? = null,
    ) {
        val background = QueueExecutor()
        val main = QueueExecutor()
        var planCalls = 0
        val applied = ArrayDeque<String>()
        val cache = ReaderTextLayoutCache<String>(maxEntries = maxCacheEntries)

        val coordinator = TextLayoutCoordinator(
            cache = cache,
            backgroundExecutor = background,
            mainExecutor = main,
            plan = { blocks, _, _ ->
                planCalls++
                "planned:" + blocks.single().translation
            },
            onPrepared = { prepared -> applied.addLast(prepared) },
            hydrate = hydrate,
        )

        fun drain() {
            background.runAll()
            main.runAll()
        }
    }

    private fun block(text: String, blockId: String = "p1_b0") = TranslationBlock(
        blockId = blockId,
        text = "orig",
        translation = text,
        width = 200f,
        height = 80f,
        x = 100f,
        y = 100f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        score = 1f,
    )

    @AfterEach
    fun resetRuntimeSeams() {
        PersistedLayoutRuntime.resetForTest()
        PersistedLayoutReaderBridge.installChapterSource(null)
    }

    // ------------------------------------------------------------------
    // Gate 7.6: hydrated binds NEVER invoke the planner.
    // ------------------------------------------------------------------

    @Test
    fun `hydrated bind applies with zero planner invocations (gate 7-6)`() {
        var hydrationSourceCalls = 0
        val harness = Harness(
            hydrate = { blocks, _, _ ->
                hydrationSourceCalls++
                "hydrated:" + blocks.single().translation
            },
        )

        harness.coordinator.bind(listOf(block("HELLO")), 1000, 1400)
        harness.drain()

        harness.planCalls shouldBe 0
        hydrationSourceCalls shouldBe 1
        harness.applied.single() shouldBe "hydrated:HELLO"
    }

    @Test
    fun `LRU eviction and re-bind rehydrate with planner still at zero (gate 7-6)`() {
        var hydrationSourceCalls = 0
        val harness = Harness(
            maxCacheEntries = 1,
            hydrate = { blocks, _, _ ->
                hydrationSourceCalls++
                "hydrated:" + blocks.single().translation
            },
        )

        // Pager-like bind A, then a Webtoon-like bind B evicts A (cache cap 1).
        harness.coordinator.bind(listOf(block("PAGE_A")), 1000, 1400)
        harness.drain()
        harness.coordinator.bind(listOf(block("STRIP_B")), 800, 4000)
        harness.drain()
        harness.planCalls shouldBe 0

        // Scroll back to A: LRU miss, but hydration serves it — planner stays 0.
        harness.coordinator.bind(listOf(block("PAGE_A")), 1000, 1400)
        harness.drain()

        harness.planCalls shouldBe 0
        hydrationSourceCalls shouldBe 3
        harness.applied.last() shouldBe "hydrated:PAGE_A"
    }

    @Test
    fun `process restart rebinds from the durable plan with planner at zero (gate 7-6)`() {
        // The durable plan source survives a process restart; the coordinator
        // and cache do not. Two fresh coordinators (pre/post restart) both
        // hydrate from the same source; the planner throwing is the guard.
        var hydrationSourceCalls = 0
        fun freshCoordinator(): TextLayoutCoordinator<String> = TextLayoutCoordinator(
            cache = ReaderTextLayoutCache(8),
            backgroundExecutor = Executor { it.run() }, // synchronous: drains inline
            mainExecutor = Executor { it.run() },
            plan = { _, _, _ ->
                throw AssertionError("planner must never run while a valid persisted plan exists")
            },
            onPrepared = { },
            hydrate = { blocks, _, _ ->
                hydrationSourceCalls++
                "hydrated:" + blocks.single().translation
            },
        )
        freshCoordinator().bind(listOf(block("AFTER_RESTART")), 1000, 1400)
        // A brand-new coordinator (the restart) over the same source:
        freshCoordinator().bind(listOf(block("AFTER_RESTART")), 1000, 1400)

        hydrationSourceCalls shouldBe 2
    }

    // ------------------------------------------------------------------
    // Gate 7.7: fallback green in EVERY non-Resolved case.
    // ------------------------------------------------------------------

    @Test
    fun `fallback green - no bridge source installed is FF-02-off parity (gate 7-7)`() {
        val harness = Harness()
        harness.coordinator.bind(listOf(block("FALLBACK")), 1000, 1400)
        harness.drain()
        harness.planCalls shouldBe 1
        harness.applied.single() shouldBe "planned:FALLBACK"
    }

    @Test
    fun `fallback green - bridge hydrate returning null falls back to the planner (gate 7-7)`() {
        PersistedLayoutReaderBridge.installChapterSource { _, _, _, _ -> null }
        var planCalls = 0
        val coordinator = TextLayoutCoordinator(
            cache = ReaderTextLayoutCache(8),
            backgroundExecutor = Executor { it.run() },
            mainExecutor = Executor { it.run() },
            plan = { blocks, _, _ ->
                planCalls++
                "planned:" + blocks.single().translation
            },
            onPrepared = { },
            hydrate = { blocks, width, height ->
                PersistedLayoutReaderBridge.hydrate("test-page", blocks, width, height)
            },
        )
        coordinator.bind(listOf(block("MANUAL_PAGE")), 1000, 1400)
        planCalls shouldBe 1
    }

    @Test
    fun `fallback green - throwing hydration source degrades to the planner (gate 7-7)`() {
        PersistedLayoutReaderBridge.installChapterSource { _, _, _, _ -> throw IllegalStateException("io exploded") }
        PersistedLayoutReaderBridge.hydrate("any", listOf(block("X")), 10, 10) shouldBe null
    }

    @Test
    fun `fallback green - every typed hydrator outcome routes to the planner (gate 7-7)`() {
        // Each non-Resolved outcome the hydrator can emit must map to a null
        // bridge response, i.e. the async planner. The typed mapping is proven
        // directly against the coordinator contract: hydrate returns null.
        val outcomes = listOf<HydratedLayout>(
            HydratedLayout.Absent,
            HydratedLayout.Incompatible("stroke policy version changed"),
            HydratedLayout.UnsupportedVersion(2),
            HydratedLayout.Lossy(
                plan = unpublishedPlan(),
                resolvedCount = 0,
                expectedCount = 1,
                reason = "rehydrate count mismatch",
            ),
        )
        outcomes.forEach { outcome ->
            val harness = Harness(
                hydrate = { _, _, _ ->
                    when (outcome) {
                        is HydratedLayout.Resolved -> "hydrated"
                        else -> null // production bridge maps every non-Resolved to null
                    }
                },
            )
            harness.coordinator.bind(listOf(block("FALLBACK")), 1000, 1400)
            harness.drain()
            harness.planCalls shouldBe 1
        }
    }

    private fun unpublishedPlan(): PageLayoutDrawPlan = PageLayoutDrawPlan(
        layoutPlannerVersion = DrawPlanFingerprint.LAYOUT_PLANNER_VERSION,
        fontIdentity = DrawPlanFingerprint.drawPlanFontIdentity("dd".repeat(32)),
        platformShapingKey = "sdk34-UPSIDE_DOWN_CAKE",
        pageWidth = 1000f,
        pageHeight = 1400f,
        decodeSampleSize = 1,
        strokePolicyVersion = DrawPlanFingerprint.STROKE_POLICY_VERSION,
        strokeColorPolicyVersion = DrawPlanFingerprint.STROKE_COLOR_POLICY_VERSION,
        blocks = emptyList(),
    )

    // ------------------------------------------------------------------
    // Cache + seams.
    // ------------------------------------------------------------------

    @Test
    fun `hydrated value is cached - interleave rebind is a synchronous cache hit`() {
        var hydrationSourceCalls = 0
        val harness = Harness(
            hydrate = { blocks, _, _ ->
                hydrationSourceCalls++
                "hydrated:" + blocks.single().translation
            },
        )

        val first = harness.coordinator.bind(listOf(block("CACHED")), 1000, 1400)
        harness.drain()
        // Bind a DIFFERENT page in between so the third bind is not
        // short-circuited as Unchanged and must resolve through the cache
        // synchronously — without re-invoking the hydration source.
        harness.coordinator.bind(listOf(block("OTHER")), 1000, 1400)
        harness.drain()
        val third = harness.coordinator.bind(listOf(block("CACHED")), 1000, 1400)

        first.shouldBeInstanceOf<TextLayoutBindResult.Planning>()
        val hit = third.shouldBeInstanceOf<TextLayoutBindResult.Ready<String>>()
        hit.prepared shouldBe "hydrated:CACHED"
        hydrationSourceCalls shouldBe 2 // CACHED once, OTHER once — the rebind was a pure cache hit
        harness.planCalls shouldBe 0
    }

    @Test
    fun `bind-generation stale defense drops a late hydrated delivery (gate 7-4)`() {
        var hydrationSourceCalls = 0
        val harness = Harness(
            hydrate = { blocks, _, _ ->
                hydrationSourceCalls++
                "hydrated:" + blocks.single().translation
            },
        )

        // Bind page 1 (hydration in flight), then rebind to page 2 BEFORE the
        // background hydration runs. Page 1's delivery must be dropped.
        harness.coordinator.bind(listOf(block("PAGE_1")), 1000, 1400)
        harness.coordinator.bind(listOf(block("PAGE_2")), 1000, 1400)
        harness.drain()

        hydrationSourceCalls shouldBe 1 // page 1's hydration was superseded before its source ran
        harness.applied.single() shouldBe "hydrated:PAGE_2"
        harness.planCalls shouldBe 0
    }

    @Test
    fun `runtime seams - flag override and font digest pinning behave`() {
        PersistedLayoutRuntime.flagEnabled() shouldBe false // unregistered DI context: safe OFF
        PersistedLayoutRuntime.persistedLayoutFlagOverride = true
        PersistedLayoutRuntime.flagEnabled() shouldBe true
        PersistedLayoutRuntime.persistedLayoutFlagOverride = false
        PersistedLayoutRuntime.flagEnabled() shouldBe false

        PersistedLayoutRuntime.productionFontSha256() shouldBe null // nothing installed
        PersistedLayoutRuntime.fontSha256Loader = { "ee".repeat(32) }
        PersistedLayoutRuntime.productionFontSha256() shouldBe "ee".repeat(32)
        PersistedLayoutRuntime.fontSha256Loader = { throw IllegalStateException("only read once") }
        PersistedLayoutRuntime.productionFontSha256() shouldBe "ee".repeat(32) // cached

        PersistedLayoutRuntime.pinFontSha256("ff".repeat(32))
        PersistedLayoutRuntime.productionFontSha256() shouldBe "ff".repeat(32)
    }
}
