# T912 slice 7 — independent verification review

Scope: commit `972e653` ("T912 slice 7 — finite final post-anchor safety and
explicit non-draw") on `codex/text-layout-renderer`, reviewed against
`engineering/architecture.md` revision 2 ("Slice 7: finite final post-anchor
safety", "Slice 5" occupancy/clip distinction, "Global budgets and candidate
limits", "Failure/fallback matrix", invariants 1/5/8/9), Director behavior
item 7 (task README), `engineering/slice-7-implementation.md` (report under
review, including its documented deviations), and `review/slice-5-verification.md`
(the obstacle-blindness caveat slice 7 closes). Baseline for behavior
comparison: `1bc8571`. Reviewer examined the commit, current file contents,
and the baseline planner; both gates were executed by the reviewer. Working
tree clean at HEAD = `972e653`.

## Verdict

**ACCEPT.** No blocking findings. The conservative occupancy semantics, the
hard-cell disjointness exemption, and the eight-candidate ladder are
implemented as mandated and pinned by non-vacuous exact-counter tests; the
attempt-counter seam measures evaluated candidates deterministically; the two
updated `MaskMetadataTest` fixtures assert the mandated outcome for genuinely
oversubscribed synthetic grids (arithmetic below); the non-colliding common
case is behavior-identical to baseline (`TextLayoutPlannerTest` 31 unmodified
and green; `PageTextRenderer`, `AdaptiveBandPlanner`, `MaskTextRegionPlanner`,
and the test file itself have empty diffs vs `1bc8571`). All 9 documented
deviations were verified against primary evidence and assessed below
(8 sanctioned, 1 sanctioned with a recorded caveat).

The slice-5 blocker-adjacent caveat ("adaptive placement is obstacle-blind;
visible overlap possible against earlier-placed unclipped neighbors") is now
CLOSED at the planning level: every post-anchor pair without two disjoint
hard cells is collision-checked on stroke/AA/half-gap-inflated occupancies,
and unresolvable pairs terminate in an explicit `NonDraw` instead of
overlapping ink. Residual real-page risk (resolution quality on crowded
pages) is correctly deferred to the slice-8 device gate by the implementer.

## Findings

### BLOCKING

None.

### MEDIUM

None.

### NOTE

1. **NOTE — Shifted ADAPTIVE candidates are not re-validated against
   component spans; only box-in-slab containment is checked.**
   `TextLayoutPlanner.kt:1218-1223` builds shift candidates with
   `translateLayout`, and `validates` (`:1099-1114`) checks box containment in
   page/slab, budgets, and occupancy collisions — but not the per-pixel
   span-ownership gate that `AdaptiveBandPlanner.kt:297-300` enforces at
   original construction. A shift can therefore move an adaptive line over a
   component hole inside its own slab; the glyph parts over the hole are then
   erased by the component clip (`PageTextRenderer.kt:77-78`). Structural
   containment (invariant 5) still holds — the paint cannot leave
   component ∩ slab — and the outcome degrades appearance only, failing safe.
   The implementer's deviation 2 documents the box-based reading. Evidence
   that would confirm: a fixture with a hole inside a slab plus a collision
   forcing a shift across it. Options: re-run the band validator per shift
   candidate (more work), or accept and watch slice-8 device pages.
2. **NOTE — Candidate 8 uses up to two font binary searches, so the
   architecture sentence "at most seven font binary-search steps [per
   candidate]" is exceeded in the letter (2 × ≤7).**
   `fitIntoFreeRect` (`TextLayoutPlanner.kt:1352-1400`) runs a two-pass fixed
   point on the inflation estimate, each pass one `binarySearchFontSize`
   (≤ `MAX_FONT_BINARY_STEPS = 7`, `TextLayoutTuning.kt` guard at
   `TextLayoutPlanner.kt:398`, search at `:2837-2881`). Documented as
   deviation 7; bounded, deterministic, fails safe (occupancy collision check
   still rejects unusable slivers). Worst case per colliding block stays
   8 candidates × ~14 search evaluations. Recommend recording the amendment in
   the architecture at the slice-8 gate rather than changing code.
3. **NOTE — Candidate 1 can never validate when the resolver is engaged.**
   The resolver is entered only when `footprintCollides` is true
   (`TextLayoutPlanner.kt:731`), and candidate 1 re-validates the same
   geometry with the same predicate (`:1131`, `:1113`) — so attempt 1 is a
   guaranteed failure. This matches the architecture's mandated candidate
   list ("(1) selected final geometry"), costs one of the eight attempts, and
   keeps the effective resolution ladder at seven useful candidates. Expected
   behavior; recorded so the exact-counter expectations are readable.
4. **NOTE — The `MAX_FINAL_PLACEMENT_ATTEMPTS` constant is not referenced at
   runtime; the ≤8 bound is structural.** The ladder of
   `resolvePostAnchorPlacement` contains exactly eight `tryCandidate` call
   sites (`TextLayoutPlanner.kt:1131,1165,1201,1223,1326-1330`), which is a
   stronger guarantee than a checked guard, but means retuning the constant
   alone would not change behavior. Documentation-only hazard.
5. **NOTE — Adaptive-vs-unmasked occupancy collision is exercised only
   indirectly.** The exemption test pins adaptive-vs-adaptive (zero attempts,
   `TextLayoutPlannerFinalSafetyTest.kt:323-357`) and the concave test pins a
   legacy/region-vs-unmasked collision (`:179-222`). No fixture has an
   ADAPTIVE layout colliding with a cell-less (unmasked/groupless) neighbor —
   the slice-5 scenario-(c/d) closure is proven by code inspection
   (`hardCellsDisjoint` requires BOTH cells non-null, `TextLayoutPlanner.kt:938-939`;
   cellless placed blocks store `cellRect = null`, `:786`) rather than by a
   dedicated test. Low risk (single shared predicate), but a one-block fixture
   would close it completely.
6. **NOTE — Post-anchor NonDraw keeps any budget already reserved by the
   block's failed attempts** (adaptive reservation retained; legacy +2
   retained; replacement candidates that shrink free reservations only on
   success). Over-reservation only — it can cause a spurious
   `STATIC_LAYOUT_BUDGET_EXHAUSTED` for a LATER block on an already near-cap
   page, never an under-count (invariant 10 preserved). Documented by the
   implementer ("Remaining risks"); acceptable, watch slice-8.
7. **NOTE — Duplicate/unconstructible candidate counting is a documented
   ambiguity, resolved defensibly.** The architecture says duplicates are
   "skipped but do not create replacements" without fixing the counter
   semantics; the implementation counts only EVALUATED candidates
   (`tryCandidate`, `TextLayoutPlanner.kt:1119-1128`). This is the only
   reading under which "at most eight candidates" stays a work bound while
   skipped slots stay empty, and the tests pin the semantics exactly (test 1:
   4 evaluated with 1 duplicate skipped; page-edge test: right-shift skipped
   uncounted at zero room). Sanctioned; recorded because an alternative
   reading exists.
8. **NOTE — Groupless-no-metadata pin strength in the 34-mask fixture is
   reduced, with compensation elsewhere.** In the updated 34-block fixture
   `b32` non-draws, so `assertNoMetadata` survives only on the dead Draw
   branch (`TextLayoutPlannerMaskMetadataTest.kt:309-313`). The property
   "groupless/fallback DRAWN layout carries no geometry ids" remains pinned by
   the 100-islands fixture in the same file (`:258-282`: `maskGeometry`,
   `planGeometryId`, `maskComponentId` all null on a drawn layout) and by the
   assertion at `:194`. The implementer's residual-pin note is accurate and
   the compensation is adequate; a dedicated drawing-groupless fixture remains
   optional hardening.

## Per-acceptance-criterion verification (architecture "Slice 7" test list)

Architecture slice-7 text (revision 2, `architecture.md:380-411`) plus the
acceptance items in the review assignment:

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 1 | Score-desc-then-input priority; anchors finalized BEFORE the safety pass | PASS | `ordered` sort unchanged (baseline loop); safety pass runs after every anchor/branch at `TextLayoutPlanner.kt:723-758`, after free-text/adaptive/legacy anchor selection (`:607-722`) |
| 2 | Occupancy semantics: legacy = `extentOf` inflated by stroke+AA+gap/2 per side; adaptive = union bbox of per-line conservative occupancies; un-clipped | PASS | `conservativeOccupancyOf` `TextLayoutPlanner.kt:868-902` (all four sides inflated by `computeStrokeWidth + aaGuard + gapHalf`); per-line inflation `AdaptiveBandPlanner.kt:283-284,301-306` (stroke + AA + `collisionGapPx/2f` on every side); `gapHalf = collisionGap/2f` `TextLayoutPlanner.kt:582`; nothing intersects the occupancy with clips — un-clipped by construction. Scale plumbing: `scale = 1f/sampleSize` `:530`, `aaGuard(scale) = max(1*scale, 0.5)` `:370`, `collisionGapPx = ceil(clamp(0.001*shortSide, 2*scale, 4*scale))` `MaskTextRegionPlanner.kt:140-141` — formulas match the architecture verbatim |
| 3 | Exemption: both cells non-null + `!overlaps` (touching = separated) → occupancy overlap not a collision | PASS | `hardCellsDisjoint` `TextLayoutPlanner.kt:938-939`; `FloatRect.overlaps` is positive-area (touching excluded, `:53-55`); applied in `footprintCollides` `:942-950` and in shift/free-rect obstacle filtering. Exempt pairs are structurally separated because EVERY placed Draw with a non-null `cellRect` is canvas-clipped to it on all three render paths (`PageTextRenderer.kt:77-79` before the Horizontal/Vertical/Positioned dispatch `:128-133`), and wiring guarantees `cellRect = slab` whenever `cellPlan.optimized && slab != null` (`withSharedCellMetadata` `:2559-2575`, every branch) — candidate-side `hardCell` and placed-side `final.cellRect` are the same slab value, so no asymmetric hole |
| 4 | Adaptive-vs-adaptive adjacent slabs → ZERO attempts; adaptive-vs-unmasked and legacy pairs DO collide | PASS / PASS by inspection | Zero-attempt exemption pinned non-vacuously (`TextLayoutPlannerFinalSafetyTest.kt:323-357`: occupancy boxes asserted overlapping, attempts == 0, slabs `[0,300]x[0,59]` / `[0,300]x[61,120]`); legacy-vs-unmasked collision + resolution pinned (`:179-222`); adaptive-vs-unmasked covered by the single shared predicate (NOTE 5) |
| 5 | Exemption holes checked (own-component dead zone; cell vs page edge) | PASS | Dead-zone pair: both cells non-null and disjoint → exempt; paint is clipped to disjoint slabs and the dead zone is unowned by both — no leak path. Page edge: exemption is irrelevant to bounds; box-in-page containment checked separately (`validates` `:1103-1109`). Cross-group overlapping slabs: `hardCellsDisjoint` false → collision applies. No hole found |
| 6 | Eight-candidate ladder: exact set/order; first validating candidate wins | PASS | `resolvePostAnchorPlacement` `TextLayoutPlanner.kt:1051-1334`: (1) as-is `:1131`; (2) next smaller font `:1134-1165` (adaptive: band refit with `maxFontPx = fontPx-1`; legacy: `fontSizePx-1` floored at `FIT_MIN_FONT_PX*scale`); (3) baseline `:1168-1201` (adaptive: exact slice-5 `placeBlock` call; legacy: `allowGrowth = false` pre-growth base rect); (4-7) min L/R/U/D shifts `:1206-1223`; (8) one free-rect clip/refit `:1231-1331`. Every step `tryCandidate(...)?.let { return }` — first validation wins |
| 7 | Duplicates after clamping skipped; counted-as-evaluated semantics pinned | PASS (documented reading) | `tryCandidate` `:1119-1128` (dedup via data-class equality, uncounted skip); pinned by test 1 (attempts 4 with a duplicate candidate 3) and the page-edge test (right shift skipped at zero room) — see NOTE 7 |
| 8 | ≤7 font steps per candidate; candidates recompute anchor→lines→stroke→occupancy→containment→collisions | PASS (equivalence; candidate 8 caveat) | Candidates 2/3/8 recompute wrap+font via `fitAdaptiveBands`/`cjkWrap`/`binarySearchFontSize` at construction; shifts translate anchor/lines/occupancy exactly with stroke unchanged (equivalent); `validates` then checks containment → budgets → occupancy collisions (`:1099-1114`) — pure predicates, order-insensitive AND. Each individual search ≤7 steps (`:398`, `:2850`); candidate 8 runs ≤2 searches (NOTE 2) |
| 9 | Shifts minimal, from inflated occupancies, constrained to page/component/cell | PASS | `minShiftDisplacement` `:990-1030` — bounded fixed point over applicable (non-exempt) placed occupancies, minimal d clearing all; clamped `coerceAtMost(maxDisplacement)` where `maxDisplacement` derives from `bounds = page ∩ hardCell` (`:1076-1086`) and box containment re-checked in `validates` |
| 10 | Candidate 8 free-rect: four strips around accepted occupancy → max area → shortest displacement → fixed L/R/U/D; only that rectangle fitted | PASS | Four strips `:1231-1312` (each cut only by blockers that REACH into the strip; straddlers degenerate the strip); selection `compareByDescending(area).thenBy(freeRectDisplacement).thenBy(direction)` with LEFT=0/RIGHT=1/UP=2/DOWN=3 (`:1315-1320`); single `fitIntoFreeRect` with `clipRect = freeRect` `:1321-1330,1352-1400` |
| 11 | Only the colliding lower-priority block changes; higher-score layout bit-identical | PASS (pinned) | The ladder runs only inside the colliding block's iteration; test 1 asserts the higher-score block is bit-identical to its solo plan (`TextLayoutPlannerFinalSafetyTest.kt:115-116`); font equalization remains removed (grep: `equalizeSharedMaskFonts` absent) |
| 12 | Accepted occupancy stays conservative and un-clipped | PASS | Placed footprints store the un-clipped conservative occupancy (`:784-789`); nothing clips occupancies (grep + read of slice-7 code) |
| 13 | All fail → `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)`; identity/cardinality intact; renderOrdinal null | PASS | `:760-772` (explicit NonDraw, null renderOrdinal, `continue` — not placed, not an obstacle); pinned by the impossible-space test (`TextLayoutPlannerFinalSafetyTest.kt:267-318`: 4 explicit results, exact text/identity order, `renderOrdinal` null, consecutive `[0,2,1]` over survivors, byte-identical replay) and both MaskMetadata fixtures |
| 14 | Attempt counter seam counts evaluated candidates summed over colliding blocks | PASS | `planPageInternal`/`PagePlanWithAttempts` `:543-803`, accumulation `:759`; exact pins 4/3/6/4/2/8 and the 0-attempt exemption (see "Counter trustworthiness" below) |
| 15 | Deterministic render ordinals; `COLLISION_GAP`/`AA_GUARD` formulas | PASS | `renderOrdinal = drawable.size - 1` after append (`:783,795`) — consecutive in placement order; `COLLISION_GAP` `MaskTextRegionPlanner.kt:140-141`, `AA_GUARD` `TextLayoutPlanner.kt:370` — both verbatim vs `architecture.md:410-411` |

### Counter trustworthiness (assignment point 4)

The seven FinalSafetyTest counters (4, 3, 6, 4, 2, 8, 0) are exact
`shouldBe` pins against the seam, and each is corroborated by independent
outcome assertions, so none can pass vacuously:

- **4 (anchor):** winner geometry pinned (`originX == 403.68f`, `:111`), font
  unchanged, A bit-identical to solo plan (`:115-116`). The count 4 = {1, 2,
  left, right} with candidate 3 a pinned duplicate skip — a wrong order, a
  counted duplicate, or a wrong winner all flip it.
- **3 (thick stroke):** detection depends on the inflation (bare extents 11.2
  px clear; occupancies overlap ~10 px — a no-stroke check would produce
  attempts == 0 and no resolution); winner pinned (`originX ≈ 105.92`,
  inflated-left-edge bound `:171-173`).
- **6 (concave):** three shift directions evaluated in fixed order before UP
  wins; winner pinned (`originY ≈ 90.12`, `:221`) and A keeps the legacy
  region path (`positionedLines` null, `:213`) — proving the conservative
  bbox (not a hole-aware check) drove the conflict.
- **4 (page edge):** pins BOTH skip rules (duplicate baseline; right shift at
  zero legal room) and the clamp-fail path (left shift clamped, still
  collides); winner pinned (`originY ≈ 149.92`, x unchanged `:244-245`).
- **2 (winning at k):** 0.54 px overlap resolved by exactly one font step;
  font and unshifted origin pinned (`:262-263`).
- **8 (impossible):** all eight evaluated with none skipped, NonDraw reason +
  null renderOrdinal + identity order + consecutive survivor ordinals +
  byte-identical replay (`:290-317`). The count 8 fails under any
  under-counting, double-counting, or early exit.
- **0 (exemption):** attempts == 0 with occupancy boxes asserted to overlap
  (`:353-356`) — explicitly non-vacuous; without the exemption this page
  would evaluate the full ladder and mutate both layouts.

Hand-recomputation of test 1 from the documented constants (stroke
`max(2, 0.12*32) = 3.84`, AA 1, gap `clamp(0.3, 2, 4) = 2` → inflation 5.84,
extents [104,296]/[254,446] → occupancies [98.16,301.84]/[248.16,451.84])
confirms the collision, the failed smaller font, the 53.68 px right-shift
minimum, and the resulting touch-not-overlap at 301.84 — the fixture
arithmetic is genuine, not reverse-engineered from outputs.

## Fixture-arithmetic check (assignment point 5, CRITICAL)

**129-block fixture** (`TextLayoutPlannerMaskMetadataTest.kt:316-346`): 13
columns × 10 rows on a 300×120 page; boxes 20×8 at 23 px × 9 px pitch; all
masks distinct instances of one full-page geometry (reference/fingerprint
cap intent preserved — still exercised).

- Minimum font is `FIT_MIN_FONT_PX * scale = 8`; at font 8 stroke =
  `max(2, 0.96) = 2`, AA = 1, gap = `ceil(clamp(0.001*300, 2, 4)) = 2` →
  inflation **4 px/side**. Even a 3-char text wraps to a ~14.4×9.6 extent →
  occupancy ≈ 22.4 × 17.6 per block.
- Vertical pitch 9 px < 17.6 px occupancy height → every block's occupancy
  spans ≥ 2 rows; horizontal pitch 23 px ≈ 22.4 occupancy width → adjacent
  columns touch/overlap. Axis-aligned packing limit at these occupancies:
  floor(300/22.4) × floor(120/17.6) = 13 × 6 = **78 < 129**. The page is
  oversubscribed under the MANDATED inflation; measured 58 draws + 71
  non-draws is at/below the theoretical limit (the first 8 optimized-cell
  members get larger fonts, consuming more area), i.e. the resolver converges
  toward the packing limit rather than giving up early.
- Identity cardinality (129 results, exact multiset) and renderOrdinal
  consecutiveness (`mapNotNull == 0 until drawable.size`) are pinned
  (`:336-339`); every NonDraw must carry the mandated reason (`:340-345`).

**34-block fixture** (`:284-314`): 34 distinct 1-px-island masks; the
32-unique-mask cap leaves late masks groupless, whose fallback regions span
the full 300×120 mask rect, so `placeBlock` fits font-72 legacy layouts
(extent ≈ 130×86, inflation ≈ 10.64/side → occupancy ≈ 150.9×107.7 ≈
16,250 px² each). Two such occupancies already fill 300×120 (36,000 px²);
34 of them are arithmetically unresolvable — non-draws for all but the first
are forced for ANY conforming implementation.

**Inflation sanity (point 5c):** at scale 1 the per-side inflation is
`max(2, 0.12*font) + 1 + 1` = 4 px/side at the font-8 floor and ≈ 10.6 px/side
at font 72. A normal bubble outline at scale 1 is 2–8 px (`MIN_STROKE_PX = 2`,
`STROKE_WIDTH_FRACTION = 0.12`, `TextLayoutPlanner.kt:477-478,2883-2888`), so
stroke+AA+half-gap of ~4 px/side for small text is proportionate, not
over-inflation; the fixtures fail because the synthetic grids (9 px pitch,
font-8 minimum, 2 px mandatory stroke, 1 px AA, 1 px half-gap) are denser
than any real page, not because the envelope is inflated beyond the
architecture formula. The pre-slice-7 behavior for these fixtures (silent
overlapping draws) is exactly what Director behavior 7 and the failure-matrix
row "Final eight candidates unsafe → NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)"
(`architecture.md:507`) forbid. **The updated expectations are the MANDATED
outcome.**

**Point 5d:** `assertNoMetadata` survives on the Draw branch of the b32 pin
(`:311`) and elsewhere (`:194`); the drawn-groupless-no-ids property is
additionally pinned by the 100-islands fixture (`:258-282`). See NOTE 8 for
the residual strength assessment.

## Legacy byte-identity (assignment point 6)

- `git diff 1bc8571 972e653` is EMPTY for `TextLayoutPlannerTest.kt`,
  `PageTextRenderer.kt`, `AdaptiveBandPlanner.kt`,
  `MaskTextRegionPlanner.kt`; the commit touches exactly
  `TextLayoutPlanner.kt`, the NEW `TextLayoutPlannerFinalSafetyTest.kt`,
  `TextLayoutPlannerMaskMetadataTest.kt`, and the report (verified via
  `git show 972e653 --name-status`). `TextLayoutPlannerTest` 31/31 green,
  unmodified.
- The obstacle list used DURING placement is still `drawable.map { extentOf }
  }` with the pre-slice un-inflated `extentOf` (`TextLayoutPlanner.kt:615`,
  `extentOf` at `:2269-2309`) — only the FINAL check uses the inflated
  occupancy (`:730`). Placement ordering, `placeBlock` semantics (new
  `allowGrowth` param defaults to true, sole new call site passes false),
  `computeRects`, free-text widening, and the adaptive attempt are unchanged.
- For non-colliding blocks the only new work is
  `conservativeOccupancyOf` + `footprintCollides` (`:730-731`), both read-only
  — no double validation, no ordering change. Verified by the untouched green
  suites (186 tests, including the slice-5 budget discriminator and the
  free-text suite).

## Implementer bug fixes (assignment point 7)

- **Shift-displacement formulas:** final forms are correct. For each
  direction the incremental clearance is measured against the moved rect's
  near edge past the obstacle's far-side boundary — e.g. LEFT:
  `clear = moved.right - other.left` (`:1019-1024`), accumulated
  `need = max(need, d + clear)` and iterated to a bounded fixed point, with
  full-set re-validation in `validates` so a non-converged value can only
  fail a candidate, never over-accept. The earlier inverted formulas (which
  produced no-ops) would fail the thick-stroke and anchor tests.
- **Free-rect strips:** the implemented condition cuts a strip only for a
  blocker that overlaps the occupancy's cross-axis range AND protrudes past
  the block's near edge AND pokes past the current cut (e.g. LEFT:
  `other.top < occupancy.bottom && occupancy.top < other.bottom &&
  other.left < occupancy.left && other.right > x1`, `:1240-1247`); a
  straddling blocker degenerates the strip. Far-side blockers no longer kill
  strips (the reported over-cut bug) and straddlers no longer leave bogus
  rects (the reported under-cut bug). Consequence pinned by the
  impossible-space test, where exactly one strip (UP) survives and its refit
  legitimately fails.

