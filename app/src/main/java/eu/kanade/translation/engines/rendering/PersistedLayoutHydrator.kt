package eu.kanade.translation.engines.rendering

import eu.kanade.translation.engines.vision.segmentation.MaskGeometry
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.PageLayoutDrawPlan
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead

/**
 * Resolves a page's persisted
 * [PageLayoutDrawPlan] and rehydrates it to the planner's draw shape
 * ([LayoutDrawPlanProjection.rehydrate]), with an EXPLICIT, typed loss
 * contract (wave-2 review F5 / gap 7): the caller can always distinguish a
 * fully-resolved hydration from a lossy one — a lossy `rehydrate` is detected
 * by COUNT comparison and reported as [HydratedLayout.Lossy], never silently
 * returned as a partial draw list. Every failure mode maps to exactly one
 * typed outcome, and every outcome except [HydratedLayout.Resolved] sends the
 * caller to the async planner fallback. The fallback is mandatory for
 * Manual/Auto, legacy data, missing/corrupt/unsupported plans, or a disabled
 * feature flag.
 *
 * Compatibility boundary (gates 7.2/7.5, invalidation rows 9/10/11): a plan
 * whose font digest, font/paint identity, planner version, stroke policy
 * version, stroke color policy version, platform shaping key, source page
 * dimensions, decode sample size, or stored compatibility fingerprint does not
 * match the reader's current inputs is [HydratedLayout.Incompatible] — the plan
 * is re-planned, never mis-drawn. SSIV pan/zoom/holder size/orientation are
 * structurally absent from every check (final-target §3).
 */
