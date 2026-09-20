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

internal class StandardLaneWorkerContext(
    val store: ChapterTranslationStore,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val standardTranslateOutcome: (suspend (OcrReadyPageRef) -> ChunkCompletionOutcome)?,
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
    val runFinalizeAndComplete: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
)

internal class StandardLaneWorker(
    private val context: StandardLaneWorkerContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val frozenConfig: RunConfigSnapshot
        get() = context.frozenConfig
    private val effectiveSourcePairs: List<Pair<String, String>>
        get() = context.effectiveSourcePairs
    private val standardTranslateOutcome: (suspend (OcrReadyPageRef) -> ChunkCompletionOutcome)?
        get() = context.standardTranslateOutcome
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

    private suspend fun runFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = context.runFinalizeAndComplete(
        artifact,
        runId,
        orderedPages,
        corpusFingerprint,
        baseCounters,
    )

    private fun runConfigFingerprint(config: RunConfigSnapshot): String =
        ChapterProfileBatchCoordinator.runConfigFingerprint(config)

    private fun orderedSourceDigest(pairs: List<Pair<String, String>>): String =
        ChapterProfileBatchCoordinator.orderedSourceDigest(pairs)

    private fun standardPageTerminalAtTranslate(page: PageTranslation): Boolean =
        ChapterProfileBatchCoordinator.standardPageTerminalAtTranslate(page)

    suspend fun runPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String?,
        baseCounters: Map<String, Int>,
        hasGaps: Boolean = false,
    ): BatchPass1Outcome {
        val overlapScheduler = context.overlapScheduler
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val allPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first }

        fun translateCounters(extra: Map<String, Int>): Map<String, Int> = baseCounters + extra

        fun translateRecord(state: ChapterRunState, counters: Map<String, Int>): ChapterRunRecord =
            record(
                runId,
                state,
                frozenFingerprint,
                sourceDigest,
                counters,
                ocrCorpusFingerprint = corpusFingerprint,
            )

        // Phase entry: TRANSLATE (no profile pointer — the standard lane's
        // records carry only the schema-required policy fingerprints).
        publishRecord(artifact, translateRecord(ChapterRunState.TRANSLATE, translateCounters(emptyMap())))

        val seam = standardTranslateOutcome
        if (seam == null) {
            // Typed CONFIGURATION-class gate, mirroring the analysis runner
            // seam: never run provider-bound work without a typed transport.
            publishRecord(
                artifact,
                translateRecord(
                    ChapterRunState.TRANSLATE,
                    translateCounters(mapOf(ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_TRANSPORT to 1, ChapterProfileBatchCoordinator.COUNTER_STOP to 1)),
                ),
            )
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 standard translate paused: no typed standard seam wired (CONFIGURATION gate)"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = allPageKeys,
                reason = ChapterProfileBatchCoordinator.STANDARD_NO_SEAM_REASON,
            )
        }

        // D2: publish the persisted layout right after each inpaint commits
        // (the same per-page hook the AI envelope lane installs).
        overlapScheduler?.onInpaintCommitted = { pageKey ->
            renderJoin?.publishPersistedLayoutForCompletedPage(pageKey)
            Unit
        }
        // T924 Stage 7 (D1) idiom: the overlap loop runs BESIDE the serial
        // translate loop and is stopped between pages once the tail ends.
        val overlapLoop: suspend (suspend () -> BatchPass1Outcome) -> BatchPass1Outcome =
            { runTail ->
                if (overlapScheduler == null) {
                    runTail()
                } else {
                    coroutineScope {
                        val loop = launch { overlapScheduler.runOverlapLoop() }
                        val outcome = runTail()
                        // No further windows: stop the loop between pages
                        // (a running inpaint finishes through the lane).
                        overlapScheduler.stopOverlap()
                        loop.join()
                        outcome
                    }
                }
            }

        return overlapLoop {
            var translatedPages = 0
            for (page in orderedPages) {
                val (pageKey, pageIndex) = page
                currentCoroutineContext().ensureActive()
                // Reader-priority courtesy between pages (preflight idiom).
                yield()

                // Resume-reuse parity: an already-terminal page never re-pays
                // the provider (READY/PARTIAL committed, textless, or the
                // cross-schedule rendered safety).
                val live = store.state.value[pageKey]
                if (live != null && standardPageTerminalAtTranslate(live)) continue

                // D1: bracket the per-page translate call with the SAME
                // remote-window mechanism the AI lane rides — the overlap
                // scheduler runs serial inpaint ONLY inside the window, so
                // native work never overlaps translation (ALL engines).
                overlapScheduler?.onRemoteWindowOpened()
                val outcome = try {
                    seam(
                        OcrReadyPageRef(
                            pageKey = pageKey,
                            pageIndex = pageIndex,
                            generation = store.currentGeneration,
                            blockFingerprints = emptyList(),
                        ),
                    )
                } finally {
                    overlapScheduler?.onRemoteWindowClosed()
                }
                when (outcome) {
                    is ChunkCompletionOutcome.Completed -> translatedPages += outcome.completedPageKeys.size
                    is ChunkCompletionOutcome.Paused -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        ChapterProfileBatchCoordinator.COUNTER_PAGES_TRANSLATED to translatedPages,
                                        ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT t924 standard translate paused: ${outcome.reason}"
                        }
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PAUSED,
                            anchorPageKey = outcome.anchorPageKey,
                            completedPageKeys = allPageKeys,
                            retryablePageKeys = outcome.retryablePageKeys,
                            failure = outcome.failure,
                            nextEligibleRetryAtEpochMs = outcome.nextEligibleRetryAtEpochMs,
                            reason = outcome.reason,
                        )
                    }
                    is ChunkCompletionOutcome.Failed -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        ChapterProfileBatchCoordinator.COUNTER_PAGES_TRANSLATED to translatedPages,
                                        ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT t924 standard translate failed: ${outcome.reason}"
                        }
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.FAILED,
                            anchorPageKey = outcome.anchorPageKey,
                            completedPageKeys = allPageKeys,
                            terminalPageKeys = outcome.terminalPageKeys,
                            failure = outcome.failure,
                            reason = outcome.reason,
                        )
                    }
                    is ChunkCompletionOutcome.Unexpected -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        ChapterProfileBatchCoordinator.COUNTER_PAGES_TRANSLATED to translatedPages,
                                        ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.FAILED,
                            anchorPageKey = outcome.anchorPageKey,
                            completedPageKeys = allPageKeys,
                            terminalPageKeys = outcome.terminalPageKeys,
                            reason = outcome.reason,
                            unexpectedStage = outcome.stage,
                        )
                    }
                    is ChunkCompletionOutcome.PersistenceRejected -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        ChapterProfileBatchCoordinator.COUNTER_PAGES_TRANSLATED to translatedPages,
                                        ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PERSISTENCE_REJECTED,
                            anchorPageKey = outcome.anchorPageKey,
                            reason = outcome.reason,
                            persistenceRejectedStage = outcome.stage,
                        )
                    }
                }
            }

            // Drained: every ordered page reached its terminal. The drained
            // TRANSLATE record mirrors the AI lane's (counters + stop), then
            // the SHARED engine-agnostic Stage-7 finalize publishes the run's
            // single COMPLETE.
            publishRecord(
                artifact,
                translateRecord(
                    ChapterRunState.TRANSLATE,
                    translateCounters(
                        mapOf(
                            ChapterProfileBatchCoordinator.COUNTER_PAGES_TRANSLATED to translatedPages,
                            ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                        ),
                    ),
                ),
            )
            // X6 invariant: "PARTIAL-corpus COMPLETE" is unrepresentable.
            // If hasGaps == true (corpusGaps > 0) or corpusFingerprint == null,
            // we stop at TRANSLATE and return PAUSED, never COMPLETE.
            if (hasGaps || corpusFingerprint == null) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT M4 standard translate finished available checkpoints but chapter has gaps; returning PAUSED"
                }
                return@overlapLoop BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    completedPageKeys = allPageKeys,
                    reason = ChapterProfileBatchCoordinator.STOP_REASON,
                )
            }
            runFinalizeAndComplete(
                artifact = artifact,
                runId = runId,
                orderedPages = orderedPages,
                corpusFingerprint = corpusFingerprint,
                baseCounters = translateCounters(mapOf(ChapterProfileBatchCoordinator.COUNTER_PAGES_TRANSLATED to translatedPages)),
            )
        }
    }

}

