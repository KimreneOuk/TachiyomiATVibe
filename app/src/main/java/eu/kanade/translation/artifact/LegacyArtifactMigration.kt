package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal

/**
 * Physical state of a legacy cleaned-image file referenced by a legacy record.
 * [VALID] is only assigned after the file bytes decoded under a bounded probe
 * and the decoded dimensions matched the page's recorded geometry; existence
 * and non-emptiness alone never qualify.
 */
enum class CleanedFileState {
    /** No cleaned image name recorded on the legacy page. */
    NONE_RECORDED,

    /** File exists, decodes, and matches the recorded page dimensions. */
    VALID,

    /** Name recorded but the file does not exist. */
    MISSING,

    /** File exists but is empty. */
    EMPTY,

    /** File exists and is non-empty but failed the bounded decode probe. */
    CORRUPT_BYTES,

    /** File decodes but its dimensions disagree with the recorded page geometry. */
    DIMENSION_MISMATCH,
}

/** Inputs the migration needs beyond the legacy record itself. */
data class LegacyPageFacts(
    val page: PageTranslation,
    val cleanedFileState: CleanedFileState = CleanedFileState.NONE_RECORDED,
    /** Bounds proven by the cleaned-image metadata probe, when available. */
    val cleanedImageDimensions: ProbedImage? = null,
)

/** Whole-chapter legacy input for one migration run. */
data class LegacyChapterSnapshot(
    val pages: Map<String, LegacyPageFacts> = emptyMap(),
    val glossary: Map<String, String> = emptyMap(),
    /** True when the legacy translation JSON existed but could not be parsed. */
    val translationFileCorrupt: Boolean = false,
    /** Exact identity of the legacy translation file these facts came from. */
    val legacyIdentity: LegacySourceIdentity? = null,
    val migratedAtEpochMs: Long = 0L,
)

/**
 * TachiyomiAT: deterministic legacy-state mapping into the chapter artifact
 * manifest (lifecycle contract §14).
 *
 * Rules honored here:
 * - the only copy of a legacy record is never mutated — migration produces a
 *   new manifest and leaves the legacy flat file untouched;
 * - a legacy page becomes a strict provisional committed bundle only when it
 *   is strictly complete: current-revision cleaned file that decodes with
 *   matching dimensions, every required nonblank OCR block carrying a
 *   nonblank target, valid render status, and in-bounds block geometry;
 * - legacy output the old reader can still show without meeting that bar
 *   (e.g. PARTIAL pages) is preserved as an explicit [PageArtifactRecord.legacyVisible]
 *   last-known-good reference that never populates the strict committed
 *   bundle or `DISPLAY_READY`;
 * - non-displayable legacy states map deterministically per the contract
 *   table, including persisted RUNNING, missing/corrupt cleaned files, and
 *   partial translations;
 * - nothing is inferred that cannot be proven from the stored record.
 */
object LegacyArtifactMigration {

    fun migrateChapter(snapshot: LegacyChapterSnapshot): ChapterArtifactManifest {
        val pages = snapshot.pages.map { (pageKey, facts) -> pageKey to migratePage(pageKey, facts) }
        return ChapterArtifactManifest(
            chapterKey = "",
            pages = pages.toMap(),
            durableFailures = durableFailures(snapshot),
            glossary = glossaryPointerOrNull(snapshot),
            legacySource = snapshot.legacyIdentity,
            migratedFromLegacyAtEpochMs = snapshot.migratedAtEpochMs.takeIf { it > 0L },
            updatedAtEpochMs = snapshot.migratedAtEpochMs,
        )
    }

