package eu.kanade.translation.workflow

import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class AdmissionConcurrencyTest {
    @Test
    fun singlePathRearmsAfterMutationLockRelease() {
        val fixture = AdmissionTestFixture(
            initialQueue = listOf(Triple(1L, Translation.State.PAUSED, 1L)),
            pendingGenerations = mapOf(1L to 4L),
        )
        try {
            val lock = fixture.mutationLock()
            var lockHeldWhenStartCalled: Boolean? = null
            var statusWhenStartCalled: Translation.State? = null
            fixture.startHook = {
                lockHeldWhenStartCalled = Thread.holdsLock(lock)
                statusWhenStartCalled = fixture.queue.value.first { it.chapter.id == 1L }.status
            }

            fixture.manager.translateChapter(
                fixture.manga(),
                fixture.chapter(1L),
                expectedRequestGeneration = 4L,
            )

            lockHeldWhenStartCalled shouldBe false
            statusWhenStartCalled shouldBe Translation.State.QUEUE
            fixture.startCount.get() shouldBe 1
        } finally {
            fixture.close()
        }
    }

    @Test
    fun listAdmissionKeepsOneLockAcrossConcurrentCancelAndStartsOnceAfterUnlock() {
        val fixture = AdmissionTestFixture(
            initialQueue = listOf(
                Triple(2L, Translation.State.PAUSED, 1L),
                Triple(3L, Translation.State.ERROR, 1L),
            ),
            pendingGenerations = mapOf(2L to 7L, 3L to 8L),
        )
        val lock = fixture.mutationLock()
        val secondQueueCall = CountDownLatch(1)
        val releaseSecondQueueCall = CountDownLatch(1)
        val cancelAttempted = CountDownLatch(1)
        val cancelFinished = CountDownLatch(1)
        val batchResult = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable?>()
        var firstStatusAtSecondQueueCall: Translation.State? = null
        var lockHeldAtSecondQueueCall: Boolean? = null
        var lockHeldWhenStartCalled: Boolean? = null
        var queueAtStart: String? = null

        fixture.queueHook = { chapter ->
            if (chapter.id == 3L) {
                lockHeldAtSecondQueueCall = Thread.holdsLock(lock)
                firstStatusAtSecondQueueCall =
                    fixture.queue.value.first { it.chapter.id == 2L }.status
                secondQueueCall.countDown()
                releaseSecondQueueCall.await()
            }
        }
        fixture.startHook = {
            lockHeldWhenStartCalled = Thread.holdsLock(lock)
            queueAtStart = fixture.queueSnapshot()
            cancelFinished.await()
        }

        val batchThread = Thread {
            try {
                batchResult.set(
                    fixture.manager.translateChaptersIfCurrent(
                        fixture.manga(),
                        listOf(fixture.chapter(2L), fixture.chapter(3L)),
                        expectedGenerations = mapOf(2L to 7L, 3L to 8L),
                    ),
                )
            } catch (caught: Throwable) {
                failure.set(caught)
                secondQueueCall.countDown()
            }
        }
        val cancelThread = Thread {
            try {
                cancelAttempted.countDown()
                fixture.manager.cancelTranslationRequest(3L)
            } catch (caught: Throwable) {
                failure.compareAndSet(null, caught)
            } finally {
                cancelFinished.countDown()
            }
        }

        try {
            batchThread.start()
            secondQueueCall.await()
            cancelThread.start()
            cancelAttempted.await()
            while (cancelThread.state == Thread.State.RUNNABLE) {
                Thread.yield()
            }
            cancelThread.state shouldBe Thread.State.BLOCKED
            releaseSecondQueueCall.countDown()
            batchThread.join()
            cancelThread.join()
            failure.get()?.let { throw AssertionError("concurrent admission failed", it) }

            lockHeldAtSecondQueueCall shouldBe true
            firstStatusAtSecondQueueCall shouldBe Translation.State.QUEUE
            batchResult.get() shouldBe true
            lockHeldWhenStartCalled shouldBe false
            queueAtStart shouldBe "2:QUEUE,3:QUEUE"
            fixture.queueSnapshot() shouldBe "2:QUEUE,3:QUEUE"
            fixture.startCount.get() shouldBe 1
        } finally {
            releaseSecondQueueCall.countDown()
            if (batchThread.isAlive) batchThread.join()
            if (cancelThread.isAlive) cancelThread.join()
            fixture.close()
        }
    }
}
