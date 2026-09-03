# Text-layout renderer architecture and extraction audit

## Recommendation

Extract by ownership boundaries, not by rewrite. First move shared contracts and rules, characterize the production overlay, then mechanically move mask orchestration, legacy placement, and special resolution policies until TextLayoutPlanner is only a page coordinator. Do not replace the live overlay with PageTextRenderer during this work: they have different rendering semantics.

No production or test source was changed.

## Evidence conventions

VERIFIED means direct source, test, or command evidence. STRONG INFERENCE follows from verified data flow but needs characterization before it is treated as a compatibility guarantee. CONTRADICTION identifies an implementation conflict with accepted architecture intent. UNKNOWN was not established. All file:line references are primary evidence from this checkout.

## Key findings

1. **VERIFIED — TextLayoutPlanner is the remaining god file.** It is 3,793 LOC (168,350 bytes); the next largest rendering file is 454 LOC. Its page loop is 356 LOC and owns score ordering, per-input outcomes, page budgets, mask grouping and cells, layout routing, accepted footprints, metadata, and render order (app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt:622-977). It also contains the 363-LOC final resolver (:1759-2121), 267-LOC legacy placer (:2200-2466), 197-LOC growth algorithm (:2573-2769), and mask assembly/integration (:3118-3421).

2. **VERIFIED — TranslationOverlayView, not PageTextRenderer, is the reader’s production drawing path.** ReaderPageImageView creates and binds TranslationOverlayView (app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt:280-305). The overlay invokes the planner, prepares component paths, applies SSIV source-to-view transforms, and draws text itself (TranslationOverlayView.kt:67-74,77-111,130-245). A full main-source reference search found no PageTextRenderer instantiation; its direct uses are instrumentation tests, e.g. PageTextRendererInstrumentedTest.kt:48-51. A PageTextRenderer-only gate cannot certify reader output.

3. **VERIFIED — two Android renderers duplicate paint/clip/draw responsibilities, but are not interchangeable.** Both configure fill/stroke, intersect component/cell/collision clips, and draw every layout form (PageTextRenderer.kt:68-84,100-125,175-257; TranslationOverlayView.kt:175-245). The test renderer uses StaticLayout (PageTextRenderer.kt:137-195), while the live overlay uses direct drawText (TranslationOverlayView.kt:208-243). Vertical rendering also differs: grapheme clusters plus rotation in PageTextRenderer.kt:215-257 versus Char iteration and punctuation substitution in TranslationOverlayView.kt:147-173,266-273. This duplication is the first extraction risk.

4. **VERIFIED — good modules exist, but core dependencies still point back into the god file.** MaskTextRegionPlanner isolates pure cell partitioning (MaskTextRegionPlanner.kt:10-63); AdaptiveBandPlanner isolates bounded band fit (AdaptiveBandPlanner.kt:60-454); TextLineBreaker isolates wrap/trial logic (TextLineBreaker.kt:27-217). But adaptive bands and breaker call TextLayoutPlanner for stroke/CJK rules (AdaptiveBandPlanner.kt:283; TextLineBreaker.kt:65-71), and TextLayoutTuning calls the planner stroke method (TextLayoutPlanner.kt:373-394). Move contracts and rules before moving algorithms.

5. **VERIFIED — bounded-memory/work contracts are page-scoped and must remain stateful.** The page loop reserves positioned-line and StaticLayout budgets in placement order (TextLayoutPlanner.kt:703-873). SharedMaskSession carries conversion budgets through grouping and conversion (:269-351); CellSpanBudget counts before materializing cells (MaskTextRegionPlanner.kt:40-45,72-87); both render paths cap component paths before creating them (PageTextRenderer.kt:334-366; TranslationOverlayView.kt:82-110). No BubbleMaskRle.decode call is present in rendering/live-overlay source; conversion uses MaskGeometry.fromOrderedRle (TextLayoutPlanner.kt:317-325).

