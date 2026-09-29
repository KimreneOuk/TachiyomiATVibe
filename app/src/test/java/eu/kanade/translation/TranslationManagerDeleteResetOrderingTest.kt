package eu.kanade.translation

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.workflow.ChapterTranslator
import eu.kanade.translation.workflow.DurableChapterKey
import eu.kanade.translation.workflow.DurableChapterStatusResolver
import eu.kanade.translation.workflow.DurableStatus
import eu.kanade.translation.workflow.TranslationManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the required delete/reset order: cancellation and worker joins precede store eviction,
 * reader-stream clearing, artifact deletion, cache invalidation, and companion-image retirement.
 * Exact event sequences protect this teardown contract from accidental reordering.
 */
class TranslationManagerDeleteResetOrderingTest {

    private val events = mutableListOf<String>()

    private fun log(event: String) {
        events += event
    }

    /** Cache whose clear() is observable in the event stream. */
    private fun observableCache(): ConcurrentHashMap<Any, Any> = object : ConcurrentHashMap<Any, Any>() {
        override fun clear() {
            log("clearDurableStatusCache")
            super.clear()
        }
    }

    private fun queuedTranslation(chapter: Chapter, manga: Manga, source: HttpSource): Translation =
        Translation(source, manga, chapter).also { it.status = Translation.State.QUEUE }

    private fun storeWithPage(pageKey: String): ChapterTranslationStore {
        val store = mockk<ChapterTranslationStore>(relaxed = true)
        every { store.state } returns MutableStateFlow(
            mapOf(pageKey to PageTranslation(cleanedImageName = "old.jpg")),
        )
        coEvery { store.markDefunct() } coAnswers { log("markDefunct") }
        coEvery { store.updatePageFromCurrentSnapshot(any(), any(), any()) } answers {
            log("updatePageFromCurrentSnapshot")
            ChapterTranslationStore.PatchResult.Accepted(
                mockk<ChapterTranslationStore.PageSnapshot>(relaxed = true),
            )
        }
        coEvery { store.demoteCommittedDisplay(any(), any()) } answers {
            log("demoteCommittedDisplay")
            Unit
        }
        coEvery { store.deletePage(any()) } answers {
            log("deletePage")
            Unit
        }
        coEvery { store.flush() } answers {
            log("storeFlush")
            Unit
        }
        return store
    }

    private fun newManager(
        store: ChapterTranslationStore,
        queue: MutableStateFlow<List<Translation>>,
    ): TranslationManager {
        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L

        val scheduler = mockk<TranslationScheduler>(relaxed = true)
        every { scheduler.cancelAutoTranslations(any()) } answers {
            log("cancelAutoTranslations")
            true
        }
        coEvery { scheduler.cancelPageTranslations(any()) } answers {
            log("cancelPageTranslations")
            Unit
        }
        every { scheduler.cancelPageTranslation(any(), any()) } answers {
            log("cancelPageTranslation")
            true
        }

        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue
        every { translator.isRunning } returns false
        every { translator.removeFromQueue(any<Chapter>()) } answers { log("removeFromQueue") }
        coEvery { translator.cancelTranslatorJobAndJoin() } answers { log("cancelTranslatorJobAndJoin") }

        val provider = mockk<TranslationFileProvider>(relaxed = true)
        every { provider.findMangaDir(any(), any()) } returns null
        every {
            provider.findTranslationFile(any(), any(), any(), any())
        } answers {
            log("findTranslationFile")
            null
        }
        every { provider.findCompanionImageDir(any(), any(), any(), any()) } answers {
            log("findCompanionImageDir")
            null
        }
        every { provider.findPageCleanedImage(any(), any(), any(), any(), any()) } answers {
            log("findPageCleanedImage")
            null
        }

        val streamRegistry = mockk<TranslationStreamRegistry>(relaxed = true)
        every { streamRegistry.clearChapter(any(), any(), any()) } answers { log("clearChapter") }
        every { streamRegistry.clearPage(any(), any(), any(), any()) } answers { log("clearPage") }
        every {
            streamRegistry.retireCleanedImage(any(), any(), any(), any(), any(), any())
        } answers { log("retirePageCompanionImage") }

        val batchTrackerRegistry = mockk<TranslationBatchTrackerRegistry>(relaxed = true)
        every { batchTrackerRegistry.dispose(any()) } answers { log("disposeBatchTracker") }

        val sourceManager = mockk<SourceManager>(relaxed = true)
        val activeStores = ActiveChapterStoreRegistry().apply { register(42L, store) }
        val manager = TranslationManager.createForTesting(
            context = mockk(relaxed = true),
            provider = provider,
            sourceManager = sourceManager,
            translationPreferences = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            pipeline = mockk(relaxed = true),
            translator = translator,
            pendingRequestStore = mockk(relaxed = true),
            scheduler = scheduler,
            streamRegistry = streamRegistry,
        )
        setField(manager, "scheduler", scheduler)
        setField(manager, "activeStores", activeStores)
        setField(manager, "applicationScope", CoroutineScope(SupervisorJob() + Dispatchers.IO))
        setField(manager, "readerTeardownMutex", Mutex())
        setField(manager, "batchTrackerRegistry", batchTrackerRegistry)

        val durableResolver = DurableChapterStatusResolver(
            providerProvider = { provider },
            sourceManagerProvider = { sourceManager },
            activeStoresProvider = { activeStores },
        )
        setField(durableResolver, "durableStatusCache", observableCache())
        setField(manager, "durableStatusResolver\$delegate", lazyOf(durableResolver))
        return manager
    }

