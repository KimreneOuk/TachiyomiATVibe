package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Physical-registry drain contract at the scheduler seam (R-DRAIN1): the queue
 * must cancel and drain live workers even when the scheduler's session-token
 * metadata is gone (invalidated/rebound), session stops must spare a
 * different-chapter binding, and destructive chapter resets must fence
 * admission for the chapter (even with no tokens) while stopping and draining
 * ALL of its workers, including a current same-chapter binding.
 */
class SchedulerRegistryDrainTest {

    private class GatedExecutor : TranslationExecutor {
        val started = ConcurrentHashMap.newKeySet<String>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val exited = ConcurrentHashMap.newKeySet<String>()

        fun hold(pageKey: String) {
            gates.getOrPut(pageKey) { CompletableDeferred() }
        }

        fun release(pageKey: String) {
            gates[pageKey]?.complete(Unit)
        }

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
            started += pageKey
            try {
                withContext(NonCancellable) { gates[pageKey]?.await() }
            } finally {
                exited += pageKey
            }
            return SinglePageOutcome.Completed
        }
    }

    private val manga = mockk<Manga>(relaxed = true)
    private val source = mockk<HttpSource>(relaxed = true)
    private val store = mockk<ChapterTranslationStore>()

    private fun chapterWithId(id: Long): Chapter = mockk<Chapter>(relaxed = true).also { every { it.id } returns id }

    private fun newScheduler(executor: GatedExecutor): TranslationScheduler = TranslationScheduler(
        executor,
        { null },
        manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(ByteArray(0)) } },
    )

    private suspend fun awaitExit(executor: GatedExecutor, pageKey: String) {
        withTimeoutOrNull(10_000) {
            while (pageKey !in executor.exited) delay(10)
        } shouldNotBe null
    }

    /** T1: invalidated session then reader stop with NO rebind — the stop must still join the orphaned worker. */
    @Test
    @Timeout(60)
    fun `invalidate then awaitReaderStop joins the orphaned worker without any token metadata`() = runBlocking<Unit> {
        val executor = GatedExecutor()
        val scheduler = newScheduler(executor)
        val chapter = chapterWithId(7L)
        every { manga.id } returns 7L
        executor.hold("p0")
        try {
            scheduler.translatePage(manga, chapter, source, "p0")
            withTimeoutOrNull(10_000) { while ("p0" !in executor.started) delay(10) } shouldNotBe null

            scheduler.invalidateQueueSession(7L)

            val stop = async(Dispatchers.Default) { scheduler.awaitReaderStop() }
            // The registry drain must keep the stop suspended until physical exit —
            // the pre-fix implementation returned immediately here.
            withTimeoutOrNull(500) { stop.await() } shouldBe null
            executor.release("p0")
            stop.await()
            awaitExit(executor, "p0")
        } finally {
            executor.release("p0")
            scheduler.close()
        }
    }

    /** T2a: destructive chapter reset with no tokens spares a different-chapter binding. */
    @Test
    @Timeout(60)
    fun `destructive chapter drain with no tokens spares the different-chapter binding`() = runBlocking<Unit> {
        val executor = GatedExecutor()
        val scheduler = newScheduler(executor)
        val chapter7 = chapterWithId(7L)
        val chapter9 = chapterWithId(9L)
        every { manga.id } returns 7L
        executor.hold("p0")
        try {
            scheduler.translatePage(manga, chapter7, source, "p0")
            withTimeoutOrNull(10_000) { while ("p0" !in executor.started) delay(10) } shouldNotBe null
            scheduler.invalidateQueueSession(7L)

            val sessionB = TranslationSession("identity-b", manga, chapter9, source, store)
            val tokenB = scheduler.bindReaderQueueSession(9L, sessionB, TranslatorComputeClass.REMOTE_IO)
            tokenB shouldNotBe null

            val reset = async(Dispatchers.Default) { scheduler.cancelReaderQueueSession(7L) }
            withTimeoutOrNull(500) { reset.await() } shouldBe null

            // Chapter 7 admission is fenced for the whole destructive drain even
            // though the scheduler had no tokens for it.
            val denied = TranslationSession("identity-a2", manga, chapter7, source, store)
            scheduler.bindReaderQueueSession(7L, denied, TranslatorComputeClass.REMOTE_IO) shouldBe null
            // Chapter 9's live binding survives untouched (token reuse path).
            scheduler.bindReaderQueueSession(9L, sessionB, TranslatorComputeClass.REMOTE_IO) shouldBe tokenB

            executor.release("p0")
            reset.await()
            awaitExit(executor, "p0")

            // Epoch released after the await: chapter 7 can bind again.
            scheduler.bindReaderQueueSession(7L, denied, TranslatorComputeClass.REMOTE_IO) shouldNotBe null
        } finally {
            executor.release("p0")
            scheduler.close()
        }
    }

    /** T2b: same-chapter rebind then destructive reset — both bindings stopped/drained, admission fenced. */
    @Test
    @Timeout(60)
    fun `same-chapter rebind then destructive reset drains both bindings and fences admission`() = runBlocking<Unit> {
        val executor = GatedExecutor()
        val scheduler = newScheduler(executor)
        val chapter7 = chapterWithId(7L)
        every { manga.id } returns 7L
        executor.hold("p0")
        executor.hold("q0")
        try {
            scheduler.translatePage(manga, chapter7, source, "p0")
            withTimeoutOrNull(10_000) { while ("p0" !in executor.started) delay(10) } shouldNotBe null

            val sessionB = TranslationSession("identity-b", manga, chapter7, source, store)
            val tokenB = scheduler.bindReaderQueueSession(7L, sessionB, TranslatorComputeClass.REMOTE_IO)
            tokenB shouldNotBe null
            scheduler.submitReaderPage(
                tokenB!!,
                ReaderPageWork("q0", -1) { ByteArrayInputStream(ByteArray(0)) },
                ReaderTrigger.MANUAL_TAP,
            )
            withTimeoutOrNull(10_000) { while ("q0" !in executor.started) delay(10) } shouldNotBe null

            val reset = async(Dispatchers.Default) { scheduler.cancelReaderQueueSession(7L) }
            withTimeoutOrNull(500) { reset.await() } shouldBe null

            // Admission for the chapter is denied during the destructive drain —
            // bound-session rebinds AND fresh manual taps.
            val sessionC = TranslationSession("identity-c", manga, chapter7, source, store)
            scheduler.bindReaderQueueSession(7L, sessionC, TranslatorComputeClass.REMOTE_IO) shouldBe null
            scheduler.translatePage(manga, chapter7, source, "p1")
            scheduler.manualOutcomeFor(7L, "p1")
                .shouldBeInstanceOf<SinglePageOutcome.Rejected>()
                .kind shouldBe PageRejectionKind.SESSION_DENIED

            // Both the retired A worker and the current B worker must physically
            // exit before the destructive reset returns.
            executor.release("p0")
            executor.release("q0")
            reset.await()
            awaitExit(executor, "p0")
            awaitExit(executor, "q0")

            // Epoch released after the await: admission works again.
            scheduler.bindReaderQueueSession(7L, sessionC, TranslatorComputeClass.REMOTE_IO) shouldNotBe null
        } finally {
            executor.release("p0")
            executor.release("q0")
            scheduler.close()
        }
    }
}
