# T911 Technical investigation — download handoff and live drawer ownership

## Scope and baseline

This is a read-only investigation of current committed behavior at
`dd9652bc44411646a0b86d21b98a23127e3e13b8`. The only working-tree changes seen
were the untracked T911 investigation workspace. No production or test code was
modified.

The Director's exact reproduction is real, but it combines three different
behaviors that should be separated:

1. **VERIFIED — deterministic navigation defect:** confirming batch translation
   never opens the progress drawer. The drawer is opened only by the later
   `DETAILS` action.
2. **VERIFIED — deterministic semantic defect:** while ownership is still with
   the pending request/downloader, the drawer is given an empty *translation*
   snapshot and renders it as `0%`, `0/0`; it does not observe download progress.
3. **VERIFIED mechanism / STRONG INFERENCE for this reproduction — stale local
   projection race:** manga-list recomposition can replace an already observed
   translation snapshot with `null`, while the keyed collector remains active
   and suppresses an unchanged canonical snapshot. The drawer then falls back
   to an empty snapshot even after a real batch snapshot exists.

The report does not claim that the download failed in the Director's scenario:
the reported download completed. Current download finalization and handoff do
contain additional failure/race windows, but those are robustness defects, not
the necessary explanation for the missing drawer.

## Exact current-HEAD reproduction

### 1. Tap and confirmation

- `START` records `pendingTranslationGroup`; it either shows the configuration
  dialog or calls `confirmChapterTranslation` directly
  (`MangaScreenModel.kt:851-878`).
- The confirmation dialog first dismisses itself and then invokes confirmation
  (`ConfirmTranslationDialog.kt:54-59`).
- `confirmChapterTranslation` acknowledges `STARTING`, updates the row, and
  starts the live filesystem probe in `screenModelScope`
  (`MangaScreenModel.kt:996-1029`).
- **No statement in this path sets `Dialog.TranslationProgress`.** The only such
  assignment is the `DETAILS` branch at `MangaScreenModel.kt:880-884`.

**Conclusion (VERIFIED):** neither the initial acknowledgement, download
completion callback, nor translation queue admission owns navigation. The
drawer cannot open automatically in current HEAD. This is not a timing failure;
it is absent product wiring.

### 2. Pending request and download

- The live probe partitions the request using `isChapterDownloaded(...,
  skipCache = true)`. Undownloaded chapters become durable
  `WAITING_FOR_DOWNLOAD`, are queued in the downloader, and are explicitly sent
  through the T907 start bridge (`MangaScreenModel.kt:1020-1043,1681-1707`).
- `DownloadManager.downloadChapters` delegates to `Downloader.queueChapters`
  (`DownloadManager.kt:133-137`). The downloader rejects non-HTTP sources,
  already downloaded chapters and duplicates, then schedules its foreground
  job only when the queue was previously empty (`Downloader.kt:273-313`). The
  translation helper additionally calls idempotent `startDownloads`, and
  re-fronts retained `ERROR` entries (`MangaScreenModel.kt:1691-1706`).
- Current `startDownloads` invokes the in-process pump only if WorkManager is
  already reported running; otherwise it schedules `DownloadJob`
  (`DownloadManager.kt:71-79`). Therefore the T907 bridge is verified to request
  a start, but its comment that it “guarantees the downloader actually runs” is
  stronger than the implementation. Delayed WorkManager execution remains a
  **STRONG INFERENCE** risk, not a cause of this completed-download report.

### 3. Completion and translation handoff

On a successful download, the downloader:

1. validates all pages (`Downloader.kt:387-390,592-615`);
2. writes metadata and archives/renames the temporary chapter
   (`Downloader.kt:393-423,621-633`);
3. marks the `Download` object `DOWNLOADED` (`Downloader.kt:427`);
4. optionally rekeys existing translation artifacts
   (`Downloader.kt:429-437`); and only then
5. calls `startTranslationAfterDownloadIfRequested`
   (`Downloader.kt:439`).

The callback verifies that a pending request still exists, persists
`PREPARING`, and calls `translateChapter`
(`TranslationRequestCoordinator.kt:208-217`). `translateChapter` persists queue
membership, clears pending ownership if admission succeeded, and starts the
translation worker/service (`TranslationManager.kt:419-430`; translation queue
persistence at `ChapterTranslator.kt:689-700`).

