package eu.kanade.translation.ui

import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.orchestration.rebuildTruthFromRunRecord
import eu.kanade.translation.orchestration.withRunRecordTruth
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 *  U.1/U.7: the run record → rebuild/restore phase truth and its
 * transitions across a resumed run's lifecycle
 * (rebuild → restoring → running → finished). The derivation is pure: a
 * record in the preflight preamble (or the   ENVELOPE_PLAN
 * plan-build window) projects the rebuild/restore phases with the
 * restored/remaining payload; every other post-preflight or terminal state
 * projects NO rebuild phase, so ordinary running and finished work is never
 * restamped.
 */
class RebuildTruthTransitionsTest {

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
        counters: Map<String, Int> = emptyMap(),
    ): ChapterRunRecord = ChapterRunRecord(
        runId = "run-1758000000000-abc123",
        state = state,
        frozenConfig = frozenConfig(),
        frozenRunConfigFingerprint = hex64,
        orderedSourceDigest = hex64,
        analysisPolicyFingerprint = hex64,
        envelopePolicyFingerprint = hex64,
        phaseCounters = counters,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
    )

    /** Mirrors the coordinator's preflight counters (total/done/reused). */
    private fun counters(done: Int, total: Int = 70, reused: Int = done) = mapOf(
        "ocrPagesTotal" to total,
        "ocrPagesDone" to done,
        "ocrPagesReused" to reused,
    )

    @Test
    fun `no record projects no rebuild truth`() {
        rebuildTruthFromRunRecord(null).shouldBeNull()
    }

    @Test
    fun `resumed run preamble projects the rebuilding phase`() {
        val runSnapshot = rebuildTruthFromRunRecord(record(ChapterRunState.RUN_SNAPSHOT, counters(0)))
        runSnapshot!!.first shouldBe TranslationBatchPhase.REBUILDING
        runSnapshot.second!!.totalPages shouldBe 70
        runSnapshot.second!!.restoredPages shouldBe 0

        val sourceValidation = rebuildTruthFromRunRecord(record(ChapterRunState.SOURCE_VALIDATION))
        sourceValidation!!.first shouldBe TranslationBatchPhase.REBUILDING
    }

    @Test
    fun `ocr plan with zero done count is deliberately not a rebuild`() {
        // A first run also sits in OCR_PLAN (done=0) through its whole fresh
        // OCR pass — labeling it a rebuild would fake rebuild copy over an
        // ordinary first translation.
        rebuildTruthFromRunRecord(record(ChapterRunState.OCR_PLAN, counters(0))).shouldBeNull()
    }

    @Test
    fun `ocr plan adoption progress projects restoring with the payload`() {
        val truth = rebuildTruthFromRunRecord(record(ChapterRunState.OCR_PLAN, counters(37)))

        truth!!.first shouldBe TranslationBatchPhase.RESTORING
        truth.second!!.restoredPages shouldBe 37
        truth.second!!.totalPages shouldBe 70
        truth.second!!.remainingPages shouldBe 33
    }

    @Test
    fun `post preflight running states project no rebuild phase`() {
        rebuildTruthFromRunRecord(record(ChapterRunState.OCR_PREFLIGHT, counters(70))).shouldBeNull()
        rebuildTruthFromRunRecord(record(ChapterRunState.ANALYSIS_CHUNKS)).shouldBeNull()
        rebuildTruthFromRunRecord(record(ChapterRunState.TRANSLATE)).shouldBeNull()
        rebuildTruthFromRunRecord(record(ChapterRunState.FINALIZE)).shouldBeNull()
    }

    @Test
    fun `envelope plan record projects the rebuilding phase`() {
        //   the run record parks in ENVELOPE_PLAN while the
        // coordinator rebuilds the dispatch work (resume hydration). It used
        // to project null, freezing the sheet on a stale numeric hero for the
        // whole window; it is a rebuild phase now.
        val truth = rebuildTruthFromRunRecord(record(ChapterRunState.ENVELOPE_PLAN, counters(37)))

        truth!!.first shouldBe TranslationBatchPhase.REBUILDING
        truth.second!!.restoredPages shouldBe 37
        truth.second!!.totalPages shouldBe 70
    }

    @Test
    fun `terminal run states project no rebuild phase`() {
        rebuildTruthFromRunRecord(record(ChapterRunState.COMPLETE)).shouldBeNull()
        rebuildTruthFromRunRecord(record(ChapterRunState.PAUSED)).shouldBeNull()
        rebuildTruthFromRunRecord(record(ChapterRunState.ABORTED)).shouldBeNull()
    }

    // ------------------------- snapshot stamping (live window gating) ----

    private fun liveSnapshot() = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATING)
        .copy(batchPhase = TranslationBatchPhase.FIRST_PASS, totalPages = 70)

    @Test
    fun `live first pass snapshot is stamped with the restoring truth`() {
        val stamped = liveSnapshot()
            .withRunRecordTruth(record(ChapterRunState.OCR_PLAN, counters(37, 70, 30)))

        stamped.batchPhase shouldBe TranslationBatchPhase.RESTORING
        stamped.rebuildProgress!!.restoredPages shouldBe 37
        stamped.rebuildProgress!!.remainingPages shouldBe 33
        // The live window keeps its other truth fields untouched.
        stamped.state shouldBe Translation.State.TRANSLATING
        stamped.totalPages shouldBe 70
    }

    @Test
    fun `live first pass snapshot is stamped with the rebuilding truth`() {
        val stamped = liveSnapshot()
            .withRunRecordTruth(record(ChapterRunState.RUN_SNAPSHOT, counters(0)))

        stamped.batchPhase shouldBe TranslationBatchPhase.REBUILDING
        stamped.rebuildProgress!!.restoredPages shouldBe 0
    }

    @Test
    fun `rebuild truth holds only while the record stays in the preamble`() {
        // The next FRESH snapshot emission (record now at OCR_PREFLIGHT) is
        // projected unchanged: the rebuild phase leaves with the window.
        val running = liveSnapshot()
            .withRunRecordTruth(record(ChapterRunState.OCR_PREFLIGHT, counters(70)))

        running.batchPhase shouldBe TranslationBatchPhase.FIRST_PASS
        running.rebuildProgress.shouldBeNull()
    }

    @Test
    fun `a finished snapshot is never restamped`() {
        val finished = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATED)
            .copy(batchPhase = TranslationBatchPhase.FINISHED, totalPages = 70)

        val stamped = finished.withRunRecordTruth(record(ChapterRunState.OCR_PLAN, counters(37)))

        stamped.batchPhase shouldBe TranslationBatchPhase.FINISHED
        stamped.rebuildProgress.shouldBeNull()
    }

    @Test
    fun `queued and paused snapshots are never restamped`() {
        val queued = TranslationProgressSnapshot.empty(1L, Translation.State.QUEUE)
            .copy(batchPhase = TranslationBatchPhase.IDLE)
        queued.withRunRecordTruth(record(ChapterRunState.OCR_PLAN, counters(37)))
            .batchPhase shouldBe TranslationBatchPhase.IDLE

        val paused = TranslationProgressSnapshot.empty(1L, Translation.State.PAUSED)
            .copy(batchPhase = TranslationBatchPhase.FINISHED, pauseReason = "provider unavailable")
        val stampedPause = paused.withRunRecordTruth(record(ChapterRunState.OCR_PLAN, counters(37)))
        stampedPause.batchPhase shouldBe TranslationBatchPhase.FINISHED
        stampedPause.rebuildProgress.shouldBeNull()
    }

    @Test
    fun `an already rebuilding snapshot stays inside the live window`() {
        // Between probes the projector re-stamps from its cached record; a
        // snapshot already carrying REBUILDING must remain stampable.
        val rebuilding = liveSnapshot().copy(batchPhase = TranslationBatchPhase.REBUILDING)
        val restamped = rebuilding.withRunRecordTruth(record(ChapterRunState.OCR_PLAN, counters(40)))

        restamped.batchPhase shouldBe TranslationBatchPhase.RESTORING
        restamped.rebuildProgress!!.restoredPages shouldBe 40
    }

    @Test
    fun `restoring payload never exceeds the registered total`() {
        val truth = rebuildTruthFromRunRecord(
            record(ChapterRunState.OCR_PLAN, counters(80, total = 70)),
        )

        truth!!.second!!.restoredPages shouldBe 70
        truth.second!!.remainingPages shouldBe 0
    }
}
