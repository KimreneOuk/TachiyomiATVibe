package eu.kanade.translation.pipeline.batch.envelope

import eu.kanade.translation.engines.translator.BatchRequestSublimitGate
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.engines.translator.contextual.EnvelopePlannerPolicy
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.ProfilePointer
import eu.kanade.translation.persistence.artifact.RunConfigSnapshot
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.BatchPass1Outcome
import eu.kanade.translation.pipeline.batch.BatchPass1Status
import eu.kanade.translation.pipeline.batch.BatchRenderJoin
import eu.kanade.translation.pipeline.batch.BatchScheduleListener
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator.EnvelopeWorkBuild
import eu.kanade.translation.pipeline.batch.OverlapScheduler
import eu.kanade.translation.pipeline.batch.PageKey
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class EnvelopeDispatcherContext(
    val store: ChapterTranslationStore,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val listener: BatchScheduleListener,
    val textTranslator: TextTranslator?,
    val translationSublimitGate: BatchRequestSublimitGate,
    val overlapScheduler: OverlapScheduler?,
    val renderJoin: BatchRenderJoin?,
    val envelopePlannerPolicy: EnvelopePlannerPolicy?,
    val nowEpochMs: () -> Long,
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
    val buildEnvelopeDispatchWork: suspend (
        ChapterArtifactEngine,
        List<PageKey>,
        String,
    ) -> ChapterProfileBatchCoordinator.EnvelopeWorkBuild,
    val rebuildDispatchWork: suspend (
        ChapterArtifactEngine,
        List<PageKey>,
        String,
        String,
    ) -> ReplanResult,
    val persistEnvelopeStructuralFailure: suspend (
        String,
        String,
    ) -> Unit,
    val providerChunkProfile: () -> TranslationContextChunkPlanner.Profile,
    val runFinalizeAndComplete: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
)

