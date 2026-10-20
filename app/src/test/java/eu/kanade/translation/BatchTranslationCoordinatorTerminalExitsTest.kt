package eu.kanade.translation

import eu.kanade.translation.coordinator.*
import eu.kanade.translation.persistence.*
import eu.kanade.translation.processing.*

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.coordinator.InMemorySharedPreferences
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.processing.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.util.getChapterPages
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import com.hippo.unifile.UniFile
import java.io.InputStream
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

/**
 * Exceptional exits of the chapter-level
 * batch runner produce a typed terminal tracker snapshot with the real reason
 * â€” never a live nonterminal `0/0` tracker, never a silent empty projection.
 *
 * Chapter page enumeration is replaced with a fake at the [getChapterPages] seam:
 * the real implementation filters through ImageUtil, whose class initializer
 * needs Android graphics and cannot load on the JVM.
 */
class BatchTranslationCoordinatorTerminalExitsTest {

    private val chapterId = 7L
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns chapterId
        every { name } returns "Chapter 1"
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
    private val provider = mockk<TranslationFileProvider>(relaxed = true)
    private val downloadProvider = mockk<DownloadProvider>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val preferences = mockk<TranslationPreferences>(relaxed = true)
    private val processor = mockk<TranslationProcessor>(relaxed = true)
    private val pageOperation = mockk<BatchPageOperation>(relaxed = true)

    private val registry = TranslationBatchTrackerRegistry()
    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeStoreResolver: (suspend (Translation) -> ChapterTranslationStore?)? = null
    private var trackerFactory: (
        (
            Long,
            ChapterTranslationStore,
            List<String>,
        ) -> TranslationBatchProgressTracker?
    )? = { id, store, keys ->
        registry.createTracker(id, store, keys, trackerScope)
    }

    private fun translator(
        onBatchClosed: suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit = { _, _, _, _ -> },
    ): BatchTranslationCoordinator {
        return BatchTranslationCoordinator(
            context,
            provider,
            downloadProvider,
            sourceManager,
            preferences,
            TranslationQueueStore(context),
            processor,
            pageOperation = pageOperation,
            resolveStore = { translation -> activeStoreResolver?.invoke(translation) },
            batchTrackerFactory = { id, store, pageKeys -> trackerFactory?.invoke(id, store, pageKeys) },
            onBatchClosed = onBatchClosed,
        )
    }

    private fun fixtureTranslation() = Translation(source, manga, chapter)

    /** Waits for the tracker's terminal snapshot to land in the registry cache. */
    private suspend fun awaitTerminal(): eu.kanade.translation.model.TranslationProgressSnapshot =
        withTimeout(TimeUnit.SECONDS.toMillis(10)) {
            registry.terminal.first { it.containsKey(chapterId) }.getValue(chapterId)
        }

    private suspend fun awaitNoLiveTracker() = withTimeout(TimeUnit.SECONDS.toMillis(10)) {
        registry.live.first { live -> live.isEmpty() }
    }

    private fun stubEnumeration() {
        mockkStatic(CHAPTER_PAGES_KT)
        every { getChapterPages(any(), any()) } returns singlePageStreams()
    }

    private fun unstubEnumeration() {
        unmockkStatic(CHAPTER_PAGES_KT)
    }

    private fun singlePageStreams(): List<Pair<String, () -> InputStream>> =
        listOf("001.jpg" to { error("stream must not be opened before the pipeline runs") })

    @Test
    fun `pre-registration rejection produces a typed terminal aborted snapshot and no live tracker`() = runBlocking<Unit> {
        val store = ChapterTranslationStore()
        activeStoreResolver = { store }
        // The known ordered keys are rejected by the defunct store: the caller
        // must observe the rejection instead of silently running an empty batch.
        store.markDefunct()
        store.preRegisterPages(listOf("001.jpg"))::class.simpleName shouldBe "Rejected"

        stubEnumeration()

        val translation = fixtureTranslation()
        try {
            translator().translateChapterInternal(translation)
        } finally {
            unstubEnumeration()
        }

        translation.status shouldBe Translation.State.ERROR
        awaitNoLiveTracker()
        val terminal = awaitTerminal()
        terminal.state shouldBe Translation.State.ERROR
        terminal.aborted shouldBe true
        terminal.abortedReason!!.contains("pre-registration") shouldBe true
        // The known ordered key still produces a real total (never 0/0).
        terminal.totalPages shouldBe 1
    }

    @Test
    fun `missing chapter files produce an aborted terminal snapshot with the reason`() = runBlocking<Unit> {
        val store = ChapterTranslationStore()
        activeStoreResolver = { store }
        every { downloadProvider.findChapterDir(any(), any(), any(), any()) } returns null

        val translation = fixtureTranslation()
        translator().translateChapterInternal(translation)

        translation.status shouldBe Translation.State.ERROR
        awaitNoLiveTracker()
        val terminal = awaitTerminal()
        terminal.state shouldBe Translation.State.ERROR
        terminal.aborted shouldBe true
        terminal.abortedReason!!.contains("files not found") shouldBe true
    }

    @Test
    fun `empty page enumeration still runs the batch close lifecycle`() = runBlocking<Unit> {
        val store = ChapterTranslationStore()
        activeStoreResolver = { store }
        val chapterDir = mockk<UniFile>(relaxed = true)
        every { chapterDir.isFile } returns false
        every { downloadProvider.findChapterDir(any(), any(), any(), any()) } returns chapterDir
        mockkStatic(CHAPTER_PAGES_KT)
        every { getChapterPages(any(), any()) } returns emptyList()
        var closed = false
        val activeJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { awaitCancellation() }

        try {
            val coordinator = translator(onBatchClosed = { _, _, _, _ -> closed = true })
            setField(coordinator, "translationJob", activeJob)
            coordinator.translateChapterInternal(fixtureTranslation())
        } finally {
            activeJob.cancel()
            unmockkStatic(CHAPTER_PAGES_KT)
        }

        closed shouldBe true
    }

    @Test
    fun `unexpected pipeline exception aborts the tracker with the typed reason`() = runBlocking<Unit> {
        val store = ChapterTranslationStore()
        activeStoreResolver = { store }

        stubEnumeration()
        // The batch worker only runs when the translator job is active; inject
        // an active job so the coordinator call is reached, then make it explode.
        val activeJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { awaitCancellation() }
        coEvery {
            pageOperation.execute(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("provider exploded")

        try {
            val translator = translator()
            setField(translator, "translationJob", activeJob)
            val translation = fixtureTranslation()
            translator.translateChapterInternal(translation)

            translation.status shouldBe Translation.State.ERROR
            awaitNoLiveTracker()
            val terminal = awaitTerminal()
            terminal.state shouldBe Translation.State.ERROR
            terminal.aborted shouldBe true
            terminal.abortedReason!!.contains("provider exploded") shouldBe true
            terminal.totalPages shouldBe 1
        } finally {
            activeJob.cancel()
            BitmapPool.releaseAll()
            unstubEnumeration()
        }
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
        /** mockkStatic marker for the top-level getChapterPages function's file class. */
        const val CHAPTER_PAGES_KT = "eu.kanade.translation.util.ChapterPagesKt"
    }
}
