# PageTextRenderer removal plan

## Recommendation

Remove PageTextRenderer only after its useful tests are relocated to the live TranslationOverlayView path or their real owner. Keep ComponentClipCache, which currently shares the PageTextRenderer source file but is used by the production overlay. The removal is safe as a narrow, no-layout-logic change if performed in the two commits described below.

No production or test source was changed for this plan.

## Evidence and removal decision

- **VERIFIED:** PageTextRenderer has no production caller. ReaderPageImageView creates TranslationOverlayView and binds translated blocks to it (app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt:280-305). The overlay calls TextLayoutPlanner and does the live painting itself (TranslationOverlayView.kt:67-74,130-245). All direct PageTextRenderer construction found in the checkout is in PageTextRendererInstrumentedTest.
- **VERIFIED:** deleting the whole source file without an extraction would break the live path because ComponentClipCache is declared at the end of PageTextRenderer.kt (app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt:324-378) and the overlay uses it (TranslationOverlayView.kt:83,106).
- **VERIFIED:** the renderer and live overlay deliberately differ: StaticLayout is used by PageTextRenderer for positioned and horizontal forms (PageTextRenderer.kt:137-195), while the overlay draws directly with Canvas drawText (TranslationOverlayView.kt:192-243). Vertical behavior differs too: renderer grapheme/rotation behavior is at PageTextRenderer.kt:215-257; overlay Char/punctuation behavior is at TranslationOverlayView.kt:147-173,266-273.
- **CONTRADICTION:** PageTextRenderer invalid metadata fails closed by omitting a prepared layout (PageTextRenderer.kt:45-59), whereas the live overlay treats unavailable component metadata as an absent component path and still prepares/draws the layout (TranslationOverlayView.kt:97-110). The former is not an observable reader contract and must not be migrated as a required behavior.
- **VERIFIED:** TranslationOverlayView already has Android instrumentation for component, cell, and collision-clip intersection on legacy, positioned, and vertical forms (app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewInstrumentedTest.kt:34-72).

The technically preferred removal is deletion, not a renderer substitution. Switching the live overlay to PageTextRenderer would be an output change and is out of scope for a cleanup.

## Exact file changes

### Production files

| Action | File | Required change |
|---|---|---|
| Add | app/src/main/java/eu/kanade/translation/rendering/ComponentClipCache.kt | Move ComponentClipCache, Entry, counters, resolve, clear, and pack from PageTextRenderer.kt:324-378 unchanged. Retain package, internal visibility, generic API, cap-before-create behavior, and identity check. |
| Delete | app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt | Delete the renderer class after ComponentClipCache has moved. This removes the unused StaticLayout/Path painter only. |
| Edit comment | app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt:219-226 | Remove the PageTextRenderer convention reference. State the actual direct drawText positioned-line convention without implying StaticLayout parity. No executable change. |
| Edit comments | app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt:106-108,518-522 | Replace historical PageTextRenderer ownership wording with generic previous renderer/live overlay wording; retain planner-draw separation statement. |
| Edit comments | app/src/main/java/eu/kanade/translation/rendering/RenderColorEstimator.kt:32-36,53-54,98-100 | Replace PageTextRenderer ownership references with the live overlay renderer or generic render stage. No color logic change. |
| Edit comment | app/src/main/java/eu/kanade/translation/model/PageTranslationHelper.kt:112 | Remove stale PageTextRenderer link from layout commentary. |

### Test and comment files

| Action | File | Required change |
|---|---|---|
| Add/extend | app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewInstrumentedTest.kt | Keep existing three generic hard-clip tests; add the exact migrated clip/cell/disconnected/shared-cell tests below. |
| Add | app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewRenderingInstrumentedTest.kt | New production-painter test class for legacy and positioned painting behavior listed below. Share a small test fixture if necessary; do not put paint implementation into tests. |
| Add | app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewLifecycleInstrumentedTest.kt | New rebind/clear state test listed below. |
| Add | app/src/androidTest/java/eu/kanade/translation/rendering/RenderColorEstimatorInstrumentedTest.kt | Move the Bitmap recomputeFor test to its actual owner. |
| Delete | app/src/androidTest/java/eu/kanade/translation/rendering/PageTextRendererInstrumentedTest.kt | Delete only after every mapped test below is covered or deliberately retired. |
| Rename/edit | app/src/test/java/eu/kanade/translation/rendering/PageTextRendererDirectionTest.kt | Rename file and class to TextLayoutPlannerDirectionTest; it is a pure planner test and never creates the renderer (current source :17-21). |
| Edit comments | app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerTest.kt:11; TextLayoutPlannerQualityRepairTest.kt:27; RenderColorEstimatorLayoutSamplingTest.kt:13 | Remove stale PageTextRenderer historical/test links. Point the color comment to RenderColorEstimatorInstrumentedTest. |
| Keep unchanged | app/src/test/java/eu/kanade/translation/rendering/ComponentClipCacheTest.kt | This is the JVM contract suite for the extracted cache; package/API remain identical. |
| Keep unchanged | app/src/test/java/eu/kanade/translation/rendering/TextLineBreakerTest.kt | This already owns source-hyphen and atomic-Latin policy coverage. |

