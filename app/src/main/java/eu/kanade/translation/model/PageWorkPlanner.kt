package eu.kanade.translation.model

import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.PageArtifactRecord

/**
 * Pure lifecycle planner for chapter batches.
 *
 * The input list to [planChapter] is already natural page order.  This class
 * deliberately has no reader position, viewport, or last-read-page input: a
 * chapter batch always walks page 1 through page N and only reuses evidence
 * whose payload, provenance, and dependency fingerprint are still valid.
 */
object PageWorkPlanner {

    /**
     * Compatibility projection for the older four-boolean API.
     *
     * T924-R012: a caller that can observe current source/configuration
     * evidence may supply [expectedFingerprints]/[sourceFingerprint]; a forced
     * plan then validates its detection/OCR reuse against that evidence.
     * Callers that supply none (the historical signature) keep the
     * status/payload-only pass-through, and the non-force path ignores both
     * parameters entirely (it delegates to [planPage], whose evidence inputs
     * are unchanged).
     */
    fun plan(
        page: PageTranslation?,
        force: Boolean = false,
        expectedFingerprints: BatchExpectedFingerprints = BatchExpectedFingerprints(),
        sourceFingerprint: String? = null,
    ): PageWorkPlan {
        if (page == null) {
            return PageWorkPlan(
                runOcr = true,
                runTranslation = true,
                runInpaint = true,
                runRender = true,
            )
        }

        if (force) {
            // T924-R012: forced translation reuses valid detection/OCR evidence
            // independently of inpaint readiness. Inpaint readiness only decides
            // the inpaint stage: a page with valid OCR evidence is no longer
            // re-OCR'd just because its cleaned image is missing or stale. A
            // stale OCR stage still drags inpaint with it (OCR changes invalidate
            // downstream stages), so `canReuseNative` keeps gating inpaint only.
            val ocrReady = page.ocrStatus == StageStatus.READY && page.blocks.isNotEmpty()
            val inpaintReady = page.inpaintStatus == StageStatus.READY && page.cleanedImageName != null
            val ocrEvidenceValid = ocrReady &&
                forceOcrEvidenceMatches(page, expectedFingerprints, sourceFingerprint)
            val canReuseNative = ocrEvidenceValid && inpaintReady
            return PageWorkPlan(
                runOcr = !ocrEvidenceValid,
                runTranslation = true,
                runInpaint = !canReuseNative,
                runRender = true,
                displayReady = false,
            )
        }

        val batch = planPage(
            BatchPlannerInput(
                pageKey = page.sourceFileName.orEmpty(),
                page = page,
            ),
        )
        return PageWorkPlan(
            runOcr = batch.shouldRun(BatchStage.OCR),
            runTranslation = batch.shouldRun(BatchStage.TRANSLATION),
            runInpaint = batch.shouldRun(BatchStage.INPAINT),
            runRender = batch.shouldRun(BatchStage.LAYOUT),
            stageDecisions = batch.stages,
            displayReady = batch.displayReady,

        )
    }

    fun planPage(input: BatchPlannerInput): BatchPageWorkPlan {
        val page = input.page
        val artifact = input.artifact
        val expected = input.expectedFingerprints
        val textless = page?.ocrStatus == StageStatus.TEXTLESS ||
            page?.isTextlessTerminal == true ||
            artifact?.ocr?.status == ArtifactStageStatus.TEXTLESS

        val evidence = BatchStage.entries.associateWith { stage ->
            stageEvidence(
                stage,
                page,
                artifact,
                expected,
                input.translationOrigin,
                input.sourceFingerprint,
                input.durableFailure?.takeIf { it.stage.matches(stage) },
            )
        }
        val decisions = linkedMapOf<BatchStage, StageWorkDecision>()
        BatchStage.entries.forEach { stage ->
            val stageEvidence = evidence.getValue(stage)
            val decision = decideStage(
                stage = stage,
                evidence = stageEvidence,
                prior = decisions,
                textless = textless,
                nowEpochMs = input.nowEpochMs,
                forceRetry = input.forceRetry,
                currentGlossaryVersion = input.currentGlossaryVersion,
                recordedGlossaryVersion = page?.translationGlossaryVersion,
            )
            decisions[stage] = decision
        }

        val displayReady = page?.toPageDisplayProjection()?.displayReady == true ||
            artifact?.let { PageDisplayProjection.from(it).displayReady } == true
        val firstIncomplete = decisions.values.firstOrNull {
            it.decision != StageDecision.REUSE && it.decision != StageDecision.TERMINAL_COMPLETE
        }?.stage

        return BatchPageWorkPlan(
            pageKey = input.pageKey,
            stages = decisions.values.toList(),
            displayReady = displayReady,
            firstIncompleteStage = firstIncomplete,
        )
    }

