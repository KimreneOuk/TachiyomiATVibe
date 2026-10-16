---
title: "P1.2 rendering island — implementation results and evidence"
kind: spec
comments: none
---

# P1.2 implementation results

Base: `architecture-cleanup` @ `b452499ca14a1bafef5dbc8f1cb12c76a0776fb9` (coordinator's reviewed P1.1 commit; still unpushed, no new commits). Worktree clean except the six P1.2 paths and the user's untouched report. Verification evidence: full rendering package (19 baseline classes) on `:app:testDevDebugUnitTest`, DevDebug fresh main+test compilation after edits.

# File delta (actual)

| Path | Change |
| --- | --- |
| `app/src/main/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlanner.kt` | Deleted (454 lines; types `AdaptiveLine`, `AdaptiveResult`, object `AdaptiveBandPlanner`) |
| `app/src/main/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlanner.kt` | Deleted (451 lines; object with `partition`, `Cell`, `Member`, `ComponentRegion`, `CellSpanBudget`, `collisionGapPx`, caps) |
| `app/src/test/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlannerTest.kt` | Deleted (244 lines; 9 tests) |
| `app/src/test/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlannerTest.kt` | Deleted (377 lines; 13 tests) |
| `app/src/main/java/eu/kanade/translation/engines/rendering/TextLayoutPlanner.kt` | 2607 → 1332 lines (−1275); island-only removals, see below |
| `app/src/main/java/eu/kanade/translation/engines/rendering/CollisionRelaxation.kt` | 1133 → 392 lines; ladder/widening removals, see below |

`git diff --numstat`: **3542 deletions, 0 insertions** across the six files (`git diff --check` clean). Zero new files. Main translation scope `app/src/main/java/eu/kanade/translation`: **288→286 all sources (284 .kt + 2 .java unchanged)** — matches forecast exactly; test scope 317→315 .kt — matches exactly.

# Reachability audit (refreshed at b452499c, per-symbol)

Method: full-repo content search over all `.kt` (main + test + testDebug) AND all non-Kotlin files (reflection/keep-rule/resource edges: none found for any island symbol). Live roots: `plan`/`planPage`/`planPageInternal` (frozen; `planPageInternal` returns `PagePlanWithAttempts(plan, 0)`), `fitTextInMask`/`fitTextVertical` (frozen), `placeBlock` (frozen), CR `resolveMinimalDisplacementX`/`growIntoFreeSpaceIfNeeded`/`overflows`/`translateRect`/`translateLayout`/free-space helpers (retained per ticket). External production consumers found: `TranslationOverlayView.plan` (line 100), `LayoutPlanPublication.planPage` (81), `TextLineBreaker` → `TextLayoutPlanner.isCJK` (64, 70). Test consumers counted as live.

## AdaptiveBandPlanner.kt + MaskTextRegionPlanner.kt (deleted)

`AdaptiveBandPlanner`, `fitAdaptiveBands`, `AdaptiveResult`, `AdaptiveLine`: referenced only by TextLayoutPlanner island members (harmonizedReplacement:1006-old, containedReflowRescue:1405-old, bandBeatsRectangleFit param, CR ladder:385-old) and by their own deleted tests. `MaskTextRegionPlanner.*`: referenced only by TextLayoutPlanner island members (`buildSharedCellPlans`/`plannerMember`, both dead) and its own deleted test. No external production path reaches either file: `planPageInternal`'s live body never constructs `SharedCellPlan`s, adaptive fits, or cell plans (it emits plain masked/legacy `BlockLayout`s and a constant attempts count 0).

## TextLayoutPlanner.kt — removed members (all with zero live/test consumers)

- Types: `TextLayoutTuning` (entire object — after the purge every member's consumers were island-only; its only test references were in the deleted `AdaptiveBandPlannerTest`), `PlacedFootprint`, `SharedCellPlan`, `RescueBudget`, `FinalResolution`, `FreeRectCandidate`, `FreeTextWideningPlan`.
- Constants: `RESHAPE_TALL_RATIO`/`MIN`/`MAX_WIDTH_FACTOR`, `FIT_MAX_FONT_PX`, `FIT_MIN_FONT_PX` (internal; consumers were all retiring: harmonizedReplacement, containedReflowRescue, CR ladder/fitIntoFreeRect), `FIT_START_WIDTH_FACTOR`, `STROKE_WIDTH_FRACTION`/`MIN_STROKE_PX` (kernels moved to `FontFittingAlgorithms` earlier; zero TLP references remained), `MAX_COMPONENT_ASSIGNMENTS_PER_PAGE`. Kept: `VERTICAL_CHAR_STEP`/`VERTICAL_COL_STEP` (live: fitTextVertical, extentOf, CR), `MIN_GAP_PX` (live: placeBlock, centeredExtentOverlapsObstacle), `LEGIBLE_*` (live: minLegibleFont).
- Functions: `fitTextUnmasked` (zero callers repo-wide), `adaptiveBlockLayout`, `bandBeatsRectangleFit`, `applySiblingFontHarmony`, `harmonizedReplacement` (KDoc claimed a "test seam" but repo-wide grep found no test reference — the KDoc was stale), `conservativeOccupancyOf`, `inkRectOf`, `containedIn`, `paintRectContainedInSpans`, `paintEnvelopeContainedInSpans`, `dilateSpans`, `clipSpansToRect`, `rescueCeilingSpans`, `containedReflowRescue`, `paintEnvelopeContainedInComponent`, `maskedShiftCap`, `relocationWithinCap` (KDoc claimed test consumers; none exist), the `resolvePostAnchorPlacement` delegate, `buildSharedCellPlans`, `symmetricFitRegion`, `withSharedCellMetadata`, `componentAssignmentAllowed` (same stale-seam situation), `componentBounds`, `plannerMember`.
- Imports exclusive to removed members: `MaskConversionBudgets`, `OrderedMaskResult` (rescueCeilingSpans only), `kotlin.math.hypot` (relocationWithinCap only), `kotlin.math.roundToInt` (rescueCeilingSpans/buildSharedCellPlans only). `BubbleMaskRle` import retained (supports a KDoc link in retained `buildMaskRegions`).
- Explicitly retained untouched: `plan`, `planPage`, `planPageInternal` (+`PagePlanWithAttempts`, `FitResult`), `fitTextInMask`, `fitTextVertical`, `placeBlock`, `minLegibleFont`, `chosenText`, `extentOf`, `buildMaskRegions`/`MaskGrouping`, `resolveComponentId`, `centeredExtentOverlapsObstacle`, `overflows`/`fitsAt`/`columnsFor`/`strippedLength`/`minWidthForLines`/`tokenize`, `GrownBox`, the `FontFittingAlgorithms` seam wrappers (incl. `graphemeClusters`, whose last production caller retired with the widening plan but which remains a live one-line seam over a live shared file — left in place as out of ticket manifest), all public plan types.

## CollisionRelaxation.kt — removed members

`FinalResolution`/`FreeRectCandidate`/`RescueBudget`/`FreeTextWideningPlan`/`SharedCellPlan` typealiases (kept `GrownBox`), `FIT_MIN_FONT_PX` const delegate, `maskedShiftCap`/`relocationWithinCap`/`conservativeOccupancyOf`/`inkRectOf`/`containedIn`/`paintEnvelopeContainedInComponent`/`adaptiveBlockLayout`/`placeBlock`/`computeStrokeWidth`/`computeRects`/`graphemeClusters` delegates, `hardCellsDisjoint`, `footprintCollides`, `SHIFT_*` + `minShiftDisplacement`, `resolvePostAnchorPlacement` (the ladder; no caller — its only consumer was the dead TLP delegate), `freeRectDisplacement`, `fitIntoFreeRect`, `boundedFreeTextWideningPlan` (zero live callers; the TLP delegate was dead), `collisionFreeWidthForBand`. Import `kotlin.math.abs` removed (fitIntoFreeRect/widening only); `floor` retained (translateLayout). Retained untouched: `VERTICAL_CHAR_STEP`/`VERTICAL_COL_STEP` delegates, `cjkWrap`/`binarySearchFontSize`/`fitsAt`/`columnsFor`/`strippedLength`/`minWidthForLines` delegates, `translateRect`, `translateLayout`, `resolveMinimalDisplacementX`, `freeSpaceLeft/Right/VerticalUp/VerticalDown`, `growIntoFreeSpaceIfNeeded`, `overflows`.

# Frozen-live proof

The complete diff is **pure deletion with exactly two byte-identical restorations** (see Corrections): git therefore reports 0 insertions (restored lines align as moved context). Structural argument: no retained member's body contains a diff line; every removed line belongs to a whole removed member/type/import listed above. Restorations verified byte-identical against `git show HEAD:` extraction (case-sensitive compare, lengths 30 lines / 926 chars incl. trailing blank):

1. CR `cjkWrap` + `binarySearchFontSize` delegates — shared live dependencies of retained `growIntoFreeSpaceIfNeeded`/`overflows`.
2. TLP `resolveComponentId` — live dependency of frozen `planPageInternal` (Step 1 component assignment).

Runtime corroboration: retained per-class test counts and outcomes identical before/after (below), including `TextLayoutPlannerTest` 34/34 (placement, growth, clip, ordering contracts).

# Corrections made during implementation (for reviewer visibility)

The first post-change compile attempt failed with exactly two unresolved-reference errors, both of which were MY excision overreach, not plan errors; both members were restored byte-identical (they are shared dependencies of retained/frozen code, which the ticket explicitly keeps):

1. Run `52fcc353…` (BUILD FAILED): TLP `resolveComponentId` had been swallowed by the SharedCellPlan→centeredExtent excision range (it sits between two island members); CR `binarySearchFontSize`/`cjkWrap` delegates were classified dead but are used by retained `growIntoFreeSpaceIfNeeded`/`overflows`.
2. Run `f8a82d9f…` (BUILD SUCCESSFUL) after restoration. Both failure and success logs are preserved (paths below).

# Validation executed

Gradle 8.12 wrapper, PATH JDK 21 (Phase 0 environment; CI-JDK-17 deviation stands). One Gradle owner; sequential runs; no other agents' tasks touched.

| Run | Command (repo root) | Exit | Result |
| --- | --- | --- | --- |
| Baseline | `./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.engines.rendering.*" --console=plain --stacktrace` | 0 | BUILD SUCCESSFUL in 1m 31s; 19 classes / 188 tests / 0 failures / 0 skipped. Retiring island classes: AdaptiveBandPlannerTest 9, MaskTextRegionPlannerTest 13 (= 22 tests). Retained: 17 classes / 166 tests |
| Post-change (failed compile — superseded) | same + `--rerun` | 1 | compileDevDebugKotlin FAILED: 2 unresolved references (see Corrections). Log preserved |
| Post-change (final) | same + `--rerun` | 0 | BUILD SUCCESSFUL in 2m 29s; fresh `compileDevDebugKotlin` + `compileDevDebugUnitTestKotlin` of the purged tree; **17 classes / 166 tests / 0 failures / 0 skipped — per-class counts identical to baseline** (TextLayoutPlannerTest 34, TextLineBreakerTest 19, TextLayoutCoordinatorTest 14, DrawPlanCompatibilityTest 14, PersistedLayoutHydrationTest 10, ReaderTextLayoutCacheTest 10, DrawPlanDtoRoundTripTest 7, DrawPlanFingerprintTest 7, RenderColorEstimator* 7+3+5+7, ComponentClipCacheTest 6, PageLayoutPlanContractTest 6, LayoutDrawPlanProjectionTest 5, TextLayoutPlannerDirectionTest 8, TextLayoutPlannerStrokeTest 4) |

Log paths (traycer command logs, epic `699de408-00ac-4fe5-a903-b541c23063cc`):

- Baseline: `commands/…/ed4448b8-44f4-4e83-8b06-9b308628ab16/output.log`
- Post-change failed attempt: `commands/…/52fcc353-8d51-44dd-89b6-c460adcc8247/output.log`
- Post-change final: `commands/…/f8a82d9f-c1a5-47f3-a275-938b3a9c1360/output.log`

JUnit XML preserved at epic scratch `phase-1-cleanup/rendering-island/`:

- Baseline (19 XML): `baseline-test-results/2026-10-14T15-33-08Z/`
- Post-change (17 XML): `post-change-test-results/2026-10-14T15-47-29Z/`

No new compiler warnings attributable to the changed files; only pre-existing `-Xcontext-receivers`/delicate-API notes and the Gradle 9.0 deprecation notice. `git diff --check` exit 0.

# Preservation and scope checks

- Phase 0 evidence untouched at epic scratch `phase-0-baseline/…`; user report re-hashed after all work: SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` — byte-identical.
- No commits/pushes; HEAD remains `b452499c`; `git status` = exactly the six P1.2 paths + the untracked report.
- The six ledgered Phase 0 failure methods belong to other packages; the rendering package had zero failures before and after. No all-suite claims; no unrelated repair.

# Limits and notes

- Verification scope: DevDebug unit-test task, rendering package filter + full main/test compilation of that variant. StandardDebug variant and non-rendering suites were not re-run (P1.5 owns consolidated regression).
- `TextLineBreaker.applyBestHyphenTrial` lost its last production caller (AdaptiveBandPlanner) but is pinned by retained `TextLineBreakerTest` (10+ call sites); `TextLineBreaker.kt` is outside this slice's file list — left untouched, noted for a later slice's audit.
- `PagePlanWithAttempts.finalPlacementAttempts` is now vestigial (live root returns 0), but `planPageInternal` is frozen; its type/KDoc left untouched by design.
- `TextLayoutPlanner.graphemeClusters` (one-line FontFittingAlgorithms seam) retained though its last production caller retired; not in the ticket manifest, still a live public seam.
- `NonDrawReason` values beyond `INVALID_OR_SUBPIXEL_SOURCE_RECT` remain declared (public enum tied to `LayoutOutcome`; not ticket candidates; untouched).
- The stale "test seam" KDocs on `harmonizedReplacement`/`relocationWithinCap`/`componentAssignmentAllowed` were removed with their members; repo-wide grep proved no test consumers existed (same audit care that caught the P1.1 policy consumer).

# Next

Slice complete and verified; stopping for fresh Gemini/Antigravity review per parent gating. P1.3 (timestamp assertion + test-mirror repair) not started.
