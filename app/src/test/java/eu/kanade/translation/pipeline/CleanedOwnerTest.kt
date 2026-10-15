package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * Deterministic host coverage for the single-slot [CleanedOwner] (plan §5):
 * release idempotence, borrow completion before recycling, receipt-join
 * gating and alias identity transfer.
 *
 * Determinism and cleanup rules (validation-audit policy): suspending release
 * calls are started with [CoroutineStart.UNDISPATCHED] so they are observed
 * AT their suspension point without yield-count timing; every held borrow or
 * receipt is released in a `finally` even when an assertion throws. Each
 * suspension-bearing test carries a JUnit [Timeout]: the repo's JUnit version
 * lacks `ThreadMode.SEPARATE_THREAD` (compile-proven), so this same-thread
 * timeout records an overrun but cannot interrupt a hung body — the hard
 * bound for a stalled execution is the OUTER run guard (project-filtered
 * test-worker watchdog, scratch helper). Android bitmap host-test limit:
 * [Bitmap] instances here are MockK stubs, so recycle/ownership assertions
 * prove the OWNER'S CONTROL FLOW only — actual native pixel-buffer disposal
 * stays device-gated and is neither claimed nor exercised by these tests.
 */
class CleanedOwnerTest {

    private fun stubBitmap(): Bitmap = mockk<Bitmap>(relaxed = true)

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `release is idempotent and recycles exactly once`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)

        owner.release()
        owner.release()

        verify(exactly = 1) { bitmap.recycle() }
        owner.isAvailable shouldBe false
        owner.owns(bitmap) shouldBe false
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `release waits for an open borrow and recycles only after close`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)
        owner.borrow() shouldBe bitmap
        var borrowClosed = false
        try {
            // UNDISPATCHED: release runs until its borrow-count suspension
            // point before this line returns — a deterministic rendezvous.
            val releaseJob = async(start = CoroutineStart.UNDISPATCHED) { owner.release() }
            releaseJob.isActive shouldBe true
            verify(exactly = 0) { bitmap.recycle() }

            owner.closeBorrow()
            borrowClosed = true
            releaseJob.await()

            verify(exactly = 1) { bitmap.recycle() }
        } finally {
            if (!borrowClosed) owner.closeBorrow()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `borrow is denied once release has started`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)
        owner.borrow() shouldBe bitmap
        var borrowClosed = false
        try {
            val releaseJob = async(start = CoroutineStart.UNDISPATCHED) { owner.release() }
            releaseJob.isActive shouldBe true
            owner.borrow() shouldBe null

            owner.closeBorrow()
            borrowClosed = true
            releaseJob.await()

            verify(exactly = 1) { bitmap.recycle() }
        } finally {
            if (!borrowClosed) owner.closeBorrow()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `receipt join gates recycling`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)
        val receipt = CompletableDeferred<Boolean>()
        owner.registerReceipt(receipt)
        var receiptCompleted = false
        try {
            val releaseJob = async(start = CoroutineStart.UNDISPATCHED) { owner.release() }
            releaseJob.isActive shouldBe true
            verify(exactly = 0) { bitmap.recycle() }

            receipt.complete(true)
            receiptCompleted = true
            releaseJob.await()

            verify(exactly = 1) { bitmap.recycle() }
        } finally {
            if (!receiptCompleted) receipt.complete(false)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `a suppressed receipt completion still releases`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)
        val receipt = CompletableDeferred<Boolean>()
        owner.registerReceipt(receipt)
        var receiptCompleted = false
        try {
            val releaseJob = async(start = CoroutineStart.UNDISPATCHED) { owner.release() }
            releaseJob.isActive shouldBe true
            verify(exactly = 0) { bitmap.recycle() }

            receipt.complete(false)
            receiptCompleted = true
            releaseJob.await()

            verify(exactly = 1) { bitmap.recycle() }
        } finally {
            if (!receiptCompleted) receipt.complete(false)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `an exceptional receipt completion fails release without recycling`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)
        val receipt = CompletableDeferred<Boolean>()
        owner.registerReceipt(receipt)
        var receiptCompleted = false
        // Supervisor scope on the shared test scheduler: the expected release
        // failure must stay contained in the Deferred, not fail the runTest
        // parent via structured-child propagation.
        val releaseScope = CoroutineScope(SupervisorJob() + testScheduler)
        val releaseJob = releaseScope.async(start = CoroutineStart.UNDISPATCHED) { owner.release() }
        try {
            releaseJob.isActive shouldBe true
            verify(exactly = 0) { bitmap.recycle() }

            // Protocol violation: the store contract completes receipts
            // normally (true/false) — an exceptional completion is NOT proof
            // the queued borrower physically exited. Release must fail CLOSED.
            receipt.completeExceptionally(IllegalStateException("receipt protocol violation"))
            receiptCompleted = true
            assertThrows<IllegalStateException> { releaseJob.await() }

            verify(exactly = 0) { bitmap.recycle() }
        } finally {
            if (!receiptCompleted) receipt.complete(false)
            if (releaseJob.isActive) releaseJob.cancel()
            releaseScope.cancel()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `a cancelled receipt fails release without recycling`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)
        val receipt = CompletableDeferred<Boolean>()
        owner.registerReceipt(receipt)
        var receiptCompleted = false
        // Supervisor scope as above: contained failure, shared scheduler.
        val releaseScope = CoroutineScope(SupervisorJob() + testScheduler)
        val releaseJob = releaseScope.async(start = CoroutineStart.UNDISPATCHED) { owner.release() }
        try {
            releaseJob.isActive shouldBe true
            verify(exactly = 0) { bitmap.recycle() }

            receipt.cancel()
            receiptCompleted = true
            assertThrows<CancellationException> { releaseJob.await() }

            // Fail closed: an externally cancelled receipt never fabricates a
            // `false` completion for the queued borrower and never recycles.
            verify(exactly = 0) { bitmap.recycle() }
        } finally {
            if (!receiptCompleted) receipt.complete(false)
            if (releaseJob.isActive) releaseJob.cancel()
            releaseScope.cancel()
        }
    }

    @Test
    fun `release without production is a no-op`() = runTest {
        val owner = CleanedOwner()

        owner.isAvailable shouldBe false
        owner.borrow() shouldBe null
        owner.release()

        owner.isAvailable shouldBe false
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `alias identity transfers into the owner`() = runTest {
        val owner = CleanedOwner()
        val alias = stubBitmap()
        val other = stubBitmap()

        owner.produce(alias, CleanedProvenance.INPAINT)
        owner.owns(alias) shouldBe true
        owner.owns(other) shouldBe false

        owner.release()

        verify(exactly = 1) { alias.recycle() }
        verify(exactly = 0) { other.recycle() }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `retry replace on an empty slot transfers identity`() = runTest {
        val owner = CleanedOwner()
        val retried = stubBitmap()

        owner.replace(retried, CleanedProvenance.RETRY)

        owner.isAvailable shouldBe true
        owner.owns(retried) shouldBe true

        owner.release()

        verify(exactly = 1) { retried.recycle() }
    }

    @Test
    fun `registerReceipt after release started fails fast`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)

        owner.release()

        assertThrows<IllegalStateException> { owner.registerReceipt(CompletableDeferred()) }
    }

    @Test
    fun `produce and borrow discipline fails fast on misuse`() {
        val produced = stubBitmap()
        val second = stubBitmap()

        assertThrows<IllegalStateException> {
            val owner = CleanedOwner()
            owner.produce(produced, CleanedProvenance.INPAINT)
            owner.produce(second, CleanedProvenance.INPAINT)
        }

        assertThrows<IllegalStateException> {
            val owner = CleanedOwner()
            owner.produce(produced, CleanedProvenance.INPAINT)
            owner.replace(second, CleanedProvenance.RETRY)
        }

        assertThrows<IllegalStateException> {
            val owner = CleanedOwner()
            owner.registerReceipt(CompletableDeferred())
            owner.registerReceipt(CompletableDeferred())
        }

        assertThrows<IllegalStateException> { CleanedOwner().closeBorrow() }
    }
}
