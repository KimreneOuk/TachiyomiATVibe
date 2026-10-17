---
title: "Phase 4 seam survey - actual code at b10c8b66"
kind: spec
status: 0
assignee: ""
comments: none
---

# Method

All seven named files read in full at worktree HEAD `b10c8b66` (Phase 3 checkpoint), plus the secondary seams listed by the [caller map](../../translation-step1-plan/reader-queue/index.md). Line anchors are current-HEAD, not the old Step-1 baseline. Symbol fan-out counted by ripgrep across `app/src`.

# Retirement targets

## `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` (~1500 lines)

Delete whole after queue parity. Internals the queue must absorb or replace:

- Constructor: `(executor, computeClass, memoryGate = { TranslationMemoryBudget.hasHeadroomForPrefetch() }, injectedScope, predecessorCoordinators, ownerVersion, drainGraceMs = 210_000L)`.
- Two-lock design: `lifecycleLock` (control) + `snapshotPublicationLock` (UI projection). Conflated `trigger` Channel drives `runCoordinationLoop`; `MAX_REPREPARE_ATTEMPTS = 3`; `reprepareAttempts`/`pausedTranslations` maps.
- Compute gate: `computeGate = if (!computeClass.mayOverlapNative) Semaphore(1) else null` — the LOCAL_COMPUTE exclusion the queue must re-own. `preparedChannel` capacity `TranslationMemoryBudget.recommendedPrefetchCapacity()`.
- `updateWindow` (identity/session/store change → `cancelLocked(COORDINATOR_REPLACED)` + reset), `reconcile()`, `cancel()`, `shutdown()`, `awaitTermination()`.
- `reconcilePass`: desired set = not-display-ready visible+ahead via `AutoWindowBounds`; evicts obsolete; memory gate defers non-foreground (`AutoDeferralReason.Memory`); resolver-null → `Deferred(SourceUnavailable)`; admits at most one page per pass; marks native admitted, registers run trace under lock, runs `executor.prepareSinglePage(...)` under the compute gate when local, hands off `PreparedWork` after `markTranslateAdmitted`.
- `consumeTranslations`: stale handoff is terminal; drain-not-cancel — `withContext(NonCancellable) { withTimeout(drainGraceMs) { attempt gate ?: runAutoAttempt } }` where `runAutoAttempt` = `executor.translatePreparedPage(...)` + `store.resolveAttempt`.
- Outcome mapping: Completed→Ready; null→bounded reprepare; Paused→`deferTranslationRetry(nextEligibleRetryAtEpochMs)`; Failed retryable→deferred else Failed(false); Unexpected/PersistenceRejected→Failed(true). `mapTranslatedTraceOutcome` marks EVICTED when `store.pageLeaseOwner != PageWriteOrigin.AUTO`.
- Currency check `isWorkCurrent`: generation + identity + session key + store identity — the dedup/fencing semantic the queue re-implements per submission.
- Attempt gate before auto attempts (`store.autoAttemptAllowed`); manual force path bypasses via forced retry.
- `AutoSmoothnessTelemetry` object lives in this file (`logUserArrival`, `logDebounceLifecycle`, `logArrivalWaitRecorded`, `logWindowReconcile`, `logAutoAdmit`) — relocate, do not delete.
- Nested `data class PageWorkItem(pageKey, streamFn)` — replaced by `ReaderPageWork`.

## `app/src/main/java/eu/kanade/translation/scheduling/AutoWindowState.kt` (256 lines)

Delete whole. Contains `AutoChapterIdentity(chapterId, sessionKey)`, `AutoDeferralReason` (precedence LocalComputeBusy→SourceUnavailable→Network→Memory), `AutoSlotState` sealed hierarchy + `isActive`/`isReady`/`deferralReason`, `AutoWindowSlot`, `AutoWindowBounds` pure window math (ahead-target clamp, ahead-page prefix, hasVisiblePage), `AutoActivityStatus`, `AutoTranslationSnapshot` with `ownerVersion`/`windowVersion`/`readyAheadCount`/`failedAheadCount`/`activeDeferral`/`status`. The queue owns demand math (exactly N/N+1) and typed deferrals; no renamed slot state machine.

# Facade targets

## `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt` (895 lines)

Keep as collapsed compatibility facade; delegate reader execution to one queue.

