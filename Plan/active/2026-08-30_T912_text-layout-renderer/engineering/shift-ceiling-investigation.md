# T912 investigation — excessive shift and mask-ceiling escape

Branch `codex/text-layout-renderer`, HEAD `b994bc6`. Technical Lead
investigation of the Director report that translated text moves too far and
does not stay below/inside the supplied bubble-segmentation mask ceiling.

No production code or tests were changed. The recommended repair is a small
masked-layout safety slice: cap slice-7 relocation, revalidate shifted geometry
against component row spans with stroke/AA included, and make the production
overlay apply the component path and cell rectangle to every masked layout.

## Superseding addendum — visible containment, not clipping (Director correction)

The Director rejected the apparent result of the first recommendation: moving
the layout less and then hard-clipping away the part that does not fit is not a
meaningful improvement. This addendum **supersedes the fallback recommendation
in “Minimal preferred design” below**. The shift cap and render-time component
clip remain useful guards, but neither is the primary repair.

### Assessment of the current uncommitted implementation

The worktree currently contains an uncommitted implementation of the first
recommendation. It adds the masked shift cap and component-row predicate to
`TextLayoutPlanner`, and prepares component paths in the production overlay.
Those parts are directionally sound, but its masked tail is the rejected
behavior:

- after the ladder fails it returns the resolver-entry layout with
  `clipRect = inkRectOf(layout)`;
- the overlay then intersects that rectangle with the component path and cell;
- the focused JVM test explicitly expects a non-null clip while the Android
  test proves only `insideInk > 0`.

“At least one painted pixel survives” is not a visibility guarantee. A word or
line can be mostly erased and still pass the test. **VERIFIED:** none of the new
tests asserts full text consumption, a fitted contained layout, or a meaningful
retained-ink ratio. The implementation therefore proves leakage prevention but
not the Director's requested visible improvement.

### Smallest concrete planner correction

For a masked span-mode member, use one exact mask-aware **contained reflow** as
the preferred rescue before any directional relocation. The necessary fitter
already exists: `AdaptiveBandPlanner.fitAdaptiveBands` performs bounded font
fit, reflow, three anchor choices (OCR center, component-content center, and a
half-line bias), and exact row-span validation. The production change need not
invent another layout algorithm.

Inside `resolvePostAnchorPlacement`:

1. When `cellPlan.componentId != null`, `cellPlan.spans` is non-empty, and the
   block is masked, call `fitAdaptiveBands` once with the existing cell spans
   and slab, `minFontPx = FIT_MIN_FONT_PX * scale`, and
   `maxFontPx = layout.fontSizePx`. Convert the result with
   `adaptiveBlockLayout`. This intentionally bypasses
   `bandBeatsRectangleFit`: that quality guard decides whether bands are a
   worthwhile normal optimization; here bands are a containment rescue and a
   one-pixel/small-font reduction is preferable to clipped-away text.
2. On the masked branch, replace candidate 1 (which is known to fail whenever
   the resolver is entered because it repeats the same collision predicate;
   see `review/slice-7-verification.md`, NOTE 3) with this contained-reflow
   candidate. This keeps the structural maximum at eight and leaves every
   unmasked candidate and counter unchanged.
3. Run the normal collision predicate on the contained reflow first. If it is
   collision-free, accept it. If it still collides, retain it as
   `bestContainedRescue` while the existing smaller/baseline and **capped,
   component-contained** shift candidates are tried. A small clean nudge may
   still win.
4. If no collision-free candidate succeeds, return `bestContainedRescue`
   **without a new `clipRect` and without rejecting it for collision**. The
   never-drop contract already permits overlapping text. This ordering makes
   the trade explicit: contained and legible text in its own bubble is more
   important than moving it far away or cutting it off merely to satisfy the
   conservative inter-text occupancy rule.
5. Only when the exact contained fitter returns null may the resolver fall back
   to the unshifted entry layout under the component/cell Canvas clip. That is
   the true last-resort safety path. Do not return a rejected free-strip refit,
   and do not add `clipRect = inkRectOf(layout)` on the masked tail; the latter
   can additionally shear stroke/AA without improving mask fit.

This is the smallest useful change because it reuses the already budgeted
shape-aware fitter, replaces a provably wasted masked candidate slot, and
changes only the collision failure preference. Recognition, partitioning,
normal band acceptance, font harmony, line breaking, and unmasked behavior stay
untouched.

### Containment predicate correction

