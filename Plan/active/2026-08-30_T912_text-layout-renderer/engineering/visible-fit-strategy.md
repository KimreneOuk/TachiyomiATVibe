# T912 strategy — visible fit inside mask geometry

Branch `codex/text-layout-renderer`, inspected at committed HEAD `b994bc6` plus
the current uncommitted shift/ceiling implementation and tests. This is a
strategy/math report only; it makes no production or test edits.

## Director outcome

For a valid bubble mask, the planner must first make the complete translated
text fit visibly inside the assigned mask component/cell. It may shrink,
rewrap, or choose a nearby mask-valid anchor. It must not move the translation
to another lobe/panel merely to avoid conservative text overlap. Canvas
component clipping is an AA/glyph-overhang safety net, not a substitute for a
fit; an ordinary successful candidate should lose no material ink when that
clip is applied.

If no contained fit exists under bounded work, visibility wins over the mask:
draw an unshifted OCR/legacy fallback without the exact component clip rather
than erase most of the text. This is the only outcome that simultaneously
preserves the never-drop guarantee and avoids presenting a heavily clipped
fragment as success. Such a result is explicitly a mask-unusable fallback, not
a mask-contained result.

Ordinary unmasked manga is outside this strategy and stays byte-identical.

## 1. Layout model and exact constraints

For one nonblank input identity, let:

- `T` be its complete chosen text;
- `P = [0,W) × [0,H)` be the page;
- `M` be the assigned component's row-span set;
- `S` be the block's disjoint half-open cell slab;
- `F = M ∩ S ∩ P` be the allowed mask geometry;
- `o = (ox, oy)` be the OCR/entry anchor;
- `f` be the candidate font;
- `h(f)` be measured line height;
- `a_i(f)` be measured advance of line `i`;
- `s(f)` be the actual outline width used by the painter;
- `g(f) = ceil(s(f)/2 + AA_GUARD)` be the painted-ink guard;
- `v` be the existing extra visual padding used during fitting.

For horizontal line `i` drawn at `(x_i,y_i)`, define the conservative painted
envelope (not collision occupancy):

```text
E_i = [x_i - g, x_i + a_i(f) + g)
      × [y_i - g, y_i + h(f) + g)
```

`layoutWidthPx` is a shaping width and must not be inflated again as though it
were raw glyph advance; it already contains a shaping guard. The current
uncommitted helper inflates the entire `layoutWidthPx`, which double-counts that
guard for positioned lines and can reject a genuinely safe reflow.

A horizontal candidate is **exactly mask-contained** iff, for every line
envelope and every integer row
`r ∈ floor(E_i.top) .. ceil(E_i.bottom)-1`, there is one row span
`[p,q) ∈ F(r)` such that:

```text
p <= floor(E_i.left)  and  ceil(E_i.right) <= q
```

A missing row is failure. The row interval must never be clamped to the first
or last available mask row: clamping would ignore ink above a sloped upper
ceiling. The current `AdaptiveBandPlanner.bandForLine` clamps its inspected
rows to `rowIndex.minRow/maxRow`; the final candidate predicate must close this
gap even if the fitter retains that internal optimization.

For vertical CJK, apply the same condition to each glyph/column envelope. A
safe first production slice may conservatively use one guarded rectangle per
column; it must not pretend that the horizontal adaptive-line proof covers the
vertical renderer.

Additional hard constraints:

1. The wrap consumes all grapheme clusters in `T`, preserving forced newlines
   and existing line-break contracts. No truncation/ellipsis.
2. Every identity remains a Draw unless the existing page-wide render-object
   resource guard fires. Placement failure never produces `NonDraw`.
3. Candidate render objects remain within the existing line/static-layout
   budgets.
4. `f >= FIT_MIN_FONT_PX * scale` for a normal exact fit.
5. The final exact candidate lies in `P` and `S` as well as `M`.

The Canvas component path is then an exact pixel backstop for glyph behavior
that JVM advance/line-height metrics cannot predict. For an exact candidate it
should remove, at most, an AA fringe.

