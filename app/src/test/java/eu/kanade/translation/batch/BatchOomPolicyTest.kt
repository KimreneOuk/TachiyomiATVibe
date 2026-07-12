package eu.kanade.translation.batch

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class BatchOomPolicyTest {

    @Test
    fun `zero consecutive OOM does not abort`() {
        val decision = BatchOomPolicy.shouldAbort(0)
        decision.abort shouldBe false
        decision.reason shouldBe null
    }

    @Test
    fun `one or two consecutive OOMs do not abort`() {
        BatchOomPolicy.shouldAbort(1).abort shouldBe false
        BatchOomPolicy.shouldAbort(2).abort shouldBe false
    }

    @Test
    fun `three consecutive OOMs trigger abort with reason`() {
        val decision = BatchOomPolicy.shouldAbort(3)
        decision.abort shouldBe true
        decision.reason shouldNotBe null
        decision.consecutiveCount shouldBe 3
    }

    @Test
    fun `custom threshold is respected`() {
        BatchOomPolicy.shouldAbort(1, threshold = 1).abort shouldBe true
        BatchOomPolicy.shouldAbort(4, threshold = 5).abort shouldBe false
    }
}
