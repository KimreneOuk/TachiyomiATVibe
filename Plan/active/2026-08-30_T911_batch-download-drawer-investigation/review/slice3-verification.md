# T911 Slice 3 verification review — tracker totals, terminal exits, handoff split, durable reconstruction

Independent Reviewer pass over the uncommitted Slice 3 working tree on
`t911/repair` (Slices 1–2 committed at `e5e8011`, `66fa2c9`; HEAD `a313863`
is docs-only). Every claim below was checked against live source, the
`git diff` of all 10 modified files, the 5 untracked test suites, and fresh
test executions — not the implementer's report. Evidence labels per
`docs/roles/reviewer.md`.

Baseline: 10 modified files (9 production + 1 test) + 6 untracked entries
(5 test files/dirs + the slice report), matching the report's file list
exactly. This review modified no production or test code and committed
nothing.

## Executive verdict

All five Slice 3 contract items are implemented and verified in source, and
all 25 new tests execute and pass (XML-proven, not console-proven). The
reviewer independently reproduced the implementer's flagged flake
(`TranslationRequestGenerationFenceTest`, `expected:<2L> but was:<3L>`) in
both a focused run and the full-suite gate run, and root-caused it to an
exact, benign interleaving: the async STARTING acknowledgement commit's
defensive re-tombstone double-bumps the durable generation after a cancel,
and the test asserts the tombstone's exact value without draining the async
lane. Production behavior is correct in every interleaving — this is a
test-only determinism bug in committed Slice 2 fixture code, untouched by the
Slice 3 diff. Because it failed the full gate run (and ~50% of observed full
runs), the final repair-wide gate cannot be certified green until the trivial
test fix lands: **FIX-FIRST with a single test-only fix item.** No Slice 3
code change is required. The fix has since been applied exactly as specified
and the gate closed green — see "Gate closure" below; final verdict ACCEPT.

## Per-item verification

### 1. Tracker totals from ordered work keys — PASS

- First snapshot derives from ordered keys at construction:
  `TranslationBatchProgressTracker.kt:54`
  (`_snapshot = MutableStateFlow(snapshotFor(Projection()))`). Property init
  order is safe: `indexResolver` (:48) initializes before `_snapshot` (:54);
  `snapshotFor` touches only constructor params and `indexResolver`.
- `snapshotFor` (:169-211) now builds `pageMap` from ALL
  `orderedPageKeys.distinct()` via `associateWith`; a key missing from
  `store.state` projects as a fresh pending `PageTranslation(sourceFileName =
  pageKey)` (:180) instead of being dropped by the old `mapNotNull`. A fresh
  `PageTranslation` has all-pending statuses → `progressStage` → QUEUED, so
  placeholders claim no completion. Event phase-override folding
  (:181-191) applies to placeholders too. Display map remains the store
  intersection (:193-197).
- Completed counts can only come from the store intersection because
  placeholders project QUEUED — verified behaviorally by
  `TranslationBatchProgressTrackerTotalsTest` (5/5 executed): nonzero total
  from empty store (:47-65), 1 done + 1 placeholder with `queuedCount == 1`
  (:68-90), placeholder registration does not inflate done (:93-111), phase
  events reach placeholders (:114-131), aborted terminal keeps totals with
  reason (:134-154).
- No double counting when the store catches up: registration replaces the
  placeholder with an identical pending projection; totals stay
  `distinct().size` (test :93-111).
- Old zero-total expectation updated:
  `TranslationBatchProgressTrackerTest.kt:53` (`totalPages shouldBe 1`);
  the diff touches only that expectation + a comment; post-`rebuildFromStore`
  assertions unchanged. VERIFIED against `git diff`.
- Persisted leftovers still cannot inflate totals: `pageMap` keys come only
  from `orderedPageKeys` (:179).

### 2. Pre-registration rejection observable — PASS

