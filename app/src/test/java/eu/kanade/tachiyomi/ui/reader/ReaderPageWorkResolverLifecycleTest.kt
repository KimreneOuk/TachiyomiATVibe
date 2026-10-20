package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.coordinator.ReaderSessionToken
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

/**
 * Reader-side lifecycle invariants for the typed queue facade: resolver
 * generation fencing, session/store binding, stream teardown, the shared
 * source-ready predicate, and reset-before-recycle ordering. The N/N+1 demand
 * window and session invalidation at the queue itself are pinned by
 * [eu.kanade.translation.coordinator.ReaderTranslationCoordinator].
 */
class ReaderPageWorkResolverLifecycleTest {

    private val firstSession = ReaderSessionToken(chapterIdentity = "1:2:7", readerGeneration = 1L)
    private val replacementSession = ReaderSessionToken(chapterIdentity = "1:2:7", readerGeneration = 2L)

    @Test
    fun `source readiness reconciles the active session and manual demand wakes with auto off`() {
        val chapter = readerChapter(7L)

        shouldReconcileReaderSourceReady(
            currentChapter = chapter,
            boundChapter = chapter,
            activeSession = firstSession,
            boundSession = firstSession,
            activeReadinessGeneration = 4L,
            boundReadinessGeneration = 4L,
            translationEnabled = true,
        ) shouldBe true

        // A different chapter must never reconcile another session's callback.
        shouldReconcileReaderSourceReady(
            currentChapter = readerChapter(8L),
            boundChapter = chapter,
            activeSession = firstSession,
            boundSession = firstSession,
            activeReadinessGeneration = 4L,
            boundReadinessGeneration = 4L,
            translationEnabled = true,
        ) shouldBe false
        // A replaced session token fences the stale callback.
        shouldReconcileReaderSourceReady(
            currentChapter = chapter,
            boundChapter = chapter,
            activeSession = replacementSession,
            boundSession = firstSession,
            activeReadinessGeneration = 4L,
            boundReadinessGeneration = 4L,
            translationEnabled = true,
        ) shouldBe false
        // A superseded readiness generation fences an old callback.
        shouldReconcileReaderSourceReady(
            currentChapter = chapter,
            boundChapter = chapter,
            activeSession = firstSession,
            boundSession = firstSession,
            activeReadinessGeneration = 5L,
            boundReadinessGeneration = 4L,
            translationEnabled = true,
        ) shouldBe false
        // Shared reader semantics: a deferred manual demand wakes with auto off.
        shouldReconcileReaderSourceReady(
            currentChapter = chapter,
            boundChapter = chapter,
            activeSession = firstSession,
            boundSession = firstSession,
            activeReadinessGeneration = 4L,
            boundReadinessGeneration = 4L,
            translationEnabled = false,
        ) shouldBe false
    }

    @Test
    fun `late resolver cannot resolve or open old page resources after session replacement`() {
        var opened = false
        val oldPage = ReaderPage(
            index = 0,
            url = "old-page",
            originalStream = {
                opened = true
                ByteArrayInputStream(byteArrayOf(1))
            },
        )
        val newPage = ReaderPage(
            index = 0,
            url = "new-page",
            originalStream = { ByteArrayInputStream(byteArrayOf(2)) },
        )
        val resolver = ReaderPageWorkResolver(chapterCache = null)
        val oldResolver = resolver.bind(
            session = firstSession,
            store = Any(),
            pages = listOf(oldPage),
        )
        val oldWork = oldResolver(0)!!

        val newResolver = resolver.bind(
            session = replacementSession,
            store = Any(),
            pages = listOf(newPage),
        )

        oldResolver(0) shouldBe null
        assertThrows<IllegalStateException> { oldWork.streamFn!!.invoke() }
        opened shouldBe false
        newResolver(0)?.pageKey shouldBe "new-page"

        resolver.invalidate()
        newResolver(0) shouldBe null
    }

    @Test
    fun `same session page-list replacement keeps already-issued stream handles usable`() {
        val oldPage = ReaderPage(
            index = 0,
            url = "same-page-old-anchor",
            originalStream = { ByteArrayInputStream(byteArrayOf(1)) },
        )
        val newPage = ReaderPage(
            index = 0,
            url = "same-page-new-anchor",
            originalStream = { ByteArrayInputStream(byteArrayOf(2)) },
        )
        val resolver = ReaderPageWorkResolver(chapterCache = null)
        val store = Any()
        val firstResolver = resolver.bind(
            session = firstSession,
            store = store,
            pages = listOf(oldPage),
        )
        val oldWork = firstResolver(0)!!

        val secondResolver = resolver.bind(
            session = firstSession,
            store = store,
            pages = listOf(newPage),
        )

        firstResolver(0) shouldBe null
        oldWork.streamFn!!.invoke().use { it.read() } shouldBe 1
        secondResolver(0)!!.streamFn!!.invoke().use { it.read() } shouldBe 2
        secondResolver(0)?.pageKey shouldBe "same-page-new-anchor"
        secondResolver(0)?.readerPageIndex shouldBe 0
    }

