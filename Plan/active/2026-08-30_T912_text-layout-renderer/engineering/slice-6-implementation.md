# T912 slice 6 — bounded long `text_free` widening

## Result

Implemented slice 6 of architecture revision 2 on `codex/text-layout-renderer`
(base `6e6fcdc`), one commit, no rebase/reset. Slice 4 (necks) stays formally
deferred; its formal-deferral determinism pin is included as the mandated
ride-along (see below).

- **`TextLayoutPlanner.kt`** (the ONLY production file touched):
  - `TextLayoutTuning` gains the isolated slice-6 tunables
    (`FREE_TEXT_MIN_GRAPHEMES = 24`, `FREE_TEXT_TALL_RATIO = 2.0f`,
    `FREE_TEXT_WIDEN_FACTOR_1 = 1.25f`, `FREE_TEXT_WIDEN_FACTOR_2 = 1.50f`,
    `FREE_TEXT_MAX_PAGE_ADD_FRACTION = 0.08f`,
    `FREE_TEXT_MIN_FONT_GAIN = 1.15f`, `FREE_TEXT_WIDTH_EPSILON = 0.01f`).
  - New private `boundedFreeTextWideningPlan(...)`: the whole slice-6
    decision. Gates evaluated in the mandated order — label 2 → no valid
    parent → resolved horizontal → >= 24 non-whitespace graphemes (via
    `graphemeClusters`) → `height/width >= 2.0` (float division, `width > 0`
    guarded) → OCR-box fit pressure (baseline font below `minLegibleFont` OR
    overflow at it). Baseline = the UNRESHAPED original OCR box; candidates
    2/3 keep the OCR center X/Y and the original height with widths
    `min(w*factor, w + 0.08*pageShortSide, page-clamped, collision-free)`,
    deduplicated after clamping (0.01 px epsilon); the smallest wider
    candidate whose fitted font >= 1.15x baseline OR that removes overflow
    (candidate fits fully at its fitted font while the baseline overflowed)
    wins; otherwise the OCR baseline is kept with NO region override (today's
    exact label-2 legacy behavior: containment clip preserved through the
    OCR-center anchor branch). The accepted widened rect is passed to the
    EXISTING `placeBlock` as `regionOverride` (its center == OCR center, so
    anchoring semantics are unchanged).
  - New private `collisionFreeWidthForBand(...)`: widest symmetric box at the
    OCR center X that stays on-page and overlaps no already-placed obstacle
    extent whose vertical extent overlaps the candidate's band — the existing
    `freeSpaceLeft`/`freeSpaceRight` bound scan extended with the band filter
    (and a straddling-obstacle guard, see deviations). Deterministic in
    obstacle (score) order.
  - `planPage` wiring: `isVertical` and `placedObstacles` are computed a few
    lines earlier (pure, no decision change); the trial is evaluated BEFORE
    the legacy `computeRects` so an ELIGIBLE block never reaches the reshape
    branch (`val rect = if (freeText == null) computeRects(...) else
    freeText.rect`); `placeBlock` receives `freeText.regionOverride` when a
    plan exists and today's `regionOverride` otherwise. The StaticLayout
    budget check/reserve is unchanged and applies identically on the slice-6
    path (eligible blocks are horizontal → 2 layouts, same NonDraw semantics).
- **Ride-along (slice 4 formal deferral)** in
  `TextLayoutPlannerMaskMetadataTest`: for joined-lobe shared-mask fixtures
  (one continuous converted mask, 2 members, diagonal centers with facing
  parents → parent-biased VERTICAL cut; and one with equal centers → midpoint
  HORIZONTAL cut), `planPage` twice yields byte-identical `PageLayoutPlan`
  values (every LayoutResult/BlockLayout field compared; MaskGeometry by
  value) and the cell slabs are pinned to exact expected values —
  `[0,169)×[0,200)` / `[171,300)×[0,200)` (cut 170, dead rows {169,170}) and
  `[0,149)×[0,120)` / `[151,300)×[0,120)` (cut 150, dead columns {149,150}).
