# T911 Slice 3 implementation report — tracker totals, terminal exits, handoff failure split, durable reconstruction

Implementer role, T911 Slice 3. Branch `t911/repair`, built on top of the
committed Slice 1–2 work (`e5e8011`, `66fa2c9`, report commit `a313863`).
Changes are left UNCOMMITTED in the working tree per contract. Implements
`IMPLEMENTATION.md` "Slice 3" items 1–5 only; no drive-by refactors.

## Contract item 1 — tracker totals from ordered work keys

`app/src/main/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTracker.kt`

- `snapshotFor` (~167-207): the page map is now built from ALL distinct
  ordered page keys. A known key whose store placeholder is missing
  (rejected/delayed pre-registration) is projected as a fresh pending
  `PageTranslation(sourceFileName = pageKey)` placeholder instead of being
  dropped by `mapNotNull`. Event phase-override folding is unchanged and
  applies to placeholders as well (a tracker-only FAILED page event still
  shows). Display map stays the store intersection. Persisted leftovers
  still never inflate totals.
  Totals therefore derive from the ordered keys; completed counts can only
  come from the store intersection because placeholders always project as
  QUEUED and claim no completion.
- `_snapshot` is now initialized with `snapshotFor(Projection())` (~54) and
  the unused `emptySnapshot()` helper was removed, so the very first
  snapshot already reflects the real total — no `rebuildFromStore()` needed
  to escape `0/0`.

`app/src/test/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTrackerTest.kt`
— the old invariant (`totalPages == 0` before `rebuildFromStore`, line 51)
was updated: the total is now 1 immediately from the ordered key; all
post-rebuild assertions unchanged.

## Contract item 2 — pre-registration failure surfaces as explicit error

`app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`

- New nested `PagePreRegistration` sealed class (`Accepted` / `Rejected(reason)`,
  ~1207-1212); `preRegisterPages` (~1226) returns it: empty keys → Accepted,
  defunct store → Rejected("store is defunct"), `admitMutationLocked()`
  rejection (artifact authority / rescue failure) → Rejected(admission
  message). All existing callers compile unchanged (statement calls ignore
  the result).

`app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`

- `translateChapterInternal` (~630-646): a `Rejected` result now routes into
  `failBeforePipeline` (typed terminal exit, below) with reason
  "Page pre-registration was rejected: <reason>" and returns — the
  translator never creates a live nonterminal zero tracker.

Design decision: the rejection surfaces through the tracker/registry path
(create tracker → rebuild → abort) rather than the pending-request failure
path, because at this point queue admission has already cleared pending
ownership; the queue entry is the owner and gets `Translation.State.ERROR`,
and the bounded registry keeps the aborted terminal snapshot with the typed
reason for the drawer.

## Contract item 3 — every exceptional exit emits a typed terminal snapshot

`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt`

- Zero/empty pages (~199-208): `translateBatch` aborts the tracker with
  `remainingPageKeys = emptySet()` and reason "Chapter has no readable pages
  to translate" before returning null. The empty ordered key set keeps this a
  DISTINCT zero-page failure: the hero projection still renders
  `FAILED_NO_PAGES` (verified by test), never a numeric 0/0 or a generic
  failure.
