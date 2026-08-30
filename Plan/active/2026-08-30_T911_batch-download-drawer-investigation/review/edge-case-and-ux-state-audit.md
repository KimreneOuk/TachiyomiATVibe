# T911 Reviewer audit — batch download handoff and live UX failure modes

## Scope and baseline

Independent review of current committed `HEAD`
`dd9652bc44411646a0b86d21b98a23127e3e13b8` on
`optimize_translation_finishing_page`. The only working-tree entry at the start
of this audit was the untracked T911 task directory; no production or test code
was modified.

Evidence labels:

- **VERIFIED** — directly established by current source/tests.
- **STRONG INFERENCE** — the mechanism is established and the user-visible
  result follows under the stated runtime ordering.
- **ASSUMPTION** — depends on a setting/device condition not captured here.
- **UNKNOWN** — requires device/runtime evidence.
- **CONTRADICTION** — the stated interpretation cannot be true in steady state
  given the cited current projection.

## Executive verdict

The Director's report is supported by multiple independent defects and one
important terminology ambiguity:

1. **The drawer is never requested to open after confirmation or download
   handoff.** Confirmation dismisses the configuration dialog, acknowledges the
   request, and starts asynchronous probing. The only code that selects
   `Dialog.TranslationProgress` is a later `DETAILS` action from the row. This
   fully explains the required second tap. **VERIFIED, HIGH.**
2. **While the chapter is downloading, the catalog's percentage and the
   drawer's `0/0` are intentionally fed by different owners.** The catalog's
   download ring consumes `Download.progress`; the batch drawer receives an
   empty `TranslationProgressSnapshot` containing only a request phase. The
   downloader's page count/progress is never joined into the drawer. This is
   the most direct explanation if “catalog progress” means the download ring.
   **VERIFIED, HIGH.**
3. **After handoff, the manga-screen projection can erase a valid translation
   snapshot.** Every download-cache/download-queue/translation-queue/request
   emission reconstructs every `ChapterList.Item` with the default
   `translationProgress = null`. An already-running collector is not restarted,
   so the drawer remains empty until a later tracker/store emission. At terminal
   queue removal, the final snapshot is predictably vulnerable to being erased
   after the collector has been stopped. **VERIFIED mechanism, STRONG INFERENCE
   for the observed persistent `0/0`; HIGH.**
4. **The tracker can remain at `0/0` even when it already knows the ordered page
   keys.** It drops every known key missing from `store.state`; pre-registration
   can reject without returning a failure to its caller. Empty/unreadable
   chapters also create a zero-page tracker and return before emitting a
   terminal tracker event. **VERIFIED, HIGH conditional.**

If “catalog progress” instead means a determinate *translation* percentage,
then a simultaneous steady-state drawer `0/0` is a **CONTRADICTION**: both are
passed the same `ChapterList.Item.translationProgress` object
(`presentation/manga/MangaScreen.kt:817-820` and
`tachiyomi/ui/manga/MangaScreen.kt:290-295`). In that interpretation, the most
likely explanation is a transition/rebuild race (finding R3) or that the user
was comparing the download indicator with the translation drawer. A short
screen recording plus a chapter-ID-correlated state trace is needed to decide.

No CRITICAL data-loss defect was established. There are several HIGH UX and
state-machine defects that can leave a durable request silent, misleading, or
detached from work.

## Exact fresh-undownloaded-chapter trace

