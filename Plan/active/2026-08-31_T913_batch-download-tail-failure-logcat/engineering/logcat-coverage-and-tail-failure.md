# T913 technical investigation — Logcat coverage for download-tail failure

## Executive finding

**VERIFIED — existing Logcat is not sufficient for the requested device diagnosis.**
The failing final page can be reduced to `Page.State.ERROR` with its exception
discarded, and the subsequent all-pages check returns only a Boolean. In the
most likely 98–99% failure path there may therefore be **no Logcat line at all
for the page failure**, followed only by a generic durable
`DOWNLOAD_FAILED / "Chapter download failed"` state. Existing logs cannot name
the page, attempt, pipeline stage, exception class, ready/on-disk counts, or
whether the failure was source URL resolution, network response, temporary-file
creation/write/rename, or final validation.

Recommendation: do not ask the Director to reproduce against the current APK.
First add one privacy-safe `BatchDownloadTrace` event stream at the narrow
translation-driven download boundary described below, build/install that APK,
then start the filtered capture and give the Director the single cue to tap
**Batch Translate**.

Baseline inspected: current HEAD `40edf1a65de3a0bb1529fe95ed44e30378f68f32`.
No product code was edited by this investigation.

## Current path and existing evidence

### Request, queue, and admission

- **VERIFIED:** Batch confirmation acknowledges the request, probes the live
  filesystem, moves undownloaded chapters to `WAITING_FOR_DOWNLOAD`, enqueues
  them, and explicitly starts the downloader at
  `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt:1041-1109`
  and `:1778-1793`.
- The only existing start-side diagnostic is for the already-downloaded branch:
  `"TachiyomiAT translate START"` at `MangaScreenModel.kt:1135-1138`. It logs
  chapter and manga **names**, has no stable tag/event, and is not emitted for
  the download-first path under investigation.
- `Downloader.queueChapters` silently filters already-downloaded and duplicate
  chapters at `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt:323-332`.
  Unsupported sources produce a typed request failure at `:309-320`, but none
  of those queue outcomes is logged.
- Request phase changes and typed terminal kinds are durably written in
  `app/src/main/java/eu/kanade/translation/manager/TranslationRequestCoordinator.kt:324-373`.
  That central transition has no log. Consequently Logcat cannot show the
  normal `STARTING -> WAITING_FOR_DOWNLOAD -> PREPARING` sequence or terminal
  `STORAGE`, `SOURCE_UNSUPPORTED`, `DOWNLOADER_STOPPED`, `QUEUE_CLEARED`,
  `CANCELLED`, or queue-admission reason.
- The only coordinator trace is a stale completion callback at
  `TranslationRequestCoordinator.kt:467-498`; the message is tagged implicitly,
  not with a stable diagnostic tag. Startup reconciliation has several
  `T911 reconcile` lines at `TranslationManager.kt:489-523`, but those do not
  cover a live tail-page failure.

### Page pipeline — decisive coverage gap

- **VERIFIED:** the chapter page list is obtained and re-indexed at
  `Downloader.kt:399-410`; there is no page-count/start trace.
- Image URL resolution errors are caught at `Downloader.kt:425-431`, converted
  to `Page.State.ERROR`, and the throwable is discarded without logging.
- Each page then runs through local-file/cache/network selection and completion
  at `Downloader.kt:548-581`. Any throwable is caught at `:582-588`, converted
  to `Page.State.ERROR`, and only shown through the notification. There is no
  Logcat call and no page index, stage, or exception record.
- Network download retries happen up to three retries at
  `Downloader.kt:599-625`. Attempts and final error are not logged. The one
  `try/catch` combines output-stream write, image-type detection, and rename
  (`:603-613`), so present evidence cannot distinguish HTTP/source failure from
  storage create/write/rename failure.
- After all page flows settle, validation at `Downloader.kt:683-706` checks
  `READY == expected` and on-disk count `== expected`, but returns only a
  Boolean. Its caller at `:445-448` writes generic
  `"Chapter download failed"` without a Logcat line or the three counts.
- Therefore the reported “last 1–2 pages” symptom is a direct fit for the
  implementation: concurrency continues the other pages after a page-local
  error, then the final Boolean validation fails once all flows finish. This is
  a **STRONG INFERENCE** for the mechanism; the actual on-device cause remains
  **UNKNOWN** until instrumented capture.

### Finalization, cancellation, and handoff

- Exceptions during page-list/finalization are logged only as raw throwables at
  `Downloader.kt:486-497`; the line has no chapter ID or stage. Insufficient
  storage is typed at `:371-385` but not logged. A `false` validation result
  never enters this exception logger.
