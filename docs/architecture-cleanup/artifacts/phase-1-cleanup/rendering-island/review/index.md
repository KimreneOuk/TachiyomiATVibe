---
title: "P1.2 code review - unwired rendering island removal"
kind: review
comments: none
---

# Executive Summary

This independent code review evaluates the uncommitted changes for **Phase 1 Slice 2 (P1.2)** on branch `architecture-cleanup` against commit `b452499c` (P1.1).

- **Verdict:** **ACCEPTED (CLEAN)**.
- **Diff summary:** 6 files changed (4 deleted, 2 modified), **0 insertions, 3,542 deletions (net -3,542 lines)**.
- **Source count impact:**
  - `app/src/main/java/eu/kanade/translation`: **288 -> 286 source files** (Kotlin: 286 -> 284; Java: 2 unchanged).
  - `app/src/test/java/eu/kanade/translation`: **317 -> 315 ordinary unit test sources**.
- **Integrity checks:** User report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` SHA256 verified byte-identical (`EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`). Git working tree remains uncommitted and untracked report preserved.
- **Phase 0 baseline preservation:** The six known baseline test failures identified in Phase 0 remain untouched; no test deletion or masking of pre-existing failures occurred.

# 1. Detailed Diff & Structural Verification

### 1.1 Pure Deletion & Frozen-Live Proof

- The diff across all 6 files contains **0 added lines** (`git diff --check` clean, zero insertions).
- Every retained production method and data structure in `TextLayoutPlanner.kt` and `CollisionRelaxation.kt` remains completely untouched at the line level.
- Working tree line counts verified:
  - `TextLayoutPlanner.kt`: git HEAD `2,607` -> working tree `1,332` lines (net `-1,275`).
  - `CollisionRelaxation.kt`: git HEAD `1,133` -> working tree `392` lines (net `-741`).
  - Sum of modified file deletions: `1,275 + 741 = 2,016 lines`.
  - Deleted files: `AdaptiveBandPlanner.kt` (454), `MaskTextRegionPlanner.kt` (451), `AdaptiveBandPlannerTest.kt` (244), `MaskTextRegionPlannerTest.kt` (377) = `1,526 lines`.
  - Total deletions: `2,016 + 1,526 = 3,542 lines`.

### 1.2 Deleted Helper Files & Test Coverage

- **Deleted production files:**
  - `AdaptiveBandPlanner.kt` (-454 lines)
  - `MaskTextRegionPlanner.kt` (-451 lines)
- **Deleted test files:**
  - `AdaptiveBandPlannerTest.kt` (-244 lines, 9 tests)
  - `MaskTextRegionPlannerTest.kt` (-377 lines, 13 tests)
- **Call-site & reachability analysis:**
  - Repo-wide search confirms zero references to `AdaptiveBandPlanner` and `MaskTextRegionPlanner` remain in the codebase.
  - The deleted tests exclusively asserted the internal mechanisms of these two retired helpers.
  - Neither class had any production caller outside the dead rendering island.

### 1.3 Removed Members in `TextLayoutPlanner.kt`

- **Removed dead island members & types:**
  - `fitTextUnmasked`, `adaptiveBlockLayout`, `bandBeatsRectangleFit`, `applySiblingFontHarmony`, `harmonizedReplacement`, `dilateSpans`, `clipSpansToRect`, `rescueCeilingSpans`, `containedReflowRescue`, `paintEnvelopeContainedInComponent`, `resolvePostAnchorPlacement` delegate, `boundedFreeTextWideningPlan`, `buildSharedCellPlans`, `symmetricFitRegion`, `withSharedCellMetadata`, `componentAssignmentAllowed`, `componentBounds`, `plannerMember`.
  - Removed unused types: `PlacedFootprint`, `SharedCellPlan`, `RescueBudget`, `FinalResolution`.
  - Removed exclusive unused imports: `MaskConversionBudgets`, `OrderedMaskResult`, `kotlin.math.hypot`, `kotlin.math.roundToInt`.
- **Preserved live roots & boundaries:**
  - Live entry points and core algorithms remain intact: `plan`, `planPage`, `planPageInternal`, `fitTextInMask`, `fitTextVertical`, `placeBlock`, `minLegibleFont`, `chosenText`, `extentOf`, `buildMaskRegions`, `resolveComponentId`, `centeredExtentOverlapsObstacle`, `GrownBox`, `FontFittingAlgorithms` wrappers, and all public plan types.
  - Byte-identical restoration of `resolveComponentId` was verified against HEAD extraction; it is correctly retained as a live dependency of `planPageInternal`.

### 1.4 Removed Members in `CollisionRelaxation.kt`

- **Removed dead members:**
  - `resolvePostAnchorPlacement` (post-anchor ladder), `footprintCollides`, `minShiftDisplacement`, `freeRectDisplacement`, `fitIntoFreeRect`, `boundedFreeTextWideningPlan`, `collisionFreeWidthForBand`, and exclusive delegates/constants (`SHIFT_*`, `FIT_MIN_FONT_PX`, etc.).
  - Unused typealiases removed: `FinalResolution`, `FreeRectCandidate`, `RescueBudget`, `FreeTextWideningPlan`, `SharedCellPlan`.
- **Preserved live collision behavior:**
  - Retained untouched: `growIntoFreeSpaceIfNeeded`, `resolveMinimalDisplacementX`, `freeSpaceLeft/Right/VerticalUp/VerticalDown`, `translateRect`, `translateLayout`, `overflows`, and delegates (`binarySearchFontSize`, `cjkWrap`, `fitsAt`, `columnsFor`, etc.).
  - Byte-identical retention of `cjkWrap` and `binarySearchFontSize` verified against HEAD; they are active dependencies of retained `growIntoFreeSpaceIfNeeded`/`overflows`.

# 2. Validation & Non-Regression Evidence

1. **Test Results Parity:**
  - Independent verification of preserved JUnit XMLs under `phase-1-cleanup/rendering-island/`:
    - Baseline (`2026-10-14T15-33-08Z`): **19 classes, 188 tests, 0 failures, 0 skips**.
    - Post-change (`2026-10-14T15-47-29Z`): **17 classes, 166 tests, 0 failures, 0 skips**.
    - Test delta: Exactly **22 tests removed**, accounting 100% for `AdaptiveBandPlannerTest` (9 tests) and `MaskTextRegionPlannerTest` (13 tests).
    - Retained suite parity: All 17 retained rendering test classes passed with identical per-class test counts, including `TextLayoutPlannerTest` (34/34), `TextLineBreakerTest` (19/19), `TextLayoutCoordinatorTest` (14/14), and `DrawPlanCompatibilityTest` (14/14).
2. **Compilation Cleanliness:**
  - Fresh compilation of `compileDevDebugKotlin` and `compileDevDebugUnitTestKotlin` succeeded with zero errors and no new compiler warnings.
3. **Pre-existing Failure Isolation:**
  - None of the six Phase 0 baseline failure methods touch the rendering package. Baseline failure isolation remains preserved.

# 3. Limitations & Gating Scope

- **Scope:** This review validates Slice P1.2 only. Slices P1.3, P1.4, and P1.5 have not been implemented.
- **Device gate:** Physical device testing remains deferred per user selection; host-level tests confirm algorithm and layout contract preservation.
- **Upstream callers:** `TextLineBreaker.applyBestHyphenTrial` lost its last production caller (`AdaptiveBandPlanner`) but remains pinned by existing `TextLineBreakerTest` cases; appropriately left untouched for future consolidation.

# 4. Conclusion & Next Steps

Phase 1 Slice 2 (P1.2) successfully removes the dead unwired rendering island, eliminating 3,542 lines of dead code across 6 files while preserving live text layout, placement, and collision algorithms with zero regressions.

**Slice P1.2 is APPROVED.** GLM executor is clear to commit P1.2 and proceed to P1.3.
