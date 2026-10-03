package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.persistence.internal.StoreWriteDrainCoordinator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefunctFlushWindowTest {

    private fun drainJob(store: ChapterTranslationStore): Job? {
        val drain = ChapterTranslationStore::class.java.getDeclaredField("writeDrain").apply {
            isAccessible = true
        }.get(store)
        return StoreWriteDrainCoordinator::class.java.getDeclaredField("drainJob").apply {
            isAccessible = true
        }.get(drain) as? Job
    }

    @Test
    fun `eviction joins the active drain before flipping the store defunct`() = runTest {
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )
        val joinStarted = CompletableDeferred<Unit>()
        val releaseJoin = CompletableDeferred<Unit>()
        val evictionReturned = CompletableDeferred<Unit>()
        val firstSignal = CompletableDeferred<Boolean>()
        joinStarted.invokeOnCompletion { firstSignal.complete(true) }
        evictionReturned.invokeOnCompletion { firstSignal.complete(false) }
        val pendingDrain = mockk<Job>(relaxed = true)
        coEvery { pendingDrain.join() } coAnswers {
            joinStarted.complete(Unit)
            releaseJoin.await()
        }
        store.mutex.withLock {
            val drain = ChapterTranslationStore::class.java.getDeclaredField("writeDrain").apply {
                isAccessible = true
            }.get(store)
            StoreWriteDrainCoordinator::class.java.getDeclaredField("drainJob").apply {
                isAccessible = true
            }.set(drain, pendingDrain)
        }

        val eviction = async(Dispatchers.IO) {
            try {
                store.markDefunct()
            } finally {
                evictionReturned.complete(Unit)
            }
        }
        try {
            firstSignal.await() shouldBe true
            store.isDefunct shouldBe false
            evictionReturned.isCompleted shouldBe false
            drainJob(store) shouldNotBe null
        } finally {
            releaseJoin.complete(Unit)
        }

        eviction.await()
        store.isDefunct shouldBe true
        evictionReturned.isCompleted shouldBe true
    }
}
