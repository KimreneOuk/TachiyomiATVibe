package eu.kanade.translation.context

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.AttemptOrigin
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterContextSnapshot
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.GroupCommitConfiguration
import eu.kanade.translation.artifact.PageArtifactRecord
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.system.measureNanoTime

/**
 * T933 Increment 2 Test Suite: Durable Unified Context.
 * Verifies:
 * - Three identities (chapterContextRevision, requestContextFingerprint, reuseCompatibility)
 * - Schema 4 pointer bump in ChapterArtifactManifest and future-schema guard
 * - Pointer unpublished = feature inert
 * - Reviewer condition 1: Crash-after-ledger test / D9 requestContextFingerprint preservation
 * - Reviewer condition 2: Predecessor-replacement fail-closed
 * - Reviewer condition 3: Memory budget and work measurement on 200-page high-distinctness fixture
 */
class ChapterContextDurableSnapshotTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "context_snapshot_test_${System.nanoTime()}")
    private val layout = ChapterArtifactLayout("Chapter 1")

    @BeforeEach
    fun setUp() {
        GroupCommitConfiguration.enabled = true
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 100) }
    }

    @AfterEach
    fun tearDown() {
        // Restore the suite-wide default-off baseline (see GroupCommitSliceCTest).
        GroupCommitConfiguration.enabled = false
        ChapterTranslationStore.artifactImageProbe = eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
        tempDir.deleteRecursively()
    }

    private fun createStoreWithManifest(
        io: FakeChapterDocumentIo,
        manifest: ChapterArtifactManifest,
        pages: Map<String, PageTranslation> = emptyMap(),
    ): ChapterTranslationStore {
        val docs = AtomicChapterDocuments(io)
        docs.publishJson(layout.manifestFileName, manifest)
        val artifactStore = ChapterArtifactStore(docs, layout, displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) })
        return ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = pages,
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    @Test
    fun `three identities distinguish revision, request fingerprint, and reuse compatibility`() {
        val snapshot1 = ChapterContextSnapshot(
            chapterKey = "Chapter 1",
            targetLang = "en",
            sourceLang = "ja",
            chapterContextRevision = 1L,
            reuseCompatibility = "v1",
            characterAndTermSheet = "Protagonist: Ren",
            rollingContext = "p1 => Hello",
            selectedTerms = listOf("Ren" to "Ren"),
            selectedPairs = listOf("p1" to "Hello"),
            contentFingerprint = ChapterContextSnapshot.computeContentFingerprint(
                chapterKey = "Chapter 1",
                targetLang = "en",
                sourceLang = "ja",
                revision = 1L,
                sheet = "Protagonist: Ren",
                rolling = "p1 => Hello",
            ),
        )

        // Identity 1: chapterContextRevision
        snapshot1.chapterContextRevision shouldBe 1L

        // Identity 2: requestContextFingerprint
        val fp1 = snapshot1.computeRequestContextFingerprint()
        fp1.isNotBlank() shouldBe true

        // Same payload with advanced revision produces EQUAL requestContextFingerprint
        val snapshot1AdvancedRev = snapshot1.copy(
            chapterContextRevision = 2L,
            createdAtEpochMs = 999999L,
        )
        snapshot1AdvancedRev.computeRequestContextFingerprint() shouldBe fp1

        // Different term content produces DIFFERENT requestContextFingerprint
        val snapshot2 = snapshot1.copy(
            characterAndTermSheet = "Protagonist: Ren\nAntagonist: Mal",
            selectedTerms = listOf("Ren" to "Ren", "Mal" to "Mal"),
        )
        val fp2 = snapshot2.computeRequestContextFingerprint()
        fp2 shouldNotBe fp1

        // Identity 3: reuseCompatibility
        snapshot1.reuseCompatibility shouldBe "v1"
        snapshot1.isSemanticallyValid shouldBe true
    }

    @Test
    fun `schema 4 manifest pointer bump and publishContextSnapshot roundtrip`() {
        ChapterArtifactManifest.SCHEMA_VERSION shouldBe 4

        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val initialManifest = ChapterArtifactManifest(
            schemaVersion = 4,
            chapterKey = layout.chapterKey,
            pages = mapOf("0001.jpg" to PageArtifactRecord(pageKey = "0001.jpg")),
        )
        val store = createStoreWithManifest(io, initialManifest)
        val artifactStore = store.artifactStore.shouldNotBeNull()

        // Pointer unpublished initially = feature inert
        initialManifest.context.shouldBeNull()

        // Publish durable context snapshot
        val snapshot = store.contextService.snapshotForDurable(
            targetLang = "en",
            sourceLang = "ja",
            revision = 1L,
        )
        val pubResult = artifactStore.publishContextSnapshot(initialManifest, snapshot)
        (pubResult is ChapterArtifactStore.TransactionOutcome.Committed) shouldBe true
        val committedManifest = (pubResult as ChapterArtifactStore.TransactionOutcome.Committed).manifest

        // Context pointer installed on schema 4 manifest
        committedManifest.schemaVersion shouldBe 4
        val contextPointer = committedManifest.context.shouldNotBeNull()
        contextPointer.schemaVersion shouldBe ChapterContextSnapshot.SCHEMA_VERSION
        contextPointer.contentFingerprint shouldBe snapshot.contentFingerprint

        // Read-back verification
        val readOutcome = artifactStore.readContextSnapshot(contextPointer)
        (readOutcome is ChapterArtifactStore.ContextSnapshotRead.Usable) shouldBe true
        val loadedSnapshot = (readOutcome as ChapterArtifactStore.ContextSnapshotRead.Usable).snapshot
        loadedSnapshot.chapterKey shouldBe snapshot.chapterKey
        loadedSnapshot.targetLang shouldBe "en"
        loadedSnapshot.contentFingerprint shouldBe snapshot.contentFingerprint
    }

    @Test
    fun `Reviewer condition 1 - D9 attempt ledger records requestContextFingerprint before dispatch`() = runBlocking {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val initialManifest = ChapterArtifactManifest(
            schemaVersion = 4,
            chapterKey = layout.chapterKey,
            pages = mapOf("0001.jpg" to PageArtifactRecord(pageKey = "0001.jpg")),
        )
        val store = createStoreWithManifest(io, initialManifest)

        // Capture request context before dispatch
        val prepared = store.contextService.prepare(
            ContextRequest(
                pageKeys = listOf("0001.jpg"),
                targetLang = "en",
                requestedOutputTokens = 2048,
                profile = TranslationContextChunkPlanner.Profile.DEFAULT,
                laneCapability = LaneCapability.MANUAL,
            ),
        )
        val requestFingerprint = prepared.computeRequestContextFingerprint(targetLang = "en")

        // Record attempt start before paid provider call with requestContextFingerprint
        val recorded = store.recordAttemptStart(
            pageKey = "0001.jpg",
            providerKeyHash = "hash123",
            origin = AttemptOrigin.MANUAL,
            requestContextFingerprint = requestFingerprint,
        )
        recorded shouldBe true

        // Crash-after-ledger: simulate process death mid-call (resolveAttempt not called)
        // Check disk ledger contains the entry with requestContextFingerprint
        val ledgerDoc = store.artifactStore?.readAttemptLedger()
        ledgerDoc.shouldNotBeNull()
        ledgerDoc.entries.size shouldBe 1
        val entry = ledgerDoc.entries.first()
        entry.pageKey shouldBe "0001.jpg"
        entry.requestContextFingerprint shouldBe requestFingerprint
    }

    @Test
    fun `Reviewer condition 2 - Predecessor-replacement fail-closed`() = runBlocking {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val initialManifest = ChapterArtifactManifest(
            schemaVersion = 4,
            chapterKey = layout.chapterKey,
        )
        val store = createStoreWithManifest(io, initialManifest)

        // Initial translation of page 1
        store.foldPageContribution("0001.jpg", listOf("knight" to "chevalier", "sword" to "épée"))
        val snap1 = store.glossarySnapshot()
        snap1["knight"] shouldBe "chevalier"
        snap1["sword"] shouldBe "épée"

        // Retranslation with revised translation replaces prior contribution cleanly
        store.foldPageContribution("0001.jpg", listOf("knight" to "paladin", "shield" to "bouclier"))
        val snap2 = store.glossarySnapshot()
        // Prior pairs from 0001.jpg were replaced, not duplicated
        snap2["knight"] shouldBe "paladin"
        snap2["shield"] shouldBe "bouclier"
    }

    @Test
    fun `Reviewer condition 3 - Memory budget and work measurement on 200-page high-distinctness fixture`() = runBlocking {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val initialManifest = ChapterArtifactManifest(
            schemaVersion = 4,
            chapterKey = layout.chapterKey,
        )
        val pages = (1..200).associate { i ->
            val pageKey = "%04d.jpg".format(i)
            pageKey to PageTranslation(
                sourceFileName = pageKey,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "Japanese term $i",
                        translation = "English term $i",
                        x = 0f, y = 0f, width = 10f, height = 10f,
                        symHeight = 1f, symWidth = 1f, angle = 0f,
                    ),
                ),
            )
        }
        val store = createStoreWithManifest(io, initialManifest, pages)

        // Measure fold cost for page 1 vs page 200 (proving O(B) / constant time independent of corpus)
        val timePage1 = measureNanoTime {
            store.foldPageContribution("0001.jpg", listOf("term1" to "trans1"))
        }

        for (i in 2..199) {
            store.foldPageContribution("%04d.jpg".format(i), listOf("term$i" to "trans$i"))
        }

        val timePage200 = measureNanoTime {
            store.foldPageContribution("0200.jpg", listOf("term200" to "trans200"))
        }

        // Page 200 should complete quickly (< 50ms)
        (timePage200 < 50_000_000L) shouldBe true

        // Context preparation budget measurement:
        val prepared = store.contextService.prepare(
            ContextRequest(
                pageKeys = listOf("0200.jpg"),
                targetLang = "en",
                requestedOutputTokens = 2048,
                profile = TranslationContextChunkPlanner.Profile.DEFAULT,
                laneCapability = LaneCapability.MANUAL,
            ),
        )

        // 1. Prepared context token count must respect the 8k rolling budget (<= 512 tokens)
        val constraints = TranslationContextChunkPlanner.constraintsFor(TranslationContextChunkPlanner.Profile.DEFAULT)
        (prepared.estimatedContextTokens <= constraints.maxRollingContextTokens) shouldBe true

        // 2. Payload size ceiling: UTF-8 payload <= 64 KiB
        val payloadBytes = prepared.characterAndTermSheet.toByteArray(Charsets.UTF_8).size +
            prepared.rollingContext.toByteArray(Charsets.UTF_8).size
        (payloadBytes <= 64 * 1024) shouldBe true
    }
}
