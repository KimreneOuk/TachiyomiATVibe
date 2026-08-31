# T912 render-quality REPAIR — implementation report

Branch `codex/text-layout-renderer`, base HEAD `c27f95b` (no rebase, no push),
worktree `t912-investigation-wt`. Implements the Director's three-part
render-quality mandate on top of the accepted missing-text repair:
Fix 1 — the production overlay consumes the T912 layout model; Fix 2 — a
band-acceptance quality guard ("beats-the-rectangle"); Fix 3 — font harmony
for same-component siblings.

## Result

**Fused-cloud render quality is governed again.** Adaptive bands are no
longer accepted unconditionally: they must beat the conservative rectangle
layout of their own cell by [TextLayoutTuning.BAND_ACCEPT_FACTOR] (1.15) — or
rescue text the rectangle cannot host at all — otherwise the block takes the
EXISTING legacy rectangle form in the same fit region. Same-component sibling
fonts are capped DOWN to 1.4 × the group median (never inflated). The overlay
now draws positioned lines at their planned placements clipped to
cellRect → clipRect, so the band model's containment finally reaches
production. Unmasked/legacy rendering is byte-identical.

### Fix 1 — overlay consumes the positioned layout (TranslationOverlayView.kt)

`drawLayout`: when `layout.positionedLines != null`, the new
`drawPositionedLayout` applies clips ONCE per layout — `cellRect` first
(structural bound; the overlay has no component Path), then the legacy
`clipRect` — then per line: save, translate `(leftPx, topPx)`, draw the raw
line text stroke-then-fill at `baseline = top - ascent`, x = left, with
`textAlign = LEFT` set for positioned draws only. `restoreToCount` per line
and per layout. Empty line texts are skipped (mirror of the renderer's
reserved blank slots). No StaticLayout, no per-frame allocation beyond the
draw calls. `positionedLines == null` → the EXACT previous body (centered
lines, `clipRect` only), untouched.

### Fix 2 — band-acceptance guard (TextLayoutPlanner.kt)

In the adaptive branch of `planPageInternal`, a non-null `adaptiveFit` is now
additionally gated by the new `bandBeatsRectangleFit`: the rectangle fit is
`binarySearchFontSize(text, safeW, safeH, safeW, false, scale, measurer)` with
safeW/H = the cell's content bounds (`SharedCellPlan.fitRegion` — the span
content bbox — else the slab) shrunk by `TextLayoutTuning.strokeInsetPx`
(stroke/2 + AA guard + VISUAL_PADDING_PX per side) at the band-fit font.
Bands are accepted only when
`adaptiveFit.fontPx >= BAND_ACCEPT_FACTOR * rectFitFont`, OR the rectangle
overflows at its fitted font (nothing fits the shrunk rect) while the band
result consumes the text fully inside the cell (a successful band fit's lines
are the complete slab-validated wrap of the trial text — true by
construction; see deviation D2). Rejected fits reserve NOTHING (budget
accounting stays inside the acceptance branch) and fall through to the
existing legacy `placeBlock` path with the same fit region — the identical
branch a null band fit already took.

### Fix 3 — font harmony (TextLayoutPlanner.kt)

After the whole placement loop, `applySiblingFontHarmony` groups accepted
Draw layouts by `(groupId, componentId)` (span-mode cell plans only; empty
plans, bounds-rect cells and unmasked blocks are never touched), and for
every group with ≥ 2 members caps every member DOWN to
`FONT_HARMONY_MEDIAN_CAP (1.4) × median(fonts)`. Deterministic: groups in
first-appearance (render) order, members by input index, even-sized medians =
lower-middle element. Capped LEGACY members are re-wrapped via `cjkWrap` at
the capped font with `computeStrokeWidth` (vertical members keep their empty
line list — columns derive from the font at draw time). Capped ADAPTIVE
members re-run `AdaptiveBandPlanner.fitAdaptiveBands` with `maxFontPx = cap`
and the metadata (cellRect/ids/hardClip) copied onto the refit; when the
refit fails, the member falls back to its legacy `placeBlock` form with the
same fit region (see deviation D3 for the clip-rect guard). The seam
`harmonizedReplacement` implements the per-member replacement and is internal
for tests.

