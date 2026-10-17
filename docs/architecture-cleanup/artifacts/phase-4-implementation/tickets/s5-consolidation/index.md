---
title: "S5 - consolidation and regression validation"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Goal

Consolidated regression validation of the whole Phase 4 surface, adapter-zero proof, behavior-ledger sign-off, and artifact updates. Broad suites are justified here by the consolidation boundary.

# Work items

1. Focused-then-broad run plan: queue + scheduler + workflow + coexistence + reader-UI classes first on StandardDebug, then DevDebug; if green, one broader translation-package run per flavor (the justified consolidation sweep). Guarded, attempt-specific evidence, fresh XML preserved.
2. Temporary-adapter census: snapshot adapter and every counted shim must be gone; record the census result.
3. Behavior ledger table (plan §Required behavior ledger) signed off item by item against executed tests; items only expressible on device are recorded as deferred gates, not claims.
4. File accounting reconciliation vs the plan table (−1 production, −7 tests, rename net zero); deviations recorded, not silently compensated.
5. Artifact updates: this index and the survey/plan gain execution evidence links; the phases index Phase 4 row status is updated only per parent policy.
6. Spotless full-inventory pass; known LiteRT baseline hunks expected, no new violations.

# Gates

All executed runs green or explicitly owned failures preserved; no unresolved adapter; ledger complete; device/physical gates (foreground latency, serial-vs-overlap acceptance, teardown-on-hardware) recorded as open Phase 4/8 gates. No push. Commit discipline per parent; slices committed only after independent review.

# Execution evidence (18 October 2026, host worktree `phase4-s5-consolidation` at `4c3a683a`, zero source changes)

Full detail: durable notes at `epics/3629f617-2f5f-4113-8a68-bd214874467d/artifacts/architecture-cleanup-resume/phase4-s5/index.md` (Traycer artifact; evidence dirs under `C:\Users\ADMIN\AppData\Local\Temp\opencode\phase-4-s5\` with one-use launch claims and fresh XML per attempt, guarded by `validation/windows-run-guard.py`).

| Item | Result |
| --- | --- |
| Focused 27-class set (queue/scheduler/workflow/coexistence/pipeline/reader-UI) | 223/0/0/0 on StandardDebug AND DevDebug |
| Broad translation-package sweep | StandardDebug 2168/1/0/2 (1 = single-occurrence `EngineEpochStopRaceTest` 60s-await timeout, green in 3 other runs at same bytes); DevDebug 2168/0/0/2. Three owned-finding classes excluded with dedicated evidence. |
| Temporary-adapter census | **0** (single sanctioned historical comment, `AutoProviderCallDrainsNotCancelsTest.kt:63`) |
| Behavior ledger | Signed item-by-item (see resume artifact); controller-level dedup (item 1) and five-state promotion remainder + device gates recorded as deferred, not claimed |
| File accounting | **Deviations recorded**: production net 0 vs plan −1 (`OperationPaidCallLifecycle.kt`, +1); tests net −3 vs plan −7 (`PaidCallLifecycleTest`, `SchedulerTeardownDrainTest`, `ReaderPageWorkResolverLifecycleTest`, +3); renames net zero, now 2 (R059 resolver + R041 test) |
| Spotless `:app:` + `:domain:` full inventory (forced rerun) | Clean, 0 violations (the "known LiteRT hunks" expectation was stale) |

**Status: NOT green — owned findings require S5 r1.** (1) `TranslationScheduler.close()` drains unbounded (deterministic `PipelineStallWatchdogTest` teardown hang; jstack-pinned, reproducible). (2) `P5HonestOutcomeTypingTest` + `ForegroundProviderWindowFairnessTest` read the retired reflective `manualOutcomes` seam — Phase-4-introduced (pass at `b10c8b66`); semantic re-validation pending the re-point to `manualOutcomeFor`. (3) `EngineEpochStopRaceTest` one-shot 60s await timeout under sweep context — reproduction required. No commit; no push.

# S5 r1 execution evidence (18 October 2026, coordinator-authorized remediation)

Diff vs `4c3a683a`: 7 code/test files + 3 docs. Full detail in the Traycer resume artifact `architecture-cleanup-resume/phase4-s5` (r1 section on top).

| Fix | Verification |
| --- | --- |
| F1: `close()` drain join bounded (`withTimeoutOrNull`, `CLOSE_DRAIN_JOIN_TIMEOUT_MS = 10s`) | `PipelineStallWatchdogTest` 3/0 both flavors (was a deterministic hang) |
| F2: three classes re-pointed to `manualOutcomeFor()` (reflection removed) | P5 6/0 + Fairness 1/0 both flavors — **all T917 typing conditions pass the live facade; no production outcome-typing regression existed** |
| F4 (new): explicit tap vs a retained `Stalled` terminal now re-drives (`submitCurrent` `stalledRetryable`; revealed by F1's fix — the hang had masked this deterministic failure) | New queue pin test; `ReaderTranslationQueueTest` 62/0 both flavors |
| F3 mode 2: missing `closedSignal` await added before the `closedFlag` assert | `EngineEpochStopRaceTest` 6/0 ×3 post-fix |
| F3 mode 1: epoch-retry second-park 60s timeout | **OPEN** — reproduced at HEAD bytes pre- and post-r1; 3×6/0 at base `b10c8b66`; in-flight EngineLane retry chain needs a dedicated diagnosis slice |
| F5 (new): `StandardPipelineCoordinatorTest` suite-context interference on Dev (1 sample) | Green isolated (12/0), green in full Std suite (2179/0); recorded |

Consolidation at r1 bytes: StandardDebug full package **2179/0/0/2 (no exclusions, exit 0)**; DevDebug full samples 2179/2/0/2 (F5) and 2179/1/0/2 (F3 mode 1) — both intermittent context failures owned above; Spotless forced rerun clean (0 violations). No commit; no push.