| Transition | Owner / durable source | Current producer -> consumer | Visible result |
| --- | --- | --- | --- |
| Translate tap | `MangaScreenModel.pendingTranslationGroup` (memory only) | Row/bottom action -> `runChapterTranslationActions` | Usually opens config popup; no work yet (`MangaScreenModel.kt:851-877`). |
| Confirm | `TranslationPendingRequestStore` + `pendingTranslationRequestsState` | `ConfirmTranslationDialog` dismisses, then calls `confirmChapterTranslation` | Popup closes; no progress dialog is selected (`ConfirmTranslationDialog.kt:54-59`; `MangaScreenModel.kt:996-1009`). |
| Live disk probe | Screen-model coroutine | `isChapterDownloaded(... skipCache = true)` partitions selection | Correctly avoids the old cached-download misroute (`MangaScreenModel.kt:1010-1030`). |
| Attach request to download | Pending store phase `WAITING_FOR_DOWNLOAD` | `queueTranslationAfterDownload` -> `TranslationRequestCoordinator` | Request is durable and list row can open details (`MangaScreenModel.kt:1031-1042`; `TranslationRequestCoordinator.kt:56-59`). |
| Download queue/start | `Downloader._queueState` + `DownloadStore`; WorkManager `DownloadJob` | `enqueueTranslationDownloads` -> `DownloadManager` -> `Downloader` | Catalog shows ordinary download progress (`MangaScreenModel.kt:1691-1706`; `Downloader.kt:273-313`). |
| Download progress | `Download.pages[*].progressFlow` | `DownloadManager.progressFlow` -> `MangaScreenModel.updateDownloadState` -> `ChapterDownloadIndicator` | Independent determinate/indeterminate download ring (`DownloadManager.kt:435-448`; `MangaScreenModel.kt:511-520,638-649`; `ChapterDownloadIndicator.kt:111-160`). |
| Drawer during download | Pending request only | `BatchProgressProjector.observeBatchProgress` emits `TranslationProgressSnapshot.empty(...).copy(requestState=...)` | Subtitle says waiting, but hero/pipeline are `0/0`; no numeric download progress (`BatchProgressProjector.kt:131-149`; `TranslationProgressSheet.kt:162-232,724-736`). |
| Successful finalization | Downloader/cache | finalize/move/cache -> `download.status = DOWNLOADED` | Download visually finishes before translation handoff (`Downloader.kt:417-439`). |
| Handoff | Downloader callback into `TranslationManager` | `startTranslationAfterDownloadIfRequested` changes request to PREPARING and calls `translateChapter` | Pending intent becomes queue membership (`TranslationRequestCoordinator.kt:208-217`; `TranslationManager.kt:419-430`). |
| Translation queue | `ChapterTranslator.queueState` + `TranslationQueueStore` | queue `StateFlow`, status flows, foreground service | Queue is durable; pending request is cleared after queue admission (`TranslationManager.kt:423-430`; `ChapterTranslator.kt:477-506,689-700`). |
| Page enumeration/tracker | `ChapterTranslationStore` + `TranslationBatchTrackerRegistry` | translator enumerates, pre-registers, creates tracker, runs pipeline | Only here can drawer obtain `N` pages (`ChapterTranslator.kt:552-628`). |
| Drawer/catalog projection | Volatile `ChapterList.Item.translationProgress` | manager projection -> per-chapter screen collector -> item | Both translation surfaces share this item, but full list rebuilds discard it (`MangaScreenModel.kt:557-600,652-703`). |
| Terminal | Artifact store/summary + queue mutation; bounded terminal tracker cache | tracker finish -> status terminal -> queue removal | Final snapshot can be cleared from the item by the later list rebuild; the translated row exposes a menu, not DETAILS (`ChapterTranslator.kt:415-443`; `ChapterTranslationIndicator.kt:281-327`). |

## Findings

### R1 — start/confirm/handoff never opens the progress drawer

- **Severity:** HIGH
- **Likelihood:** CERTAIN for the reported flow
- **Type:** defect relative to the requested live-batch UX; otherwise an
  undocumented design limitation
- **Evidence:** VERIFIED

The confirmation button first calls `onDismissRequest`, then `onConfirm`
(`ConfirmTranslationDialog.kt:54-59`). `confirmChapterTranslation` publishes an
acknowledgement and launches probing/queue work but never writes
`Dialog.TranslationProgress` (`MangaScreenModel.kt:996-1068`). The only such
write is the `DETAILS` action (`MangaScreenModel.kt:880-884`). The pending and
translating row indicators map a normal tap to `DETAILS`
(`ChapterTranslationIndicator.kt:120-128,211-219`).

This is not a slow callback or lifecycle race. The current state machine has no
open-on-accept or open-on-handoff transition at all.

**Confirm/refute:** a unit test of the screen-model state after confirmation is
sufficient. Refutation would require another current call site that assigns
`Dialog.TranslationProgress`; repository search found none.

### R2 — catalog download progress is not part of batch progress

- **Severity:** HIGH
- **Likelihood:** CERTAIN while an undownloaded chapter is downloading
- **Type:** design limitation / UX defect
- **Evidence:** VERIFIED

