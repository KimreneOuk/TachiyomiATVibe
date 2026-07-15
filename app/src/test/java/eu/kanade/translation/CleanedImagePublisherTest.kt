package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import io.kotest.matchers.shouldBe
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

        val result = publisher.publish("chapter", "p1", "old.jpg") { newName ->
            events += "commit:$newName"
            store.patchPage("p1", before.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
        }

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

        val result = publisher.publish("chapter", "p1", "old.jpg") { newName ->
            events += "commit:$newName"
            store.patchPage("p1", stale.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
        }

        (result is CleanedImagePublisher.Result.Rejected) shouldBe true
        store.snapshot("p1").page!!.cleanedImageName shouldBe "old.jpg"
        events shouldBe listOf("write:new.jpg", "commit:new.jpg", "delete:new.jpg")
    }

    private fun fakeFiles(events: MutableList<String>) = object : CleanedImagePublisher.Files {
        override fun writeVerifiedVersionedFile(): String = "new.jpg".also { events += "write:$it" }
        override fun delete(name: String): Boolean = true.also { events += "delete:$name" }
    }

    private fun ChapterTranslationStore.PageSnapshot.precondition() = ChapterTranslationStore.PatchPrecondition(
        generation,
        pageVersion,
        blockFingerprints,
    )
}
