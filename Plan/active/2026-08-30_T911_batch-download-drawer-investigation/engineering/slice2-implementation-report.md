# T911 Slice 2 implementation report — durable coordinator, reconciliation, multi-select

Implementer: Implementer role, T911 Slice 2. Branch `t911/repair` (Slice 1
committed at `e5e8011`; Slice 2 built on top, left UNCOMMITTED in the working
tree per contract). Verified against `IMPLEMENTATION.md` "Slice 2" and the
SYNTHESIS "Handoff, cancellation, and persistence" section.

## Post-review fixes (reviewer verdict: FIX-FIRST, all applied)

1. **Five @Test methods were silently skipped** (expression-bodied
   `= runBlocking { ... }` whose last expression was a `shouldBe` overload
   returning the receiver, so the test method returned non-void and JUnit
   Jupiter never executed it): the three completion-callback fence tests in
   `TranslationRequestGenerationFenceTest` and the queue-wins /
   interrupted-failure reconciler tests in
   `TranslationManagerStartupReconciliationTest`. All five (and, for
   uniformity, every other expression-bodied test in those two suites) now
   declare `= runBlocking<Unit> { ... }`. The reconciler suite proves 7/7 and
   the fence suite 8/8 executed testcases in the JUnit XML.
2. **Atomic completion-callback fence**
   (`manager/TranslationRequestCoordinator.kt`
   `startTranslationAfterDownloadIfRequested`): the fence check and the
   PREPARING write now happen under the SAME `pendingRequestMutationLock`
   (previously the check ran outside any lock, leaving a sub-millisecond
   cancel window). The captured generation is then passed into
   `translateChapter(manga, chapter, expectedRequestGeneration)`, which
   re-validates it under the lock before admission, so a cancel between the
   PREPARING write and admission still wins. The coordinator's injected
   `translateChapter` seam changed to `(Manga, Chapter, Long?) -> Unit`
   (manager is the only construction site). New deterministic barrier test
   `cancel serialized with the completion callback wins - no resurrect or
   admission`: the callback thread blocks on the mutation lock while the test
   cancels inside it (fixture style of
   `TranslationManagerPendingAcknowledgementTest`); after release the
   callback must find no request, admit nothing, and leave the durable store
   clean. Running the tests for the first time also exposed a fixture gap —
   the fence suite's translator mock never simulated admission (no queue
   insertion), so the happy-path callback test now stubs `queueChapter` to
   insert the QUEUE entry.
3. **Fence-test determinism (Slice 3 review adjudication, test-only):**
   `TranslationRequestGenerationFenceTest` now drains the async STARTING-ack
   persistence lane before its durable assertions and asserts the cancel
   tombstone monotonically (`shouldBeGreaterThan generation`) instead of an
   exact value — the exact tombstone legitimately depends on whether the
   lane's defensive re-remove landed (production unchanged; verified 3/3
   isolated fence-suite runs, 8 tests each, 0 failures).

## Contract item 1 — durable request record with generations

`app/src/main/java/eu/kanade/translation/model/TranslationRequestState.kt`

- New `TranslationRequestFailureKind` enum: `NONE, DOWNLOAD_FAILED, STORAGE,
  CANCELLED, QUEUE_CLEARED, DOWNLOADER_STOPPED, SOURCE_UNSUPPORTED,
  QUEUE_ADMISSION_FAILED, CONFIG_INVALID, INTERRUPTED` (typed last-failure;
  `reason` keeps the human detail).
- `TranslationRequestPhase` gains `CANCELLED` (download-side cancel/remove/
  clear/stop) and `ADMISSION_FAILED` (R10: translation queue refused
  admission — never a download failure). `TranslationRequestState` gains
  `generation: Long = 0` and `failureKind`, plus an `isTerminal` helper;
  the existing 3-arg constructor keeps compiling everywhere.
- New pure classifier `translationQueueAdmissionFailureKind(sourceIsHttp,
  configValid)` shared by the manager and its tests.

`app/src/main/java/eu/kanade/translation/TranslationPendingRequestStore.kt`

