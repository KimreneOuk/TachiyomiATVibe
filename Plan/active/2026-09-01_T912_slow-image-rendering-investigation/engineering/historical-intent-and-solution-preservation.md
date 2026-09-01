# Historical Implementation Intent & Non-Breaking Architecture Analysis

**Task:** T912  
**Date:** 2026-09-01  
**Scope:** Deep-dive into original developer intents, historical constraints, design trade-offs, and how to implement a flawless fix without regressing prior solutions.

---

## 1. Context & Historical Archaeology

To engineer a safe, elegant fix, we traced the codebase history, commit logs, design specifications (e.g. `docs/superpowers/specs/2026-06-22-audit-driven-translation-fixes-design.md`, `docs/DATA_FLOW.md`, `docs/TRANSLATION_MODULE.md`), and historical PR tracks.

### 1.1 Why was `ReaderPageWarmWindow` Created?
- **Problem the original developers solved:** In 100+ to 200+ page manhwa/manga chapters, attaching active stream lambdas, keeping open file descriptors, or buffering decoded byte arrays for all pages simultaneously led to native handle exhaustion, GC pressure, and Out-Of-Memory (OOM) crashes on low/mid-tier Android devices.
- **Architectural Invariant (`docs/DATA_FLOW.md:264-269`):**
  > *"Reader-side translated image streams are on demand. `ReaderPageWarmWindow` attaches translated streams only for the current page plus two pages on either side; cold pages keep only persisted metadata and reopen translated images from disk when they enter the warm window. A 200-page chapter therefore does not keep 200 translated stream factories or compressed source byte arrays in memory."*
- **The Design:** A sliding window (`currentIndex +/- 2` for Pager mode, `currentIndex +/- 4` for Webtoon mode). Pages entering the window have their lazy stream attached; pages leaving the window have `translatedStream` nulled.

### 1.2 Why was `attachTranslatedStreamForPage` Created (Historical Track D)?
- **Problem the original developers solved:** In `docs/superpowers/specs/2026-06-22-audit-driven-translation-fixes-design.md:128-150` ("Track D — §16 re-flash fix"), developers noticed that when navigating to a page, `setImage()` often executed before the asynchronous translation store flow delivered the translated state to the view holder, resulting in an original-image flash.
- **Developer's Intended Solution:** Add `viewModel.attachTranslatedStreamForPage(page)` eagerly inside `PageHolder.setImage()` right before `page.stream` is resolved.
- **Why Track D Failed on Fresh Chapter Opens:**  
  `attachTranslatedStreamForPage` delegates to `attachTranslatedStreamIfWarm`.  
  Inside `isInTranslationWarmWindow(page)`:
  ```kotlin
  val currentIndex = if (page.chapter === getCurrentChapter()) {
      chapterPageIndex
  } else {
      page.chapter.requestedPage
  }
  ```
  On a fresh chapter open, `chapterPageIndex` is uninitialized (`-1`).  
  Because `page.chapter === getCurrentChapter()` is `true`, `currentIndex` evaluated to `-1`.  
  `ReaderPageWarmWindow.contains(0, -1, ...)` returned `false`.  
  `attachTranslatedStreamIfWarm` interpreted Page 0 as "cold" and **explicitly nulled `page.translatedStream` and set `page.showTranslatedImage = false`**.  
  This unintended logic inversion completely neutralized the developer's Track D fix on every fresh chapter load!

### 1.3 Why does `ReaderPageImageView.beginImageTransition()` Crossfade Dual SSIV Views?
- **Problem the original developers solved:** During interactive reading, when a user taps the translation toggle button on the bottom bar OR when background auto-translation finishes translating the page currently visible on screen, swapping the image abruptly caused an unbuffered black flicker / visual pop.
- **The Design:** Retain the old view in `previousPageView`, instantiate the new view in `pageView`, wait for the new view's `onReady()` tile decode callback, and execute a smooth 150ms alpha crossfade before recycling `previousPageView`.
- **The Misapplication:** This dual-SSIV crossfade is vital for *live runtime state transitions*. However, for *pre-translated chapters*, the page should have started in the translated state on its very first layout pass, rendering only once directly into `pageView`.

---

## 2. Invariants that MUST NOT be Broken

Any fix must strictly preserve all of the following existing system guarantees:

| Invariant | Purpose | How We Preserve It |
| :--- | :--- | :--- |
| **1. Bounded Memory (Sliding Window)** | Prevent OOM / file descriptor leaks on 200+ page chapters. | Keep `ReaderPageWarmWindow` fully active. Cold pages outside `currentIndex +/- radius` still have their streams nulled on eviction. |
| **2. Interactive Translation Toggle** | User can toggle translation ON/OFF at runtime via bottom bar. | `beginImageTransition()` and `translationToggled` crossfade logic remain 100% untouched for user toggles. |
| **3. Live Auto-Translation Handoff** | When reading an untranslated chapter, live background translation smoothly crossfades when complete. | `observePageView` and `refreshTranslation()` retain their transition pipeline for live transitions. |
| **4. Normal Manga Compatibility** | Normal (untranslated) manga must not incur overhead or behavior changes. | If `page.translation` is null or translation is disabled, `translatedStream` is null and `showTranslatedImage` is false, executing normal single-pass decoding. |
| **5. Partial Translation Handling** | Chapters with only some pages translated must render correctly. | Each page independently resolves its stream based on whether that specific page has pre-rendered cleaned output. |
| **6. Lazy Stream Evaluation** | Attaching streams must not perform disk I/O on the Main UI thread. | `resolveTranslatedStream` and `getCleanedImageStream` return lazy `(() -> InputStream)?` closures; zero disk I/O occurs until `SubsamplingScaleImageView` opens the stream on its background decoder worker. |

---

## 3. The Non-Breaking Engineering Solution

### 3.1 Fix 1: Align Warm Window Fallback in `ReaderViewModel`
In [`ReaderViewModel.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt):
1. When `loadChapter(loader, chapter)` executes, immediately set `chapterPageIndex` to the landing page:
   ```kotlin
   val landingIndex = chapter.requestedPage.coerceAtLeast(0)
   if (chapterPageIndex < 0) {
       chapterPageIndex = landingIndex
   }
   ```
2. In `isInTranslationWarmWindow(page)`:
   ```kotlin
   val currentIndex = if (page.chapter === getCurrentChapter()) {
       if (chapterPageIndex >= 0) chapterPageIndex else page.chapter.requestedPage
   } else {
       page.chapter.requestedPage
   }
   ```
   **Why this is safe:** If `chapterPageIndex` is `-1` (initial layout), it falls back to `requestedPage` (the page being opened). Once the user scrolls and `onPageSelected` fires, `chapterPageIndex` tracks the live page index. `ReaderPageWarmWindow` operates as intended from frame 1.

### 3.2 Fix 2: Eager Stream Attachment in `DownloadPageLoader` & `ArchivePageLoader`
In [`DownloadPageLoader.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt) and [`ArchivePageLoader.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ArchivePageLoader.kt):
- In `getPagesFromDirectory()` and `ArchivePageLoader.getPages()`, `resolveTranslatedStream(pageTranslation)` already exists.
- For pages where `translation?.hasRenderedResult == true`, attach `page.translatedStream = resolveTranslatedStream(translation)`.
- **Why this is safe:** `resolveTranslatedStream` returns a lazy lambda `(() -> InputStream)?`. It does NOT open files or read bytes during `getPages()`. It simply provides the stream supplier so that when `setImage()` runs on a pre-translated page, `page.stream` immediately provides the cleaned image.

### 3.3 Fix 3: Cache Storage Access Framework (SAF) Directory Handles
In [`TranslationProvider.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/data/TranslationProvider.kt) / [`ChapterTranslationStore`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt):
- Memoize the resolved companion directory `UniFile` handle per active chapter session so repeated stream opens do not run 4 sequential `UniFile.findFile()` IPC binder calls.

---

## 4. Expected Performance & UX Impact

1. **Zero Double-Decode:** Pre-translated pages decode **only once** (the cleaned image). Peak memory allocation is halved, and raw image decode time is eliminated.
2. **Zero Untranslated Flash:** The user sees the cleaned image and translated overlay on the very first rendered frame.
3. **Preserved Bounded Memory:** 200+ page chapters remain strictly bounded by `ReaderPageWarmWindow`.
4. **Preserved Interactive Transitions:** Manual toggles and live auto-translation completions continue to use the smooth 150ms crossfade.