- **NEW `TextLayoutPlannerFreeTextTest`** (17 tests): every gate individually
  violated → legacy path (incl. the reshape) with exact legacy pins; all
  gates met → candidate flow (exact font/box/origin pins); baseline-is-OCR-box
  on no-gain rejection with containment intact; 8% page-add cap binding with
  dedup; page-edge clamp exact at the boundary; collision clamp exact at the
  obstacle extent; raw 1.25 acceptance with >= 15% font gain; masked free
  text keeps slice-5 contracts; direction/isVertical identical across
  eligible/ineligible twins (Latin + CJK); label-2-short vs label-1-short
  bit-for-bit BlockLayout equality; a 27-fixture sweep asserting no eligible
  box exceeds 1.50x / the 8% page-add cap and stays on-page; identity
  cardinality (one Draw, identity pair, renderOrdinal 0).

## Verification

From repo root, Git Bash on Windows (total wall for the task ≈ 3 min across
three invocations):

1. Early probe, three suites:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat
   :app:testDevDebugUnitTest --tests "...TextLayoutPlannerFreeTextTest"
   --tests "...TextLayoutPlannerMaskMetadataTest"
   --tests "...TextLayoutPlannerTest"`
   — 58 tests, 1 failure (a wrong hand-computed TEST expectation in the
   collision fixture — see "Updated expectations" below; production behavior
   was correct and its exact-width assertion already passed), fixed.
2. Full gate 1:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat
   :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*"
   --tests "eu.kanade.translation.rendering.*"`
   — **BUILD SUCCESSFUL in 19s** (wall ≈ 20s). JUnit XML aggregate:
   **179 tests, 0 failures, 0 errors, 0 skipped across 19 suites**:
   - NEW TextLayoutPlannerFreeTextTest 17/17
   - TextLayoutPlannerMaskMetadataTest 10 (8 existing + 2 NEW ride-along)
   - Existing, UNCHANGED and green: **TextLayoutPlannerTest 31 (no edits —
     including `free text stays anchored to OCR centre when reshaped`
     (19-graphemes fixture → ineligible → legacy reshape origin (195,250))
     and `computeRects reshapes a tall parentless box but not a parented
     one`)**, TextLayoutPlannerSlice5Test 8, TextLineBreakerTest 19,
     AdaptiveBandPlannerTest 9, MaskTextRegionPlannerTest 13,
     PageLayoutPlanContractTest 6, PageTextRendererDirectionTest 8,
     TextLayoutPlannerStrokeTest 4, ComponentClipCacheTest 6, MaskGeometryTest 7,
     MaskGeometryStressTest 2, MaskGeometryOrderedRleTest 9,
     BubbleSegmentationDecoderTest 8, RenderColorEstimator 22 (7+5+7+3).
   Byte-identity for ordinary/unmasked manga and ineligible blocks: the
   unchanged 31-case planner suite plus every other pre-existing suite pass
   with zero edits.
