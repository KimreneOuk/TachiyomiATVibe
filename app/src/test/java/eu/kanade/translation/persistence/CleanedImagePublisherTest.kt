package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        val store = ChapterTranslationStore(mapOf("p1" to PageTranslation(cleanedImageName = "old.jpg")))
        val before = store.snapshot("p1")

        val result = publisher.publish("chapter", "p1", "old.jpg", commit = { newName ->
            events += "commit:$newName"
            store.patchPage("p1", before.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
        })

        (result is CleanedImagePublisher.Result.Published) shouldBe true
        events shouldBe listOf("write:new.jpg", "commit:new.jpg", "delete:old.jpg")
    }

    @Test
    fun `rejection preserves current old file and cleans unpublished new file under explicit authorization`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val store = ChapterTranslationStore(mapOf("p1" to PageTranslation(cleanedImageName = "old.jpg")))
        val stale = store.snapshot("p1")
        store.updatePage("p1") { it!!.apply { errorMessage = "newer work" } }

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = "old.jpg",
            commit = { newName ->
                events += "commit:$newName"
                store.patchPage("p1", stale.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
            },
            mayDeleteRejected = { true },
        )

        (result is CleanedImagePublisher.Result.Rejected) shouldBe true
        store.snapshot("p1").page!!.cleanedImageName shouldBe "old.jpg"
        events shouldBe listOf("write:new.jpg", "commit:new.jpg", "delete:new.jpg")
    }

    @Test
    fun `rejection without store authorization retains the new file as uncollected debt`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val store = ChapterTranslationStore(mapOf("p1" to PageTranslation(cleanedImageName = "old.jpg")))
        val stale = store.snapshot("p1")
        store.updatePage("p1") { it!!.apply { errorMessage = "newer work" } }

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = "old.jpg",
            commit = { newName ->
                events += "commit:$newName"
                store.patchPage("p1", stale.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
            },
        )

        val rejected = result as CleanedImagePublisher.Result.Rejected
        rejected.unpublishedCleaned shouldBe false
        rejected.name shouldBe "new.jpg"
        rejected.reason shouldContain "pageVersion"
        store.snapshot("p1").page!!.cleanedImageName shouldBe "old.jpg"
        events shouldBe listOf("write:new.jpg", "commit:new.jpg")
    }

    @Test
    fun `commit exception retains the uncertain new file without a cleanup claim`() = runTest {
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
            unpublishedCleaned = false,
        )
        events shouldBe listOf("write:new.jpg")
    }

    @Test
    fun `commit cancellation retains the uncertain new file and rethrows`() = runTest {
        val events = mutableListOf<String>()
        val cancellation = CancellationException("commit cancelled")
        val publisher = CleanedImagePublisher(fakeFiles(events))

        val thrown = try {
            publisher.publish(
                chapter = "chapter",
                pageKey = "p1",
                previousName = "old.jpg",
                commit = {
                    events += "commit:new.jpg"
                    throw cancellation
                },
            )
            null
        } catch (error: CancellationException) {
            error
        }

        thrown.shouldBeInstanceOf<CancellationException>()
        thrown shouldBe cancellation
        events shouldBe listOf("write:new.jpg", "commit:new.jpg")
    }

    @Test
    fun `rejected hash patch retains a name that became live between write and rejection`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val store = ChapterTranslationStore(mapOf("p1" to PageTranslation(cleanedImageName = "old.jpg")))
        store.updatePage("p1") { it!!.apply { errorMessage = "newer work" } }

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = "old.jpg",
            commit = { newName ->
                events += "commit:$newName"
                // The name goes LIVE before the hash patch is rejected: the
                // store may now hold this exact name as the live cleaned image.
                store.updatePage("p1") { it!!.apply { cleanedImageName = newName } }
                ChapterTranslationStore.PatchResult.Rejected("hash patch rejected")
            },
            mayDeleteRejected = { name -> store.authorizeCleanedImageDeletion("p1", name) },
        )

        result shouldBe CleanedImagePublisher.Result.Rejected(
            name = "new.jpg",
            reason = "hash patch rejected",
            unpublishedCleaned = false,
        )
        store.snapshot("p1").page!!.cleanedImageName shouldBe "new.jpg"
        events shouldBe listOf("write:new.jpg", "commit:new.jpg")
    }

    @Test
    fun `prior-live-then-cleared rejected name is retained even though the store would authorize it`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        // The very name the publisher writes was accepted live once, then a
        // reset/replacement cleared the live pointer — historical acceptance
        // the point-in-time store check cannot see. (The write resolves to the
        // same name, as writeVerifiedCleanedImage does when existing verified
        // bytes match.)
        val store = ChapterTranslationStore(mapOf("p1" to PageTranslation(cleanedImageName = "new.jpg")))
        store.updatePage("p1") { it!!.apply { cleanedImageName = null } }

        // The store's point-in-time authorization WOULD allow deleting exactly
        // this name — so the store alone is insufficient.
        store.authorizeCleanedImageDeletion("p1", "new.jpg") shouldBe true

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = null,
            commit = {
                events += "commit:new.jpg"
                ChapterTranslationStore.PatchResult.Rejected("store generation changed")
            },
            // Lazy-path caller policy: provenance is not never-accepted, so the
            // seam fails closed regardless of the store's answer.
            mayDeleteRejected = { _ -> false },
        )

        result shouldBe CleanedImagePublisher.Result.Rejected(
            name = "new.jpg",
            reason = "store generation changed",
            unpublishedCleaned = false,
        )
        store.snapshot("p1").page!!.cleanedImageName shouldBe null
        events shouldBe listOf("write:new.jpg", "commit:new.jpg")
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
        val store = ChapterTranslationStore(mapOf("p1" to page))
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
        store.drainRetiredCleanedImages("p1") shouldBe listOf("old.jpg")
    }

    @Test
    fun `accepted candidate predecessor deletion uses the retirement callback`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val store = ChapterTranslationStore(mapOf("p1" to PageTranslation()))
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

    @Test
    fun `rejected never-registered name is store-authorized and deleted outside the store lock`() = runTest {
        val events = mutableListOf<String>()
        val publisher = CleanedImagePublisher(fakeFiles(events))
        val store = ChapterTranslationStore(mapOf("p1" to PageTranslation(cleanedImageName = "old.jpg")))
        val stale = store.snapshot("p1")
        store.updatePage("p1") { it!!.apply { errorMessage = "newer work" } }

        val result = publisher.publish(
            chapter = "chapter",
            pageKey = "p1",
            previousName = "old.jpg",
            commit = { newName ->
                events += "commit:$newName"
                store.patchPage("p1", stale.precondition(), "publish") { it!!.apply { cleanedImageName = newName } }
            },
            mayDeleteRejected = { name ->
                // Fresh-unique provenance: this commit was the name's only
                // possible acceptance and the store just rejected it, so the
                // never-accepted knowledge holds; the store's mutex-held
                // point-in-time check is the remaining condition. Outside-lock
                // proof is TWO-PART: this event ordering shows the physical
                // delete runs strictly AFTER the authorization call returned,
                // and the source of authorizeCleanedImageDeletion shows the
                // call itself releases the store mutex on completion
                // (mutex.withLock returns) — the events alone do not prove the
                // lock state.
                events += "authorize:$name"
                store.authorizeCleanedImageDeletion("p1", name)
            },
        )

        (result as CleanedImagePublisher.Result.Rejected).unpublishedCleaned shouldBe true
        store.snapshot("p1").page!!.cleanedImageName shouldBe "old.jpg"
        events shouldBe listOf("write:new.jpg", "commit:new.jpg", "authorize:new.jpg", "delete:new.jpg")
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
