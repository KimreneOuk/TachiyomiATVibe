# T912 text-layout renderer architecture — revision 2

This revision is authoritative. It supersedes the original architecture and
resolves every HIGH and MEDIUM finding in `review/architecture-review.md`.

## Decision summary

Implement T912 as seven independently accepted slices plus the regression gate.
Recognition and persisted `TranslationBlock` data remain unchanged. Planning emits
exactly one explicit result for every nonblank input identity; rendering order is a
separate deterministic field. Valid masks are converted by a budgeted ordered
streaming path, shared components are divided into disjoint half-open cells, and
adaptive text is structurally clipped by both its component and cell. Planner
occupancy is deliberately conservative; only hard clips provide pixel containment.
Normal unmasked manga retains its legacy decisions and single-`StaticLayout` path,
except that ordinary Latin words no longer receive invented hyphens.

Neck refinement is **formally deferred** from the first production delivery. The
initial joined-lobe behavior is the deterministic midpoint/parent-biased partition
defined below. This already keeps every text identity independent and is the safe
fallback required by the Director. A later neck slice may replace a cut only after
its own deterministic corpus gate.

## Verified baseline and contradictions being repaired

- **VERIFIED:** labels are `0=bubble`, `1=text_bubble`, `2=text_free`
  (`OnnxPageTextDetector.kt:32-36`). Recognition attaches source direction and the
  first RLE mask containing the text-center point without changing block identity
  (`RoiPageRecognitionEngine.kt:299,374-397,480-503`).
- **VERIFIED:** `BubbleMaskRle` stores ordered row-major `(start,length)` runs;
  `decode()` allocates a dense page byte array and is forbidden in layout
  (`BubbleMaskRle.kt:7-40`).
- **VERIFIED:** current `MaskGeometry.fromSpans()` sorts, then allocates union-find,
  component lists, and coordinate strings (`MaskGeometry.kt:108-139,151-215`). Its
  per-mask 100,000-span guard does not bound component/key/path allocation.
- **CONTRADICTION:** `BlockLayout` declares mask metadata and the renderer can clip
  a component, but the planner never populates those fields
  (`TextLayoutPlanner.kt:79-94,471-484`; `PageTextRenderer.kt:37-66`).
- **CONTRADICTION:** the renderer currently treats component `clipPath` and
  `clipRect` as mutually exclusive (`PageTextRenderer.kt:76-89`). Independent
  shared cells require their intersection.
- **VERIFIED:** the planner invents hyphens for any oversized Latin token even
  though Android hyphenation is disabled (`TextLayoutPlanner.kt:1203-1261`;
  `PageTextRenderer.kt:216-222`).
- **VERIFIED:** target orientation is already
  `direction == "TTB" && CJK ratio > 0.5` (`TextLayoutPlanner.kt:188`;
  `PageTextRendererDirectionTest.kt:24-64`). Long Latin free text needs region
  widening, not a persisted direction or orientation change.
- **CONTRADICTION:** present collision extents omit stroke and are computed before
  final parent/mask/free-text re-anchoring; later branches can also clear the clip
  (`TextLayoutPlanner.kt:311-469,916-943`).
- **VERIFIED:** shared masks currently retain separate blocks but force the minimum
  sibling font (`TextLayoutPlanner.kt:950-1063`). That equalization is removed.
- **VERIFIED:** renderer allocation occurs in `bind()` and drawing consumes prepared
  objects (`PageTextRenderer.kt:37-76,112-176`). This lifecycle remains mandatory.
- **CONTRADICTION:** current Android instrumentation does not compile: it imports
  missing test dependencies, passes nonexistent `conservativeFootprint`, and calls
  nonexistent `resolveLayoutColors` (`PageTextRendererInstrumentedTest.kt:7-13,
  96-99,181-187`). Repairing this is slice 1, before production behavior edits.

## Exact planner contract

### Identity, cardinality, and ordering

Define nonblank input using the current chosen-text policy before score sorting.
For each nonblank input at original list index `i`, emit exactly one `LayoutResult`.
`blockId` is not sufficient identity because it is nullable and may be duplicated;
the audit identity is `(inputIndex, blockId)`.

