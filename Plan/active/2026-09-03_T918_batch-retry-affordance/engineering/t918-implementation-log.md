# T918 implementation log — batch retry affordance (field defect)

Implementer log for Plan/active/2026-09-03_T918_batch-retry-affordance.
Branch: `fix/batch-retry-affordance` (created from `main` @ checkpoint/t917-release).
No pushes. Specific-path `git add` only.

## Defect (recap)

A batch cancelled mid-run hits the ChapterTranslator CancellationException
branch (ChapterTranslator.kt:722-729): `tracker?.abort(pages, "Batch cancelled")`
fires only when the queue entry is already gone, and the removed entry's
`statusFlow()` simply stops (no terminal emission). Consequences:

1. The screen model's projected `item.translationState` stays stranded at
   QUEUE/TRANSLATING/PAUSED forever; `translationIndicatorTapAction` routes
   every tap into the progress drawer.
2. The progress sheet shows only the aborted banner — no Restart affordance
   exists anywhere. The chapter is unrestartable from the UI.

## Changes

| File | Lines | Change |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt` | 285-316 (`forSheetRetryAction`), import at line 8 | New pure truth projection: Retry offered only for a terminal-aborted snapshot or a terminal ERROR batch (FINISHED phase + ERROR state) AND only when the caller wired a restart callback. Label/contentDescription = "Retry translation" (P5 truth-named wording). FINISHED + non-failed snapshots never offer Retry. |
| `i18n-at/src/commonMain/moko-resources/base/strings.xml` | 161 | `<string name="manga_batch_retry">Retry translation</string>` directly under `manga_batch_aborted` (160). Base English only. |
| `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt` | 99-107 (`onRetry` param), 185-210 (Retry button) | Optional `onRetry: (() -> Unit)? = null` (last param; null keeps today's banner-only shape). When the truth offers a retry AND `onRetry != null`, a full-width primary Button renders directly under the aborted banner: PlayArrow icon, `stringResource(ATMR.strings.manga_batch_retry)`, semantics `contentDescription` from the truth. |
| `app/src/main/java/eu/kanade/translation/model/TranslationUiProjection.kt` | 33-49 (`reconcileAbortedBatchState`) | Pure mapper: QUEUE/TRANSLATING/PAUSED -> NOT_TRANSLATED (restartable); already-honest states (NOT_TRANSLATED/TRANSLATED/ERROR/READY_WITH_WARNINGS) -> null (never touched). |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt` | 606 + 615-645 (`reconcileAbortedBatch`, called from `updateTranslationProgress`), 992-1006 (`retryBatchTranslation`) | Reconciliation + restart entry point (details below). |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt` | 334-341 | Wires `onRetry = { screenModel.retryBatchTranslation(dialog.chapterId) }` into the single `TranslationProgressSheet` call site. No duplicate progress observers. |
| `app/src/test/java/eu/kanade/translation/model/TranslationUiProjectionTest.kt` | 47-63 | Direct (non-reflective) test for the pure reconciliation mapper. |

### `MangaScreenModel.retryBatchTranslation(chapterId)`

Resolves the chapter exactly like the cancel snackbar's Undo path
(MangaScreenModel.kt:956-961): `successState.manga` + the chapter item, then
`translationManager.translateChapter(manga, item.chapter)`. `translateChapter`'s
artifact scan (BatchResumeGateDecider) reuses READY work, so completed pages
are not re-OCR'd — pinned by the pipeline test below.

### `MangaScreenModel.reconcileAbortedBatch(chapterId, progress)`

Called first thing in `updateTranslationProgress` (the existing
`observeBatchProgress` collector — no new observers). Guards:

- `progress?.aborted != true` -> no-op (only terminal-aborted snapshots
  reconcile; live snapshots never carry `aborted=true`).
- `translationManager.getQueuedTranslationOrNull(chapterId) != null` -> no-op
  (never clobber a batch queued after the abort — Order(2) pins this).
- `TranslationUiProjection.reconcileAbortedBatchState(item.translationState)`
  null -> no-op (already-honest projection).

Otherwise sets `item.translationState = NOT_TRANSLATED` via the existing
`updateSuccessState` mechanism, which makes `translationIndicatorTapAction`
route taps to START again.

## Seam choice (and why)

**Reconciliation lives in MangaScreenModel's progress observation (the
task-authorized alternative), NOT in the ChapterTranslator catch branch.**

Rationale: after `removeFromQueue`, the entry is detached — its `statusFlow()`
has no remaining UI observer, so a status change on the catch branch is
UI-inert. The durable per-chapter status is DERIVED (store projection), not a
stored enum; the stranded value lives only in the screen model's
`ChapterList.Item.translationState` projection. The observation seam is where
the stranded state actually exists, where the queue-membership guard is
available (`getQueuedTranslationOrNull`), and where it is idempotent (the
registry keeps the terminal snapshot; reconciliation only fires on the
aborted-terminal emission).

The pipeline itself needed no fix: the exact defect choreography (cancel
mid-run, entry already gone) already lands a typed terminal-aborted snapshot
("Batch cancelled"), removes the queue entry, and settles the detached entry
NOT_TRANSLATED (ChapterTranslator.removeFromQueue). The restartability was
there; the UI affordance and the un-stick were missing.

## Tests (TDD)

RED commit: `74abc05 test(batch): pin the cancelled-batch restart affordance contract (T918 RED)`.

### RED evidence (before implementation)

- `T918SheetRetryTruthTest` — 4 tests / 4 failures, all named assertions via
  the reflection bridge (P5 precedent), never compile errors:
  - `aborted snapshot with a wired callback offers the truth-named retry control`
    — "T918 RED defect (no restart affordance): TranslationUiTruth has no
    sheet retry projection ..."
  - `aborted snapshot without a wired callback offers no retry control`
  - `successfully finished snapshot never offers a retry control`
  - `terminal failed snapshot with a wired callback offers the retry control`
- `MangaScreenModelCancelledBatchReconciliationTest` — 2 tests / 1 failure:
  - Order(1) `cancelled batch strands chapter in translating state with no
    restart affordance` — FAILED: "Timed out after 10000ms waiting for: T918
    defect: the stranded chapter must land restartable (NOT_TRANSLATED) once
    the aborted terminal snapshot is observed with no queue entry" (state dump
    showed the item stuck at TRANSLATING with the aborted snapshot visible).
  - Order(2) `aborted snapshot does not clobber a newly queued batch for the
    same chapter` — passed pre-implementation (bounded negative probe; the
    guard behavior it pins must survive the fix).
- `T918CancelledBatchRestartTest` — passed at RED by design: this leg pins the
  EXISTING pipeline restartability contract the new affordance relies on
  (the defect was that the UI could never trigger it). Real-graph choreography:
  - p1 parks at PROVIDER_START after its paid call started (counter=1); p0
    completes transport+OCR+inpaint.
  - Real `removeFromQueue` + `batch.job.cancel()`; job joins; registry
    terminal snapshot asserted aborted=true / "Batch cancelled" / ERROR /
    FINISHED; queue empty; detached entry NOT_TRANSLATED.
  - Restart (same chapter, same store, D5/D10-seeded provenance): completes
    the chapter, both pages render READY, NEITHER page re-decoded
    (NATIVE_ACQUIRE arrival counts unchanged), paid transport calls
    p0=1 (no second call), p1=2 (interrupted + exactly one retry).

### GREEN

- `T918SheetRetryTruthTest`: 4/4 pass.
- `MangaScreenModelCancelledBatchReconciliationTest`: 2/2 pass (Order(1) now
  reconciles to NOT_TRANSLATED; Order(2) guard still holds).
- `TranslationUiProjectionTest` new case `aborted batch reconciles only
  stranded in-flight states`: pass.

## Deviations

1. **D5/D10 reuse-fixture seeding in the pipeline test** (documented in the
   test's class doc): the memory-only harness store does not run the durable
   artifact-store persist chain (out of T918 scope), so run-1's page records
   are re-seeded to the exact engine provenance the resume gate requires
   (`expectedFingerprints()` probe from a throwaway harness + real
   `computeSourceFingerprint`, `cleanedImagesOnDisk` seam) before the restart.
   The cancellation, terminal snapshot, queue settlement, and restart work
   routing are all the REAL graph.
2. **Queue seeding via reflection** on the private `_queueState` backing flow:
   the public `queueState` is a read-only projection; the backing flow is the
   SAME instance the CancellationException branch reads.
3. **NativeRunQuarantine constraint on choreography** (test design, not
   product change): native blocks run DETACHED and drain NonCancellable on
   cancellation, so a native invocation parked awaiting a signal only a later
   stage can fire is uncancellable. The test therefore parks page 1 at
   PROVIDER_START (its native invocation already exited), which is also the
   honest shape of the defect scenario (paid work cut mid-flight).
4. **TranslatingIndicator dead menu code**: noted only, not touched (out of
   scope).

## Verification

Mandated command: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testStandardDebugUnitTest --rerun`
Judged ONLY from the XML files under
`app/build/test-results/testStandardDebugUnitTest/`.

Final sweep XML counts (mandated `--rerun` sweep, BUILD SUCCESSFUL):

```
suites=201 tests=1454 failures=0 errors=0 skipped=0
failing suites: NONE
NormalMangaIsolationTest: tests=1 failures=0 errors=0
```

## Commits

- `74abc05` test(batch): pin the cancelled-batch restart affordance contract (T918 RED)
- `df4e6a9` fix(batch): retry affordance + stranded-state reconciliation for cancelled batches (T918)

Both commits build; the GREEN commit's content is exactly what the final
`--rerun` sweep validated (no other tracked modifications in the tree).