- Existing reader engine: `activePageJobs: ConcurrentHashMap` keyed `"$chapterId:$pageKey"`; `manualOutcomes` bounded LinkedHashMap, cap 32 (reuse as the queue outcome cap).
- Auto-owner machinery to delete after cutover: `autoCoordinator: AutoCoordinatorOwner?`, `autoCoordinatorLock`, `retiringAutoCoordinators`, `globalAutoCancellationInFlight`, `autoCancellationEpoch`, `autoSnapshotFlow`, `updateAutoWindow` owner matching (`identity+sessionKey+store identity`, else `retireLocked` + replacement with `predecessorCoordinators` + `ownerVersion`), `shutdownAutoCoordinator`, `reconcileAutoWindow(admissionGuard)`, `AutoCoordinatorOwner.callLock` serialization, and the `beginAutoCancellation`/`performAutoCancellation`/`finishAutoCancellation` epoch protocol.
- Keep until session fencing is demonstrated: `chapterCancellationEpochs` map and `markChapterCancelledAsync`; `markPageCancelled` store-fenced write with BATCH stage-lease skip; stranded-RUNNING reset runs in `NonCancellable` when a job is cancelled mid-flight; `markPageJobStuck`.
- `translatePage` semantics to preserve: dedup skip (never cancel+restart a duplicate), trace schedule/run/lease-wait spans created before launch, completion poke to auto reconcile (becomes queue demand reconcile).
- `cancelPageTranslations` bounded join `JOIN_TIMEOUT_MS = 2_000L`; `markChapterCancelledSync` runBlocking bridge removed after callers migrate.

## `app/src/main/java/eu/kanade/translation/workflow/TranslationManager.kt` (2172 lines)

- `openTranslationSession:1908`: `clearStaleDownloadFailedRequest` + `openOrCreateActiveChapterTranslationStoreSuspend` → `TranslationSession(key = "${source.id}:${manga.id}:$chapterId")`. Kept as the suspending admission prerequisite.
- Auto facade `1929-1992`: `cancelAutoTranslations`, `autoSnapshot` StateFlow, `updateAutoWindow` (session admission → same-chapter BATCH suppression `includePaused = true` → scheduler), `reconcileAutoWindow(admissionGuard)`. Replaced by the queue facade (`bindReaderQueue`/`submitReaderPage`/`updateReaderViewport`/`setReaderAutoEnabled`/`readerOutcomes`); BATCH suppression stays in the manager.
- Batch suppression entry points: `admitChapterThroughQueue` and `admitRestoredPendingTranslation` call `scheduler.shutdownAutoCoordinator(chapterId)` before queueing — must instead invalidate/drain the reader queue session.
- `attemptDownloadRekey:1678` calls `scheduler.cancelAutoTranslations(chapterId)` + `cancelPageTranslations(chapterId)` inside the admission/file-opening locks — rekey invalidates the old session token before cancellation.
- `ReaderTeardownCoordinator` (`workflow/ReaderTeardownCoordinator.kt`, 194 lines): stays lifecycle owner. `requestReaderStop`/`awaitReaderStop` hold `readerTeardownMutex`, run `abortPausingToBatch` → `scheduler.awaitReaderStop` → evict non-BATCH stores → `finishSession` → `retryPendingDownloadRekeysAfterReaderLeave`. Must await the queue's `PendingNativeDrain` handle before store eviction/completion, never while holding the queue control, native-finalizer, or store lock. Preserve the durable-CANCELLED-before-`markDefunct` ordering in `cancelAllPageTranslations`.
- `onMemoryPressure` currently touches only the translator; the queue adds memory-deferral reconciliation.

# Reader callers

## `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt` (1758 lines)

- Auto path: `handleAutoTranslation` 150 ms debounce (`pendingDebouncePage`/`pendingDebounceStartNs` + telemetry) → `handleAutoTranslationOnIo`: `openTranslationSession`, `currentTranslatorComputeClass()` (from three prefs), `identity = AutoChapterIdentity(chapterId, "$autoReaderSessionKey:${session.key}:${computeClass.name}")`, `configuredAheadTarget = autoTranslatePrefetchCount().get().coerceIn(1, 5)` (retired — exactly N+1 is the new policy), owner-version hint, `observeAutoSnapshot(identity, store, currentIndex)` projection, `autoPageResolver.bind(...)`, `updateAutoWindow`, `bindAutoSourceReadiness` (loader `onPageStreamReady` weak-ref callback → `onAutoSourceReady` → reconcile), final reconcile.
- `resetAutoTranslationState`: clear source readiness → resolver invalidate → generation++ → cancel collector → clear identity/token → empty UI state. The invalidation-before-resource-recycle ordering is preserved by the queue's `cancelSession` flow.
- Manual path `translateSinglePage`: `sessionCoordinator.requestReaderSession` (BATCH_ACTIVE → `Dialog.BatchReaderSwitch`; PAUSING_IN_PROGRESS → toast; READER_ACTIVE → proceed) → `clearStaleDownloadFailedRequest`; `effectiveForce` = any stage FAILED unless explicitly forced; three dispatch branches — (1) `page.originalStream` registered then `translatePage`, (2) `page.imageUrl` lazy cancellable HTTP download into `chapterCache` then register+translate, (3) downloaded-chapter direct `translatePage`. All three become one `submitCurrent` for the captured session; the late-download branch revalidates session/config/page identity before submission.
- Cancellations: `cancelSinglePageTranslation` → per-page cancel; `stopAllTranslation` → `cancelAllPageTranslationsOffMain(cancelBatchQueue = true)` + `translatorStop(closeEngines = true)` + `streamRegistry.clearAll()`; `cancelTranslationForChapter` → reset auto state + `awaitReaderStop("chapter switch")` + `streamRegistry.clearChapter` + page field reset; `cancelTranslationsOnBackground` → `stopReaderTranslations`; `resumeTranslationsOnForeground` → reconcile + re-arm; `onMemoryPressure` → manager + reconcile + Critical-class reset.
- Display: `observeLiveTranslationStore` (stranded-RUNNING sweep + merged state), `observePageView` (store display projection — keep). `setAutoTranslatePrefetchCount` retires in S4.

