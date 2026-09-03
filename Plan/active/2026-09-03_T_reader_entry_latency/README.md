# Reader entry latency optimization

## Objective

Make a 260-page translated chapter enter the reader without perceptible blocking. The active page must become available first, with a small `n + 1`, `n + 2` lookahead; remaining pages must be resolved or hydrated lazily as navigation/scrolling requires them.

## Known baseline

- Branch: `optimize_reader_lazy_loading`
- Existing commits: `e487259`, `de943a9`, `5584e0a`
- Translation-store open improved from 58,774 ms to about 499 ms.
- Suspected remaining bottleneck: `DownloadManager.buildPageList()` synchronously performs repeated SAF metadata queries for every chapter file.
- Also inspect `ChapterLoader`, `ReaderViewModel`, and `WebtoonViewer.setChapters()` for eager work on all 260 page holders.

## Required work

1. Add high-resolution `LogPriority.INFO` timing around `DownloadPageLoader.getPages()`, `DownloadManager.buildPageList()`, and `WebtoonViewer.setChapters()`.
2. Measure the real remaining stall on device `192.168.100.223:34075` for package `app.kanade.tachiyomi.at.debug`.
3. Implement the smallest safe architecture that exposes the active page immediately and defers remaining page metadata/image/translation work.
4. Preserve normal manga behavior and reader stability on Android 8.0+ with bounded memory.
5. Add focused automated tests where practical, run translation unit tests, assemble/install the APK, and validate on the target device.
6. Keep commits small and incremental.

## Acceptance criteria

- Reader entry no longer waits for metadata or hydration of all 260 pages.
- Active page rendering is prioritized; at most a small forward window is eagerly prepared.
- Remaining pages continue to become available correctly as the user scrolls.
- Timing evidence identifies the post-translation costs before and after the change.
- No regression in the relevant unit tests/build, and no obvious crash or reader lifecycle failure in device logs.

## Entry points

- `DownloadPageLoader.kt`
- `DownloadManager.kt`
- `ChapterLoader.kt`
- `ReaderViewModel.kt`
- `WebtoonViewer.kt`