```kotlin
data class InputIdentity(val inputIndex: Int, val blockId: String?)

enum class NonDrawReason {
    INVALID_OR_SUBPIXEL_SOURCE_RECT,
    EMPTY_SHARED_CELL,
    MASK_OR_WORK_BUDGET_EXHAUSTED,
    POSITIONED_LINE_BUDGET_EXHAUSTED,
    STATIC_LAYOUT_BUDGET_EXHAUSTED,
    NO_DISJOINT_POST_ANCHOR_PLACEMENT,
    INVALID_RENDER_METADATA,
}

sealed interface LayoutOutcome {
    data class Draw(val layout: BlockLayout) : LayoutOutcome
    data class NonDraw(val reason: NonDrawReason) : LayoutOutcome
}

data class LayoutResult(
    val identity: InputIdentity,
    val block: TranslationBlock,
    val chosenText: String,
    val planningOrdinal: Int, // score desc, then inputIndex
    val renderOrdinal: Int?,  // non-null only for Draw
    val outcome: LayoutOutcome,
)

data class PageLayoutPlan(
    val resultsInInputOrder: List<LayoutResult>,
    val drawableInRenderOrder: List<BlockLayout>,
)
```

`resultsInInputOrder.size` must equal the count of nonblank chosen texts, and its
identity multiset must match those inputs exactly. Score order is used only for
placement priority. `renderOrdinal` is assigned consecutively in accepted score
order and is the only list the renderer consumes. No result may contain sibling
text, substitute a sibling block, or concatenate strings. Blank inputs remain the
only intentional absence from this contract.

Cap/bad-geometry conditions normally return a drawable legacy rectangle result.
They return `NonDraw` only when the legacy source rectangle is invalid/subpixel, a
shared cell is empty, page-wide render-object budget is exhausted, or no disjoint
terminal placement exists. Thus no identity silently disappears.

### Drawable layout structures

```kotlin
data class PositionedLine(
    val text: String,
    val leftPx: Int,
    val topPx: Int,
    val layoutWidthPx: Int,
    val layoutHeightPx: Int,
    val conservativeOccupancy: FloatRect,
)

data class HardClip(
    val planGeometryId: Int?,
    val componentId: Int?,
    val cellRect: FloatRect?,
)

data class BlockLayout(
    // existing fields retained
    val positionedLines: List<PositionedLine>? = null,
    val conservativeOccupancy: List<FloatRect> = emptyList(),
    val hardClip: HardClip = HardClip(null, null, null),
)
```

`positionedLines == null` selects the exact legacy horizontal/vertical renderer.
Adaptive horizontal layouts use positioned lines. `conservativeOccupancy` is a
planning envelope, not exact glyph ink. `HardClip` is the structural render
boundary: component path then cell/safety rectangle. `planGeometryId` is a compact
integer valid only within one `PageLayoutPlan`; no coordinate string is used as a
cache key.

## Global budgets and candidate limits

These are hard guards, checked before the relevant allocation/work:

| Constant | Initial value |
|---|---:|
| `MAX_MASK_REFERENCES_PER_PAGE` | 128 |
| `MAX_UNIQUE_MASKS_PER_PAGE` | 32 |
| `MAX_RLE_INTS_SCANNED_PER_PAGE` | 200,000 |
| `MAX_DERIVED_SPANS_PER_MASK` | 100,000 |
| `MAX_DERIVED_SPANS_PER_PAGE` | 100,000 |
| `MAX_COMPONENTS_PER_MASK` | 64 |
| `MAX_COMPONENTS_PER_PAGE` | 64 |
| `MAX_COMPONENT_SWEEP_COMPARISONS` | 1,000,000/page |
| `MAX_DERIVED_CELL_SPANS_PER_PAGE` | 100,000 |
| `MAX_SHARED_BLOCKS_OPTIMIZED` | 8/component |
| `MAX_POSITIONED_LINES_PER_BLOCK` | 24 |
| `MAX_POSITIONED_LINES_PER_PAGE` | 256 |
| `MAX_STATIC_LAYOUTS_PER_PAGE` | 512 |
| `MAX_FONT_BINARY_STEPS` | 7 |
| `MAX_BAND_ALIGNMENTS` | 3 |
| `MAX_BAND_FIXED_POINT_PASSES` | 3 |
| `MAX_FINAL_PLACEMENT_ATTEMPTS` | 8/block |

