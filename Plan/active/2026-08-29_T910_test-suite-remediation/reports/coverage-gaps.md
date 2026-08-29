# T910 — Coverage gaps F3–F7: characterization tests delivered

Implementer: coverage implementer (T910 work item 3)
Date: 2026-08-29 · Branch: `optimize_translation_finishing_page` (working tree, no commits)
Source: `Plan/active/2026-08-27_T906_test-suite-audit/review/area3-lifecycle-ui-tests.md`, findings F3–F7.

## Result

**20 new tests across 5 gaps, all green** (23 total across the 6 touched/created
classes, 0 failed, 0 skipped). No production code changed. Guard task
`:app:checkTestRunBlocking` green (all new coroutine tests use
`= runBlocking<Unit> { ... }` or plain block bodies; no expression-body
`= runBlocking {`).

Verification (JAVA_HOME = Android Studio jbr):

```
./gradlew :app:testStandardDebugUnitTest \
  --tests ReaderTranslationOverlayBindingTest \
  --tests TranslationQueueStoreTest \
  --tests TranslationPendingRequestStoreTest \
  --tests TranslationManagerPendingAcknowledgementTest \
  --tests TranslationManagerPausedAffordanceTest \
  --tests TranslationManagerAutoDeleteProtectionTest
→ BUILD SUCCESSFUL
  ReaderTranslationOverlayBindingTest            tests="3" skipped="0" failures="0"
  TranslationManagerAutoDeleteProtectionTest     tests="1" skipped="0" failures="0"
  TranslationManagerPausedAffordanceTest         tests="5" skipped="0" failures="0"
  TranslationManagerPendingAcknowledgementTest   tests="4" skipped="0" failures="0"
  TranslationPendingRequestStoreTest             tests="5" skipped="0" failures="0"
  TranslationQueueStoreTest                      tests="5" skipped="0" failures="0"
./gradlew :app:checkTestRunBlocking
→ BUILD SUCCESSFUL
```

All counts are from the JUnit XML result files (`tests="N" skipped="0"
failures="0" errors="0"`), so every method provably executed — the T906/U3
silent-skip hazard does not apply to any new test.

## Per gap

### F3 — direct store characterization (recommended: "a small characterization test pair")

Implemented the pair, one class per store, backed by a single shared
in-memory `SharedPreferences` fake (no Robolectric on this source set; no fake
existed in the tree — one reusable helper, not per-file copies):

- `app/src/test/java/eu/kanade/translation/InMemorySharedPreferences.kt`
  (new shared fixture; editor buffers ops and removes erased keys from
  already-committed values, matching the real `Editor` contract the stores
  rely on).
- `TranslationQueueStoreTest.kt` — round-trip order (L27), empty load (L36),
  save-replaces-removes (L43), clear (L53), `load()` positional scan stops at
  the first missing/unparseable index (L63).
- `TranslationPendingRequestStoreTest.kt` — add/phase/reason/load round-trip
  (L29), null-or-blank reason drops a stale reason key (L40), remove drops
  phase + reason (L53), clear drops everything (L65), `load()` numeric-key
  filter keeps reason keys out of the id set (L78).

Deviation: the finding also mentions queue rehydration
(`restoreQueue`/`persistedQueueChapterIds`) — those are ChapterTranslator
methods, not store methods; the store-level pair is what the recommendation
prescribes, and the translator merge-restore semantics are already covered by
`ChapterTranslatorQueueRestoreTest` (audit negative results). Not duplicated.

### F4 — STARTING-before-commit ordering (recommended: manager-level tests, uninitialized-manager pattern, interleavings against `persistPendingStartingAcknowledgement`)

Extended `TranslationManagerPendingAcknowledgementTest.kt` (its 21-line pure
helper test is untouched at L30) with three manager-level tests using the
established `uninitializedManager` fixture (mirrors
`TranslationManagerDownloadFailureRecoveryTest`, plus a `storeScope` whose
`SupervisorJob` the test can `complete()+join()` under `withTimeout(5_000)`
for deterministic, latch-free lane completion):

- L42 `acknowledgement publishes in-memory before the durable commit` — with
  the manager's `pendingRequestMutationLock` held by the test, STARTING is
  visible in memory while the commit provably cannot have run (it needs the
  same lock); after the drain the store saw exactly one
  `add(10, STARTING, null)`.
- L68 `cancelling while the starting commit is in flight never resurrects the
  request` — acknowledge + cancel ordered under the lock; the fenced commit
  then performs only the durable keep-clear (`remove` twice, `add` never).
- L98 `a newer phase while the starting commit is in flight fences the stale
  write` — PREPARING under the lock advances the write version; the stale
  STARTING commit is dropped (store sees only the PREPARING write).

Deviations:

1. The audit's "must converge within 4 iterations" refers to a bounded repair
   loop that existed in the audited `56179d7` snapshot. The current
   `persistPendingStartingAcknowledgement` (TranslationManager.kt:393–406) is
   a single-shot version-fenced check under the mutation lock — no loop. The
   tests characterize current behavior (version fence + cancel keep-clear),
   which is the load-bearing contract.
2. A "controllable executor for storeScope" is not possible without a
   production change: `acknowledgeTranslationRequests` hardcodes
   `storeScope.launch(Dispatchers.IO)`, overriding any injected dispatcher.
   Determinism is achieved instead via the mutation lock (interleave fencing)
   and a joinable `CompletableJob` backing storeScope (bounded drain). No
   fixed latches anywhere; the only wait is `withTimeout(5_000)`-bounded.

### F5 — manager paused affordances (recommended: characterization tests for `projectQueueStatus` and `withDurablePause`, both pure; collectors via harness)

New `TranslationManagerPausedAffordanceTest.kt` — 5 tests invoking the two
private pure member extensions reflectively on an Unsafe-allocated manager
(they touch no manager state; `getDeclaredMethod` precedent exists in
`SmartBubbleTextCleanerTest`):

- L26 QUEUE projection clears the paused affordance (anchor/reason/retry
  nulled, batchPhase → IDLE).
- L39 TRANSLATING promotes IDLE → FIRST_PASS and keeps an explicit phase;
  characterized as-is: unlike QUEUE, this branch does NOT clear an existing
  pause affordance (noted, not "fixed").
- L61 PAUSED → batchPhase FINISHED; `queueStatus == null` is an identity.
- L73 `withDurablePause` surfaces a retryable TRANSLATION failure as
  pauseAnchorPageKey/pauseReason/nextEligibleRetryAtEpochMs (store mocked at
  `durableFailuresSnapshot()`; populating a real artifact manifest would leave
  the pure-function scope).
- L85 untouched cases: no failures, wrong stage, terminal (non-retryable)
  status, and non-PAUSED snapshot states all return unchanged.

Deviation/scoping: the audit's primary action is the two pure functions
(above). The two collectors were noted as needing "the uninitialized-manager
harness from F4", but the detached paused-notification collector is wired in
the manager `init {}` block (TranslationManager.kt:210–228) and renders via
`TranslationForegroundService.showPaused` — it cannot execute under the
Unsafe-allocated harness at all (instance initializers never run) and would
require constructing the full Android/DI manager. The `statusFlow` queue
replay is similarly embedded in the flatMapLatest projection chain. Both need
a dedicated harness decision; out of scope for "exactly the recommended
tests". Left documented for a follow-up.

### F6 — manager auto-delete protection (recommended: one uninitialized-manager test across {PAUSED queue entry, persisted queue id, in-memory pending request})

New `TranslationManagerAutoDeleteProtectionTest.kt` L36 — exactly one test:
PAUSED queue entry (10L), in-memory pending request (20L), persisted queue id
via `translator.persistedQueueChapterIds()` (30L), durable pending request via
`pendingRequestStore.load()` (40L) all protect; an untouched chapter (99L)
stays deletable; `protectedChapterIds()` returns exactly the union. Fixture
mirrors the established uninitialized-manager pattern.

### F7 — overlay binding positive path (recommended: one positive case: cleaned image + current revision + READY stages + non-blank block → blocks + 1200x1800)

`ReaderTranslationOverlayBindingTest.kt` L43
`display ready translated image binds its blocks and page dimensions` —
`cleanedImageName` + `inpaintStatus = READY` at `CURRENT_INPAINT_REVISION` +
READY translation/render + non-blank block →
`ReaderTranslationOverlayBinding(listOf(block), 1200, 1800)`, i.e. the
`showTranslatedImage && displayReady` branch through
`isTranslationDisplayShapeReady` (PageDisplayProjection.kt:140–144). No
deviation.

## Files touched

Modified (tests only):

- `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationOverlayBindingTest.kt`
- `app/src/test/java/eu/kanade/translation/TranslationManagerPendingAcknowledgementTest.kt`

Created (tests only):

- `app/src/test/java/eu/kanade/translation/InMemorySharedPreferences.kt`
- `app/src/test/java/eu/kanade/translation/TranslationQueueStoreTest.kt`
- `app/src/test/java/eu/kanade/translation/TranslationPendingRequestStoreTest.kt`
- `app/src/test/java/eu/kanade/translation/TranslationManagerPausedAffordanceTest.kt`
- `app/src/test/java/eu/kanade/translation/TranslationManagerAutoDeleteProtectionTest.kt`

Not touched (owned by other T910 items): `app/build.gradle.kts`,
`scheduling/RollingAutoCoordinatorTest.kt`. No production sources changed;
nothing committed — all changes are in the working tree.
