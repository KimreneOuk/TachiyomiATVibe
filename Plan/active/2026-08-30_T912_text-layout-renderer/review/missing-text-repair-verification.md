# T912 missing-text REPAIR — independent verification

Reviewer: independent failure-mode audit of commit `44279b3`
(`fix(translation): T912 missing-text repair — placement safety never drops text`),
worktree `t912-investigation-wt`, branch `codex/text-layout-renderer`, clean tree.
Inputs: role file, investigation report (RC1–RC5), repair report (incl. deviations),
architecture rev-2 + addendum, live source and tests. Gates re-run by this reviewer.

## Verdict: ACCEPT

The repair delivers the Director mandate — *placement safety may never remove
text; worst case clipped/overlapping text, never a missing block* — with no
blocking defect found and no regression to the common path or the legacy
guarantee. Findings below are NOTE-level only.

---

## Findings

No BLOCKING findings. No MEDIUM findings. Five NOTEs:

- **N1 (NOTE, pre-existing, not a mandate violation)** — R2 pixel safety is
  proven at planner level and asserted by tests, but the production overlay
  consumes only `clipRect`, not `hardClip`/`cellRect`
  (`TranslationOverlayView.kt` `drawLayout`: applies `canvas.clipRect(layout.clipRect)`
  only; no `cellRect`/`positionedLines` consumption). Consequently, for
  exemption-protected pairs (disjoint hard cells, veto skipped via
  `hardCellsDisjoint`, TextLayoutPlanner.kt:928-942) actual painted overlap is
  still possible at paint time — the cells are planner fiction until the
  overlay consumes `hardClip`. This is exactly the integration gap the
  investigation §2 flagged (out of scope), and the mandate explicitly allows
  overlapping text. Evidence classification: VERIFIED (code read).
- **N2 (NOTE, test gap)** — the R2 fully-enclosed branch (no positive-area
  free strip → `layout.copy(clipRect = inkRectOf(layout, measurer))`,
  TextLayoutPlanner.kt:1344-1347) has no dedicated test; the impossible-space
  fixture exercises the positive-strip branch (free rect exists). The branch is
  three lines and correct by inspection (clip = own ink → ink remains visible),
  but the mandated "fully enclosed → own-rect containment clip" outcome is
  currently pinned only by inspection, not by a test.
- **N3 (NOTE, quality-only, documented)** — R3 zero-overlap assignment scans
  ALL components by nearest bounds center; on a thin-neck mask a block whose
  OCR box overlaps nothing can be nearest a 1px singleton neck fragment,
  yielding a ~1px hard cell (floor-font/clip-cramped output, mostly-illegible
  placement). Draws — never drops — and legacy was also degraded here
  (font 8, 104 px displacement, investigation §4). Correctly listed as
  residual risk 5 in the repair report.
- **N4 (NOTE, documented)** — partition cuts now span all members
  (MaskTextRegionPlanner.kt:195-221), so >8-member groups get narrower
  optimized slabs than before the repair. Verified bit-identical for ≤8-member
  groups by construction (`sorted.size == optimizedCount` makes every changed
  loop identical). No pinned test asserted the old >8 positions; the repair
  report documents this. The committed R4b test pins the new 9-member positions.
- **N5 (NOTE)** — `INVALID_OR_SUBPIXEL_SOURCE_RECT` (TextLayoutPlanner.kt:642)
  remains dead code: every `RectResult` producer clamps `safeW/safeH` to
  `max(1f, …)` (TextLayoutPlanner.kt:2836; the slice-6 free-text baseline and
  candidates are also `computeRects` outputs, 2078/2133). Unchanged by the
  repair; not a live drop path.

## Per-repair verification