Keep the uncommitted stroke-aware row-span check for shifted legacy candidates,
but do not double-count a positioned line's shaping guard. The current helper
inflates the entire `layoutWidthPx` even though that width already includes
`2 * SHAPING_GUARD`. For positioned rescue validation, derive the paint envelope
from the measured line advance and line height, then inflate by
`ceil(actualStrokeWidth / 2 + AA_GUARD)`; or expose the successful
`AdaptiveBandPlanner` containment result as the proof. The hard component clip
remains the exact pixel backstop for glyph overhang that JVM metrics cannot
model.

### Required evidence replacing the current clipping assertions

The implementation tests should demonstrate visible fit, not merely safe
clipping:

1. Change the far-refit JVM fixture so the masked result has
   `positionedLines != null`, reconstructs the complete chosen text from those
   lines, stays at/below the entry font, remains inside the component spans, and
   has no newly introduced `clipRect`. It must still be a Draw even if its
   conservative occupancy overlaps the higher-priority block.
2. Add a normal-quality-guard contrast: the same shape without a collision may
   remain legacy because bands do not beat the rectangle; with a ceiling/collision
   rescue it may use the contained band fit. This pins the deliberate, narrow
   bypass rather than globally weakening `bandBeatsRectangleFit`.
3. Keep the sloped-ceiling and hole tests, but drive them through `planPage`, not
   only internal geometry seams. Assert complete text reconstruction and exact
   row-span containment after planning.
4. Replace the Android `insideInk > 0` assertion with a planned contained-layout
   fixture whose rendering with and without the component path differs only by
   a small AA fringe (initial acceptance target: at least 95% retained alpha,
   subject to device calibration). Also assert every outside-mask pixel is zero.
   A separate deliberately impossible fixture may prove the last-resort clip,
   but must not be presented as the ordinary success case.
5. Preserve the existing no-drop/cardinality sweep and all 31 unedited unmasked
   legacy cases. Add byte-equality for an unmasked colliding page to prove the
   masked rescue branch cannot change its decisions.

### Revised recommendation

**Prefer contained reflow/shrink over collision separation.** Keep the masked
shift cap and exact component/cell Canvas clip, but use the existing adaptive
band fitter to produce a fully consumed, stroke-contained layout first. When
collision-free placement is impossible, accept that contained layout with
overlap; component clipping is only a final pixel guard and should remove no
material portion of the ordinary successful result.

## Verdict

The report is explained by three cooperating defects:

1. **VERIFIED — slice-7 relocation is unbounded.**
   `minShiftDisplacement` may accumulate enough displacement to pass every
   previously placed occupancy (`TextLayoutPlanner.kt:1212-1255`). Candidates
   4–7 clamp only to the page or the complete rectangular hard-cell slab
   (`:1304-1315,1432-1452`); there is no distance budget relative to the OCR
   block or to the layout that entered the resolver. The hard clip/refit path
   can relocate again to the center of the selected free strip
   (`:1542-1560,1624-1637`). Worse, after all eight candidates fail, a non-null
   free-strip refit is accepted unconditionally (`:1563-1579`), so candidate 8
   can still win even when validation rejected it. Existing focused evidence
   demonstrates the scale of this behavior: a 53.68 px shift and a 378.08 px
   shift on 1000 px fixtures (`TextLayoutPlannerFinalSafetyTest.kt:90-173`),
   while the earlier missing-text investigation measured 65–160 px exile on
   shared masks (`engineering/missing-text-investigation.md`, RC3/§4).

2. **VERIFIED — shifted adaptive layouts lose their component-span proof.**
   `AdaptiveBandPlanner` initially validates every planned line across every
   covered mask row (`AdaptiveBandPlanner.kt:267-309`). `translateLayout` then
   translates those already-validated line rectangles (`TextLayoutPlanner.kt:
   1176-1197`), but the slice-7 `validates` predicate checks only the page, the
   rectangular `hardCell`, budgets, and occupancy collision
   (`TextLayoutPlanner.kt:1328-1342`). It does not re-check the translated lines
   against `SharedCellPlan.spans`. A line can therefore move above a sloped
   component ceiling or into a hole while remaining inside the component's
   bounding slab. This exact gap was already recorded independently as NOTE 1
   in `review/slice-7-verification.md`; the present production report confirms
   that it is no longer only a theoretical quality risk.

3. **VERIFIED — the production overlay does not enforce the full hard clip.**
   `TranslationOverlayView` is the production planner consumer. Its positioned
   branch clips only `cellRect` then `clipRect`
   (`TranslationOverlayView.kt:180-213`) and explicitly has no component path.
   Its legacy horizontal/vertical branch clips only `clipRect`
   (`:135-177`): it ignores even `cellRect`. This matters frequently after the
   quality repair because adaptive layouts that do not beat the rectangle are
   intentionally returned as `positionedLines == null` while still carrying
   mask/component/cell metadata (`engineering/quality-repair.md`, Fix 2 and
   updated tests 1/4). Thus a shifted masked legacy layout is painted with no
   mask or cell ceiling in the actual reader. `PageTextRenderer` has the correct
   composition — component path, then cell rect, then legacy clip for all three
   layout forms (`PageTextRenderer.kt:68-84`) — but has no production caller.

