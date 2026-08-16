# Checkpoints

## Baseline

- Worktree was clean before implementation.
- Tickets 01 and 02 started as the first dependency batch.
- Validation not yet run.

## Ticket 01 — state contract

- Status: complete and independently accepted
- Changes:
  - `app/src/main/java/eu/kanade/translation/scheduling/AutoWindowState.kt` (NEW, prior agent; reviewed from scratch): pure rolling auto-window contract. `AutoChapterIdentity`; `AutoDeferralReason` (Memory/Network/SourceUnavailable/LocalComputeBusy with precedence); `AutoSlotState` sealed interface (Queued + ReadingText/Cleaning/Translating/Rendering + Ready + Deferred + Failed) with `isActive`/`isProcessing`/`isReady`/`deferralReason`; `AutoWindowBounds` pure geometry (clamped `availableAheadTarget` + `aheadPageIndices` + `hasVisiblePage`); `AutoActivityStatus`; `AutoTranslationSnapshot` (value-only, structural equality, init invariants enforce foreground==visible / ahead>visible / strict ordering / size<=target; derived `orderedSlots`, `readyAheadCount`, `processingAheadCount`, `failedAheadCount`, `activeDeferral`, `status`). No contract defect found — all ticket scope items and acceptance criteria covered.
  - `app/src/test/java/eu/kanade/translation/scheduling/AutoWindowStateTest.kt` (NEW, prior agent + this fix): 28 focused tests — normal/first/last/penultimate windows, short chapters, N bounds, empty chapter, out-of-bounds, negative-input rejection, ready/processing/failed counting, foreground separation, orderedSlots ordering + null-foreground, all snapshot invariants, structural + hash equality, identity inequality, Working/Paused/FullyReady/Idle status, deferral precedence. Fixed the flagged nullable-contains assertion: replaced `aheadSlots.none { it.pageIndex == foreground?.pageIndex } shouldBe true` (nullable deref; vacuously true if foreground null) with the idiomatic non-nullable `aheadSlots.map { it.pageIndex } shouldNotContain snapshot.visiblePageIndex`.
- Validation (Android Studio JBR 21, SDK via local.properties):
  - `:app:testDevDebugUnitTest --tests 'eu.kanade.translation.scheduling.AutoWindowStateTest'` — BUILD SUCCESSFUL, 28/28 PASSED.
  - `:app:compileDevDebugKotlin` — BUILD SUCCESSFUL.
  - `:app:spotlessCheck` — BUILD SUCCESSFUL.
- Note: the line-177 "type error" recorded in the ticket-02 checkpoint no longer reproduces — the file already compiled before this fix; the residual nullable-contains smell is now resolved.
- Unexpected findings: none. Ticket-02 pipeline files left untouched.
- Review: `artifacts/review-01-auto-window-state-contract/index.md`
- Low-severity consumer notes were carried into ticket 03 before production wiring.
- Deviations: none.

## Ticket 02 — prepared-page boundary

- Status: complete after three review/fixup rounds
- Changes:
  - `app/src/main/java/eu/kanade/translation/scheduling/TranslationExecutor.kt`: boundary interface + docs. `translatePreparedPage` returns false ONLY for stale/race, throws on genuine failure; inpaint-failure (missing cleaned image) documented as re-prepare signal.
  - `app/src/main/java/eu/kanade/translation/scheduling/PreparedPageBoundary.kt` (NEW): top-level internal production helpers `publishPreparedPageFromOcr` (the durability write + bitmap recycle + PreparedPage construction) and `isPreparedPageTerminal` (shared terminal predicate). Extracted from the pipeline so pure-JVM tests can drive the REAL boundary code path.
  - `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`: boundary implementation + all fixup fixes. `prepareSinglePage` delegates to `publishPreparedPageFromOcr` (P0 durability write). `translatePreparedPage` throws on failure, returns false only for stale, does NOT mutate chapter state (P1/P2). `processSinglePage` split into analyze()+inpaint() with CLEANING event between them (P2). Inpaint catch blocks set `inpaintStatus = FAILED` (R4). Dead `currentChapterTranslation` + `registerActiveStore`/`unregisterActiveStore` deleted entirely — confirmed inert by repo-wide reference trace (R2/R3).
  - `app/src/test/java/eu/kanade/translation/scheduling/PreparedPageRuntimeBoundaryTest.kt` (REWRITTEN): 8 tests that call the REAL production helpers (`publishPreparedPageFromOcr`, `isPreparedPageTerminal`) with a real `ChapterTranslationStore`. The headline test proves OCR blocks become durable at handoff and would FAIL if the production `store.updatePage` durability write were removed.
  - `app/src/test/java/eu/kanade/translation/scheduling/PreparedPageBoundaryTest.kt` (TRIMMED): 4 reflection tests pinning PreparedPage field types/names + enum surface + functional interface. Tautological tests removed.
  - `app/src/test/java/eu/kanade/translation/scheduling/TranslationStageEventContractTest.kt`: DELETED (tautological — tested a MutableList, not production behavior).
  - `app/src/test/java/eu/kanade/translation/scheduling/CancelSyncStoreWriteTest.kt`: fake executor conformance for extended interface.
