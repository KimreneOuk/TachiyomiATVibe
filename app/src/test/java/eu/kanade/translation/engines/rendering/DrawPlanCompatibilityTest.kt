package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.PageLayoutDrawPlan
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 *  WP9 — Stage-7 exit oracle `DrawPlanCompatibilityTest` (gate rows
 * 7.2/7.5, invalidation matrix rows 9/10/11): EVERY fingerprint-relevant
 * input — font asset digest, font/paint identity, layout planner version,
 * stroke policy version, stroke color policy version, platform shaping key,
 * source page dimensions, decode sample size, and the stored compatibility
 * fingerprint — when changed between publication and hydration, yields
 * [HydratedLayout.Incompatible]: the reader re-plans via the async fallback
 * and NEVER mis-draws stale text. Color-only changes (row 10) do NOT
 * invalidate geometry (the color preparation is separately invalidatable),
 * and SSIV pan/zoom/holder transforms are structurally absent from every
 * check (final-target §3) — they can never invalidate a plan.
 */
class DrawPlanCompatibilityTest {

    private class FakeMeasurer : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * 0.6f * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private fun blocks(text: String = "THE RITUAL BEGINS") = listOf(
        TranslationBlock(
            blockId = "p1_b0",
            text = "orig",
            translation = text,
            width = 300f,
            height = 120f,
            x = 100f,
            y = 100f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
            score = 0.95f,
            textColor = 0xFF112233,
            strokeColor = 0xFFFFFFFF,
        ),
    )

    private val fontSha = "bb".repeat(32)

    private fun prepared(
        blocks: List<TranslationBlock> = blocks(),
        pageWidth: Float = 1000f,
        pageHeight: Float = 1400f,
        sampleSize: Int = 1,
        fontAssetSha256: String = fontSha,
        platformKey: String = PUBLISHED_PLATFORM_KEY,
    ): LayoutPlanPublication.Prepared = LayoutPlanPublication.prepare(
        blocks = blocks,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        decodeSampleSize = sampleSize,
        measurer = FakeMeasurer(),
        cleanedImageName = "cleaned.jpg",
        inpaintRevision = 1,
        fontAssetSha256 = fontAssetSha256,
        compatInputs = LayoutPlanPublication.CompatInputs(
            translationArtifactId = "ocr-1",
            cleanedImageArtifactIdOrOriginalSourceId = "cleaned.jpg",
        ),
        platformShapingKey = platformKey,
    ).shouldBeInstanceOf<LayoutPlanPublication.Prepared>()

    /** In-memory pointer source standing in for the manifest + stage record. */
    private inner class PlanSource(
        val prepared: LayoutPlanPublication.Prepared,
        var storedCompatibilityFingerprint: String? = prepared.compatibilityFingerprint,
    ) {
        val pointer = SidecarPointer(
            fileName = "layout/f-test.json",
            schemaVersion = PageLayoutDrawPlan.SCHEMA_VERSION,
            contentFingerprint = prepared.planContentFingerprint,
        )

        fun hydrator(
            fontAssetSha256: String = fontSha,
            platformKey: String = PUBLISHED_PLATFORM_KEY,
        ): PersistedLayoutHydrator = PersistedLayoutHydrator(
            resolve = { PersistedLayoutHydrator.PersistedPlanRef(pointer, storedCompatibilityFingerprint) },
            readDocument = { SidecarRead.Usable(prepared.plan) },
            fontSha256 = { fontAssetSha256 },
            platformShapingKey = { platformKey },
        )

        fun hydrate(
            hydrator: PersistedLayoutHydrator = hydratorFor(prepared),
            blocks: List<TranslationBlock> = blocks(),
            pageWidth: Float = prepared.plan.pageWidth,
            pageHeight: Float = prepared.plan.pageHeight,
            bindPageWidth: Int = prepared.plan.pageWidth.toInt(),
            bindPageHeight: Int = prepared.plan.pageHeight.toInt(),
            sampleSize: Int = prepared.plan.decodeSampleSize,
            expectedCompatibilityFingerprint: String? = storedCompatibilityFingerprint,
        ): HydratedLayout = hydrator.hydrate(
            pageKey = "page-001.jpg",
            blocks = blocks,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            bindPageWidth = bindPageWidth,
            bindPageHeight = bindPageHeight,
            decodeSampleSize = sampleSize,
            expectedCompatibilityFingerprint = expectedCompatibilityFingerprint,
        )

        private fun hydratorFor(p: LayoutPlanPublication.Prepared): PersistedLayoutHydrator =
            PersistedLayoutHydrator(
                resolve = { PersistedLayoutHydrator.PersistedPlanRef(pointer, storedCompatibilityFingerprint) },
                readDocument = { SidecarRead.Usable(p.plan) },
                fontSha256 = { fontSha },
                platformShapingKey = { PUBLISHED_PLATFORM_KEY },
            )
    }