**Conclusion (VERIFIED):** the UI may report download completion before the
translation handoff has happened. Rekeying is in that gap and can block or
throw. The transition is a sequence of independent durable mutations, not one
atomic transaction.

### 4. Why the second tap opens `0/0`

Pending request indicators deliberately route a click to `DETAILS`
(`ChapterTranslationIndicator.kt:113-127`). The drawer retrieves the current
chapter item; if its copied `translationProgress` is absent it supplies
`TranslationProgressSnapshot.empty(chapterId)`
(`tachiyomi/ui/manga/MangaScreen.kt:290-295`).

While the request has no translation-queue owner, the canonical projector also
intentionally emits an empty snapshot plus `requestState`
(`BatchProgressProjector.kt:131-149`). The drawer header correctly says
“Waiting for chapter download” or “Preparing,” but its hero card unconditionally
renders percentage and page totals (`TranslationProgressSheet.kt:162-232`), so
unknown translation work is presented as `0%` and `0/0`
(`TranslationProgressSheet.kt:177-188`).

**Conclusion (VERIFIED):** opening during download, the completion-to-handoff
gap, queue setup, archive enumeration, or before page registration legitimately
has no translation total. `0/0` is a misleading formatting of “not known yet,”
not evidence that zero pages were downloaded.

## Drawer progress versus catalog progress

There are two meanings of “catalog progress”:

### Adjacent download progress — expected data-source disagreement

The chapter row renders two independent adjacent controls:

- `ChapterTranslationIndicator` receives `translationProgress` and pending
  request state.
- `ChapterDownloadIndicator` receives `downloadState` and `downloadProgress`.

This split is explicit at `MangaChapterListItem.kt:183-199`, with providers
wired from separate item fields at `presentation/manga/MangaScreen.kt:818-820`.
The translation projector has no `DownloadManager` input
(`BatchProgressProjector.kt:125-241`). Thus a download ring can visibly advance
while the drawer truthfully has no translation pages — but the drawer's `0/0`
label communicates that truth badly.

### Translation ring versus drawer — should not disagree in a stable frame

Both the manga translation ring and drawer consume the same
`ChapterList.Item.translationProgress`. A stable, determinate translation ring
and a simultaneously stable `0/0` drawer would therefore be a
**CONTRADICTION** in the intended composition. Runtime video/logging is needed
to determine whether the Director meant the adjacent download ring, or observed
the stale race below.

### True stale-observer race

The manga model combines database chapters, download cache, download queue,
translation queue and pending requests; every emission rebuilds every
`ChapterList.Item` (`MangaScreenModel.kt:182-200`). The constructor does not pass
`translationProgress`, whose default is `null`
(`MangaScreenModel.kt:652-702,1663-1673`). Meanwhile the per-chapter progress
collector:

- refuses to start a second collector when one is already active;
- applies `distinctUntilChanged()` to the canonical snapshots; and
- copies emitted snapshots into the current item
  (`MangaScreenModel.kt:551-599`).

Therefore this interleaving is possible and code-complete:

1. canonical snapshot `S` is emitted and copied into the item;
2. an unrelated queue/cache/pending emission rebuilds the item with progress
   `null`;
3. `observeTranslationProgress` sees its job already active;
4. canonical state remains `S`, so `distinctUntilChanged` emits nothing;
5. the drawer falls back to empty `0/0` until progress actually changes.

Terminal state is worse: queue removal can rebuild the item with `null`, while
the status handler cancels the collector (`MangaScreenModel.kt:603-625`). That
contradicts the comment claiming the terminal snapshot is retained
(`MangaScreenModel.kt:583-588`). This is a **VERIFIED race mechanism**. Whether
it is the exact observed catalog/drawer discrepancy is a **STRONG INFERENCE**
pending a timestamped runtime trace.

There are two shorter legitimate `0/0` windows as translation starts:

- `ChapterTranslator` marks `TRANSLATING` before it enumerates a directory/CBZ,
  pre-registers pages, constructs the tracker, then rebuilds it from the store
  (`ChapterTranslator.kt:552-616`). Slow archive/SAF access extends this window.
- A new tracker is initially empty; the current unit test explicitly expects
  `totalPages == 0` until `rebuildFromStore`
  (`TranslationBatchProgressTrackerTest.kt:42-58`).