- Validation:
  - `:app:spotlessCheck` — BUILD SUCCESSFUL.
  - `:app:compileDevDebugKotlin` — BUILD SUCCESSFUL.
  - `:app:testDevDebugUnitTest` for 3 ticket-02 test classes — 15/15 PASSED (CancelSyncStoreWriteTest=3, PreparedPageBoundaryTest=4, PreparedPageRuntimeBoundaryTest=8).
- Mutation evidence: removing the `store.updatePage` durability write from `publishPreparedPageFromOcr` causes `production durability write makes OCR blocks survive handoff` to fail (blocks empty, ocrStatus=RUNNING instead of READY).
- Unexpected findings: concurrent agent's `AutoWindowStateTest.kt:177` pre-existing type error still blocks the full test source set; temporarily moved aside for validation, restored byte-for-byte.
- Review: `artifacts/review-02-auto-page-phase-boundary-rereview/index.md`
- Fixup: `artifacts/tickets/02-auto-page-phase-boundary/fixups/real-boundary-regression-test/index.md`
- Final re-review found a member wrapper recursively shadowing the top-level terminal predicate on null-result/resume paths.
- Third fixup: `artifacts/tickets/02-auto-page-phase-boundary/fixups/remove-terminal-predicate-recursion/index.md`
- Final recursion fix: deleted the shadowing member wrapper; the remaining pipeline call resolves to the tested top-level helper.
- Final validation: `:app:compileDevDebugKotlin`, `:app:spotlessCheck`, and 15 focused unit tests passed.
- Deviations: none. R1/R2/R3/R4 all addressed. Ticket 01 files untouched.

## Ticket 03 — rolling coordinator

- Status: static completion; execution validation pending
- Changes:
  - `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` (NEW; prior partial + this pass): chapter-scoped rolling coordinator. One inline native lane + one translate/render consumer over a bounded (capacity-1) prepared-page channel; REMOTE_IO overlaps native-B with translate-A, LOCAL_COMPUTE serializes both lanes through a shared `Semaphore(1)`. Event-driven reconcile loop suspends on a conflated trigger (no busy-wait/poll/sleep). This pass fixed the stale-translate contract: `translatePreparedPage == false` now re-prepares (slot → Queued) with a bounded cap (`MAX_REPREPARE_ATTEMPTS = 3`) before flipping `Failed(retryable)`; a thrown translate marks `Failed`. Truthful snapshots: foreground==null ⟺ visible needs no auto work; `availableAheadTarget` derived via `AutoWindowBounds`; ready-ahead counts only durable display-ready pages; an all-queued window is Working, a real pause publishes `Deferred`. Started obsolete work may finish; obsolete queued work never starts; per-admission memory gate publishes `Deferred(Memory)`.
  - `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt` (prior partial + this pass): owns at most one coordinator; `updateAutoWindow`, `shutdownAutoCoordinator`, `autoSnapshot`. This pass added: (1) leak fix — `close()` and `cancelAllPageTranslations()` now `shutdownAutoCoordinator()` (the coordinator owns its own scope, so `scope.cancel()` alone leaked it); (2) `cancelPageTranslations(chapterId)` (chapter-switch) cancels coordinator admission; (3) manual arbitration — `updateAutoWindow` wraps the resolver so pages with an active manual single-page job are hidden from the coordinator, and `translatePage`'s finally pokes `autoCoordinator?.reconcile()` so the slot is re-admitted once manual completes; (4) `cancelAutoTranslations` already cancels the coordinator (re-armable).
  - `app/src/main/java/eu/kanade/translation/TranslationManager.kt` (prior partial + this pass): `autoSnapshot`, `updateAutoWindow`, `shutdownAutoCoordinator` plumbing. This pass added batch arbitration — `updateAutoWindow` shuts down the coordinator and no-ops while `isBatchTranslationActive(chapterId)` (batch owns the chapter), re-arming on the next window update.
  - `app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorTest.kt` (NEW; prior partial + this pass): deterministic tests on `Dispatchers.Unconfined` with `CompletableDeferred`-gated fake executor — no sleeps/polling; every test calls `shutdown` + `awaitTermination`; added `@AfterEach` scope cancel to stop the leaking injected scope. Coverage: remote overlap (maxConcurrent==2), local-compute serialization (==1), memory deferral + refill on reconcile, prepare-failure → Failed + continue, terminal/textless → Ready, rapid anchors 4→5→6 (no duplicate work), obsolete queued never starts / started finishes, ready-ahead from durable store, foreground-null when display-ready, missing stream + recovery, cancel + re-arm, shutdown clears snapshot. This pass added: stale-translate re-prepare then succeed, persistently-stale bounded → Failed, translate throw → Failed, and manual arbitration (window proceeds around a hidden page then admits it on reconcile).