- Engine-setup failure exit (~240-252): the `nativeLane == null` branch now
  also aborts the tracker ("Translation could not start: batch engine setup
  failed or timed out") — same gap as the OOM branch, reachable in tests.
- OOM abort (~543-556): the `aborted.get()` branch now aborts the tracker
  ("Translation aborted: device memory pressure (OOM)") instead of returning
  with the tracker still live/nonterminal.
- New pure internal companion helper `remainingAbortKeys(orderedStreams,
  store)` (~716-731): ordered keys minus durably terminal pages
  (rendered / textless / failed). Used by both the engine-setup and OOM
  branches so the aborted snapshot reports the real remaining work set.
  Unit-tested directly.
- Note (documented finding): the `aborted` AtomicBoolean has NO writer
  anywhere in the codebase today (`grep aborted.set` → no matches) — the OOM
  branch is currently unreachable dead code inherited from the T909 move.
  The branch is now armed with the correct terminal exit for when an OOM
  path starts setting it; covered by the shared `remainingAbortKeys` + abort
  semantics tests. Follow-up: wire OOM detection to actually set the flag.

`app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`

- New private `failBeforePipeline(translation, store, orderedPageKeys,
  reason)` (~736-750): marks the queue entry ERROR, creates a tracker via
  `pipeline.batchTrackerFactory` with the known ordered keys, publishes the
  key totals via `rebuildFromStore()`, then `abort(keys, reason)`. The
  registry's identity-fenced terminal callback caches the snapshot and closes
  the tracker — a typed terminal snapshot with reason, never a live
  nonterminal 0/0.
- Missing chapter files (~578-594): `chapterPath == null` now calls
  `failBeforePipeline(..., "Chapter files not found — the download may have
  been deleted")` (previously only set queue ERROR; no typed reason).
- Unexpected exceptions (~715-721): the non-cancellation catch now aborts the
  tracker with "Translation failed unexpectedly: <message>" in addition to
  setting queue ERROR. Safe when the batch already finished (`emit` ignores
  late events) and intentionally skipped for pause/cancel-with-queue
  membership (resumable), matching the existing cancellation semantics.
- Deliberate boundary: failures during store RESOLUTION (no artifact at all)
  still surface only as queue ERROR with no tracker — there is no store to
  project through and no tracker is created; the drawer shows the ERROR state
  via `projectQueueStatus`. Not a `0/0` tracker hazard.

## Contract item 4 — handoff failure split in the downloader

`app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`

- `downloadChapter` (now `internal`, ~368): the finalize boundary (page-list
  fetch, validation `isDownloadSuccessful`, ComicInfo metadata write,
  CBZ/rename, cache, NoMedia) is unchanged INSIDE the try with its original
  catch (validation/metadata/archive failure → download ERROR +
  `markTranslationDownloadFailed("Chapter download failed")` — exactly as
  today; the catch gained a behavior-neutral `return` so the fall-through
  path after the try is reachable only on success).
- New `internal suspend fun handOffAfterFinalization(download, pageList,
  onDiskKeys)` (~513-540), called AFTER `download.status = DOWNLOADED`
  (~499-500): rekey + `startTranslationAfterDownloadIfRequested` moved out of
  the finalize catch into their own boundary. A failure there no longer flips
  the download to ERROR — the download stays `DOWNLOADED` and the translation
  intent gets the real typed failure via the new manager seam. Cancellation
  still propagates. Normal downloads without a pending request are unaffected
  (the seam is existence-checked, T907-hook style).
- Notification is integrated through the Slice 2 seam style (direct manager
  call, existence-checked in the coordinator) — no new dispatch machinery.

`app/src/main/java/eu/kanade/translation/manager/TranslationRequestCoordinator.kt`

- New `markTranslationHandoffFailed(chapterId, reason)` (~201-222): writes
  phase `ADMISSION_FAILED` with kind `QUEUE_ADMISSION_FAILED` (R10 typing:
  the files are fine, translation could not start — never labeled a download
  failure). Existence-checked like `markTranslationDownloadFailed`.

`app/src/main/java/eu/kanade/translation/TranslationManager.kt`

- Public seam `markTranslationHandoffFailed` (~342-347) delegating to the
  coordinator (same-signature stub pattern).

## Contract item 5 — durable terminal reconstruction

`app/src/main/java/eu/kanade/translation/manager/BatchProgressProjector.kt`

- Pure gate `isReconstructibleDurableState(state)` (~56-66): only TRANSLATED /
  READY_WITH_WARNINGS / ERROR / PAUSED are reconstructible — a crash-mid-run
  artifact must never be projected as running work.
- New constructor seam `reconstructDurableTerminalSnapshot:
  suspend (chapterId) -> TranslationProgressSnapshot?` (default `{ null }`,
  so existing constructions compile untouched), consulted in the projection's
  miss branch (~252-266): no live tracker, no cached terminal snapshot, no
  queue owner, no active store → read-through reconstruction; falls back to
  the previous `empty(chapterId, state)` when nothing is reconstructible.
  Read-only by design: `openOrCreateStoreSuspend` is NOT consulted on this
  path (no store is created) and no new cache is added.

`app/src/main/java/eu/kanade/translation/manager/DurableChapterStatusResolver.kt`

- New `internal suspend fun <T> withDurableStore(...)` (~145-163): resolves
  the durable document and runs `withProbeStore` — prefers the active store,
  otherwise opens through the bounded probe registry and releases afterwards.
  Read-through only.

`app/src/main/java/eu/kanade/translation/TranslationManager.kt`

- New `internal suspend fun reconstructDurableTerminalSnapshot(chapterId,
  resolveTranslation = { Translation.fromChapterId(it) })` (~941-970):
  resolves the chapter, reads the durable status via the existing
  `persistedChapterStatus`, gates on `isReconstructibleDurableState`, then
  projects `TranslationProgressSnapshot.compute(state, store pages/display)
  .withDurablePause(store)` through `withDurableStore`. Wired into the
  projector seam at ~868-870. `resolveTranslation` is injectable so unit
  tests drive the gate without the database. Bounded memory: reuses the
  bounded probe registry + existing durable-status cache; no new global
  cache.

## Tests (all in `app/src/test`)

New suites:

| Suite | Tests (XML) | Result |
| --- | --- | --- |
| `eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTotalsTest` | 5 | pass |
| `eu.kanade.translation.pipeline.batch.BatchTerminalExitTest` | 3 | pass |
| `eu.kanade.translation.ChapterTranslatorTerminalExitsTest` | 3 | pass |
| `eu.kanade.tachiyomi.data.download.DownloaderHandoffFailureSplitTest` | 8 | pass |
| `eu.kanade.translation.manager.BatchProgressProjectorDurableReconstructionTest` | 6 | pass |

Coverage per required matrix:

- Tracker totals: nonzero total immediately from ordered keys with an EMPTY
  store; completed counts from the store intersection only (1 done + 1
  placeholder); placeholder registration does not inflate completed counts;
  phase events apply to placeholders; aborted terminal snapshot keeps totals
  with the typed reason. Old zero-total expectation updated in
  `TranslationBatchProgressTrackerTest`.
- Pre-registration rejection: store returns `Rejected` (defunct) and
  `translateChapterInternal` produces the aborted terminal snapshot
  ("pre-registration" reason), totals 1 from the known key, no live tracker.
- Exit matrix: zero-page (real `translateBatch` with empty streams → aborted
  terminal, reason, registry cache, hero == `FAILED_NO_PAGES` — DISTINCT from
  other failures); engine-setup failure exit (totals kept, reason); OOM abort
  remaining-keys computation (pure `remainingAbortKeys`: rendered/textless/
  failed excluded, pending/running/unknown included) + shared abort
  mechanics; missing files (E2E through `translateChapterInternal`, typed
  reason); unexpected exception (E2E: injected active translator job +
  `translateBatch` throwing → aborted terminal snapshot with the thrown
  reason, totals kept).
- Downloader fault injection: injected rekey throw and injected handoff throw
  each leave the download `DOWNLOADED` and record the typed translation-intent
  failure with the real cause (and skip/keep the respective steps); happy
  path verifies rekey-then-handoff sequence with no failure; CancellationException
  propagates; no-key handoff skips rekey only; finalize-stage validation
  failure through `downloadChapter` keeps the OLD semantics exactly (download
  ERROR + `markTranslationDownloadFailed("Chapter download failed")`, no
  handoff). `markTranslationHandoffFailed` writes ADMISSION_FAILED +
  QUEUE_ADMISSION_FAILED durably (real uninitialized-manager fixture) and is
  a no-op without a pending request.
- Terminal reconstruction: registry empty (eviction/restart simulation) + no
  queue owner → the drawer flow emits the reconstructed terminal snapshot
  (totals 3/3, TRANSLATED, FINISHED) and never invokes the store-creating
  path; nothing reconstructible → empty fallback; live tracker still wins
  without attempting reconstruction; state gate unit-tested; resolver
  read-through returns null for an unresolvable source and prefers the
  active store without opening a probe.

All `@Test` bodies are `runTest { ... }` / `runBlocking<Unit> { ... }` (void);
JUnit XML testcase counts equal the authored method counts (25/25 executed,
0 skipped) — the Slice 2 silent-skip hazard is checked.

Test-infra note: chapter page enumeration is stubbed at the top-level
`getChapterPages` seam (`mockkStatic`) because the real implementation
filters through `ImageUtil`, whose class initializer needs Android graphics
and cannot load on the JVM (verified: `ExceptionInInitializerError` /
`NoClassDefFoundError` without the stub). Enumeration itself is NOT the
behavior under test.

## Verification

Command (Windows Git Bash, full 28-suite set = 23 Slice 1–2 suites + 5 new):

```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" \
./gradlew :app:testDevDebugUnitTest --console=plain \
  --tests "eu.kanade.translation.model.BatchHeroProjectionTest" \
  --tests "eu.kanade.tachiyomi.ui.manga.ChapterTranslationSnapshotRegistryTest" \
  --tests "eu.kanade.presentation.manga.components.ChapterTranslationIndicatorRoutingTest" \
  --tests "eu.kanade.tachiyomi.ui.manga.MangaScreenModelTranslationDrawerTest" \
  --tests "eu.kanade.tachiyomi.ui.manga.EnqueueTranslationDownloadsTest" \
  --tests "eu.kanade.translation.TranslationManagerPendingAcknowledgementTest" \
  --tests "eu.kanade.translation.TranslationManagerDownloadFailureRecoveryTest" \
  --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest" \
  --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTest" \
  --tests "eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistryTest" \
  --tests "eu.kanade.translation.pipeline.batch.BatchProgressReconcilerTest" \
  --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressReducerTest" \
  --tests "eu.kanade.translation.model.TranslationUiProjectionTest" \
  --tests "eu.kanade.translation.model.TranslationProgressTest" \
  --tests "eu.kanade.translation.TranslationPendingRequestStoreTest" \
  --tests "eu.kanade.translation.ChapterTranslatorQueueRestoreTest" \
  --tests "eu.kanade.translation.TranslationQueueStoreTest" \
  --tests "eu.kanade.translation.TranslationRequestGenerationFenceTest" \
  --tests "eu.kanade.translation.TranslationManagerDownloadNotificationsTest" \
  --tests "eu.kanade.translation.TranslationManagerQueueAdmissionFailureKindTest" \
  --tests "eu.kanade.translation.TranslationManagerStartupReconciliationTest" \
  --tests "eu.kanade.presentation.manga.components.TranslationQueuePositionAndPhasesTest" \
  --tests "eu.kanade.tachiyomi.ui.manga.MangaScreenModelMultiSelectBatchTest" \
  --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTotalsTest" \
  --tests "eu.kanade.translation.pipeline.batch.BatchTerminalExitTest" \
  --tests "eu.kanade.translation.ChapterTranslatorTerminalExitsTest" \
  --tests "eu.kanade.tachiyomi.data.download.DownloaderHandoffFailureSplitTest" \
  --tests "eu.kanade.translation.manager.BatchProgressProjectorDurableReconstructionTest"
```

Results (JUnit XML verified, `app/build/test-results/testDevDebugUnitTest/`):

- Run A (first full run): 155 tests, 1 failure —
  `TranslationRequestGenerationFenceTest` "cancel-then-late-completion-callback
  does not admit or recreate the request" (`generation(10L)` expected 2, was
  3). See flake note below.
