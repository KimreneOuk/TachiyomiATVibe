# T912 architecture review

## Verdict

**CHANGES REQUIRED before production edits.** The proposed direction is viable: mask
geometry can remain pure/JVM-testable, Android 8 supports the existing
`StaticLayout.Builder` and intersecting `Canvas` clips, and preparing all shaped
objects in `bind()` can preserve an allocation-free `draw()`. However, the current
plan does not yet prove the strict block-identity/cardinality contract, disjoint
shared-cell ownership, bounded worst-case memory, or pixel-level stroke-aware
non-overlap. The Android instrumentation source also does not compile on the
current branch.

## Verified baseline

- **VERIFIED:** production returns layouts in score-stable order and already keeps
  each nonblank block as a separate `BlockLayout`; it drops blank blocks and also
  drops blocks whose computed safe region is below one pixel
  (`TextLayoutPlanner.kt:153-206`). `BlockLayout` retains the source
  `TranslationBlock`, including `blockId` (`TextLayoutPlanner.kt:79-95`;
  `PageTranslation.kt:277-300`).
- **VERIFIED:** current shared-mask code partitions only rectangular bounds and
  then equalizes every sibling to the smallest font
  (`TextLayoutPlanner.kt:945-1062`). The proposed removal of equalization is
  necessary for independent maximum fit.
- **VERIFIED:** `MaskGeometry.fromSpans()` sorts the input, builds all connected
  components, and materializes text keys from span coordinates
  (`MaskGeometry.kt:108-139,151-215`). The present 100,000-span limit is not a
  component, key-memory, path-memory, or CPU-work limit.
- **VERIFIED:** the renderer already creates `Path` and `StaticLayout` objects in
  `bind()` and only performs canvas operations/draws in `draw()`
  (`PageTextRenderer.kt:37-68,76-90,117-162`). Applying a component path and a
  rectangle sequentially is feasible on Android 8; the current code incorrectly
  makes them mutually exclusive (`PageTextRenderer.kt:81-85`).
- **VERIFIED:** planner measurement exposes advance width and font-metric line
  height only (`PageTextRenderer.kt:22-30`), while actual rendering is delegated
  to `StaticLayout` (`PageTextRenderer.kt:137-145,216-222`). Those two measurements
  are not an exact glyph-ink footprint contract.

## Blocking findings

### 1. HIGH — One-layout-per-input and fail-closed omission contradict each other

- **Likelihood:** high on crowded pages or degenerate OCR boxes.
- **Classification:** design defect.
- **Evidence:** **CONTRADICTION.** The report says planning returns exactly one
  layout for every drawable nonblank block, but its terminal collision fallback
  omits the draw layout. Production also currently skips sub-pixel safe boxes
  (`TextLayoutPlanner.kt:182-186`). This makes cardinality loss indistinguishable
  from filtering and weakens the Director's “never merge” audit trail.
- **Required change:** define the contract as exactly one result per nonblank input
  identity. If a terminal case must not draw, represent it explicitly (for example,
  a non-drawable layout/result with a bounded reason) rather than removing it from
  the plan. Never concatenate strings or substitute a sibling block. Preserve
  `blockId`/input index through score-order planning and expose deterministic
  render order separately.
- **Confirm/refute:** tests must compare input and output identity multisets for
  ordinary, three-block shared-mask, empty-cell, cap-fallback, and impossible-space
  cases; each result's displayed text must derive only from its own block.

### 2. HIGH — Shared-cell ownership is not yet disjoint or geometrically correct

- **Likelihood:** high for the exact connected three-bubble example.
- **Classification:** algorithm defect.
- **Evidence:** **CONTRADICTION.** The report says every source span belongs to at
  most one cell. A horizontal row span that crosses two vertical cuts must be
  split into up to three half-open segments if all three cells are to retain their
  pixels; assigning the whole span once either discards valid mask area or gives a
  cell pixels across a boundary. Separately, choosing `component intersection
  parent box` does not make cells disjoint when distinct parent boxes overlap.
  Current parent rectangles can be arbitrary floats (`PageTranslation.kt:291-294`),
  and current rectangular partitioning does not reserve a gap
  (`TextLayoutPlanner.kt:969-1028`).
- **Required change:** partition **per shared component**, not merely per equal RLE.
  Define ownership per pixel/interval: split or lazily clip a source span at every
  half-open cut, and prove each output pixel belongs to at most one cell. Parent
  boxes may bias cut placement but must still be intersected with a deterministic
  disjoint partition. Erode adjacent cell boundaries by half the scaled collision
  gap (or reserve an equivalent dead zone), otherwise touching clips satisfy
  non-overlap but not the required gap. Empty cells must retain their own explicit
  result and safe fallback; they must never receive the whole shared component.
- **Confirm/refute:** row-level ownership tests should union three derived cells,
  assert no pairwise pixel intersection, assert the intended gap, and cover one
  long source span crossing all cuts, overlapping parents, equal centers, diagonal
  centers, and blocks assigned to different components of the same mask.