- Static findings / correctness notes:
  - The single-native-lane and single-translate-lane invariants are enforced inline by the reconcile loop (prepare is a suspending inline call) and the single channel consumer; the executor's own native/translator permits are the hard backstop.
  - `cancel()` + immediate re-arm without `awaitTermination` is a benign transient in production (page-change driven, natural delay): the cancelling loop stops admitting promptly and the executor permits serialize any briefly-overlapping in-flight prepare. Tests always `awaitTermination` before re-arm.
  - The legacy generation/list auto path (`requestAutoWindow`) is intentionally retained alongside the new coordinator; reader wiring to switch onto `updateAutoWindow` is ticket 04 (out of scope here). No ReaderViewModel/UI files were touched.
- Validation: NOT executed this turn (per constraint — no Gradle/Java/Kotlin/daemon commands). `git diff --check` clean (no whitespace errors). Awaiting `:app:spotlessCheck`, `:app:compileDevDebugKotlin`, and `:app:testDevDebugUnitTest --tests 'eu.kanade.translation.scheduling.RollingAutoCoordinatorTest'` (+ AutoWindowState/PreparedPage tests) to confirm.
- Removed untracked temp files: `runlog.txt`, `runlog2.txt` (Gradle build logs; verified contents before removal).
- Deviations: none. Ticket 01 (`AutoWindowState.kt`) and ticket 02 (`TranslationPipeline.kt`, `TranslationExecutor.kt`, `PreparedPageBoundary.kt`, `BubbleMaskRle.kt`, related tests) left untouched and preserved.

### Fixup: foreground-memory-and-lifecycle-hardening

- Status: static implementation complete; execution validation pending
- Artifact: `artifacts/tickets/03-rolling-auto-coordinator/fixups/foreground-memory-and-lifecycle-hardening/index.md`
- Changes:
  - `RollingAutoCoordinator.kt`: (1) `lifecycleLock` serializes `currentIdentity`/`coordinationJob` lifecycle mutation in `updateWindow`/`cancel`/`shutdown` via `cancelLocked()`/`ensureCoordinationRunningLocked()` — concurrent lifecycle calls can't launch two loops; `poke()` stays lock-free (thread-safe `trySend`). (2) Memory gate now defers AHEAD pages only; the visible foreground page bypasses it (highest priority); hard decode safety remains in the pipeline. `desired.sorted()` computed once per pass. (3) `cancel()` clears transient slotStates + `reprepareAttempts` (`resetState`) and republishes from store truth (display-ready stays Ready). (4) `evictObsolete` clears `reprepareAttempts` for pages leaving the desired window.
  - `TranslationManager.kt`: `updateAutoWindow` now suppresses auto while `isBatchTranslationActive(chapterId) || isRevisionActive(chapterId)`.
  - `RollingAutoCoordinatorTest.kt`: updated the memory-deferral test (visible page display-ready → foreground null, ahead-only deferral preserved); added 3 tests — foreground-bypasses-memory-gate, identity-switch resets + binds new window, cancel-clears-stale-reprepare re-arm.
