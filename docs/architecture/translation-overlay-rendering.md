# Translation overlay rendering

This document describes how translated text is laid out and drawn over the
reader's cleaned page image when bubble segmentation masks are available. It
covers the split between the pure layout planner and the Android renderer, the
sparse-mask invariants the split enforces, and the failure policy that keeps
the overlay safe when a mask cannot be honored.

The live source of truth is the code under
`app/src/main/java/eu/kanade/translation/rendering/` and
`app/src/main/java/eu/kanade/translation/segmentation/`. This document
describes verified behavior; where a property is only checkable on a device it
is explicitly listed as unverified in the last section.

## Planner vs renderer responsibilities

Text rendering is split into a pure, side-effect-free planner that decides
*where* and *how large* each block's text is, and an Android renderer that only
*draws* those decisions. The planner contains no `Bitmap`, `Canvas`, `Paint`,
or other `android.graphics` dependency, which keeps it unit-testable on the JVM
with a deterministic fake measurement; the renderer holds the platform
`Paint`/`Canvas` I/O and performs no further layout.

- `TextLayoutPlanner` (`rendering/TextLayoutPlanner.kt`) is the page-level entry
  point. For each block it chooses the rendered text, resolves a sparse
  `MaskGeometry` from the persisted `BubbleMaskRle` segmentation mask, routes
  mask-bearing blocks to `MaskedTextLayoutSolver`, runs maskless/invalid blocks
  through the unchanged legacy placement path, and merges results back in stable
  input order. It also owns the legibility floor, the stroke-width formula, CJK
  ratio / vertical detection, and grapheme-safe wrapping.
- `MaskedTextLayoutSolver` (`rendering/MaskedTextLayoutSolver.kt`) is the
  bounded deterministic solver for text whose complete ink must remain inside a
  sparse mask. It takes immutable `MaskedTextInput` snapshots plus a
  `TextMeasurer` and returns `MaskedTextLayoutResult.Placed` or
  `MaskedTextLayoutResult.Failed` per input.
- `TranslationOverlayView` (`ui/reader/viewer/TranslationOverlayView.kt`) is the
  Android-side drawer. It binds resolved `BlockLayout`s, builds the exact clip
  paths, and draws horizontal, vertical, and positioned text with
  stroke-then-fill. It performs no layout decisions.
- `MaskGeometry` (`segmentation/MaskGeometry.kt`) is the immutable sparse
  geometry shared by both sides: spans, 4-connected components, exact
  point/rectangle containment, and rectangle-to-component assignment.

Measurement is injected through the `TextMeasurer` interface so the planner
never touches `android.graphics.Paint`. Tests pass a deterministic fake; the
renderer supplies the real `Paint`-backed implementation.

## Validated-mask smart layout

A block enters the smart-layout path only when its persisted mask is present and
its dimensions exactly match the page being rendered
(`mask.width == pageWidth.toInt() && mask.height == pageHeight.toInt()`).
`TextLayoutPlanner.planDetailed` resolves each block's mask through a
per-page `geometryCache` so masks with equal RLE convert to `MaskGeometry` once
as the source geometry. Conversion traverses the persisted runs a single time; if the
sparse conversion would exceed `MaskGeometry.MAX_SPANS` (100 000) the block
fails closed as `MASK_GEOMETRY_INVALID` rather than entering legacy rendering.

For each valid masked block the planner snapshots the render-relevant input
into `MaskedTextInput`: input index, source coordinates, score, selected text,
resolved text color, direction, its owned sparse geometry, a stable mask key
(`"${width}x${height}:${geometry.stableKey}"`), and the assigned component id.
Snapshots are immutable; mutating the source `TranslationBlock` after planning
cannot change the solver's output. The planner maps placements back to source
blocks only at its API boundary.

Absent or dimension-mismatched masks keep the approved legacy placement: the
block is rendered through the pre-existing midpoint-slicing / equalization
path, unchanged. Valid masks never fall back to legacy.

## Assigned-component clip with bounded edge tolerance

Each OCR owner is first reduced to the one connected mask component overlapping
its source box. That component is then dilated by exactly two source pixels,
clamped to the page and, for a shared mask, to the owner's midpoint partition.
The hard pixel invariant is: rendered ink never appears outside that locally
assigned, two-pixel-tolerant geometry. This is enforced in two layers:

1. The solver only emits a placement whose conservative footprint (text extent
   plus half stroke plus anti-alias inset) is contained inside the input's
   sparse geometry, verified through `MaskGeometry.containsRectangle` and, when
   a component is assigned, `componentForRectangle`. The footprint is
   conservative search/collision geometry — it bounds the candidate, but the
   renderer's clip is the exact pixel invariant.
2. The renderer builds one source-space row-span `Path` per stable assigned
   component during `bind` and clips to it before both stroke and fill. The
   clip is the exact expanded assigned component, so disconnected components, concavities,
   holes, thick outlines, and transformed anti-aliasing cannot emit text
   outside the allowed geometry. Disconnected components are never unioned:
   a block assigned to component N is clipped to component N only.

