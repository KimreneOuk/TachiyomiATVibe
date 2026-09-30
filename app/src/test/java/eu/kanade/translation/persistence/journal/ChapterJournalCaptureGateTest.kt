package eu.kanade.translation.persistence.journal

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChapterJournalCaptureGateTest {

    @Test
    fun `snapshot write operations serialize without parking journal producers`() = runTest {
        val coordinator = ChapterJournalCaptureCoordinator()
        val firstWriteEntered = CompletableDeferred<Unit>()
        val finishFirstWrite = CompletableDeferred<Unit>()
        var secondWriteEntered = false

        val firstWrite = launch {
            coordinator.withSnapshotOperation {
                firstWriteEntered.complete(Unit)
                finishFirstWrite.await()
            }
        }
        firstWriteEntered.await()
        val secondWrite = launch {
            coordinator.withSnapshotOperation { secondWriteEntered = true }
        }
        runCurrent()
        secondWriteEntered shouldBe false

        var producerRan = false
        coordinator.gate.withProducer { producerRan = true }
        producerRan shouldBe true

        finishFirstWrite.complete(Unit)
        firstWrite.join()
        secondWrite.join()
        secondWriteEntered shouldBe true
    }

    @Test
    fun `barrier drains active capture and parks later producers until reopen`() = runTest {
        val gate = ChapterJournalCaptureGate()
        val producerEntered = CompletableDeferred<Unit>()
        val finishProducer = CompletableDeferred<Unit>()
        val captureEntered = CompletableDeferred<Unit>()
        val finishCapture = CompletableDeferred<Unit>()
        var parkedProducerEntered = false

        val activeProducer = launch {
            gate.withProducer {
                producerEntered.complete(Unit)
                finishProducer.await()
            }
        }
        producerEntered.await()

        val barrier = async {
            gate.withBarrier {
                captureEntered.complete(Unit)
                finishCapture.await()
                "snapshot"
            }
        }
        runCurrent()
        gate.isClosed shouldBe true
        captureEntered.isCompleted shouldBe false

        val parkedProducer = launch {
            gate.withProducer {
                parkedProducerEntered = true
            }
        }
        runCurrent()
        parkedProducerEntered shouldBe false
        gate.activeProducerCount shouldBe 1

        finishProducer.complete(Unit)
        runCurrent()
        captureEntered.isCompleted shouldBe true
        parkedProducerEntered shouldBe false
        activeProducer.join()

        finishCapture.complete(Unit)
        barrier.await() shouldBe "snapshot"
        runCurrent()
        parkedProducerEntered shouldBe true
        parkedProducer.join()
        gate.isClosed shouldBe false
        gate.activeProducerCount shouldBe 0
    }

    @Test
    fun `cancellation inside barrier always reopens the producer gate`() = runTest {
        val gate = ChapterJournalCaptureGate()
        val captureEntered = CompletableDeferred<Unit>()
        val holdCapture = CompletableDeferred<Unit>()
        val barrier = launch {
            gate.withBarrier {
                captureEntered.complete(Unit)
                holdCapture.await()
            }
        }
        captureEntered.await()

        barrier.cancel()
        barrier.join()
        gate.isClosed shouldBe false

        var producerRan = false
        gate.withProducer { producerRan = true }
        producerRan shouldBe true
    }
}
