package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslation
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChapterTranslationStoreRekeyTest {

    private fun store(vararg keys: String): ChapterTranslationStore = ChapterTranslationStore(
        artifactParentResolver = null,
        initialPages = keys.associateWith { PageTranslation(sourceFileName = it) },
    )

    @Test
    fun `URL keys move to downloaded filenames and update source names`() = runTest {
        val store = store("page-a.jpg", "page-b.jpg")

        store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg"),
        ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            listOf("page-a.jpg" to "000.jpg", "page-b.jpg" to "001.jpg"),
            skippedCollisions = 0,
        )

        store.state.value.keys shouldBe setOf("000.jpg", "001.jpg")
        store.state.value.values.map { it.sourceFileName } shouldBe listOf("000.jpg", "001.jpg")
    }

    @Test
    fun `mapping size mismatch is a typed rejection and leaves the store untouched`() = runTest {
        val store = store("page-a.jpg", "page-b.jpg", "page-c.jpg")
        val before = store.state.value

        val outcome = store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg", "page-c.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg", "002.jpg", "002__001.jpg", "002__002.jpg"),
        )

        outcome.shouldBeInstanceOf<ChapterTranslationStore.PageRekeyOutcome.Rejected>()
        store.state.value shouldBe before
    }

    @Test
    fun `already downloaded keys are an honest no-op`() = runTest {
        val store = store("000.jpg", "001.jpg")
        val before = store.state.value

        store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg"),
        ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Noop

        store.state.value shouldBe before
    }

    // ------------------------------------------------------- partial subset --

    @Test
    fun `partial reader subset rekeys only the owned pages`() = runTest {
        // Reader translated page-a only; the chapter has three pages on disk.
        val store = store("page-a.jpg")

        val outcome = store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg", "page-c.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg", "002.jpg"),
        )

        outcome shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            listOf("page-a.jpg" to "000.jpg"),
            skippedCollisions = 0,
        )
        store.state.value.keys shouldBe setOf("000.jpg")
        store.state.value.values.single().sourceFileName shouldBe "000.jpg"
    }

    // ------------------------------------------- placeholder destination ----

    @Test
    fun `empty placeholder destination is adopted and moved content wins`() = runTest {
        val pages = linkedMapOf(
            "page-a.jpg" to PageTranslation(sourceFileName = "page-a.jpg").apply {
                cleanedImageName = "cleaned-a.png"
            },
            // Pre-registered disk-key placeholder: no payload of its own.
            "000.jpg" to PageTranslation(sourceFileName = "000.jpg"),
        )
        val store = ChapterTranslationStore(artifactParentResolver = null, initialPages = pages)

        val outcome = store.rekeyPages(
            onlineKeys = listOf("page-a.jpg"),
            onDiskKeys = listOf("000.jpg"),
        )

        outcome shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            listOf("page-a.jpg" to "000.jpg"),
            skippedCollisions = 0,
        )
        // The placeholder must be GONE and the moved record must own the key:
        // map order must never let the consumed placeholder overwrite content.
        store.state.value.keys shouldBe setOf("000.jpg")
        withClue("moved record content must win over the consumed placeholder") {
            store.state.value.getValue("000.jpg").cleanedImageName shouldBe "cleaned-a.png"
        }
    }

    @Test
    fun `nonempty destination collision is skipped with partial typed truth`() = runTest {
        val pages = linkedMapOf(
            "page-a.jpg" to PageTranslation(sourceFileName = "page-a.jpg"),
            "000.jpg" to PageTranslation(sourceFileName = "000.jpg").apply {
                cleanedImageName = "real-payload.png"
            },
        )
        val store = ChapterTranslationStore(artifactParentResolver = null, initialPages = pages)
        val before = store.state.value

        val outcome = store.rekeyPages(
            onlineKeys = listOf("page-a.jpg"),
            onDiskKeys = listOf("000.jpg"),
        )

        // No move happened but the outcome must expose the collision, not a
        // silent success.
        outcome shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            emptyList(),
            skippedCollisions = 1,
        )
        store.state.value shouldBe before
        store.state.value.getValue("000.jpg").cleanedImageName shouldBe "real-payload.png"
    }

    @Test
    fun `mixed move and collision reports partial truth`() = runTest {
        val pages = linkedMapOf(
            "page-a.jpg" to PageTranslation(sourceFileName = "page-a.jpg").apply {
                cleanedImageName = "cleaned-a.png"
            },
            "page-b.jpg" to PageTranslation(sourceFileName = "page-b.jpg"),
            "000.jpg" to PageTranslation(sourceFileName = "000.jpg").apply {
                cleanedImageName = "real-payload.png"
            },
        )
        val store = ChapterTranslationStore(artifactParentResolver = null, initialPages = pages)

        val outcome = store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg"),
        )

        outcome shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            listOf("page-b.jpg" to "001.jpg"),
            skippedCollisions = 1,
        )
        store.state.value.keys shouldBe setOf("page-a.jpg", "000.jpg", "001.jpg")
        store.state.value.getValue("page-a.jpg").cleanedImageName shouldBe "cleaned-a.png"
        store.state.value.getValue("000.jpg").cleanedImageName shouldBe "real-payload.png"
        store.state.value.getValue("001.jpg").sourceFileName shouldBe "001.jpg"
    }

    @Test
    fun `repeated handoff after a completed rekey is an honest no-op`() = runTest {
        val store = store("page-a.jpg")

        store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg"),
        ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            listOf("page-a.jpg" to "000.jpg"),
            skippedCollisions = 0,
        )

        store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg"),
        ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Noop
    }

    @Test
    fun `file rekey gate serializes chapter file and probe store admission`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val fileKey = "shared-rekey-open-gate"
        val maintenanceEntered = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        var createCount = 0

        val maintenance = async {
            registry.withFileOpeningLock(fileKey) {
                registry.hasStoreForFile(fileKey) shouldBe false
                maintenanceEntered.complete(Unit)
                releaseMaintenance.await()
            }
        }
        maintenanceEntered.await()

        val chapterOpen = async {
            registry.getOrCreate(101, fileKey) {
                createCount++
                ChapterTranslationStore()
            }
        }
        val fileOpen = async {
            registry.getOrCreateFile(fileKey) {
                createCount++
                ChapterTranslationStore()
            }
        }
        val probeOpen = async {
            registry.getOrCreateProbe(fileKey) {
                createCount++
                ChapterTranslationStore()
            }
        }
        runCurrent()
        createCount shouldBe 0
        registry.hasStoreForFile(fileKey) shouldBe false

        releaseMaintenance.complete(Unit)
        maintenance.await()
        val chapterStore = chapterOpen.await()
        chapterStore shouldBe fileOpen.await()
        chapterStore shouldBe probeOpen.await()?.store
        createCount shouldBe 1
        registry.get(101) shouldBe chapterStore
        registry.getByFile(fileKey) shouldBe chapterStore
        registry.hasStoreForFile(fileKey) shouldBe true

        // Existing chapter/file/probe fast paths must also wait behind the
        // same gate; otherwise a concurrent opener can adopt the store after
        // maintenance has already passed its ownership check.
        val fastPathEntered = CompletableDeferred<Unit>()
        val releaseFastPathMaintenance = CompletableDeferred<Unit>()
        val fastPathMaintenance = async {
            registry.withFileOpeningLock(fileKey) {
                fastPathEntered.complete(Unit)
                releaseFastPathMaintenance.await()
            }
        }
        fastPathEntered.await()
        val chapterFastPath = async {
            registry.getOrCreate(101, fileKey) { error("existing chapter fast path must be reused") }
        }
        val fileFastPath = async {
            registry.getOrCreateFile(fileKey) { error("existing file fast path must be reused") }
        }
        val probeFastPath = async {
            registry.getOrCreateProbe(fileKey) { error("existing file must satisfy probe fast path") }
        }
        runCurrent()
        chapterFastPath.isCompleted shouldBe false
        fileFastPath.isCompleted shouldBe false
        probeFastPath.isCompleted shouldBe false
        releaseFastPathMaintenance.complete(Unit)
        fastPathMaintenance.await()
        chapterFastPath.await() shouldBe chapterStore
        fileFastPath.await() shouldBe chapterStore
        probeFastPath.await()?.store shouldBe chapterStore

        val probeKey = "shared-rekey-open-gate-probe"
        val probeStore = ChapterTranslationStore()
        registry.getOrCreateProbe(probeKey) { probeStore }?.store shouldBe probeStore
        val probeMaintenanceEntered = CompletableDeferred<Unit>()
        val releaseProbeMaintenance = CompletableDeferred<Unit>()
        val probeMaintenance = async {
            registry.withFileOpeningLock(probeKey) {
                probeMaintenanceEntered.complete(Unit)
                releaseProbeMaintenance.await()
            }
        }
        probeMaintenanceEntered.await()
        val existingProbeFastPath = async {
            registry.getOrCreateProbe(probeKey) { error("existing probe fast path must be reused") }
        }
        runCurrent()
        existingProbeFastPath.isCompleted shouldBe false
        releaseProbeMaintenance.complete(Unit)
        probeMaintenance.await()
        existingProbeFastPath.await()?.store shouldBe probeStore

        registry.releaseProbe(probeKey, probeStore) shouldBe true
        probeStore.closeAndFlush()
        registry.remove(101)?.closeAndFlush()
    }
}
