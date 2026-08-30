# T912 slice 3 — disjoint shared cells and independent fonts

## Result

Implemented slice 3 of architecture revision 2 on `codex/text-layout-renderer`
(base `6c18f3e`), one commit, no rebase/reset.

- **New pure `MaskTextRegionPlanner.kt`** (rendering package, zero
  `android` imports, JVM-testable): partitions ONE
  `(planGeometryId, componentId)` group's members into disjoint half-open
  cells. Integer slab math + span intersection only. Implements the
  architecture "Slice 3" algorithm exactly: rounded/clamped scan centers
  (`floor(v + 0.5)`); horizontal axis when X spread >= Y spread; stable sort
  (axis center, orthogonal center, input index); floor-midpoint cuts with
  parent bias ONLY for valid, facing, non-overlapping parent pairs (biased to
  the parent-edge midpoint, clamped to the closed interval between centers);
  strictly monotonic cuts with the deterministic equal-width slab fallback
  (`[left + (k*W)/n, left + ((k+1)*W)/n)` in stable order); half-open slabs
  with the `gapBefore/gapAfter` dead zone; span intersection split at every
  slab boundary (a row span crossing two cuts yields up to three segments,
  never assigned whole); first-pass segment counting charged to a page-wide
  `CellSpanBudget` (default `MAX_DERIVED_CELL_SPANS_PER_PAGE = 100_000`,
  caller-tunable) BEFORE any cell list is allocated, with per-group fallback
  to bounds-rect mode cells (same slabs, no span lists, budget not charged);
  `MAX_SHARED_BLOCKS_OPTIMIZED = 8` per component (beyond-cap members get no
  cell at all); fit region = bbox of the cell's spans (span mode) or the slab
  (bounds mode) with the explicit empty-cell probes (component ∩ slab ∩ own
  OCR, then block-center-nearest continuous interval on the cut axis, then
  `empty` flag); one `Cell` entry per member in INPUT order.
  Also exposes `internal fun collisionGapPx(pageShortSide, scale)` =
  `ceil(clamp(0.001 * pageShortSide, 2*scale, 4*scale))` (COLLISION_GAP).
- **`TextLayoutPlanner.kt` integration**: added the architecture's contract
  types verbatim (`InputIdentity`, full `NonDrawReason` enum, `LayoutOutcome`,
  `LayoutResult`, `PageLayoutPlan`). New `planPage(...)` is the real
  implementation; `plan(...)` is a thin wrapper returning
  `planPage(...).drawableInRenderOrder`, so `TranslationOverlayView` and every
  existing caller/test stay untouched. After `SharedMaskSession` conversion
  (slice 2, unchanged), `buildSharedCellPlans` partitions per (group,
  componentId) of assigned nonblank members: converted geometry with
  page-matching dims → span-mode cells (single-member component groups =
  one cell over the component bounds, content-bounds fit region, no OCR
  probe); grouped conversion-fallback masks (empty runs / caps) with
  page-matching dims → BOUNDS-RECT mode cells over the mask bounds; groupless
  masks (beyond 128 refs / 32 unique) and dim mismatches keep the slice-2
  legacy region path. Placement loop otherwise unchanged (`placeBlock`,
  obstacles, growth, clips); `regionOverride` = the cell's fitRegion for
  optimized members, null for beyond-8 members, legacy region otherwise. A
  cell that owns no component pixels yields `NonDraw(EMPTY_SHARED_CELL)`
  (not placed, not an obstacle); the legacy silent `continue` for
  `safeW < 1f || safeH < 1f` became `NonDraw(INVALID_OR_SUBPIXEL_SOURCE_RECT)`
  (drawable output unchanged). Metadata: `cellRect` is now the hard disjoint
  SLAB; span-mode members keep `(planGeometryId, maskComponentId)` + geometry
  under the unchanged 64-distinct-pair page cap; bounds-rect members get the
  slab WITHOUT geometry ids.
- **`equalizeSharedMaskFonts()` removed entirely** — font fitting is per
  result; each shared member keeps its independently fitted font/lines
  (verified no other caller existed).
- `PageTextRenderer.kt`, `MaskGeometry.kt`, `BubbleMaskRle.kt`: NO changes
  (git verified). The renderer already clips `component → cellRect → legacy
  clipRect` and keeps `draw()` allocation-free.
- Slice 4 stays formally deferred: NO neck inference anywhere; joined lobes
  use the deterministic midpoint/parent-biased cells — this slice IS that
  fallback.