## Deviation assessments (report's "Intentional deviations" 1-9)

| # | Deviation | Assessment |
|---|---|---|
| 1 | Skipped duplicates/unconstructible candidates not counted | **SANCTIONED.** Architecture fixes neither reading; this one keeps "at most eight" a work bound with skipped slots empty, and both skip rules are exactly pinned (tests 1 and page-edge). NOTE 7 records the ambiguity. |
| 2 | Containment on the painted box, collisions on the occupancy | **SANCTIONED.** Direct consequence of the architecture's own occupancy/clip distinction ("conservativeOccupancy … never intersected … HardClip is exact structural containment"); adaptive containment delegated to band gates + per-candidate box-in-slab check. Residual hole-clipping nuance = NOTE 1. |
| 3 | Shift bounds = page ∩ hard-cell slab (parent/OCR not additional bounds) | **SANCTIONED.** Parents bias cuts only (slice 3); the slab is inside the component; a second bounds system would fight the clamp-fail semantics the page-edge test pins. |
| 4 | Strips cut only for blockers reaching into them | **SANCTIONED.** Correct reachability semantics; both prior buggy variants are refuted by existing tests (see "bug fixes" above). |
| 5 | Adaptive candidates re-check page line/layout budgets | **SANCTIONED.** Required by invariant 10 when a candidate changes line count (`pageBudgetAllows` `:1091-1097`); over-reservation direction preserves safety. NOTE 6 records the residual. |
| 6 | Legacy candidate 2 floor at `FIT_MIN_FONT_PX*scale`, not `minLegible` | **SANCTIONED.** Matches the actual legacy fit floor (`binarySearchFontSize` low bound `:2848`) and the clip net's floor-skipping; the ladder is a last resort below the legibility preference, not below the fit floor. |
| 7 | `fitIntoFreeRect` two-pass fixed point (≤2 font searches) | **SANCTIONED with recorded caveat.** Exceeds the "≤7 steps per candidate" sentence in the letter (NOTE 2); each search is bounded, deterministic, and collision-checked. Recommend an architecture amendment note at slice 8. |
| 8 | Non-colliding common case unchanged | **SANCTIONED (verified).** Code inspection + empty diffs + 186 green tests incl. the untouched 31-case legacy suite. |
| 9 | `translateLayout` re-floors positioned-line pixels, translates float occupancies exactly | **SANCTIONED.** Preserves the integer render contract; the candidate's collision envelope equals the translated original envelope exactly (no under-count); ≤1 px int/float slack is conservative. |