6. **CONTRADICTION — independent shared-result fit is not a clean present boundary.** The T912 architecture calls for independent per-result fitting without sibling equalisation. Current code applies a post-placement same-component median font cap (TextLayoutPlanner.kt:957-973,1058-1127; tuning :508-512). It is not full equalisation, but can lower a valid sibling font. Preserve it unchanged initially behind a named SiblingFontHarmonyPolicy and characterize both binding and non-binding cases before a later product decision.

## Current ownership and data flow

    ReaderPageImageView
      -> TranslationOverlayView.bind(blocks, source page dimensions)
           -> TextLayoutPlanner.plan(..., overlay-local TextMeasurer)
                -> PageLayoutPlan.drawableInRenderOrder
           -> ComponentClipCache builds bounded component Paths
           -> onDraw applies source-to-view transform and direct Canvas drawing

    TextLayoutPlanner.planPage
      -> score/input ordering and explicit outcome contract
      -> SharedMaskSession -> MaskGeometry.fromOrderedRle
      -> MaskTextRegionPlanner.partition shared cells
      -> contained rescue / adaptive bands / legacy placement
      -> final collision resolver and visibility fallback
      -> BlockLayout hard-clip metadata

    PageTextRenderer
      -> separate bind-time StaticLayout/Path preparation strategy

**VERIFIED:** source-coordinate transformation is correctly outside planning. The overlay resolves the source page rect through SSIV and scales the canvas once (TranslationOverlayView.kt:130-144). Do not move transforms into planner, geometry, or placement modules.

| Owner | Current responsibility | Evidence | Target judgment |
|---|---|---|---|
| TranslationOverlayView | View lifecycle, SSIV transform, planner call, live painting | TranslationOverlayView.kt:67-74,77-111,130-245 | Retain lifecycle/transform; later extract unchanged source-space painter only. |
| PageTextRenderer | Separate bind-time shaped, allocation-free renderer | PageTextRenderer.kt:37-84,111-184 | Preserve separately initially; do not substitute for live overlay. |
| TextLayoutPlanner facade | Public plan/planPage, identity/order | TextLayoutPlanner.kt:574-613,622-639,948-977 | Keep as thin stable facade/coordinator. |
| Planner internals | DTOs, tuning, masks, fit, placement, rescue, safety, text rules | TextLayoutPlanner.kt:45-513,990-3748 | Split by roles below. |
| MaskGeometry / BubbleMaskRle | Compact mask persistence and bounded components | BubbleMaskRle.kt:7-45; MaskGeometry.kt:5-12,199-409 | Keep segmentation-owned and immutable. |
| MaskTextRegionPlanner | Pure disjoint cell/span partition | MaskTextRegionPlanner.kt:10-63,158-326 | Keep low-level; remove back-dependency on planner-owned primitives. |
| AdaptiveBandPlanner / TextLineBreaker | Adaptive fit and render-only hyphen trials | AdaptiveBandPlanner.kt:96-318; TextLineBreaker.kt:51-192 | Point at extracted rules, not facade statics. |
| RenderColorEstimator | Bitmap fill-colour sampling | RenderColorEstimator.kt:12-36,102-155,288-315 | Keep separate from layout extraction. |

BlockLayout is the main compatibility DTO. It combines source block, legacy placement, renderer-mode selection, mask geometry, clips, occupancy and visibility fallback (TextLayoutPlanner.kt:118-170). PageLayoutPlan separates input-order audit results from score-order draw layouts (:201-227). First move must preserve package, names, defaults, constructors and copy semantics exactly; a replacement DTO would combine API behavior change with every later extraction.

## Dependency direction

    UI / reader lifecycle
      -> overlay host + source-space painter adapters (Android)
      -> TextLayoutPlanner facade / PagePlanningCoordinator
      -> final resolver, contained rescue, legacy placer, free-text widening,
         shared-cell assembler, adaptive bands
      -> contracts, metrics, CJK/wrap/vertical/stroke rules, mask partition
      -> immutable segmentation geometry + TranslationBlock

    RenderColorEstimator -> TranslationBlock only

Pure strategies must not depend on TextLayoutPlanner merely for FloatRect, tuning, CJK classification, or stroke calculation. Android painters may consume BlockLayout; planning must not depend on Canvas, Paint, StaticLayout, View or SSIV.

