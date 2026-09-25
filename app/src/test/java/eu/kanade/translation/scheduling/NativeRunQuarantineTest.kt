package eu.kanade.translation.scheduling

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeRunQuarantineTest {
    @Test
    fun `timeout blocks new admission until underlying exit and discards late result`() = runTest {
        val quarantine = NativeRunQuarantine(backgroundScope)
        val firstMayExit = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val timeoutObserved = CompletableDeferred<Unit>()

        val first = async {
            quarantine.run("chapter", "p1", 100, onTimeout = { timeoutObserved.complete(Unit) }) {
                firstMayExit.await()
                "late"
            }
        }
        advanceTimeBy(101)
        timeoutObserved.isCompleted shouldBe true
        val second = async {
            quarantine.run("chapter", "p2", 100) {
                secondEntered.complete(Unit)
                "second"
            }
        }
        testScheduler.runCurrent()

        secondEntered.isCompleted shouldBe false
        firstMayExit.complete(Unit)
        testScheduler.runCurrent()

        (first.await() is NativeRunQuarantine.Outcome.TimedOut) shouldBe true
        secondEntered.isCompleted shouldBe true
        (second.await() as NativeRunQuarantine.Outcome.Accepted).value shouldBe "second"
    }

    @Test
    fun `exclusive lifecycle action cannot enter while timed out invocation is alive`() = runTest {
        val quarantine = NativeRunQuarantine(backgroundScope)
        val invocationMayExit = CompletableDeferred<Unit>()
        val timeoutObserved = CompletableDeferred<Unit>()
        var lifecycleRuns = 0

        val invocation = async {
            quarantine.run("chapter", "p1", 100, onTimeout = { timeoutObserved.complete(Unit) }) {
                invocationMayExit.await()
            }
        }
        advanceTimeBy(101)
        timeoutObserved.isCompleted shouldBe true

        quarantine.tryRunExclusive { lifecycleRuns++ } shouldBe false
        lifecycleRuns shouldBe 0

        invocationMayExit.complete(Unit)
        testScheduler.runCurrent()
        invocation.await()

        quarantine.tryRunExclusive { lifecycleRuns++ } shouldBe true
        lifecycleRuns shouldBe 1
    }
}