- Pause, stop, clear, and removal transitions are implemented at
  `Downloader.kt:155-210` and `:777-813`. They update typed pending-request
  state, but do not produce a correlated trace. Cancellation exceptions are
  intentionally rethrown at `:274-288`, `:486-497`, `:530-538`, and `:582-588`,
  so cancellation can only be inferred indirectly.
- Finalization settles `DOWNLOADED` before rekey/admission at
  `Downloader.kt:475-500`. Handoff exceptions are logged at `:518-538` and are
  correctly typed as admission failure, but the log cannot distinguish
  `rekey` from `admission` and has no chapter ID/generation.
- Existing focused tests verify behavioral boundaries, not observability:
  `app/src/test/java/eu/kanade/tachiyomi/data/download/DownloaderHandoffFailureSplitTest.kt:95-236`
  covers rekey/handoff/final-validation semantics, and
  `app/src/test/java/eu/kanade/translation/TranslationManagerDownloadNotificationsTest.kt:54-142`
  covers typed cancel/clear/stop/storage transitions. Neither asserts a
  diagnosable trace.

## What current Logcat can and cannot distinguish

| Cause | Current evidence | Verdict |
| --- | --- | --- |
| Source cannot supply image URL | Throwable swallowed at `Downloader.kt:425-431` | **Cannot diagnose** |
| HTTP/network image failure and retry exhaustion | Throwable swallowed at `:582-588`; retries silent at `:616-624` | **Cannot diagnose** |
| Invalid/undecodable page/image type | Collapsed into the same page catch; split failure alone logs generically at `:660-674` | **Cannot correlate** |
| Temp file create/write/rename or SAF/provider I/O | Collapsed with network work at `:599-613`; raw outer error only if it escapes | **Cannot diagnose** |
| Ready-count versus on-disk-count mismatch | Boolean only at `:683-706` | **Cannot diagnose** |
| Insufficient free storage | Typed request state, notification only (`:371-385`) | **Not visible in Logcat** |
| Pause/cancel/remove/clear/offline stop | Correct state callbacks, no correlated logs (`:155-210`, `:777-813`) | **Not reliably distinguishable** |
| Queue filter/stall | Queue filters and start have no trace (`:306-358`; `MangaScreenModel.kt:1778-1793`) | **Cannot diagnose** |
| Stale generation callback | Existing informational line (`TranslationRequestCoordinator.kt:487-493`) | **Partially diagnosable** |
| Post-finalization rekey/admission exception | Raw throwable plus typed state (`Downloader.kt:518-538`) | **Failure visible, stage/correlation missing** |

## Smallest sufficient instrumentation

Add a single internal diagnostic helper with explicit methods and the fixed
Android tag **`BatchDownloadTrace`**. Emit only for chapters with a live
translation request (except the initial request event). This prevents normal
manga download noise and preserves normal downloader behavior.

All messages should be one-line `key=value` records beginning with a versioned
schema marker:

`schema=1 event=<event> chapter_id=<Long> generation=<Long-or-none> ...`

Use IDs and enums only. Do **not** log manga/chapter titles, source URLs, image
URLs, request/response bodies, headers, cookies, API keys, prompts, page text,
filesystem/SAF URIs, or raw throwable messages. `error_class` should use only
`throwable::class.java.simpleName`; `cause` and `stage` must be controlled enums,
not `Throwable.message`.

Required bounded events:

1. `request_phase` from the central request transition at
   `TranslationRequestCoordinator.kt:324-373`: `chapter_id`, `generation`,
   `from`, `to`, `failure_kind`. Also emit `request_cleared` from `:376-387`.
2. `queue_result` in `Downloader.queueChapters` (`:306-358`), only for a live
   translation request: `chapter_id`, `generation`, `result` =
   `enqueued|already_downloaded|already_queued|unsupported_source`,
   `queue_size`, `start_requested`.
3. `chapter_start` after page-list creation (`:399-410`): `chapter_id`,
   `generation`, `page_total`, `resumed_ready`, `save_as_cbz`.
4. `page_attempt_failed` at each caught attempt, bounded by the existing four
   attempts: `chapter_id`, `generation`, `page_index`, `page_number`, `attempt`,
   `stage`, `cause`, `error_class`. Controlled `stage` values:
   `resolve_image_url|http_fetch|create_temp|write_temp|detect_type|rename_temp|cache_copy|split`.
   Controlled `cause` values: `source|network|http|storage|invalid_page|unknown`.
   Add the same fields to the image-URL catch at `:425-431`. No success/progress
   event per network chunk.
5. `page_terminal_failed` once in the outer page catch (`:582-588`) with the
   final stage/cause/error class. This is at most one line per failed page.
6. `validation` immediately before the Boolean return at `:683-706`:
   `expected`, `ready`, `on_disk`, `error_count`, and `error_pages` capped to the
   first eight numeric indexes plus `error_pages_truncated=true|false`. Emit one
   line per chapter, success or failure.
