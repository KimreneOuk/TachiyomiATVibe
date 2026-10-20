package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.hasCommittedDisplay
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageCancelled
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.flow.StateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat




/**
 * Projection inputs handed to [StoreStatusProjector] by the owning store. The
 * manifest and flows are captured together, then each projection reads each
 * needed flow value once.
 */
internal class StoreStatusInputs(
    val manifest: ChapterArtifactManifest?,
    val state: StateFlow<Map<String, PageTranslationView>>,
    val display: StateFlow<Map<String, PageTranslationView>>,
)

/**
 * Plain-data status projection inputs. The live store captures its flows into
 * this shape once per projection; the publication-free durable read builds it
 * directly, so one pure projection serves both authorities.
 */
internal data class StoreStatusProjectionData(
    val manifest: ChapterArtifactManifest?,
    val pagesSnapshot: Map<String, PageTranslationView>,
    val displaySnapshot: Map<String, PageTranslationView>,
)

/**
 * Pure durable status projection (P3D / P4 cutover). The decision rules read
 * only page records and expected page count, so the live store and the
 * opener's file-only durable read project IDENTICAL statuses from identical
 * durable truth. Manifest is the sole persistence authority.
 */
internal object ChapterStatusProjection {

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        data: StoreStatusProjectionData,
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? =
        data.manifest?.durableFailures?.get("$pageKey:${stage.name}")

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(data: StoreStatusProjectionData): Map<String, DurableFailureMetadata> =
        data.manifest?.durableFailures?.toMap().orEmpty()

    /**
     * Derives durable artifact status without consulting the flat-file summary sidecar.
     * Manifest is the sole persistence authority.
     */
    fun artifactStatus(data: StoreStatusProjectionData): Translation.State? {
        val manifest = data.manifest ?: return null
        val pagesSnapshot = data.pagesSnapshot
        val visiblePages = data.displaySnapshot
        val hasReadableOutput = visiblePages.values.any { it.toPageDisplayProjection().displayReady } ||
            pagesSnapshot.values.any { it.hasRenderedResult || it.isTextlessTerminal }
        val hasPartialArtifact = pagesSnapshot.isNotEmpty() || manifest.pages.isNotEmpty()
        val expectedPageCount = manifest.expectedPageCount
        val expectedPageCountTrusted = manifest.expectedPageCountTrusted
        val durableFailures = manifest.durableFailures.values
        val hasTerminalDurableFailure = durableFailures.any { failure ->
            failure.status == ArtifactStageStatus.FAILED_TERMINAL ||
                failure.status == ArtifactStageStatus.CORRUPT ||
                failure.status == ArtifactStageStatus.STALE
        }
        val hasRetryableDurableFailure = durableFailures.any { failure ->
            failure.status == ArtifactStageStatus.FAILED_RETRYABLE
        }
        val retryablePageKeys = durableFailures
            .filter { it.status == ArtifactStageStatus.FAILED_RETRYABLE }
            .mapTo(mutableSetOf()) { it.pageKey }
        val hasInMemoryFailure = pagesSnapshot.any { (pageKey, page) ->
            page.isStageFailed &&
                pageKey !in retryablePageKeys &&
                !page.hasRenderedResult &&
                !page.isTextlessTerminal
        }
        val hasInFlightPage = pagesSnapshot.any { (pageKey, page) ->
            !page.isStageFailed &&
                (
                    manifest.pages[pageKey]?.candidate != null ||
                        (!page.hasRenderedResult && !page.isTextlessTerminal && page.isStageRunning)
                )
        }
        if (hasTerminalDurableFailure || hasInMemoryFailure) return Translation.State.ERROR
        if (hasRetryableDurableFailure) return Translation.State.PAUSED
        if (hasInFlightPage) {
            return if ((expectedPageCountTrusted && expectedPageCount != null && expectedPageCount > 0) ||
                hasReadableOutput ||
                hasPartialArtifact
            ) {
                Translation.State.READY_WITH_WARNINGS
            } else {
                null
            }
        }
        val expectedPageCountValue = expectedPageCount
            ?.takeIf { expectedPageCountTrusted }
            ?: return if (hasReadableOutput || hasPartialArtifact) {
                Translation.State.READY_WITH_WARNINGS
            } else {
                null
            }
        if (expectedPageCountValue <= 0) return null
        val expectedKeys = manifest.pages.keys.toMutableList()
        repeat((expectedPageCountValue - expectedKeys.size).coerceAtLeast(0)) { index ->
            expectedKeys += "__missing_expected_page_$index"
        }
        val activeGeneration = pagesSnapshot.values.maxOfOrNull { it.runGeneration } ?: 0L
        val unexpectedPageKeys = ChapterPageReconciler.findUnexpectedPageKeys(pagesSnapshot, expectedKeys)
        val reconciliation = ChapterPageReconciler.reconcile(
            pageMap = pagesSnapshot,
            orderedKeys = expectedKeys,
            activeGeneration = activeGeneration,
            unexpectedPageKeys = unexpectedPageKeys,
        )
        // Expected totals can exceed registered pages after upgrade residue;
        // reconciliation represents that shortfall with placeholder keys.
        // Soften only that case. A real stranded page must remain an error so
        // the reader retains its Retry affordance.
        val strandedRealPages =
            reconciliation.strandedPages.keys.any { !it.startsWith("__missing_expected_page_") }
        return if (
            reconciliation.chapterStatus == Translation.State.ERROR &&
            !strandedRealPages &&
            pagesSnapshot.values.none { it.isStageFailed }
        ) {
            Translation.State.READY_WITH_WARNINGS
        } else {
            reconciliation.chapterStatus
        }
    }

}

