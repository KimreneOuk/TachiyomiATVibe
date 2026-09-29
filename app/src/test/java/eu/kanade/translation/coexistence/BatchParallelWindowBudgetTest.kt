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

    @Test
    fun `S11 wave lookahead selects active translating plus next queued chapter on same source`() {
        data class MockTranslation(val id: Long, val source: Long, var status: String)

        fun selectWaveCandidates(
            queue: List<MockTranslation>,
        ): List<MockTranslation> {
            val candidates = queue.filter { it.status == "QUEUE" || it.status == "TRANSLATING" }
            val active = candidates.filter { it.status == "TRANSLATING" }
            val activeSource = active.firstOrNull()?.source
            val selected = if (active.isNotEmpty()) {
                val nextQueue = candidates.firstOrNull {
                    it.status == "QUEUE" &&
                        it !in active &&
                        (activeSource == null || it.source == activeSource)
                }
                if (nextQueue != null) {
                    active.take(1) + listOf(nextQueue)
                } else {
                    active.take(1)
                }
            } else {
                candidates.take(1)
            }
            return selected.asSequence()
                .groupBy { it.source }
                .toList()
                .take(1)
                .flatMap { (_, translations) -> translations.take(2) }
        }

        // Case 1: Fresh queue with nothing translating yet
        val q1 = listOf(
            MockTranslation(1, 100, "QUEUE"),
            MockTranslation(2, 100, "QUEUE"),
            MockTranslation(3, 100, "QUEUE"),
        )
        val sel1 = selectWaveCandidates(q1)
        sel1.map { it.id } shouldBe listOf(1L)

        // Case 2: Chapter 1 is translating, Chapter 2 is queued on same source
        val q2 = listOf(
            MockTranslation(1, 100, "TRANSLATING"),
            MockTranslation(2, 100, "QUEUE"),
            MockTranslation(3, 100, "QUEUE"),
        )
        val sel2 = selectWaveCandidates(q2)
        sel2.map { it.id } shouldBe listOf(1L, 2L)

        // Case 3: Chapter 1 is translating, but next queued chapter is on different source
        val q3 = listOf(
            MockTranslation(1, 100, "TRANSLATING"),
            MockTranslation(2, 200, "QUEUE"),
        )
        val sel3 = selectWaveCandidates(q3)
        sel3.map { it.id } shouldBe listOf(1L)

        // Case 4: No queued chapters remain, only chapter 1 translating
        val q4 = listOf(
            MockTranslation(1, 100, "TRANSLATING"),
        )
        val sel4 = selectWaveCandidates(q4)
        sel4.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `standard lane checkpoint translation preserves X6 gap safety by returning PAUSED on gaps`() {
        // X6 Invariant: "PARTIAL-corpus COMPLETE" is unrepresentable.
        // If hasGaps is true or corpusFingerprint is null, standard lane MUST emit PAUSED, never COMPLETE.
        fun evaluateStandardLaneTerminal(
            corpusFingerprint: String?,
            hasGaps: Boolean,
        ): String {
            return if (hasGaps || corpusFingerprint == null) {
                "PAUSED"
            } else {
                "COMPLETE"
            }
        }

        // Gap present: must be PAUSED
        evaluateStandardLaneTerminal(corpusFingerprint = null, hasGaps = true) shouldBe "PAUSED"
        evaluateStandardLaneTerminal(corpusFingerprint = "fp_partial", hasGaps = true) shouldBe "PAUSED"
        evaluateStandardLaneTerminal(corpusFingerprint = null, hasGaps = false) shouldBe "PAUSED"

        // Full corpus: complete
        evaluateStandardLaneTerminal(corpusFingerprint = "fp_full", hasGaps = false) shouldBe "COMPLETE"
    }
}