- Outcome type: `ChapterTranslationStore.kt:1207-1212`
  (`PagePreRegistration.Accepted / Rejected(reason)`); `preRegisterPages`
  (:1226) returns `Rejected("store is defunct")` for a defunct store (:1232)
  and `Rejected(admission.message)` for an `admitMutationLocked()` rejection
  (:1242); empty keys → Accepted (:1227). Existing statement callers compile
  unchanged (result ignored where not inspected).
- Caller observes and fails typed:
  `ChapterTranslator.kt:637-646` — `Rejected` routes to `failBeforePipeline`
  with reason "Page pre-registration was rejected: <reason>" and returns
  before any tracker runs the batch. VERIFIED.
- Design note (accepted): the rejection surfaces via queue-ERROR +
  aborted terminal tracker snapshot rather than the pending-request failure
  path — at this point queue admission has cleared pending ownership, so the
  queue entry is the owner. Consistent with the Slice 2 ownership model.
- E2E test drives the REAL `translateChapterInternal` with a defunct store
  (`ChapterTranslatorTerminalExitsTest`:122-147, executed): asserts ERROR
  status, no live tracker, terminal snapshot with "pre-registration" reason
  and real total 1.

### 3. Exit matrix — PASS (one pre-existing cancellation-family residual, documented)

Verified per exit, all with file:line:

- Zero pages: `BatchChapterTranslator.kt:195-205` — `translateBatch` aborts
  the tracker with `remainingPageKeys = emptySet()` and reason "Chapter has
  no readable pages to translate" before returning null. DISTINCT confirmed
  behaviorally: `BatchTerminalExitTest`:95-135 asserts terminal.aborted,
  reason, `totalPages == 0`, and `BatchHeroProjection.of(terminal) ==
  Phase(FAILED_NO_PAGES, isError = true)` — the Slice 1 hero path is still
  wired (`BatchHeroProjection.kt:102,160`). 3/3 executed.
- Engine-setup failure: `BatchChapterTranslator.kt:238-249` — the
  `nativeLane == null` branch aborts with the typed reason; test
  `BatchTerminalExitTest`:138-175 keeps totals 2. This gap was found and
  fixed by the implementer beyond the literal matrix; the contract's
  "unexpected exception" family covers it. VERIFIED.
- OOM abort: `BatchChapterTranslator.kt:540-554` — the `aborted.get()`
  branch aborts with "Translation aborted: device memory pressure (OOM)".
  `remainingAbortKeys` (:713-731) = ordered keys minus durably terminal
  pages (rendered / textless / failed); unit-tested pure
  (`BatchTerminalExitTest`:178-205, executed).
  **Documented accuracy check:** `grep aborted.set` over main sources → no
  matches; the `aborted` flag has NO writer today, so this branch is
  currently unreachable dead code inherited from the T909 move. The
  implementer's report states exactly this. The exit is armed correctly for
  when OOM detection is wired; wiring it is a follow-up, not a Slice 3
  contract item.
- Missing files: `ChapterTranslator.kt:581-593` — `chapterPath == null` →
  `failBeforePipeline(..., "Chapter files not found — ...")` (previously
  only set queue ERROR). E2E test executed
  (`ChapterTranslatorTerminalExitsTest`:150-164) with typed reason asserted.
- Unexpected exception: `ChapterTranslator.kt:713-721` — the non-cancellation
  catch aborts the tracker with "Translation failed unexpectedly: <message>"
  (late events are ignored by `emit` after finish; safe). E2E test executed
  (`ChapterTranslatorTerminalExitsTest`:167-197) with an injected active job
  + throwing `pipeline.translateBatch` stub; typed reason and totals 1
  asserted. Cancellation semantics preserved: :704-712 aborts only when the
  entry left the queue; pause/cancel-with-queue membership keeps the tracker
  resumable (existing semantics, intentionally unchanged).
- `failBeforePipeline` (:736-749) creates the tracker via the production
  factory, publishes ordered-key totals via `rebuildFromStore()`, then
  aborts — a typed terminal snapshot, never a live 0/0. Terminal-on-abort
  closes the tracker via the registry's identity-fenced callback (observed
  in tests as `registry.live` empty + terminal cached).