`Download.progress` is derived from downloader page flows
(`Download.kt:46-64`) and copied into the chapter list independently
(`MangaScreenModel.kt:511-520,638-649`). The batch projection deliberately emits
an empty page snapshot when a pending request exists but queue membership does
not (`BatchProgressProjector.kt:131-149`). The sheet then renders percentage and
page count from that empty snapshot, producing `0%` and `0/0`
(`TranslationProgressSheet.kt:162-232`). It does render a small waiting subtitle
(`TranslationProgressSheet.kt:724-736`), but does not render download percent,
downloaded pages, total download pages, queue position, or failure/cancel state
from `Download`.

Therefore `catalog download progress > 0` plus `drawer 0/0` is normal current
projection, not evidence that the downloader stopped. It is still misleading
because the user's single “Batch translation” operation is split across two
unjoined UI state machines.

**Confirm/refute:** capture which of the two adjacent row indicators shows the
reported progress. A trace should log request phase, download status/progress,
and snapshot `(donePages,totalPages)` for the same chapter ID.

### R3 — full chapter-list rebuilds erase live and terminal snapshots

- **Severity:** HIGH
- **Likelihood:** HIGH at handoff/terminal transitions; timing-dependent while active
- **Type:** defect
- **Evidence:** VERIFIED mechanism / STRONG INFERENCE impact

The main combined collector reruns on manga data, download-cache changes,
download queue, translation queue, and pending request changes, then replaces
the entire chapter list (`MangaScreenModel.kt:182-200`). The replacement path
constructs new items without carrying forward `translationProgress`
(`MangaScreenModel.kt:652-703`); the field therefore takes its default `null`
(`MangaScreenModel.kt:1663-1673`). The drawer converts null back to an empty
snapshot (`MangaScreen.kt:290-295`).

The per-chapter collector refuses to restart while its job is active
(`MangaScreenModel.kt:557-560`), so a rebuild remains visible until the tracker
or store happens to emit again. This is especially damaging at completion:
the tracker emits terminal state, the translation status becomes terminal and
stops/cancels the collector (`MangaScreenModel.kt:603-615`), then successful
queue removal emits another full-list rebuild. The comment promising to retain
the terminal snapshot (`MangaScreenModel.kt:583-587`) is not upheld by the full
reconstruction path.

This can produce all of: a sheet reverting to `0/0`, a terminal sheet losing
failure/readiness detail, and re-entry showing no completed snapshot even
though the bounded manager terminal cache still has it.

**Confirm/refute:** deterministic flow test: inject snapshot `N`, then emit a
download-cache or queue-list change without a tracker/store change and assert
the item remains `N`. A terminal variant must assert queue removal preserves the
terminal item snapshot.

### R4 — known page totals are dropped when store pre-registration fails; zero-page paths never terminate the tracker

- **Severity:** HIGH
- **Likelihood:** MEDIUM overall; HIGH with revoked/flaky SAF or invalid/empty chapter content
- **Type:** defect
- **Evidence:** VERIFIED

The translator knows `orderedPageKeys` before tracker creation and passes them
to the tracker (`ChapterTranslator.kt:597-616`). However, tracker projection
uses `mapNotNull`: a known key contributes nothing unless it already exists in
`store.state` (`TranslationBatchProgressTracker.kt:167-186`).

`preRegisterPages` can reject a defunct store or artifact-authority failure,
logs the result, and returns `Unit`; its caller cannot observe failure and still
creates the tracker (`ChapterTranslationStore.kt:1209-1226`;
`ChapterTranslator.kt:604-616`). The result can stay `0/0` even though the page
enumeration was known and work/status events continue.

For an actually empty/unreadable directory/archive, a zero-key tracker is
created. `translateBatch` immediately returns null
(`BatchChapterTranslator.kt:183-192`), the outer reconciler marks the chapter
`ERROR` for an empty expected set (`BatchProgressReconciler.kt:40-57`), but no
`tracker.finish` or `tracker.abort` occurs. The live tracker therefore remains a
nonterminal `0/0`. The OOM-abort branch similarly skips tracker finish
(`BatchChapterTranslator.kt:518-526`).

**Confirm/refute:** tests need a store whose pre-registration admission rejects,
an empty directory/archive, and an injected OOM abort. Assert nonzero known
totals when keys exist and a terminal error snapshot for every exit.

### R5 — several download stop/failure paths leave the durable request forever “waiting”

- **Severity:** HIGH
- **Likelihood:** MEDIUM-HIGH across cancellation/offline/storage-provider failures
- **Type:** defect
- **Evidence:** VERIFIED

