package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.CheckpointOcrResult
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.context.SeriesProfileRegistry
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AnalysisChunkCoverage
import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.EvidenceStrength
import eu.kanade.translation.artifact.FactConflictState
import eu.kanade.translation.artifact.FactProvenance
import eu.kanade.translation.artifact.FactScope
import eu.kanade.translation.artifact.FactType
import eu.kanade.translation.artifact.ProfileFact
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterAttemptLedgerDocument
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.ExtractedEntity
import eu.kanade.translation.artifact.ExtractedRelationship
import eu.kanade.translation.artifact.ExtractedTerm
import eu.kanade.translation.artifact.ExtractedTermKind
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.OcrCheckpointMode
import eu.kanade.translation.artifact.PageRange
import eu.kanade.translation.artifact.ProfilePointer
import eu.kanade.translation.artifact.ProfileScene
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.SceneRegister
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.SidecarRead
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.ToneFlag
import eu.kanade.translation.artifact.isSha256Hex
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCommittedDisplay
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.ocrFingerprint
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.translator.SharedBatchRequestSublimitGate
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisRequestBuilder
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.analysis.GlossaryEntryKind
import eu.kanade.translation.translator.analysis.GlossarySynthesizer
import eu.kanade.translation.translator.analysis.GlossarySynthesisOutcome
import eu.kanade.translation.translator.analysis.AnalyzerProvenanceFactory
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanResult
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanner
import eu.kanade.translation.translator.contextual.AnalysisChunkPolicy
import eu.kanade.translation.translator.contextual.ChunkPlannerPage
import eu.kanade.translation.translator.contextual.EnvelopePlannerBlock
import eu.kanade.translation.translator.contextual.EnvelopePlannerPage
import eu.kanade.translation.translator.contextual.EnvelopePlannerPolicy
import eu.kanade.translation.translator.contextual.EnvelopePlanResult
import eu.kanade.translation.translator.contextual.GlobalEnvelopePlanner
import eu.kanade.translation.translator.contextual.OcrCorpusManifest
import eu.kanade.translation.translator.contextual.OcrCorpusPageEntry
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest


internal class FinalizeWorkerContext(
    val store: ChapterTranslationStore,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val overlapScheduler: OverlapScheduler?,
    val renderJoin: BatchRenderJoin?,
    val publishRecord: suspend (
        ChapterArtifactEngine,
        ChapterRunRecord,
    ) -> ChapterArtifactEngine.TransactionOutcome?,
    val record: (
        String,
        ChapterRunState,
        String,
        String,
        Map<String, Int>,
        String?,
        ProfilePointer?,
    ) -> ChapterRunRecord,
    val drainDisplayTailBeforeComplete: suspend (
        List<String>,
    ) -> RecoveryWorker.DisplayTailDrain,
    val t924PageTerminalAtFinalize: (PageTranslation?, Long) -> Boolean,
    val strandedPageReason: (PageTranslation?) -> String,
    val persistEnvelopeStructuralFailure: suspend (String, String, String) -> Unit,
)