## Verification

From repo root, Git Bash on Windows:

1. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   — **BUILD SUCCESSFUL in 1m 29s** (two earlier invocations failed at
   compile — a Kotlin `Triple` inference error in the new planner and a
   missing `kotlin.math.max` import in the updated test — both fixed; total
   wall ≈ 7 min across three invocations). JUnit XML aggregate:
   **123 tests, 0 failures, 0 errors, 0 skipped** across 15 suites:
   - NEW MaskTextRegionPlannerTest: 12/12 (span crossing two cuts → 3 exact
     segments; pairwise pixel disjointness per row; exact half-gap dead zone
     at every column; overlapping parents keep the center midpoint; facing
     parents bias + clamp; equal centers → 33/33/34 equal-width slabs;
     diagonal spread → horizontal; different components never share cuts;
     explicit empty cell; 9 members → first 8 optimized, 9th flagged;
     budget exceeded → bounds-mode cells with zero budget charge;
     collisionGapPx clamping both bounds + scaling)
   - NEW PageLayoutPlanContractTest: 6/6 (identity multiset in input order;
     planningOrdinal = score-desc/input rank, renderOrdinal consecutive over
     Draw only, NonDraw null; blank absence; empty shared cell →
     `NonDraw(EMPTY_SHARED_CELL)` with siblings Draw; sub-pixel behavior pin;
     wrapper equivalence vs `plan()` field-by-field)
   - TextLayoutPlannerMaskMetadataTest: 8/8 (expectations updated where
     slice 3 legitimately changes them — see below)
   - MaskGeometryOrderedRleTest: 9/9 (8 existing + 1 NEW pinning row-major
     first-appearance ids for 3 components, including the case where the
     legacy key-STRING sort would order them differently — closes slice-2
     review NOTE 6)
   - Existing, UNCHANGED and green: TextLayoutPlannerTest 31 (no edits),
     TextLayoutPlannerStrokeTest 4, PageTextRendererDirectionTest 8,
     ComponentClipCacheTest 6, MaskGeometryTest 7, MaskGeometryStressTest 2,
     BubbleSegmentationDecoderTest 8, RenderColorEstimator 22 (7+5+7+3).
2. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   — **BUILD SUCCESSFUL in 54s**.
3. `git diff --check` — clean. `git status` shows exactly the allowed files.

Known-fixture pre-verifications are now PINNED by tests:
- 3-block 300×120 full-rect fixture: cuts 100/200, cells
  `[0,99) [101,199) [201,300)`, dead columns {99,100} and {199,200}, shared
  geometry, per-block text unchanged, fonts independently fitted (each equals
  its own `binarySearchFontSize` in its own cell; longest text strictly
  smaller font than shortest sibling).
- Conjoined-cloud fixture (empty runs → bounds mode): parent-biased cut 400,
  slabs `[100,399) × [100,450)` / `[401,700) × [100,450)`, both fonts >= 16,
  both origins inside their parent boxes.

No device/emulator was available; instrumented pixel verification remains for
the slice-8 gate (unchanged from slices 1–2).

## Scope and risks

Touched exactly the allowed files: NEW `MaskTextRegionPlanner.kt`,
`TextLayoutPlanner.kt`, three test files (one new ×2, one strengthened), this
report. `PageTextRenderer.kt` / `MaskGeometry.kt` / `BubbleMaskRle.kt`
untouched; no TranslationOverlayView/batch/download/drawer/progress/pipeline
files touched; no positioned lines / adaptive bands / free-text widening /
final-safety retries / neck inference.

Ordinary unmasked manga: byte-identical decisions — unmasked blocks never get
cell plans (region path identical), `equalizeSharedMaskFonts` was a no-op for
them, and the existing 31 TextLayoutPlannerTest cases pass with NO edits.

### TextLayoutPlannerMaskMetadataTest expectation changes (legitimate slice-3 effects)

1. `empty-runs shared mask` (was "produces no metadata"): NOW gets disjoint
   bounds-rect cells — `cellRect` = `[100,399)×[100,450)` / `[401,700)×[100,450)`,
   still no geometry ids; cloud-font/origin assertions kept green. Reason: the
   architecture's failure matrix routes conversion-fallback shared masks
   through the same disjoint cut logic on the bounds rectangle.
2. `mask with more than 64 components`: `cellRect` is now the mask bounds
   rect (single-member fallback cell), still no geometry ids.
