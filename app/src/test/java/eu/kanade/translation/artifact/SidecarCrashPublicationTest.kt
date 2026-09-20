package eu.kanade.translation.artifact

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 *  Stage 1 gate 1.3 (schemas contract /20/22): fault
 * injection at every publication boundary — sidecar write, sidecar rename,
 * manifest publish — must leave the prior manifest authoritative with at most
 * an orphan sidecar; a manifest pointer never dangles.
 */
class SidecarCrashPublicationTest {

    private val layout = ChapterArtifactLayout("Chapter 1")
    private val hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun artifactStore(io: FakeChapterDocumentIo) =
        ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    private fun runRecord(state: ChapterRunState = ChapterRunState.RUN_SNAPSHOT) = ChapterRunRecord(
        runId = "run-1757050000000-a1b2c3d4",
        state = state,
        frozenConfig = RunConfigSnapshot(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "onnx-v3",
            ocrModelHash = hex64,
            detectorModelHash = hex64,
            inpaintMode = "QUALITY",
            providerKey = "gemini:gemini-2.5",
            protocolVersion = 2,
            readingOrderVersion = 1,
        ),
        frozenRunConfigFingerprint = hex64,
        orderedSourceDigest = hex64,
        analysisPolicyFingerprint = hex64,
        envelopePolicyFingerprint = hex64,
        createdAtEpochMs = 1757050000000L,
        updatedAtEpochMs = 1757050000000L,
    )

    private fun profile() = ChapterTranslationProfile(
        version = 1,
        contentFingerprint = hex64,
        profileInputFingerprint = hex64,
        sourceRunId = "run-1757050000000-a1b2c3d4",
        analyzerProvenance = AnalyzerProvenance(
            providerId = "gemini",
            modelId = "gemini-2.5",
            promptVersion = 3,
            analysisSchemaVersion = 1,
        ),
        frozenAtEpochMs = 1757050800000L,
    )

    /** A minimal durable artifact-authoritative manifest to transact against. */
    private fun initialManifest(io: FakeChapterDocumentIo): ChapterArtifactManifest {
        val store = artifactStore(io)
        store.publishManifest(
            ChapterArtifactManifest(
                chapterKey = "Chapter 1",
                updatedAtEpochMs = 1L,
            ),
        )
        return store.readManifest()!!
    }

