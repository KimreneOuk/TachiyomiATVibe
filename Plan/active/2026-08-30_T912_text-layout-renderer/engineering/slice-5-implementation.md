# T912 slice 5 — adaptive bands and the Android shaping contract

## Result

Implemented slice 5 of architecture revision 2 on `codex/text-layout-renderer`
(base `3a4b367`), one commit, no rebase/reset. Slice 4 (necks) stays formally
deferred — no neck logic anywhere.

- **NEW pure `TextLineBreaker.kt`** (rendering, zero `android` imports):
  `tokenize` (newline marker / whitespace / CJK grapheme / Latin-ish run that
  ends after a non-leading source hyphen — the exact legacy `cjkWrap`
  tokenizer), `prewrap` (greedy wrap with cjkWrap-identical semantics:
  atomic overflow kept whole, separator dropped after a break, trailing
  separators trimmed, empty stream → `listOf(text)`; parity pinned by a
  18-text × 3-font × 3-width cross-check corpus), `trialEligible` (pure ASCII
  `[A-Z]{8,}` — letters-only subsumes the digit / `. / @ _ :` / hyphen bans),
  and `applyBestHyphenTrial` (trial set EXACTLY baseline / one balanced break
  at `floor(len/2)` / two balanced breaks at `floor(len/3)`,`floor(2len/3)`
  when length permits; 3-letter per-segment minimum enforced by clamping each
  split into its feasible range; first eligible token in reading order; at
  most two insertions per block; acceptance only on overflow removal
  (baseline floored at min font) or `trialFont >= 1.15 × baselineFont`; ties
  keep the earlier fewer-insertion candidate; operates on copies — persisted
  text is never mutated).
- **NEW pure `AdaptiveBandPlanner.kt`** (zero `android` imports):
  `fitAdaptiveBands(...)` implementing the architecture "Slice 5" algorithm:
  bounded font binary search (integer candidates over the page font bounds,
  ≤ 7 steps, largest-fit first); per-line bands from the covered rows
  (`floor(yTop)..ceil(yTop+lineH)-1` clamped to covered span rows) by
  pairwise coalesced interval-list intersection with a 64-interval explosion
  guard; center-nearest surviving interval (ties: wider, then leftmost); 3
  fixed-order vertical alignments (OCR/block center, cell content center,
  half-line toward the larger free side within the slab); ≤ 3 centering
  fixed-point passes (wrap at min band width − 2·inset, re-band from the new
  y positions, stop when stable); per-line acceptance
  `layoutWidthPx = max(1, ceil(advance + 2·SHAPING_GUARD)) <= band width`,
  integer-floor `leftPx/topPx`, and EXACT span containment of every pixel
  column on every covered row; > 24 lines or an unvalidatable line fails the
  font; all fonts/alignments fail → null (legacy rectangular fallback). The
  ALL-CAPS trial runs ONCE per block before band fitting against a
  conservative rectangular fit of the slab (same bounded binary search,
  stroke-inset rectangle, `prewrap` wrapping); `AdaptiveResult.usedTrialText`
  records which text was wrapped; `Stats(fontSteps, alignmentsTried,
  fixedPointPasses)` are exact counters.
- **`TextLayoutPlanner.kt`**: `BlockLayout` gains `positionedLines`,
  `conservativeOccupancy`, `hardClip` (defaults preserve every existing
  constructor call); new top-level `PositionedLine` and `HardClip` contract
  types verbatim; new isolated `TextLayoutTuning` holder (`AA_GUARD`,
  `SHAPING_GUARD`, `VISUAL_PADDING_PX = 2f`, stroke inset
  `ceil(stroke/2 + AA + padding)`, the 24/256/512/7/3/3 budget constants, and
  the 64-interval band guard). `SharedCellPlan` now carries the cell's
  slab-intersected spans. `planPage` runs the adaptive attempt for eligible
  blocks (nonblank horizontal text in an optimized SPAN-MODE cell with
  non-empty spans) in place of `placeBlock`; band failure or a page-budget
  overflow retries the EXISTING legacy `placeBlock` path with the same
  fitRegion; a legacy horizontal form that would exceed the 512-StaticLayout
  budget emits the slice's first `NonDraw(STATIC_LAYOUT_BUDGET_EXHAUSTED)`
  (`POSITIONED_LINE_BUDGET_EXHAUSTED` stays declared-but-unemitted). Budgets
  reserve deterministically in placement order: legacy horizontal = 2
  StaticLayouts, each positioned line = 2, vertical = 0. `hardClip` mirrors
  the wired metadata 1:1 on every Draw path (`HardClip(planGeometryId,
  maskComponentId, cellRect)`; `HardClip(null,null,null)` for
  unmasked/legacy). `extentOf` uses the union bounding box of the per-line
  conservative occupancy rects for adaptive layouts, so obstacle consistency
  holds for every block kind. Adaptive `BlockLayout`s carry: origin = the
  alignment anchor, `safeW/safeH` = slab bounds, `drawAlign = CENTER`,
  `fontSizePx` = fitted font, `strokeWidth = computeStrokeWidth`, `clipRect`
  = null, `lines` = the wrapped trial-resolved texts (uniform handle),
  `layout.text` = the block's own chosen text. The 64-distinct-pair metadata
  cap predicate was extracted verbatim into
  `internal fun componentAssignmentAllowed(assignment, assigned)` (same
  semantics; see deviations).
