---
title: "Step 1 purge — production Delete/Inline/Keep manifest (plan only, baseline 4de121bf)"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

# Step-1 Production Purge Manifest (PLAN ONLY — no repo edits)

Baseline: `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`, clean tree. Evidence and per-symbol verification live in the parent preflight review: [Dead-code preflight audit](epics/699de408-00ac-4fe5-a903-b541c23063cc/artifacts/maintainability-refactor-plan/preflight-purge/dead-code/index.md).

**Scope guards (per instruction):** no batch-callback refactor, no persistence internals rework, no inlining of cohesive batch policy into mega classes. This manifest plans the purge + Manual/Auto queue unification *pre-conditions* only. Queue unification itself is not designed here; its dead-code consequences are recorded as owner-gated deferrals.

## 1. Exact file counts (HEAD `4de121bf`)

| Scope | Production | Tests |
| --- | --- | --- |
| **Requested subtree** `app/src/main/java/eu/kanade/translation` | **290** `.kt`/`.java` sources (291 files incl. 1 non-source) | — |
| Additive: `domain/src/main/java/tachiyomi/domain/translation` | +6 | — |
| *(the 296 figure in earlier drafts was this additive sum — labeled explicitly, not the requested subtree number)* | 296 combined | — |
| `app/src/test/java/eu/kanade/translation` | — | **318** |
| `app/src/testDebug` | — | **1** |
| `app/src/androidTest` (translation-scoped) | — | **6** (9 repo-wide) |
| Whole repo (`*/src/main` vs source sets) | **1102** (1100 `.kt` + 2 `.java`) | **357** `src/test` (318 translation + 39 other) + 1 testDebug + 9 androidTest |

User approximation 290/326 matches the requested subtree (290 exact) and the test view (318 + 1 testDebug + 6 translation androidTest = 325, ≈326). All module splits: app 867 prod, domain 99, presentation-core 49, core/common 44, data 21, presentation-widget 10, core/archive 6, buildSrc 4, core-metadata 2. All line counts below are **actual source line counts** (all lines incl. blanks), not non-blank counts.

## 2. Manifest A — method-level DELETE (conservative, static-reachability-verified; no runtime claims)

These reduce **LOC only — net file delta 0**. Persistence-scope symbols are excluded here (see §6).

| Symbol | Full path:line | Evidence | Tests impact | LOC (actual) |
| --- | --- | --- | --- | --- |
| `markBatchTranslationFailed` | app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:1265–1286 | declaration-only; live replacement `PageStoreWriter.`kt `markPageFailed`:195 (attempt/retry charge at 231–232), `markPageTimedOut`:108 | none reference it | ~22 |
| `isStructuralAnalysisRefusal` | app/src/main/java/eu/kanade/translation/engines/translator/analysis/AnalysisChunkExecutor.kt:353–354 | declaration-only; KDoc claims tests that don't exist; live callers use `TranslationResponseFaithfulness.isStructuralRefusal` (line 264) | none | ~3 |
| `batchSublimitKeyFor` | app/src/main/java/eu/kanade/translation/engines/translator/analysis/AnalysisChunkExecutor.kt:357–358 | declaration-only; tests already call `BatchProviderSublimit.batchSublimitKey` directly (ProviderGovernorBatchSublimitTest.kt:53) | none | ~3 |
| `runWithInpaintingProviderRecovery` + its file | app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecovery.kt:7 (30-line file) | production-dead; only reference is its own test → **file delete, see Manifest B** | test file deleted with it | 30 |

## 3. Manifest B — file-level DELETE (render island: RECOMMENDED purge, baseline-gated; no planning-time confirmation required)