## PageTextRendererInstrumentedTest disposition matrix

All source line references below are in app/src/androidTest/java/eu/kanade/translation/rendering/PageTextRendererInstrumentedTest.kt.

| Current test case | Disposition and exact target | Rationale |
|---|---|---|
| concaveMaskHoleAndThickStrokeHaveZeroAlphaOutsideAssignedComponent (18) | **Migrate** to TranslationOverlayViewInstrumentedTest.concaveMaskHoleAndThickStrokeHaveZeroAlphaOutsideAssignedComponent | This is structural component clipping, which the live overlay applies at TranslationOverlayView.kt:185-215. Preserve concavity and thick-stroke pixel proof on production painter. |
| componentClipIntersectsCellRect (33) | **Retain elsewhere** in existing TranslationOverlayViewInstrumentedTest.legacyLayoutClipsToComponentThenCellThenCollisionRect, positionedLayoutClipsToComponentThenCellThenCollisionRect, and verticalLegacyLayoutUsesTheSameHardClipIntersection | Those three existing tests already prove the same ordered intersection for every production layout form. Do not duplicate a fourth generic intersection fixture solely because the dead renderer had one. |
| disconnectedMaskClipsToAssignedComponentOnly (85) | **Migrate** to TranslationOverlayViewInstrumentedTest.disconnectedMaskClipsToAssignedComponentOnly | Assigned-component-only clipping is a live safety guarantee; test the production cache/path application. |
| invalidDimensionsFailClosed (99) | **Intentionally retire**; no replacement test for fail-closed omission | It asserts PageTextRenderer-only behavior. Production overlay instead degrades to available cell/legacy clips and draws (TranslationOverlayView.kt:77-110), which is compatible with planner visibility-first fallbacks. Migrating it would test the wrong user-visible contract. |
| fractionalCanvasTransformStillHasZeroSourcePixelsOutsideMask (109) | **Migrate** to TranslationOverlayViewRenderingInstrumentedTest.fractionalCanvasTransformKeepsLegacyLayoutInsideComponent | Preserve Android clip rasterization proof under fractional canvas transform for the live painter. The test can use drawLayoutsForTest with the same transform; SSIV mapping is outside the removed renderer. |
| colorRecomputeSamplesTheCurrentBlockRectangle (131) | **Retain elsewhere** as RenderColorEstimatorInstrumentedTest.recomputeForSamplesTheCurrentBlockOcrRectangle | It tests RenderColorEstimator.recomputeFor, not PageTextRenderer. Keep the same Bitmap fixture and assertions under the colour owner. |
| rapidRebindAndClearCannotDrawStaleState (152) | **Migrate** to TranslationOverlayViewLifecycleInstrumentedTest.rebindAndClearCannotDrawStalePreparedLayouts | Reader recycling/rebind is production lifecycle behavior. Use bindLayoutsForTest, then public clear, then drawLayoutsForTest to prove stale prepared layouts are absent. |
| unmaskedVerticalCjkMatchesLegacyPixelsExactly (169) | **Migrate** to TranslationOverlayViewRenderingInstrumentedTest.unmaskedVerticalCjkMatchesLegacyPixelsExactly | This target must use the overlay’s current Char/punctuation legacy painter as the expected implementation. It preserves actual reader output, not PageTextRenderer grapheme behavior. |
| mixedVerticalLatinRotationChangesInkOrientationWithinConservativeBounds (182) | **Intentionally retire**; no replacement | The assertion covers PageTextRenderer’s rotated-Latin vertical strategy. Live overlay does not rotate Latin (TranslationOverlayView.kt:147-173). Migrating it would introduce a behavior requirement absent from the reader. |
| unmaskedHorizontalAndVerticalRenderOnSoftwareCanvas (197) | **Migrate** to TranslationOverlayViewRenderingInstrumentedTest.unmaskedHorizontalAndVerticalRenderOnSoftwareCanvas | Keep the basic Android software-canvas smoke test for both production legacy forms. |
| positionedAdaptiveLinesStayInsideComponentWithThickStrokeAndComplexGlyphs (210) | **Migrate** to TranslationOverlayViewRenderingInstrumentedTest.positionedLinesStayInsideComponentWithThickStrokeAndComplexGlyphs | Complex glyphs, thick stroke, concavity, and clip containment remain valuable live-painter pixel coverage. The direct painter does not shape StaticLayouts, so retain only observed one-line/clip assertions. |
| twoAdaptiveLayoutsSharingOneComponentKeepDisjointCellFootprintsWithAlphaGap (255) | **Migrate** to TranslationOverlayViewInstrumentedTest.twoPositionedLayoutsSharingOneComponentKeepDisjointCellFootprintsWithAlphaGap | Cell-rect hard clips and gap preservation are live structural safety behavior; use the same two-layout fixture against the overlay. |
| sourceHyphenAndAtomicOverwideWordShapeExactlyOneLine (287) | **Retain elsewhere** in TextLineBreakerTest.a source hyphen ends its token but stays attached and TextLineBreakerTest.ordinary latin tokens are atomic and never hyphenated by prewrap | Its StaticLayout maxLines assertion belongs only to the deleted renderer. The actual line-break content contract is already covered by the pure owner; overlay drawText draws each planned PositionedLine as one canvas call. |
| fractionalTransformKeepsPositionedLinesInsideComponent (313) | **Migrate** to TranslationOverlayViewRenderingInstrumentedTest.fractionalCanvasTransformKeepsPositionedLinesInsideComponent | Preserve fractional-transform structural clipping for the live positioned path. |
| positionedLinesAreLeftAlignedInsideTheirPlannedWidth (335) | **Migrate** to TranslationOverlayViewRenderingInstrumentedTest.positionedLinesAreLeftAlignedAtTheirPlannedLeftCoordinate | The live painter explicitly sets LEFT alignment and draws at leftPx (TranslationOverlayView.kt:228-243). Keep the pixel assertion against that actual behavior. |