    /**
     * Merges a fresh migration with additive metadata from a prior manifest
     * that was not derived from stale page content. Durable-failure entries
     * survive only for page keys that still exist; the first-migration stamp
     * and an unchanged glossary pointer are preserved so a resync neither
     * loses runtime-recorded failures nor republishes identical glossary
     * versions forever.
     */
    fun resyncManifest(prior: ChapterArtifactManifest, fresh: ChapterArtifactManifest): ChapterArtifactManifest =
        fresh.copy(
            expectedPageCount = maxOf(
                prior.expectedPageCount ?: 0,
                fresh.expectedPageCount ?: 0,
            ).takeIf { it > 0 },
            expectedPageCountTrusted = prior.expectedPageCountTrusted || fresh.expectedPageCountTrusted,
            durableFailures = prior.durableFailures.filterKeys { key ->
                fresh.pages.containsKey(key.substringBeforeLast(':'))
            },
            glossary = prior.glossary?.takeIf { pointer ->
                fresh.glossary?.versionFingerprint == pointer.versionFingerprint
            } ?: fresh.glossary,
            migratedFromLegacyAtEpochMs = prior.migratedFromLegacyAtEpochMs
                ?: fresh.migratedFromLegacyAtEpochMs,
        )

    fun migratePage(pageKey: String, facts: LegacyPageFacts): PageArtifactRecord {
        val page = facts.page
        val detection = detectionRecord(page)
        val ocr = ocrRecord(page)
        val inpaint = inpaintRecord(page, facts.cleanedFileState)
        val translation = translationRecord(page)
        val layout = layoutRecord(page)

        val geometryValid = blockGeometryIsValid(page, facts.cleanedImageDimensions)
        val committed = committedBundleOrNull(pageKey, page, facts.cleanedFileState, geometryValid)
        val legacyVisible = if (committed == null) {
            legacyVisibleOrNull(page, facts.cleanedFileState)
        } else {
            null
        }
        val hasIncompleteWork = runningStageOrNull(page) != null || page.blocks.isNotEmpty()
        val displayState = displayStateFor(page, facts.cleanedFileState, committed != null, legacyVisible != null)

        return PageArtifactRecord(
            pageKey = pageKey,
            naturalPageIndex = null,
            pageVersion = 0L,
            source = SourceIdentity(pageKey = pageKey),
            detection = detection,
            ocr = ocr,
            inpaint = inpaint,
            translation = translation,
            layout = layout,
            committed = committed,
            previousCommitted = null,
            candidate = if (committed == null && hasIncompleteWork) {
                CandidateGenerationMetadata(
                    generationId = legacyCandidateGenerationId(pageKey),
                    origin = ArtifactOrigin.LEGACY,
                    createdAtEpochMs = page.updatedAt,
                )
            } else {
                null
            },
            legacyVisible = legacyVisible,
            displayState = displayState,
        )
    }

    fun legacyCandidateGenerationId(pageKey: String): String = "legacy-$pageKey"

    /**
     * Strict legacy promotion (contract §14 row 1). Requires a current-revision
     * cleaned file that fully validated, successful render/inpaint statuses,
     * nonblank target text on EVERY required nonblank-source block, and block
     * geometry that is finite, positive, and in bounds. A status string alone
     * never qualifies a page.
     */
    fun committedBundleOrNull(
        pageKey: String,
        page: PageTranslation,
        cleanedFileState: CleanedFileState,
        geometryValid: Boolean = blockGeometryIsValid(page),
    ): CommittedBundleMetadata? {
        if (cleanedFileState != CleanedFileState.VALID) return null
        if (page.inpaintRevision < PageTranslation.CURRENT_INPAINT_REVISION) return null
        if (page.renderStatus != StageStatus.READY || page.inpaintStatus != StageStatus.READY) return null
        if (page.translationStatus != StageStatus.READY) return null
        if (!geometryValid) return null
        if (page.blocks.isEmpty()) return null
        if (!allRequiredBlocksTranslated(page)) return null
        val cleanedName = page.cleanedImageName ?: return null
        return CommittedBundleMetadata(
            generationId = "legacy-$pageKey",
            bundleFingerprint = null,
            displayBase = DisplayBaseReference(
                kind = DisplayBaseKind.CLEANED_IMAGE,
                fileName = cleanedName,
                validated = true,
                legacyLayout = true,
            ),
            translationFingerprint = null,
            layoutFingerprint = null,
            origin = ArtifactOrigin.LEGACY,
            provisional = true,
            hasManualEdits = page.blocks.any { it.userEditedAt != null },
            promotedAtEpochMs = page.updatedAt,
        )
    }