## Scope and gates (reviewer-executed)

- Scope: `git show 972e653 --name-status` — exactly
  `TextLayoutPlanner.kt` (M), `TextLayoutPlannerFinalSafetyTest.kt` (A),
  `TextLayoutPlannerMaskMetadataTest.kt` (M), `slice-7-implementation.md` (A).
  No renderer/geometry/overlay/pipeline/batch/download/drawer/progress
  changes; no slice-4 neck creep; no debug logging in the new production code.
- Gate 1:
  `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
  → **BUILD SUCCESSFUL in 29s.** JUnit XML aggregate: **186 tests,
  0 failures, 0 errors, 0 skipped across 20 suites** — FinalSafetyTest 7/7
  (NEW), MaskMetadataTest 10, TextLayoutPlannerTest 31 (unmodified),
  Slice5 8, FreeText 17, LineBreaker 19, AdaptiveBandPlanner 9,
  MaskTextRegionPlanner 13, Contract 6, Direction 8, Stroke 4,
  ComponentClipCache 6, MaskGeometry 7+9+2, BubbleSegmentationDecoder 8,
  RenderColorEstimator 22. Matches the implementation report exactly.
- Gate 2: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
  → **BUILD SUCCESSFUL in 23s.** No device available; instrumented pixel
  proofs remain compile-gated for the slice-8 device gate (consistent with
  slices 1-6).
- `git status --short` clean at HEAD; `git diff 1bc8571 972e653` per-file
  hunk review (planner diff fully read; helper files diff-empty).

## Relation to prior reviews

- Slice-5 MEDIUM-adjacent caveat (deviation 12: adaptive obstacle-blind
  placement; visible overlap possible vs earlier-placed unclipped neighbors,
  scenarios c/d): **CLOSED at the planning level** by the post-anchor pass —
  every pair without two disjoint hard cells is now occupancy-checked, and
  unresolvable blocks non-draw explicitly. The structural half (component +
  slab clips) was already in place; slice 7 adds the planning-level
  disjointness or the explicit refusal. Re-check on device at slice 8.
- Slice-5 NOTE 1 (`replaceFirst` substring hazard in hyphen trials): still
  open, orthogonal to slice 7; carried as optional hardening.
- Slice-5 NOTE 7 (adaptive phantom-footprint obstacle conservatism): now
  also applies to collision detection by design; covered by the mandated
  un-clipped semantics and the exemption.

## Conclusion

Slice 7 implements the mandated finite final post-anchor safety exactly:
conservative un-clipped occupancy after every anchor, the hard-cell
exemption, the bounded eight-candidate ladder with the correct free-rect
selection, explicit `NO_DISJOINT_POST_ANCHOR_PLACEMENT` with intact identity,
and zero behavior change for the non-colliding common case. The two updated
MaskMetadata fixtures assert the arithmetic-forced, Director-mandated
outcome. ACCEPT; the eight NOTEs above are non-blocking, with NOTE 1
(adaptive shift vs component holes) and NOTE 2 (candidate-8 font-step
budget) recommended for the slice-8 device gate watch list.