The clip path cache is bounded (64 components, 100 000 row spans) and cleared on
`bind`, `clear`, and `detach`. A dimension/component mismatch or cache
exhaustion fails closed — the affected layout is not drawn — rather than drawing
without the clip.

## Shared-mask OCR ownership

The segmentation model is bubble-level while OCR boxes are text-block-level, so
several independent OCR boxes may legitimately reference the same persisted
mask. This relationship does not create a shared layout canvas. When a mask has
multiple renderable OCR owners, the planner partitions its bounds at pixel-aligned
midpoints along the owners' dominant center axis and intersects the sparse mask
with each region. Every owner receives a different `MaskGeometry` and stable key.
A single owner may use its complete assigned connected component, plus the two
pixel tolerance; it does not inherit other disconnected mask components.

Each block may enlarge beyond its tight OCR rectangle only inside its owned
mask partition. Candidate generation, containment checks, and the renderer's
exact clip all use that owned geometry, so one block cannot consume a sibling's
space or visually merge with it. Blank translations do not reserve a partition.
Blocks retain independent font selection, text payload, input identity, and
input order.

The solver derives each input's complete reachable bounds from its assigned
component, finds overlapping collision islands with a left-to-right sweep, and
runs one bounded beam per island. Page-distant domains therefore never share a
beam, font/movement ranking, or obstacle scan. Adjacent or overlapping domains
remain coordinated: collision-free states rank ahead of overlapping states,
and each font preserves its lowest-overlap candidates before movement.

Sibling overlap is measured as the deterministic geometric union of obstacle
intersections clipped to the candidate, normalized by candidate area, using an
x-sweep so overlapping frozen/sibling rectangles never double-count coverage.
Controlled overlap is capped at `MAX_ACCEPTABLE_OVERLAP_RATIO` (0.20 of
candidate ink area) at every font, including the below-readable technical
fallback. Candidates above the cap are rejected so the explicit failure branch
wins rather than rendering heavily obscured text.

## Distinct-mask coordination

Blocks with distinct masks or distinct owned partitions are coordinated only
when their reachable component bounds overlap. Their geometries are never
unioned. Adjacent/overlapping masks avoid colliding when space allows; distant
bubbles cannot alter one another's selected candidate. Containment is always
evaluated against the block's own two-pixel-tolerant geometry. Legacy maskless
placements are frozen obstacles only for islands whose reachable bounds can
intersect those placements.

## Failure policy

The solver is bounded by fixed rectangle, font-step, candidate, and beam
ceilings with no wall-clock cutoff. One block examines at most 16 open
rectangles and 16 sampled font sizes, retains at most 16 candidates per sampled
font and 256 generated candidates overall, prunes each block to 32 candidates,
and retains 16 beam states. When a block has no viable complete placement, the solver
keeps an explicit omission branch so later blocks in the same beam state can
still be evaluated. A block that cannot be placed fails explicitly with
`MaskedTextLayoutFailure.NO_COMPLETE_FIT`. A mask whose sparse conversion is
invalid fails with `MASK_GEOMETRY_INVALID`. Both carry stable input index and
source block identity up to `planDetailed`, which surfaces them in
`TextLayoutPlan.failures` sorted by input index.

Valid masks never enter legacy rendering: a `MASK_GEOMETRY_INVALID` block is
omitted from output, not silently rerouted through the legacy path. The
candidate search descends from the geometry-derived font ceiling to the
readable floor, and then to a 2 px technical minimum, before explicit omission.
`MaskedTextSolveResult` also reports `materializedCandidateCount` (candidates
actually generated, summed once per input) separately from `candidateBudget`
(`inputs.size * MAX_GENERATED_CANDIDATES_PER_BLOCK`) so callers can observe
bounded generation.

## Placement-aware color

Text color is resolved after placement planning so immutable resolved color
reaches the renderer. `RenderColorEstimator` samples the cleaned bitmap under
the *placed* footprint (footprint ∩ assigned component) for masked layouts via
`resolveLayoutColors` / `recomputeForPlacedLayouts`, rather than under the OCR
source rectangle. The planner snapshots `textColor` onto the `MaskedTextInput`
and the resulting `MaskedTextLayout`/`BlockLayout` after placement, so the
overlay color no longer reads mutable source state at draw time.

Sampling is bounded by a positive sample cap (`MAX_LAYOUT_COLOR_SAMPLES = 64`)
from which a division-safe stride is derived; the masked-branch sampling cannot
divide by zero or over-allocate. Unmasked layouts keep the legacy
`decideTextFill` behavior on their OCR-rectangle pixels and are stable
regardless of mask state. Cleaned bitmaps are available before persistence and
`recomputeFor` already runs against them in every active pipeline
completion/resume path, so no overlay bitmap callback or fake sampling path was
added. Polarity is deliberately binary: light sampled backgrounds use pure
black ink, dark sampled backgrounds use pure white ink, with the inverse-pole
outline.

