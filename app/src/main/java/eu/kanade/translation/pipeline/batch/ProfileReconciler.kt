package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.storage.CheckpointOcrResult
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.context.SeriesProfileRegistry
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.StagePatchResult
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
import eu.kanade.translation.pipeline.ocrBlockFingerprints
import eu.kanade.translation.pipeline.ocrFingerprint
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

internal class ProfileReconcilerContext(
    val store: ChapterTranslationStore,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val glossarySynthesizer: GlossarySynthesizer?,
    val seriesKey: String?,
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
    val profileInputFingerprintOf: (String) -> String,
    val runEnvelopePlanAndTranslate: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
)

internal class ProfileReconciler(
    private val context: ProfileReconcilerContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val frozenConfig: RunConfigSnapshot
        get() = context.frozenConfig
    private val effectiveSourcePairs: List<Pair<String, String>>
        get() = context.effectiveSourcePairs
    private val glossarySynthesizer: GlossarySynthesizer?
        get() = context.glossarySynthesizer
    private val seriesKey: String?
        get() = context.seriesKey
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

    private fun profileInputFingerprintOf(corpusFingerprint: String): String =
        context.profileInputFingerprintOf(corpusFingerprint)

    private suspend fun runEnvelopePlanAndTranslate(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = context.runEnvelopePlanAndTranslate(
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
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)

        fun freezeCounters(extra: Map<String, Int>): Map<String, Int> = baseCounters + extra

        // ST-09 entry: re-read the durable chunk list from the manifest.
        val manifestAtEntry = store.artifactManifest
        if (manifestAtEntry == null || manifestAtEntry.analysisChunks.isEmpty()) {
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PERSISTENCE_REJECTED,
                reason = "T924 profile reconcile deferred: durable chunk list unavailable",
            )
        }
        val chunks = mutableListOf<AnalysisChunkResult>()
        for (index in manifestAtEntry.analysisChunks.indices) {
            val read = store.withArtifactEngineLocked { artifact ->
                artifact.readSidecarDocument(
                    pointer = manifestAtEntry.analysisChunks[index],
                    serializer = AnalysisChunkResult.serializer(),
                    currentSchemaVersion = AnalysisChunkResult.SCHEMA_VERSION,
                    expectedKind = AnalysisChunkResult.KIND,
                    schemaVersionOf = { it.schemaVersion },
                    kindOf = { it.kind },
                    isValid = { it.isSemanticallyValid },
                )
            } ?: SidecarRead.Absent
            if (read !is SidecarRead.Usable) {
                // ST-30: unreadable/invalid target = absent, never partially
                // trusted. Resume re-validates the prefix (typed pause there).
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    reason = "T924 profile reconcile deferred: persisted chunk $index " +
                        "unreadable or invalid ($read)",
                )
            }
            chunks += read.document
        }

        // PROFILE_RECONCILE phase record (entered).
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.PROFILE_RECONCILE,
                frozenFingerprint,
                sourceDigest,
                freezeCounters(
                    mapOf(
                        ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                        ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_RECONCILED to 0,
                        ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_PENDING to 0,
                    ),
                ),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        // Director decision (summary-glossary redesign): the profile's
        // content is ONE small identity sheet synthesized from the durable
        // chunk summaries — characters and places only, capped hard, because
        // anything beyond identity anchors is noise for the translation
        // envelopes. The deterministic cross-chunk reconcile of structured
        // extraction records is retired with the strict response contract.
        val synthesis = when (val source = synthesizeProfileContent(chunks)) {
            is ProfileContentSource.Content -> source
            is ProfileContentSource.Pause -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 glossary synthesis paused: ${source.reason}"
                }
                publishRecord(
                    artifact,
                    record(
                        runId,
                        ChapterRunState.PROFILE_RECONCILE,
                        frozenFingerprint,
                        sourceDigest,
                        freezeCounters(mapOf(ChapterProfileBatchCoordinator.COUNTER_PROFILE_RECONCILE_REJECTED to 1)),
                        ocrCorpusFingerprint = corpusFingerprint,
                    ),
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    failure = source.failure,
                    reason = source.reason,
                )
            }
        }

        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.PROFILE_RECONCILE,
                frozenFingerprint,
                sourceDigest,
                freezeCounters(
                    mapOf(
                        ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                        ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_RECONCILED to synthesis.summarizedChunks,
                        ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_PENDING to 0,
                    ),
                ),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        // Assemble the DTO: operational envelope + the FP-05 content hash
        // (computed over the DTO with the operational fields zeroed, so the
        // hash is computed BEFORE the field is set — FP-05 cannot contain
        // itself). The input identity is the SAME FP-04 the reuse probe uses.
        val inputFingerprint = profileInputFingerprintOf(corpusFingerprint)
        val nextVersion = (store.artifactManifest?.profile?.version ?: 0) + 1
        val draft = ChapterTranslationProfile(
            version = nextVersion,
            contentFingerprint = "",
            profileInputFingerprint = inputFingerprint,
            sourceRunId = runId,
            analyzerProvenance = synthesis.analyzerProvenance,
            entities = synthesis.entities,
            terms = synthesis.terms,
            scenes = emptyList(),
            unresolvedFacts = emptyList(),
            seriesUpdateCandidates = emptyList(),
            correctionCandidates = emptyList(),
            frozenAtEpochMs = nowEpochMs(),
        )
        val profile = draft.copy(
            contentFingerprint = StageFingerprints.profileContentFingerprint(draft),
        )

        val manifestForPublish = store.artifactManifest ?: return BatchPass1Outcome(
            needsTranslation = emptyList(),
            status = BatchPass1Status.PERSISTENCE_REJECTED,
            reason = "T924 profile freeze deferred: manifest unavailable",
        )
        return when (
            val publication = ProfileFreezePublication.publish(
                store = store,
                manifest = manifestForPublish,
                profile = profile,
                nowEpochMs = nowEpochMs(),
            )
        ) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                store.artifactManifest = publication.manifest
                seriesKey?.let { key ->
                    SeriesProfileRegistry.register(
                        seriesKey = key,
                        profile = profile,
                        sourceLang = frozenConfig.sourceLang,
                        targetLang = frozenConfig.targetLang,
                        providerKey = frozenConfig.providerKey,
                        nowEpochMs = nowEpochMs(),
                    )
                }
                publishRecord(
                    artifact,
                    record(
                        runId,
                        ChapterRunState.PROFILE_FROZEN,
                        frozenFingerprint,
                        sourceDigest,
                        freezeCounters(
                            mapOf(
                                ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                                ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_RECONCILED to synthesis.summarizedChunks,
                                ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_PENDING to 0,
                                ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN to 1,
                                ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                            ),
                        ),
                        ocrCorpusFingerprint = corpusFingerprint,
                        profilePointer = publication.manifest.profile,
                    ),
                )
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 profile frozen version=$nextVersion " +
                        "synthesized=${synthesis.summarizedChunks} entries=${synthesis.entities.size + synthesis.terms.size}"
                }
                // Stage-6 slice A: PROFILE_FROZEN no longer terminates the
                // run — the coordinator CONTINUES into ENVELOPE_PLAN (ST-11)
                // and TRANSLATE (ST-12). The terminal stays PAUSED (native /
                // render are Stage 7; NEVER COMPLETE in this slice).
                return runEnvelopePlanAndTranslate(
                    artifact = artifact,
                    runId = runId,
                    orderedPages = orderedPages,
                    corpusFingerprint = corpusFingerprint,
                    baseCounters = freezeCounters(
                        mapOf(
                            ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                            ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_RECONCILED to synthesis.summarizedChunks,
                            ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_PENDING to 0,
                            ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN to 1,
                        ),
                    ),
                )
            }
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                // Prior manifest authoritative (TX-22); the run pauses; the
                // next attempt re-reconciles deterministically (ST-09 resume).
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 profile freeze rejected: ${publication.reason}"
                }
                publishRecord(
                    artifact,
                    record(
                        runId,
                        ChapterRunState.PROFILE_RECONCILE,
                        frozenFingerprint,
                        sourceDigest,
                        freezeCounters(mapOf(ChapterProfileBatchCoordinator.COUNTER_PROFILE_FREEZE_REJECTED to 1)),
                        ocrCorpusFingerprint = corpusFingerprint,
                    ),
                )
                BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PERSISTENCE_REJECTED,
                    reason = "T924 profile freeze rejected: ${publication.reason}",
                )
            }
        }
    }

    /** [synthesizeProfileContent] outcome: freezeable content or a typed pause. */
    private sealed interface ProfileContentSource {
        data class Content(
            val analyzerProvenance: AnalyzerProvenance,
            val entities: List<ProfileFact>,
            val terms: List<ProfileFact>,
            val summarizedChunks: Int,
        ) : ProfileContentSource

        data class Pause(val failure: ProviderFailure?, val reason: String) : ProfileContentSource
    }

    /**
     * ONE synthesis call over the durable chunk summaries builds the
     * profile's identity sheet. Characters become ENTITY_IDENTITY facts,
     * places become TERM facts; every fact is chapter-wide and deliberately
     * WEAK-strength — the summary path carries no per-block evidence, and
     * the sheet's job is consistent renderings, not auditable provenance.
     * An empty or missing glossary is still freezeable content (translation
     * proceeds without the sheet).
     */
    private suspend fun synthesizeProfileContent(
        chunks: List<AnalysisChunkResult>,
    ): ProfileContentSource {
        val synthesizer = glossarySynthesizer
            ?: return ProfileContentSource.Pause(
                failure = null,
                reason = ChapterProfileBatchCoordinator.GLOSSARY_SYNTHESIS_NO_TRANSPORT_REASON,
            )
        val summaries = chunks.mapNotNull { chunk ->
            chunk.narrativeSummary?.takeIf(String::isNotBlank)
        }
        return when (
            val outcome = synthesizer.synthesize(
                sourceLanguage = frozenConfig.sourceLang,
                targetLanguage = frozenConfig.targetLang,
                summaries = summaries,
            )
        ) {
            is GlossarySynthesisOutcome.Glossary -> {
                var entityOrdinal = 0
                var termOrdinal = 0
                val entities = outcome.entries
                    .filter { it.kind == GlossaryEntryKind.CHARACTER }
                    .map { entry ->
                        ProfileFact(
                            factId = "e%03d".format(++entityOrdinal),
                            type = FactType.ENTITY_IDENTITY,
                            canonicalSourceForm = entry.source,
                            canonicalTargetForm = entry.target,
                            aliases = entry.aliases,
                            evidenceStrength = EvidenceStrength.WEAK,
                            scope = FactScope.CANONICAL_CHAPTER_WIDE,
                            provenance = FactProvenance.CHAPTER_ANALYSIS,
                            conflictState = FactConflictState.RESOLVED,
                        )
                    }
                val terms = outcome.entries
                    .filter { it.kind == GlossaryEntryKind.PLACE }
                    .map { entry ->
                        ProfileFact(
                            factId = "t%03d".format(++termOrdinal),
                            type = FactType.TERM,
                            canonicalSourceForm = entry.source,
                            canonicalTargetForm = entry.target,
                            aliases = entry.aliases,
                            evidenceStrength = EvidenceStrength.WEAK,
                            scope = FactScope.CANONICAL_CHAPTER_WIDE,
                            provenance = FactProvenance.CHAPTER_ANALYSIS,
                            conflictState = FactConflictState.RESOLVED,
                        )
                    }
                ProfileContentSource.Content(
                    analyzerProvenance = chunks.last().analyzerProvenance,
                    entities = entities,
                    terms = terms,
                    summarizedChunks = summaries.size,
                )
            }
            is GlossarySynthesisOutcome.Paused -> ProfileContentSource.Pause(
                failure = outcome.failure,
                reason = "T924 glossary synthesis paused: ${outcome.reason}",
            )
        }
    }

}
