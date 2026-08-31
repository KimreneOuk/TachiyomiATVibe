# T912 slice 6 — independent verification review

- Reviewed commit: `de818c6` (`feat(translation): T912 slice 6 — bounded long free-text widening`)
  on `codex/text-layout-renderer`, base `6e6fcdc`, working tree clean at review time.
- Inputs: `docs/roles/reviewer.md`, task README (Director item 6 + tuning envelope),
  `engineering/architecture.md` revision 2 ("Slice 6", invariants 7/9, failure matrix),
  `engineering/slice-6-implementation.md`, live source/tests at HEAD vs
  `git show 6e6fcdc:...TextLayoutPlanner.kt`.
- Method: full diff review of the commit, targeted reads of `planPage`, `computeRects`,
  `placeBlock`, `extentOf`, `buildMaskRegions`, `graphemeClusters`, `binarySearchFontSize`,
  `minLegibleFont`, and hand re-derivation of every pinned fixture expectation.
  Both gates re-run by the reviewer.

## Verdict

**ACCEPT.** No blocking findings. The implementation matches the architecture's slice-6
contract exactly, including all four clamp terms, AND-semantics eligibility, the
unreshaped OCR baseline, and the byte-identical legacy path for ineligible blocks.
All eight documented deviations are sanctioned (assessed individually below).

## Verification commands and results (run by reviewer)

1. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   → **BUILD SUCCESSFUL in 28s**. JUnit XML aggregate: **179 tests, 0 failures,
   0 errors, 0 skipped, 19 suites** — TextLayoutPlannerFreeTextTest **17/17 (new)**,
   TextLayoutPlannerMaskMetadataTest **10 (8 existing + 2 new)**,
   TextLayoutPlannerTest **31, unchanged, green**, TextLayoutPlannerSlice5Test 8,
   TextLineBreakerTest 19, AdaptiveBandPlannerTest 9, MaskTextRegionPlannerTest 13,
   PageLayoutPlanContractTest 6, PageTextRendererDirectionTest 8,
   TextLayoutPlannerStrokeTest 4, ComponentClipCacheTest 6, MaskGeometryTest 7,
   MaskGeometryStressTest 2, MaskGeometryOrderedRleTest 9,
   BubbleSegmentationDecoderTest 8, RenderColorEstimator 22 (7+5+7+3).
   Matches the implementation report's claim exactly.
2. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   → **BUILD SUCCESSFUL in 18s**.
3. `git show de818c6 --name-status` → exactly
   `M TextLayoutPlanner.kt`, `A TextLayoutPlannerFreeTextTest.kt`,
   `M TextLayoutPlannerMaskMetadataTest.kt`, `A slice-6-implementation.md`. Scope clean.

## Findings

### BLOCKING

None.

### MEDIUM

None.

### NOTE

1. **Legacy reshape boundary semantics (`TextLayoutPlanner.kt:2088` vs `:1428`).**
   The eligibility ratio gate is `height/width >= 2.0` while the legacy reshape fires
   at `> 2.0` (strict). A block at exactly 2.0 is trial-eligible; if the trial rejects,
   it keeps the unreshaped OCR box — identical to what legacy would have done at
   exactly 2.0 (no reshape). No boundary defect; recorded for future tuning.
