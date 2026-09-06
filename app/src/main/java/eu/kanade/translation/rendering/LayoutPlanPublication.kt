package eu.kanade.translation.rendering

import eu.kanade.translation.artifact.CleanedImageReference
import eu.kanade.translation.artifact.ColorImageSourceKind
import eu.kanade.translation.artifact.ColorStyleEntry
import eu.kanade.translation.artifact.ColorStylePreparation
import eu.kanade.translation.artifact.PageLayoutDrawPlan
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.model.TranslationBlock

/**
 * T924 WP9 (T924-FF-02a(1)): pure assembly of the two separately invalidatable
 * LAYOUT_PREPARE sub-results — the geometry [PageLayoutDrawPlan] (schemas
 * contract §1.6) and the [ColorStylePreparation] (schemas contract §1.7) —
 * from the state the Batch render join already has when a page's translation
 * completes. The caller (BatchRenderJoin, FF-02 ON) owns the transaction; this
 * object owns NO store, NO Android types beyond the injected [TextMeasurer],
 * and never mutates the planner (TextLayoutPlanner stays byte-identical).
 *
 * Content fingerprints (T924-SC-10) are SHA-256 over the canonical re-encoded
 * JSON of each DTO, through the same byte-level core the envelope plan uses
 * ([StageFingerprints.envelopePlanContentFingerprint]).
 *
 * The compatibility fingerprint (T924-FP-07,
 * [DrawPlanFingerprint.layoutCompatibilityFingerprint]) is stored by the caller
 * on the page's durable LAYOUT stage record; the hydrator recomputes it from
 * reader-side inputs via [compatibilityFingerprint] and rejects stale plans
 * (gates 7.3/7.5 invalidation rows 9/10/11).
 */
object LayoutPlanPublication {

    /** Rendering engine identity input of the FP-07 fingerprint (stable constant). */
    const val LAYOUT_ENGINE_VERSION: String = "textLayoutPlanner"

    /**
     * The planner consumes no user font-scale/style preference (geometry is a
     * pure function of blocks, page dimensions, sample size and the font), so
     * both the publisher and the hydrator feed the same constant instead of
     * pretending an input exists.
     */
    const val NO_PREFERENCE_INPUT: String = "none"

    /** Reader/publisher-shared inputs of the compatibility fingerprint. */
    data class CompatInputs(
        /** The page's OCR/layout artifact id (`PageTranslation.ocrArtifactId`). */
        val translationArtifactId: String,
        /** Cleaned image name, or `original:<sourceFingerprint>` for textless pages. */
        val cleanedImageArtifactIdOrOriginalSourceId: String,
        val fontScalePreferences: String = NO_PREFERENCE_INPUT,
        val stylePreferences: String = NO_PREFERENCE_INPUT,
    )

    /** The two assembled, validated, canonically encoded sub-results. */
    data class Prepared(
        val plan: PageLayoutDrawPlan,
        val planJson: String,
        val planContentFingerprint: String,
        val compatibilityFingerprint: String,
        val colorPreparation: ColorStylePreparation,
        val colorJson: String,
        val colorContentFingerprint: String,
    )

