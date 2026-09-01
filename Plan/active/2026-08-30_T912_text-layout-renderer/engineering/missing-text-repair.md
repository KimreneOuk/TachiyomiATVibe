# T912 missing-text REPAIR — implementation report

Branch `codex/text-layout-renderer`, base HEAD `64e2b78` (rebased on the
t913 batch-fix tip `48b42bc`; not rebased further during this slice).
Implements the four repairs (R1–R4) of the Director's visibility override on
top of the committed root-cause analysis
(`missing-text-investigation.md`, RC1–RC5), amending architecture rev-2 with
the "Revision 2 addendum — visibility override" section.

## Result

**Placement safety never drops text.** `TextLayoutPlanner.planPage` no longer
emits `NonDraw(EMPTY_SHARED_CELL)` (RC2) or
`NonDraw(NonDrawReason.NO_DISJOINT_POST_ANCHOR_PLACEMENT)` (RC1 drop path) —
both enum values stay declared but are unemitted. Every nonblank input draws;
the worst case is clipped or overlapping text, exactly per the Director
mandate quoted in the architecture addendum.

### R1 — EMPTY_SHARED_CELL abolished (RC2)

`TextLayoutPlanner.planPageInternal`: an empty/degenerate `SharedCellPlan`
(inverted slab in span or bounds mode) no longer short-circuits to a NonDraw.
The plan is nulled for that block, which routes it through the pre-slice-3
region path: `buildMaskRegions` legacy region as `regionOverride` (parent box
if valid / legacy midpoint partition / OCR rectangle), NO `cellRect`, NO
`hardClip`, no adaptive bands, no metadata — it draws exactly like the legacy
renderer. Focused test pins the fallback placement equal to the legacy region
(origin at the legacy midpoint-region center, floor font, no clip).

### R2 — ladder exhausted → clip, don't drop (RC1 drop, RC3 drops)

`resolvePostAnchorPlacement` never returns a null layout. After the unchanged
eight-candidate ladder (cap still exactly 8 evaluated candidates; the fallback
is NOT an extra candidate):

- A positive-area free rectangle exists (the candidate-8 computation — four
  free strips around the block's conservative occupancy within page ∩ hard
  cell, cut past applicable accepted occupancies, max area → shortest
  displacement → fixed order): the candidate-8 refit layout is accepted
  as-is. Its hard `clipRect` IS that free rectangle, disjoint from every
  applicable accepted occupancy by construction — painted pixels cannot
  overlap the accepted set (renderer and overlay both honor `clipRect`; the
  metadata wiring adds `cellRect` on top for cell'd blocks, and the free rect
  ⊆ that slab, so containment only tightens).
- Fully enclosed (no positive-area strip anywhere): the block draws with a
  containment clip of its own rect (`clipRect = inkRectOf(layout)`) — still a
  Draw with visible text; overlapping text is the accepted worst case.

Test asserts the pixel-safety property (clip ∩ accepted occupancies = ∅),
attempts == 8, and higher-score blocks bit-identical to their uncollided plan.

### R3 — deterministic component assignment for straddlers (RC1 root)

New `MaskGeometry.componentForRectangleDeterministic(left, top, right,
bottom)`: unique positive max overlap wins; exact overlap tie → among the
TIED components the one whose integer bounds center is nearest the OCR-rect
center; further distance tie → lower component id; zero overlap with all →
nearest bounds center over all components (same tie rules). Degenerate
rectangles / empty component lists stay `null`.
`TextLayoutPlanner.resolveComponentId` now uses it.
`componentForRectangle` keeps its null-on-tie contract for its other callers
(pinned). Conjoined-mask straddlers always get a cell, so the hard-cell
exemption applies and the flagship repro draws all three blocks in place.

Interpretation note (deviation D1 below): "nearest bounds center" for an
overlap tie is evaluated among the tied components; the all-components scan
applies only to the zero-overlap case. Scanning all components on ties would
assign straddlers to 1px singleton neck fragments, defeating the repair's
purpose.

### R4 — hardening: dims-mismatch and beyond-8 groups get cells (RC4, RC5)

- (a) Dims mismatch: `buildSharedCellPlans` now routes EVERY grouped mask
  that did not take the span path (conversion fallback OR geometry dims ≠
  page dims) through the BOUNDS-RECT-mode partition. The mask bounds are
  scaled into page space (`floor/ceil(left/right·pageW/maskW, top/bottom
  ·pageH/maskH)`, clamped to the page) because the slabs are page-space clip
  rects; when dims match the scale is exactly 1 and the conversion-fallback
  behavior is bit-identical (pinned tests unchanged).
