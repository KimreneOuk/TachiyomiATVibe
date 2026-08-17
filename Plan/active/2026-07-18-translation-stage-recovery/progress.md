# Progress - Translation Stage Recovery

Date: 2026-07-18
Branch: `unified-translation-pipeline-cp0-cp10`

## Status by checkpoint

Reconciled against live code in `audit.md`. Verdicts supersede earlier
optimistic handoff notes.

- CP0 - DONE
- CP1 - NOT STARTED (deferred; blocking full reset UX)
- CP2 - DEFINED, UNUSED (reconciliation step wires one merge as proof)
- CP3 - LIVE in working tree only; committed `e99cd6b` is the old
  regression. B1 commits the fix.
- CP4 - PARTIAL (remote overlap works; ML Kit lane deferred)
- CP5 - HALF-DONE (gates real, callbacks empty, render=colors only)
- CP6 - NOT STARTED
- CP7 - NOT STARTED (B5 ships a scoped dependency-aware reset sheet)
- CP8 - PARTIAL + REGRESSION (queue-cancel removal causes bug 3)
- CP9 - BLOCKED ON ENV (re-attempt at session end)

## Session execution log

| Step | Status | Commit | Notes |
| --- | --- | --- | --- |
| Part A - audit.md + progress.md | done | (this commit) | grounded CP table |
| B1 - Bug 1 OCR/inpaint barrier | pending | | commit working-tree `BatchCoordinator.kt`; strengthen `BatchCoordinatorWiredTest`; new `BatchCoordinatorCancellationTest` |
| B4 step 1+3 - sync CANCELLED write + defunct ordering fix | pending | | extends `markPageCancelled` sync pattern to auto/all cancel paths |
| B3 - Bug 3 queue conflict resolution | pending | | same-source queue eviction + confirmation dialog + artifact-scan resume |
| B2 - Bug 2 displayReady flag + honest indicator | pending | | two-row Color/Display sheet, durable flag, reset matrix tests |
| B4 step 2+4 - toast + undo snackbar + indicator animation | pending | | |
| B5 - delete-with-summary sheet + preflight | pending | | CP7 scoped delivery |
| Part C - reconciliation commits (merge wire, render-join callbacks, sessionGeneration, unique temp filename) | pending | | one per commit |
| Validation - `spotlessCheck`, `assembleStandardRelease`, `testReleaseUnitTest`, `testStandardReleaseUnitTest` | pending | | env-blocked earlier; re-attempt at session end |

## Open risks

- `displayReady` reset must cover every invalidation path; test matrix
  enforced in B2.
- Sync `runBlocking` in cancel paths must stay off the UI thread;
  `cancelPageTranslation` is the template.
- B5 reset sheet counts from live two-artifact reality, not the plan's
  four-artifact ideal.
- Undo snackbar for batch cancel re-queues via `translateChapter`;
  `ArtifactScanResumeTest` must prove READY artifacts are reused.
- ML Kit single-page path remains barrier-bypassing by design;
  documented in header.