There is a fourth containment weakness:

- **VERIFIED — planner containment is not stroke-inclusive.** `inkRectOf`
  returns positioned layout rectangles or an uninflated legacy extent
  (`TextLayoutPlanner.kt:1121-1145`). `validates` compares that box to the page
  and slab (`:1328-1340`). The outline and AA samples are added only to the
  collision occupancy (`:1109-1118`), not to containment. A candidate whose
  fill box touches the slab/component ceiling can therefore have its stroke
  cross it. Canvas component clipping would make the final pixels safe, but the
  production overlay currently lacks that clip, and accepting a candidate that
  will be visibly sheared is also a poor placement decision.

The screenshot is consistent with a mask-backed block taking the legacy
rectangle form and then a slice-7 relocation: the planner sees a large
rectangular slab, while the production painter sees neither the component
contour nor (on the legacy branch) the slab. Exact attribution to one block's
candidate number is **UNKNOWN** because the screenshot contains no serialized
`TranslationBlock`, mask RLE, or plan trace.

## Minimal preferred design

### 1. Cap only post-anchor relocation on masked blocks

Record the `layout.originX/Y` and `inkRectOf(layout)` on entry to
`resolvePostAnchorPlacement`; this is the slice-7 reference geometry. Do not
measure from a subsequently shifted candidate, so several operations cannot
ratchet the text farther away.

Add isolated tunables under `TextLayoutTuning` and compute one masked-layout
budget from the OCR rectangle and page short side:

```text
shiftCap = min(
    MAX_MASK_SHIFT_REGION_FRACTION * min(block.width, block.height),
    MAX_MASK_SHIFT_PAGE_FRACTION * min(pageWidth, pageHeight),
)
```

Initial values of `0.25` and `0.04` respectively are an **ASSUMPTION** for a
focused/device corpus, not a verified final tuning. The contract, rather than
those preliminary numbers, is important: a slice-7 result may not move farther
than `shiftCap` from the geometry that entered the resolver.

- For candidates 4–7, skip a direction when its required displacement exceeds
  the cap. Do not clamp to a partial move merely to consume an attempt; it is
  already known not to clear the obstacle.
- For candidate 8, reject a refit's anchor when its distance from the reference
  anchor exceeds the same cap. The tail must not return that rejected
  `clipCandidate` merely because it is non-null.
- If every capped candidate fails, return the original selected layout, clipped
  by its component/cell at paint time. Overlap or clipping remains the permitted
  worst case; never emit a placement `NonDraw`.
- Gate the new relocation limit on `block.segmentationMask != null`. The
  unmasked path therefore retains its current decisions byte-for-byte, including
  the existing slice-7 behavior for colliding unmasked blocks. The common
  non-colliding path is untouched in either case.

This is preferable to removing shift candidates: small local nudges remain
available, but a collision cannot exile a translation to another lobe/panel.

### 2. Make masked candidate acceptance component- and stroke-aware

For a span-mode cell (`cellPlan.componentId != null` and non-empty
`cellPlan.spans`), add a pure allocation-free predicate used by `validates`:

1. Build conservative paint envelopes at the candidate's final coordinates.
   For positioned layouts, use one envelope per `PositionedLine`; for legacy
   horizontal/vertical layouts, use `extentOf` as one envelope.
2. Inflate each envelope by at least
   `ceil(strokeWidth / 2 + TextLayoutTuning.aaGuard(scale))`. Collision gap is
   deliberately excluded: it is inter-text spacing, not painted ink.
3. Require every integer row of every inflated envelope to be continuously
   covered by one of the already slab-intersected row spans. This checks the
   real upper/lower contour and holes, not only the component bounding box.
4. Retain the existing page/slab and occupancy checks.

The span list is already row-major and page-bounded (`SharedCellPlan.spans`,
`TextLayoutPlanner.kt:2669-2691`). Use a two-pointer scan or bounded row offsets;
do not build a dense mask, bitmap, coordinate key, or per-candidate `Map`.
Candidate count remains eight. If the predicate rejects every candidate, use
the clipped original Draw fallback above. Bounds-mode/capped cells without
component ids retain rectangular slab containment only because no exact
component geometry is available.

This validation prevents choosing a shifted layout that will be cut at the
mask ceiling. It is still not the final pixel proof because JVM measurements
cannot model every glyph overhang; the renderer clip below provides that proof.

### 3. Give the production overlay hard-clip parity

