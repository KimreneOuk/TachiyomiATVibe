# Ticket P4-02: Extract pure geometry from TextLayoutPlanner

**Phase:** 4 | **Risk:** Medium (layout math invariants) | **Type:** Behavior-preserving extraction

## Current state

`TextLayoutPlanner.kt` = 3,605 lines. Pure-math regions per audit (re-verify spans first):
- RLE mask unpacking + Union-Find connected-component labeling (~289–350)
- Font stroke & bounding-box math (~378–400)
- Iterative obstacle collision-relaxation loops (~1600–1750)
- CJK vertical/horizontal line-wrapping algorithms (~3544–3605 area, now end-of-file)

## Changes

1. Extract mask/cluster math → `rendering/MaskGeometryClustering.kt` (RLE decode, Union-Find,
   component ordering — the deterministic assignment logic covered by
   `MaskGeometryDeterministicAssignmentTest`, `MaskGeometryOrderedRleTest`).
2. Extract collision relaxation + font fitting heuristics → `rendering/FontFittingAlgorithms.kt`
   (stroke measurement, bbox math, relaxation iterations, CJK wrapping kernels).
3. `TextLayoutPlanner` stays as the facade: same public API, delegates to the extracted objects.
   Pure moves only — identical algorithms, identical inputs/outputs, no floating-point expression
   reassociation, no reordered loops, no early-exit introduction.

## Verification

ZERO modifications to any test under `rendering/` (they pin the math; a diff = behavior change: STOP).
Full both-flavor suites green; `assembleDevDebug` green. Planner file < ~1,500 lines; extracted
files have no Android imports (pure Kotlin) unless the span genuinely needs them (Bitmap etc.) —
note any exception in the report.

## Commit(s)

`refactor(translation): extract MaskGeometryClustering (1/2)` + `refactor(translation): extract FontFittingAlgorithms (2/2)`