- Invariants: single coordination loop (lifecycle serialization); foreground bypasses prefetch headroom gate; cancel/re-arm/eviction never leak stale in-flight presentation or stale retry counters; durable display-ready results remain store truth; memory-recovery wakeup handed to ticket 04.
- Validation: NOT executed (per constraint). `git diff --check` clean. Awaiting `:app:spotlessCheck`, `:app:compileDevDebugKotlin`, and `:app:testDevDebugUnitTest --tests 'eu.kanade.translation.scheduling.RollingAutoCoordinatorTest'`.
- Preserved: ticket 01/02 untouched; no ReaderViewModel/UI changes.

### Fixup: session-fencing-and-truthful-deferral

- Status: static implementation complete; execution validation pending
- Artifact: `artifacts/tickets/03-rolling-auto-coordinator/fixups/session-fencing-and-truthful-deferral/index.md`
- Changes:
  - `RollingAutoCoordinator.kt`: corrected the obsolete-key union; retained geometric targets with null resolvers so missing/manual-hidden pages publish `Deferred(SourceUnavailable)`; retained cancelled ownership until completion and made replacements wait on every older job; added monotonically increasing session generations and captured identity/session metadata to fence prepared handoffs, stage callbacks, transient state, and snapshots; preserved foreground-only memory bypass and retry eviction/reset.
  - `TranslationManager.kt` + `RevisionOwnershipGate.kt`: reserved revision ownership before Auto cancellation/registration under one lock, exposed pending reservations to Auto arbitration, and released reservations on setup failure, cancellation, completion, and failure.
  - `RollingAutoCoordinatorTest.kt`: added a distinct-session in-flight chapter-switch test with a non-cooperative native gate and late callback, plus normal-dispatcher concurrent lifecycle coverage.
  - `RevisionOwnershipGateTest.kt`: added focused reservation/registration/cancellation tests against the extracted ownership helper.
- Invariants: no replacement coordinator loop starts before all prior owned loops finish; obsolete work uses only its producing session and cannot mutate the current snapshot; resolver-null geometric targets are visibly SourceUnavailable; Auto cannot re-arm during the entire revision reservation/registration window.
- Validation: `git diff --check` clean. No Gradle, Java, Kotlin compiler, test, daemon, sleep, polling, or background commands run; execution validation remains pending.
- Preserved: Ticket 01/02 and foreground-memory behavior; no ReaderViewModel/UI files touched.

### Fixup: scheduler-ownership-and-reentrant-publication

- Status: static implementation complete; execution validation pending
- Artifact: `artifacts/tickets/03-rolling-auto-coordinator/fixups/scheduler-ownership-and-reentrant-publication/index.md`
- Changes:
  - `TranslationScheduler.kt`: added scheduler-owned retiring predecessors, replacement barriers, serialized pointer ownership, identity/session/store owner records, identity-matched chapter cancellation, and lock-free-outside-gate reconcile/shutdown/cancel calls.
  - `RollingAutoCoordinator.kt`: replacement coordinators await scheduler predecessors; snapshot inputs are captured under lifecycle state, resolved/built outside `lifecycleLock`, and publication is generation/identity/session checked immediately before a StateFlow assignment outside the lifecycle monitor.
  - `TranslationManager.kt` + `RevisionOwnershipGate.kt`: added the production revision transaction seam. Every setup failure, rejected registration, pre-body cancellation, normal completion, and failure releases matching ownership; tracker cleanup is idempotent and identity-conditional.
  - `TranslationBatchTrackerRegistry.kt`: added current-tracker conditional disposal so stale transaction cleanup cannot close a newer tracker.
  - `RollingAutoCoordinatorTest.kt`: added scheduler replacement/non-cooperative preparation, mismatched chapter cancellation, and reentrant resolver publication coverage.
  - `RevisionOwnershipGateTest.kt`: added transaction setup-abort, rejected-registration, late-completion/new-reservation, and pre-body-cancellation cleanup coverage.