## `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationPageResolver.kt` (212 lines)

Rename to `ReaderPageWorkResolver.kt`, net zero files. `bind(identity, pages, ownerVersion, ownerToken)` with `AtomicLong` generation fencing and `BoundBinding(identity, ownerToken, ownerVersion: AtomicReference<Long?>)`; rebind reuses a compatible binding. `Binding.resolve(index)`: `resolveReaderPageTranslationKey` (sourceFileName → translationStorageKey → `onlinePageTranslationKey(imageUrl, page.url)`), stream = `page.originalStream` ?: chapter-cache `getImageFile(url).inputStream()`, null when no stream and not a local loader; issues fresh `InputStream` per call behind a `LifecycleGate` (`check(active)` invalidation-before-open). Remove owner-version tracking; bind to reader session token + store identity; return `ReaderPageWork(pageKey, readerPageIndex, streamFn)`. `shouldReconcileAutoSourceReady` becomes the shared source-ready predicate; allow manual deferred demand to wake when auto is off.

## `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` (1873 lines)

- `autoTranslationUiState:163` and `State.autoTranslation:1681` — delete with the mirrored UI state.
- Auto fields `356-389`: `autoSnapshotJob`, `currentTranslationStore`, `autoPageResolver`, `autoReadinessLock/Generation/Loader/PokeInFlight`, `activeAutoIdentity`, `autoSnapshotGeneration`, `autoSnapshotFenceLock` + `acceptedAutoOwnerVersion/WindowVersion` + `activeAutoOwnerToken`, `autoReaderSessionKey`. Keep one reader session generation and the shared resolver/readiness lifetime; delete owner/window version fencing.
- Init collectors: auto toggle `593` (becomes `setAutoEnabled` + cancel-session semantics, keeps the disabled toast), prefetch-count `616` (delete in S4), engine-family rebind `628` (becomes compute-epoch rebind), master toggle `650` (keeps cancel-all + stop + registry clear).
- `loadChapter:881` cancels the previous chapter before loading; landing-page auto kick `962`; `onPageSelected:1086` telemetry `1101` + synchronous `chapterPageIndex` + stale cross-chapter drop `1145`; `autoTranslationScrollJob:1162`.
- `onCleared:793`: reset auto state, `requestReaderStop`, cleanup after stop. `observePageView:1811` stays.

# Secondary seams

| File | Seam | Disposition |
| --- | --- | --- |
| `ui/reader/ReaderAutoTranslationUiState.kt` (183 lines) | Mirrored auto DTO hierarchy + `projectReaderAutoTranslationUiState` + identity/version acceptance predicates + `TranslationUiTruth.forAutoSlot` mapping | Delete in S4 after overlay/P5 tests absorb copy and stale-session cases. |
| `translation/presentation/TranslationUiTruth.kt` | `forAutoSlot:125`, `AutoSlotState` import `:16` | Remove mapper; per-page reader truth derives from `forManualOutcome` + typed queue outcomes + display projection. |
| `domain/.../TranslationPreferences.kt` | `autoTranslate:107` (keep), `autoTranslatePrefetchCount:113` (retire accessor; stored key retained, no migration) | |
| `presentation/reader/TranslationSettingsSheet.kt` | `onAutoTranslatePrefetchCountChange:77`, `TogglesSection` params `393-399`, `PrefetchSlider:417` | Keep auto toggle; delete slider plumbing in S4. |
| `pipeline/execution/TranslationExecutor.kt` (213 lines) | `translateSinglePage:41` (force/stageListener/origin), `prepareSinglePage:74`, `translatePreparedPage:108`, `PreparedPage`, `TranslationCompletionOutcome` | Add trailing optional `readerStreamFn` + live `readerPriority` to the common runner, defaults preserve behavior. Public prepared-page APIs retire in S4; internal guarded helpers stay shared. |
| `pipeline/TranslationPipeline.kt` | Fixed `AdmissionPriority.INTERACTIVE` at `:482`/`:501`; `prepareSinglePage:1122` (AUTO lease, `ROLLING_AUTO_PREPARED` site), `translatePreparedPage:1403` (AUTO re-admission, stale-reference null) | Replace fixed foreground priority with the live reader source; queue submissions pass `PageWriteOrigin.MANUAL` regardless of trigger (AUTO enum + persisted evidence stay compatible). Remove the public prepared-boundary pair in S4. |
| `engines/translator/ProviderRequestGovernor.kt` | `ProviderRequestPriorityContext:132-144`; waiter reserve math `541-559`; `selectWaiter:635-647` (waiter priority captured in metadata at enqueue) | Add owner-neutral live priority re-read at selection and a promotion wake that re-runs the grant loop; preserve bucket quotas, ordering age, reservation identity, admitted accounting. |
| `model/SinglePageOutcome.kt` | Typed outcomes already exist (Admitted/Completed/Paused/Stalled/Failed/Attached/Rejected) — Phase 3 delivered this file; the old Step-1 forecast counted it as new | Reuse as the queue outcome type; no new outcome hierarchy. |