One legacy horizontal block costs two `StaticLayout`s; one positioned line also
costs two. Budget is reserved deterministically in `renderOrdinal` order. If an
adaptive result would exceed either page-wide line/layout budget, retry its legacy
single-layout form (cost two). If even that exceeds the static-layout budget, emit
`NonDraw(STATIC_LAYOUT_BUDGET_EXHAUSTED)`. Vertical layouts do not consume a
`StaticLayout` but still consume one result and conservative occupancy.

## Slice 2: bounded ordered RLE conversion and clip wiring

### Mask grouping and compact identity

Walk block masks in input-index order. An identity cache first recognizes repeated
object references. For distinct instances, compute a bounded 64-bit streaming
fingerprint over dimensions, bounds, run count, and runs; use it only to select a
small bucket, then verify full field/run equality before declaring the masks equal.
Assign sequential `planGeometryId` values on first verified appearance. Hash
collision therefore cannot merge masks. Abort mask optimization before scanning a
reference that would exceed the reference/RLE-int/work budgets.

### Two-pass ordered conversion

Add `MaskGeometry.fromOrderedRle(...)`; do not route through sorting
`fromSpans()`.

1. **Pass A, no span/component objects:** validate nondecreasing runs, split lengths
   arithmetically at row boundaries, count derived spans, and accumulate projected
   sweep work. Stop immediately before any cap would be exceeded.
2. **Pass B, bounded primitive storage:** allocate arrays sized exactly to the
   accepted span count and fill `(y,start,end)` in row-major/start order. Build row
   offsets without sorting. Union adjacent-row overlaps with the existing
   two-pointer sweep while incrementing the page work counter.
3. Count union roots before creating any `Component`, component span list, key, or
   renderer `Path`. If per-mask/page component caps would be exceeded, discard the
   primitive arrays and return budget fallback.
4. Build bounded component span views/lists. Replace coordinate-string stable keys
   in the T912 path with `(planGeometryId, componentId)`. The renderer path cache
   uses that pair and verifies it refers to the same bound geometry instance.

The RLE run that crosses a row boundary is split into one span per affected row.
Empty masks, dimension mismatch, arithmetic overflow, ambiguity, or any cap yield
an explicit mask-fallback result; layout never calls `decode()` or allocates
`width*height`.

The planner populates component metadata only after exact non-tied overlap
assignment. The renderer applies `clipPath(component)` and then `clipRect(cell)`;
invalid non-null metadata fails closed as `NonDraw(INVALID_RENDER_METADATA)`.

## Slice 3: disjoint cells per shared component

Partition independently for each `(planGeometryId, componentId)` group. Blocks on
different components of the same RLE never share cuts.

### Stable ordering and cuts

Use block center in page coordinates; round integer scan centers with
`floor(value + 0.5)` and clamp to component bounds. Select horizontal partitioning
when X spread is greater than or equal to Y spread, otherwise vertical. Sort by
axis center, then orthogonal center, then input index.

For adjacent blocks A/B:

1. Base cut is integer floor midpoint of their axis centers.
2. If their valid parent boxes have facing, nonoverlapping edges in center order,
   bias the cut to the integer midpoint of those edges, clamped to the closed
   interval between the two centers. Overlapping/reversed parents do not directly
   own pixels and do not override the center midpoint.
3. Enforce monotonically increasing cuts. If equal centers/cuts make that
   impossible, replace all cuts with deterministic equal-width cuts across the
   component bounds in stable block order.

### Half-open ownership and dead zone

Let `gap = ceil(COLLISION_GAP)`, `gapBefore=floor(gap/2)`, and
`gapAfter=gap-gapBefore`. At cut `c`, the earlier cell ends at
`c-gapBefore` exclusive; the later cell begins at `c+gapAfter` inclusive. Pixels in
between are deliberately unowned. Outer cell bounds are component bounds.

Ownership is `component spans intersect half-open cell slab`. A source row span
crossing two cuts is lazily intersected into up to three cell segments; it is never
assigned whole to one cell. Derived segments are counted in a first pass and the
page cell-span cap is enforced before allocating cell lists. Tests must prove
pairwise-empty intersection and the dead-zone gap at every row/column.

Parent boxes bias cuts only. A hard cell remains the disjoint slab, optionally
using parent/OCR bounds as a preferred fit subregion inside it. Overlapping parents
therefore cannot create overlapping ownership.

