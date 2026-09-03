# T919 — PageTextRenderer removal plan

## Recommendation

Remove the unused `PageTextRenderer` class in two small commits. Do not change
the live `TranslationOverlayView` draw logic or replace it with the removed
renderer. First preserve relevant assertions on the production overlay and
extract `ComponentClipCache` unchanged, because the live overlay uses that
cache even though it is currently declared in `PageTextRenderer.kt`.

## Commit 1 — Preserve live behavior with tests only

No production deletion or drawing change.

- Extend `TranslationOverlayViewInstrumentedTest.kt` with concave/thick-stroke
  containment, disconnected component isolation, and two positioned layouts'
  shared-component cell-gap proofs.
- Add `TranslationOverlayViewRenderingInstrumentedTest.kt` for fractional
  transforms (legacy and positioned), vertical CJK, software-canvas smoke,
  complex-glyph/stroke containment, and positioned-line left origin.
- Add `TranslationOverlayViewLifecycleInstrumentedTest.kt` for rebind/clear
  stale-state safety.
- Add `RenderColorEstimatorInstrumentedTest.kt`, moving the bitmap colour
  sampling case to its real owner.
- Keep the existing PageTextRenderer source/tests until these new tests pass.

## Commit 2 — Mechanical deletion

- Add `rendering/ComponentClipCache.kt`, moving the existing cache unchanged.
- Delete `rendering/PageTextRenderer.kt` and
  `PageTextRendererInstrumentedTest.kt`.
- Rename `PageTextRendererDirectionTest.kt` to
  `TextLayoutPlannerDirectionTest.kt`; all eight cases remain because they test
  planner direction rules, not the renderer.
- Update live-source and test comments that name the removed renderer:
  `TranslationOverlayView.kt`, `TextLayoutPlanner.kt`,
  `RenderColorEstimator.kt`, `PageTranslationHelper.kt`,
  `docs/ARCHITECTURE.md`, `TextLayoutPlannerTest.kt`,
  `TextLayoutPlannerQualityRepairTest.kt`, and
  `RenderColorEstimatorLayoutSamplingTest.kt`.

## Instrumentation case disposition

### Migrate to the live overlay

- `concaveMaskHoleAndThickStrokeHaveZeroAlphaOutsideAssignedComponent`
- `disconnectedMaskClipsToAssignedComponentOnly`
- `fractionalCanvasTransformStillHasZeroSourcePixelsOutsideMask`
- `rapidRebindAndClearCannotDrawStaleState`
- `unmaskedVerticalCjkMatchesLegacyPixelsExactly`
- `unmaskedHorizontalAndVerticalRenderOnSoftwareCanvas`
- `positionedAdaptiveLinesStayInsideComponentWithThickStrokeAndComplexGlyphs`
- `twoAdaptiveLayoutsSharingOneComponentKeepDisjointCellFootprintsWithAlphaGap`
- `fractionalTransformKeepsPositionedLinesInsideComponent`
- `positionedLinesAreLeftAlignedInsideTheirPlannedWidth`

### Retain under its actual owner or existing coverage

- `componentClipIntersectsCellRect`: existing overlay legacy/positioned/vertical
  hard-clip tests already cover the live contract; strengthen only if their
  independent path-vs-cell fixture is insufficient.
- `colorRecomputeSamplesTheCurrentBlockRectangle`: move to
  `RenderColorEstimatorInstrumentedTest`.
- `sourceHyphenAndAtomicOverwideWordShapeExactlyOneLine`: keep text policy in
  `TextLineBreakerTest`; retire only the alternate renderer's StaticLayout
  one-line assertion.

### Intentionally retire as alternate-renderer-only behavior

- `invalidDimensionsFailClosed`: live overlay deliberately draws with the
  available fallback clips rather than omitting text.
- `mixedVerticalLatinRotationChangesInkOrientationWithinConservativeBounds`:
  the live overlay does not rotate Latin glyphs. Record/characterize current
  overlay mixed-script behavior before deletion; do not import an unused
  renderer's rotation requirement.

## Required gates

1. JDK configured, then compile production and Android test source.
2. Run `ComponentClipCacheTest`, `TextLineBreakerTest`, planner direction,
   mask-metadata, and adaptive-band JVM suites.
3. Run all three overlay instrumentation suites plus the estimator test on an
   Android 8.0/API-26-or-newer device/emulator.
4. Confirm repeated overlay draws do not rebuild component paths or introduce
   unbounded application-owned allocations.
5. `rg -n "PageTextRenderer" app/src/main app/src/test app/src/androidTest`
   returns no stale live/test references; review docs separately.
6. Use containment/gap/range assertions rather than cross-renderer pixel
   equality: the removed adapter used `StaticLayout`; production uses direct
   `Canvas.drawText()`.

## Rollback

Revert the second commit only if compile, source-reference, or migrated
production-overlay tests fail. Do not modify live overlay behavior merely to
make a removed renderer's former test pass.

## Detailed evidence

- Engineering plan: `engineering/page-text-renderer-removal-plan.md`
- Independent review: `review/page-text-renderer-removal-review.md`
