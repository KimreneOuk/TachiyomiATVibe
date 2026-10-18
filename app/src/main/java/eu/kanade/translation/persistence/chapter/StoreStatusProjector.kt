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
import eu.kanade.translation.persistence.journal.ChapterJournalPageOutcome
import kotlinx.coroutines.flow.StateFlow

/**
 * Opening status state seeded from the recovered journal prefix and then
 * advanced by accepted mutations in the live store.
 */
internal data class StoreStatusInventory(
    val expectedPageKeys: Set<String>,
    val expectedPageCount: Int?,
    val expectedPageCountTrusted: Boolean,
)

internal data class StoreStatusSnapshot(
    val inventory: StoreStatusInventory,
    val durableFailures: Map<String, DurableFailureMetadata>,
    val pageOutcomes: Map<String, ChapterJournalPageOutcome>,
)

/**
 * Projection inputs handed to [StoreStatusProjector] by the owning store. The
 * manifest, flows, and optional journal-seeded store status snapshot are
 * captured together, then each projection reads each needed flow value once.
 */
internal class StoreStatusInputs(
    val manifest: ChapterArtifactManifest?,
    val state: StateFlow<Map<String, PageTranslationView>>,
    val display: StateFlow<Map<String, PageTranslationView>>,
    val journalStatus: StoreStatusSnapshot? = null,
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
    val journalStatus: StoreStatusSnapshot?,
)

/**
 * Pure durable status projection (P3D). The decision rules read only the
 * plain-data inputs plus one run-record lookup, so the live store and the
 * opener's file-only durable read project IDENTICAL statuses from identical
 * durable truth. No store, flow, or journal bridge participates.
 */
internal object ChapterStatusProjection {

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        data: StoreStatusProjectionData,
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? {
        val journalStatus = data.journalStatus
        return if (journalStatus != null) {
            journalStatus.durableFailures["$pageKey:${stage.name}"]
        } else {
            data.manifest?.durableFailures?.get("$pageKey:${stage.name}")
        }
    }

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(data: StoreStatusProjectionData): Map<String, DurableFailureMetadata> {
        val journalStatus = data.journalStatus
        return if (journalStatus != null) {
            journalStatus.durableFailures
        } else {
            data.manifest?.durableFailures?.toMap().orEmpty()
        }
    }

