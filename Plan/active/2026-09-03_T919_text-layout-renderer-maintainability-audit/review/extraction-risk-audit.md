# Extraction-risk audit — independent review

## Verdict

The proposed incremental split is feasible and would substantially reduce the
3,793-line `TextLayoutPlanner.kt`, but it is **not safe as a file-moving
exercise**. The current implementation has two Android draw paths, a plan model
whose clip fields have overlapping authority, and several current helper
dependencies pointing from the proposed leaves back to the orchestrator.

The safe extraction order is therefore constrained: establish the shared pure
contracts/primitives first, preserve the complete `PageLayoutPlan` boundary,
then separate geometry, wrapping/fitting, and orchestration. The Android draw
adapter needs an explicit decision and parity gate before it is treated as a
single module. No production code or tests were changed for this review.

## Scope and evidence method

Reviewed live rendering/layout source, direct reader call sites, focused JVM
tests and instrumentation tests. The T912 architecture and architecture-review
reports were used only to identify intended legacy contracts. Evidence labels
mean: VERIFIED = directly established in current code/tests; CONTRADICTION =
current source conflicts with the cited earlier architecture; UNKNOWN = a
needed fact is not proven by the reviewed scope.

## Findings

### R1 — The production renderer is a separate implementation from the tested renderer

- **Severity:** HIGH
- **Likelihood:** high during any Android-draw-adapter extraction
- **Classification:** design limitation with regression risk
- **Evidence status:** VERIFIED
- **Primary evidence:** `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt:67-74` calls `TextLayoutPlanner.plan()` and prepares/draws its own layouts; `TranslationOverlayView.kt:147-245` independently implements vertical, legacy horizontal, and positioned-line drawing. `app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt:37-84` is a different bind/draw pipeline. The latter prepares `StaticLayout`s during bind (`PageTextRenderer.kt:137-166`); the live overlay directly calls `Canvas.drawText()` per positioned line (`TranslationOverlayView.kt:228-244`).
- **Why it matters:** moving `PageTextRenderer` into an “Android draw adapter” does not, by itself, change the reader’s actual renderer. Consolidating the two paths without proving their equivalence can alter shaping, line breaks, vertical punctuation/rotation, clipping, allocation timing, and failure behavior. Conversely, leaving both preserves the god-file problem across modules.
- **Evidence that would refute/reduce it:** a focused Android differential corpus that renders identical `BlockLayout`s through both paths and asserts pixel/alpha bounds (including CJK vertical/mixed Latin, positioned lines, malformed metadata, concave components, and fractional transforms), followed by a call-site proof that the reader uses exactly the chosen adapter. Existing tests prove only individual paths: `PageTextRendererInstrumentedTest.kt:16-352` targets `PageTextRenderer`; the overlay test only checks clip intersection for manually constructed valid layouts (`TranslationOverlayViewInstrumentedTest.kt:34-72`).

### R2 — Invalid mask metadata intentionally fails differently in the two draw paths

- **Severity:** HIGH
- **Likelihood:** medium; metadata is conditional on mask conversion, component assignment, and page-scoped caps
- **Classification:** design limitation / externally observable behavior fork
- **Evidence status:** VERIFIED
- **Primary evidence:** `PageTextRenderer.bind()` drops a layout when geometry dimensions or either compact id are invalid (`PageTextRenderer.kt:45-57`). The production overlay instead substitutes a null component path and still retains/draws the layout (`TranslationOverlayView.kt:77-111`, `TranslationOverlayView.kt:175-216`). The planner deliberately emits slab-only or no-mask metadata on fallback paths (`TextLayoutPlanner.kt:3394-3421`).
- **Why it matters:** extraction can accidentally choose either "fail closed" or "visibility wins" as the common adapter behavior. That decision changes whether text disappears or can escape a component clip. It also undermines the claim that `BlockLayout` alone has one renderer-independent rendering contract.
- **Evidence that would refute/reduce it:** an approved, explicit policy table for every metadata state plus Android tests for both adapters (or only the selected adapter) covering dimensions mismatch, absent id, invalid component id, component-cache cap, `maskUsable=false`, slab-only fallback, and legacy `clipRect`.

### R3 — `BlockLayout` contains duplicate/partially authoritative clip representations