- **`PageTextRenderer.kt`**: when `layout.positionedLines != null`, bind
  prepares EXACTLY one StaticLayout pair (fill + stroke) per line at
  `StaticLayout.Builder.obtain(line.text, 0, len, paint, line.layoutWidthPx)`
  with `ALIGN_NORMAL`, `setIncludePad(false)`, `BREAK_STRATEGY_SIMPLE`,
  `HYPHENATION_FREQUENCY_NONE`, `setMaxLines(1)`; `lineCount == 1` is
  verified at bind and a violation drops the whole prepared layout (fail
  closed — renderer-side invalid render metadata; the planner-model reason
  text remains a later-slice artifact). `draw()` composes the existing clips
  ONCE per layout (component path → cellRect → legacy clipRect), then per
  line: save, translate(leftPx, topPx), stroke, fill, restore. All
  construction stays in bind(); draw() allocates nothing. Vertical and legacy
  horizontal paths are byte-identical.
- **`MaskTextRegionPlanner.kt`**: ONE unavoidable production change — see
  deviations (vertical-partition span-intersection bug fix exposed by the
  mandated ride-along fixture). No API changes; the horizontal path is
  byte-identical.
- **`BubbleMaskRle.kt`**: no changes.

## Verification

From repo root, Git Bash on Windows (wall times are the recorded gradle
totals; total wall for the task ≈ 12 min across five invocations):

1. An early `:app:compileDevDebugKotlin` probe — 1 failure (missing
   `kotlin.math.min` import in the new planner), fixed.
2. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   — first run BUILD FAILED in 3m 23s with 6 failures (2 real production
   bugs, 4 wrong hand-computed expectations — all fixed, see deviations);
   second run BUILD FAILED in 32s with 1 remaining fixture bug (test spans
   wider than their slab violated the slab-intersected precondition); final
   run **BUILD SUCCESSFUL in 29s**: **160 tests, 0 failures, 0 errors, 0
   skipped across 18 suites** (JUnit XML aggregate):
   - NEW TextLineBreakerTest 19/19, NEW AdaptiveBandPlannerTest 9/9, NEW
     TextLayoutPlannerSlice5Test 8/8
   - MaskTextRegionPlannerTest 13 (12 existing + 1 NEW vertical ride-along)
   - TextLayoutPlannerMaskMetadataTest 8 (expectations updated where slice 5
     legitimately changes them — documented below)
   - Existing, UNCHANGED and green: TextLayoutPlannerTest 31 (no edits),
     PageLayoutPlanContractTest 6 (no edits), PageTextRendererDirectionTest 8,
     TextLayoutPlannerStrokeTest 4, ComponentClipCacheTest 6,
     MaskGeometryTest 7, MaskGeometryStressTest 2, MaskGeometryOrderedRleTest 9,
     BubbleSegmentationDecoderTest 8, RenderColorEstimator 22 (7+5+7+3).
   Byte-identity for ordinary unmasked manga: the unchanged 31-case planner
   suite plus the four direction/stroke suites pass with zero edits.
3. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   — **BUILD SUCCESSFUL in 25s**. No device/emulator was available, so the
   new instrumented pixel tests remain compile-gated (same as slices 1–3;
   device proof belongs to the slice-8 gate).
4. `git diff --check` — clean. `git status` shows exactly the allowed files
   plus the documented MaskTextRegionPlanner fix.

Task-mandated pre-verification pins are in the tests: full-rect rows give
band width == slab width so usable width is `slabWidth - 2*inset` (the
104px/103px wrap-boundary pair flips 1↔2 lines — any other inset fails);
a 1-row-class thin component anchors via the alignment ladder; and the
H-check `ceil(stroke/2 + AA + 2)` at font 30, scale 1 = 5 is pinned directly
(`strokeInsetPx(30f, 1f) shouldBe 5f`).

## Scope and risks