## 2. Lexicographic objective

Do not collapse visibility, movement, and collision into one arbitrary weighted
score. Use a lexicographic policy whose ordering matches the Director's intent:

1. **Identity/full consumption:** all of `T` is present.
2. **Containment class:** exact component-contained > rectangular-cell-contained
   > mask-unusable OCR fallback.
3. **Legibility:** maximize `f`, subject to a small quality-loss allowance when
   a collision-free candidate exists.
4. **Locality:** minimize displacement from `o`/the resolver-entry anchor.
5. **Collision:** prefer a collision-free candidate only after 1–4 remain
   acceptable; otherwise accept contained overlapping text.
6. Deterministic tiebreak: existing anchor order, then candidate order.

Concretely, first compute `B`, the best exact-contained candidate ignoring
other text. A collision-resolution candidate `C` is allowed to beat `B` only
when:

```text
C is exact-contained
font(C) >= (1 - MAX_COLLISION_FONT_LOSS) * font(B)
distance(anchor(C), anchor(B)) <= maskShiftCap
C is collision-free
```

Among such candidates choose maximum font, then minimum displacement, then the
existing fixed direction order. If none exists, return `B` even when its
conservative occupancy overlaps another layout. This prevents the planner from
making text tiny, exiling it, or clipping it away solely to satisfy a planning
envelope.

Initial `MAX_COLLISION_FONT_LOSS = 0.15` is an **ASSUMPTION** aligned with the
existing 15% “material improvement” conventions; it requires page-corpus/device
calibration. It is not a permission to go below the render floor.

## 3. Bounded candidate algorithm

### Phase A — preserve the common path

1. Run the existing normal planner, including parent/mask region selection,
   free-text widening, normal band quality guard, and font harmony.
2. If the block is unmasked, execute today's code exactly.
3. If a masked layout is already exact-contained and does not collide, accept
   it byte-identically. No additional fitter or path work is needed in the
   planner.

### Phase B — compute the best visible contained fit

For a span-mode masked cell, call the existing adaptive-band machinery in
**containment-rescue mode**. This mode deliberately bypasses
`bandBeatsRectangleFit`; a band layout that is only slightly smaller than the
rectangle is still preferable when the rectangle crosses the mask ceiling.

Use a bounded anchor set, in deterministic order:

1. resolver-entry/OCR anchor;
2. nearest feasible projection of that anchor into the guarded component rows;
3. component-cell content center;
4. half-line toward the larger valid vertical side (existing choice);
5. optional opposite half-line only if the first four have no exact fit.

For each anchor, use the existing maximum seven font evaluations. For each font:

1. prewrap all text using the current pure breaker;
2. reject `> MAX_POSITIONED_LINES_PER_BLOCK`;
3. compute every line's guarded row range without clamping;
4. intersect row spans over that whole range;
5. choose the continuous interval nearest the anchor;
6. place using current integer floor semantics;
7. validate the measured painted envelope against every covered row.

The highest-font exact result is `B`; ties choose minimum anchor displacement,
then anchor order. This operation already supplies shrink + reflow + an inward
anchor. It should be computed once per resolver invocation and retained even if
the collision predicate rejects it.

### Phase C — local collision improvement

The current eight-candidate structural limit can be preserved:

- On the masked branch, replace candidate 1 (a known duplicate collision
  failure whenever the resolver is entered) with `B`.
- Try the current smaller/baseline concepts only when they remain exact-contained
  and within the allowed font-loss threshold.
- Directional movement is never a raw translation of already-positioned lines.
  For each derived left/right/up/down anchor, rerun the bounded band placement
  against `F`; this preserves the ceiling proof. Skip the direction when the
  required displacement exceeds `maskShiftCap`.
- A masked free-strip candidate is admissible only if it consumes all text,
  passes the same exact component-row proof, stays within `maskShiftCap`, and
  meets the font-loss threshold. A refit merely having a non-null rectangle is
  not enough.