    /**
     * Plan a natural-order chapter. Once a page needs translation work, later
     * pages wait for that page so chapter context stays gap-free. Native work
     * on later pages remains independently reusable/runnable.
     */
    fun planChapter(pages: List<BatchPlannerInput>): BatchChapterWorkPlan {
        var translationBlocked = false
        val planned = pages.map { input ->
            val page = planPage(input)
            val index = page.stages.indexOfFirst { it.stage == BatchStage.TRANSLATION }
            if (index < 0 || !translationBlocked) {
                if (page.translationNeedsOrderedWork()) translationBlocked = true
                page
            } else {
                val stages = page.stages.toMutableList()
                val translation = stages[index]
                if (translation.decision !in setOf(
                        StageDecision.FAILED,
                        StageDecision.FAILED_RETRYABLE,
                        StageDecision.FAILED_TERMINAL,
                        StageDecision.TERMINAL_COMPLETE,
                    )
                ) {
                    stages[index] = translation.copy(
                        decision = StageDecision.WAIT_FOR_DEPENDENCY,
                        reason = StageReasonCode.PRIOR_PAGE_INCOMPLETE,
                    )
                    val layoutIndex = stages.indexOfFirst { it.stage == BatchStage.LAYOUT }
                    if (layoutIndex >= 0 &&
                        stages[layoutIndex].decision !in setOf(
                            StageDecision.FAILED,
                            StageDecision.FAILED_RETRYABLE,
                            StageDecision.FAILED_TERMINAL,
                        )
                    ) {
                        stages[layoutIndex] = stages[layoutIndex].copy(
                            decision = StageDecision.WAIT_FOR_DEPENDENCY,
                            reason = StageReasonCode.DEPENDENCY_INCOMPLETE,
                        )
                    }
                }
                page.copy(
                    stages = stages,
                    firstIncompleteStage = stages.firstOrNull {
                        it.decision != StageDecision.REUSE && it.decision != StageDecision.TERMINAL_COMPLETE
                    }?.stage,
                )
            }
        }
        return BatchChapterWorkPlan(planned)
    }

    private fun BatchPageWorkPlan.translationNeedsOrderedWork(): Boolean {
        val translation = stages.first { it.stage == BatchStage.TRANSLATION }
        return translation.decision == StageDecision.RUN ||
            translation.decision == StageDecision.FAILED ||
            translation.decision == StageDecision.FAILED_RETRYABLE ||
            translation.decision == StageDecision.FAILED_TERMINAL ||
            translation.decision == StageDecision.WAIT_FOR_DEPENDENCY
    }

    private fun BatchPageWorkPlan.shouldRun(stage: BatchStage): Boolean {
        // FAILED is a terminal diagnostic for this attempt, but the legacy
        // boolean projection means "eligible for the next retry".
        return stages.first { it.stage == stage }.decision in setOf(
            StageDecision.RUN,
            StageDecision.FAILED,
            StageDecision.FAILED_RETRYABLE,
        ) &&
            stages.first { it.stage == stage }.let { decision ->
                decision.decision != StageDecision.FAILED_RETRYABLE || decision.retryEligible
            }
    }

    private data class StageEvidence(
        val status: String,
        val fingerprint: String?,
        val expectedFingerprint: String?,
        val sourceFingerprint: String?,
        val expectedSourceFingerprint: String?,
        val payloadValid: Boolean,
        val origin: ArtifactOrigin,
        val skipReason: String? = null,
        val durableFailure: DurableFailureMetadata? = null,
    )