- **Severity:** HIGH
- **Likelihood:** high when contracts are moved out of the planner
- **Classification:** design limitation
- **Evidence status:** VERIFIED
- **Primary evidence:** `BlockLayout` carries `maskGeometry`, `planGeometryId`, `maskComponentId`, `cellRect`, legacy `clipRect`, and the nominal `HardClip` simultaneously (`TextLayoutPlanner.kt:118-169`). The planner wires the former fields and then separately mirrors only three of them into `HardClip` (`TextLayoutPlanner.kt:933-940`). Both draw implementations consume the former fields/`clipRect` rather than `hardClip` (`PageTextRenderer.kt:45-58,73-80`; `TranslationOverlayView.kt:97-110,185-191`).
- **Why it matters:** extracting a pure contracts/model module can freeze two sources of truth. A future change that updates `HardClip` but not legacy fields will silently render the old clip; a change in the reverse direction will make inspection/testing misleading. This is especially hazardous because compact ids are explicitly only valid within a page plan (`TextLayoutPlanner.kt:133-135`) while `BlockLayout` can be passed directly to either adapter.
- **Evidence that would refute/reduce it:** a contract test that constructs every permitted model state and asserts a single canonical clip interpretation, plus a decision to retain one authoritative render-clip representation during the no-logic-change extraction. There is no test located that mutates one representation independently and asserts either rejection or equivalence; current tests construct matching values (for example `TranslationOverlayViewInstrumentedTest.kt:107-113`).

### R4 — Proposed leaf modules currently depend back on the planner, creating reverse dependencies

- **Severity:** HIGH
- **Likelihood:** high if the named split is performed literally
- **Classification:** design limitation
- **Evidence status:** VERIFIED
- **Primary evidence:** the ostensibly pure line breaker delegates CJK classification to `TextLayoutPlanner` (`TextLineBreaker.kt:65-71`); the band fitter calls `TextLayoutPlanner.computeStrokeWidth()` (`AdaptiveBandPlanner.kt:282-284`); `TextLayoutTuning` itself calls that planner method (`TextLayoutPlanner.kt:373-394`); and the Android renderer calls planner grapheme/orientation helpers (`PageTextRenderer.kt:215-226`). The planner also duplicates the legacy wrapper/tokenization implementation (`TextLayoutPlanner.kt:3631-3679`) while `TextLineBreaker` promises matching behavior (`TextLineBreaker.kt:8-13,45-49`).
- **Why it matters:** moving “text fitting/wrapping” or an Android adapter first either preserves a cycle or requires behavior edits (different Unicode classification, stroke rounding, or grapheme orientation). The latter violates the requested preserve-logic migration and can make legacy vs adaptive wrapping drift.
- **Evidence that would refute/reduce it:** a dependency graph after extraction in which contracts/primitives own CJK classification, grapheme/vertical orientation, stroke/AA tuning, and `TextMeasurer`; geometry, wrapping, fitting, planner orchestration, and adapter only depend downward. Characterization tests must compare legacy and adaptive token/wrap content over the existing mixed corpus and add grapheme/vertical-orientation cases; `TextLineBreakerTest.kt:73-106` covers a wrap cross-check but does not cover the renderer’s grapheme-orientation helpers.

### R5 — Current post-placement font harmony is a live cross-result behavior and conflicts with the prior architecture baseline

- **Severity:** HIGH
- **Likelihood:** high if orchestration is simplified or each result is made independent
- **Classification:** design limitation / specification contradiction
- **Evidence status:** CONTRADICTION (live behavior is VERIFIED)
- **Primary evidence:** after accepted draw layouts and collision footprints are produced, the planner applies group-level `applySiblingFontHarmony()` (`TextLayoutPlanner.kt:957-973`). It groups same-component accepted results, derives a median, and replaces layouts above the cap (`TextLayoutPlanner.kt:1057-1127`); the replacement can rewrap legacy text or rerun adaptive fitting (`TextLayoutPlanner.kt:1148-1210`). This behavior is pinned by `TextLayoutPlannerQualityRepairTest.kt:159-205`. In contrast, the explicitly relevant prior architecture says sibling font equalization is removed and each result fits independently (`Plan/active/2026-08-30_T912_text-layout-renderer/engineering/architecture.md:53,273,403-404`).
- **Why it matters:** a clean “planner orchestration” boundary can easily omit this late cross-result mutation, which changes final font sizes and lines even if per-block algorithms stay byte-for-byte identical. Retaining it also means the plan is not final at the first draw/occupancy acceptance point, so ownership must include the result-list/update protocol rather than just one-block planning.
- **Evidence that would refute/reduce it:** Director/technical decision on whether current harmony is intentional baseline behavior for T919, then characterization fixtures for both capped legacy and adaptive siblings, including render ordinal/result-list consistency after replacement. The contradiction cannot be resolved from current source alone.

### R6 — Page planning has no local cap on input cardinality and performs density-sensitive repeated work