// Durable status and failure views are projected from one store snapshot. The
// store-bound class stays a thin adapter: it captures its flows into plain
// [StoreStatusProjectionData] once per projection and delegates every rule to
// the pure [ChapterStatusProjection].
internal class StoreStatusProjector(private val store: ChapterTranslationStore) {

    private fun projectionData(): StoreStatusProjectionData {
        val inputs = store.statusProjectionInputs()
        return StoreStatusProjectionData(
            manifest = inputs.manifest,
            pagesSnapshot = inputs.state.value,
            displaySnapshot = inputs.display.value,
        )
    }

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? = ChapterStatusProjection.durableFailure(projectionData(), pageKey, stage)

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(): Map<String, DurableFailureMetadata> =
        ChapterStatusProjection.durableFailuresSnapshot(projectionData())

    /**
     * Derives durable artifact status without consulting the flat-file summary sidecar.
     */
    fun artifactStatus(): Translation.State? = ChapterStatusProjection.artifactStatus(projectionData())
}


/** Strict page-map projection shared by durable chapter status and batch progress. */
internal data class ChapterPageReconciliation(
    val chapterStatus: Translation.State,
    val strandedPages: Map<String, String>,
    val unexpectedPageKeys: Set<String>,
    val doneCount: Int,
    val failedCount: Int,
    val partialCount: Int,
)

/** Projects expected pages using the same readable-terminal rules in both callers. */
internal object ChapterPageReconciler {

    /** Logs unexpected keys while keeping their identities out of log output. */
    fun findUnexpectedPageKeys(
        pageMap: Map<String, PageTranslationView>,
        orderedKeys: List<String>,
    ): Set<String> {
        val expectedKeys = orderedKeys.distinct().toSet()
        val unexpectedPageKeys = pageMap.keys - expectedKeys
        unexpectedPageKeys.forEach { pageKey ->
            logcat(LogPriority.WARN) {
                "event=batch_reconciliation reason=unexpected_page pageHash=${ShortHash.hash(pageKey)}"
            }
        }
        return unexpectedPageKeys
    }

    fun reconcile(
        pageMap: Map<String, PageTranslationView>,
        orderedKeys: List<String>,
        activeGeneration: Long,
        unexpectedPageKeys: Set<String>,
    ): ChapterPageReconciliation {
        val expectedKeys = orderedKeys.distinct()
        if (expectedKeys.isEmpty()) {
            return ChapterPageReconciliation(
                chapterStatus = Translation.State.ERROR,
                strandedPages = emptyMap(),
                unexpectedPageKeys = unexpectedPageKeys,
                doneCount = 0,
                failedCount = 0,
                partialCount = 0,
            )
        }

        val strandedPages = linkedMapOf<String, String>()
        var doneCount = 0
        var failedCount = 0
        var partialCount = 0

        for (pageKey in expectedKeys) {
            val page = pageMap[pageKey]
            if (page == null) {
                strandedPages[pageKey] = "Translation incomplete — expected page is missing"
                failedCount++
                continue
            }
            if (page.runGeneration != activeGeneration && !page.hasRenderedResult && !page.isTextlessTerminal) {
                // A nonterminal page from another generation was skipped by the planner and never reached by the producer.
                strandedPages[pageKey] =
                    "Translation incomplete — expected page was stranded by a prior run and not reached"
                failedCount++
                continue
            }
            when {
                page.isStageFailed -> failedCount++
                page.hasRenderedResult || page.isTextlessTerminal -> doneCount++
                // A partial candidate without rendered output is unreadable and remains retryable work.
                page.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL -> {
                    partialCount++
                    strandedPages[pageKey] =
                        "Translation incomplete — partial result was never rendered"
                    failedCount++
                }
                page.isStageCancelled || page.isStageRunning || page.isNonTerminalWithoutOutput() -> {
                    strandedPages[pageKey] = "Translation incomplete — page was left cancelled or non-terminal"
                    failedCount++
                }
                else -> {
                    strandedPages[pageKey] = "Translation incomplete — expected page has no readable terminal output"
                    failedCount++
                }
            }
        }

        val chapterStatus = when {
            failedCount > 0 -> Translation.State.ERROR
            partialCount > 0 -> Translation.State.READY_WITH_WARNINGS
            else -> Translation.State.TRANSLATED
        }

        return ChapterPageReconciliation(
            chapterStatus = chapterStatus,
            strandedPages = strandedPages,
            unexpectedPageKeys = unexpectedPageKeys,
            doneCount = doneCount,
            failedCount = failedCount,
            partialCount = partialCount,
        )
    }

