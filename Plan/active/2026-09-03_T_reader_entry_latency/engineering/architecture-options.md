# Architecture options and recommendation

## Recommended design: complete lightweight index, lazy page content

**Recommendation:** keep the complete stable `List<ReaderPage>` required by the reader, but build it from a single SAF child listing without per-child type/header probes; let RecyclerView/ViewPager holders and the existing translation warm window lazily open only the landing/nearby page content.

This satisfies the user's intent—no all-page image/translation hydration before display—without pretending that a three-element list is the chapter.

### Incremental implementation

1. **Instrumentation-only commit.** Add monotonic nanosecond timing and one INFO summary per operation. Measure the target chapter before behavior changes.
2. **SAF descriptor fast-path commit.** Resolve the chapter handle once in `DownloadPageLoader`, pass that handle into `DownloadManager`, and map each listed child once to a `(name, uri)` value. Filter with `ImageUtil.isImage(name)` (extension-only) and remove `file.isFile` plus stream sniffing from this app-managed download path. Preserve sort/index/error behavior.
3. **Device validation.** Reopen the same restored page in the 260-page chapter in webtoon and one pager mode. Confirm active image display, page count, forward/back scroll, translation display, last-page transition, and no stale-URI crash.
4. **Only if the one-listing cost remains perceptible:** add an atomically written ordered page-index sidecar at download finalization, read it on open, and fall back to the bulk SAF listing for legacy/invalid indexes. Do not introduce a progressively mutating chapter list.

### Suggested API seam

- `DownloadPageLoader.getPages()` owns the already-resolved `chapterPath` and archive/directory decision.
- Add a `DownloadManager.buildPageList(chapterDir: UniFile)` seam (or an internal overload) so it does not call `DownloadProvider.findChapterDir()` again.
- Convert each listed child immediately to a small immutable entry such as `DownloadedPageEntry(name: String, uri: Uri)`. Do not carry `UniFile` into sorting/mapping, which prevents accidental later metadata calls.
- Create all `ReaderPage` descriptors with stable zero-based indexes and lazy `originalStream` closures. Do not invoke any stream during construction.
- Leave arbitrary local-source `DirectoryPageLoader` behavior unchanged; this optimization relies on downloader publication invariants.

### Active page and lookahead

- The active page is chosen by `requestedPage` after the descriptor index is published. Its holder is bound first by the viewer.
- Original images are opened only by bound holders. RecyclerView may bind more than exactly three items depending on viewport/cache/layout, but work remains bounded by visible/cache demand rather than chapter length.
- Existing translation warm windows attach lazy stream factories around the current page and evict far factories. Auto-translation already submits the visible page plus a configured forward count. Keep these mechanisms rather than creating a second loader scheduler.
- If product requires exactly forward-only `n..n+2` translated snapshot hydration, implement that later in `ReaderPageWarmWindow`/`updateTranslationWorkingSet` with tests. It is independent of and lower impact than SAF enumeration; changing the existing symmetric webtoon hysteresis during this latency fix risks scroll-back flashes/thrash.

## Instrumentation specification

Use `android.os.SystemClock.elapsedRealtimeNanos()` (monotonic, high resolution), convert only in the final INFO message, and emit no per-page logs.

### `DownloadPageLoader.getPages()`

One correlated summary containing:

- chapter ID and requested/landing page (avoid manga/chapter title in the metric);
- `findChapterDir` duration and whether a handle was found;
- translation-store fetch duration and translation entry count;
- archive/directory branch;
- directory `buildPageList` duration;
- `ReaderPage` mapping duration;
- total duration and result count/error class.

Place timestamps immediately before/after `findChapterDir`, `getChapterTranslationForReader`/fallback, `buildPageList`, directory mapping, and method exit (`DownloadPageLoader.kt:42-115`). Use `LogPriority.INFO`.

### `DownloadManager.buildPageList()`

Before the optimization, one summary should separately report:

- duplicate `findChapterDir` duration;
- `listFiles` duration and raw child count;
- cumulative `isFile` time/call count;
- cumulative name-read time/call count;
- cumulative fallback stream-sniff time/count (count only when extension recognition fails, if practical);
- filtering duration and accepted/skipped/error counts;
- sorting plus page-object mapping duration;
- total duration.

After the optimization, keep the same summary schema with `isFileCalls=0` and `sniffCalls=0`; this makes the causal improvement explicit. Never log each filename or each caught child error at INFO. Retain WARN for exceptional unreadable entries.

### `WebtoonViewer.setChapters()`

One Main-thread INFO summary containing:

- current page count, requested page, old/new adapter item counts;
- `adapter.setChapters()` duration (includes list copy and `DiffUtil`);
- initial `moveToPage()` duration when applicable;
- total synchronous `setChapters()` duration and whether this was first layout.

Use timestamps around `adapter.setChapters`, `moveToPage`, and visibility assignment (`WebtoonViewer.kt:259-267`). This distinguishes provider delay from adapter/diff delay. If perceived display still trails method completion, add a separate correlated first-holder-image-decoded marker; `setChapters()` alone cannot measure bitmap decode/display.

### Existing `ChapterLoader` timing

Replace or supplement the current `System.currentTimeMillis()` timing (`ChapterLoader.kt:47-52`) with the same monotonic clock so nested timings reconcile. The required three new timing points should share a stable prefix such as `[reader_entry]` for logcat filtering.

## Alternatives considered

### A. Progressive/partial `List<ReaderPage>`

**Reject for this task.** It requires a new page-collection abstraction carrying total count, stable global indexes, placeholders/readiness, and mutation notifications. Both viewers, adapters, progress UI, chapter transitions, split-page handling, translation resolver, and lifecycle cleanup would need changes. It creates considerably more correctness and normal-manga risk than removing the Binder loop.

### B. Build child URIs directly from translation keys or numeric filenames

**Reject.** Translation keys do not cover untranslated normal manga, extensions vary, split pages create extra names, and constructing document IDs from filenames assumes provider-specific identity semantics. It can silently point at the wrong/missing document across SAF providers.

### C. Query `ContentResolver` directly for ID/name/MIME in one cursor

**Fallback option.** This can exclude directories without per-child queries and is more semantically complete than extension-only filtering. It also duplicates DocumentsContract/UniFile compatibility logic and needs separate handling for raw/file-backed `UniFile`. Prefer the downloader-invariant extension fast path first; use a tested helper only if edge-case requirements prohibit skipping `isFile`.

### D. Persistent page-index sidecar

**Second-stage option only.** An atomic sidecar makes repeated opens O(1 file read) with no directory listing, but legacy chapters still need a fallback, and index invalidation must cover redownload, split-tall changes, manual deletion, CBZ conversion, and stale URIs. Measurement should justify this added state.

## Verification gates

- Unit test entry mapping with 260 fake children and assert no `isFile` or `openInputStream` call occurs during list construction.
- Unit test lexicographic order, zero-based indexes, URI preservation, metadata/null-name exclusion, empty-list error, and one inaccessible page not aborting other valid descriptors.
- Regression test saved/restored page near the middle/end selects the same global page and reports `/260`.
- Run translation unit tests and standard debug assembly.
- Device before/after: three cold-ish opens of the same page, record median and worst timings for translation fetch, list, filter, map, `ChapterLoader.getPages`, and `WebtoonViewer.setChapters`.
- Scroll forward beyond `n+2`, backward, jump via slider, toggle translated/original, rotate/background/return, and reach chapter end. Watch for ERROR/ANR, recycled-holder wrong images, and stale translation refreshes.

