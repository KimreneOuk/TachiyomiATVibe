package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ChapterArtifactStoreTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun block(userEditedAt: Long? = null) = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        userEditedAt = userEditedAt,
    )

    private fun displayablePage() = PageTranslation(
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun identity(tag: String) = LegacySourceIdentity(
        sha256 = "sha-$tag",
        lengthBytes = tag.length.toLong(),
        lastModifiedMs = 1L,
    )

    private fun legacySnapshot(
        identityTag: String = "v1",
        page: PageTranslation = displayablePage(),
        glossary: Map<String, String> = mapOf("sensei" to "teacher"),
    ) = LegacyChapterSnapshot(
        pages = mapOf("page.jpg" to LegacyPageFacts(page, CleanedFileState.VALID)),
        glossary = glossary,
        legacyIdentity = identity(identityTag),
        migratedAtEpochMs = 42L,
    )

    private fun artifactStore(io: FakeChapterDocumentIo) =
        ChapterArtifactStore(AtomicChapterDocuments(io), layout)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `loadOrMigrate publishes manifest and versioned glossary sidecar`() {
        val io = FakeChapterDocumentIo()
        val result = artifactStore(io).loadOrMigrate(legacySnapshot())
        result.migratedFromLegacy shouldBe true
        result.resyncedFromLegacy shouldBe false
        result.manifest.chapterKey shouldBe "Chapter 1"
        result.manifest.legacySource shouldBe identity("v1")
        result.manifest.pages.getValue("page.jpg").committed.shouldNotBeNull()

        val pointer = result.manifest.glossary.shouldNotBeNull()
        pointer.fileName shouldBe layout.glossaryFile(1)
        val glossary = artifactStore(io).readGlossary(pointer).shouldNotBeNull()
        glossary.kind shouldBe ChapterGlossary.KIND_VOCABULARY_HINTS
        glossary.entries shouldBe mapOf("sensei" to "teacher")
        glossary.versionFingerprint shouldBe StageFingerprints.glossaryVersion(mapOf("sensei" to "teacher"))
    }

    @Test
    fun `second load with an unchanged legacy identity takes the fast path`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot())
        val second = store.loadOrMigrate(legacySnapshot())
        second.migratedFromLegacy shouldBe false
        second.resyncedFromLegacy shouldBe false
        second.manifest shouldBe first.manifest
        io.files.containsKey("${layout.manifestFileName}.bak") shouldBe false
    }

    @Test
    fun `changed legacy identity forces a resync to the newest content`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        store.loadOrMigrate(legacySnapshot(identityTag = "v1"))

        val editedPage = displayablePage().copy(
            blocks = mutableListOf(block(userEditedAt = 99L), block()),
        )
        val second = store.loadOrMigrate(legacySnapshot(identityTag = "v2", page = editedPage))

        second.resyncedFromLegacy shouldBe true
        second.manifest.legacySource shouldBe identity("v2")
        val committed = second.manifest.pages.getValue("page.jpg").committed.shouldNotBeNull()
        committed.hasManualEdits shouldBe true
        // The resynced manifest is durable: a plain read sees the same bytes.
        store.readManifest() shouldBe second.manifest
    }

    @Test
    fun `resync keeps runtime durable failures for surviving pages`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))
        val failure = DurableFailureMetadata(
            pageKey = "page.jpg",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_TERMINAL,
            category = FailureCategory.PROVIDER_REFUSAL,
            retryCount = 3,
            lastFailureMessage = "refused",
            lastFailedAtEpochMs = 100L,
            failureFingerprint = "fp",
        )
        val stored = store.recordDurableFailure(first.manifest, failure)
        val storedOutcome = stored.shouldBeInstanceOf<ChapterArtifactStore.RecordOutcome.Stored>()
        storedOutcome.manifest.durableFailures.size shouldBe 1

        val second = store.loadOrMigrate(legacySnapshot(identityTag = "v2"))
        second.resyncedFromLegacy shouldBe true
        second.manifest.durableFailures.getValue("page.jpg:TRANSLATION") shouldBe failure
    }

    @Test
    fun `failed resync publish keeps the prior manifest authoritative`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))

        io.failWrites = true
        val second = store.loadOrMigrate(legacySnapshot(identityTag = "v2"))
        // The in-memory result of this load is the prior manifest.
        second.manifest shouldBe first.manifest
        second.resyncedFromLegacy shouldBe false
        io.failWrites = false
        store.readManifest() shouldBe first.manifest
    }

    @Test
    fun `legacy flat file is never modified by migration`() {
        val io = FakeChapterDocumentIo()
        io.write("Chapter 1.json", "{\"page.jpg\":{}}".toByteArray())
        artifactStore(io).loadOrMigrate(legacySnapshot())
        String(io.files.getValue("Chapter 1.json")) shouldBe "{\"page.jpg\":{}}"
        io.deletedNames.none { it == "Chapter 1.json" } shouldBe true
    }

    @Test
    fun `corrupt manifest primary recovers from retained backup`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot())
        store.publishManifest(first.manifest.copy(updatedAtEpochMs = 43L))
        io.files[layout.manifestFileName] = "not json".toByteArray()
        val recovered = store.readManifest().shouldNotBeNull()
        recovered shouldBe first.manifest
        io.files.containsKey("${layout.manifestFileName}.corrupt") shouldBe true
    }

    @Test
    fun `manifest with an unsupported primary schema is left untouched`() {
        val io = FakeChapterDocumentIo()
        val future = ChapterArtifactManifest(schemaVersion = 99, chapterKey = "Chapter 1")
        io.write(layout.manifestFileName, json.encodeToString(future).toByteArray())
        val result = artifactStore(io).loadOrMigrate(legacySnapshot())
        result.migratedFromLegacy shouldBe false
        result.resyncedFromLegacy shouldBe false
        String(io.read(layout.manifestFileName)!!) shouldBe json.encodeToString(future)
    }

    @Test
    fun `future-schema backup with missing primary is returned read-only and preserved`() {
        val io = FakeChapterDocumentIo()
        val future = ChapterArtifactManifest(schemaVersion = 99, chapterKey = "Chapter 1")
        io.write(AtomicChapterDocuments.backupNameFor(layout.manifestFileName), json.encodeToString(future).toByteArray())

        val result = artifactStore(io).loadOrMigrate(legacySnapshot())

        result.manifest shouldBe future
        result.migratedFromLegacy shouldBe false
        // The future document was not renamed, deleted, quarantined, or
        // overwritten, and no v1 primary was published beside it.
        String(io.read(AtomicChapterDocuments.backupNameFor(layout.manifestFileName))!!) shouldBe
            json.encodeToString(future)
        io.files.containsKey(layout.manifestFileName) shouldBe false
        io.files.containsKey("${layout.manifestFileName}.corrupt") shouldBe false
        io.deletedNames.none { it.endsWith(".bak") } shouldBe true
    }

    @Test
    fun `future-schema backup with corrupt primary preserves both documents`() {
        val io = FakeChapterDocumentIo()
        val future = ChapterArtifactManifest(schemaVersion = 99, chapterKey = "Chapter 1")
        val backupName = AtomicChapterDocuments.backupNameFor(layout.manifestFileName)
        io.write(layout.manifestFileName, "{ corrupt".toByteArray())
        io.write(backupName, json.encodeToString(future).toByteArray())

        val result = artifactStore(io).loadOrMigrate(legacySnapshot())

        result.manifest shouldBe future
        // Corrupt primary bytes remain preserved; the future backup is intact.
        String(io.read(layout.manifestFileName)!!) shouldBe "{ corrupt"
        String(io.read(backupName)!!) shouldBe json.encodeToString(future)
        io.files.containsKey("${layout.manifestFileName}.corrupt") shouldBe false
    }

    @Test
    fun `usable primary wins over a future backup without touching it`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))
        val future = ChapterArtifactManifest(schemaVersion = 99, chapterKey = "Chapter 1")
        io.write(
            AtomicChapterDocuments.backupNameFor(layout.manifestFileName),
            json.encodeToString(future).toByteArray(),
        )

        val result = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))

        result.manifest shouldBe first.manifest
        // Fast path must not delete the future backup.
        io.files.containsKey(AtomicChapterDocuments.backupNameFor(layout.manifestFileName)) shouldBe true
    }

    @Test
    fun `recordDurableFailure reports NotStored on write failure and keeps the prior manifest`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot())
        val failure = DurableFailureMetadata(
            pageKey = "page.jpg",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_TERMINAL,
            category = FailureCategory.PROVIDER_REFUSAL,
            retryCount = 3,
            lastFailureMessage = "refused",
            lastFailedAtEpochMs = 100L,
        )

        io.failWrites = true
        val outcome = store.recordDurableFailure(first.manifest, failure)
        val notStored = outcome.shouldBeInstanceOf<ChapterArtifactStore.RecordOutcome.NotStored>()
        notStored.reason shouldNotBe ""
        io.failWrites = false
        store.readManifest() shouldBe first.manifest
    }

    @Test
    fun `recordDurableFailure reports NotStored on rename failure`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot())
        val failure = DurableFailureMetadata(
            pageKey = "page.jpg",
            stage = ArtifactStage.OCR,
            status = ArtifactStageStatus.FAILED_TERMINAL,
            category = FailureCategory.SOURCE,
            retryCount = 1,
            lastFailedAtEpochMs = 100L,
        )

        // Make the final temp->primary rename fail.
        io.renamesToFail += AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        val outcome = store.recordDurableFailure(first.manifest, failure)
        outcome.shouldBeInstanceOf<ChapterArtifactStore.RecordOutcome.NotStored>()
        // Prior manifest still parses as the authoritative document.
        store.readManifest() shouldBe first.manifest
    }

    @Test
    fun `recordDurableFailure is durable on the happy path`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val manifest = store.loadOrMigrate(legacySnapshot()).manifest
        val failure = DurableFailureMetadata(
            pageKey = "page.jpg",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_TERMINAL,
            category = FailureCategory.PROVIDER_REFUSAL,
            retryCount = 3,
            lastFailureMessage = "refused",
            lastFailedAtEpochMs = 100L,
            failureFingerprint = "fp",
        )
        val outcome = store.recordDurableFailure(manifest, failure)
        val stored = outcome.shouldBeInstanceOf<ChapterArtifactStore.RecordOutcome.Stored>()
        stored.manifest.durableFailures.getValue("page.jpg:TRANSLATION") shouldBe failure
        store.readManifest().shouldNotBeNull().durableFailures.getValue("page.jpg:TRANSLATION") shouldBe failure
    }

    @Test
    fun `reconcileRetention uses canonical layout paths for all managed subtrees`() {
        val io = FakeChapterDocumentIo()
        val committedImage = layout.imageFile("0001.jpg", "g1", "fp1", "jpg")
        val previousImage = layout.imageFile("0001.jpg", "g0", "fp0", "jpg")
        val candidateImage = layout.imageFile("0001.jpg", "g2", "fp2", "jpg")
        val ocrSidecar = layout.stageArtifactFile("0001.jpg", ArtifactStage.OCR, "deadbeef")
        val keptGeneration = layout.generationFile("g1")
        val keptCheckpoint = layout.contextCheckpointFile(3, "cphash")
        val keptGlossary = layout.glossaryFile(1)
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            pages = mapOf(
                "0001.jpg" to PageArtifactRecord(
                    pageKey = "0001.jpg",
                    committed = CommittedBundleMetadata(
                        generationId = "g1",
                        displayBase = DisplayBaseReference(
                            kind = DisplayBaseKind.CLEANED_IMAGE,
                            fileName = committedImage,
                        ),
                    ),
                    previousCommitted = CommittedBundleMetadata(
                        generationId = "g0",
                        displayBase = DisplayBaseReference(
                            kind = DisplayBaseKind.CLEANED_IMAGE,
                            fileName = previousImage,
                        ),
                    ),
                    candidate = CandidateGenerationMetadata(generationId = "g2"),
                    contextCheckpointFileName = keptCheckpoint,
                    ocr = StageArtifactRecord(
                        status = ArtifactStageStatus.READY,
                        origin = ArtifactOrigin.BATCH,
                        artifactFileName = ocrSidecar,
                    ),
                ),
            ),
            glossary = GlossaryPointer(fileName = keptGlossary, version = 1, versionFingerprint = "fp"),
        )
        io.write(committedImage, byteArrayOf(1))
        io.write(previousImage, byteArrayOf(2))
        io.write(candidateImage, byteArrayOf(3))
        io.write("$candidateImage.tmp", byteArrayOf(4))
        io.write(ocrSidecar, byteArrayOf(5))
        io.write(layout.stageArtifactFile("0001.jpg", ArtifactStage.OCR, "ffffffff"), byteArrayOf(6))
        io.write(keptGeneration, byteArrayOf(7))
        io.write(layout.generationFile("stale-gen"), byteArrayOf(8))
        io.write(keptCheckpoint, byteArrayOf(9))
        io.write(layout.contextCheckpointFile(4, "orphan"), byteArrayOf(10))
        io.write(keptGlossary, byteArrayOf(11))
        io.write(layout.glossaryFile(2), byteArrayOf(12))
        io.write(layout.glossaryFile(9), byteArrayOf(13))
        // Legacy documents outside the managed tree survive.
        io.write("Chapter 1.json", byteArrayOf(14))
        io.write("Chapter 1_images/page.cleaned.abc.jpg", byteArrayOf(15))

        val result = artifactStore(io).reconcileRetention(manifest)

        io.files.containsKey(committedImage) shouldBe true
        io.files.containsKey(previousImage) shouldBe true
        io.files.containsKey(candidateImage) shouldBe true
        io.files.containsKey("$candidateImage.tmp") shouldBe false
        io.files.containsKey(ocrSidecar) shouldBe true
        io.files.containsKey(layout.stageArtifactFile("0001.jpg", ArtifactStage.OCR, "ffffffff")) shouldBe false
        io.files.containsKey(keptGeneration) shouldBe true
        io.files.containsKey(layout.generationFile("stale-gen")) shouldBe false
        io.files.containsKey(keptCheckpoint) shouldBe true
        io.files.containsKey(layout.contextCheckpointFile(4, "orphan")) shouldBe false
        io.files.containsKey(keptGlossary) shouldBe true
        io.files.containsKey(layout.glossaryFile(2)) shouldBe false
        io.files.containsKey(layout.glossaryFile(9)) shouldBe false
        io.files.containsKey("Chapter 1.json") shouldBe true
        io.files.containsKey("Chapter 1_images/page.cleaned.abc.jpg") shouldBe true
        result.deletedCount shouldBe 6
    }

    @Test
    fun `candidate generation retention uses an exact encoded identity`() {
        val io = FakeChapterDocumentIo()
        val kept = layout.imageFile("p", "g", "keep", "jpg")
        val similarlyPrefixedOrphan = layout.imageFile("p", "g-other", "orphan", "jpg")
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            pages = mapOf(
                "p" to PageArtifactRecord(
                    pageKey = "p",
                    candidate = CandidateGenerationMetadata("g"),
                ),
            ),
        )
        io.write(kept, byteArrayOf(1))
        io.write(similarlyPrefixedOrphan, byteArrayOf(2))

        artifactStore(io).reconcileRetention(manifest)

        io.files.containsKey(kept) shouldBe true
        io.files.containsKey(similarlyPrefixedOrphan) shouldBe false
    }

    @Test
    fun `retention never deletes backups of reachable documents`() {
        val io = FakeChapterDocumentIo()
        val committedImage = layout.imageFile("p", "g1", "fp1", "jpg")
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            pages = mapOf(
                "p" to PageArtifactRecord(
                    pageKey = "p",
                    committed = CommittedBundleMetadata(
                        generationId = "g1",
                        displayBase = DisplayBaseReference(
                            kind = DisplayBaseKind.CLEANED_IMAGE,
                            fileName = committedImage,
                        ),
                    ),
                ),
            ),
        )
        io.write(committedImage, byteArrayOf(1))
        io.write("$committedImage.bak", byteArrayOf(2))

        val result = artifactStore(io).reconcileRetention(manifest)

        io.files.containsKey(committedImage) shouldBe true
        io.files.containsKey("$committedImage.bak") shouldBe true
        result.deletedCount shouldBe 0
    }

    @Test
    fun `glossary republish advances the sidecar version and retention bounds it`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val manifest = store.loadOrMigrate(legacySnapshot()).manifest
        val second = store.publishGlossary(mapOf("sensei" to "professor")).shouldNotBeNull()
        second.version shouldBe 2
        val reread = store.readGlossary(second).shouldNotBeNull()
        reread.entries shouldBe mapOf("sensei" to "professor")
        // First version still readable as an immutable sidecar.
        store.readGlossary(manifest.glossary.shouldNotBeNull()) shouldNotBe null

        // Retention bounded to the pointed version only.
        val updated = manifest.copy(glossary = second)
        store.reconcileRetention(updated)
        io.files.containsKey(layout.glossaryFile(1)) shouldBe false
        io.files.containsKey(layout.glossaryFile(2)) shouldBe true
    }

    @Test
    fun `empty legacy chapter produces an empty manifest and no glossary`() {
        val io = FakeChapterDocumentIo()
        val result = artifactStore(io).loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L))
        result.manifest.pages.isEmpty() shouldBe true
        result.manifest.glossary.shouldBeNull()
    }
}
