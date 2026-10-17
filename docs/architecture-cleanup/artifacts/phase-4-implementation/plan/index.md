---
title: "Phase 4 implementation plan - one reader queue, coordinator retirement"
kind: spec
status: 0
assignee: ""
comments: none
---

# Authority and reconciliations

The [Phase 4 gate](../../translation-simplicity-investigation/phases/index.md) and the [master API contract](../../translation-step1-plan/index.md#unified-reader-api-contract) with its [caller map](../../translation-step1-plan/reader-queue/index.md) govern. Deviations recorded here, none silent:

1. **Naming.** `ReaderPriority { CURRENT_PAGE, PREFETCH_NEXT }` only. "Speculative" is the prunable demand class (auto-origin, outside N/N+1, no explicit manual demand), not an enum value.
2. **Outcome types.** The queue reuses the existing `SinglePageOutcome` and `TranslationCompletionOutcome`; the Step-1 forecast to create them is already satisfied by Phase 3.
3. **Ahead policy.** Exactly one next page (window = N, N+1). The configurable ahead-count is retired as a requested behavior change; the stored preference key remains without migration.
4. **Lease origin.** All reader submissions use `PageWriteOrigin.MANUAL` regardless of trigger; `AUTO` stays a valid persisted origin. Manual-over-auto arbitration becomes explicit-demand precedence inside the queue.
5. **Concurrency.** At most two automatic operations, exactly one in native admission, REMOTE_IO may overlap native and HTTP, LOCAL_COMPUTE serialized by one queue-owned `Semaphore(1)` around the whole runner. The bounds are constructor constants, so a serial-only policy is a parameter change if measurement demands it. Serial-vs-overlap acceptance remains a deferred measured decision (device gate), not a host-claimable result.

# Target design

## Queue file and types

New `app/src/main/java/eu/kanade/translation/scheduling/ReaderTranslationQueue.kt`, co-locating the contract types:

```kotlin
enum class ReaderPriority { CURRENT_PAGE, PREFETCH_NEXT }
enum class ReaderTrigger { MANUAL_TAP, AUTO_VIEWPORT }
data class ReaderSessionToken(val chapterIdentity: String, val readerGeneration: Long)
data class ReaderPageWork(val pageKey: String, val readerPageIndex: Int, val streamFn: (() -> InputStream)?)

sealed interface ReaderSubmission {
    data class Queued(val pageKey: String) : ReaderSubmission
    data class Promoted(val pageKey: String) : ReaderSubmission
    data class Existing(val outcome: SinglePageOutcome?) : ReaderSubmission
    data class Rejected(val outcome: SinglePageOutcome.Rejected) : ReaderSubmission
}

sealed interface ReaderDrainResult {
    data object Completed : ReaderDrainResult
    data class PendingNativeDrain(val completion: CompletableDeferred<Unit>) : ReaderDrainResult
}
```

Class shape per the master contract: constructor `(executor, scope, workerDispatcher, storeResolver, nowEpochMs, cancellationWaitMs = 2_000L)` plus the two concurrency constants (`maxAutomaticOperations = 2`, local-compute semaphore). Methods: `bindSession`, `submitCurrent`, `updateViewport`, `setAutoEnabled`, `cancelPage`, `outcomes`, `cancelSession`, `closeAndDrain`. All queue-only types stay in this file.

## Lock and demand discipline

- One short synchronous control lock over entries/demands/priority. No I/O, suspension, bitmap work, store mutation, or inference under it. Viewport revision is captured under the lock; the page resolver runs outside it; installation applies only if session and revision still match. Lazy worker jobs are allocated/registered under the lock and started outside it.
- Demand model per page: `explicit: Boolean` (manual tap or promoted-from-viewport) and `priority: ReaderPriority`. Auto viewport demands cover exactly the canonical keys of pages N and N+1 (real reader ordering, double-page insertions deduplicated by canonical key; no manufactured N+1 at chapter end).
- Display-ready pages are skipped via the existing projection unless an explicit force retry asks for work.
- Dedup key: reader-session generation + store/chapter identity + canonical page key. A repeated compatible tap returns `Existing`; a failed-stage force creates an ordered replacement attempt behind the owned generation — never merged into stale results, never silently dropped.
- Explicit manual demand survives every prune and every auto-off transition; auto-off removes automatic demands and abandons speculative operations that carry no explicit demand. Committed display is untouched.

## Execution, promotion, and completion

- Every stage runs through the one existing `TranslationExecutor.translateSinglePage` with `MANUAL` origin, the captured `readerStreamFn`, and the live priority source (`CURRENT_PAGE → INTERACTIVE`, `PREFETCH_NEXT → BACKGROUND`).
- Promotion (viewport move onto a running prefetch, or a tap on it) mutates the entry's priority/demand in place: same parent Job, native operation, lease, trace, and paid provider work. Native-wait promotion updates the same WAITING gate entry under its lock; provider promotion wakes the existing waiter (live priority re-read + grant-loop wake in the governor) retaining sequence/reservation. Never cancel/restart/reacquire to promote.
- Entry removal at completion only when the operation identity still matches; a cancelled predecessor's completion cannot erase a replacement. One typed terminal outcome and one trace terminal per operation, including cancellation before body entry. Cancellation cleanup uses captured lease/generation/version.
- Outcome retention reuses the 32-cap bounded map. Explicit pending capacity is finite with typed rejection; a full queue never silently drops an acknowledged tap.

## Deferral policy (queue rules, not UI guesswork)

| Condition | Behavior |
| --- | --- |
| Memory (`TranslationMemoryBudget` no headroom) | Typed deferred non-success for non-foreground entries; reconciled on memory-pressure events, not per scroll. |
| Missing source/stream | Retryable demand (`Deferred(SourceUnavailable)` semantics); source-ready and lazy-download completion events reconcile; never fabricated success. |
| Provider pause | `Paused(nextEligibleRetryAtEpochMs)` honored; release only on event/cooldown; no retry loop on viewport updates; retry budgets and the attempt-cap store gate (`store.autoAttemptAllowed`) still bind auto attempts. |
| Bounded reprepare | Cap 3 (the coordinator's `MAX_REPREPARE_ATTEMPTS`), then terminal failed truth. |
| Compute-class/config epoch | Frozen at `bindSession`; epoch change invalidates compatibility, drains active reader operations, then rebinds (the ViewModel engine-family collector is the trigger). |

## Session lifecycle and teardown

- `bindSession` validates workflow admission (existing `requestReaderSession` path), authoritative store identity, and generation; later submissions must match the binding. BATCH suppression stays in the manager: same-chapter queued/paused batch blocks reader admission for the whole batch lifetime; reader cancellation never clears BATCH leases or stores.
- `cancelSession`/`closeAndDrain` return `ReaderDrainResult` after the bounded wait; `PendingNativeDrain` carries a completion handle owned by workflow/native quarantine. `ReaderTeardownCoordinator.awaitReaderStop` awaits that handle before store eviction/engine close and before completing its acknowledgement APIs; never awaited under the queue control, native-finalizer, or store lock. Resolver invalidation happens first; captured decoded input stays valid until physical completion; the next session binds without synchronous native waiting.
- Chapter cancellation epochs (`chapterCancellationEpochs`) and the store-fenced `markPageCancelled` path remain until session fencing is demonstrated; the `runBlocking` sync bridge goes once its callers migrate.

## Manager facade

Exposed by delegation (signatures per the caller map): `bindReaderQueue`, `submitReaderPage`, `updateReaderViewport`, `setReaderAutoEnabled`, `readerOutcomes`. `openTranslationSession` stays the suspending admission prerequisite. External `translatePage` remains a compatibility facade that resolves the admitted token and delegates once — never a second worker path.

## UI truth

`TranslationUiTruth.forAutoSlot` is removed. Reader per-page copy comes from `forManualOutcome` over the queue's typed outcomes plus the display projection; deferral/retry copy reuses the existing truths (paused/failed/queued). The `AutoSmoothnessTelemetry` object relocates verbatim to `scheduling/AutoSmoothnessTelemetry.kt`; its call sites and test stay.

# Migration staging

Three cutover stages; production always has exactly one live reader execution path.

1. **S2 — internal cutover at the scheduler boundary.** The queue becomes the engine behind the existing `translatePage`/`updateAutoWindow` signatures; a temporary outcome→snapshot adapter keeps `autoSnapshot` alive for the un-migrated UI. No new coordinator is constructed; the coordinator class stays compiled but orphaned. Temporary adapter is counted and removed in S4.
2. **S3 — typed facade at the reader callers.** Controller/ViewModel/resolver move to `submitCurrent`/`updateViewport`/`setAutoEnabled`/`readerOutcomes`; the snapshot adapter and mirrored UI state lose their last consumers.
3. **S4 — retirement.** Files and machinery are deleted only now, with the replacement coverage from S1-S3 in hand.

# Event → transaction (implementation form of the master table)

| Event | Queue transaction |
| --- | --- |
| Auto off, tap N | Explicit demand, CURRENT_PAGE, one common runner, no N+1. |
| Auto on, viewport N | Bind/refresh demands for N and N+1; promote existing N in place; prune only speculative entries outside the window. |
| Swipe onto running prefetch N+1 | Demand/priority update on the same operation; zero restart/recharge. |
| Tap an already running viewport page | Explicit demand added; future prunes cannot cancel it. |
| Incompatible force/config/source request | Ordered replacement attempt behind the owned generation. |
| Auto off | Automatic demands removed; speculative work without explicit demand abandoned; explicit work and committed display survive. |
| Chapter/session switch | Token + resolver invalidated first; bounded control drain; native drain handle retained for workflow teardown; next session binds without synchronous native wait. |
| Provider/native wait promotion | Live priority update + wake of the same WAITING entry; granted work is non-preemptive and never re-charged. |

# Required behavior ledger (parity cases → slices)

| # | Case (from the caller map) | Covered in |
| --- | --- | --- |
| 1 | Auto-off tap N runs once; repeated taps dedupe; failed-stage force orders a new attempt | S1 queue tests; S3 controller test |
| 2 | Auto on creates only N/N+1; last page/empty chapter safe; insertions dedupe by canonical key | S1; S2 scheduler wiring |
| 3 | Promotion in all five states (queued, waiting-native, native, waiting-provider, admitted HTTP) preserves identity; exactly one attempt | S2 (admission-time), governor wake tests; full five-state matrix at S5 where HTTP/native fakes permit; device remainder |
| 4 | Rapid swipe prunes stale speculative demand, retains explicit demand, counts abandoned physical work until exit; auto-off keeps explicit work | S1 |
| 5 | N HTTP overlaps N+1 native; native occupancy one; local translator serial via queue semaphore | S2 coexistence harness |
| 6 | Source readiness / lazy download completions reject stale tokens; missing source is retryable demand | S1 + S3 resolver tests |
| 7 | Memory/provider deferral pauses safely; event reconciliation never bypasses retry budgets or re-charges per scroll | S1 (policy) + S2 (events) |
| 8 | Same-chapter queued/paused BATCH blocks reader admission; reader cancellation preserves BATCH leases/stores | S2 manager tests |
| 9 | Native cancellation, pending drain, chapter switch, delete/reset, engine teardown preserve ownership/fences/committed display | S2 teardown + S5 regression |
| 10 | PageLifecycle/PageView equality/readiness and overlay copy covered; auto-slot retirement only with equivalent safety coverage | S3/S4 |

# File accounting for this phase

| Scope | Removed | Added | Net |
| --- | --- | --- | --- |
| Production | 3 (`RollingAutoCoordinator.kt`, `AutoWindowState.kt`, `ReaderAutoTranslationUiState.kt`) | 2 (`ReaderTranslationQueue.kt`, relocated `AutoSmoothnessTelemetry.kt`) | −1 |
| Unit tests | 8 (table in the seam survey) | 1 (`ReaderTranslationQueueTest.kt`) | −7 |
| Rename, net zero | `ReaderAutoTranslationPageResolver.kt` → `ReaderPageWorkResolver.kt` | | 0 |

The Step-1 forecast's net −11 is historical accounting, not this phase's quota.

# Validation strategy

Per the [standing run policy](../../phase-3-implementation/validation-audit/index.md): iteration on the smallest failing class, one variant; slice gates run the focused new/changed classes on `testStandardDebugUnitTest` then `testDevDebugUnitTest` under the monitored guard with per-attempt evidence directories and one-use launch claims; fresh XML preserved for every executed attempt including failures; read-only Spotless inventory per slice; full-suite runs only at S5 consolidation. Diagnose before rerun; no dev/flavor repeat while the first run is unresolved; formatting-only edits never re-trigger tests.

# Open gates and risks

- **Device measurement.** Foreground latency and serial-vs-overlap acceptance need physical evidence; the queue is built so the policy is a parameter. Host greens do not close this gate.
- **Lock-order proof.** Before S2 cutover acceptance, an explicit test demonstrates no queue-lock → native-finalizer-lock or store-lock ordering inversion in teardown.
- **Epoch interactions.** Engine-epoch stop races (`EngineEpochStopRaceTest`) and the rekey path's in-lock cancellation calls are the two spots where the queue's invalidation order can regress fencing; both have named regression tests in S2.
- **Adapter leakage.** The S2 snapshot adapter and any compatibility shims are counted in S4 and must be zero at S5.

## Settled staging correction — 17 October 2026

The cap of three reprepare retries applies specifically to stale prepared-page handoff (`RollingAutoCoordinator.handleStaleTranslate`), not to ordinary automatic executions, provider pauses, or terminal `SinglePageOutcome.Failed`. S1 uses only `translateSinglePage`, whose typed outcomes cannot identify this stale handoff. S1 must preserve terminal outcome semantics and must not invent a generic attempt counter or infer retryability from diagnostic text.

Implement the bounded stale-handoff policy at the typed prepared/stale boundary in S2, before production reader cutover. This is a mandatory S2 acceptance dependency; its absence is not a completed S1 test or an accepted loss of runtime protection. Keep attempt-ledger admission and provider cooldown budgets independent. S1 tests remain responsible for terminal failure retention and event/cooldown reconciliation that the single-page contract can express.

# Execution evidence (S5 consolidation, 18 October 2026)

Consolidation results, behavior-ledger sign-off, file-accounting reconciliation (with recorded deviations from the table above), and owned findings are recorded in the [S5 ticket execution evidence](../tickets/s5-consolidation/index.md#execution-evidence-18-october-2026-host-worktree-phase4-s5-consolidation-at-4c3a683a-zero-source-changes) and the Traycer resume artifact `architecture-cleanup-resume/phase4-s5`. Headline: focused 27-class regression 223/0/0/0 on both flavors; broad sweep 2168 tests per flavor with three owned-finding classes excluded (unbounded sync `close()` drain, stale reflective `manualOutcomes` seam ×2 classes, one single-occurrence epoch-retry await timeout); adapter census 0; Spotless full inventory clean.
