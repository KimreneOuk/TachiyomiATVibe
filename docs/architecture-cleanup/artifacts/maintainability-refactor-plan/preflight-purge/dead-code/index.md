---
title: "Dead-code preflight audit — rendering, pipeline, workflow, model, persistence, TranslationPreferences (baseline 4de121bf)"
kind: review
comments: none
---

# Dead-Code Preflight Audit (read-only)

Baseline: `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`, clean working tree. Constraints honored: no production/test/build edits, no builds/test runs, no subagents.

**Method.** Repo-wide occurrence counting (`rg` over every tracked file: Kotlin/Java sources, tests, XML/resources, docs, Gradle, and `app/proguard-rules.pro`) used as a candidate generator only; every verdict below was then confirmed by reading the declaration and resolving **every** reference (call sites, method references `::name`, string literals for reflection, preference-key strings, serialization). A dedicated scan of every non-Kotlin/Java file (XML, JSON, `.pro`/R8 rules, docs) for all candidate and island class names returned **zero** references — no resource, reflection-string, or keep-rule path keeps any of them alive. History caveat: the available history of this repo starts at squashed root `0289de32` (73 commits; it is the `--max-parents=0` root), so "appears in `0289de32`" means *first appearance in available history* — earlier-path provenance is untrackable and **no origination/orphaning claim is made**. All verdicts rest solely on HEAD reachability. Reachability was computed transitively: a symbol is "dead" only if it is unreachable from live entry points (`plan`/`planPage` → `planPageInternal`, `TranslationOverlayView:100`, `LayoutPlanPublication:81`, pipeline/phases, workflow, persistence entry points). **No runtime claims are made anywhere in this artifact** — "dead" is a static-reachability verdict at HEAD.

