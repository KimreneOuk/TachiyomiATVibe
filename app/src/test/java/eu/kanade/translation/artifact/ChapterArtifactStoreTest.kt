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
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.security.MessageDigest
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
        sourceFileName = "Chapter 1.json",
        glossaryFileName = "Chapter 1.glossary.json",
        glossaryIdentity = identity("glossary-$identityTag"),
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
    )

    private fun sourceIdentity(bytes: ByteArray, lastModifiedMs: Long = 0L) = LegacySourceIdentity(
        sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) },
        lengthBytes = bytes.size.toLong(),
        lastModifiedMs = lastModifiedMs,
    )

    private fun legacySnapshotForSource(bytes: ByteArray) = legacySnapshot(
        glossary = emptyMap(),
    ).copy(
        legacyIdentity = sourceIdentity(bytes),
        glossaryFileName = null,
        glossaryIdentity = null,
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
    fun `URI-backed rescue publishes manifest snapshots and glossary`() {
        val io = FakeChapterDocumentIo().apply { supportsNoReplaceRename = false }
        val result = artifactStore(io).loadOrMigrate(legacySnapshot())

        result.manifest.authority shouldBe ManifestAuthority.ARTIFACTS
        io.files[layout.manifestFileName] shouldNotBe null
        io.files[result.manifest.pages.getValue("page.jpg").committed!!.pageSnapshotFileName] shouldNotBe null
        io.files[result.manifest.glossary!!.fileName] shouldNotBe null
        io.files.keys.none { it.endsWith(".tmp") } shouldBe true
    }

    @Test
    fun `rescue records a collision-safe source target without overwriting it`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-v1".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.write("Chapter 1.json.migrated", "another-chapter".toByteArray())

        val result = artifactStore(io).loadOrMigrate(legacySnapshotForSource(sourceBytes))
        val metadata = result.manifest.legacyMigration.shouldNotBeNull()
        metadata.sourcePreservation shouldBe LegacyPreservationState.PRESERVED
        metadata.resolvedSourceFileName shouldNotBe "Chapter 1.json.migrated"
        metadata.resolvedSourceFileName.shouldNotBeNull().let { resolved ->
            io.read(resolved) shouldBe sourceBytes
        }
        io.read("Chapter 1.json.migrated") shouldBe "another-chapter".toByteArray()
        io.read("Chapter 1.json") shouldBe null
    }

    @Test
    fun `rescue preserves glossary identity with the same collision-safe rename`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-with-glossary".toByteArray()
        val glossaryBytes = "{\"sensei\":\"teacher\"}".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.write("Chapter 1.glossary.json", glossaryBytes)
        io.write("Chapter 1.glossary.json.migrated", "another-glossary".toByteArray())

        val snapshot = legacySnapshotForSource(sourceBytes).copy(
            glossary = mapOf("sensei" to "teacher"),
            glossaryFileName = "Chapter 1.glossary.json",
            glossaryIdentity = sourceIdentity(glossaryBytes),
        )
        val result = artifactStore(io).loadOrMigrate(snapshot)
        val metadata = result.manifest.legacyMigration.shouldNotBeNull()
        metadata.sourcePreservation shouldBe LegacyPreservationState.PRESERVED
        metadata.glossaryPreservation shouldBe LegacyPreservationState.PRESERVED
        metadata.resolvedGlossaryFileName shouldNotBe "Chapter 1.glossary.json.migrated"
        io.read("Chapter 1.glossary.json.migrated") shouldBe "another-glossary".toByteArray()
        metadata.resolvedGlossaryFileName.shouldNotBeNull().let { resolved ->
            io.read(resolved) shouldBe glossaryBytes
        }
        io.read("Chapter 1.glossary.json") shouldBe null
    }

    @Test
    fun `rescue adopts a matching target after rename before marker publication`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-v2".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.renamesToFail += "Chapter 1.json"
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshotForSource(sourceBytes))
        val intent = first.manifest.legacyMigration.shouldNotBeNull()
        intent.sourcePreservation shouldBe LegacyPreservationState.INTENT

        io.renamesToFail.clear()
        io.renameNoReplace("Chapter 1.json", "Chapter 1.json.migrated") shouldBe RenameResult.MOVED
        val recovered = store.reconcileLegacyPreservation(first.manifest)
        recovered.legacyMigration.shouldNotBeNull().sourcePreservation shouldBe
            LegacyPreservationState.PRESERVED
        recovered.legacyMigration.shouldNotBeNull().resolvedSourceFileName shouldBe
            "Chapter 1.json.migrated"
        store.readManifest() shouldBe recovered
    }

    @Test
    fun `source preservation adopts content match when rename changes mtime`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-mtime".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.lastModifiedTimes["Chapter 1.json"] = 11L
        io.renameChangesLastModified = true

        val snapshot = legacySnapshotForSource(sourceBytes).copy(
            legacyIdentity = sourceIdentity(sourceBytes, lastModifiedMs = 11L),
        )
        val result = artifactStore(io).loadOrMigrate(snapshot)
        val metadata = result.manifest.legacyMigration.shouldNotBeNull()

        metadata.sourcePreservation shouldBe LegacyPreservationState.PRESERVED
        metadata.resolvedSourceFileName.shouldNotBeNull().let { resolved ->
            io.read(resolved) shouldBe sourceBytes
            io.lastModified(resolved) shouldBe 12L
        }
    }

    @Test
    fun `glossary preservation adopts content match when rename changes mtime`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-glossary-mtime".toByteArray()
        val glossaryBytes = "{\"sensei\":\"teacher\"}".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.write("Chapter 1.glossary.json", glossaryBytes)
        io.lastModifiedTimes["Chapter 1.json"] = 11L
        io.lastModifiedTimes["Chapter 1.glossary.json"] = 21L
        io.renameChangesLastModified = true

        val snapshot = legacySnapshotForSource(sourceBytes).copy(
            glossary = mapOf("sensei" to "teacher"),
            glossaryFileName = "Chapter 1.glossary.json",
            glossaryIdentity = sourceIdentity(glossaryBytes, lastModifiedMs = 21L),
            legacyIdentity = sourceIdentity(sourceBytes, lastModifiedMs = 11L),
        )
        val result = artifactStore(io).loadOrMigrate(snapshot)
        val metadata = result.manifest.legacyMigration.shouldNotBeNull()

        metadata.glossaryPreservation shouldBe LegacyPreservationState.PRESERVED
        metadata.resolvedGlossaryFileName.shouldNotBeNull().let { resolved ->
            io.read(resolved) shouldBe glossaryBytes
            io.lastModified(resolved) shouldBe 22L
        }
    }

    @Test
    fun `later healthy chapter verifies then deletes exact preserved source`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-health-source".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        val migrated = artifactStore(io).loadOrMigrate(
            legacySnapshotForSource(sourceBytes).copy(
                pages = mapOf(
                    "page.jpg" to LegacyPageFacts(
                        displayablePage().copy(
                            cleanedImageName = null,
                            inpaintStatus = StageStatus.SKIPPED,
                            renderStatus = StageStatus.SKIPPED,
                        ),
                        CleanedFileState.NONE_RECORDED,
                    ),
                ),
            ),
        )
        val record = migrated.manifest.pages.getValue("page.jpg")
        val healthy = migrated.manifest.copy(
            pages = mapOf(
                "page.jpg" to record.copy(
                    displayState = PageDisplayState.DISPLAY_READY,
                    legacyVisible = null,
                ),
            ),
        )
        artifactStore(io).publishManifest(healthy) shouldBe true
        val store = artifactStore(io)
        val result = store.verifyLegacyArtifactHealth(
            healthy,
            currentVersionCode = healthy.legacyMigration!!.migratedByVersionCode + 1,
            nowEpochMs = 100L,
        )

        result.verified shouldBe true
        result.manifest.legacyMigration!!.health shouldBe LegacyMigrationHealth.VERIFIED
        result.manifest.legacyMigration!!.sourcePreservation shouldBe LegacyPreservationState.DELETED
        io.read("Chapter 1.json.migrated") shouldBe null
    }

    @Test
    fun `health gate retains preserved source for same version or warning page`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-health-warning".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        val migrated = artifactStore(io).loadOrMigrate(
            legacySnapshotForSource(sourceBytes).copy(
                pages = mapOf(
                    "page.jpg" to LegacyPageFacts(
                        displayablePage().copy(
                            cleanedImageName = null,
                            inpaintStatus = StageStatus.SKIPPED,
                            renderStatus = StageStatus.SKIPPED,
                        ),
                        CleanedFileState.NONE_RECORDED,
                    ),
                ),
            ),
        )
        val record = migrated.manifest.pages.getValue("page.jpg")
        val warning = migrated.manifest.copy(
            pages = mapOf("page.jpg" to record.copy(displayState = PageDisplayState.ORIGINAL_ONLY)),
        )
        artifactStore(io).publishManifest(warning) shouldBe true
        val store = artifactStore(io)

        store.verifyLegacyArtifactHealth(
            warning,
            currentVersionCode = warning.legacyMigration!!.migratedByVersionCode,
        ).verified shouldBe false
        store.verifyLegacyArtifactHealth(
            warning,
            currentVersionCode = warning.legacyMigration!!.migratedByVersionCode + 1,
        ).verified shouldBe false
        io.read("Chapter 1.json.migrated") shouldNotBe null
    }

    @Test
    fun `health gate retains source when content is replaced after preservation`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-health-replacement".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        val migrated = artifactStore(io).loadOrMigrate(
            legacySnapshotForSource(sourceBytes).copy(
                pages = mapOf(
                    "page.jpg" to LegacyPageFacts(
                        displayablePage().copy(
                            cleanedImageName = null,
                            inpaintStatus = StageStatus.SKIPPED,
                            renderStatus = StageStatus.SKIPPED,
                        ),
                        CleanedFileState.NONE_RECORDED,
                    ),
                ),
            ),
        )
        val record = migrated.manifest.pages.getValue("page.jpg")
        val healthy = migrated.manifest.copy(
            pages = mapOf("page.jpg" to record.copy(displayState = PageDisplayState.DISPLAY_READY)),
        )
        val target = healthy.legacyMigration!!.resolvedSourceFileName!!
        io.write(target, "external-replacement".toByteArray())
        val result = artifactStore(io).verifyLegacyArtifactHealth(
            healthy,
            currentVersionCode = healthy.legacyMigration!!.migratedByVersionCode + 1,
        )

        result.verified shouldBe true
        result.manifest.legacyMigration!!.sourcePreservation shouldBe LegacyPreservationState.PRESERVED
        io.read(target) shouldBe "external-replacement".toByteArray()
    }

    @Test
    fun `source preservation retains bytes when every destination races into existence`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-race".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.beforeRenameAttempt = { from, to ->
            if (from == "Chapter 1.json" && to.startsWith("Chapter 1.json.migrated")) {
                io.files[to] = "external-copy".toByteArray()
            }
        }

        val result = artifactStore(io).loadOrMigrate(legacySnapshotForSource(sourceBytes))
        val metadata = result.manifest.legacyMigration.shouldNotBeNull()

        metadata.sourcePreservation shouldBe LegacyPreservationState.INTENT
        io.read("Chapter 1.json") shouldBe sourceBytes
        io.files.filterKeys { it.startsWith("Chapter 1.json.migrated") }
            .values.forEach { it shouldBe "external-copy".toByteArray() }
    }

    @Test
    fun `glossary preservation retains bytes when every destination races into existence`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-glossary-race".toByteArray()
        val glossaryBytes = "{\"sensei\":\"teacher\"}".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.write("Chapter 1.glossary.json", glossaryBytes)
        io.beforeRenameAttempt = { from, to ->
            if (from == "Chapter 1.glossary.json" && to.startsWith("Chapter 1.glossary.json.migrated")) {
                io.files[to] = "external-glossary".toByteArray()
            }
        }

        val snapshot = legacySnapshotForSource(sourceBytes).copy(
            glossary = mapOf("sensei" to "teacher"),
            glossaryFileName = "Chapter 1.glossary.json",
            glossaryIdentity = sourceIdentity(glossaryBytes),
        )
        val result = artifactStore(io).loadOrMigrate(snapshot)
        val metadata = result.manifest.legacyMigration.shouldNotBeNull()

        metadata.sourcePreservation shouldBe LegacyPreservationState.PRESERVED
        metadata.glossaryPreservation shouldBe LegacyPreservationState.INTENT
        io.read("Chapter 1.glossary.json") shouldBe glossaryBytes
        io.files.filterKeys { it.startsWith("Chapter 1.glossary.json.migrated") }
            .values.forEach { it shouldBe "external-glossary".toByteArray() }
    }

    @Test
    fun `unsupported destination admission retains legacy source for retry`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-unsupported-rename".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.supportsNoReplaceRename = false

        val result = artifactStore(io).loadOrMigrate(legacySnapshotForSource(sourceBytes))
        val metadata = result.manifest.legacyMigration.shouldNotBeNull()

        metadata.sourcePreservation shouldBe LegacyPreservationState.INTENT
        io.read("Chapter 1.json") shouldBe sourceBytes
        io.read("Chapter 1.json.migrated") shouldBe null
    }

    @Test
    fun `snapshot publication failure keeps legacy authority and source bytes`() {
        val io = FakeChapterDocumentIo()
        val sourceBytes = "legacy-source-v3".toByteArray()
        io.write("Chapter 1.json", sourceBytes)
        io.writeNamesToFail += "/pages/"

        val result = artifactStore(io).loadOrMigrate(legacySnapshotForSource(sourceBytes))
        result.migratedFromLegacy shouldBe false
        result.manifest.authority shouldBe ManifestAuthority.LEGACY
        io.read("Chapter 1.json") shouldBe sourceBytes
        io.read("Chapter 1.json.migrated") shouldBe null
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
    fun `changed legacy identity cannot resync an artifact-authoritative chapter`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        store.loadOrMigrate(legacySnapshot(identityTag = "v1"))

        val editedPage = displayablePage().copy(
            blocks = mutableListOf(block(userEditedAt = 99L), block()),
        )
        val second = store.loadOrMigrate(legacySnapshot(identityTag = "v2", page = editedPage))

        second.resyncedFromLegacy shouldBe false
        second.manifest.legacySource shouldBe identity("v1")
        val committed = second.manifest.pages.getValue("page.jpg").committed.shouldNotBeNull()
        committed.hasManualEdits shouldBe false
        // The resynced manifest is durable: a plain read sees the same bytes.
        store.readManifest() shouldBe second.manifest
    }

    @Test
    fun `artifact reopen keeps runtime durable failures without legacy resync`() {
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
        second.resyncedFromLegacy shouldBe false
        second.manifest.durableFailures.getValue("page.jpg:TRANSLATION") shouldBe failure
    }

    @Test
    fun `reopen with a changed legacy identity returns the prior manifest unchanged`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))

        val second = store.loadOrMigrate(legacySnapshot(identityTag = "v2"))
        // The in-memory result of this load is the prior manifest.
        second.manifest shouldBe first.manifest
        second.resyncedFromLegacy shouldBe false
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
    fun `corrupt manifest recovery does not overwrite an existing quarantine`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot())
        store.publishManifest(first.manifest.copy(updatedAtEpochMs = 43L))
        val corruptBytes = "still not json".toByteArray()
        io.files[layout.manifestFileName] = corruptBytes
        io.files["${layout.manifestFileName}.corrupt"] = "previous copy".toByteArray()

        store.readManifest() shouldBe first.manifest
        io.read("${layout.manifestFileName}.corrupt") shouldBe "previous copy".toByteArray()
        io.files.keys.count {
            it.startsWith("${layout.manifestFileName}.corrupt.")
        } shouldBe 1
        io.files.values.count { it.contentEquals(corruptBytes) } shouldBe 1
    }

    @Test
    fun `load preserves a valid backup when primary promotion is interrupted`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val first = store.loadOrMigrate(legacySnapshot())
        val artifactManifest = first.manifest.copy(
            authority = ManifestAuthority.ARTIFACTS,
            updatedAtEpochMs = 43L,
        )
        store.publishManifest(artifactManifest)
        store.publishManifest(artifactManifest.copy(updatedAtEpochMs = 44L))

        val backupName = AtomicChapterDocuments.backupNameFor(layout.manifestFileName)
        val backupBytes = io.read(backupName).shouldNotBeNull()
        io.deletedNames.clear()
        io.files.remove(layout.manifestFileName)
        io.renamesToFail += backupName

        val recovered = store.loadOrMigrate(legacySnapshot())

        recovered.manifest.authority shouldBe ManifestAuthority.ARTIFACTS
        recovered.manifest.pages.keys shouldBe setOf("page.jpg")
        io.read(backupName) shouldBe backupBytes
        io.files.containsKey(layout.manifestFileName) shouldBe false
        io.deletedNames.none { it == backupName } shouldBe true
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
        io.ownedRenamesToFail += AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
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
        io.write("Chapter 1_artifacts/context/legacy-orphan.json", byteArrayOf(9))
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
        io.files.containsKey("Chapter 1_artifacts/context/legacy-orphan.json") shouldBe false
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
    fun `incomplete legacy page materializes through the provisional committed pointer`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val page = displayablePage().copy(
            blocks = mutableListOf(block(), block().copy(translation = "")),
            translationStatus = StageStatus.PARTIAL,
        )
        val migrated = store.loadOrMigrate(legacySnapshot(page = page)).manifest
        val before = migrated.pages.getValue("page.jpg").committed.shouldNotBeNull()
        before.provisional shouldBe true
        before.origin shouldBe ArtifactOrigin.LEGACY
        before.pageSnapshotFileName.shouldNotBeNull()

        val materialized = store.materializeLegacyCommittedSnapshot(migrated, "page.jpg", page)
            .shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val committed = materialized.manifest.pages.getValue("page.jpg").committed.shouldNotBeNull()
        val snapshot = store.readPageSnapshot(committed.pageSnapshotFileName).shouldNotBeNull()
        snapshot.blocks.size shouldBe 2
        snapshot.translationStatus shouldBe StageStatus.PARTIAL
        snapshot.cleanedImageName shouldBe page.cleanedImageName
    }

    @Test
    fun `incomplete legacy page with unusable image materializes original-source compatibility`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val page = displayablePage()
        val migrated = store.loadOrMigrate(
            legacySnapshot(page = page).copy(
                pages = mapOf("page.jpg" to LegacyPageFacts(page, CleanedFileState.CORRUPT_BYTES)),
            ),
        ).manifest
        val committed = migrated.pages.getValue("page.jpg").committed.shouldNotBeNull()
        committed.displayBase.kind shouldBe DisplayBaseKind.ORIGINAL_SOURCE

        val materialized = store.materializeLegacyCommittedSnapshot(migrated, "page.jpg", page)
            .shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val snapshot = store.readPageSnapshot(
            materialized.manifest.pages.getValue("page.jpg").committed?.pageSnapshotFileName,
        ).shouldNotBeNull()
        snapshot.cleanedImageName shouldBe null
    }

    @Test
    fun `promotion reuses an identical candidate snapshot and defers retention sweep`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val opened = store.openCandidate(
            manifest = migrated,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps",
            nowEpochMs = 100L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val openedPage = opened.manifest.pages.getValue("page.jpg")
        val page = displayablePage()
        io.write(layout.legacyCompanionImageFile(page.cleanedImageName!!), byteArrayOf(2))
        val persisted = store.persistLiveCandidate(
            manifest = opened.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = openedPage.pageVersion,
            expectedDependencyFingerprint = "deps",
            pageSnapshot = page,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 101L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val candidateFile = persisted.manifest.pages.getValue("page.jpg").candidate
            .shouldNotBeNull().pageSnapshotFileName.shouldNotBeNull()
        val committedTemp = AtomicChapterDocuments.tempNameFor(
            layout.committedPageSnapshotFile("page.jpg", generationId),
        )
        io.writtenNames.clear()
        io.listedDirectories.clear()

        val promoted = store.promoteLiveCandidate(
            manifest = persisted.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = persisted.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps",
            pageSnapshot = page,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 102L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        promoted.manifest.pages.getValue("page.jpg").candidate.shouldBeNull()
        io.files.containsKey(candidateFile) shouldBe true
        io.writtenNames.count { it == committedTemp } shouldBe 1
        io.writtenNames.none { it == AtomicChapterDocuments.tempNameFor(candidateFile) } shouldBe true
        io.listedDirectories shouldBe emptyList()

        store.reconcileRetention(promoted.manifest)
        io.files.containsKey(candidateFile) shouldBe false
        io.listedDirectories.isEmpty() shouldBe false
    }

    @Test
    fun `candidate and retryable failure publish atomically without replacing committed display`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val committedGeneration = migrated.pages.getValue("page.jpg").committed
            .shouldNotBeNull().generationId
        val opened = store.openCandidate(
            manifest = migrated,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps",
            nowEpochMs = 100L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val openedPage = opened.manifest.pages.getValue("page.jpg")
        val partial = displayablePage().apply {
            sourceFileName = "page.jpg"
            translationStatus = StageStatus.PARTIAL
        }
        val failure = DurableFailureMetadata(
            pageKey = "page.jpg",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.TRANSIENT,
            retryCount = 1,
            lastFailedAtEpochMs = 101L,
            nextEligibleRetryAtEpochMs = 200L,
            failureFingerprint = "deps",
            envelopeId = "envelope-hash",
            missingBlockIds = setOf("block-hash"),
        )

        val persisted = store.persistLiveCandidateAndFailure(
            manifest = opened.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = openedPage.pageVersion,
            expectedDependencyFingerprint = "deps",
            pageSnapshot = partial,
            origin = ArtifactOrigin.BATCH,
            failure = failure,
            nowEpochMs = 101L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        persisted.manifest.pages.getValue("page.jpg").committed
            .shouldNotBeNull().generationId shouldBe committedGeneration
        persisted.manifest.pages.getValue("page.jpg").candidate.shouldNotBeNull()
        persisted.manifest.durableFailures["page.jpg:TRANSLATION"] shouldBe failure
        persisted.manifest.pages.getValue("page.jpg").translation
            .shouldNotBeNull().status shouldBe ArtifactStageStatus.FAILED_RETRYABLE

        io.write(layout.legacyCompanionImageFile(partial.cleanedImageName!!), byteArrayOf(2))
        val promoted = store.promoteLiveCandidate(
            manifest = persisted.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = persisted.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps",
            pageSnapshot = partial.copy(translationStatus = StageStatus.READY),
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 102L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        promoted.manifest.durableFailures.containsKey("page.jpg:TRANSLATION") shouldBe false
        promoted.manifest.pages.getValue("page.jpg").candidate.shouldBeNull()
    }

    @Test
    fun `process restart turns an interrupted artifact stage into a durable retryable failure`() {
        val io = FakeChapterDocumentIo()
        val store = transactionStore(io)
        val migrated = store.loadOrMigrate(legacySnapshot()).manifest
        val interrupted = migrated.copy(
            authority = ManifestAuthority.ARTIFACTS,
            pages = migrated.pages + (
                "page.jpg" to migrated.pages.getValue("page.jpg").copy(
                    translation = StageArtifactRecord(
                        status = ArtifactStageStatus.RUNNING,
                        fingerprint = "deps",
                        origin = ArtifactOrigin.BATCH,
                    ),
                )
                ),
        )
        store.publishManifest(interrupted) shouldBe true

        val restarted = store.loadOrMigrate(legacySnapshot()).manifest

        restarted.pages.getValue("page.jpg").translation
            .shouldNotBeNull().status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        val failure = restarted.durableFailures["page.jpg:TRANSLATION"].shouldNotBeNull()
        failure.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        failure.category shouldBe FailureCategory.LEGACY_UNKNOWN
        failure.nextEligibleRetryAtEpochMs shouldNotBe null
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

    // ------------------------------------------------------------------
    // T924 Stage 1 gate 1.1 (T924-SC-04): a pre-change schemaVersion-2
    // manifest loads cleanly under the new code (additive pointer fields
    // defaulted) and survives a write cycle rewritten as schemaVersion 3
    // with the old data intact.
    // ------------------------------------------------------------------

    private fun v2FixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/t924/manifest-v2.json")!!.readBytes()

    @Test
    fun `pre-change v2 manifest fixture loads cleanly with new pointer fields defaulted`() {
        val io = FakeChapterDocumentIo()
        io.write(layout.manifestFileName, v2FixtureBytes())

        val loaded = artifactStore(io).loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L))

        loaded.migratedFromLegacy shouldBe false
        loaded.resyncedFromLegacy shouldBe false
        val manifest = loaded.manifest
        // In-memory normalization stamps the current schema version.
        manifest.schemaVersion shouldBe ChapterArtifactManifest.SCHEMA_VERSION
        manifest.authority shouldBe ManifestAuthority.ARTIFACTS
        // Old data intact.
        manifest.chapterKey shouldBe "Chapter 1"
        manifest.expectedPageCount shouldBe 1
        manifest.expectedPageCountTrusted shouldBe true
        manifest.legacySource shouldBe LegacySourceIdentity(
            sha256 = "4d3c2b1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c",
            lengthBytes = 4211L,
            lastModifiedMs = 1757030000000L,
        )
        manifest.glossary shouldBe GlossaryPointer(
            fileName = "Chapter 1_artifacts/glossary/chapter.glossary.1.json",
            version = 1,
            versionFingerprint = "2b1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a",
        )
        val page = manifest.pages.getValue("0001.jpg")
        page.pageVersion shouldBe 3L
        page.committed.shouldNotBeNull().generationId shouldBe "g-1757040000000-0001jpg-5baa61e4"
        page.displayState shouldBe PageDisplayState.DISPLAY_READY
        // New additive pointer fields load with neutral defaults.
        manifest.activeRun shouldBe null
        manifest.ocrCheckpoints shouldBe emptyMap()
        manifest.analysisChunks shouldBe emptyList()
        manifest.profile shouldBe null
        manifest.envelopePlan shouldBe null
        manifest.layoutPlans shouldBe emptyMap()
        manifest.colorPreparations shouldBe emptyMap()
        manifest.context shouldBe null
    }

    @Test
    fun `v2 manifest survives a write cycle rewritten as v3 with old data intact`() {
        val io = FakeChapterDocumentIo()
        io.write(layout.manifestFileName, v2FixtureBytes())
        val store = artifactStore(io)
        val loaded = store.loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L)).manifest

        store.publishManifest(loaded.copy(updatedAtEpochMs = 999L)) shouldBe true

        String(io.read(layout.manifestFileName)!!).contains("\"schemaVersion\":${ChapterArtifactManifest.SCHEMA_VERSION}") shouldBe true
        val reparsed = store.readManifest().shouldNotBeNull()
        reparsed shouldBe loaded.copy(updatedAtEpochMs = 999L)
        // The old page record and glossary pointer survive the rewrite byte-for-byte.
        reparsed.pages shouldBe loaded.pages
        reparsed.glossary shouldBe loaded.glossary
        reparsed.legacyMigration shouldBe loaded.legacyMigration
        // Second load with unchanged bytes takes the fast path and stays equal.
        val reloaded = store.loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L))
        reloaded.manifest shouldBe reparsed
    }
}