A single PagePlanningCoordinator must own the shared page ledger: conversion/cell/rescue budgets, positioned-line and StaticLayout reservations, accepted footprints and component assignments. Extracted strategies receive this ledger; they must never create fresh default budgets. The painter consumes finalized BlockLayout only and must not fit/wrap at draw time. This preserves the current planner/draw separation (TextLayoutPlanner.kt:106-108; PageTextRenderer.kt:111-125).

## Ordered no-logic-change extraction slices

| Slice | Mechanical seam | Estimated ownership removed | Benefit / effort | Risk and required gate |
|---|---|---:|---|---|
| 0. Characterize live output | Add/extend TranslationOverlayView tests for legacy horizontal, vertical, positioned, clipping, source transform and rebind/clear. Add normalized-plan fixtures for identity/order/budgets/harmony. | 0 LOC | High risk reduction / S | Do not rely only on PageTextRenderer tests. Run focused JVM suite plus Android overlay and renderer suites on API 26+. |
| 1. Contracts and rules | Move TextMeasurer, FloatRect, TextAlign, layout/result DTOs to LayoutContracts; move tuning to LayoutTuning; extract StrokeMetrics, TextScriptRules and VerticalTextRules. Temporary delegates only, no changed values. | about 470 LOC | High decoupling / S | Preserve Kotlin defaults, visibility, equality/copy and float rounding. Gate DTO/default, CJK, vertical and stroke tests. |
| 2. Live Android painter seam | Extract unchanged shared paint configuration and clip application. Move the overlay direct-draw implementation intact to Android-only OverlaySourcePainter; view retains SSIV transform. Do not route through PageTextRenderer. | about 170 LOC from view | High / M | StaticLayout and drawText outputs differ. Gate new overlay pixel characterization before and after move; PageTextRenderer remains separate. |
| 3. Mask layout assembly | Move SharedMaskSession, fingerprint/equality, MaskGrouping, buildMaskRegions, shared-cell assembly and metadata wiring to MaskLayoutAssembly. Pass existing conversion budget/session in. | about 430 LOC | High / M | Do not reset page budgets, alter input-order grouping, or attach exact clips on visibility fallback. Gate MaskGeometry, MaskTextRegionPlanner and planner-mask-metadata suites. |
| 4. Legacy fit and placement | Move RectResult, wrapping/fit/overflow, free-space helpers, growth and placeBlock to LegacyBlockPlacement, called once from coordinator. | about 800 LOC | High / L | Parent/OCR/TTB anchoring and ordinary-manga legacy reshape are sensitive. Gate baseline planner/free-text/direction tests and unmasked overlay images. |
| 5. Special policies | Extract FreeTextWideningPlanner, then ContainedFitRescue, then FinalPlacementResolver. Move font harmony as unchanged named policy. | about 900 LOC | High / L | Candidate order, post-anchor recheck and budget spending must stay exact. Gate free-text, rescue, final-safety, quality/shift and missing-text repro suites; assert max eight evaluated attempts. |
| 6. Thin coordinator | Retain facade signatures; coordinator contains only score-order loop, ledger and result assembly. | remaining 1,000+ LOC reduced to a few hundred | High / M | Score-order budget reservations must not move to input order. Gate whole focused rendering JVM suite plus both Android painter suites. |
| 7. Optional convergence decision | Only after parity report, decide whether render strategies can share prepared shape data. | optional | Medium / L | A renderer swap is behavior change, not refactor. Require device pixel parity for scripts, clips, fractional transforms and allocation profiling plus explicit approval. |

The desired end state is not an arbitrary LOC target. It is a facade/coordinator that owns only page order, page ledger and result assembly: no canvas calls, mask partition implementation, stroke math or placement branch implementation.

## Contracts and regression gates