**Headline.** All 11 supplied candidates verified. 3 additional first-order dead symbols found (`containedReflowRescue`, `TextLayoutPlanner.resolvePostAnchorPlacement` delegate, `ChapterArtifactEngine` trio — plus `InpaintingProviderRecovery`). The candidates are the visible edge of one large **unwired second-generation rendering engine** (~2.3k production LOC + ~0.7k test LOC; every involved file first appears at HEAD's paths in root commit `0289de32` — see the history caveat above; this is a first-appearance fact, not an orphaning proof). The two TranslationPreferences accessors have **no readers anywhere** (names and key strings, all file types including R8 rules); their legacy-compat KDoc records intent but is not a technical necessity — accessor-only removal is optional and safe, and retains stored keys/data either way.

## Verdict table

| # | Symbol | Location | Verdict | Confidence |
| --- | --- | --- | --- | --- |
| 1 | `fitTextUnmasked` | TextLayoutPlanner.kt:552 | DEAD (public, zero refs anywhere) | High |
| 2 | `bandBeatsRectangleFit` | TextLayoutPlanner.kt:873 | DEAD (declaration-only) | High |
| 3 | `applySiblingFontHarmony` | TextLayoutPlanner.kt:901 | DEAD (declaration-only) | High |
| 4 | `dilateSpans` | TextLayoutPlanner.kt:1212 | DEAD (declaration-only) | High |
| 5 | `rescueCeilingSpans` | TextLayoutPlanner.kt:1270 | DEAD (declaration-only) | High |
| 6 | `buildSharedCellPlans` | TextLayoutPlanner.kt:2258 | DEAD (declaration-only) | High |
| 7 | `withSharedCellMetadata` | TextLayoutPlanner.kt:2426 | DEAD (declaration-only) | High |
| 8 | `markBatchTranslationFailed` | TranslationPipeline.kt:1265 | DEAD (declaration-only; live replacement exists) | High |
| 9 | `isStructuralAnalysisRefusal` | AnalysisChunkExecutor.kt:353 | DEAD (test-seam wrapper, zero test refs) | High |
| 10 | `batchSublimitKeyFor` | AnalysisChunkExecutor.kt:357 | DEAD (test-seam wrapper, zero test refs) | High |
| 11 | `translationOnnxEp` | TranslationPreferences.kt:140 | COMPATIBILITY CONTRACT — keep by policy | High (liveness), owner decision on removal |
| 12 | `translationExperimentalQnn` | TranslationPreferences.kt:186 | COMPATIBILITY CONTRACT — keep by policy | High (liveness), owner decision on removal |
| 13 | `containedReflowRescue` | TextLayoutPlanner.kt:1327 | **NEW** — DEAD (sole other mention is stale KDoc, CollisionRelaxation.kt:261) | High |
| 14 | `resolvePostAnchorPlacement` (TLP private delegate) | TextLayoutPlanner.kt:1468 | **NEW** — DEAD (zero callers; only prod reference to CR ladder) | High |
| 15 | CollisionRelaxation ladder `resolvePostAnchorPlacement` | CollisionRelaxation.kt:272–~645 | **NEW** — DEAD (zero callers incl. tests; ~860-line function + exclusive helpers) | High |
| 16 | AdaptiveBandPlanner.kt (entire file) | 429 lines | **NEW** — DEAD file (only refs: island + own test) | High |
| 17 | MaskTextRegionPlanner.kt (entire file) | 424 lines | **NEW** — DEAD file (only refs: island + own test) | High |
| 18 | `ocrCheckpointSidecarName` | ChapterArtifactEngine.kt:968 | **NEW** — DEAD (declaration-only) | High |
| 19 | `analysisChunkSidecarName` | ChapterArtifactEngine.kt:984 | **NEW** — DEAD (declaration-only) | High |
| 20 | `stagePayloadIsValid` | ChapterArtifactEngine.kt:2191 | **NEW** — DEAD (declaration-only) | High |
| 21 | `runWithInpaintingProviderRecovery` | InpaintingProviderRecovery.kt:7 (30-line file) | **NEW** — DEAD in production; test-only orphan | High |

## The unwired rendering engine island (highest-impact finding)

The live planner chain is exactly: `plan` (452) → `planPage` (472) → `planPageInternal` (618–811). Its step-3 loop ends at `drawable.add(layout)` / return (797–810): masked blocks take `fitTextVertical`/`fitTextInMask`, unmasked take `computeRects`+`placeBlock`. **Nothing else is invoked.** `PagePlanWithAttempts` always reports 0 attempts, consistent with the ladder being unwired. The following form one coherent, mutually-reachable dead feature ("shared-cell / band-fit / collision-ladder v2"):

- **TextLayoutPlanner.kt** dead members: 1–7 above, plus second-order members whose sole callers are dead code — `harmonizedReplacement`:981 (sole caller `applySiblingFontHarmony`:942), `clipSpansToRect`:1235 (sole caller `containedReflowRescue`:1393), `symmetricFitRegion`:2398 (sole callers 2321/2372 in `buildSharedCellPlans`), `componentAssignmentAllowed`:2473 (sole caller 2451 in `withSharedCellMetadata`), `componentBounds`:2477 (sole caller 2291), `plannerMember`:2508 (sole callers 2300/2355), `paintEnvelopeContainedInComponent`:1441 (sole caller is a ladder-only wrapper), `boundedFreeTextWideningPlan`:1919 (zero callers), private delegate 1468; and island types `SharedCellPlan`:2215, `RescueBudget`:1255, `FinalResolution`:1049, `PlacedFootprint`:239.
- **CollisionRelaxation.kt** (1133 lines actual) is **partially live — do not delete the file.** Live members (called from `placeBlock`): `overflows`:1114 (via TLP:1951), `growIntoFreeSpaceIfNeeded`:798 (via TLP:1605→1835→1850), `resolveMinimalDisplacementX`:703, and their shared helpers (`freeSpaceLeft/Right/VerticalUp/Down` 737/751/765/778, `translateRect`:158, `translateLayout`:166). Dead core: `resolvePostAnchorPlacement`:272 with nested `pageBudgetAllows`/`validates`/`tryCandidate`, plus ladder-exclusive helpers `footprintCollides`:146, `minShiftDisplacement`:194, `freeRectDisplacement`:646, `fitIntoFreeRect`:661 (+`inflationAt`), `boundedFreeTextWideningPlan`:996, `collisionFreeWidthForBand`:1092, and the ~17 one-line private delegates (28–140) that exist only so the ladder can reach TLP internals. Per-member confirmation of the delegate set is mechanical at purge time.
- **AdaptiveBandPlanner.kt** (454 lines actual): `fitAdaptiveBands`:95 production callers are exclusively island code (TLP:1006, TLP:1405, CR:385). `AdaptiveResult`/`adaptiveBlockLayout` have no live consumers. File-level deletion candidate.
- **MaskTextRegionPlanner.kt** (451 lines actual): production callers exclusively `buildSharedCellPlans`/`plannerMember` (dead). File-level deletion candidate.
- **Tests retiring with the island**: `AdaptiveBandPlannerTest.kt` (244 lines), `MaskTextRegionPlannerTest.kt` (377 lines). `TextLayoutPlanner{,Stroke,Direction}Test` contain **zero** references to any dead member and survive the purge.

**Stale documentation claims (fix or delete with the code):** `harmonizedReplacement` KDoc ("tests drive this function directly", TLP:976–979) is false — no test references it; `componentAssignmentAllowed` KDoc ("tests can pin the cap boundary directly", TLP:2463–2469) is false; `CollisionRelaxation.kt:261` KDoc cites the dead `containedReflowRescue` as if wired.

**Effect of deleting the island (static):** nothing reachable is removed; removes ~1,750 TLP+CR LOC net of live members, plus 905 lines of dead-file code (AdaptiveBandPlanner.kt 454 + MaskTextRegionPlanner.kt 451, actual line counts) and 621 lines of orphan tests (244 + 377). **Recommendation: remove as one coherent Step-1 cleanup**, gated by a behavior baseline (existing test suite green before/after the change) with plain git rollback as the recovery path; the existence of tests over non-production helpers is not a reason to keep them. If a future roadmap wants to rewire a resolver, it should be designed fresh against the live `planPageInternal` contract, not resurrected from this island.

## Single-symbol findings

- **`markBatchTranslationFailed`** (TranslationPipeline.kt:1265–1286): declaration-only. Live failure handling routes through `PageStoreWriter.markPageFailed` (195–236; guarded snapshot update, OOM-safe, charges `attemptCount`/`retryCount` at 231–232) and `markPageTimedOut`, with `BatchChapterTranslator` collaborators. Deletion: no behavior change; the attempt-charge semantics it documents already live in the replacement. Tests preserved.
- **Analysis wrappers** (AnalysisChunkExecutor.kt:353–358): top-level re-exports whose KDoc claims test consumers that do not exist. Live code uses `TranslationResponseFaithfulness.isStructuralRefusal` directly (line 264) and `BatchProviderSublimit.batchSublimitKey` (ProviderRequestGovernor.kt:871; ProviderGovernorBatchSublimitTest.kt:53 tests the real one). Deletion: none.
- **ChapterArtifactEngine trio**: `ocrCheckpointSidecarName`:968, `analysisChunkSidecarName`:984, `stagePayloadIsValid`:2191 — declaration-only. The live sidecar machinery (`SidecarPointer`/`SidecarPublication`/`publishSidecarPointers`) does not use them; cleaned-image sidecar naming goes through `CleanedImageIdentity.sidecarName` (live, 7 refs). Step-1 disposition: **deferred out of the conservative immediate set** — these are pure unused names, but they live inside the persistence engine, and the Step-1 scope guard excludes persistence internals; they are recorded here and left to the persistence-scope pass rather than bundled into Step 1.
- **`runWithInpaintingProviderRecovery`** (app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecovery.kt, 30 lines): `internal inline fun` in the main source set whose only reference is `app/src/test/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecoveryTest.kt` (103 lines). Production-dead, test-only orphan. Delete function + test together.

## TranslationPreferences legacy accessors — no readers; removal optional

`translationOnnxEp` (domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt:140, key `translation_onnx_ep`) and `translationExperimentalQnn` (TranslationPreferences.kt:186, key `translation_experimental_qnn`).

Liveness: the accessor names and both key strings have **zero occurrences** outside these declarations — no reader in production, settings UI, backup/migration, tests, resources, or R8 rules. "No-reader" is established fact. The KDoc's compat rationale ("kept for backward compatibility", "so old preference files deserialize cleanly") records intent but is **not a technical necessity**: unknown keys persist independently in the preference store and are ignored by readers that never ask for them — removing the accessors retains all stored keys and data, and no storage purge or migration is involved either way. Deletion would be accessor-only and optional; keeping them is equally defensible as documentation of reserved key strings (the keys should not be reused for new settings either way). Not pending wiring: HEAD's GPU/CPU controls use different keys (`translation_hardware_accelerator`, `paddle_ocr_*`), so these are not candidates for a current feature.

## Verified live — do NOT purge

- **PageLifecycle / PageView (prior F08)**: active via getters/data-class equality/Flow; confirmed referenced across workflow/scheduling/viewer and tests. Never classify dead.
- Model-dir helpers `buildComponents`, `buildRowOffsets`, `requireValidDimensions` (MaskGeometry.kt:520/527/244, called at 506/507/485) and `setErrorMessageRaw` (PageTranslation.kt:116, called from PageTranslationView.kt:396) — all live.
- TextLayoutPlanner live surface: `plan`/`planPage`/`planPageInternal`, `fitTextInMask`, `fitTextVertical`, `placeBlock`, `fitText*` helpers, `paintEnvelopeContainedInSpans`/`paintRectContainedInSpans`, `inkRectOf`, `extentOf`, `containedIn`, `conservativeOccupancyOf`, `buildMaskRegions`, `resolveComponentId` (live via 647), `MaskConversionBudgets`, and CR's live members listed above.
- `translateSinglePageOnnx`/`translateSinglePageHttpRender` pipeline wrappers (called at TranslationPipeline.kt:615/618/703/914/917/1180).
- `RollingAutoCoordinator` (62 refs; TranslationManager, TranslationScheduler, ReaderTranslationController, ReaderAutoTranslationPageResolver) and the `AutoWindowState.kt` model types (`AutoChapterIdentity`, `AutoDeferralReason`, … — note the file declares types without the file's name) — live; candidate for removal only via the reader-queue replacement, owned by the reader-architecture agents.

## Flaky-test purge

Static pass surfaced no flaky-test claims; without test runs (forbidden here) flakiness cannot be established. Recommend the flaky-test artifact be driven by repeat-run data, not static analysis. No test in scope references any dead symbol except the two orphan test files listed above.

## Removal-dependency summary

Order of removal within the island is irrelevant to correctness (mutually dead), but the island must be removed as a unit. After purge, re-run the same occurrence-count pass over `TextLayoutPlanner.kt`/`CollisionRelaxation.kt` to catch newly exposed second-order orphans and fix the three stale KDoc references. Companion manifest: [Step-1 production manifest](epics/699de408-00ac-4fe5-a903-b541c23063cc/artifacts/translation-step1-plan/production-manifest/index.md).
