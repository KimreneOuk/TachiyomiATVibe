# T913 implementation report — batch-download diagnostic trace

## Outcome

Implemented a diagnostic-only, translation-request-scoped Logcat stream with
the fixed tag `BatchDownloadTrace`. The trace now correlates the request
generation through queue admission, chapter/page download, final validation,
publication, and translation handoff. No retry count/delay, download ordering,
storage mutation, UI, request state, or handoff behavior was intentionally
changed.

## Implementation

- Added `BatchDownloadDiagnostics`, a versioned one-line schema that accepts
  only chapter/generation IDs, controlled enums, numeric counts, booleans, and
  sanitized throwable class names.
- Added central `request_phase` and `request_cleared` events.
- Added translation-driven `queue_result` and `chapter_start` events.
- Added bounded `page_attempt_failed` events (at most the existing four network
  attempts), one `page_terminal_failed` event, and stage/cause classification
  for URL resolution, HTTP fetch, temporary-file create/write/type/rename,
  cache copy, and split handling.
- Added one final `validation` event with expected/ready/on-disk counts and at
  most eight numeric failed-page indexes.
- Added `finalization`, `download_terminal`, and `handoff` events to distinguish
  completed bytes from metadata/archive/rename/cache publication and
  rekey/admission failures.
- Logging is emitted only when a translation request generation exists, except
  for the request-origin event itself. Ordinary manga downloads do not emit
  this trace.

The trace API cannot accept titles, URLs, paths, messages, response bodies,
headers, cookies, prompts, content, or credentials. Throwable messages are
never rendered.

## Verification

Passed:

```text
./gradlew.bat :app:testStandardDebugUnitTest \
  --tests eu.kanade.translation.diagnostics.BatchDownloadDiagnosticsTest \
  --tests eu.kanade.tachiyomi.data.download.DownloaderHandoffFailureSplitTest \
  --tests eu.kanade.translation.TranslationRequestGenerationFenceTest \
  --no-daemon

19 tests completed, 0 failed
BUILD SUCCESSFUL
```

The new focused tests verify the exact schema, single-line output, eight-index
cap, controlled enum tokens, and exclusion of throwable messages/URLs.

Passed:

```text
./gradlew.bat :app:compileStandardDebugKotlin --no-daemon
BUILD SUCCESSFUL
```

`spotlessCheck --no-daemon` was run and remains blocked by the repository
baseline: 84 pre-existing Kotlin formatting violations were reported, starting
with `PageTextRendererInstrumentedTest.kt`, `ChapterTranslationIndicator.kt`,
and `TranslationProgressSheet.kt`. The generated Spotless clean-output set
showed one touched-file indentation issue in
`TranslationRequestCoordinator.kt`; that issue was corrected. No unrelated
files were formatted or modified. `git diff --check` passes.

## Device capture handoff

After installing the instrumented debug APK, use package
`app.kanade.tachiyomi.at.debug`, clear Logcat (not app data), resolve the process
PID, and capture only:

```text
adb logcat --pid=<pid> -v threadtime BatchDownloadTrace:V *:S
```

Save the output to
`Plan/active/2026-08-31_T913_batch-download-tail-failure-logcat/batch-download-trace.txt`.
The implementation is ready for the Director's single Batch Translate
reproduction after the APK is installed and the filtered capture is waiting.

## Remaining risk

The actual device-specific tail-page cause remains unknown until reproduction.
The diagnostic count for `on_disk` is best-effort only when page readiness has
already failed; `none` means the provider could not be enumerated without
altering the existing failure path.

## Repair follow-up

The Director authorized the repair after the device capture. The downloader
now keeps the exact page file handle through publication and tall-image
processing, rejects false temporary-file renames, records a page as `READY`
only after a published handle is available, and uses verified per-page handles
when SAF directory enumeration is stale or empty. Archive input is the union
of the provider listing and the known published handles, and failed final
directory/CBZ renames now remain download failures. The all-pages gate and
reader/batch ownership boundaries are unchanged.

Focused verification covers rejected renames, strict validation with an empty
SAF listing plus verified handles, and refusal to accept an unavailable
listing without a handle. The focused suites passed 25/0, the Standard arm64
debug APK assembled successfully, and version `0.17.1-300` was installed on
the connected device without clearing app data. A fresh user-triggered batch
translation is still needed for final behavioral confirmation.

## Capture-readiness follow-up

The independent review identified and the follow-up closed the capture-critical
gaps before device installation. Path operations now record false, null, and
thrown results for page/temp/final publication; validation counts are gated so
ordinary downloads retain their prior fast path; and a bounded trace context
records a generation attachment when a request arrives after download work has
started. Product state, retry behavior, file mutation, and handoff semantics are
unchanged. The focused suites now pass 22/0, including the added false-rename,
late-attachment, and event-sequence tests. A compiler warning remains in the
existing handoff test (`Any?` passed to an `Any` matcher); it does not fail the
build.
