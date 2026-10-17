package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Teardown drain-handle contract at the scheduler seam: [TranslationScheduler.awaitReaderStop]
 * awaits the queue's `PendingNativeDrain` handle — physical worker exit,
 * including non-cooperative native stragglers — before returning, so the
 * teardown coordinator's store eviction can never precede the drain.
 */
class SchedulerTeardownDrainTest {

    private class NonCooperativeExecutor : TranslationExecutor {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()

        override suspend fun translateSinglePage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            force: Boolean,
            stageListener: TranslationStageListener?,
            origin: PageWriteOrigin,
            readerStreamFn: (() -> InputStream)?,
            readerPriority: (() -> AdmissionPriority)?,
        ): SinglePageOutcome {
            started.complete(Unit)
            try {
                withContext(NonCancellable) { release.await() }
            } finally {
                exited.complete(Unit)
            }
            return SinglePageOutcome.Completed
        }
    }

    @Test
    @Timeout(60)
    fun `awaitReaderStop completes only after physical worker exit`() = runBlocking<Unit> {
        val executor = NonCooperativeExecutor()
        val scheduler = TranslationScheduler(
            executor,
            { null },
            manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(ByteArray(0)) } },
        )
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        val source = mockk<HttpSource>(relaxed = true)
        every { chapter.id } returns 7L
        every { manga.id } returns 7L
        every { source.id } returns 7L

        try {
            scheduler.translatePage(manga, chapter, source, "p0")
            executor.started.await()

            val stop = async(Dispatchers.Default) { scheduler.awaitReaderStop("teardown drain") }
            // The worker ignores cancellation; the drain handle must keep the
            // stop suspended until physical exit.
            withTimeoutOrNull(500) { stop.await() } shouldBe null

            executor.release.complete(Unit)
            executor.exited.await()
            stop.await()
        } finally {
            executor.release.complete(Unit)
            scheduler.close()
        }
    }
}