This maps all 15 instrumentation test cases. The two intentional retirements are renderer-private behavior that contradicts or exceeds live overlay behavior; neither is a planner safety regression.

## Ordered implementation

1. Run the baseline commands below with an API 26+ device/emulator and save results.
2. Add the new/extended overlay and color tests while PageTextRenderer and its test still exist. Do not delete or edit production code in this first commit. The new tests must pass alongside the old test suite.
3. Move ComponentClipCache unchanged into ComponentClipCache.kt. Run ComponentClipCacheTest and compilation before deleting the old renderer file.
4. Delete PageTextRenderer.kt and PageTextRendererInstrumentedTest.kt together. This prevents a dead renderer from retaining a separate test-only contract.
5. Rename PageTextRendererDirectionTest to TextLayoutPlannerDirectionTest and update the class name/import references.
6. Update only the stale comments listed above. Then assert the source tree has no PageTextRenderer references or file.
7. Run the complete removal gate. If any migrated live-painter test fails, restore PageTextRenderer only by reverting the removal commit; do not change overlay drawing behavior as an unreviewed deletion fix.

## Exact verification commands

Run from the repository root with a configured JDK and an Android 8.0/API-26-or-newer connected emulator/device.

    .\gradlew.bat :app:compileDevDebugKotlin :app:compileDevDebugAndroidTestKotlin --console=plain

    .\gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.rendering.ComponentClipCacheTest" --tests "eu.kanade.translation.rendering.TextLineBreakerTest" --tests "eu.kanade.translation.rendering.TextLayoutPlannerDirectionTest" --tests "eu.kanade.translation.rendering.TextLayoutPlannerMaskMetadataTest" --tests "eu.kanade.translation.rendering.TextLayoutPlannerSlice5Test" --console=plain

    .\gradlew.bat :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewInstrumentedTest --console=plain

    .\gradlew.bat :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewRenderingInstrumentedTest --console=plain

    .\gradlew.bat :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewLifecycleInstrumentedTest --console=plain

    .\gradlew.bat :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.translation.rendering.RenderColorEstimatorInstrumentedTest --console=plain

    rg -n "PageTextRenderer" app/src/main app/src/test app/src/androidTest
    git diff --check

The final ripgrep command must return no references. If a retained historical comment is intentionally approved, it must be excluded explicitly and documented; this plan recommends zero references.

## Risks and rollback

| Risk | Mitigation | Rollback |
|---|---|---|
| ComponentClipCache is accidentally deleted with renderer | Move it unchanged first; keep its JVM suite and verify overlay compile. | Revert only the cache-move/removal commit; no layout data changes exist. |
| Test migration silently tests a helper rather than production painter | New tests must instantiate TranslationOverlayView and use its test bind/draw seams. | Revert the test/deletion commit and restore dead renderer until live coverage is corrected. |
| PageTextRenderer output is accidentally treated as reader baseline | Migrate only contracts actually implemented by overlay; retire renderer-only rotation/fail-closed tests. | No production behavior changes; revert deletion and revise test map. |
| Larger Android test suite becomes flaky because of font rasterization | Assert containment/gaps/alignment ranges rather than device-specific full bitmaps except the overlay’s own explicit legacy parity fixture. Run API 26+ gate. | Retain deterministic geometric assertions and investigate device/font configuration separately. |
| Stale comments suggest a deleted owner | Zero-reference grep and comment-only cleanup are mandatory in the deletion commit. | Amend comment-only commit; no runtime rollback required. |

## Proposed small commit boundary

Commit 1: test migration only. Add the three overlay/color Android test destinations and extend the existing overlay clip suite; old PageTextRenderer source and test remain. This establishes production coverage before deletion.

Commit 2: removal only. Move ComponentClipCache unchanged to its own file, delete PageTextRenderer.kt and PageTextRendererInstrumentedTest.kt, rename the pure direction test, and update the listed stale comments. No planner, overlay draw, DTO, budget, or segmentation logic changes are allowed.

**VERIFIED:** this boundary is reversible because the removed renderer has no live caller. **STRONG INFERENCE:** it minimizes review scope and prevents renderer-removal approval from becoming implicit approval for a rendering-engine replacement.

