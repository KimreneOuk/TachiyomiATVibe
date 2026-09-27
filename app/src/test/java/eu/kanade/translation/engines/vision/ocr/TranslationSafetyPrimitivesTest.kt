package eu.kanade.translation.engines.vision.ocr

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression guards for a race-condition invariant, encoded as a pure helper
 * so it can be driven without constructing the OCR engine or loading ONNX.
 *
 * Each test RED-firsts the bug: if the helper regressed to the buggy behavior,
 * the assertion would fail.
 */
class TranslationSafetyPrimitivesTest {

    @Test
    fun `native buffer drain is skipped while inference lock is held`() {
        // Draining child pools while native inference owns them is a
        // use-after-free, so skip the whole drain when lockHeld.
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
    fun `native buffer drain visits every child when inference lock is free`() {
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
    fun `one child throwing does not abort the native buffer drain`() {
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