Also, current tracker totals are derived only from ordered keys that are found
in `store.state` (`TranslationBatchProgressTracker.kt:167-186`). If placeholder
registration is rejected or incomplete, an ordered non-empty chapter can remain
projected as zero. That is a **VERIFIED mechanism**, but no current runtime
evidence ties it to this reproduction.

## Ownership and persistence state machine

| Phase | Authoritative owner / producer | Persistence | Live UI source | Restart/cancel behavior |
|---|---|---|---|---|
| Before confirm | Manga `Dialog` and `pendingTranslationGroup` | None | dialog only | Process death loses it; no work exists. |
| `STARTING` | `TranslationRequestCoordinator` | StateFlow immediately; async SharedPreferences commit | pending request projector | Crash before async commit can lose intent. Screen-scope cancellation after acknowledgement can strand it. |
| `WAITING_FOR_DOWNLOAD` | pending request + downloader queue | pending phase committed synchronously; download queue membership durable | translation drawer receives empty request snapshot; separate download indicator receives `Download` progress | Download pages/status/progress are memory-only. Restore repopulates queue but does not auto-start. Pending cancel does not cancel download. |
| Download finalization | `Downloader` | files plus queue membership | download row; still pending translation | `DOWNLOADED` is published before rekey/handoff. Failure after that can revert to `ERROR`. |
| `PREPARING` | pending request coordinator | committed synchronously | empty request snapshot | Crash before queue admission leaves durable `PREPARING`; there is no startup reconciler. |
| Translation `QUEUE`/`TRANSLATING`/`PAUSED` | `ChapterTranslator.queueState` and `TranslationQueueStore` | ordered IDs plus durable store/artifacts | batch projector: live tracker > terminal cache > store > empty | Restore is asynchronous and never auto-starts OCR/LLM (`TranslationManager.kt:201-225`; `ChapterTranslator.kt:170-225`). |
| OCR/AI/inpaint/render | live tracker + `ChapterTranslationStore` | page/stage artifacts; tracker itself in memory | shared `observeBatchProgress` | Store can reconstruct progress; live/terminal tracker registry is bounded memory only. |
| Terminal | durable artifact/summary; transient terminal registry | artifact durable; terminal registry only memory, bounded to 20 | copied manga item snapshot | Queue removal/list rebuild can erase item snapshot and make details inaccessible. |

The pending request store is separate from translation queue ownership by
design and persists only chapter ID, phase, and reason
(`TranslationPendingRequestStore.kt:7-33,46-60`). It has no request generation,
group ID, timestamps, manga/source metadata, or transaction marker. Normal
phase changes are synchronous (`TranslationRequestCoordinator.kt:154-174`),
but `STARTING` publication intentionally precedes its asynchronous commit
(`TranslationRequestCoordinator.kt:62-81,188-205`).

### Unreconciled transaction boundaries (VERIFIED)

- **Crash at acknowledgement:** in-memory `STARTING` can be lost before its IO
  commit. The current acknowledgement test proves publication-before-durability
  (`TranslationManagerPendingAcknowledgementTest.kt:43-62`).
- **Crash at `PREPARING`:** pending is durable before translation queue
  admission; no initializer resolves pending entries against files/queues.
- **Crash after queue persist but before pending clear:** both owners survive.
  Translation restore does not clear the pending record. After terminal queue
  removal, the stale request can resurface and indefinitely protect files.
- **Downloaded externally:** no observer reconciles pending requests merely
  because a chapter becomes downloaded. Only downloader success, drawer Resume,
  or a new tap does so.
- **Downloader restored:** `Downloader.init` restores asynchronously by blindly
  adding the stored queue (`Downloader.kt:116-120`), unlike translation restore's
  merge. A foreground enqueue can race restore and duplicate an entry. Restored
  downloads are not auto-started.
- **Cancel/remove/clear/stop:** pending cancellation leaves the normal download
  running by contract (`TranslationRequestCoordinator.kt:100-105`). Conversely,
  removing/cancelling a download or `Downloader.stop` does not transition its
  translation request; `stop` only changes active downloads to `ERROR`
  (`Downloader.kt:147-167`). The drawer can wait forever.
- **Late completion:** without request generation, a late downloader callback is
  checked only against “any pending request for chapter ID.” It can hand off a
  newer request and cannot distinguish cancelled/re-requested generations.

## Edge-case findings

