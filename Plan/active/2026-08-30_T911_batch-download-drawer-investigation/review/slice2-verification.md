# T911 Slice 2 verification review — durable coordinator, reconciliation, multi-select

Independent Reviewer pass over the uncommitted Slice 2 working tree on
`t911/repair` (Slice 1 committed at `e5e8011`). Every claim below was checked
against the live source and the re-run test results, not the implementer's
report. Evidence labels per `docs/roles/reviewer.md`.

Baseline: 19 modified production/test files + 8 untracked test/report files in
`git status`, matching the slice report's file list. No production or test code
was modified by this review.

## Executive verdict

The Slice 2 implementation is substantially correct: every mechanism the
contract requires exists in source and reads sound. However, **five of the new
tests never execute** — JUnit 5 silently ignores `@Test` methods with non-void
return types, and the implementer's reported counts are wrong — and among the
never-executed tests are ALL THREE completion-callback fence tests and TWO OF
THE FIVE startup-reconciliation rules. The acceptance gate ("focused suites
green, exact counts") cannot be verified for those behaviors, so this is a
FIX-FIRST verdict with a trivial, mechanical fix.

## Test re-run (authoritative numbers)

Reconstructed the implementer's elided 23-suite command as: all 14 Slice 1
suites + `TranslationManagerAutoArbitrationTest` + `ChapterTranslatorQueueRestoreTest`
+ `TranslationQueueStoreTest` + the 6 new Slice 2 suites (exactly the suites the
report names). Run with `--rerun-tasks`, fresh JVM:

```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDevDebugUnitTest \
  --console=plain --rerun-tasks --tests <23 suites>
BUILD SUCCESSFUL in 4m 59s (205 tasks, exit 0)
```

Real result: **23 suites, 124 tests executed, 0 failures, 0 errors,
0 skipped.** The report claims 125 with fence=7 and reconcile=7 — both wrong
(actual 4 and 5; a truthful run of the same command cannot produce 125).

### The five tests that never execute (CRITICAL test defect)

`javap` on the compiled test classes proves the cause — the methods return
non-void values, and the JUnit 5 platform silently refuses to run non-void
`@Test` methods (no error, no skip marker):

| Suite | Declared in source | Executed | Missing method (return type) |
| --- | --- | --- | --- |
| `TranslationRequestGenerationFenceTest` | 7 | 4 | `cancel-then-late-completion-callback does not admit or recreate the request` (returns `long`), `re-requested generation fences the previous request's late callback` (returns `Long`), `current generation callback admits exactly once and clears the request` (returns `boolean`) |
| `TranslationManagerStartupReconciliationTest` | 7 | 5 | `pending plus download-queue member - normalized to WAITING` (returns `TranslationRequestPhase`), `pending with neither queue nor files - explicit interrupted failure not deleted` (returns `TranslationRequestFailureKind`) |

Each method's final expression is a kotest `shouldBe` whose operand type
becomes the method's return type (e.g. `store.generation(10L) shouldBe
(generation + 1)` → `Long`). The remaining 17 suites' source `@Test` counts all
match their XML counts exactly (verified one-by-one), so the blast radius is
exactly these five methods.

Consequences, classified **HIGH / defect (test)**:

- The completion-callback fence — the heart of contract item 2 — has ZERO
  executing coverage (cancel-then-callback dropped, re-request fence,
  current-generation admits once: all three silent no-ops).
- Two of the five reconciliation rules required to be "implemented and tested"
  (contract item 4) have no executing coverage: download-queue → WAITING
  normalization, and neither-queue-nor-files → explicit INTERRUPTED failure.
- The suites still report green, so nothing surfaces the gap.

The production behavior itself was verified by code reading (below) and appears
correct; the defect is that the acceptance evidence does not exist.

## Per-item verification

### 1. Generation fence end-to-end — CONCERN (mechanism present; one non-atomic window)

- Allocation per request: `TranslationRequestCoordinator.kt:145-174`
  (`acknowledgeTranslationRequests` allocates one generation per chapter under
  `pendingRequestMutationLock`), `:390-398` (`allocateGeneration` seeded from
  durable tombstone + live state). VERIFIED.
- Bump on cancel/re-request: `clearPendingTranslationRequest` (:354-366)
  increments; `setPendingTranslationRequest` (:318-325) allocates a fresh
  generation when a live phase is written over a terminal record. VERIFIED
  (tested by the executing `generation advances after each download-side
  cancellation` notifications test).
- Downloader completion callback fenced:
  `startTranslationAfterDownloadIfRequested` (:450-466) drops when the request
  is gone, terminal, never attached, or re-requested (attached generation !=
  current generation); never admits, never recreates. VERIFIED by reading —
  but its three dedicated tests are among the never-executed five.
- Screen-model probe re-checks before EACH durable mutation:
  `MangaScreenModel.kt:1067-1080` (generations captured right after the
  synchronous acknowledgement), WAITING writes fenced via
  `queueTranslationAfterDownloadIfCurrent` (coordinator :90-101, check+write
  under the lock), PREPARING via `markTranslationRequestPreparingIfCurrent`
  (:104-110), admission via `translateChapter(manga, chapter, generation)` /
  `translateChaptersIfCurrent` — the whole admit sequence runs under
  `pendingRequestMutationLock` (`TranslationManager.kt:644-695, 713-759`), so a
  cancel either lands before the write (fence drops) or after admission
  completes. R7's probe check/use gaps are closed. VERIFIED.
- Residual window (MEDIUM, narrow): the callback path itself is not atomic —
  `currentRequest` + `isTerminal` + attach-generation comparison (:452-463)
  run OUTSIDE the lock, then `setPendingTranslationRequest(PREPARING)` and
  unfenced `translateChapter(manga, chapter)` (:464-465). A user cancel landing
  between the check and the PREPARING write is resurrected as a NEW generation
  PREPARING record and the translation is admitted — exactly the R7 class the
  slice set out to close, on the completion path. Window is sub-millisecond
  (no suspension between statements) and requires a cancel racing a download
  completion; the fence catches every cancel that lands before the check.
  Minimal fix: re-check the fence and write PREPARING under the mutation lock
  (reusing `markTranslationRequestPreparingIfCurrent` semantics) and call
  `translateChapter` with the expected generation.
- Bypass check: remaining unfenced pending-state callers are
  `MangaScreenModel.kt:951` (undo-cancel snackbar) and `:1194`
  (`confirmReplaceRunningChapter`) — both are explicit fresh user intents where
  a fence would be wrong; the reconciler's mutations re-fence admission under
  the lock; notification transitions are existence-checked. No bypassing path
  found. VERIFIED.
- Reconciler honors generations: `admitRestoredPendingTranslation`
  (`TranslationManager.kt:527-549`) re-reads the generation (live state OR
  durable record) inside the lock and drops the admission when it moved.
  VERIFIED (and the executing cancel-during-pass test covers it).

### 2. Store migration — PASS

- Legacy entries parse with safe defaults: `record()`
  (`TranslationPendingRequestStore.kt:52-66`) — generation 0, no group, kind
  NONE, zero timestamps; `runCatching` guards unknown enum strings.
- Round-trip: `add(record)` (:78-104) writes all keys; verified by the
  executing `rich record round-trips every new field` test.
- Legacy 3-arg shim preserves generation/group/kind, refreshes `updatedAt`
  (:111-126), verified by an executing test; `load()`'s numeric-key filter
  still hides all auxiliary keys (executing test).
- Tombstone: `remove()` (:148-161) bumps and keeps `<id>.generation`;
  re-requests can never reuse a removed generation (executing store test +
  executing fence-race test).
- Growth bound (review instruction): tombstones have **no cleanup path** except
  `clear()` — one ~30-byte SharedPreferences key per removed/cancelled chapter
  ever, plus one in-memory `AtomicLong` entry per touched chapter
  (`pendingRequestGenerationCounters`). LOW / design limitation, accepted:
  growth is user-action-bound (one cancel = one entry), far below any memory
  pressure on the 6 GB floor, and pruning tombstones would weaken the fence
  (a pruned generation could be reused after restart). Justified as bounded-in-
  practice by construction of usage, not by code. Note: `clearAllPendingTranslationRequests`
  (:368-381) wipes tombstones via `store.clear()` but the in-memory counters
  survive, so in-process reuse is impossible; across a process restart after a
  clear, counters re-seed at 0, but no callback survives process death, so the
  fence holds.

### 3. Notification seams (R5) — PASS

Every seam traced in `Downloader.kt`:

- `stop(reason)` (:155-180): notifies `onDownloadStoppedForTranslation` for
  every DOWNLOADING → ERROR flip. Covers the offline/Wi-Fi policy stop
  (`DownloadJob.kt:91,97` via `DownloadManager.downloaderStop`).
- **Pause does NOT notify** — `DownloadManager.pauseDownloads()`
  (`DownloadManager.kt:84-87`) calls `downloader.pause()` first (:188-196
  drains DOWNLOADING → QUEUE), so `stop()` finds nothing to notify. The
  report's "pause+stop leaves nothing to notify" is VERIFIED. Pause keeps
  pending requests WAITING (correct).
- `clearQueue()` (:199-212): notifies `QUEUE_CLEARED` for every
  DOWNLOADING/QUEUE entry captured before `internalClearQueue()`.
  `DownloadManager.clearQueue()` = clear + stop → stop finds nothing (no double
  terminal flip; a second transition over a terminal record only rewrites the
  reason, never resurrects).
- `removeFromQueue(download)` (:735-752) and `removeFromQueueIf` (:754-770):
  notify cancel only for ACTIVE (DOWNLOADING/QUEUE) removals; a DOWNLOADED
  chapter leaving after success does not notify (success belongs to the
  completion callback). `DownloadManager.removeFromDownloadQueue` pauses first,
  so bulk deletes are QUEUE (active) when removed — notified.
- Outer catch (`launchDownloadJob`, :275-290): pre-protected-try failures mark
  `DOWNLOAD_FAILED` before stopping the downloader.
- `queueChapters` non-HTTP rejection (:308-317): marks
  `SOURCE_UNSUPPORTED` per chapter instead of stranding WAITING.
- Each seam is a no-op without a pending request: existence check in
  `transitionAttachedRequest` (coordinator :232-243) and
  `markTranslationDownloadFailed` (:184-199) over live state OR durable store.
  The executing `notifications are a no-op without a pending request - no phase
  write` test asserts zero writes. Normal downloads unaffected. VERIFIED.
- Terminal typing: cancelled/removed → `CANCELLED`/`CANCELLED`; cleared →
  `CANCELLED`/`QUEUE_CLEARED`; stopped → `DOWNLOAD_FAILED`/`DOWNLOADER_STOPPED`
  with reason (coordinator :206-224). Durability asserted by executing tests.
- No indefinite WAITING (R5): cancel/remove/clear/stop/offline/outer-catch/
  non-HTTP all terminate the request. Two residual WAITING stranders, both
  declared or negligible: (a) the already-downloaded silent filter in
  `queueChapters` (report deviation 2, deferred to Slice 3 with rationale);
  (b) `updateQueue` reorder is silent, but its only caller
  (`DownloadManager.reorderQueue`, used by reorder/startDownloadNow) preserves
  the full set, so no entry is dropped. VERIFIED with the declared exception.
- Design observation (not a violation): a transient offline stop permanently
  fails the attached request (`DOWNLOAD_FAILED`/`DOWNLOADER_STOPPED`); the
  queued download itself resumes on reconnect and completes, but the terminal
  request is fenced (`isTerminal`), so translation is not auto-admitted after
  resume — the user sees a truthful, retryable failure instead of the old
  silent forever-WAITING. This is the contract-mandated trade-off.

### 4. Startup reconciler — CONCERN (implementation PASS; 2 of 5 rule tests never execute)

- Barrier: `onDownloadQueueRestored(queuedChapterIds)`
  (`TranslationManager.kt:399-404`) is called by `Downloader.init` after its
  async `store.restore()` completes (`Downloader.kt:118-125`); the translation
  side captures `translationQueueRestoreJob` and calls
  `runStartupReconciliationIfReady()` after `translator.restoreQueue()`
  (:252-262). One-shot via `AtomicBoolean` consumed only when the downloader
  snapshot is present (:407-417), so either restore completing late still
  triggers the pass. No polling. VERIFIED.
- Deadlock: the wait is `translationQueueRestoreJob?.join()` in a
  `Dispatchers.IO` launch — no lock is held, so a hung restore starves the pass
  but cannot deadlock any thread; both restores completing normally is the
  production case. ACCEPTABLE.
- Five rules implemented: queue-wins (clear pending), download-queue → WAITING
  (generation kept), valid files → admit once via `admitRestoredPendingTranslation`
  (fenced, no `startTranslation()` — declared deviation consistent with the
  no-auto-OCR/LLM policy), neither → `DOWNLOAD_FAILED`+`INTERRUPTED` (kept,
  never deleted), missing chapter/source → purge. VERIFIED by reading.
- Tested: 5 of 7 tests execute (queue-wins, files-admit-once, missing-purge,
  idempotent second pass, cancel-during-pass fence). The WAITING-normalization
  and neither→INTERRUPTED rule tests are among the never-executed five (see
  re-run section). Contract requires "each of the five resolution rules
  implemented and tested" — FAIL as of this tree.
- Idempotent and race-fenced: executing tests cover the second pass and the
  cancel-during-pass guard. Concurrent completion during the pass is
  structurally implausible (restored download queues do not auto-start), and
  the admission re-fences under the lock regardless. VERIFIED.

### 5. Multi-select (R6) — PASS

- Both layouts: `MangaScreen.kt` (presentation) threads the new
  `onTranslationChapters` into `MangaScreenSmallImpl` (:333-339) AND
  `MangaScreenLargeImpl` (:587-593); each bottom bar calls
  `handler(items, ChapterTranslationAction.START)` exactly once — the old
  `items.forEach { handler(it, START) }` loop is gone from both.
- UI wiring: `ui/manga/MangaScreen.kt:138-146` wires single (row taps) and list
  (bottom bar) overloads.
- ONE confirmation: `runChapterTranslationActions(items, START)` stores the
  whole group once (`pendingTranslationGroup = items`); when the confirm
  preference is on, `showConfirmTranslationDialog(items)` creates a single
  `Dialog.ConfirmTranslation(primary, summary, items)`;
  `ConfirmTranslationDialog` renders `chapterNames: List<String>` — every
  selected chapter listed.
- Acknowledge all N: confirm proceeds via `confirmChapterTranslation(primary)`;
  the group is recovered from `pendingTranslationGroup` (contains the primary)
  and `acknowledgeTranslationRequests` is called ONCE with all N
  (`MangaScreenModel.kt:1032-1040`). Verified by the executing multiselect test
  (one acknowledgement capturing 5,6,7).
- Mixed partition: downloaded → `translateChaptersIfCurrent` for >1 (list
  admission; `translateChaptersInternal` never calls
  `evictStaleQueuedChapters`, so no same-source silent eviction) or the fenced
  single path; undownloaded → fenced WAITING writes + ONE
  `enqueueTranslationDownloads`. Drawer opens for the primary chapter
  (`openTranslationProgressDrawer(item)`, item = first). All verified by the
  executing multiselect tests (mixed, all-downloaded, fence-refusal).
- Single-chapter path unchanged: same preflight/conflict dialog; the only
  deltas are the fenced PREPARING write and fenced `translateChapter` —
  behavior-preserving. VERIFIED.

### 6. R10 admission failures — PASS

- `markTranslationQueueFailureIfAcknowledged` (`TranslationManager.kt:761-776`)
  writes `ADMISSION_FAILED` with `translationQueueAdmissionFailureKind`
  (`TranslationRequestState.kt`) — `SOURCE_UNSUPPORTED` (non-HTTP),
  `CONFIG_INVALID` (`isQueueConfigValid` mirrors `queueChapter`'s rejection
  order incl. ML Kit), else `QUEUE_ADMISSION_FAILED` — never
  `DOWNLOAD_FAILED`. `queueChapter` catches config errors internally and
  returns silently (`ChapterTranslator.kt:498-528`), so the classification is
  reachable in production for every rejection `queueChapter` can produce.
- Sheet renders it truthfully: `TranslationProgressSheet.kt` — pill label
  "Translation could not be queued", subtitle
  "Translation could not be queued — check the source and translation settings"
  (:851-876); hero `ADMISSION_FAILED` phase with error styling
  (`BatchHeroProjection.kt:47-50, 126-129`); new i18n strings
  (`strings.xml:226-229`). Executing tests assert the subtitle never contains
  "Download failed" and the hero mapping.
- Note (LOW): `isQueueConfigValid()` reads preferences at failure time, not
  admission time — misclassification only if prefs changed mid-admission;
  negligible.

### 7. Queue position — PASS

- Projection: `translationQueuePosition` (`BatchProgressProjector.kt:36-47`)
  computes the 1-based index among QUEUE/TRANSLATING entries + total; attached
  only to QUEUE-state snapshots via `withQueuePosition` (:253-268).
- Rendering honest: first-in-line keeps "Queued — ready to resume remaining
  pages"; later chapters read "Queued (2nd of 3) — waiting for earlier batches"
  (`TranslationProgressSheet.kt:588-600, 920-930`); null position falls back to
  the existing wording — no fabricated numbers. Executing tests cover ordinals,
  label, first-in-line, fallback, and subtitle.
- LOW: the pure `translationQueuePosition` function itself has no direct test
  (label/subtitle are tested; the position math is not).

### 8. Concurrency / thread-safety — PASS (with notes)

- Attach/generation maps (`pendingRequestGenerationCounters`,
  `downloadAttachGenerations`, `pendingGroupIdSequence`): all
  `ConcurrentHashMap`/`AtomicLong`; structural mutations happen under
  `pendingRequestMutationLock` (allocate :390-398 called from lock-holders;
  clear/remove under lock :354-381); lock-free reads are benign-staleness.
  Touching threads: downloader IO threads (callbacks, notifications), main
  (screen-model probe), storeScope IO (STARTING commits), applicationScope IO
  (reconciler). VERIFIED.
- Lock order: mutation lock → store monitor (`@Synchronized` store methods);
  the store never calls back into the manager, so no inverse order exists. The
  new admit-under-lock sequences hold the mutation lock across N synchronous
  store commits and translator admissions (`translateChaptersInternal`
  :713-759, `admitRestoredPendingTranslation`) — bounded and small; a cancel
  waits for the batch instead of interleaving (this is the point). No deadlock
  observed in runs; translator-internal locks are only ever acquired after the
  mutation lock, never the reverse.
- STARTING publish-before-commit + version fence: shape unchanged
  (:412-442); the commit now writes the rich record with the allocated
  generation/groupId and is suppressed when a cancel or a newer phase landed
  first. No new race introduced. VERIFIED.

### 9. Scope discipline — PASS

- No tracker-total changes: `TranslationBatchProgressTracker` and the registry
  are untouched (not in the diff).
- Finalization/rekey split (Slice 3 item 3): NOT attempted — `Downloader.kt`
  still marks DOWNLOADED before rekey/handoff inside one try, and a handoff
  failure still flips the download to ERROR (:486-495). Correctly deferred; the
  only touch is adding typed kinds + the outer-catch/notifications (R5 scope).
- Normal downloads: every seam is existence-checked (executing no-op test);
  pause verified non-notifying; reorder silent by design with full-set
  preservation. No unrelated refactors found; the `translateChapter(s)`
  refactors are in-scope fencing work.
- Declared deviations reviewed and accepted: no auto-start of restored
  admissions (policy gate), `clearStaleDownloadFailedRequest` also clears the
  new terminal phases (reader-entry cleanup exists: `ReaderViewModel.kt:2133`),
  no `startTranslation()` when nothing was admitted, test-only
  ScreenModelStore eviction.
- Style nit (LOW): `ChapterTranslator.kt:492` — statement glued to the
  `queueChapter` brace line (`fun queueChapter(...) {        val source = ...`).

### 10. Test honesty — FAIL (the decisive finding)

- Suites that DO execute assert behavior, not smoke: real stores
  (`InMemorySharedPreferences`), durability assertions against persisted keys,
  admit/no-admit verification against translator stubs, a 64-iteration
  cancel-vs-fenced-write thread race, end-to-end screen-model flows with call
  verification. Quality of the executing tests is good.
- But 5 of the 47 new tests never execute (non-void `@Test` methods, silently
  skipped by JUnit 5 — see re-run section), including all completion-callback
  fence coverage and 2 of 5 reconciliation rules.
- The report's test evidence is inaccurate: claims fence=7/reconcile=7 and a
  125-test total; a truthful fresh run of the same 23-suite command yields 4/5
  and 124. The claimed "exact test counts" gate was therefore not met. The
  report's suite arithmetic ("15 Slice 1 suites + 3 + 6 = 23") is also
  internally inconsistent (Slice 1 ran 14; 14+3+6=23 only without
  `TranslationPendingRequestStoreTest` double-counted, and it was included in
  both lists).

## Minimal fix list (FIX-FIRST)

1. Make the five never-executing `@Test` methods return `Unit` — e.g. declare
   them `= runBlocking<Unit> { ... }` (or end each with a Unit expression):
   - `app/src/test/java/eu/kanade/translation/TranslationRequestGenerationFenceTest.kt`:
     `cancel-then-late-completion-callback does not admit or recreate the
     request` (:94), `re-requested generation fences the previous request's
     late callback` (:112), `current generation callback admits exactly once
     and clears the request` (:135).
   - `app/src/test/java/eu/kanade/translation/TranslationManagerStartupReconciliationTest.kt`:
     `pending plus download-queue member - normalized to WAITING` (:148),
     `pending with neither queue nor files - explicit interrupted failure not
     deleted` (:195).
   Mechanically trivial; each method's assertions are already correct once it
   actually runs.
2. Re-run the 23-suite command and correct the slice2 report's suite counts and
   total (expected: fence=7, reconcile=7, grand total 129 for the same
   command — not 125).
3. (Recommended, small, same area as item 1's subject matter): close the
   completion-callback check/use window — do the fence re-check + PREPARING
   write under `pendingRequestMutationLock` and pass the expected generation to
   `translateChapter` from `startTranslationAfterDownloadIfRequested`
   (`TranslationRequestCoordinator.kt:450-466`), so a cancel landing inside the
   callback window cannot resurrect the request and admit translation.

Items 1-2 are the gate blockers; item 3 is recommended before Slice 3 because
it touches the exact fencing Slice 3 builds on.

VERDICT: FIX-FIRST

## Re-verification (2026-08-30, after implementer fixes) — all three items hold

Focused re-check of the three FIX-FIRST items on the updated working tree.
No code was modified by this review.

### 1. The five previously-silent tests now execute — VERIFIED (JUnit XML, not console)

Fresh `--rerun-tasks` run of the same 23-suite command; verified against the
JUnit XML, whose `testcase` entries prove execution (source `@Test` counts now
match XML exactly for all 23 suites):

- `TranslationRequestGenerationFenceTest`: **8/8 executed** — includes
  `cancel-then-late-completion-callback does not admit or recreate the request`,
  `re-requested generation fences the previous request's late callback`,
  `current generation callback admits exactly once and clears the request`
  (the three previously-silent callback-fence tests, bodies unchanged, now
  declared `runBlocking<Unit>` at :103/:121/:144) plus the new barrier test.
- `TranslationManagerStartupReconciliationTest`: **7/7 executed** — includes
  `pending plus download-queue member - normalized to WAITING` and
  `pending with neither queue nor files - explicit interrupted failure not
  deleted` (the two previously-silent rule tests, bodies unchanged, now
  `runBlocking<Unit>` at :148/:195).

### 2. Atomic completion-path fence — VERIFIED (no remaining check-to-write window)

- `startTranslationAfterDownloadIfRequested`
  (`manager/TranslationRequestCoordinator.kt:450-480`) now performs the
  existence check, terminal check, attach-generation comparison, AND the
  PREPARING write inside one `synchronized(pendingRequestMutationLock)` block,
  returning the captured generation; a cancel is fully serialized — it either
  runs first (callback dropped) or after the PREPARING write (request removed
  by the cancel itself, never resurrected).
- The admission re-validates the generation under the same lock before any
  mutation: `translateChapter(manga, chapter, generation)`
  (`TranslationManager.kt:649-675`) aborts when the live generation moved, and
  the whole admit sequence (preparing/queue/clear-or-fail) holds the lock, so
  no cancel can interleave between fence and admission. A cancel landing in
  the (lock-free) gap between the two synchronized blocks leaves the live map
  empty, so the admission fence sees `null != expected` and drops. No
  resurrection path remains.
- Seam wiring: the coordinator's `translateChapter` provider is now
  `(Manga, Chapter, Long?) -> Unit` (`TranslationRequestCoordinator.kt:55`);
  there is exactly ONE construction site (`TranslationManager.kt:151-163`),
  wired to the 3-arg `translateChapter`. Test fixtures build the manager by
  reflection, not the coordinator, so no other site exists. VERIFIED.

### 3. New barrier test — VERIFIED (deterministic no-resurrect/no-admission)

`cancel serialized with the completion callback wins - no resurrect or
admission` (`TranslationRequestGenerationFenceTest.kt:217-243`): the test
thread holds the mutation lock, starts the callback thread (which blocks on
that monitor — the exact old check/write gap ordering, parked 150 ms to ensure
the callback reaches the lock first), runs `cancelTranslationRequest` inside
the lock, releases, joins the callback, then asserts `admitted == false` (the
translator stub's admission flag), no live pending request, and no durable
record (tombstone only). Against the pre-fix code this interleave deterministically
resurrected and admitted; with the atomic fence the callback is dropped. The
assertions are deterministic; a pathologically slow callback start can only
weaken the interleave, never produce a false failure. Correct regression test.

### 4. Full 23-suite re-run — VERIFIED (with one count correction)

Same reconstructed 23-suite command, `--rerun-tasks`, fresh run:
`BUILD SUCCESSFUL` (exit 0, 205 tasks executed).

**Real result from the JUnit XML: 23 suites, 130 tests, 0 failures,
0 errors, 0 skipped.**

Per-suite delta vs the pre-fix run: fence 4 → 8, reconciliation 5 → 7; every
other suite unchanged (124 + 6 = 130). The implementer's claimed "131/0/0" is
off by one — the correct total is **130**. This is a tally slip in prose only:
the XML proves every declared test executes and passes, which is the gate
condition. The slice report's totals must be corrected to 130 before commit.

### Residual (non-blocking) notes carried forward

- The callback-fence window identified in item 1 of the original review is now
  closed; the earlier sub-millisecond concern no longer applies.
- LOW items unchanged: generation tombstones have no pruning path (justified:
  user-action-bound growth, ~30 bytes/chapter); the pure
  `translationQueuePosition` function still has no direct test; the
  `queueChapter` brace-line style nit persists; the slice report must say 130,
  not 131.

VERDICT: ACCEPT