    private fun manifestTmpName() = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)

    @Test
    fun `happy path publishes sidecar first then installs the pointer`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(io)
        io.writtenNames.clear()

        val outcome = store.publishActiveRun(initial, runRecord(), hex64)

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.manifest.activeRun.shouldNotBeNull().contentFingerprint shouldBe hex64
        io.files.containsKey(layout.runRecordFile(hex64)) shouldBe true
        // Sidecar bytes were durable before the single manifest publication.
        io.writtenNames.indexOf(AtomicChapterDocuments.tempNameFor(layout.runRecordFile(hex64))) shouldBe 0
        io.writtenNames.count { it == manifestTmpName() } shouldBe 1
        store.readManifest() shouldBe committed.manifest
    }

    @Test
    fun `sidecar write failure leaves the prior manifest authoritative with no pointer`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(io)
        io.writeNamesToFail += "/runs/"

        val outcome = store.publishActiveRun(initial, runRecord(), hex64)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        store.readManifest() shouldBe initial
        io.files.keys.none { it.startsWith("${layout.runRecordsRootDirectory}/") } shouldBe true
    }

    @Test
    fun `sidecar rename failure leaves the prior manifest authoritative with at most an orphan temp`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(io)
        io.ownedRenamesToFail += AtomicChapterDocuments.tempNameFor(layout.runRecordFile(hex64))

        val outcome = store.publishActiveRun(initial, runRecord(), hex64)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        store.readManifest() shouldBe initial
        // Only the in-progress temp remains; the pointer was never installed.
        io.files.containsKey(layout.runRecordFile(hex64)) shouldBe false
        io.files.containsKey(AtomicChapterDocuments.tempNameFor(layout.runRecordFile(hex64))) shouldBe true
    }

    @Test
    fun `manifest publication failure after a durable sidecar keeps the pointer absent`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(io)
        io.ownedRenamesToFail += manifestTmpName()

        val outcome = store.publishActiveRun(initial, runRecord(), hex64)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        // The prior manifest stays authoritative: the pointer never dangles.
        val durable = store.readManifest().shouldNotBeNull()
        durable shouldBe initial
        durable.activeRun shouldBe null
        // The sidecar is a reachable-by-name orphan only; retention reclaims it.
        io.files.containsKey(layout.runRecordFile(hex64)) shouldBe true
        store.reconcileRetention(durable)
        io.files.containsKey(layout.runRecordFile(hex64)) shouldBe false
    }

    @Test
    fun `manifest temp write failure keeps the prior manifest authoritative`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(io)
        io.writeNamesToFail += manifestTmpName()

        val outcome = store.publishActiveRun(initial, runRecord(), hex64)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        store.readManifest() shouldBe initial
        // Orphan sidecar only.
        io.files.containsKey(layout.runRecordFile(hex64)) shouldBe true
    }

    @Test
    fun `generic transaction publishes multiple sidecars before one manifest publication`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val docs = AtomicChapterDocuments(io)
        val initial = initialManifest(io)
        val record = runRecord(state = ChapterRunState.PROFILE_FROZEN)
        val runName = layout.runRecordFile(hex64)
        val profileName = layout.profileFile(hex64)

        fun publication() = listOf(
            ChapterArtifactEngine.SidecarPublication(runName, hex64) {
                docs.publishJson(runName, record)
            },
            ChapterArtifactEngine.SidecarPublication(profileName, hex64) {
                docs.publishJson(profileName, profile())
            },
        )
        fun installPointers(manifest: ChapterArtifactManifest) = manifest.copy(
            activeRun = SidecarPointer(fileName = runName, schemaVersion = 1, contentFingerprint = hex64),
            profile = ProfilePointer(
                fileName = profileName,
                schemaVersion = 1,
                contentFingerprint = hex64,
                version = 1,
                profileInputFingerprint = hex64,
            ),
        )

        // Fail the SECOND sidecar: neither pointer may appear, the first
        // sidecar stays an orphan only.
        io.writeNamesToFail += "/profiles/"
        val failed = store.publishSidecarPointers(initial, publication(), ::installPointers)
        failed.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        store.readManifest() shouldBe initial
        io.files.containsKey(runName) shouldBe true
        io.files.containsKey(profileName) shouldBe false
        io.writeNamesToFail.clear()

        // Retry publishes both sidecars and exactly ONE manifest publication.
        io.writtenNames.clear()
        val committed = store.publishSidecarPointers(initial, publication(), ::installPointers)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        io.writtenNames.count { it == manifestTmpName() } shouldBe 1
        committed.manifest.activeRun?.fileName shouldBe runName
        committed.manifest.profile?.fileName shouldBe profileName
        store.readManifest() shouldBe committed.manifest

        // Both pointed sidecars survive retention.
        store.reconcileRetention(committed.manifest)
        io.files.containsKey(runName) shouldBe true
        io.files.containsKey(profileName) shouldBe true
    }

    @Test
    fun `corrupt quarantined sidecar stays preserved while its pointer references it`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(io)

        // Publish a usable run record first.
        val committed = store.publishActiveRun(initial, runRecord(), hex64)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val pointer = committed.manifest.activeRun.shouldNotBeNull()

        // Corrupt the pointed bytes: treated as absent, quarantined.
        io.files[pointer.fileName] = "{ corrupted".toByteArray()
        store.readRunRecord(pointer) shouldBe ChapterArtifactEngine.RunRecordRead.Absent
        io.files.keys.count { it.startsWith("${pointer.fileName}.corrupt") } shouldBe 1
        store.reconcileRetention(committed.manifest)
        io.files.keys.any { it.startsWith("${pointer.fileName}.corrupt") } shouldBe true
    }

    @Test
    fun `stale manifest snapshots recover through the one-shot retry`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(io)
        val stale = initial.copy(updatedAtEpochMs = 2L)

        val outcome = store.publishActiveRun(stale, runRecord(), hex64)

        //   a stale caller snapshot (the façade cached the
        // pre-verify manifest while the background health verify republished)
        // is retried ONCE against the freshly re-read durable manifest instead
        // of surfacing a spurious rejection on a healthy chapter.
        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.manifest.activeRun.shouldNotBeNull().contentFingerprint shouldBe hex64
        io.files.containsKey(layout.runRecordFile(hex64)) shouldBe true
        store.readManifest() shouldBe committed.manifest
    }
}