Covered failures inside the main `try` mark `DOWNLOAD_FAILED`
(`Downloader.kt:387-390,440-446`), and low-space is covered separately
(`Downloader.kt:323-333`). The following are not:

- `getMangaDir`, storage-space probing, and `createDirectory(...)!!` occur before
  the protected `try`; an exception/null reaches `launchDownloadJob`'s outer
  catch, which only stops the downloader (`Downloader.kt:239-255,320-339`).
- Offline/Wi-Fi-policy stop converts currently DOWNLOADING entries to `ERROR`
  but never informs `TranslationManager` (`Downloader.kt:147-166`;
  `DownloadJob.kt:56-65,87-99`). Queued entries can remain QUEUE.
- Removing a chapter or clearing the download queue changes/removes `Download`
  objects but does not cancel or fail the pending translation request
  (`Downloader.kt:686-727`; `DownloadManager.kt:241-243`).
- `queueChapters` silently returns if the source is not an `HttpSource`, and
  silently filters already-on-disk/duplicate items; no handoff reconciliation
  occurs there (`Downloader.kt:273-287`). A missing source after restart is
  dropped by `DownloadStore.restore` while the pending translation record is
  separate (`DownloadStore.kt:93-114`).

In each case the drawer remains an apparently live `WAITING_FOR_DOWNLOAD`
request with no download capable of completing it.

**Confirm/refute:** integration tests must cover queue cancel/remove/clear,
offline before start and during download, a null/throwing temp-directory
provider, and missing source restoration while a pending request exists.

### R6 — multi-select “batch translation” starts only the last selected chapter

- **Severity:** HIGH
- **Likelihood:** CERTAIN for selection size > 1 through the current bottom bar
- **Type:** defect
- **Evidence:** VERIFIED

The bottom action receives the selected list but iterates it, invoking the
single-item callback once per chapter (`presentation/manga/MangaScreen.kt:321-333,
570-582`). Each call overwrites `pendingTranslationGroup` with a one-item list
and overwrites the single dialog slot (`MangaScreenModel.kt:858-877`). Only the
last rendered dialog can be confirmed, and its group contains only that item.
The model has a list overload, but the UI never calls it as a list.

This also means the first selected versus later selected semantics are not a
real multi-chapter batch: earlier selections silently disappear before request
acknowledgement.

**Confirm/refute:** UI/model test with three selected IDs must assert one dialog
represents all three and confirmation acknowledges all three. Current code will
acknowledge only the final ID.

### R7 — cancellation is not atomic with the asynchronous confirm/probe path

- **Severity:** HIGH
- **Likelihood:** LOW-MEDIUM, but user-triggerable on slow SAF/source checks
- **Type:** defect
- **Evidence:** VERIFIED race

The code filters candidates by `hasPendingTranslationRequest`, but the check and
later mutation/admission are separate (`MangaScreenModel.kt:1016-1049`). A
cancel after `pendingAwaitingDownload` is computed but before
`queueTranslationAfterDownload` recreates the canceled request. A cancel after
`pendingDownloaded` is computed but before `translateChapters` still queues and
starts translation; `markTranslationRequestPreparing` becoming a no-op does not
stop the later queue call. The version fence only protects the asynchronous
STARTING persistence write (`TranslationRequestCoordinator.kt:62-81,188-205`),
not these screen-model check/use races.

Duplicate taps are mostly deduplicated at queue mutation
(`ChapterTranslator.kt:689-700`), but overlapping probe coroutines can still
change request phases and defeat a user's intervening cancel.

**Confirm/refute:** barrier-based coroutine tests should cancel at each gap
between candidate filter, WAITING write, PREPARING write, and queue admission.

### R8 — download finalization and translation handoff share one failure boundary

- **Severity:** MEDIUM-HIGH
- **Likelihood:** MEDIUM on SAF/reader-rekey/foreground-service failures
- **Type:** ownership defect
- **Evidence:** VERIFIED

The downloader marks `DOWNLOADED` before optional translation rekey and handoff
(`Downloader.kt:417-439`). Both operations are inside the same broad catch; an
exception in rekey, queue admission, or foreground-service start flips the
already-finalized download object back to `ERROR` and labels the pending intent
`DOWNLOAD_FAILED` (`Downloader.kt:440-446`). Conversely, `ComicInfo.xml`
creation is mandatory inside this same boundary, so metadata failure prevents
handoff even after all images passed validation (`Downloader.kt:387-415`).