The shift cap may retain the current uncommitted formula:

```text
maskShiftCap = min(
    MAX_MASK_SHIFT_REGION_FRACTION * min(OCR width, OCR height),
    MAX_MASK_SHIFT_PAGE_FRACTION * min(W,H)
)
```

Current proposed fractions `0.25` and `0.04` are **ASSUMPTIONS**. They are safe
as isolated tunables but should be tuned against manga/webtoon corpora before
being treated as final.

### Phase D — failure ladder (never drop)

Use this exact priority:

1. Normal candidate: exact-contained, full text, no collision.
2. `B`: exact contained reflow/shrink; collision-free if available.
3. Local contained alternative within font-loss and shift caps.
4. `B` with overlap. This is the normal “no disjoint placement” terminal
   result: no extra `clipRect`; component clip is safety only.
5. Bounds-mode mask (no exact spans): fit/shrink/reflow inside the disjoint
   rectangular cell; accept overlap rather than relocate beyond the cap.
6. Exact spans exist but no full contained fit at the render floor/line budget:
   mark the component mask unusable for this block and draw the unshifted
   OCR/legacy result without the exact component path. Keep page/cell safety
   where it does not cut text. This is an explicit degraded containment class,
   not a hidden success.
7. Only the existing `STATIC_LAYOUT_BUDGET_EXHAUSTED` resource guard may remain
   non-drawable.

Step 6 is important. If a mask is too small, fragmented, wrongly assigned, or
topologically corrupt, “all text visible” and “all pixels inside this mask” are
mathematically incompatible. Hard-clipping most of the word satisfies the
second statement only by violating the user-visible objective. Failing open to
the OCR region is the honest never-drop behavior.

## 4. Render-path contract

Keep the uncommitted bounded component-path preparation and the common clip
order:

```text
component path -> cellRect -> existing clipRect -> paint
```

but apply the component path only to layouts whose planner result carries an
exact-containment proof. This can be represented by an explicit metadata bit or
by clearing component ids on the mask-unusable fallback; do not infer it from
`positionedLines != null` because vertical and future layout forms also need a
proof state.

The production overlay must apply `cellRect` consistently to legacy and
positioned paths, as the current uncommitted overlay change does. Paths remain
prepared in `bind`, bounded by 64 components/100,000 spans; no path/dense mask
is built in `onDraw`.

Render acceptance for an exact-contained fixture must prove both:

- zero alpha outside `component ∩ cell ∩ legacy clip`; and
- material retention, initially `alphaWithClip / alphaWithoutClip >= 0.95`
  plus nonzero ink for every planned nonblank line.

The 95% value is a calibration threshold, not core geometry. The current
uncommitted Android assertion `insideInk > 0` must not be used as evidence that
the visible-fit goal is met.

## 5. Tunables and hard budgets

| Parameter | Initial strategy value | Meaning / fallback |
|---|---:|---|
| `MAX_MASK_SHIFT_REGION_FRACTION` | 0.25 (assumption) | Caps local movement by OCR short dimension |
| `MAX_MASK_SHIFT_PAGE_FRACTION` | 0.04 (assumption) | Absolute page-relative movement ceiling |
| `MAX_COLLISION_FONT_LOSS` | 0.15 (assumption) | Maximum font sacrifice allowed solely to avoid another text |
| `MIN_RENDERED_ALPHA_RETENTION` | 0.95 (Android-test calibration) | Hard clip must remove only fringe on proven fits |
| `MAX_CONTAINMENT_ANCHORS` | 5 | Finite anchor trials listed above |
| `MAX_FONT_BINARY_STEPS` | existing 7 | Per anchor, unchanged |
| `MAX_POSITIONED_LINES_PER_BLOCK` | existing 24 | Beyond → next failure-ladder form |
| `MAX_POSITIONED_LINES_PER_PAGE` | existing 256 | Retry rectangular/single-layout form |
| `MAX_STATIC_LAYOUTS_PER_PAGE` | existing 512 | Only live resource non-draw guard |
| component/span/path caps | existing 64 / 100,000 | No new full-page work |

