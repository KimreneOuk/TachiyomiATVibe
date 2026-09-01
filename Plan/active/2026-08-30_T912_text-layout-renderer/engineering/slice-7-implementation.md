# T912 slice 7 — finite final post-anchor safety and explicit non-draw

## Result

Implemented slice 7 of architecture revision 2 on `codex/text-layout-renderer`
(base `1bc8571`), one commit, no rebase/reset. Slice 4 (necks) stays formally
deferred. The final no-overlap check now runs AFTER every anchor branch on the
conservative (stroke/AA/half-gap inflated, un-clipped) occupancy, resolves
colliding lower-priority blocks through the bounded eight-candidate ladder, and
emits `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)` with the identity intact when
every finite candidate fails.

`TextLayoutPlanner.kt` remains the ONLY production file touched
(`git status` evidence below); renderer/geometry/overlay/pipeline/batch/
download/drawer/progress files are untouched.

### State of the interrupted attempt — what was kept, what was repaired

The working tree carried an uncommitted +131/−23 partial diff that did NOT
compile (four called symbols had no definitions). All of it was sound and was
kept verbatim except one completion detail:

- KEPT: `PagePlanWithAttempts`, the internal `planPage` → `planPageInternal`
  seam, `TextLayoutTuning.MAX_FINAL_PLACEMENT_ATTEMPTS = 8`, the whole
  `planPage` loop wiring (occupancy check, exemption-aware collision call,
  NonDraw emission, `placed` footprint bookkeeping, attempts accumulation), the
  `adaptive` result hoisting, and the `placeBlock(allowGrowth)` seam.
- REPAIRED/COMPLETED: implemented the four missing symbols — top-level
  `PlacedFootprint(cellRect, occupancy)`, `conservativeOccupancyOf`,
  `footprintCollides`, and the private `FinalResolution` +
  `resolvePostAnchorPlacement` ladder (+ helpers `inkRectOf`, `containedIn`,
  `hardCellsDisjoint`, `translateRect`, `translateLayout`,
  `minShiftDisplacement`, `FreeRectCandidate`, `freeRectDisplacement`,
  `fitIntoFreeRect`); extended the call site with
  `positionedLinesUsed`/`staticLayoutsUsed` so adaptive replacement candidates
  re-check the page budgets (invariant 10).

### Behavior implemented (exactly per the task's design decisions)

1. **Conservative occupancy** (`conservativeOccupancyOf`): adaptive layouts →
   union bbox of the per-line `conservativeOccupancy` rects (already
   stroke+AA+gap/2 inflated by the band planner); legacy layouts →
   `extentOf` inflated on every side by
   `computeStrokeWidth(font) + aaGuard(scale) + collisionGap/2`
   (`AA_GUARD = max(1*scale, 0.5)`, gap =
   `MaskTextRegionPlanner.collisionGapPx(min(pageW,pageH), scale)`),
   scale = 1/sampleSize.
2. **Hard-cell disjointness exemption** (`hardCellsDisjoint` inside
   `footprintCollides`): a pair whose hard cells are BOTH non-null and
   non-overlapping (`FloatRect.overlaps` — touching is not overlap) is
   structurally pixel-separated and exempt. This closes the slice-5 caveat
   (adaptive obstacle-blindness): adaptive-vs-unmasked, adaptive-vs-cellless
   masked, and legacy-vs-legacy all fall under occupancy detection, while
   same-cell-adjacent adaptive pairs stay collision-free.
