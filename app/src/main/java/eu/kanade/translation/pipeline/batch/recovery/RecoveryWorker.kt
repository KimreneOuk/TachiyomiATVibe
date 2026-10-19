package eu.kanade.translation.pipeline.batch.recovery

import eu.kanade.translation.engines.translator.TranslationOutputSemantics
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCommittedDisplay
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.ArtifactStage
import eu.kanade.translation.persistence.ArtifactStageStatus
import eu.kanade.translation.persistence.ChapterArtifactEngine
import eu.kanade.translation.persistence.ChapterArtifactManifest
import eu.kanade.translation.persistence.ChapterRunRecord
import eu.kanade.translation.persistence.ChapterRunState
import eu.kanade.translation.persistence.DurableFailureMetadata
import eu.kanade.translation.persistence.FailureCategory
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.BatchPageTraceRegistry
import eu.kanade.translation.pipeline.batch.BatchPass1Outcome
import eu.kanade.translation.pipeline.batch.BatchPass1Status
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import eu.kanade.translation.pipeline.batch.PageKey
import eu.kanade.translation.util.ShortHash
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class RecoveryWorkerContext(
    val store: ChapterTranslationStore,
    val nowEpochMs: () -> Long,
    val drainFinalize: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
    val pageTraceRegistry: BatchPageTraceRegistry? = null,
)