- **Severity:** MEDIUM
- **Likelihood:** medium (depends on upstream block-count limits, which are UNKNOWN in this review)
- **Classification:** design limitation / performance and memory risk
- **Evidence status:** VERIFIED for the local absence; UNKNOWN for any upstream bound
- **Primary evidence:** `planPageInternal()` allocates result and drawable containers sized from all inputs (`TextLayoutPlanner.kt:647-651`) and loops every nonblank ordered input (`TextLayoutPlanner.kt:708-713`). For each it recreates prior extents with `drawable.map` (`TextLayoutPlanner.kt:735`) and records every footprint (`TextLayoutPlanner.kt:941-946`); later harmony again creates prior-obstacle lists (`TextLayoutPlanner.kt:1122`). Static-layout and positioned-line budgets are capped (`TextLayoutPlanner.kt:703-706,847-873`), but vertical layouts do not consume that static-layout budget, and no planner-local maximum input/result count is present in these paths.
- **Why it matters:** the existing mask/RLE caps do not bound allocations or repeated placement work for a large list of unmasked or vertical blocks. Splitting modules can turn these transient collections into additional intermediate lists, worsening main-thread planning latency and memory without changing visible logic.
- **Evidence that would refute/reduce it:** a verified upstream hard maximum passed as a documented precondition, or a planner-level bounded-input policy with explicit `LayoutResult` outcomes. Add a high-cardinality JVM characterization/performance gate covering unmasked, masked, and vertical inputs; current budget tests concentrate on mask/line/layout budgets rather than total input cardinality.

### R7 — The public compatibility wrapper discards result-level outcomes and ordering metadata

- **Severity:** MEDIUM
- **Likelihood:** high if the new planner API replaces the wrapper without a migration gate
- **Classification:** design limitation
- **Evidence status:** VERIFIED
- **Primary evidence:** `PageLayoutPlan` preserves input-order results and placement-order drawables (`TextLayoutPlanner.kt:201-226`), but `TextLayoutPlanner.plan()` immediately returns only `drawableInRenderOrder` (`TextLayoutPlanner.kt:575-586`). The live reader uses this compatibility wrapper (`TranslationOverlayView.kt:67-73`). The wrapper equality is intentionally asserted in `PageLayoutPlanContractTest.kt:143-176`, not observational equivalence of non-draw reasons at the call site.
- **Why it matters:** extracting a planner/orchestrator API risks either leaking placement order where input identity is expected or losing all non-draw diagnostics. Keeping the wrapper is valid for no-output-change migration, but deleting it changes the reader boundary and makes a later outcome-aware UI change appear to be a refactor.
- **Evidence that would refute/reduce it:** an explicit caller migration plan and tests that verify unchanged reader draw order plus defined behavior for every `LayoutOutcome.NonDraw`. Until then, retain the wrapper as an adapter at the existing call site.

## Extraction judgment by proposed boundary

| Proposed boundary | Judgment | Required preservation gate |
| --- | --- | --- |
| Pure model/contracts | Extract first, but keep one canonical clip authority and the page-scoped compact-id lifetime explicit. | `PageLayoutPlan` identity/order plus all clip-state contract tests. |
| Geometry | Already has a viable pure seam in `MaskGeometry` and `MaskTextRegionPlanner`; preserve page budgets and fallback metadata wiring. | Ordered-RLE, cell ownership/dead-zone, caps/fallback fixtures. |
| Text fitting/wrapping | Viable only after shared Unicode/stroke primitives stop depending on `TextLayoutPlanner`. | Legacy/adaptive wrap corpus, all-caps trial, CJK/grapheme orientation characterization. |
| Mask-aware placement | Keep it distinct from pure geometry; it owns assignment, fit-region choice, hard-cell metadata, and mask-usability fallback. | Component assignment, metadata, contained-fit, and final collision gates. |
| Planner orchestration | Extract after prior contracts; it must retain score ordering, all page budgets, late harmony (unless explicitly retired), and result-list mutation. | Input-result cardinality/order, budget exhaustion, post-anchor attempt/collision fixtures. |
| Android draw adapter | Highest-risk final boundary: there are currently two implementations. Do not declare it singular before choosing/validating the production path. | Android differential/pixel and invalid-metadata tests on the selected adapter. |

## Review conclusion

Proceed incrementally, with no behavioral cleanup folded into the move. The
priority risk is not the geometry or wrapping files themselves; it is the
unacknowledged adapter fork and the model/orchestrator protocols that straddle
the existing 3,793-line planner. Resolve the R1/R2 policy and R5 baseline
contradiction before accepting an Android-adapter or orchestration extraction.
