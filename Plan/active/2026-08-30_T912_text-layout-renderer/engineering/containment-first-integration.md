# T912 containment-first integration — device-finding repair report

Branch `codex/text-layout-renderer`, worktree `t912-investigation-wt`.
One commit on top of the ANR-fix HEAD `8e168cf`. No rebase, no push.

## Device finding

The Director tested build 0.17.1-308 (commit `45c8f03`, verified on device by
`versionName` + byte-identical md5 `483c4d12…` between the local arm64 APK and
`pm path`'s installed base.apk) and reported: the chapter loads without the
freeze (the ANR fix works), but "the thing we prototyped is nowhere to be
seen — the same messy render layout still exists".

The laptop rig then confirmed it on the real page-15 fixture: the SHIPPED
planner sent **all 29 blocks down the legacy path** (`pos? leg` for every
block, "masked positioned fully contained: 0"), with the original far shifts
(blocks 6/7/8 shifted 136/149/133 px from their OCR homes) intact. The
prototype was real in the rig but absent in production.

## Root cause (verified, not inferred)

The contained-fit rescue had been wired as a **collision-resolution tail**
inside `resolvePostAnchorPlacement`, and the resolver only runs when
`footprintCollides(...)` is true. Two structural facts made that a no-op on
every real page:

1. **The far shifts happen before the check.** Legacy `placeBlock` dodges ALL
   placed extents (including same-mask neighbors) while choosing its box, so
   its output is usually collision-free by construction.
2. **Shared-mask members are collision-exempt anyway.** The shared-cell
   optimization partitions a mask into DISJOINT slabs;
   `footprintCollides` skips any pair whose hard cells are disjoint
   ("structurally pixel-separated"). Cloud neighbors could therefore never
   trigger the resolver against each other even when they did collide.

Both together: the resolver never ran on real pages → the rescue never ran →
the old messy render. The JVM tests missed this because their synthetic
fixtures were built WITH overlapping hard cells, which real disjoint-cell
pages never produce. An unmasked-only control run on the same fixture
reproduced the collision-free acceptance, and an adoption-gated bisect
(rescue computed but not adopted ⇒ block 23 back to baseline) pinned the
mechanism empirically.

Also ruled out during the investigation: mask starvation (the served chapter
artifacts DO carry masks — a 55-page census of the pulled Konoka chapter
shows masks on 456/516 blocks), settings gates (none exist), alternate render
paths (single overlay path: `TranslationOverlayView.bind` →
`TextLayoutPlanner.plan`, used by both pager and webtoon viewers), persisted
plan caches (layout is computed at bind time, never persisted), and stale
APKs (checksums match; the earlier "process predates install" alarm was a
false positive from the device's stepped clock — `ps` start-time projections
are unreliable on that ROM).

## The repair: containment-first

The rescue moved from the collision tail to the PRIMARY placement path, which
is what the Director actually approved (OCR box = home, mask = ceiling):

- `planPageInternal` now computes `cachedContainmentRescue` for EVERY masked
  block (one cached attempt per block per page; vertical blocks cache the
  result for the resolver without adopting it as a layout). A non-null result
  is adopted directly as the block's layout — no shift ladder, no reshape.
- New `rescueCeilingSpans` resolves the ceiling for EVERY masked block, not
  only shared-cell members: the block's own optimized cell spans → its
  assigned component in the group's page-sized `MaskGeometry` → the raw
  persisted mask converted under a dedicated page budget. Page-dimension
  checks keep every span space page-space. This closes the old "beyond
  MAX_SHARED_BLOCKS_OPTIMIZED gets no rescue" gap (rig blocks 0/1/10: cell
  but no component metadata — now positioned).
- Tier regions are clamped to the cell slab ONLY for a real assigned
  component cell. Overflow/bounds slabs from the partition fallback can be
  degenerate zero-width strips; clamping to them strangled every tier (found
  on the 9-member probe fixture: `region∩slab` = 0.0-wide for all four
  tiers). For those members the mask ceiling alone governs.
- The resolver receives the cached rescue as `precomputedRescue` (masked
  candidate 1) instead of recomputing — the page-wide band-fit budget is
  never double-spent, and "null" (no ceiling / no contained fit / budget
  exhausted) still drives the ladder + mask-unusable fail-open tail exactly
  as before.
- Adopted rescue layouts charge ONLY `positionedLinesUsed`. Charging the
  StaticLayout lane starved later UNMASKED blocks' placement budgets on dense
  pages (observed live: unmasked block 23 dropped to the 8 px floor until the
  lane split) — positioned lines are drawn via `drawText`, never
  StaticLayout.
- `MAX_RESCUE_FIT_CALLS_PER_PAGE` 24 → 96: containment-first now pays for
  every horizontal masked block (~13–20 on a dense masked page × 1–4 fits),
  not just the colliding subset.

## Verification on real data (page 15 fixture, 1280×1780, 29 blocks)

