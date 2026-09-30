package eu.kanade.translation.persistence.internal

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.hasCommittedDisplay
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.toArtifactDisplayProjection
import eu.kanade.translation.persistence.chapter.ChapterPageReconciler
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Consistent projection inputs handed to [StoreStatusProjector] by the owning
 * store's internal accessor. The two flows are carried by reference, so their
 * `.value` reads still happen at the projection body's original points — only
 * the manifest is captured once, exactly where the pre-move body read it.
 */
internal class StoreStatusInputs(
    val manifest: ChapterArtifactManifest?,
    val state: StateFlow<Map<String, PageTranslationView>>,
    val display: StateFlow<Map<String, PageTranslationView>>,
)

// Durable status and failure views are projected from one store snapshot.
internal class StoreStatusProjector(private val store: ChapterTranslationStore) {

    // Read the manifest/state/display inputs together through the store's
    // consistent-snapshot accessor.
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
     * When an artifact-authoritative run record is durably COMPLETE, its
     * manifest page records are the completion authority. Live-page
     * reconciliation checks the legacy committed-display shape, but some runs
     * commit translations without rendering during that pass; those healthy
     * pages must not be reported as stranded. Per-page evidence is a committed
     * display bundle, a TEXTLESS_COMPLETE state, or an open candidate snapshot
     * ([completedRunRecordStatus]). Partial translation evidence demotes the
     * chapter to READY_WITH_WARNINGS, and an expected page without evidence
     * yields ERROR. If the run record is missing, unreadable, non-COMPLETE, or
     * has no page records, the legacy live-page projection remains in force.
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

    /**
     * Uses manifest page records only when the active run record is durably
     * COMPLETE. Returns null when that authority cannot be proven, allowing
     * the caller to use the legacy projection. Trusted expected-page
     * shortfalls count as unevidenced. A committed display bundle, a
     * TEXTLESS_COMPLETE state, or an open candidate snapshot is completion
     * evidence; the candidate remains the result until reader adoption
     * publishes a rendered bundle. Partial translation evidence yields
     * READY_WITH_WARNINGS.
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
            val projection = pageRecord.toArtifactDisplayProjection()
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