## Updated test expectations (every change documented)

Protected suites stayed green; four fixtures pinned outcomes the guard
legitimately changes (full-height rectangle cells give bands no meaningful
win). No assertion was weakened; every flip is the mandated behavior.

1. `TextLayoutPlannerSlice5Test` — `span mode horizontal block becomes
   adaptive with the full positioned line contract` → renamed `span mode
   horizontal block on a rectangle cell keeps the legacy rectangle layout`.
   The cell's content bounds span the whole 300x120 rectangle: band fit 43
   does not beat the rectangle fit 44 → adaptive REJECTED; the test now pins
   the legacy rectangle form (origin 150/60, font 44, safe 292x112, lines
   `["Hello there", "friend"]`, `positionedLines == null`) with the IDENTICAL
   metadata contract (slab cellRect, ids, hardClip).
2. `TextLayoutPlannerSlice5Test` — `adaptive block reserves two static
   layouts per positioned line`: text `"AAA BBB"` → `"A".repeat(8)`. The old
   text's band fit (72) merely matched its rectangle fit (72) and is now
   rejected; the 8-char ALL-CAPS token admits exactly one balanced break, so
   the hyphenated band fit (72) beats the rectangle fit (59) and the adaptive
   result survives with its 2 positioned lines. Every original expectation
   (including `l254` → `STATIC_LAYOUT_BUDGET_EXHAUSTED`) is preserved — the
   reservation semantics under test are unchanged.
3. `TextLayoutPlannerSlice5Test` — `page positioned line budget retries the
   legacy single layout form`: strip 240 → 236 (page 2640 → 2596). At 240 the
   rectangle fit of the cell content fits the floor font (24 lines = 230.4 px
   ≤ 232) so the guard rejected the bands; at 236 the rectangle overflows at
   its floor font (230.4 > 228) while the bands consume the text fully — the
   guard's second acceptance clause. All other expectations unchanged.
4. `TextLayoutPlannerMaskMetadataTest` — `three blocks on one shared
   full-rectangle mask share geometry group and disjoint cells`: the slice-5
   positioned-lines block replaced with legacy-rectangle assertions
   (`positionedLines == null`, hardClip mirror, `computeStrokeWidth`
   formula). Cells, ids, texts and the disjoint-slab assertions are
   unchanged; the per-result font monotonicity (`plan[2] < plan[1]`) is
   retained and still holds (the harmony median cap does not bind here).

New: `TextLayoutPlannerQualityRepairTest` (4 tests) — guard reject (legacy
rectangle + full metadata), guard keep (thin slanted lobe, 3 planned lines
reconstructing the trial text, font ≥ 1.15 × the rectangle fit), harmony cap
end-to-end (fonts 72/13/13 → 18/13/13, within 1.4 × median, byte-identical
replay), and the refit-failure fallback via the `harmonizedReplacement` seam.

## Scope and risks

Deviations and residual risks, all documented:

1. **Guard inset font (D1).** The mandate fixed the shrink formula but not
   the font for `strokeInsetPx`; it is evaluated at `adaptiveFit.fontPx` (the
   font under judgment). Deterministic and documented in-code.
2. **"Consumes fully without overflow" (D2).** Implemented as the planner
   invariant it is: a successful band fit's lines are the complete wrap of
   the (trial) text, each validated band-contained and slab-contained by
   `AdaptiveBandPlanner`, so the clause reduces to
   `rectOverflows && adaptiveFit.lines.isNotEmpty()` — no fake re-verification.
