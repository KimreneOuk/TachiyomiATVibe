package eu.kanade.translation.workflow

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger

class TranslationSessionCoordinatorTest {

    @Test
    fun `idle admits batch and repeated batch request is idempotent`() {
        val coordinator = TranslationSessionCoordinator()

        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
            .shouldBeInstanceOf<SessionAdmission.Admitted>()
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION

        val repeated = coordinator.requestBatchSession(BatchSessionIntent(setOf(11L)))
            .shouldBeInstanceOf<SessionAdmission.Admitted>()
        repeated.state shouldBe TranslationSessionState.BATCH_SESSION
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }

    @Test
    fun `idle admits reader and teardown returns to idle`() {
        val coordinator = TranslationSessionCoordinator()

        coordinator.requestReaderSession(ReaderSessionIntent(chapterId = 10L))
            .shouldBeInstanceOf<SessionAdmission.Admitted>()
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION

        coordinator.finishSession()
        coordinator.state.value shouldBe TranslationSessionState.IDLE
    }

    @Test
    fun `reader request during batch is typed rejection until switch is confirmed`() {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))

        val rejected = coordinator.requestReaderSession(ReaderSessionIntent(chapterId = 10L))
            .shouldBeInstanceOf<SessionAdmission.Rejected>()
        rejected.reason shouldBe SessionRejection.BATCH_ACTIVE
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION

        val switched = coordinator.requestReaderSession(
            ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
        ).shouldBeInstanceOf<SessionAdmission.Switched>()
        switched.previous shouldBe TranslationSessionState.BATCH_SESSION
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `confirmed switch invokes the plain-pause callback exactly once`() {
        val pauseCalls = AtomicInteger(0)
        val coordinator = TranslationSessionCoordinator(onBatchSwitchRequested = { pauseCalls.incrementAndGet() })
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))

        coordinator.requestReaderSession(
            ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
        )
        coordinator.requestReaderSession(
            ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
        )

        pauseCalls.get() shouldBe 1
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `batch request while reader owns session is typed rejection`() {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestReaderSession(ReaderSessionIntent(chapterId = 10L))

        val rejected = coordinator.requestBatchSession(BatchSessionIntent(setOf(11L)))
            .shouldBeInstanceOf<SessionAdmission.Rejected>()
        rejected.reason shouldBe SessionRejection.READER_ACTIVE
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `pausing rejects both origins until the owner releases it`() {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
        coordinator.beginPausing()

        coordinator.requestReaderSession(ReaderSessionIntent(chapterId = 10L))
            .shouldBeInstanceOf<SessionAdmission.Rejected>().reason shouldBe SessionRejection.PAUSING_IN_PROGRESS
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
            .shouldBeInstanceOf<SessionAdmission.Rejected>().reason shouldBe SessionRejection.PAUSING_IN_PROGRESS

        coordinator.finishSession()
        coordinator.state.value shouldBe TranslationSessionState.IDLE
    }

    @Test
    fun `direct scheduler entry cannot bypass an active batch session`() {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
        val scheduler = TranslationScheduler(
            executor = mockk<TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            readerSessionRejectionReason = { chapterId ->
                when (val admission = coordinator.requestReaderSession(ReaderSessionIntent(chapterId))) {
                    is SessionAdmission.Rejected -> admission.reason.name
                    else -> null
                }
            },
        )
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        val source = mockk<HttpSource>(relaxed = true)
        every { chapter.id } returns 10L

        scheduler.translatePage(manga, chapter, source, "p0")

        scheduler.manualOutcomeFor(10L, "p0")
            .shouldBeInstanceOf<eu.kanade.translation.pipeline.execution.SinglePageOutcome.Rejected>()
            .reason shouldBe "reader session rejected: BATCH_ACTIVE"
        scheduler.close()
    }
}