Touched files: NEW `TextLineBreaker.kt`, NEW `AdaptiveBandPlanner.kt`,
`TextLayoutPlanner.kt`, `PageTextRenderer.kt`, the two new JVM test files +
`TextLineBreakerTest`, the instrumented test, `MaskTextRegionPlannerTest`
(one ride-along), `TextLayoutPlannerMaskMetadataTest` (updated fixture), and
this report. No TranslationOverlayView/batch/download/drawer/progress/
pipeline files touched. No slice-6 free-text widening, no slice-7
final-safety retries, no neck inference.

### MaskTextRegionPlanner.kt production change (unavoidable)

The ride-along mandate ("one VERTICAL partition fixture — two stacked
members, exact slab/dead-row values asserted") exposed a REAL latent slice-3
defect in the vertical branch of the span materialization (exactly where
review NOTE 1 predicted a swap bug would hide): both the segment-counting
pass and the fill pass intersected each span's X interval with the slab's
axis bounds unconditionally — correct for horizontal cuts, but for a
vertical cut the slab bounds are Y values, so every cell received corrupted
spans (x-ranges derived from the y-slab, rows outside the slab, dead rows
owned). Fix: the two passes are now axis-aware — horizontal keeps the
previous x-interval intersection byte-for-byte; vertical assigns a row to
the slab WHOLE (`span.y ∈ [slab.start, slab.endExclusive)`, x interval
untouched). This matches `slabRect`'s existing vertical semantics and
`spansInNearestInterval`'s row projection. All 12 pre-existing planner tests
and every integration fixture are horizontal and pass unchanged. Without the
fix the new fixture cannot pass, and a vertical-partition cell's fitRegion
(slices 5–7 input) would be built from garbage spans — the fix is required
for this slice's contract, hence "genuinely unavoidable".

### TextLayoutPlannerMaskMetadataTest expectation changes (legitimate slice-5 effects)

1. 3-block full-rect fixture: span-mode + horizontal → now ADAPTIVE. The
   legacy per-cell font equality
   (`fontSizePx shouldBe binarySearchFontSize(text, cellW-8, cellH-8, ...)`)
   was replaced by the positioned-line contract assertions
   (`positionedLines != null`, `lines == positionedLine texts`,
   `hardClip == HardClip(ids, slab)`, exact per-line `layoutWidthPx`
   formula, placement inside the slab, `strokeWidth` formula). KEPT intact:
   shared geometry instance, ids, exact slab rects, pairwise disjointness,
   dead columns, per-block text map, and the strict cross-cell font ordering
   (`plan[2].font < plan[1].font` still holds — equal-width bands, longer
   atomic tokens bound the fit). Fonts MAY differ from the legacy rect fit
   (band width − shaping guards vs fitRegion − 8px padding); that is the
   sanctioned visibility gain.
2. All other fixtures pass unchanged (single-block mask, two-component,
   tie/no-metadata, empty-runs bounds-mode, >64 components, 34-mask and
   129-block caps — none are span-mode+horizontal optimized members).
`TextLayoutPlannerTest` and `PageLayoutPlanContractTest` pass UNCHANGED; no
budget-model contract assertion needed updating (the 512 static budget is
only reachable at 257+ horizontal blocks, far beyond every existing fixture).

### Intentional deviations / local decisions (all recorded here)

1. **`applyBestHyphenTrial` takes an extra `minFontPx` parameter.** The
   acceptance rule "removes overflow (baseline could not fit without
   overflow at min font)" is only decidable if the trial knows the floor;
   the injected fitter returns min font when nothing fits, so
   `baselineFont <= minFontPx` IS the overflow signal. Without the parameter
   the rule is unimplementable as specified.
2. **Vertical slab containment gate.** The task's "band rows ... clamped to
   span rows" reading alone would accept stacks extending beyond the slab
   (rows clamp back into the span range), letting the cell/slab clip erase
   ink invisibly. The planner additionally requires the whole line stack to
   lie inside the hard slab (±0.001 float slack) per alignment; a violation
   fails that alignment/font and falls through the ladder (smaller fonts,
   other alignments, legacy fallback). Conservative tightening in service of
   the architecture's containment invariants; the row-clamp itself is
   implemented exactly as specified for span lookups.
3. **`AdaptiveResult` carries `anchorX`/`anchorY`** (not in the task's data
   class sketch) because the contract requires "originX/originY = the
   alignment anchor". Alignments are vertical anchors; `originX` is the
   block OCR center X on every alignment.
4. **`layoutHeightPx = max(1, ceil(lineH))`** — the task defines
   `layoutWidthPx` exactly but leaves `layoutHeightPx` open; the ceil of the
   line height is the planner-side analogue of the StaticLayout height.
