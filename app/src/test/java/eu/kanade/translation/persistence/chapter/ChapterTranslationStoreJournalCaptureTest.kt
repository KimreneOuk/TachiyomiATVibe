package eu.kanade.translation.persistence.chapter

import com.hippo.unifile.FakeUniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.ArtifactOrigin
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.CandidateGenerationMetadata
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.CleanedImageIdentity
import eu.kanade.translation.persistence.artifact.CommittedBundleMetadata
import eu.kanade.translation.persistence.artifact.DisplayBaseKind
import eu.kanade.translation.persistence.artifact.DisplayBaseReference
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.SourceIdentity
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.journal.ChapterJournalBulkRecord
import eu.kanade.translation.persistence.journal.ChapterJournalFormat
import eu.kanade.translation.persistence.journal.ChapterJournalInventoryRecord
import eu.kanade.translation.persistence.journal.ChapterJournalInventorySnapshot
import eu.kanade.translation.persistence.journal.ChapterJournalPageOutcome
import eu.kanade.translation.persistence.journal.ChapterJournalRecord
import eu.kanade.translation.persistence.journal.ChapterJournalReplayEpoch
import eu.kanade.translation.persistence.journal.ChapterJournalReplayReducer
import eu.kanade.translation.persistence.journal.ChapterJournalReplaySegment
import eu.kanade.translation.persistence.journal.ChapterJournalSink
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
import eu.kanade.translation.persistence.journal.FileChapterJournalStorage
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.UUID

class ChapterTranslationStoreJournalCaptureTest {

    @TempDir
    lateinit var chapterDir: File

    @Test
    fun `successful legacy artifact commit is handed to the shadow journal`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Chapter 1.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        var recordedPageHash: String? = null