- Run B (rerun, identical command): **155 tests, 0 failures, 0 errors, 0
  skipped — BUILD SUCCESSFUL** (28 suites green: 23 pre-existing + 5 new).
- Run C (`--rerun-tasks`, clean rerun): **BUILD SUCCESSFUL** again.
- New-suite detail (Run A XML): TrackerTotals 5, BatchTerminalExit 3,
  ChapterTranslatorTerminalExits 3, DownloaderHandoff 8, Projector 6 —
  25/25 executed, 0 failures.

### Flake note (pre-existing, not a Slice 3 regression)

The single Run A failure is in Slice 2's
`TranslationRequestGenerationFenceTest` and is a timing-dependent race in
that fixture: the coordinator's async STARTING persistence commit
(`storeScope.launch(Dispatchers.IO)`) races the cancel tombstone write; under
full-suite JVM load the tombstone generation can observe one extra
allocation-bump (2 vs 3). The suite passes in isolation, passed in both
subsequent full runs, and the assertion path involves only Slice 2 code —
the Slice 3 diff touches none of it (verified against `git diff`: the
coordinator change is the add-only `markTranslationHandoffFailed`; the
manager change is the add-only handoff seam + durable reconstruction).
Follow-up recommendation: the fence fixture should await the async STARTING
commit (or use a synchronous store scope) before cancelling.