- (b) Beyond-8 members: `MaskTextRegionPlanner.partition` computes its cut
  sequence over ALL stable-ordered members (unchanged for ≤8-member groups)
  and reports each beyond-cap member's bounds-rect slab in the new
  `Cell.overflowSlab` field (`slab` stays `null` — the pure partitioner
  contract is untouched). The integration maps `overflowSlab` to a
  `SharedCellPlan` with a hard slab, `fitRegion = null`, NO component id —
  the member keeps its in-place legacy placement and is
  exemption-protected. Degenerate overflow slabs are reported as null and
  degrade to the old no-cell placement (draws; R2 covers drops).

Outcome: every member of a shared mask group has SOME disjoint hard cell (or
the R1 legacy fallback), so occupancy vetoes between siblings are prevented
everywhere.

## Scope and risks

Deviations from the mandate / rev-2 contracts, all documented:

1. **R2 mechanism choice.** The mandate offered "clip the SELECTED final
   geometry to the free rect (anchor/font as-is) | cellRect for adaptive" and
   delegated the "simplest CORRECT mechanism". Clipping the selected geometry
   to a free strip is pixel-safe but paints NOTHING in every case (the ink
   lies strictly inside the block's own conservative occupancy; the strip is
   strictly outside it), which would reintroduce the missing-text bug it
   repairs. Implemented instead: accept the candidate-8 refit (floor-font fit
   centered in the free rect, `clipRect = freeRect`) — visible text, hard
   pixel containment, clip rect disjoint from accepted occupancies (the
   mandated test contract), attempts ≤ 8. For the fully-enclosed case the
   mandated own-rect containment clip is implemented literally. Adaptive
   blocks on this path accept the legacy refit form (positioned lines are not
   re-generated) — the same legacy-retry convention slice 5 already uses.
2. **"Emission sites = zero".** The two repaired reasons are unemitted, but
   `NonDraw(STATIC_LAYOUT_BUDGET_EXHAUSTED)` remains as the page-wide
   StaticLayout resource guard: the protected `TextLayoutPlannerSlice5Test`
   pins it, and removing it would violate the bounded-memory constraint
   (>512 StaticLayouts ≈ 256 horizontal blocks on one page — unreachable for
   real manga). The no-NonDraw invariant is asserted as a sweep over the
   previously-dropping repro/sweep fixtures in `MissingTextReproTest`.
3. **R3 tie scope** — see the interpretation note above.
4. **Protected-suite updates** (outcomes legitimately changed by the repairs;
   every other protected suite is UNCHANGED and green — `TextLayoutPlannerTest`
   31 legacy cases, `PageLayoutPlanContractTest` remainder, Slice5, FreeText,
   Stroke, `MaskTextRegionPlannerTest`, geometry suites):
   - `PageLayoutPlanContractTest` — `empty shared cell yields explicit non
     draw…` → `…falls back to the legacy region and still draws (R1)`.
     (Listed as protected in the mandate, but its pinned outcome WAS the R1
     drop; the flip is the mandated repair itself.)
   - `TextLayoutPlannerMaskMetadataTest` — `tied overlap … no metadata` →
     `…resolves deterministically to the lower component id with metadata
     (R3)`.
   - `TextLayoutPlannerFinalSafetyTest` — `impossible space … non-draws
     explicitly` → `…falls back to a clipped draw` (attempts stay 8; adds the
     pixel-safety assertion); `concave external mask … conservative bbox` →
     `concave U mask resolves deterministically to the nearest arm` (R3 makes
     the previously tie-null block cell'd on one arm, dissolving the old
     bbox-displacement scenario; 0 attempts, nobody displaced).
5. **Known residual quality (not correctness) risks:**
   - R3 zero-overlap blocks are assigned to the nearest component's cell and
     may be pulled onto the mask (away from a distant OCR box) — mandated
     behavior; legacy was also displaced (104 px measured in the
     investigation).
   - R1/R2 fallbacks can produce small/clipped text (a degenerate-cell
     sliver at floor font) or overlapping text — allowed by the visibility
     override; never a blank region.
   - Equal-center partitions now cut over all members; >8-member groups get
     narrower slabs than before (no pinned test asserted their positions).
   - The `t913` branch may gain commits while this slice landed on
     `64e2b78`; per instructions NO rebase was performed — final integration
     is the coordinator's.

## Verification

Worktree `C:\Users\User\Documents\TachiyomiAT-1.16.8-dev\t912-investigation-wt`,
Git Bash, JAVA_HOME = Android Studio JBR:

1. Mandated focused suite:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   → BUILD SUCCESSFUL in 41s (wall ≈ 42s). `tests=203, failures=0,
   errors=0, skipped=0` across 22 suites (includes the flipped
   `MissingTextReproTest` 11 tests and the new
   `MaskGeometryDeterministicAssignmentTest` 6 tests).
2. Mandated instrumentation compile:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   → BUILD SUCCESSFUL in 26s (exit 0).
3. Extra safety — full module unit suite:
   `./gradlew.bat :app:testDevDebugUnitTest` → BUILD SUCCESSFUL in 1m45s;
   `tests=1313, failures=0, skipped=0`.