internal class RecoveryWorker(
    private val context: RecoveryWorkerContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val nowEpochMs: () -> Long
        get() = context.nowEpochMs

    private suspend fun drainFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = context.drainFinalize(
        artifact,
        runId,
        orderedPages,
        corpusFingerprint,
        baseCounters,
    )

    suspend fun resumePhase(
        artifact: ChapterArtifactEngine,
        priorRecord: ChapterRunRecord?,
        frozenFingerprint: String,
        sourceDigest: String,
        orderedPages: List<PageKey>,
    ): BatchPass1Outcome? {
        val resumable = priorRecord
            ?.takeIf {
                (it.state == ChapterRunState.FINALIZE || it.state == ChapterRunState.COMPLETE) &&
                    it.frozenRunConfigFingerprint == frozenFingerprint &&
                    it.orderedSourceDigest == sourceDigest
            }
            ?: return null
        if (resumable.state == ChapterRunState.COMPLETE) {
            //   (the mirror of the flag-OFF F-4 gate in
            // deleted shell-level F-4 gate): a recorded
            // COMPLETE is NOT enough to retire the chapter — the zero-work
            // finished outcome is authorized only when every ordered page's
            // translated result is still durably addressable. The flagged lane
            // keeps the translated page snapshot under the page record's
            // committed bundle or (before reader adoption) its candidate
            // pointer; a user reset overwrites the candidate snapshot with the
            // cleared PENDING page and demotes committed displays without
            // (previously) retiring the run record, so pointer PRESENCE alone
            // is not evidence — the snapshot CONTENT must still be a
            // translation-terminal page. Any page whose record and snapshot
            // carry no readable translated/textless result must behave the
            // same way: instead of short-circuiting, the run STARTS FRESH
            // (-style supersession — the normal run-start path publishes
            // a new RUN_SNAPSHOT and re-derives the missing page state).
            // Evidence is judged against the DURABLE manifest (same read the
            // pointer above came from), never the facade's mutable cache.
            val durableManifest = store.withArtifactEngineLocked { artifact ->
                artifact.readManifest()
            }
            val allPagesWorkProductEvidenced = orderedPages.all { (pageKey, _) ->
                pageWorkProductResolvable(artifact, pageKey, durableManifest)
            }
            if (!allPagesWorkProductEvidenced) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 resume: recorded COMPLETE lacks display evidence; " +
                        "superseding with a fresh run"
                }
                return null
            }
            logcat(LogPriority.INFO) {
                "TachiyomiAT t924 resume: recorded run already COMPLETE; finished without re-running work"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.COMPLETED,
                completedPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first },
                reason = ChapterProfileBatchCoordinator.RESUME_COMPLETE_REASON,
            )
        }
        // A FINALIZE record always carries the run's corpus fingerprint (both
        // drain entry paths publish it); a record without one is not this
        // coordinator's shape — fall through to the normal start.
        val corpusFingerprint = resumable.ocrCorpusFingerprint ?: return null
        logcat(LogPriority.INFO) {
            "TachiyomiAT t924 resume: FINALIZE record durable; re-running the idempotent finalize drain (ST-14)"
        }
        return drainFinalizeAndComplete(
            artifact = artifact,
            runId = resumable.runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = resumable.phaseCounters,
        )
    }

    /**
     *   helper for the COMPLETE resume gate: true when [pageKey]'s
     * translated result under the recorded COMPLETE run is still durably
     * addressable — a committed bundle, a committed/textless display state, or
     * a candidate snapshot whose CONTENT is still a translation-terminal page
     * (a user reset persists the cleared PENDING page OVER that snapshot
     * without clearing the pointer, so pointer presence alone is not
     * evidence). Falls back to the live store's durable textless terminal for
     * pages with no readable record sidecar.
     */
    suspend fun pageWorkProductResolvable(
        artifact: ChapterArtifactEngine,
        pageKey: String,
        durableManifest: ChapterArtifactManifest?,
    ): Boolean {
        // The durable no-text terminal (the legacy worker's textless commit
        // AND the OCR-side finalizePostOcrStage both set it): a committed
        // snapshot carrying no translatable text is exactly as durable as a
        // translated one (the standard lane's textless evidence shape).
        fun isNoTextTerminal(page: PageTranslationView): Boolean =
            page.isTextlessTerminal || page.translationStatus == StageStatus.SKIPPED
        val liveTextless = store.state.value[pageKey]?.let(::isNoTextTerminal) == true
        val record = durableManifest?.pages?.get(pageKey) ?: return liveTextless
        if (record.committed != null) return true
        if (record.displayState.hasCommittedDisplay ||
            record.displayState == PageDisplayState.TEXTLESS_COMPLETE
        ) {
            return true
        }
        val snapshotFile = record.candidate?.pageSnapshotFileName
        if (snapshotFile != null) {
            val snapshot = store.withArtifactEngineLocked { artifact ->
                artifact.readPageSnapshot(snapshotFile)
            }
            if (snapshot != null &&
                (
                    snapshot.isTextlessTerminal ||
                        snapshot.hasRecognizedTranslation ||
                        isNoTextTerminal(snapshot)
                    )
            ) {
                return true
            }
        }
        return liveTextless
    }

    fun t924PageTerminalAtFinalize(page: PageTranslationView?, activeGeneration: Long): Boolean {
        if (page == null) return false
        if (page.isTextlessTerminal) return true
        //  : a COMMITTED terminal stage is durable
        // regardless of which generation wrote it — a restart after a cancel
        // or process death must never strand (and durable-fail) a prior
        // run's committed work (/ reuse). Only OPEN states
        // (PENDING/RUNNING/CANCELLED, below) are generation-owned: a page
        // still mid-write from a dead run is genuinely stranded.
        when (page.translationStatus) {
            StageStatus.READY,
            StageStatus.PARTIAL,
            StageStatus.FAILED,
            StageStatus.SKIPPED,
            StageStatus.TEXTLESS,
            -> return true
            else -> {}
        }
        if (page.runGeneration != activeGeneration) return false
        return page.blocks.isNotEmpty() && page.blocks.all { it.userEditedAt != null }
    }

    fun strandedPageReason(page: PageTranslationView?): String {
        if (page == null) return "expected page is missing from the store"
        val frozenConfig = store.readActiveRunRecord()?.frozenConfig
        val textBlocks = page.blocks.filter { it.text.isNotBlank() }
        val resolved = textBlocks.count { block ->
            block.userEditedAt != null ||
                TranslationOutputSemantics.isResolved(
                    source = block.text,
                    output = block.translation,
                    sourceLanguageCode = frozenConfig?.sourceLang,
                    targetLanguageCode = frozenConfig?.targetLang,
                )
        }
        return when {
            textBlocks.isEmpty() ->
                "no translatable text (status=${page.translationStatus}, blocks=${page.blocks.size})"
            resolved == textBlocks.size ->
                "all ${textBlocks.size} text blocks already translated or user-edited " +
                    "(status=${page.translationStatus})"
            else ->
                "translation left non-terminal (status=${page.translationStatus}, " +
                    "textBlocks=${textBlocks.size}, unfinished=${textBlocks.size - resolved})"
        }
    }

    /**
     *  terminal: a page that cannot fit any legal envelope takes a
     * durable structural failure (SOURCE category — the page content, not
     * the transport, cannot fit the policy) so later runs do not re-plan it
     * silently. Best-effort: a rejected record keeps the typed pause.
     * The [carrier] names the sweep that failed the page (planner rejection
     * vs the FINALIZE stranded sweep) so the recorded reason is accurate.
     */
    suspend fun persistEnvelopeStructuralFailure(
        pageKey: String,
        reason: String,
        carrier: String = "envelope planner rejected the page",
    ) {
        try {
            val snapshot = store.snapshot(pageKey)
            val metadata = DurableFailureMetadata(
                pageKey = pageKey,
                stage = ArtifactStage.TRANSLATION,
                status = ArtifactStageStatus.FAILED_RETRYABLE,
                category = FailureCategory.SOURCE,
                retryCount = 1,
                lastFailureMessage = "$carrier: $reason",
                lastFailedAtEpochMs = nowEpochMs(),
                nextEligibleRetryAtEpochMs = null,
            )
            store.persistDurableStageFailure(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = snapshot.generation,
                    pageVersion = snapshot.pageVersion,
                    leaseToken = snapshot.leaseToken,
                ),
                failure = metadata,
                description = "t924 envelope structural failure",
            ) { current ->
                (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                    sourceFileName = pageKey
                    translationStatus = StageStatus.FAILED
                    errorMessage = metadata.lastFailureMessage
                    updatedAt = nowEpochMs()
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 envelope structural failure record rejected pageHash=" +
                    "${ShortHash.hash(pageKey)} error=${e::class.java.simpleName}"
            }
        }
    }
}
