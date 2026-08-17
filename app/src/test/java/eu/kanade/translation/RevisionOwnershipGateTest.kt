package eu.kanade.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class RevisionOwnershipGateTest {

    @Test
    fun `reservation is active before job registration and blocks auto rearm`() {
        val gate = RevisionOwnershipGate()

        gate.withLock { gate.reserveLocked(7L) shouldBe true }
        gate.isActive(7L) shouldBe true
        gate.withLock { gate.reserveLocked(7L) shouldBe false }

        val job = Job()
        gate.withLock { gate.registerLocked(7L, job) shouldBe true }
        gate.isActive(7L) shouldBe true

        gate.release(7L, job)
        gate.isActive(7L) shouldBe false
    }

    @Test
    fun `cancelled reservation cannot be registered`() {
        val gate = RevisionOwnershipGate()
        gate.withLock { gate.reserveLocked(9L) shouldBe true }
        gate.cancel(9L)

        val job = Job()
        gate.withLock { gate.registerLocked(9L, job) shouldBe false }
        job.isCancelled shouldBe true
        gate.isActive(9L) shouldBe false
    }

    @Test
    fun `late completion cannot release a newer reservation`() {
        val gate = RevisionOwnershipGate()
        gate.withLock { gate.reserveLocked(11L) shouldBe true }
        val oldJob = Job()
        gate.withLock { gate.registerLocked(11L, oldJob) shouldBe true }
        gate.release(11L, oldJob)

        gate.withLock { gate.reserveLocked(11L) shouldBe true }
        gate.release(11L, oldJob)
        gate.isActive(11L) shouldBe true
    }

    @Test
    fun `transaction abort disposes tracker and releases setup reservation`() {
        val gate = RevisionOwnershipGate()
        gate.withLock { gate.reserveLocked(12L) shouldBe true }
        val disposed = AtomicBoolean(false)
        val transaction = RevisionOwnershipTransaction(gate, 12L) { disposed.set(true) }

        transaction.abortSetup()

        disposed.get() shouldBe true
        gate.isActive(12L) shouldBe false
    }

    @Test
    fun `transaction rejects cancelled registration and disposes tracker`() {
        val gate = RevisionOwnershipGate()
        gate.withLock { gate.reserveLocked(13L) shouldBe true }
        gate.cancel(13L)
        val disposed = AtomicBoolean(false)
        val transaction = RevisionOwnershipTransaction(gate, 13L) { disposed.set(true) }

        transaction.register(Job()) shouldBe false

        disposed.get() shouldBe true
        gate.isActive(13L) shouldBe false
    }

    @Test
    fun `transaction completion hook disposes tracker when body never starts`() {
        val gate = RevisionOwnershipGate()
        gate.withLock { gate.reserveLocked(14L) shouldBe true }
        val disposed = AtomicBoolean(false)
        val transaction = RevisionOwnershipTransaction(gate, 14L) { disposed.set(true) }
        val job = Job()

        transaction.register(job) shouldBe true
        job.cancel()

        disposed.get() shouldBe true
        gate.isActive(14L) shouldBe false
    }

    @Test
    fun `transaction terminal completion releases ownership without preterminal disposal`() {
        val gate = RevisionOwnershipGate()
        gate.withLock { gate.reserveLocked(15L) shouldBe true }
        val disposed = AtomicBoolean(false)
        val transaction = RevisionOwnershipTransaction(gate, 15L) { disposed.set(true) }
        val job = Job()

        transaction.register(job) shouldBe true
        transaction.markBodyStarted()
        transaction.markTerminalRequested()
        job.complete()

        disposed.get() shouldBe false
        gate.isActive(15L) shouldBe false
    }
}