    private fun decideStage(
        stage: BatchStage,
        evidence: StageEvidence,
        prior: Map<BatchStage, StageWorkDecision>,
        textless: Boolean,
        nowEpochMs: Long,
        forceRetry: Boolean,
        currentGlossaryVersion: Int? = null,
        recordedGlossaryVersion: Int? = null,
    ): StageWorkDecision {
        val durableFailureFingerprintMismatch = evidence.durableFailure?.let { failure ->
            failure.failureFingerprint != null &&
                evidence.expectedFingerprint != null &&
                failure.failureFingerprint != evidence.expectedFingerprint
        } == true
        if (durableFailureFingerprintMismatch) {
            // A failure recorded for an older translator/model/configuration is
            // no longer an admission fence. Re-plan the stage from current
            // evidence instead of treating the stale failure as retryable.
            return dependencyOrRun(stage, StageReasonCode.FINGERPRINT_MISMATCH, prior)
        }
        evidence.durableFailure
            ?.let { failure ->
                val retryable = failure.status == ArtifactStageStatus.FAILED_RETRYABLE
                return StageWorkDecision(
                    stage = stage,
                    decision = if (retryable) StageDecision.FAILED_RETRYABLE else StageDecision.FAILED_TERMINAL,
                    reason = StageReasonCode.FAILED_STAGE,
                    nextEligibleRetryAtEpochMs = failure.nextEligibleRetryAtEpochMs,
                    retryEligible = retryable &&
                        (
                            forceRetry ||
                                failure.nextEligibleRetryAtEpochMs == null ||
                                failure.nextEligibleRetryAtEpochMs <= nowEpochMs
                            ),
                )
            }
        if (textless && stage != BatchStage.DETECTION && stage != BatchStage.OCR) {
            return StageWorkDecision(
                stage,
                StageDecision.TERMINAL_COMPLETE,
                if (stage == BatchStage.INPAINT && evidence.skipReason == NO_ERASE_REGIONS) {
                    StageReasonCode.NO_ERASE_REGIONS
                } else {
                    StageReasonCode.TEXTLESS
                },
            )
        }

        var raw = when {
            evidence.status == StageStatus.FAILED ->
                StageWorkDecision(stage, StageDecision.FAILED, StageReasonCode.FAILED_STAGE)

            evidence.status == ArtifactStageStatus.FAILED_RETRYABLE.name ->
                StageWorkDecision(
                    stage,
                    StageDecision.FAILED_RETRYABLE,
                    StageReasonCode.FAILED_STAGE,
                    evidence.durableFailure?.nextEligibleRetryAtEpochMs,
                    retryEligible = true,
                )

            evidence.status == ArtifactStageStatus.FAILED_TERMINAL.name ->
                StageWorkDecision(stage, StageDecision.FAILED_TERMINAL, StageReasonCode.FAILED_STAGE)

            evidence.status == StageStatus.RUNNING || evidence.status == ArtifactStageStatus.RUNNING.name ->
                dependencyOrRun(stage, StageReasonCode.INTERRUPTED_STAGE, prior)

            evidence.status == StageStatus.CANCELLED ->
                dependencyOrRun(stage, StageReasonCode.CANCELLED_STAGE, prior)

            evidence.status == StageStatus.PARTIAL || evidence.status == ArtifactStageStatus.PARTIAL.name ->
                dependencyOrRun(stage, StageReasonCode.PARTIAL_ARTIFACT, prior)

            !isTerminalSuccess(evidence.status) ->
                dependencyOrRun(stage, StageReasonCode.MISSING_ARTIFACT, prior)

            !evidence.payloadValid ->
                dependencyOrRun(stage, StageReasonCode.MISSING_PAYLOAD, prior)

            evidence.expectedFingerprint != null &&
                evidence.fingerprint == null ||
                evidence.expectedSourceFingerprint != null &&
                evidence.sourceFingerprint == null ->
                dependencyOrRun(stage, StageReasonCode.UNKNOWN_PROVENANCE, prior)

            !fingerprintMatches(evidence) ->
                dependencyOrRun(stage, StageReasonCode.FINGERPRINT_MISMATCH, prior)

            evidence.status == ArtifactStageStatus.SKIPPED.name ->
                StageWorkDecision(stage, StageDecision.TERMINAL_COMPLETE, skipReason(stage, evidence))

            else -> StageWorkDecision(stage, StageDecision.REUSE, StageReasonCode.VALID_ARTIFACT)
        }

        // TachiyomiAT T917 D5 glossary-aware reuse gate (phase3-design §1.2):
        // when the chapter's current glossary version is greater than the
        // version recorded on the persisted translation (absence = 0), a
        // translation-stage REUSE downgrades to RUN — a targeted, one-time
        // terminology repair. The gate is armed only when [currentGlossaryVersion]
        // is non-null: the AI lane with an ARTIFACTS-authority manifest and a
        // published glossary pointer. TERMINAL_COMPLETE (textless/skip) never
        // reaches this branch, and repair converges: the re-run re-folds the
        // same pairs, the version does not bump, the next plan REUSEs.
        if (stage == BatchStage.TRANSLATION &&
            raw.decision == StageDecision.REUSE &&
            currentGlossaryVersion != null &&
            currentGlossaryVersion > (recordedGlossaryVersion ?: 0)
        ) {
            raw = StageWorkDecision(
                stage = stage,
                decision = StageDecision.RUN,
                reason = StageReasonCode.GLOSSARY_MATURED,
            )
        }

        if (raw.decision in setOf(
                StageDecision.FAILED,
                StageDecision.FAILED_RETRYABLE,
                StageDecision.FAILED_TERMINAL,
            )
        ) {
            return raw
        }
        val dependencies = dependencies(stage)
        val blocked = dependencies.any { dependency ->
            prior[dependency]?.decision !in setOf(StageDecision.REUSE, StageDecision.TERMINAL_COMPLETE)
        }
        return if (blocked) {
            raw.copy(
                decision = StageDecision.WAIT_FOR_DEPENDENCY,
                reason = StageReasonCode.DEPENDENCY_INCOMPLETE,
            )
        } else {
            raw
        }
    }

