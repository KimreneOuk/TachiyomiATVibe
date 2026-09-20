package eu.kanade.translation.rendering

import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.DrawPlanAlign
import eu.kanade.translation.artifact.DrawPlanBlock
import eu.kanade.translation.artifact.DrawPlanFontIdentity
import eu.kanade.translation.artifact.DrawPlanMaskComponentRef
import eu.kanade.translation.artifact.DrawPlanPositionedLine
import eu.kanade.translation.artifact.DrawPlanRect
import eu.kanade.translation.artifact.PageLayoutDrawPlan
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.MaskGeometry

/**
 *  WP8 (schemas contract §1.6): pure, additive projection between the
 * runtime layout result ([PageLayoutPlan] / [BlockLayout] /
 * [PositionedLine]) and the landed durable DTO
 * [PageLayoutDrawPlan]. `BlockLayout` itself is deliberately NOT serializable
 * (it embeds a mutable `TranslationBlock`, page-local `planGeometryId`, and
 * planner occupancy/debug structures); this object is the ONLY serialization
 * boundary of the layout track.
 *
 * Round-trip contract (gate 7.1): project → serialize → deserialize →
 * [rehydrate] preserves all geometry EXACTLY (floats keep their bits; the DTO
 * has no Int truncation anywhere; kotlinx emits round-trippable decimal float
 * literals). Excluded by contract (schemas contract §1.6) and NOT restored
 * bit-exactly here:
 *  - `TranslationBlock` payloads (colors, translation, masks) — rehydrated by
 *    reference from the caller-supplied input blocks, exactly like the
 *    planner received them;
 *  - planner occupancy structures (`PositionedLine.conservativeOccupancy`,
 *    `BlockLayout.conservativeOccupancy`) — planning-only; hydrated layouts
 *    are draw-terminal (WP9 hydrates straight to render), so rehydrate
 *    restores the honest un-inflated line rect instead;
 *  - `MaskGeometry` objects and page-local `planGeometryId` — pixel paths
 *    rebuild at hydration (final-target §3); the durable
 *    [DrawPlanMaskComponentRef] pins the geometry content via
 *    [maskGeometryContentHash] and carries the component id.
 *
 * No planning behavior is altered: [TextLayoutPlanner] is untouched and the
 * projection is a pure function of its output.
 */
object LayoutDrawPlanProjection {

    /**
     * Deterministic content hash of a block's mask geometry (the durable
     * replacement for the page-local `planGeometryId`). SHA-256 lowercase hex
     * over the geometry's canonical row-major span key, so equal geometry
     * content always maps to the same durable reference.
     */
    fun maskGeometryContentHash(geometry: MaskGeometry): String =
        StageFingerprints.sourceExcerptHash(geometry.stableKey)

    /**
     * Project one page's planner results onto the durable draw plan. `results`
     * is [PageLayoutPlan.resultsInInputOrder]; every [LayoutOutcome.Draw]
     * becomes a [DrawPlanBlock] in input order (each carries its own
     * `inputIndex`, so ordering is recoverable). Blocks whose OCR id is absent
     * project with a blank `stableBlockId`; such a plan fails
     * [PageLayoutDrawPlan.validationError] and must not be published — real
     * OCR snapshots always carry `pN_bN` ids.
     */
    fun projectToDrawPlan(
        results: List<LayoutResult>,
        pageWidth: Float,
        pageHeight: Float,
        decodeSampleSize: Int,
        fontIdentity: DrawPlanFontIdentity,
        platformShapingKey: String,
    ): PageLayoutDrawPlan = PageLayoutDrawPlan(
        layoutPlannerVersion = DrawPlanFingerprint.LAYOUT_PLANNER_VERSION,
        fontIdentity = fontIdentity,
        platformShapingKey = platformShapingKey,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        decodeSampleSize = decodeSampleSize,
        strokePolicyVersion = DrawPlanFingerprint.STROKE_POLICY_VERSION,
        strokeColorPolicyVersion = DrawPlanFingerprint.STROKE_COLOR_POLICY_VERSION,
        blocks = results.mapNotNull { result ->
            (result.outcome as? LayoutOutcome.Draw)?.let { projectBlock(it.layout, result.identity.inputIndex) }
        },
    )

    /** One [BlockLayout] plus its planner input ordinal onto a [DrawPlanBlock]. */
    fun projectBlock(layout: BlockLayout, inputIndex: Int): DrawPlanBlock {
        val maskComponentRef = if (layout.maskGeometry != null && layout.maskComponentId != null) {
            DrawPlanMaskComponentRef(
                maskGeometryContentHash = maskGeometryContentHash(layout.maskGeometry),
                componentId = layout.maskComponentId,
            )
        } else {
            null
        }
        return DrawPlanBlock(
            stableBlockId = layout.block.blockId.orEmpty(),
            inputIndex = inputIndex,
            chosenText = layout.text,
            isVertical = layout.isVertical,
            originX = layout.originX,
            originY = layout.originY,
            safeW = layout.safeW,
            safeH = layout.safeH,
            fontSizePx = layout.fontSizePx,
            strokeWidth = layout.strokeWidth,
            drawAlign = DrawPlanAlign.valueOf(layout.drawAlign.name),
            clipRect = layout.clipRect?.toDrawPlanRect(),
            lines = layout.lines,
            positionedLines = layout.positionedLines?.map { line ->
                DrawPlanPositionedLine(
                    text = line.text,
                    leftPx = line.leftPx,
                    topPx = line.topPx,
                    layoutWidthPx = line.layoutWidthPx,
                    layoutHeightPx = line.layoutHeightPx,
                )
            },
            cellRect = layout.cellRect?.toDrawPlanRect(),
            maskComponentRef = maskComponentRef,
            maskUsable = layout.maskUsable,
        )
    }

