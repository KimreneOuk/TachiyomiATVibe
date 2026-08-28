# Task T901 — Batch Translation Verification vs Reader Translation

## Status
Investigation only. No code changes authorized in this task.

## Director's report (verbatim symptoms)

1. Batch translation does not work as intended as chunk with optimized
   scheduling.
2. Batch translation is lost after app cleared or restart.
3. Batch translation shows a configuration popup with a translation button
   that does nothing when clicked. Also, on chapters that previously went
   through batch translation or translation directly in the reader page, it
   still shows the configuration page instead of resuming translation.
4. Investigate flawed ownership transfer, disk I/O, and more.

## Goal

Determine, from source evidence, what the batch translation pipeline
actually does today, contrasted with the manual (reader) and rolling-auto
translation paths, and identify the root cause(s) behind each symptom.

## Environment notes

- Investigate the working tree as-is. It is intentionally dirty
  (`MangaScreenModel.kt` and `DownloadCache.kt` have uncommitted edits).
- Windows host. Use the grep/glob tools; `rg` is not on PATH in bash.

## Key source entry points

Batch core & scheduling:
- `app/src/main/java/eu/kanade/translation/batch/` (SequentialBatchCoordinator,
  BatchCoordinatorInterfaces, BatchContextFrontier, trackers, BatchResumeGateDecider,
  BatchProgressReconciler, BatchOomPolicy)
- `app/src/main/java/eu/kanade/translation/translator/StreamingChunkPlanner.kt`,
  `TranslationContextChunkPlanner.kt`, `BatchTranslationProtocol.kt`,
  `ContextualTranslationBatch.kt`, `ContextualRequestBuilder.kt`,
  `ContextualResponseParser.kt`, `StableBlockIds.kt`
- `app/src/main/java/eu/kanade/translation/scheduling/` (TranslationScheduler,
  TranslationExecutor, RollingAutoCoordinator, AutoWindowState)
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`,
  `TranslationSession.kt`, `TranslationPipeline.kt`, `ChapterTranslator.kt`

Persistence & ownership:
- `app/src/main/java/eu/kanade/translation/artifact/` (ChapterArtifactStore,
  ChapterArtifactManifest, ChapterArtifactLayout, ChapterDocumentIo,
  StageFingerprints, ArtifactContracts)
- `TranslationQueueStore.kt`, `ChapterTranslationStore.kt`,
  `ChapterTranslationSummaryStore.kt`, `ActiveChapterStoreRegistry.kt`,
  `CleanedImagePublisher.kt`
- `model/PageTranslationOwnership.kt`, `util/ResumeOrdering.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt`,
  `BatchTranslationForegroundPolicy.kt`

UI & reader paths:
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
  (batch confirm popup ~lines 800-1050, progress ~1400-1600)
- Reader manual/auto translation: `ui/reader/` screen models and the
  TranslationManager entry points they call (`translateChapter`,
  rolling-auto window updates).

## Relevant prior work (read only what your assignment needs)

- `Plan/active/2026-08-24-batch-translation-followups/` (revision.md,
  checkpoints.md, repair-order.md)
- `Plan/active/2026-08-23-batch-translation-deslimming/` (plan.md, progress.md)
- `Plan/active/2026-08-21-sequential-chunk-pipeline/` (design.md)
- `Plan/active/2026-08-21-batch-store-recovery/` (brief.md, checkpoints.md)
- `Plan/active/2026-08-19-batch-translation-reliability/` (phase-0/1/2 docs)

## Report contract

Specialists write to `engineering/` under this folder:
- `engineering/code-investigation-scheduling.md` (Specialist A)
- `engineering/code-investigation-persistence.md` (Specialist B)
- `engineering/code-investigation-ui-gating.md` (Specialist C)
- `engineering/review-verification.md` (Reviewer)

Every important claim must be classified VERIFIED / STRONG INFERENCE /
ASSUMPTION / UNKNOWN / CONTRADICTION and cite `file:line`. Include a
"root cause per Director symptom" section with confidence levels.

Return only: "Completed. <one-line result>. Report: <path>"