        try {
            store.updatePage("page.jpg") {
                PageTranslation(
                    sourceFileName = "page.jpg",
                    blocks = mutableListOf(
                        TranslationBlock(
                            text = "source",
                            translation = "manual edit",
                            width = 10f,
                            height = 10f,
                            x = 0f,
                            y = 0f,
                            symHeight = 1f,
                            symWidth = 1f,
                            angle = 0f,
                            userEditedAt = 1L,
                        ),
                    ),
                )
            }
            runCurrent()

            val scanned = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            )
            scanned.validHeader shouldBe true
            scanned.frames.map { it.commitSeq } shouldContainExactly listOf(null, 1L)
            scanned.frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
            )
            val inventory = journalJson.decodeFromString<ChapterJournalInventoryRecord>(
                scanned.frames.first().payload.decodeToString(),
            )
            inventory.expectedPageKeys shouldContainExactly listOf("page.jpg")
            inventory.expectedPageCount shouldBe 1
            inventory.sourceFingerprint shouldBe StageFingerprints.canonicalFingerprint(
                listOf("journal-source-inventory-v2", false, 1, "page.jpg", ""),
            )
            val record = journalJson.decodeFromString<ChapterJournalRecord>(
                scanned.frames.last().payload.decodeToString(),
            )
            record.pageKey shouldBe "page.jpg"
            record.generation shouldBe 0L
            record.state?.blocks?.single()?.translation shouldBe "manual edit"
            record.artifactContentHash shouldBe StageFingerprints.pageSnapshot(record.state!!)
            recordedPageHash = record.artifactContentHash
        } finally {
            store.closeAndFlush()
        }

        writer.ackedHighWaterSeq shouldBe 2L

        // Reopen the persisted legacy artifact and derive the semantic snapshot hash again,
        // without consulting the live store instance that performed the original write.
        val reopenedStore = ChapterTranslationStore.openArtifact(root, "Chapter 1.json")
        try {
            val persistedPage = reopenedStore.state.value.getValue("page.jpg")
            StageFingerprints.pageSnapshot(persistedPage) shouldBe recordedPageHash
        } finally {
            reopenedStore.closeAndFlush()
        }
    }

    @Test
    fun `real captured journal prefix replays to the immutable published page snapshot`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Replay parity chapter.json")
        val cleanedImageName = "page.cleaned.legacy-token.jpg"
        val cleanedImageBytes = "real-cleaned-image-bytes".encodeToByteArray()
        val cleanedImageHash = StageFingerprints.sha256Hex(cleanedImageBytes)
        val imageDirectory = checkNotNull(root.createDirectory("Replay parity chapter_images"))
        checkNotNull(imageDirectory.createFile(cleanedImageName)).openOutputStream().use { output ->
            output.write(cleanedImageBytes)
        }
        checkNotNull(imageDirectory.createFile(CleanedImageIdentity.sidecarName(cleanedImageName)))
            .openOutputStream().use { output ->
                output.write(
                    CleanedImageIdentity.encode(
                        CleanedImageIdentity.create("page.jpg", cleanedImageName, cleanedImageBytes.inputStream()),
                    ),
                )
            }
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        var closed = false

        try {
            store.preRegisterPages(listOf("page.jpg")) shouldBe ChapterTranslationStore.PagePreRegistration.Accepted
            store.updatePage("page.jpg") {
                PageTranslation(
                    sourceFileName = "page.jpg",
                    cleanedImageName = cleanedImageName,
                    cleanedImageContentHash = cleanedImageHash,
                    blocks = mutableListOf(
                        TranslationBlock(
                            text = "source",
                            translation = "durable output",
                            width = 10f,
                            height = 10f,
                            x = 0f,
                            y = 0f,
                            symHeight = 1f,
                            symWidth = 1f,
                            angle = 0f,
                        ),
                    ),
                )
            }
            runCurrent()
            val expectedSnapshotPages = store.pages
            (store.state.value.getValue("page.jpg") === expectedSnapshotPages.getValue("page.jpg")) shouldBe true

            store.closeAndFlush()
            writer.drainAndClose()
            closed = true

            val persistedEpoch = ChapterJournalReplayEpoch(
                order = ChapterJournalFormat.EpochOrderKey(0L, 0L, UUID(0L, 0L)),
                segments = storage.segmentIndexes().map { index ->
                    ChapterJournalReplaySegment(index, storage.readSegment(index))
                },
            )
            val artifactEngine = checkNotNull(store.artifactEngine)
            val artifactManifest = checkNotNull(store.readArtifactManifest())
            val replayed = ChapterJournalReplayReducer.replay(
                epochs = listOf(persistedEpoch),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(artifactEngine, artifactManifest),
            )

            replayed.pages shouldBe expectedSnapshotPages
            replayed.expectedPageKeys shouldContainExactly expectedSnapshotPages.keys
            replayed.expectedPageCount shouldBe expectedSnapshotPages.size
            replayed.missingPageKeys shouldBe emptySet()
            replayed.hasCompleteInventory shouldBe true
        } finally {
            if (!closed) {
                store.closeAndFlush()
                writer.drainAndClose()
            }
        }
    }

    @Test
    fun `artifact open does not expose a candidate ahead of the journal prefix`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val privateJournalRoot = File(chapterDir, "private-journal")
        val journalIdentity = "source:manga:Cutover chapter.json"
        val layout = ChapterArtifactLayout("Cutover chapter")
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(root))
        val engine = ChapterArtifactEngine(documents, layout)
        val pageKey = "page.jpg"
        val journalFailure = DurableFailureMetadata(
            pageKey = pageKey,
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.TRANSIENT,
            retryCount = 2,
            lastFailureMessage = "journal-owned retryable failure",
            lastFailedAtEpochMs = 2L,
        )
        val journalWinner = PageTranslation(
            sourceFileName = pageKey,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "journal winner",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
            translationStatus = StageStatus.FAILED,
            translationError = "journal-owned retryable failure",
        ).toPublishedPage()
        val journalHash = StageFingerprints.pageSnapshot(journalWinner)
        val committedSnapshot = layout.committedPageSnapshotFile(pageKey, "journal-winner")
        documents.publishJson(committedSnapshot, journalWinner.toDraft()) shouldBe true

        val artifactAhead = journalWinner.toDraft().apply {
            blocks = blocks.map { it.copy(translation = "manifest-only candidate") }.toMutableList()
            translationStatus = StageStatus.READY
            translationError = null
        }
        val candidateSnapshot = layout.candidatePageSnapshotFile(pageKey, "artifact-ahead")
        val candidateHash = StageFingerprints.pageSnapshot(artifactAhead)
        documents.publishJson(candidateSnapshot, artifactAhead) shouldBe true

        val candidateId = "artifact-ahead"
        val pageRecord = PageArtifactRecord(
            pageKey = pageKey,
            committed = CommittedBundleMetadata(
                generationId = "journal-winner",
                displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                translationFingerprint = journalHash,
                pageSnapshotFileName = committedSnapshot,
            ),
            candidate = CandidateGenerationMetadata(
                generationId = candidateId,
                dependencyFingerprint = journalHash,
                pageSnapshotFileName = candidateSnapshot,
                pageSnapshotFingerprint = candidateHash,
            ),
        )
        var manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(pageKey to pageRecord),
            durableFailures = mapOf(
                "$pageKey:${ArtifactStage.TRANSLATION.name}" to DurableFailureMetadata(
                    pageKey = pageKey,
                    stage = ArtifactStage.TRANSLATION,
                    status = ArtifactStageStatus.FAILED_TERMINAL,
                    category = FailureCategory.PROTOCOL,
                    retryCount = 1,
                    lastFailureMessage = "stale manifest-only failure",
                    lastFailedAtEpochMs = 1L,
                ),
            ),
            expectedPageCount = 1,
            expectedPageCountTrusted = true,
            activeCandidateGenerationIds = setOf(candidateId),
        )
        engine.publishManifest(manifest) shouldBe true
        val frozenRunConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        )
        val completeRunRecord = ChapterRunRecord(
            runId = "complete-cutover-run",
            state = ChapterRunState.COMPLETE,
            frozenConfig = frozenRunConfig,
            frozenRunConfigFingerprint = ChapterProfileBatchCoordinator.runConfigFingerprint(frozenRunConfig),
            orderedSourceDigest = StageFingerprints.sha256Hex("ordered-source".encodeToByteArray()),
            analysisPolicyFingerprint = StageFingerprints.sha256Hex("analysis-policy".encodeToByteArray()),
            envelopePolicyFingerprint = StageFingerprints.sha256Hex("envelope-policy".encodeToByteArray()),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val completeRunFingerprint = StageFingerprints.sha256Hex(
            ArtifactDocumentJson.encodeToString(completeRunRecord).encodeToByteArray(),
        )
        manifest = engine.publishActiveRun(
            manifest = engine.readManifest() ?: error("fixture: manifest missing before run record"),
            record = completeRunRecord,
            contentFingerprint = completeRunFingerprint,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>().manifest

        // Write an ordinary CRC-framed journal prefix to the same app-private epoch layout
        // consumed by production open, then materialize its segment as a real file.
        val epochOrdinal = 4L
        val sessionId = UUID.randomUUID()
        val chapterIdentityHash = StageFingerprints.sha256Hex(journalIdentity.toByteArray())
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val storage = MemoryStorage()
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { journalJson.encodeToString(it).encodeToByteArray() },
            encodeBulkRecord = { journalJson.encodeToString(it).encodeToByteArray() },
            encodeInventory = { journalJson.encodeToString(it).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
            storeGeneration = 0L,
            epochOrdinal = epochOrdinal,
            sessionId = sessionId,
            chapterIdentityHash = chapterIdentityHash,
        )
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(
                commitSeq = writer.nextCommitSeq(),
                credit = credit,
                pageKey = pageKey,
                generation = 0L,
                fencingToken = 0L,
                page = journalWinner,
                durableFailure = journalFailure,
                artifactContentHash = journalHash,
                inventory = ChapterJournalInventorySnapshot(
                    expectedPageKeys = setOf(pageKey),
                    expectedPageCount = 1,
                    sourceShaByPageKey = emptyMap(),
                ),
            )
            writer.flushToCaptureBarrier() shouldBe 2L
        } finally {
            writer.drainAndClose()
        }
        val epochDirectory = File(
            File(File(privateJournalRoot, "translation-journal-v1"), chapterIdentityHash),
            "epoch-${epochOrdinal.toString().padStart(20, '0')}-g0-$sessionId",
        )
        check(epochDirectory.mkdirs())
        val segmentBytes = storage.readSegment(0L)
        epochDirectory.resolve("segment-00000000.tjr").writeBytes(segmentBytes)

        val scannedPrefix = ChapterJournalFormat.scanSegment(
            bytes = segmentBytes,
            expectedSegmentIndex = 0L,
            expectedGeneration = 0L,
            expectedEpochOrdinal = epochOrdinal,
            expectedSessionId = sessionId,
            firstExpectedFrameSeq = 1L,
            firstExpectedCommitSeq = 1L,
        )
        scannedPrefix.validHeader shouldBe true
        scannedPrefix.frames.map { it.kind } shouldContainExactly listOf(
            ChapterJournalFormat.RecordKind.INVENTORY,
            ChapterJournalFormat.RecordKind.FREE_STATE,
        )
        val scannedRecord = journalJson.decodeFromString<ChapterJournalRecord>(
            scannedPrefix.frames.last().payload.decodeToString(),
        )
        scannedRecord.pageKey shouldBe pageKey
        scannedRecord.state shouldBe journalWinner
        scannedRecord.artifactContentHash shouldBe journalHash
        scannedRecord.durableFailure shouldBe journalFailure

        // Pin the setup: the production resolver validates the acknowledged prefix even though
        // the newer candidate is manifest-visible.
        val artifactResolver = ChapterJournalReplayReducer.artifactResolver(engine, manifest)
        artifactResolver.matches(pageKey, journalHash, journalWinner) shouldBe true
        val replay = ChapterJournalReplayReducer.replay(
            epochs = ChapterJournalReplayReducer.readAppPrivateEpochs(privateJournalRoot, journalIdentity).also {
                it.size shouldBe 1
                it.single().segments.map(ChapterJournalReplaySegment::index) shouldContainExactly listOf(0L)
            },
            artifactResolver = artifactResolver,
            expectedChapterIdentityHash = chapterIdentityHash,
        )
        replay.validFrameCount shouldBe 2
        replay.appliedRecordCount shouldBe 1
        replay.pages[pageKey] shouldBe journalWinner
        replay.invalidPageKeys shouldBe emptySet()
        replay.durableFailures shouldBe mapOf("$pageKey:${ArtifactStage.TRANSLATION.name}" to journalFailure)

        val reopened = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Cutover chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
        )
        try {
            reopened.pages[pageKey] shouldBe journalWinner
            reopened.pages.getValue(pageKey).blocks.single().translation shouldBe "journal winner"
            reopened.durableFailure(pageKey, ArtifactStage.TRANSLATION) shouldBe journalFailure
            reopened.durableFailuresSnapshot() shouldBe mapOf(
                "$pageKey:${ArtifactStage.TRANSLATION.name}" to journalFailure,
            )
            reopened.artifactStatus() shouldBe Translation.State.PAUSED

            // A durable COMPLETE record preserves translated-but-unrendered
            // pages at the display tail. The manifest's READY candidate cannot
            // provide the evidence: only this accepted active-store mutation
            // clears the retryable failure and makes the exception apply.
            reopened.updatePage(pageKey) { page ->
                checkNotNull(page).copy(
                    translationStatus = StageStatus.READY,
                    translationError = null,
                )
            }
            reopened.durableFailuresSnapshot() shouldBe emptyMap()
            reopened.pages.getValue(pageKey).translationStatus shouldBe StageStatus.READY
            reopened.pages.getValue(pageKey).renderStatus shouldBe StageStatus.PENDING
            reopened.artifactStatus() shouldBe Translation.State.TRANSLATED
        } finally {
            reopened.closeAndFlush()
        }
    }

    @Test
    fun `empty journal seeds the valid legacy manifest page and committed display`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val privateJournalRoot = File(chapterDir, "empty-journal")
        check(privateJournalRoot.mkdirs())
        val journalIdentity = "source:manga:Empty journal chapter.json"
        val layout = ChapterArtifactLayout("Empty journal chapter")
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(root))
        val engine = ChapterArtifactEngine(documents, layout)
        val pageKey = "legacy-page.jpg"
        val legacyPage = PageTranslation(
            sourceFileName = pageKey,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "legacy manifest winner",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        ).toPublishedPage()
        val snapshotName = layout.committedPageSnapshotFile(pageKey, "legacy-commit")
        documents.publishJson(snapshotName, legacyPage.toDraft()) shouldBe true
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                pageKey to PageArtifactRecord(
                    pageKey = pageKey,
                    committed = CommittedBundleMetadata(
                        generationId = "legacy-commit",
                        displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                        translationFingerprint = StageFingerprints.pageSnapshot(legacyPage),
                        pageSnapshotFileName = snapshotName,
                    ),
                ),
            ),
            expectedPageCount = 1,
            expectedPageCountTrusted = true,
        )
        engine.publishManifest(manifest) shouldBe true

        val reopened = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Empty journal chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
            journalStorageFactory = ::jvmFileBackedJournalStorage,
        )
        try {
            // An empty journal means this pre-cutover manifest is the seed input;
            // it must remain visible until its state has been captured into the journal.
            reopened.pages[pageKey] shouldBe legacyPage
            reopened.display.value[pageKey]?.blocks?.single()?.translation shouldBe "legacy manifest winner"
            val seed = reopened.journalSeedDiagnostics
            check(seed.failure == null) {
                "journal seed failed: ${describeCauseChain(seed.failure)}"
            }
            seed.inventoryCaptureRequested shouldBe true
            seed.pageKeysCaptureRequested shouldBe setOf(pageKey)
            seed.firstBarrierFrameSeq shouldBe 2L
            seed.trustedInventoryCaptureRequested shouldBe true
            seed.trustedBarrierFrameSeq shouldBe 3L
            seed.completed shouldBe true
            val seededPrefix = checkNotNull(reopened.replayJournalForRecovery())
            seededPrefix.validFrameCount shouldBe 3
            seededPrefix.pages[pageKey] shouldBe legacyPage
            seededPrefix.pageOutcomes[pageKey] shouldBe ChapterJournalPageOutcome.RECORDED
            seededPrefix.inventory?.hasTrustedExpectedPageCount shouldBe true
        } finally {
            reopened.closeAndFlush()
        }
    }

    @Test
    fun `trusted terminal-lag journal fills a verified committed page with no journal outcome`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val privateJournalRoot = File(chapterDir, "terminal-lag-journal").also { check(it.mkdirs()) }
        val journalIdentity = "source:manga:Terminal lag chapter.json"
        val layout = ChapterArtifactLayout("Terminal lag chapter")
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(root))
        val engine = ChapterArtifactEngine(documents, layout)
        val capturedPageKey = "captured-before-lag.jpg"
        val committedAfterLagPageKey = "committed-after-lag.jpg"

        fun translatedPage(pageKey: String, translation: String) = PageTranslation(
            sourceFileName = pageKey,
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
            originalImageFallback = true,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = translation,
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        ).toPublishedPage()

        val capturedPage = translatedPage(capturedPageKey, "captured before lag")
        val committedAfterLagPage = translatedPage(committedAfterLagPageKey, "committed after lag")
        fun commitSnapshot(pageKey: String, generationId: String, page: PageTranslationView) =
            CommittedBundleMetadata(
                generationId = generationId,
                displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                translationFingerprint = StageFingerprints.pageSnapshot(page),
                pageSnapshotFileName = layout.committedPageSnapshotFile(pageKey, generationId),
            )

        val capturedCommit = commitSnapshot(capturedPageKey, "captured-commit", capturedPage)
        val postLagCommit = commitSnapshot(committedAfterLagPageKey, "post-lag-commit", committedAfterLagPage)
        documents.publishJson(capturedCommit.pageSnapshotFileName!!, capturedPage.toDraft()) shouldBe true
        documents.publishJson(postLagCommit.pageSnapshotFileName!!, committedAfterLagPage.toDraft()) shouldBe true
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                capturedPageKey to PageArtifactRecord(pageKey = capturedPageKey, committed = capturedCommit),
                committedAfterLagPageKey to PageArtifactRecord(
                    pageKey = committedAfterLagPageKey,
                    committed = postLagCommit,
                ),
            ),
            expectedPageCount = 2,
            expectedPageCountTrusted = true,
        )
        engine.publishManifest(manifest) shouldBe true

        val chapterHash = StageFingerprints.sha256Hex(journalIdentity.toByteArray(Charsets.UTF_8))
        val sessionId = UUID.randomUUID()
        val epochDirectory = File(
            File(File(privateJournalRoot, "translation-journal-v1"), chapterHash),
            "epoch-${1L.toString().padStart(20, '0')}-g0-$sessionId",
        ).also { check(it.mkdirs()) }
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val trustedInventory = ChapterJournalInventorySnapshot(
            expectedPageKeys = setOf(capturedPageKey, committedAfterLagPageKey),
            expectedPageCount = 2,
            sourceShaByPageKey = emptyMap(),
            expectedPageCountTrusted = true,
        ).record(chapterHash)
        val capturedRecord = ChapterJournalRecord(
            pageKey = capturedPageKey,
            generation = 0L,
            fencingToken = 0L,
            pageVersion = capturedPage.pageVersion,
            state = capturedPage,
            artifactContentHash = StageFingerprints.pageSnapshot(capturedPage),
        )
        val terminalLagRecord = ChapterJournalRecord(
            pageKey = "",
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 0L,
            state = null,
            terminalReason = "${ChapterJournalRecord.TERMINAL_LAG_REASON_CREDIT_WINDOW}:1",
            terminalCommitSeq = 2L,
        )
        val segment = ByteArrayOutputStream().apply {
            write(ChapterJournalFormat.segmentHeader(0L, 0L, 1L, sessionId))
            write(
                ChapterJournalFormat.encodeFrame(
                    1L,
                    null,
                    ChapterJournalFormat.RecordKind.INVENTORY,
                    journalJson.encodeToString(trustedInventory).encodeToByteArray(),
                ),
            )
            write(
                ChapterJournalFormat.encodeFrame(
                    2L,
                    1L,
                    ChapterJournalFormat.RecordKind.FREE_STATE,
                    journalJson.encodeToString(capturedRecord).encodeToByteArray(),
                ),
            )
            write(
                ChapterJournalFormat.encodeFrame(
                    3L,
                    null,
                    ChapterJournalFormat.RecordKind.TERMINAL_LAG,
                    journalJson.encodeToString(terminalLagRecord).encodeToByteArray(),
                ),
            )
        }.toByteArray()
        epochDirectory.resolve("segment-00000000.tjr").writeBytes(segment)

        val replayEpochs = ChapterJournalReplayReducer.readAppPrivateEpochs(privateJournalRoot, journalIdentity)
        val replayBeforeOpen = ChapterJournalReplayReducer.replay(
            epochs = replayEpochs,
            artifactResolver = ChapterJournalReplayReducer.artifactResolver(engine, manifest),
            expectedChapterIdentityHash = chapterHash,
        )
        replayBeforeOpen.corruptEpochs shouldBe emptySet()
        replayBeforeOpen.validFrameCount shouldBe 3
        replayBeforeOpen.inventory?.hasTrustedExpectedPageCount shouldBe true
        replayBeforeOpen.expectedPageKeys shouldBe setOf(capturedPageKey, committedAfterLagPageKey)
        replayBeforeOpen.pageOutcomes.keys shouldBe setOf(capturedPageKey)
        replayBeforeOpen.missingPageKeys shouldBe setOf(committedAfterLagPageKey)

        val reopened = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Terminal lag chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
            journalStorageFactory = ::jvmFileBackedJournalStorage,
        )
        try {
            reopened.pages[capturedPageKey] shouldBe capturedPage
            reopened.pages[committedAfterLagPageKey] shouldBe committedAfterLagPage
            reopened.display.value[committedAfterLagPageKey]?.blocks?.single()?.translation shouldBe
                "committed after lag"
        } finally {
            reopened.closeAndFlush()
        }
    }

    @Test
    fun `rekeyed manifest excludes stale v1 inventory keys from the trusted seed`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val privateJournalRoot = File(chapterDir, "v1-rekey-journal").also { check(it.mkdirs()) }
        val journalIdentity = "source:manga:Rekeyed v1 chapter.json"
        val layout = ChapterArtifactLayout("Rekeyed v1 chapter")
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(root))
        val engine = ChapterArtifactEngine(documents, layout)
        val oldKey = "online-page.jpg"
        val newKey = "disk-page.jpg"
        val page = PageTranslation(
            sourceFileName = oldKey,
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
            originalImageFallback = true,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "rekeyed translation",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        )
        val pageHash = StageFingerprints.pageSnapshot(page)
        val displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true)
        val sourceSha = "d".repeat(64)
        val sourceIdentity = SourceIdentity(oldKey, sha256 = sourceSha, width = 10, height = 10)
        val snapshotName = layout.committedPageSnapshotFile(oldKey, "v1-committed")
        documents.publishJson(snapshotName, page) shouldBe true
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                oldKey to PageArtifactRecord(
                    pageKey = oldKey,
                    source = sourceIdentity,
                    committed = CommittedBundleMetadata(
                        generationId = "v1-committed",
                        bundleFingerprint = StageFingerprints.committedBundle(
                            sourceIdentity = sourceIdentity,
                            displayBase = displayBase,
                            translationFingerprint = pageHash,
                            layoutFingerprint = null,
                        ),
                        displayBase = displayBase,
                        translationFingerprint = pageHash,
                        origin = ArtifactOrigin.UNKNOWN,
                        pageSnapshotFileName = snapshotName,
                    ),
                ),
            ),
            expectedPageCount = 1,
            expectedPageCountTrusted = true,
            sourceShaByPageKey = mapOf(oldKey to sourceSha),
        )
        engine.publishManifest(manifest) shouldBe true

        val chapterHash = StageFingerprints.sha256Hex(journalIdentity.toByteArray(Charsets.UTF_8))
        val sessionId = UUID.nameUUIDFromBytes("v1-rekey-session".encodeToByteArray())
        val epochDirectory = File(
            File(File(privateJournalRoot, "translation-journal-v1"), chapterHash),
            "epoch-${1L.toString().padStart(20, '0')}-g0-$sessionId",
        ).also { check(it.mkdirs()) }
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val v1Inventory = ChapterJournalInventoryRecord(
            schemaVersion = ChapterJournalInventoryRecord.LEGACY_SCHEMA_VERSION,
            chapterIdentityHash = chapterHash,
            expectedPageKeys = listOf(oldKey),
            expectedPageCount = 1,
            sourceFingerprint = "v1-inventory-before-rekey",
        )
        val terminalLagRecord = ChapterJournalRecord(
            pageKey = "",
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 0L,
            state = null,
            terminalReason = "${ChapterJournalRecord.TERMINAL_LAG_REASON_CREDIT_WINDOW}:1",
            terminalCommitSeq = 1L,
        )
        val segment = ByteArrayOutputStream().apply {
            write(ChapterJournalFormat.segmentHeader(0L, 0L, 1L, sessionId))
            write(
                ChapterJournalFormat.encodeFrame(
                    frameSeq = 1L,
                    commitSeq = null,
                    kind = ChapterJournalFormat.RecordKind.INVENTORY,
                    payload = journalJson.encodeToString(v1Inventory).encodeToByteArray(),
                ),
            )
            write(
                ChapterJournalFormat.encodeFrame(
                    frameSeq = 2L,
                    commitSeq = null,
                    kind = ChapterJournalFormat.RecordKind.TERMINAL_LAG,
                    payload = journalJson.encodeToString(terminalLagRecord).encodeToByteArray(),
                ),
            )
        }.toByteArray()
        epochDirectory.resolve("segment-00000000.tjr").writeBytes(segment)

        val store = ChapterTranslationStore(
            initialPages = mapOf(oldKey to page),
            artifactStore = engine,
            initialCommittedPages = mapOf(oldKey to page),
            initialArtifactManifest = manifest,
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
        )
        store.enableLazyPersistence()
        val writer = ChapterJournalWriter(
            storage = jvmFileBackedJournalStorage(epochDirectory, privateJournalRoot),
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeBulkRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
            storeGeneration = 0L,
            epochOrdinal = 1L,
            sessionId = sessionId,
            chapterIdentityHash = chapterHash,
        )
        store.attachJournalWriterForTests(writer)

        try {
            val beforeRekey = checkNotNull(store.replayJournalForRecovery())
            beforeRekey.corruptEpochs shouldBe emptySet()
            beforeRekey.validFrameCount shouldBe 2
            beforeRekey.inventory?.schemaVersion shouldBe ChapterJournalInventoryRecord.LEGACY_SCHEMA_VERSION
            beforeRekey.inventory?.hasTrustedExpectedPageCount shouldBe false
            beforeRekey.expectedPageKeys shouldBe setOf(oldKey)
            beforeRekey.pageOutcomes.keys shouldBe emptySet()

            store.rekeyPages(listOf(oldKey), listOf(newKey)) shouldBe
                ChapterTranslationStore.PageRekeyOutcome.Moved(listOf(oldKey to newKey), skippedCollisions = 0)
            runCurrent()
            writer.drainAndClose()

            val rekeyedManifest = checkNotNull(engine.readManifest())
            rekeyedManifest.pages.keys shouldBe setOf(newKey)
            rekeyedManifest.sourceShaByPageKey.keys shouldBe setOf(newKey)
            store.display.value.getValue(newKey).blocks.single().translation shouldBe "rekeyed translation"

            val afterRekey = checkNotNull(store.replayJournalForRecovery())
            afterRekey.corruptEpochs shouldBe emptySet()
            afterRekey.validFrameCount shouldBe 2
            afterRekey.inventory?.hasTrustedExpectedPageCount shouldBe false
            afterRekey.expectedPageKeys shouldBe setOf(oldKey)
            afterRekey.pageOutcomes.keys shouldBe emptySet()
        } finally {
            store.closeAndFlush()
            writer.drainAndClose()
        }

        val reopened = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Rekeyed v1 chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
            journalStorageFactory = ::jvmFileBackedJournalStorage,
        )
        try {
            reopened.pages[newKey]?.blocks?.single()?.translation shouldBe "rekeyed translation"
            reopened.pages[newKey]?.originalImageFallback shouldBe true
            reopened.pages[newKey]?.hasRenderedResult shouldBe true
            val seededPrefix = checkNotNull(reopened.replayJournalForRecovery())
            seededPrefix.inventory?.hasTrustedExpectedPageCount shouldBe true
            seededPrefix.expectedPageKeys shouldBe setOf(newKey)
            seededPrefix.pageOutcomes.keys shouldBe setOf(newKey)

            reopened.artifactStatus() shouldBe Translation.State.TRANSLATED
        } finally {
            reopened.closeAndFlush()
        }
    }

    @Test
    fun `partial untrusted seed prefix retries missing pages before trusted inventory`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val privateJournalRoot = File(chapterDir, "partial-seed-journal").also { check(it.mkdirs()) }
        val journalIdentity = "source:manga:Partial seed chapter.json"
        val layout = ChapterArtifactLayout("Partial seed chapter")
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(root))
        val engine = ChapterArtifactEngine(documents, layout)
        val pageOneKey = "page-one.jpg"
        val pageTwoKey = "page-two.jpg"
        fun translatedPage(key: String, translation: String) = PageTranslation(
            sourceFileName = key,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = translation,
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        ).toPublishedPage()

        val pageOne = translatedPage(pageOneKey, "already seeded page")
        val pageTwo = translatedPage(pageTwoKey, "manifest-only page")
        val pageOneSnapshot = layout.committedPageSnapshotFile(pageOneKey, "page-one-commit")
        val pageTwoSnapshot = layout.committedPageSnapshotFile(pageTwoKey, "page-two-commit")
        documents.publishJson(pageOneSnapshot, pageOne.toDraft()) shouldBe true
        documents.publishJson(pageTwoSnapshot, pageTwo.toDraft()) shouldBe true
        engine.publishManifest(
            ChapterArtifactManifest(
                chapterKey = layout.chapterKey,
                pages = mapOf(
                    pageOneKey to PageArtifactRecord(
                        pageKey = pageOneKey,
                        committed = CommittedBundleMetadata(
                            generationId = "page-one-commit",
                            displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                            translationFingerprint = StageFingerprints.pageSnapshot(pageOne),
                            pageSnapshotFileName = pageOneSnapshot,
                        ),
                    ),
                    pageTwoKey to PageArtifactRecord(
                        pageKey = pageTwoKey,
                        committed = CommittedBundleMetadata(
                            generationId = "page-two-commit",
                            displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                            translationFingerprint = StageFingerprints.pageSnapshot(pageTwo),
                            pageSnapshotFileName = pageTwoSnapshot,
                        ),
                    ),
                ),
                expectedPageCount = 2,
                expectedPageCountTrusted = true,
            ),
        ) shouldBe true

        val chapterHash = StageFingerprints.sha256Hex(journalIdentity.toByteArray(Charsets.UTF_8))
        val sessionId = UUID.randomUUID()
        val epochDirectory = File(
            File(File(privateJournalRoot, "translation-journal-v1"), chapterHash),
            "epoch-${1L.toString().padStart(20, '0')}-g0-$sessionId",
        ).also { check(it.mkdirs()) }
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val untrustedInventory = ChapterJournalInventorySnapshot(
            expectedPageKeys = setOf(pageOneKey, pageTwoKey),
            expectedPageCount = 2,
            sourceShaByPageKey = emptyMap(),
        ).record(chapterHash)
        val firstSeedRecord = ChapterJournalRecord(
            pageKey = pageOneKey,
            generation = 0L,
            fencingToken = 0L,
            pageVersion = pageOne.pageVersion,
            state = pageOne,
            artifactContentHash = StageFingerprints.pageSnapshot(pageOne),
        )
        val bytes = ByteArrayOutputStream().apply {
            write(ChapterJournalFormat.segmentHeader(0L, 0L, 1L, sessionId))
            write(
                ChapterJournalFormat.encodeFrame(
                    1L,
                    null,
                    ChapterJournalFormat.RecordKind.INVENTORY,
                    journalJson.encodeToString(untrustedInventory).encodeToByteArray(),
                ),
            )
            write(
                ChapterJournalFormat.encodeFrame(
                    2L,
                    1L,
                    ChapterJournalFormat.RecordKind.FREE_STATE,
                    journalJson.encodeToString(firstSeedRecord).encodeToByteArray(),
                ),
            )
        }.toByteArray()
        epochDirectory.resolve("segment-00000000.tjr").writeBytes(bytes)

        val reopened = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Partial seed chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
            journalStorageFactory = ::jvmFileBackedJournalStorage,
        )
        try {
            reopened.pages[pageOneKey] shouldBe pageOne
            reopened.pages[pageTwoKey] shouldBe pageTwo
            val seed = reopened.journalSeedDiagnostics
            check(seed.failure == null) {
                "journal seed failed: ${describeCauseChain(seed.failure)}"
            }
            seed.inventoryCaptureRequested shouldBe true
            seed.pageKeysCaptureRequested shouldBe setOf(pageTwoKey)
            seed.firstBarrierFrameSeq shouldBe 2L
            seed.trustedInventoryCaptureRequested shouldBe true
            seed.trustedBarrierFrameSeq shouldBe 3L
            seed.completed shouldBe true
            val completedPrefix = checkNotNull(reopened.replayJournalForRecovery())
            completedPrefix.pages[pageOneKey] shouldBe pageOne
            completedPrefix.pages[pageTwoKey] shouldBe pageTwo
            completedPrefix.pageOutcomes.keys shouldBe setOf(pageOneKey, pageTwoKey)
            completedPrefix.inventory?.hasTrustedExpectedPageCount shouldBe true
        } finally {
            reopened.closeAndFlush()
        }
    }

    @Test
    fun `metadata-only fallback stays visible in union mode until content-backed seed heals trust`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val privateJournalRoot = File(chapterDir, "fallback-journal")
        check(privateJournalRoot.mkdirs())
        val neverStarted = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Never started chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = "source:manga:Never started chapter.json",
            journalStorageFactory = ::jvmFileBackedJournalStorage,
        )
        try {
            neverStarted.artifactStatus() shouldBe null
        } finally {
            neverStarted.closeAndFlush()
        }

        val journalIdentity = "source:manga:Fallback-only chapter.json"
        val layout = ChapterArtifactLayout("Fallback-only chapter")
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(root))
        val engine = ChapterArtifactEngine(documents, layout)
        val pageKey = "metadata-only.jpg"
        val fallbackManifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(pageKey to PageArtifactRecord(pageKey = pageKey)),
            expectedPageCount = 1,
            expectedPageCountTrusted = true,
        )
        engine.publishManifest(fallbackManifest) shouldBe true

        val firstOpen = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Fallback-only chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
            journalStorageFactory = ::jvmFileBackedJournalStorage,
        )
        try {
            firstOpen.pages.containsKey(pageKey) shouldBe true
            firstOpen.display.value.containsKey(pageKey) shouldBe true
            firstOpen.artifactStatus() shouldBe Translation.State.ERROR
            val seed = firstOpen.journalSeedDiagnostics
            check(seed.failure == null) {
                "journal seed failed: ${describeCauseChain(seed.failure)}"
            }
            seed.inventoryCaptureRequested shouldBe true
            seed.pageKeysCaptureRequested shouldBe emptySet()
            seed.firstBarrierFrameSeq shouldBe 1L
            seed.trustedInventoryCaptureRequested shouldBe false
            seed.completed shouldBe true
            val fallbackPrefix = checkNotNull(firstOpen.replayJournalForRecovery())
            fallbackPrefix.inventory?.hasTrustedExpectedPageCount shouldBe false
            fallbackPrefix.pageOutcomes shouldBe emptyMap()
        } finally {
            firstOpen.closeAndFlush()
        }

        val validPage = PageTranslation(
            sourceFileName = pageKey,
            translationStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
            originalImageFallback = true,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "healed content",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        ).toPublishedPage()
        val snapshotName = layout.committedPageSnapshotFile(pageKey, "healed-commit")
        documents.publishJson(snapshotName, validPage.toDraft()) shouldBe true
        engine.publishManifest(
            fallbackManifest.copy(
                pages = mapOf(
                    pageKey to PageArtifactRecord(
                        pageKey = pageKey,
                        committed = CommittedBundleMetadata(
                            generationId = "healed-commit",
                            displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                            translationFingerprint = StageFingerprints.pageSnapshot(validPage),
                            pageSnapshotFileName = snapshotName,
                        ),
                    ),
                ),
            ),
        ) shouldBe true

        val healedOpen = ChapterTranslationStore.openArtifactSuspend(
            root,
            "Fallback-only chapter.json",
            privateStorageRoot = privateJournalRoot,
            privateStorageIdentity = journalIdentity,
            journalStorageFactory = ::jvmFileBackedJournalStorage,
        )
        try {
            healedOpen.pages[pageKey] shouldBe validPage
            healedOpen.artifactStatus() shouldBe Translation.State.TRANSLATED
            val healedPrefix = checkNotNull(healedOpen.replayJournalForRecovery())
            healedPrefix.pageOutcomes[pageKey] shouldBe ChapterJournalPageOutcome.RECORDED
            healedPrefix.inventory?.hasTrustedExpectedPageCount shouldBe true
        } finally {
            healedOpen.closeAndFlush()
        }
    }

    @Test
    fun `tombstone and invalid journal winners block manifest fallback during union open`() = runTest {
        val scenarios = listOf(
            "tombstoned" to ChapterJournalPageOutcome.TOMBSTONED,
            "invalid" to ChapterJournalPageOutcome.INVALID,
        )
        scenarios.forEach { (scenario, expectedOutcome) ->
            val scenarioDir = File(chapterDir, scenario).also { check(it.mkdirs()) }
            val root = FakeUniFile(parent = null, backing = scenarioDir)
            val privateJournalRoot = File(scenarioDir, "private-journal").also { check(it.mkdirs()) }
            val journalIdentity = "source:manga:Blocked $scenario.json"
            val layout = ChapterArtifactLayout("Blocked $scenario")
            val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(root))
            val engine = ChapterArtifactEngine(documents, layout)
            val pageKey = "blocked-page.jpg"
            val page = PageTranslation(
                sourceFileName = pageKey,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source",
                        translation = "manifest content must not return",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                    ),
                ),
            ).toPublishedPage()
            val snapshotName = layout.committedPageSnapshotFile(pageKey, "manifest-commit")
            documents.publishJson(snapshotName, page.toDraft()) shouldBe true
            engine.publishManifest(
                ChapterArtifactManifest(
                    chapterKey = layout.chapterKey,
                    pages = mapOf(
                        pageKey to PageArtifactRecord(
                            pageKey = pageKey,
                            committed = CommittedBundleMetadata(
                                generationId = "manifest-commit",
                                displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                                translationFingerprint = StageFingerprints.pageSnapshot(page),
                                pageSnapshotFileName = snapshotName,
                            ),
                        ),
                    ),
                    expectedPageCount = 1,
                    expectedPageCountTrusted = true,
                ),
            ) shouldBe true

            val chapterHash = StageFingerprints.sha256Hex(journalIdentity.toByteArray(Charsets.UTF_8))
            val sessionId = UUID.randomUUID()
            val epochDirectory = File(
                File(File(privateJournalRoot, "translation-journal-v1"), chapterHash),
                "epoch-${1L.toString().padStart(20, '0')}-g0-$sessionId",
            ).also { check(it.mkdirs()) }
            val journalJson = Json {
                encodeDefaults = true
                explicitNulls = true
            }
            val inventory = ChapterJournalInventorySnapshot(
                expectedPageKeys = setOf(pageKey),
                expectedPageCount = 1,
                sourceShaByPageKey = emptyMap(),
            ).record(chapterHash)
            val mutation = ChapterJournalRecord(
                pageKey = pageKey,
                generation = 0L,
                fencingToken = 0L,
                pageVersion = 2L,
                state = page.takeIf { expectedOutcome == ChapterJournalPageOutcome.INVALID },
                artifactContentHash = if (expectedOutcome == ChapterJournalPageOutcome.INVALID) {
                    "0".repeat(64)
                } else {
                    null
                },
            )
            val bytes = ByteArrayOutputStream().apply {
                write(ChapterJournalFormat.segmentHeader(0L, 0L, 1L, sessionId))
                write(
                    ChapterJournalFormat.encodeFrame(
                        1L,
                        null,
                        ChapterJournalFormat.RecordKind.INVENTORY,
                        journalJson.encodeToString(inventory).encodeToByteArray(),
                    ),
                )
                write(
                    ChapterJournalFormat.encodeFrame(
                        2L,
                        1L,
                        ChapterJournalFormat.RecordKind.FREE_STATE,
                        journalJson.encodeToString(mutation).encodeToByteArray(),
                    ),
                )
            }.toByteArray()
            epochDirectory.resolve("segment-00000000.tjr").writeBytes(bytes)

            val reopened = ChapterTranslationStore.openArtifactSuspend(
                root,
                "Blocked $scenario.json",
                privateStorageRoot = privateJournalRoot,
                privateStorageIdentity = journalIdentity,
            )
            try {
                reopened.pages.containsKey(pageKey) shouldBe false
                reopened.display.value.containsKey(pageKey) shouldBe false
                val prefix = checkNotNull(reopened.replayJournalForRecovery())
                prefix.pageOutcomes[pageKey] shouldBe expectedOutcome
            } finally {
                reopened.closeAndFlush()
            }
        }
    }

    @Test
    fun `stale version and released lease rejections never enter the replay prefix`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Rejected writes chapter.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { journalJson.encodeToString(it).encodeToByteArray() },
            encodeInventory = { journalJson.encodeToString(it).encodeToByteArray() },
            encodeTerminalLag = { count, sequence -> "lag:$count:$sequence".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        var closed = false

        try {
            store.updatePage("page.jpg") {
                PageTranslation(sourceFileName = "page.jpg", pageVersion = 1L, translationStatus = StageStatus.PENDING)
            }
            runCurrent()
            val staleVersion = store.snapshot("page.jpg").toPrecondition()

            store.updatePage("page.jpg") { current ->
                checkNotNull(current).copy(
                    pageVersion = 2L,
                    translationStatus = StageStatus.PENDING,
                    blocks = mutableListOf(
                        TranslationBlock(
                            text = "accepted",
                            translation = "accepted",
                            width = 10f,
                            height = 10f,
                            x = 0f,
                            y = 0f,
                            symHeight = 1f,
                            symWidth = 1f,
                            angle = 0f,
                        ),
                    ),
                )
            }
            runCurrent()
            store.flush()
            val versionRejected = store.updatePageGuarded(
                pageKey = "page.jpg",
                expected = staleVersion,
                description = "stale-version replay regression",
            ) { current ->
                checkNotNull(current).copy(pageVersion = 99L, translationStatus = StageStatus.READY)
            }
            (versionRejected as ChapterTranslationStore.PatchResult.Rejected).detail
                .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected.Detail.PageVersionMismatch>()

            val lease = (
                store.tryAcquirePageStageLease(
                    "page.jpg",
                    PageStage.Translation,
                    PageWriteOrigin.BATCH,
                ) as LeaseAcquisition.Granted
                ).lease
            val releasedLeasePrecondition = store.snapshot("page.jpg").toPrecondition()
            releasedLeasePrecondition.leaseToken shouldBe lease.token
            store.releasePageStageLease("page.jpg", PageWriteOrigin.BATCH)
            val leaseRejected = store.updatePageGuarded(
                pageKey = "page.jpg",
                expected = releasedLeasePrecondition,
                description = "released-lease replay regression",
            ) { current ->
                checkNotNull(current).copy(pageVersion = 100L, translationStatus = StageStatus.READY)
            }
            (leaseRejected as ChapterTranslationStore.PatchResult.Rejected).detail
                .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected.Detail.PageLeaseTokenMismatch>()

            runCurrent()
            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            frames.filter { it.commitSeq != null }.map { it.commitSeq } shouldContainExactly listOf(1L, 2L)
            val stateFrames = frames.filter { frame ->
                frame.kind == ChapterJournalFormat.RecordKind.FREE_STATE ||
                    frame.kind == ChapterJournalFormat.RecordKind.PAID_STATE
            }
            stateFrames.map { journalJson.decodeFromString<ChapterJournalRecord>(it.payload.decodeToString()).pageVersion } shouldContainExactly
                listOf(1L, 2L)

            store.closeAndFlush()
            writer.drainAndClose()
            closed = true
            val epoch = ChapterJournalReplayEpoch(
                order = ChapterJournalFormat.EpochOrderKey(0L, 0L, UUID(0L, 0L)),
                segments = storage.segmentIndexes().map { index ->
                    ChapterJournalReplaySegment(index, storage.readSegment(index))
                },
            )
            val replayed = ChapterJournalReplayReducer.replay(
                epochs = listOf(epoch),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(
                    checkNotNull(store.artifactEngine),
                    checkNotNull(store.readArtifactManifest()),
                ),
            )
            replayed.pages.getValue("page.jpg").pageVersion shouldBe 2L
            replayed.pages.getValue("page.jpg").translationStatus shouldBe StageStatus.PENDING
            replayed.appliedRecordCount shouldBe 2
        } finally {
            if (!closed) {
                store.closeAndFlush()
                writer.drainAndClose()
            }
        }
    }

    @Test
    fun `failed progress projection does not replace pending durable state during replay`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Projection truth chapter.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { journalJson.encodeToString(it).encodeToByteArray() },
            encodeInventory = { journalJson.encodeToString(it).encodeToByteArray() },
            encodeTerminalLag = { count, sequence -> "lag:$count:$sequence".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        val tracker = TranslationBatchProgressTracker(1L, store, listOf("page.jpg"), this)
        var closed = false

        try {
            store.updatePage("page.jpg") {
                PageTranslation(sourceFileName = "page.jpg", translationStatus = StageStatus.PENDING)
            }
            runCurrent()
            tracker.markTranslateFailed("page.jpg", "projection-only rejection")
            runCurrent()

            tracker.snapshot.value.pages.single().stage shouldBe TranslationProgressStage.FAILED
            store.state.value.getValue("page.jpg").translationStatus shouldBe StageStatus.PENDING

            store.closeAndFlush()
            writer.drainAndClose()
            closed = true
            val epoch = ChapterJournalReplayEpoch(
                order = ChapterJournalFormat.EpochOrderKey(0L, 0L, UUID(0L, 0L)),
                segments = storage.segmentIndexes().map { index ->
                    ChapterJournalReplaySegment(index, storage.readSegment(index))
                },
            )
            val replayed = ChapterJournalReplayReducer.replay(
                epochs = listOf(epoch),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(
                    checkNotNull(store.artifactEngine),
                    checkNotNull(store.readArtifactManifest()),
                ),
            )
            replayed.pages.getValue("page.jpg").translationStatus shouldBe StageStatus.PENDING
            replayed.invalidPageKeys shouldBe emptySet()
        } finally {
            tracker.close()
            if (!closed) {
                store.closeAndFlush()
                writer.drainAndClose()
            }
        }
    }

    @Test
    fun `large replace and rekey operations remain one shadow event each`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Bulk chapter.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeBulkRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        val pages = (1..12).associate { index ->
            val key = "page-$index.jpg"
            key to PageTranslation(
                sourceFileName = key,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source-$index",
                        translation = "manual-$index",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                        userEditedAt = index.toLong(),
                    ),
                ),
            )
        }

        try {
            store.replaceAll(pages)
            runCurrent()

            val oldKeys = pages.keys.toList()
            val newKeys = oldKeys.map { "disk-$it" }
            store.rekeyPages(oldKeys, newKeys) shouldBe
                ChapterTranslationStore.PageRekeyOutcome.Moved(oldKeys.zip(newKeys), skippedCollisions = 0)
            runCurrent()

            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            frames.filter { it.commitSeq != null }.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.BULK_REPLACE,
                ChapterJournalFormat.RecordKind.BULK_REKEY,
            )
            frames.filter { it.commitSeq != null }.map { it.commitSeq } shouldContainExactly listOf(1L, 2L)

            val replace = journalJson.decodeFromString<ChapterJournalBulkRecord>(
                frames.single { it.kind == ChapterJournalFormat.RecordKind.BULK_REPLACE }.payload.decodeToString(),
            )
            replace.mapping.size shouldBe 12
            replace.mutations.size shouldBe 12
            val rekey = journalJson.decodeFromString<ChapterJournalBulkRecord>(
                frames.single { it.kind == ChapterJournalFormat.RecordKind.BULK_REKEY }.payload.decodeToString(),
            )
            rekey.mapping shouldBe oldKeys.zip(newKeys).toMap()
            rekey.mutations.size shouldBe 24
            writer.shadowLaggedCount shouldBe 0L
            writer.inFlightCount shouldBe 0
        } finally {
            store.closeAndFlush()
            writer.drainAndClose()
        }
    }

    @Test
    fun `cross session rekey wins after restart even when its generation and token reset`() = runTest {
        val io = FakeChapterDocumentIo()
        val documents = AtomicChapterDocuments(io)
        val layout = ChapterArtifactLayout("Rekey immediate replay")
        val artifactEngine = ChapterArtifactEngine(documents, layout)
        val oldValidKey = "online-valid.jpg"
        val newValidKey = "disk-valid.jpg"
        val oldPointerlessKey = "online-textless.jpg"
        val newPointerlessKey = "disk-textless.jpg"
        val oldSourceOnlyKey = "online-unregistered-textless.jpg"
        val newSourceOnlyKey = "disk-unregistered-textless.jpg"
        val committedSnapshot = PageTranslation(
            sourceFileName = oldValidKey,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "committed text",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
            pageVersion = 4L,
        )
        val committedHash = StageFingerprints.pageSnapshot(committedSnapshot)
        val sourceSha = "a".repeat(64)
        val committedSnapshotName = layout.committedPageSnapshotFile(oldValidKey, "g-valid")
        val pointerlessSnapshot = PageTranslation(
            sourceFileName = oldPointerlessKey,
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
        )
        val sourceOnlySnapshot = PageTranslation(
            sourceFileName = oldSourceOnlyKey,
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
        )
        val unverifiedSnapshotName = layout.committedPageSnapshotFile(oldPointerlessKey, "g-unverified")
        documents.publishJson(committedSnapshotName, committedSnapshot) shouldBe true
        documents.publishJson(unverifiedSnapshotName, pointerlessSnapshot) shouldBe true
        val displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true)
        val sourceIdentity = SourceIdentity(oldValidKey, sha256 = sourceSha, width = 20, height = 30)
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                oldValidKey to PageArtifactRecord(
                    pageKey = oldValidKey,
                    pageVersion = 4L,
                    source = sourceIdentity,
                    committed = CommittedBundleMetadata(
                        generationId = "g-valid",
                        bundleFingerprint = StageFingerprints.committedBundle(
                            sourceIdentity = sourceIdentity,
                            displayBase = displayBase,
                            translationFingerprint = committedHash,
                            layoutFingerprint = null,
                        ),
                        displayBase = displayBase,
                        translationFingerprint = committedHash,
                        origin = ArtifactOrigin.UNKNOWN,
                        pageSnapshotFileName = committedSnapshotName,
                    ),
                ),
                oldPointerlessKey to PageArtifactRecord(
                    pageKey = oldPointerlessKey,
                    pageVersion = 2L,
                    committed = CommittedBundleMetadata(
                        generationId = "g-unverified",
                        displayBase = displayBase,
                        translationFingerprint = null,
                        pageSnapshotFileName = unverifiedSnapshotName,
                    ),
                ),
            ),
            expectedPageCount = 3,
            expectedPageCountTrusted = true,
            sourceShaByPageKey = mapOf(
                oldValidKey to sourceSha,
                oldPointerlessKey to "b".repeat(64),
                oldSourceOnlyKey to "c".repeat(64),
            ),
        )
        artifactEngine.publishManifest(manifest) shouldBe true
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = mapOf(
                oldValidKey to committedSnapshot,
                oldPointerlessKey to pointerlessSnapshot,
                oldSourceOnlyKey to sourceOnlySnapshot,
            ),
            artifactStore = artifactEngine,
            initialCommittedPages = mapOf(oldValidKey to committedSnapshot),
            initialArtifactManifest = manifest,
        )
        store.enableLazyPersistence()
        store.beginGeneration("completed-download rekey after restart") shouldBe 1L
        val storage = MemoryStorage()
        val sessionId = UUID.nameUUIDFromBytes("rekey-session-2".encodeToByteArray())
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeBulkRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
            storeGeneration = 1L,
            epochOrdinal = 21L,
            sessionId = sessionId,
        )
        store.attachJournalWriterForTests(writer)

        try {
            store.rekeyPages(
                onlineKeys = listOf(oldValidKey, oldPointerlessKey, oldSourceOnlyKey),
                onDiskKeys = listOf(newValidKey, newPointerlessKey, newSourceOnlyKey),
            ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
                listOf(
                    oldValidKey to newValidKey,
                    oldPointerlessKey to newPointerlessKey,
                    oldSourceOnlyKey to newSourceOnlyKey,
                ),
                skippedCollisions = 0,
            )
            // Draining the journal writer deliberately does not flush the legacy store scheduler.
            runCurrent()
            writer.drainAndClose()

            val currentManifest = checkNotNull(artifactEngine.readManifest())
            currentManifest.pages.keys shouldBe setOf(newValidKey, newPointerlessKey)
            currentManifest.expectedPageCount shouldBe 3
            currentManifest.sourceShaByPageKey.keys shouldBe setOf(newValidKey, newPointerlessKey, newSourceOnlyKey)
            val committedNew = checkNotNull(currentManifest.pages[newValidKey]?.committed)
            val newCommittedSnapshot = checkNotNull(artifactEngine.readPageSnapshot(committedNew.pageSnapshotFileName))
            newCommittedSnapshot.sourceFileName shouldBe newValidKey
            newCommittedSnapshot.blocks.single().translation shouldBe "committed text"
            StageFingerprints.pageSnapshot(newCommittedSnapshot) shouldBe committedNew.translationFingerprint
            store.display.value.getValue(newValidKey).sourceFileName shouldBe newValidKey
            store.display.value.getValue(newValidKey).blocks.single().translation shouldBe "committed text"
            currentManifest.pages.getValue(newPointerlessKey).committed?.pageSnapshotFileName shouldBe null
            currentManifest.pages.getValue(newPointerlessKey).committed?.translationFingerprint shouldBe null

            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 1L,
                expectedEpochOrdinal = 21L,
                expectedSessionId = sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.BULK_REKEY,
            )
            val bulk = journalJson.decodeFromString<ChapterJournalBulkRecord>(frames.last().payload.decodeToString())
            bulk.mapping shouldBe mapOf(
                oldValidKey to newValidKey,
                oldPointerlessKey to newPointerlessKey,
                oldSourceOnlyKey to newSourceOnlyKey,
            )
            bulk.mutations.single { it.pageKey == oldValidKey }.state shouldBe null
            val invalidation = bulk.mutations.single { it.pageKey == newPointerlessKey }
            invalidation.state?.sourceFileName shouldBe newPointerlessKey
            invalidation.state?.blocks shouldBe emptyList()
            invalidation.artifactContentHash shouldBe null
            bulk.mutations.single { it.pageKey == oldPointerlessKey }.state shouldBe null
            val sourceOnlyInvalidation = bulk.mutations.single { it.pageKey == newSourceOnlyKey }
            sourceOnlyInvalidation.state?.sourceFileName shouldBe newSourceOnlyKey
            sourceOnlyInvalidation.state?.blocks shouldBe emptyList()
            sourceOnlyInvalidation.artifactContentHash shouldBe null
            bulk.mutations.single { it.pageKey == oldSourceOnlyKey }.state shouldBe null

            val priorPage = committedSnapshot.toPublishedPage()
            val priorSessionId = UUID.nameUUIDFromBytes("rekey-session-1".encodeToByteArray())
            val priorRecord = ChapterJournalRecord(
                pageKey = oldValidKey,
                generation = 9L,
                fencingToken = 12L,
                pageVersion = priorPage.pageVersion,
                state = priorPage,
                cleanedImageName = priorPage.cleanedImageName,
                cleanedImageContentHash = priorPage.cleanedImageContentHash,
                artifactContentHash = StageFingerprints.pageSnapshot(priorPage),
            )
            val priorBytes = ByteArrayOutputStream().apply {
                write(ChapterJournalFormat.segmentHeader(0L, 9L, 20L, priorSessionId))
                write(
                    ChapterJournalFormat.encodeFrame(
                        frameSeq = 1L,
                        commitSeq = null,
                        kind = ChapterJournalFormat.RecordKind.INVENTORY,
                        payload = journalJson.encodeToString(
                            ChapterJournalInventoryRecord(
                                chapterIdentityHash = "prior-session",
                                expectedPageKeys = listOf(oldValidKey),
                                expectedPageCount = 1,
                                sourceFingerprint = "prior-session-inventory",
                            ),
                        ).encodeToByteArray(),
                    ),
                )
                write(
                    ChapterJournalFormat.encodeFrame(
                        frameSeq = 2L,
                        commitSeq = 1L,
                        kind = ChapterJournalFormat.RecordKind.FREE_STATE,
                        payload = journalJson.encodeToString(priorRecord).encodeToByteArray(),
                    ),
                )
            }.toByteArray()
            val priorEpoch = ChapterJournalReplayEpoch(
                order = ChapterJournalFormat.EpochOrderKey(9L, 20L, priorSessionId),
                segments = listOf(ChapterJournalReplaySegment(0L, priorBytes)),
            )
            val epoch = ChapterJournalReplayEpoch(
                order = ChapterJournalFormat.EpochOrderKey(1L, 21L, sessionId),
                segments = storage.segmentIndexes().map { index ->
                    ChapterJournalReplaySegment(index, storage.readSegment(index))
                },
            )
            val replayed = ChapterJournalReplayReducer.replay(
                epochs = listOf(priorEpoch, epoch),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(artifactEngine, currentManifest),
            )
            replayed.expectedPageKeys shouldBe setOf(newValidKey, newPointerlessKey, newSourceOnlyKey)
            replayed.expectedPageCount shouldBe 3
            replayed.pages.keys shouldBe setOf(newValidKey)
            replayed.invalidPageKeys shouldBe setOf(newPointerlessKey, newSourceOnlyKey)
            replayed.missingPageKeys shouldBe emptySet()
            replayed.ignoredStaleRecordCount shouldBe 0
            (oldValidKey in replayed.pages || oldPointerlessKey in replayed.pages || oldSourceOnlyKey in replayed.pages) shouldBe false

            // A rekey frame without its old-key tombstone must not install the
            // authenticated destination alongside the earlier old-key winner,
            // and replay must stop before a later tombstone can hide that defect.
            val bothKeyManifest = currentManifest.copy(
                pages = currentManifest.pages + (oldValidKey to checkNotNull(manifest.pages[oldValidKey])),
            )
            val destinationOnlyBulk = bulk.copy(
                mapping = mapOf(oldValidKey to newValidKey),
                mutations = listOf(bulk.mutations.single { it.pageKey == newValidKey }),
            )
            val malformedSessionId = UUID.nameUUIDFromBytes("missing source tombstone".encodeToByteArray())
            val malformedEpochBytes = ByteArrayOutputStream().apply {
                write(ChapterJournalFormat.segmentHeader(0L, 1L, 22L, malformedSessionId))
                write(
                    ChapterJournalFormat.encodeFrame(
                        frameSeq = 1L,
                        commitSeq = null,
                        kind = ChapterJournalFormat.RecordKind.INVENTORY,
                        payload = journalJson.encodeToString(
                            ChapterJournalInventoryRecord(
                                chapterIdentityHash = "malformed-rekey-test",
                                expectedPageKeys = listOf(oldValidKey),
                                expectedPageCount = 1,
                                sourceFingerprint = "malformed-rekey-inventory",
                            ),
                        ).encodeToByteArray(),
                    ),
                )
                write(
                    ChapterJournalFormat.encodeFrame(
                        frameSeq = 2L,
                        commitSeq = 1L,
                        kind = ChapterJournalFormat.RecordKind.BULK_REKEY,
                        payload = journalJson.encodeToString(destinationOnlyBulk).encodeToByteArray(),
                    ),
                )
                write(
                    ChapterJournalFormat.encodeFrame(
                        frameSeq = 3L,
                        commitSeq = 2L,
                        kind = ChapterJournalFormat.RecordKind.FREE_STATE,
                        payload = journalJson.encodeToString(
                            bulk.mutations.single { it.pageKey == oldValidKey },
                        ).encodeToByteArray(),
                    ),
                )
            }.toByteArray()
            val malformedEpochOrder = ChapterJournalFormat.EpochOrderKey(1L, 22L, malformedSessionId)
            val replayedMalformedMapping = ChapterJournalReplayReducer.replay(
                epochs = listOf(
                    priorEpoch,
                    ChapterJournalReplayEpoch(
                        order = malformedEpochOrder,
                        segments = listOf(ChapterJournalReplaySegment(0L, malformedEpochBytes)),
                    ),
                ),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(artifactEngine, bothKeyManifest),
            )
            replayedMalformedMapping.pages.keys shouldBe setOf(oldValidKey)
            replayedMalformedMapping.pages.getValue(oldValidKey).blocks.single().translation shouldBe "committed text"
            replayedMalformedMapping.pages.containsKey(newValidKey) shouldBe false
            replayedMalformedMapping.corruptEpochs.contains(malformedEpochOrder) shouldBe true
        } finally {
            store.closeAndFlush()
        }
    }

    @Test
    fun `journal capture and manifest publication failures preserve the old rekey state`() = runTest {
        val captureFailure = IOException("injected journal sink open failure")
        val failedStorage = MemoryStorage().apply { openFailure = captureFailure }
        val failedFixture = committedRekeyFixture(
            testScheduler,
            "Rekey capture failure",
            journalStorage = failedStorage,
        )
        try {
            val rejected = failedFixture.store.rekeyPages(
                listOf(failedFixture.oldKey),
                listOf(failedFixture.newKey),
            ).shouldBeInstanceOf<ChapterTranslationStore.PageRekeyOutcome.Rejected>()
            describeCauseChain(rejected.cause).contains(checkNotNull(captureFailure.message)) shouldBe true
            failedFixture.store.pages.keys shouldBe setOf(failedFixture.oldKey)
            checkNotNull(failedFixture.engine.readManifest()).pages.keys shouldBe setOf(failedFixture.oldKey)
            failedStorage.segmentIndexes() shouldBe emptyList()
        } finally {
            failedFixture.store.closeAndFlush()
        }

        val fixture = committedRekeyFixture(testScheduler, "Rekey publish failure")
        val oldSnapshotBytes = checkNotNull(fixture.io.files[fixture.oldSnapshotName]).copyOf()
        fixture.io.writeNamesToFail += fixture.layout.manifestFileName
        try {
            fixture.store.rekeyPages(listOf(fixture.oldKey), listOf(fixture.newKey))
                .shouldBeInstanceOf<ChapterTranslationStore.PageRekeyOutcome.Rejected>()
            fixture.store.pages.keys shouldBe setOf(fixture.oldKey)
            checkNotNull(fixture.engine.readManifest()).pages.keys shouldBe setOf(fixture.oldKey)
            checkNotNull(fixture.io.files[fixture.oldSnapshotName]).contentEquals(oldSnapshotBytes) shouldBe true
            val frames = ChapterJournalFormat.scanSegment(
                bytes = fixture.storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 22L,
                expectedSessionId = fixture.sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            frames.any { it.kind == ChapterJournalFormat.RecordKind.BULK_REKEY } shouldBe true
        } finally {
            fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `captured rekey waits for manifest publication and retries from file backed replay`() = runTest {
        val journalDirectory = File(chapterDir, "rekey-retry-journal-epoch")
        val journalDurableRoot = File(chapterDir, "rekey-retry-journal-root")
        val fixture = committedRekeyFixture(
            scheduler = testScheduler,
            chapterName = "Rekey publication retry",
            includeCleanedImage = false,
            journalStorage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot),
        )
        var storeClosed = false
        try {
            fixture.store.updatePage(fixture.oldKey) { current ->
                checkNotNull(current).apply {
                    blocks = blocks.map { it.copy(translation = "durable before rekey") }.toMutableList()
                }
            }
            fixture.store.flush()
            fixture.writer.flushToCaptureBarrier()
            val capturedState = fixture.store.pages.getValue(fixture.oldKey)
            val previousManifest = checkNotNull(fixture.engine.readManifest())
            val durableRecord = previousManifest.pages.getValue(fixture.oldKey)
            val durableSnapshotName = checkNotNull(
                durableRecord.candidate?.pageSnapshotFileName ?: durableRecord.committed?.pageSnapshotFileName,
            )
            val durableSnapshot = checkNotNull(fixture.engine.readPageSnapshot(durableSnapshotName)).toPublishedPage()
            durableSnapshot shouldBe capturedState
            durableSnapshot.blocks.single().translation shouldBe "durable before rekey"
            val priorResolver = ChapterJournalReplayReducer.artifactResolver(fixture.engine, previousManifest)
            priorResolver.matches(
                fixture.oldKey,
                StageFingerprints.pageSnapshot(capturedState),
                capturedState,
            ) shouldBe true
            val oldEpoch = replayEpoch(
                storage = fixture.storage,
                generation = 0L,
                ordinal = 22L,
                sessionId = fixture.sessionId,
            )
            val priorReplay = ChapterJournalReplayReducer.replay(
                epochs = listOf(oldEpoch),
                artifactResolver = priorResolver,
            )
            priorReplay.pages[fixture.oldKey] shouldBe capturedState
            priorReplay.invalidPageKeys shouldBe emptySet()
            priorReplay.missingPageKeys shouldBe emptySet()

            fixture.io.writeNamesToFail += fixture.layout.manifestFileName
            fixture.store.rekeyPages(listOf(fixture.oldKey), listOf(fixture.newKey))
                .shouldBeInstanceOf<ChapterTranslationStore.PageRekeyOutcome.Rejected>()
            fixture.store.pages.keys shouldBe setOf(fixture.oldKey)
            fixture.engine.readManifest() shouldBe previousManifest

            val laterCredit = checkNotNull(fixture.writer.tryAcquireShadowCredit(foreground = false))
            fixture.store.withJournalCapturePermit {
                fixture.writer.captureLegacyDeletion(
                    commitSeq = fixture.writer.nextCommitSeq(),
                    credit = laterCredit,
                    pageKey = fixture.newKey,
                    generation = 0L,
                    fencingToken = 0L,
                    pageVersion = 1L,
                    inventory = ChapterJournalInventorySnapshot.EMPTY,
                )
            }
            fixture.writer.flushToCaptureBarrier()

            val reopenedBeforeRetryEngine = ChapterArtifactEngine(
                AtomicChapterDocuments(fixture.io),
                fixture.layout,
            )
            val reopenedBeforeRetryManifest = checkNotNull(reopenedBeforeRetryEngine.readManifest())
            reopenedBeforeRetryManifest shouldBe previousManifest
            val oldManifestReplay = ChapterJournalReplayReducer.replay(
                epochs = listOf(
                    replayEpoch(
                        storage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot),
                        generation = 0L,
                        ordinal = 22L,
                        sessionId = fixture.sessionId,
                    ),
                ),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(
                    reopenedBeforeRetryEngine,
                    reopenedBeforeRetryManifest,
                ),
            )
            oldManifestReplay.pages[fixture.oldKey] shouldBe capturedState
            oldManifestReplay.pages.getValue(fixture.oldKey).blocks.single().translation shouldBe "durable before rekey"
            oldManifestReplay.pageOutcomes[fixture.oldKey] shouldBe ChapterJournalPageOutcome.RECORDED
            oldManifestReplay.pageOutcomes.containsKey(fixture.newKey) shouldBe false

            val framesBeforeRetry = ChapterJournalFormat.scanSegment(
                bytes = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot).readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 22L,
                expectedSessionId = fixture.sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            framesBeforeRetry.any { it.kind == ChapterJournalFormat.RecordKind.BULK_REKEY } shouldBe true

            fixture.io.writeNamesToFail.remove(fixture.layout.manifestFileName)
            var recoveredBetweenPublicationAndSwap = false
            fixture.store.afterRekeyManifestPublicationBeforeLiveSwapForTests = {
                // The durable manifest and BULK_REKEY are visible while the live map is
                // deliberately still on the old key: this is the second crash window.
                fixture.store.pages.keys shouldBe setOf(fixture.oldKey)
                val boundaryEngine = ChapterArtifactEngine(
                    AtomicChapterDocuments(fixture.io),
                    fixture.layout,
                )
                val boundaryManifest = checkNotNull(boundaryEngine.readManifest())
                boundaryManifest.pages.keys shouldBe setOf(fixture.newKey)
                val boundaryReplay = ChapterJournalReplayReducer.replay(
                    epochs = listOf(
                        replayEpoch(
                            storage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot),
                            generation = 0L,
                            ordinal = 22L,
                            sessionId = fixture.sessionId,
                        ),
                    ),
                    artifactResolver = ChapterJournalReplayReducer.artifactResolver(
                        boundaryEngine,
                        boundaryManifest,
                    ),
                )
                boundaryReplay.pages.keys shouldBe setOf(fixture.newKey)
                boundaryReplay.pages.getValue(fixture.newKey).blocks.single().translation shouldBe
                    "durable before rekey"
                boundaryReplay.invalidPageKeys shouldBe emptySet()
                boundaryReplay.missingPageKeys shouldBe emptySet()
                recoveredBetweenPublicationAndSwap = true
            }
            try {
                fixture.store.rekeyPages(listOf(fixture.oldKey), listOf(fixture.newKey)) shouldBe
                    ChapterTranslationStore.PageRekeyOutcome.Moved(
                        listOf(fixture.oldKey to fixture.newKey),
                        skippedCollisions = 0,
                    )
            } finally {
                fixture.store.afterRekeyManifestPublicationBeforeLiveSwapForTests = null
            }
            recoveredBetweenPublicationAndSwap shouldBe true
            fixture.store.pages.keys shouldBe setOf(fixture.newKey)
            fixture.store.pages.getValue(fixture.newKey).blocks.single().translation shouldBe "durable before rekey"
            runCurrent()
            fixture.store.closeAndFlush()
            storeClosed = true

            val reopenedEngine = ChapterArtifactEngine(
                AtomicChapterDocuments(fixture.io),
                fixture.layout,
            )
            val publishedManifest = checkNotNull(reopenedEngine.readManifest())
            publishedManifest.pages.keys shouldBe setOf(fixture.newKey)
            val reopenedStorage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot)
            val framesAfterRetry = ChapterJournalFormat.scanSegment(
                bytes = reopenedStorage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 22L,
                expectedSessionId = fixture.sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            framesAfterRetry.count { it.kind == ChapterJournalFormat.RecordKind.BULK_REKEY } shouldBe 2
            val oldFrames = ChapterJournalFormat.scanSegment(
                bytes = reopenedStorage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 22L,
                expectedSessionId = fixture.sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            val replayAfterRetry = ChapterJournalReplayReducer.replay(
                epochs = listOf(
                    replayEpoch(
                        storage = reopenedStorage,
                        generation = 0L,
                        ordinal = 22L,
                        sessionId = fixture.sessionId,
                    ),
                ),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(reopenedEngine, publishedManifest),
            )
            replayAfterRetry.pages.keys shouldBe setOf(fixture.newKey)
            replayAfterRetry.pages.getValue(fixture.newKey).sourceFileName shouldBe fixture.newKey
            replayAfterRetry.pages.getValue(fixture.newKey).blocks.single().translation shouldBe "durable before rekey"
            replayAfterRetry.pageOutcomes[fixture.newKey] shouldBe ChapterJournalPageOutcome.RECORDED
            replayAfterRetry.invalidPageKeys shouldBe emptySet()
            replayAfterRetry.missingPageKeys shouldBe emptySet()
            oldFrames.any { it.kind == ChapterJournalFormat.RecordKind.BULK_REKEY } shouldBe true
        } finally {
            if (!storeClosed) fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `stale rekey preparation cannot overwrite live referenced snapshot or move the page`() = runTest {
        val fixture = committedRekeyFixture(testScheduler, "Rekey stale preparation")
        val oldSnapshotBytes = checkNotNull(fixture.io.files[fixture.oldSnapshotName]).copyOf()
        var injected = false
        fixture.io.beforeOwnedRenameAttempt = { _, target ->
            if (!injected && target.contains("rekey-committed")) {
                injected = true
                fixture.io.beforeOwnedRenameAttempt = null
                fixture.engine.publishManifest(fixture.manifest.copy(updatedAtEpochMs = 1L)) shouldBe true
            }
        }
        try {
            fixture.store.rekeyPages(listOf(fixture.oldKey), listOf(fixture.newKey))
                .shouldBeInstanceOf<ChapterTranslationStore.PageRekeyOutcome.Rejected>()
            injected shouldBe true
            fixture.store.pages.keys shouldBe setOf(fixture.oldKey)
            val durable = checkNotNull(fixture.engine.readManifest())
            durable.pages.keys shouldBe setOf(fixture.oldKey)
            durable.pages.getValue(fixture.oldKey).committed?.pageSnapshotFileName shouldBe fixture.oldSnapshotName
            checkNotNull(fixture.io.files[fixture.oldSnapshotName]).contentEquals(oldSnapshotBytes) shouldBe true
            runCurrent()
            fixture.storage.segmentIndexes() shouldBe emptyList()
        } finally {
            fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `rekey preserves candidate committed and previous artifact roles`() = runTest {
        val fixture = committedRekeyFixture(
            scheduler = testScheduler,
            chapterName = "Rekey all roles",
            includeCandidateAndPrevious = true,
        )
        try {
            fixture.store.rekeyPages(listOf(fixture.oldKey), listOf(fixture.newKey)) shouldBe
                ChapterTranslationStore.PageRekeyOutcome.Moved(
                    listOf(fixture.oldKey to fixture.newKey),
                    skippedCollisions = 0,
                )
            val pageRecord = checkNotNull(fixture.engine.readManifest()?.pages?.get(fixture.newKey))
            val candidate = checkNotNull(pageRecord.candidate)
            val committed = checkNotNull(pageRecord.committed)
            val previous = checkNotNull(pageRecord.previousCommitted)

            candidate.generationId shouldBe "candidate-generation"
            val candidateSnapshot = checkNotNull(fixture.engine.readPageSnapshot(candidate.pageSnapshotFileName))
            candidateSnapshot.sourceFileName shouldBe fixture.newKey
            StageFingerprints.pageSnapshot(candidateSnapshot) shouldBe candidate.pageSnapshotFingerprint
            candidateSnapshot.blocks.single().translation shouldBe "committed"

            committed.generationId shouldBe "generation-one"
            val committedSnapshot = checkNotNull(fixture.engine.readPageSnapshot(committed.pageSnapshotFileName))
            committedSnapshot.sourceFileName shouldBe fixture.newKey
            committedSnapshot.blocks.single().translation shouldBe "committed"

            previous.generationId shouldBe "generation-zero"
            val previousSnapshot = checkNotNull(fixture.engine.readPageSnapshot(previous.pageSnapshotFileName))
            previousSnapshot.sourceFileName shouldBe fixture.newKey
            previousSnapshot.blocks.single().translation shouldBe "previous"
            StageFingerprints.pageSnapshot(previousSnapshot) shouldBe previous.translationFingerprint
            fixture.engine.readPageSnapshot(fixture.manifest.pages.getValue(fixture.oldKey).committed?.pageSnapshotFileName)
                ?.sourceFileName shouldBe fixture.oldKey
            fixture.store.mayDeleteCleanedImage(fixture.oldKey, "shared-cleaned.jpg") shouldBe false
            fixture.store.isCleanedImageReferencedByAnotherPage(fixture.oldKey, "shared-cleaned.jpg") shouldBe true
            fixture.store.isCleanedImageReferencedByAnotherPage(fixture.newKey, "shared-cleaned.jpg") shouldBe false
        } finally {
            fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `overlapping rekey keeps the incoming page mutation through file backed replay`() = runTest {
        val journalDirectory = File(chapterDir, "overlapping-rekey-journal-epoch")
        val journalDurableRoot = File(chapterDir, "overlapping-rekey-journal-root")
        val fixture = committedRekeyFixture(
            scheduler = testScheduler,
            chapterName = "Rekey overlapping placeholder source",
            oldKey = "p.jpg",
            newKey = "001.jpg",
            includeCandidateAndPrevious = true,
            includeCleanedImage = false,
            journalStorage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot),
        )
        var storeClosed = false
        try {
            fixture.store.preRegisterPages(listOf("001.jpg")) shouldBe
                ChapterTranslationStore.PagePreRegistration.Accepted
            // Persistent-map iteration follows the deterministic hash-trie order
            // for these keys: translated p.jpg is visited before placeholder 001.jpg.
            fixture.store.state.value.keys.toList() shouldBe listOf("p.jpg", "001.jpg")

            val outcome = fixture.store.rekeyPages(
                onlineKeys = listOf("p.jpg", "001.jpg"),
                onDiskKeys = listOf("001.jpg", "002.jpg"),
            )
            outcome shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
                listOf("p.jpg" to "001.jpg", "001.jpg" to "002.jpg"),
                skippedCollisions = 0,
            )
            fixture.store.state.value.getValue("001.jpg").blocks.single().translation shouldBe "committed"

            runCurrent()
            val journalJson = Json {
                encodeDefaults = true
                explicitNulls = true
            }
            val storage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot)
            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 22L,
                expectedSessionId = fixture.sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            val bulkFrame = frames.single { it.kind == ChapterJournalFormat.RecordKind.BULK_REKEY }
            val bulk = journalJson.decodeFromString<ChapterJournalBulkRecord>(bulkFrame.payload.decodeToString())

            fixture.store.closeAndFlush()
            storeClosed = true
            val reopenedEngine = ChapterArtifactEngine(
                AtomicChapterDocuments(fixture.io),
                fixture.layout,
            )
            val manifest = checkNotNull(reopenedEngine.readManifest())
            val replay = ChapterJournalReplayReducer.replay(
                epochs = listOf(
                    replayEpoch(
                        storage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot),
                        generation = 0L,
                        ordinal = 22L,
                        sessionId = fixture.sessionId,
                    ),
                ),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(reopenedEngine, manifest),
            )

            val destinationMutation = bulk.mutations.singleOrNull { it.pageKey == "001.jpg" }
            val recoveredDestination = replay.pages["001.jpg"]
            val sourceTombstone = bulk.mutations.singleOrNull { it.pageKey == "p.jpg" }
            val retryableMutation = bulk.mutations.singleOrNull { it.pageKey == "002.jpg" }
            val defects = buildList {
                if (bulk.mapping != mapOf("p.jpg" to "001.jpg")) {
                    add("successfulMapping should retain p.jpg -> 001.jpg, got ${bulk.mapping}")
                }
                if (destinationMutation?.state == null) {
                    add("001.jpg must carry the incoming page state, got $destinationMutation")
                }
                if (destinationMutation?.state?.blocks?.singleOrNull()?.translation != "committed") {
                    add("001.jpg journal state must retain translated content, got $destinationMutation")
                }
                if (recoveredDestination?.blocks?.singleOrNull()?.translation != "committed") {
                    add("reopen must recover translated content at 001.jpg, got $recoveredDestination")
                }
                if (replay.pageOutcomes["001.jpg"] != ChapterJournalPageOutcome.RECORDED) {
                    add("001.jpg must not be clobbered by a tombstone, got ${replay.pageOutcomes}")
                }
                if (sourceTombstone == null || sourceTombstone.state != null) {
                    add("p.jpg must retain its source tombstone, got $sourceTombstone")
                }
                if (replay.pageOutcomes["p.jpg"] != ChapterJournalPageOutcome.TOMBSTONED) {
                    add("p.jpg must replay as tombstoned, got ${replay.pageOutcomes}")
                }
                if (retryableMutation == null || retryableMutation.state == null) {
                    add("002.jpg must carry its retryable destination mutation, got $retryableMutation")
                }
                if (retryableMutation?.artifactContentHash != null) {
                    add("002.jpg must remain unauthenticated for retry, got $retryableMutation")
                }
                if (replay.pageOutcomes["002.jpg"] != ChapterJournalPageOutcome.INVALID) {
                    add("002.jpg must remain retryable after replay, got ${replay.pageOutcomes}")
                }
            }
            defects.joinToString("\n") shouldBe ""
        } finally {
            if (!storeClosed) fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `overlapping rekey preserves pending registrations through a later artifact update`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Pending overlap chapter.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        var storeClosed = false
        try {
            store.preRegisterPages(listOf("p.jpg", "001.jpg")) shouldBe
                ChapterTranslationStore.PagePreRegistration.Accepted
            store.state.value.keys.toList() shouldBe listOf("p.jpg", "001.jpg")

            store.rekeyPages(
                onlineKeys = listOf("p.jpg", "001.jpg"),
                onDiskKeys = listOf("001.jpg", "002.jpg"),
            ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
                listOf("p.jpg" to "001.jpg", "001.jpg" to "002.jpg"),
                skippedCollisions = 0,
            )
            store.updatePage("002.jpg") { current ->
                checkNotNull(current).apply {
                    blocks = mutableListOf(
                        TranslationBlock(
                            text = "source",
                            translation = "registered destination",
                            width = 10f,
                            height = 10f,
                            x = 0f,
                            y = 0f,
                            symHeight = 1f,
                            symWidth = 1f,
                            angle = 0f,
                        ),
                    )
                }
            }
            runCurrent()
            store.state.value.getValue("002.jpg").blocks.single().translation shouldBe "registered destination"
            checkNotNull(store.readArtifactManifest()).pages.containsKey("002.jpg") shouldBe true
            store.closeAndFlush()
            storeClosed = true

            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            val inventory = journalJson.decodeFromString<ChapterJournalInventoryRecord>(
                frames.last { it.kind == ChapterJournalFormat.RecordKind.INVENTORY }.payload.decodeToString(),
            )
            inventory.expectedPageKeys.toSet() shouldBe setOf("001.jpg", "002.jpg")
        } finally {
            if (!storeClosed) store.closeAndFlush()
        }
    }

    @Test
    fun `overlapping rekey preserves retired image ownership from the prestate`() = runTest {
        val journalDirectory = File(chapterDir, "overlapping-retired-owner-epoch")
        val journalDurableRoot = File(chapterDir, "overlapping-retired-owner-root")
        val fixture = committedRekeyFixture(
            scheduler = testScheduler,
            chapterName = "Rekey overlapping retired image owner",
            oldKey = "p.jpg",
            newKey = "001.jpg",
            includeCleanedImage = false,
            journalStorage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot),
            initialRetiredCleanedImages = mapOf("p.jpg" to setOf("retired-p.jpg")),
        )
        try {
            fixture.store.preRegisterPages(listOf("001.jpg")) shouldBe
                ChapterTranslationStore.PagePreRegistration.Accepted
            fixture.store.state.value.keys.toList() shouldBe listOf("p.jpg", "001.jpg")
            fixture.store.referencedCleanedImageNames().contains("retired-p.jpg") shouldBe true

            fixture.store.rekeyPages(
                onlineKeys = listOf("p.jpg", "001.jpg"),
                onDiskKeys = listOf("001.jpg", "002.jpg"),
            ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
                listOf("p.jpg" to "001.jpg", "001.jpg" to "002.jpg"),
                skippedCollisions = 0,
            )
            runCurrent()

            fixture.store.drainRetiredCleanedImages("001.jpg") shouldBe listOf("retired-p.jpg")
            fixture.store.drainRetiredCleanedImages("002.jpg") shouldBe emptyList()
        } finally {
            fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `rekey adopts empty registered destination and journal replay keeps moved content`() = runTest {
        val journalDirectory = File(chapterDir, "adopted-placeholder-replay-epoch")
        val journalDurableRoot = File(chapterDir, "adopted-placeholder-replay-root")
        val fixture = committedRekeyFixture(
            scheduler = testScheduler,
            chapterName = "Rekey adopted placeholder replay",
            includeCandidateAndPrevious = true,
            includeCleanedImage = false,
            journalStorage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot),
        )
        var storeClosed = false
        try {
            fixture.store.preRegisterPages(listOf(fixture.newKey)) shouldBe
                ChapterTranslationStore.PagePreRegistration.Accepted
            fixture.store.state.value.keys shouldBe setOf(fixture.oldKey, fixture.newKey)
            fixture.store.state.value.getValue(fixture.newKey).blocks shouldBe emptyList()

            fixture.store.rekeyPages(listOf(fixture.oldKey), listOf(fixture.newKey)) shouldBe
                ChapterTranslationStore.PageRekeyOutcome.Moved(
                    listOf(fixture.oldKey to fixture.newKey),
                    skippedCollisions = 0,
                )
            fixture.store.state.value.keys shouldBe setOf(fixture.newKey)
            fixture.store.state.value.getValue(fixture.newKey).blocks.single().translation shouldBe "committed"
            fixture.store.display.value.getValue(fixture.newKey).blocks.single().translation shouldBe "committed"
            runCurrent()
            fixture.store.closeAndFlush()
            storeClosed = true

            val reopenedEngine = ChapterArtifactEngine(
                AtomicChapterDocuments(fixture.io),
                fixture.layout,
            )
            val manifest = checkNotNull(reopenedEngine.readManifest())
            manifest.pages.keys shouldBe setOf(fixture.newKey)
            manifest.sourceShaByPageKey shouldBe mapOf(fixture.newKey to "c".repeat(64))
            manifest.durableFailures.keys shouldBe emptySet()
            val record = checkNotNull(manifest.pages[fixture.newKey])
            val candidate = checkNotNull(record.candidate)
            val committed = checkNotNull(record.committed)
            val previous = checkNotNull(record.previousCommitted)
            candidate.generationId shouldBe "candidate-generation"
            committed.generationId shouldBe "generation-one"
            previous.generationId shouldBe "generation-zero"
            val candidateSnapshot = checkNotNull(reopenedEngine.readPageSnapshot(candidate.pageSnapshotFileName))
            val committedSnapshot = checkNotNull(reopenedEngine.readPageSnapshot(committed.pageSnapshotFileName))
            val previousSnapshot = checkNotNull(reopenedEngine.readPageSnapshot(previous.pageSnapshotFileName))
            candidateSnapshot.sourceFileName shouldBe fixture.newKey
            committedSnapshot.sourceFileName shouldBe fixture.newKey
            previousSnapshot.sourceFileName shouldBe fixture.newKey
            candidateSnapshot.blocks.single().translation shouldBe "committed"
            committedSnapshot.blocks.single().translation shouldBe "committed"
            previousSnapshot.blocks.single().translation shouldBe "previous"
            StageFingerprints.pageSnapshot(candidateSnapshot) shouldBe candidate.pageSnapshotFingerprint
            StageFingerprints.pageSnapshot(committedSnapshot) shouldBe committed.translationFingerprint
            StageFingerprints.pageSnapshot(previousSnapshot) shouldBe previous.translationFingerprint

            val reopenedJournalStorage = jvmFileBackedJournalStorage(journalDirectory, journalDurableRoot)
            val replay = ChapterJournalReplayReducer.replay(
                epochs = listOf(
                    replayEpoch(
                        reopenedJournalStorage,
                        generation = 0L,
                        ordinal = 22L,
                        sessionId = fixture.sessionId,
                    ),
                ),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(reopenedEngine, manifest),
            )
            replay.corruptEpochs shouldBe emptySet()
            replay.pages.keys shouldBe setOf(fixture.newKey)
            replay.pages.getValue(fixture.newKey).blocks.single().translation shouldBe "committed"
            replay.pages.getValue(fixture.newKey).sourceFileName shouldBe fixture.newKey
            replay.pageOutcomes[fixture.oldKey] shouldBe ChapterJournalPageOutcome.TOMBSTONED
            replay.pageOutcomes[fixture.newKey] shouldBe ChapterJournalPageOutcome.RECORDED
            replay.durableFailures.keys shouldBe emptySet()
        } finally {
            if (!storeClosed) fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `rekey preparation refuses a conflicting immutable destination`() = runTest {
        val fixture = committedRekeyFixture(
            scheduler = testScheduler,
            chapterName = "Rekey destination collision",
        )
        try {
            val oldSnapshotBytes = checkNotNull(fixture.io.files[fixture.oldSnapshotName]).copyOf()
            val rekeyed = checkNotNull(fixture.engine.readPageSnapshot(fixture.oldSnapshotName)).apply {
                sourceFileName = fixture.newKey
            }
            val fingerprint = StageFingerprints.pageSnapshot(rekeyed)
            val operationId = "collision-test"
            val collisionName = fixture.layout.rekeyedPageSnapshotFile(
                pageKey = fixture.newKey,
                role = "committed",
                generationId = "generation-one",
                operationId = "$operationId:${fixture.oldKey}:${fixture.newKey}:committed:$fingerprint",
            )
            val conflicting = rekeyed.copy(sourceFileName = "other-page.jpg")
            val conflictingBytes = ArtifactDocumentJson.encodeToString(conflicting).encodeToByteArray()
            fixture.io.files[collisionName] = conflictingBytes

            val prepared = fixture.engine.preparePageSnapshotRekey(
                manifest = fixture.manifest,
                moves = mapOf(fixture.oldKey to fixture.newKey),
                livePages = mapOf(fixture.oldKey to fixture.store.pages.getValue(fixture.oldKey)),
                operationId = operationId,
            )

            prepared shouldBe null
            checkNotNull(fixture.io.files[collisionName]).contentEquals(conflictingBytes) shouldBe true
            checkNotNull(fixture.io.files[fixture.oldSnapshotName]).contentEquals(oldSnapshotBytes) shouldBe true
            fixture.store.pages.keys shouldBe setOf(fixture.oldKey)
            checkNotNull(fixture.engine.readManifest()).pages.keys shouldBe setOf(fixture.oldKey)
        } finally {
            fixture.store.closeAndFlush()
        }
    }

    @Test
    fun `replace aggregate contains only successful per-page outcomes`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Partial bulk chapter.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeBulkRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        val pages = (1..4).associate { index ->
            val key = "partial-$index.jpg"
            key to PageTranslation(
                sourceFileName = key,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source-$index",
                        translation = "manual-$index",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                        userEditedAt = index.toLong(),
                    ),
                ),
            )
        }
        val rejectedPageKey = "partial-2.jpg"
        var leaseAcquired = false

        try {
            store.replaceAll(pages)
            runCurrent()
            store.tryAcquirePageStageLease(rejectedPageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>()
            leaseAcquired = true

            // replaceAll deletes the old durable page, then its unfenced update
            // is rejected because the test still owns a live stage lease.
            // Other per-page commits succeed and are the only replacement states
            // admitted to the aggregate frame.
            store.replaceAll(pages)
            runCurrent()

            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            val bulkFrame = frames.single {
                it.kind == ChapterJournalFormat.RecordKind.BULK_REPLACE && it.commitSeq == 2L
            }
            val bulk = journalJson.decodeFromString<ChapterJournalBulkRecord>(bulkFrame.payload.decodeToString())
            bulk.mutations.filter { it.state != null }.map { it.pageKey }.toSet() shouldBe pages.keys - rejectedPageKey
            bulk.mutations.single { it.pageKey == rejectedPageKey }.state shouldBe null
            bulk.mapping shouldBe pages.keys.associateWith { pageKey ->
                pageKey.takeUnless { pageKey == rejectedPageKey }
            }
        } finally {
            if (leaseAcquired) store.releasePageStageLease(rejectedPageKey, PageWriteOrigin.BATCH)
            store.closeAndFlush()
            writer.drainAndClose()
        }
    }

    private class MemoryStorage : ChapterJournalStorage {
        private val segments = TreeMap<Long, ByteArray>()
        var openFailure: Throwable? = null

        override fun segmentIndexes(): List<Long> = segments.keys.toList()

        override fun readSegment(index: Long): ByteArray = segments.getValue(index).copyOf()

        override fun truncateSegment(index: Long, byteCount: Long) {
            segments[index] = segments.getValue(index).copyOf(byteCount.toInt())
        }

        override fun discardSegmentsAfter(index: Long) {
            segments.keys.filter { it > index }.forEach(segments::remove)
        }

        override fun openSegment(index: Long, create: Boolean): ChapterJournalSink {
            openFailure?.let { failure ->
                openFailure = null
                throw failure
            }
            if (!create && index !in segments) throw IOException("missing segment $index")
            if (create) segments.putIfAbsent(index, byteArrayOf())
            return object : ChapterJournalSink {
                override val size: Long get() = segments.getValue(index).size.toLong()

                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    val current = segments.getValue(index)
                    segments[index] = current + bytes.copyOfRange(offset, offset + length)
                    return length
                }

                override fun flush() = Unit

                override fun sync() = Unit

                override fun close() = Unit
            }
        }

        override fun syncDirectory() = Unit
    }

    private data class RekeyFixture(
        val io: FakeChapterDocumentIo,
        val layout: ChapterArtifactLayout,
        val engine: ChapterArtifactEngine,
        val manifest: ChapterArtifactManifest,
        val store: ChapterTranslationStore,
        val storage: ChapterJournalStorage,
        val writer: ChapterJournalWriter,
        val sessionId: UUID,
        val oldKey: String,
        val newKey: String,
        val oldSnapshotName: String,
    )

    private fun committedRekeyFixture(
        scheduler: TestCoroutineScheduler,
        chapterName: String,
        oldKey: String = "online-page.jpg",
        newKey: String = "disk-page.jpg",
        includeCandidateAndPrevious: Boolean = false,
        includeCleanedImage: Boolean = true,
        journalStorage: ChapterJournalStorage = MemoryStorage(),
        initialRetiredCleanedImages: Map<String, Set<String>> = emptyMap(),
    ): RekeyFixture {
        val io = FakeChapterDocumentIo()
        val documents = AtomicChapterDocuments(io)
        val layout = ChapterArtifactLayout(chapterName)
        val engine = ChapterArtifactEngine(documents, layout)
        val sourceSha = "c".repeat(64)
        val page = PageTranslation(
            sourceFileName = oldKey,
            cleanedImageName = "shared-cleaned.jpg".takeIf { includeCleanedImage },
            cleanedImageContentHash = StageFingerprints.sha256Hex("shared-cleaned-image".encodeToByteArray())
                .takeIf { includeCleanedImage },
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "committed",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        )
        val pageHash = StageFingerprints.pageSnapshot(page)
        val displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true)
        val sourceIdentity = SourceIdentity(oldKey, sha256 = sourceSha, width = 10, height = 10)
        val oldSnapshotName = layout.committedPageSnapshotFile(oldKey, "generation-one")
        documents.publishJson(oldSnapshotName, page)
        val candidate = if (includeCandidateAndPrevious) {
            val fileName = layout.candidatePageSnapshotFile(oldKey, "candidate-generation")
            documents.publishJson(fileName, page)
            CandidateGenerationMetadata(
                generationId = "candidate-generation",
                dependencyFingerprint = "candidate-dependency",
                pageSnapshotFileName = fileName,
                pageSnapshotFingerprint = pageHash,
            )
        } else {
            null
        }
        val previousCommitted = if (includeCandidateAndPrevious) {
            val previousPage = page.copy(
                blocks = page.blocks.map { it.copy(translation = "previous") }.toMutableList(),
            )
            val previousHash = StageFingerprints.pageSnapshot(previousPage)
            val previousFileName = layout.committedPageSnapshotFile(oldKey, "generation-zero")
            documents.publishJson(previousFileName, previousPage)
            CommittedBundleMetadata(
                generationId = "generation-zero",
                bundleFingerprint = StageFingerprints.committedBundle(
                    sourceIdentity = sourceIdentity,
                    displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                    translationFingerprint = previousHash,
                    layoutFingerprint = null,
                ),
                displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE, validated = true),
                translationFingerprint = previousHash,
                origin = ArtifactOrigin.UNKNOWN,
                pageSnapshotFileName = previousFileName,
            )
        } else {
            null
        }
        val manifest = ChapterArtifactManifest(
            chapterKey = chapterName,
            pages = mapOf(
                oldKey to PageArtifactRecord(
                    pageKey = oldKey,
                    source = sourceIdentity,
                    candidate = candidate,
                    committed = CommittedBundleMetadata(
                        generationId = "generation-one",
                        bundleFingerprint = StageFingerprints.committedBundle(
                            sourceIdentity = sourceIdentity,
                            displayBase = displayBase,
                            translationFingerprint = pageHash,
                            layoutFingerprint = null,
                        ),
                        displayBase = displayBase,
                        translationFingerprint = pageHash,
                        origin = ArtifactOrigin.UNKNOWN,
                        pageSnapshotFileName = oldSnapshotName,
                    ),
                    previousCommitted = previousCommitted,
                ),
            ),
            expectedPageCount = 1,
            expectedPageCountTrusted = true,
            sourceShaByPageKey = mapOf(oldKey to sourceSha),
        )
        check(engine.publishManifest(manifest))
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = mapOf(oldKey to page),
            artifactStore = engine,
            initialCommittedPages = mapOf(oldKey to page),
            initialArtifactManifest = manifest,
            initialRetiredCleanedImages = initialRetiredCleanedImages,
        )
        store.enableLazyPersistence()
        val sessionId = UUID.nameUUIDFromBytes("rekey-fixture:$chapterName".encodeToByteArray())
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = journalStorage,
            dispatcher = StandardTestDispatcher(scheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeBulkRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
            storeGeneration = 0L,
            epochOrdinal = 22L,
            sessionId = sessionId,
        )
        store.attachJournalWriterForTests(writer)
        return RekeyFixture(
            io,
            layout,
            engine,
            manifest,
            store,
            journalStorage,
            writer,
            sessionId,
            oldKey,
            newKey,
            oldSnapshotName,
        )
    }

    private fun replayEpoch(
        storage: ChapterJournalStorage,
        generation: Long,
        ordinal: Long,
        sessionId: UUID,
    ) = ChapterJournalReplayEpoch(
        order = ChapterJournalFormat.EpochOrderKey(generation, ordinal, sessionId),
        segments = storage.segmentIndexes().map { index ->
            ChapterJournalReplaySegment(index, storage.readSegment(index))
        },
    )

    private fun jvmFileBackedJournalStorage(directory: File, durableRoot: File): ChapterJournalStorage {
        val delegate = FileChapterJournalStorage(directory, durableRoot)
        return object : ChapterJournalStorage by delegate {
            override fun syncDirectory() = Unit
        }
    }

    private fun describeCauseChain(failure: Throwable?): String =
        generateSequence(failure) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
}
