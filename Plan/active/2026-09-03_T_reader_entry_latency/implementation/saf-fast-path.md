# SAF page-index fast-path implementation report

## Result

Downloaded-directory reader entry now reuses the `UniFile` chapter handle already resolved by `DownloadPageLoader`. `DownloadManager` performs one `listFiles()` call, reads each child name and URI once into an immutable descriptor, filters by supported image filename through extension-only `ImageUtil.isImage(name)`, and performs sorting/index creation entirely in memory.

The complete stable page list is retained. Original image streams remain lazy closures in `DownloadPageLoader`, archive handling is unchanged, and arbitrary local-source `DirectoryPageLoader` behavior is untouched.

## Commit

- `f0d9f46 Avoid per-page SAF queries on reader open`

This is a separate behavior commit after instrumentation commit `8a54b60`.

## Production changes

- Replaced `DownloadManager.buildPageList(source, manga, chapter)` with `buildPageList(chapterDir)` so the reader path no longer repeats `DownloadProvider.findChapterDir()`.
- Removed per-child `UniFile.isFile` queries from the app-managed download path.
- Removed fallback content/header sniffing and all eager child stream opens from page-index construction.
- Materialized `(name, URI)` values before sorting so no `UniFile` child handle remains in the sort/map path.
- Preserved lexicographic filename order, zero-based `Page` indexes, `Page.State.READY`, URI identity, null/unsupported/unreadable child skipping, localized empty-list error behavior, and existing WARN reporting for exceptional entries.
- Preserved the `[reader_entry]` timing schema. Optimized `findDirMs`, `isFileMs`/`isFileCalls`, and `sniffMs`/`sniffCalls` now remain zero inside `DownloadManager`; listing, name reads, filtering, sorting/mapping, totals, counts, and errors continue to be measured.

## Tests

Added `DownloadedPageIndexTest` with coverage for:

- a 260-page reverse listing becoming a complete lexicographically ordered list;
- stable zero-based indexes and ready state;
- exact URI preservation;
- zero `isFile` calls;
- zero `openInputStream` calls;
- unsupported metadata and null-name filtering;
- one stale/unreadable child being skipped without aborting valid pages.

The production helper accepts injected clock and image-name predicates for local JVM testing. Android production uses `SystemClock.elapsedRealtimeNanos` and `ImageUtil.isImage(name)`. The predicate seam avoids initializing Android-resource-dependent parts of `ImageUtil` in a plain JVM test; it does not change production filtering.

## Verification

- `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.data.download.DownloadedPageIndexTest"` — passed, 2 tests.
- `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"` — passed; `BUILD SUCCESSFUL in 1m 25s`.
- `./gradlew.bat :app:assembleStandardDebug` — passed with exit code 0.
- Targeted Spotless formatting for all touched Kotlin files — clean.
- `git diff --check` — passed before commit.

An initial focused-test run exposed that direct `ImageUtil` initialization calls Android `Resources.getSystem()` and is unsupported in the plain JVM harness. The test-only predicate seam was added, after which the focused suite passed.

## Intentional boundary and remaining risk

The fast path relies on downloader publication invariants: downloaded pages have supported image extensions, while metadata files do not. A manually inserted directory named with an image extension (for example, `001.jpg`) can now become a descriptor and fail lazily when opened. This tradeoff removes 260 synchronous SAF Binder type queries without changing arbitrary local-directory semantics.

Device timing and interaction validation remain required. The device was previously locked, so no target-chapter baseline was fabricated. With it unlocked, install the new APK, clear logcat, open the exact 260-page chapter, and confirm `findDirMs=0.0`, `isFileCalls=0`, `sniffCalls=0`, correct `/260` count, restored-page selection, forward/backward scroll, translated/original toggling, rotation/background recovery, and chapter-end behavior.
