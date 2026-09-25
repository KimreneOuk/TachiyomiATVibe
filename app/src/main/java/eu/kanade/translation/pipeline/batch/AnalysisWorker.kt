package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.ProfilePointer
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanResult
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanner
import eu.kanade.translation.translator.contextual.AnalysisChunkPolicy
import eu.kanade.translation.translator.contextual.ChunkPlannerPage
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class AnalysisWorkerContext(
    val store: ChapterTranslationStore,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val analysisChunkRunner: AnalysisChunkRunner?,
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
    val corpusEntriesFromCheckpoints: suspend (
        ChapterArtifactEngine,
        List<PageKey>,
        Int,
    ) -> ChapterProfileBatchCoordinator.AnalysisCorpus?,
    val validatePersistedPrefix: suspend (
        ChapterArtifactEngine,
        List<PlannedAnalysisChunk>,
    ) -> String?,
    val runProfileReconcileAndFreeze: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
)

internal class AnalysisWorker(
    private val context: AnalysisWorkerContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val frozenConfig: RunConfigSnapshot
        get() = context.frozenConfig
    private val effectiveSourcePairs: List<Pair<String, String>>
        get() = context.effectiveSourcePairs
    private val analysisChunkRunner: AnalysisChunkRunner?
        get() = context.analysisChunkRunner
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

    private suspend fun corpusEntriesFromCheckpoints(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        expectedPageCount: Int,
    ): ChapterProfileBatchCoordinator.AnalysisCorpus? =
        context.corpusEntriesFromCheckpoints(artifact, orderedPages, expectedPageCount)

    private suspend fun validatePersistedPrefix(
        artifact: ChapterArtifactEngine,
        plannedChunks: List<PlannedAnalysisChunk>,
    ): String? = context.validatePersistedPrefix(artifact, plannedChunks)

    private suspend fun runProfileReconcileAndFreeze(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = context.runProfileReconcileAndFreeze(
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

    private fun policyFingerprint(tag: String, vararg values: Int): String =
        ChapterProfileBatchCoordinator.policyFingerprint(tag, *values)

    suspend fun runPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val total = orderedPages.size
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)

        fun analysisCounters(extra: Map<String, Int>): Map<String, Int> =
            baseCounters + mapOf(ChapterProfileBatchCoordinator.COUNTER_ANALYSIS_PLAN to 1) + extra

        //  phase transition only — the corpus manifest is recomputed
        // from the checkpoints below (pure planner output, never stale).
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.ANALYSIS_PLAN,
                frozenFingerprint,
                sourceDigest,
                analysisCounters(mapOf(ChapterProfileBatchCoordinator.COUNTER_ANALYSIS_PLAN to 1)),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        // Rebuild the corpus entries from the DURABLE checkpoints (never from
        // the in-memory pass): idempotent against the  postcondition.
        val corpus = corpusEntriesFromCheckpoints(artifact, orderedPages, total)
        if (corpus == null) {
            // A checkpoint vanished between the preflight barrier and here
            // (concurrent invalidation): stop; resume re-plans honestly.
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first },
                reason = "T924 analysis plan deferred: corpus checkpoints changed under the run",
            )
        }
        if (corpus.corpusFingerprint != corpusFingerprint) {
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first },
                reason = "T924 analysis plan deferred: corpus fingerprint drifted between preflight and plan",
            )
        }

        val chunkPages = corpus.entries.map { entry ->
            ChunkPlannerPage(
                pageKey = entry.storagePageKey,
                naturalPageIndex = entry.naturalPageIndex,
                contentFingerprint = entry.contentFingerprint,
                blockIds = entry.wireBlockIds,
                estimatedInputTokens = entry.estimatedInputTokens,
            )
        }
        val policy = AnalysisChunkPolicy(
            overlapPages = frozenConfig.analysisPolicy.overlapPages
                .coerceIn(0, AnalysisChunkResult.MAX_OVERLAP_PAGES),
        )
        val plan = when (val planned = AnalysisChunkPlanner.plan(chunkPages, policy)) {
            is AnalysisChunkPlanResult.Success -> planned
            is AnalysisChunkPlanResult.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 analysis plan rejected: ${planned.reason}"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.FAILED,
                    reason = "T924 analysis plan rejected: ${planned.reason}",
                )
            }
        }

        // Wave-4 F-W4-1: the persisted pointer prefix is resume-authoritative
        // ONLY when it came from THIS plan. A cross-run corpus change
        // (re-download → new checkpoints → re-planned windows) must never be
        // silently skipped into a mixed-plan chunk list — mismatch is a typed
        // PAUSED, exactly like the within-run drift gate above.
        validatePersistedPrefix(artifact, plan.chunks)?.let { staleReason ->
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpus.entries.mapTo(mutableSetOf()) { it.storagePageKey },
                reason = staleReason,
            )
        }

        // Skip rules (provider-analysis contract §5): no chunkable work
        // (textless / no translatable blocks) skips provider analysis.
        if (plan.chunks.isEmpty()) {
            publishRecord(
                artifact,
                record(
                    runId,
                    ChapterRunState.ANALYSIS_CHUNKS,
                    frozenFingerprint,
                    sourceDigest,
                    analysisCounters(
                        mapOf(
                            ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL to 0,
                            ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE to 0,
                            ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_WORK to 1,
                        ),
                    ),
                    ocrCorpusFingerprint = corpusFingerprint,
                ),
            )
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpus.entries.mapTo(mutableSetOf()) { it.storagePageKey },
                reason = ChapterProfileBatchCoordinator.ANALYSIS_NO_WORK_REASON,
            )
        }

        val runner = analysisChunkRunner
        if (runner == null) {
            // Slice-A shell: no typed analysis transport is wired yet. A
            // missing transport is a typed CONFIGURATION-class gate failure
            // (provider-analysis contract §2.1) — never garbage chunks.
            publishRecord(
                artifact,
                record(
                    runId,
                    ChapterRunState.ANALYSIS_CHUNKS,
                    frozenFingerprint,
                    sourceDigest,
                    analysisCounters(
                        mapOf(
                            ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                            ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE to 0,
                            ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_TRANSPORT to 1,
                        ),
                    ),
                    ocrCorpusFingerprint = corpusFingerprint,
                ),
            )
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpus.entries.mapTo(mutableSetOf()) { it.storagePageKey },
                reason = ChapterProfileBatchCoordinator.ANALYSIS_NO_TRANSPORT_REASON,
            )
        }

        //  resume rule: the persisted pointer prefix (chunk-ordinal
        // order, identity-validated above) is never re-sent; execution
        // restarts at the first missing chunk.
        val manifestNow = store.artifactManifest
        val persistedPrefix = manifestNow?.analysisChunks?.size ?: 0

        val identity = AnalysisRunIdentity(
            runId = runId,
            mangaKeyHash = AnalysisRunIdentity.SCOPE_ABSENT,
            chapterKeyHash = "sha256:$sourceDigest",
            sourceLanguage = frozenConfig.sourceLang,
            targetLanguage = frozenConfig.targetLang,
            analysisPolicyFingerprint = policyFingerprint(
                "analysis-policy-v1",
                frozenConfig.analysisPolicy.overlapPages,
            ),
            ocrCorpusFingerprint = corpusFingerprint,
            maxOutputTokens = ChapterProfileBatchCoordinator.ANALYSIS_MAX_OUTPUT_TOKENS,
        )

        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.ANALYSIS_CHUNKS,
                frozenFingerprint,
                sourceDigest,
                analysisCounters(
                    mapOf(
                        ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                        ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE to persistedPrefix,
                        ChapterProfileBatchCoordinator.COUNTER_CHUNKS_PENDING to 0,
                    ),
                ),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        var done = persistedPrefix
        var pending = 0
        for (chunk in plan.chunks) {
            currentCoroutineContext().ensureActive()
            if (chunk.chunkOrdinal < persistedPrefix) continue
            yield() // reader-priority courtesy between paid provider calls
            val evidence = corpus.evidenceTextsFor(chunk)
            when (val outcome = runner.executeChunk(chunk, identity, evidence)) {
                is AnalysisChunkRunOutcome.Completed -> {
                    val input = corpus.publicationInput(chunk, outcome)
                        ?: return BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PAUSED,
                            reason = "T924 analysis chunk ${chunk.chunkId} deferred: " +
                                "contributing OCR snapshots no longer readable",
                        )
                    val result = AnalysisChunkPublication.buildResult(chunk, input, nowEpochMs())
                    val publication = AnalysisChunkPublication.publish(
                        store = store,
                        manifest = store.artifactManifest ?: return BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PERSISTENCE_REJECTED,
                            reason = "T924 analysis chunk publication: manifest unavailable",
                        ),
                        result = result,
                        nowEpochMs = nowEpochMs(),
                    )
                    when (publication) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                            store.artifactManifest = publication.manifest
                            done++
                            if (outcome.coverage.kind == AnalysisCoverageKind.MISSING_ONLY) {
                                // DR-A Option 1: the independently complete
                                // subset committed; the remainder is pending
                                // and never blocks the chapter.
                                pending++
                            }
                            publishRecord(
                                artifact,
                                record(
                                    runId,
                                    ChapterRunState.ANALYSIS_CHUNKS,
                                    frozenFingerprint,
                                    sourceDigest,
                                    analysisCounters(
                                        mapOf(
                                            ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                                            ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE to done,
                                            ChapterProfileBatchCoordinator.COUNTER_CHUNKS_PENDING to pending,
                                        ),
                                    ),
                                    ocrCorpusFingerprint = corpusFingerprint,
                                ),
                            )
                        }
                        is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                            // Prior manifest stays authoritative; the chunk
                            // stays unpersisted; resume re-executes it.
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 analysis chunk publication rejected " +
                                    "chunk=${chunk.chunkId}: ${publication.reason}"
                            }
                            return BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.PERSISTENCE_REJECTED,
                                anchorPageKey = chunk.corePageKeys.firstOrNull(),
                                reason = "T924 analysis chunk publication rejected: ${publication.reason}",
                            )
                        }
                    }
                }
                is AnalysisChunkRunOutcome.Paused -> {
                    publishRecord(
                        artifact,
                        record(
                            runId,
                            ChapterRunState.ANALYSIS_CHUNKS,
                            frozenFingerprint,
                            sourceDigest,
                            analysisCounters(
                                mapOf(
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE to done,
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_PENDING to pending,
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_FAILURES to 1,
                                ),
                            ),
                            ocrCorpusFingerprint = corpusFingerprint,
                        ),
                    )
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 analysis paused at chunk=${chunk.chunkId}: ${outcome.reason}"
                    }
                    return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PAUSED,
                        anchorPageKey = chunk.corePageKeys.firstOrNull(),
                        failure = outcome.failure,
                        nextEligibleRetryAtEpochMs = outcome.failure.retryAfterAtEpochMs,
                        reason = outcome.reason,
                    )
                }
                is AnalysisChunkRunOutcome.Refused -> {
                    publishRecord(
                        artifact,
                        record(
                            runId,
                            ChapterRunState.ANALYSIS_CHUNKS,
                            frozenFingerprint,
                            sourceDigest,
                            analysisCounters(
                                mapOf(
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE to done,
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_PENDING to pending,
                                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_FAILURES to 1,
                                ),
                            ),
                            ocrCorpusFingerprint = corpusFingerprint,
                        ),
                    )
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 analysis refusal at chunk=${chunk.chunkId}: ${outcome.reason}"
                    }
                    return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PAUSED,
                        anchorPageKey = chunk.corePageKeys.firstOrNull(),
                        failure = outcome.failure,
                        reason = outcome.reason,
                    )
                }
            }
        }

        // ---- Stage-5 slice B: the validated chunk set is durable; run the ----
        // ----  reconcile over the DURABLE chunk list, then  freeze.
        return runProfileReconcileAndFreeze(
            artifact = artifact,
            runId = runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = analysisCounters(
                mapOf(
                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE to done,
                    ChapterProfileBatchCoordinator.COUNTER_CHUNKS_PENDING to pending,
                ),
            ),
        )
    }
}
