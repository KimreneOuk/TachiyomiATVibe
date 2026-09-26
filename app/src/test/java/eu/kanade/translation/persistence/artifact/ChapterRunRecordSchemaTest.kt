package eu.kanade.translation.persistence.artifact

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

/**
 *  Stage 1 gate 1.2 (schemas contract /06/12/13/17):
 * run-record schema validation, unknown-version preservation, and
 * byte-identical canonical round-trips through the shared artifact Json
 * instance.
 */
class ChapterRunRecordSchemaTest {

    private val layout = ChapterArtifactLayout("Chapter 1")
    private val hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun frozenConfig() = RunConfigSnapshot(
        sourceLang = "ja",
        targetLang = "en",
        ocrEngine = "onnx-v3",
        ocrModelHash = hex64,
        detectorModelHash = hex64,
        inpaintMode = "QUALITY",
        providerKey = "gemini:gemini-2.5",
        protocolVersion = 2,
        readingOrderVersion = 1,
    )

    /** Mirrors the schemas-contract §1.1 v1 example (PROFILE_FROZEN state). */
    private fun contractExampleRecord() = ChapterRunRecord(
        runId = "run-1757050000000-a1b2c3d4",
        state = ChapterRunState.PROFILE_FROZEN,
        frozenConfig = frozenConfig(),
        frozenRunConfigFingerprint = hex64,
        orderedSourceDigest = hex64,
        ocrCorpusFingerprint = hex64,
        analysisPolicyFingerprint = hex64,
        envelopePolicyFingerprint = hex64,
        profilePointer = ProfilePointer(
            fileName = "Chapter 1_artifacts/profiles/f-$hex64.json",
            schemaVersion = 1,
            contentFingerprint = hex64,
            version = 1,
            profileInputFingerprint = hex64,
        ),
        phaseCounters = mapOf("ocrPagesDone" to 200, "ocrPagesTotal" to 200, "analysisChunksDone" to 10),
        createdAtEpochMs = 1757050000000L,
        updatedAtEpochMs = 1757050900000L,
    )

    private fun artifactStore(io: FakeChapterDocumentIo) =
        ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    private fun initialManifest(store: ChapterArtifactEngine): ChapterArtifactManifest {
        store.publishManifest(
            ChapterArtifactManifest(
                chapterKey = "Chapter 1",
                updatedAtEpochMs = 1L,
            ),
        )
        return store.readManifest()!!
    }

    @Test
    fun `v1 contract example round-trips byte-identically through the shared Json`() {
        val record = contractExampleRecord()
        val first = ArtifactDocumentJson.encodeToString(record)
        // Declaration-order fields first.
        first shouldStartWith "{\"schemaVersion\":1,\"kind\":\"CHAPTER_RUN_RECORD\",\"runId\":"
        val decoded = ArtifactDocumentJson.decodeFromString<ChapterRunRecord>(first)
        ArtifactDocumentJson.encodeToString(decoded) shouldBe first
    }

    @Test
    fun `published run record sidecar round-trips byte-identically`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(store)
        val record = contractExampleRecord()