    /**
     * Derives durable artifact status without consulting the flat-file summary sidecar.
     *
     * When a durable run record is COMPLETE, its manifest page records are
     * completion evidence only for artifact-only stores without a journal. After
     * journal bootstrap, the record is only a gate: active store pages plus
     * recovered/accepted inventory supply the evidence, and manifest
     * candidates never count. This keeps translated-but-unrendered pages from
     * being reported stranded at the display tail without restoring
     * artifact-first authority. If the run record cannot be verified, ordinary
     * live-page reconciliation remains in force.
     *
     * [readRunRecord] is deferred: it runs only when a manifest carries an
     * active-run pointer, and receives that captured pointer.
     */
    fun artifactStatus(
        data: StoreStatusProjectionData,
        readRunRecord: (SidecarPointer) -> ChapterRunRecord?,
    ): Translation.State? {
        val manifest = data.manifest ?: return null
        val journalStatus = data.journalStatus
        val pagesSnapshot = data.pagesSnapshot
        if (manifest.activeRun != null) {
            completedRunRecordStatus(manifest, pagesSnapshot, journalStatus, readRunRecord)?.let { return it }
        }
        // The recovered prefix seeds the store's opening state. From then on,
        // display and status use the active store snapshot, including accepted
        // mutations that have not reached the journal writer yet.
        val visiblePages = data.displaySnapshot
        val hasReadableOutput = visiblePages.values.any { it.toPageDisplayProjection().displayReady } ||
            pagesSnapshot.values.any { it.hasRenderedResult || it.isTextlessTerminal }
        val hasPartialArtifact = pagesSnapshot.isNotEmpty() ||
            (journalStatus == null && manifest.pages.isNotEmpty())
        val inventory = journalStatus?.inventory
        val expectedPageCount = if (journalStatus != null) {
            inventory?.expectedPageCount
        } else {
            manifest.expectedPageCount
        }
        val expectedPageCountTrusted = if (journalStatus != null) {
            inventory?.expectedPageCountTrusted == true
        } else {
            manifest.expectedPageCountTrusted
        }
        val durableFailures = if (journalStatus != null) {
            journalStatus.durableFailures.values
        } else {
            manifest.durableFailures.values
        }
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
                    (journalStatus == null && manifest.pages[pageKey]?.candidate != null) ||
                        (!page.hasRenderedResult && !page.isTextlessTerminal && page.isStageRunning)
                    )
        }
        if (hasTerminalDurableFailure || hasInMemoryFailure) return Translation.State.ERROR
        if (hasRetryableDurableFailure) return Translation.State.PAUSED
        if (journalStatus != null) {
            val expectedJournalKeys = inventory?.expectedPageKeys.orEmpty()
            // A materialized page row or replay outcome distinguishes an admitted/migrating
            // chapter from an untouched chapter. With no rows or outcomes, progress falls back
            // to NOT_TRANSLATED rather than treating an empty inventory as interrupted work.
            val hasObservedChapterWork = pagesSnapshot.isNotEmpty() ||
                journalStatus.pageOutcomes.isNotEmpty() ||
                durableFailures.isNotEmpty()
            val hasInertExpectedPageWithoutOutcome = expectedJournalKeys.any { pageKey ->
                if (journalStatus.pageOutcomes[pageKey] != null) return@any false

                val page = pagesSnapshot[pageKey]
                val manifestPage = manifest.pages[pageKey]
                val readableDisplay = visiblePages[pageKey]
                    ?.toPageDisplayProjection()
                    ?.displayReady == true
                val hasContentOrCommittedDisplay = page?.blocks?.isNotEmpty() == true ||
                    page?.hasRenderedResult == true ||
                    readableDisplay
                val hasCandidate = manifestPage?.candidate != null
                val hasTerminalState = page?.isStageFailed == true ||
                    page?.isTextlessTerminal == true ||
                    page?.isStageCancelled == true
                val hasLiveStage = page?.isStageRunning == true

                !hasContentOrCommittedDisplay && !hasCandidate && !hasTerminalState && !hasLiveStage
            }
            if (hasObservedChapterWork && hasInertExpectedPageWithoutOutcome) {
                // The accepted expected-page registration proves work was admitted, but no
                // journal winner or content-backed terminal state proves this page completed.
                // Retry is the safe healing direction; metadata-only fallback remains visible.
                return Translation.State.ERROR
            }
        }
        if (hasInFlightPage) {
            // A journal-backed RUNNING page survived its process owner and is
            // orphaned work; keep the chapter retryable even when other pages
            // remain readable. A live artifact-only store still has an owner,
            // so retain its warnings-as-progress behavior.
            if (journalStatus != null) return Translation.State.ERROR
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
        val expectedKeys = if (journalStatus != null) {
            inventory?.expectedPageKeys.orEmpty().toMutableList()
        } else {
            manifest.pages.keys.toMutableList()
        }
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
     * Artifact-only stores use manifest page records only
     * when the active run record is durably COMPLETE. Trusted expected-page
     * shortfalls count as unevidenced. A committed display bundle, a
     * TEXTLESS_COMPLETE state, or an open candidate snapshot is completion
     * evidence there; partial translation evidence yields READY_WITH_WARNINGS.
     * Journal-backed stores use [completedJournalRunRecordStatus] instead.
     */
    private fun completedRunRecordStatus(
        manifest: ChapterArtifactManifest,
        pagesSnapshot: Map<String, PageTranslationView>,
        journalStatus: StoreStatusSnapshot?,
        readRunRecord: (SidecarPointer) -> ChapterRunRecord?,
    ): Translation.State? {
        val record = readRunRecord(manifest.activeRun ?: return null) ?: return null
        if (record.state != ChapterRunState.COMPLETE) return null
        if (journalStatus != null) {
            return completedJournalRunRecordStatus(journalStatus, pagesSnapshot)
        }
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

    /**
     * Preserve the completed-run display-tail exception after journal bootstrap.
     * The durable run record is only a completion gate; page evidence and the
     * expected set come from the active store snapshot seeded by replay and
     * advanced by accepted mutations. Manifest candidates never count here.
     * Returning null for incomplete/failed live state lets the ordinary
     * journal-backed projection retain its retry, failure, and in-flight rules.
     */
    private fun completedJournalRunRecordStatus(
        journalStatus: StoreStatusSnapshot,
        pagesSnapshot: Map<String, PageTranslationView>,
    ): Translation.State? {
        val inventory = journalStatus.inventory
        val expectedCount = inventory.expectedPageCount
            ?.takeIf { inventory.expectedPageCountTrusted }
            ?: return null
        if (expectedCount <= 0) return null

        val expectedKeys = inventory.expectedPageKeys
        if (journalStatus.durableFailures.values.any { it.pageKey in expectedKeys }) return null
        if (expectedCount != expectedKeys.size) return Translation.State.ERROR

        var partialCount = 0
        for (pageKey in expectedKeys) {
            val page = pagesSnapshot[pageKey] ?: return null
            if (page.isStageFailed) return null
            when {
                page.translationStatus == StageStatus.PARTIAL -> partialCount++
                page.hasRenderedResult || page.isTextlessTerminal || page.translationStatus == StageStatus.READY -> Unit
                else -> return null
            }
        }
        return if (partialCount > 0) {
            Translation.State.READY_WITH_WARNINGS
        } else {
            Translation.State.TRANSLATED
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
            journalStatus = inputs.journalStatus,
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
