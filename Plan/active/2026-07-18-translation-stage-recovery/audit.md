# CP Execution Audit - Translation Stage Recovery

Date: 2026-07-18
Auditor: live-code trace on branch `unified-translation-pipeline-cp0-cp10`
(working tree state, including uncommitted edits).

Supersedes the optimistic checkpoint claims in `plan.md` and the handoff
notes. Every row below is backed by `file:line` evidence traced during
this audit; see the per-CP notes.

## Verdict summary

| CP | Plan claim | Live reality | Verdict |
| --- | --- | --- | --- |
| CP0 | Freeze contracts and baseline tests | Tests added for coordinator, lifecycle policy, store, cleaned-image publication, planner flush, reader retry | DONE |
| CP1 | Stable OCR block IDs, artifact/configuration identities, independent `PageWorkPlan`, stage-specific errors | None of these exist. `TranslationLifecyclePolicy.NextStage` is still `SKIP` / `RENDER` / `INPAINT` / `FULL`. Single shared `PageTranslation.errorMessage`. No `OcrArtifact`/`TranslationArtifact`/`InpaintArtifact`/`RenderArtifact` types. No `PageWorkPlan`. | NOT STARTED |
| CP2 | Wire stage-specific merge contracts; add atomic invalidation APIs | `TranslationStagePatch`, `InpaintStagePatch`, `RenderStagePatch`, relevant-field fingerprints, and `ChapterTranslationStore.mergeTranslation/Inpaint/Render` exist. Zero production call sites. | DEFINED, UNUSED |
| CP3 | Correct OCR producer + explicit all-OCR barrier before any inpaint | True in the working tree only (`BatchCoordinator.kt:77-88`, `check(terminalOcrKeys == expectedPageKeys)`). The committed `e99cd6b` "CP3" still fed the translation queue after `ocrJobs.awaitAll()` (the old regression). The uncommitted working-tree edit moved `translationQueue.send(ref)` into the per-page OCR job. Manual/auto single-page path bypasses the barrier by design (single page, no chapter invariant). | LIVE (uncommitted fix only) |
| CP4 | Remote translation overlaps OCR; ML Kit serialized with native/local lane after barrier | Remote overlap works (translation channel fed per-page during OCR sweep). No ML Kit serialization lane exists; ML Kit shares the single-page path with remote. | PARTIAL |
| CP5 | Real per-page render join, bounded render worker, render overlaps inpaint of another page, consistent layout-failure reporting | `nativeGate`/`translationGate` CompletableDeferred join is real (`BatchCoordinator.kt:29-32, 112-122`). But `RenderJoinWorker.onNativeBranchDone`/`onTranslationBranchDone` are still empty (`TranslationPipeline.kt:1543-1549`). No separate render worker (render runs serially in a single `async` after the full inpaint sweep). No render/inpaint overlap. `renderStatus = READY` is set after `RenderColorEstimator.recomputeFor` only, never after overlay layout (`TranslationPipeline.kt:945-956`, `:2342-2345`). Commit `885b181` "CP4/CP5" touched only test/docs files, zero production code. | HALF-DONE |
| CP6 | Carry `completedPages` through inactivity flush, real mutex for planner acceptance/flush/provider admission, shared provider admission, persist translated blocks before render wait | `InactivityFlusher` translation mutex is defined but normal `accept()` and size-driven provider work bypass it. Final inactivity flush still drops the completion set in some paths. | NOT STARTED |
| CP7 | Dependency-aware reset UX (translation / inpaint / OCR / everything), show cascade before confirmation, clear reader streams/overlay cache after reset | Chapter-level REVIEW action added. No selective reset actions exposed in UI. No `ContinuationPreflight`, no cascade summary. The existing `ChapterTranslationAction.DELETE` is a blunt delete-all. | NOT STARTED |
| CP8 | Generation-aware reconciler, ownership-guarded stranded-page repair, legacy migration, remove duplicate contracts | `BatchProgressReconciler` exists and is generation-aware. No legacy migration. No stranded-page ownership guard beyond the reconciler's counting. CP8 deliberately removed the batch-queue cancel on chapter navigation (`ReaderViewModel.kt:1899-1908`); the comment justifies the change for reader teardown, but it is the direct cause of bug "new chapter batch resumes old chapter." | PARTIAL + REGRESSION |
| CP9 | `spotlessCheck`, `assembleStandardRelease`, `testReleaseUnitTest`, `testStandardReleaseUnitTest`, device tests | Never run during this plan - `JAVA_HOME` unset, no `java` available in prior sessions. Env-blocked. | BLOCKED ON ENV |

## Grounded artifact facts

The plan and design propose four first-class artifacts
(`OcrArtifact`, `TranslationArtifact`, `InpaintArtifact`, `RenderArtifact`).
The live code persists two durable entities and treats the other two as
inline flags:

1. OCR JSON document (`PageTranslation.blocks` + `inpaintMaskBoxes` +
   per-stage statuses). Detection is fused with OCR (plan rule 1), so
   there is no independent detection artifact.
2. Inpaint cleaned-image companion file on disk plus `cleanedImageName`
   and `inpaintRevision` references in the OCR JSON.

Translation text is stored inline on each block
(`TranslationBlock.translation`); there is no separate translation
artifact, no parent OCR artifact id, and no provider/model/prompt
signature persisted with it (initial-findings F7).

Render is a single `renderStatus` enum plus per-block fill colors
produced by `RenderColorEstimator`. Overlay layout (`TextLayoutPlanner`)
is not persisted; it runs every reader page bind
(`TranslationOverlayView.kt:70`) and discards detailed layout failures
(initial-findings F10).

CP1 (deferred) is the work that would promote translation and render to
first-class artifacts with parent identities and configuration
signatures.

## Reported-bug root-cause map

The four user-reported bugs map to specific CP gaps:

- **Bug 1 - inpaint runs while OCR still runs.** Batch path is correct
  in the working tree (`BatchCoordinator.kt:77-88`). The observed
  symptom is either the committed `e99cd6b` regression or the
  manual/auto single-page path (`TranslationPipeline.translateSinglePage`
  -> `recognitionEngine.inpaint` at `:2087`, `:2779`), which bypasses
  the chapter barrier by design. Fix B1 commits the working-tree edit
  and documents the bypass.
- **Bug 2 - indicator says N rendered; reader shows original + grayed
  overlay.** `TranslationBatchProgressTracker.kt:320-336` counts
  `renderStatus == READY` as rendered, but READY is set after color
  estimation only (`TranslationPipeline.kt:953-954`, `:2343-2344`).
  The reader gate is `translatedStream != null`, which requires a real
  cleaned-image file on disk
  (`PageTranslationState.kt:20-21, 31-34`). The overlay draws
  independently of the translated-image gate on
  `shouldShowTranslationOverlay` (`PagerPageHolder.kt:468`,
  `WebtoonPageHolder.kt:425`). Fix B2 adds a durable `displayReady`
  flag and an honest two-row indicator.
- **Bug 3 - new chapter batch resumes old chapter.**
  `ChapterTranslator.launchTranslatorJob` picks the first queued
  chapter per source (`ChapterTranslator.kt:280-282`); the queue is
  persisted and rehydrated (`:128-131`). CP8 stopped reader teardown
  from touching the queue (`ReaderViewModel.kt:1899-1908`), so a stale
  queued chapter outlives the user's intent and runs before the newly
  requested chapter. Fix B3 detects same-source queue conflicts on
  explicit `Start Batch`, evicts stale queued entries while keeping
  their artifacts, and lets the new chapter's batch scan its own store
  via `BatchResumeGateDecider`.
- **Bug 4 - toggle off leaves dim; no toast.** `cancelAutoTranslations`
  only cancels jobs (`TranslationScheduler.kt:272-300`) without
  touching the store, so `isPageBeingTranslated`
  (`PagerPageHolder.kt:195-198`) keeps returning true.
  `cancelAllPageTranslations` does try the durable clear but launches
  it on `storeScope` AFTER `unregisterActiveTranslationStore`/`markDefunct`
  runs synchronously (`TranslationManager.kt:1147-1166`), so the clear
  is rejected as defunct. There is no translation-cancel toast or
  snackbar anywhere in the codebase. Fix B4 reuses the per-page
  `markPageCancelled` sync pattern from `cancelPageTranslation`
  (`TranslationScheduler.kt:499-513`), fixes the defunct ordering, and
  adds toasts, undo snackbars, and an indicator settle animation.

## Deferred

The following are recorded here so they are not re-discovered later.
They are intentionally out of scope for this session:

- Full `ChapterWorkController` rewrite - the central architecture in
  `design.md`. Multi-week; defer until CP1 artifact identity and CP2
  merges land and prove out.
- `PageWorkPlan`, stable `blockId`, first-class `TranslationArtifact`
  and `RenderArtifact` (CP1). Required before the reset UX can show a
  true four-artifact cascade. Current B5 reset sheet is honest about
  the two-artifact reality.
- Real batch layout pass (persist `TextLayoutPlanner` output). B2's
  honest-indicator fix makes the UI correct without it.
- ML Kit serialization lane (CP4). The single-page path does not bite
  yet because it processes one page at a time.
- Legacy JSON migration (CP8). The legacy decoder keeps old data
  readable; lazy migration remains the plan but is not blocking.

## Validation status

This audit is a read-only investigation of live code on
`unified-translation-pipeline-cp0-cp10`. No production code was
changed. Gradle validation remains blocked on the missing `JAVA_HOME` /
`java` environment noted in the original plan; it will be re-attempted
at the end of this session.
