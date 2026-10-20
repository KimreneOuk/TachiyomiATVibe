package eu.kanade.translation.engines.translator

import eu.kanade.translation.engines.translator.ProviderAdmissionEvent
import eu.kanade.translation.engines.translator.ProviderQuotaPolicy
import eu.kanade.translation.engines.translator.ProviderRequestDiagnostics
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.ProviderRequestPausedException
import eu.kanade.translation.engines.translator.retry.RequestRetryBudget
import eu.kanade.translation.engines.translator.retry.withRequestRetryBudget
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class PaidCallLifecycleTest {
    @Test
    fun `stop before entry prevents admission network and budget charge`() = runTest {
        val lifecycle = OperationPaidCallLifecycle()
        lifecycle.requestStop(0).retainWorker shouldBe false
        val events = mutableListOf<ProviderAdmissionEvent>()
        val governor = governor(events)
        val budget = RequestRetryBudget(maxAttempts = 3)
        var network = 0
        val outcome = runCatching {
            withContext(lifecycle) {
                withRequestRetryBudget(budget) {
                    governor.executeValue(metadata()) {
                        network++
                        "unexpected"
                    }
                }
            }
        }
        network shouldBe 0
        budget.attemptsUsed shouldBe 0
        events.size shouldBe 0
        outcome.exceptionOrNull().shouldBeInstanceOf<ProviderRequestPausedException>()
    }

    @Test
    fun `entered call finishes but same operation retry after stop is refused`() = runTest {
        val lifecycle = OperationPaidCallLifecycle()
        val events = mutableListOf<ProviderAdmissionEvent>()
        val governor = governor(events)
        val networkEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val budget = RequestRetryBudget(maxAttempts = 3)
        var network = 0
        var firstResult: String? = null
        var retryFailure: Throwable? = null
        val worker = launch(lifecycle) {
            withRequestRetryBudget(budget) {
                firstResult = governor.executeValue(metadata()) {
                    network++
                    networkEntered.complete(Unit)
                    release.await()
                    "committed-tail"
                }
                retryFailure = runCatching {
                    governor.executeValue(metadata()) {
                        network++
                        "unexpected-retry"
                    }
                }.exceptionOrNull()
            }
        }
        try {
            runCurrent()
            networkEntered.isCompleted shouldBe true
            network shouldBe 1
            val decision = lifecycle.requestStop(500)
            release.complete(Unit)
            worker.join()
            worker.isCancelled shouldBe false
            firstResult shouldBe "committed-tail"
            network shouldBe 1
            budget.attemptsUsed shouldBe 1
            events.count { it.outcome == "admitted" } shouldBe 1
            retryFailure.shouldBeInstanceOf<ProviderRequestPausedException>()
            decision.retainWorker shouldBe true
            decision.deadlineEpochMs shouldBe 210_500L
        } finally {
            release.complete(Unit)
            worker.cancelAndJoin()
        }
    }

    @Test
    fun `stop after grant releases the unused permit without charging or calling network`() = runTest {
        val lifecycle = OperationPaidCallLifecycle()
        val events = mutableListOf<ProviderAdmissionEvent>()
        var stopAtGrant = true
        var retainedAtGrant: Boolean? = null
        val governor = ProviderRequestGovernor(
            policy = { ProviderQuotaPolicy(minimumSpacingMs = 0, maxInFlight = 1) },
            diagnostics = ProviderRequestDiagnostics {
                events += it
                if (it.outcome == "admitted" && stopAtGrant) {
                    stopAtGrant = false
                    retainedAtGrant = lifecycle.requestStop(100).retainWorker
                }
            },
        )
        val budget = RequestRetryBudget(maxAttempts = 3)
        var network = 0
        val outcome = runCatching {
            withContext(lifecycle) {
                withRequestRetryBudget(budget) {
                    governor.executeValue(metadata()) {
                        network++
                        "unexpected"
                    }
                }
            }
        }
        network shouldBe 0
        budget.attemptsUsed shouldBe 0
        outcome.exceptionOrNull().shouldBeInstanceOf<ProviderRequestPausedException>()
        events.count { it.outcome == "admitted" } shouldBe 1
        events.count { it.outcome == "stopped" } shouldBe 1
        retainedAtGrant shouldBe false
        withTimeout(1_000) {
            governor.executeValue(metadata()) { "slot-freed" }
        } shouldBe "slot-freed"
        events.count { it.outcome == "admitted" } shouldBe 2
        events.count { it.outcome == "completed" } shouldBe 1
    }

    @Test
    fun `expiry requests cancellation once without claiming physical exit`() {
        val lifecycle = OperationPaidCallLifecycle(drainGraceMs = 1_000)
        lifecycle.tryEnterPaidCall() shouldBe true
        lifecycle.claimExpiryCancellation(10_000) shouldBe false
        lifecycle.requestStop(500).deadlineEpochMs shouldBe 1_500L
        lifecycle.claimExpiryCancellation(1_499) shouldBe false
        lifecycle.claimExpiryCancellation(1_500) shouldBe true
        lifecycle.claimExpiryCancellation(1_501) shouldBe false
        lifecycle.isPhysicallyExited() shouldBe false
        lifecycle.tryEnterPaidCall() shouldBe false
        lifecycle.recordPhysicalExit()
        lifecycle.isPhysicallyExited() shouldBe true
        lifecycle.requestStop(2_000).retainWorker shouldBe false
    }

    private fun metadata() = ProviderRequestMetadata(key = ProviderRequestKey("gemini", "flash", "one"))

    private fun governor(events: MutableList<ProviderAdmissionEvent>) = ProviderRequestGovernor(
        policy = { ProviderQuotaPolicy(minimumSpacingMs = 0, maxInFlight = 1) },
        diagnostics = ProviderRequestDiagnostics { events += it },
    )
}
