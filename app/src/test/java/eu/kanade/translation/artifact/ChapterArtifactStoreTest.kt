package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageDisplayState
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private fun transactionStore(io: FakeChapterDocumentIo) =
        ChapterArtifactStore(
            AtomicChapterDocuments(io),
            layout,
            object : CleanedImageProbe {
                override fun probe(input: InputStream): ProbedImage? =
                    if (input.read() == 1) null else ProbedImage(100, 100)
            },
        )

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
    fun `artifact load removes corrupt and orphan managed temp files but keeps active candidate`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps",
            nowEpochMs = 500L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val candidateImage = layout.imageFile("page.jpg", generationId, "candidate", "jpg")
        io.write(candidateImage, byteArrayOf(1, 2, 3))

        val orphanStage = layout.stageArtifactFile("page.jpg", ArtifactStage.OCR, "orphan")
        val orphanGeneration = layout.generationFile("orphan-generation")
        io.write("$orphanStage.tmp", byteArrayOf(4))
        io.write(orphanGeneration, byteArrayOf(5))
        io.write(AtomicChapterDocuments.tempNameFor(layout.manifestFileName), byteArrayOf(6))

        val reloaded = ChapterArtifactStore(AtomicChapterDocuments(io), layout)
            .loadOrMigrate(legacySnapshot())

        reloaded.manifest.authority shouldBe ManifestAuthority.ARTIFACTS
        io.files.containsKey(candidateImage) shouldBe true
        io.files.containsKey("$orphanStage.tmp") shouldBe false
        io.files.containsKey(orphanGeneration) shouldBe false
        io.files.containsKey(AtomicChapterDocuments.tempNameFor(layout.manifestFileName)) shouldBe false
    }

    @Test
    fun `generation retention stays bounded across repeated reruns`() {
        val io = FakeChapterDocumentIo()
        val committed = layout.imageFile("page.jpg", "g20", "fp20", "jpg")
        val previous = layout.imageFile("page.jpg", "g19", "fp19", "jpg")
        val candidate = layout.imageFile("page.jpg", "g21", "fp21", "jpg")
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            pages = mapOf(
                "page.jpg" to PageArtifactRecord(
                    pageKey = "page.jpg",
                    committed = CommittedBundleMetadata(
                        generationId = "g20",
                        displayBase = DisplayBaseReference(
                            kind = DisplayBaseKind.CLEANED_IMAGE,
                            fileName = committed,
                        ),
                    ),
                    previousCommitted = CommittedBundleMetadata(
                        generationId = "g19",
                        displayBase = DisplayBaseReference(
                            kind = DisplayBaseKind.CLEANED_IMAGE,
                            fileName = previous,
                        ),
                    ),
                    candidate = CandidateGenerationMetadata(generationId = "g21"),
                ),
            ),
        )
        (0..100).forEach { generation ->
            io.write(
                layout.imageFile("page.jpg", "g$generation", "fp$generation", "jpg"),
                byteArrayOf(generation.toByte()),
            )
        }

        artifactStore(io).reconcileRetention(manifest)

        val retainedGenerationIds = io.files.keys
            .filter { it.startsWith("${layout.imagesRootDirectory}/") }
            .map { it.substringAfterLast('/').substringBefore("-f-") }
            .toSet()
        retainedGenerationIds shouldBe setOf(
            layout.generationSegment("g19"),
            layout.generationSegment("g20"),
            layout.generationSegment("g21"),
        )
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

    @Test
    fun `candidate promotion is atomic and legacy changes cannot overwrite artifact authority`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot(identityTag = "v1")).manifest
        val opened = store.openCandidate(
            manifest = migrated,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 100L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        opened.manifest.authority shouldBe ManifestAuthority.ARTIFACTS
        opened.manifest.cutoverAtEpochMs shouldBe 100L
        val generationId = opened.generationId.shouldNotBeNull()

        val translation = store.commitStagePayload(
            manifest = opened.manifest,
            pageKey = "page.jpg",
            stage = ArtifactStage.TRANSLATION,
            generationId = generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = "deps-v1",
            fingerprint = "translation-v1",
            payload = buildJsonObject { put("stage", "translation") },
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 101L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val layoutCommit = store.commitStagePayload(
            manifest = translation.manifest,
            pageKey = "page.jpg",
            stage = ArtifactStage.LAYOUT,
            generationId = generationId,
            expectedPageVersion = 2L,
            expectedDependencyFingerprint = "deps-v1",
            fingerprint = "layout-v1",
            payload = buildJsonObject { put("stage", "layout") },
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 102L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        val corruptDisplay = "corrupt-image.jpg"
        io.write(corruptDisplay, byteArrayOf(1))
        val rejectedPromotion = store.promoteCandidate(
            manifest = layoutCommit.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = 3L,
            expectedDependencyFingerprint = "deps-v1",
            bundle = ChapterArtifactStore.PromotionBundle(
                displayBaseKind = DisplayBaseKind.CLEANED_IMAGE,
                displayBaseFileName = corruptDisplay,
                translationFingerprint = "translation-v1",
                layoutFingerprint = "layout-v1",
            ),
            nowEpochMs = 103L,
        )
        rejectedPromotion.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Rejected>()
        store.readManifest() shouldBe layoutCommit.manifest

        val display = "valid-image.jpg"
        io.write(display, byteArrayOf(2, 3, 4))
        val promoted = store.promoteCandidate(
            manifest = layoutCommit.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = 3L,
            expectedDependencyFingerprint = "deps-v1",
            bundle = ChapterArtifactStore.PromotionBundle(
                displayBaseKind = DisplayBaseKind.CLEANED_IMAGE,
                displayBaseFileName = display,
                translationFingerprint = "translation-v1",
                layoutFingerprint = "layout-v1",
            ),
            nowEpochMs = 104L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val promotedPage = promoted.manifest.pages.getValue("page.jpg")
        promotedPage.candidate.shouldBeNull()
        promotedPage.committed.shouldNotBeNull().origin shouldBe ArtifactOrigin.BATCH
        promotedPage.previousCommitted.shouldNotBeNull().origin shouldBe ArtifactOrigin.LEGACY

        val reopened = store.loadOrMigrate(
            legacySnapshot(identityTag = "v2", page = displayablePage().copy(blocks = mutableListOf(block(), block()))),
        )
        reopened.resyncedFromLegacy shouldBe false
        reopened.manifest.pages.getValue("page.jpg").committed?.generationId shouldBe generationId
        reopened.manifest.legacySource shouldBe identity("v1")
    }

    @Test
    fun `concurrent candidate opens serialize against the same manifest snapshot`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0 until 2).map {
                executor.submit<ChapterArtifactStore.TransactionOutcome> {
                    check(start.await(5, TimeUnit.SECONDS))
                    store.openCandidate(
                        manifest = migrated,
                        pageKey = "page.jpg",
                        origin = ArtifactOrigin.BATCH,
                        expectedPageVersion = 0L,
                        dependencyFingerprint = "concurrent-deps",
                    )
                }
            }
            start.countDown()

            val outcomes = futures.map { it.get(5, TimeUnit.SECONDS) }
            outcomes.count { it is ChapterArtifactStore.TransactionOutcome.Committed } shouldBe 1
            outcomes.count { it is ChapterArtifactStore.TransactionOutcome.Rejected } shouldBe 1
            store.readManifest()?.pages?.getValue("page.jpg")?.candidate.shouldNotBeNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `running stage recovers after process death and stale worker cannot commit`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps",
            nowEpochMs = 200L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val running = store.beginStage(
            opened.manifest,
            "page.jpg",
            ArtifactStage.OCR,
            generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = "deps",
            fingerprint = "ocr-v1",
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 201L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        val recovered = store.loadOrMigrate(legacySnapshot(identityTag = "changed")).manifest
        recovered.authority shouldBe ManifestAuthority.ARTIFACTS
        recovered.pages.getValue("page.jpg").ocr?.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        recovered.pages.getValue("page.jpg").candidate.shouldNotBeNull()
        recovered.pages.getValue("page.jpg").committed.shouldNotBeNull()

        val stale = store.commitStagePayload(
            running.manifest,
            "page.jpg",
            ArtifactStage.OCR,
            generationId,
            expectedPageVersion = 2L,
            expectedDependencyFingerprint = "deps",
            fingerprint = "ocr-v1",
            payload = buildJsonObject { put("stage", "ocr") },
            origin = ArtifactOrigin.BATCH,
        )
        stale.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Rejected>()
        store.readManifest() shouldBe recovered

        val retried = store.commitStagePayload(
            recovered,
            "page.jpg",
            ArtifactStage.OCR,
            generationId,
            expectedPageVersion = 3L,
            expectedDependencyFingerprint = "deps",
            fingerprint = "ocr-v1",
            payload = buildJsonObject { put("stage", "ocr") },
            origin = ArtifactOrigin.BATCH,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        retried.manifest.pages.getValue("page.jpg").ocr?.status shouldBe ArtifactStageStatus.READY
    }

    @Test
    fun `failed interrupted-stage recovery preserves the crash-safe backup`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps",
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val running = store.beginStage(
            opened.manifest,
            "page.jpg",
            ArtifactStage.OCR,
            generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = "deps",
            fingerprint = "ocr",
            origin = ArtifactOrigin.BATCH,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val backupName = AtomicChapterDocuments.backupNameFor(layout.manifestFileName)
        io.files.containsKey(backupName) shouldBe true

        io.failWrites = true
        val recovered = store.loadOrMigrate(legacySnapshot(identityTag = "changed")).manifest

        recovered.pages.getValue("page.jpg").ocr?.status shouldBe ArtifactStageStatus.RUNNING
        io.files.containsKey(backupName) shouldBe true
        running.manifest shouldBe recovered
    }

    @Test
    fun `cancel restores a textless display state captured before candidate open`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val textless = PageTranslation(
            sourceFileName = "page.jpg",
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
        )
        val migrated = store.loadOrMigrate(legacySnapshot(page = textless)).manifest
        migrated.pages.getValue("page.jpg").displayState shouldBe PageDisplayState.TEXTLESS_COMPLETE
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps",
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        val cancelled = store.cancelCandidate(
            opened.manifest,
            "page.jpg",
            opened.generationId.shouldNotBeNull(),
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        cancelled.manifest.pages.getValue("page.jpg").displayState shouldBe PageDisplayState.TEXTLESS_COMPLETE
    }

    @Test
    fun `reader adhoc can display a candidate but cannot publish batch context`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.READER_ADHOC,
            expectedPageVersion = 0L,
            dependencyFingerprint = "reader-deps",
            nowEpochMs = 300L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val context = store.commitContextCheckpoint(
            manifest = opened.manifest,
            pageKey = "page.jpg",
            naturalPageIndex = 0,
            checkpointHash = "reader-context",
            payload = buildJsonObject { put("context", "must-not-write") },
            generationId = generationId,
            origin = ArtifactOrigin.READER_ADHOC,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = "reader-deps",
        )
        context.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Rejected>()
        io.files.keys.none { it.contains("reader-context") } shouldBe true

        val translation = store.commitStagePayload(
            opened.manifest,
            "page.jpg",
            ArtifactStage.TRANSLATION,
            generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = "reader-deps",
            fingerprint = "reader-translation",
            payload = buildJsonObject { put("stage", "translation") },
            origin = ArtifactOrigin.READER_ADHOC,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val layoutCommit = store.commitStagePayload(
            translation.manifest,
            "page.jpg",
            ArtifactStage.LAYOUT,
            generationId,
            expectedPageVersion = 2L,
            expectedDependencyFingerprint = "reader-deps",
            fingerprint = "reader-layout",
            payload = buildJsonObject { put("stage", "layout") },
            origin = ArtifactOrigin.READER_ADHOC,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val display = "reader-image.jpg"
        io.write(display, byteArrayOf(5, 6, 7))
        val promoted = store.promoteCandidate(
            layoutCommit.manifest,
            "page.jpg",
            generationId,
            expectedPageVersion = 3L,
            expectedDependencyFingerprint = "reader-deps",
            bundle = ChapterArtifactStore.PromotionBundle(
                displayBaseKind = DisplayBaseKind.CLEANED_IMAGE,
                displayBaseFileName = display,
                translationFingerprint = "reader-translation",
                layoutFingerprint = "reader-layout",
            ),
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        promoted.manifest.pages.getValue("page.jpg").committed?.origin shouldBe ArtifactOrigin.READER_ADHOC
    }

    @Test
    fun `cancel rejects stale snapshot and removes only candidate-owned files`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps",
            nowEpochMs = 400L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val running = store.beginStage(
            opened.manifest,
            "page.jpg",
            ArtifactStage.OCR,
            generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = "deps",
            fingerprint = "ocr",
            origin = ArtifactOrigin.BATCH,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val sidecar = store.commitStagePayload(
            running.manifest,
            "page.jpg",
            ArtifactStage.OCR,
            generationId,
            expectedPageVersion = 2L,
            expectedDependencyFingerprint = "deps",
            fingerprint = "ocr",
            payload = buildJsonObject { put("stage", "ocr") },
            origin = ArtifactOrigin.BATCH,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val staleCancel = store.cancelCandidate(opened.manifest, "page.jpg", generationId)
        staleCancel.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Rejected>()

        val cancelled = store.cancelCandidate(sidecar.manifest, "page.jpg", generationId)
            .shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val page = cancelled.manifest.pages.getValue("page.jpg")
        page.candidate.shouldBeNull()
        page.committed?.origin shouldBe ArtifactOrigin.LEGACY
        cancelled.deletedFiles.any { it.endsWith(".json") } shouldBe true
        store.readManifest() shouldBe cancelled.manifest
    }
}
