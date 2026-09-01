# T912 contained-fit rescue — implementation report

Branch `codex/text-layout-renderer`, base HEAD `b994bc6`, worktree
`t912-investigation-wt`. One commit. No rebase, no push.

## Result

The masked collision tail no longer clips text to make it fit. When a masked
block enters the post-anchor resolver, the planner now computes an
**OCR-box-first tiered contained reflow** — the exact model validated with the
Director on the live laptop rig over the real page-15 detection data
(`visible-fit-strategy.md`, Revision 2, iterations 1–10):

- the text column keeps the OCR bounding box's x-range; tiers grow it
  VERTICALLY only (1.0×/1.25×/1.5×/2.0×, then the cell's own content region
  for blocks with a real assigned component cell);
- the font never exceeds the block's **natural OCR-box fit** (recovery target
  for blocks the old plan crushed), and every tier is evaluated with the
  **largest fully-contained font winning** (ties keep the more local tier);
- containment is the EXACT painted-envelope predicate: measured advance +
  measured line height (the old `layoutWidthPx` double-inflation is fixed),
  inflated by `stroke/2 + AA guard`, validated per integer row against the
  component's real row spans;
- the rescue replaces masked candidate 1 (the provably wasted re-validation,
  slice-7 review NOTE 3); if it is collision-free it wins immediately;
- when every capped candidate also fails, the rescue is returned as the
  terminal result WITH overlap — contained and legible beats exile, shrinking,
  or clipping. No new `clipRect` on this path: the component/cell painter
  clips can only remove an AA fringe of an exact-contained layout;
- if no tier contains the complete text at the render floor, the tail **fails
  open**: the unshifted resolver-entry draw with `maskUsable = false`, so
  metadata wiring attaches NO exact component clip. Visibility wins over a
  mask that cannot host the text; the own-ink clip keeps only the page bound.

Unmasked behavior is untouched (all gated on `segmentationMask != null`), and
non-colliding masked blocks never reach the resolver, never pay for the rescue
(Phase A preserved; `finalPlacementAttempts == 0` pinned by test).

Kept from the rejected clip-first prototype: the masked-only shift caps
(0.25 region / 0.04 page), the candidate-8 refit cap, the production overlay's
bind-time component-path cache and the common
`component path → cellRect → clipRect` envelope for positioned, legacy
horizontal, and legacy vertical layouts, and its Android pixel test.

## Files

- `TextLayoutPlanner.kt` — reworked `paintEnvelopeContainedInSpans` (measured
  envelopes); new `clipSpansToRect`, `containedReflowRescue`; resolver wiring
  (candidate 1 replacement, rescue tail, mask-unusable fallback);
  `withSharedCellMetadata` honors `maskUsable = false`;
  `BlockLayout.maskUsable` (default true); `TextLayoutTuning`:
  `OCR_GROW_FACTORS`, `MAX_CONTAINMENT_WALK_STEPS = 4`,
  `CONTAINMENT_WALK_STEP_PX = 0.5f`.
- `TranslationOverlayView.kt` — prototype clip parity (kept as-is).
- Tests: NEW `TextLayoutPlannerContainedRescueTest` (sloped-ceiling invariant,
  fail-open mask-unusable, untouched non-colliding path); REWORKED
  `TextLayoutPlannerShiftCeilingRepairTest` test 1 — from the rejected
  `clipRect != null` expectation to the contained-rescue contract (complete
  text, no clip, exact row-span containment, deterministic replay, bounded
  attempts); scratch rig `Page15MockRig` (path-portable, assumption-guarded)
  plus fixtures (real page-15 artifact + exact source image) committed as the
  standing planner fixture harness with a fixed-seed 20-case randomized cloud
  stress.

## Verification

Windows, Android Studio JBR, Git Bash.

1. Focused: `./gradlew.bat :app:testDevDebugUnitTest --tests
   "eu.kanade.translation.segmentation.*" --tests
   "eu.kanade.translation.rendering.*"` → BUILD SUCCESSFUL; 216 tests,
   0 failures (including `TextLayoutPlannerTest` 31/31 unedited,
   `MissingTextReproTest` 11/11, `TextLayoutPlannerFinalSafetyTest` 7/7 —
   the unmasked 53.68/378.08 px resolver fixtures stay green, proving the
   masked gating cannot alter unmasked decisions).
2. Full module gate: `./gradlew.bat :app:testDevDebugUnitTest` → BUILD
   SUCCESSFUL; suites=175, tests=1326, failures=0 (HEAD 1317 + prototype 4 +
   rescue 3 + rig 2).
3. `./gradlew.bat :app:compileDevDebugAndroidTestKotlin` → BUILD SUCCESSFUL.
4. Real-page rig (fixtures under `engineering/fixtures/`, live preview at
   `http://127.0.0.1:8765`): the production planner now plans the Director's
   problem page with all 13 masked blocks fully contained, zero horizontal
   anchor movement, ≤6 px vertical movement; the randomized stress (20
   synthetic cloud configurations, 102 masked blocks, standard and tall
   webtoon shapes) passes with 102/102 contained.

## Deviations and remaining risk

- The rig's contact-scoot pass is a rig-level experiment, NOT wired into
  production: the existing capped shift ladder (candidates 4–7) plus the
  accepted contained overlap at the tail play that role. A dedicated
  post-placement contact pass would be a separate slice.
- Blocks with a mask but NO usable cell (beyond-8 members, assignment ties)
  still get no rescue: with no spans there is nothing to contain against, so
  the colliding ones fail open to the visible OCR draw. Raising
  `MAX_SHARED_BLOCKS_OPTIMIZED` or deriving rescue spans from the raw mask
  inside the planner remains the follow-up (Director decision pending).
- Bounds-mode cells (degenerate masks, e.g. empty runs) have no spans; their
  masked colliding blocks also fail open via the same tail — pinned by the
  new fail-open test.
- Device execution of the Android overlay pixel suite remains compile-gated
  (UTP invalid-path defect on this host's wireless-ADB serial + the earlier
  `Failure [-99]` instrumentation install; device offline since).
- The rig's measurer is a 0.56 px/char desktop approximation; on device the
  same ladder runs under the real `PaintTextMeasurer`, so exact fitted fonts
  will differ slightly while the containment/shift invariants hold by
  construction.