3. Gate 2:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat
   :app:compileDevDebugAndroidTestKotlin`
   — **BUILD SUCCESSFUL in 17s**. No device/emulator available; instrumented
   pixel proofs remain compile-gated for the slice-8 device gate (consistent
   with slices 1–5).
4. `git diff --check` clean; `git status` shows exactly the allowed files
   (`TextLayoutPlanner.kt`, the two test files, this report).

All expected values in the new tests are hand-derived from the planner's pure
math under the deterministic fake measurer (0.6 px/char, 1.2x line height) and
pinned exactly (fonts, safe dims, origins, clip rects, accepted widths).

## Scope and risks

Touched files: `TextLayoutPlanner.kt` (production; ONLY file),
NEW `TextLayoutPlannerFreeTextTest.kt`,
`TextLayoutPlannerMaskMetadataTest.kt` (2 ride-along tests), this report.
NO renderer, MaskTextRegionPlanner, MaskGeometry, BubbleMaskRle,
overlay/pipeline/batch/download/drawer/progress changes. `placeBlock`,
`computeRects`, `extentOf`, the obstacle list construction, the
`TranslationBlock.direction` field and the `isVertical` expression are
untouched. NO slice-7 machinery: no 8-candidate post-anchor loop, no shift or
clip-refit candidates, no `NO_DISJOINT_POST_ANCHOR_PLACEMENT` emission —
candidate selection uses only the collision-free-width cap; the final-safety
pass remains the next slice.

### Intentional deviations / local decisions (all recorded here)

1. **Path precondition before the gates: `block.segmentationMask == null`.**
   The trial is defined only on the plain unmasked legacy path (where today's
   `regionOverride == null`). Masked blocks are governed by the slice 2/3/5
   mask/cell/containment contracts: optimized-cell members take slice-5
   adaptive bands; other masked members place with their mask region. Passing
   "NO regionOverride" to a masked block (baseline-kept) or an OCR-centered
   widened rect would silently discard its mask containment. Note the reshape
   can never fire for any masked block with a region, and the one
   masked-with-null-region case (a beyond-8 shared member) keeps its exact
   legacy path — including the reshape — under the ineligible-blocks rule.
   Backed by the `masked long free text keeps the slice 5 cell contracts with
   no widening trial` test (masked → positioned lines, i.e. slice 5 owns it).
2. **Candidates must be strictly wider than the OCR box (> 0.01 px).** A
   clamped width at or below the original is not a widening trial (a narrower
   or equal box cannot lift the fitted font or remove overflow) and is
   skipped, consistent with "Accept the SMALLEST wider candidate". The same
   epsilon doubles as the post-clamp dedup distance, per the task.
3. **Baseline/candidate safe dims come from `computeRects` with the OCR (or
   widened) rect as `regionOverride`.** This reuses the exact parentless
   padding/safe-dim math mandated ("exactly as computeRects computes today")
   with zero duplicated formulas, and structurally guarantees the reshape
   branch is unreachable on the eligible path (it is suppressed by a non-null
   override; planPage only calls the reshaping legacy `computeRects` when the
   helper returned null, i.e. for ineligible/no-pressure blocks — "gate the
   reshape branch to ineligible blocks only").
4. **Straddling obstacle forces collision-free width 0.** If a placed extent
   straddles the OCR center X within the candidate's band, no positive-width
   symmetric box can avoid it; the strict reading of "does not overlap any
   already-placed obstacle extent" yields width 0 → no widening candidates →
   baseline. Deterministic and fail-safe.
5. **Acceptance fonts use `containerW = rect.baseW`** (mirrors `placeBlock`'s
   initial fit call). The FINAL layout font is `placeBlock`'s own — for an
   accepted widened rect that is the region-override refit at
   `(width-8) x (height-8)` — and the tests pin those final values.
6. **`placedObstacles`/`isVertical` hoisted above the eligibility check in
   `planPage`.** Pure computations previously evaluated a few lines later;
   every legacy decision point is unchanged, proven by the untouched
   31-case TextLayoutPlannerTest and all other pre-existing suites.
7. **Slice-4 ride-along coverage split.** The diagonal fixture exercises the
   parent-biased cut on a VERTICAL partition: the axis-center midpoint of
   80/220 would be 150, and the facing parents (bottom 160 <= top 180) bias
   it to the parent-edge midpoint floor((160+180)/2) = 170, which lies inside
   the closed interval [80, 220] so it stands. The equal-centers fixture pins
   the pure midpoint cut at the shared center on a HORIZONTAL partition.
   Parent bias on a horizontal axis was already exactly pinned by the
   conjoined-cloud fixtures (cut 400), so the pair above covers both axes,
   bias, and the equal-center degenerate case.
8. **Determinism comparison normalizes `MaskGeometry` by value.** Separate
   `planPage` calls build separate geometry instances by design (per-plan
   sessions), so byte-identity is asserted field-wise — every
   LayoutResult/BlockLayout field exactly equal, geometry spans/components
   value-equal — mirroring the existing `PageLayoutPlanContractTest`
   wrapper-equivalence convention.

### Updated expectations (with reasons)

- **Collision fixture, one test-only expectation corrected during the probe
  run.** The first version asserted the drawn-ink right edge touches the
  obstacle extent; actually the drawn token (124.8 px at font 8) is narrower
  than its accepted box (134.2 px), so the BOX edge is the touching edge. The
  assertion was corrected to compare the box edge
  (`originX + (safeW + 8)/2`) against the obstacle extent; the exact
  `safeW == collisionFreeWidth - 8` assertion had already passed, confirming
  the production clamp behaved exactly as derived. No production change.

### Remaining risks for later slices

- An accepted widened block's containment is the region override itself; when
  even the widened box cannot fit the text at the floor font, ink can exceed
  the box (no clipRect on that branch) — this is the existing label-2
  region-override behavior and the failure matrix routes final safety to
  slice 7 (`NO_DISJOINT_POST_ANCHOR_PLACEMENT` machinery).
- The trial can accept a candidate that later overlaps a not-yet-placed
  neighbor's extent — sanctioned by the staging (final collision validation
  runs only after the OCR-center anchor in slice 7; today's growth/clip net
  still applies to subsequently placed blocks).
- `graphemeClusters` allocates a `BreakIterator` + list per evaluated block —
  bounded (only blocks passing gates 1–3), no dense/page-size allocations.