The download owner therefore reports translation/store/service failures as
download failures, and a brief catalog DOWNLOADED transition can precede ERROR.
This makes diagnosis and retry semantics inaccurate.

**Confirm/refute:** fault-inject each post-finalization operation independently
and assert the on-disk download remains DOWNLOADED while the translation intent
reports the correct non-download failure.

### R9 — restart persistence is durable but not self-reconciling

- **Severity:** MEDIUM-HIGH
- **Likelihood:** MEDIUM (depends on process death timing)
- **Type:** design limitation plus stale-state defects
- **Evidence:** VERIFIED

Pending phases are synchronously persisted (except the fenced asynchronous
STARTING write) in `translation_pending_requests`
(`TranslationPendingRequestStore.kt:16-57`; `TranslationRequestCoordinator.kt:154-174`).
Translation queue membership is persisted separately and restored as QUEUE,
PAUSED, or ERROR, but restore intentionally does not start work
(`ChapterTranslator.kt:159-225`). The translation foreground service is
`START_NOT_STICKY` (`TranslationForegroundService.kt:63-92`).

Consequences by kill point:

- During download: intent and download membership can survive, but downloader
  restoration is asynchronous (`Downloader.kt:116-121`) and `DownloadJob`
  can observe an empty queue before restore. There is no ready barrier.
- After final files exist but before handoff: the durable request is not
  proactively reconciled against disk on startup. It remains STARTING,
  WAITING, or PREPARING until a user taps Resume/Translate or a downloader
  completion callback happens.
- During translation: artifacts and queue survive, but the restored QUEUE needs
  explicit user Start/Resume; work does not restart automatically.
- Deleted chapters/sources can be dropped from download/translation queue
  restore while their independent pending-request ID has no startup garbage
  collector.

This is safer than unexpectedly starting OCR/LLM after reboot, but the UI must
state “paused/restored — tap to resume” and reconcile already-downloaded files;
current PREPARING/WAITING wording can remain indefinitely false.

**Confirm/refute:** process-kill instrumentation at each phase with assertions
over all three stores and first post-restart UI projection.

### R10 — non-download queue-admission failures are mislabeled as download failures

- **Severity:** MEDIUM
- **Likelihood:** CONDITIONAL (local/non-HTTP source, invalid languages, unsupported ML Kit target)
- **Type:** defect
- **Evidence:** VERIFIED

`queueChapter` silently rejects non-HTTP sources and rejects invalid
configuration via toast (`ChapterTranslator.kt:477-503`). If no queue entry
appears, `TranslationManager` stores phase `DOWNLOAD_FAILED` with reason
“Translation could not be queued” (`TranslationManager.kt:419-457`). Every UI
maps the enum itself to “Download failed” (`TranslationProgressSheet.kt:724-736`;
`ChapterTranslationIndicator.kt:113-144`). A chapter already downloaded can
therefore show a false download failure.

**Confirm/refute:** admission tests for each rejection must assert a distinct
queue/config/source failure phase and durable reason.

### R11 — terminal translation visibility still depends on cached download truth

- **Severity:** MEDIUM
- **Likelihood:** CONDITIONAL on stale/revoked download cache
- **Type:** defect
- **Evidence:** VERIFIED mechanism / STRONG INFERENCE impact

Live queue/request state now wins over stale cache, which is an improvement
(`TranslationUiProjection.kt:11-20`; `MangaScreenModel.kt:670-689`). But after
queue/request cleanup, persisted translation status is queried only when cached
`downloadState == DOWNLOADED` (`MangaScreenModel.kt:660-688`). A stale false
cache therefore hides completed/warning artifacts immediately after the queue
owner is gone. Combined with R3, the row loses both terminal progress and the
translated state/details affordance.

**Confirm/refute:** finish a batch, force cache to NOT_DOWNLOADED while files and
artifact summary remain, and assert terminal translation state remains visible.

### R12 — translation-driven start affects the global downloader and performs blocking work on the screen path

- **Severity:** MEDIUM
- **Likelihood:** MEDIUM with a pre-existing queue/slow WorkManager or SAF provider
- **Type:** regression/performance risk; partly expected behavior
- **Evidence:** VERIFIED

