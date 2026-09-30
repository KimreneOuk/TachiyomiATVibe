package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.RunConfigSnapshot
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class StandardLaneWorkerContext(
    val store: ChapterTranslationStore,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val standardTranslateOutcome: (suspend (OcrReadyPageRef) -> TranslationCompletionOutcome)?,
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
    private val standardTranslateOutcome: (suspend (OcrReadyPageRef) -> TranslationCompletionOutcome)?
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
    ): ChapterRunRecord = context.record(
        runId,
        state,
        frozenFingerprint,
        sourceDigest,
        counters,
        ocrCorpusFingerprint,
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

    private fun standardPageTerminalAtTranslate(page: PageTranslationView): Boolean =
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

        //  publish the persisted layout right after each inpaint commits
        // (the same per-page hook the AI envelope lane installs).
        overlapScheduler?.onInpaintCommitted = { pageKey ->
            renderJoin?.publishPersistedLayoutForCompletedPage(pageKey)
            Unit
        }
        // The overlap loop runs beside the serial translate loop and stops
        // between pages when the translation tail ends.
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

                //  bracket the per-page translate call with the SAME
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
                    is TranslationCompletionOutcome.Completed -> translatedPages += outcome.completedPageKeys.size
                    is TranslationCompletionOutcome.Paused -> {
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
                    is TranslationCompletionOutcome.Failed -> {
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
                    is TranslationCompletionOutcome.Unexpected -> {
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
                            unexpectedStage = outcome.stage.toBatchDiagnosticStage(),
                        )
                    }
                    is TranslationCompletionOutcome.PersistenceRejected -> {
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
                            persistenceRejectedStage = outcome.stage.toBatchDiagnosticStage(),
                        )
                    }
                }
            }

            // Drained: every ordered page reached its terminal. The drained
            // TRANSLATE record mirrors the AI lane's (counters + stop), then
            // the shared engine-agnostic finalizer publishes the run's
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
