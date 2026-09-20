package eu.kanade.translation

import eu.kanade.translation.storage.*

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CleanedImagePublisherTest {
    @Test
    fun `old file is deleted only after accepted commit`() = runTest {
        val events = mutableListOf<String>()
        val files = fakeFiles(events)
        val publisher = CleanedImagePublisher(files)
        val store = ChapterTranslationStore(null, null, mapOf("p1" to PageTranslation(cleanedImageName = "old.jpg")))
        val before = store.snapshot("p1")

        val result = publisher.publish("chapter", "p1", "old.jpg", commit = { newName ->
            events += "commit:$newName"
            store.patchPage("p1", before.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
        })

        (result is CleanedImagePublisher.Result.Published) shouldBe true
        events shouldBe listOf("write:new.jpg", "commit:new.jpg", "delete:old.jpg")
    }

    @Test
    fun `rejection preserves current old file and cleans unpublished new file`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val store = ChapterTranslationStore(null, null, mapOf("p1" to PageTranslation(cleanedImageName = "old.jpg")))
        val stale = store.snapshot("p1")
        store.updatePage("p1") { it!!.apply { errorMessage = "newer work" } }

        val result = publisher.publish("chapter", "p1", "old.jpg", commit = { newName ->
            events += "commit:$newName"
            store.patchPage("p1", stale.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
        })

        (result is CleanedImagePublisher.Result.Rejected) shouldBe true
        store.snapshot("p1").page!!.cleanedImageName shouldBe "old.jpg"
        events shouldBe listOf("write:new.jpg", "commit:new.jpg", "delete:new.jpg")
    }

    @Test
    fun `commit exception cleans unpublished new file without deleting old file`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = "old.jpg",
            commit = { error("commit failed") },
        )

        result shouldBe CleanedImagePublisher.Result.Rejected(
            name = "new.jpg",
            reason = "commit failed",
            unpublishedCleaned = true,
        )
        events shouldBe listOf("write:new.jpg", "delete:new.jpg")
    }

    @Test
    fun `write cancellation propagates instead of becoming a retryable write failure`() = runTest {
        val cancellation = CancellationException("cancelled")
        var commitCalled = false
        val publisher = CleanedImagePublisher(object : CleanedImagePublisher.Files {
            override fun writeVerifiedVersionedFile(): String = throw cancellation
            override fun delete(name: String): Boolean = true
        })

        val thrown = try {
            publisher.publish(
                chapter = "chapter",
                pageKey = "p1",
                previousName = "old.jpg",
                commit = {
                    commitCalled = true
                    error("commit must not run after cancellation")
                },
            )
            null
        } catch (error: CancellationException) {
            error
        }
        thrown.shouldBeInstanceOf<CancellationException>()
        commitCalled shouldBe false
    }

    @Test
    fun `committed display retains superseded file until the next pointer is drained`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val page = PageTranslation(
            blocks = mutableListOf(
                eu.kanade.translation.model.TranslationBlock(
                    text = "source",
                    translation = "target",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
            imgWidth = 100f,
            imgHeight = 100f,
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
            cleanedImageName = "old.jpg",
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        )
        val store = ChapterTranslationStore(null, null, mapOf("p1" to page))
        val before = store.snapshot("p1")

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = "old.jpg",
            commit = { newName ->
                events += "commit:$newName"
                store.patchPage("p1", before.precondition(), "publish") {
                    it!!.apply { cleanedImageName = newName }
                }
            },
            mayDeletePrevious = { name -> store.mayDeleteCleanedImage("p1", name) },
        )

        (result is CleanedImagePublisher.Result.Published) shouldBe true
        events shouldBe listOf("write:new.jpg", "commit:new.jpg")
        store.drainRetiredCleanedImage("p1") shouldBe "old.jpg"
    }

    @Test
    fun `accepted candidate predecessor deletion uses the retirement callback`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val store = ChapterTranslationStore(null, null, mapOf("p1" to PageTranslation()))
        val before = store.snapshot("p1")
        var deferredDelete: (() -> Unit)? = null

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = "old.jpg",
            commit = { newName ->
                store.patchPage("p1", before.precondition(), "publish") {
                    it!!.apply { cleanedImageName = newName }
                }
            },
            retirePrevious = { name, delete ->
                events += "retire:$name"
                deferredDelete = delete
            },
        )

        (result is CleanedImagePublisher.Result.Published) shouldBe true
        events shouldBe listOf("write:new.jpg", "retire:old.jpg")
        deferredDelete?.invoke()
        events shouldBe listOf("write:new.jpg", "retire:old.jpg", "delete:old.jpg")
    }

    private fun fakeFiles(events: MutableList<String>) = object : CleanedImagePublisher.Files {
        override fun writeVerifiedVersionedFile(): String = "new.jpg".also { events += "write:$it" }
        override fun delete(name: String): Boolean = true.also { events += "delete:$name" }
    }

    private fun ChapterTranslationStore.PageSnapshot.precondition() = ChapterTranslationStore.PatchPrecondition(
        generation,
        pageVersion,
        blockFingerprints,
        leaseToken,
    )
}
