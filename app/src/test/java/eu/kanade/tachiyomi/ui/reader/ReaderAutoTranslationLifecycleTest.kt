package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.scheduling.AutoActivityStatus
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.AutoDeferralReason
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.AutoTranslationSnapshot
import eu.kanade.translation.scheduling.AutoWindowBounds
import eu.kanade.translation.scheduling.AutoWindowSlot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

class ReaderAutoTranslationLifecycleTest {

    private val firstIdentity = AutoChapterIdentity(chapterId = 7L, sessionKey = "reader-a:one")
    private val replacementIdentity = AutoChapterIdentity(chapterId = 8L, sessionKey = "reader-a:two")

    @Test
    fun `source readiness accepts only the active chapter generation and preferences`() {
        val chapter = readerChapter(7L)

        shouldReconcileAutoSourceReady(
            currentChapter = chapter,
            boundChapter = chapter,
            activeIdentity = firstIdentity,
            boundIdentity = firstIdentity,
            activeGeneration = 4L,
            boundGeneration = 4L,
            translationEnabled = true,
            autoTranslate = true,
        ) shouldBe true

        shouldReconcileAutoSourceReady(
            currentChapter = readerChapter(8L),
            boundChapter = chapter,
            activeIdentity = firstIdentity,
            boundIdentity = firstIdentity,
            activeGeneration = 4L,
            boundGeneration = 4L,
            translationEnabled = true,
            autoTranslate = true,
        ) shouldBe false
        shouldReconcileAutoSourceReady(
            currentChapter = chapter,
            boundChapter = chapter,
            activeIdentity = replacementIdentity,
            boundIdentity = firstIdentity,
            activeGeneration = 5L,
            boundGeneration = 4L,
            translationEnabled = true,
            autoTranslate = true,
        ) shouldBe false
        shouldReconcileAutoSourceReady(
            currentChapter = chapter,
            boundChapter = chapter,
            activeIdentity = firstIdentity,
            boundIdentity = firstIdentity,
            activeGeneration = 4L,
            boundGeneration = 4L,
            translationEnabled = false,
            autoTranslate = true,
        ) shouldBe false
    }

    @Test
    fun `stale switching snapshots are ignored while null remains a lifecycle reset`() {
        isReaderAutoTranslationSnapshotForIdentity(
            snapshot = AutoTranslationSnapshot(
                identity = replacementIdentity,
                visiblePageIndex = 1,
                configuredAheadTarget = 1,
                availableAheadTarget = 0,
                foreground = null,
                aheadSlots = emptyList(),
            ),
            expectedIdentity = firstIdentity,
        ) shouldBe false
        isReaderAutoTranslationSnapshotForIdentity(
            snapshot = null,
            expectedIdentity = firstIdentity,
        ) shouldBe true
        isReaderAutoTranslationSnapshotForIdentity(
            snapshot = null,
            expectedIdentity = null,
        ) shouldBe true
        isReaderAutoTranslationSnapshotForIdentity(
            snapshot = AutoTranslationSnapshot(
                identity = firstIdentity,
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                availableAheadTarget = 0,
                foreground = null,
                aheadSlots = emptyList(),
            ),
            expectedIdentity = null,
        ) shouldBe false
    }

    @Test
    fun `reader snapshot fence rejects lower owner and window versions during rapid replay`() {
        isReaderAutoTranslationSnapshotVersionAccepted(
            snapshot = snapshot(ownerVersion = 11L, windowVersion = 8L),
            expectedIdentity = firstIdentity,
            acceptedOwnerVersion = 11L,
            acceptedWindowVersion = 7L,
        ) shouldBe true
        isReaderAutoTranslationSnapshotVersionAccepted(
            snapshot = snapshot(ownerVersion = 11L, windowVersion = 6L),
            expectedIdentity = firstIdentity,
            acceptedOwnerVersion = 11L,
            acceptedWindowVersion = 7L,
        ) shouldBe false
        isReaderAutoTranslationSnapshotVersionAccepted(
            snapshot = snapshot(ownerVersion = 11L, windowVersion = 7L),
            expectedIdentity = firstIdentity,
            acceptedOwnerVersion = 11L,
            acceptedWindowVersion = 7L,
            requireStrictlyNew = true,
        ) shouldBe false
        isReaderAutoTranslationSnapshotVersionAccepted(
            snapshot = snapshot(ownerVersion = 10L, windowVersion = 99L),
            expectedIdentity = firstIdentity,
            acceptedOwnerVersion = 11L,
            acceptedWindowVersion = 7L,
        ) shouldBe false
        isReaderAutoTranslationSnapshotVersionAccepted(
            snapshot = snapshot(ownerVersion = 12L, windowVersion = 1L),
            expectedIdentity = firstIdentity,
            acceptedOwnerVersion = 11L,
            acceptedWindowVersion = 8L,
        ) shouldBe true
        isReaderAutoTranslationSnapshotVersionAccepted(
            snapshot = snapshot(identity = replacementIdentity, ownerVersion = 12L, windowVersion = 2L),
            expectedIdentity = firstIdentity,
            acceptedOwnerVersion = 11L,
            acceptedWindowVersion = 8L,
        ) shouldBe false
    }

