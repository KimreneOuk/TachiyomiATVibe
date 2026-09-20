package eu.kanade.translation

import eu.kanade.translation.pipeline.*

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationMemoryPressureForwarderTest {
    @Test
    fun `application forwarding seam delivers the exact memory level once`() {
        var calls = 0
        var received = -1

        forwardTranslationMemoryPressure(15) {
            calls++
            received = it
        }

        calls shouldBe 1
        received shouldBe 15
    }
}
