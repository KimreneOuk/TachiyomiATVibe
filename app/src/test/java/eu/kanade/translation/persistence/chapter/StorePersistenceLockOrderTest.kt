package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.persistence.internal.StorePersistenceScheduler
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class StorePersistenceLockOrderTest {

    private fun persistenceScheduler(store: ChapterTranslationStore): Any =
        ChapterTranslationStore::class.java.getDeclaredField("persistenceScheduler").apply {
            isAccessible = true
        }.get(store)

    private fun persistJob(store: ChapterTranslationStore): Job? =
        StorePersistenceScheduler::class.java.getDeclaredField("persistJob").apply {
            isAccessible = true
        }.get(persistenceScheduler(store)) as Job?

    @Test
    fun `schedule handoff stays synchronous and flush waits on the store mutex`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = { error("lock-order fixture must not open a legacy file") },
            initialPages = emptyMap(),
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        var scheduledJob: Job? = null

        try {
            store.mutex.lock()
            try {
                // This store-to-scheduler call is deliberately made while the
                // only state mutex is held. It must return synchronously and
                // repeated requests must reuse the same active persist job.
                store.schedulePersist()
                scheduledJob = persistJob(store)
                scheduledJob shouldNotBe null
                store.schedulePersist()
                persistJob(store) shouldBe scheduledJob

                // Virtual time runs the debounce worker until it reaches the
                // scheduler-to-store mutex boundary. The active job remains
                // parked there while this coroutine owns the store mutex.
                advanceTimeBy(StorePersistenceScheduler.PERSIST_DEBOUNCE_MS + 1L)
                runCurrent()
                scheduledJob?.isActive shouldBe true
                persistJob(store) shouldBe scheduledJob
            } finally {
                store.mutex.unlock()
            }

            runCurrent()
            scheduledJob?.join()
            persistJob(store) shouldBe null
        } finally {
            store.closeAndFlush()
        }
    }
}
