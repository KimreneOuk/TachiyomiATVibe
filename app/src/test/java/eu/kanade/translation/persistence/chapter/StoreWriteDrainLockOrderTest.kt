package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.persistence.internal.StoreWriteDrainCoordinator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class StoreWriteDrainLockOrderTest {

    private fun writeDrain(store: ChapterTranslationStore): Any =
        ChapterTranslationStore::class.java.getDeclaredField("writeDrain").apply {
            isAccessible = true
        }.get(store)

    private fun drainJob(store: ChapterTranslationStore): Job? =
        StoreWriteDrainCoordinator::class.java.getDeclaredField("drainJob").apply {
            isAccessible = true
        }.get(writeDrain(store)) as Job?

    @Test
    fun `drain handoff stays synchronous and flush waits on the store mutex`() = runTest {
        val store = ChapterTranslationStore(
            artifactParentResolver = { error("lock-order fixture must not open a legacy file") },
            initialPages = emptyMap(),
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        var scheduledJob: Job? = null

        try {
            store.mutex.lock()
            try {
                // This store-to-drain call is deliberately made while the
                // only state mutex is held. It must return synchronously and
                // repeated requests must reuse the same active drain job.
                store.schedulePendingLazyDrain()
                scheduledJob = drainJob(store)
                scheduledJob shouldNotBe null
                store.schedulePendingLazyDrain()
                drainJob(store) shouldBe scheduledJob

                // Virtual time runs the debounce worker until it reaches the
                // drain-to-store mutex boundary. The active job remains
                // parked there while this coroutine owns the store mutex.
                advanceTimeBy(StoreWriteDrainCoordinator.PERSIST_DEBOUNCE_MS + 1L)
                runCurrent()
                scheduledJob?.isActive shouldBe true
                drainJob(store) shouldBe scheduledJob
            } finally {
                store.mutex.unlock()
            }

            runCurrent()
            scheduledJob?.join()
            drainJob(store) shouldBe null
        } finally {
            store.closeAndFlush()
        }
    }
}