internal class FinalizeWorker(
    private val context: FinalizeWorkerContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val frozenConfig: RunConfigSnapshot
        get() = context.frozenConfig
    private val effectiveSourcePairs: List<Pair<String, String>>
        get() = context.effectiveSourcePairs
    private val overlapScheduler: OverlapScheduler?
        get() = context.overlapScheduler
    private val renderJoin: BatchRenderJoin?
        get() = context.renderJoin

    private suspend fun publishRecord(
        artifact: ChapterArtifactEngine,
        record: ChapterRunRecord,
    ): ChapterArtifactEngine.TransactionOutcome? =
        context.publishRecord(artifact, record)

    private fun record(
        runId: String,
        state: ChapterRunState,
        frozenFingerprint: String,
        sourceDigest: String,
        counters: Map<String, Int>,
        ocrCorpusFingerprint: String? = null,
        profilePointer: ProfilePointer? = null,
    ): ChapterRunRecord = context.record(
        runId,
        state,
        frozenFingerprint,
        sourceDigest,
        counters,
        ocrCorpusFingerprint,
        profilePointer,
    )

    private suspend fun drainDisplayTailBeforeComplete(
        orderedPageKeys: List<String>,
    ): RecoveryWorker.DisplayTailDrain =
        context.drainDisplayTailBeforeComplete(orderedPageKeys)

    private fun t924PageTerminalAtFinalize(
        page: PageTranslation?,
        activeGeneration: Long,
    ): Boolean = context.t924PageTerminalAtFinalize(page, activeGeneration)

    private fun strandedPageReason(page: PageTranslation?): String =
        context.strandedPageReason(page)

    private suspend fun persistEnvelopeStructuralFailure(
        pageKey: String,
        reason: String,
        carrier: String,
    ) = context.persistEnvelopeStructuralFailure(pageKey, reason, carrier)

    private fun runConfigFingerprint(config: RunConfigSnapshot): String =
        ChapterProfileBatchCoordinator.runConfigFingerprint(config)

    private fun orderedSourceDigest(pairs: List<Pair<String, String>>): String =
        ChapterProfileBatchCoordinator.orderedSourceDigest(pairs)

    suspend fun runPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)

        // ST-14 entry: the FINALIZE phase pointer (resume re-runs finalize —
        // every step below is an idempotent re-run).
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.FINALIZE,
                frozenFingerprint,
                sourceDigest,
                baseCounters + mapOf(ChapterProfileBatchCoordinator.COUNTER_FINALIZE to 1) +
                    // Gate-6.5 evidence rides the FINALIZE record: the schema
                    // bounds phaseCounters at 32 keys, and baseCounters + the
                    // finalize keys + the full overlap snapshot would push the
                    // COMPLETE record past it (36 > 32 — the COMPLETE
                    // publication would be silently rejected, wave-7b fix).
                    (overlapScheduler?.let { scheduler ->
                        scheduler.counters.snapshot().mapValues { it.value.toInt() }
                    } ?: emptyMap()),
                ocrCorpusFingerprint = corpusFingerprint,
                profilePointer = store.artifactManifest?.profile,
            ),
        )
        return drainFinalizeAndComplete(
            artifact = artifact,
            runId = runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = baseCounters,
        )
    }

    /**
     * Steps 2-6 of the ST-14 FINALIZE phase — the idempotent drain shared by
     * the fresh entry ([runFinalizeAndComplete]) and the ST-14 FINALIZE
     * resume ([resumeFinalizeOrComplete]):
     *
     *  2. Serial inpaint drain through the overlap scheduler — pages whose
     *     inpaint already committed are skipped by the scheduler's candidate
     *     rule (never re-inpainted); with no scheduler this is a no-op.
     *  2b. T934 display-tail drain: pages whose translate+inpaint work is
     *     done but whose committed display (render-terminal stamp →
     *     promotion) never landed are drained to completion here, bounded;
     *     a page the drain cannot finish takes a SPECIFIC typed terminal and
     *     the run still completes (as a warning).
     *  3. Persisted-layout publication sweep (idempotent per page).
     *  4. Stranded-page reconciliation (safe re-run: terminal pages skip).
     *  5. NonCancellable flush + retention reconciliation.
     *  6. The run's FIRST and ONLY `COMPLETE` publication.
     */

    suspend fun drainPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = drainFinalizeAndComplete(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
    )

    private suspend fun drainFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val allPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first }

        // 2. Serial post-translate inpaint drain (overlap-fallback arm).
        overlapScheduler?.drainSerial()

        // 2b. T934 display-tail drain: COMPLETE means "every page readable",
        //     not "every ingredient done". The inpaint lane's render-terminal
        //     stamp only fires when the page's translation was ALREADY
        //     terminal at inpaint time, so order-inverted pages (inpaint
        //     committed before the envelope translation — the decoupled
        //     candidacy norm) stay render-PENDING with ALL work done and are
        //     invisible to the stranded sweep below (translation READY is
        //     terminal there). Drain that tail to completion here — bounded
        //     passes, each a fresh snapshot so a stale-write rejection
        //     retries — and give a page the drain genuinely cannot finish a
        //     SPECIFIC typed terminal instead of publishing COMPLETE over a
        //     silently frozen ORIGINAL_ONLY page.
        val displayTail = drainDisplayTailBeforeComplete(orderedPages.map { it.first })

        // 3. Persisted-layout publication sweep for any page the per-page
        //    hook missed (idempotent — pages with a published plan skip).
        var layoutsPublished = 0
        val renderJoin = context.renderJoin
        if (renderJoin != null) {
            for ((pageKey, _) in orderedPages) {
                if (renderJoin.publishPersistedLayoutForCompletedPage(pageKey)) {
                    layoutsPublished++
                }
            }
        }

        // 4. Stranded-page reconciliation: pages that are not durably
        //    terminal get a durable failure + a reported reason. The T924
        //    terminal predicate is NOT the legacy reconciler's one: the legacy
        //    schedule renders + promotes display in-pass (hasRenderedResult),
        //    while the flagged pipeline's committed-translation state is
        //    terminal WITHOUT an in-pass render — reusing the legacy
        //    definition here would mark every healthy page stranded.
        val stateNow = store.state.value
        var strandedReconciled = 0
        for (pageKey in orderedPages.map { it.first }) {
            val page = stateNow[pageKey]
            if (t924PageTerminalAtFinalize(page, store.currentGeneration)) continue
            // T934 stranded-page fix: the reason must name the page's actual
            // work state (all-translated, textless, user-owned, or genuinely
            // unfinished) — a bare status is what made these pages show as a
            // generic "Unknown error" class in the progress sheet.
            val reason = strandedPageReason(page)
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 stranded page at FINALIZE pageHash=${ShortHash.hash(pageKey)} reason=$reason"
            }
            persistEnvelopeStructuralFailure(
                pageKey,
                reason,
                carrier = "stranded page reconciled at FINALIZE",
            )
            strandedReconciled++
        }

        // 5. Durable teardown: NonCancellable flush + retention reconciliation
        //    (the BatchChapterTranslator :781-794 idiom). The sweep itself runs
        //    fire-and-forget: its crawl is minutes of SAF round-trips and must
        //    neither delay run closure nor hold the store mutex (2026-09-15
        //    jdb-proven 20+ minute stall).
        withContext(NonCancellable) {
            store.flush()
        }
        store.reconcileArtifactRetentionAsync()

        // 6. Run closure: the single COMPLETE publication of the run. The
        //    overlap counters live on the FINALIZE record (see above — the
        //    32-key phaseCounters bound). The outcome is NOT advisory here:
        //    a rejected (or unpublishable) closure leaves the run durably at
        //    FINALIZE, so the coordinator pauses instead of reporting a
        //    completion the record disagrees with (ChapterProfileBatchCoordinator.RUN_CLOSURE_REJECTED_REASON).
        //
        //    T934 round 3: the publication CASes against the façade manifest
        //    snapshot while the drain steps above move durable state through
        //    their own store transactions. One fresh-baseline retry — re-read
        //    the durable manifest into the façade, then re-publish — keeps
        //    the closure off the typed pause when the snapshot is merely
        //    stale (the same one-shot rebase every artifact-store seam gets
        //    from retryOnStaleManifest); a genuine rejection still pauses.
        fun completeRecord() = record(
            runId,
            ChapterRunState.COMPLETE,
            frozenFingerprint,
            sourceDigest,
            baseCounters +
                mapOf(
                    ChapterProfileBatchCoordinator.COUNTER_FINALIZE to 1,
                    ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE to 1,
                    ChapterProfileBatchCoordinator.COUNTER_LAYOUTS_PUBLISHED to layoutsPublished,
                    ChapterProfileBatchCoordinator.COUNTER_STRANDED_RECONCILED to strandedReconciled,
                    ChapterProfileBatchCoordinator.COUNTER_DISPLAY_TAIL_DRAINED to displayTail.drained,
                    ChapterProfileBatchCoordinator.COUNTER_DISPLAY_TAIL_FAILED to displayTail.failed.size,
                ),
            ocrCorpusFingerprint = corpusFingerprint,
            profilePointer = store.artifactManifest?.profile,
        )
        var closure = publishRecord(artifact, completeRecord())
        if (closure !is ChapterArtifactEngine.TransactionOutcome.Committed) {
            store.withArtifactEngineLocked { artifact ->
                artifact.readManifest()
            }?.let { store.artifactManifest = it }
            closure = publishRecord(artifact, completeRecord())
        }
        when (closure) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 run COMPLETE pages=${allPageKeys.size} stranded=$strandedReconciled " +
                        "layouts=$layoutsPublished displayTailDrained=${displayTail.drained} " +
                        "displayTailFailed=${displayTail.failed.size} " +
                        "overlap=${overlapScheduler?.counters?.snapshot() ?: emptyMap()}"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.COMPLETED,
                    completedPageKeys = allPageKeys,
                    reason = ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON,
                )
            }
            else -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 run COMPLETE publication rejected " +
                        "(outcome=${closure?.javaClass?.simpleName ?: "no-manifest"}); " +
                        "pausing at FINALIZE"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    completedPageKeys = allPageKeys,
                    reason = ChapterProfileBatchCoordinator.RUN_CLOSURE_REJECTED_REASON,
                )
            }
        }
    }

    /**
     * T924 Phase 4 Wave A — the STANDARD-engine translate tail, entered
     * exactly when the OCR preflight published a COMPLETE corpus (the
     * whole-corpus gap gate above is unchanged). The DIRECTOR design: the
     * standard engine continues batch translation just like the AI lane,
     * without the glossary and without anything AI-specific:
     *
     *  1. `TRANSLATE` phase record carrying the run's OCR corpus fingerprint
     *     and NO analysis/profile/envelope pointers (the standard lane never
     *     produces them; the ST-14 resume gate reads the fingerprint from
     *     the FINALIZE record this tail leads to).
     *  2. IN-ORDER per-page translation through the injected
     *     [standardTranslateOutcome] seam — the LEGACY per-page machinery
     *     (BatchLaneWorkers idiom), never the envelope provenance ladder.
     *     Pages already translation-terminal (READY/PARTIAL, textless, or
     *     rendered — resume-reuse parity) are never re-paid. Every seam call
     *     is bracketed by the overlap window (open before, close after) for
     *     ALL standard engines: native inpaint must never overlap
     *     translation.
     *  3. Typed seam outcomes map to the SAME chapter-level semantics the
     *     legacy schedule uses (SBC): Completed → next page; Paused → typed
     *     PAUSE; Failed/Unexpected → FAILED; PersistenceRejected →
     *     PERSISTENCE_REJECTED.
     *  4. When the translate loop drains → [runFinalizeAndComplete] verbatim
     *     — the engine-agnostic Stage-7 finalize (serial inpaint drain,
     *     layout sweep, stranded reconciliation, single COMPLETE
     *     publication). Completion is translation-terminal WITHOUT an
     *     in-pass render, exactly like the AI lane (display rides the live
     *     overlay + candidate snapshots; renderStatus stays PENDING).
     */

}