- New persisted fields per chapter: `generation`, `groupId`,
  `failureKind`, `createdAtEpochMs`, `updatedAtEpochMs` (keys
  `<id>.generation`, `<id>.group`, `<id>.failureKind`, `<id>.createdAt`,
  `<id>.updatedAt` next to the legacy `<id>`/`<id>.reason` keys).
- Backward-compatible migration: `record(chapterId)` parses legacy entries as
  generation 0 / no group / no timestamps / kind NONE; the numeric-key
  `load()` filter still hides all auxiliary keys.
- New rich `add(record)` (pure persistence write; caller owns
  generation/timestamps) and `record(chapterId)`; the legacy 3-arg
  `add(chapterId, phase, reason)` is kept and preserves
  generation/group/failure-kind while refreshing the update timestamp.
- `remove(chapterId)` now bumps and keeps a generation **tombstone**
  (`generation(chapterId)`), so a request re-created after a cancel can never
  reuse the removed generation. One tiny key per chapter; filtered out of
  `load()`.
- Phase transitions keep the synchronous-commit behavior; the STARTING
  publish-before-commit + version fence in the coordinator is unchanged in
  shape (the commit now writes the rich record with the allocated generation
  and batch group id).

Generation allocation lives in
`app/src/main/java/eu/kanade/translation/manager/TranslationRequestCoordinator.kt`:
per-chapter in-memory counters (manager fields
`pendingRequestGenerationCounters`/`downloadAttachGenerations`/
`pendingGroupIdSequence`, wired as coordinator providers) seeded from the
durable tombstone. A generation is allocated on every new request
(acknowledgement, or a live phase written over a terminal record — a retry IS
a new request) and on cancel/clear.

## Contract item 2 — generation fencing of callbacks and admission

`manager/TranslationRequestCoordinator.kt`

- Attach: `queueTranslationAfterDownload` records
  `downloadAttachGenerations[chapterId] = <generation at WAITING write>`
  under the mutation lock.
- Fenced callback: `startTranslationAfterDownloadIfRequested` drops the
  callback with a log line when the request is gone, terminal
  (cancelled/failed), never attached, or re-requested (attached generation !=
  current generation). It never admits and never recreates a request.
- Fenced probe mutations: `queueTranslationAfterDownloadIfCurrent(manga,
  chapter, generation)` and `markTranslationRequestPreparingIfCurrent(...)` —
  check + write under `pendingRequestMutationLock`, so a user cancel landing
  before the write wins atomically (R7 check/use gaps closed for the WAITING
  and PREPARING writes).
- `isTranslationRequestCurrent(chapterId, generation)` (manager stub
  exposed for the screen model) requires a live, non-terminal request with
  the same generation.

`app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
(`confirmChapterTranslation` probe, ~1027-1110)

- Generations are captured right after the synchronous acknowledgement and
  every durable mutation is fenced on them: WAITING writes via
  `queueTranslationAfterDownloadIfCurrent`, PREPARING via
  `markTranslationRequestPreparingIfCurrent`, single admission via
  `translateChapter(manga, chapter, expectedRequestGeneration)` and batch
  admission via `translateChaptersIfCurrent(manga, chapters, generations)`.
- `translateChapter` (TranslationManager ~508-540) runs its admit sequence
  (evict/markPreparing/queueChapter/clear-or-fail) under the request mutation
  lock and aborts when the expected generation moved; `translateChapters`
  was refactored into `translateChaptersInternal` taking optional per-chapter
  expected generations. Cancel cannot interleave with the admission.

## Contract item 3 — download-side lifecycle notifications

All seams are T907-hook style calls into the manager, each a no-op without a
pending request (normal downloads bit-for-bit unaffected).

`app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- Public seams: `onDownloadCancelledForTranslation(chapterId)`,
  `onDownloadQueueClearedForTranslation(chapterId)`,
  `onDownloadStoppedForTranslation(chapterId, reason)`;
  `markTranslationDownloadFailed(chapterId, reason, failureKind)` gained the
  typed kind.