internal class EnvelopeDispatcher(
    private val context: EnvelopeDispatcherContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val frozenConfig: RunConfigSnapshot
        get() = context.frozenConfig
    private val effectiveSourcePairs: List<Pair<String, String>>
        get() = context.effectiveSourcePairs
    private val listener: BatchScheduleListener
        get() = context.listener
    private val textTranslator: TextTranslator?
        get() = context.textTranslator
    private val translationSublimitGate: BatchRequestSublimitGate
        get() = context.translationSublimitGate
    private val overlapScheduler: OverlapScheduler?
        get() = context.overlapScheduler
    private val renderJoin: BatchRenderJoin?
        get() = context.renderJoin
    private val envelopePlannerPolicy: EnvelopePlannerPolicy?
        get() = context.envelopePlannerPolicy
    private val nowEpochMs: () -> Long
        get() = context.nowEpochMs

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

    private suspend fun buildEnvelopeDispatchWork(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
    ): ChapterProfileBatchCoordinator.EnvelopeWorkBuild =
        context.buildEnvelopeDispatchWork(artifact, orderedPages, corpusFingerprint)

    private suspend fun rebuildDispatchWork(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        reason: String,
    ): ReplanResult = context.rebuildDispatchWork(artifact, orderedPages, corpusFingerprint, reason)

    private suspend fun persistEnvelopeStructuralFailure(
        pageKey: String,
        reason: String,
    ) = context.persistEnvelopeStructuralFailure(pageKey, reason)

    private fun providerChunkProfile(): TranslationContextChunkPlanner.Profile =
        context.providerChunkProfile()

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

    suspend fun runPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val overlapScheduler = context.overlapScheduler
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val allPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first }

        fun envelopeCounters(extra: Map<String, Int>): Map<String, Int> =
            baseCounters + extra

        fun envelopeRecord(state: ChapterRunState, counters: Map<String, Int>): ChapterRunRecord =
            record(
                runId,
                state,
                frozenFingerprint,
                sourceDigest,
                counters,
                ocrCorpusFingerprint = corpusFingerprint,
            )

        //  entry: phase record, then plan (pure re-derivation).
        publishRecord(
            artifact,
            envelopeRecord(ChapterRunState.ENVELOPE_PLAN, envelopeCounters(emptyMap())),
        )

        val manifest = store.artifactManifest ?: return BatchPass1Outcome(
            needsTranslation = emptyList(),
            status = BatchPass1Status.PERSISTENCE_REJECTED,
            reason = "T924 envelope plan deferred: manifest unavailable",
        )
        //  entry needs a typed AI transport; the plan still publishes so
        // a later wired run resumes directly into TRANSLATE. For AI translation, the
        // constructor widened to TextTranslator for the standard lane, so the
        // envelope path re-narrows here — a non-contextual translator on the
        // AI lane takes the SAME typed CONFIGURATION pause as a missing one
        // (the dispatch gate makes this unreachable in production).
        val translator = textTranslator as? ContextualTextTranslator

        // ---- ENVELOPE_PLAN: build + plan + publish (or reuse). ----
        when (val build = buildEnvelopeDispatchWork(artifact, orderedPages, corpusFingerprint)) {
            is EnvelopeWorkBuild.CorpusDrift -> return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = allPageKeys,
                reason = build.reason,
            )
            is EnvelopeWorkBuild.NothingPending -> {
                // Skip rule: no translatable work — textless chapters
                // never reach TRANSLATE. Typed PAUSED no-work terminal.
                publishRecord(
                    artifact,
                    envelopeRecord(
                        ChapterRunState.ENVELOPE_PLAN,
                        envelopeCounters(
                            mapOf(
                                ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_TOTAL to 0,
                                ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_DONE to 0,
                                ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_WORK to 1,
                                ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                            ),
                        ),
                    ),
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    completedPageKeys = allPageKeys,
                    reason = ChapterProfileBatchCoordinator.ENVELOPE_NO_WORK_REASON,
                )
            }
            is EnvelopeWorkBuild.PlannerRejected -> {
                //  terminal: a page that cannot fit any legal envelope
                // is rejected whole (page atomicity); it takes a durable
                // structural failure and the phase pauses at it.
                build.namedPageKeys.forEach { pageKey ->
                    persistEnvelopeStructuralFailure(pageKey, build.reasons.joinToString("; "))
                }
                publishRecord(
                    artifact,
                    envelopeRecord(
                        ChapterRunState.ENVELOPE_PLAN,
                        envelopeCounters(
                            mapOf(
                                ChapterProfileBatchCoordinator.COUNTER_ENVELOPE_PLAN_REJECTED to 1,
                                ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                            ),
                        ),
                    ),
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    anchorPageKey = build.namedPageKeys.firstOrNull(),
                    reason = "T924 envelope plan rejected: ${build.reasons.joinToString("; ")}",
                )
            }
            is EnvelopeWorkBuild.Ready -> {
                val fresh = build.plan
                //  resume rule: identical inputs re-derive an identical
                // plan fingerprint — reuse the published plan, no write.
                val reuse = manifest.envelopePlan != null &&
                    EnvelopePlanPublication.readValidatedPlan(store, manifest).let { read ->
                        read is EnvelopePlanPublication.EnvelopePlanRead.Usable &&
                            read.plan.planFingerprint == fresh.planFingerprint
                    }
                if (!reuse) {
                    val manifestForPublish = store.artifactManifest ?: return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PERSISTENCE_REJECTED,
                        reason = "T924 envelope plan deferred: manifest unavailable",
                    )
                    when (
                        val publication = EnvelopePlanPublication.publish(
                            store = store,
                            manifest = manifestForPublish,
                            plan = fresh,
                            nowEpochMs = nowEpochMs(),
                        )
                    ) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed ->
                            store.artifactManifest = publication.manifest
                        is ChapterArtifactEngine.TransactionOutcome.Rejected ->
                            // Prior manifest stays authoritative (SC-20/22).
                            return BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.PERSISTENCE_REJECTED,
                                reason = "T924 envelope plan publication rejected: ${publication.reason}",
                            )
                    }
                }
                //   the plan is durable again (published, or an
                // identical fingerprint was reused) — end the rebuild window.
                listener.envelopePlanCommitted()

                // ----  TRANSLATE. ----
                if (translator == null) {
                    publishRecord(
                        artifact,
                        envelopeRecord(
                            ChapterRunState.TRANSLATE,
                            envelopeCounters(
                                mapOf(
                                    ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_TOTAL to fresh.envelopes.size,
                                    ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_DONE to 0,
                                    ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_TRANSPORT to 1,
                                    ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                                ),
                            ),
                        ),
                    )
                    return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PAUSED,
                        completedPageKeys = allPageKeys,
                        reason = ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON,
                    )
                }

                publishRecord(
                    artifact,
                    envelopeRecord(
                        ChapterRunState.TRANSLATE,
                        envelopeCounters(
                            mapOf(
                                ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_TOTAL to fresh.envelopes.size,
                                ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_DONE to 0,
                                ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_PENDING to fresh.envelopes.size,
                            ),
                        ),
                    ),
                )

                val work = build.work
                // When the overlap scheduler is present, every provider
                // envelope dispatch opens a remote window that
                // drives serial inpaint of committed pages. Admission
                // semantics are unchanged — the wrapper delegates to the SAME
                // process-wide sub-limit gate.
                val dispatchGate = overlapScheduler?.let { scheduler ->
                    OverlapScheduler.WindowSignallingGate(translationSublimitGate, scheduler)
                } ?: translationSublimitGate
                //  publish the persisted layout right after each inpaint
                // commits (per page, never blocking the envelope loop — the
                // hook runs inside the overlap scheduler's coroutine).
                overlapScheduler?.onInpaintCommitted = { pageKey ->
                    renderJoin?.publishPersistedLayoutForCompletedPage(pageKey)
                    Unit
                }
                val executor = ProfileEnvelopeExecutor(
                    store = store,
                    textTranslator = translator,
                    replan = { reason ->
                        rebuildDispatchWork(artifact, orderedPages, corpusFingerprint, reason)
                    },
                    sublimitGate = dispatchGate,
                    providerProfile = providerChunkProfile(),
                    nowEpochMs = nowEpochMs,
                    //  track V: freed write slots wake the overlap lane at
                    // the commit settle — deferred inpaint candidates no longer
                    // wait a whole envelope cycle for the next window's open.
                    onCommitSettled = { overlapScheduler?.notifyCandidatesChanged() },
                )
                val overlapLoop: suspend (suspend () -> ProfileEnvelopeExecutor.PhaseOutcome) -> ProfileEnvelopeExecutor.PhaseOutcome =
                    { runPhase ->
                        if (overlapScheduler == null) {
                            runPhase()
                        } else {
                            kotlinx.coroutines.coroutineScope {
                                val loop = launch { overlapScheduler.runOverlapLoop() }
                                val outcome = runPhase()
                                // No further windows: stop the loop between pages
                                // (a running inpaint finishes through the lane).
                                overlapScheduler.stopOverlap()
                                loop.join()
                                outcome
                            }
                        }
                    }
                return overlapLoop { executor.run(work) }.let { outcome ->
                    when (outcome) {
                        is ProfileEnvelopeExecutor.PhaseOutcome.Drained -> {
                            publishRecord(
                                artifact,
                                envelopeRecord(
                                    ChapterRunState.TRANSLATE,
                                    envelopeCounters(
                                        outcome.counters.toMap() + mapOf(ChapterProfileBatchCoordinator.COUNTER_STOP to 1),
                                    ),
                                ),
                            )
                            // Translation has drained; finalization completes the run and
                            // publishes its single COMPLETE.
                            runFinalizeAndComplete(
                                artifact = artifact,
                                runId = runId,
                                orderedPages = orderedPages,
                                corpusFingerprint = corpusFingerprint,
                                baseCounters = envelopeCounters(
                                    outcome.counters.toMap() + mapOf(ChapterProfileBatchCoordinator.COUNTER_STOP to 1),
                                ),
                            )
                        }
                        is ProfileEnvelopeExecutor.PhaseOutcome.Paused -> {
                            overlapScheduler?.stopOverlap()
                            publishRecord(
                                artifact,
                                envelopeRecord(
                                    ChapterRunState.TRANSLATE,
                                    envelopeCounters(
                                        outcome.counters.toMap() +
                                            mapOf(
                                                ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                                                ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_PENDING to outcome.counters.envelopesPending,
                                            ),
                                    ),
                                ),
                            )
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 translate paused: ${outcome.reason}"
                            }
                            BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.PAUSED,
                                anchorPageKey = outcome.anchorPageKey,
                                failure = outcome.failure,
                                nextEligibleRetryAtEpochMs = outcome.nextEligibleRetryAtEpochMs,
                                reason = outcome.reason,
                            )
                        }
                    }
                }
            }
        }
    }
}
