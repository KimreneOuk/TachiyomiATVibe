package eu.kanade.translation.coexistence

import eu.kanade.translation.pipeline.batch.CrossOriginBitmapBudget
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class BatchParallelWindowBudgetTest {

    @BeforeEach
    @AfterEach
    fun resetBudget() {
        CrossOriginBitmapBudget.resetForTesting()
    }

    @Test
    fun `cross origin bitmap budget acquires and releases within ceiling`() {
        runBlocking {
            CrossOriginBitmapBudget.activeCount shouldBe 0

            // Acquire first slot
            CrossOriginBitmapBudget.acquireBatchPermit()
            CrossOriginBitmapBudget.activeCount shouldBe 1

            // Acquire second slot (ceiling = 2)
            CrossOriginBitmapBudget.acquireBatchPermit()
            CrossOriginBitmapBudget.activeCount shouldBe 2

            // Release first slot
            CrossOriginBitmapBudget.releaseBatchPermit()
            CrossOriginBitmapBudget.activeCount shouldBe 1

            // Release second slot
            CrossOriginBitmapBudget.releaseBatchPermit()
            CrossOriginBitmapBudget.activeCount shouldBe 0

            // Extra releases do not underflow
            CrossOriginBitmapBudget.releaseBatchPermit()
            CrossOriginBitmapBudget.activeCount shouldBe 0
        }
    }

    @Test
    fun `cross origin bitmap budget bounds concurrency at 2 and suspends 3rd acquirer until release`() {
        runBlocking {
            CrossOriginBitmapBudget.acquireBatchPermit()
            CrossOriginBitmapBudget.acquireBatchPermit()
            CrossOriginBitmapBudget.activeCount shouldBe 2

            val thirdAcquired = AtomicBoolean(false)
            val job = launch {
                CrossOriginBitmapBudget.acquireBatchPermit()
                thirdAcquired.set(true)
            }

            // Give the coroutine a tick to attempt acquire
            delay(50)
            thirdAcquired.get() shouldBe false
            job.isActive shouldBe true

            // Releasing one permit unblocks the third acquirer
            CrossOriginBitmapBudget.releaseBatchPermit()
            withTimeout(1000) {
                job.join()
            }

            thirdAcquired.get() shouldBe true
            CrossOriginBitmapBudget.activeCount shouldBe 2

            // Clean up
            CrossOriginBitmapBudget.releaseBatchPermit()
            CrossOriginBitmapBudget.releaseBatchPermit()
            CrossOriginBitmapBudget.activeCount shouldBe 0
        }
    }
}