# Test fan-out and absorption

Ripgrep fan-out at `b10c8b66`: `RollingAutoCoordinator` in 12 files; `AutoWindowState` family in 18; `TranslationScheduler` in 31 (facade stays, so most only need re-verifying); resolver in 3 (ViewModel, lifecycle test, itself).

Legacy files the [test manifest](../../translation-step1-plan/test-manifest/index.md) retires, with absorption destinations:

| File | Absorbed into |
| --- | --- |
| `scheduling/RollingAutoCoordinatorTest.kt` | `ReaderTranslationQueueTest` — deferral/memory/source policy, display-ready skip, bounded reprepare. |
| `scheduling/RollingAutoCoordinatorTraceTest.kt` | Queue/trace tests — one operation, one terminal, cancellation-before-body terminal. |
| `scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt` | Queue dedup/session fencing tests. |
| `scheduling/AutoWindowStateTest.kt` | Queue N/N+1 demand/prune + existing display-readiness coverage. |
| `workflow/TranslationManagerAutoArbitrationTest.kt` | Manager admission/BATCH suppression + same-page promotion tests (S2). |
| `coexistence/ReaderManualPreemptsAutoLeaseTest.kt` | Same-job/lease/no-duplicate-work promotion test (S2). |
| `ui/reader/ReaderAutoTranslationUiStateTest.kt` | Overlay/P5 projection copy + stale-session cases (S3). |
| `ui/reader/ReaderAutoTranslationLifecycleTest.kt` | N/N+1 demand, session invalidation, stream teardown (S3). |

Kept but re-seamed in S4: `coexistence/AutoProviderCallDrainsNotCancelsTest`, `EngineEpochStopRaceTest`, `NormalMangaIsolationTest`, `TranslationCoexistenceHarness`, `AdmissionTestFixture` (bind the queue instead of the coordinator), `scheduling/AutoSmoothnessTelemetryTest` (follows the telemetry relocation), `P5OutcomeProjectionTest` (drop auto-slot inputs).

# Findings that shape the plan

1. `SinglePageOutcome` and the guarded-preparation helpers already exist — Phase 4 adds one production file, not the old forecast's three.
2. The pipeline's prepared boundary takes AUTO leases and MANUAL evicts AUTO; switching queue submissions to MANUAL origin makes manual-outranks-auto a demand-precedence rule inside one queue instead of cross-origin eviction.
3. The engine-family combine in the ViewModel re-kicks auto on translator change — that is the compute/config epoch invalidation trigger the queue contract requires.
4. `markChapterCancelledSync` still bridges through `runBlocking`; its callers must be counted and migrated before removal.
5. `updateAutoWindow`'s owner identity embeds the compute class in `sessionKey` — the queue's `bindSession(computeClass)` epoch must reproduce this compatibility break on mode change (drain-then-rebind).
6. The scheduler hides pages with active manual jobs from the coordinator (arbitration at window time). In the queue this becomes explicit-demand precedence over viewport demand — no second job map.

# Execution evidence (S5 consolidation, 18 October 2026)

Post-phase consolidation evidence — absorption-table outcomes, file accounting reconciliation against the [plan](../plan/index.md), and the residual findings that remain open — is recorded in the [S5 ticket execution evidence](../tickets/s5-consolidation/index.md#execution-evidence-18-october-2026-host-worktree-phase4-s5-consolidation-at-4c3a683a-zero-source-changes). Note: the survey's 8th retirement row (`ui/reader/ReaderAutoTranslationLifecycleTest.kt`) was resolved by rename/evolution into `ReaderPageWorkResolverLifecycleTest.kt` (R041), not deletion — recorded as an accounting deviation, not silently compensated.
