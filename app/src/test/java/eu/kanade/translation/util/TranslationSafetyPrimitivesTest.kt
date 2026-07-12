package eu.kanade.translation.util

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression guards for the three Wave 1 P0 race-condition invariants, each
 * encoded as a pure helper in [TranslationSafetyPrimitives] so they can be
 * driven without the singleton pipeline or an ONNX engine:
 *
 * - P0-1 SIGSEGV: drain skipped when the native lock is held.
 * - P0-3 clear-at-top: dedup keys cleared BEFORE the permit is acquired.
 * - P0-4 watchdog isolation: every step runs even if an earlier one throws.
 *
 * Each test RED-firsts the bug: if the helper regressed to the buggy behavior,
 * the assertion would fail.
 */
class TranslationSafetyPrimitivesTest {

    // ---- P0-1: SIGSEGV guard ----

    @Test
    fun `P0-1 drain skipped when native lock is held`() {
        // The bug: draining child pools while native inference owns them is a
        // use-after-free. The guard: skip the whole drain when lockHeld.
        val drained = AtomicInteger(0)
        val engines = listOf<TranslationSafetyPrimitives.ForceReleasable>(
            TranslationSafetyPrimitives.ForceReleasable { drained.incrementAndGet() },
        )
        val outcome = TranslationSafetyPrimitives.drainChildBuffersGuarded(
            lockHeld = true,
            engines = engines,
        )
        outcome.shouldBeInstanceOf<TranslationSafetyPrimitives.DrainOutcome.Skipped>()
        drained.get() shouldBe 0
    }

    @Test
    fun `P0-1 drain runs every child when native lock is free`() {
        val drained = AtomicInteger(0)
        val engines = List(4) {
            TranslationSafetyPrimitives.ForceReleasable { drained.incrementAndGet() }
        }
        val outcome = TranslationSafetyPrimitives.drainChildBuffersGuarded(
            lockHeld = false,
            engines = engines,
        )
        outcome.shouldBeInstanceOf<TranslationSafetyPrimitives.DrainOutcome.Drained>()
        (outcome as TranslationSafetyPrimitives.DrainOutcome.Drained).count shouldBe 4
        drained.get() shouldBe 4
    }

    @Test
    fun `P0-1 one child throwing does not abort the drain of the others`() {
        // Production wraps each in try/catch so a single broken child cannot
        // leave the rest holding native buffers.
        val drained = AtomicInteger(0)
        val engines = listOf(
            TranslationSafetyPrimitives.ForceReleasable { drained.incrementAndGet() },
            TranslationSafetyPrimitives.ForceReleasable { throw RuntimeException("boom") },
            TranslationSafetyPrimitives.ForceReleasable { drained.incrementAndGet() },
        )
        val outcome = TranslationSafetyPrimitives.drainChildBuffersGuarded(
            lockHeld = false,
            engines = engines,
        )
        // Only the 2 non-throwing engines count; the throwing one is swallowed.
        (outcome as TranslationSafetyPrimitives.DrainOutcome.Drained).count shouldBe 2
        drained.get() shouldBe 2
    }

    // ---- P0-3: clear-at-top ----