Worst-case new planner work for one colliding masked block is at most five
anchors × seven font trials × 24 line validations over bounded row spans. Row
coverage should use ordered span cursors/row offsets shared by the cell, not a
new dense mask or per-candidate map. If a page-wide containment comparison cap
is needed after measurement, exhaustion goes to the mask-unusable visible
fallback, never NonDraw.

## 6. Edge-case matrix

| Page/content case | Strategy outcome | Important invariant |
|---|---|---|
| Ordinary unmasked manga bubble | Exact current planner/renderer | Byte-identical 31-case legacy suite; no new cap/fit branch |
| Normal masked oval, rectangle layout already safe | Keep current layout | No gratuitous band conversion or font loss |
| Curved/sloped upper bubble ceiling | Reflow/shrink and project anchor inward; exact full-row proof | Missing top rows fail; never clamp them away |
| Concave bubble or internal hole | Per-line continuous interval across all guarded rows | Never validate from component bbox alone |
| Fused cloud, several text blocks in one component | Independent existing disjoint cells; fit each within its own spans | Never merge text; overlap with foreign text is preferable to exile |
| Thin/diagonal neck fragmented into components | Deterministic assigned component, contained fit | If assigned component cannot host text, mask-unusable OCR fallback; do not clip to a 1 px fragment |
| Webtoon full-height shared mask / single-axis strips | Contained fit inside assigned strip; no cross-strip shift | 2D partitioning remains a separate improvement |
| Very tall/narrow vertical partition | Fix/verify the known Y-vs-X span filter before trusting exact proof | Otherwise spans can disappear and cause false mask-unusable fallback |
| Vertical Japanese/CJK manga text | Guarded glyph/column containment, bounded font/column reflow | Horizontal line proof is not reused blindly |
| Long Latin manhwa dialogue | Word rules unchanged; shrink/wrap inside bands | No invented hyphen except existing ALL-CAPS contract |
| Long `text_free` webtoon narration | Existing OCR/1.25×/1.50× widening first; if masked, final candidate still passes containment | No new direction/orientation mutation |
| Borderless text/SFX with no valid mask | Existing unmasked/text-free behavior | No mask clip or masked shift cap |
| OCR center slightly outside component | Nearest feasible anchor projection, bounded by shift cap | Do not pull text across the page to component center |
| Mask/page dimension mismatch or conversion budget fallback | Rectangular-cell fit; accept overlap | No false exact-component claim |
| Degenerate/empty cell | Existing legacy OCR-region Draw fallback | Never drop; no fake component proof |
| More than eight siblings | Existing disjoint overflow slab; rectangular fit | No component path if no component id |
| Thick outline, combining marks, emoji/ZWJ, punctuation overhang | Guarded metrics plus Canvas component safety | Android retention test catches material clipping |
| Low-resolution/downsampled page | Scale-aware stroke/AA and shift caps | At least half-pixel AA guard; deterministic rounding |
| Huge page / complex mask | Existing geometry/path/span budgets | No dense page allocation; budget fallback stays visible |
| Crowded panel with no collision-free solution | Return best exact-contained candidate with overlap | Collision safety never causes exile, tiny text, or clipping |
| Wrong/tiny mask cannot host complete text | Explicit mask-unusable unshifted OCR fallback | Visibility/identity wins; do not call a fragment “contained” |

## 7. Current uncommitted changes: disposition

### Keep

- Masked-only shift-cap concept and isolation from unmasked behavior.
- Ordered row-span containment helper concept.
- Bounded component-path cache built during overlay bind.
- Common overlay clip composition for legacy horizontal, legacy vertical, and
  positioned lines.
- No-drop/cardinality and deterministic replay assertions.

### Rework

- Make contained reflow/shrink the resolver's preferred masked rescue and saved
  overlap fallback.