    private fun assertIncompatible(reason: String, result: HydratedLayout) {
        val incompatible = result.shouldBeInstanceOf<HydratedLayout.Incompatible>()
        incompatible.reason shouldBe reason
    }

    @Test
    fun `baseline published plan hydrates resolved`() {
        val source = PlanSource(prepared())
        source.hydrate().shouldBeInstanceOf<HydratedLayout.Resolved>()
    }

    // ------------------------------------------------------------------
    // Gate 7.2 compatibility matrix — every field flip yields Incompatible
    // with its NAMED reason.
    // ------------------------------------------------------------------

    @Test
    fun `font asset digest change invalidates (row-font)`() {
        val source = PlanSource(prepared())
        val hydrator = source.hydrator(fontAssetSha256 = "cc".repeat(32))
        assertIncompatible("font asset digest changed", source.hydrate(hydrator = hydrator))
    }

    @Test
    fun `font or paint identity change invalidates (row 11 family)`() {
        val published = prepared()
        val tampered = published.plan.copy(
            fontIdentity = published.plan.fontIdentity.copy(typefaceStyle = "NORMAL"),
        )
        val source = PlanSource(published)
        val hydrator = PersistedLayoutHydrator(
            resolve = { PersistedLayoutHydrator.PersistedPlanRef(source.pointer, source.storedCompatibilityFingerprint) },
            readDocument = { SidecarRead.Usable(tampered) },
            fontSha256 = { fontSha },
            platformShapingKey = { PUBLISHED_PLATFORM_KEY },
        )
        assertIncompatible("font/paint identity changed", source.hydrate(hydrator = hydrator))
    }

    @Test
    fun `layout planner version bump invalidates (row 11)`() {
        val published = prepared()
        val tampered = published.plan.copy(layoutPlannerVersion = published.plan.layoutPlannerVersion + 1)
        val source = PlanSource(published)
        val hydrator = PersistedLayoutHydrator(
            resolve = { PersistedLayoutHydrator.PersistedPlanRef(source.pointer, source.storedCompatibilityFingerprint) },
            readDocument = { SidecarRead.Usable(tampered) },
            fontSha256 = { fontSha },
            platformShapingKey = { PUBLISHED_PLATFORM_KEY },
        )
        assertIncompatible("layout planner version changed", source.hydrate(hydrator = hydrator))
    }

    @Test
    fun `stroke policy version change invalidates (row 9 - geometry-affecting)`() {
        val published = prepared()
        val tampered = published.plan.copy(strokePolicyVersion = published.plan.strokePolicyVersion + 1)
        val source = PlanSource(published)
        val hydrator = PersistedLayoutHydrator(
            resolve = { PersistedLayoutHydrator.PersistedPlanRef(source.pointer, source.storedCompatibilityFingerprint) },
            readDocument = { SidecarRead.Usable(tampered) },
            fontSha256 = { fontSha },
            platformShapingKey = { PUBLISHED_PLATFORM_KEY },
        )
        assertIncompatible("stroke policy version changed", source.hydrate(hydrator = hydrator))
    }

    @Test
    fun `stroke color policy version change invalidates the persisted plan`() {
        val published = prepared()
        val tampered = published.plan.copy(strokeColorPolicyVersion = published.plan.strokeColorPolicyVersion + 1)
        val source = PlanSource(published)
        val hydrator = PersistedLayoutHydrator(
            resolve = { PersistedLayoutHydrator.PersistedPlanRef(source.pointer, source.storedCompatibilityFingerprint) },
            readDocument = { SidecarRead.Usable(tampered) },
            fontSha256 = { fontSha },
            platformShapingKey = { PUBLISHED_PLATFORM_KEY },
        )
        assertIncompatible("stroke color policy version changed", source.hydrate(hydrator = hydrator))
    }

    @Test
    fun `platform shaping key change invalidates (on-device SDK drift)`() {
        val source = PlanSource(prepared())
        // JVM SDK_INT = 0, so production platformShapingKey() differs from the
        // published key — exactly the conservative re-plan the decision 7.5
        // key guarantees.
        val hydrator = source.hydrator(platformKey = "sdk0-BASE")
        assertIncompatible("platform shaping key changed", source.hydrate(hydrator = hydrator))
    }

    @Test
    fun `source page dimension change invalidates (measurement row)`() {
        val source = PlanSource(prepared())
        assertIncompatible(
            "source page dimensions changed",
            source.hydrate(pageWidth = 1200f),
        )
    }

    @Test
    fun `decode sample size change invalidates (stroke width is sample-size dependent)`() {
        val source = PlanSource(prepared())
        assertIncompatible("decode sample size changed", source.hydrate(sampleSize = 2))
    }

    @Test
    fun `bind dimensions disagreeing with source dims invalidate`() {
        val source = PlanSource(prepared())
        assertIncompatible(
            "bind dimensions disagree with source dimensions",
            source.hydrate(bindPageWidth = 500),
        )
    }

