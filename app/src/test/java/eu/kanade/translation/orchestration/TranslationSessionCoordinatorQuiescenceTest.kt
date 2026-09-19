package eu.kanade.translation.orchestration

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

class TranslationSessionCoordinatorQuiescenceTest {

    @Test
    fun `joined batch switch admits reader only after real unwind`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
        val pauseCalls = mutableListOf("none")
        val joinTimeouts = mutableListOf<Long>()
        var attempts = 0

        val admission = coordinator.switchBatchToReader(
            intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
            pauseBatch = { pauseCalls += "pause" },
            joinBatch = {
                attempts += 1
                attempts == 2
            },
            timeoutMs = 3_000L,
            retryDelayMs = 0L,
            onJoinTimeout = { joinTimeouts += it },
        )

        admission shouldBe SessionAdmission.Switched(TranslationSessionState.BATCH_SESSION)
        pauseCalls shouldContainExactly listOf("none", "pause")
        joinTimeouts shouldContainExactly listOf(3_000L)
        attempts shouldBe 2
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `timed out join keeps pausing while retry is in progress`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
        val statesSeenByJoin = mutableListOf<TranslationSessionState>()
        var attempts = 0

        coordinator.switchBatchToReader(
            intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
            pauseBatch = {},
            joinBatch = {
                statesSeenByJoin += coordinator.state.value
                attempts += 1
                attempts == 2
            },
            timeoutMs = 3_000L,
            retryDelayMs = 0L,
        )

        statesSeenByJoin shouldContainExactly listOf(
            TranslationSessionState.PAUSING,
            TranslationSessionState.PAUSING,
        )
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `reader teardown cancellation restores batch ownership`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))

        val switchJob = launch {
            coordinator.switchBatchToReader(
                intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
                pauseBatch = {},
                joinBatch = {
                    kotlinx.coroutines.awaitCancellation()
                },
                timeoutMs = 3_000L,
                retryDelayMs = 2_000L,
            )
        }
        runCurrent()
        coordinator.state.value shouldBe TranslationSessionState.PAUSING

        switchJob.cancelAndJoin()
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }

    @Test
    fun `reader teardown abort prevents late reader admission`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))

        val switchJob = launch {
            coordinator.switchBatchToReader(
                intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
                pauseBatch = {},
                joinBatch = {
                    kotlinx.coroutines.awaitCancellation()
                },
            )
        }
        runCurrent()
        coordinator.state.value shouldBe TranslationSessionState.PAUSING

        coordinator.abortPausingToBatch() shouldBe true
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
        switchJob.cancelAndJoin()
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }
}