    private fun dependencyOrRun(
        stage: BatchStage,
        reason: StageReasonCode,
        prior: Map<BatchStage, StageWorkDecision>,
    ): StageWorkDecision {
        val blocked = dependencies(stage).any { dependency ->
            prior[dependency]?.decision !in setOf(StageDecision.REUSE, StageDecision.TERMINAL_COMPLETE)
        }
        return StageWorkDecision(
            stage,
            if (blocked) StageDecision.WAIT_FOR_DEPENDENCY else StageDecision.RUN,
            if (blocked) StageReasonCode.DEPENDENCY_INCOMPLETE else reason,
        )
    }

    private fun dependencies(stage: BatchStage): List<BatchStage> = when (stage) {
        BatchStage.DETECTION -> emptyList()
        BatchStage.OCR -> listOf(BatchStage.DETECTION)
        // OCR text changes do not invalidate a valid erase mask.
        BatchStage.INPAINT -> listOf(BatchStage.DETECTION)
        BatchStage.TRANSLATION -> listOf(BatchStage.OCR)
        BatchStage.LAYOUT -> listOf(BatchStage.TRANSLATION, BatchStage.INPAINT)
    }

    private fun isTerminalSuccess(status: String): Boolean = status in setOf(
        StageStatus.READY,
        StageStatus.TEXTLESS,
        StageStatus.SKIPPED,
        ArtifactStageStatus.READY.name,
        ArtifactStageStatus.TEXTLESS.name,
        ArtifactStageStatus.SKIPPED.name,
    )

    private fun fingerprintMatches(evidence: StageEvidence): Boolean {
        val configMatches = evidence.expectedFingerprint == null ||
            evidence.fingerprint == evidence.expectedFingerprint
        val sourceMatches = evidence.expectedSourceFingerprint == null ||
            evidence.sourceFingerprint == evidence.expectedSourceFingerprint
        return configMatches && sourceMatches
    }

    /**
     * T924-R012 evidence gate for the forced path's combined detection+OCR
     * reuse decision. Mirrors the batch planner's [fingerprintMatches] and
     * [stageEvidence] semantics:
     *  - a supplied configuration expectation must equal the recorded
     *    fingerprint; a legacy snapshot with NO recorded fingerprint keeps the
     *    batch pass-through (`!provenanceRequired`), while
     *    `provenanceRequired = true` refuses to reuse unknown provenance;
     *  - a supplied current source hash must equal the recorded one, and a
     *    missing recorded source fingerprint under a known current hash is a
     *    mismatch (the batch UNKNOWN_PROVENANCE rule).
     * Absent expectations never invalidate: callers that supply none reuse on
     * status/payload evidence exactly as before.
     */
    private fun forceOcrEvidenceMatches(
        page: PageTranslation,
        expected: BatchExpectedFingerprints,
        sourceFingerprint: String?,
    ): Boolean {
        val detectionExpectation = expected.detection.takeUnless {
            !expected.provenanceRequired && page.detectionFingerprint == null
        }
        if (detectionExpectation != null && page.detectionFingerprint != detectionExpectation) {
            return false
        }
        val ocrExpectation = expected.ocr.takeUnless {
            !expected.provenanceRequired && page.ocrFingerprint == null
        }
        if (ocrExpectation != null && page.ocrFingerprint != ocrExpectation) {
            return false
        }
        if (sourceFingerprint != null && page.sourceFingerprint != sourceFingerprint) {
            return false
        }
        return true
    }

