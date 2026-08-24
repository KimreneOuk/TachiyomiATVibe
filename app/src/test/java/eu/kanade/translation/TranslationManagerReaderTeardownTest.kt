package eu.kanade.translation

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.scheduling.TranslationScheduler
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TranslationManagerReaderTeardownTest {

    @Test
    fun `reader stop returns before store cleanup completes and preserves active batch store`() = runBlocking {
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

            assertTrue(
                entryReturned.await(1, TimeUnit.SECONDS),
                "reader lifecycle entry must not wait for store persistence",
            )
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS))
            assertNotSame(callerThread.get(), cleanupThread.get())
            assertFalse(batchStoreDefunct.get(), "batch-owned store must remain registered")

            cleanupRelease.countDown()
            callerFuture.get(1, TimeUnit.SECONDS)
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
    fun `await reader stop moves scheduler teardown off caller thread`() = runBlocking {
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

            assertTrue(schedulerStarted.await(1, TimeUnit.SECONDS))
            assertNotSame(callerThread.get(), schedulerThread.get())
            schedulerRelease.countDown()
            assertTrue(callerFinished.await(1, TimeUnit.SECONDS))
            callerFuture.get(1, TimeUnit.SECONDS)
        } finally {
            schedulerRelease.countDown()
            readerExecutor.shutdownNow()
            managerScope.cancel()
        }
    }

    @Test
    fun `request reader stop has no undispatched scheduler prefix`() = runBlocking {
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

            assertTrue(
                entryReturned.await(1, TimeUnit.SECONDS),
                "requestReaderStop must return before scheduler teardown joins",
            )
            assertTrue(schedulerStarted.await(1, TimeUnit.SECONDS))
            assertNotSame(callerThread.get(), schedulerThread.get())
            schedulerRelease.countDown()
            deferredRef.get().await()
            callerFuture.get(1, TimeUnit.SECONDS)
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
        setField(manager, "activeStores", activeStores)
        setField(manager, "applicationScope", applicationScope)
        setField(manager, "readerTeardownMutex", Mutex())
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
