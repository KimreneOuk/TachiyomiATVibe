package eu.kanade.translation.store

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.batch.BatchProgressReconciler
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
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

// T909 Phase 15: durable status projection moved from `ChapterTranslationStore`
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

    /** Derives durable artifact status without consulting the legacy summary sidecar. */
    fun artifactStatus(): Translation.State? {
        val manifest = artifactManifest ?: return null
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
        return if (
            reconciliation.chapterStatus == Translation.State.ERROR &&
            pagesSnapshot.values.none { it.isStageFailed }
        ) {
            Translation.State.READY_WITH_WARNINGS
        } else {
            reconciliation.chapterStatus
        }
    }
}
