# T909 — Campaign Complete

Date: 2026-08-29 · All 20 committed phases landed · Status: **COMPLETE** (manual smoke pending)

## Result

Every phase of the dismantling plan (1–20) was implemented, gated, committed,
independently verified, and pushed. The optional phases 21/22 remain deferred
per the plan's risk recommendation.

| God-file | Start | End | Δ |
|---|---|---|---|
| `TranslationPipeline.kt` | 5,415 | 1,079 | −4,336 |
| `ChapterTranslationStore.kt` | 2,552 | 2,025 | −527 |
| `TranslationManager.kt` | 2,083 | 1,240 | −843 |
| `ChapterArtifactStore.kt` | 1,504 | 998 | −506 |
| `RoiPageRecognitionEngine.kt` | 1,517 | 953 | −564 |
| `ProviderRequestGovernor.kt` | 828 | 638 | −190 |
| `ChapterTranslator.kt` | 807 | 755 | −52 (wrapper delete) |
| **Total** | **15,706** | **7,688** | **−7,618 (49%)** |

The extracted code lives in 24+ named components under `pipeline/` (+`pipeline/batch/`),
`manager/`, `store/`, `artifact/`, `recognition/`, `translator/`, `legacy/`.

## Process record

- 36 commits from `b8a0a74` (Phase 1) to `43f8de2` (Wave 5 report) — every
  phase its own revertible commit, per the plan's contract.
- Gates per phase: targeted tests → full suite (`eu.kanade.translation.*` +
  manga/reader/data/extension UI tests), green after every phase.
- Independent full-suite verification by the Main Leader after each wave.
- Special audits honored: all 11 `durableStatusCache` clears routed through
  `DurableChapterStatusResolver`; bitmap recycle/CancellationException parity
  checked 1:1 across the ONNX and batch moves; the batch closure web converted
  closure→class members only (never restructured).
- New test assets created as prerequisites: `deleteTranslation`/reset ordering
  characterization test (Phase 19).

## Outstanding

1. **Manual smoke (Director, on device):** single-page translate (Phases 12/14),
   batch translate + resume mid-batch + pause/stop (Phase 20). All automated
   gates green; this is the last acceptance step.
2. Optional Phases 21 (store stage-merge engine, HIGH) and 22 (AOT split, needs
   corpus harness first) — deferred; revisit only with their prerequisites funded.

Wave deliveries: `reports/wave1-delivery.md` … `wave5-delivery.md`.
