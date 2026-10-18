package eu.kanade.translation.persistence.artifact

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * P3A destructive/initialization publication receipts: initialization and
 * destructive completions must leave a verified same-state backup (recovery
 * can never resurrect removed state), a backup-copy failure stays visible as
 * a typed incomplete receipt, future-schema guards refuse without any write,
 * and the open-time unconditional backup deletion is gone.
 */
class DestructivePublicationReceiptTest {

    private val layout = ChapterArtifactLayout("Chapter 1")
    private val manifestName = layout.manifestFileName
    private val backupName = AtomicChapterDocuments.backupNameFor(manifestName)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun artifactStore(io: FakeChapterDocumentIo) =
        ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    private fun pageSnapshot() = eu.kanade.translation.model.PageTranslation(
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
        ocrStatus = eu.kanade.translation.model.StageStatus.READY,
        translationStatus = eu.kanade.translation.model.StageStatus.READY,
        inpaintStatus = eu.kanade.translation.model.StageStatus.READY,
        renderStatus = eu.kanade.translation.model.StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = eu.kanade.translation.model.PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun seededSnapshot() = ArtifactSeed(
        pages = mapOf(
            "page.jpg" to ArtifactPageFacts(pageSnapshot(), CleanedFileState.VALID),
        ),
        createdAtEpochMs = 42L,
    )

    private fun durableManifest(io: FakeChapterDocumentIo): ChapterArtifactManifest =
        ArtifactDocumentJson.decodeFromString(String(io.read(manifestName).shouldNotBeNull()))

    @Test
    fun `initialization leaves a verified same-state backup`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)

        store.load()

        io.files.containsKey(manifestName) shouldBe true
        io.files.containsKey(backupName) shouldBe true
        io.read(backupName) shouldBe io.read(manifestName)
    }

    @Test
    fun `user reset leaves a verified same-state backup and recovery cannot resurrect`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val seeded = store.loadArtifact(seededSnapshot()).manifest

        val outcome = store.demoteLivePage(seeded, "page.jpg")

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.unverifiedBackupReason shouldBe null
        io.read(backupName) shouldBe io.read(manifestName)
        durableManifest(io) shouldBe committed.manifest

        // Primary corruption recovers the RESET state from the backup — the
        // removed committed bundle never comes back.
        io.files[manifestName] = "{ corrupt".toByteArray()
        val recovered = store.readManifest().shouldNotBeNull()
        recovered shouldBe committed.manifest
        recovered.pages.getValue("page.jpg").committed shouldBe null
    }

    @Test
    fun `page removal leaves a verified same-state backup`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val seeded = store.loadArtifact(seededSnapshot()).manifest

        val outcome = store.deleteLivePage(seeded, "page.jpg")

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.unverifiedBackupReason shouldBe null
        io.read(backupName) shouldBe io.read(manifestName)
        durableManifest(io) shouldBe committed.manifest
        committed.manifest.pages.containsKey("page.jpg") shouldBe false
    }

    @Test
    fun `backup copy failure stays visible while the primary advances`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val seeded = store.loadArtifact(seededSnapshot()).manifest
        io.writeNamesToFail += backupName

        val outcome = store.demoteLivePage(seeded, "page.jpg")

        // The reset itself is durable and reported Committed; the unverified
        // same-state backup is carried as a typed, non-silent reason.
        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.unverifiedBackupReason.shouldNotBeNull()
        durableManifest(io) shouldBe committed.manifest
        String(io.read(backupName).shouldNotBeNull()) shouldNotBe String(io.read(manifestName).shouldNotBeNull())
    }

    @Test
    fun `future-schema primary refuses destructive publication without any write`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val seeded = store.loadArtifact(seededSnapshot()).manifest
        val future = seeded.copy(schemaVersion = 99)
        io.write(manifestName, json.encodeToString(future).toByteArray())
        io.writtenNames.clear()

        val outcome = store.demoteLivePage(future, "page.jpg")

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.incompletePublication shouldBe null
        io.writtenNames shouldBe emptyList()
        String(io.read(manifestName)!!) shouldBe json.encodeToString(future)
    }

    @Test
    fun `open no longer deletes a retained backup`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadArtifact(seededSnapshot())
        store.publishManifest(first.manifest.copy(updatedAtEpochMs = 43L))
        io.files.containsKey(backupName) shouldBe true
        io.deletedNames.clear()

        store.loadArtifact(seededSnapshot())

        io.deletedNames.none { it == backupName } shouldBe true
        io.files.containsKey(backupName) shouldBe true
    }

    @Test
    fun `destructive publication during a coalescing window writes through`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val seeded = store.loadArtifact(seededSnapshot()).manifest

        store.beginManifestCoalescing()
        val committed = store.demoteLivePage(seeded, "page.jpg")
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        // Stash acceptance is never success: the durable file already holds
        // the reset state while the window is still open.
        durableManifest(io) shouldBe committed.manifest
        store.endManifestCoalescing()
        // The final flush rewrites the same refreshed state, not the stale one.
        durableManifest(io) shouldBe committed.manifest
    }
}