## Unicode and vertical handling

Horizontal rendering uses Android `StaticLayout` with the planner's physical
alignment and precomputed line breaks, preserving the platform's bidi, Indic,
and Thai shaping. Vertical iteration is extended-grapheme-safe and keeps
right-to-left columns; the planner applies legacy vertical punctuation
substitutions.

The planner's pure Unicode handling is JVM-testable and pinned by stress
coverage:

- CJK detection counts code points, not UTF-16 code units, so supplementary Han
  (U+20000..U+2A6DF, encoded as surrogate pairs) is recognized as CJK and
  correctly triggers vertical layout when it is the majority of the text.
- Grapheme clustering keeps supplementary Han, emoji ZWJ sequences (family,
  skin tone), regional indicator pairs (flags), and combining marks atomic; it
  never splits them.
- `cjkWrap` never drops content: CJK remains breakable by grapheme, while a
  Latin/non-CJK word remains atomic even when wider than the requested wrap
  width. Candidate width and Android `StaticLayout` width are raised to the
  measured atomic line width, preventing the platform from splitting the word
  again. ZWSP (U+200B) is an explicit break opportunity;
  ZWJ (U+200D) never breaks a sequence.

Advanced per-glyph Latin rotation in vertical mode was intentionally deferred
rather than changing established output without a reliable instrumentation
oracle.

## Lifecycle, cache, transform, and dimension fail-closed

The renderer's lifecycle is bind → draw → clear → detach. Planning is
dispatched from `bind` to `Dispatchers.Default` with an isolated measurer, so a
mask-heavy page cannot block the reader's main thread. Each bind has a
generation token; clear, rebind, or detach cancels the active job and invalidates
that holder's await and any result that was already posted. Planning itself is
owned by a reader-session cache, so recycling a page holder does not discard a
completed or in-flight plan that a nearby page will need again. Cache keys include
chapter/page identity, translation generation/version/timestamp, page dimensions,
and the layout policy revision; a translation edit or replacement therefore cannot
reuse a stale plan. The cache retains at most nine least-recently-used pages, also
caps retained sparse geometry at 300 000 mask row spans, and runs at most two
planners concurrently. Closing or replacing the viewer clears the cache. It retains
the prepared renderer's bounded `Path` and `StaticLayout` data so scroll-back does
not rebuild them, but never retains a page bitmap, holder, overlay `View`, or
`Canvas`.

The exact clip path cache remains renderer-local. It is built when the current
planning result is accepted and cleared on `bind`, `clear`, and `detach`.
Zoom/pan and canvas transforms only invalidate the redraw and never replan —
the planner's layouts are source-space and stable; only the renderer's
transform maps them to view space.

Fail-closed boundaries:

- A persisted mask whose dimensions do not match the page uses legacy
  rendering (the pre-existing, approved path), not the smart layout.
- A persisted mask whose sparse conversion exceeds limits fails closed as
  `MASK_GEOMETRY_INVALID` and is omitted; it never escapes to legacy.
- Clip cache exhaustion (more than 64 components or 100 000 row spans) fails
  closed for the affected layout.
- Transform validation, frame coalescing, and detach live in the overlay view;
  the renderer never draws after detach.

## JVM vs instrumentation test boundary

Pure planner, solver, and geometry behavior is covered by JVM unit tests
(`:app:testStandardDebugUnitTest`) using deterministic fakes — no
`android.graphics`, no Robolectric. This covers the planner/solver/geometry
math, the Unicode grapheme/wrapping helpers, candidate budgets, determinism,
shared/distinct-mask coordination, and the failure policy.

The exact-pixel clip invariant, direct `Canvas` drawing, `Paint` measurement,
and the Bitmap+Canvas end-to-end path are Android-bound and live in
`app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewInstrumentedTest.kt`.
These compile against the instrumentation source set
(`:app:assembleStandardDebugAndroidTest`) but require a connected device or
AVD to execute.

## Unverified items (instrumentation; device not available)

The following are compiled but not executed in this environment because no ADB
device or AVD is connected (`android_preflight` reports 0 devices and 0 AVDs):

- `TranslationOverlayViewInstrumentedTest` end-to-end cases: concave mask hole
  and thick stroke zero-alpha outside the assigned component; disconnected mask
  clipping to the assigned component only; metadata fallback; fractional canvas
  transform preserving zero source pixels outside the mask;
  rapid rebind/clear; mixed-script horizontal shaping payload; vertical
  graphemes; unmasked drawing; and the moved-footprint cleaned-pixel sampling
  case.
- The Bitmap+Canvas confirmation that a moved footprint samples the cleaned
  bitmap under `footprint ∩ assigned component` with an immutable color on the
  layout and no source mutation.
- Advanced per-glyph Latin rotation in vertical mode (deferred).

These are reported as unverified, not as passing. Follow-up is to run
`:app:connectedStandardDebugAndroidTest` (or the equivalent variant) on a
connected device or started emulator.
