package eu.kanade.translation.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression guards for the Wave 1 P0-1 race-condition invariant, encoded as a
 * pure helper in [TranslationSafetyPrimitives] so it can be driven without the
 * singleton pipeline or an ONNX engine.
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
}
