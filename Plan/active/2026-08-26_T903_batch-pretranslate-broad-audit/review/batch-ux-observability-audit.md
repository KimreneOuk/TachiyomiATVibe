---
kind: review
title: "T903 Batch UX Observability Audit"
---

# Scope and conclusion

This review covers the committed tree at HEAD 926ae00 (T902 phases A/B/C
landed). It traces the two Director symptoms:

1. S1 — a batch-start interaction appears to do nothing for minutes.
2. S2 — entering the reader while a batch is active, then leaving it, makes
   the batch UX disappear or makes continued execution indeterminate.

No production code was changed for this audit. Evidence labels are:
VERIFIED (directly established by current source), STRONG INFERENCE (the
source path is clear but the exact device timing/user rendering is not tested),
ASSUMPTION (depends on a preference or runtime condition), UNKNOWN (not
established), and CONTRADICTION (the observed symptom conflicts with the
current source path).

The main result is that S1 has two different causes. A chapter that is not yet
downloaded is not a translation batch at all: the request is held in an
in-memory map and the user sees only the normal download queue. A chapter that
is already downloaded is queued immediately, but confirmation closes without a
completion acknowledgement and detailed progress is not available until
chapter setup creates a tracker. S2 is primarily observability: normal reader
exit preserves an active batch, but the reader only renders generic translation
state and does not render the batch snapshot that it already collects. The
manga screen can recover the state, but only when its cached download gate
recognizes the chapter. [VERIFIED / STRONG INFERENCE]

# S1 trace: from confirm to first useful feedback

| Phase | Current behavior and visible surface | Evidence and classification |
| --- | --- | --- |
| Confirm | The confirm button first dismisses the dialog and then invokes the asynchronous start path. There is no immediate “accepted”, “preparing”, or snackbar state. | ConfirmTranslationDialog.kt:54-63, MangaScreen.kt:340-352, MangaScreenModel.kt:946-952. VERIFIED. |
| Live download probe | The start path partitions the selection with a live skipCache=true probe. Chapters not on disk are placed in TranslationManager.translateAfterDownload, then normal chapter downloads are queued. If every selected chapter is awaiting download, the coroutine returns after scheduling downloads. | MangaScreenModel.kt:959-976, TranslationManager.kt:208-217. VERIFIED. |
| While download runs | Downloader state progresses through the ordinary download indicator. It does not create a Translation.State. Only after a successful download, directory move, cache update, and DOWNLOADED state does it consume the pending request and call translateChapter. | Downloader.kt:358-389,415-443. VERIFIED. |
| Batch enqueue | For an actually downloaded chapter, queueChapter changes the Translation to QUEUE, persists the queue, and startTranslation starts the foreground service while QUEUE or TRANSLATING exists. | ChapterTranslator.kt:582-588, TranslationManager.kt:289-335, TranslationManager.kt:205-206. VERIFIED. |
| Queue-state delivery | The generic statusFlow drops the first status-flow emission for each Translation. Since addToQueue sets QUEUE before publishing queueState, the initial QUEUE is dropped; onStart only re-emits current TRANSLATING entries. The manga list has a second queueState-driven recomputation that often masks this gap. | ChapterTranslator.kt:582-586, TranslationManager.kt:1722-1735, MangaScreenModel.kt:182-198. VERIFIED mechanics; STRONG INFERENCE for the resulting screen race. |
| Chapter setup | Before OCR, the batch opens/resolves the store, locates the chapter path, enumerates archive/directory pages, pre-registers pages, creates the tracker, and only then enters the pipeline. The tracker starts as an empty 0/0 snapshot; pre-registration can produce 0/N once reached. | ChapterTranslator.kt:420-496, ChapterTranslationStore.kt:1190-1254, TranslationBatchProgressTracker.kt:49-71. VERIFIED. |
| First stage feedback | Engine setup and all-page source fingerprinting happen before page OCR. The first OCR-running tracker event is emitted only after a page has successfully decoded/analyzed. The foreground service therefore initially publishes “No progress” while the tracker is absent or has zero total. | TranslationPipeline.kt:1169-1201,1254-1265,2012-2143, TranslationForegroundService.kt:37-52,72-106, TranslationManager.kt:273-275. VERIFIED. |