    private fun PageTranslationView.isNonTerminalWithoutOutput(): Boolean {
        return !isStageFailed &&
            !hasRenderedResult &&
            !isTextlessTerminal &&
            (
                ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                    ocrStatus == eu.kanade.translation.model.StageStatus.PENDING
                )
    }
}


/**
 * Durable-only projections of a [PageArtifactRecord] (Phase 7 S8): both
 * answers derive exclusively from the manifest record when no live page
 * snapshot is available, so they share one committed/display-state
 * interpretation module.
 */

/**
 * Manifest fallback used by the open path ([eu.kanade.translation.persistence.ChapterStoreOpener]):
 * synthesizes the page shape implied by the manifest alone. A committed
 * pointer and validated display base are required before a page counts as
 * stage-ready.
 */
internal fun PageArtifactRecord.toArtifactPageFallback(): PageTranslation {
    val committed = committed
    val displayBase = committed?.displayBase
    val hasCommittedDisplay = committed != null &&
        (
            displayBase?.kind == DisplayBaseKind.ORIGINAL_SOURCE ||
                (
                    displayBase?.kind == DisplayBaseKind.CLEANED_IMAGE &&
                        displayBase.fileName != null &&
                        !displayBase.legacyLayout
                    )
            )
    val displayReady = displayState == PageDisplayState.DISPLAY_READY ||
        displayState == PageDisplayState.TEXTLESS_COMPLETE ||
        displayState == PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT ||
        displayState == PageDisplayState.FAILED_WITH_COMMITTED_RESULT
    val ready = hasCommittedDisplay && displayReady
    val running = displayState == PageDisplayState.CANDIDATE_RUNNING
    val failed = displayState == PageDisplayState.FAILED_NO_RESULT
    val cleanedName = displayBase?.fileName?.takeIf {
        displayBase.kind == DisplayBaseKind.CLEANED_IMAGE &&
            !displayBase.legacyLayout
    }
    return PageTranslation(
        sourceFileName = pageKey,
        cleanedImageName = cleanedName,
        ocrStatus = when {
            ready -> StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        translationStatus = when {
            ready -> StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        inpaintStatus = when {
            ready && cleanedName != null -> StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        renderStatus = when {
            ready && (cleanedName != null || displayBase?.kind == DisplayBaseKind.ORIGINAL_SOURCE) ->
                StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        pageVersion = pageVersion,
        updatedAt = committed?.promotedAtEpochMs ?: 0L,
    )
}

/**
 * Project a durable artifact record when no live page snapshot is available.
 * A committed pointer and validated display base are required before a page
 * counts as translated-ready.
 */
fun PageArtifactRecord.toArtifactDisplayProjection(): PageDisplayProjection {
    val committed = committed
    val committedBaseValid = committed?.displayBase?.validated == true
    val committedDisplayState = displayState
    val committedStagesValid =
        inpaint?.status in
            setOf(
                ArtifactStageStatus.READY,
                ArtifactStageStatus.SKIPPED,
            ) &&
            translation?.status in
            setOf(
                ArtifactStageStatus.READY,
                ArtifactStageStatus.PARTIAL,
            ) &&
            layout?.status == ArtifactStageStatus.READY
    val hasCommittedDisplay =
        committed != null &&
            committedBaseValid &&
            (committedDisplayState != PageDisplayState.DISPLAY_READY || committedStagesValid) &&
            committedDisplayState.hasCommittedDisplay
    val processed =
        committedDisplayState in
            setOf(
                PageDisplayState.DISPLAY_READY,
                PageDisplayState.FAILED_WITH_COMMITTED_RESULT,
                PageDisplayState.FAILED_NO_RESULT,
                PageDisplayState.TEXTLESS_COMPLETE,
            )

    return PageDisplayProjection(
        state = committedDisplayState,
        displayReady = hasCommittedDisplay,
        processed = processed,
    )
}