class PersistedLayoutHydrator(
    /**
     * Resolves the page's persisted plan reference: manifest `layoutPlans`
     * pointer plus the compatibility fingerprint stored on the page's durable
     * LAYOUT stage record. Null = nothing published for the page (Absent).
     */
    private val resolve: (pageKey: String) -> PersistedPlanRef?,
    /**
     * Store-side document read with the `readOcrCheckpoint`/`readRunRecord`
     * idioms: corrupt bytes are quarantined and reported
     * [SidecarRead.Absent]; a newer schema version is reported
     * [SidecarRead.UnsupportedVersion] with the bytes preserved untouched.
     */
    private val readDocument: (SidecarPointer) -> SidecarRead<PageLayoutDrawPlan>,
    /** The pinned production font digest ([PersistedLayoutRuntime.productionFontSha256]). */
    private val fontSha256: () -> String?,
    /**
     * Rebuilds a block's [MaskGeometry] from the page's OCR snapshot for a
     * durable mask reference (pixel component paths rebuild at hydration,
     * final-target §3). Null disables component-clip reconstruction (hydrated
     * layouts keep cell/legacy clips only — safe degradation, never re-planning).
     */
    private val maskGeometryResolver: ((dtoRef: eu.kanade.translation.persistence.artifact.DrawPlanMaskComponentRef) -> MaskGeometry?)? = null,
    /** Injectable platform key (production: [DrawPlanFingerprint.platformShapingKey]). */
    private val platformShapingKey: () -> String = { DrawPlanFingerprint.platformShapingKey() },
) {

    /**
     * Resolves and rehydrates the persisted plan for [pageKey], or explains —
     * typed, never partial — why it could not.
     *
     * [blocks] must be the SAME planner inputs the plan was computed from (the
     * page's current translated blocks). [pageWidth]/[pageHeight] are the
     * snapshot's source-image dimensions the plan must have been computed at;
     * [bindPageWidth]/[bindPageHeight] the bind-time page dimensions (the
     * reader's stored page dims) which must agree with them. [decodeSampleSize]
     * is the page's OCR decode sample size the planner consumed.
     * [expectedCompatibilityFingerprint] is the reader-side recomputation of
     * the FP-07 fingerprint ([LayoutPlanPublication.compatibilityFingerprint]);
     * null skips the stored-fingerprint comparison (weaker but still safe: the
     * per-field checks above still run).
     */
    fun hydrate(
        pageKey: String,
        blocks: List<TranslationBlock>,
        pageWidth: Float,
        pageHeight: Float,
        bindPageWidth: Int,
        bindPageHeight: Int,
        decodeSampleSize: Int,
        expectedCompatibilityFingerprint: String?,
    ): HydratedLayout {
        val ref = resolve(pageKey) ?: return HydratedLayout.Absent
        val plan = when (val read = readDocument(ref.pointer)) {
            is SidecarRead.Usable -> read.document
            is SidecarRead.UnsupportedVersion -> return HydratedLayout.UnsupportedVersion(read.schemaVersion)
            SidecarRead.Absent -> return HydratedLayout.Absent
        }
        // Defensive double validation (the store reader already validated).
        plan.validationError()?.let { return HydratedLayout.Absent }

        // Compatibility matrix (gate 7.2 / rows 9/10/11) — every check names
        // its reason; any mismatch re-plans, none ever draws from a stale plan.
        val pinnedDigest = fontSha256()
            ?: return HydratedLayout.Incompatible("production font digest not pinned")
        if (plan.fontIdentity.assetSha256 != pinnedDigest) {
            return HydratedLayout.Incompatible("font asset digest changed")
        }
        with(DrawPlanFingerprint) {
            if (plan.fontIdentity.assetName != FONT_ASSET_NAME ||
                plan.fontIdentity.typefaceStyle != TYPEFACE_STYLE ||
                plan.fontIdentity.paintFlags != PAINT_MEASUREMENT_FLAGS
            ) {
                return HydratedLayout.Incompatible("font/paint identity changed")
            }
            if (plan.layoutPlannerVersion != LAYOUT_PLANNER_VERSION) {
                return HydratedLayout.Incompatible("layout planner version changed")
            }
            if (plan.strokePolicyVersion != STROKE_POLICY_VERSION) {
                return HydratedLayout.Incompatible("stroke policy version changed")
            }
            if (plan.strokeColorPolicyVersion != STROKE_COLOR_POLICY_VERSION) {
                return HydratedLayout.Incompatible("stroke color policy version changed")
            }
        }
        if (plan.platformShapingKey != platformShapingKey()) {
            return HydratedLayout.Incompatible("platform shaping key changed")
        }
        if (plan.pageWidth.toRawBits() != pageWidth.toRawBits() ||
            plan.pageHeight.toRawBits() != pageHeight.toRawBits()
        ) {
            return HydratedLayout.Incompatible("source page dimensions changed")
        }
        if (bindPageWidth <= 0 ||
            bindPageHeight <= 0 ||
            bindPageWidth != pageWidth.toInt() ||
            bindPageHeight != pageHeight.toInt()
        ) {
            return HydratedLayout.Incompatible("bind dimensions disagree with source dimensions")
        }
        if (plan.decodeSampleSize != decodeSampleSize) {
            return HydratedLayout.Incompatible("decode sample size changed")
        }
        val stored = ref.compatibilityFingerprint
        if (stored != null && expectedCompatibilityFingerprint != null && stored != expectedCompatibilityFingerprint) {
            return HydratedLayout.Incompatible("compatibility fingerprint changed")
        }

        // Gate 7.3 user-edit authority: the plan rendered the translation text
        // chosen AT PUBLICATION time. If any block's current translation has
        // moved, the persisted plan is stale — replan, never draw old text.
        plan.blocks.forEach { planBlock ->
            val current = blocks.firstOrNull { it.blockId == planBlock.stableBlockId }
            if (current != null && current.translation != planBlock.chosenText) {
                return HydratedLayout.Incompatible("translation content changed since publication")
            }
        }

        // Rehydrate with the explicit F5 loss contract: the hydrated draw list
        // must account for EVERY plan block. rehydrate skips inputs it cannot
        // resolve; a count mismatch is Lossy (caller falls back), never a
        // silently partial draw.
        val layouts = LayoutDrawPlanProjection.rehydrate(plan, blocks, maskGeometryResolver)
        val expectedCount = plan.blocks.size
        if (layouts.size != expectedCount) {
            return HydratedLayout.Lossy(
                plan = plan,
                resolvedCount = layouts.size,
                expectedCount = expectedCount,
                reason = "rehydrate count mismatch: expected $expectedCount, resolved ${layouts.size}",
            )
        }
        return HydratedLayout.Resolved(
            layouts = attachComponentGeometry(layouts, plan),
            plan = plan,
        )
    }

    /**
     * Rebuilds the page-local mask group ids hydration cannot know: with a
     * restored [MaskGeometry], each layout gets a synthetic per-page
     * `planGeometryId` (its plan input index — unique per plan block, stable
     * within one hydration) so the overlay's component-clip cache can key
     * `(planGeometryId, componentId)` exactly like a planner-produced layout.
     * Layouts without restored geometry keep the structural cell/legacy clips.
     */
    private fun attachComponentGeometry(
        layouts: List<BlockLayout>,
        plan: PageLayoutDrawPlan,
    ): List<BlockLayout> =
        layouts.map { layout ->
            val componentId = layout.maskComponentId ?: return@map layout
            if (layout.maskGeometry == null) return@map layout
            val planBlock = plan.blocks.firstOrNull {
                it.stableBlockId == layout.block.blockId && it.maskComponentRef?.componentId == componentId
            } ?: return@map layout
            layout.copy(planGeometryId = planBlock.inputIndex)
        }

    /** Resolved by [resolve]: pointer + the stored compatibility fingerprint (nullable). */
    data class PersistedPlanRef(
        val pointer: SidecarPointer,
        val compatibilityFingerprint: String?,
    )
}