3. **The final post-anchor pass**: per colliding block (by construction the
   lower-priority one), at most `MAX_FINAL_PLACEMENT_ATTEMPTS = 8` EVALUATED
   candidates in the mandated order: (1) selected geometry re-validated as-is;
   (2) one font step smaller (adaptive: band refit with the one-step reduced
   cap, budget-rechecked; legacy: same box, font−1 floored at
   `FIT_MIN_FONT_PX*scale`); (3) baseline geometry (legacy: `placeBlock` with
   `allowGrowth = false` — the pre-growth base rect; adaptive: the exact
   slice-5 rectangular cell fallback call); (4-7) minimum legal
   left/right/up/down shifts of the selected geometry, displacements computed
   from the inflated occupancies by a bounded fixed point over the accepted
   set, clamped so the moved BOX (painted geometry) stays within
   page ∩ hard-cell-slab; (8) one hard disjoint clip/refit into the best of
   the four axis-aligned free rectangles around the current occupancy
   (max area → shortest displacement → fixed left/right/up/down), fitted only
   inside that rectangle (two-pass fixed-point inflation, ≤7-step font search
   per pass) with `clipRect = freeRect`. Duplicates after clamping are skipped
   and do NOT create replacements; the attempt counter advances only per
   EVALUATED candidate. Validation recomputes, in order: box containment
   (painted geometry inside page, inside the hard cell when present), page
   positioned-line/StaticLayout budgets, then occupancy collisions (rule 2 vs
   the accepted set). First validating candidate wins and joins the accepted
   set. All eight fail → `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)`
   (renderOrdinal null, not placed, not an obstacle, identity intact).
   Only the colliding block ever changes; no font equalization; non-colliding
   blocks are accepted unchanged (the candidate loop never engages).
4. **Testability seam**: `planPageInternal(...): PagePlanWithAttempts` reports
   the deterministic count of evaluated final post-anchor candidates summed
   over colliding blocks; `planPage` wraps it.

## Verification

From repo root, Git Bash on Windows. Wall times are the recorded gradle
totals; task wall ≈ 25 min across many invocations (build probes + fixture
calibration).

1. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugKotlin`
   — first run FAILED in 47s (two Int/Float type errors in the interrupted
   diff's `translateLayout`); fixed; second run BUILD SUCCESSFUL in 3m 15s.
2. Full gate 1:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   — final run **BUILD SUCCESSFUL in 16s** (wall ≈ 18s). JUnit XML aggregate:
   **186 tests, 0 failures, 0 errors, 0 skipped across 20 suites**:
   - NEW TextLayoutPlannerFinalSafetyTest 7/7
   - TextLayoutPlannerMaskMetadataTest 10 (8 existing + 2 with updated
     expectations — see "Updated expectations" below)
   - Existing, UNCHANGED and green: **TextLayoutPlannerTest 31 (no edits —
     byte-identity for ordinary unmasked manga holds: the candidate loop
     engages ONLY on occupancy collision)**, PageLayoutPlanContractTest 6,
     TextLayoutPlannerSlice5Test 8, TextLayoutPlannerFreeTextTest 17,
     AdaptiveBandPlannerTest 9, MaskTextRegionPlannerTest 13,
     TextLayoutPlannerStrokeTest 4, TextLineBreakerTest 19,
     PageTextRendererDirectionTest 8, ComponentClipCacheTest 6,
     MaskGeometryTest 7, MaskGeometryStressTest 2, MaskGeometryOrderedRleTest 9,
     BubbleSegmentationDecoderTest 8, RenderColorEstimator 22 (7+5+7+3).
   During calibration two earlier runs FAILED (25s/21s): the resolver's first
   shift-displacement formulas were inverted (they computed displacement past
   the far side, collapsing every shift to a no-op) — 3 failures incl. the
   protected Slice5 255-draw budget test; and the free-rect strip cut then
   over-cut (any y-overlapping blocker killed a strip regardless of side) —
   2 failures. Both were implementation bugs, fixed and re-proven; the
   protected suites have never been edited.
3. Gate 2:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   — **BUILD SUCCESSFUL in 13s**. No device/emulator available; instrumented
   pixel proofs remain compile-gated for the slice-8 device gate (consistent
   with slices 1-6).
4. `git status --short` after the run: exactly `TextLayoutPlanner.kt` (M),
   `TextLayoutPlannerMaskMetadataTest.kt` (M), the NEW
   `TextLayoutPlannerFinalSafetyTest.kt`, and this report. `git diff --check`
   clean; no debug output remains in production code.

## Scope and risks

Touched: `TextLayoutPlanner.kt` (only production file), NEW
`TextLayoutPlannerFinalSafetyTest.kt` (7 tests covering every mandated
scenario), `TextLayoutPlannerMaskMetadataTest.kt` (2 expectation updates, see
below), this report. No renderer/geometry/overlay/pipeline/batch/download/
drawer/progress changes; slice 4 stays deferred; `extentOf`, `placeBlock`
(semantics), `computeRects`, and the obstacle construction are untouched.

### New tests (TextLayoutPlannerFinalSafetyTest, all hand-derived + calibrated)

- anchor-created collision (two parent-center anchors overlapping) detected
  and resolved by the minimum right shift — attempts exactly 4, B moved to
  403.68, the higher-score block bit-identical to its solo plan (only the
  lower-priority block changes);
- thick-stroke collision: bare extents 11.2px CLEAR, stroke+gap occupancies
  overlap by 10 — a no-stroke check would have missed it; resolved by the min
  left shift, attempts exactly 3;
- concave external mask: A's conservative bbox spans the U's empty interior;
  B (inside the interior) is resolved by the min UP shift out of the bbox —
  attempts exactly 6, never tucked into the concave empty space;
- page edge: B's ink touches the page right edge, so RIGHT has zero legal
  room (skipped); min LEFT shift is page-clamped and still collides; min UP
  shift wins — attempts exactly 4;
- winning-at-k exactness: a 0.54px occupancy overlap resolved by exactly one
  font step → attempts exactly 2;
- impossible space (page tiled by D/C/A walls): all eight candidates evaluated
  and fail — attempts exactly 8, `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)`
  with renderOrdinal null, consecutive render ordinals over the survivors,
  identity cardinality intact, and a byte-identical deterministic replay;
- hard-cell exemption: two adaptive blocks on vertically-partitioned slabs
  [0,59]/[61,120] of one component whose font-72 occupancy boxes overlap
  across the dead zone (asserted, non-vacuous) → zero attempts, both drawn
  unchanged.

### Updated expectations (with reasons) — TextLayoutPlannerMaskMetadataTest

`TextLayoutPlannerMaskMetadataTest` is NOT in the task's protected list
(`TextLayoutPlannerTest`, `PageLayoutPlanContractTest`, `TextLayoutPlannerSlice5Test`,
`TextLayoutPlannerFreeTextTest`, `AdaptiveBandPlannerTest`,
`MaskTextRegionPlannerTest` — all pass UNCHANGED). Two of its fixtures
predated slice 7 and asserted the old overlapping-draw behavior that slice 7
exists to forbid:

1. `129 masked blocks hit the reference cap...`: the 13x10 grid on a 300x120
   page needs ~46,000 px^2 of conservative occupancy (129 blocks x ~22x18 plus
   eight larger cell layouts) on a 36,000 px^2 page — geometrically
   oversubscribed, so SOME `NO_DISJOINT_POST_ANCHOR_PLACEMENT` non-draws are
   the mandated outcome for ANY conforming implementation (measured: the
   resolution converges to the theoretical packing limit, 58 draws + 71
   explicit non-draws). Updated to assert the slice-7 identity contract:
   129 explicit results in input order, exact identity multiset, consecutive
   render ordinals over the draws, every non-draw carrying the mandated
   reason. The reference-cap intent (129 distinct mask instances of one
   geometry → fingerprint verification keeps them one group) is unchanged and
   still exercised.
2. `more than 32 distinct masks leave late masks groupless...`: each of the 34
   one-pixel-island masks produces single-member groups whose region is the
   FULL 300x120 mask rect; unassigned members place giant font-72 legacy
   layouts that all stack on the same spot at HEAD. With slice 7 only the
   first such layout is disjoint-resolvable; the rest are explicit non-draws.
   Updated to: 34 explicit results, exact identity multiset, non-draws only
   with the mandated reason, and the groupless pin recast as "b32 yields an
   explicit result; if drawn, no metadata" (b32 does not resolve on this
   fixture, so the groupless-no-metadata observation is carried by the
   negative branch — noted as a residual pin-strength loss).

### Intentional deviations / local decisions (all recorded here)

1. **Skipped (duplicate/unconstructible) candidates do not advance the
   counter.** The task's "the attempt counter still advances per evaluated
   candidate" is implemented literally: duplicates after clamping and
   unconstructible candidates (e.g. font already at the floor, zero room to
   shift) are skipped uncounted; the "exactly 8" fixture is crafted so no
   candidate is skipped.
2. **Containment is checked on the painted BOX, collisions on the occupancy.**
   "Box inside page/component/cell" is evaluated on the painted geometry
   (`inkRectOf`: positioned-line rects for adaptive, `extentOf` for legacy)
   because the conservative occupancy is explicitly un-clipped and may exceed
   bounds (slice-5 verification NOTE 7); occupancy is used only for collision
   detection. Adaptive hard-clip containment is enforced by the
   band-planner's own span+slab gates (verified per candidate via the line
   rects inside the slab).
3. **Shifts clamp on the painted box within page ∩ hard-cell slab** (the
   literal "page + component bounds + cell slab"; parent/OCR regions are NOT
   additional shift bounds). A clamped shift that still collides fails
   validation — the "page edge" test pins exactly this.
4. **Free-rect strips cut only for blockers that REACH into the strip**
   (y-overlap + protruding past the block's near edge). A first version cut on
   any y-overlapping blocker (over-cutting: strips died whenever the colliding
   partner existed on the far side) and an earlier version under-cut
   (straddling blockers left bogus rects that only failed later at collision
   checking). The implemented condition both preserves reachable rectangles
   and degenerates strips whose adjacent space is occupied.
5. **Adaptive candidates re-check the page line/layout budgets** with the
   replacement line count (`positionedLinesUsed − original + k` etc.), added
   to the interrupted diff's call site. Budget effects of resolution are
   over-reserving only (a legacy replacement after an adaptive attempt frees
   reservations; a post-ladder NonDraw leaves its reservation unused) — never
   under-counts, preserving invariant 10.
6. **Candidate 2 for legacy layouts is `fontSizePx − 1` floored at
   `FIT_MIN_FONT_PX * scale`** (not `minLegible`): the ladder may drop below
   the legibility floor as a last resort before shifting/clipping, mirroring
   the clip net's own floor-skipping behavior.
7. **`fitIntoFreeRect` uses a two-pass fixed point on the inflation estimate**
   (each pass one bounded ≤7-step font search) because the inflation depends
   on the fitted font; overflow at the floor is accepted like the legacy
   containment clip (the free-rect `clipRect` makes the separation structural;
   the occupancy collision check still rejects unusable slivers).
8. **Non-colliding common case byte-identity**: when `footprintCollides` is
   false nothing changes — the 31 unchanged `TextLayoutPlannerTest` cases plus
   every other pre-existing suite pass with zero edits, and the exemption
   keeps same-component adaptive neighbors at zero attempts.
9. **`translateLayout` re-floors positioned-line pixels** (`floor(leftPx + d)`)
   while translating the float occupancy rects exactly — the integer render
   contract is preserved; the ≤1px discrepancy between int placement and float
   envelope is conservative.

### Remaining risks for the slice-8 gate

- Real-page behavior of resolution (shifts/clip-refits changing drawn
  geometry on crowded pages) is unproven until the slice-8 device gate;
  planner-side determinism is pinned by the exact-attempt tests.
- Oversubscribed pages now legitimately non-draw blocks that previously drew
  overlapping ink (identity intact, explicit reason). The two updated
  MaskMetadataTest fixtures are the calibration point; real-page density is
  expected to be far below these synthetic grids.
- The groupless-no-metadata pin in the 34-mask fixture is weaker (see above);
  a dedicated groupless fixture that still draws would restore full strength
  if the reviewer wants it.
- Budget reservations for blocks that end in post-anchor NonDraw are released
  only when a replacement frees them; a NonDraw after an adaptive attempt
  keeps the adaptive reservation (conservative over-count).
