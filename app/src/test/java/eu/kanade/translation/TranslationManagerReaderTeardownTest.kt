package eu.kanade.translation

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.workflow.ChapterTranslator
import eu.kanade.translation.workflow.TranslationManager
import eu.kanade.translation.workflow.TranslationSessionCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TranslationManagerReaderTeardownTest {

    @Test
    fun `reader stop returns before store cleanup completes and preserves active batch store`() = runBlocking<Unit> {
        val cleanupStarted = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val cleanupThread = AtomicReference<Thread>()
        val readerStoreDefunct = AtomicBoolean(false)
        val batchStoreDefunct = AtomicBoolean(false)

        val readerStore = mockk<ChapterTranslationStore>(relaxed = true)
        coEvery { readerStore.clearTransientQueuePages(any()) } coAnswers {
            cleanupThread.set(Thread.currentThread())
            cleanupStarted.countDown()
            cleanupRelease.await(5, TimeUnit.SECONDS)
            Unit
        }
        every { readerStore.markDefunct() } answers { readerStoreDefunct.set(true) }

        val batchStore = mockk<ChapterTranslationStore>(relaxed = true)
        every { batchStore.markDefunct() } answers { batchStoreDefunct.set(true) }

        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        val activeBatch = Translation(source, manga, chapter).also {
            it.status = Translation.State.QUEUE
        }
        val queue = MutableStateFlow(listOf(activeBatch))
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue

        val scheduler = mockk<TranslationScheduler>(relaxed = true)
        val activeStores = ActiveChapterStoreRegistry().apply {
            register(41L, readerStore)
            register(42L, batchStore)
        }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(scheduler, translator, activeStores, managerScope)
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

            withTimeout(5_000) { while (entryReturned.count > 0L) delay(10) }
            assertTrue(
                entryReturned.count == 0L,
                "reader lifecycle entry must not wait for store persistence",
            )
            withTimeout(5_000) { while (cleanupStarted.count > 0L) delay(10) }
            assertNotSame(callerThread.get(), cleanupThread.get())
            assertFalse(batchStoreDefunct.get(), "batch-owned store must remain registered")

            cleanupRelease.countDown()
            callerFuture.get(5, TimeUnit.SECONDS)
            withTimeout(5_000) {
                while (!readerStoreDefunct.get()) delay(10)
            }
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
        every { batchStore.markDefunct() } answers { storeDefunct.set(true) }

        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        val pausedBatch = Translation(source, manga, chapter).also {
            it.status = Translation.State.PAUSED
        }
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(listOf(pausedBatch))
        val scheduler = mockk<TranslationScheduler>(relaxed = true)
        val activeStores = ActiveChapterStoreRegistry().apply {
            register(42L, batchStore)
        }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(scheduler, translator, activeStores, managerScope)

        try {
            manager.requestReaderStop("reader closed").await()
            assertFalse(storeDefunct.get(), "paused batch artifacts must remain available for retry")
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `await reader stop moves scheduler teardown off caller thread`() = runBlocking<Unit> {
        val schedulerStarted = CountDownLatch(1)
        val schedulerRelease = CountDownLatch(1)
        val schedulerThread = AtomicReference<Thread>()
        val scheduler = mockk<TranslationScheduler>(relaxed = true)
        coEvery { scheduler.awaitReaderStop() } coAnswers {
            schedulerThread.set(Thread.currentThread())
            schedulerStarted.countDown()
            schedulerRelease.await(5, TimeUnit.SECONDS)
            Unit
        }
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(emptyList())
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(
            scheduler = scheduler,
            translator = translator,
            activeStores = ActiveChapterStoreRegistry(),
            applicationScope = managerScope,
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

            withTimeout(5_000) { while (schedulerStarted.count > 0L) delay(10) }
            assertTrue(schedulerStarted.count == 0L)
            assertNotSame(callerThread.get(), schedulerThread.get())
            schedulerRelease.countDown()
            withTimeout(5_000) { while (callerFinished.count > 0L) delay(10) }
            assertTrue(callerFinished.count == 0L)
            callerFuture.get(5, TimeUnit.SECONDS)
        } finally {
            schedulerRelease.countDown()
            readerExecutor.shutdownNow()
            managerScope.cancel()
        }
    }

    @Test
    fun `request reader stop has no undispatched scheduler prefix`() = runBlocking<Unit> {
        val schedulerStarted = CountDownLatch(1)
        val schedulerRelease = CountDownLatch(1)
        val schedulerThread = AtomicReference<Thread>()
        val scheduler = mockk<TranslationScheduler>(relaxed = true)
        coEvery { scheduler.awaitReaderStop() } coAnswers {
            schedulerThread.set(Thread.currentThread())
            schedulerStarted.countDown()
            schedulerRelease.await(5, TimeUnit.SECONDS)
            Unit
        }
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(emptyList())
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = uninitializedManager(
            scheduler = scheduler,
            translator = translator,
            activeStores = ActiveChapterStoreRegistry(),
            applicationScope = managerScope,
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

            withTimeout(5_000) { while (entryReturned.count > 0L) delay(10) }
            assertTrue(
                entryReturned.count == 0L,
                "requestReaderStop must return before scheduler teardown joins",
            )
            withTimeout(5_000) { while (schedulerStarted.count > 0L) delay(10) }
            assertTrue(schedulerStarted.count == 0L)
            assertNotSame(callerThread.get(), schedulerThread.get())
            schedulerRelease.countDown()
            deferredRef.get().await()
            callerFuture.get(5, TimeUnit.SECONDS)
        } finally {
            schedulerRelease.countDown()
            readerExecutor.shutdownNow()
            managerScope.cancel()
        }
    }

    private fun uninitializedManager(
        scheduler: TranslationScheduler,
        translator: ChapterTranslator,
        activeStores: ActiveChapterStoreRegistry,
        applicationScope: CoroutineScope,
    ): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "scheduler", scheduler)
        setField(manager, "translator", translator)
        // Unsafe.allocateInstance skips the manager's admission-owner
        // initializer; ReaderTeardownCoordinator now aborts a pending
        // batch-to-reader handoff before it joins reader work.
        setField(manager, "sessionCoordinator", TranslationSessionCoordinator())
        setField(manager, "activeStores", activeStores)
        setField(manager, "applicationScope", applicationScope)
        setField(manager, "readerTeardownMutex", Mutex())
        setField(manager, "durableStatusCache", ConcurrentHashMap<Any, Any>())
        // Unsafe.allocateInstance skips field initializers; the resolver's
        // document-memo provider captures this field and NPEs when unset.
        setField(manager, "durableDocumentCache", ConcurrentHashMap<Any, Any>())
        return manager
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