## S1 findings

### S1-A — pending-after-download has no translation acknowledgement

Severity: HIGH. Likelihood for “nothing happened” when selecting an
undownloaded chapter: HIGH. [VERIFIED]

The request is represented only by translateAfterDownload, a process-memory
ConcurrentHashMap keyed by chapter ID. It is not in TranslationManager.queueState,
so it cannot drive the manga translation indicator, the batch progress sheet,
or the foreground translation service. The all-awaiting branch returns without
any translation-specific UI event. The only visible activity is download
progress, which does not tell the user that a later translation was requested.
If the download fails, Downloader marks the download ERROR and reports a
download error, but there is no corresponding translation-pending failure or
retry explanation. [VERIFIED]

This also means a process death during the download loses the pending
translation intent, because the translation queue is persisted only after
queueChapter runs. [VERIFIED: TranslationManager.kt:208-217,
ChapterTranslator.kt:141-164]

Recommended direction: create one durable/observable “translation requested,
waiting for download” state, show it immediately after confirmation, and
transition it visibly on download success or failure.

### S1-B — downloaded starts have no explicit accepted/preparing state

Severity: MEDIUM-HIGH. Likelihood when SAF/archive setup or engine work is
slow: MEDIUM-HIGH. [STRONG INFERENCE]

For a downloaded chapter, the queue is created, but the confirm dialog closes
before the live probe and queue work finish. The manga indicator is only an
icon/spinner; there is no start acknowledgement. The progress sheet is only
reachable through that indicator and can initially receive
TranslationProgressSnapshot.empty, which renders 0/0 and “No active batch in
progress” until the store/tracker collector catches up. [VERIFIED:
MangaScreenModel.kt:952-976, MangaScreen.kt:290-315,
TranslationProgressSnapshot.kt:105-112, TranslationProgressSheet.kt:91-94,702-728]

The long pre-tracker interval is real work, not necessarily a stall: archive
enumeration, artifact-store opening/migration, engine admission, and
source-fingerprint hashing precede the first page event. The current
notification helps only weakly because it starts with “No progress” and
getTranslationProgress is tracker-only. [VERIFIED:
ChapterTranslator.kt:420-496, TranslationPipeline.kt:1169-1265,
TranslationForegroundService.kt:37-52,72-106,
TranslationManager.kt:273-275]

Recommended direction: expose a lifecycle-independent STARTING/PREPARING state
with a cancel/details affordance immediately after confirmation, then replace
it with tracker progress.

### S1-C — queue replay and silent rejection are contributing gaps

Severity: MEDIUM. Likelihood: MEDIUM. [VERIFIED mechanics / STRONG INFERENCE
impact]

statusFlow does not replay QUEUE to a new subscriber, and the reader’s
effective-state merge treats QUEUE as neutral. The chapter translator also
returns silently for a non-HTTP source or duplicate chapter entry. Invalid
language and unsupported ML Kit targets do show a toast, so those are not
silent. [VERIFIED: TranslationManager.kt:1722-1735,
ReaderViewModel.kt:402-414, ChapterTranslator.kt:342-370]

The manga list’s combined queueState path normally reconstructs QUEUE for a
downloaded chapter, so this is not sufficient evidence that every downloaded
start is invisible. It is a fragile secondary source and can fail to help
when the list’s download gate is false. [STRONG INFERENCE]

# S2 trace: reader entry, reader exit, and return to manga

## What entering the reader does

On chapter load, ReaderViewModel directly asks TranslationManager for the
current chapter status, then opens the shared store and starts both the batch
progress collector and the store collector. This means an already active
TRANSLATING batch can initially show a reader spinner, and the manager/store
state is not inherently lost on entry. [VERIFIED:
ReaderViewModel.kt:940-991,2543-2587, TranslationManager.kt:409-429]

However, the reader’s merged state explicitly handles only TRANSLATING,
ERROR, and live TRANSLATED. QUEUE falls through to NOT_TRANSLATED, so a
batch waiting in the queue looks the same as an idle chapter. [VERIFIED:
ReaderViewModel.kt:382-415]