    /**
     * Every OCR block with nonblank source text must carry nonblank target
     * text. Blank-source (noise) blocks impose no requirement.
     */
    fun allRequiredBlocksTranslated(page: PageTranslation): Boolean =
        page.blocks.all { block -> block.text.isBlank() || block.translation.isNotBlank() }

    /** Persisted block boxes must be finite, positive, and inside the recorded page dimensions. */
    fun blockGeometryIsValid(page: PageTranslation, probedDimensions: ProbedImage? = null): Boolean {
        val width = probedDimensions?.width?.toFloat() ?: page.imgWidth
        val height = probedDimensions?.height?.toFloat() ?: page.imgHeight
        if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return false
        return page.blocks.all { block ->
            val finite = block.x.isFinite() &&
                block.y.isFinite() &&
                block.width.isFinite() &&
                block.height.isFinite()
            val positive = block.width > 0f && block.height > 0f
            val nonNegativeOrigin = block.x >= 0f && block.y >= 0f
            val inBounds = (
                block.x + block.width <= width + GEOMETRY_TOLERANCE_PX &&
                    block.y + block.height <= height + GEOMETRY_TOLERANCE_PX
                )
            finite && positive && nonNegativeOrigin && inBounds
        }
    }

    /**
     * Last-known-good display base the legacy reader can still show: a fully
     * validated current-revision cleaned file, successful render, and some
     * translated content, on a page that did not meet the strict committed
     * bar (e.g. PARTIAL translation). Purely compatibility visibility —
     * never strict-ready.
     */
    fun legacyVisibleOrNull(
        page: PageTranslation,
        cleanedFileState: CleanedFileState,
    ): DisplayBaseReference? {
        if (cleanedFileState != CleanedFileState.VALID) return null
        if (page.inpaintRevision < PageTranslation.CURRENT_INPAINT_REVISION) return null
        if (page.inpaintStatus != StageStatus.READY || page.renderStatus != StageStatus.READY) return null
        if (page.translationStatus != StageStatus.READY && page.translationStatus != StageStatus.PARTIAL) return null
        val cleanedName = page.cleanedImageName ?: return null
        if (page.blocks.none { it.translation.isNotBlank() }) return null
        return DisplayBaseReference(
            kind = DisplayBaseKind.CLEANED_IMAGE,
            fileName = cleanedName,
            validated = true,
            legacyLayout = true,
        )
    }

    private fun displayStateFor(
        page: PageTranslation,
        cleanedFileState: CleanedFileState,
        hasCommitted: Boolean,
        hasLegacyVisible: Boolean,
    ): PageDisplayState {
        if (page.isTextlessTerminal) return PageDisplayState.TEXTLESS_COMPLETE
        if (hasCommitted) return PageDisplayState.DISPLAY_READY
        if (hasLegacyVisible) {
            // Strictly incomplete: keep the state non-ready; the legacyVisible
            // reference carries the compatibility display base separately.
            return PageDisplayState.ORIGINAL_ONLY
        }
        if (page.isStageRunning) return PageDisplayState.CANDIDATE_RUNNING
        if (cleanedFileState == CleanedFileState.MISSING ||
            cleanedFileState == CleanedFileState.EMPTY ||
            cleanedFileState == CleanedFileState.CORRUPT_BYTES ||
            cleanedFileState == CleanedFileState.DIMENSION_MISMATCH
        ) {
            // Display-shaped page whose physical file is unusable: never point
            // the reader at a missing/corrupt file (contract §14 row 5).
            return if (page.isTranslationDisplayReadyShaped()) {
                PageDisplayState.FAILED_NO_RESULT
            } else if (page.isStageFailed) {
                PageDisplayState.FAILED_NO_RESULT
            } else {
                PageDisplayState.ORIGINAL_ONLY
            }
        }
        return when {
            page.isStageFailed -> PageDisplayState.FAILED_NO_RESULT
            else -> PageDisplayState.ORIGINAL_ONLY
        }
    }

    private fun PageTranslation.isTranslationDisplayReadyShaped(): Boolean =
        blocks.isNotEmpty() &&
            renderStatus == StageStatus.READY &&
            blocks.any { it.translation.isNotBlank() }