/**
 * Typed hydration outcomes. Everything except [Resolved] routes the caller to
 * the async planner.
 */
sealed interface HydratedLayout {

    /** Fully resolved: one hydrated layout for EVERY plan block. */
    data class Resolved(val layouts: List<BlockLayout>, val plan: PageLayoutDrawPlan) : HydratedLayout

    /**
     * F5 loss contract: `rehydrate` could not resolve every plan block to its
     * input (count mismatch). The caller detects this by type and falls back —
     * the partially hydrated list is carried for diagnostics only and must
     * never be drawn.
     */
    data class Lossy(
        val plan: PageLayoutDrawPlan,
        val resolvedCount: Int,
        val expectedCount: Int,
        val reason: String,
    ) : HydratedLayout

    /** Compatibility matrix mismatch — replan, never mis-draw (gates 7.2/7.5). */
    data class Incompatible(val reason: String) : HydratedLayout

    /** 13: a newer schema owns the semantics; bytes preserved untouched. */
    data class UnsupportedVersion(val schemaVersion: Int) : HydratedLayout

    /** Missing pointer, unreadable sidecar, corrupt (quarantined), or invalid. */
    data object Absent : HydratedLayout
}

/**
 * The reader-side hydration seam. The overlay's
 * [TextLayoutCoordinator] consults [hydrate] BEFORE the async planner; a null
 * return (feature flag OFF, nothing installed, or any non-Resolved outcome) is the
 * mandatory fallback path. The production source is installed by the reader
 * chapter wiring with the chapter's artifact context; no installation means
 * byte-for-byte legacy behavior (planner path, ).
 */
object PersistedLayoutReaderBridge {

    /** Returns hydrated draw layouts for the bind inputs, or null to fall back. */
    fun interface Source {
        fun hydrate(blocks: List<TranslationBlock>, pageWidth: Int, pageHeight: Int): List<BlockLayout>?
    }

    /**
     *  Stage 7: the chapter-keyed production source. Receives the
     * page key alongside the bind inputs so the install site can resolve the
     * page's manifest `layoutPlans` pointer directly (the page key is
     * propagated from the reader holder binding — the overlay's legacy
     * pageKey-less bind path keeps the planner fallback, byte-identical).
     */
    fun interface PageKeyedSource {
        fun hydrate(
            pageKey: String,
            blocks: List<TranslationBlock>,
            pageWidth: Int,
            pageHeight: Int,
        ): List<BlockLayout>?
    }

    @Volatile
    internal var source: Source? = null

    /**  Stage 7: the per-chapter production source. */
    @Volatile
    internal var chapterSource: PageKeyedSource? = null

    /** Installs the per-reader hydration source (reader chapter wiring). */
    fun install(source: Source?) {
        this.source = source
    }

    /**
     *  Stage 7: installs (or uninstalls with null) the chapter
     * hydration source. Called by the reader chapter wiring when the chapter's
     * artifact store opens/closes;  OFF installs null.
     */
    fun installChapterSource(source: PageKeyedSource?) {
        this.chapterSource = source
    }

    /**
     * Never throws to the caller: a source failure degrades to the planner
     * fallback exactly like an absent plan.
     */
    fun hydrate(blocks: List<TranslationBlock>, pageWidth: Int, pageHeight: Int): List<BlockLayout>? =
        runCatching { source?.hydrate(blocks, pageWidth, pageHeight) }.getOrNull()

    /**
     *  Stage 7: page-keyed consult. The chapter source is preferred;
     * a null pageKey, a null/throwing chapter source, or any non-Resolved
     * outcome returns null — the async planner fallback.
     */
    fun hydrate(
        pageKey: String?,
        blocks: List<TranslationBlock>,
        pageWidth: Int,
        pageHeight: Int,
    ): List<BlockLayout>? {
        if (pageKey == null) return null
        return runCatching { chapterSource?.hydrate(pageKey, blocks, pageWidth, pageHeight) }.getOrNull()
    }
}