        val published = store.publishActiveRun(initial, record, hex64)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        val pointer = published.manifest.activeRun.shouldNotBeNull()
        pointer.contentFingerprint shouldBe hex64
        val sidecarBytes = io.read(layout.runRecordFile(hex64)).shouldNotBeNull()
        String(sidecarBytes) shouldBe ArtifactDocumentJson.encodeToString(record)
        store.readRunRecord(pointer) shouldBe ChapterArtifactEngine.RunRecordRead.Usable(record)
    }

    @Test
    fun `wrong kind or schema bounds fail semantic validation`() {
        contractExampleRecord().copy(kind = "OTHER").validationError() shouldNotBe null
        contractExampleRecord().copy(schemaVersion = 0).validationError() shouldNotBe null
        contractExampleRecord().copy(runId = " ").validationError() shouldNotBe null
        contractExampleRecord().copy(frozenRunConfigFingerprint = "not-hex").validationError() shouldNotBe null
        contractExampleRecord().copy(
            phaseCounters = (0 until 33).associate { "k$it" to 1 },
        ).validationError() shouldNotBe null
        contractExampleRecord().copy(
            phaseCounters = mapOf("ocrPagesDone" to -1),
        ).validationError() shouldNotBe null
        contractExampleRecord().isSemanticallyValid shouldBe true
    }

    @Test
    fun `oversized frozenConfig exceeds the serialized bound`() {
        val oversized = contractExampleRecord().copy(
            frozenConfig = frozenConfig().copy(detectionThresholds = "x".repeat(64 * 1024 + 1)),
        )
        oversized.validationError() shouldNotBe null
    }

    @Test
    fun `a required missing field is a parse failure treated as absent-for-planning`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(store)
        // A document without runId (required, no default) cannot parse.
        val withoutRunId = ArtifactDocumentJson.encodeToString(contractExampleRecord())
            .replace("\"runId\":\"run-1757050000000-a1b2c3d4\",", "")
        runCatching {
            ArtifactDocumentJson.decodeFromString<ChapterRunRecord>(withoutRunId)
        }.isFailure shouldBe true

        val fileName = layout.runRecordFile(hex64)
        io.write(fileName, withoutRunId.toByteArray())
        val manifestWithPointer = initial.copy(
            activeRun = SidecarPointer(fileName = fileName, schemaVersion = 1, contentFingerprint = hex64),
        )
        store.publishManifest(manifestWithPointer) shouldBe true

        store.readRunRecord(manifestWithPointer.activeRun!!) shouldBe ChapterArtifactEngine.RunRecordRead.Absent
        // The unparseable payload was quarantined, not silently consumed.
        io.files.containsKey("$fileName.corrupt") shouldBe true
    }

    @Test
    fun `wrong kind is rejected at publication and quarantined at read`() {
        val wrongKind = contractExampleRecord().copy(kind = "OTHER")
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(store)

        val rejected = store.publishActiveRun(initial, wrongKind, hex64)
        rejected.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        store.readManifest() shouldBe initial

        // A wrong-kind document already on disk is corrupt, never consumed.
        val fileName = layout.runRecordFile(hex64)
        io.write(fileName, ArtifactDocumentJson.encodeToString(wrongKind).toByteArray())
        store.readRunRecord(
            SidecarPointer(fileName = fileName, schemaVersion = 1, contentFingerprint = hex64),
        ) shouldBe ChapterArtifactEngine.RunRecordRead.Absent
        io.files.containsKey("$fileName.corrupt") shouldBe true
    }

    @Test
    fun `unknown newer version is unusable for planning with bytes preserved untouched`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = initialManifest(store)
        // A hypothetical v2 run record written by a newer app version.
        val futureJson = ArtifactDocumentJson.encodeToString(contractExampleRecord())
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        val fileName = layout.runRecordFile(hex64)
        io.write(fileName, futureJson.toByteArray())
        val withPointer = initial.copy(
            activeRun = SidecarPointer(fileName = fileName, schemaVersion = 2, contentFingerprint = hex64),
        )
        store.publishManifest(withPointer) shouldBe true

        val read = store.readRunRecord(withPointer.activeRun!!)
        read shouldBe ChapterArtifactEngine.RunRecordRead.UnsupportedVersion(2)
        // Bytes preserved untouched: never deleted, overwritten, or quarantined.
        String(io.read(fileName)!!) shouldBe futureJson
        io.files.containsKey("$fileName.corrupt") shouldBe false
        io.deletedNames.none { it == fileName } shouldBe true

        // Retention keeps the future bytes while a pointer references them.
        store.reconcileRetention(withPointer)
        io.files.containsKey(fileName) shouldBe true
    }
}
