package eu.kanade.translation.store

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.pipeline.batch.BatchProgressReconciler
import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.hasCommittedDisplay
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toPageDisplayProjection
import kotlinx.coroutines.flow.StateFlow

/**
 * Consistent projection inputs handed to [StoreStatusProjector] by the owning
 * store's internal accessor. The two flows are carried by reference, so their
 * `.value` reads still happen at the projection body's original points — only
 * the manifest is captured once, exactly where the pre-move body read it.
 */
internal class StoreStatusInputs(
    val manifest: ChapterArtifactManifest?,
    val state: StateFlow<Map<String, PageTranslation>>,
    val display: StateFlow<Map<String, PageTranslation>>,
)

//  Phase 15: durable status projection moved from `ChapterTranslationStore`
// (artifactStatus + the durable-failure read API). The projector reads the
// store's projection inputs through the same-name accessors below; the store
// keeps same-signature delegating stubs at the old qualified names
// (ChapterTranslator, DurableChapterStatusResolver, and the migration /
// artifact-read tests resolve them there).
internal class StoreStatusProjector(private val store: ChapterTranslationStore) {

    // Same-name dependency reads the moved bodies use; resolved through the
    // store's consistent-snapshot accessor at each call.
    private val artifactManifest get() = store.statusProjectionInputs().manifest

    private val state get() = store.statusProjectionInputs().state

    private val display get() = store.statusProjectionInputs().display

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? = artifactManifest?.durableFailures?.get("$pageKey:${stage.name}")

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(): Map<String, DurableFailureMetadata> =
        artifactManifest?.durableFailures?.toMap().orEmpty()

    /**
     * Derives durable artifact status without consulting the legacy summary sidecar.
     *
     *   (durable half): when the manifest authority is ARTIFACTS and a
     * durably COMPLETE run record owns the chapter ([ChapterArtifactManifest.activeRun]
     * readable at [ChapterRunState.COMPLETE]), the MANIFEST PAGE RECORDS are the
     * completion authority and the legacy live-page reconcile is skipped — that
     * reconcile's done-predicate is the legacy display-committed shape, while a
     * flagged-lane ( ON) run commits translations WITHOUT an in-pass
     * render, so every healthy page misprojected as stranded → chapter ERROR.
     * Done evidence per page: a committed display bundle, a TEXTLESS_COMPLETE
     * display state, or an open candidate snapshot (the flagged lane keeps the
     * translated page snapshot addressable through the candidate pointer —
     * see [completedRunRecordStatus]); a partial translation stage demotes the
     * chapter to READY_WITH_WARNINGS; any expected page without such evidence
     * yields ERROR. A missing/unreadable record, a non-COMPLETE state, or an
     * empty page record set falls through to the existing legacy projection
     * unchanged (legacy chapters have no activeRun).
     */
    fun artifactStatus(): Translation.State? {
        val manifest = artifactManifest ?: return null
        if (manifest.activeRun != null) {
            completedRunRecordStatus(manifest)?.let { return it }
        }
        val pagesSnapshot = state.value
        val visiblePages = display.value
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
        val reconciliation = BatchProgressReconciler.reconcile(pagesSnapshot, expectedKeys, activeGeneration)
        //  field fix (Chapter 21): the softener exists for stores whose
        // trusted expected total exceeds the registered pages with no recorded
        // failure (upgrade residue) — reconcile synthesizes placeholder keys
        // for the shortfall. A REAL page stranded cancelled/non-terminal is
        // not that case: softening it produced a "Ready (Warnings)" chapter
        // with unrendered pages and no Retry affordance.
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

    /**
     *   projects the chapter status from the MANIFEST PAGE RECORDS
     * under a durably COMPLETE active run; null whenever the run-record
     * authority is not provable (pointer missing/unreadable, non-COMPLETE
     * state, or no page records to project) so the caller keeps the existing
     * legacy projection. Expected pages beyond the registered records (trusted
     * baseline shortfall) carry no per-page evidence and count as unevidenced —
     * the run record claims completion the manifest cannot show.
     *
     * Done evidence per page: a committed display bundle
     * ([eu.kanade.translation.model.PageDisplayProjection.from] displayReady),
     * a TEXTLESS_COMPLETE display state, or an open candidate snapshot — the
     * flagged lane keeps the translated page snapshot addressable through the
     * candidate pointer (promotion to a committed bundle requires a rendered
     * result and happens later, at reader adoption), so under a COMPLETE run
     * record the candidate-addressable snapshot is the completed work product
     * a re-opened chapter lazily loads
     * ([ChapterTranslationStore.getOrLoadPageSnapshot]). Partial evidence (a
     * PARTIAL translation stage record, or a live PARTIAL page) demotes the
     * chapter to READY_WITH_WARNINGS.
     */
    private fun completedRunRecordStatus(manifest: ChapterArtifactManifest): Translation.State? {
        val record = store.readActiveRunRecord() ?: return null
        if (record.state != ChapterRunState.COMPLETE) return null
        val pagesSnapshot = state.value
        val pageRecords = manifest.pages.values
        if (pageRecords.isEmpty()) return null
        var doneCount = 0
        var partialCount = 0
        var unevidencedCount = 0
        pageRecords.forEach { pageRecord ->
            val projection = PageDisplayProjection.from(pageRecord)
            val livePartial = pagesSnapshot[pageRecord.pageKey]?.translationStatus ==
                eu.kanade.translation.model.StageStatus.PARTIAL
            val done = projection.displayReady ||
                projection.isTextless ||
                pageRecord.candidate != null ||
                pageRecord.displayState.hasCommittedDisplay ||
                pageRecord.displayState == PageDisplayState.TEXTLESS_COMPLETE
            if (done) {
                doneCount++
                if (pageRecord.translation?.status == ArtifactStageStatus.PARTIAL || livePartial) {
                    partialCount++
                }
            } else {
                unevidencedCount++
            }
        }
        manifest.expectedPageCount
            ?.takeIf { manifest.expectedPageCountTrusted }
            ?.let { expected -> (expected - pageRecords.size).coerceAtLeast(0) }
            ?.let { shortfall -> unevidencedCount += shortfall }
        return when {
            unevidencedCount > 0 -> Translation.State.ERROR
            partialCount > 0 -> Translation.State.READY_WITH_WARNINGS
            else -> Translation.State.TRANSLATED
        }
    }
}
