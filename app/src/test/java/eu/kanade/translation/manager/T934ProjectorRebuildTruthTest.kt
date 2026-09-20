package eu.kanade.translation.manager

import eu.kanade.translation.storage.ActiveChapterStoreRegistry
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * T934 U.1/U.7: the live batch projection stamps the durable run record's
 * rebuild/restore truth onto the FIRST_PASS snapshot — an adopted-page
 * OCR_PLAN record projects RESTORING with the restored/remaining payload, a
 * post-preflight record projects the plain live phase, and a chapter without
 * a run record stays untouched. The stamp is read-only projection: the store,
 * the manifest, and the run record are never mutated.
 */
class T934ProjectorRebuildTruthTest {

    private val chapterId = 9L
    private val hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun frozenConfig() = RunConfigSnapshot(
        sourceLang = "ja",
        targetLang = "en",
        ocrEngine = "onnx-v3",
        ocrModelHash = hex64,
        detectorModelHash = hex64,
        inpaintMode = "FAST",
        providerKey = "gemini:gemini-2.5",
        protocolVersion = 2,
        readingOrderVersion = 1,
    )

    private fun record(
        state: ChapterRunState,
        done: Int,
        total: Int = 70,
    ): ChapterRunRecord = ChapterRunRecord(
        runId = "run-1758000001000-abc123",
        state = state,
        frozenConfig = frozenConfig(),
        frozenRunConfigFingerprint = hex64,
        orderedSourceDigest = hex64,
        analysisPolicyFingerprint = hex64,
        envelopePolicyFingerprint = hex64,
        phaseCounters = mapOf(
            "ocrPagesTotal" to total,
            "ocrPagesDone" to done,
            "ocrPagesReused" to done,
        ),
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
    )

    /** Store whose in-memory manifest carries the published active-run pointer. */
    private fun storeWithRunRecord(record: ChapterRunRecord): ChapterTranslationStore {
        val artifact = ChapterArtifactEngine(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("Chapter 9"),
        )
        artifact.publishManifest(
            ChapterArtifactManifest(
                chapterKey = "Chapter 9",
                updatedAtEpochMs = 1L,
            ),
        )
        val initial = artifact.readManifest().shouldNotBeNull()
        val committed = artifact.publishActiveRun(initial, record, hex64)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        return ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            artifactStore = artifact,
            initialArtifactManifest = committed.manifest,
        )
    }

    private fun projector(
        activeStores: ActiveChapterStoreRegistry,
        registry: TranslationBatchTrackerRegistry,
    ): TranslationProgressProjection = TranslationProgressProjection(
        activeStoresProvider = { activeStores },
        batchTrackerRegistryProvider = { registry },
        queueStateProvider = { MutableStateFlow(emptyList()) },
        pendingTranslationRequestsProvider = { MutableStateFlow(emptyMap()) },
        pipelineProvider = { mockk<TranslationPipeline>(relaxed = true) },
        getQueuedTranslationOrNull = { null },
        persistedChapterStatus = { _, _, _, _, _ -> null },
        openOrCreateStoreSuspend = { _, _, _, _, _, _ -> null },
        observeActiveDisplayStore = { null },
    )

    /**
     * Registers the store + a live tracker (initial snapshot:
     * TRANSLATING/FIRST_PASS — the live window the probe stamps) and returns
     * the projection's first snapshot. `runTest` returns Unit, so this is a
     * suspend TestScope extension each test calls inside its own runTest.
     */
    private suspend fun TestScope.firstSnapshot(
        store: ChapterTranslationStore,
    ): TranslationProgressSnapshot {
        val activeStores = ActiveChapterStoreRegistry()
        activeStores.register(chapterId, store)
        val registry = TranslationBatchTrackerRegistry()
        registry.createTracker(
            chapterId = chapterId,
            store = store,
            orderedPageKeys = listOf("001.jpg", "002.jpg", "003.jpg"),
            scope = backgroundScope,
        )
        return projector(activeStores, registry).observeBatchProgress(chapterId).first()
    }

    @Test
    fun `adopting page record projects restoring with the payload on the live snapshot`() = runTest {
        val snapshot = firstSnapshot(storeWithRunRecord(record(ChapterRunState.OCR_PLAN, done = 37)))

        snapshot.state shouldBe Translation.State.TRANSLATING
        snapshot.batchPhase shouldBe TranslationBatchPhase.RESTORING
        snapshot.rebuildProgress.shouldNotBeNull()
        snapshot.rebuildProgress!!.restoredPages shouldBe 37
        snapshot.rebuildProgress!!.totalPages shouldBe 70
        snapshot.rebuildProgress!!.remainingPages shouldBe 33
    }

    @Test
    fun `run snapshot record projects rebuilding on the live snapshot`() = runTest {
        val snapshot = firstSnapshot(storeWithRunRecord(record(ChapterRunState.RUN_SNAPSHOT, done = 0)))

        snapshot.batchPhase shouldBe TranslationBatchPhase.REBUILDING
        snapshot.rebuildProgress.shouldNotBeNull()
        snapshot.rebuildProgress!!.restoredPages shouldBe 0
    }

    @Test
    fun `envelope plan record projects rebuilding on the live snapshot`() = runTest {
        // T934 LI-4: the run record parks in ENVELOPE_PLAN while the
        // coordinator rebuilds the dispatch work (resume hydration). Without a
        // branch this window projected null and the sheet froze on a stale
        // numeric hero; it must project the rebuild phase instead.
        val snapshot = firstSnapshot(storeWithRunRecord(record(ChapterRunState.ENVELOPE_PLAN, done = 37)))

        snapshot.batchPhase shouldBe TranslationBatchPhase.REBUILDING
        snapshot.rebuildProgress.shouldNotBeNull()
        snapshot.rebuildProgress!!.restoredPages shouldBe 37
        snapshot.rebuildProgress!!.totalPages shouldBe 70
    }

    @Test
    fun `post preflight record leaves the plain live phase`() = runTest {
        val snapshot = firstSnapshot(
            storeWithRunRecord(record(ChapterRunState.OCR_PREFLIGHT, done = 70)),
        )

        snapshot.batchPhase shouldBe TranslationBatchPhase.FIRST_PASS
        snapshot.rebuildProgress.shouldBeNull()
    }

    @Test
    fun `a chapter without a run record stays untouched`() = runTest {
        // Memory-only store: no artifact manifest, no active run pointer.
        val snapshot = firstSnapshot(ChapterTranslationStore(null, null))

        snapshot.batchPhase shouldBe TranslationBatchPhase.FIRST_PASS
        snapshot.rebuildProgress.shouldBeNull()
    }
}
