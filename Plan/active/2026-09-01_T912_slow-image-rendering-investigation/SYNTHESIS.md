# Task T912 Master Synthesis: Slow Image Rendering & Delayed Cleaned Image/Overlay Transition

## Status

INVESTIGATION ONLY — Complete. Ready for Director Decision.

## 1. Executive Summary

This investigation determined the exact technical reasons why opening a fresh chapter with existing translations takes a long time to load, renders the raw/original image first, and takes a significant delay before transitioning to the cleaned image and translated overlay.

The behavior is not caused by slow device hardware or slow inpainting (the inpainting is already done and saved on disk). Instead, it is caused by **compounding logical and architectural defects** in the Reader's page initialization and rendering pipeline:

1. **The Warm Window Uninitialized Index Defect (`chapterPageIndex == -1`):**  
   On chapter open, `ReaderViewModel.chapterPageIndex` starts at `-1`. When the viewholder asks to attach the translated stream, `isInTranslationWarmWindow()` evaluates `ReaderPageWarmWindow.contains(0, -1, ...)` which returns `false`. `attachTranslatedStreamIfWarm()` **actively strips and nulls `page.translatedStream` and sets `page.showTranslatedImage = false`**, forcing the reader to fall back to `originalStream`.
2. **PageLoaders Omit Translation Stream Attachment:**  
   `DownloadPageLoader` and `ArchivePageLoader` decode the translation JSON into `page.translation`, but leave `page.translatedStream = null` and `showTranslatedImage = false`. `HttpPageLoader` does not query the translation store at all.
3. **Double Full-Image Decoding Overhead (Dual `SubsamplingScaleImageView` Instances):**  
   Because `showTranslatedImage` starts as `false`, the reader launches a complete decode pass for the raw image (spawning `SubsamplingScaleImageView`, allocating `BitmapRegionDecoder`, parsing headers, and decoding raw tiles). When the asynchronous flow or `onPageSelected` finally attaches `translatedStream`, `ReaderPageImageView.beginImageTransition()` instantiates a **second** `SubsamplingScaleImageView`, opens the cleaned image from disk, creates a second region decoder, decodes cleaned tiles, and performs a 150ms crossfade while keeping the first decoder in memory.
4. **SAF Tree Iteration & Flow Scheduling Latencies:**  
   Store resolution runs asynchronously across cold flows on `Dispatchers.IO`, and `UniFile.findFile()` performs repeated un-cached IPC traversals over Android's Storage Access Framework.

---

## 2. Root Cause Breakdown

### Primary Defect A: `chapterPageIndex == -1` Nulls Translation Streams
- **Files:** `ReaderViewModel.kt:437, 797-802, 857-870`, `ReaderPageWarmWindow.kt:17`
- **Impact:** 100% of fresh chapter opens fail the warm window check on initial bind. Pre-translated pages are treated as "cold", their streams are discarded, and the original raw image is loaded.

### Primary Defect B: Page Loaders Do Not Eagerly Wire `translatedStream`
- **Files:** `DownloadPageLoader.kt:82-106`, `ArchivePageLoader.kt:26-41`, `HttpPageLoader.kt:72-81`
- **Impact:** Even when `page.translation` is read into memory, `page.stream` resolves to `originalStream` because `translatedStream` is `null` and `showTranslatedImage` is `false`.

### Primary Defect C: Redundant Double Decoding & Memory Spikes
- **Files:** `ReaderPageImageView.kt:172-192, 903-955`
- **Impact:** Every pre-translated page is decoded twice from disk. For high-resolution webtoons/manhwa (e.g. 1600x10000 px), two `SubsamplingScaleImageView` instances, two `BitmapRegionDecoder` native handles, and two tile grids coexist simultaneously, doubling heap/native memory (80–160 MB per page) and thrashing the thread pool.

### Primary Defect D: Cold Flow Asynchrony & 4-Tier SAF Storage Probes
- **Files:** `ReaderViewModel.kt:2781-2818`, `TranslationProvider.kt:57-84, 154-164`
- **Impact:** `observePageView` cold flow takes 50–200ms to resolve store and directory paths on `Dispatchers.IO`, racing far behind main-thread `setImage()`.

---

## 3. Comprehensive Solution Architecture

To achieve instantaneous, single-pass rendering of pre-translated pages:

1. **Initialize `chapterPageIndex` to Landing Page:**  
   In `ReaderViewModel.loadChapter()`, immediately set `chapterPageIndex = chapter.requestedPage.coerceAtLeast(0)`. In `isInTranslationWarmWindow()`, fall back to `page.chapter.requestedPage` if `chapterPageIndex < 0`.
2. **Eager Stream Attachment in Page Loaders & Chapter Open:**  
   In `DownloadPageLoader`, `ArchivePageLoader`, and `DirectoryPageLoader`, attach `page.translatedStream` and set `page.showTranslatedImage = true` directly during `getPages()` when `translation.hasRenderedResult` is true and translation is enabled.
3. **Synchronous/Pre-warmed Translation Store Seeding:**  
   In `ReaderViewModel.loadChapter()`, await active translation store resolution before emitting `ViewerChapters` to the UI, ensuring all initial pages in the warm window have their translated streams attached prior to viewholder binding.
4. **Single-Pass Decoding:**  
   With `translatedStream` attached and `showTranslatedImage = true` on the first `setImage()` call, `ReaderPageImageView` decodes **only the cleaned image**. Raw image decoding, duplicate `SubsamplingScaleImageView` allocation, tile thrashing, and the 150ms crossfade are completely eliminated.
5. **SAF Companion Directory Handle Memoization:**  
   Cache the resolved `UniFile` directory handles in `ChapterTranslationStore` for the duration of the chapter session to avoid repeated 4-level `findFile()` binder calls per page.

---

## 4. Deliverables Index

- Task README: `Plan/active/2026-09-01_T912_slow-image-rendering-investigation/README.md`
- Repository Health: `Plan/active/2026-09-01_T912_slow-image-rendering-investigation/REPO_HEALTH.md`
- Technical Lead Report: `Plan/active/2026-09-01_T912_slow-image-rendering-investigation/engineering/code-investigation-slow-image-rendering.md`
- Reviewer Audit Report: `Plan/active/2026-09-01_T912_slow-image-rendering-investigation/review/failure-mode-and-performance-audit.md`
- Master Synthesis: `Plan/active/2026-09-01_T912_slow-image-rendering-investigation/SYNTHESIS.md`