    @Test
    fun `unpinned production font digest never draws from a plan`() {
        val source = PlanSource(prepared())
        val hydrator = PersistedLayoutHydrator(
            resolve = { PersistedLayoutHydrator.PersistedPlanRef(source.pointer, source.storedCompatibilityFingerprint) },
            readDocument = { SidecarRead.Usable(source.prepared.plan) },
            fontSha256 = { null },
            platformShapingKey = { PUBLISHED_PLATFORM_KEY },
        )
        assertIncompatible("production font digest not pinned", source.hydrate(hydrator = hydrator))
    }

    // ------------------------------------------------------------------
    // Gate 7.5b: stale hydration rejected via the stored compatibility
    // fingerprint (the LAYOUT stage record's fingerprint).
    // ------------------------------------------------------------------

    @Test
    fun `stored compatibility fingerprint mismatch rejects stale hydration (gate 7-5b)`() {
        val published = prepared()
        val currentExpected = LayoutPlanPublication.compatibilityFingerprint(
            compatInputs = LayoutPlanPublication.CompatInputs(
                translationArtifactId = "ocr-1",
                // Reader-side state moved: e.g. the page was re-OCRed into a
                // new artifact id after publication.
                cleanedImageArtifactIdOrOriginalSourceId = "cleaned.jpg",
            ),
            fontAssetSha256 = fontSha,
            decodeSampleSize = 1,
            pageWidth = 1000f,
            pageHeight = 1400f,
            platformShapingKey = PUBLISHED_PLATFORM_KEY,
        )
        val source = PlanSource(published)
        source.hydrate(expectedCompatibilityFingerprint = currentExpected)
            .shouldBeInstanceOf<HydratedLayout.Resolved>()

        val movedExpected = currentExpected.reversed()
        assertIncompatible(
            "compatibility fingerprint changed",
            source.hydrate(expectedCompatibilityFingerprint = movedExpected),
        )
    }

    // ------------------------------------------------------------------
    // Invalidation matrix row 10: a COLOR-ONLY change must NOT invalidate
    // geometry (no reflow) — the color preparation is separately
    // invalidatable.
    // ------------------------------------------------------------------

    @Test
    fun `color-only change keeps geometry compatible and color prep separately invalidatable (row 10)`() {
        val original = blocks()
        val recolored = listOf(original[0].copy(textColor = 0xFF445566, strokeColor = 0xFF000000))

        val publishedGeometry = prepared(original)
        val republishedColor = prepared(recolored)

        // Geometry plan compatibility fingerprint: identical, because color is
        // NOT a fingerprint input — no reflow.
        publishedGeometry.compatibilityFingerprint shouldBe republishedColor.compatibilityFingerprint

        // Geometry plan content: color fields are excluded from the DTO, so
        // the published plan bytes are unchanged too.
        publishedGeometry.planContentFingerprint shouldBe republishedColor.planContentFingerprint

        // The color preparation content fingerprint DID change — that is the
        // separately invalidatable sub-result.
        republishedColor.colorContentFingerprint shouldNotBe publishedGeometry.colorContentFingerprint

        // Hydration of the ORIGINAL geometry plan against recolored blocks
        // resolves: the reader consumes the persisted color prep independently.
        val source = PlanSource(publishedGeometry)
        source.hydrate(blocks = recolored).shouldBeInstanceOf<HydratedLayout.Resolved>()
    }

    // ------------------------------------------------------------------
    // SSIV pan/zoom: presentation transforms are structurally not inputs
    // (final-target §3) — nothing in the hydrator can see them, so they can
    // never invalidate; a re-hydration with identical source inputs always
    // resolves.
    // ------------------------------------------------------------------

    @Test
    fun `repeated hydration with unchanged source inputs is stable (SSIV transforms absent)`() {
        val source = PlanSource(prepared())
        source.hydrate().shouldBeInstanceOf<HydratedLayout.Resolved>()
        source.hydrate().shouldBeInstanceOf<HydratedLayout.Resolved>()

        val fingerprint = LayoutPlanPublication.compatibilityFingerprint(
            compatInputs = LayoutPlanPublication.CompatInputs(
                translationArtifactId = "ocr-1",
                cleanedImageArtifactIdOrOriginalSourceId = "cleaned.jpg",
            ),
            fontAssetSha256 = fontSha,
            decodeSampleSize = 1,
            pageWidth = 1000f,
            pageHeight = 1400f,
            platformShapingKey = PUBLISHED_PLATFORM_KEY,
        )
        // Deterministic: the same inputs recompute the stored fingerprint.
        fingerprint shouldBe source.prepared.compatibilityFingerprint
    }

    private companion object {
        const val PUBLISHED_PLATFORM_KEY = "sdk34-UPSIDE_DOWN_CAKE"
    }
}
