# Reader Translation Review Plan

## Goal
Trace the reader translation flow end to end, then isolate the causes of:
- crashes on downloaded chapters
- auto-translation flicker/blink
- skipped pages
- unclear processing state/current page feedback

## Current Flow Map

### Chapter loading
- `ReaderViewModel.init()` loads the manga and selected chapter.
- `ChapterLoader` picks the page loader:
  - `DownloadPageLoader` for downloaded chapters
  - `HttpPageLoader` for online chapters

### Downloaded chapters
- `DownloadPageLoader` resolves the chapter directory/archive from `DownloadProvider.findChapterDir(...)`.
- Archive chapters use `ArchivePageLoader`.
- Directory chapters use local files via `DownloadManager.buildPageList(...)`.
- Each `ReaderPage` gets `sourceFileName`, `originalStream`, and optional `translatedStream`.

### Online chapters
- `HttpPageLoader.getPages()` first tries page-list cache, otherwise fetches from source.
- Each page is queued and downloaded into `ChapterCache` on demand.
- When a page is ready, `originalStream` is attached from the cached file.

### Translation
- Page selection calls `ReaderViewModel.onPageSelected(page)`.
- If auto-translate is enabled, `handleAutoTranslation(page)` enqueues the current page and a small lookahead.
- `TranslationManager.translatePage(...)` creates/cancels per-page jobs.
- `ChapterTranslator` performs decode -> OCR -> inpaint -> translate -> render.
- `ReaderViewModel.observeLiveTranslationStore()` receives live stage/status updates and forwards them to the visible viewer.

## Dynamic Download vs Full Download

### Full chapter download
Use this when the chapter exists in the downloads folder/archive.
Detection is currently chapter-level:
- `DownloadCache.isChapterDownloaded(...)`
- `DownloadProvider.findChapterDir(...)`

This means the app assumes the chapter is local if the folder/archive exists.

### Dynamic / online page cache
For online chapters, the local signal is page-level:
- `ChapterCache.isImageInCache(imageUrl)`
- `HttpPageLoader` sets `page.originalStream` only after the image is downloaded into cache.

So the reader currently distinguishes local availability by:
- full download path for downloaded chapters
- cache file existence for online chapters

## Likely Problem Areas

### 1. Downloaded chapter crashes
Risk points:
- `DownloadPageLoader`: unsafe `!!` when opening local streams
- `ArchivePageLoader`: unsafe `!!` when reading archive entries
- `ChapterTranslator`: single-page decode path has less recovery than batch flow

### 2. Auto-translation flicker
Likely caused by:
- stage-by-stage live updates triggering full viewer refreshes
- page holders being recreated/rebound for every state change
- overlay/button state toggling between stages

### 3. Skipped pages
Likely caused by:
- repeated auto-enqueue of already-running pages
- chapter boundary mismatch (`page.chapter` vs `getCurrentChapter()`)
- duplicate job cancellation/restart behavior

### 4. Unclear processing feedback
Current UI only shows chapter-level translation progress.
It does not expose:
- current page being processed
- current stage
- stable page identifier

## Expected Correct Behavior

1. A page should translate from its own chapter context, not from stale current-chapter state.
2. A page already running translation should not be re-enqueued just because auto mode sees it again.
3. Viewer refresh should distinguish between:
   - stage-only updates
   - final image output changes
4. Downloaded/local page handling should fail safely, not crash on invalid SAF/archive reads.
5. The UI should show a stable processing state for the active page and avoid blinking during stage transitions.

## Review Checklist

- Confirm how `ReaderPage.sourceFileName` is resolved in each loader.
- Confirm whether translation requests use `page.chapter` or `getCurrentChapter()`.
- Confirm whether duplicate auto-enqueue can cancel an in-flight page.
- Confirm whether translation refresh events force holder recreation.
- Confirm how downloaded/local page availability is detected for:
  - full chapter downloads
  - online image cache

## Proposed Fix Order

1. Make page translation idempotent per page key.
2. Stop using chapter-global state when translating a specific page.
3. Separate stage-only updates from image-refresh updates.
4. Harden all local stream opens.
5. Add explicit current-page/stage state for translation UI.

## Notes For Review

- The biggest architectural split is between full chapter download and online image cache.
- Translation should prefer stable local page streams when available.
- The reader should not assume every refresh means the image changed.