    @Test
    fun `P0-3 keys cleared before permit is acquired even when permit is held`() {
        // The bug: if clear() happened AFTER tryAcquire, an early return
        // (permit held) would leave the abandoned worker's keys in place, and
        // the dedup gate would silently drop every retry of those pages.
        val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            addAll(listOf("page-1.jpg", "page-2.jpg", "page-3.jpg"))
        }
        val outcome = TranslationSafetyPrimitives.clearKeysBeforePermitAcquire(
            keys = keys,
            acquirePermit = { false }, // permit held by an abandoning worker
        )
        outcome shouldBe TranslationSafetyPrimitives.PermitOutcome.PermitHeld
        keys shouldBe emptySet<String>() // load-bearing: cleared even though permit was held
    }

    @Test
    fun `P0-3 keys cleared and permit acquired when free`() {
        val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            add("page-x.jpg")
        }
        val outcome = TranslationSafetyPrimitives.clearKeysBeforePermitAcquire(
            keys = keys,
            acquirePermit = { true },
        )
        outcome shouldBe TranslationSafetyPrimitives.PermitOutcome.PermitAcquired
        keys shouldBe emptySet<String>()
    }

    @Test
    fun `P0-3 clear runs unconditionally even on an already-empty set`() {
        // No-op clear must still call acquirePermit (return the right outcome),
        // not short-circuit. Guards against an "optimization" that skips the
        // permit check when keys is empty.
        val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var permitChecked = false
        val outcome = TranslationSafetyPrimitives.clearKeysBeforePermitAcquire(
            keys = keys,
            acquirePermit = { permitChecked = true; true },
        )
        outcome shouldBe TranslationSafetyPrimitives.PermitOutcome.PermitAcquired
        permitChecked shouldBe true
    }

    // ---- P0-4: watchdog callback isolation ----

    @Test
    fun `P0-4 every step runs even when an earlier step throws`() = runTest {
        // The bug: if onPageStuck threw, onForceRelease was never called, so
        // inFlightPageKeys stayed populated and the permit was never released
        // — every subsequent translation attempt deadlocked.
        val ran = AtomicInteger(0)
        val steps = arrayOf(
            TranslationSafetyPrimitives.WatchdogStep("onTimeout") { ran.incrementAndGet() },
            TranslationSafetyPrimitives.WatchdogStep("onPageStuck") { throw IllegalStateException("listener crashed") },
            TranslationSafetyPrimitives.WatchdogStep("onForceRelease") { ran.incrementAndGet() },
        )
        val outcome = TranslationSafetyPrimitives.runGuardedWatchdogChain(steps = steps)
        outcome.ran shouldBe 3
        ran.get() shouldBe 2 // both non-throwing steps ran
        outcome.failures shouldHaveSize 1
        outcome.failures.first().message shouldBe "listener crashed"
    }

    @Test
    fun `P0-4 first step throwing does not block the rest`() = runTest {
        val ran = mutableListOf<String>()
        val steps = arrayOf(
            TranslationSafetyPrimitives.WatchdogStep("onTimeout") { throw RuntimeException("timeout body crashed"); },
            TranslationSafetyPrimitives.WatchdogStep("onPageStuck") { ran += "stuck" },
            TranslationSafetyPrimitives.WatchdogStep("onForceRelease") { ran += "force" },
        )
        val outcome = TranslationSafetyPrimitives.runGuardedWatchdogChain(steps = steps)
        outcome.ran shouldBe 3
        ran shouldBe listOf("stuck", "force")
        outcome.failures shouldHaveSize 1
    }

    @Test
    fun `P0-4 middle step throwing does not block later steps`() = runTest {
        val ran = mutableListOf<String>()
        val steps = arrayOf(
            TranslationSafetyPrimitives.WatchdogStep("onTimeout") { ran += "timeout" },
            TranslationSafetyPrimitives.WatchdogStep("onPageStuck") { throw RuntimeException("stuck crashed") },
            TranslationSafetyPrimitives.WatchdogStep("onForceRelease") { ran += "force" },
        )
        val outcome = TranslationSafetyPrimitives.runGuardedWatchdogChain(steps = steps)
        ran shouldBe listOf("timeout", "force")
        outcome.failures shouldHaveSize 1
    }

    @Test
    fun `P0-4 all steps throwing collects all failures without rethrowing`() = runTest {
        val steps = arrayOf(
            TranslationSafetyPrimitives.WatchdogStep("a") { throw RuntimeException("a") },
            TranslationSafetyPrimitives.WatchdogStep("b") { throw IllegalStateException("b") },
            TranslationSafetyPrimitives.WatchdogStep("c") { throw Error("c") },
        )
        val outcome = TranslationSafetyPrimitives.runGuardedWatchdogChain(steps = steps)
        outcome.ran shouldBe 3
        outcome.failures shouldHaveSize 3
    }

    @Test
    fun `P0-4 suspend steps are awaited correctly`() = runTest {
        // onTimeout is suspend in production; the helper must actually await it.
        val ran = AtomicInteger(0)
        val steps = arrayOf(
            TranslationSafetyPrimitives.WatchdogStep("onTimeout") { ran.incrementAndGet() },
            TranslationSafetyPrimitives.WatchdogStep("onForceRelease") { ran.incrementAndGet() },
        )
        val outcome = TranslationSafetyPrimitives.runGuardedWatchdogChain(steps = steps)
        outcome.ran shouldBe 2
        ran.get() shouldBe 2
        outcome.failures shouldHaveSize 0
    }

    @Test
    fun `P0-4 empty chain is a no-op`() = runBlocking {
        val outcome = TranslationSafetyPrimitives.runGuardedWatchdogChain()
        outcome.ran shouldBe 0
        outcome.failures shouldHaveSize 0
    }
}
