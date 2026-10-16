---
title: "Final cold source cross-check — translation-step1-plan primary docs (baseline 4de121bf)"
kind: review
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

# Final Review — cold source checks and file accounting

<user_quoted_section>Primary disposition: F1–F3 below were incorporated after this review: the root policy row now says one Inline / one Keep; the fingerprint table includes BatchLaneWorkers and its pure comparison of resolved source evidence; the nonexistent TranslationPipeline telemetry caller was removed. Findings remain as the review record. Mechanism critique and runtime gates are separate and still outstanding.</user_quoted_section>

**Scope of this review (declared):** cold SOURCE verification and file accounting of `index.md`, `single-page/index.md`, `boundaries-and-bridges/index.md`, `commits/index.md` — path existence, line-claim spot checks, caller-list accuracy, compile-affected-file counts for the fingerprint callback migration, dead-engine-inline check, and forecast arithmetic. **Not claimed:** fresh independent mechanism critique (assigned to the separate fresh-agent review); proposals are not reviewed as delivered code; no builds or test runs were performed. Baseline `4de121bf`, read-only.

## Verdict: 1 accounting contradiction (fix before these docs serve as final authorization), 1 compile-set omission, 1 caller-list error. Everything else verified exact.

## F1 — BLOCKING accounting contradiction: root `index.md` line 44 vs line 75 (BatchResumePolicy)

Line 44: "The exact requested name-pattern scan found only two files, **both justified Keep**; no arbitrary inlining quota."
Line 75 (same document, deletion table): `BatchResumePolicy.kt` → "**Co-locate** private pure object in `BatchResumeGateDecider.kt`" (−1 production), and the −4/−11 forecast depends on that co-location.

The co-locate verdict (agreed in the production manifest §4) is the correct one; line 44 is stale and contradicts both the table and the forecast. Fix: line 44 should read one Keep (MemoryPressurePolicy, 2 referrers) + one inline/colocate (BatchResumePolicy, single referrer). Until fixed, the root document asserts two different dispositions for the same file in its accounting section.

## F2 — Missed compile-affected file: `BatchLaneWorkers.kt` absent from the fingerprint suspension propagation table (boundaries-and-bridges)

The propagation table lists 6 files (BatchChapterTranslator, BatchResumePlanner, StandardLaneWorker, ChapterProfileBatchCoordinator, PreflightWorker, FinalizeWorker). Verified source facts:

- `BatchLaneWorkers.kt:110` declares its own `private val freshSourceShaByPageKey: (String) -> String?`, consumed at `:179`, `:1075`, `:1244`.
- It is fed by `BatchChapterTranslator.kt:660/:842/:889` as `freshSourceShaByPageKey = { pageKey -> sourceFingerprints[pageKey] }` — i.e., a synchronous `Map.get` over `LazySourceFingerprints`.

When the cache `get` at `:433`/`:757` becomes suspend (the entire point of C07), these three lambdas must become suspend lambdas, which forces `BatchLaneWorkers.kt`'s property type and its three call sites to change in the same commit or the commit does not compile. The document's own rule ("if the migration uncovers another non-suspending caller, add it to this manifest … in the same commit") covers the fix: add `pipeline/batch/BatchLaneWorkers.kt` as a seventh propagation row.

## F3 — Caller-list error: `TranslationPipeline.kt` is not a `NativeVisionTelemetry` referencer (boundaries-and-bridges move table)

Line 13 lists `pipeline/TranslationPipeline.kt` among the callers to update when moving `pipeline/NativeVisionTelemetry.kt`. Repo-wide reference scan: the only production referencers are `engines/vision/ocr/PaddlePageOcrCoordinator.kt`, `pipeline/SinglePageOnnxPhase.kt`, `pipeline/SinglePageHttpRenderPhase.kt`, and `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt` (the declaring object itself at `NativeVisionTelemetry.kt:10` makes five files). TranslationPipeline has zero references (case-insensitive). Drop it from the caller list.

## Verified exact (no findings)