The T907 bridge unconditionally calls `startDownloads`, which can resume every
ordinary queued download, not only the translation-requested chapter
(`MangaScreenModel.kt:1681-1706`; `Downloader.kt:193-217`). This is scoped to a
translation action and fixes a real stall, but it can override the user's
global paused/stale queue expectation. Normal downloads without pending
translation remain semantically protected because failure and completion hooks
are no-ops when no pending request exists
(`TranslationRequestCoordinator.kt:91-98,208-214`).

The confirmation coroutine returns from an IO disk probe and then calls
`queueChapters`, whose live filesystem checks are synchronous, followed by
`DownloadJob.isRunning(context)`, which blocks on WorkManager `.get()`
(`MangaScreenModel.kt:1009-1042`; `Downloader.kt:273-287`;
`DownloadManager.kt:71-79`; `DownloadJob.kt:118-123`). For large selections or
slow providers this can make the post-confirm UI itself hesitate.

**Confirm/refute:** verify dispatcher/thread in a screen integration test and
measure main-thread time with slow fake SAF/WorkManager; assert normal paused
downloads are not unintentionally resumed unless that policy is explicitly
accepted.

### R13 — queued/multi-chapter UX does not express ownership or queue position

- **Severity:** MEDIUM
- **Likelihood:** HIGH once more than one queue entry exists
- **Type:** design limitation
- **Evidence:** VERIFIED

The translator admits only the first active source group/chapter
(`ChapterTranslator.kt:368-410`). A later entry's drawer merely says
“Queued — ready to resume remaining pages” and enables Resume
(`TranslationProgressSheet.kt:271-281,747-752`), even though it is simply waiting
behind another owner. Resume on an ordinary QUEUE returns success and starts no
second worker (`ChapterTranslator.kt:342-360`; `MangaScreen.kt:296-304`). The
drawer is per chapter, while pause is global (`MangaScreenModel.kt:841-848`).
There is no queue position, active owner, or distinction between “waiting its
turn,” “restored and needs start,” and “preparing.”

**Confirm/refute:** multi-entry UI tests across same/different sources and a
product decision on whether controls are chapter-local or queue-global.

## Edge-case matrix

