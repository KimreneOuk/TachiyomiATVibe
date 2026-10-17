---
title: "S2 - scheduler, manager, pipeline, governor and teardown cutover"
kind: ticket
status: 1
assignee: "24062d6a-6932-4374-9777-496cb961c7d1"
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


## Execution entry — 17 October 2026

S1 independently accepted and locally checkpointed as `e2ebf51f`. S2 starts from that exact commit in clean branch/worktree `phase4-s2-facade-wiring`; local changes from S1, including unrelated LiteRT edits and validation caches, were not carried. Root coordinates mechanisms and builds. Existing implementer/reviewer are performing read-only source preflight before red-test and cutover edits. No S2 production behavior or validation result is claimed yet. Fresh-worktree Gradle stop has not been run; confirm machine idle before that one-time stop and first build.


## Partial checkpoint — 17 October 2026

S2 remains in progress. [Portable checkpoint/handoff](../../handoff-checkpoint/index.md) records completed prerequisite mechanisms, focused evidence, settled contracts and remaining work. Queue retention69 and boundary/authority33 focused StandardDebug gates passed; S2 facade/live-priority/stage projection/source reconcile/teardown cutover NOT complete. S1 ancestor is e2ebf51f. Fresh-worktree Gradle stop was performed once before S2 builds; do not repeat without confirming machine idle.

The earlier Files table's retained stranded-RUNNING reset is superseded: per-operation cancellation now belongs to the pipeline boundary's captured AttemptAuthority. The manual scheduler finally current-snapshot reset was removed in the same slice. Do not reintroduce it or adapt chapter-wide snapshot sweeps to queue-owned cancellation; migrate those call sites during facade cutover. The snapshot adapter must carry real demand/stage/deferral truth; a terminal-outcomes-only/Queued fallback is insufficient.

The reprepare-cap wording above is producer-scoped: retain existing cap3 on the old prepared-null producer until that producer retires. No cap counter is to be fabricated on single-page outcomes that cannot express staleness.