Prepare bounded Android `Path`s from each referenced component during
`TranslationOverlayView.bind`, using the same `(planGeometryId, componentId)`
cache and 64-component/100,000-span limits as `PageTextRenderer`. At draw time,
wrap every layout — positioned, legacy horizontal, and legacy vertical — in one
common clip composition:

```text
component Path -> cellRect -> clipRect -> draw existing body
```

Do not replace the legacy drawing body with `PageTextRenderer`; that risks
changing shaping/alignment. Only centralize the save/clip/restore envelope.
Build paths in `bind`, never in `onDraw`; use float `clipRect` overloads to avoid
new per-frame `RectF` objects. Invalid/missing component metadata must degrade to
the available `cellRect`/`clipRect` and still draw, preserving the never-drop
contract rather than copying `PageTextRenderer.bind`'s `mapNotNull` failure
behavior.

This makes the final stroke-inclusive guarantee exact: the Canvas clips the
actual stroke/fill samples to the component, including a curved upper ceiling,
then to the member's disjoint cell. It also closes the current legacy-branch
`cellRect` omission.

## Why this is the minimum safe repair

- No recognition, OCR identity, RLE storage, mask partition, font fitting,
  line breaking, font harmony, or persisted direction changes.
- No 2D partition or neck inference is needed.
- No new full-page allocation. Prepared component paths are bounded by the
  existing page component/span budgets; planner validation streams existing
  cell spans over at most eight candidates.
- Every nonblank identity still draws. A failed cap or containment trial falls
  back to clipped/overlapping text, never to `EMPTY_SHARED_CELL` or
  `NO_DISJOINT_POST_ANCHOR_PLACEMENT`.
- Unmasked layout and rendering decisions remain unchanged because the cap,
  span check, and component path are all conditional on mask metadata.

The known vertical-partition filter defect
(`MaskTextRegionPlanner.kt:230-262` compares `span.y` with X bounds before its
vertical branch) is real but separate: it can remove cell spans before fitting
on tall/narrow vertical partitions. Fix it in the same implementation series
only as an independently tested mechanical precondition; it is not the cause of
an already-created layout escaping its upper ceiling.

## Required focused evidence for implementation

1. **Masked shift cap (JVM):** reproduce a 50+ px and a 300+ px required move;
   assert the final masked origin stays within the cap, outcome is Draw, chosen
   text/identity is unchanged, and attempt count stays bounded/deterministic.
2. **Candidate-8 cap (JVM):** make the only free strip lie beyond the cap;
   assert the rejected refit is not returned by the tail and the original
   clipped Draw is retained.
3. **Sloped upper ceiling (JVM):** force an upward shift whose uninflated box
   fits the slab but whose stroke envelope crosses the component's row-span
   ceiling; assert that candidate is rejected. Add the corresponding accepted
   just-inside boundary case.
4. **Hole/concavity (JVM):** force an adaptive shift into a component hole;
   assert span revalidation rejects it (closing slice-7 review NOTE 1).
5. **Never-drop/cardinality (JVM):** impossible capped space still yields one
   Draw per nonblank input; `STATIC_LAYOUT_BUDGET_EXHAUSTED` remains the only
   live resource guard.
6. **Production overlay pixels (Android):** for both `positionedLines != null`
   and `positionedLines == null`, draw thick-stroked text across a sloped
   component ceiling and a cell edge; assert zero alpha outside
   `component path ∩ cellRect ∩ clipRect` and nonzero alpha inside. The legacy
   fixture is essential because it is the currently un-clipped production path.
7. **Compatibility:** keep all 31 `TextLayoutPlannerTest` cases unedited and
   green; add explicit byte-equality for masked non-colliding plans and unmasked
   colliding plans; rerun the rendering/segmentation focused suite,
   instrumentation, and full module gate.

## Claim classification summary

- “Post-anchor shifts have no distance cap” — **VERIFIED** by production code
  and existing 53.68/378.08 px fixtures.
- “A translated adaptive candidate is not revalidated against component spans”
  — **VERIFIED** by code and the accepted slice-7 review NOTE 1.
- “The production legacy branch ignores `cellRect`; all production branches
  ignore the component path” — **VERIFIED** by `TranslationOverlayView`.
- “Current containment excludes stroke/AA” — **VERIFIED** by `inkRectOf` and
  `validates` versus `conservativeOccupancyOf`.
- “The screenshot used candidate 4–8 on the legacy masked branch” — **STRONG
  INFERENCE**, not provable without the page's block/mask/plan trace.
- “0.25 region / 0.04 page are the final tuning values” — **ASSUMPTION**;
  device-corpus evidence must confirm or adjust them without changing the
  contract.