    private fun runningStageOrNull(page: PageTranslation): String? = when {
        page.ocrStatus == StageStatus.RUNNING -> StageStatus.RUNNING
        page.translationStatus == StageStatus.RUNNING -> StageStatus.RUNNING
        page.inpaintStatus == StageStatus.RUNNING -> StageStatus.RUNNING
        page.renderStatus == StageStatus.RUNNING -> StageStatus.RUNNING
        else -> null
    }

    private fun detectionRecord(page: PageTranslation): StageArtifactRecord? = when {
        page.ocrStatus == StageStatus.READY || page.blocks.isNotEmpty() || page.detectionCount > 0 ->
            legacyStage(ArtifactStageStatus.READY)
        else -> null
    }

    private fun ocrRecord(page: PageTranslation): StageArtifactRecord = when (page.ocrStatus) {
        StageStatus.RUNNING -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
        StageStatus.FAILED -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
        StageStatus.CANCELLED -> StageArtifactRecord(ArtifactStageStatus.ABSENT, origin = ArtifactOrigin.LEGACY)
        StageStatus.TEXTLESS -> legacyStage(ArtifactStageStatus.TEXTLESS)
        StageStatus.SKIPPED -> legacyStage(ArtifactStageStatus.SKIPPED)
        else -> when {
            page.ocrStatus == StageStatus.READY -> legacyStage(ArtifactStageStatus.READY)
            else -> StageArtifactRecord(ArtifactStageStatus.ABSENT, origin = ArtifactOrigin.LEGACY)
        }
    }

    private fun inpaintRecord(page: PageTranslation, cleanedFileState: CleanedFileState): StageArtifactRecord? =
        when (page.inpaintStatus) {
            StageStatus.READY -> when (cleanedFileState) {
                CleanedFileState.VALID -> if (page.inpaintRevision >= PageTranslation.CURRENT_INPAINT_REVISION) {
                    legacyStage(ArtifactStageStatus.READY, page.cleanedImageName)
                } else {
                    // File is fine but the persisted revision predates the
                    // current mask semantics; the live resume gate re-inpaints.
                    legacyStage(ArtifactStageStatus.STALE, page.cleanedImageName)
                }
                CleanedFileState.NONE_RECORDED -> legacyStage(ArtifactStageStatus.CORRUPT)
                CleanedFileState.MISSING, CleanedFileState.EMPTY,
                CleanedFileState.CORRUPT_BYTES, CleanedFileState.DIMENSION_MISMATCH,
                -> legacyStage(ArtifactStageStatus.CORRUPT, page.cleanedImageName)
            }
            StageStatus.RUNNING -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
            StageStatus.FAILED -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
            StageStatus.CANCELLED -> StageArtifactRecord(ArtifactStageStatus.ABSENT, origin = ArtifactOrigin.LEGACY)
            StageStatus.SKIPPED -> legacyStage(
                ArtifactStageStatus.SKIPPED,
                skipReason = SKIP_REASON_NO_ERASE_REGIONS,
            )
            else -> null
        }

    private fun translationRecord(page: PageTranslation): StageArtifactRecord = when (page.translationStatus) {
        StageStatus.READY -> if (allRequiredBlocksTranslated(page)) {
            legacyStage(ArtifactStageStatus.READY)
        } else {
            // Status says READY but required blocks lack targets: diagnostic,
            // never promotable (lifecycle contract §2).
            legacyStage(ArtifactStageStatus.PARTIAL)
        }
        StageStatus.PARTIAL -> legacyStage(ArtifactStageStatus.PARTIAL)
        StageStatus.RUNNING -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
        StageStatus.FAILED -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
        StageStatus.CANCELLED -> StageArtifactRecord(ArtifactStageStatus.ABSENT, origin = ArtifactOrigin.LEGACY)
        StageStatus.SKIPPED -> legacyStage(
            ArtifactStageStatus.SKIPPED,
            skipReason = SKIP_REASON_TEXTLESS,
        )
        else -> StageArtifactRecord(ArtifactStageStatus.ABSENT, origin = ArtifactOrigin.LEGACY)
    }