- Invariants: no replacement admits native work before every retiring predecessor terminates; all scheduler pointer transitions are gate-serialized without suspension; chapter cancellation targets only its owner; snapshots cannot run resolver/store/StateFlow code under `lifecycleLock` or overwrite a newer generation/spec version; revision ownership/tracker cleanup is transactional and identity-safe.
- Coordination: inspected the active Ticket 04 sibling and current shared worktree immediately before patching. Ticket 04 ReaderViewModel/state/UI files were preserved and not edited; no overlapping API change with its reader work was required.
- Validation: targeted live-code/test inspection and `git diff --check` only. No Gradle, Java, Kotlin compiler, tests, daemons, background processes, sleeps, or polling run; execution validation remains pending.

## Ticket 04 — reader integration

- Status: static completion; execution validation pending
- Changes:
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`: replaced the reader's legacy Auto request construction with the single rolling `translationManager.updateAutoWindow` path. The visible page is submitted as the coordinator anchor and the configured preference is passed as exactly-N-ahead target semantics; chapter-end clamping and slot ordering remain coordinator-owned. Added page resolution for reader/cached streams, translator compute classification, active snapshot collection with identity and observer-generation fencing, and `autoTranslationUiState` exposure separate from batch/manual state. Toggle, target/engine preference, chapter, background/foreground, reader-close, translation-disable, batch-start, manual chapter-cancel, and memory/lifecycle paths now reset, rebind, cancel, or reconcile the rolling state at their existing ownership boundaries. Revision re-arm remains suppressed by the already-live manager ownership gate.
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationUiState.kt` (NEW): pure reader projection of `AutoTranslationSnapshot`; keeps foreground stage separate from ordered ahead slots, copies truthful configured/available and ready-ahead counts, exposes aggregate activity/failure, and preserves typed Memory/Network/SourceUnavailable/LocalComputeBusy deferrals. Mismatched identities and null snapshots reset transient state.
  - `app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationUiStateTest.kt` (NEW): production-driven mapping coverage for every coordinator slot state, exact visible-plus-N geometry, ready/available semantics, Working/Paused/FullyReady/Idle aggregate outcomes, foreground/offscreen separation, failure state, distinct deferrals, rapid anchors, stale cross-chapter emissions, and null-snapshot reset.
  - `app/src/main/java/eu/kanade/translation/TranslationManager.kt`: narrow `reconcileAutoWindow()` bridge to the scheduler for reader lifecycle/memory wakeups. `TranslationScheduler`'s live owner-aware reconcile path is preserved alongside Ticket 03's dormant legacy implementation.
- Lifecycle evidence:
  - `ReaderActivity.onResume()` already invokes `resumeTranslationsOnForeground()`, which now issues `reconcileAutoWindow()` before re-arming the current rolling window. This is the real reader-lifecycle recovery signal for fully `Deferred(Memory)` windows; the reader memory callback also reconciles when used.
  - `git grep -n 'requestAutoWindow' -- app/src/main/java/eu/kanade/tachiyomi/ui/reader app/src/test/java/eu/kanade/tachiyomi/ui/reader` returned no output. The repo-wide search found only the dormant `TranslationManager` wrapper, `TranslationScheduler` definition, and an executor documentation reference. The only Reader Auto scheduling call is `ReaderViewModel.kt:1189` → `translationManager.updateAutoWindow`.
- Static validation: targeted live-code/test inspection and `git diff --check` only. No Gradle, Java, Kotlin compiler, tests, daemons, background processes, sleeps, or polling run; execution validation remains pending (`spotlessCheck`, focused reader/projection tests, and affected scheduling tests).

### Fixup: source-wakeup-and-switching-stream