- Grep for remaining live-tracker leaks between creation (:649, :744) and
  terminal: the only path that leaves a live nonterminal tracker is
  `translationJob?.isActive != true` (`ChapterTranslator.kt:657-658`) —
  pre-existing, cancellation-family (the job is going down; on re-admission
  the registry replaces and closes the old tracker). After item 1 its
  snapshot carries real totals, not 0/0. Not a contract-matrix exit; noted
  as a residual, not a violation. All four matrix exits terminate the
  tracker. No remaining early return between tracker creation and
  finish/pause/abort was found in `translateBatch` (all `return@withGeneration`
  sites preceded by abort/pause/finish — verified at :249, :553, :607, :638).

### 4. Handoff failure split in Downloader — PASS

- Byte-level comparison against `git show HEAD` confirms the finalize
  boundary (page-list fetch, tmp cleanup, DOWNLOADING, image loop,
  `isDownloadSuccessful` early return :445-449, ComicInfo write, onDiskKeys,
  CBZ/rename, cache, NoMedia, DOWNLOADED :485) is unchanged INSIDE the try,
  and its catch (:486-498) contains the same five statements as HEAD plus a
  behavior-neutral `return` — in HEAD, nothing followed the catch, so
  fall-through was already impossible. Finalize-stage failures keep exactly
  the old semantics: download ERROR +
  `markTranslationDownloadFailed(id, "Chapter download failed")` +
  `notifier.onError` (:490-492).
- `handOffAfterFinalization` (:513-539) runs rekey +
  `startTranslationAfterDownloadIfRequested` AFTER `download.status =
  DOWNLOADED` (:485, call at :500). A failure there no longer touches the
  download status — it calls the new seam `markTranslationHandoffFailed`
  with the real cause (:533-537). Cancellation still propagates (:531).
- Typed intent failure: `TranslationRequestCoordinator.kt:207-221` writes
  phase `ADMISSION_FAILED` with kind `QUEUE_ADMISSION_FAILED` (R10 typing —
  never a download failure), existence-checked over live state OR durable
  store, same pattern as `markTranslationDownloadFailed` (:184-199).
  Manager seam: `TranslationManager.kt:342-347`.
- No notification duplication: the handoff path does not call
  `markTranslationDownloadFailed` nor `notifier.onError`; the Slice 2
  lifecycle seams do not fire for a DOWNLOADED chapter leaving the queue
  (Slice 2 verification), and a write over the already-terminal
  ADMISSION_FAILED record never resurrects (Slice 2 invariant).
- Normal downloads: the seam is existence-checked (no-op without a pending
  request — tested, `DownloaderHandoffFailureSplitTest`:284-290) and rekey
  only runs when `onDiskKeys` is non-empty, which requires
  `hasTranslationStore` (:458) — unchanged from HEAD. Verified by tests:
  rekey/handoff failure keeps `DOWNLOADED` + typed failure (:96-146),
  happy path order (:149-166), cancellation propagates (:169-187), no-key
  skips rekey only (:190-205), finalize-stage validation failure keeps old
  semantics exactly with no handoff (:210-238). 8/8 executed.

### 5. Durable terminal reconstruction — PASS

- Gate: `BatchProgressProjector.kt:56-64` — only TRANSLATED /
  READY_WITH_WARNINGS / ERROR / PAUSED reconstructible; live-looking states
  never (unit-tested, `BatchProgressProjectorDurableReconstructionTest`
  :121-131).
- Read-through: the projection's full-miss branch (no live tracker :216, no
  cached terminal :219-221, no queue owner, no active store) consults the
  new constructor seam `reconstructDurableTerminalSnapshot` (default
  `{ null }`, :93-99) and falls back to `empty(chapterId, state)` (:252-264).
  The store-creating queued-owner path (:226-251) is untouched and runs
  only when a queue owner exists. Live tracker wins without attempting
  reconstruction (test :93-118, `reconstructionAttempted == false`).