- Refit at a shifted anchor; never translate previously validated adaptive
  lines and assume the proof survived.
- Compute positioned paint envelopes from measured advance/line height, not an
  already-guarded `layoutWidthPx` inflated again.
- Treat missing component rows as failure instead of clamping the checked range.
- Carry an explicit exact-containment/mask-unusable state to the overlay.
- Replace `insideInk > 0` with full text reconstruction, plan-level containment,
  and material alpha-retention evidence.

### Discard

- Masked tail `layout.copy(clipRect = inkRectOf(layout, measurer))` as the normal
  result.
- The JVM expectation that success is demonstrated by `clipRect != null`.
- Unconditional component clipping of a layout for which the planner could not
  find/prove a full visible fit.
- Returning any rejected far free-strip refit merely because it is non-null.
- Treating hard clipping or one surviving painted pixel as a render-quality
  success.

## Recommendation

Implement one masked-only contained-reflow rescue using the existing adaptive
band fitter, save its best full-text result before collision checks, and return
it with overlap when bounded local collision candidates fail. Retain the shift
cap and component path only as secondary guards. If exact fitting is
impossible, fail open to the unshifted OCR result rather than materially erase
the text.

---

# Revision 2 — real-page calibration and the OCR-box-first containment ladder

Supersedes the Phase B anchor list in §3 and the movement-cap emphasis of the
shift-ceiling documents where they conflict with this section. Written after
the Director's live iteration feedback: text should start from its
`bubble_text` (OCR) bounding box, should not move up/down much, should not
shrink much, and should never sprawl across the entire shared component.

## Evidence source — the page-15 laptop rig

A scratch JVM rig (uncommitted `Page15MockRig.kt` test) runs the real
`TextLayoutPlanner.planPage` against the Director's actual problem page:
read-only device extraction of the committed page artifact (29 blocks, real
OCR rects, real `BubbleMaskRle` masks, 1280×1780) plus the exact page image,
cached once under `engineering/fixtures/`. It emits SVG previews and
containment/shift diagnostics; the Director reviews iterations live
(`http://127.0.0.1:8765`, static copy of `fixtures/rig-out/`). No APK rebuild,
no device use.

Facts measured on the real page (committed planner `b994bc6` + prototype):

1. The fused cloud is ONE connected component (1,165 runs, 1090×577) shared by
   10 OCR blocks. The single-axis slab partition does not match the round
   lobes; two members get no component metadata, one pair-member gets no cell
   at all (beyond-8 cap / assignment tie are visible in production data).
2. Every masked member's entry layout fails exact component containment
   (`legN`): the legacy extent is not continuously covered by its rows.
3. Three cloud members are exiled 133–149 px from their OCR boxes (and ~137–148
   px from their own parent lobes) with fonts crushed to 10–12 px while
   siblings get 24–27 px — the Director's complaint, now quantified.
4. After the revised ladder below, ALL 10 masked blocks reach a fully
   contained wrap with ZERO horizontal and ZERO vertical anchor shift:
   eight at the OCR box itself or one 1.25× widening (font loss 0–2 px, ≤12%),
   and the single-oval block at 24 px vs entry 31 px (−23%) at 1.5× widening —
   the oval physically cannot hold the rectangle-fitted 31 px ink.

## Revised Phase B — OCR-box-first tiered containment

For a masked span-mode member, replacing the resolver's masked rescue:

```text
regions = [
  ocr-box      : OCR rect ∩ F                    (zero shift by construction)
  ocr-x1.25    : center-preserving 1.25× of OCR rect, clamped to cell slab
  ocr-x1.5     : center-preserving 1.50× of OCR rect, clamped to cell slab
  ocr-x2.0     : center-preserving 2.00× of OCR rect, clamped to cell slab
  cell-content : ONLY for blocks with a real assigned component cell
                 (never for mask-derived contexts: the mask is a ceiling,
                  not a region supplier)
]
MIN_GOOD_FONT_FRACTION = 0.85   (assumption; existing font-loss convention)
SCOOT_CAP = 0.5 × min(OCR width, OCR height) per move
```