2. **Collision-free width uses ink extents, not slice-7 conservative occupancy.**
   `collisionFreeWidthForBand` (`TextLayoutPlanner.kt:1493-1511`) bounds against
   `extentOf` results of already-placed blocks — the same basis as the existing
   `freeSpaceLeft/freeSpaceRight` scan, extended with the band filter. Final collision
   validation is explicitly slice 7 ("Final collision validation runs only after the
   OCR-center anchor"); `placeBlock`'s growth/clip net still applies. Consistent with
   staging; the implementer documents the residual risk.
3. **Dedup is behaviorally unobservable when a duplicated candidate follows a
   rejection** (identical width ⇒ identical font ⇒ identical qualifies result), so the
   `8 percent page-add cap binds and both factors dedup` test pins the exact accepted
   width (124) rather than proving the skip itself. The exact-width assertion is the
   meaningful part and passes; test name slightly overclaims.
4. **Sweep cap assertions are off by the safe padding.** The sweep asserts
   `layout.safeW <= 1.5*w` (`TextLayoutPlannerFreeTextTest.kt:383-384`); since
   `safeW = boxW - 8`, this technically tolerates a box up to `1.5*w + 8`. Not a gap in
   practice: candidate widths are min()-bounded in production
   (`TextLayoutPlanner.kt:1435-1440`) and exact candidate widths are pinned by the
   1.25/8%-page/1.50-page-edge/collision fixtures.
5. **Trial cost envelope.** `graphemeClusters` allocates a `BreakIterator` + list, and
   fit-pressure runs an extra `computeRects` + `binarySearchFontSize` + `overflows`,
   for every unmasked label-2 parentless horizontal block with >= 24 graphemes and
   ratio >= 2.0. Bounded per page, no dense/page-size allocation; other blocks return
   at an early gate with no allocation. Documented by the implementer.
6. **Hoist of `isVertical`/`placedObstacles` above the subpixel NonDraw exit**
   (`TextLayoutPlanner.kt:563-564`, removed from `:600-601` baseline) adds one
   `extentOf` list computation for blocks that then take `INVALID_OR_SUBPIXEL_SOURCE_RECT`.
   Pure: `drawable` is identical at both evaluation points within one iteration, the
   expressions are byte-identical to baseline, and the untouched 31-case
   TextLayoutPlannerTest plus all other pre-existing suites pass. No decision change.

## Per-acceptance-criterion verification (architecture "Slice 6", verbatim clauses)

| # | Architecture clause (slice-6 test list / acceptance) | Evidence | Result |
|---|---|---|---|
| 1 | Every eligibility gate, AND semantics | `boundedFreeTextWideningPlan` `TextLayoutPlanner.kt:1373-1380`: mask precondition, `label != 2`, `parentWidth > 0 && parentHeight > 0` (same valid-parent definition as `computeRects:2059`), `isVertical`, `graphemeClusters(text).count { !it.isBlank() } < 24`, `width <= 0 \|\| height/width < 2.0` (divide-by-zero guarded), fit pressure `baselineFont < minLegible \|\| overflows(text, minLegible, ...)` — all must pass; any failure → `null` → legacy. Gates individually violated → legacy: tests at `TextLayoutPlannerFreeTextTest.kt:216,230,249,259,273,286` | PASS |
| 2 | Ineligible blocks byte-identical legacy (incl. reshape) | `planPage:587`: `freeText == null` → the exact baseline call `computeRects(block, sampleSize, regionOverride)`; `buildMaskRegions:1670` skips unmasked ⇒ `regionOverride` unchanged; untouched 31-case suite green incl. reshape fixtures; `short free text matches the label 1 legacy decision bit for bit` (test:340-357) | PASS |
| 3 | Candidate 1 ALWAYS the unreshaped original OCR box | Baseline = `computeRects` with the OCR rect as override (`:1392-1397`); `computeRects:2088` reshapes only when `regionOverride == null` ⇒ unreshaped; `no qualifying candidate returns the unreshaped OCR baseline` pins `clipRect == FloatRect(300,200,400,500)`, `safeW 100`, `safeH 300` (test:131-147) | PASS |
| 4 | 1.25/1.50 widths with all four clamp terms | `:1435-1440`: `minOf(w*factor, w + 0.08*pageShortSide, pageClampedWidth, collisionFreeWidth)`; `pageShortSide = min(pageWidth, pageHeight)` (`:1424`); page clamp centered at OCR center `2*min(centerX, pageWidth-centerX)` (`:1426`); collision-free symmetric width with vertical band overlap filter (`:1493-1511`) | PASS |
| 5 | Deduplicate equal widths after clamping | `:1442-1449`: skip when `width - block.width <= 0.01` (not wider) or `\|width - previous\| <= 0.01`; epsilon is isolated tunable `FREE_TEXT_WIDTH_EPSILON` (`:420`) | PASS (see NOTE 3) |
| 6 | Smallest-qualifying-wins; >= 15% font gain or overflow removal; else OCR baseline | Factors tried in order 1.25 → 1.50 (`:1434`), first `qualifies` returns (`:1456-1457`); `font >= 1.15*baselineFont \|\| (baselineOverflows && !overflows at candidate)` (`:1454-1455`); rejection → `FreeTextWideningPlan(baseline, null)` (`:1460`) | PASS |
| 7 | Rejection → legacy label-2 baseline path intact (containment clip through OCR-center anchor) | `placeBlock` gets `regionOverride = null`; `anchorToOcrCenter` true via `block.label == 2` (`:784`), anchor branch `:992-996`; containment clip region = OCR box (`:793`, `:924-948`); pinned exactly by test:140-146 | PASS |
| 8 | No eligible path calls the legacy 1.5x..3.5x reshape | Trial evaluated before `computeRects`; `val rect = if (freeText == null) computeRects(...) else freeText.rect` (`:587`); reshape unreachable with a non-null override (`computeRects:2059,2088`); `RESHAPE_MIN/MAX_WIDTH_FACTOR = 1.5/3.5` (`:443-444`); gate-4/gate-6 tests prove reshape still fires for INELIGIBLE blocks only | PASS |
| 9 | Ineligible short SFX retain the exact legacy path | Grapheme gate precedes all allocation/fit work (`:1381-1382`); `gate 4 violated` test:259-270 pins the reshaped legacy box (font 14, safeW 160-170 — distinguishable from any trial box <= 150 and from the 92px baseline) | PASS |
| 10 | Final collision validation only after the OCR-center anchor | No slice-7 machinery in commit: `NO_DISJOINT_POST_ANCHOR_PLACEMENT` only declared (`:183`), never emitted; no `MAX_FINAL_PLACEMENT_ATTEMPTS` loop. Trial uses only the collision-free-width cap; `placeBlock` anchor/clip unchanged | PASS |
| 11 | Every eligibility gate (test) | 6 individual gate-violation tests + all-gates-met tests | PASS |
| 12 | Original OCR baseline (test) | test:131-147, exact clip/box/origin pins | PASS |
| 13 | Clamped/deduplicated 1.25 and 1.50 trials (test) | raw 1.25 exact (test:114-126: 12f / 117f); 1.50 exact (test:93-111: 9f / 142f); 8%-cap dedup exact 124/116 (test:152-165) | PASS |
| 14 | 8% page-add / page-edge / collision caps (test) | exact boundary pins: test:152-165 (124 = 100+0.08*300), test:168-181 (box right edge == 1000 within 0.01), test:184-211 (`safeW == collisionFreeWidth - 8` within 0.01, no overlap with obstacle extent) | PASS |
| 15 | No-gain rejection to OCR baseline (test) | test:131-147 (100-char token cannot fit any trial width) | PASS |
| 16 | No eligible width above 1.50 (test) | 81-fixture sweep (>= 27 asserted), test:362-393 | PASS (see NOTE 4) |
| 17 | Unchanged direction/orientation (test) | test:316-335: eligible/ineligible twins (Latin TTB → horizontal both; CJK → vertical both); `direction` is a `val` never copied by the trial | PASS |
| 18 | Short free text byte-for-byte legacy (test) | test:340-357: label-2 vs label-1 `BlockLayout` equality after `copy(block =)` | PASS |

## Invariants (architecture "Compatibility and acceptance")

- **Invariant 7** (eligible long free text uses only OCR, 1.25x, 1.50x bounded
  candidates and returns to OCR on rejection): VERIFIED — candidate set is exactly
  {OCR baseline, 1.25 clamped, 1.50 clamped}; no other geometry is constructed
  (`:1392-1461`).
- **Invariant 9** (ordinary unmasked/noneligible manga takes the legacy path):
  VERIFIED — all pre-existing suites pass with zero production edits outside the new
  trial; ineligible blocks hit the byte-identical legacy call (`planPage:587`).
- **One explicit result per nonblank input on every eligible path**: VERIFIED —
  eligible paths emit Draw (or an explicit budget NonDraw, unchanged semantics);
  identity test pins `(inputIndex, blockId)`, ordinals, single Draw (`:398-411`).
- **Direction/orientation never mutated**: VERIFIED — `TranslationBlock.direction`
  is an immutable `val`; `isVertical` expression `block.direction == "TTB" &&
  shouldRenderVertical(text)` is byte-identical to baseline, merely hoisted.
- **Slice-4 deferral pin present and meaningful**: VERIFIED — two new joined-lobe
  fixtures (`TextLayoutPlannerMaskMetadataTest.kt:332-393,395-428`): `planPage` twice
  with field-wise byte-identity (MaskGeometry normalized by value), exact slabs
  `[0,169)×[0,200)` / `[171,300)×[0,200)` (parent-biased vertical cut 170, dead rows
  {169,170}) and `[0,149)×[0,120)` / `[151,300)×[0,120)` (equal-center horizontal
  midpoint 150, dead columns {149,150}); hand-checked against the slice-3 gap math
  (`gap = ceil(clamp(0.2, 2, 4)) = 2`). The horizontal parent-biased case was already
  pinned at cut 400 by the pre-existing conjoined-cloud fixture (`:245-249`), so the
  pair covers both axes, bias, and the equal-center degenerate case.

## Masked-block exclusion (acceptance item 3): SANCTIONED, not drift

The architecture's slice-6 eligibility list is silent about masks; the implementer
added `if (block.segmentationMask != null) return null` before the gates
(`TextLayoutPlanner.kt:1374`). Assessment: **this is the only deterministic safe
reading**, because a masked tall label-2 block has no correct behavior anywhere in
the candidate flow:

- **Accepted candidate**: `placeBlock` would take the `regionOverride != null` branch
  (`:972-991`), anchoring at the widened-rect center and setting `clipRect = null` —
  discarding the mask region and, with it, the slice-2/3 structural containment
  contract (invariants 3/5) that governs masked blocks.
- **Rejected baseline**: the trial would return `regionOverride = null` with a rect
  computed from the OCR box; for a masked block the legacy path uses the mask region
  as `regionOverride` (`maskRegions`, `:556-561`) for anchoring, containment clipping
  (`:786-794,924`), and growth bounds. Passing null would silently change anchor and
  containment; passing the OCR rect would mis-measure (mask bounds != OCR box).
- The trial's own vocabulary ("original OCR box", OCR-center anchor, parentless
  padding) is defined on the unmasked legacy path; "no valid parent" free-text scope
  is that path.