## Deviations and documented follow-ups

1. **OOM abort branch is currently unreachable** — no code sets the
   `aborted` AtomicBoolean today (inherited from the T909 move). The branch
   now terminates the tracker correctly and its key computation is unit
   tested, but actual OOM detection wiring is a separate fix (follow-up).
2. **Engine-setup failure exit also terminated** — same gap class as the OOM
   abort, found and fixed while making the exits testable; the contract's
   "unexpected exception" family covers it.
3. **Pre-registration + unexpected-exception E2E tests stub enumeration** at
   the `getChapterPages` seam because `ImageUtil` cannot class-initialize on
   the JVM; the missing-files test exercises the real
   `translateChapterInternal` wiring end-to-end (no enumeration needed), so
   the shared `failBeforePipeline` → tracker → registry → drawer chain is
   proven with real code.
4. **Store-resolution failures (no artifact at all)** deliberately keep
   today's shape (queue ERROR, no tracker) — nothing exists to project a
   terminal snapshot from; not a `0/0` tracker hazard. Documented boundary,
   not a gap.
5. **Stale-cache terminal visibility (R11)** — out of scope per assignment;
   the reconstruction here reads the durable store when the projector runs;
   R11's stale `downloadState == DOWNLOADED` cache gate lives in
   MangaScreenModel's row wiring and is explicitly left for a later slice.