- No store creation, no new cache: `openOrCreateStoreSuspend` is NOT
  consulted on the reconstruction path (test asserts zero invocations,
  :59-75). `DurableChapterStatusResolver.withDurableStore` (:144-163)
  prefers the active store, else opens through the bounded probe registry
  and releases it in `finally` (`withProbeStore` :166-186,
  `releaseProbe` + `closeAndFlush` :183-185). `TranslationManager.
  reconstructDurableTerminalSnapshot` (:945-973) gates on
  `isReconstructibleDurableState` (:959) and projects through
  `TranslationProgressSnapshot.compute(...).withDurablePause(store)`;
  `resolveTranslation` is injectable for tests. Each collector re-runs the
  read (cold flow) — bounded disk I/O on drawer open, nothing retained.
- Eviction/restart simulation test executed: registry empty + no queue
  owner → drawer flow emits reconstructed terminal (3/3, TRANSLATED,
  FINISHED) (:59-75); nothing reconstructible → empty fallback (:78-90).

### 6. FLAKE ADJUDICATION — test-only determinism bug (a); MUST-FIX for the final gate

Reproduced independently: my focused Run 1 and the full-suite gate run both
failed the same test with the same values (`expected:<2L> but was:<3L>` at
`TranslationRequestGenerationFenceTest.kt:117`, assertion
`store.generation(10L) shouldBe (generation + 1)`), matching the
implementer's Run A exactly. Tally across all known full-command runs of
this suite: implementer 1/3, this review 2/3 — ~50% reproduction.

Exact interleaving (all line numbers verified in source):

1. `acknowledgeTranslationRequests` (test :104) allocates generation 1 under
   the mutation lock and launches the STARTING persistence ASYNC:
   `storeScope.launch(Dispatchers.IO)` →
   `persistPendingStartingAcknowledgement` (`TranslationRequestCoordinator.
   kt:169-173`, :434-464).
2. `queueTranslationAfterDownload` (test :106) synchronously writes the
   WAITING record (gen 1) to the store (:72-83, :324-374).
3. `cancelTranslationRequest` (test :110) → `clearPendingTranslationRequest`
   (:376-388) bumps the in-memory counter 1→2 and calls
   `store.remove(10L)`, which unconditionally bumps the durable tombstone:
   `putLong(generationKey, generation + 1)` with no record-existence check
   (`TranslationPendingRequestStore.kt:148-161`, bump at :159) → tombstone 2.
4. `startTranslationAfterDownloadIfRequested` (test :111) is dropped
   (request gone; attach generation also removed).