3. 3-block fixture: added exact slab/dead-zone values and per-result font
   freedom assertions (replacing the equalization-era silence).
Everything else (tie/no-metadata, groupless cap, 129-reference cardinality,
single-block mask) passes unchanged.

### Intentional deviations / local decisions (all recorded here)

1. **`withMaskMetadata` removed, not just bypassed.** After slice 3 it is
   provably dead: every block that could pass its guards (grouped, converted,
   page-dim geometry, unambiguous assignment) now receives a cell plan and is
   wired via `withSharedCellMetadata`; every remaining legacy path
   (groupless, tied, dim mismatch, fallback geometry) returned the layout
   unchanged from it anyway. The 64-distinct-pair cap and clamped-OCR
   component resolution were carried over verbatim.
2. **Equal-width fallback slabs carry no additional dead zone.** The task
   text gives the explicit fallback slab formula
   `[left + (k*W)/n, left + ((k+1)*W)/n)`; applying `gapBefore/After` on top
   would contradict it (and can empty small slabs). Dead zone remains a
   property of the midpoint/parent-biased cut path; the fallback slabs are
   still half-open and pairwise disjoint, satisfying the disjointness
   invariant. (Architecture says "equal-width cuts"; the task's slab formula
   is the more specific implementer contract.)
3. **Empty-cell probes are slab-bounded.** Architecture probe 1 is literally
   `component ∩ assigned slab ∩ own OCR`; the task's "spans ∩ own OCR" is
   implemented with that restriction (an unrestricted reading could hand a
   block pixels inside a sibling's slab, violating "never another block's
   cell"). Consequence: `EMPTY_SHARED_CELL` occurs exactly when a slab owns
   zero component pixels; when the cell's pixels don't overlap the OCR rect,
   probe 2 (nearest continuous cut-axis interval) supplies the fit region.
4. **INVALID_OR_SUBPIXEL_SOURCE_RECT is a formal mapping of a dead legacy
   branch.** `computeRects` clamps `safeW/safeH` to `max(1f, ...)`, so the
   predicate `rect.safeW < 1f || rect.safeH < 1f` can never fire through the
   public API (pre-existing; changing the clamp would break byte-identical
   compatibility). The mandated "subpixel → NonDraw" contract test is therefore
   unachievable via `planPage`; the test instead pins the reachable behavior
   (0.5×0.5 source still draws with `safeW = safeH = 1`) and the branch maps
   to the explicit NonDraw for direct-rect future callers. Flagged for the
   reviewer in case slice 7 wants a reachable definition.
5. **Empty-cell contract fixture uses a degenerate middle slab** (members at
   centers 100/101/102 → cuts 100/101, gap 2 → slab `[101,100)`), because the
   task's sketch ("4 members, two-island component, equal centers") cannot
   produce an empty slab: equal centers only occur within one
   component group, a 4-connected component spanning two lobes puts pixels in
   every full-ortho slab, and two islands are two components that partition
   independently. The pure-planner test DOES realize the two-island span-list
   sketch at planner level. Outcome matches the mandate: one
   `NonDraw(EMPTY_SHARED_CELL)`, siblings still Draw.
6. **Degenerate fallback-bounds single group** (mask bounds with
   `right == left`) now yields an empty cell → `NonDraw` instead of slice 2's
   draw-into-a-zero-width-region. Extreme edge case; the renderer's zero-width
   cellRect clip drew nothing visible anyway.
7. **Cell-span budget fallback keeps `(planGeometryId, componentId)` ids**
   (only the cell span LISTS are dropped): the renderer's component clip path
   is built from the geometry, not from cell spans, so structural clipping is
   unaffected. A failed group never charges the budget; consumption order is
   deterministic (groups in first-appearance order, components by id).

### Remaining risks for later slices

- FitRegion (bbox of cell spans) can be tighter than the old legacy region
  for single-block masks whose component is smaller than the mask bounds;
  sanctioned by the task ("fitRegion = content bounds"), placement anchors
  shift accordingly. Watch real-page behavior at the slice-8 gate.
- Beyond-8 members place with `regionOverride = null` (own box/parent) and
  can collide with cell members; collision handling stays the pre-existing
  growth/clip net until slices 5–7.
- The 64-pair cap now leaves rare members with a slab `cellRect` but no
  component path (slab-clip only) — fail-safe, never wrong content.
