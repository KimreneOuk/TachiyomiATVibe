package eu.kanade.translation.workflow

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.scheduling.TranslationScheduler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicReference

class EvictionOffMainTest {

    @Test
    fun `reader chapter teardown evicts on IO when called from Main`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mainThread = AtomicReference<Thread>()
        val evictionThread = AtomicReference<Thread>()
        val activeStores = ActiveChapterStoreRegistry()
        val coordinator = ReaderTeardownCoordinator(
            applicationScopeProvider = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
            readerTeardownMutexProvider = { Mutex() },
            schedulerProvider = { mockk<TranslationScheduler>(relaxed = true) },
            activeStoresProvider = { activeStores },
            translatorProvider = { mockk<ChapterTranslator>(relaxed = true) },
            sessionCoordinatorProvider = { mockk<TranslationSessionCoordinator>(relaxed = true) },
            isAnyBatchTranslationActiveProvider = { false },
            isChapterBatchActiveFn = { _, _ -> false },
            unregisterActiveTranslationStoreFn = { evictionThread.set(Thread.currentThread()) },
            disposeBatchTrackerFn = {},
            clearAllPendingTranslationRequestsFn = {},
        )

        try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                mainThread.set(Thread.currentThread())
                coordinator.cancelPageTranslations(chapterId = 42L)
            }

            assertNotNull(evictionThread.get(), "reader teardown did not evict the active store")
            assertNotSame(mainThread.get(), evictionThread.get(), "reader eviction ran on Main")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `await reader stop evicts stores off Main`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mainThread = AtomicReference<Thread>()
        val evictionThread = AtomicReference<Thread>()
        val activeStores = ActiveChapterStoreRegistry().apply {
            register(42L, mockk<ChapterTranslationStore>(relaxed = true))
        }
        val coordinator = ReaderTeardownCoordinator(
            applicationScopeProvider = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
            readerTeardownMutexProvider = { Mutex() },
            schedulerProvider = { mockk<TranslationScheduler>(relaxed = true) },
            activeStoresProvider = { activeStores },
            translatorProvider = { mockk<ChapterTranslator>(relaxed = true) },
            sessionCoordinatorProvider = { mockk<TranslationSessionCoordinator>(relaxed = true) },
            isAnyBatchTranslationActiveProvider = { false },
            isChapterBatchActiveFn = { _, _ -> false },
            unregisterActiveTranslationStoreFn = { evictionThread.set(Thread.currentThread()) },
            disposeBatchTrackerFn = {},
            clearAllPendingTranslationRequestsFn = {},
        )

        try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                mainThread.set(Thread.currentThread())
                coordinator.awaitReaderStop("reader chapter switch")
            }

            assertNotNull(evictionThread.get(), "reader stop did not evict the active store")
            assertNotSame(mainThread.get(), evictionThread.get(), "awaitReaderStop eviction ran on Main")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `delete translation evicts on IO when called from Main`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mainThread = AtomicReference<Thread>()
        val evictionThread = AtomicReference<Thread>()
        val controller = ChapterDataResetController(
            findTranslationDocumentFn = { _, _, _, _ -> null },
            schedulerProvider = { mockk<TranslationScheduler>(relaxed = true) },
            cancelPageTranslationsFn = {},
            cancelPageTranslationFn = { _, _ -> false },
            removeFromTranslationQueueFn = {},
            translatorProvider = { mockk<ChapterTranslator>(relaxed = true) },
            disposeBatchTrackerFn = {},
            unregisterActiveTranslationStoreFn = { evictionThread.set(Thread.currentThread()) },
            streamRegistryProvider = { mockk<TranslationStreamRegistry>(relaxed = true) },
            providerProvider = { mockk<TranslationFileProvider>(relaxed = true) },
            retireChapterCompanionImagesFn = { _, _, _ -> },
            retirePageCompanionImageFn = { _, _, _, _, _ -> },
            durableStatusResolverProvider = { mockk<DurableChapterStatusResolver>(relaxed = true) },
            activeStoresProvider = { ActiveChapterStoreRegistry() },
            openExistingChapterTranslationStoreFn = { _, _, _, _, _ -> null },
        )
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        val manga = mockk<Manga>(relaxed = true)
        val source = mockk<Source>(relaxed = true)

        try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                mainThread.set(Thread.currentThread())
                controller.deleteTranslation(chapter, manga, source)
            }

            assertNotNull(evictionThread.get(), "delete did not evict the active store")
            assertNotSame(mainThread.get(), evictionThread.get(), "delete eviction ran on Main")
        } finally {
            Dispatchers.resetMain()
        }
    }
}
