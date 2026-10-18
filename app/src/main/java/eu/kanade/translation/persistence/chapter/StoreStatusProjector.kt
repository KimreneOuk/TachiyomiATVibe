package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageDisplayState
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
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.toArtifactDisplayProjection
import kotlinx.coroutines.flow.StateFlow

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
 * only the plain-data inputs plus one run-record lookup, so the live store and the
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
    fun artifactStatus(
        data: StoreStatusProjectionData,
        readRunRecord: (SidecarPointer) -> ChapterRunRecord?,
    ): Translation.State? {
        val manifest = data.manifest ?: return null
        val pagesSnapshot = data.pagesSnapshot
        if (manifest.activeRun != null) {
            completedRunRecordStatus(manifest, pagesSnapshot, readRunRecord)?.let { return it }
        }
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

    /**
     * Artifact manifest page records are consulted only
     * when the active run record is durably COMPLETE. Trusted expected-page
     * shortfalls count as unevidenced. A committed display bundle, a
     * TEXTLESS_COMPLETE state, or an open candidate snapshot is completion
     * evidence; partial translation evidence yields READY_WITH_WARNINGS.
     */
    private fun completedRunRecordStatus(
        manifest: ChapterArtifactManifest,
        pagesSnapshot: Map<String, PageTranslationView>,
        readRunRecord: (SidecarPointer) -> ChapterRunRecord?,
    ): Translation.State? {
        val record = readRunRecord(manifest.activeRun ?: return null) ?: return null
        if (record.state != ChapterRunState.COMPLETE) return null
        val pageRecords = manifest.pages.values
        if (pageRecords.isEmpty()) return null
        var doneCount = 0
        var partialCount = 0
        var unevidencedCount = 0
        pageRecords.forEach { pageRecord ->
            val projection = pageRecord.toArtifactDisplayProjection()
            val livePartial = pagesSnapshot[pageRecord.pageKey]?.translationStatus ==
                StageStatus.PARTIAL
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
    fun artifactStatus(): Translation.State? =
        ChapterStatusProjection.artifactStatus(projectionData()) { pointer ->
            store.readRunRecord(pointer)
        }
}