    @Test
    fun `late resolver cannot resolve or open old page resources after replacement`() {
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
        val resolver = ReaderAutoTranslationPageResolver(chapterCache = null)
        val oldResolver = resolver.bind(
            identity = firstIdentity,
            pages = listOf(oldPage),
            ownerVersion = null,
            ownerToken = null,
        )
        val oldItem = oldResolver(0)!!

        val newResolver = resolver.bind(
            identity = replacementIdentity,
            pages = listOf(newPage),
            ownerVersion = null,
            ownerToken = null,
        )

        oldResolver(0) shouldBe null
        assertThrows<IllegalStateException> { oldItem.streamFn!!.invoke() }
        opened shouldBe false
        newResolver(0)?.pageKey shouldBe "new-page"

        resolver.invalidate()
        newResolver(0) shouldBe null
    }

    @Test
    fun `same identity anchor replacement keeps already-issued stream handles usable`() {
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
        val resolver = ReaderAutoTranslationPageResolver(chapterCache = null)
        val ownerToken = Any()
        val firstResolver = resolver.bind(
            identity = firstIdentity,
            pages = listOf(oldPage),
            ownerVersion = 4L,
            ownerToken = ownerToken,
        )
        val oldItem = firstResolver(0)!!

        val secondResolver = resolver.bind(
            identity = firstIdentity,
            pages = listOf(newPage),
            ownerVersion = 4L,
            ownerToken = ownerToken,
        )

        firstResolver(0) shouldBe null
        oldItem.streamFn!!.invoke().use { it.read() } shouldBe 1
        secondResolver(0)!!.streamFn!!.invoke().use { it.read() } shouldBe 2
        secondResolver(0)?.pageKey shouldBe "same-page-new-anchor"
    }

    @Test
    fun `same-key store replacement clears old owner resolver and issued resources`() {
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
        val resolver = ReaderAutoTranslationPageResolver(chapterCache = null)
        val oldStoreToken = Any()
        val newStoreToken = Any()
        val oldResolver = resolver.bind(
            identity = firstIdentity,
            pages = listOf(oldPage),
            ownerVersion = 8L,
            ownerToken = oldStoreToken,
        )
        val oldItem = oldResolver(0)!!
        val newResolver = resolver.bind(
            identity = firstIdentity,
            pages = listOf(replacementPage),
            ownerVersion = 9L,
            ownerToken = newStoreToken,
        )

        oldResolver(0) shouldBe null
        assertThrows<IllegalStateException> { oldItem.streamFn!!.invoke() }
        oldOpened shouldBe false
        newResolver(0)!!.pageKey shouldBe "replacement-store-page"
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
        val resolver = ReaderAutoTranslationPageResolver(chapterCache = null)
        val issuedItem = resolver.bind(
            identity = firstIdentity,
            pages = listOf(page),
            ownerVersion = null,
            ownerToken = null,
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
        assertThrows<IllegalStateException> { issuedItem.streamFn!!.invoke() }
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

    @Test
    fun `chapter end clamps the rolling ahead target without including visible page`() {
        val bounds = AutoWindowBounds(
            visiblePageIndex = 8,
            configuredAheadTarget = 5,
            pageCount = 10,
        )

        bounds.aheadPageIndices shouldBe listOf(9)
        bounds.aheadPageIndices.contains(bounds.visiblePageIndex) shouldBe false
    }

    @Test
    fun `memory recovery keeps the same anchor and exposes the deferred ahead slot`() {
        val snapshot = AutoTranslationSnapshot(
            identity = firstIdentity,
            visiblePageIndex = 4,
            configuredAheadTarget = 1,
            availableAheadTarget = 1,
            foreground = AutoWindowSlot(4, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Deferred(AutoDeferralReason.Memory)),
            ),
        )

        val projected = projectReaderAutoTranslationUiState(snapshot, firstIdentity)
        projected.visiblePageIndex shouldBe 4
        projected.pauseReason shouldBe AutoDeferralReason.Memory
        projected.availableAheadTarget shouldBe 1
    }

    @Test
    fun `manual or revision reset projects a neutral auto state`() {
        val reset = ReaderAutoTranslationUiState.empty()
        reset.orderedSlots shouldBe emptyList()
        reset.activity shouldBe AutoActivityStatus.Idle
    }

    private fun readerChapter(id: Long): ReaderChapter = ReaderChapter(
        ChapterImpl().apply {
            this.id = id
            manga_id = 1L
            url = "chapter-$id"
            name = "Chapter $id"
        },
    )

    private fun snapshot(
        identity: AutoChapterIdentity = firstIdentity,
        ownerVersion: Long,
        windowVersion: Long,
    ): AutoTranslationSnapshot = AutoTranslationSnapshot(
        identity = identity,
        visiblePageIndex = 0,
        configuredAheadTarget = 1,
        availableAheadTarget = 0,
        foreground = null,
        aheadSlots = emptyList(),
        ownerVersion = ownerVersion,
        windowVersion = windowVersion,
    )

    private class TestPageLoader : PageLoader() {
        override var isLocal: Boolean = false

        override suspend fun getPages(): List<ReaderPage> = emptyList()

        fun signalSourceReady() {
            notifyPageStreamReady()
        }
    }
}
