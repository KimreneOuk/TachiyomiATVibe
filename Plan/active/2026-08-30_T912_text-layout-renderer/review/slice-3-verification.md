# T912 slice 3 — independent verification review

Scope: commit `22108f0` ("T912 slice 3 — disjoint shared cells and independent
fonts") on `codex/text-layout-renderer`, reviewed against
`engineering/architecture.md` revision 2 ("Exact planner contract", "Slice 3:
disjoint cells per shared component", "Slice 4" deferral, "Global budgets and
candidate limits", "Failure/fallback matrix", "Compatibility and acceptance
invariants") and `review/slice-2-verification.md` (prior baseline). Reviewer
examined the commit, current file contents, the pre-slice baseline (`6c18f3e`),
and ran both gates. Working tree clean at `22108f0` (= HEAD).

## Verdict

**ACCEPT.** No blocking findings. The slice-3 acceptance fixture (three blocks
on one continuous mask → three results, three pairwise-disjoint cells, exact
dead zone, no font equalization) is proven by code and pinned by non-vacuous
tests. The planner contract model matches the architecture verbatim, all
fallback paths keep exactly one explicit result per nonblank input, and scope
discipline is exact. All seven documented deviations were verified against
primary evidence; all are sanctioned, two with doc/coverage follow-ups
recommended (NOTE-level only).

## Findings

### BLOCKING

None.

### MEDIUM

None.

### NOTE

1. **NOTE — Vertical partitioning branch has no planner-level test.** All 12
   `MaskTextRegionPlannerTest` fixtures are horizontal or force horizontal
   (the diagonal test pins `X spread >= Y spread ⇒ horizontal`,
   `MaskTextRegionPlannerTest.kt:200-220`). The vertical branches
   (`slabRect` else-arm `MaskTextRegionPlanner.kt:329-330`, `cutBetween`
   vertical facing `:312,316-318`, `spansInNearestInterval` row projection
   `:364`) are exact axis mirrors of the tested horizontal code (verified by
   reading) and the horizontal path is exhaustively pinned, so risk is low —
   but the ortho/top/bottom mapping is exactly where a swap bug would hide.
   Recommend one vertical fixture (two stacked members, exact slab/dead-row
   values) in a later slice's test batch. Likelihood of latent defect: low.
   Classification: coverage gap, not a known defect.

2. **NOTE — The 64-distinct-pair cap path (slab `cellRect` WITHOUT geometry
   ids) is untested.** `withSharedCellMetadata`
   (`TextLayoutPlanner.kt:1468-1471`) returns `layout.copy(cellRect = slab)`
   when the cap trips — new in slice 3 (baseline gave the capped member
   nothing at all). No JVM test drives a 65th distinct `(group,component)`
   pair (neither did slice 2 test the cap itself; `PageTextRenderer.bind()`
   is Android-bound and the slab-only render path remains compile-gated).
   Correctness was verified by code reading: `bind()` keeps a layout with
   `geometry == null && cellRect != null` (`PageTextRenderer.kt:46-48` —
   `geometry == null -> null` component clip, layout still prepared) and
   `draw()` clips to `cellRect` (`:77`); a slab-only clip can only shrink the
   painted area, never merge or misplace content. Fail-safe by construction.
   Classification: coverage gap on a fail-safe path.

3. **NOTE — Doc gap behind deviation 2: the equal-width fallback carries no
   dead zone, so the architecture's "dead-zone gap at every row/column" test
   mandate can only apply to the cut path.** Architecture rev 2 defines the
   dead zone as a property of "cut `c`" and gives the fallback only as
   "deterministic equal-width cuts"; the implementer applied the explicit
   slab formula `[left + (k*W)/n, left + ((k+1)*W)/n)` with no additional
   gap (`MaskTextRegionPlanner.kt:197-206`). This reviewer assesses the
   reading as legitimate: (a) the fallback formula is the more specific
   contract; (b) layering `gapBefore/After` on top is not derivable from it
   and can empty narrow slabs (e.g. W=3, n=2, gap=2 ⇒ both cells empty ⇒
   everything NonDraw); (c) the acceptance invariant 3 ("every shared
   component pixel owned by at most one cell") holds — the fallback slabs are
   half-open and tile the bounds exactly, which is tested
   (`MaskTextRegionPlannerTest.kt:181-198` pins boundaries 0/33/66/100).
   "Dead zone owned by none" is vacuously satisfied when no dead zone exists.
   Recommend a one-line architecture addendum recording that the fallback
   path has no dead zone. Classification: design decision, documented.

4. **NOTE — Empty-cell probe 1 returns the bbox of ALL cell spans, not of the
   `∩ own-OCR` subset.** `MaskTextRegionPlanner.kt:269-272`: if any cell span
   intersects the member's OCR rect, `fitRegion = boundingBox(spans)` (whole
   cell content), whereas architecture probe 1 reads literally as
   `component ∩ assigned slab ∩ own OCR` (the subset's bbox). Consequence is
   a preference-only delta: the fit region may extend past the OCR rect but
   is always bounded by the member's own slab (spans ⊂ slab by construction,
   `:232-239`), and hard containment is the component+slab clip pair, so
   neither containment nor disjointness can be violated; probe 2 (nearest
   cut-axis interval, `:357-396`) is correctly slab-bounded and never returns
   sibling pixels. Classification: expected reading of "preferred fit
   subregion inside the hard cell" (architecture, Slice 3, "Parent boxes bias
   cuts only"). Recorded as a nuance of sanctioned deviation 3.

5. **NOTE — Single-member component groups now use component CONTENT bounds
   as region/cellRect, where baseline used the mask bounds rect.** For a
   mask with one component the two coincide (pinned:
   `TextLayoutPlannerMaskMetadataTest.kt:123-143`, cellRect = mask bounds
   `[40,30,260,100)`), but for a multi-component mask with a single block the
   assigned component's bbox is smaller than the mask bounds, so the
   placement anchor moves from mask center to component center and the
   baseline `cellRect = maskRect` becomes the tighter slab
   (`TextLayoutPlanner.kt:1390-1405`, single member ⇒ one cell over
   component bounds; `componentBounds` `:1482-1494`). This is sanctioned by
   the per-component partition mandate ("Blocks on different components of
   the same RLE never share cuts") and is exactly the implementer's flagged
   residual risk; watch real-page behavior at the slice-8 gate.
   Classification: intended behavior change with documented regression risk.

6. **NOTE — Beyond-8 members and tie/ambiguous members place with the legacy
   region and no cell metadata, so their painted text can still overlap an
   optimized sibling's cell area until slices 5–7.** Beyond-cap members get
   `regionOverride = null` (`TextLayoutPlanner.kt:430-435`), ties get no cell
   plan at all (`buildSharedCellPlans` skips unassigned members, `:1384-1388`)
   and keep the slice-2 legacy partition; neither receives `cellRect`
   (asserted: `TextLayoutPlannerMaskMetadataTest.kt:170-187` — tie ⇒
   `assertNoMetadata` including `cellRect == null`). Optimized siblings are
   hard-clipped to their slabs, so no wrong-content risk; the residual risk
   is visual collision between a legacy-rect member and a cell member, which
   the implementer documents and which the pre-existing growth/clip net
   (unchanged in this slice) only partially covers. Baseline had the same
   tie-member behavior, so this is not a regression. Classification:
   known staged limitation, per failure matrix ("Ambiguous component
   assignment → Own legacy rectangle").

7. **NOTE — A NonDraw(EMPTY_SHARED_CELL) member stops acting as a placement
   obstacle, so sibling placement can differ from baseline.**
   `TextLayoutPlanner.kt:417-428` continues before `placeBlock`, so the
   dropped member's extent is never in `placedObstacles` (`:450`). Intended
   (a cell owning no pixels paints nothing, and its phantom footprint should
   not push siblings around); recorded so slice-8 real-page diffs are not
   misread as regressions. Classification: expected behavior.

8. **NOTE — Parent-edge midpoint bias uses Float arithmetic**
   (`floor((parentA.right + parentB.left) / 2f)`, `MaskTextRegionPlanner.kt:314-318`).
   Exact for all realistic page coordinates; would lose precision only above
   2^24 px. The center-midpoint base cut uses exact integer `Math.floorDiv`
   (`:308`), which is correct for negative/large values (the off-by-one class
   the review brief asked about does not exist: centers are clamped to
   non-negative component bounds, so `floorDiv(a+b,2) == floor((a+b)/2)` and
   `a+b` cannot overflow given MAX_DIMENSION = 1,000,000).
   Classification: expected behavior, no action.

9. **NOTE — Cell-span first-pass counting is an O(componentSpans × slabs)
   read-only iteration, repeated by the fill pass** (`MaskTextRegionPlanner.kt:213-241`).
   Worst case ≈ 64 components × 100k spans × 8 slabs ≈ 51M cheap int
   comparisons per page (no allocation; consumption still capped at 100k
   derived segments). The counting pass itself is mandated by architecture
   ("counted in a first pass ... before allocating cell lists"); flagged only
   as a bounded-CPU consideration for the slice-8 perf evidence.
   Classification: expected behavior.

## Per-acceptance-criterion verification

| # | Acceptance criterion / test-list item | Result | Evidence |
|---|---|---|---|
| 1 | Cut/slab math exactness: floor(v+0.5) rounding + clamp | PASS | `clampRound` = `floor(value + 0.5f).toInt().coerceIn(low, high)` (`MaskTextRegionPlanner.kt:399-400`); applied to both axis and orthogonal centers (`:163-167`, `:290-292`) |
| 2 | Horizontal when X spread >= Y spread | PASS | `isHorizontalAxis` returns `(maxX - minX) >= (maxY - minY)` over clamped scan centers (`:285-299`); equality ⇒ horizontal pinned (`MaskTextRegionPlannerTest.kt:200-220`) |
| 3 | Stable sort (axis center, orthogonal, inputIndex) | PASS | `sortedWith(compareBy({ axisCenter }, { orthoCenter }, { inputIndex }))` (`:169-171`); Kotlin sort is stable; 9-member test pins stable order deciding WHO is optimized (`MaskTextRegionPlannerTest.kt:267-293`) |
| 4 | Base cut = integer floor midpoint | PASS | `Math.floorDiv(a.axisCenter + b.axisCenter, 2)` (`:308`) — exact floor semantics incl. negatives; no overflow (centers clamped ≤ 1e6) |
| 5 | Parent bias only for valid/facing/nonoverlapping parents, clamped to [centerA, centerB] | PASS | `cutBetween` requires both `parentValid` + non-null (`:311`), facing `A.right <= B.left` (horizontal) / `A.bottom <= B.top` (vertical) (`:312`), biased = floor edge midpoint clamped via `coerceIn(minOf, maxOf)` (`:314-319`); overlapping parents keep center midpoint (`MaskTextRegionPlannerTest.kt:129-148`); bias + out-of-range clamp tested (`:151-178`) |
| 6 | Strictly monotonic cuts; equal-width fallback | PASS | `cuts.zipWithNext().all { (p, n) -> p < n }` (`:188`); fallback `axisLow + Math.floorDiv(k * width, n)` / `((k+1) * width)` (`:197-206`); equal centers → 33/33/34 slabs pinned (`MaskTextRegionPlannerTest.kt:181-198`) |
| 7 | Half-open slabs, gapBefore/gapAfter dead zone, outer bounds = component bounds | PASS | `gapBefore = gap / 2`, `gapAfter = gap - gapBefore`; first slab starts `axisLow`, last ends `axisHigh` (`:190-196`); `slabRect` right = `endExclusive` (`:326-331`), consistent with Android `clipRect` exclusive edges (`PageTextRenderer.kt:77`) |
| 8 | Pairwise pixel disjointness in EVERY mode | PASS (cut path from spans; other modes from slabs + code proof) | Cut path: slabs separated by `[cut-gapBefore, cut+gapAfter)` ⇒ disjoint for any gap ≥ 0; asserted from cell SPANS per row (`MaskTextRegionPlannerTest.kt:71-94`); parent-biased slabs asserted (`:151-178`); 8-slab cap set asserted (`:287-292`). Fallback mode: half-open tiling, exact boundaries pinned (`:181-198`). Bounds-rect mode: same slab construction; disjoint slabs asserted (`:296-323`, `TextLayoutPlannerMaskMetadataTest.kt:240-242`). Degenerate slabs own nothing (`start >= endExclusive` fails both count and fill guards, `:218-222,235-237`). A row span crossing two cuts is intersected per slab, never assigned whole (`:232-239`; exact 3-segment pin `:50-68`) |
| 9 | Exact half-gap dead zone | PASS | gap 5 → gapBefore 2/gapAfter 3 ⇒ unowned columns exactly {98..102} and {198..202}, all other columns owned exactly once, every row (`MaskTextRegionPlannerTest.kt:97-126`); integration-level dead columns {99,100},{199,200} (`TextLayoutPlannerMaskMetadataTest.kt:96-98`); gap=1 case still disjoint (cut path proof above); `collisionGapPx` clamping both bounds + scaling pinned (`:326-337`) |
| 10 | Different components of one mask never share cuts | PASS | Partition is per `(groupId, componentId)` group: `byComponent` split + one `partition` call per component (`TextLayoutPlanner.kt:1389-1415`); planner test with two independent components (`MaskTextRegionPlannerTest.kt:223-237`); integration ids L=0/R=1 (`TextLayoutPlannerMaskMetadataTest.kt:146-167`) |
| 11 | Explicit empty-cell probes → NonDraw(EMPTY_SHARED_CELL) | PASS | Slab with zero component pixels ⇒ `empty` immediately (probes are slab-bounded, cannot rescue, `:261-267`); probe 1 = any own-span∩OCR → content bbox, probe 2 = center-nearest continuous cut-axis interval (`:269-276`); integration maps `empty` → `NonDraw(EMPTY_SHARED_CELL)`, not placed, not an obstacle (`TextLayoutPlanner.kt:417-428`); planner two-island test (`MaskTextRegionPlannerTest.kt:240-264`) + integration fixture (`PageLayoutPlanContractTest.kt:103-123`: middle NonDraw, siblings Draw, render ordinals consecutive 0/1) |
| 12 | Cap fallback (8/component; cell-span budget; groupless/unique/reference caps) | PASS | `MAX_SHARED_BLOCKS_OPTIMIZED = 8` (`:64`), 9th member slab=null/optimized=false (`MaskTextRegionPlannerTest.kt:267-293`), integration regionOverride=null for it (`TextLayoutPlanner.kt:430-435`); `CellSpanBudget` default 100k, `tryConsume` all-or-nothing, failed group charges 0 (`:75-85`; budget test `MaskTextRegionPlannerTest.kt:296-323` pins `used == 0` + bounds-mode cells); groupless/32-unique/129-reference cardinality tests unchanged and green (`TextLayoutPlannerMaskMetadataTest.kt:277-311`) |
| 13 | Identity/text provenance; contract model verbatim | PASS | `InputIdentity`/`NonDrawReason` (all 7 values)/`LayoutOutcome`/`LayoutResult`/`PageLayoutPlan` match architecture word-for-word (`TextLayoutPlanner.kt:120-170` vs architecture.md:70-100); `planPage` ordinals: planningOrdinal per nonblank in score-desc/input order (`:405-411`), renderOrdinal = `drawable.size - 1` for Draw only (`:469-476`), results in input order skipping blanks (`:478-481`); `plan()` wrapper returns `planPage(...).drawableInRenderOrder` (`:357-365`); tests: identity multiset in input order, ordinal maps, blank absence, wrapper field-by-field equivalence incl. metadata + geometry value equality (`PageLayoutPlanContractTest.kt:59-100,142-176`); text provenance through metadata wiring (`TextLayoutPlannerMaskMetadataTest.kt:101-105`) |
| 14 | One explicit result per nonblank input on EVERY path | PASS | Loop body has exactly three exits: blank `continue` (intentional absence), empty cell → NonDraw, sub-pixel → NonDraw, else Draw (`TextLayoutPlanner.kt:406-477`) — no other `continue`/drop; bounds-mode degenerate slab also funnels to `empty` → NonDraw (deviation 6); cardinalities pinned: 3/1/2/34/129 results in metadata tests, 3/1 in contract tests |
| 15 | Integration deltas: regionOverride = fitRegion; cellRect = hard slab; single-member = component-bounds cell; fallback groups = bounds-rect cells; groupless keep legacy partition | PASS | `regionOverride = cellPlan.fitRegion` for optimized, null beyond-cap, legacy region otherwise (`TextLayoutPlanner.kt:430-435`); `cellRect = slab` (NOT fitRegion) in every metadata arm (`:1455-1479`); conversion-fallback groups (geometry==null, mask dims == page) partition the bounds rect (`:1416-1439`; empty-runs fixture pins slabs [100,399)/[401,700) × [100,450) with parent-biased cut 400, `TextLayoutPlannerMaskMetadataTest.kt:190-248`); groupless/dim-mismatch keep `buildMaskRegions` (no cell plans, `:1378,1416`) |
| 16 | `equalizeSharedMaskFonts` fully removed; fonts independent | PASS | Function and every reference gone (grep: zero hits in `app/src/`); diff removes baseline lines 1253-1280; 3-block fixture asserts each font equals its own per-cell `binarySearchFontSize` and that longest-text font < shortest-sibling font — non-vacuous since equalization would force equality (`TextLayoutPlannerMaskMetadataTest.kt:107-119`) |
| 17 | 64-distinct-pair cap + tie/ambiguity carried over verbatim; renderer fail-closed correct for slab-only cellRect | PASS | Cap logic byte-equivalent to baseline `withMaskMetadata` (packed-long set check, `TextLayoutPlanner.kt:1468-1471` vs baseline :1240-1243); component assignment = identical clamped-OCR `componentForRectangle` (`:1501-1507`; `MaskGeometry.kt:55-81` untouched in commit); `bind()` does NOT drop `geometry==null && cellRect!=null` (`PageTextRenderer.kt:46-48`) and requires ids ONLY when geometry != null (`:49-55`) — `withSharedCellMetadata` sets geometry+ids+cellRect all-or-nothing (`:1473-1478`), so no incomplete-metadata layout can be produced. Cap-path coverage gap: NOTE 2 |
| 18 | Slice 4 deferral held (no neck inference); no slice 5-7 creep | PASS | grep of `rendering/` main sources: zero hits for `PositionedLine`/`HardClip`/neck/widening; joined lobes get midpoint/parent-biased cells only (`MaskTextRegionPlanner` has no neck logic); `NonDrawReason` values for later slices declared but never emitted (`TextLayoutPlanner.kt:124-127,131-135`) |
| 19 | Scope discipline: allowed files only; untouched files untouched; TextLayoutPlannerTest unmodified | PASS | `git show 22108f0 --name-status`: exactly MaskTextRegionPlanner.kt (new), TextLayoutPlanner.kt, MaskTextRegionPlannerTest.kt (new), PageLayoutPlanContractTest.kt (new), TextLayoutPlannerMaskMetadataTest.kt, MaskGeometryOrderedRleTest.kt, slice-3 report; empty diffs for PageTextRenderer.kt, MaskGeometry.kt, BubbleMaskRle.kt, TranslationOverlayView.kt, and TextLayoutPlannerTest.kt (`git show 22108f0 -- <file>` empty); planner diff hunks confined to the contract types, plan/planPage restructure, and the metadata/cell-plan region — `placeBlock`, `computeRects`, `buildMaskRegions` partition math, growth/clip math byte-identical (hunk map verified) |
| 20 | Acceptance fixture: 3-block continuous mask → 3 results, 3 disjoint cells, no shared pixels, no font equalization | PASS | `TextLayoutPlannerMaskMetadataTest.kt:65-120`: plan size 3; shared geometry instance (`===`); cells exactly [0,99)×[0,120), [101,199)×[0,120), [201,300)×[0,120); pairwise `overlaps == false`; dead columns {99,100,199,200} owned by none; per-block text unchanged; fonts independently fitted per own cell with a strictly cross-cell size ordering (see #16) |

Slice-3 architecture test list vs implemented tests — all ten mandated cases
present and non-vacuous: span crossing two cuts into three cells
(`MaskTextRegionPlannerTest.kt:50`); pairwise pixel disjointness (`:71`);
exact half-gap dead zone (`:97`); overlapping parents (`:129`); equal centers
(`:181`); diagonal centers (`:201`); different components of one mask (`:223`
+ `TextLayoutPlannerMaskMetadataTest.kt:146`); explicit empty-cell result
(`:240` + `PageLayoutPlanContractTest.kt:103`); cap fallback (`:267`, `:296`
+ groupless/unique/reference/64-component fixtures); identity/text provenance
(`PageLayoutPlanContractTest.kt:59` + `TextLayoutPlannerMaskMetadataTest.kt:101`);
independent sibling fonts (`TextLayoutPlannerMaskMetadataTest.kt:107-119`).
First-appearance id pinning for 3+ components (slice-2 NOTE 6 closure) is in:
`MaskGeometryOrderedRleTest.kt` "component ids follow row-major first
appearance for three or more components" — pins ids 0/1/2 in row-major order
AND demonstrates the legacy `fromSpans` key-string sort would order them
differently ("10:…" < "2:…" < "5:…"), so the pin is real, not vacuous.

## Assessment of the 7 documented deviations

| # | Deviation | Assessment |
|---|---|---|
| 1 | `withMaskMetadata` removed as provably dead | **SANCTIONED (verified).** Line-by-line: every block that could pass its guards (grouped, converted, page-dims geometry, unambiguous component) is now routed through `buildSharedCellPlans` (identical clamp/assignment via `resolveComponentId`, `TextLayoutPlanner.kt:1501-1507`) and receives metadata via `withSharedCellMetadata` under the identical 64-pair cap (`:1468-1471`). Every remaining legacy path (groupless, tied, dim mismatch, non-page-dims fallback) returns the layout unchanged from both old and new code — except conversion-fallback groups with page-matching mask dims, which now get bounds-rect slab cells (that change is deviation/test-expectation territory, separately sanctioned by the failure matrix row "Shared mask but conversion cap"). Renderer safety for the two new slab-only cases (64-pair cap, fallback groups): `geometry==null && cellRect!=null` is kept and clipped (`PageTextRenderer.kt:46-48,77`); tied members get NO cellRect at all (asserted), dim-mismatch groups get NO cellRect (no cell plans) — both exactly baseline. Claim VERIFIED. |
| 2 | Equal-width fallback slabs carry no extra dead zone | **SANCTIONED** (see NOTE 3). Legitimate reading of the explicit formula; disjointness invariant holds; recommend a one-line architecture addendum. |
| 3 | Empty-cell probes are slab-bounded | **SANCTIONED.** Architecture probe 1 is literally `component ∩ assigned slab ∩ own OCR`; restricting both probes to the cell's own spans is the only reading compatible with "never another block's cell" (an unrestricted `component ∩ own OCR` could return pixels inside a sibling's slab). One fidelity nuance recorded as NOTE 4 (probe-1 success returns the whole-cell bbox, not the ∩OCR-subset bbox; preference-only). |
| 4 | `INVALID_OR_SUBPIXEL_SOURCE_RECT` mapped from a dead branch | **SANCTIONED (verified).** `computeRects` clamps `safeW/safeH` to `max(1f, …)` in BOTH the current code (`TextLayoutPlanner.kt:1659-1660`) and the baseline (`6c18f3e` lines 1410-1411), so `rect.safeW < 1f || rect.safeH < 1f` was unreachable before and after; baseline silently `continue`d on the same dead predicate, so drawable output is unchanged. The 0.5×0.5 fixture pins the reachable behavior (Draw with safeW = safeH = 1, `PageLayoutPlanContractTest.kt:126-139`). The unreachable `NonDraw` mapping is a reasonable formal contract placeholder; slice 7 should revisit if a reachable definition is wanted. |
| 5 | Empty-cell contract fixture uses a degenerate middle slab | **SANCTIONED (verified).** Math re-derived independently: centers 100/101/102 → cuts floorDiv = 100 and 101; gap 2 (0.001×120 → clamp→2) → gapBefore=gapAfter=1 → middle slab [101,100) degenerate → zero spans → `empty`. The task-sketch impossibility argument is sound (equal centers ⇒ one component group; a 4-connected component spanning two lobes has pixels in every full-ortho slab; two islands = two independently partitioned components), and the planner-level test DOES realize the two-island sketch with a synthetic pixel gap (`MaskTextRegionPlannerTest.kt:240-264`). Outcome matches the mandate: one NonDraw, siblings Draw, ordinals intact. |
| 6 | Degenerate fallback-bounds single group (right == left) → NonDraw | **SANCTIONED.** Verified: width-0 bounds → equal-width slab [L, L) → degenerate → `empty` → `NonDraw(EMPTY_SHARED_CELL)`; one explicit result per input preserved; baseline drew into a zero-width clip rect (nothing visible), so no user-visible regression; reason choice (EMPTY vs INVALID) is defensible since the cell is literally empty. |
| 7 | Cell-span budget fallback keeps `(planGeometryId, componentId)` ids | **SANCTIONED (verified).** The renderer's component clip is built from the GEOMETRY's spans (`componentPath` over `component.spans`, `PageTextRenderer.kt:86-97`) via `ComponentClipCache.resolve`, not from planner cell-span lists — dropping only the planner span lists leaves structural clipping (component path + slab) fully intact, i.e. strictly stronger than required. `tryConsume` is all-or-nothing so a failed group charges 0 (`MaskTextRegionPlanner.kt:80-84`; pinned `used == 0`); consumption order is deterministic (LinkedHashMap first-appearance groups × sorted component ids, `TextLayoutPlanner.kt:1377,1390`). |

## Test-quality assessment (non-vacuousness)

- `MaskTextRegionPlannerTest` (12): exact segment intervals (`0 to 99` etc.),
  per-row span-derived pairwise disjointness (not slab-only), exhaustive
  per-column ownership counting 0/1 with exact dead ranges, exact biased and
  clamped slab edges, exact equal-width boundaries, exact diagonal behavior,
  exact empty-slab slab rect, cap membership by input index with pairwise
  slab disjointness, budget fallback with `used == 0`, and six exact
  `collisionGapPx` values including both clamp bounds and sub-1 scaling.
  None vacuous.
- `PageLayoutPlanContractTest` (6): exact ordinal maps (a=2/b=0/d=1), blank
  absence, NonDraw-with-siblings-Draw plus consecutive 0/1 render ordinals,
  sub-pixel Draw pin, wrapper equivalence over 13 fields + geometry value
  equality on a shared-mask page. None vacuous.
- `TextLayoutPlannerMaskMetadataTest` (8): shared-geometry instance identity,
  exact slab rects + dead columns, per-block text map, per-cell font
  recomputation equality (each font equals `binarySearchFontSize` in its own
  cell) plus a strict cross-cell inequality, single-block cellRect = mask
  bounds, two-component ids, tie → fully metadata-less INCLUDING cellRect,
  empty-runs → bounds-rect slabs exact + no ids, >64-component and caps
  cardinalities. None vacuous.
- `MaskGeometryOrderedRleTest` (+1): 3-component first-appearance pinning
  with an explicit legacy-sort contrast (closes slice-2 NOTE 6).
- Instrumented pixel proofs remain compile-gated only (no device available,
  consistent with slices 1-2); deferred to the slice-8 device gate.

## Verification commands run (reviewer-executed)

1. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   → **BUILD SUCCESSFUL in 1m.** JUnit XML aggregate: **123 tests, 0 failures,
   0 errors, 0 skipped** across 15 suites: MaskTextRegionPlannerTest 12 (new),
   PageLayoutPlanContractTest 6 (new), TextLayoutPlannerMaskMetadataTest 8,
   MaskGeometryOrderedRleTest 9 (8+1 new), TextLayoutPlannerTest 31
   (unmodified), TextLayoutPlannerStrokeTest 4, PageTextRendererDirectionTest 8,
   ComponentClipCacheTest 6, MaskGeometryTest 7, MaskGeometryStressTest 2,
   BubbleSegmentationDecoderTest 8, RenderColorEstimatorTest 7,
   RenderColorEstimatorSamplingTest 5, RenderColorEstimatorDedupTest 7,
   RenderColorEstimatorLayoutSamplingTest 3. Matches the implementation
   report's counts exactly.
2. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   → **BUILD SUCCESSFUL in 58s.**
3. `git show 22108f0 --name-status` / `--stat` (scope), `git show 22108f0 --`
   each of PageTextRenderer.kt / MaskGeometry.kt / BubbleMaskRle.kt /
   TranslationOverlayView.kt / TextLayoutPlannerTest.kt (all empty diffs),
   `git status --short` (clean at HEAD = 22108f0).
4. Baseline comparison: `git show 6c18f3e:...TextLayoutPlanner.kt` — removed-
   lines diff confirms only `withMaskMetadata`, `equalizeSharedMaskFonts`,
   and the `plan()` body were removed/replaced; baseline `safeW/safeH`
   clamps at lines 1410-1411 and the dead `continue` at line 330 verify
   deviation 4's "pre-existing" claim; baseline hunk map confirms
   `placeBlock`/`computeRects`/growth/clip/`buildMaskRegions` untouched.

## Relation to prior reviews

- Slice-2 NOTE 6 (first-appearance ids weakly pinned): CLOSED by the new
  MaskGeometryOrderedRleTest 3-component test.
- Slice-2 NOTE 2 (renderer clip-cache/`mapNotNull` silent drop): unchanged in
  behavior; slice 3 adds no new droppable layout shape (slab-only cellRect
  layouts are kept by `bind()`), and the explicit
  `NonDraw(INVALID_RENDER_METADATA)` representation remains a later-slice
  artifact per staging.
