package eu.kanade.translation.pipeline.batch.recovery

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCommittedDisplay
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
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
        fun isNoTextTerminal(page: PageTranslation): Boolean =
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
                    snapshot.hasRenderedResult ||
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

    fun t924PageTerminalAtFinalize(page: PageTranslation?, activeGeneration: Long): Boolean {
        if (page == null) return false
        if (page.hasRenderedResult || page.isTextlessTerminal) return true
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

    suspend fun stampAdoptedRenderTerminal(pageKey: String) {
        stampRenderTerminalIfDisplayComplete(
            pageKey,
            "t924 resume: stamp adopted display-complete page render-terminal",
        )
    }

    /**
     *.4 deterministic suffix re-plan: rebuilds the pending work from
     * fresh store state with the SAME pure planner and publishes the
     * superseding plan (SC-20) before dispatch resumes. An unchanged
     * fingerprint reuses the published plan (identical content-addressed
     * bytes, idempotent). Never mutates committed history — committed pages
     * are simply no longer pending.
     */

    private sealed interface RenderStampOutcome {
        data object Committed : RenderStampOutcome
        data object PublicationRejected : RenderStampOutcome
        data class Blocked(val reason: String) : RenderStampOutcome
    }

    /**
     *  display-tail drain: stamps [pageKey] render-terminal when it
     * carries the full display evidence (translation READY/PARTIAL, inpaint
     * READY, cleaned image, a translated block) and renderStatus is still
     * PENDING — the exact durable shape of an order-inverted page whose
     * inpaint committed before its envelope translation (the in-lane stamp
     * only fires when translation was ALREADY terminal at inpaint time).
     * The guarded write carries hasRenderedResult, which fires the committed
     * display promotion ([ChapterTranslationStore.promoteDisplayIfReadyLocked]
     * via publishLocked) and flips the reader gate. Idempotent.
     */
    private suspend fun stampRenderTerminalIfDisplayComplete(
        pageKey: String,
        description: String,
    ): RenderStampOutcome {
        when (store.tryAcquirePageStageLease(pageKey, PageStage.Render, PageWriteOrigin.BATCH)) {
            is LeaseAcquisition.Granted -> Unit
            else -> return RenderStampOutcome.Blocked(
                "render-terminal stamp could not acquire the Render lease " +
                    "(owned by MANUAL/native; never preempted)",
            )
        }
        try {
            // The guarded write's lease fence is checked against the CURRENT
            // page lease (render-stage token just acquired), so the page is
            // snapshotted AFTER the acquire — the same idiom the overlap
            // scheduler's drain-side stamp uses (a pre-acquire snapshot would
            // fence on a null token and reject every free page).
            val held = store.snapshot(pageKey)
            val page = held.page
                ?: return RenderStampOutcome.Blocked("expected page is missing from the store")
            if (page.renderStatus == StageStatus.READY) return RenderStampOutcome.Committed
            val displayComplete = (page.translationStatus == StageStatus.READY || page.translationStatus == StageStatus.PARTIAL) &&
                page.inpaintStatus == StageStatus.READY &&
                page.cleanedImageName != null &&
                page.renderStatus == StageStatus.PENDING &&
                page.blocks.any { it.translation.isNotBlank() }
            if (!displayComplete) return RenderStampOutcome.Blocked(displayTailFailureReason(page))
            val expected = ChapterTranslationStore.PatchPrecondition(
                generation = held.generation,
                pageVersion = held.pageVersion,
                blockFingerprints = held.blockFingerprints,
                leaseToken = held.leaseToken,
                candidateGenerationId = held.candidateGenerationId,
                dependencyFingerprint = held.dependencyFingerprint,
                artifactPageVersion = held.artifactPageVersion,
            )
            val outcome = store.updatePageGuarded(
                pageKey = pageKey,
                expected = expected,
                description = description,
            ) { current ->
                (current ?: page).apply {
                    if (renderStatus == StageStatus.PENDING) {
                        renderStatus = StageStatus.READY
                        updatedAt = System.currentTimeMillis()
                    }
                }
            }
            if (outcome is ChapterTranslationStore.PatchResult.Rejected) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 render stamp rejected pageHash=${ShortHash.hash(pageKey)} reason=${outcome.reason}"
                }
                return if (outcome.reason == ChapterProfileBatchCoordinator.REJECTED_ARTIFACT_PUBLICATION) {
                    RenderStampOutcome.PublicationRejected
                } else {
                    RenderStampOutcome.Blocked("render-terminal write rejected: ${outcome.reason}")
                }
            }
            return RenderStampOutcome.Committed
        } finally {
            store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        }
    }

    internal class DisplayTailDrain(
        val drained: Int,
        val failed: List<String>,
    )

    /**
     * The exact durable shape of a display-tail page: translate+inpaint work
     * DONE (translation READY/PARTIAL, inpaint READY, cleaned image, a
     * translated block) with the display commit still missing (renderStatus
     * PENDING — the render-terminal stamp never landed). Textless terminals,
     * genuinely unfinished pages and already-stamped pages are NOT tail: they
     * belong to the stranded sweep / the healthy COMPLETE path.
     */
    private fun displayTailPending(page: PageTranslation?): Boolean =
        page != null &&
            page.renderStatus == StageStatus.PENDING &&
            (page.translationStatus == StageStatus.READY || page.translationStatus == StageStatus.PARTIAL) &&
            page.inpaintStatus == StageStatus.READY &&
            page.cleanedImageName != null &&
            page.blocks.any { it.translation.isNotBlank() }

    /**
     *  drains the display/compose tail before the run may publish
     * COMPLETE — every page whose translate+inpaint work is DONE but whose
     * display commit (render-terminal stamp → committed promotion) has not
     * landed. The overlap scheduler's drain-side sweep
     * ([OverlapScheduler.drainSerial] → stampRenderTerminalOrphans) heals the
     * tail in one pass; a lease denial or a rejected write silently skips a
     * page there, and nothing re-checks — that silent skip is the frozen-52
     * signature of the 2026-09-17 run (ready trailed cleaning all pass long,
     * then froze at COMPLETE).
     *
     * Bounded: at most [ChapterProfileBatchCoordinator.MAX_DISPLAY_TAIL_DRAIN_PASSES] passes over the
     * remaining tail, each recomputed from FRESH snapshots (a stale-write
     * rejection heals on the retry; a MANUAL Render owner does not). A page
     * still pending after the last pass takes the typed terminal
     * ([persistDisplayTailFailure]) — the run still completes, surfacing the
     * page in the pages-need-attention UI with its specific reason instead of
     * publishing a clean COMPLETE over a frozen ORIGINAL_ONLY page. A page
     * whose attempts failed ONLY with publication rejections is the exception:
     * it is work-complete and healthy, so it stays pending (invisible to the
     * stranded sweep — translation READY is terminal there) for the next run's
     * re-drain instead of taking a false typed failure. All progress is store
     * state — a run killed mid-drain re-enters FINALIZE and re-runs this
     * idempotently.
     */
    suspend fun drainDisplayTailBeforeComplete(orderedPageKeys: List<String>): DisplayTailDrain {
        var drained = 0
        var pending = orderedPageKeys
            .map { pageKey -> pageKey to store.snapshot(pageKey).page }
            .filter { (_, page) -> displayTailPending(page) }
            .map { (pageKey, _) -> pageKey }
        // Pages whose LAST attempt failed only with a publication rejection,
        // and the specific blocker of every other pending page (a later
        // attempt of either kind supersedes the earlier classification).
        val publicationRejected = mutableSetOf<String>()
        val blockedReasons = mutableMapOf<String, String>()
        repeat(ChapterProfileBatchCoordinator.MAX_DISPLAY_TAIL_DRAIN_PASSES) {
            if (pending.isEmpty()) return DisplayTailDrain(drained, emptyList())
            pending = pending.filter { pageKey ->
                when (
                    val outcome = stampRenderTerminalIfDisplayComplete(
                        pageKey,
                        "t934 finalize: drain display-complete page tail to its committed display",
                    )
                ) {
                    is RenderStampOutcome.Committed -> {
                        drained++
                        publicationRejected.remove(pageKey)
                        blockedReasons.remove(pageKey)
                        false
                    }
                    is RenderStampOutcome.PublicationRejected -> {
                        publicationRejected += pageKey
                        true
                    }
                    is RenderStampOutcome.Blocked -> {
                        publicationRejected.remove(pageKey)
                        blockedReasons[pageKey] = outcome.reason
                        true
                    }
                }
            }
        }
        val blocked = pending.filter { it !in publicationRejected }
        if (publicationRejected.isNotEmpty()) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT t934 display tail left pending on publication rejections: " +
                    "count=${publicationRejected.size}"
            }
        }
        blocked.forEach { pageKey ->
            val reason = blockedReasons[pageKey]
                ?: displayTailFailureReason(store.snapshot(pageKey).page)
            logcat(LogPriority.WARN) {
                "TachiyomiAT t934 display tail page not committed at FINALIZE " +
                    "pageHash=${ShortHash.hash(pageKey)} reason=$reason"
            }
            persistDisplayTailFailure(pageKey, reason)
        }
        return DisplayTailDrain(drained, blocked)
    }

    /**
     *  display-tail companion of [strandedPageReason]: the SPECIFIC reason
     * a translate+inpaint-complete page still lacks its display commit after
     * the bounded drain. Names the blocking evidence so the durable failure is
     * actionable instead of a bare stage status.
     */
    private fun displayTailFailureReason(page: PageTranslation?): String {
        if (page == null) return "expected page is missing from the store"
        return when {
            page.renderStatus == StageStatus.READY -> "display commit landed after the drain gave up"
            page.inpaintStatus != StageStatus.READY ->
                "inpaint no longer terminal (status=${page.inpaintStatus})"
            page.cleanedImageName == null -> "cleaned image reference missing"
            page.translationStatus != StageStatus.READY && page.translationStatus != StageStatus.PARTIAL ->
                "translation no longer terminal (status=${page.translationStatus})"
            else ->
                "display evidence incomplete: no translated block to show " +
                    "(translation=${page.translationStatus}, inpaint=${page.inpaintStatus}, " +
                    "render=${page.renderStatus})"
        }
    }

    /**
     *  the typed terminal for a page whose display work genuinely cannot
     * finish this run — a durable retryable LAYOUT-stage failure (the
     * render-terminal stamp's stage) plus the live renderStatus FAILED flip,
     * mirroring the stranded-page sweep's carrier/reason style. The run still
     * completes: the reconciled outcome carries the page as a warning
     * (BatchProgressReconciler.reconcileFlaggedCompleted) and the next run's
     * drain (or the reader's manual retry) re-attempts the stamp. Best-effort:
     * a rejected record keeps the page pending, where the next FINALIZE
     * re-drains it — never a silent drop. Only [RenderStampOutcome.Blocked]
     * pages route here — a page rejected merely on its publication
     * ([RenderStampOutcome.PublicationRejected]) is work-complete and stays
     * pending, never typed-failed.
     */
    private suspend fun persistDisplayTailFailure(
        pageKey: String,
        reason: String,
    ) {
        try {
            val carrier = "display commit did not land at FINALIZE"
            val metadata = DurableFailureMetadata(
                pageKey = pageKey,
                stage = ArtifactStage.LAYOUT,
                status = ArtifactStageStatus.FAILED_RETRYABLE,
                category = FailureCategory.TRANSIENT,
                retryCount = 1,
                lastFailureMessage = "$carrier: $reason",
                lastFailedAtEpochMs = nowEpochMs(),
                nextEligibleRetryAtEpochMs = null,
            )
            suspend fun patch(expected: ChapterTranslationStore.PageSnapshot) =
                store.persistDurableStageFailure(
                    pageKey = pageKey,
                    expected = ChapterTranslationStore.PatchPrecondition(
                        generation = expected.generation,
                        pageVersion = expected.pageVersion,
                        leaseToken = expected.leaseToken,
                    ),
                    failure = metadata,
                    description = "t934 finalize display tail failure",
                ) { current ->
                    (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                        sourceFileName = pageKey
                        renderStatus = StageStatus.FAILED
                        errorMessage = metadata.lastFailureMessage
                        updatedAt = nowEpochMs()
                    }
                }
            //  round 3: result-aware (the persistDurablePreflightFailure
            // idiom) — a Rejected must surface its store reason, never vanish.
            // One fresh-snapshot retry heals a fence drifted between the
            // classification and this persist (the stamp attempts of the
            // intervening drain passes move page/lease state); the page flip
            // and the durable failure record share ONE publication per
            // attempt, so a retry that lands keeps them atomic.
            var result = patch(store.snapshot(pageKey))
            if (result is ChapterTranslationStore.PatchResult.Rejected) {
                result = patch(store.snapshot(pageKey))
            }
            if (result is ChapterTranslationStore.PatchResult.Rejected) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t934 display tail failure record rejected " +
                        "pageHash=${ShortHash.hash(pageKey)} reason=${result.reason}"
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT t934 display tail failure record rejected pageHash=" +
                    "${ShortHash.hash(pageKey)} error=${e::class.java.simpleName}"
            }
        }
    }

    fun strandedPageReason(page: PageTranslation?): String {
        if (page == null) return "expected page is missing from the store"
        val textBlocks = page.blocks.filter { it.text.isNotBlank() }
        val resolved = textBlocks.count { block ->
            block.userEditedAt != null ||
                (
                    block.translation.isNotBlank() &&
                        block.translation.trim() != block.text.trim()
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