The reader does collect translationBatchProgress and passes it through
ReaderActivity into TranslationSettingsSheet, but QueueSection never reads
that parameter. QueueSection derives its rows from the live per-page store;
untouched/PENDING pages are omitted. The bottom reader bar therefore shows
only a generic translate icon for QUEUE or a generic spinner for TRANSLATING.
The sheet’s only batch-wide stop action is Stop All. [VERIFIED:
ReaderActivity.kt:471-483,562-575,609-633,
TranslationSettingsSheet.kt:80-112,183-235,
ReaderViewModel.kt:177-191,288-336,
BottomReaderBar.kt:92-120, ReaderViewModel.kt:2264-2280]

This is the central S2 observability defect: a rich snapshot exists, including
phase, page totals, active stages, AI pending/buffered/running/failed counts,
and terminal failure details, but the reader surface discards it. The manga
progress sheet does render those details, but it is not the reader surface.
[VERIFIED: TranslationProgressSheet.kt:80-94,154-236,258-361,
TranslationSettingsSheet.kt:184-203]

## What ordinary reader exit does

ReaderActivity calls reader-background teardown on pause and finish, and
ReaderViewModel cancels its progress/store/status collectors when cleared.
TranslationManager deliberately calls cancelAllPageTranslations(false);
batch-active chapter IDs are excluded from store eviction and the batch queue
is not cleared. stopReaderTranslations stops the underlying translator only
when no batch is active. Thus a normal reader exit does not, by itself, cancel
an active QUEUE/TRANSLATING batch. [VERIFIED:
ReaderActivity.kt:266-286,304-315,
ReaderViewModel.kt:868-886,2374-2418,
TranslationManager.kt:205-230,1679-1720]

After exit, the reader-specific detailed UX is gone because its collectors and
sheet are gone. The manga screen’s lifecycle-gated collectors should resume
and its combined queueState path should rebuild the chapter item for a
downloaded chapter. Therefore “reader exit always stops the batch” is a
CONTRADICTION with the normal lifecycle path; the exact Director report of
continued work is UNKNOWN without runtime tracing. [VERIFIED source path /
STRONG INFERENCE about re-render timing]

## Why the manga UX can still disappear on return

MangaScreenModel computes translationState only inside the branch where its
downloadState is DOWNLOADED. It obtains that downloadState through the cached
DownloadCache path, not the live skipCache=true probe used by confirmation.
If the cache says the chapter is not downloaded after reader exit, the item is
forced to NOT_TRANSLATED even when TranslationManager.queueState still holds
QUEUE/TRANSLATING. The user then sees the idle translate icon and cannot open
batch DETAILS from that row. Starting again can be a no-op or conflict because
the global queue already owns the chapter. [VERIFIED:
MangaScreenModel.kt:624-655, DownloadCache.kt:150-175,
MangaScreenModel.kt:953-968; STRONG INFERENCE for the exact post-exit
render]

This is conditional, not an unconditional lifecycle loss. T902 A1 fixed the
older confirmation dead-button path by using skipCache=true; the list-side
cached gate remains a separate observability edge. [VERIFIED:
MangaScreenModel.kt:953-968 versus MangaScreenModel.kt:632-652]

## Conditional real batch failure on reader finish

Severity: HIGH impact, MEDIUM likelihood, conditional. [STRONG INFERENCE /
ASSUMPTION]

When the last page is marked read, ReaderViewModel can enqueue a read chapter
for deletion according to removeAfterReadSlots. On activity finish it invokes
the pending deletion. If that policy selects the chapter whose batch is still
running, the downloader can remove the chapter files; ChapterTranslator then
fails the batch when findChapterDir returns null. This is a real
execution-failure path, but it requires the read/delete preference and chapter
position to line up, so it should not be presented as the explanation for every
reader exit. [VERIFIED chain:
ReaderViewModel.kt:1465-1509,1863-1882,888-894,
DownloadManager.kt:319-331, ChapterTranslator.kt:420-434]

# Surface audit

