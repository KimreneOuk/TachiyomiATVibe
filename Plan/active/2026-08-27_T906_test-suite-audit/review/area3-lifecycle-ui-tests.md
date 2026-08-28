# T906 Area 3 — Manager / Lifecycle / UI tests audit

Auditor: T906 Lifecycle UI Test Auditor (reviewer role: docs/roles/reviewer.md)
Audit target: commit `56179d7` on `t904/integration` (worktree HEAD confirmed `56179d7` at audit start).
AUDIT ONLY — no code changes.

## Scope

- `TranslationManagerAutoArbitrationTest`
- `TranslationManagerReaderTeardownTest`
- `TranslationManagerPendingAcknowledgementTest` (STARTING acknowledgement ordering)
- `BatchTranslationForegroundPolicyTest`
- `TranslationQueueStore` / `TranslationPendingRequestStore` tests (direct + indirect coverage)
- Reader viewmodels / translation sheet tests: `ReaderAutoTranslationUiStateTest`,
  `ReaderAutoTranslationLifecycleTest`, `ReaderTranslationOverlayBindingTest`,
  `ReaderTranslationFeedbackTest`, `ReaderAutoTranslationPageResolver` (projection, paused
  affordances, auto-delete protection, STARTING ordering)

Production sources of truth used (all read at `56179d7`):

- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/translation/BatchTranslationForegroundPolicy.kt`
- `app/src/main/java/eu/kanade/translation/TranslationQueueStore.kt`
- `app/src/main/java/eu/kanade/translation/TranslationPendingRequestStore.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationLifecycle.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationUiState.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationPageResolver.kt`

Key current contracts extracted from source (evidence, `56179d7`):

- C1. STARTING acknowledgement publishes in-memory before disk; disk commit is version-fenced
  and cannot overwrite a newer phase or resurrect a cancelled request
  (TranslationManager.kt:260-276, 362-393; `acknowledgePendingTranslationState`
  TranslationManager.kt:81-87).
- C2. STOP is a non-blocking acknowledgement; full teardown (`requestReaderStop`/
  `awaitReaderStop`) joins scheduler jobs and evicts only stores not retained
  (QUEUE/TRANSLATING/**PAUSED** retained: TranslationManager.kt:526-533, 440-451).
- C3. `stopReaderTranslations` runs on the manager application scope under
  `readerTeardownMutex`, cancels page translations without touching the batch queue, and only
  stops the translator when no batch is active (TranslationManager.kt:415-427).
- C4. Foreground service is kept while any queue entry is QUEUE or TRANSLATING — PAUSED is
  intentionally excluded; a detached paused projection notification is owned by the manager
  (BatchTranslationForegroundPolicy.kt:7-9; TranslationManager.kt:207-225, 250-251).
- C5. `cancelAllPageTranslations` durably clears transient queue pages **before** marking
  stores defunct (bug-4 fix ordering), TranslationManager.kt:2015-2040.
- C6. Auto-delete protection covers QUEUE/TRANSLATING/PAUSED + pending requests + persisted
  queue (TranslationManager.kt:310-332; TranslationUiProjection.protectsChapterFromDeletion).
- C7. Reader resolver: after `invalidate()`, resolver closures return null and stream access
  throws; `runAfterReaderAutoReset` orders reset before action
  (ReaderAutoTranslationPageResolver.kt:120-123, 169-203; ReaderAutoTranslationLifecycle.kt:20-26).
- C8. Version gate: owner version wins over window version; a snapshot from another identity
  is rejected, null snapshot is a valid reset
  (ReaderAutoTranslationUiState.kt:128-159).

## Findings

### F1. WRONG (HIGH) — `TranslationManagerAutoArbitrationTest."manager keeps auto window active while the chapter batch is queued"` fails deterministically at `56179d7` (stale Unsafe fixture)

- Classification: **WRONG** (the production code is right; the test would fail). Likelihood of failure on any run: certain.
- What the test asserts today: batch queueing retires the auto coordinator and a later
  reader-window update re-arms it while the batch stays queued
  (TranslationManagerAutoArbitrationTest.kt:113-128).
- Why it fails: the fixture builds `TranslationManager` via `sun.misc.Unsafe.allocateInstance`
  and injects only `scheduler` and `translator`
  (TranslationManagerAutoArbitrationTest.kt:168-180). The test then calls
  `manager.translateChapter(manga, chapter)` (line 113). Since commit `e18ed84`
  ("unify batch UX and reader lifecycle"), `translateChapter` calls
  `markTranslationRequestPreparing(chapterId)` (TranslationManager.kt:539) which dereferences
  `pendingTranslationRequestsState` (TranslationManager.kt:280), and then
  `clearPendingTranslationRequest(chapterId)` (TranslationManager.kt:542) which dereferences
  `pendingRequestWriteVersions` (TranslationManager.kt:359-360) and `pendingRequestStore`
  (TranslationManager.kt:348). All three fields are left **null** by `allocateInstance` and are
  never set by the fixture → NPE before the first assertion after line 113.
  - Test last modified: `b56f56c` (verified older than `e18ed84` via `git merge-base --is-ancestor`);
    no later commit touched the file (git log). The teardown-test sibling fixture
    WAS updated for `e18ed84` (sets applicationScope/readerTeardownMutex/activeStores/
    durableStatusCache, TranslationManagerReaderTeardownTest.kt:242-259) — the arbitration
    fixture was missed.
  - Even if the pending-state fields were fixed, `translateChapter` ends in
    `startTranslation()` → `TranslationForegroundService.start(context)`
    (TranslationManager.kt:487-494) with `context == null` → second NPE
    (TranslationForegroundService.kt:189-191, non-null `Context` parameter).
- Evidence class: VERIFIED (call chain and field-initialization semantics verified against the
  exact `56179d7` snapshot via `git show`; not executed — executing would require writing build
  outputs into the read-only audit worktree, which is forbidden).
- Recommended action: rewrite the fixture — initialize `pendingTranslationRequestsState`
  (`MutableStateFlow(emptyMap())`), `pendingRequestWriteVersions` (`ConcurrentHashMap()`), a
  relaxed mock `pendingRequestStore`, and a mock `context`; alternatively refactor the
  arbitration path so the pending-acknowledgement concern is injected/separable. The behavioral
  assertions themselves (auto-window retire/re-arm while batch queued) remain a valid contract —
  do not delete.

### F2. FLAKY (MEDIUM) — `TranslationManagerReaderTeardownTest` fixed 1-second latches depend on cross-dispatcher scheduling; the Phase-4 observed failure is genuine flakiness, not a contract violation

- Classification: **FLAKY** (tests' contracts are VALID; the timing harness can fail spuriously).
- Observed failure: `engineering/phase4-ux-lifecycle.md:20` — "An earlier combined focused run
  exposed a timing-only reader teardown latch failure; the isolated teardown test was rerun
  successfully". Diagnosis: consistent with load-sensitive latches, not with a production race:
  - All latch deadlines are fixed 1s and each waits for a coroutine on a *different* dispatcher
    (manager `applicationScope` on `Dispatchers.IO`) to be scheduled and reach a mock:
    `entryReturned.await(1s)` / `cleanupStarted.await(1s)` / `callerFuture.get(1s)`
    (TranslationManagerReaderTeardownTest.kt:88-97), `schedulerStarted.await(1s)` /
    `callerFinished.await(1s)` (lines 176-179), same pattern in the fourth test
    (lines 226-234). Under a busy JVM/CI these can exceed 1s with no production defect.
  - The blocking mock bridges use 5s (`coAnswers { ... await(5, SECONDS) }`, lines 46, 148,
    197): if a 1s assertion fails first, the manager coroutine keeps a `runBlocking` bridge
    (TranslationManager.kt:2029) alive up to 5s after the test completes — benign but can pollute
    a subsequent run's thread budget.
- What the tests assert (all match current contracts — see C2/C3): stop returns before store
  persistence completes and off the caller thread; PAUSED/QUEUE batch stores are retained
  (`isBatchTranslationRetained` includes PAUSED, TranslationManager.kt:526-533);
  `requestReaderStop` must not run an undispatched prefix (`CoroutineStart.DEFAULT`,
  TranslationManager.kt:434-437). These assertions are worth keeping.
- Recommended action: stabilize — replace fixed `await(1, SECONDS)` with condition polling under
  `withTimeout(5-10s)` (the pattern already used at TranslationManagerReaderTeardownTest.kt:98-100
  for `readerStoreDefunct`), or count-down latches from an injected dispatcher/test scope. No
  production change required; flake risk is harness-side.

### F3. Observation (coverage gap) — no direct unit tests for `TranslationQueueStore` / `TranslationPendingRequestStore`

- No test file references `TranslationQueueStore` or `TranslationPendingRequestStore`
  (repo-wide search over `app/src/test`, `56179d7`). Their behavior is exercised only indirectly
  (manager paths) or not at all: queue rehydration (`restoreQueue`/`persistedQueueChapterIds`)
  and the pending-store round-trip (`add`/`phase`/`reason`/`remove`, `load()` long-parse-stops-at-
  first-unparseable semantics, TranslationQueueStore.kt:55-64) have no direct coverage. The
  queue-merge-under-lock and STARTING-before-commit round-2 behaviors live in the manager, not
  the stores (see F5/F7 below).
- Not a WRONG/STALE/FLAKY finding — recorded because the task README lists these stores; a small
  characterization test pair would close the gap (needs Director approval; audit-only).

### F4. Coverage gap (MEDIUM) — the round-2 STARTING-before-commit ordering has no test beyond a 3-line pure helper

- `TranslationManagerPendingAcknowledgementTest` (21 lines) covers only
  `acknowledgePendingTranslationState` (TranslationManager.kt:81-87) — the in-memory map
  overwrite with STARTING. The load-bearing round-2 ordering contract is untested:
  - `acknowledgeTranslationRequests` publishes the in-memory acknowledgement **before** the disk
    commit and fences the async commit with per-chapter write versions
    (TranslationManager.kt:260-276, 118, 359-360).
  - `persistPendingStartingAcknowledgement`'s bounded repair loop must not overwrite a newer
    phase, must not resurrect a request cancelled while the commit was in flight, and must
    converge within 4 iterations (TranslationManager.kt:362-393).
  - The race `cancelTranslationRequest` vs an in-flight STARTING commit
    (TranslationManager.kt:295-300, 373-379) is exactly the class of bug the fence exists for —
    a regression there would ship silently.
- Evidence class: VERIFIED (repo-wide test-source search for
  `acknowledgeTranslationRequests|persistPendingStartingAcknowledgement` returns no test hits at
  `56179d7`).
- Recommended action: add manager-level tests using the uninitialized-manager pattern plus a
  controllable executor for `storeScope` (or inject the persistence lane); drive the
  cancel/phase-change interleavings against `persistPendingStartingAcknowledgement`. Director
  approval required (audit-only).

### F5. Coverage gap (LOW-MEDIUM) — manager-level paused affordances are untested

- Untested at `56179d7` (repo-wide test search: `showPaused|statusFlow|observeBatchProgress|
  projectQueueStatus|withDurablePause` → no hits):
  - detached paused-notification collector (TranslationManager.kt:207-225): PAUSED emits a
    detached notification only when no batch is active;
  - `statusFlow` queue-membership replay (`onStart { emit(translation) }`,
    TranslationManager.kt:2053-2066) — the "queue membership is the acknowledgement" contract;
  - `projectQueueStatus` pause/phase projection (QUEUE clears pause anchor, TRANSLATING promotes
    IDLE→FIRST_PASS, PAUSED→FINISHED, TranslationManager.kt:1491-1517);
  - `withDurablePause` — PAUSED snapshots surface the durable retryable translation failure as
    pauseAnchor/pauseReason/nextEligibleRetryAtEpochMs (TranslationManager.kt:1474-1489). This is
    the sheet/notification "paused affordance" the UI renders and it has zero characterization.
- Pure-function siblings (projection mapping, UI state) ARE tested (see Summary); only the
  manager glue is dark. Recommended action: characterization tests for `projectQueueStatus` and
  `withDurablePause` (both pure) are cheap and high-value; the collectors need the
  uninitialized-manager harness from F4.

### F6. Coverage gap (LOW) — manager-level auto-delete protection untested; only the pure helper is

- `TranslationUiProjectionTest` (TranslationUiProjectionTest.kt:30-44) covers
  `protectsChapterFromDeletion` incl. PAUSED — current and correct.
- The manager entry points that actually gate deletion are untested:
  `isChapterTranslationProtected` (adds the persisted-queue union via
  `translator.persistedQueueChapterIds()`, TranslationManager.kt:310-318) and
  `protectedChapterIds` (QUEUE/TRANSLATING/PAUSED + in-memory + persisted pending + persisted
  queue, TranslationManager.kt:321-332). A regression dropping PAUSED or the persisted-queue
  union would re-enable reader auto-delete of retryable chapters. No test file references either
  symbol. Recommended action: one uninitialized-manager test asserting protection across
  {PAUSED queue entry, persisted queue id, in-memory pending request}.

### F7. Note (LOW) — `ReaderTranslationOverlayBindingTest` never exercises the positive path

- All five cases assert the empty binding (ReaderTranslationOverlayBindingTest.kt:24-84). The
  branch that returns real blocks with dimensions
  (ReaderTranslationOverlayBinding.kt:35-41, `showTranslatedImage && displayReady`) is never
  asserted, though it is the path every translated page takes. Contracts asserted are correct
  and current (verified against `isTranslationDisplayShapeReady`,
  PageDisplayProjection.kt:140-144). Recommended action: add one positive case
  (cleaned image + current revision + READY stages + non-blank block → blocks + 1200x1800).

### Negative results (explicitly checked, nothing found)

- **Pre-PAUSED-state contracts: none.** Every test in scope treats PAUSED as durable-but-inactive
  and retained: arbitration test 1 (TranslationManagerAutoArbitrationTest.kt:30-63), teardown
  test 2 (TranslationManagerReaderTeardownTest.kt:109-137), foreground policy
  (BatchTranslationForegroundPolicyTest.kt:13-15), projection
  (TranslationUiProjectionTest.kt:36-39). No test expects PAUSED to keep the service alive or to
  count as "translating" — the pre-redesign semantics are fully gone from this area.
- **Round-2 queue-merge-under-lock: covered and current.**
  `ChapterTranslatorQueueRestoreTest` (2 tests) matches `mergeRestoredQueueEntries`
  (ChapterTranslator.kt:51-57): durable order preserved, concurrent additions retained, removals
  and stale lookups dropped, duplicates collapsed. The `synchronized(queueMutationLock)` wrapper
  (ChapterTranslator.kt:189-223) is not concurrency-tested, but its merge semantics are.
- **Bug-4 ordering (durable clear before markDefunct): locked in by teardown test 1** —
  cleanup must complete before the defunct assertion passes
  (TranslationManagerReaderTeardownTest.kt:92-101), matching TranslationManager.kt:2015-2040.
- **Resolver/gate contracts (C7/C8): fully covered** by ReaderAutoTranslationLifecycleTest
  (11/11 verified against ReaderAutoTranslationPageResolver.kt and
  ReaderAutoTranslationUiState.kt:128-159, including owner-version-wins-over-window-version).
- **Coalescer/fence timing: deterministic** (injected `nowMs`) — no wall-clock dependence in
  ReaderTranslationFeedbackTest.

## Summary

Scope: 10 test files, 51 test methods, audited against `56179d7` production sources.

| Class | Count | Items |
|---|---|---|
| WRONG | 1 | F1 — TranslationManagerAutoArbitrationTest."manager keeps auto window active while the chapter batch is queued" fails deterministically (Unsafe fixture never initializes fields required since `e18ed84`; NPE in `markTranslationRequestPreparing` before its first post-queue assertion) |
| STALE | 0 | (F1's root cause is a stale *fixture*, but the observable is a deterministic failure → classified WRONG per the scheme) |
| REDUNDANT | 0 | — |
| FLAKY | 1 class (4 tests) | F2 — TranslationManagerReaderTeardownTest fixed 1s latches on cross-dispatcher scheduling; Phase-4 observed failure was genuine harness flakiness, contracts themselves valid |
| VALID | 46 | 46 remaining methods across all 10 files (incl. the other 3 teardown tests) |

Coverage gaps (not classified as test defects; Director decision needed to add tests):
F4 STARTING-before-commit ordering (MEDIUM), F5 manager paused affordances (LOW-MEDIUM),
F6 manager auto-delete protection (LOW), F7 overlay positive path (LOW),
F3 store round-trips (LOW).

Highest-priority action: fix F1 (rewrite arbitration fixture: initialize
`pendingTranslationRequestsState`, `pendingRequestWriteVersions`, `pendingRequestStore`,
`context`; fence/mask `TranslationForegroundService.start`). Second: F2 latch stabilization.
Third: F4 tests for the version-fenced STARTING commit.

## Dispositions (fix round, 2026-08-28 — commit `2bd73b3` on `t906/fix-area3`)

### F1 — RESOLVED, root cause revised: the test never executed at all

The audit's call-chain analysis was correct but incomplete: at `56179d7` the
observable failure mode was **silent skip**, not a run-time NPE. Empirical
resolution (replacement implementer, run in the `t906/fix-area3` worktree):

- JUnit Jupiter 5.11.4 does not discover `@Test` methods whose JVM return type
  is non-void. `fun ...() = runBlocking { ... }` infers its return type from
  the lambda's last expression; here that was `withTimeout(...) { ... }`
  returning `AutoTranslationSnapshot`, so the compiled method signature was
  `public final AutoTranslationSnapshot manager keeps auto window active...()`
  (verified via `javap` over the compiled test class). Jupiter skipped it.
  - Class-level filter run: `tests="1"` — only the paused-chapter test
    executed, BUILD SUCCESSFUL.
  - Explicit method filter for the skipped method: Gradle
    "No tests found for given includes".
- This is also the empirical answer to why the full-suite gates at `56179d7`
  passed: the "certain NPE" never had a chance to fire. The method has plausibly
  never executed since it was written (bare `runBlocking {` with a non-Unit
  last expression already at `b56f56c`, byte-identical tail — `git show`).

Fix shipped in `2bd73b3` (test-only):

- `runBlocking<Unit>` so the method is void and discoverable.
- Fixture now injects `context` (relaxed mock), `pendingRequestStore`
  (relaxed mock), `pendingTranslationRequestsState`
  (`MutableStateFlow(emptyMap())`), `pendingRequestWriteVersions`
  (`ConcurrentHashMap`) — the fields required since `e18ed84` exactly as the
  audit predicted (`translateChapter` → `markTranslationRequestPreparing` /
  `clearPendingTranslationRequest`).
- `TranslationForegroundService.start` masked via `mockkObject` on the
  companion (`startTranslation()` reaches it whenever a batch is queued; the
  unit-test android.jar throws on the real `Intent`/`startForegroundService`
  path, and the project does not set `returnDefaultValues`).
- Once actually running, the test exposed a **second stale expectation**: the
  never-executed tail (post-batch-drain) asserted that a repeated identical
  `updateAutoWindow` nulls the snapshot. No code path does that — `updateWindow`
  publishes non-null anchors and is documented safe to call repeatedly
  (RollingAutoCoordinator.kt:138-142); the snapshot only goes null via
  `shutdownAutoCoordinator` / coordinator retirement
  (TranslationScheduler.kt:214-223, 977-987). The tail was rewritten to the
  current contract: repeated updates keep the coordinator armed → explicit
  `shutdownAutoCoordinator` retires (null) → a later update re-arms (identity).
- All original behavioral assertions (auto-window retire on queue, re-arm
  while the batch stays queued, reconcile stability) are preserved and green.

Classification update: the WRONG finding stands, but the mechanism is now
"silent skip (never ran) + stale Unsafe fixture + one never-valid tail
expectation", not "deterministic NPE".

### F2 — RESOLVED (stabilized + two more silent skips revived)

- All fixed `await(1, TimeUnit.SECONDS)` latch waits replaced with condition
  polling under `withTimeout(5_000)` (polling the same `CountDownLatch.count`,
  matching the in-file `readerStoreDefunct` pattern); failure messages kept as
  post-poll assertions; `Future.get` joins widened 1s → 5s. Contracts unchanged.
- Discovery fix in the same class: `await reader stop moves scheduler teardown
  off caller thread` and `request reader stop has no undispatched scheduler
  prefix` were ALSO silently skipped for the same non-void reason (their
  `runBlocking` lambdas end in `callerFuture.get(...)`, javap: `java.lang.Object`
  return). Both revived with `runBlocking<Unit>` — and both pass, so their
  contracts (off-thread scheduler teardown; no undispatched prefix) hold at
  `56179d7`. The class now truly executes 4 tests (it executed 2 before).

### Gate evidence

- `:app:testStandardDebugUnitTest --tests TranslationManagerAutoArbitrationTest
  --tests TranslationManagerReaderTeardownTest` → `tests="2"` + `tests="4"`,
  0 failures, 0 skipped; `spotlessCheck` green.
- Single commit `2bd73b3` on `t906/fix-area3` (two test files, no production
  changes).
- F3–F7 coverage gaps: no action taken (Director approval still required).

## Second pass — AUDIT-ONLY unnecessary-test sweep (2026-08-28)

Scope: the same 10 area-3 files. No code changed in this pass.

### U1. REDUNDANT (LOW) — three `ReaderTranslationOverlayBindingTest` cases are result-identical

- `original image does not receive tier 1 translated text`,
  `original image does not receive tier 2 translated text`, and
  `original image does not receive text before translation is ready` all call
  `selectReaderTranslationOverlayBinding(false, translation)`. With
  `showTranslatedImage == false` the selector short-circuits to the empty
  binding before consulting any stage/revision state — the only positive branch
  requires `showTranslatedImage && displayReady`
  (ReaderTranslationOverlayBinding.kt). They are byte-for-byte result-identical
  to `original image never receives translated text` (same branch, same
  expected `ReaderTranslationOverlayBinding(emptyList(), 0, 0)`); the richer
  fixtures add zero assertion power.
- Recommendation: delete the three tier variants (or parameterize into one
  case). Their names suggest stage-gating coverage that actually lives in
  `toPageDisplayProjection().displayReady` — which is exercised only on the
  `showTranslatedImage = true` path, i.e. the F7 gap, not here.

### U2. Trivial-but-keep (LOW) — duplicate predicate in the arbitration paused test

- `manager treats a paused chapter as durable but inactive` asserts both
  `isBatchTranslationActive(10L)` and `isTranslationActive(10L)`; the latter is
  a one-line delegation (`isTranslationActive` → `isBatchTranslationActive`,
  TranslationManager.kt:475-477). Duplicate assertion, harmless — keep as a
  guard on the public delegation seam, or drop the duplicate line. No action
  taken.

### U3. Illusory coverage (HIGH for the suite overall) — silent JUnit skips are the dominant "unnecessary test" hazard

- The F1/F2 investigation showed the area's reported pass counts overstated
  real coverage: 3 of the 7 manager-lifecycle methods in this area's two
  flaky/broken classes never ran, while counting as suite members. Fixed
  in-area at `2bd73b3`.
- Cross-area evidence recorded here because it was found by this pass's
  bytecode sweep — **not acted on (Area 1 scope; Director decision needed)**:
  `RollingAutoCoordinatorTest` has 8 test methods with non-void JVM returns
  that Jupiter 5.11.4 silently skips (javap over the compiled class):
  - `remote overlap - native B starts while translate A is in flight`
  - `local compute serializes prepare and translate through a shared gate`
  - `shutdown clears snapshot`
  - `chapter mismatched cancellation leaves the active scheduler owner running`
  - `same-chapter replacement is suppressed while cancellation is signalling`
  - `global cancellation epoch rejects a concurrent update and permits post-cancel rearm`
  - `stable scheduler snapshot switches pointer and rejects same-identity replay`
  - `reconcile admission guard suppresses batch or revision recovery poke`
- Recommendation: route to the scheduler-area owner: add `runBlocking<Unit>`
  (or end the lambdas with `Unit`), then re-validate each of the 8 expectations
  before trusting them — they have plausibly never executed (same b56f56c-era
  authoring style). A repo-wide one-line-inference lint rule (e.g. ktlint
  custom rule or a code-review checklist item: "test methods must declare
  `runBlocking<Unit>`") would prevent recurrence.
- Everything else in scope: no REDUNDANT or dead-contract candidates. The four
  teardown tests each carry a distinct contract (early return before store
  persistence; PAUSED retention; off-thread scheduler join; no undispatched
  prefix); resolver/gate/projection/policy/acknowledgement tests assert
  current, distinct invariants (see Negative results above).