    private fun layoutRecord(page: PageTranslation): StageArtifactRecord = when (page.renderStatus) {
        StageStatus.READY -> legacyStage(ArtifactStageStatus.READY)
        StageStatus.RUNNING -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
        StageStatus.FAILED -> legacyStage(ArtifactStageStatus.FAILED_RETRYABLE)
        StageStatus.CANCELLED -> StageArtifactRecord(ArtifactStageStatus.ABSENT, origin = ArtifactOrigin.LEGACY)
        StageStatus.SKIPPED -> legacyStage(
            ArtifactStageStatus.SKIPPED,
            skipReason = SKIP_REASON_TEXTLESS,
        )
        else -> StageArtifactRecord(ArtifactStageStatus.ABSENT, origin = ArtifactOrigin.LEGACY)
    }

    private fun durableFailures(snapshot: LegacyChapterSnapshot): Map<String, DurableFailureMetadata> =
        buildMap {
            snapshot.pages.forEach { (pageKey, facts) ->
                val page = facts.page
                failedLegacyStage(page)?.let { stage ->
                    put(
                        durableFailureKey(pageKey, stage),
                        DurableFailureMetadata(
                            pageKey = pageKey,
                            stage = stage,
                            status = ArtifactStageStatus.FAILED_RETRYABLE,
                            category = FailureCategory.LEGACY_UNKNOWN,
                            retryCount = page.retryCount,
                            lastFailureMessage = page.activeError,
                            lastFailedAtEpochMs = page.updatedAt,
                            nextEligibleRetryAtEpochMs = null,
                            failureFingerprint = null,
                        ),
                    )
                }
                if (facts.cleanedFileState != CleanedFileState.VALID &&
                    facts.cleanedFileState != CleanedFileState.NONE_RECORDED &&
                    facts.page.cleanedImageName != null
                ) {
                    put(
                        durableFailureKey(pageKey, ArtifactStage.INPAINT),
                        DurableFailureMetadata(
                            pageKey = pageKey,
                            stage = ArtifactStage.INPAINT,
                            status = ArtifactStageStatus.CORRUPT,
                            category = FailureCategory.SOURCE,
                            retryCount = page.retryCount,
                            lastFailureMessage = "legacy cleaned image file unusable: ${facts.cleanedFileState}",
                            lastFailedAtEpochMs = page.updatedAt,
                            nextEligibleRetryAtEpochMs = null,
                            failureFingerprint = null,
                        ),
                    )
                }
            }
        }

    private fun failedLegacyStage(page: PageTranslation): ArtifactStage? = when {
        page.ocrStatus == StageStatus.FAILED -> ArtifactStage.OCR
        page.translationStatus == StageStatus.FAILED -> ArtifactStage.TRANSLATION
        page.inpaintStatus == StageStatus.FAILED -> ArtifactStage.INPAINT
        page.renderStatus == StageStatus.FAILED -> ArtifactStage.LAYOUT
        else -> null
    }

    private fun glossaryPointerOrNull(snapshot: LegacyChapterSnapshot): GlossaryPointer? {
        if (snapshot.glossary.isEmpty()) return null
        val fingerprint = StageFingerprints.glossaryVersion(snapshot.glossary)
        return GlossaryPointer(
            fileName = "",
            version = 1,
            versionFingerprint = fingerprint,
        )
    }

    private fun legacyStage(
        status: ArtifactStageStatus,
        legacyPayloadReference: String? = null,
        skipReason: String? = null,
    ): StageArtifactRecord = StageArtifactRecord(
        status = status,
        skipReason = skipReason,
        fingerprint = null,
        origin = ArtifactOrigin.LEGACY,
        artifactFileName = null,
        legacyPayloadReference = legacyPayloadReference,
    )

    private fun durableFailureKey(pageKey: String, stage: ArtifactStage): String = "$pageKey:${stage.name}"

    const val SKIP_REASON_TEXTLESS = "TEXTLESS"
    const val SKIP_REASON_NO_ERASE_REGIONS = "NO_ERASE_REGIONS"
    const val GEOMETRY_TOLERANCE_PX = 1f
}