    /**
     * Plans the page geometry (same planner, same deterministic inputs the
     * reader would use), projects it onto the durable DTO, assembles the color
     * preparation, and fingerprints both. Returns null when the page is
     * unpublishable (no blocks, DTO validation failure such as a blank
     * `stableBlockId`, or more blocks than the schema cap) — the caller then
     * simply skips publication and readers keep the async planner (T924-FF-02b);
     * a plan that would fail validation must never reach the store.
     */
    fun prepare(
        blocks: List<TranslationBlock>,
        pageWidth: Float,
        pageHeight: Float,
        decodeSampleSize: Int,
        measurer: TextMeasurer,
        cleanedImageName: String?,
        inpaintRevision: Int,
        fontAssetSha256: String,
        compatInputs: CompatInputs,
        platformShapingKey: String = DrawPlanFingerprint.platformShapingKey(),
    ): Prepared? {
        if (blocks.isEmpty() || blocks.size > PageLayoutDrawPlan.MAX_BLOCKS) return null
        if (!pageWidth.isFinite() || pageWidth <= 0f || !pageHeight.isFinite() || pageHeight <= 0f) return null
        if (decodeSampleSize < 1) return null

        val layoutPlan = TextLayoutPlanner.planPage(
            blocks,
            pageWidth,
            pageHeight,
            sampleSize = decodeSampleSize,
            renderSourceText = false,
            measurer = measurer,
        )
        val plan = LayoutDrawPlanProjection.projectToDrawPlan(
            results = layoutPlan.resultsInInputOrder,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            decodeSampleSize = decodeSampleSize,
            fontIdentity = DrawPlanFingerprint.drawPlanFontIdentity(fontAssetSha256),
            platformShapingKey = platformShapingKey,
        )
        if (plan.validationError() != null) return null
        val planJson = LayoutDrawPlanProjection.encodeToCanonicalJson(plan)

        val cleanedRef = cleanedImageName?.takeIf { it.isNotBlank() }?.let {
            if (inpaintRevision < 1) return null
            CleanedImageReference(fileName = it, inpaintRevision = inpaintRevision)
        }
        val colorPreparation = ColorStylePreparation(
            colorEstimatorVersion = RenderColorEstimator.COLOR_ESTIMATOR_VERSION,
            imageSource = if (cleanedRef != null) ColorImageSourceKind.CLEANED_IMAGE else ColorImageSourceKind.ORIGINAL_SOURCE,
            cleanedImageRef = cleanedRef,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            blocks = blocks.mapIndexed { index, block ->
                ColorStyleEntry(
                    stableBlockId = block.blockId.orEmpty(),
                    inputIndex = index,
                    textColor = block.textColor,
                    derivedStrokeColor = block.strokeColor,
                )
            },
        )
        if (colorPreparation.validationError() != null) return null
        val colorJson = encodeColorPreparation(colorPreparation)

        return Prepared(
            plan = plan,
            planJson = planJson,
            planContentFingerprint = StageFingerprints.envelopePlanContentFingerprint(planJson),
            compatibilityFingerprint = compatibilityFingerprint(
                compatInputs = compatInputs,
                fontAssetSha256 = fontAssetSha256,
                decodeSampleSize = decodeSampleSize,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                platformShapingKey = platformShapingKey,
            ),
            colorPreparation = colorPreparation,
            colorJson = colorJson,
            colorContentFingerprint = StageFingerprints.envelopePlanContentFingerprint(colorJson),
        )
    }

    /**
     * The T924-FP-07 layout compatibility fingerprint, computed from the same
     * inputs on both sides (publication stores it; hydration recomputes and
     * compares). Private publisher-side constants
     * ([LAYOUT_ENGINE_VERSION], [NO_PREFERENCE_INPUT],
     * [DrawPlanFingerprint.FONT_ASSET_NAME]) are mirrored verbatim.
     */
    fun compatibilityFingerprint(
        compatInputs: CompatInputs,
        fontAssetSha256: String,
        decodeSampleSize: Int,
        pageWidth: Float,
        pageHeight: Float,
        platformShapingKey: String = DrawPlanFingerprint.platformShapingKey(),
    ): String = DrawPlanFingerprint.layoutCompatibilityFingerprint(
        translationArtifactId = compatInputs.translationArtifactId,
        cleanedImageArtifactIdOrOriginalSourceId = compatInputs.cleanedImageArtifactIdOrOriginalSourceId,
        layoutEngineVersion = LAYOUT_ENGINE_VERSION,
        fontIdentity = DrawPlanFingerprint.FONT_ASSET_NAME,
        fontScalePreferences = compatInputs.fontScalePreferences,
        stylePreferences = compatInputs.stylePreferences,
        outputDimensions = outputDimensionsInput(pageWidth, pageHeight),
        fontAssetSha256 = fontAssetSha256,
        decodeSampleSize = decodeSampleSize,
        sourcePageWidth = pageWidth,
        sourcePageHeight = pageHeight,
        layoutPlannerVersion = DrawPlanFingerprint.LAYOUT_PLANNER_VERSION,
        strokePolicyVersion = DrawPlanFingerprint.STROKE_POLICY_VERSION,
        platformShapingKey = platformShapingKey,
    )

    /** Canonical encode of the color sub-result (T924-SC-06, shared Json only). */
    fun encodeColorPreparation(preparation: ColorStylePreparation): String =
        eu.kanade.translation.artifact.ArtifactDocumentJson.encodeToString(
            ColorStylePreparation.serializer(),
            preparation,
        )

    /** Canonical decode of the color sub-result (T924-SC-06). */
    fun decodeColorPreparation(json: String): ColorStylePreparation =
        eu.kanade.translation.artifact.ArtifactDocumentJson.decodeFromString(
            ColorStylePreparation.serializer(),
            json,
        )

    private fun outputDimensionsInput(pageWidth: Float, pageHeight: Float): String =
        "${pageWidth.toRawBits()}x${pageHeight.toRawBits()}"
}
