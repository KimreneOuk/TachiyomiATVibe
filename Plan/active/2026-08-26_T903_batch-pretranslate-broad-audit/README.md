# Task T903 — Broad Audit: Batch (Pre-Translate) Pipeline

## Status

INVESTIGATION ONLY. No code changes are authorized in this task.

## Director's request (2026-08-26, verbatim intent)

1. Broad audit of Batch translation (pre-translate): how does it actually work?
2. The algorithm/step handling is unclear: how does it handle I/O, translation,
   OCR, and other scheduling?
3. Manual and auto (reader) translation work, but batch translation is
   extremely buggy.
4. With the Gemini FREE API it has become even worse.
5. Director is non-technical: the Main Leader will synthesize a plain-language
   executive report and decision options.

## Environment notes

- Investigate the committed working tree as-is (HEAD 926ae00, T902 phases
  A/B/C all landed). Only docs/plan churn is uncommitted — code is clean.
- Windows host. Use the grep/glob/read tools; `rg` is not on PATH in bash.
- Do NOT read `AGENTS.md` at the repository root. Read only what your
  assignment lists.

## Key source entry points

Batch core & scheduling:
- `app/src/main/java/eu/kanade/translation/batch/` (SequentialBatchCoordinator,
  trackers, BatchResumeGateDecider, BatchProgressReconciler, BatchOomPolicy,
  BatchTranslationDiagnostics)
- `app/src/main/java/eu/kanade/translation/scheduling/` (TranslationScheduler,
  TranslationExecutor, RollingAutoCoordinator, AutoWindowState)
- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
  (manual path ~line 648; STAGED BATCH path ~line 1119 onward; envelope
  lifecycle ~1700-2000; `markBatchTranslationFailed` ~2748)
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
  (queue, `isBatchTranslationActive`, progress flows ~1184)

OCR / chunking / AI request protocol:
- `app/src/main/java/eu/kanade/translation/translator/` (StreamingChunkPlanner,
  TranslationContextChunkPlanner, BatchTranslationProtocol,
  ContextualRequestBuilder, ContextualResponseParser, ContextualTranslationBatch,
  StableBlockIds, AiTranslationRetryController, TranslationRetry,
  GeminiTranslator, GeminiRequestPayload)
- OCR/inpaint execution lives inside TranslationPipeline.kt (ONNX OCR stages,
  OOM policy diagnostics ~4032, 4210)

Persistence / I/O:
- `app/src/main/java/eu/kanade/translation/artifact/` (ChapterArtifactStore,
  ChapterArtifactManifest, ChapterDocumentIo, StageFingerprints)
- `ChapterTranslationStore.kt`, `TranslationQueueStore.kt`,
  `ActiveChapterStoreRegistry.kt`, `CleanedImagePublisher.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/translation/`
  (TranslationForegroundService.kt, BatchTranslationForegroundPolicy.kt)

UI entry / reader contrast:
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
  (batch confirm + enqueue ~800-1050, progress ~1400-1600)
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
  (manual/auto translation entry, batchTranslationState handling)

Relevant tests (behavioral evidence):
- `app/src/test/java/eu/kanade/translation/batch/` (Phase0BatchTranslationCharacterizationTest,
  BatchTranslateBlockMergeTest, BatchTranslationDiagnosticsTest)
- `app/src/test/java/eu/kanade/translation/translator/` (TranslationRetryTest,
  AiTranslationRetryControllerTest, GeminiRequestPayloadTest,
  Checkpoint2IntegrationTest)

## Relevant prior work (read only what your assignment needs)

- `Plan/active/2026-08-25_T901_batch-translation-verification/engineering/`
  (code-investigation-scheduling.md, code-investigation-persistence.md,
  code-investigation-ui-gating.md, review-verification.md) — verified defects
  found BEFORE the T902 fixes.
- `Plan/active/2026-08-25_T902_storage-io-batch-behavior-legacy-cleanup/`
  (README.md, checkpoints.md, engineering/) — what was actually changed.
  All T902 phases A/B/C1-C5 + final audit fix are COMMITTED (HEAD 926ae00).
- `Plan/active/2026-08-19-batch-translation-reliability/` and
  `Plan/active/2026-08-21-sequential-chunk-pipeline/` (older background).

IMPORTANT: T901 reports describe PRE-T902 code. Any claim you reuse from them
must be re-verified against the current tree before you cite it as fact.

## Director amendment (2026-08-26) — UI/UX consistency symptoms

5. SILENT START: after triggering batch translation, the UI shows nothing
   for a few minutes; the batch translation UX only appears later.
   Director: "every interaction must have a reaction."
6. READER EXIT HIDES BATCH UX: entering the reader while batch translation
   is running, then exiting, leaves the user unable to trigger or see the
   batch translation UX, while the batch itself may or may not still be
   running — indeterminate from the UI.

Entry points for symptom 5/6 investigation:
- `MangaScreenModel.kt` (confirm/enqueue ~800-1050; progress observation
  ~1400-1600), `MangaScreen.kt` (~344 confirm dialog wiring),
  `ChapterTranslationIndicator.kt`
- `TranslationManager.kt` progress/state flows (~1184) and
  `isBatchTranslationActive` gates (~205-312)
- `ReaderViewModel.kt` batchTranslationState lifecycle (~383-406, 641, 792,
  1964-2019, 2278, 2458-2476)
- `TranslationForegroundService.kt` / `BatchTranslationForegroundPolicy.kt`
  (when the notification appears relative to enqueue/translate states)
- Download path between confirm and first translated page (queue → download
  → OCR → first progress emission)

## Report contract

All reports go under this folder. Every important claim must be classified:

VERIFIED / STRONG INFERENCE / ASSUMPTION / UNKNOWN / CONTRADICTION

and cite `file:line` evidence. Distinguish committed behavior from uncommitted.

Assignments:
- Technical Lead → `engineering/batch-pipeline-architecture.md`
- Failure-mode Auditor → `review/batch-failure-mode-audit.md`
- UX Observability Auditor → `review/batch-ux-observability-audit.md`

Return only: "Completed. <one-line result>. Report: <path>"