Per tier, in order: clip the component row spans to the region, run
`fitAdaptiveBands` (max font = entry font; reflow first, shrink last) with the
anchor pinned at the OCR center, then validate the result with the corrected
envelope predicate (§1) against the FULL component rows. Accept the first tier
whose contained font ≥ `MIN_GOOD_FONT_FRACTION × entryFont`. If a tier's top
font crosses the real contour (the fitter's internal row check accepts
boundary-crossing fonts today), walk the font down in bounded steps under the
corrected predicate — containment class always outranks font size; a smaller
CONTAINED fit beats a larger crossing one. If no tier contains the wrap at the
render floor, the block has NO CONTAINED FIT → failure ladder §3 Phase D
(best contained result, then mask-unusable visible OCR fallback), never
clipping and never `NonDraw`.

After all masked blocks hold their contained layouts, run a CONTACT pass:
when two blocks' final ink extents actually touch/overlap, move the
lower-priority block by the MINIMAL separation vector (left/right/up/down,
sorted by distance) subject to (a) staying under `SCOOT_CAP`, (b) remaining
fully component-contained, and (c) actually removing the contact. If no
direction satisfies all three, accept the contained overlap — per the
lexicographic policy, contained text overlap is preferable to exile,
shrinking, or clipping. On the real page this pass produced exactly one 6 px
scoot and two accepted contained overlaps (both pairs of OCR boxes that
genuinely overlap inside one small bubble).

This replaces the five-anchor wander of §3 Phase B: anchors are no longer free
points; locality is encoded in the region ladder. It also directly implements
the Director's model — fit/shrink/reflow first, movement only by bounded local
widening, clip only as AA-fringe insurance.

### Production consequences measured by the rig

- The unclamped final containment predicate MUST live inside (or immediately
  wrap) the band fitter's font search; otherwise the top font it returns
  silently crosses curved ceilings (observed on the oval block: fitter 25 px
  crossing, corrected search 24 px fully contained).
- `MIN_GOOD_FONT_FRACTION` bounds how far the ladder descends before accepting
  a tier: at 0.85, eight blocks stop at tier 1–2 (no shift), and the oval's
  −23% is an explicit containment-driven exception the Director can tune.
- Beyond-8 members and assignment ties on 10-member clouds fall back to
  uncontained rectangle layouts today; they need either a raised
  `MAX_SHARED_BLOCKS_OPTIMIZED`, overflow cells, or the ladder run against
  slab-clipped component spans instead of `SharedCellPlan.spans`.

## Tunables added (all isolated, all ASSUMPTIONS pending corpus calibration)

| Parameter | Value | Meaning |
|---|---:|---|
| `OCR_GROW_TIERS` | 1.0 / 1.25 / 1.5 | Locality ladder around the OCR box (1.25/1.5 reuse the text_free widening conventions) |
| `MIN_GOOD_FONT_FRACTION` | 0.85 | Minimum font retention for a tier to be accepted without descending |
| `CONTAINED_FONT_STEPS` | ≤ 4 | Bounded walk-down steps per tier under the corrected predicate |

## Fixture and success criteria (updated)

The rig is the standing fixture harness: `page15-committed.json` +
`page15-source.jpg` are deterministic, corpus-derived JVM fixtures. Success on
this page is now defined as: (a) all masked blocks fully row-span contained;
(b) horizontal/vertical anchor shift 0 for every block whose entry layout did
not collide; (c) font retention ≥ 85% except explicitly contained-driven
exceptions; (d) the failure ladder reachable only for the deliberately
impossible fixtures; (e) zero device installs required for planner-level
verification. Android pixel tests remain only for the AA-fringe retention
proof (§4).
it with overlap when bounded local collision candidates fail. Retain the shift
cap and component path only as secondary guards. If exact fitting is impossible,
fail open to the unshifted OCR result rather than materially erase the text.