    @Test
    fun `same-key store replacement clears old resolver and issued resources`() {
        var oldOpened = false
        val oldPage = ReaderPage(
            index = 0,
            url = "old-store-page",
            originalStream = {
                oldOpened = true
                ByteArrayInputStream(byteArrayOf(3))
            },
        )
        val replacementPage = ReaderPage(
            index = 0,
            url = "replacement-store-page",
            originalStream = { ByteArrayInputStream(byteArrayOf(4)) },
        )
        val resolver = ReaderPageWorkResolver(chapterCache = null)
        val oldStoreToken = Any()
        val newStoreToken = Any()
        val oldResolver = resolver.bind(
            session = firstSession,
            store = oldStoreToken,
            pages = listOf(oldPage),
        )
        val oldWork = oldResolver(0)!!
        val newResolver = resolver.bind(
            session = firstSession,
            store = newStoreToken,
            pages = listOf(replacementPage),
        )

        oldResolver(0) shouldBe null
        assertThrows<IllegalStateException> { oldWork.streamFn!!.invoke() }
        oldOpened shouldBe false
        newResolver(0)!!.pageKey shouldBe "replacement-store-page"
    }

    @Test
    fun `resolver prefers the live original stream and reports streamless local pages`() {
        var originalOpened = false
        val streamedPage = ReaderPage(
            index = 0,
            url = "streamed-page",
            originalStream = {
                originalOpened = true
                ByteArrayInputStream(byteArrayOf(9))
            },
        )
        val streamlessPage = ReaderPage(index = 1, url = "local-page")
        val localChapter = readerChapter(7L).also {
            it.pageLoader = object : PageLoader() {
                override var isLocal: Boolean = true
                override suspend fun getPages(): List<ReaderPage> = emptyList()
            }
            it.state = ReaderChapter.State.Loaded(listOf(streamedPage, streamlessPage))
        }
        streamedPage.chapter = localChapter
        streamlessPage.chapter = localChapter

        val resolver = ReaderPageWorkResolver(chapterCache = null)
        val resolve = resolver.bind(
            session = firstSession,
            store = Any(),
            pages = listOf(streamedPage, streamlessPage),
        )

        val streamed = resolve(0)!!
        streamed.readerPageIndex shouldBe 0
        streamed.streamFn!!.invoke().use { it.read() } shouldBe 9
        originalOpened shouldBe true

        // A local loader page without bytes still resolves; the queue owns the
        // typed SourceUnavailable deferral for its null stream factory.
        val streamless = resolve(1)!!
        streamless.pageKey shouldBe "local-page"
        streamless.streamFn shouldBe null
    }

    @Test
    fun `non-cooperative reader stop defers recycle and fences issued streams`() {
        val stop = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var opened = false
        val page = ReaderPage(
            index = 0,
            url = "non-cooperative-old-page",
            originalStream = {
                opened = true
                ByteArrayInputStream(byteArrayOf(5))
            },
        )
        val resolver = ReaderPageWorkResolver(chapterCache = null)
        val issuedWork = resolver.bind(
            session = firstSession,
            store = null,
            pages = listOf(page),
        )(0)!!
        // The reader invalidates the resolver before it asks the manager to stop; the old
        // Epub/HTTP-like factory must therefore be inert even while the stop is still pending.
        resolver.invalidate()
        registerReaderCleanupAfterStop(stop) { events += "recycle" }

        events shouldBe emptyList()
        events += "stop-requested"
        events shouldBe listOf("stop-requested")

        stop.complete(Unit)
        events shouldBe listOf("stop-requested", "recycle")
        assertThrows<IllegalStateException> { issuedWork.streamFn!!.invoke() }
        opened shouldBe false
    }

    @Test
    fun `delete action cannot run before resolver reset`() {
        val events = mutableListOf<String>()
        runAfterReaderAutoReset(
            reset = { events += "resolver-reset" },
            action = { events += "delete" },
        )

        events shouldBe listOf("resolver-reset", "delete")
    }

    @Test
    fun `page loader readiness hook is cleared before recycled resources can signal`() {
        val loader = TestPageLoader()
        var reconcileCount = 0
        loader.onPageStreamReady = { reconcileCount++ }

        loader.signalSourceReady()
        reconcileCount shouldBe 1

        loader.recycle()
        loader.signalSourceReady()
        reconcileCount shouldBe 1
    }

    private fun readerChapter(id: Long): ReaderChapter = ReaderChapter(
        ChapterImpl().apply {
            this.id = id
            manga_id = 1L
            url = "chapter-$id"
            name = "Chapter $id"
        },
    )

    private class TestPageLoader : PageLoader() {
        override var isLocal: Boolean = false

        override suspend fun getPages(): List<ReaderPage> = emptyList()

        fun signalSourceReady() {
            notifyPageStreamReady()
        }
    }
}