### Explicit empty-cell fallback

If a component cell has no spans or no overlap with its own block:

1. Try `component ∩ assigned slab ∩ own OCR rectangle`.
2. If empty, try `component ∩ assigned slab` with the block-center-nearest
   continuous interval.
3. If still empty, emit `NonDraw(EMPTY_SHARED_CELL)` for that identity.

Never give the block the whole shared component, another block's cell, or another
block's text. When mask conversion itself falls back, use the same disjoint cut
logic on the RLE bounds rectangle; each result remains independent and collision
validation still runs.

Font fitting is per result. Remove `equalizeSharedMaskFonts()` entirely.

### Joined lobes

Initial T912 does not infer necks. Connected jointed masks use the deterministic
parent-biased/midpoint cells above. This is the formal ambiguous-topology fallback.
Optional neck refinement is a separate later slice and is not a prerequisite for
the first production integration; failure/uncertainty must return byte-identical
midpoint cuts.

## Line breaking contract

The line breaker is pure and deterministic:

1. Forced newline remains forced; whitespace separates; CJK graphemes remain
   breakable individually.
2. A source hyphen remains in displayed text and permits a break immediately after
   itself.
3. Ordinary/mixed/lowercase Latin tokens are atomic. If too wide, font fit or region
   fallback handles them; no hyphen is invented.
4. A render-only inserted-hyphen trial is eligible only for ASCII `[A-Z]{8,}`,
   minimum three letters per segment, no useful existing source hyphen, maximum two
   insertions per block, and no digit/URL-like token.
5. Trial set is exactly baseline, one balanced break, and (when length permits) two
   balanced breaks. Accept only if it removes overflow or improves final fitted
   font by at least 15%. Persisted translation is never mutated.

## Slice 5: adaptive bands and the Android shaping contract

Adaptive bands apply only to horizontal text with a valid independent component
cell. Unmasked ordinary blocks remain legacy.

For each font candidate, compute stroke and inset
`ceil(stroke/2 + AA_GUARD + visualPadding)`. A line band is valid only when one
continuous interval survives intersection across every covered component row and
the hard cell slab. Select the interval nearest the block/cell center, wrap against
that actual width, and validate the resulting rectangle with exact span
containment. Candidate search is bounded by seven font steps, three alignments
(OCR center, cell center, half-line toward larger free side), and three centering
fixed-point passes. More than 24 lines or failure to consume text falls back to the
stroke-inset rectangular cell layout.

### Same rounding and one-line shaping

Planner and renderer share these rules:

- line `leftPx` and `topPx` are integer `floor()` results;
- `layoutWidthPx = max(1, ceil(plannedAdvance + 2 * SHAPING_GUARD))`;
- planner accepts a line only if that integer width fits its hard band;
- text contains no newline and is prewrapped by the planner;
- renderer constructs exactly that width with
  `BREAK_STRATEGY_SIMPLE`, `HYPHENATION_FREQUENCY_NONE`, `includePad=false`, and
  `setMaxLines(1)`;
- renderer verifies `lineCount == 1` during `bind()`. Failure is
  `NonDraw(INVALID_RENDER_METADATA)`, never implicit second-line drawing.

`SHAPING_GUARD = ceil(stroke/2 + AA_GUARD)` is conservative, not an exact glyph
bound. Combining marks, emoji/ZWJ, italic overhang, and fractional transforms are
made structurally safe by component+cell/safety clips and verified by Android
pixel tests.

### Conservative occupancy versus structural clip

`conservativeOccupancy` is the un-clipped advance/line-height rectangle inflated by
stroke, AA guard, and half collision gap. It is never intersected into a fake
rectangle for a concave component. Collision planning treats any overlap of these
conservative rectangles as a possible collision.

`HardClip` is exact structural containment: component row spans become the Android
path, then the disjoint cell/safety rectangle is intersected on the Canvas. Only
these clips justify pixel-containment claims. For two layouts that do not have
provably disjoint hard cells, the final safety pass must select disjoint rectangular
hard clips or return non-draw; advance metrics alone never prove pixel separation.

## Slice 6: bounded long `text_free`

Do not change `TranslationBlock.direction` or the existing `isVertical` expression.
Eligibility is exactly:

