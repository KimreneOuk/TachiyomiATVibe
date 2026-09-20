# Ticket P4-01: Decompose ChapterProfileBatchCoordinator into phase workers

**Phase:** 4 | **Risk:** Medium | **Type:** Behavior-preserving structural refactor

## Current state (main @ `e4bec7b`)

`ChapterProfileBatchCoordinator.kt` = 4,256 lines after Phase 3's S8 removal. Internal phases
(from p3-scoping-report §4.1, re-verify line spans before extracting):
1. Pass 1 OCR preflight & memory gating (~366–930)
2. Analysis planning & chunk execution (~950–1279)
3. Profile reconcile/synthesis/freeze (~1298–1631)
4. Envelope planning & AI dispatch (~1656+)
5. Finalize & completion (~2005–2237 area)
6. Standard lane fallback (~2243–2488)
7. Work recovery, display-tail drain, disk transaction logging (~2523–4256)

## Changes

Extract phase workers into `pipeline/batch/` as focused classes (naming per audit: `PreflightWorker`,
`AnalysisWorker`, `ProfileReconciler`, `EnvelopeDispatcher`, `StandardLaneWorker`, `FinalizeWorker`,
`RecoveryWorker` — adjust to actual boundaries you find, but each worker < ~800 lines and owns ONE phase).
The coordinator becomes a thin router: constructs workers with its existing dependencies, sequences
phases, owns the top-level job/cancellation. Pure moves: bodies relocate with their helpers; state
passes explicitly (constructor-injected services + per-phase context objects). NO logic edits, NO
reordering, NO renames of public seams used by other files (BatchChapterTranslator, tests).

Rules:
- One worker per commit (reviewability); each commit compiles + focused batch suites green.
- `translator/analysis` package is consumed as-is (not moved — Phase 5 decides packages).
- If a span doesn't decompose cleanly (shared mutable state mid-phase), extract what is clean and
  leave the remainder in the coordinator with a comment — do NOT force a behavior-risking split.

## Verification

Full both-flavor suites green with ZERO test modifications (batch tests are the behavior contract —
if a test needs changing, the refactor changed behavior: STOP). `assembleDevDebug` green.
Coordinator file < ~900 lines (router + shared context).

## Commit(s)

`refactor(translation): extract <WorkerName> from ChapterProfileBatchCoordinator (n/N)`