    private fun skipReason(stage: BatchStage, evidence: StageEvidence): StageReasonCode =
        if (stage == BatchStage.INPAINT && evidence.skipReason == NO_ERASE_REGIONS) {
            StageReasonCode.NO_ERASE_REGIONS
        } else {
            StageReasonCode.TEXTLESS
        }

    private fun stageEvidence(
        stage: BatchStage,
        page: PageTranslation?,
        artifact: PageArtifactRecord?,
        expected: BatchExpectedFingerprints,
        translationOrigin: ArtifactOrigin?,
        sourceFingerprint: String?,
        durableFailure: DurableFailureMetadata?,
    ): StageEvidence {
        val record = when (stage) {
            BatchStage.DETECTION -> artifact?.detection
            BatchStage.OCR -> artifact?.ocr
            BatchStage.INPAINT -> artifact?.inpaint
            BatchStage.TRANSLATION -> artifact?.translation
            BatchStage.LAYOUT -> artifact?.layout
        }
        val fallbackStatus = when (stage) {
            BatchStage.DETECTION, BatchStage.OCR -> page?.ocrStatus ?: StageStatus.PENDING
            BatchStage.INPAINT -> page?.inpaintStatus ?: StageStatus.PENDING
            BatchStage.TRANSLATION -> page?.translationStatus ?: StageStatus.PENDING
            BatchStage.LAYOUT -> page?.renderStatus ?: StageStatus.PENDING
        }
        val status = record?.status?.name ?: fallbackStatus
        val fingerprint = record?.fingerprint ?: when (stage) {
            BatchStage.DETECTION -> page?.detectionFingerprint
            BatchStage.OCR -> page?.ocrFingerprint
            BatchStage.INPAINT -> page?.inpaintFingerprint
            BatchStage.TRANSLATION -> page?.translationFingerprint
            BatchStage.LAYOUT -> page?.layoutFingerprint
        }
        val currentSourceFingerprint = artifact?.source?.sha256 ?: page?.sourceFingerprint
        val expectedSourceFingerprint = sourceFingerprint.takeIf {
            stage == BatchStage.DETECTION || stage == BatchStage.INPAINT
        }
        val expectedFingerprint = when (stage) {
            BatchStage.DETECTION -> expected.detection
            BatchStage.OCR -> expected.ocr
            BatchStage.INPAINT -> expected.inpaint
            BatchStage.TRANSLATION -> expected.translation
            BatchStage.LAYOUT -> expected.layout
        }
        val payloadValid = when {
            record != null -> record.artifactFileName != null || record.legacyPayloadReference != null
            page == null -> false
            stage == BatchStage.DETECTION || stage == BatchStage.OCR ->
                page.ocrStatus == StageStatus.READY || page.ocrStatus == StageStatus.TEXTLESS
            stage == BatchStage.INPAINT -> page.inpaintStatus == StageStatus.READY && page.cleanedImageName != null
            stage == BatchStage.TRANSLATION ->
                page.translationStatus == StageStatus.READY ||
                    page.translationStatus == StageStatus.PARTIAL ||
                    page.translationStatus == StageStatus.SKIPPED
            stage == BatchStage.LAYOUT -> page.renderStatus == StageStatus.READY || page.renderStatus == StageStatus.SKIPPED
            else -> false
        }
        return StageEvidence(
            status = status,
            fingerprint = fingerprint,
            expectedFingerprint = expectedFingerprint.takeUnless {
                !expected.provenanceRequired && record == null && fingerprint == null
            },
            sourceFingerprint = currentSourceFingerprint,
            expectedSourceFingerprint = expectedSourceFingerprint,
            payloadValid = payloadValid,
            origin = record?.origin ?: translationOrigin ?: page?.translationOrigin?.let {
                runCatching { ArtifactOrigin.valueOf(it) }.getOrDefault(ArtifactOrigin.UNKNOWN)
            } ?: ArtifactOrigin.UNKNOWN,
            skipReason = record?.skipReason,
            durableFailure = durableFailure,
        )
    }

    private fun ArtifactStage.matches(stage: BatchStage): Boolean = when (this) {
        ArtifactStage.DETECTION -> stage == BatchStage.DETECTION
        ArtifactStage.OCR -> stage == BatchStage.OCR
        ArtifactStage.INPAINT -> stage == BatchStage.INPAINT
        ArtifactStage.TRANSLATION -> stage == BatchStage.TRANSLATION
        ArtifactStage.LAYOUT -> stage == BatchStage.LAYOUT
    }

    private const val NO_ERASE_REGIONS = "NO_ERASE_REGIONS"
}