7. `finalization` around metadata/archive-or-rename/cache publication
   (`:451-485`): `stage=metadata|archive|rename|cache`, `result=start|success|failed`,
   `error_class` on failure. This distinguishes downloaded bytes from durable
   chapter publication.
8. `download_terminal` at ERROR/DOWNLOADED and for stop/pause/cancel/clear
   boundaries: `state`, `cause`, `expected`, `ready`, `on_disk` where known.
9. `handoff` around `Downloader.handOffAfterFinalization` (`:513-538`):
   `stage=rekey|admission`, `result=start|success|failed`, `generation`,
   `error_class`. The final `request_phase` event proves whether PREPARING was
   written and whether queue admission cleared or failed the request.

This is bounded by O(chapters + failed-page attempts), apart from one validation
line per chapter. It retains no new state and logs no bytes or continuous
progress. A successful chapter needs roughly 8–12 lines, not one line per page.

### Implementation notes that preserve behavior

- Keep URL resolution, retry count/delays, file cleanup, exceptions, state
  mutations, archive/rename, and handoff ordering unchanged. Diagnostics are
  side effects only.
- To distinguish network from storage inside `downloadImage`, maintain a local
  controlled `stage` variable or place logging-only catches around the existing
  operations and immediately rethrow the identical throwable. Do not wrap or
  replace the exception and do not move cleanup.
- Obtain `generation` through the existing pending-request API; absence is
  `none`. Never create a pending request merely to log it.
- Do not put raw `reason` from `setPendingTranslationRequest` into Logcat.
  `failureKind` is the safe diagnostic value.

## Focused verification

Diagnostics do not change product semantics, but the following is proportionate:

- Add `BatchDownloadDiagnosticsTest` for exact one-line schema, controlled
  stage/cause values, numeric error-page cap, and proof that arbitrary
  throwable messages/URLs are absent.
- Extend `DownloaderHandoffFailureSplitTest` to verify diagnostic sink events
  for validation failure, rekey failure, admission failure, and cancellation
  propagation without changing the existing status/callback assertions.
- Add a downloader page-failure test using a fake source/output failure to
  assert `page_attempt_failed -> page_terminal_failed -> validation(failed) ->
  download_terminal(ERROR)` with the correct page index and no URL/message.
- Run the focused suites plus `:app:compileStandardDebugKotlin` and
  `spotlessCheck`. Existing behavioral tests cited above must remain green.

## Exact device capture after the instrumented APK is installed

The app installs as `app.kanade.tachiyomi.at` for release and
`app.kanade.tachiyomi.at.debug` for debug
(`app/build.gradle.kts:26-30,66-70`). Logging is installed at VERBOSE for the
process (`app/src/main/java/eu/kanade/tachiyomi/App.kt:161-163`). Prefer the
debug package and fixed tag.

PowerShell prerequisites and capture:

```powershell
adb devices -l
adb shell pm list packages | Select-String 'app\.kanade\.tachiyomi\.at'

$tracePackage = 'app.kanade.tachiyomi.at.debug'
$traceFile = Join-Path (Get-Location) 'Plan/active/2026-08-31_T913_batch-download-tail-failure-logcat/batch-download-trace.txt'

adb logcat -c
adb shell monkey -p $tracePackage -c android.intent.category.LAUNCHER 1
$tracePid = (adb shell pidof -s $tracePackage).Trim()
if (-not $tracePid) { throw "TachiyomiAT process is not running: $tracePackage" }
adb logcat --pid=$tracePid -v threadtime 'BatchDownloadTrace:V' '*:S' | Tee-Object -FilePath $traceFile
```

If the installed build is release, change only `$tracePackage` to
`app.kanade.tachiyomi.at`. Do not clear app data. Once capture is visibly
waiting, the Main Leader's cue to the Director should be exactly:

> Logcat capture is ready. Please click **Batch Translate** once on the chapter
> that normally fails near the final one or two pages, then leave the app on
> that progress screen until it either starts translation or reports failure.

Stop with `Ctrl+C`. Preserve the raw tagged file; it contains no intended user
content. Correlate one `chapter_id`/`generation` from `request_phase` through
`queue_result`, page/final validation, `download_terminal`, and `handoff`.

## Read-only sibling-commit check

**VERIFIED:** unintegrated commit `7c517d5775be65351dad5ca2d99ba77d1620c40a`
contains broad SAF/CBZ/finalization and retry changes, but no stable tail-failure
diagnostic event stream. Its added logs are limited to generic metadata/rekey
warnings and do not supply page/stage/count/generation correlation. It does not
close this observability gap and should not be integrated for T913.