| Case | Current behavior / risk | Classification |
|---|---|---|
| Already downloaded | Skips downloader and moves to preparation/queue, but still does not open the drawer; archive enumeration can show `0/0`. | VERIFIED |
| Partial `_tmp` download | Images can be reused, but page/progress/status are not durable and work does not auto-resume after process death. | VERIFIED |
| Already queued/downloading | Duplicate is filtered and T907 explicitly requests start; pending stays waiting. Restored queue admission can race as above. | VERIFIED |
| Download paused | Active download goes back to `QUEUE`; translation request remains `WAITING_FOR_DOWNLOAD` without a distinct paused phase. | VERIFIED |
| Download error | Explicit download paths write `DOWNLOAD_FAILED` and reason; retry re-fronts retained `ERROR`. A generic `stop` does not notify pending. | VERIFIED |
| Download cancelled/removed | Pending intent is not cleared or failed, so wait can be permanent. | VERIFIED |
| Chapter/file deleted | Batch worker detects missing chapter files and marks translation `ERROR` (`ChapterTranslator.kt:552-566`); stale pending IDs are not DB-reconciled. | VERIFIED |
| Duplicate tap | Pending row opens details. A fresh START re-acknowledges the same chapter without a generation token; late callbacks are not fenced. | VERIFIED |
| Translation retry while another batch runs | `queueChapter` returns for a duplicate; `startTranslation` returns while worker is running. An existing `ERROR` entry can remain ERROR even though pending is cleared (`TranslationManager.kt:419-430`; `ChapterTranslator.kt:274-289,477-505`). | VERIFIED |
| Zero/invalid pages | Remote zero-page download fails validation; an externally “downloaded” empty directory can enter translation, produce an empty tracker, and expose terminal/cleanup gaps. | VERIFIED mechanism; runtime outcome needs test |
| Missing/invalid source/config | Non-HTTP source is silently rejected by both download and translation admission; queue failure is currently mislabeled `DOWNLOAD_FAILED` (`TranslationManager.kt:449-456`). | VERIFIED |
| Network/storage | Storage and caught download failures notify pending. One failed page fails the whole chapter after other pages finish; this can look like an 80–99% failure. | VERIFIED mechanism, not the reported completed-download case |
| Directory/CBZ/SAF | `isDownloadSuccessful` and `archiveChapter` rely on `UniFile.listFiles`; provider enumeration/rename behavior can fail finalization (`Downloader.kt:592-633`). Actual device/provider failure is UNKNOWN without logs. | STRONG INFERENCE / UNKNOWN occurrence |
| Leave/re-enter/background | Manga collectors are lifecycle-gated (`MangaScreenModel.kt:182-200,498-548`) and StateFlows should replay current state on START. | VERIFIED design |
| Configuration change | Whether the retained `ScreenModel` and construction-time lifecycle always reattach correctly was not established by a current test. | UNKNOWN |
| Reader enter/exit | Normal teardown protects retained batches; global cancel clears queue/pending. Reader surfaces now consume shared batch progress. | VERIFIED (`ReaderTeardownCoordinator.kt:67-103,122-163`) |
| Foreground service | Translation FGS begins only after queue admission, is `START_NOT_STICKY`, and shows generic no-progress for zero total. Downloader owns the earlier service. | VERIFIED (`TranslationForegroundService.kt:45-92,109-167`) |
| Multi-select | UI loops selected items through the *single-item* callback (`presentation/manga/MangaScreen.kt:320-334,570-583`). With confirmation enabled, each call overwrites the pending group/dialog, leaving only the last. Without it, concurrent single admissions can evict same-source queued work. The model's list overload is not called by UI. | VERIFIED, high severity |
| Later chapters | The scheduler runs one global source lane (`ChapterTranslator.kt:368-413`), but the drawer has no queue position and shows unknown `0/0` until setup. | VERIFIED |
| Terminal/retry | Translated/error chips do not consistently expose details; terminal memory is bounded and non-durable. A base-list rebuild can erase the copied snapshot. | VERIFIED |

## Root-cause ranking

1. **P0 UX / deterministic:** no owner opens the drawer on confirm; the user must
   tap again.
2. **P0 UX / deterministic:** a translation-only empty snapshot is rendered as
   real `0/0` progress during download/unknown-total phases; download progress
   is not part of the drawer projection.