5. **Stats semantics**: `fontSteps` counts the main font binary-search
   evaluations for the whole fit (the trial's internal fit is a separate
   bounded search, not counted); `alignmentsTried`/`fixedPointPasses`
   describe the WINNING (font, alignment) attempt. Failed-font counters are
   not observable in a successful result; this is the only well-defined
   deterministic reading, and the crafted tests pin exact values.
6. **Trial candidate selection**: first eligible token in reading order;
   among qualifying candidates the largest fitted font wins, ties keep the
   earlier (fewer-insertion) candidate. The task's trial set is exactly
   {baseline, one break, two breaks} on one token, so the block insertion
   cap (≤ 2) is structural.
7. **3-letter minimum "shift"** is implemented as deterministic clamping of
   each split point into its feasible range (`[3, len-3]` one-break;
   `[3, len-6]` / `[p1+3, len-3]` two-break). For eligible tokens the
   balanced points never actually violate the minimum (len ≥ 8 ⇒ one-break
   valid; len ≥ 9 ⇒ two-break valid; len 8 has no two-break trial), so the
   shift path is pinned via the len-8 refusal test rather than an observable
   shifted split.
8. **Band-interval explosion guard** = 64 surviving intervals
   (`TextLayoutTuning.MAX_BAND_INTERVALS`), the task's "small guard, e.g. 64".
9. **Conservative occupancy inflation** = `stroke + AA_GUARD + gap/2` per
   side around the [leftPx, leftPx+advance] × [topPx, topPx+lineH] rect —
   the literal reading of "inflated by stroke, AA guard, and half collision
   gap"; un-clipped by construction.
10. **Empty positioned lines** (forced blank lines from source newlines,
    e.g. "A\n\nB") reserve their stack slot but build no StaticLayouts
    (nullable per-line pair in the renderer); a non-empty line that shapes
    to ≠ 1 line still drops the WHOLE prepared layout (fail closed). Without
    this, any translation containing a blank forced line would fail closed
    entirely.
11. **65th-distinct-pair cap coverage is a seam test, not an integration
    test.** Through `planPage` the cap is PROVABLY unreachable: distinct
    `(group,component)` pairs ≤ converted components page-wide ≤
    `maxComponentsPerPage` (64) == the assignment cap, and the conversion
    budget/session are hard-defaulted inside the planner (no injection
    point). The carried-over predicate was therefore extracted verbatim into
    `internal fun TextLayoutPlanner.componentAssignmentAllowed(assignment,
    assigned)` and the 65th-pair semantics (64th new pair passes, 65th new
    pair refused, existing pair passes at cap) are pinned directly. The
    member outcome of the capped path (Draw + slab `cellRect`, no geometry
    ids) is unchanged slice-3 code. Flagged for the reviewer: a true
    integration fixture would require an injectable component budget.
12. **Adaptive placement ignores obstacles** (no collision checks against
    sibling extents). The architecture assigns collision/final-safety
    validation to slice 7; obstacles for later blocks still use the
    adaptive layout's conservative occupancy union, and hard clips bound
    painted pixels structurally.
13. **Adaptive font bounds** are the floats `FIT_MIN_FONT_PX*scale ..
    FIT_MAX_FONT_PX*scale` (task wording); candidates are the integers
    `[ceil(min)..floor(max)]` mirroring `binarySearchFontSize`'s integer
    search.
14. **Instrumented one-line assertions**: since `setMaxLines(1)` makes a
    second line structurally impossible at bind (and `lineCount == 1` is
    verified), the pixel tests pin "no second line DRAWN" via ink-bottom
    bounds and pin alignment/containment exactly; a bind-drop test for a
    wrapping line is not constructible because the planner cannot produce
    one (maxLines truncates before the check could observe 2 lines) — the
    `lineCount == 1` check remains as the mandated fail-closed guard.

### Remaining risks for later slices

- Adaptive fonts/placements differ from the legacy rect fit on shared-mask
  pages (that is the feature); real-page visual behavior is unproven until
  the slice-8 device gate (instrumented pixel tests are compile-gated only,
  consistent with slices 1–3).
- The vertical-planner bug fix changes fitRegions for vertically-partitioned
  shared components (previously garbage spans fed the fit region); slice-8
  real-page diffs on vertically-split shared masks should expect the
  corrected (slab-bounded) regions.
- Conservative occupancy can exceed the hard slab (un-clipped by design), so
  two adaptive layouts on adjacent cells of one component may "overlap" in
  planning while remaining pixel-disjoint through their hard clips; slice 7
  owns the final disjoint-placement safety net.
- `positionedLines` layouts reserve 2 StaticLayouts per line in the planner,
  but a forced blank line builds none at bind — reservation is conservative,
  never under-counts.
