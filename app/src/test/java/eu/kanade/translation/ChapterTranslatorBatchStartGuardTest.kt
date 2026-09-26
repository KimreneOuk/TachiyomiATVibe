package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.chapter.TranslationProvider
import eu.kanade.translation.persistence.queue.TranslationQueueStore
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.workflow.ChapterTranslator
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

/**
 *  hotfix regression guards for the batch start admission path:
 * - a generic queue start must not resurrect an ERROR-restored queue entry
 *   (restore work resumes only through an explicit per-chapter request);
 * - an admission for a chapter whose batch is still in flight must be a no-op
 *   for the running work — exactly ONE concurrent batch schedule (two live
 *   schedules for one chapter make each store generation advance cancel the
 *   prior run; observed on device as back-to-back `schedule_start` events for
 *   the same chapter with `outcome=cancelled`).
 */
class ChapterTranslatorBatchStartGuardTest {

    private val chapterId = 21L
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns chapterId
        every { name } returns "Chapter 21"
        every { scanlator } returns null
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { title } returns "fixture"
        every { source } returns 1L
    }
    private val source = mockk<HttpSource>(relaxed = true)

    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns InMemorySharedPreferences()
    }
    private val provider = mockk<TranslationProvider>(relaxed = true)
    private val downloadProvider = mockk<DownloadProvider>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val preferences = mockk<TranslationPreferences>(relaxed = true)
    private val streamRegistry = mockk<TranslationStreamRegistry>(relaxed = true)
    private val pipeline = mockk<TranslationPipeline>(relaxed = true)

    private fun translatorWithQueue(queue: List<Translation>): ChapterTranslator {
        val translator = ChapterTranslator(
            context,
            provider,
            downloadProvider,
            sourceManager,
            preferences,
            streamRegistry,
            TranslationQueueStore(context),
            pipeline,
        )
        // queueState is bound to the constructor-time _queueState instance, so
        // the injected flow must replace BOTH fields to be visible to start().
        val flow = MutableStateFlow(queue)
        setField(translator, "_queueState", flow)
        setField(translator, "queueState", flow.asStateFlow())
        return translator
    }

    private fun translation(status: Translation.State) =
        Translation(source, manga, chapter).also { it.status = status }

    /**
     * Every batch "runs" by first entering the store resolver — the same
     * position the pipeline occupies before its generation is advanced. The
     * resolver blocks inside non-suspending code, so a cancelled-but-unwinding
     * batch stays in flight exactly like a mid-run uncancellable native call.
     */
    private fun stubInFlightResolver(concurrent: AtomicInteger, maxConcurrent: AtomicInteger) {
        every { pipeline.activeStoreResolver } answers {
            { _ ->
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
                Thread.sleep(IN_FLIGHT_WINDOW_MS)
                concurrent.decrementAndGet()
                null
            }
        }
        // Fast ERROR exit after the resolver: no artifact directory resolves,
        // and no chapter dir means the typed pre-pipeline failure exit.
        every { provider.findTranslationFile(any(), any(), any(), any()) } returns null
        every { downloadProvider.findChapterDir(any(), any(), any(), any()) } returns null
        every { pipeline.batchTrackerFactory } returns null
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            Thread.sleep(25)
        }
    }

    @Test
    fun `generic queue start does not resurrect an ERROR entry`() {
        val translation = translation(Translation.State.ERROR)
        val translator = translatorWithQueue(listOf(translation))

        translator.start() shouldBe false
        translator.isRunning shouldBe false
        translation.status shouldBe Translation.State.ERROR
    }

    @Test
    fun `generic queue start still admits a plain QUEUE entry`() {
        stubInFlightResolver(AtomicInteger(), AtomicInteger())
        val translation = translation(Translation.State.QUEUE)
        val translator = translatorWithQueue(listOf(translation))

        translator.start() shouldBe true
        awaitTrue { !translator.isRunning && translation.status == Translation.State.ERROR }
    }

    @Test
    fun `overlapping admissions while a batch is in flight schedule exactly one concurrent batch`() {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        stubInFlightResolver(concurrent, maxConcurrent)
        val translation = translation(Translation.State.QUEUE)
        val translator = translatorWithQueue(listOf(translation))

        translator.start() shouldBe true
        awaitTrue { concurrent.get() == 1 }

        // Overlapping admission for the SAME chapter while batch #1 is still
        // in flight (the device idiom: pause cancels the translator job, the
        // next admission re-admits the queued entry while the previous batch
        // coroutine has not unwound yet).
        translator.pause()
        translator.start()

        awaitTrue { !translator.isRunning && concurrent.get() == 0 }
        maxConcurrent.get() shouldBe 1
        translation.status shouldBe Translation.State.ERROR
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
    }

    private companion object {
        const val IN_FLIGHT_WINDOW_MS = 300L
    }
}