3. **P1 correctness/observability:** `ChapterList.Item` is a lossy copy of
   canonical progress and is reset by unrelated list rebuilds; unchanged and
   terminal snapshots may never be restored.
4. **P1 lifecycle/correctness:** pending, download queue, translation queue and
   artifact writes are separate transactions with no startup reconciler or
   request-generation fence.
5. **P1 correctness:** multi-selection does not reach the model's group API.
6. **P2 robustness:** post-download SAF/finalization and initial tracker/store
   gaps can prevent or obscure handoff. They need device/fault evidence before a
   broad downloader rewrite.

## Recommended repair boundaries

### Navigation and UI ownership

- The manga screen remains the only navigation owner. Open the keyed batch
  drawer in the same UI transaction as successful confirmation/acknowledgement.
  Do not let a late downloader callback navigate after the user has left.
- Give the drawer one phase-aware per-chapter UI projection combining:
  request phase, current download state/progress, translation queue position,
  and tracker/store progress. Keep those subsystems' raw ownership separate.
- Render unknown total as “Waiting for download,” “Downloading n%,” “Preparing,”
  or “Queued,” never as `0/0`. Preserve actual zero-page failure as a distinct
  error.
- Subscribe the drawer directly to the keyed canonical Flow (or retain keyed
  snapshots in model state); do not source truth through rebuilt list-item
  copies. Preserve terminal reconstruction from durable store/artifacts.
- Make DETAILS available from waiting, queued, translating, paused, translated,
  warning and error states.

### Handoff and recovery ownership

- Introduce a coordinator-owned durable request record with request generation,
  optional group ID, phase, timestamps and last failure. Downloader should emit
  generic completion/failure/cancel events; the coordinator should perform
  idempotent transitions.
- After both queues restore, reconcile:
  - pending + translation queue -> queue wins, clear pending;
  - pending + valid files -> admit translation once;
  - pending + download queue -> waiting/paused state;
  - pending + neither -> explicit failed/cancelled/retry state;
  - missing chapter -> purge stale ownership.
- Fence completion callbacks by request generation so cancellation/re-request
  cannot be crossed. Define cancellation explicitly: “cancel translation
  intent” versus “cancel intent and chapter download.”
- Use the model's list API end-to-end: one confirmation, one group record, all
  chapter IDs admitted without same-source eviction, and queue position for
  later chapters.

### Downloader and pipeline regression boundary

- Preserve normal non-translation download scheduling, pause, deletion and
  notifications. Translation-specific explicit start/recovery should not alter
  normal manga downloads unless a generic defect is separately proven.
- Make page count derive from the batch's ordered work keys even when placeholder
  state is delayed, while treating failed pre-registration as an explicit
  pipeline error rather than silently blessing an empty store.
- Any SAF/CBZ change needs directory and CBZ fault-injection plus real SAF tests:
  complete files, empty/incomplete enumeration, rename collision/failure,
  metadata failure, retry and no data loss. Do not accept “in-memory page count
  reached N” alone as proof that persistent files exist.
- Keep memory bounded: one compact keyed state/count record per active request
  and on-demand durable terminal reconstruction; do not retain unbounded stores
  or per-page snapshots across all catalog chapters. This is compatible with
  Android 8.0+ and the stated 6 GB minimum.

## Testable acceptance criteria

### Single fresh chapter

1. Confirming opens the drawer immediately, without a second tap.
2. Before page count exists, the drawer says Accepted/Waiting/Downloading/
   Preparing and exposes cancel; it never claims `0/0` progress.
3. The same drawer shows download progress from 0 through completion, then
   transitions exactly once to Preparing -> Queued/Translating -> `0/N` and live
   stages without closing or resetting.
4. Download success yields exactly one translation admission and clears pending
   ownership. Download fail, pause, cancel, removal, missing source, zero page,
   storage and network errors produce explicit actionable states.
5. Drawer and translation ring agree on translation progress. If the chapter row
   is showing download progress, the drawer labels the same phase as download.

### Lifecycle and recovery

6. Rotation, background/foreground, leaving/re-entering manga, and reader
   enter/exit retain the correct phase and do not erase an unchanged snapshot.
7. Fault-injected process death at STARTING, WAITING, file-complete-before-
   callback, PREPARING, queue-persist-before-pending-clear, active and terminal
   converges to one owner after restart. Remote OCR/LLM does not auto-resume
   unless policy explicitly allows it; UI clearly offers Resume.