- `manager/TranslationRequestCoordinator.kt`
  `transitionAttachedRequest(...)` maps: download cancelled/removed →
  phase `CANCELLED` + kind `CANCELLED`; queue cleared → `CANCELLED` +
  `QUEUE_CLEARED`; downloader stopped (offline/Wi-Fi/generic) →
  `DOWNLOAD_FAILED` + `DOWNLOADER_STOPPED` with the stop reason text.

`app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`
- `stop(reason)` (~149-175): every download it flips DOWNLOADING→ERROR
  notifies `onDownloadStoppedForTranslation` (covers the offline/Wi-Fi
  policy stop from `DownloadJob.checkNetworkState` via
  `DownloadManager.downloaderStop`). A pause+stop leaves nothing to notify;
  a completion stop has no DOWNLOADING left.
- Private `removeFromQueue(download)` / `removeFromQueueIf(...)` (~721-760):
  removal of an ACTIVE (DOWNLOADING/QUEUE) download notifies cancel — a
  DOWNLOADED chapter leaving the queue after success does not.
- `clearQueue()` (~190-205): notifies `QUEUE_CLEARED` for every
  queued/downloading chapter. `updateQueue` (reorder) still replaces the
  queue silently, so reordering never cancels intent.
- `queueChapters` (~290-303): the silent non-HTTP-source rejection now marks
  pending requests `DOWNLOAD_FAILED` + `SOURCE_UNSUPPORTED` (chapter will
  never download).
- `launchDownloadJob` catch (~258-272): pre-protected-try failures (manga
  dir, storage probe, tmp-dir creation) reaching the outer catch now mark
  the pending request failed instead of only stopping the downloader.
- Insufficient-space path now uses kind `STORAGE`.

R10 mapping (`TranslationManager.markTranslationQueueFailureIfAcknowledged`,
~737-754): when `translator.queueChapter` does not produce a queue entry, the
request gets phase `ADMISSION_FAILED` with kind `SOURCE_UNSUPPORTED`
(non-HTTP source), `CONFIG_INVALID` (invalid languages/ML Kit target via the
new add-only `ChapterTranslator.isQueueConfigValid()`), or
`QUEUE_ADMISSION_FAILED` (anything else) — never `DOWNLOAD_FAILED`.

## Contract item 4 — startup reconciler

`TranslationManager.kt` (~395-540)

- Readiness barrier: the translation queue restore job is captured
  (`translationQueueRestoreJob`); the downloader side of the barrier is a new
  T907-style seam `onDownloadQueueRestored(queuedChapterIds)` called by
  `Downloader.init` after its async `store.restore()` completes
  (Downloader.kt ~116-129). `runStartupReconciliationIfReady()` fires the
  one-shot pass only after BOTH, guarded by an `AtomicBoolean` (consumed only
  when the downloader snapshot is present, so a late downloader restore still
  triggers the pass). No polling; one bounded pass.