| Case | Current behavior | Audit result |
| --- | --- | --- |
| Already downloaded | STARTING -> live disk probe -> PREPARING -> translation queue. No drawer auto-open. | R1 applies; otherwise handoff bypasses downloader correctly. |
| Partially downloaded temp directory | Request attaches to downloader; non-`.tmp` files may be reused, `.tmp` files are deleted (`Downloader.kt:340-357,475-490`). | Resume is supported, but SAF `listFiles()` null/empty can make final validation fail despite page objects reporting ready (`Downloader.kt:592-615`). |
| Already QUEUED/DOWNLOADING | Duplicate download is not added; request attaches; T907 forces global downloader start. | Usually works in-process. Drawer has request-only `0/0`; cancellation/removal leaves orphan request (R2/R5). |
| Download globally paused | Batch start calls `startDownloads`, resuming queue. | Behavior is not surfaced; possible normal-download regression boundary (R12). |
| Download ERROR | Re-tap rewrites pending phase to WAITING, re-fronts ERROR entry, starts downloader (`MangaScreenModel.kt:1697-1706`). | T907 recovery is present. Real downloader integration is not tested. |
| Download canceled/removed/queue cleared | Download disappears or resets; pending translation remains. | Indefinite WAITING defect (R5). Canceling translation does not cancel the download by design (`TranslationRequestCoordinator.kt:100-105`). |
| Chapter deleted after handoff | Translation cannot find files and sets queue ERROR before tracker creation (`ChapterTranslator.kt:552-566`). | No rich terminal reason; drawer may retain/return `0/0`. Manual delete is not protected; reader auto-delete is protected. |
| Stale/restored download queue | Restore is async and no readiness barrier exists (`Downloader.kt:116-121`). | Race/stall possible; user batch action generally forces start afterward. |
| Stale pending request | Durable and projected on next process; no disk/queue reconciliation loop. | Can remain STARTING/WAITING/PREPARING forever (R9). |
| Duplicate taps | Queue mutation deduplicates IDs; request phases are repeatedly overwritten. | Mostly idempotent, but async cancellation race remains (R7). |
| Overlapping batch requests | Single mutable dialog/group; bottom-bar multi-select calls single-item path repeatedly. | Last-selected-only defect (R6); independent taps can race. |
| Process death during request acknowledgement | Versioned STARTING commit prevents canceled/newer phase resurrection (`TranslationRequestCoordinator.kt:62-81,188-205`). | Good local invariant; covered by focused unit tests. |
| Process death during download | Pending and download records are separate and durable; worker restoration/start is not atomic. | Work may require user resume; UI does not say why (R9). |
| Process death during PREPARING | Pending survives; queue may or may not already have been committed. | No startup reconciliation of downloaded file -> request -> queue. |
| Process death during OCR/chunk/render | Queue/artifacts survive; service is non-sticky and restore does not auto-start. | Safe artifact reuse, explicit restart required. |
| Drawer before/during download | Only reachable by tapping pending icon. Subtitle shows phase; numeric cards show `0/0`. | R1/R2 exact. |
| Drawer at completion | No code opens it automatically. If already open, list rebuild can null the terminal snapshot. | R1/R3. |
| Drawer after success | Translated indicator opens Translate/Delete menu, not DETAILS (`ChapterTranslationIndicator.kt:281-327`). | Completed progress/failure detail is effectively undiscoverable. |
| Drawer after ERROR | Error indicator starts retry/config directly, not DETAILS (`ChapterTranslationIndicator.kt:330-353`). | Root-cause detail may be inaccessible. |
| Leave/re-enter manga screen | New screen model rebuilds active queue/request and starts a progress collector. Finished chapters do not start one. | Active work should reconnect; terminal history is lost from item (R3/R11). |
| Enter/exit reader | Reader observes manager batch progress before opening store (`ReaderViewModel.kt:2587-2627`). Teardown retains queued/active/paused batch stores (`ReaderTeardownCoordinator.kt:92-130`). | Normal exit should not cancel batch. Reader auto-delete passes protected IDs (`ReaderViewModel.kt:1901-1904`). |
| Configuration change / background-foreground | Manga collectors are lifecycle-gated; queue/pending/store/tracker are StateFlows and should replay. | Active work normally reconnects. Dialog is screen-model state, not durable process state; process recreation loses open drawer. Runtime configuration test absent. |
| Foreground service lifetime | Starts only after translation queue admission; pending download relies on download worker. Translation service is non-sticky. | Correct ownership split but weak continuity/wording after process death (R9). |
| Single chapter | Exact Director path reproduced by source trace. | R1/R2/R3/R4 relevant. |
| Multiple chapters | Current bottom-bar wiring loses all but last selection. If directly queued, translator serializes and drawers lack queue position. | R6/R13. |
| Empty/zero-page/invalid chapter | Download page-list empty -> DOWNLOAD_FAILED. Post-download enumeration empty -> queue ERROR + nonterminal 0/0 tracker. | Failure reason and terminal progress are incomplete (R4). |
| Missing source/network/storage | Some paths mark DOWNLOAD_FAILED; missing source, offline stop, and pre-try storage failures do not. | R5. |
| Chapter becomes downloaded externally | Pending request is not polled/reconciled. A later user Resume does a live probe and can advance it. | Silent until user action (R9). |
| Unknown total | Represented as literal zero pages and zero-stage cards, not an indeterminate total. | R2 design defect. |
| Completed artifacts / retry | Shared store and planner reuse artifacts; restored translation queue needs explicit start. | Execution reuse exists; terminal UX/history does not. |
| Cancellation during active batch | Queue cancellation can abort tracker if it has been created; before tracker creation there is no rich terminal projection. | Timing-dependent stale/empty sheet. |
| Normal non-translation download | Translation callbacks check pending request and otherwise return. | No direct translation side effect; global forced start/pause semantics still require regression coverage (R12). |
| Android 8.0+ / bounded memory | Download worker and translation foreground service cover long-running work; batch comments and implementation bound native page work, while downloader can fetch two pages per active source and batch/download can overlap (`Downloader.kt:193-217,361-379`; `BatchChapterTranslator.kt:170-177`). | No unbounded progress-list retention found (terminal cache is 20: `TranslationBatchTrackerRegistry.kt:16-33,139-141`). Mixed download+translation peak and foreground restart behavior need device tests. |

## Test audit and missing coverage

Existing focused tests verify useful local invariants but not the reported flow:

- `EnqueueTranslationDownloadsTest` only mocks `DownloadManager` and verifies
  `downloadChapters/startDownloads/startDownloadNow` calls
  (`EnqueueTranslationDownloadsTest.kt:34-71`). It does not instantiate
  `Downloader`, `DownloadStore`, `DownloadJob`, handoff, or UI.
- Pending acknowledgement tests verify publish-before-commit and the STARTING
  version fence using an Unsafe/reflection fixture
  (`TranslationManagerPendingAcknowledgementTest.kt:42-126,147-195`).
- Download-failure recovery tests verify phase reset and a mocked successful
  queue admission (`TranslationManagerDownloadFailureRecoveryTest.kt:65-159`).
- Tracker registry tests verify terminal caching in isolation, often with an
  empty ordered page list (`TranslationBatchTrackerRegistryTest.kt:13-38`).
- No matching test was found under `app/src/test` or `app/src/androidTest` for
  `MangaScreenModel.confirmChapterTranslation`, automatic drawer selection,
  chapter-item snapshot retention, the real download-to-translation callback,
  multi-select batch wiring, or lifecycle/process restoration of this full
  state machine. **VERIFIED repository search; broader indirect coverage
  UNKNOWN.**

Minimum regression suite required before a repair can be trusted:

1. Single fresh chapter: confirm immediately selects/keeps a visible progress
   surface, shows live download percent/indeterminate count, hands off exactly
   once, then shows page totals and terminal result.
2. Existing QUEUE/DOWNLOADING/ERROR/paused download: attach intent without
   duplicate records; cancel/remove/clear/offline produce an explicit terminal
   or paused request state.
3. Snapshot retention: cache/queue/request list emissions cannot null a newer
   live or terminal snapshot.
4. Tracker exits: pre-registration rejection, zero pages, OOM, missing files,
   and unexpected exception all emit a terminal snapshot with reason.
5. Cancellation barriers at every asynchronous confirm/probe/admission gap;
   cancellation must never recreate a request or start translation.
6. Three selected chapters: one confirmation/contract contains all IDs and all
   three transition independently through mixed downloaded/undownloaded states.
7. Process kill/restart at STARTING, WAITING, final-download-before-handoff,
   PREPARING, QUEUE, TRANSLATING, PAUSED, terminal-before-cleanup.
8. Normal-download boundary: no pending translation projection is created;
   explicit user pause is not unexpectedly defeated unless product policy says
   batch start owns the whole global queue.
9. Device/API matrix: Android 8, current target API, directory and CBZ storage,
   internal and SAF-backed storage, offline/Wi-Fi transitions, low free space,
   long/tall pages, and a mixed download+translation workload on the 6 GB floor.

## Evidence needed from the Director/device

One diagnostic capture can distinguish the remaining UNKNOWN in the reported
“catalog progress” wording. For a single chapter ID, record only non-sensitive
state transitions:

`dialog -> pending phase -> download queue/status/progress/pages -> translation
queue/status -> active/terminal tracker present -> store page count -> item
snapshot counts -> foreground worker/service state`.

Also capture whether the visible row progress is the download arrow/ring or the
translation glyph/percentage. No URLs, API keys, prompts, response bodies, or
translated content are required.

## Non-current one-commit-ahead comparison

Per the Main Leader's instruction, commit
`7c517d5775be65351dad5ca2d99ba77d1620c40a` was inspected only as non-current
comparative evidence. Its diff addresses several related symptoms (unknown-page
wording, tracker placeholders for known keys, download/SAF finalization, and a
distinct generic translation-failure phase). It does **not** add an automatic
drawer-open transition, join live downloader percentage into batch progress,
preserve `ChapterList.Item.translationProgress` across full list rebuilds, fix
multi-select's per-item callback loop, or make screen-model cancellation atomic.
It therefore does not cover R1, the central part of R2, R3, R6, or R7 and must
not be treated as a complete resolution of T911.

## Reviewer recommendation

Treat this as a cross-component state-ownership repair, not a drawer-only
cosmetic fix. The repair boundary should establish one chapter-keyed observable
operation model spanning pending download, download progress/terminal state,
translation queue/preparation, tracker/store progress, and durable terminal
history. The progress sheet should be selected immediately on accepted user
intent (or an equally explicit product-approved surface), and list/drawer/reader
must project that same operation without rebuilding away newer state. Preserve
stock download semantics outside chapters carrying a translation request.