3. **Refit skipped for clipped adaptive members (D3).** An accepted adaptive
   layout that carries a containment `clipRect` (R2 fallback forms) is NOT
   re-fit: a re-fit could place lines outside that clip and erase them.
   Those members take the legacy `placeBlock` fallback instead.
4. **Refit-failure fallback is seam-tested (D4).** Through `planPage`, band
   fitting is downward-closed in the font for a fixed cell, so a capped refit
   of a genuinely accepted fit cannot fail on well-formed spans. The fallback
   branch is therefore driven directly through the internal seam
   `harmonizedReplacement` with a span set no band can fit (single 20px
   spanned row); the end-to-end harmony path is covered by the cap test.
5. **Harmony post-placement bookkeeping.** Harmony runs after placement;
   capped members only shrink (legacy re-wraps; adaptive refits are
   slab-contained), so the accepted collision set is not re-validated and
   page budgets are not re-charged (bounded: only capped members, one refit
   each). The legacy fallback for a failed refit is recomputed against the
   layouts placed BEFORE it (post-capping extents), which can differ from the
   member's original turn — bounded by the fit region ⊆ its own slab.
6. **DISCOVERED PRE-EXISTING DEFECT (out of scope, NOT fixed — follow-up
   recommended).** `MaskTextRegionPlanner.partition` filters component spans
   with `span.y < orthoLow || span.y >= orthoHigh` where — for VERTICAL
   partitions — `orthoLow/High` are the component's X bounds. Rows ≥ the
   component's width therefore lose their spans: tall-narrow vertical
   partitions produce truncated fit regions or EMPTY cells (the empty-cell
   path is safe since the R1 repair — the member draws via its legacy
   region). Exposed while building the harmony fixture on a 300x1200 mask;
   the fixture was moved to landscape geometry. Fix = filter x-overlap (or
   skip the filter) for vertical slabs; it changes cell span contents for
   tall vertical partitions and deserves its own reviewed slice.
7. **Overlay parity (N1 closure).** The overlay now consumes `cellRect` +
   `clipRect`. The component-path clip (renderer-only) remains unplanned for
   the overlay by mandate ("the overlay has no component Path"); the
   reviewer's N1 is closed to planner-fiction level the mandate allows.
8. **Non-goal per mandate:** no 2D partitioning, no shift-distance caps.

## Verification

Worktree `C:\Users\User\Documents\TachiyomiAT-1.16.8-dev\t912-investigation-wt`,
Git Bash, JAVA_HOME = Android Studio JBR:

1. Mandated focused suite:
   `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   → BUILD SUCCESSFUL in 21s (wall ≈ 22s). suites=23, tests=207, failures=0,
   errors=0, skipped=0. Protected counts from JUnit XML:
   `TextLayoutPlannerTest` 31/31 (file UNCHANGED), `MissingTextReproTest`
   11/11 (never-drop invariant intact), `TextLayoutPlannerFinalSafetyTest`
   7/7, `TextLayoutPlannerSlice5Test` 8/8 (3 documented updates),
   `TextLayoutPlannerFreeTextTest` 17/17, `PageLayoutPlanContractTest` 6/6,
   `TextLayoutPlannerMaskMetadataTest` 10/10 (1 documented update),
   `AdaptiveBandPlannerTest` 9/9, `MaskTextRegionPlannerTest` 13/13 (both
   untouched), new `TextLayoutPlannerQualityRepairTest` 4/4.
2. Mandated instrumentation compile:
   `./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   → BUILD SUCCESSFUL in 15s (exit 0). This is the automated proof for the
   Android-only overlay change (no JVM surface; semantics mirror
   PageTextRenderer, which the instrumented suite pins — Director will
   device-test).
3. Mandated full module suite: `./gradlew.bat :app:testDevDebugUnitTest`
   → BUILD SUCCESSFUL in 53s (wall ≈ 54s). suites=172, tests=1317,
   failures=0, errors=0, skipped=0 (HEAD's 1313 + 4 new).