- Exclusion loses nothing: the reshape can never fire for a masked block carrying a
  region (`computeRects:2088` requires `regionOverride == null`), so masked blocks
  were already outside the reshape/candidate problem space; slice-5 adaptive bands
  own optimized span-mode cells. The one masked-with-null-region case (beyond-8
  shared member) keeps its exact legacy path — including reshape — under the
  ineligible rule, consistent with "ineligible blocks byte-identical legacy".
- Backed by `masked long free text keeps the slice 5 cell contracts with no widening
  trial` (test:302-311): a slice-6-eligible-but-masked fixture asserts positioned
  lines (slice-5 ownership), proving the trial did not run.

## Documented deviations in the implementation report: per-deviation assessment

| # | Deviation | Assessment |
|---|---|---|
| 1 | `segmentationMask == null` path precondition | **Sanctioned** — see dedicated section above. |
| 2 | Candidates must be strictly wider (> 0.01 px) | **Sanctioned.** "Accept the smallest wider candidate": a clamped width at or below the original cannot lift the font or remove overflow; skipping is the correct reading. The epsilon is an isolated `TextLayoutTuning` constant per the README tuning envelope. |
| 3 | Baseline/candidate dims via `computeRects` with the rect as `regionOverride` | **Sanctioned.** Reuses the exact parentless padding/safe-dim math with zero duplicated formulas and structurally makes the reshape unreachable (`computeRects:2059,2088` require a null override). Eligible blocks have no valid parent, so the parentless textPad is the correct branch. |
| 4 | Straddling obstacle forces collision-free width 0 | **Sanctioned.** No positive-width symmetric box at that center can avoid a straddling obstacle; width 0 fails the strictly-wider test → baseline. Deterministic, fail-safe, and the strict reading of the fourth clamp term. |
| 5 | Acceptance fonts use `containerW = rect.baseW` | **Sanctioned.** Mirrors `placeBlock`'s initial fit call (`:813`); the FINAL layout font is `placeBlock`'s region-override refit at `(w-8)×(h-8)` (`:979-989`), and the tests pin those final values — the acceptance rule and the drawn font cannot diverge. |
| 6 | `placedObstacles`/`isVertical` hoisted above the eligibility check | **Sanctioned.** Pure move, byte-identical expressions, `drawable` unchanged between the two points within one iteration; proven by the untouched 31-case suite and all other pre-existing suites (see NOTE 6). |
| 7 | Slice-4 ride-along coverage split (vertical parent bias + equal-center midpoint; horizontal bias pre-pinned) | **Sanctioned.** Slice-4 acceptance is "midpoint output is golden and repeated-run deterministic for all joined fixtures"; the two new fixtures add exact slab pins and double-`planPage` byte-identity on both axes plus the degenerate case, complementing the existing cut-400 pin. |
| 8 | Determinism comparison normalizes `MaskGeometry` by value | **Sanctioned.** Geometry instances are per-plan by design (slice-2 session); field-wise equality of every other field plus value-equal spans/components is the correct byte-identity notion and mirrors the `PageLayoutPlanContractTest` convention. |

## Scope discipline (acceptance item 6)

- Commit touches exactly `TextLayoutPlanner.kt`, the new `TextLayoutPlannerFreeTextTest.kt`,
  `TextLayoutPlannerMaskMetadataTest.kt` (+2 tests), and the report — verified via
  `git show de818c6 --name-status`. No renderer / MaskTextRegionPlanner / MaskGeometry /
  BubbleMaskRle / pipeline changes.
- No slice-7 machinery: `NO_DISJOINT_POST_ANCHOR_PLACEMENT` declared but never emitted;
  no 8-candidate post-anchor loop, no shift/clip-refit candidates.
- Existing `TextLayoutPlannerTest` unmodified (absent from the commit) and 31/31 green.
- `direction`/`isVertical` expression untouched (verified against the `6e6fcdc` baseline).
- Instrumented pixel proofs remain compile-gated (no device available), consistent with
  slices 1-5; the slice-8 device gate owns them.

## Conclusion

Slice 6 is implemented exactly to the architecture: bounded candidates, exact clamp
terms, AND-semantics eligibility, byte-identical legacy for ineligible blocks, and a
sanctioned, well-tested mask exclusion. Recommend proceeding to slice 7 (final
post-anchor safety), which owns the residual collision risks documented above.