- Status: static implementation complete; execution validation pending
- Artifact: `artifacts/tickets/04-reader-auto-state-integration/fixups/source-wakeup-and-switching-stream/index.md`
- Changes:
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/PageLoader.kt` and `HttpPageLoader.kt`: added a loader-level `onPageStreamReady` hook, invoked after HTTP `originalStream` installation and READY publication, with a protected production notification seam and recycle-time callback clearing. Pager and webtoon both use the existing `page.chapter.pageLoader.loadPage(page)` status loop, so one hook covers both viewers without per-page observers.
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`: binds the source-ready hook only for the active Auto chapter/session, using weak reader/chapter references, a monotonic readiness generation, identity/chapter/preference fencing, and a synchronous re-entry guard whose reset allows later source transitions. Reconcile is also issued once after binding to cover the update-to-bind race. The switching snapshot collector ignores non-null cross-chapter/session emissions while still treating null as a lifecycle reset. Reset/disable/manual-batch/background/reader-close paths invalidate the callback and resolver before coordinator cancellation or loader/page recycle; foreground and memory callbacks remain the real recovery signals.
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationPageResolver.kt` (NEW): reader-owned resolver indirection binds a page list to the Auto identity/generation without giving the coordinator a direct page-list closure. Same-identity anchor updates reuse the binding so already-issued in-flight stream handles stay usable; chapter/session replacement or teardown clears the page list and all stream handles, and retained resolver calls return null before accessing old page/loader resources. Shared page-key resolution remains the production key path.
  - `app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationLifecycleTest.kt` (NEW): production-seam coverage for source readiness and recycle clearing, active generation/chapter/session/preference fencing, rapid same-identity anchor replacement, stale snapshot filtering, late teardown access, chapter-end clamp, and neutral manual/revision reset projection. Existing pure mapper tests remain intact.
- Invariants/evidence:
  - SourceUnavailable windows are re-opened by HTTP `originalStream` readiness without navigation, toggle, or memory transition; the scheduler trigger remains conflated and the reader callback guard is reset after each reconcile.
  - Resolver/callback invalidation precedes `ReaderChapter.unref()`/loader recycle on reader close and precedes manager cancellation on manual/batch/background/disable teardown. No ReaderPage/EpubReader resource closure is retained after the active reader generation is invalidated.
  - Current production deferral producers remain Memory and SourceUnavailable; Network and LocalComputeBusy remain exhaustively mapped in the reader projection but are not fabricated by this fixup.
- Static validation: targeted source/test inspection and `git diff --check` only. No Gradle, Java, Kotlin compiler, test, daemon, background, sleep, or polling command was run; execution validation remains pending.

### Fixup: owner-version-and-joined-teardown

- Status: static implementation complete; execution validation pending.
- `ReaderViewModel.kt`: collector publication is fenced by `(AutoChapterIdentity, ownerVersion, windowVersion)` with a strict fresh-tuple gate during each anchor update and expected visible-index check. Resolver binding receives the same store token/owner hint and is updated from accepted snapshots. Reader close invalidates callbacks/resolvers, requests manager-owned joined stop, and defers `ViewerChapters.unref()`/download cleanup until the Deferred completes; chapter navigation awaits the joined manager boundary before old chapter resources are released. Delete uses a production ordering helper so Auto reset precedes delete teardown.
- `ReaderAutoTranslationPageResolver.kt`: owner/store-aware binding clears same-key store replacements; same-owner page-list replacement captures immutable issued factories, while a shared lifecycle gate prevents late access after invalidation. No issued item retargets a replacement page at the same index.
- `ReaderAutoTranslationUiState.kt`: carries accepted owner/window versions and exposes a pure lower/foreign tuple gate.
- Tests: lifecycle regressions cover rapid tuple replay, same-key new-store replacement, marked-stream continuity, non-cooperative joined-stop-before-recycle, delete/reset ordering, plus the existing source-readiness/stale-chapter/chapter-end/memory/manual-revision seams. UI projection tests carry owner/window versions.
- Static evidence: live API inspection of Ticket 03 `requestReaderStop`/`awaitReaderStop`/snapshot ownerVersion, targeted searches, repo-wide `git grep -n -F 'requestAutoWindow'` (no Reader references), and `git diff --check`.
- Constraint honored: no Gradle/Java/Kotlin compiler/tests/daemons/background processes/sleeps/polling; execution validation remains pending.

## Ticket 05 — live reader feedback

- Status: pending

### Fixup: cancellation-truth-and-snapshot-ordering (including Ticket 04 boundary re-review)

- Status: static implementation complete; execution validation pending.
- `RollingAutoCoordinator.cancel()` now rebases the retained spec to the post-cancel generation/version, clears transient state, and publishes durable/queued truth immediately. Monotonic `stateSequence` fencing rejects delayed same-spec snapshot builds after later stage mutations.
- `TranslationScheduler` now exposes one stable switching reader `StateFlow`, and `TranslationManager.autoSnapshot` keeps that same stable reference; coordinator installation/replacement/shutdown updates the source pointer, while cancel flows through the active coordinator snapshot. Same-identity updates synchronously publish a resolver/store-free anchor projection carrying a monotonic `windowVersion` so rapid reader collectors cannot replay an older anchor or fabricated readiness.
- Scheduler cancellation owns a chapter/global epoch before invoking coordinator methods. Owner call gates keep all coordinator calls outside `autoCoordinatorLock`; matching replacements are suppressed during cancellation and retiring predecessors remain joined before replacement admission.
- `TranslationManager.reconcileAutoWindow()` and `translateChapter()` now serialize through `RevisionOwnershipGate`; batch/revision ownership suppresses recovery Auto and batch acquisition stops only the matching coordinator atomically with queue insertion. Revision startup rechecks batch ownership under the same gate.
- `TranslationBatchTrackerRegistry.completeIfCurrent()` plus manager identity capture fence late terminal events; registry-backed tests prove old tracker cleanup cannot close or cache over a newer owner.
- Production-path regression intent: truthful post-cancel snapshot, delayed same-spec stage ordering, same-chapter cancel/replacement race, stable pointer/replay switching, and batch/revision reconcile suppression. Ticket 04 Reader/UI and Ticket 05 files were preserved.
- Validation: targeted live-code inspection and `git diff --check` only. No Gradle, Java, Kotlin compiler, tests, daemons, background processes, sleeps, or polling run; execution validation remains pending.

### Fixup: revision-reservation-transaction-safety and owner-version-and-joined-teardown

- Status: static implementation complete; execution validation pending.
- Ticket 03 transaction safety:
  - `TranslationManager.startRevision()` starts cleanup immediately after `reserveLocked`; Auto cancellation, resolver/store/tracker setup, and registration are covered by an exception-safe transaction. Pre-registration failure releases the pending reservation once; registered ownership is left for terminal cleanup.
  - `TranslationBatchTrackerRegistry.createTracker()` is the manager production factory. Real old/new tracker terminal callbacks are identity-fenced, with queued terminal delivery allowed to drain after replacement so stale cleanup cannot touch the newer tracker.
  - `TranslationManagerAutoArbitrationTest` uses real manager methods, scheduler, and revision gate to cover batch/revision reconcile suppression and re-arm after release. Coordinator tests cover same-chapter post-cancel pointer/re-arm and a normal-dispatcher global cancellation epoch/update race.
- Ticket 04 scheduler/manager boundary:
  - `AutoTranslationSnapshot.ownerVersion` is monotonic for every scheduler coordinator/store owner replacement, even when identity/session text is unchanged; same-owner anchor updates retain ownerVersion and synchronously publish a newer windowVersion projection.
  - `TranslationScheduler.autoSnapshot` is one stable switching flow, and `TranslationManager.requestReaderStop(reason): Deferred<Unit>` / `awaitReaderStop(reason)` expose manager-lifetime joined cleanup. Scheduler stop keeps `readerStopInFlight` through coordinator/native/auto/page joins and never suspends under scheduler monitors.
  - d99a5e6a was handed the exact API and will keep Reader invalidation -> joined stop -> unref ordering. No ReaderViewModel/UI or Ticket 05 files were edited in this pass.
- Changed production files: `TranslationManager.kt`, `TranslationScheduler.kt`, `RollingAutoCoordinator.kt`, `AutoWindowState.kt`, `TranslationBatchTrackerRegistry.kt`, `TranslationBatchProgressTracker.kt`.
- Changed tests: `TranslationManagerAutoArbitrationTest.kt`, `TranslationBatchTrackerRegistryTest.kt`, `RollingAutoCoordinatorTest.kt` (plus previously accepted Ticket 03/04 tests remain intact).
- Validation: targeted static inspection and `git diff --check` only. No Gradle, Java, Kotlin compiler, tests, daemons, background processes, sleeps, or polling run; execution validation remains pending.
