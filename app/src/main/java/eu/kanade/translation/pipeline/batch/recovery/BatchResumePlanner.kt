package eu.kanade.translation.pipeline.batch.recovery

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.persistence.chapter.ocrFingerprint
import eu.kanade.translation.pipeline.batch.BatchContextFrontier
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.planning.BatchPlannerInput
import eu.kanade.translation.pipeline.planning.BatchStage
import eu.kanade.translation.pipeline.planning.PageWorkPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/** Per-page resume decision in the 3-lane batch pipeline (Lane A). Lives at
 *  file scope because Kotlin forbids local enum classes. */
internal enum class BatchResumeGate { SKIP_ALL, INPAINT_ONLY, FULL }

/**
 * Plans per-page batch resume work, stamps provenance, enforces the translation
 * failure fence, and updates rolling context. The planner shares its context
 * frontier with the shell and lane workers.
 */
internal class BatchResumePlanner(
    private val store: ChapterTranslationStore,
    private val provider: TranslationFileProvider,
    private val manga: Manga,
    private val source: HttpSource,
    private val chapter: Chapter,
    private val orderedStreams: List<Pair<String, () -> InputStream>>,
    private val isAi: Boolean,
    private val sourceFingerprints: Map<String, String>,
    private val expectedBatchFingerprints: BatchExpectedFingerprints,
    val contextFrontier: BatchContextFrontier,
    private val inpaintingModeFromPref: () -> InpaintingMode,
) {

    var rollingContext = contextFrontier.rollingContext
        private set

    val batchPagePlans = buildBatchPagePlans()

    fun recordContextPage(
        pageKey: String,
        page: PageTranslation,
        terminalFailure: Boolean = false,
    ) {
        contextFrontier.record(pageKey, page, terminalFailure)
        rollingContext = contextFrontier.rollingContext
    }

    fun stampBatchProvenance(page: PageTranslation, stage: BatchStage?): PageTranslation = page.apply {
        when (stage) {
            BatchStage.OCR -> {
                detectionFingerprint = expectedBatchFingerprints.detection
                ocrFingerprint = expectedBatchFingerprints.ocr
            }
            BatchStage.INPAINT -> inpaintFingerprint = expectedBatchFingerprints.inpaint
            BatchStage.TRANSLATION -> {
                translationFingerprint = expectedBatchFingerprints.translation
                translationOrigin = PageWriteOrigin.BATCH.name
                // Stamp the live store glossary
                // version at commit-provenance time, alongside the translation
                // fingerprint. The planner's reuse gate compares this recorded
                // value against the chapter's current version (`absence = 0`)
                // to schedule the one-time repair of pages translated before
                // the glossary matured.
                translationGlossaryVersion = store.currentGlossaryVersion() ?: 0
            }
            BatchStage.LAYOUT -> layoutFingerprint = expectedBatchFingerprints.layout
            else -> {}
        }
    }

    // Plan the complete chapter once, in the same natural order
    // passed to the coordinator.  Native resume gates consume this
    // snapshot; they never derive work from lastPageRead or the
    // reader viewport.
    private fun buildBatchPagePlans() = PageWorkPlanner.planChapter(
        orderedStreams.map { (pageKey, _) ->
            BatchPlannerInput(
                pageKey = pageKey,
                page = store.state.value[pageKey],
                expectedFingerprints = expectedBatchFingerprints,
                sourceFingerprint = if (store.state.value[pageKey] != null) sourceFingerprints[pageKey] else null,
                durableFailure = store.durableFailure(pageKey),
                // AI lane only — the standard
                // engine lane passes null so its decisions are byte-identical
                // to pre-. The accessor itself returns null (gate off) for a
                // legacy authority or a chapter with no glossary pointer, which
                // keeps glossary-less chapters cost-flat. Read ONCE at plan
                // time, exactly like the fingerprints.
                currentGlossaryVersion = if (isAi) store.currentGlossaryVersion() else null,
            )
        },
    ).pages.associateBy { it.pageKey }

    fun translationFailureFence(pageKey: String): Boolean =
        batchPagePlans[pageKey]
            ?.stages
            ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
            ?.decision in setOf(
            eu.kanade.translation.pipeline.planning.StageDecision.FAILED,
            eu.kanade.translation.pipeline.planning.StageDecision.FAILED_RETRYABLE,
            eu.kanade.translation.pipeline.planning.StageDecision.FAILED_TERMINAL,
        )

    // Only seed from pages whose current translation plan still proves that
    // their persisted result is reusable/terminal. A stale page snapshot must
    // not become a context predecessor merely because its old status is READY.
    fun seed() {
        contextFrontier.seed(
            pages = store.state.value,
            eligible = { pageKey, _ ->
                batchPagePlans[pageKey]
                    ?.stages
                    ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                    ?.decision in setOf(
                    eu.kanade.translation.pipeline.planning.StageDecision.REUSE,
                    eu.kanade.translation.pipeline.planning.StageDecision.TERMINAL_COMPLETE,
                )
            },
            terminalFailure = { pageKey, page ->
                val durableRetryable = store.durableFailure(pageKey)?.status ==
                    eu.kanade.translation.persistence.artifact.ArtifactStageStatus.FAILED_RETRYABLE
                val plannedTerminal = batchPagePlans[pageKey]
                    ?.stages
                    ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                    ?.decision in setOf(
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED,
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED_TERMINAL,
                )
                (page.translationStatus == StageStatus.FAILED && !durableRetryable) ||
                    (plannedTerminal && !page.isTextlessTerminal)
            },
        )
        rollingContext = contextFrontier.rollingContext
    }

    private fun plannedTranslationDecision(pageKey: String) =
        batchPagePlans[pageKey]?.stages?.firstOrNull { it.stage == BatchStage.TRANSLATION }?.decision

    fun recordReusableContextPage(pageKey: String, page: PageTranslation) {
        if (!isAi ||
            plannedTranslationDecision(pageKey) !in setOf(
                eu.kanade.translation.pipeline.planning.StageDecision.REUSE,
                eu.kanade.translation.pipeline.planning.StageDecision.TERMINAL_COMPLETE,
            )
        ) {
            return
        }
        if (page.ocrStatus in setOf(StageStatus.READY, StageStatus.TEXTLESS) &&
            page.translationStatus in setOf(
                StageStatus.READY,
                StageStatus.SKIPPED,
            )
        ) {
            // A reused page becomes context only when natural traversal reaches it.
            // Pages after a missing predecessor remain retained by the frontier until
            // that predecessor resolves, so they cannot leak into an earlier request.
            recordContextPage(pageKey, page)
        }
    }

    fun plannedTranslationNeedsWork(pageKey: String): Boolean =
        plannedTranslationDecision(pageKey) == eu.kanade.translation.pipeline.planning.StageDecision.RUN ||
            batchPagePlans[pageKey]
                ?.stages
                ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                ?.let { decision ->
                    decision.decision == eu.kanade.translation.pipeline.planning.StageDecision.FAILED_RETRYABLE &&
                        decision.retryEligible
                } == true

    fun plannedRenderNeedsWork(pageKey: String): Boolean =
        batchPagePlans[pageKey]?.let { plan ->
            val translation = plan.stages.first { it.stage == BatchStage.TRANSLATION }
            val ocr = plan.stages.first { it.stage == BatchStage.OCR }
            if (translation.decision in setOf(
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED,
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED_RETRYABLE,
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED_TERMINAL,
                ) ||
                ocr.decision in setOf(
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED,
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED_RETRYABLE,
                    eu.kanade.translation.pipeline.planning.StageDecision.FAILED_TERMINAL,
                ) ||
                translation.decision == eu.kanade.translation.pipeline.planning.StageDecision.WAIT_FOR_DEPENDENCY &&
                translation.reason == eu.kanade.translation.pipeline.planning.StageReasonCode.PRIOR_PAGE_INCOMPLETE
            ) {
                false
            } else {
                plan.stages.any { decision ->
                    decision.stage in setOf(BatchStage.TRANSLATION, BatchStage.INPAINT, BatchStage.LAYOUT) &&
                        (
                            decision.decision == eu.kanade.translation.pipeline.planning.StageDecision.RUN ||
                                decision.decision == eu.kanade.translation.pipeline.planning.StageDecision.WAIT_FOR_DEPENDENCY &&
                                decision.reason == eu.kanade.translation.pipeline.planning.StageReasonCode.DEPENDENCY_INCOMPLETE
                            )
                }
            }
        } == true

    suspend fun resumeGate(page: PageTranslation?): BatchResumeGate {
        val planned = page?.sourceFileName?.let(batchPagePlans::get)
        if (planned != null) {
            val ocr = planned.stages.first { it.stage == BatchStage.OCR }
            val inpaint = planned.stages.first { it.stage == BatchStage.INPAINT }
            val ocrNeedsWork = ocr.decision == eu.kanade.translation.pipeline.planning.StageDecision.RUN ||
                ocr.decision == eu.kanade.translation.pipeline.planning.StageDecision.WAIT_FOR_DEPENDENCY ||
                ocr.decision == eu.kanade.translation.pipeline.planning.StageDecision.FAILED
            if (inpaint.decision == eu.kanade.translation.pipeline.planning.StageDecision.REUSE &&
                page.cleanedImageName != null
            ) {
                val physicallyPresent = withContext(Dispatchers.IO) {
                    provider.findPageCleanedImage(
                        manga.title,
                        source,
                        chapter.name,
                        chapter.scanlator,
                        page.cleanedImageName!!,
                    )?.let { it.exists() && it.length() > 0L } == true
                }
                if (!physicallyPresent) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT planned resume invalidated metadata-only cleaned image: " +
                            "pageKey=${page.sourceFileName} cleaned=${page.cleanedImageName}"
                    }
                    return if (!ocrNeedsWork && page.hasCurrentInpaintMask) {
                        BatchResumeGate.INPAINT_ONLY
                    } else {
                        BatchResumeGate.FULL
                    }
                }
            }
            return when {
                ocrNeedsWork ->
                    BatchResumeGate.FULL
                inpaint.decision == eu.kanade.translation.pipeline.planning.StageDecision.RUN ->
                    BatchResumeGate.INPAINT_ONLY
                else -> BatchResumeGate.SKIP_ALL
            }
        }
        // Null inpaintingModeUsed = legacy page persisted before this field; treat
        // as a match so existing chapters are not mass re-translated on first open.
        val desiredMode = inpaintingModeFromPref().name
        val inpaintModeMatches = page?.inpaintingModeUsed == null || page.inpaintingModeUsed == desiredMode
        val decision = BatchResumeGateDecider.decide(
            page,
            cleanedFileValid = true,
            inpaintModeMatches = inpaintModeMatches,
        )
        if (decision == BatchResumeGateDecider.Decision.SKIP_ALL && page?.cleanedImageName != null) {
            val physicallyPresent = withContext(Dispatchers.IO) {
                provider.findPageCleanedImage(
                    manga.title,
                    source,
                    chapter.name,
                    chapter.scanlator,
                    page.cleanedImageName!!,
                )?.let { it.exists() && it.length() > 0L } == true
            }
            if (!physicallyPresent) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT resume invalidated metadata-only cleaned image: pageKey=${page.sourceFileName} cleaned=${page.cleanedImageName}"
                }
                return if (page.hasCurrentInpaintMask) {
                    BatchResumeGate.INPAINT_ONLY
                } else {
                    BatchResumeGate.FULL
                }
            }
        }
        if (!inpaintModeMatches) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT resume re-inpainting for mode change: pageKey=${page?.sourceFileName} " +
                    "was=${page?.inpaintingModeUsed} now=$desiredMode"
            }
        }
        return when (decision) {
            BatchResumeGateDecider.Decision.SKIP_ALL -> BatchResumeGate.SKIP_ALL
            BatchResumeGateDecider.Decision.INPAINT_ONLY -> BatchResumeGate.INPAINT_ONLY
            BatchResumeGateDecider.Decision.FULL -> BatchResumeGate.FULL
        }
    }
}
