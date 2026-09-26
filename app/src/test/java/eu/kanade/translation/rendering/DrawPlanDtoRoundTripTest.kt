package eu.kanade.translation.rendering

import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ColorStylePreparation
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.PageLayoutDrawPlan
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskGeometry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 *  WP9 — Stage-7 exit oracle `DrawPlanDtoRoundTripTest` (gate rows
 * 7.3/7.4 JVM-testable substance): the FULL persisted-layout round trip
 * THROUGH THE STORE — plan + color preparation assembly
 * ([LayoutPlanPublication.prepare]), sidecar-then-pointer publication
 * ([ChapterArtifactEngine.publishSidecarPointers], exactly the transaction
 * BatchRenderJoin performs behind ), store-backed hydration through the
 * `readOcrCheckpoint`-idiom generic reader, and
 * [LayoutDrawPlanProjection.rehydrate] — reproduces the planner geometry
 * EXACTLY (floats bit-for-bit via `toRawBits`), or reports a typed loss
 * (wave-2 review F5) that the caller must fall back on. Never a silent
 * partial draw.
 */
class DrawPlanDtoRoundTripTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    /** Deterministic measurement: every char is `charWidth` wide at the given size. */
    private class FakeMeasurer(private val charWidth: Float = 0.6f) : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * charWidth * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private fun block(
        blockId: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        score: Float = 1f,
    ) = TranslationBlock(
        blockId = blockId,
        text = "orig $blockId",
        translation = text,
        width = w,
        height = h,
        x = x,
        y = y,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        score = score,
    )

    private fun prepare(
        blocks: List<TranslationBlock>,
        pageWidth: Float = 1000f,
        pageHeight: Float = 1400f,
        sampleSize: Int = 1,
        cleanedImageName: String? = "page-1-cleaned.jpg",
    ): LayoutPlanPublication.Prepared = LayoutPlanPublication.prepare(
        blocks = blocks,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        decodeSampleSize = sampleSize,
        measurer = FakeMeasurer(),
        cleanedImageName = cleanedImageName,
        inpaintRevision = 1,
        fontAssetSha256 = FAKE_ASSET_SHA256,
        compatInputs = compatInputs(),
        platformShapingKey = PLATFORM_KEY,
    ).shouldNotBeNull()

    private fun compatInputs() = LayoutPlanPublication.CompatInputs(
        translationArtifactId = "ocr-artifact-1",
        cleanedImageArtifactIdOrOriginalSourceId = "page-1-cleaned.jpg",
    )

    private fun artifactStore(io: FakeChapterDocumentIo) =
        ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    /** Minimal durable artifact-authoritative manifest with one page record. */
    private fun publishedManifest(io: FakeChapterDocumentIo): ChapterArtifactManifest {
        val store = artifactStore(io)
        store.publishManifest(
            ChapterArtifactManifest(
                chapterKey = "Chapter 1",
                pages = mapOf(PAGE_KEY to PageArtifactRecord(pageKey = PAGE_KEY, pageVersion = 7L)),
                updatedAtEpochMs = 1L,
            ),
        )
        return store.readManifest()!!
    }

    /**
     * The exact transaction BatchRenderJoin performs behind (1):
     * sidecars first, then pointers + the LAYOUT stage record carrying the
     * compatibility fingerprint, in ONE manifest publication.
     */
    private fun publishThroughStore(
        io: FakeChapterDocumentIo,
        prepared: LayoutPlanPublication.Prepared,
    ): ChapterArtifactManifest {
        val store = artifactStore(io)
        val manifest = publishedManifest(io)
        val planFileName = store.layoutPlanSidecarName(PAGE_KEY, prepared.planContentFingerprint)
        val colorFileName = store.colorPreparationSidecarName(PAGE_KEY, prepared.colorContentFingerprint)
        val outcome = store.publishSidecarPointers(
            manifest = manifest,
            sidecars = listOf(
                store.jsonSidecarPublication(
                    planFileName,
                    prepared.planContentFingerprint,
                    prepared.plan,
                    PageLayoutDrawPlan.serializer(),
                ),
                store.jsonSidecarPublication(
                    colorFileName,
                    prepared.colorContentFingerprint,
                    prepared.colorPreparation,
                    ColorStylePreparation.serializer(),
                ),
            ),
            updatePointers = { current ->
                val page = current.pages.getValue(PAGE_KEY)
                current.copy(
                    pages = current.pages + (
                        PAGE_KEY to page.copy(
                            layout = eu.kanade.translation.persistence.artifact.StageArtifactRecord(
                                status = ArtifactStageStatus.READY,
                                fingerprint = prepared.compatibilityFingerprint,
                                origin = eu.kanade.translation.persistence.artifact.ArtifactOrigin.BATCH,
                                artifactFileName = planFileName,
                                updatedAtEpochMs = 2L,
                            ),
                        )
                        ),
                    layoutPlans = current.layoutPlans + (
                        PAGE_KEY to SidecarPointer(
                            fileName = planFileName,
                            schemaVersion = PageLayoutDrawPlan.SCHEMA_VERSION,
                            contentFingerprint = prepared.planContentFingerprint,
                        )
                        ),
                    colorPreparations = current.colorPreparations + (
                        PAGE_KEY to SidecarPointer(
                            fileName = colorFileName,
                            schemaVersion = ColorStylePreparation.SCHEMA_VERSION,
                            contentFingerprint = prepared.colorContentFingerprint,
                        )
                        ),
                )
            },
        )
        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        return outcome.manifest
    }

    /** Store-backed hydrator wired exactly like the production reader source. */
    private fun pointerRef(store: ChapterArtifactEngine, pageKey: String): PersistedLayoutHydrator.PersistedPlanRef? {
        val manifest = store.readManifest() ?: return null
        val pointer = manifest.layoutPlans[pageKey] ?: return null
        return PersistedLayoutHydrator.PersistedPlanRef(
            pointer = pointer,
            compatibilityFingerprint = manifest.pages[pageKey]?.layout?.fingerprint,
        )
    }

    private fun hydratorFor(io: FakeChapterDocumentIo): PersistedLayoutHydrator {
        val store = artifactStore(io)
        return PersistedLayoutHydrator(
            resolve = { pageKey -> pointerRef(store, pageKey) },
            readDocument = { pointer ->
                store.readSidecarDocument(
                    pointer = pointer,
                    serializer = PageLayoutDrawPlan.serializer(),
                    currentSchemaVersion = PageLayoutDrawPlan.SCHEMA_VERSION,
                    expectedKind = PageLayoutDrawPlan.KIND,
                    schemaVersionOf = { it.schemaVersion },
                    kindOf = { it.kind },
                    isValid = { it.validationError() == null },
                )
            },
            fontSha256 = { FAKE_ASSET_SHA256 },
            platformShapingKey = { PLATFORM_KEY },
        )
    }

    @Test
    fun `store publication round-trips to exactly hydrated layouts`() {
        val io = FakeChapterDocumentIo()
        val blocks = listOf(
            block("p1_b0", x = 100f, y = 100f, w = 300f, h = 120f, text = "THE RITUAL BEGINS TONIGHT", score = 0.95f),
            block("p1_b1", x = 400f, y = 100f, w = 200f, h = 80f, text = "I'M SORRY!", score = 0.6f),
            block("p1_b2", x = 401.25f, y = 703.75f, w = 97.5f, h = 55.25f, text = "0.5 px budget", score = 0.3f),
        )
        val prepared = prepare(blocks)
        val manifest = publishThroughStore(io, prepared)

        val result = hydratorFor(io).hydrate(
            pageKey = PAGE_KEY,
            blocks = blocks,
            pageWidth = prepared.plan.pageWidth,
            pageHeight = prepared.plan.pageHeight,
            bindPageWidth = prepared.plan.pageWidth.toInt(),
            bindPageHeight = prepared.plan.pageHeight.toInt(),
            decodeSampleSize = 1,
            expectedCompatibilityFingerprint = manifest.pages.getValue(PAGE_KEY).layout?.fingerprint,
        )
        val resolved = result.shouldBeInstanceOf<HydratedLayout.Resolved>()
        resolved.layouts shouldHaveSize prepared.plan.blocks.size

        // Gate 7.3/7.4: EXACT geometry — every float keeps its bits through
        // project → canonical JSON → store bytes → decode → rehydrate.
        val expected = TextLayoutPlanner.plan(
            blocks,
            prepared.plan.pageWidth,
            prepared.plan.pageHeight,
            1,
            false,
            FakeMeasurer(),
        )
        resolved.layouts.map { it.text } shouldBe expected.map { it.text }
        expected.zip(resolved.layouts) { e, a ->
            a.block.blockId shouldBe e.block.blockId
            a.originX.toRawBits() shouldBe e.originX.toRawBits()
            a.originY.toRawBits() shouldBe e.originY.toRawBits()
            a.safeW.toRawBits() shouldBe e.safeW.toRawBits()
            a.safeH.toRawBits() shouldBe e.safeH.toRawBits()
            a.fontSizePx.toRawBits() shouldBe e.fontSizePx.toRawBits()
            a.strokeWidth.toRawBits() shouldBe e.strokeWidth.toRawBits()
            if (e.positionedLines != null) {
                a.positionedLines shouldNotBe null
                e.positionedLines.zip(a.positionedLines!!) { le, la ->
                    la.leftPx shouldBe le.leftPx
                    la.topPx shouldBe le.topPx
                    la.layoutWidthPx shouldBe le.layoutWidthPx
                    la.layoutHeightPx shouldBe le.layoutHeightPx
                }
            } else {
                a.positionedLines shouldBe null
            }
        }

        // The pointer content fingerprints survive as published.
        manifest.layoutPlans.getValue(PAGE_KEY).contentFingerprint shouldBe prepared.planContentFingerprint
        manifest.colorPreparations.getValue(PAGE_KEY).contentFingerprint shouldBe prepared.colorContentFingerprint
    }

    @Test
    fun `canonical plan bytes are byte-stable across the store round trip`() {
        val io = FakeChapterDocumentIo()
        val blocks = listOf(
            block("p2_b0", x = 100f, y = 100f, w = 300f, h = 120f, text = "HELLO WORLD", score = 1f),
        )
        val prepared = prepare(blocks)
        publishThroughStore(io, prepared)

        val store = artifactStore(io)
        val manifest = store.readManifest()!!
        val read = store.readSidecarDocument(
            pointer = manifest.layoutPlans.getValue(PAGE_KEY),
            serializer = PageLayoutDrawPlan.serializer(),
            currentSchemaVersion = PageLayoutDrawPlan.SCHEMA_VERSION,
            expectedKind = PageLayoutDrawPlan.KIND,
            schemaVersionOf = { it.schemaVersion },
            kindOf = { it.kind },
            isValid = { it.validationError() == null },
        )
        val plan = read.shouldBeInstanceOf<SidecarRead.Usable<PageLayoutDrawPlan>>().document
        // 06: encode → decode → encode is byte-identical.
        LayoutDrawPlanProjection.encodeToCanonicalJson(plan) shouldBe prepared.planJson

        val colorRead = store.readSidecarDocument(
            pointer = manifest.colorPreparations.getValue(PAGE_KEY),
            serializer = ColorStylePreparation.serializer(),
            currentSchemaVersion = ColorStylePreparation.SCHEMA_VERSION,
            expectedKind = ColorStylePreparation.KIND,
            schemaVersionOf = { it.schemaVersion },
            kindOf = { it.kind },
            isValid = { it.validationError() == null },
        )
        val color = colorRead.shouldBeInstanceOf<SidecarRead.Usable<ColorStylePreparation>>().document
        LayoutPlanPublication.encodeColorPreparation(color) shouldBe prepared.colorJson
        color.blocks.map { it.textColor } shouldBe blocks.map { it.textColor }
    }

    @Test
    fun `unresolvable inputs are a typed LOSSY, never a silently partial draw (F5 gap 7)`() {
        val io = FakeChapterDocumentIo()
        val blocks = listOf(
            block("p3_b0", x = 100f, y = 100f, w = 300f, h = 120f, text = "FIRST", score = 0.9f),
            block("p3_b1", x = 400f, y = 100f, w = 200f, h = 80f, text = "SECOND", score = 0.5f),
        )
        val prepared = prepare(blocks)
        publishThroughStore(io, prepared)

        // The page changed under the plan: one block id replaced.
        val changed = listOf(blocks[0], blocks[1].copy(blockId = "p3_b1_revised"))
        val result = hydratorFor(io).hydrate(
            pageKey = PAGE_KEY,
            blocks = changed,
            pageWidth = prepared.plan.pageWidth,
            pageHeight = prepared.plan.pageHeight,
            bindPageWidth = prepared.plan.pageWidth.toInt(),
            bindPageHeight = prepared.plan.pageHeight.toInt(),
            decodeSampleSize = 1,
            expectedCompatibilityFingerprint = prepared.compatibilityFingerprint,
        )
        val lossy = result.shouldBeInstanceOf<HydratedLayout.Lossy>()
        lossy.expectedCount shouldBe prepared.plan.blocks.size
        lossy.resolvedCount shouldBe prepared.plan.blocks.size - 1
    }

    @Test
    fun `user-edited translation invalidates the layout, OCR identity untouched (gate 7-3)`() {
        val io = FakeChapterDocumentIo()
        val blocks = listOf(
            block("p4_b0", x = 100f, y = 100f, w = 300f, h = 120f, text = "ORIGINAL TEXT", score = 0.9f),
        )
        val prepared = prepare(blocks)
        publishThroughStore(io, prepared)

        // The user edits the translation AFTER publication. Geometry inputs
        // (dims, sample size, artifact ids, font) are all unchanged — the
        // persisted geometry no longer matches the content and must re-plan.
        val edited = listOf(blocks[0].copy(translation = "USER EDITED TEXT", userEditedAt = 999L))
        val result = hydratorFor(io).hydrate(
            pageKey = PAGE_KEY,
            blocks = edited,
            pageWidth = prepared.plan.pageWidth,
            pageHeight = prepared.plan.pageHeight,
            bindPageWidth = prepared.plan.pageWidth.toInt(),
            bindPageHeight = prepared.plan.pageHeight.toInt(),
            decodeSampleSize = 1,
            expectedCompatibilityFingerprint = prepared.compatibilityFingerprint,
        )
        result.shouldBeInstanceOf<HydratedLayout.Incompatible>()
    }

    @Test
    fun `unknown newer sidecar version is UnsupportedVersion and bytes are preserved (T924-SC-13)`() {
        val io = FakeChapterDocumentIo()
        val blocks = listOf(block("p5_b0", x = 100f, y = 100f, w = 300f, h = 120f, text = "HELLO", score = 1f))
        val prepared = prepare(blocks)
        publishThroughStore(io, prepared)

        // Simulate a future-schema writer: bump the schemaVersion in the bytes.
        val store = artifactStore(io)
        val pointer = store.readManifest()!!.layoutPlans.getValue(PAGE_KEY)
        val futureJson = io.read(pointer.fileName)!!.decodeToString()
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        io.files[pointer.fileName] = futureJson.toByteArray()

        val result = hydratorFor(io).hydrate(
            pageKey = PAGE_KEY,
            blocks = blocks,
            pageWidth = prepared.plan.pageWidth,
            pageHeight = prepared.plan.pageHeight,
            bindPageWidth = prepared.plan.pageWidth.toInt(),
            bindPageHeight = prepared.plan.pageHeight.toInt(),
            decodeSampleSize = 1,
            expectedCompatibilityFingerprint = prepared.compatibilityFingerprint,
        )
        result shouldBe HydratedLayout.UnsupportedVersion(2)
        // Never quarantined, never overwritten.
        io.files.containsKey("${pointer.fileName}.corrupt") shouldBe false
        io.files[pointer.fileName] shouldNotBe null
    }

    @Test
    fun `corrupt sidecar is quarantined and hydration falls back to Absent (gate 7-7 corrupt leg)`() {
        val io = FakeChapterDocumentIo()
        val blocks = listOf(block("p6_b0", x = 100f, y = 100f, w = 300f, h = 120f, text = "HELLO", score = 1f))
        val prepared = prepare(blocks)
        publishThroughStore(io, prepared)

        val store = artifactStore(io)
        val pointer = store.readManifest()!!.layoutPlans.getValue(PAGE_KEY)
        io.files[pointer.fileName] = "{not json".toByteArray()

        val result = hydratorFor(io).hydrate(
            pageKey = PAGE_KEY,
            blocks = blocks,
            pageWidth = prepared.plan.pageWidth,
            pageHeight = prepared.plan.pageHeight,
            bindPageWidth = prepared.plan.pageWidth.toInt(),
            bindPageHeight = prepared.plan.pageHeight.toInt(),
            decodeSampleSize = 1,
            expectedCompatibilityFingerprint = prepared.compatibilityFingerprint,
        )
        result shouldBe HydratedLayout.Absent
        io.files.containsKey("${pointer.fileName}.corrupt") shouldBe true
    }

    @Test
    fun `masked blocks rehydrate with rebuilt component geometry`() {
        val io = FakeChapterDocumentIo()
        val pageMask = BubbleMaskRle(
            width = 1000,
            height = 1400,
            bounds = listOf(0, 0, 1000, 1400),
            runs = listOf(0, 1000 * 1400),
            score = 1f,
        )
        val masked = TranslationBlock(
            blockId = "p7_b0",
            text = "orig",
            translation = "MASKED TEXT",
            width = 200f,
            height = 80f,
            x = 100f,
            y = 100f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
            score = 1f,
            segmentationMask = pageMask,
        )
        val prepared = prepare(listOf(masked))
        prepared.plan.blocks.single().maskComponentRef.shouldNotBeNull()
        publishThroughStore(io, prepared)

        // Resolve the durable ref back to the block's own mask geometry
        // (production: MaskGeometry.fromOrderedRle over segmentationMask).
        val geometry = MaskGeometryResolverStub.resolve(masked)
        geometry.shouldNotBeNull()
        val hydrator = PersistedLayoutHydrator(
            resolve = {
                val manifest = artifactStore(io).readManifest()!!
                PersistedLayoutHydrator.PersistedPlanRef(
                    manifest.layoutPlans.getValue(PAGE_KEY),
                    manifest.pages.getValue(PAGE_KEY).layout?.fingerprint,
                )
            },
            readDocument = { pointer ->
                artifactStore(io).readSidecarDocument(
                    pointer,
                    PageLayoutDrawPlan.serializer(),
                    PageLayoutDrawPlan.SCHEMA_VERSION,
                    PageLayoutDrawPlan.KIND,
                    { it.schemaVersion },
                    { it.kind },
                    { it.validationError() == null },
                )
            },
            fontSha256 = { FAKE_ASSET_SHA256 },
            maskGeometryResolver = { geometry },
            platformShapingKey = { PLATFORM_KEY },
        )
        val result = hydrator.hydrate(
            pageKey = PAGE_KEY,
            blocks = listOf(masked),
            pageWidth = prepared.plan.pageWidth,
            pageHeight = prepared.plan.pageHeight,
            bindPageWidth = prepared.plan.pageWidth.toInt(),
            bindPageHeight = prepared.plan.pageHeight.toInt(),
            decodeSampleSize = 1,
            expectedCompatibilityFingerprint = prepared.compatibilityFingerprint,
        )
        val resolved = result.shouldBeInstanceOf<HydratedLayout.Resolved>()
        resolved.layouts.single().maskGeometry shouldBe geometry
        // Synthetic per-page group id restored so the overlay's component-clip
        // cache can rebuild the structural component path (never re-planned).
        resolved.layouts.single().planGeometryId.shouldNotBeNull()
    }

    private object MaskGeometryResolverStub {
        fun resolve(block: TranslationBlock): MaskGeometry? {
            val mask = block.segmentationMask ?: return null
            return eu.kanade.translation.segmentation.MaskGeometry.fromOrderedRle(
                mask,
                eu.kanade.translation.segmentation.MaskConversionBudgets(),
            ).let { result ->
                when (result) {
                    is eu.kanade.translation.segmentation.OrderedMaskResult.Success -> result.geometry
                    is eu.kanade.translation.segmentation.OrderedMaskResult.Fallback -> null
                }
            }
        }
    }

    private companion object {
        const val PAGE_KEY = "page-001.jpg"
        const val PLATFORM_KEY = "sdk34-UPSIDE_DOWN_CAKE"
        val FAKE_ASSET_SHA256 = "aa".repeat(32)
    }
}