| metric | shipped build | containment-first |
|---|---|---|
| masked blocks on positioned lines | 0/13 | 13/13 |
| fully contained (metadata-checked) | 0 | 10/10, 0 partial |
| far shifts (blocks 6/7/8) | 136/149/133 px | 0 (parShift ≤ 10 px) |
| unmasked blocks 13–28 | baseline | byte-identical except block 23 (see below) |

Block 11 keeps a 40 px shift: its rescue centers the column in the grown
tier — vertical centering inside its own region, still 5/5 contained.

## Tests

- NEW `TextLayoutPlannerContainedRescueTest`: a solo masked block is placed
  by containment-first at its OCR home (positioned, contained, unclipped,
  deterministic); 9 siblings sharing one mask — including the beyond-cap
  member — all come out positioned and mask-contained. The old
  "non-colliding never pays" test pinned the exact dead wiring this repair
  removes, so it was rewritten to pin the live contract.
- Eight tests across Slice5 / QualityRepair / MaskMetadata / FinalSafety
  pinned superseded masked behavior (legacy rectangles, band acceptance
  numbers, harmony medians). Each was re-pinned to containment-first with its
  invariant class preserved; where machinery coverage would have been lost
  (hyphen insertion, band-beats-rectangle acceptance) the fixtures were
  reworked to reach the legacy/band path (degenerate 3×3 OCR boxes the rescue
  declines). The FinalSafety exemption test had become VACUOUS under adoption
  (the pair no longer collided); it was reworked so the hard-cell exemption
  is still proven non-vacuously at zero attempts.
- Full planner gate: 89 tests, 0 failures. Full `:app:testDevDebugUnitTest`
  module suite: green. androidTest sources compile.

## Known collateral (reported to Director, not yet tuned)

On MIXED pages, an unmasked neighbor of rescued blocks adapts to the new
obstacle geometry — page-15 block 23 ("Sexual intercourse...", unmasked TTB)
went 17 px → 8 px (floor). Mechanism (bisected): rescue extents are tight
painted envelopes while legacy extents are whole layout boxes; the changed
free-space asymmetry flips `placeBlock`'s vertical growth/dodge decisions.
Note the baseline font-17 column spilled ~3× outside its 48×105 OCR box, so
the new compact output is not obviously worse — the Director judges from the
device render. Fully-unmasked pages are untouched (no masks → no rescue →
identical planning), which is the actual "normal manga must not regress"
contract. Candidate follow-up: bring the rig's contact-scoot into production
for mixed-page neighbors instead of letting the ladder floor their fonts.

## De-shrink pass (Director feedback: "shrinking text is not ideal")

The first containment-first build capped the rescue font at the OCR box's
natural reflow fit, which regressed visible sizes on real pages (page-15
masked fonts vs the shipped baseline: 27→17, 24→14, 31→21). Three changes:

1. **Font cap = max(OCR natural fit, legacy rectangle fit)** — the size the
   old plan's own box supported is preserved as head-room; the exact
   containment walk still shrinks when the dilated mask cannot host it.
2. **Unslabbed tiers** — tier regions grow from the OCR box with NO clamp to
   the disjoint cell slab (slabs are partition artifacts, often smaller than
   the OCR box; clamping re-shrank the very fonts the cap raise freed).
   The mask ceiling alone governs growth — the rig-validated model.
3. **Ceiling dilation (`CONTAINMENT_CEILING_MARGIN_PX = 6`)** — the mask is a
   segmentation estimate, not the bubble contour; the containment predicate
   now checks against spans dilated by 6 px so edge-hugging text is not
   punished for segmentation tightness.

Measured on real page 15 (baseline → first containment-first → now):
b2 12→10→12, b3 16→13→15, b5 16.8→15→16, b7 12→12→15 (above baseline),
b8 10→10→12 (above), b12 31→21→25, b0 27→17→19. Containment still 10/10
metadata-checked, 13/13 positioned, far shifts still gone. Remaining below
baseline: b0 (27→19), b10 (24→14), b11 (18→13) — bound by genuine mask
containment at their homes (their baseline sizes crossed mask edges), and
unmasked block 23 (17→8) whose box-widening is starved by changed obstacle
geometry. That last class is the contact-scoot candidate: port the rig's
bounded contact-scoot so such blocks keep their font and dodge instead of
shrinking. The margin and cap are tuning knobs the Director can adjust.

Test updates: two containment assertions now check against the DILATED
ceiling (the contract); the Slice5 rescue fixture re-pinned to its
legacy-rect-fit font 43 (synthetic page-wide mask) with stroke/region pins
following; the harmony test re-pinned to the new median/cap arithmetic
(a capped 41→18, median 13). Planner gate 89 green; full module suite green
(one known-unrelated timing flake in MangaScreenModelTranslationDrawerTest
passes in isolation); androidTest compiles.

## Deployment state

Committed on `codex/text-layout-renderer`; APK rebuilt (`assembleDevDebug`,
arm64). NOT installed — the Director approves installs explicitly.
