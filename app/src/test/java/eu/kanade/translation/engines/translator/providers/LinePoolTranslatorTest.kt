package eu.kanade.translation.engines.translator.providers

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LinePoolTranslatorTest {

    @Test
    fun borrowedWorkerIsReturnedAfterNormalCompletion() = runTest {
        val pool = LineTranslationWorkerPool()

        pool.withWorker { "first" } shouldBe "first"
        pool.withWorker { "second" } shouldBe "second"
    }

    @Test
    fun lineWorkersEnforceBoundAndPreserveInputOrder() = runTest {
        val pool = LineTranslationWorkerPool()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val bothWorkersEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val translated = async {
            pool.mapOrdered((0 until 8).toList()) { line ->
                val nowActive = active.incrementAndGet()
                peak.updateAndGet { current -> maxOf(current, nowActive) }
                if (nowActive == pool.workerCount) bothWorkersEntered.complete(Unit)
                try {
                    bothWorkersEntered.await()
                    release.await()
                    "translated-$line"
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        bothWorkersEntered.await()
        peak.get() shouldBe pool.workerCount
        release.complete(Unit)

        translated.await() shouldBe (0 until 8).map { "translated-$it" }
        peak.get() shouldBe pool.workerCount
    }

    @Test
    fun borrowersWaitWhileEveryWorkerIsExhausted() = runTest {
        val pool = LineTranslationWorkerPool()
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger()

        val currentBorrowers = List(pool.workerCount) {
            async {
                pool.withWorker {
                    entered.incrementAndGet()
                    release.await()
                }
            }
        }
        runCurrent()
        entered.get() shouldBe pool.workerCount

        val waitingBorrowerEntered = CompletableDeferred<Unit>()
        val waitingBorrower = async {
            pool.withWorker {
                waitingBorrowerEntered.complete(Unit)
                "released"
            }
        }
        runCurrent()
        waitingBorrowerEntered.isCompleted shouldBe false

        release.complete(Unit)
        currentBorrowers.awaitAll()
        waitingBorrowerEntered.await()
        waitingBorrower.await() shouldBe "released"
    }

    @Test
    fun cancelledBorrowerReturnsWorkerWithoutLeakingCapacity() = runTest {
        val pool = LineTranslationWorkerPool()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val firstBorrower = async {
            pool.withWorker {
                firstEntered.complete(Unit)
                awaitCancellation()
            }
        }
        firstEntered.await()

        val secondBorrower = async {
            pool.withWorker {
                secondEntered.complete(Unit)
                awaitCancellation()
            }
        }
        secondEntered.await()

        val waitingBorrowerEntered = CompletableDeferred<Unit>()
        val waitingBorrower = async {
            pool.withWorker {
                waitingBorrowerEntered.complete(Unit)
                "after-cancellation"
            }
        }
        runCurrent()
        waitingBorrowerEntered.isCompleted shouldBe false

        firstBorrower.cancelAndJoin()
        waitingBorrower.await() shouldBe "after-cancellation"
        waitingBorrowerEntered.isCompleted shouldBe true
        secondBorrower.cancelAndJoin()
    }
}
