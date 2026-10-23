package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InpaintingFailureLatchTest {

    @Test
    fun `first failure announces, repeated failures inside the cooldown stay silent`() {
        val latch = InpaintingFailureLatch(cooldownMs = 100L)

        latch.tryAnnounce(nowMs = 0L) shouldBe true
        latch.tryAnnounce(nowMs = 50L) shouldBe false
        latch.tryAnnounce(nowMs = 99L) shouldBe false
    }

    @Test
    fun `a failure after the full cooldown announces again`() {
        val latch = InpaintingFailureLatch(cooldownMs = 100L)

        latch.tryAnnounce(nowMs = 0L) shouldBe true
        latch.tryAnnounce(nowMs = 100L) shouldBe true
        latch.tryAnnounce(nowMs = 150L) shouldBe false
    }
}