| File | Lines (actual) | Evidence | Tests retiring | Verdict |
| --- | --- | --- | --- | --- |
| app/src/main/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlanner.kt | 454 | only refs: dead island (TLP:1006/1405, CR:385) + own test | app/src/test/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlannerTest.kt (244) | DELETE with render island |
| app/src/main/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlanner.kt | 451 | only refs: dead `buildSharedCellPlans`/`plannerMember` + own test; fix dangling KDoc at TLP:244 on removal | app/src/test/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlannerTest.kt (377) | DELETE with render island |
| app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecovery.kt | 30 | test-only orphan inline fun | app/src/test/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecoveryTest.kt (103) | DELETE immediately |
| app/src/main/java/eu/kanade/translation/engines/rendering/CollisionRelaxation.kt — ladder core only | ~700 of 1133 | `resolvePostAnchorPlacement`:272 zero callers; ladder-exclusive helpers 146/194/646/661/996/1092 + ~17 ladder-only delegate wrappers 28–140 | none (no CR test file exists) | DELETE methods only — **file stays** (live roots: `overflows`:1114, `growIntoFreeSpaceIfNeeded`:798, `resolveMinimalDisplacementX`:703, `freeSpace*` 737–778, `translateRect`:158, `translateLayout`:166) |
| app/src/main/java/eu/kanade/translation/engines/rendering/TextLayoutPlanner.kt — island members only | ~700 of 2607 | `fitTextUnmasked`:552, `bandBeatsRectangleFit`:873, `applySiblingFontHarmony`:901, `harmonizedReplacement`:981, `dilateSpans`:1212, `clipSpansToRect`:1235, `rescueCeilingSpans`:1270, `containedReflowRescue`:1327, `paintEnvelopeContainedInComponent`:1441, delegate `resolvePostAnchorPlacement`:1468, `boundedFreeTextWideningPlan`:1919, `buildSharedCellPlans`:2258, `symmetricFitRegion`:2398, `withSharedCellMetadata`:2426, `componentAssignmentAllowed`:2473, `componentBounds`:2477, `plannerMember`:2508 + types `SharedCellPlan`:2215, `RescueBudget`:1255, `FinalResolution`:1049, `PlacedFootprint`:239 | none — TextLayoutPlanner* tests reference zero dead members and survive | DELETE methods only — **file stays** (live planner contract) |

## 4. Manifest C — microfiles (<100 lines, `*Policy/Gate/Helper/Adapter/Normalizer`, scheduling/workflow/pipeline/presentation)

Complete enumeration — exactly **2** qualify. No `*Helper`/`*Normalizer` files exist in the four scopes; scheduling/workflow/presentation have zero matches.

| File | Lines (actual) | Production referrers (semantic call sites, not imports/type-mentions/tests) | Verdict |
| --- | --- | --- | --- |
| app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumePolicy.kt | 60 | **1** — BatchResumeGateDecider.kt:34–39 (decider itself live via BatchResumePlanner.kt:257) | **INLINE/COLOCATE** into app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumeGateDecider.kt (44 lines, pure companion objects) as a private file-level object with the byte-identical `nextStage`/`NextStage` body. Both files are pure decision objects, so nothing is welded into a mega-orchestrator and no callback plumbing is touched; the independently named pure decision survives inside the decider file. Zero direct test references to `BatchResumePolicy` (test-scan hits were MemoryPressurePolicy only), so BatchResumeGateDeciderTest.kt and BatchResumeAndDisplayContractTest.kt pass unchanged — **no test deletion**. Net: −1 file, LOC conserved (~60 lines relocated) |
| app/src/main/java/eu/kanade/translation/pipeline/MemoryPressurePolicy.kt | 69 | **2** — TranslationManager.kt:755 (workflow), ReaderTranslationController.kt:1242 (reader UI); + MemoryPressurePolicyTest, StandardPipelineCoexistenceTest | **KEEP** — shared multi-caller classification policy |

Size-excluded (enumerated for completeness, >100 lines, not in scope set): app/src/main/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobSignalAdapter.kt (167), app/src/main/java/eu/kanade/translation/pipeline/adaptive/DevicePagePermitGate.kt (169), app/src/main/java/eu/kanade/translation/pipeline/batch/BatchWriteGate.kt (580 — cohesive batch policy, do not inline per scope guard). Domain module: zero matches.

## 5. Keep list (verified live — hard do-not-purge)

- PageLifecycle/PageView (prior F08 disproved; active via getters/equality/Flow).
- MaskGeometry helpers (`buildComponents`:520, `buildRowOffsets`:527, `requireValidDimensions`:244, `setErrorMessageRaw`:116 — all have live callers).
- TranslationPreferences live accessors incl. GPU/CPU controls (`translation_hardware_accelerator`, `paddle_ocr_*`, `translation_vision_gpu_acceleration`).
- `translateSinglePageOnnx`/`translateSinglePageHttpRender` wrappers (6 live call sites).
- CollisionRelaxation live roots (§3 row 4).

## 6. Deferred / owner-gated / recommended-removal (NOT assumed dead beyond HEAD reachability)

Liveness verification surface for everything marked dead here: repo-wide reference resolution across Kotlin/Java (production + tests), method references, string literals, **and every non-Kotlin/Java file (XML/resources, JSON, docs, `app/proguard-rules.pro`)** — zero non-production, non-test references to any candidate or island class name. History note: `0289de32` is the squashed ROOT of the 73-commit available history, so all these files "first appear" there by construction; this is recorded as a first-appearance fact, **not** an orphaning proof.

