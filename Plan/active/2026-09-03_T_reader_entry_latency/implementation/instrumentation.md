# Instrumentation implementation report

## Result

Added one monotonic, aggregate `LogPriority.INFO` timing summary with the stable `[reader_entry]` prefix to each requested entry point. The change is instrumentation-only and does not intentionally alter page discovery, ordering, mapping, viewer selection, or error propagation.

## Commit

- `8a54b60 Add reader entry timing instrumentation`

## Instrumented data

- `DownloadPageLoader.getPages()`: chapter ID, requested page, chapter-directory lookup, handle presence, translation fetch/count, archive/directory branch, directory list construction, `ReaderPage` mapping, total, result count, and error class.
- `DownloadManager.buildPageList()`: duplicate directory lookup, listing/raw count, cumulative `isFile` calls/time, cached name reads/time, fallback sniff calls/time, filtering outcomes, sorting/mapping, total, result count, and error class.
- `WebtoonViewer.setChapters()`: page/requested-page counts, old/new adapter counts, adapter update, initial move, visibility assignment, total synchronous time, first-layout state, and error class.

No manga titles, chapter titles, scanlators, filenames, URIs, or other page-level values are emitted by the new INFO summaries. Existing WARN behavior for unreadable SAF entries remains unchanged.

## Verification

- `./gradlew.bat :app:compileStandardDebugKotlin` — passed after formatting.
- Targeted Spotless IDE-hook formatting — clean for `DownloadManager.kt` and `WebtoonViewer.kt`; applied import-order formatting to `DownloadPageLoader.kt`.
- `git diff --check` — passed.
- Repository-wide `:app:spotlessKotlinCheck` — not clean because of pre-existing format violations in 111 unrelated files; it made no changes.

No focused tests were added because the slice only measures existing synchronous operations and introduces no independently testable loading policy or mapping behavior.

## Remaining work and risks

- Device deployment and baseline collection are intentionally left to the integration/device-validation slice. Filter logcat by `[reader_entry]` and reopen the target chapter multiple times.
- Instrumentation adds two monotonic clock reads around each per-child metadata operation. This is small relative to SAF Binder latency but should be considered when interpreting sub-millisecond in-memory results.
- The top-level loader reports detailed build/mapping durations on successful directory loads. On a directory-load exception, the nested `DownloadManager.buildPageList` summary remains the authoritative breakdown while the top-level unavailable substage fields remain zero and include the error class.