8. External download completion is reconciled. Deleted DB chapters and orphan
   request/queue IDs self-heal. A stale callback cannot activate a cancelled or
   newer request.
9. Terminal details reconstruct after process death and after more than 20 other
   batches; terminal list rebuild never falls back to `0/0`.

### Multi-chapter and regressions

10. Selecting N chapters produces one confirmation and one group request; every
    selected chapter survives, the first and later chapters expose queue
    position, and same-source chapters are not silently evicted.
11. Duplicate taps are idempotent. Retrying ERROR while another batch runs
    produces queued/retry state or a clear conflict, never a silent unchanged
    ERROR.
12. Normal downloads (fresh, restored, paused, failed, partial, directory, CBZ,
    delete and auto-delete) retain current behavior except separately approved
    fixes.

Required automated coverage:

- pure coordinator/reducer and request-generation tests;
- transaction fault injection at every persistence boundary;
- fake downloader -> handoff -> queue -> tracker integration;
- MangaScreenModel test proving base-list rebuild preserves an unchanged and a
  terminal snapshot;
- Compose test for immediate drawer and phase-aware unknown totals;
- multi-selection/group admission test;
- restored download/translation queue interleaving test;
- directory/CBZ/SAF finalization instrumentation and normal-download regression.

Current tests cover the T907 helper call contract, pending mutation/version
fencing, failure retry, request store, UI projection, tracker math and bounded
terminal registry. They do **not** cover the integrated handoff, immediate
drawer, download progress in drawer, list rebuild race, cancellation/removal,
multi-select, process-boundary reconciliation, or real SAF behavior.

## Historical and non-current comparison

### Re-verified historical claims

- T907's translation enqueue helper and focused test remain in current HEAD
  (`EnqueueTranslationDownloadsTest.kt:35-71`). Its start request is present;
  drawer ownership and progress were outside that fix.
- T903's old claim that pending requests were only in memory is now a
  **CONTRADICTION**: normal phases use synchronous SharedPreferences commits
  (`TranslationPendingRequestStore.kt:21-33`), though `STARTING` retains an
  intentional async durability gap.
- T903/T901 claims that queue status lacked an initial replay are now a
  **CONTRADICTION**: current status flows use `onStart` to emit current state
  (`BatchProgressProjector.kt:153-166`; `TranslationManager.kt:1219-1232`).
- Reader and foreground surfaces now use shared batch progress, and reader
  teardown protects retained batch files. Those older observability/deletion
  gaps are no longer current, but manga drawer navigation and download-phase
  visibility remain.

### Read-only comparison with non-current `7c517d5`

The sibling worktree commit `7c517d5775be65351dad5ca2d99ba77d1620c40a`
is not current behavior and was not integrated or copied. Its diff and two
reports were inspected only as comparative evidence and every applicable claim
was checked against current HEAD.

It appears to address:

- immediate in-process downloader start in addition to WorkManager scheduling;
- phase-aware hero formatting for unknown totals;
- tracker rows based on ordered keys rather than only existing placeholders;
- a distinct translation queue `FAILED` request phase;
- SAF/CBZ finalization and best-effort metadata/rekey behavior;
- same-owner optimistic write-gate refresh.

It does **not** address the verified primary navigation defect, download progress
inside the drawer, copied-item snapshot reset, startup transaction reconciliation,
request generations/cancellation races, broken multi-select wiring, terminal
details, or queue position. It also contains broad downloader/SAF behavior
changes without corresponding tests in that commit, so it should not be adopted
wholesale. Its SAF and write-gate changes require independent reproduction and
the regression boundaries above.

## Evidence needed to close remaining unknowns

For one device reproduction, capture timestamped chapter ID plus:

- pending phase changes;
- download queue state, page count/progress, finalization and queue removal;
- rekey start/end;
- translation queue admission/status;
- tracker registration, ordered-key count and each projected total;
- manga base-list rebuild and drawer's selected snapshot.

A screen recording must show whether “catalog progress” is the download control
or translation ring. Add device/provider/API level, save-as-CBZ preference,
storage URI/provider, source, chapter page count and process/lifecycle events.
That evidence will distinguish expected unknown-total semantics, the verified
list-reset race, SAF finalization, and a true pipeline stall without guessing.
