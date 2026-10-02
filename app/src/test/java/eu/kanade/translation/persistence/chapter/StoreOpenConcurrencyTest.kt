package eu.kanade.translation.persistence.chapter

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class StoreOpenConcurrencyTest {
    @Test
    fun `concurrent chapter opens keep the first registered store`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val chapterId = 73L
        val fileKey = "chapter-73"
        val creationStarted = CompletableDeferred<Unit>()
        val releaseCreation = CompletableDeferred<ChapterTranslationStore>()
        val createdStore = ChapterTranslationStore(null, null)
        var createCount = 0

        val firstOpen = async {
            registry.getOrCreate(chapterId, fileKey) {
                createCount += 1
                creationStarted.complete(Unit)
                releaseCreation.await()
            }
        }
        var secondOpen: Deferred<ChapterTranslationStore?>? = null

        try {
            creationStarted.await()
            val concurrentOpen = async {
                registry.getOrCreate(chapterId, fileKey) {
                    error("the second open must keep the store created by the first open")
                }
            }
            secondOpen = concurrentOpen
            runCurrent()
            releaseCreation.complete(createdStore)

            firstOpen.await() shouldBe createdStore
            concurrentOpen.await() shouldBe createdStore
            createCount shouldBe 1
            registry.get(chapterId) shouldBe createdStore
            registry.getByFile(fileKey) shouldBe createdStore

            registry.getOrCreate(chapterId, fileKey) {
                error("the registry fast path must return the active store")
            } shouldBe createdStore
            createCount shouldBe 1
        } finally {
            releaseCreation.complete(createdStore)
            firstOpen.cancelAndJoin()
            secondOpen?.cancelAndJoin()
            registry.remove(chapterId)
            createdStore.closeAndFlush()
        }
    }
}