    /**
     * Rehydrate a decoded plan back to the planner-consumable draw list
     * ([TextLayoutPlanner.plan] shape: [BlockLayout] values in render order —
     * placement order = score-descending then input index, the planner's own
     * deterministic sort). `blocks` are the SAME planner inputs the original
     * plan was computed from (the OCR snapshot blocks); each plan block
     * resolves its input by `inputIndex` first, then by `stableBlockId`.
     * Plan blocks with no resolvable input are skipped — the caller treats a
     * lossy rehydration as an invalid plan and falls back to the async
     * planner.
     *
     * `maskGeometryResolver` (WP9) rebuilds [MaskGeometry] from the page's OCR
     * snapshot for a durable ref; without it hydrated layouts keep
     * `maskGeometry = null` and the structural component clip is rebuilt
     * upstream, never re-planned.
     */
    fun rehydrate(
        plan: PageLayoutDrawPlan,
        blocks: List<TranslationBlock>,
        maskGeometryResolver: ((DrawPlanMaskComponentRef) -> MaskGeometry?)? = null,
    ): List<BlockLayout> {
        val resolved = plan.blocks.mapNotNull { planBlock ->
            resolveInput(planBlock, blocks)?.let { planBlock to it }
        }
        return resolved
            .sortedWith(
                compareByDescending<Pair<DrawPlanBlock, TranslationBlock>> { (_, block) -> block.score }
                    .thenBy { (planBlock, _) -> planBlock.inputIndex },
            )
            .map { (planBlock, input) -> rehydrateBlock(planBlock, input, maskGeometryResolver) }
    }

    private fun resolveInput(planBlock: DrawPlanBlock, blocks: List<TranslationBlock>): TranslationBlock? {
        if (planBlock.stableBlockId.isEmpty()) return blocks.getOrNull(planBlock.inputIndex)
        // Index-validated id match first (duplicate-id safe), then the first id
        // match anywhere; an unmatched id means the inputs changed — skip and
        // let the caller fall back (never mis-draw, ).
        val byIndex = blocks.getOrNull(planBlock.inputIndex)
        if (byIndex?.blockId == planBlock.stableBlockId) return byIndex
        return blocks.firstOrNull { it.blockId == planBlock.stableBlockId }
    }

    private fun rehydrateBlock(
        planBlock: DrawPlanBlock,
        input: TranslationBlock,
        maskGeometryResolver: ((DrawPlanMaskComponentRef) -> MaskGeometry?)?,
    ): BlockLayout {
        val positionedLines = planBlock.positionedLines?.map { line ->
            PositionedLine(
                text = line.text,
                leftPx = line.leftPx,
                topPx = line.topPx,
                layoutWidthPx = line.layoutWidthPx,
                layoutHeightPx = line.layoutHeightPx,
                // Planning-only envelope; hydrated layouts are draw-terminal,
                // so the honest un-inflated line rect is restored.
                conservativeOccupancy = FloatRect(
                    line.leftPx.toFloat(),
                    line.topPx.toFloat(),
                    line.leftPx.toFloat() + line.layoutWidthPx.toFloat(),
                    line.topPx.toFloat() + line.layoutHeightPx.toFloat(),
                ),
            )
        }
        val cellRect = planBlock.cellRect?.toFloatRect()
        return BlockLayout(
            block = input,
            text = planBlock.chosenText,
            isVertical = planBlock.isVertical,
            originX = planBlock.originX,
            originY = planBlock.originY,
            safeW = planBlock.safeW,
            safeH = planBlock.safeH,
            fontSizePx = planBlock.fontSizePx,
            strokeWidth = planBlock.strokeWidth,
            drawAlign = TextAlign.valueOf(planBlock.drawAlign.name),
            clipRect = planBlock.clipRect?.toFloatRect(),
            lines = planBlock.lines ?: emptyList(),
            maskGeometry = planBlock.maskComponentRef?.let { ref -> maskGeometryResolver?.invoke(ref) },
            planGeometryId = null,
            maskComponentId = planBlock.maskComponentRef?.componentId,
            cellRect = cellRect,
            positionedLines = positionedLines,
            conservativeOccupancy = emptyList(),
            hardClip = HardClip(
                planGeometryId = null,
                componentId = planBlock.maskComponentRef?.componentId,
                cellRect = cellRect,
            ),
            maskUsable = planBlock.maskUsable,
        )
    }

    /**
     * Canonical serialization: the shared artifact Json instance
     * only — never a bespoke configuration.
     */
    fun encodeToCanonicalJson(plan: PageLayoutDrawPlan): String =
        ArtifactDocumentJson.encodeToString(PageLayoutDrawPlan.serializer(), plan)

    /** Canonical decode through [ArtifactDocumentJson]. */
    fun decodeFromCanonicalJson(json: String): PageLayoutDrawPlan =
        ArtifactDocumentJson.decodeFromString(PageLayoutDrawPlan.serializer(), json)

    private fun FloatRect.toDrawPlanRect(): DrawPlanRect =
        DrawPlanRect(left = left, top = top, right = right, bottom = bottom)

    private fun DrawPlanRect.toFloatRect(): FloatRect =
        FloatRect(left = left, top = top, right = right, bottom = bottom)
}