### 3. HIGH — The proposed geometry/cache limits can be exceeded before fallback

- **Likelihood:** medium for noisy model masks; high for corrupted or highly
  fragmented persisted masks.
- **Classification:** resource-bound defect.
- **Evidence:** **VERIFIED.** `fromSpans()` sorts even already row-ordered spans
  (`MaskGeometry.kt:108-112`), allocates union-find arrays, then can allocate one
  `Component`, component span list, and string key per disconnected span before any
  proposed 64-component renderer cap is checked (`MaskGeometry.kt:151-215`). A
  100,000 one-pixel-island mask therefore permits roughly 100,000 components.
  Geometry and component `stableKey`s stringify coordinates
  (`MaskGeometry.kt:16,205,213-215`), and the renderer embeds that string in another
  cache key (`PageTextRenderer.kt:51-53`). Existing stress coverage reaches only
  20,000 spans and 200 components (`MaskGeometryStressTest.kt:27-79`). Grouping by
  `BubbleMaskRle` structural equality also hashes/compares large `runs` lists
  (`BubbleMaskRle.kt:7-12`; `TextLayoutPlanner.kt:950-956`).
- **Required change:** enforce span, component, derived-cell-span, and work budgets
  while converting/building, before component objects/keys/paths are materialized.
  Provide an ordered RLE conversion path that does not sort again. Replace
  coordinate strings with a compact per-plan numeric identity/hash plus equality
  verification; the renderer cache can key by plan geometry identity and component
  id. Add a page-wide adaptive-output budget (total positioned lines and therefore
  total fill/stroke `StaticLayout`s), not only 24 lines per block. Exceeding any
  budget must produce the explicit legacy/non-draw result from Finding 1.
- **Confirm/refute:** measured stress tests at the actual 100,000-span ceiling must
  include 100,000 disconnected islands, maximum shared groups, many eligible
  blocks, and report wall time plus allocation/heap bounds. Structural assertions
  alone do not establish the requested performance guarantee.

### 4. HIGH — Pure advance metrics cannot prove the advertised pixel footprint

- **Likelihood:** medium; higher for combining marks, emoji, italic/overhanging
  glyphs, thick outlines, and fractional canvas transforms.
- **Classification:** design limitation.
- **Evidence:** **VERIFIED.** the planner sees only `measureText()` advance width and
  `descent-ascent` line height (`PageTextRenderer.kt:22-30`), but `StaticLayout`
  performs the final shaping (`PageTextRenderer.kt:137-145,216-222`). The proposed
  `PositionedLine.strokeFootprint` therefore cannot by itself be an exact ink bound.
  Also, a per-line `StaticLayout` can wrap an atomic overwide token again unless the
  one-line contract and width rounding are made explicit.
- **Required change:** distinguish conservative planner occupancy from structural
  pixel containment. Each adaptive prepared line must be constrained to one line,
  use the same paint/width rounding as planning, and be clipped by both its hard
  cell rectangle and component path. For layouts whose no-overlap guarantee cannot
  be made structural with disjoint clips, obtain conservative ink bounds at bind
  time (including stroke/AA) or apply a hard safety clip selected by the planner.
  Do not claim exact pixel disjointness from advance rectangles alone. Preserve the
  exact legacy renderer only for layouts proven not to need a safety clip.
- **Confirm/refute:** Android pixel tests must include thick stroke, combining
  marks, emoji/ZWJ, punctuation overhang, source hyphens, an atomic overwide word,
  both alignments, fractional translate/scale, and simultaneous rendering of two
  layouts. Assert zero pixels outside component **and** cell, and the scaled gap
  between final alpha footprints.

### 5. HIGH — Free-text baseline and fallback are ambiguous and can re-enable 3.5x widening

- **Likelihood:** high for eligible long `label == 2` blocks.
- **Classification:** specification defect.
- **Evidence:** **CONTRADICTION.** production reshapes every tall parentless block
  unconditionally to `1.5x..3.5x` (`TextLayoutPlanner.kt:1177-1190`). The report
  says the bounded `1.25x/1.50x` path supersedes that behavior for eligible free
  text, but also says a rejected trial preserves the “original legacy layout.”
  Falling back to legacy `computeRects()` would restore the prohibited wider
  reshape. Production later re-anchors all label-2 text to the OCR center
  (`TextLayoutPlanner.kt:465-469`), so collision acceptance must occur after that
  anchor.
- **Required change:** state unambiguously that an eligible long `text_free`
  baseline is the original OCR rectangle; its only widening candidates are
  `min(originalWidth * factor, originalWidth + 8% pageShortSide, collision-free
  width, page width)`, deduplicated after clamping. A rejected trial falls back to
  that unreshaped OCR baseline, not the old 3.5x path. Re-run final overlap after
  OCR-center anchoring. Ineligible short SFX remain on the current legacy path.