| Surface | State it exposes | Observability gap |
| --- | --- | --- |
| Manga chapter indicator | NOT_TRANSLATED, QUEUE, TRANSLATING, TRANSLATED/WARNINGS, ERROR; determinate percentage only when a snapshot exists. | No explicit accepted/pending-download/preparing state; list status is gated by cached download truth. ChapterTranslationIndicator.kt:50-88,132-199; MangaScreenModel.kt:632-655. |
| Manga translation progress sheet | Batch phase, page totals, stage grid, AI counts, page matrix, failures, pause/resume/cancel/read-now. | Requires the row to remain visibly in QUEUE/TRANSLATING and can initially show an empty 0/0 snapshot while the collector opens the store. TranslationProgressSheet.kt:80-361; MangaScreen.kt:290-315. |
| Reader bottom bar | Generic icon for idle/queue, spinner for translating, filled icon for translated, red icon for error. | No queue position, phase, page fraction, or batch-vs-auto distinction. BottomReaderBar.kt:92-120. |
| Reader translation settings | Live page queue summary/rows and Stop All. | Batch snapshot is passed in but unused; pending pages are omitted, so an early batch can look idle. TranslationSettingsSheet.kt:80-112,183-235; ReaderViewModel.kt:318-336. |
| Foreground notification | Chapter name and processed/total pages once a tracker exists; global Stop All action. | Starts with “No progress”, tracks only the first translating queue item, and cannot represent a pending-after-download request. TranslationForegroundService.kt:37-52,72-106; TranslationManager.kt:273-275. |
| Download queue | Download progress/error. | Does not state that a translation will follow, so it is not sufficient feedback for a pre-translate action. Downloader.kt:358-389,437-443. |

# Ranked root causes and recommended direction

| Priority | Finding | Confidence / impact | Recommended direction |
| --- | --- | --- | --- |
| 1 | Undownloaded batch requests live only in an in-memory post-download map. | HIGH / HIGH likelihood for S1. VERIFIED. | Persist and expose a pending-after-download translation state; acknowledge it immediately and surface success/failure. |
| 2 | Reader drops the rich batch snapshot and renders QUEUE as neutral. | HIGH / HIGH likelihood for S2 UX loss. VERIFIED. | Render the same batch snapshot in the reader and make the translate control open batch details for the current chapter. |
| 3 | Confirmation has no STARTING/PREPARING acknowledgement; tracker and notification progress begin late. | MEDIUM-HIGH / MEDIUM-HIGH likelihood. STRONG INFERENCE. | Add an immediate state covering live probe, download wait, store/archive setup, engine setup, and fingerprint planning. |
| 4 | Manga list translation status is nested under cached DOWNLOADED state. | MEDIUM / conditional S2 likelihood. VERIFIED mechanism, STRONG INFERENCE impact. | Let an active translation request win over stale/missing cache state; show “waiting for download” separately. |
| 5 | QUEUE is not replayed by statusFlow; reader merge treats it as idle. | MEDIUM / MEDIUM likelihood as a contributing race. VERIFIED mechanics. | Make the canonical chapter status flow replay queue entries or derive reader state directly from queueState. |
| 6 | Reader auto-delete can remove files needed by an active batch. | HIGH impact / MEDIUM conditional likelihood. STRONG INFERENCE / ASSUMPTION. | Protect active batch chapters from auto-delete or require an explicit conflict decision. |
| 7 | Foreground progress is tracker-only. | LOW-MEDIUM / HIGH confidence as a short-gap contributor. VERIFIED. | Fall back to store-derived phase/page counts and distinguish queued/preparing from no progress. |

# Verification and test gap

The searched unit tests cover tracker/reducer arithmetic, but no matching
UI/lifecycle or pending-download integration test was found under the
app/src/test and app/src/androidTest trees for: confirm → download wait →
translation, reader entry/exit while QUEUE, reader return with stale cache, or
auto-delete during a batch. This is a coverage observation, not proof that no
indirect test exists. [VERIFIED search result / UNKNOWN broader coverage]

The minimum regression scenarios should assert:

1. Confirming an undownloaded chapter immediately exposes a durable pending
   translation state, then transitions to batch QUEUE after download.
2. Confirming a downloaded chapter exposes an immediate preparing/queued
   acknowledgement before tracker creation.
3. Opening the reader while QUEUE preserves a visible queued batch state and
   exposes details/progress rather than a neutral icon.
4. Leaving and re-entering the reader preserves the batch state and progress
   surface without relying on the download cache.
5. Reader auto-delete cannot remove a chapter that an active batch still needs.
