package eu.kanade.translation

import eu.kanade.translation.pipeline.MemoryPressureClass
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure-policy tests for [MemoryPressurePolicy.classify]. Mirrors the plain JUnit5 +
 * Kotest style used by [TranslationMemoryPressureForwarderTest]: raw `Int`
 * trim-memory levels, no `android.content.ComponentCallbacks2` statics (those do not
 * resolve in plain-JVM unit tests — there is no Robolectric on this source set).
 *
 * Constants are referenced by name ([MemoryPressurePolicy.LEVEL_RUNNING_CRITICAL],
 * etc.) for boundary clarity; their numeric values mirror `ComponentCallbacks2`.
 */
class MemoryPressurePolicyTest {

    @Test
    fun `running critical level 10 is Critical`() {
        MemoryPressurePolicy.classify(MemoryPressurePolicy.LEVEL_RUNNING_CRITICAL) shouldBe
            MemoryPressureClass.Critical
    }

    @Test
    fun `running low level 15 is Critical`() {
        MemoryPressurePolicy.classify(MemoryPressurePolicy.LEVEL_RUNNING_LOW) shouldBe
            MemoryPressureClass.Critical
    }

    @Test
    fun `levels inside the 10 to 15 range are Critical`() {
        MemoryPressurePolicy.classify(11) shouldBe MemoryPressureClass.Critical
        MemoryPressurePolicy.classify(14) shouldBe MemoryPressureClass.Critical
    }

    @Test
    fun `complete level 80 is Critical`() {
        MemoryPressurePolicy.classify(MemoryPressurePolicy.LEVEL_COMPLETE) shouldBe
            MemoryPressureClass.Critical
    }

    @Test
    fun `levels above complete are Critical`() {
        // level >= LEVEL_COMPLETE (80) is Critical: the process may be killed.
        MemoryPressurePolicy.classify(81) shouldBe MemoryPressureClass.Critical
        MemoryPressurePolicy.classify(100) shouldBe MemoryPressureClass.Critical
        // The implementation classifies any level >= 80 as Critical, including
        // out-of-range / unknown large values. This is exercised explicitly so
        // the documented "unknown defaults safe -> Benign" claim in the policy
        // KDoc is reconciled with the live code (code is the source of truth).
        MemoryPressurePolicy.classify(Int.MAX_VALUE) shouldBe MemoryPressureClass.Critical
    }

    @Test
    fun `ui hidden level 20 is Benign`() {
        MemoryPressurePolicy.classify(MemoryPressurePolicy.LEVEL_UI_HIDDEN) shouldBe
            MemoryPressureClass.Benign
    }

    @Test
    fun `background level 40 is Benign`() {
        MemoryPressurePolicy.classify(MemoryPressurePolicy.LEVEL_BACKGROUND) shouldBe
            MemoryPressureClass.Benign
    }

    @Test
    fun `moderate level 60 is Benign`() {
        MemoryPressurePolicy.classify(MemoryPressurePolicy.LEVEL_MODERATE) shouldBe
            MemoryPressureClass.Benign
    }

    @Test
    fun `level 0 is Benign`() {
        MemoryPressurePolicy.classify(0) shouldBe MemoryPressureClass.Benign
    }

    @Test
    fun `level 9 just below running critical is Benign`() {
        // 9 is one below LEVEL_RUNNING_CRITICAL (10): the critical range is tight
        // on its lower edge.
        MemoryPressurePolicy.classify(9) shouldBe MemoryPressureClass.Benign
    }

    @Test
    fun `level 16 just above running low is Benign`() {
        // 16 is one above LEVEL_RUNNING_LOW (15): proves the 10..15 range is tight
        // on its upper edge and does not bleed into UI_HIDDEN (20).
        MemoryPressurePolicy.classify(16) shouldBe MemoryPressureClass.Benign
    }

    @Test
    fun `level 79 just below complete is Benign`() {
        MemoryPressurePolicy.classify(79) shouldBe MemoryPressureClass.Benign
    }

    @Test
    fun `negative level is Benign`() {
        MemoryPressurePolicy.classify(-1) shouldBe MemoryPressureClass.Benign
    }

    @Test
    fun `only the explicit critical ranges are Critical everything else is Benign`() {
        // Documented invariant: the ONLY levels that classify as Critical are the
        // explicit critical ranges (10..15 and >= 80). Enumerate a representative
        // sweep across the level space; anything not in those ranges is Benign.
        val criticalLevels = (0..120).filter {
            MemoryPressurePolicy.classify(it) == MemoryPressureClass.Critical
        }
        val expectedCritical = (10..15).toList() + (80..120).toList()
        criticalLevels shouldBe expectedCritical
    }
}