- `label == 2`;
- no valid parent;
- resolved horizontal (`isVertical == false`);
- at least 24 non-whitespace graphemes;
- original OCR `height/width >= 2.0`;
- original OCR-box fit is below `minLegibleFont` or overflows at that font.

For eligible blocks, candidate 1 is always the **unreshaped original OCR box**.
Candidates 2/3 retain OCR center and height with widths:

```text
min(originalWidth * 1.25,
    originalWidth + 0.08 * pageShortSide,
    page-clamped width,
    collision-free width)

min(originalWidth * 1.50,
    originalWidth + 0.08 * pageShortSide,
    page-clamped width,
    collision-free width)
```

Deduplicate equal widths after clamping. Accept the smallest wider candidate that
removes overflow or improves fitted font by 15%; otherwise return the original OCR
baseline. No eligible path calls the legacy `1.5x..3.5x` reshape. Ineligible short
SFX retain the exact legacy path. Final collision validation runs only after the
OCR-center anchor has been applied.

## Slice 7: finite final post-anchor safety

Planning priority is score descending then input index. For each result, finalize
parent/mask/free-text anchor first, then evaluate at most eight candidates:

1. selected final geometry;
2. next smaller geometry candidate;
3. original/cell baseline geometry;
4. minimum legal left shift;
5. minimum legal right shift;
6. minimum legal up shift;
7. minimum legal down shift;
8. one hard disjoint clip/refit candidate.

Duplicate candidates after clamping are skipped but do not create replacements.
Each candidate performs at most seven font binary-search steps and must recompute,
in order: final anchor, wrap/positioned lines, stroke, conservative occupancy, hard
clip containment, and collisions. Shifts are calculated from inflated conservative
occupancies and constrained to page/component/cell. The hard-clip candidate
computes the four axis-aligned free rectangles around accepted occupancy, chooses
maximum area, then shortest displacement, then fixed order left/right/up/down; only
that one rectangle is fitted.

Only the currently colliding lower-priority block changes font/geometry; sibling
fonts are never equalized. Accepted occupancy remains conservative and un-clipped.
When hard cells are not already disjoint, candidate 8's rectangular clip supplies
the structural separation. If all finite candidates fail, emit
`NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)`. Identity/result cardinality remains
intact.

`COLLISION_GAP = clamp(0.001 * pageShortSide, 2*scale, 4*scale)`;
`AA_GUARD = max(1*scale, 0.5)`.

## Staged file/test plan and slice acceptance

Every slice must compile and pass its focused gate before the next starts.

### Slice 1 — instrumentation and characterization only

- Add correct AndroidX runner/JUnit `androidTest` dependencies.
- Remove/rewrite stale `conservativeFootprint` and `resolveLayoutColors` fixtures
  against current APIs; do not add production APIs just to satisfy stale tests.
- Gate: `:app:compileDevDebugAndroidTestKotlin` passes.
- Characterization tests: ordinary unmasked horizontal and vertical layout/render
  pixels/decisions; exact nonblank input identity multiset; deterministic score
  planning order distinct from input-order results.
- Replace the old invented-hyphen expectation with ordinary-Latin-no-insertion and
  source-hyphen-preserved expectations.

### Slice 2 — bounded geometry and intersecting clips

- Files: `BubbleMaskRle.kt`, `MaskGeometry.kt`, `TextLayoutPlanner.kt`,
  `PageTextRenderer.kt`.
- Tests: row-crossing run; ordered no-sort output; dimension/overflow/empty mask;
  cap stops before component/key/path creation; hash collision equality check;
  100,000 disconnected islands abort at component cap with measured wall time and
  peak/allocation evidence; compact `(planGeometryId,componentId)` cache;
  component path plus rectangle intersection.
- Acceptance: no dense page allocation, no coordinate-string key/path allocation
  before caps, and one explicit result per nonblank input on every fallback.

### Slice 3 — disjoint shared cells and independent fonts

- File: new pure `MaskTextRegionPlanner.kt` plus planner integration.
- Tests: one span crossing two cuts into three cells; pairwise pixel disjointness;
  exact half-gap dead zone; overlapping parents; equal centers; diagonal centers;
  different components of one mask; explicit empty-cell result; cap fallback;
  identity/text provenance; independent sibling fonts.
- Acceptance: three-block continuous-mask fixture has three results, three disjoint
  cells, no shared pixels, and no font equalization.