5. IF the IO-dispatched commit from step 1 acquires the mutation lock only
   now: `current == null` → the defensive branch
   (`TranslationRequestCoordinator.kt:441-445`, "Keep the durable store
   cleared") calls `pendingRequestStore.remove(10L)` AGAIN → tombstone 2+1
   = 3.
6. The test thread asserts the tombstone is exactly `generation + 1` = 2
   (:117). If step 5 completed first → actual 3 → FAIL; otherwise PASS.
   Whether the IO task lands before the assertion is pure thread-scheduling
   (the fixture uses a real `Dispatchers.IO` scope); assertions :113-115
   (`admitted == false`, live absent, `record == null`) pass in every
   interleaving because `remove` leaves only the generation key.

Why (a) and not (b) — production is correct in every interleaving:

- The re-remove is intentionally defensive and its ONLY observable effect is
  an EXTRA tombstone bump (3 instead of 2). Generations are used solely as
  equality fences (attach-generation vs current-generation,
  `TranslationRequestCoordinator.kt:481-497`) and as monotonic seeds
  (`allocateGeneration` :412-420 takes max(durable tombstone, live) + 1).
  A strictly larger tombstone strengthens the never-reuse invariant; no
  production code compares generations against exact values (grepped all
  `.generation` uses in `eu/kanade/translation` — the other "generation"
  hits are ChapterTranslationStore's unrelated page-patch generation).
- The stale commit cannot destroy a newer record: any re-request allocates a
  new write version, so the commit exits at the version check (:447); live
  and durable mutations share the same lock.
- The R7 invariant the test exists to protect (cancel wins; no resurrection,
  no admission) holds in all interleavings — the failing assertion is an
  over-specified tombstone VALUE, not the invariant.

Minimal fix (test-only; see fix list): drain the fixture's async lane before
the durable assertions (the barrier test already does this at :210-214) and
assert the invariant (tombstone > generation) instead of the exact `+1`.

### 7. Test honesty — PASS

- All new `@Test` bodies are `runTest { ... }` (JVM `TestResult` = `Unit`),
  `runBlocking<Unit> { ... }`, or plain void — no non-void declarations
  (checked per suite; the Slice 2 silent-skip hazard pattern is absent).
- Source `@Test` counts vs fresh JUnit XML testcase counts (run 1 focused
  XML, re-confirmed in the full-run XML): TrackerTotals 5=5, BatchTerminalExit
  3=3, ChapterTranslatorTerminalExits 3=3, DownloaderHandoff 8=8,
  ProjectorDurable 6=6, modified TrackerTest 8=8 — 25/25 new tests executed,
  0 skipped, 0 failures. Full module: 157 suites, 1182 tests, 0 skipped —
  no silent skips anywhere.
- Assertions are behavioral, not smoke: registry live/terminal transitions,
  durable store contents (`TranslationPendingRequestStore.record`),
  `BatchHeroProjection` mapping for the zero-page distinctness, download
  status preservation under injected faults, coVerifySequence ordering,
  read-only reconstruction (no `openOrCreate` invocation), real
  `translateChapterInternal` E2E flows.

### 8. Scope discipline — PASS

- `git status --porcelain` contains exactly the 9 production files + 1 test
  file of the report, plus the 5 new test files/dirs and the report. No
  Slice 1/2 committed code is reverted or reworked; the only Slice 2-area
  additions are the add-only `markTranslationHandoffFailed` coordinator/
  manager seams and the read-through reconstruction (contract items 3-4).
- Visibility relaxations are test-driven and same-module only:
  `Downloader.downloadChapter` / `handOffAfterFinalization` and
  `ChapterTranslator.translateChapterInternal` private→internal
  (Downloader.kt:368, :513; ChapterTranslator.kt:528). No behavior change.
- The sibling worktree `investigate_batch_download_failure` / `7c517d5` was
  not referenced (nothing in the diff or new files derives from it).
- Normal-download semantics: finalize boundary byte-identical (item 4);
  translation seams existence-checked; no new caches; no unrelated
  refactors found in any hunk.

## Test runs (executed by this review)

Focused 28-suite command (implementer's exact suite list), Windows Git Bash,
`JAVA_HOME` = Android Studio JBR:

- **Run 1:** `BUILD FAILED` — 155 tests completed, **1 failed**:
  `TranslationRequestGenerationFenceTest > cancel-then-late-completion-callback
  does not admit or recreate the request` — `AssertionFailedError:
  expected:<2L> but was:<3L>` at `TranslationRequestGenerationFenceTest.kt:
  117`. All other 27 suites green (XML-verified: 28 suites, 155 tests,
  1 failure, 0 errors, 0 skipped). Identical to the implementer's Run A.
- **Run 2 (identical command):** `BUILD SUCCESSFUL` — 155 tests, 0 failures,
  0 errors, 0 skipped (XML-verified). Identical to the implementer's Run B.
- New-suite counts matched source in both runs (25/25 executed).

## Final repair-wide gate — full `:app:testDevDebugUnitTest`

`./gradlew :app:testDevDebugUnitTest --console=plain --rerun` (no --tests
filter; `--rerun` used because the task was UP-TO-DATE after Run 2):

- **Result: 157 suites, 1182 tests, 1 failure, 0 errors, 0 skipped**
  (console: "1182 tests completed, 1 failed"; JUnit XML totals identical).
- The single failure is the adjudicated flake (item 6) — same test, same
  2-vs-3 values.
- **Triage:** the failure is in the T911-touched area
  (`eu.kanade.translation.TranslationRequestGenerationFenceTest`, a Slice 2
  suite), but NOT caused by Slice 3: the Slice 3 diff does not touch the
  coordinator's acknowledge/cancel/persist path (its only coordinator change
  is the add-only `markTranslationHandoffFailed`) nor this test. Root cause
  and interleaving above; pre-existing in the committed Slice 2 test code.
  **No unrelated pre-existing failures exist** — the other 1181 tests,
  including every non-T911 suite in the module, are green. There is no
  out-of-scope baseline to list.

## Minimal fix list (FIX-FIRST — gate blocker)

1. **Test determinism only** — in
   `app/src/test/java/eu/kanade/translation/TranslationRequestGenerationFenceTest.kt`,
   test `cancel-then-late-completion-callback does not admit or recreate the
   request` (:102-118):
   - drain the fixture's async STARTING-commit lane before the durable
     assertions, e.g. `withTimeout(5_000) { laneJob.complete(); laneJob.join() }`
     (same pattern as the barrier test at :210-214); and
   - replace the over-specified exact assertion `store.generation(10L)
     shouldBe (generation + 1)` (:117) with the actual invariant — the
     cancelled generation must never be reusable:
     `store.generation(10L) shouldBeGreaterThan generation` (the
     coordinator's lawful defensive re-tombstone may have bumped it more
     than once).
   No production change is needed or recommended for the gate. (Optional
   hardening, not required: make `persistPendingStartingAcknowledgement`'s
   `current == null` branch remove only when a record still exists, or make
   `TranslationPendingRequestStore.remove` bump only when a record was
   present — both would restore an exact one-bump-per-removal semantics.)

**APPLIED AND VERIFIED (2026-08-30):** the implementer applied both parts
exactly as specified plus the `shouldBeGreaterThan` import; the drain is
placed after the live-state assertions and before the durable ones, and
`laneJob` is a per-instance field (JUnit 5 per-method lifecycle), so the
drain cannot affect other tests. Verified in `git diff` — production files
unchanged (main-only diff +362/-55; with the tracker test's +3/-1 this is
exactly the reviewed +365/-56), the only code delta is the fence test fix,
plus a docs note in the slice 2 report. Implementer isolation: 3/3 fresh
`--rerun-tasks` fence-suite runs, 8 tests, 0 failures each.

## Gate closure (post-fix full-suite run)

`./gradlew :app:testDevDebugUnitTest --console=plain --rerun` (no --tests
filter), executed by this review after the fix:

- **Result: BUILD SUCCESSFUL — 157 suites, 1182 tests, 0 failures,
  0 errors, 0 skipped.** Console and JUnit XML totals agree; no suite in
  the module carries any failure, error, or skip.
- The flake suite is green in the full-suite context that triggered it:
  `TranslationRequestGenerationFenceTest` 8/8 executed, 0 failures
  (fresh XML, 13:49:32). Every previously failing interleaving is now
  either drained deterministically (lane join before assertions) or no
  longer over-specified (`shouldBeGreaterThan`).
- New-suite counts unchanged and still matching source: TrackerTotals 5,
  BatchTerminalExit 3, ChapterTranslatorTerminalExits 3, DownloaderHandoff
  8, ProjectorDurable 6 — 25/25 executed, 0 skipped.
- Expected gate condition met (1182 / 0 / 0 / 0). Slice 3 plus the test
  determinism fix are clear to commit.

Non-blocking notes carried forward: OOM `aborted` flag still has no writer
(follow-up: wire OOM detection); `translationJob?.isActive != true` leaves a
live (real-total) tracker in the cancellation family (registry replacement
cleans up on re-admission); R11 stale download-cache gate remains deferred
per assignment; optional production hardening of the double-tombstone bump
(item 1's parenthetical) remains available but is not required.

VERDICT: ACCEPT