| Repair | Verdict | Key evidence |
|---|---|---|
| R1 — EMPTY_SHARED_CELL abolished | PASS | Emission site removed: TextLayoutPlanner.kt:598-599 nulls an `empty` plan → `regionOverride = maskRegions[inputIndex]` (601-608), no `hardCell` (725), metadata untouched (`withSharedCellMetadata` 2621 returns layout for null plan). Span- and bounds-mode fixtures flipped and green (MissingTextReproTest `near-equal centers…`, `bounds-mode degenerate…`); focused placement-equality test pins fallback = legacy region (origin 47,150, floor font, no clip). Contract test flipped with documented reason (PageLayoutPlanContractTest). |
| R2 — ladder exhausted → clipped draw | PASS | `FinalResolution.layout` is non-null (836-845); tail fallback 1331-1347. Free strips (1229-1308) are cut past every non-exempt accepted occupancy (e.g. LEFT: `x1 = other.right` for any occupancy overlapping the strip's y-range and reaching left, 1238-1247; degenerate strips rejected at 1248) → clip rect disjoint from applicable accepted occupancies by construction; `fitIntoFreeRect` sets `clipRect = freeRect` (1403) and the overlay enforces `clipRect` at paint. Ladder unchanged before the fallback; cap still exactly 8 (FinalSafetyTest:303-304; repro test:412). Adaptive blocks take the legacy refit form (positionedLines dropped) — deviation 1, same convention as the slice-5 retry; containment still holds via the free-rect clip. |
| R3 — deterministic assignment | PASS | `MaskGeometry.componentForRectangleDeterministic` (MaskGeometry.kt:93-153): overlap accumulation over clamped rows; unique positive max early-return; tie/zero-overlap → nearest integer bounds center among the qualifying set, strict `<` over ascending ids → distance tie resolves to lower id; cannot return −1 (tied set always contains the max achiever; zero-overlap scans all components and `components.isEmpty()` is rejected at entry). `componentForRectangle` untouched (contrast asserts null-on-tie in two suites). Ties are scoped to the TIED components, so 1px neck fragments cannot steal a big-lobe straddler (deviation 3 — sound). 6 focused tests green. |
| R4a — dims-mismatch groups get cells | PASS | `buildSharedCellPlans` else-branch (2553-2596): mask bounds scaled into page space (`floor/ceil(·pageW/maskW)`, clamped to page, 2558-2563), degenerate scaled bounds `continue` → legacy path (no drop). Slabs disjoint by partition construction; `componentId = null` → `cellRect`-only metadata (2623) without ids; exemption active (hardCell = slab, 725). Dims-match case bit-identical: scale = 1 and `floor(int-valued bounds)` = raw bounds. Test pins exact disjoint page-space cells and absent ids. |
| R4b — beyond-8 overflow slabs | PASS | Cut sequence spans ALL sorted members (198-201); slabs from the same monotonic+half-gap (or equal-width `floorDiv`) construction → pairwise disjoint including overflow members. Integration maps non-null `overflowSlab` to `SharedCellPlan(slab, optimized=true, componentId=null)` (2528-2541, 2577-2587) → hardCell + exemption + `cellRect`-only metadata; degenerate overflow slab → null → pre-repair placement (draws; R2 covers collisions). Test: 9 members, pairwise-disjoint cells, `finalPlacementAttempts == 0`, exact tail slab `[241,0,300,120]`, in-place draw. |

## No-drop invariant (exhaustive enumeration): PASS

All `NonDraw` constructions in production `planPage` after the repair
(grep-verified, TextLayoutPlanner.kt):

1. `INVALID_OR_SUBPIXEL_SOURCE_RECT` (line 642) — dead code (N5), not a live drop.
2. `STATIC_LAYOUT_BUDGET_EXHAUSTED` (line 699) — GENUINE bounded-memory
   resource guard: fires only when reserving 2 more StaticLayouts would exceed
   `MAX_STATIC_LAYOUTS_PER_PAGE` (512 ≈ 256 horizontal legacy blocks on one
   page), checked BEFORE allocation (692-703). Not a placement-quality drop;
   unreachable for real manga; pinned by TextLayoutPlannerSlice5Test:142,170;
   retained in the architecture addendum.
3. `EMPTY_SHARED_CELL` / `NO_DISJOINT_POST_ANCHOR_PLACEMENT` — declared, never
   emitted (the only mentions left are comments; MaskTextRegionPlanner.kt:62 is
   a doc comment). Remaining 4 enum values are never emitted by the planner
   (unchanged from the investigation's enumeration).

No NEW drop path introduced: no `catch` blocks in TextLayoutPlanner.kt (no
exception→NonDraw conversion); R4a's degenerate-bounds `continue` (2564) and
R4b's degenerate-overflow null both degrade to the legacy draw path; every
nonblank input terminates in `Draw` or one of the two sites above
(`resultsByInput` is set on every path after the blank-text `continue` at 587,
the only intentional exclusion; collection at 787-788). The sweep test
(MissingTextReproTest `planPage emits no NonDraw across the missing-text
corpus`) asserts the invariant over all previously-dropping fixtures — honest
and meaningful, though of course corpus-scoped, not a proof over all inputs.

## Quality regression / legacy guarantee: PASS

- Non-colliding cell'd blocks take the identical path: the ladder runs only
  when `footprintCollides` (727); `resolution == null` → `chosen = layout`
  unchanged (761). No extra work on the common path.
- The ladder still runs entirely BEFORE the fallback: the clip refit is
  candidate 8 via `tryCandidate` (1310-1328); shifts/shrinks are preferred.
- Higher-score blocks never change: bit-identity asserted against the
  plan-without-B (repro test 429-434).
- ≤8-member partitions are bit-identical by construction (see N4).
- `TextLayoutPlannerTest` (31 legacy cases) untouched by the commit
  (last modified `1822f3a`) and green — unmasked non-colliding path intact.
- `componentForRectangle` contract unchanged; unique-max assignments take the
  same code path as before in the deterministic variant.

## Tests honest: PASS

The flipped `MissingTextReproTest` (11 tests, green) pins real fixed behavior:
flagship straddler+lobes all Draw with pairwise-disjoint cells and
`maskComponentId = 0`; anti-exile bound (<100 px); R1 span+bounds fallbacks and
exact legacy-region placement equality; R2 clipped draw with positive-area
clip, pixel-safety (clip ∩ each survivor's conservative occupancy = ∅, via an
accurate occupancy mirror), attempts == 8, and survivor bit-identity; R4a exact
disjoint page-space cells without ids; R4b disjoint cells + zero attempts +
exact tail slab + in-place origin; corpus sweep with no NonDraw. The new
`MaskGeometryDeterministicAssignmentTest` (6 tests) covers unique max, tie →
nearest center, distance tie → lower id, zero-overlap → nearest, degenerate →
null, repeat determinism, with legacy null-on-tie contrast asserts. No vacuous
assertions found. Protected-suite updates (FinalSafety, PageLayoutPlanContract,
MaskMetadata) each carry an inline documented reason, and the "impossible
space" test retains the ≤8-attempt assertion (FinalSafetyTest:303-304) and adds
the disjointness assertion; the concave-U flip (6 attempts → 0 attempts, arm
cell, nobody displaced) is a genuine consequence of R3.

## Scope and docs: PASS

Commit `44279b3` touches exactly: `TextLayoutPlanner.kt`,
`MaskTextRegionPlanner.kt`, `MaskGeometry.kt`, five test files (4 updated + 1
new), `architecture.md`, `missing-text-repair.md`. No download/batch files
(other AI's domain) touched. The architecture rev-2 addendum
(architecture.md:533-561) records the Director mandate verbatim and the amended
fallback matrix (both repaired enum values declared-but-unemitted; the
StaticLayout guard retained).

## Deviation assessments (all sound)

1. **R2 mechanism** — CORRECT and necessary: the mandate's literal
   "clip the SELECTED final geometry to the free rect" would paint nothing —
   the ink lies strictly inside the block's own conservative occupancy and the
   strip is cut back strictly outside it, so clipping the old geometry to the
   strip reintroduces the missing-text bug. Accepting the candidate-8 refit
   (floor-font fit centered in the free rect, `clipRect = freeRect`) satisfies
   the mandated test contract (clip disjoint from accepted occupancies, cap ≤ 8)
   and produces visible text. The fully-enclosed case implements the mandated
   own-rect containment clip literally.
2. **Retained `STATIC_LAYOUT_BUDGET_EXHAUSTED`** — correct: resource guard, not
   placement-quality drop; removing it would violate the bounded-memory
   constraint; pinned and documented.
3. **R3 tie scope (tied-only nearest-center scan)** — correct: scanning all
   components on ties would assign straddlers to 1px neck fragments, defeating
   the repair. Zero-overlap still scans all (mandated nearest-center rule;
   see N3 for the quality residual).
4. **Protected-suite flips** — each pinned outcome WAS the dropped behavior the
   mandate removes; reasons documented inline; every other protected suite
   unchanged and green.
5. **Residual quality risks** — as coded (see N3, N4 and the report's own list).

## Gate results (re-run by reviewer in the worktree)

| Gate | Result |
|---|---|
| `JAVA_HOME="…\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest` | BUILD SUCCESSFUL in 1m — suites=171, tests=1313, failures=0, errors=0, skipped=0 (aggregated from JUnit XML) |
| `… ./gradlew.bat :app:compileDevDebugAndroidTestKotlin` | BUILD SUCCESSFUL in 18s (exit 0) |

Focused counts confirmed from results XML: MissingTextReproTest 11/11,
MaskGeometryDeterministicAssignmentTest 6/6, TextLayoutPlannerTest 31/31.

## Claim classification summary

- "Every nonblank input draws; only the StaticLayout resource guard remains" — VERIFIED (enumeration above).
- "Free-strip clip disjoint from accepted occupancies by construction" — VERIFIED (strip cut logic 1238-1305 + pixel-safety tests).
- "Clip fallback not an extra candidate; cap stays 8" — VERIFIED (FinalSafetyTest:303, repro test:412).
- "R3 cannot return out-of-range ids / is deterministic / old contract kept" — VERIFIED (code + 6 focused tests + contrast asserts).
- "R4a bit-identical when dims match" — VERIFIED by construction (scale=1, integer-valued bounds) + unchanged pinned tests green.
- "Beyond-8 overflow slabs disjoint from the optimized 8" — VERIFIED (same monotonic/half-gap or equal-width partition) + test.
- "Painted pixels cannot overlap the accepted set" — VERIFIED at planner level and wherever `clipRect` is the mechanism; VERIFIED AS PLANNER-LEVEL ONLY for exemption-protected pairs until the overlay consumes `hardClip` (N1, pre-existing).
