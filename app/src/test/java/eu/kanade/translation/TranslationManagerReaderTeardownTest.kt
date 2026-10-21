package eu.kanade.translation

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.BatchPageOperation
import eu.kanade.translation.processing.TranslationProcessor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TranslationManagerReaderTeardownTest {

    @Test
    fun `reader teardown clears batch work without closing shared engines`() = runBlocking<Unit> {
        val processor = mockk<TranslationProcessor>(relaxed = true)
        val batchCoordinator = batchCoordinator(processor)
        val activeStores = ActiveChapterStoreRegistry()
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(batchCoordinator, activeStores, managerScope)

        try {
            manager.cancelAllPageTranslations(cancelBatchQueue = true)

            verify(exactly = 0) { processor.closeEngines() }
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `manager clear queue closes shared engines`() {
        val processor = mockk<TranslationProcessor>(relaxed = true)
        val batchCoordinator = batchCoordinator(processor)
        val activeStores = ActiveChapterStoreRegistry()
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(batchCoordinator, activeStores, managerScope)

        try {
            manager.clearQueue()

            verify(exactly = 1) { processor.closeEngines() }
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `reader stop returns before store cleanup completes and preserves active batch store`() = runBlocking<Unit> {
        val cleanupStarted = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val cleanupThread = AtomicReference<Thread>()
        val readerStoreDefunct = CompletableDeferred<Unit>()
        val batchStoreDefunct = AtomicBoolean(false)

        val readerStore = mockk<ChapterTranslationStore>(relaxed = true)
        coEvery { readerStore.clearTransientQueuePages(any()) } coAnswers {
            cleanupThread.set(Thread.currentThread())
            cleanupStarted.countDown()
            cleanupRelease.await(5, TimeUnit.SECONDS)
            Unit
        }
        coEvery { readerStore.markDefunct() } coAnswers {
            readerStoreDefunct.complete(Unit)
            Unit
        }

        val batchStore = mockk<ChapterTranslationStore>(relaxed = true)
        coEvery { batchStore.markDefunct() } coAnswers { batchStoreDefunct.set(true) }

        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        val activeBatch = Translation(source, manga, chapter).also {
            it.status = Translation.State.QUEUE
        }
        val queue = MutableStateFlow(listOf(activeBatch))
        val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
        every { translator.queueState } returns queue

        val activeStores = ActiveChapterStoreRegistry().apply {
            register(41L, readerStore)
            register(42L, batchStore)
        }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(translator, activeStores, managerScope)
        val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "reader-main-test")
        }

        try {
            val callerThread = AtomicReference<Thread>()
            val entryReturned = CountDownLatch(1)
            val callerFuture = readerExecutor.submit {
                callerThread.set(Thread.currentThread())
                try {
                    manager.stopReaderTranslations("reader backgrounded")
                } finally {
                    entryReturned.countDown()
                }
            }

            assertTrue(
                entryReturned.await(5, TimeUnit.SECONDS),
                "reader lifecycle entry must not wait for store persistence",
            )
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS), "reader store cleanup did not start")
            assertNotSame(callerThread.get(), cleanupThread.get())
            assertFalse(batchStoreDefunct.get(), "batch-owned store must remain registered")

            cleanupRelease.countDown()
            callerFuture.get(5, TimeUnit.SECONDS)
            withTimeout(5_000) { readerStoreDefunct.await() }
            assertFalse(batchStoreDefunct.get(), "batch-owned store must not be evicted")
        } finally {
            cleanupRelease.countDown()
            readerExecutor.shutdownNow()
            managerScope.cancel()
        }
    }

    @Test
    fun `reader stop preserves a paused batch store for retry`() = runBlocking<Unit> {
        val storeDefunct = AtomicBoolean(false)
        val batchStore = mockk<ChapterTranslationStore>(relaxed = true)
        coEvery { batchStore.markDefunct() } coAnswers { storeDefunct.set(true) }

        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        val pausedBatch = Translation(source, manga, chapter).also {
            it.status = Translation.State.PAUSED
        }
        val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(listOf(pausedBatch))
        val activeStores = ActiveChapterStoreRegistry().apply {
            register(42L, batchStore)
        }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(translator, activeStores, managerScope)

        try {
            manager.requestReaderStop("reader closed").await()
            assertFalse(storeDefunct.get(), "paused batch artifacts must remain available for retry")
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `await reader stop moves coordinator teardown off caller thread`() = runBlocking<Unit> {
        val coordinatorStarted = CountDownLatch(1)
        val coordinatorRelease = CountDownLatch(1)
        val coordinatorThread = AtomicReference<Thread>()
        val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(emptyList())
        val activeStores = ActiveChapterStoreRegistry().apply {
            register(42L, mockk(relaxed = true))
        }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(
            batchCoordinator = translator,
            activeStores = activeStores,
            applicationScope = managerScope,
            unregisterActiveStore = {
                coordinatorThread.set(Thread.currentThread())
                coordinatorStarted.countDown()
                coordinatorRelease.await(5, TimeUnit.SECONDS)
                activeStores.remove(it)?.markDefunct()
            },
        )
        val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "reader-main-test")
        }

        try {
            val callerThread = AtomicReference<Thread>()
            val callerFinished = CountDownLatch(1)
            val callerFuture = readerExecutor.submit {
                callerThread.set(Thread.currentThread())
                try {
                    runBlocking { manager.awaitReaderStop("chapter switch") }
                } finally {
                    callerFinished.countDown()
                }
            }

            assertTrue(coordinatorStarted.await(5, TimeUnit.SECONDS), "coordinator teardown did not start")
            assertNotSame(callerThread.get(), coordinatorThread.get())
            coordinatorRelease.countDown()
            assertTrue(callerFinished.await(5, TimeUnit.SECONDS), "caller did not finish after coordinator release")
            callerFuture.get(5, TimeUnit.SECONDS)
        } finally {
            coordinatorRelease.countDown()
            readerExecutor.shutdownNow()
            managerScope.cancel()
        }
    }

    @Test
    fun `request reader stop has no undispatched coordinator prefix`() = runBlocking<Unit> {
        val coordinatorStarted = CountDownLatch(1)
        val coordinatorRelease = CountDownLatch(1)
        val coordinatorThread = AtomicReference<Thread>()
        val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(emptyList())
        val activeStores = ActiveChapterStoreRegistry().apply {
            register(42L, mockk(relaxed = true))
        }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(
            batchCoordinator = translator,
            activeStores = activeStores,
            applicationScope = managerScope,
            unregisterActiveStore = {
                coordinatorThread.set(Thread.currentThread())
                coordinatorStarted.countDown()
                coordinatorRelease.await(5, TimeUnit.SECONDS)
                activeStores.remove(it)?.markDefunct()
            },
        )
        val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "reader-main-test")
        }

        try {
            val callerThread = AtomicReference<Thread>()
            val deferredRef = AtomicReference<kotlinx.coroutines.Deferred<Unit>>()
            val entryReturned = CountDownLatch(1)
            val callerFuture = readerExecutor.submit {
                callerThread.set(Thread.currentThread())
                try {
                    deferredRef.set(manager.requestReaderStop("reader closed"))
                } finally {
                    entryReturned.countDown()
                }
            }

            assertTrue(
                entryReturned.await(5, TimeUnit.SECONDS),
                "requestReaderStop must return before coordinator teardown joins",
            )
            assertTrue(coordinatorStarted.await(5, TimeUnit.SECONDS), "coordinator teardown did not start")
            assertNotSame(callerThread.get(), coordinatorThread.get())
            coordinatorRelease.countDown()
            deferredRef.get().await()
            callerFuture.get(5, TimeUnit.SECONDS)
        } finally {
            coordinatorRelease.countDown()
            readerExecutor.shutdownNow()
            managerScope.cancel()
        }
    }

    private fun uninitializedManager(
        batchCoordinator: BatchTranslationCoordinator,
        activeStores: ActiveChapterStoreRegistry,
        applicationScope: CoroutineScope,
        unregisterActiveStore: suspend (Long) -> Unit = { chapterId ->
            activeStores.remove(chapterId)?.markDefunct()
        },
    ): TranslationManager {
        val sessionCoordinator = TranslationSessionCoordinator()
        val readerCoordinator = ReaderTranslationCoordinator(
            processor = mockk(relaxed = true),
            scope = applicationScope,
            activeStoresProvider = { activeStores },
            batchCoordinator = batchCoordinator,
            sessionCoordinator = sessionCoordinator,
            unregisterActiveStore = unregisterActiveStore,
        )
        val manager = TranslationManager.createForTesting(
            context = mockk(relaxed = true),
            provider = mockk(relaxed = true),
            sourceManager = mockk(relaxed = true),
            translationPreferences = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            processor = mockk(relaxed = true),
            batchCoordinator = batchCoordinator,
            pendingRequestStore = mockk(relaxed = true),
            readerCoordinator = readerCoordinator,
            sessionCoordinator = sessionCoordinator,
        )
        setField(manager, "activeStores", activeStores)
        setField(manager, "applicationScope", applicationScope)
        return manager
    }

    private fun batchCoordinator(processor: TranslationProcessor) = BatchTranslationCoordinator(
        context = mockk(relaxed = true),
        provider = mockk<TranslationFileProvider>(relaxed = true),
        downloadProvider = mockk(relaxed = true),
        sourceManager = mockk(relaxed = true),
        translationPreferences = mockk(relaxed = true),
        queueStore = mockk<TranslationQueueStore>(relaxed = true),
        processor = processor,
        pageOperation = mockk<BatchPageOperation>(relaxed = true),
    )

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