    @Test
    fun `deleteTranslation teardown and deletion are strictly sequenced`() = runBlocking<Unit> {
        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        val manager = newManager(
            store = storeWithPage("p1"),
            queue = MutableStateFlow(listOf(queuedTranslation(chapter, manga, source))),
        )
        // The delete wipe must also drop the memoized translation document —
        // the two caches share one invalidation call, and nothing else pins
        // that coupling (review F5.3).
        val documentMemo = ConcurrentHashMap<Any, Any>()
        documentMemo[DurableChapterKey(42L, "Chapter 1", null, "Manga", 77L)] =
            DurableStatus(Translation.State.NOT_TRANSLATED)
        setField(durableResolver(manager), "durableDocumentCache", documentMemo)
        events.clear()

        manager.deleteTranslation(chapter, manga, source)

        assertTrue(documentMemo.isEmpty(), "deleteTranslation must drop the document memo with the status cache")
        assertTrue(
            events == listOf(
                // Preamble: capture the durable truth before any teardown (SAF reads).
                "findTranslationFile",
                // Step 1: cancelAutoTranslations stops new auto dispatch.
                "cancelAutoTranslations",
                // Step 2: cancelPageTranslations cancels + joins single-page jobs.
                "cancelPageTranslations",
                // Step 3: removeFromTranslationQueue drops the batch entry...
                "removeFromQueue",
                // ...and cancelTranslatorJobAndJoin JOINs the batch worker.
                "cancelTranslatorJobAndJoin",
                // Tracker disposal before eviction.
                "disposeBatchTracker",
                // Step 4: unregisterActiveTranslationStore marks the store
                // defunct BEFORE the registry removal's durable-cache clear.
                "markDefunct",
                "clearDurableStatusCache",
                // Step 5: stale reader closures pointing at the deleted PNGs are dropped.
                "clearChapter",
                // Step 6: legacy flat files stay on disk; only artifact
                // authority is removed. Companion-image retirement runs after
                // the manifest removal.
                "findCompanionImageDir",
                // The durable-status cache is invalidated once everything is wound down.
                "clearDurableStatusCache",
            ),
            "deleteTranslation ordering changed: $events",
        )
    }

    @Test
    fun `resetChapterTranslationData invalidates cache, cancels, mutates, demotes, flushes twice`() = runBlocking<Unit> {
        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        val manager = newManager(
            store = storeWithPage("p1"),
            queue = MutableStateFlow(listOf(queuedTranslation(chapter, manga, source))),
        )
        events.clear()

        manager.resetChapterTranslationData(chapter, manga, source, preserveEdits = false)

        assertTrue(
            events == listOf(
                "clearDurableStatusCache",
                "cancelAutoTranslations",
                "cancelPageTranslations",
                "removeFromQueue",
                "cancelTranslatorJobAndJoin",
                "clearChapter",
                "updatePageFromCurrentSnapshot",
                "demoteCommittedDisplay",
                "storeFlush",
                // reconcileBatchProgress flushes the active store again.
                "storeFlush",
            ),
            "resetChapterData ordering changed: $events",
        )
    }

    @Test
    fun `resetOcrData and deletePageTranslation cancel the page, delete, flush, and retire images in order`() =
        runBlocking<Unit> {
            val source = mockk<HttpSource>(relaxed = true)
            val manga = mockk<Manga>(relaxed = true)
            val chapter = mockk<Chapter>(relaxed = true)
            every { chapter.id } returns 42L
            val manager = newManager(
                store = storeWithPage("p1"),
                queue = MutableStateFlow(emptyList()),
            )
            events.clear()

            manager.resetOcrData(chapter, manga, source, "p1")

            assertTrue(
                events == listOf(
                    "clearDurableStatusCache",
                    "cancelPageTranslation",
                    "clearPage",
                    "deletePage",
                    "storeFlush",
                    // One companion-dir scan feeds the retired-name set; each
                    // name (persisted + fixed publication names) is then
                    // retired through the stream registry (the registry
                    // invokes the delete callback itself, so it is not part
                    // of this synchronous event stream).
                    "findCompanionImageDir",
                    "retirePageCompanionImage",
                    "retirePageCompanionImage",
                    "retirePageCompanionImage",
                    "retirePageCompanionImage",
                ),
                "resetOcrData ordering changed: $events",
            )

            // deletePageTranslation is documented as routing to resetOcrData; the
            // same ordering must hold through the alias.
            events.clear()
            manager.deletePageTranslation(chapter, manga, source, "p1")
            assertTrue(events.first() == "clearDurableStatusCache", "alias ordering changed: $events")
            assertTrue(events.contains("deletePage") && events.contains("clearPage"), "alias ordering changed: $events")
        }

    private fun durableResolver(manager: TranslationManager): DurableChapterStatusResolver {
        val field = manager.javaClass.getDeclaredField("durableStatusResolver\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(manager) as Lazy<DurableChapterStatusResolver>).value
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
}