- **Forecast arithmetic reconciles with measured scopes.** Production: 7 deletes — all 7 files exist (IPR, ABP, MTRP, BatchResumePolicy, RollingAutoCoordinator, AutoWindowState, ReaderAutoTranslationUiState), 6 inside the requested subtree +1 reader UI; 3 adds have **zero existing name collisions** (ReaderTranslationQueue, PageNativeOperation, SinglePageOutcome-as-file); 290→287 exact. Tests: 11 deletes — all exist (orphans 3: InpaintingProviderRecoveryTest/AdaptiveBandPlannerTest/MaskTextRegionPlannerTest; legacy 8 incl. 3×Rolling, AutoWindowStateTest, TranslationManagerAutoArbitrationTest, ReaderManualPreemptsAutoLeaseTest, ReaderAutoTranslationUiStateTest, ReaderAutoTranslationLifecycleTest), 9 in the subtree; 4 new tests zero collisions; 318→313 exact; global −11 exact. testDebug 1 / androidTest 6 match earlier measurement.
- **~25 line:number claims spot-checked, all exact:** TranslationPipeline 505/519 (`INTERACTIVE` hardcoded), 596 (native priority from `PageWriteOrigin.MANUAL`), 742 (raw-string `Rejected`), 209 (`REASON_TRANSLATION_NOT_SAVED`); TranslationScheduler 319 (session-rejected string `Rejected`), 584/637 (`runBlocking` bridges); TranslationManager 1065–1078 (`fun cancelRunningChapterForReplace`, `runBlocking` at 1070 and 1074), MangaScreenModel 1311; SinglePageOnnxPhase 666 (resume `recomputeFor`), 1182 (`CoroutineScope(Dispatchers.Default).async` detached launch), 1203 (fresh inpaint style pass), 1259 (`ensureActive` checkpoint); SinglePageHttpRenderPhase 662 (`inpaintJob.await`), 756/875 (normal/retry style passes), 1068 (detached-job cancel); BatchRenderJoin 235 (batch style call); PageTranslation.kt 303 (`blockId: String?`), 58 (`ocrFingerprint`); NativeRunQuarantine 72 (`minusKey(Job)`); CleanedImagePublisher 24/45/48/68/70 (publish + accepted/rejected delete semantics); BatchChapterTranslator 422 (cache creation), 433/757 (hash gets), 1203–1228 (`LazySourceFingerprints` class); PageWorkPlan 74 (`BatchExpectedFingerprints`); BatchResumePlanner 57 (`batchPagePlans = buildBatchPagePlans()`); P5OutcomeProjectionTest 240/259 (raw sentence reasons).
- **Caller lists:** DeviceStageNormalization 5/5 exact (RoiPageRecognitionEngine, TranslationPipeline, SinglePageOnnxPhase, SinglePageHttpRenderPhase, BatchRenderJoin). `SinglePageOutcome` production referrers exactly TranslationScheduler + TranslationUiTruth + TranslationPipeline + declaring TranslationExecutor (pipeline/execution/) — matches the move plan. `BatchContextFrontier` reverse dependency from `context/` verified (`context/ChapterContextService.kt`). Names-only references exist: `NativeStallWatchdog` (engines/translator/), `AnalysisEngineTransport` (engines/translator/analysis/), `BatchParallelWindowBudgetTest` (test — located at `coexistence/`, cited by name only), `ReaderViewModel`, ahead-count preference (TranslationPreferences.kt:110), `PreparedPageBoundaryTest` (test side exists; the main-source `PreparedPageBoundary` file was not located at the cited `scheduling/` path by this check — see bounded resolution below).
- **No dead engine code is inlined anywhere.** The four docs delete the inactive island (AdaptiveBandPlanner.kt, MaskTextRegionPlanner.kt + exact TLP/CollisionRelaxation members), keep active `TextLayoutPlanner`/`CollisionRelaxation` code, and touch only live files (RenderColorEstimator, NativeRunQuarantine, EngineLane, phases) — consistent with the production manifest and with "no rendering algorithm replacement in Step 1".
- **No runtime claims.** All four docs frame commands/gates as proposed and unexecuted; no performance or flakiness guarantees are asserted as measured.

## Bounded resolution items (small, non-blocking)

1. RESOLVED during this review: `PreparedPageBoundary.kt` lives at `app/src/main/java/eu/kanade/translation/pipeline/execution/PreparedPageBoundary.kt` (its test `scheduling/PreparedPageBoundaryTest.kt` is correctly cited). No primary doc claims a wrong path for it; nothing to fix.
2. Commits C01 removes preference accessors "in `domain/.../TranslationPreferences.kt`" — correct file, no line numbers given; none needed.