| Item | Status | Disposition |
| --- | --- | --- |
| Unwired rendering-engine island (TLP methods + CR ladder + AdaptiveBandPlanner.kt + MaskTextRegionPlanner.kt: ~2.3k prod LOC + ~0.7k test LOC) | DEAD at HEAD (static reachability; no runtime claims) | **RECOMMEND REMOVE in Step 1 as one coherent cleanup** — owner sign-off not required merely because orphan tests cover non-production helpers. Gate: behavior baseline = existing test suite green immediately before and after; rollback = plain git revert. If a future resolver rewire is wanted, design fresh against the live `planPageInternal` contract |
| ChapterArtifactEngine `ocrCheckpointSidecarName`:968, `analysisChunkSidecarName`:984, `stagePayloadIsValid`:2191 | declaration-only dead (pure unused names) | **Deferred out of Step 1** — they sit in the persistence engine and the Step-1 guard excludes persistence internals; do not bundle. Left to the persistence-scope pass |
| `translationOnnxEp` (TranslationPreferences.kt:140), `translationExperimentalQnn`:186 | no-reader established (names + key strings, all file types) | **Optional accessor-only removal** — KDoc compat rationale is intent, not necessity (unknown keys persist independently); removing accessors retains keys/data, no storage purge. Keep or remove both acceptable; key strings stay reserved either way |
| `RollingAutoCoordinator` (62 refs) + `AutoWindowState.kt` models (`AutoChapterIdentity`, `AutoDeferralReason`, …) | LIVE today (TranslationManager, TranslationScheduler, ReaderTranslationController, ReaderAutoTranslationPageResolver + 5 test files) | Reader-queue replacement may remove after the queue gate — owned by reader-architecture agents; do not pre-empt, do not assume compiled types dead |
| `BatchResumePolicy`/`MemoryPressurePolicy`/gate files | live | out of purge; queue-unification design may revisit placement, not existence |

## 7. Net file-count forecast (this purge subset only; pending reader-UI deletions settle in the master final account)

| Scenario | Prod files | Test files | Net total | Note |
| --- | --- | --- | --- | --- |
| Conservative (Manifest A + IPR + BatchResumePolicy inline) | −**2** (InpaintingProviderRecovery.kt, BatchResumePolicy.kt) | −1 (InpaintingProviderRecoveryTest.kt) | **−3** | methods in A → 0 file delta; inline conserves LOC (~60 lines relocated); no test deletion for the Policy — GateDecider tests keep |
| + Render-island removal (Manifest B) | −2 more (AdaptiveBandPlanner.kt, MaskTextRegionPlanner.kt) → cumulative −4 prod | −2 more → cumulative −3 tests | **−7 cumulative** | before any queue-unification additions; CollisionRelaxation.kt and TextLayoutPlanner.kt stay (partial purge) |
| Proposed NEW files from this purge | 0 | 0 | 0 | no extraction/renames proposed here |

Deferred reader-UI type deletions (RollingAutoCoordinator / AutoWindowState.kt models, post-queue-gate, reader-architecture owners) are **excluded** from this subset forecast and settle in the master final reconciliation. Methods-only work deletes roughly **~160 LOC** conservative (incl. IPR 30 + its test 103), **~3.0k LOC** (prod+test, actual line counts) with the render island. Method-level purges reduce LOC, not file count — file-count reduction comes only from whole-file deletions and the one inline/colocate above.

## 8. Sequencing

1. Conservative Manifest A + IPR file + BatchResumePolicy inline/colocate into BatchResumeGateDecider.kt — immediately, no dependencies; GateDecider tests unchanged.
2. Render-island removal (Manifest B) — recommended** for planning; no confirmation gate** — subject only to the active-output baseline (test suite green immediately before/after) with git revert as rollback. Preserving dead ~2k LOC for a hypothetical future rewire defeats the requested purge; any future resolver rewire designs fresh against the live `planPageInternal` contract. After removal, run the occurrence-count pass on TextLayoutPlanner.kt/CollisionRelaxation.kt to catch newly exposed second-order orphans; fix stale KDoc (TLP:976–979, 2463–2469, CR:261, TLP:244).
3. Manual/Auto queue unification planning — after 1–2; RollingAutoCoordinator/AutoWindowState outcomes belong to the reader-queue replacement (master final account), not this purge.
4. ChapterArtifactEngine method purges + persistence internals — explicit non-Step-1; separate persistence-scope pass.
5. Batch-callback refactor remains explicitly out of scope.
