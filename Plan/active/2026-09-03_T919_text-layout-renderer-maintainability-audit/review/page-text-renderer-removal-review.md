# PageTextRenderer removal — test-preservation review

## Recommendation

Removal is acceptable only as deletion of an **unreferenced alternate draw
adapter**, after its reusable cache is relocated and the production
`TranslationOverlayView` test surface owns the renderer-relevant guarantees.
Do not mechanically port every `PageTextRenderer` assertion: several specify
`StaticLayout` or fail-closed behavior that the live overlay never had. Those
tests must either be intentionally retired with a recorded policy decision or
recast as tests of the actual reader behavior.

## Verified baseline

- **VERIFIED:** the reader calls `TextLayoutPlanner.plan()` and renders through
  `TranslationOverlayView`, not `PageTextRenderer`
  (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt:67-74,130-145`).
- **VERIFIED:** `PageTextRenderer` has no live main-source construction site in
  the reviewed source-reference search; all direct construction is in its
  instrumentation test. The overlay contains independent legacy horizontal,
  vertical, and positioned-line drawing (`TranslationOverlayView.kt:147-245`).
- **VERIFIED:** the overlay offers instrumentation seams to prepare supplied
  layouts and draw them to a supplied `Canvas`
  (`TranslationOverlayView.kt:247-257`). Its existing Android test currently
  proves only one valid clip fixture across legacy, positioned, and vertical
  forms (`app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewInstrumentedTest.kt:34-72`).
- **VERIFIED:** `PageTextRenderer` shapes positioned lines with paired,
  one-line `StaticLayout`s during `bind()` (`app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt:137-166`); the production overlay uses direct `Canvas.drawText()` and does not consume `layoutWidthPx` (`TranslationOverlayView.kt:228-244`).

## Test disposition

"Migrate" means preserve the asserted externally relevant reader guarantee in
the overlay instrumentation suite (or the correctly owned suite). "Retire"
means remove the PageTextRenderer-only assertion only after the stated policy
is accepted. A coverage gap is not permission to delete the source test.

| Existing case | Disposition | Basis / required target assertion |
| --- | --- | --- |
| `concaveMaskHoleAndThickStrokeHaveZeroAlphaOutsideAssignedComponent` (`PageTextRendererInstrumentedTest.kt:18-29`) | **Migrate** | The overlay clips `componentPath → cellRect → clipRect` (`TranslationOverlayView.kt:185-191`). Add the concave/hole + thick-stroke alpha proof; its current stepped fixture is not equivalent (`TranslationOverlayViewInstrumentedTest.kt:23-31`). |
| `componentClipIntersectsCellRect` (`PageTextRendererInstrumentedTest.kt:32-82`) | **Migrate / strengthen existing** | Retain explicit alpha checks for the path-only excluded half and cell-covered hole. The current overlay test checks an intersection generally but not this independent-path-vs-cell fixture (`TranslationOverlayViewInstrumentedTest.kt:55-72`). |
| `disconnectedMaskClipsToAssignedComponentOnly` (`PageTextRendererInstrumentedTest.kt:85-97`) | **Migrate** | No overlay test presently proves that a selected component cannot ink a disconnected component. |
| `invalidDimensionsFailClosed` (`PageTextRendererInstrumentedTest.kt:99-107`) | **Retire as a PageTextRenderer-specific expectation; coverage gap until policy test exists** | The alternate renderer drops dimension-mismatched metadata (`PageTextRenderer.kt:45-57`), while the production overlay deliberately degrades to no component path and still draws (`TranslationOverlayView.kt:77-111`). Porting this test would change reader behavior. Add a production-policy test for the chosen fallback (including available cell/legacy clips) before deletion. |
| `fractionalCanvasTransformStillHasZeroSourcePixelsOutsideMask` (`PageTextRendererInstrumentedTest.kt:109-129`) | **Migrate** | `drawLayoutsForTest(Canvas)` can receive the same fractional transform (`TranslationOverlayView.kt:254-257`), so this source-space clip assertion is directly testable. It does not replace an SSIV transform integration case. |
| `colorRecomputeSamplesTheCurrentBlockRectangle` (`PageTextRendererInstrumentedTest.kt:131-149`) | **Migrate to a RenderColorEstimator Android test** | It calls only `RenderColorEstimator.recomputeFor()` and is not renderer behavior. The estimator itself says it owns fill color, not draw mechanics (`RenderColorEstimator.kt:32-36,280-312`). |
| `rapidRebindAndClearCannotDrawStaleState` (`PageTextRendererInstrumentedTest.kt:152-166`) | **Migrate** | Overlay `bind()` recreates prepared layouts and `clear()` binds an empty page (`TranslationOverlayView.kt:67-74,114`). Test rebind and clear through the overlay seam, plus one real `onDraw`/view lifecycle case if feasible. |
| `unmaskedVerticalCjkMatchesLegacyPixelsExactly` (`PageTextRendererInstrumentedTest.kt:169-180`) | **Migrate as an overlay characterization, not as PageTextRenderer pixel equality** | The overlay has separate punctuation mapping (`TranslationOverlayView.kt:147-172,266-273`); the removed adapter uses planner glyph/orientation helpers (`PageTextRenderer.kt:215-226`). Establish the current overlay's expected CJK output using its configured font rather than asserting it matches the removed implementation. |
| `mixedVerticalLatinRotationChangesInkOrientationWithinConservativeBounds` (`PageTextRendererInstrumentedTest.kt:182-194`) | **Retire the rotation expectation; coverage gap requiring an explicit production vertical-Latin policy** | PageTextRenderer rotates `VerticalOrientation.ROTATED` glyphs (`PageTextRenderer.kt:233-257`); the overlay never rotates glyphs (`TranslationOverlayView.kt:159-170`). There is no basis to call rotation a preserved reader contract. Add an overlay characterization/specification for mixed Latin before removal, whichever behavior is chosen. |
| `unmaskedHorizontalAndVerticalRenderOnSoftwareCanvas` (`PageTextRendererInstrumentedTest.kt:197-207`) | **Migrate** | Retain a production-overlay smoke test with representative mixed scripts. |
| `positionedAdaptiveLinesStayInsideComponentWithThickStrokeAndComplexGlyphs` (`PageTextRendererInstrumentedTest.kt:210-254`) | **Migrate containment portion; retire StaticLayout-specific one-line claim** | Overlay must retain alpha containment for complex glyphs and a hole. Its direct draw path has no `StaticLayout` rewrap behavior, so the one-line `StaticLayout` assertion cannot be preserved verbatim; line-break policy remains covered by `TextLineBreakerTest.kt:73-106,179-226`. |
| `twoAdaptiveLayoutsSharingOneComponentKeepDisjointCellFootprintsWithAlphaGap` (`PageTextRendererInstrumentedTest.kt:255-284`) | **Migrate** | This is a core structural safety proof. Existing overlay test has only one layout. Preserve the dead-column and per-cell alpha bounds. |
| `sourceHyphenAndAtomicOverwideWordShapeExactlyOneLine` (`PageTextRendererInstrumentedTest.kt:287-310`) | **Split: retain token-policy coverage in TextLineBreaker tests; retire one-`StaticLayout` assertion; add overlay containment if not subsumed** | `TextLineBreakerTest.kt:34-73,179-226` covers source-hyphen/atomic-token policy. The removed adapter's `setMaxLines(1)` is not an overlay mechanism (`PageTextRenderer.kt:157-166`; `TranslationOverlayView.kt:228-244`). Do not claim its identical shaping guarantee after deletion. |
| `fractionalTransformKeepsPositionedLinesInsideComponent` (`PageTextRendererInstrumentedTest.kt:313-332`) | **Migrate** | Same overlay seam supports this; keep positioned-line-specific transformed alpha proof. |
| `positionedLinesAreLeftAlignedInsideTheirPlannedWidth` (`PageTextRendererInstrumentedTest.kt:335-352`) | **Migrate with revised name** | Overlay sets left alignment and draws at `leftPx` (`TranslationOverlayView.kt:228-243`), although it ignores `layoutWidthPx`. Assert actual left-origin behavior, not unused planned-width shaping. |
| All eight cases in `PageTextRendererDirectionTest.kt:23-76` | **Migrate/rename to a planner-direction test; do not retire** | They exercise only `TextLayoutPlanner.cjkRatio/shouldRenderVertical`, not PageTextRenderer. Their current class/file name becomes misleading after removal. |

## Findings

### P1 — Direct removal would delete production-needed `ComponentClipCache`

- **Severity:** HIGH
- **Likelihood:** high
- **Classification:** defect risk
- **Evidence status:** VERIFIED
- **Primary evidence:** `ComponentClipCache` is declared in the PageTextRenderer
  source file (`app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt:324-378`), but production overlay imports and constructs it
  (`TranslationOverlayView.kt:16,82-96`).
- **Evidence/refutation needed:** compilation will confirm the immediate break,
  but acceptance must additionally prove the moved class preserves capacity,
  span-cap, and geometry-instance fail-closed behavior already pinned by
  `app/src/test/java/eu/kanade/translation/rendering/ComponentClipCacheTest.kt:28-105`.

### P2 — PageTextRenderer test coverage cannot be declared equivalent to overlay coverage

- **Severity:** HIGH
- **Likelihood:** high
- **Classification:** design limitation
- **Evidence status:** VERIFIED
- **Primary evidence:** PageTextRenderer bind-time `StaticLayout` construction
  and all-or-nothing invalid-layout drop occur at
  `PageTextRenderer.kt:45-59,137-166`. The overlay instead preserves a prepared
  layout with missing component metadata and draws direct text
  (`TranslationOverlayView.kt:77-111,175-245`). The only current overlay Android
  test uses valid matching metadata and contains three tests
  (`TranslationOverlayViewInstrumentedTest.kt:34-53,75-114`).
- **Evidence/refutation needed:** an approved statement that the production
  overlay—not the unused adapter—is the preservation baseline, and migrated
  tests for each applicable disposition above. A byte-identical comparison to
  PageTextRenderer is neither available nor appropriate without first changing
  reader behavior.

### P3 — Vertical mixed-script behavior is an untested production policy fork

- **Severity:** MEDIUM
- **Likelihood:** medium
- **Classification:** design limitation
- **Evidence status:** VERIFIED
- **Primary evidence:** PageTextRenderer maps/rotates vertical clusters through
  `TextLayoutPlanner.verticalGlyph/verticalOrientation`
  (`PageTextRenderer.kt:215-257`); the overlay maps a limited punctuation table
  and does not rotate (`TranslationOverlayView.kt:147-172,266-273`). Its vertical
  instrumentation fixture uses only Japanese text (`TranslationOverlayViewInstrumentedTest.kt:45-52`).
- **Evidence/refutation needed:** a product decision followed by an overlay
  pixel/bounds characterization for CJK punctuation, Latin, emoji/ZWJ, and
  mixed strings. Until then, neither importing the unused rotation test nor
  dropping it proves ordinary manga is unaffected.

### P4 — The deletion has non-code and stale-reference hazards

- **Severity:** MEDIUM
- **Likelihood:** high
- **Classification:** expected maintenance work
- **Evidence status:** VERIFIED
- **Primary evidence:** live source/docs identify PageTextRenderer as the stroke
  owner or renderer: `RenderColorEstimator.kt:32-36,53-55,98-100`,
  `TextLayoutPlanner.kt:516-519`, `PageTranslationHelper.kt:111-113`,
  `TranslationOverlayView.kt:219-226`, and `docs/ARCHITECTURE.md:156`.
- **Evidence/refutation needed:** after the move, run a source-reference search
  for `PageTextRenderer` outside historical task reports, then review each
  remaining mention. Historical `Plan/active` reports should remain historical
  evidence rather than be silently rewritten; live docs/Javadocs must name the
  retained ownership accurately.

### P5 — Allocation/lifecycle guarantee has no overlay-specific regression gate

- **Severity:** MEDIUM
- **Likelihood:** medium
- **Classification:** coverage gap
- **Evidence status:** VERIFIED
- **Primary evidence:** the task constraint requires `PageTextRenderer.draw()`
  allocation-free after bind; that adapter documents its bind-time preparation
  (`PageTextRenderer.kt:111-175`). The overlay prepares paths in `bind()`
  (`TranslationOverlayView.kt:82-111`) and draws in `onDraw()`
  (`TranslationOverlayView.kt:130-145,175-245`), but the current overlay test
  only checks pixels (`TranslationOverlayViewInstrumentedTest.kt:55-72`) and
  has no allocation or repeated-frame assertion.
- **Evidence/refutation needed:** retain the equivalent reader constraint after
  removal: no component `Path`/`StaticLayout` creation or unbounded collection
  work in repeated draws after bind. Confirm with an Android allocation/profile
  probe or a justified code-level gate plus repeated-frame test; distinguish
  unavoidable Canvas/platform work from application-owned allocations.

## Acceptance criteria

1. Relocate `ComponentClipCache` before deleting the file; its six JVM cache
   cases remain green unchanged or demonstrably equivalent.
2. Complete every **Migrate** row above in the overlay or correct owner suite.
   Every **Retire** row has a recorded policy decision; no test is deleted only
   because it is inconvenient to port.
3. Add explicit overlay tests for metadata fallback, disconnected components,
   concave holes, shared-cell gap, legacy and positioned fractional transforms,
   rebind/clear, and mixed vertical-script policy.
4. Preserve the pure direction suite under a planner-oriented name and retain
   all current cases.
5. Compile the production and Android test source sets, run focused JVM cache /
   planner / line-breaker tests, and run the overlay instrumentation suite on
   Android API 26 or newer. Record device/API and results.
6. Run a source-reference deletion audit. No live import, Javadoc, architecture
   document, or test should falsely identify the removed class as the active
   renderer or stroke owner.
7. Demonstrate the retained overlay's repeated-draw allocation/lifecycle
   contract and confirm no full-page dense allocation is introduced.

## Rollback criteria

Treat cache relocation, test migration, and file deletion as separable commits.
Rollback the deletion (restore `PageTextRenderer.kt` and its retained tests) if
any migrated safety test fails, if source-reference/compile checks reveal an
unmoved dependency, if overlay output violates a decided vertical/fallback
policy, or if repeated drawing regresses allocation behavior. Do not "fix"
such a failure by silently adopting PageTextRenderer's fail-closed or
StaticLayout semantics; that would be a reader behavior change requiring a
separate decision and gate.
