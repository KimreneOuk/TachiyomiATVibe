# T912 investigation — missing text on conjoined-bubble masks

Branch `codex/text-layout-renderer`, HEAD `40edf1a`. Technical Lead
investigation of the Director-reported production regression:

> "Some text are missing from some regions on the same pages. Some regions
> have text, some do not. It is more common with conjoined text bubbles in
> the same bubble segmentation mask. The legacy translated image had ALL text
> rendered; with the new renderer most text renders beautifully but now some
> are missing."

No production code was changed. Evidence: committed documentation tests in
`app/src/test/java/eu/kanade/translation/rendering/MissingTextReproTest.kt`
(all PASS at HEAD — they pin the bug, not the desired behavior).

## 1. Verdict (ranked root causes)

| # | Root cause | New in | Manifestation | Realism on conjoined masks |
|---|---|---|---|---|
| RC1 | **Cell-less blocks + slice-7 inflated-occupancy veto.** A block whose OCR box ties (or zero-overlaps) `componentForRectangle` gets NO shared cell; placed with the whole-bubble/midpoint legacy region, its stroke+AA+half-gap inflated occupancy blankets the cell'd siblings' slabs. When it scores highest, BOTH lobe blocks exhaust the 8-candidate ladder → `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)` → blank regions. When it scores mid, the ladder instead exiles blocks 65–160 px out of their regions. | slice 7 (needs slice 2's null assignment) | blank regions (drop) or text far outside its region | **HIGH — the reported symptom.** Reproduced deterministically (committed). Needs 2+ mask components: exactly what thin/diagonal necks produce (`MaskGeometry` unions only adjacent-row-overlapping spans; a 1 px stepping bridge shatters into singleton components). |
| RC2 | **`EMPTY_SHARED_CELL` from degenerate slabs.** Adjacent scan centers within the dead-zone gap (2 px at scale 1, up to 4·scale on large pages) make two cuts closer than the gap; the middle slab inverts (`start >= endExclusive`) and the block is dropped. Fires in span mode AND bounds-rect mode. | slice 3 | blank region | MEDIUM — needs near-equal (≤ ~2·gap) or edge-clamped scan centers; conjoined masks carry more members per component, so more adjacent cut pairs. Reproduced deterministically (committed). |
| RC3 | **Ladder displacement.** Every successful but non-first ladder candidate relocates text; observed 65–160 px from the block's OCR center. Perceived as "missing from its region" even when drawn. | slice 7 | misplaced text | HIGH (same mechanism as RC1; committed repro). |
| RC4 | **Mask/page dims mismatch disables ALL cells** (`geometry.width != roundToInt(pageWidth)` → no SharedCellPlan for the group → legacy midpoint regions + slice-7 collisions). Observed side effect: fonts shrink 36→27 px (25 % smaller). | slices 3+7 interaction | page-wide quality loss; drops only in edge-hugging cases | LOW as a production trigger: masks are reconstructed at the recognition bitmap dims and the overlay binds `translation.imgWidth` of the same bitmap (`BubbleSegmentationDecoder.kt:145`; `ReaderTranslationOverlayBinding.kt:25-28`). Keep as defensive-depth fix. |
| RC5 | **Beyond-8 members** (`MAX_SHARED_BLOCKS_OPTIMIZED`) → 9th+ block on one component is cell-less inside a bubble packed by 8 siblings' occupancies → displaced (32–42 px observed); NonDraw only when page-edge-blocked. | slice 3 | misplaced/missing | LOW-MEDIUM (needs 9+ text blocks in ONE component). |
| — | H5 (fitRegion tightening), STATIC_LAYOUT_BUDGET_EXHAUSTED, INVALID_OR_SUBPIXEL_SOURCE_RECT, MASK_OR_WORK_BUDGET_EXHAUSTED, POSITIONED_LINE_BUDGET_EXHAUSTED, INVALID_RENDER_METADATA | — | — | **Not confirmed / unreachable** — see §3. |

Key structural fact: the legacy planner at the T912 base `ece0e72` contains
**zero** NonDraw paths (verified: `git show ece0e72:...TextLayoutPlanner.kt |
grep -c NonDraw` → 0). Every dropped block is a T912 regression vector, and
because the inpaint mask already erased the original text, every NonDraw is a
visible blank region in the reader.

## 2. Render-path confirmation

VERIFIED:

- `TranslationOverlayView.kt:70` is the ONLY production consumer of
  `TextLayoutPlanner.plan(...)`; it draws exactly
  `planPage(...).drawableInRenderOrder` (`TextLayoutPlanner.kt:500-508`).
  `PageTextRenderer` has no production caller (grep over `app/src/main`).
- A `NonDraw` outcome therefore means the block is absent from the screen
  while the cleaned bitmap has its original text inpainted away.
- Note (separate integration gap, NOT the missing-text cause): the overlay's
  `drawLayout` renders only `lines` + `clipRect`
  (`TranslationOverlayView.kt:135-170`); it does not consume
  `positionedLines` or `hardClip`. Adaptive layouts are drawn legacy-style and
  unclipped in production. Flagged for the Director; out of scope here.

## 3. Exhaustive drop-path enumeration (production `planPage`)

`NonDrawReason` declares 7 reasons (TextLayoutPlanner.kt:177-185). Only 4 are
ever emitted:

1. **`EMPTY_SHARED_CELL`** — TextLayoutPlanner.kt:595-605, when
   `cellPlan.empty`. Produced by `MaskTextRegionPlanner.partition`:
   - span mode: slab owns no component pixels — MaskTextRegionPlanner.kt:274-279
     (reachable only via a degenerate/inverted slab, see RC2; a connected
     component crosses every vertical/horizontal strip of its bounds, so
     non-degenerate slabs always own pixels);
   - bounds-rect mode: degenerate slab — MaskTextRegionPlanner.kt:260-271.
2. **`INVALID_OR_SUBPIXEL_SOURCE_RECT`** — TextLayoutPlanner.kt:639-649.
   DEAD CODE: `rect` always comes from `computeRects` (or the slice-6 baseline,
   also `computeRects`), whose `safeW/safeH` are `max(1f, ...)`
   (TextLayoutPlanner.kt:2770-2771) — the condition can never fire.
3. **`STATIC_LAYOUT_BUDGET_EXHAUSTED`** — TextLayoutPlanner.kt:696-705.
   Requires >512 reserved StaticLayouts (≈256 horizontal blocks) on one page —
   unreachable for real manga pages.
4. **`NO_DISJOINT_POST_ANCHOR_PLACEMENT`** — TextLayoutPlanner.kt:760-772,
   after `resolvePostAnchorPlacement`'s bounded 8-candidate ladder
   (TextLayoutPlanner.kt:1051-1334) fails.

Declared but never emitted by the planner: `MASK_OR_WORK_BUDGET_EXHAUSTED`,
`POSITIONED_LINE_BUDGET_EXHAUSTED`, `INVALID_RENDER_METADATA` (slice-5 budget
overflow retries the legacy layout instead, TextLayoutPlanner.kt:681-692; the
metadata fail-closed exists only in `PageTextRenderer`, which is not in the
production path). The only other renderer-visible exclusion is blank
`chosenText` (pre-existing, intended).

### What makes a block CELL-LESS (no `hardCell`, no `cellRect`)

`hardCell` is null when `cellPlan` is null or not optimized
(TextLayoutPlanner.kt:729); `cellRect` then stays null. Paths:

- **tie / zero overlap** in `MaskGeometry.componentForRectangle`
  (MaskGeometry.kt:55-82): returns null on an exact integer tie of two
  components' overlap counts or when the OCR box overlaps no component pixel →
  `resolveComponentId` null → no SharedCellPlan (TextLayoutPlanner.kt:2481-2484).
  VERIFIED: for the committed symmetric straddler rect the per-component
  overlaps are exactly [384, 384] → null.
- **dims mismatch**: `geometry.width/height != roundToInt(pageWidth/Height)`
  skips the span branch AND the bounds branch requires `geometry == null`
  (TextLayoutPlanner.kt:2479, 2513) → whole group cell-less.
- **groupless masks**: >128 references / >32 unique masks / RLE-int budget
  (SharedMaskSession, TextLayoutPlanner.kt:284-300).
- **beyond-8 members** of one component get `optimized = false`
  (MaskTextRegionPlanner.kt:172-179) and, unlike other members, no region
  override at all (TextLayoutPlanner.kt:607-611).

Because a cell-less block has no hard cell, the slice-7 disjointness
exemption (`hardCellsDisjoint`, TextLayoutPlanner.kt:938-952) never applies
against it: every collision is judged on inflated occupancies
(stroke + AA + gap/2 per side; legacy extents at
TextLayoutPlanner.kt:888-897, adaptive line envelopes at
AdaptiveBandPlanner.kt:283-306). Two text blocks that merely approach each
other therefore "collide" even when their pixels are far apart, and the
ladder — which constrains cell'd candidates INSIDE their own slab
(TextLayoutPlanner.kt:1103-1110) — can be unsatisfiable when the cell-less
block's envelope blankets that slab. That is the drop mechanism of RC1.

Hypothesis checks (Director's list):

- H1 — CONFIRMED as RC2, with the narrowing that equal-width fallback slabs
  canNOT be empty for a connected component (every strip owns pixels); only
  inverted/degenerate slabs (cuts within the dead-zone gap, or a scan center
  clamped to the component edge) produce empty cells.
- H2 — CONFIRMED (tie and zero-overlap variants), with dims-mismatch downgraded
  to RC4 (production dims match by construction) and beyond-8 downgraded to RC5.
  The "8-candidate ladder plausibly fails" question: YES — when the cell-less
  occupant's envelope covers the sibling's slab, free-rect candidate 8 is cut
  to nothing (all four strips invalidated), so the ladder costs 7 attempts and
  fails (observed `attempts=14` for two dead siblings).
- H3 — CONFIRMED by code: bounds-mode members DO get `cellRect = slab`
  (TextLayoutPlanner.kt:2527-2535, 2552-2561), so bounds-mode siblings keep the
  exemption; tie/zero-overlap members get NO cellRect; beyond-8 members get
  none either (not optimized → metadata untouched, TextLayoutPlanner.kt:2559).
- H4 — PARTIALLY: mixed adaptive/legacy sibling pairs collide exactly like any
  cell-less pair (the exemption only needs BOTH cellRects non-null and
  disjoint). Adaptive envelopes DO exceed the slab by stroke+AA+gap/2 per side,
  so adjacent-cell pairs WITHOUT the exemption always "collide".
- H5 — NOT CONFIRMED: for single-member components the cell slab IS the
  component bounds and the fit region equals the component content bbox; no
  drop from fit tightening was observed in any fixture.
- H6 — enumerated exhaustively above; nothing else removes blocks.

## 4. Reproduction matrix (deterministic JVM, FakeMeasurer 0.6 px/char)

Fixture family: two ellipse lobes (100,150,r 85×80) and (285,150,r 85×80) on
400×300; neck variants thick (2 px stepping bridge → 1 component), thin
(1 px stepping bridge → adjacent-row spans never overlap → 2+ components),
none (disconnected gap → 2 components). collisionGap = 2 px, half-gap 1.

| Fixture | Outcome per block |
|---|---|
| baseline 2 blocks, thick/thin/none neck, lobe parents | all Draw, cellRect set, 0 attempts — designed path works (committed contrast test) |
| **tie straddler rect [150,236)×[60,120), whole-bubble parent, score highest** | **A=NO_DISJOINT_POST_ANCHOR_PLACEMENT, B=NO_DISJOINT_POST_ANCHOR_PLACEMENT, C=Draw font 72 (cell-less); attempts=14** — committed flagship |
| same, straddler score 0.9 vs lobes 0.6/0.55 | A, C Draw; B=NO_DISJOINT_POST_ANCHOR_PLACEMENT — one region blank (sweep; score-arrangement dependent) |
| same, straddler score mid (0.7) | all Draw but C exiled 160 px from its OCR box (committed) |
| tie straddler, all three share ONE parent box | all Draw, lobes displaced 65/67 px (committed) |
| 3 blocks, scan centers x = 85/87/89, one component | **B=EMPTY_SHARED_CELL** (middle slab inverted), committed |
| empty-runs fallback + near-equal centers | B=EMPTY_SHARED_CELL (bounds mode), committed |
| dims mismatch 400×300 mask vs 380×290 page | all Draw, cellRect null, fonts 36→27 (25 % smaller), displaced 6–16 px |
| 9 members on one component | member 9 cell-less, displaced 32–42 px; all Draw |
| 3 blocks with zero-overlap OCR rects (componentForRectangle all-zero → null) | first block legacy font 8; second displaced 104 px |

Sweep code was scratch (not committed); the minimal fixtures are committed as
passing documentation tests in `MissingTextReproTest.kt`.

Run:

```
JAVA_HOME="<jbr>" ./gradlew.bat :app:testDevDebugUnitTest \
  --tests "eu.kanade.translation.rendering.MissingTextReproTest"
```

Rendering+segmentation suites at HEAD with the new file:
`tests=194, failures=0, skipped=0`.

## 5. Fix plan (design-level, for Director decision)

Guiding invariant (recommended contract amendment): **a block that the legacy
planner would have drawn must still be drawn.** NonDraw stays reserved for
inputs legacy also dropped (blank text). Concretely, in priority order:

1. **Slice 7 — "clip, don't drop" fallback (fixes RC1/RC3 drops).**
   When the 8-candidate ladder fails, instead of
   `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)` return the block's legacy
   layout (pre-slice-7 result) with the legacy clipRect safety net
   (placeBlock step 3, TextLayoutPlanner.kt:1540-1587), which already makes
   extents structurally disjoint by clipping. Trade-off: clipped text may
   truncate visually — strictly better than a blank region, and exactly what
   legacy did. Alternative (smaller change, partial): when the colliding
   opponent is CELL-LESS, treat the cell'd block as safe (its component+cell
   hard clip provides pixel containment) and skip the occupancy veto for that
   pair — but the production overlay currently ignores hardClip, so this only
   becomes safe after the overlay consumes hardClip.
2. **Slice 3 — empty-cell fallback (fixes RC2).** On a degenerate/empty cell:
   re-cut the pair with gap = 0; if still unusable, fall back to the block's
   own legacy region (never NonDraw). Deterministic, bounded, tiny.
3. **Slice 2 — deterministic tie/breakfall assignment (fixes RC1 at the
   source).** `componentForRectangle` ties currently return null; resolve by
   nearest component-bbox center to the OCR center (then lower component id).
   Zero-overlap boxes: assign the nearest component within a small margin so
   the block gets a real cell. Trade-off: amends the "ambiguous keeps legacy
   region" contract; needs its own focused tests, and the legacy-region
   fallback should remain for genuinely distant boxes.
4. **Dims-mismatch handling (RC4, defensive).** If geometry dims ≠ page dims,
   build bounds-mode cells from the MASK dims scaled to the page (masks are
   page-space by construction; this only guards integration drift), or at
   minimum log a metric so production occurrences become visible.
5. **Beyond-8 (RC5).** Lower-risk option: treat beyond-cap members like
   tie-fallback members (legacy region + keep out of occupancy veto against
   their own component's cells, relying on clips). Cap raise is tunable later.

Sequencing recommendation: (2) is a trivially safe first fix; (1) is the one
that eliminates the reported symptom; (3) removes the trigger and improves
cell coverage on conjoined masks; (4)/(5) are robustness.

Also recommend (separate work item, not this regression): make
`TranslationOverlayView` consume `positionedLines`/`hardClip`, or stop
emitting adaptive layouts until it does — today adaptive results are painted
legacy-style with no structural clip in production.

## 6. Deliverables

- Committed passing documentation tests:
  `app/src/test/java/eu/kanade/translation/rendering/MissingTextReproTest.kt`
  (6 tests, all PASS at HEAD; each carries a `TODO(T912-fix)`).
- This report. No production code changed.
- Scratch sweep/diag classes were used for evidence and removed before
  commit.