- **Confirm/refute:** tests must demonstrate no eligible output exceeds the 1.50x
  or page-add cap, including rejection/no-gain and edge-clamped cases; assert short
  free text is byte-for-byte/layout-decision compatible.

### 6. HIGH — The required instrumentation gate is currently uncompilable

- **Likelihood:** certain.
- **Classification:** existing test-infrastructure defect.
- **Evidence:** **VERIFIED by command.** With the Android Studio JBR,
  `gradlew.bat :app:compileDevDebugAndroidTestKotlin` reaches Kotlin compilation and
  fails. `app/build.gradle.kts:364-370` has JVM test dependencies but no AndroidX
  test/JUnit androidTest dependencies. The fixture imports those APIs
  (`PageTextRendererInstrumentedTest.kt:7-13`), passes nonexistent
  `conservativeFootprint` arguments (`:96,186`), and calls nonexistent
  `RenderColorEstimator.resolveLayoutColors` (`:99`; production exposes
  `recomputeFor` at `RenderColorEstimator.kt:288`). The compile output confirmed
  all three failure classes.
- **Required change:** make the existing instrumentation source compile before
  extending it: add the correct androidTest runner/JUnit dependencies and remove
  or rewrite the reverted placement-aware color fixture against current production
  APIs. Do not add a field/API solely to satisfy stale tests unless T912 actually
  consumes it.
- **Confirm/refute:** `:app:compileDevDebugAndroidTestKotlin` must pass before any
  device test result is accepted; then run the requested instrumentation on an
  API-26-or-newer device/emulator and record device/API plus counts.

## Additional required clarifications

### MEDIUM — Final collision representation and retry bounds are underspecified

- **Likelihood:** medium.
- **Classification:** design limitation.
- **Evidence:** **STRONG INFERENCE.** Intersecting a rectangle with a concave
  component generally produces multiple non-rectangular fragments, so
  `occupiedRects: List<FloatRect>` cannot literally represent “intersect occupied
  rectangles with the effective component.” The proposed minimal shifts and
  “next-smaller width/font” also lack a fixed candidate count. Current collision
  extents omit stroke and are calculated before later re-anchoring
  (`TextLayoutPlanner.kt:916-943,424-469`).
- **Required change:** use un-clipped stroke/AA/gap-inflated rectangles as a
  conservative collision shape, or define a bounded row-span intersection type;
  do not approximate a concave intersection as one smaller rectangle. Specify the
  exact finite retry set and ensure every retry recomputes final anchor, clip,
  lines, occupied shape, and containment. Independent shared-cell fonts must never
  be equalized; only the colliding block may step down.
- **Confirm/refute:** adversarial concave/two-mask overlap tests plus a counter that
  asserts the maximum retry count.

### MEDIUM — Neck refinement inputs are not defined enough to be deterministic

- **Likelihood:** medium.
- **Classification:** specification limitation.
- **Evidence:** **ASSUMPTION.** “Median occupancy around the two centers” has no
  window width, boundary behavior, or definition when a center falls outside the
  component. Those choices can change whether a lobe is called confident.
- **Required change:** define integer scan coordinates, center rounding, median
  windows, edge handling, OCR-rectangle crossing semantics, and operation budget.
  Midpoint partition remains the only fallback; topology uncertainty must never
  alter block identity or concatenate text.
- **Confirm/refute:** deterministic golden section tests for even/odd dimensions,
  equal minima, centers outside mask, OCR box crossing, noise, and repeated runs.

## Required staged delivery

The work should be split more narrowly than the report's current Phase 3. Each
slice must compile and pass its focused tests before the next begins:

1. **Baseline/test repair:** compile androidTest; add ordinary-layout and exact
   input/result identity characterization; replace the invented-hyphen expectation.
2. **Bounded geometry:** streaming RLE adapter, early span/component/work caps,
   compact per-plan identities, component assignment, and renderer clip
   intersection. No adaptive lines yet.
3. **Disjoint shared cells:** per-component pixel ownership, half-gap boundaries,
   midpoint fallback, then independent fonts. Add the exact three-block continuous
   mask fixture before neck refinement.
4. **Optional neck refinement:** independently test the confidence rule and prove
   that failure returns the same midpoint cells.
5. **Adaptive bands + Android shaping:** positioned-line model, aggregate output
   budget, exact clip behavior, and device pixel tests.
6. **Long free text:** original-OCR baseline and only the bounded 1.25x/1.50x trials.
7. **Final post-anchor safety:** finite retries, explicit non-draw outcomes, and
   adversarial collision tests.
8. **Regression gate:** focused JVM and instrumentation suites, all named T911
   batch/download/drawer/progress suites, then the narrowest module-wide target.

The implementation review should reject integration if any slice silently drops
an input identity, allows sibling cells to overlap or share pixels, constructs
geometry beyond a cap before fallback, creates unbounded per-page `StaticLayout`s,
or claims pixel-level no-overlap solely from `measureText()` extents.
