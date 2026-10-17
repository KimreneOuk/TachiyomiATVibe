---
title: "S2 - scheduler, manager, pipeline, governor and teardown cutover"
kind: ticket
status: 0
assignee: ""
comments: none
---

# Goal

Make the queue the live reader execution engine behind the existing scheduler/manager seams, with zero reader-UI changes. The rolling coordinator is no longer constructed; its class remains compiled (retired in S4). Job/outcome ownership moves into the queue; the scheduler becomes the compatibility facade.

# Files

| Path | Action |
| --- | --- |
| `scheduling/TranslationScheduler.kt` | Construct one queue; `translatePage` delegates to `submitCurrent` (manual trigger); `updateAutoWindow` adapts to `bindSession` + `updateViewport`; job map and bounded outcome map ownership moves into the queue; temporary queue-outcome → `AutoTranslationSnapshot` adapter keeps `autoSnapshot` alive (counted, removed S4). Coordinator construction and the `beginAutoCancellation` epoch protocol become inert but stay compiled; `chapterCancellationEpochs`, store-fenced `markPageCancelled`, stranded-RUNNING NonCancellable reset, `markPageJobStuck` retained. |
| `workflow/TranslationManager.kt` | Keep session admission + BATCH suppression (`includePaused = true`) at the window entry; `admitChapterThroughQueue`/`admitRestoredPendingTranslation`/rekey path invalidate the queue session instead of `shutdownAutoCoordinator`/`cancelAutoTranslations`; `onMemoryPressure` reconciles queue deferrals. |
| `pipeline/TranslationPipeline.kt` | Live reader priority source replaces the fixed `INTERACTIVE` constants end-to-end; queue submissions carry `MANUAL` origin + `readerStreamFn` (AUTO enum/persisted evidence untouched); common runner keeps native-guard, budgets, retry, render gates. |
| `engines/translator/ProviderRequestGovernor.kt` | Waiter priority re-read live at selection + promotion wake re-running the grant loop; quotas, ordering age, reservation identity, admitted accounting preserved. |
| `workflow/ReaderTeardownCoordinator.kt` | Request abandonment delegated to the queue; `awaitReaderStop` awaits a `PendingNativeDrain` handle before store eviction/completion; lock-order test added (no inversion queue/native/store). |
| Tests | `TranslationManagerAutoArbitrationTest` invariants re-homed (admission/BATCH suppression/same-page promotion); `ReaderManualPreemptsAutoLeaseTest` replaced by same-job/no-restart promotion; `TranslationCoexistenceHarness`/`AdmissionTestFixture` gain a queue-bound mode; new viewport-promotion-without-restart and N-HTTP/N+1-native overlap tests; engine-epoch and rekey regression tests. |

# Work items

1. Queue construction and delegation at the scheduler boundary; manual facade resolves the admitted token and delegates once (no second worker path).
2. Snapshot adapter (temporary): queue typed outcomes → `AutoTranslationSnapshot` projection so the un-migrated `observeAutoSnapshot` UI keeps rendering truthfully.
3. Manager suppression/invalidation rewiring (batch admission, restore admission, download re-key).
4. Governor live priority + wake; pipeline priority threading. Map the live reader priority to native admission too: `PREFETCH_NEXT` must use `DevicePermitPriority.BACKGROUND` despite MANUAL lease origin, retaining background wait semantics. Promotion updates the same waiting native entry; it must not restart or reacquire work. The existing origin-based mapping would otherwise classify every queue request as foreground and apply the foreground wait timeout to prefetch.
5. Teardown drain-handle contract + lock-order regression test before cutover acceptance.
6. Re-home the arbitration/preemption test invariants listed above (files themselves deleted in S4).

# Test plan

Viewport promotion without restart/recharge (executor fake counts attempts; case 3 admission-time states); N HTTP overlaps N+1 native, native occupancy one, local serial via semaphore (case 5, coexistence harness); same-chapter BATCH suppression and reader-cancellation preservation (case 8); rekey + engine-epoch fencing regressions; teardown drain-handle and lock-order; adapter snapshot correctness vs queue outcomes.

# Gates

Focused scheduler/workflow/coexistence classes on both flavors; no coordinator test deletions yet; read-only Spotless. Out of scope: controller/ViewModel/resolver edits (S3), file deletions (S4).

## Settled staging correction — 17 October 2026

The cap of three reprepare retries applies specifically to stale prepared-page handoff (`RollingAutoCoordinator.handleStaleTranslate`), not to ordinary automatic executions, provider pauses, or terminal `SinglePageOutcome.Failed`. S1 uses only `translateSinglePage`, whose typed outcomes cannot identify this stale handoff. S1 must preserve terminal outcome semantics and must not invent a generic attempt counter or infer retryability from diagnostic text.

Implement the bounded stale-handoff policy at the typed prepared/stale boundary in S2, before production reader cutover. This is a mandatory S2 acceptance dependency; its absence is not a completed S1 test or an accepted loss of runtime protection. Keep attempt-ledger admission and provider cooldown budgets independent. S1 tests remain responsible for terminal failure retention and event/cooldown reconciliation that the single-page contract can express.
