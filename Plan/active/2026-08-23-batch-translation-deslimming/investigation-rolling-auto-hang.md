# Investigation: RollingAutoCoordinatorTest infinite hang (candidate 1)

Date: 2026-08-24 (session continued from batch-translation-deslimming)
Status: ROOT CAUSED AND FIXED — full class green (22/22), app suite unblocked.

## Objective

Diagnose with evidence why `eu.kanade.translation.scheduling.RollingAutoCoordinatorTest`
hangs indefinitely, blocking `:app:testStandardDebugUnitTest` for the whole repo.

## Reproduction evidence

- Full class run hangs at `older same-spec snapshot build cannot overwrite newer stage`
  (JUnit method order captured via init-script `testLogging.events(started,...)`):
  7 tests PASSED, then the target STARTED and never PASSED.
- Two `jstack` dumps of the hung Gradle Test Executor (~30 s apart):
  - Only blocking point in the whole JVM: `Test worker` parked in
    `runBlocking` inside the target test (`BlockingCoroutine.joinBlocking`).
  - `DefaultDispatcher-worker-1/2` parked IDLE, 0.00 ms CPU, zero BLOCKED
    threads → not a lock deadlock, not a livelock: a lost wakeup (an await on
    a `CompletableDeferred` nobody completes).
- Earlier assumption "order-dependent pollution from a predecessor test" was
  DISPROVEN: the target alone hangs too (two solo runs, exit 124). An initial
  "passed alone" observation was a shell artifact (`$?` read the exit of
  `tail`, not gradle) — no XML result for this class has ever existed on this
  machine.
- Instrumented probe (throwaway test class, println markers at every await,
  deleted after diagnosis) pinpointed the suspension:

      PROBE target: awaitPrepareStarted(0) returned
      PROBE target: olderBuild launched
      PROBE target: olderBuild coroutine running
      PROBE target: olderBuild emit READING done      ← last marker
      (gated resolver ENTERED never printed)

  → the test hangs at `buildStarted.await()`.

## Root cause

The test arms `blockNextBuild` AFTER `awaitPrepareStarted(0)` returns and
expects "the next snapshot build" to call `pageResolver` (where the gate
blocks). In that state no code path calls the resolver:

1. Admission is inline in `reconcilePass`
   (TranslationPipeline `RollingAutoCoordinator.kt:480-511`): while
   `prepareSinglePage` is parked at the executor gate, the whole loop
   coroutine is suspended INSIDE it — no further reconcile pass runs, so
   `computeDesiredSet`'s resolver call (`:576`) never happens again.
2. Prepare's own READING/CLEANING stage events have already populated
   `slotStates[0]` via `stageListenerFor` (`:832-852`).
3. `buildSnapshot` resolves the visible slot from transient state first
   (`:751`: `slotStates[visiblePageIndex]?.let { ... } ?: needsAutoWork(...)`)
   — non-null short-circuits the store/resolver lookup. With
   `configuredAheadTarget = 0` there are no ahead slots, so
   `aheadSlotState` (the only other resolver-backed lookup) never runs.

Therefore the gated "older build" the test waits for cannot exist:
`buildStarted` is never completed and `runBlocking` waits forever. This is a
TEST bug, not a coordinator bug — the slotStates-first snapshot resolution is
the coordinator's documented contract (snapshot building deliberately avoids
resolver/store calls when transient state exists).

Provenance: the test and the contradicting coordinator behavior were both
introduced in `efb8dee` (rolling auto coordinator). The last session's
`0b7c6d2` only changed the predecessor-job filter and prepared-channel
capacity — unrelated. The test has been a deterministic hang since it was
written.

## Fix applied

`RollingAutoCoordinatorTest.older same-spec snapshot build cannot overwrite
newer stage`: give the window one ahead page (`p1`, `updateWindow(identity,
0, 1, 2, ...)`). An ahead slot has no transient state, so listener-driven
snapshot builds must resolve it through the resolver-backed store lookup —
the gate becomes reachable and the race the test guards (older same-spec
build vs newer stage mutation) is exercised for real. Comment added stating
the constraint.

## Validation

- Target test alone: PASSED (`BUILD SUCCESSFUL`, exit 0).
- Full class: 22 PASSED, `BUILD SUCCESSFUL in 1m 4s` — first green run of
  this class on this machine.
- Full gate `spotlessCheck :app:testStandardDebugUnitTest`: spotless PASSED;
  the app suite COMPLETED (1m 59s) — **967 tests completed, 1 failed** —
  the single failure being the documented pre-existing
  `AotReportBubbleFillTest` pixel assertion (unrelated; verified pre-existing
  on HEAD in the prior session). `:app:testStandardDebugUnitTest` is a
  working gate for the first time on this machine.

## Evidence for the other follow-up candidates (collected during this dive)

- Candidate 2 (viewport-first / reader-auto alongside batch): suppression
  point is `TranslationManager.updateAutoWindow`
  (`TranslationManager.kt:799-802`): while a batch is active for the
  chapter, the rolling coordinator is shut down and the call returns — the
  reader's single-page lane is fully suppressed instead of coexisting.
  `reconcileAutoWindow` guards with `!isBatchTranslationActive(chapterId)`
  (`:817-821`).
- Candidate 3 (background survival): `ChapterTranslator` runs batch jobs on
  a plain `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
  (`ChapterTranslator.kt:177`, `launchTranslationJob :288`). No foreground
  service / WorkManager hosts translation (no translation service exists in
  the codebase) — process death on backgrounding kills the batch.
- Candidate 4 (OCR/inpaint double decode): OCR decodes the source page at
  `TranslationPipeline.kt:1910`; the inpaint stage re-decodes the same
  source at `TranslationPipeline.kt:2059` (design comment `:3704-3706`:
  "For the inpaint stage the page is re-decoded"). Each decode also re-runs
  the SHA-256 source fingerprint (`:4174-4176`). Third call site `:2727`
  (single-page path) decodes once per stage as well.