### Slice 4 — neck refinement decision

- Initial acceptance is formal deferral: midpoint output is golden and repeated-run
  deterministic for all joined fixtures.
- A future implementation requires a separate approved deterministic window/rounding/
  edge/work specification and must prove failure returns identical midpoint cells.

### Slice 5 — adaptive bands and Android shaping

- Files: new pure `TextLineBreaker.kt`, adaptive region helper, `BlockLayout`, and
  renderer preparation.
- JVM tests: thin/medium-small gain over conservative rectangle; concavity/hole;
  stroke inset; exact candidate counters; per-block/page 24/256 line limits; 512
  `StaticLayout` reservation fallback; deterministic ALL-CAPS gating.
- Android tests on API 26+: thick stroke, combining marks, emoji/ZWJ, punctuation
  overhang, source hyphen, atomic overwide word, left/center alignment, fractional
  translate/scale, and two simultaneous layouts. Assert one-line shaping, zero
  alpha outside component and cell, and the required alpha gap.
- Acceptance: `draw()` contains no new allocation/construction and rebind/clear
  cannot expose stale prepared objects.

### Slice 6 — long free text

- Tests: every eligibility gate; original OCR baseline; clamped/deduplicated 1.25
  and 1.50 trials; 8% page-add/page-edge/collision caps; no-gain rejection to OCR
  baseline; no eligible width above 1.50; unchanged direction/orientation; short
  free text byte-for-byte legacy decision compatibility.

### Slice 7 — final safety and explicit non-draw

- Tests: anchor changes after initial fit; thick-stroke collision; concave external
  masks; page edge; exact maximum-eight attempt counter; only lower-priority font
  changes; impossible space produces explicit non-draw; identity multiset remains
  exact; deterministic render ordinals.

### Slice 8 — regression gate

Run focused JVM suites, compile/run instrumentation with recorded device/API and
counts, all README-named T911 batch/download/drawer/progress suites, then the
narrowest practical module-wide target. Record exact commands, counts, failures,
errors, skipped tests, wall times, and relevant heap/allocation measurements.

## Failure/fallback matrix

| Condition | Deterministic outcome |
|---|---|
| No mask / invalid mask / geometry cap | Own legacy rectangular layout, then final safety |
| Shared mask but conversion cap | Disjoint rectangular bounds cells, or explicit empty-cell non-draw |
| Ambiguous component assignment | Own legacy rectangle; never another component |
| Ambiguous joined topology | Parent-biased/midpoint shared cell |
| Empty shared cell after both own-cell fallbacks | `NonDraw(EMPTY_SHARED_CELL)` |
| Adaptive band failure/line cap | Own stroke-inset rectangular cell layout |
| Page positioned-line cap | Own legacy single-layout form if static budget permits |
| Static-layout page cap | `NonDraw(STATIC_LAYOUT_BUDGET_EXHAUSTED)` |
| ALL-CAPS gain below 15% | Exact baseline text/wrap |
| Eligible free-text trial rejected | Unreshaped original OCR box |
| Final eight candidates unsafe | `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)` |
| Invalid non-null render metadata | `NonDraw(INVALID_RENDER_METADATA)` |

## Compatibility and acceptance invariants

1. Exactly one explicit result exists for each nonblank `(inputIndex,blockId)`;
   result order is input order and render order is separate.
2. Displayed text derives only from that result's block. No texts/blocks are merged,
   concatenated, substituted, or silently dropped.
3. Every shared-component pixel is owned by at most one cell; dead-zone pixels are
   owned by none; parents only bias disjoint cuts.
4. Each shared result fits its font independently.
5. Planner occupancy is conservative; component+cell/safety clips are the only
   structural pixel-containment claim.
6. Positioned lines use exactly the planner's integer width and render as exactly
   one `StaticLayout` line.
7. Eligible long free text uses only OCR, 1.25x, and 1.50x bounded candidates and
   returns to OCR on rejection.
8. Collision validation occurs after every final anchor and uses at most eight
   finite attempts.
9. Ordinary unmasked/noneligible manga takes the legacy decision/render path,
   except ordinary Latin invented hyphenation is removed.
10. All span/component/cell/work/positioned-line/`StaticLayout` budgets are checked
    before their corresponding allocations, and `PageTextRenderer.draw()` remains
    allocation-free.