6. **`clearStaleDownloadFailedRequest` still clears only
   DOWNLOAD_FAILED/CANCELLED/ADMISSION_FAILED** — unchanged; ADMISSION_FAILED
   records from the new handoff failure reuse the existing reader-entry
   cleanup path (Slice 2 deviation 3), so no new cleanup was needed.
7. **Visibility relaxations for fault-injection tests** (same module only):
   `Downloader.downloadChapter` and `Downloader.handOffAfterFinalization`
   private→internal; `ChapterTranslator.translateChapterInternal`
   private→internal. No behavior change.

## Files changed

Modified (production):
- `app/src/main/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTracker.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt`
- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`
- `app/src/main/java/eu/kanade/translation/manager/TranslationRequestCoordinator.kt`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/translation/manager/BatchProgressProjector.kt`
- `app/src/main/java/eu/kanade/translation/manager/DurableChapterStatusResolver.kt`

Modified (tests):
- `app/src/test/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTrackerTest.kt`

Added (tests):
- `app/src/test/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTrackerTotalsTest.kt`
- `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchTerminalExitTest.kt`
- `app/src/test/java/eu/kanade/translation/ChapterTranslatorTerminalExitsTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/download/DownloaderHandoffFailureSplitTest.kt`
- `app/src/test/java/eu/kanade/translation/manager/BatchProgressProjectorDurableReconstructionTest.kt`

Not touched: sibling worktree `investigate_batch_download_failure`;
`7c517d5` not referenced or copied. Normal non-translation downloads:
finalize-stage semantics byte-identical (same catch body, same failure
seams); translation seams are existence-checked no-ops without a pending
request; no new caches; registry bounds unchanged.