- `reconcilePendingRequestsForStartup(downloadQueueChapterIds, resolveTranslation,
  hasDownloadedFiles)` resolves each pending record:
  - chapter/source unresolvable (`Translation.fromChapterId == null`) → purge
    pending (stale ownership);
  - pending + translation-queue member → queue wins, pending cleared;
  - pending + download-queue member → normalized to `WAITING` (keeps its
    generation);
  - pending + valid downloaded files (checked via the injected
    `DownloadProvider.findChapterDir`, no queue owner) → admitted once
    through `admitRestoredPendingTranslation`, generation-fenced;
  - pending + neither → explicit `DOWNLOAD_FAILED` +
    `TranslationRequestFailureKind.INTERRUPTED` ("Interrupted before the
    chapter download or translation could run") — never silently deleted.
- Race safety: the record + generation is re-read at decision time and the
  admission re-checks the generation under the request mutation lock, so a
  concurrently completing download or user cancel converges instead of
  resurrecting state. Double admission is impossible (queueChapter duplicate
  check).
- Deviation (deliberate, policy-consistent): the admitted restored chapter
  lands in the translation queue as `QUEUE` and does NOT auto-start
  OCR/LLM (`startTranslation` is deliberately not called), matching the
  established restored-work policy (SYNTHESIS gate 7: "Remote OCR/LLM does
  not auto-resume unless policy explicitly allows it").

## Contract item 5 — multi-select end-to-end

- `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`: new
  `onTranslationChapters: ((List<ChapterList.Item>, ChapterTranslationAction) -> Unit)?`
  parameter threaded to both layouts (phone + tablet); both bottom bars now
  pass the WHOLE selection through one `handler(items, START)` call instead
  of looping the single-item callback (R6: only the last item survived).
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt` (~136-146):
  wires both `screenModel.runChapterTranslationActions` overloads (single for
  row taps, list for the bottom bar).
- `MangaScreenModel.runChapterTranslationActions(items, START)` stores the
  full group once; `showConfirmTranslationDialog(items)` overload makes the
  dialog represent the WHOLE batch; `Dialog.ConfirmTranslation` gained
  `group: List<ChapterList.Item> = emptyList()`.
- `app/src/main/java/eu/kanade/presentation/manga/components/ConfirmTranslationDialog.kt`:
  takes `chapterNames: List<String>` and lists every selected chapter
  (one confirmation representing all N).
- Confirming acknowledges all N in ONE call, the drawer opens for the primary
  (first) chapter (Slice 1's `openTranslationProgressDrawer` reused), and the
  probe partitions once: downloaded chapters → `translateChaptersIfCurrent`
  (list admission, no same-source silent eviction — the list path never calls
  `evictStaleQueuedChapters`), undownloaded → fenced WAITING writes + one
  `enqueueTranslationDownloads`. The single-chapter path keeps its existing
  preflight/translateChapter behavior.

## Contract item 6 — queue position

- `TranslationProgressSnapshot` gained `queuePosition: Int?` /
  `queueTotal: Int?`.
- `manager/BatchProgressProjector.kt`: pure
  `translationQueuePosition(queue, chapterId)` (1-based position among
  QUEUE/TRANSLATING entries) attached to QUEUE-state snapshots via
  `withQueuePosition`.
- `TranslationProgressSheet.kt`: internal `ordinalSuffix` +
  `queuePositionLabel`; the drawer subtitle for a later queued chapter now
  reads `Queued (2nd of 3) — waiting for earlier batches`; a chapter first in
  line keeps the existing "Queued — ready to resume remaining pages" wording.
  Sheet redesign: none (subtitle + hero/pill labels only).

## UI projection updates forced by the new phases

- `BatchHeroProjection`: `CANCELLED` / `ADMISSION_FAILED` hero phases
  (error styling), wired through the exhaustive `when`.
- `TranslationProgressSheet`: LiveStatusPill labels/colors and
  `batchStatusHeaderSubtitle` branches for the new phases (cancelled
  subtitle names the download-side cause; admission failure never reads as
  "Download failed").
- New i18n strings `manga_batch_phase_cancelled`,
  `manga_batch_phase_admission_failed`
  (`i18n-at/src/commonMain/moko-resources/base/strings.xml:226-229`).
- Reader surfaces (`BottomReaderBar`, `TranslationSettingsSheet`) use
  condition-`when` statements and tolerate the new phases untouched.

## Seam/design decisions taken

1. **Readiness barrier** = a `@Volatile` downloader snapshot + the captured
   translation-restore `Job` + a one-shot `AtomicBoolean`, hosted in
   TranslationManager (the pending-request owner). The downloader contributes
   its restore completion through the existing manager injection (no new DI
   edge, no cycle — injecting DownloadManager into TranslationManager would
   cycle through Downloader). The reconciler is a method with injectable
   `resolveTranslation`/`hasDownloadedFiles` lambdas, so it is unit-testable
   without DB/DI; production defaults use `Translation.fromChapterId` and
   `DownloadProvider.findChapterDir` (a new constructor dependency that
   cannot cycle back into the manager).
2. **Notification seam** = direct manager calls from the downloader paths
   (T907 hook style), each existence-checked inside the coordinator, so no
   registration/dispatch machinery and zero effect for chapters without a
   pending request. Reorder (`updateQueue`) deliberately does not notify.
3. **Generation fencing** = attach-time capture (WAITING write) + fence at
   every durable mutation, all under the existing `pendingRequestMutationLock`
   — including the downloader completion path itself, where check + PREPARING
   write are atomic (post-review fix) and admission re-validates the
   generation. Fenced list admission mirrors `translateChapters` semantics
   without the single-chapter same-source eviction. Terminal requests can
   never satisfy a fence; retry-after-cancel allocates a fresh generation.
4. **Cancel-intent vs cancel-intent-and-download**: unchanged explicit split
   — `cancelTranslationRequest` (intent only; the download continues and its
   later callback is dropped by the fence) versus the download-side cancel
   notifications (which now terminate the attached request). No new UI verb
   was required by the contract.
5. **Translation queue admission** stays "call queueChapter, then observe
   membership" (existing mocks/tests rely on it); classification of the
   rejection is a separate add-only `isQueueConfigValid()` + source check
   rather than changing `queueChapter`'s signature.

## Test evidence

New focused suites (all in `app/src/test`):

| Suite | Tests | Result |
| --- | --- | --- |
| `eu.kanade.translation.TranslationPendingRequestStoreTest` (extended) | 10 (5 new: legacy migration, rich round-trip, shim preservation, tombstone, legacy-key cleanup) | pass |
| `eu.kanade.translation.TranslationRequestGenerationFenceTest` | 8 (cancel-then-late-callback, re-request fence, current-gen admits, fenced WAITING/PREPARING after cancel, 64-iteration cancel-vs-fenced-write race, atomic-fence cancel-serialized barrier test, no-request no-op) | pass |
| `eu.kanade.translation.TranslationManagerDownloadNotificationsTest` | 7 (cancel/cleared/stopped/storage transitions + durability, no-request no-op with zero phase writes, stale-notification stays terminal, generation advances per cancel) | pass |
| `eu.kanade.translation.TranslationManagerQueueAdmissionFailureKindTest` | 4 (pure classifier + ADMISSION_FAILED/CONFIG_INVALID/SOURCE_UNSUPPORTED end-to-end, never DOWNLOAD_FAILED) | pass |
| `eu.kanade.translation.TranslationManagerStartupReconciliationTest` | 7 (queue-wins, WAITING-normalization keeps generation, files→admit-once without auto-start, neither→INTERRUPTED kept not deleted, missing→purge, idempotent second pass, cancel-during-pass race guard) | pass |
| `eu.kanade.presentation.manga.components.TranslationQueuePositionAndPhasesTest` | 8 (ordinals, position label, first-in-line wording, position subtitle, cancelled/admission subtitles, hero mapping) | pass |
| `eu.kanade.tachiyomi.ui.manga.MangaScreenModelMultiSelectBatchTest` | 4 (one confirmation for 3 + acknowledge-all-3, mixed partition list-translate/list-enqueue + primary drawer, all-downloaded list admission in one call, fence refusal prevents enqueue/admission) | pass |

Extended existing fixtures (behavior preserved, seams updated):
`TranslationManagerPendingAcknowledgementTest` (records the rich store write;
publish-before-commit and version-fence assertions unchanged),
`TranslationManagerDownloadFailureRecoveryTest` (rich-add verify; callback
test now attaches a generation first, as the fence requires; new
"callback without an attached generation is dropped"), plus three new
reflection fields (`pendingRequestGenerationCounters`,
`downloadAttachGenerations`, `pendingGroupIdSequence`) in the
PendingAcknowledgement / DownloadFailureRecovery / AutoArbitration fixtures.

Final verification (fresh run on the post-review tree, clean results dir):

- Command: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  ./gradlew :app:testDevDebugUnitTest --console=plain --tests ...` — 23
  suites: all 15 Slice 1 suites + `TranslationPendingRequestStoreTest`,
  `ChapterTranslatorQueueRestoreTest`, `TranslationQueueStoreTest` + the 6
  new Slice 2 suites listed above (exact command in the slice report handoff
  below).
- Result: **130 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESSFUL** (tally corrected during re-verification: true XML count is 130)
  (23 suites green, including both heavy screen-model fixtures co-resident;
  the five previously-skipped test methods now execute, plus the new
  atomic-fence barrier test).

Test-infra note (documented, in my fixture only): voyager caches
`screenModelScope` in the JVM-global `ScreenModelStore` under one shared key
for non-screen models. Two screen-model fixtures in one JVM used to poison
each other (the second fixture received the first fixture's cancelled scope
and timed out at boot). My fixture evicts that cached scope before and after
its model runs (reflective, best-effort), so
`MangaScreenModelMultiSelectBatchTest` and Slice 1's
`MangaScreenModelTranslationDrawerTest` are green in the same JVM and in
either order. Slice 1's fixture file is untouched.

## Deviations with justification

1. **Restored-file admission does not auto-start translation** (contract says
   "admit translation once"): admission = translation-queue membership with
   user Resume, because auto-starting OCR/LLM after a restart contradicts the
   SYNTHESIS policy gate. The queue entry is visible and resumable.
2. **The already-downloaded silent filter in `queueChapters`** (R5 table) is
   not wired to a mid-session auto-admission: auto-admitting translation from
   inside the downloader would couple the two owners the slice explicitly
   separates. Startup reconciliation and user Resume cover the case; left
   for Slice 3 along with the handoff failure split.
3. **`clearStaleDownloadFailedRequest` now also clears `CANCELLED` /
   `ADMISSION_FAILED` records at reader entry** — same stale-terminal
   purpose; otherwise cancelled records accumulate forever with no cleanup
   path.
4. **`translateChapters` without admissions no longer calls
   `startTranslation()`** — starting an empty queue was already a no-op
   (`translator.start()` returns false); this only avoids a pointless
   foreground-service start.
5. **Test-only fixture hardening** (ScreenModelStore eviction) as documented
   above; no production impact.

## Files changed

Modified (production):
- `app/src/main/java/eu/kanade/translation/model/TranslationRequestState.kt`
- `app/src/main/java/eu/kanade/translation/model/BatchHeroProjection.kt`
- `app/src/main/java/eu/kanade/translation/model/TranslationProgressSnapshot.kt`
- `app/src/main/java/eu/kanade/translation/TranslationPendingRequestStore.kt`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/translation/manager/TranslationRequestCoordinator.kt`
- `app/src/main/java/eu/kanade/translation/manager/BatchProgressProjector.kt`
- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/presentation/manga/components/ConfirmTranslationDialog.kt`
- `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt`
- `i18n-at/src/commonMain/moko-resources/base/strings.xml`

Modified (tests): `TranslationManagerPendingAcknowledgementTest`,
`TranslationManagerDownloadFailureRecoveryTest`,
`TranslationManagerAutoArbitrationTest`, `TranslationPendingRequestStoreTest`.

Added (tests):
- `app/src/test/java/eu/kanade/translation/TranslationRequestGenerationFenceTest.kt`
- `app/src/test/java/eu/kanade/translation/TranslationManagerDownloadNotificationsTest.kt`
- `app/src/test/java/eu/kanade/translation/TranslationManagerQueueAdmissionFailureKindTest.kt`
- `app/src/test/java/eu/kanade/translation/TranslationManagerStartupReconciliationTest.kt`
- `app/src/test/java/eu/kanade/presentation/manga/components/TranslationQueuePositionAndPhasesTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelMultiSelectBatchTest.kt`

## Left for Slice 3

- Finalization/rekey/handoff failure split in the downloader (R8): a failure
  after files finalize still flips the download object to ERROR; the typed
  handoff-vs-download boundary is Slice 3 item 3.
- The mid-session already-downloaded silent filter (see deviation 2).
- Tracker totals from ordered work keys, typed terminal snapshots for zero
  pages/OOM/missing files (Slice 3 items 1-2).
- Durable terminal details reconstruction after process death / cache
  eviction (Slice 3 item 4); CANCELLED/ADMISSION_FAILED records still rely on
  reader-entry cleanup rather than durable terminal history.
- Fault-injection tests for the touched downloader boundary (Slice 3 item 5).
- Device-level verification of offline/Wi-Fi stop and SAF provider failures
  (unit seams tested; real WorkManager/network behavior needs device runs).