- **Identity and draw order:** nonblank input is the intentional filter; identity uses input index plus nullable block ID; score then input index controls placement and render order (TextLayoutPlanner.kt:634-639,708-715,948-977).
- **Text selection:** source-mode uses translation.ifBlank text; normal mode uses translation only (:2185-2187).
- **Ordinary manga:** unmasked/noneligible blocks fall through to placeBlock (:859-886). Specialized cells/bands must not leak into that path.
- **Clip order:** component path then cell rect then collision rect remains the structural intersection (PageTextRenderer.kt:73-80; TranslationOverlayView.kt:185-215).
- **Visibility tails:** empty shared cells use legacy-region fallback (:716-733), and exhausted final placement returns clipped/fail-open Draw instead of omission (:2088-2120).
- **Bounds before allocation:** conversion, component/cell, rescue, band-line and static-layout caps are preconditions (TextLayoutPlanner.kt:703-873; MaskGeometry.kt:221-409; MaskTextRegionPlanner.kt:40-45).

Move pure tests with their new owner. Keep facade integration tests for ordering and shared-ledger interactions. Preserve TextLayoutPlanner.plan and planPage as test entry points throughout; temporary delegated test seams are safer than production API expansion.

Existing overlay instrumentation proves common component/cell/collision clip intersection for legacy, positioned and vertical forms (TranslationOverlayViewInstrumentedTest.kt:34-72), but does not characterize live painter parity, source transform or lifecycle. Add those before slice 2. Existing PageTextRenderer instrumentation proves its own concave/thick-stroke clipping (PageTextRendererInstrumentedTest.kt:18-81,209-322), but cannot certify the reader.

Use a same-input normalized-plan fixture after every slice: identity, text, render ordinal, layout mode, origin/safe box/font/stroke/alignment, clips, lines and maskUsable. Exact equality applies to fake-measurer cases and integer cells/spans; Android metric tolerances must be explicit.

## Performance, memory and lifecycle

- **VERIFIED:** planner math is Android-free behind TextMeasurer (TextLayoutPlanner.kt:18-37,40-64); preserve JVM testability.
- **VERIFIED:** dense BubbleMaskRle.decode allocates page-size bytes (BubbleMaskRle.kt:33-34) and is absent from live layout; retain ordered bounded conversion only.
- **VERIFIED:** PageTextRenderer.bind builds reusable objects and draw consumes prepared data (PageTextRenderer.kt:37-84,137-184). Its post-bind draw remains allocation-free.
- **STRONG INFERENCE:** extraction stays allocation-neutral only if it does not introduce request/context objects per candidate or line. Keep the existing page-owned ledger and benchmark representative manga and long-strip manhwa after slices 3, 5 and 6.
- **UNKNOWN:** local Android compilation was not established. gradlew.bat :app:compileDevDebugAndroidTestKotlin stopped before Gradle because JAVA_HOME and java were unavailable. The build declares Android test support (app/build.gradle.kts:363-367); this is neither source pass nor source failure.

## Independent extraction-risk review

| Priority | Finding | Review acceptance question |
|---|---|---|
| P0 | Live overlay and test renderer differ. | Does the slice preserve TranslationOverlayView semantics and run its Android tests? |
| P0 | Budgets are page-scoped. | Is one ledger passed through all strategies with checks before work/allocation? |
| P0 | Visibility fallback must not become omission. | Does each nonblank input retain a Draw or explicit NonDraw, with intentional fallbacks still Draws? |
| P1 | Ordinary manga depends on legacy path. | Are unmasked/noneligible fixtures decision and pixel compatible? |
| P1 | Font harmony is live policy. | Is it moved unchanged and tested both when it binds and does not bind? |
| P1 | DTO defaults select draw behavior. | Are null/default/copy semantics for positioned lines and mask usability unchanged? |

Every extraction PR should prove no change to thresholds, rounding, tie breakers, fallback order or signatures; no dense mask allocation or page-cap reset; and actual reduction of a planner responsibility rather than a wrapper around it.

## Decision

Start with slices 0 and 1, then the live Android painter seam (slice 2) before changing planner internals. This resolves the dominant maintainability and validation gap while preserving behavior. Treat slices 3–6 as independently reviewed mechanical extractions, not a rewrite or a renderer replacement.

