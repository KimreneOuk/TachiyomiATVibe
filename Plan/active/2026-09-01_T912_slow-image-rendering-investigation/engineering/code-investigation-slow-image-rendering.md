# Technical Investigation: Slow Image Rendering & Delayed Cleaned Image/Overlay Transition (Task T912)

**Author:** Technical Lead  
**Task:** T912  
**Date:** 2026-09-01  
**Status:** COMPLETE (Investigation Only — No Production Code Modified)  
**Target File:** `Plan/active/2026-09-01_T912_slow-image-rendering-investigation/engineering/code-investigation-slow-image-rendering.md`

---

## 1. Executive Summary & Problem Statement

### 1.1 Reported Symptoms
When opening a fresh chapter that has already been fully or partially translated:
1. **Initial Load Latency:** It takes a substantial amount of time to load and display an image whose cleaned bitmap and translated JSON metadata are already persisted on local disk.
2. **Original-First Rendering ("Flash of Untranslated Content"):** The reader invariably loads and renders the raw/original manga page first, displaying untranslated Japanese/Korean/Chinese text to the user.
3. **Severe Transition Delay & Janky Switch:** After displaying the original image, a noticeable delay (frequently 500ms to 2000ms+) elapses before the reader crossfades to the cleaned image and binds the translated text overlay.

### 1.2 Core Root Causes (High-Level Summary)
Our deep source-code trace across `ReaderViewModel`, `ChapterLoader`, `PageLoader`, `PagerPageHolder`, `WebtoonPageHolder`, `ReaderPageImageView`, `TranslationManager`, and `SubsamplingScaleImageView` uncovered **four compounding structural defects**:

1. **The `chapterPageIndex == -1` Initialization Bug (Warm-Window Annihilation):**  
   [`ReaderViewModel.chapterPageIndex`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437) initializes to `-1`. When [`loadChapter`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L945-L1022) and [`observeLiveTranslationStore`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L2587-L2752) execute, they invoke [`updateTranslationWorkingSet`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L740-L789) and [`isInTranslationWarmWindow`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L857-L865). Because [`ReaderPageWarmWindow.contains`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt#L11-L21) evaluates `currentIndex < 0` to `false` for every page, [`attachTranslatedStreamIfWarm`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L791-L822) **actively nulls `page.translatedStream` and forces `page.showTranslatedImage = false` for every page in the chapter**.
2. **Missing Pre-Translation Stream Attachment in Page Loaders:**  
   [`DownloadPageLoader.getPagesFromDirectory`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt#L82-L106) reads the translation map into `page.translation`, but leaves `translatedStream = null` and `showTranslatedImage = false`. [`HttpPageLoader.getPages`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt#L63-L82) does not consult the translation store at all.
3. **Double Full-Image Decoding Overhead:**  
   Because `showTranslatedImage` starts as `false`, the holder initiates a complete decode pipeline for the original raw image (creating a [`SubsamplingScaleImageView`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L933), allocating `BitmapRegionDecoder` / tile decoders, reading header metadata, and decoding base tiles). When the asynchronous translation store flow eventually emits to [`refreshTranslation()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L416-L467), [`ReaderPageImageView.beginImageTransition()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L903-L918) pushes the raw image into `previousPageView`, constructs a **second, brand-new `SubsamplingScaleImageView`**, reads the cleaned image bytes, initializes a second set of tile decoders from scratch, decodes the cleaned image tiles, and performs an alpha crossfade.
4. **SAF Tree Traversal and Multi-Thread Flow Latency:**  
   Opening the chapter store executes SAF file tree operations ([`CleanedImageLifecycleController.sweepOrphanedCleanedImages`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/manager/CleanedImageLifecycleController.kt#L100-L130) runs `directory.listFiles()` on SAF documents). The store resolution runs asynchronously on `Dispatchers.IO`, while `observePageView` cold flows and `eventChannel` hop between Main, IO, and UI dispatchers before reaching the holder.

---

## 2. End-to-End Lifecycle & Chronological Call Sequence

The following diagram and step-by-step trace illustrate the exact execution flow when opening a pre-translated chapter.

```
[User Opens Chapter]
        │
        ▼
ReaderActivity.onCreate / viewModel.init()
        │
        ▼
ReaderViewModel.loadChapter() ──────────────► [chapterPageIndex is -1]
        │
        ├──► loader.loadChapter()
        │        ├── DownloadPageLoader: creates ReaderPages (translation set, but translatedStream=null, showTranslatedImage=false)
        │        └── HttpPageLoader: creates ReaderPages (translation=null, translatedStream=null, showTranslatedImage=false)
        │
        ├──► observeLiveTranslationStore() (Async on Dispatchers.IO)
        │        ├── openOrCreateActiveChapterTranslationStoreSuspend() (SAF I/O, Manifest read)
        │        └── updateTranslationWorkingSet(currentIndex = -1)
        │                 └── WarmWindow.contains(listIndex, -1) == FALSE!
        │                          └── Actively wipes translatedStream=null & showTranslatedImage=false!
        │
        ▼
Viewer (Pager/Webtoon) binds Landing PageHolder (e.g. Page 0)
        │
        ├──► Page is READY (Downloaded) or HttpPageLoader downloads raw image
        │
        ├──► Holder calls setImage() [MAIN THREAD]
        │        ├── attachTranslatedStreamForPage() -> isInTranslationWarmWindow() -> currentIndex=-1 -> FAILS!
        │        ├── page.showTranslatedImage == false
        │        ├── streamFn = page.stream -> returns page.originalStream
        │        ├── prepareTranslationImage(false) -> clears overlay
        │        └── ReaderPageImageView.setImage(originalStream)
        │                 ├── Instantiates SubsamplingScaleImageView #1
        │                 └── SSIV #1 decodes RAW tiles -> RENDERS RAW ORIGINAL IMAGE!
        │
        ▼
[Async Store Flow / onPageSelected finally resolves]
        │
        ├──► onPageSelected(0) sets chapterPageIndex = 0
        │        └── updateTranslationWorkingSet(0) -> WarmWindow.contains(0,0) == TRUE!
        │                 └── attachTranslatedStreamIfWarm() sets page.translatedStream = CleanedStream
        │
        ├──► observePageView(page) emits PageView to Holder.refreshTranslation()
        │        └── wantTranslated=true, alreadyShowingCorrectImage=false
        │                 └── launches setImage() #2 for Cleaned Image
        │
        ▼
Holder calls setImage() #2 [MAIN THREAD]
        │
        ├──► page.showTranslatedImage = true
        ├──► streamFn = page.stream -> returns page.translatedStream
        ├──► ReaderPageImageView.beginImageTransition()
        │        ├── Retains SSIV #1 in previousPageView (alpha = 1.0)
        │        └── Instantiates brand-new SubsamplingScaleImageView #2 (alpha = 0.0)
        ├──► withIOContext: reads cleaned image bytes from disk
        ├──► SSIV #2 initializes new BitmapRegionDecoder, parses header, decodes cleaned tiles
        ├──► SSIV #2 fires onReady() -> onImageLoaded()
        │        ├── finishImageTransition(): 150ms crossfade animation from SSIV #1 to SSIV #2
        │        ├── Recycles SSIV #1 and its decoded raw bitmaps
        │        ├── translationOverlay.isVisible = true
        │        └── TranslationOverlayView.bind(): TextLayoutPlanner measures & draws text overlay
        │
        ▼
[Cleaned Image + Translated Text Finally Visible to User]
```

### 2.1 Trace of Chapter & Page Initialization
- **`ReaderViewModel.kt` lines 945–1022 (`loadChapter`):**
  When a chapter is opened, [`loadChapter`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L945-L1022) calls `loader.loadChapter(chapter)`.
- **`DownloadPageLoader.kt` lines 42–106 (`getPages` / `getPagesFromDirectory`):**
  For downloaded chapters, `getPages()` calls `translationManager.getChapterTranslationForReader(chapterId, ...)`. It builds `ReaderPage` instances where:
  - `sourceFileName` = `fileName` (e.g. `001.jpg`)
  - `translation` = `translations[fileName]` (the decoded [`PageTranslation`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt#L10) containing `cleanedImageName`, `blocks`, etc.)
  - `originalStream` = contentResolver lambda opening local file URI
  - `translatedStream` = `null` (**NOT populated**)
  - `showTranslatedImage` = `false` (**Default initialized**)
  - `status` = `Page.State.READY`
- **`HttpPageLoader.kt` lines 63–82 (`getPages`):**
  For online chapters, `getPages()` builds `ReaderPage` instances where:
  - `sourceFileName` = `onlinePageTranslationKey(imageUrl, url)`
  - `translation` = `null` (**NOT populated; store is not probed**)
  - `originalStream` = `null`
  - `translatedStream` = `null`
  - `showTranslatedImage` = `false`
  - `status` = `Page.State.QUEUE`
- **`ReaderPage.kt` lines 28–33:**
  ```kotlin
  var showTranslatedImage: Boolean = false
  val stream: (() -> InputStream)?
      get() = if (showTranslatedImage && translatedStream != null) translatedStream else originalStream
  ```
  Unless both `showTranslatedImage == true` AND `translatedStream != null`, `ReaderPage.stream` returns `originalStream`.

---

## 3. Deep Root Cause Analysis

### 3.1 Root Cause #1: Why the Raw/Original Image is Rendered First

#### 3.1.1 The `chapterPageIndex == -1` Warm-Window Defect
In [`ReaderViewModel.kt:437`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437):
```kotlin
private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
```
When opening a chapter, `chapterPageIndex` is `-1`. It is ONLY set to `>= 0` when the viewer completes layout and fires [`onPageSelected`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L1123-L1183) (line 1137).

Now inspect [`isInTranslationWarmWindow`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L857-L865):
```kotlin
private fun isInTranslationWarmWindow(page: ReaderPage): Boolean {
    val pages = page.chapter.pages?.filterIsInstance<ReaderPage>() ?: return false
    val pageIndex = pages.indexOfFirst { it === page }.takeIf { it >= 0 } ?: page.index
    val currentIndex = if (page.chapter === getCurrentChapter()) {
        chapterPageIndex
    } else {
        page.chapter.requestedPage
    }
    return ReaderPageWarmWindow.contains(
        pageIndex = pageIndex,
        currentIndex = currentIndex,
        lastIndex = pages.lastIndex,
        radius = ReaderPageWarmWindow.radiusFor(ReadingMode.fromPreference(getMangaReadingMode())),
    )
}
```
And inspect [`ReaderPageWarmWindow.contains`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt#L11-L21):
```kotlin
fun contains(
    pageIndex: Int,
    currentIndex: Int,
    lastIndex: Int,
    radius: Int = DEFAULT_RADIUS,
): Boolean {
    if (pageIndex < 0 || currentIndex < 0 || lastIndex < 0) return false
    val start = (currentIndex - radius).coerceAtLeast(0)
    val end = (currentIndex + radius).coerceAtMost(lastIndex)
    return pageIndex in start..end
}
```
Because `page.chapter === getCurrentChapter()` is `true` and `chapterPageIndex == -1`, `currentIndex` is `-1`. `ReaderPageWarmWindow.contains` explicitly returns `false` when `currentIndex < 0`!

Now look at what [`attachTranslatedStreamIfWarm`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L791-L822) does when `isInTranslationWarmWindow(page)` is `false`:
```kotlin
private fun attachTranslatedStreamIfWarm(
    page: ReaderPage,
    manga: Manga,
    chapter: ReaderChapter,
    source: HttpSource,
) {
    val batchActive = chapter.chapter.id?.let { translationManager.isBatchTranslationActive(it) } == true
    if (!batchActive && !isInTranslationWarmWindow(page)) {
        page.translatedStream = null
        page.showTranslatedImage = false
        return
    }
    ...
}
```
And in [`updateTranslationWorkingSet`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L740-L789):
```kotlin
for ((listIndex, page) in pages.withIndex()) {
    val warm = ReaderPageWarmWindow.contains(listIndex, currentIndex, pages.lastIndex, radius = warmRadius)
    if (warm) {
        ...
    } else {
        page.translatedStream = null
        page.showTranslatedImage = false
    }
}
```
**Conclusion:** During initial chapter load, `updateTranslationWorkingSet` runs with `currentIndex = -1`. It marks ALL pages as cold, explicitly clearing `page.translatedStream = null` and setting `page.showTranslatedImage = false`.

#### 3.1.2 The Page Holder Initial `setImage()` Race
In [`PagerPageHolder.kt:338-350`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L338-L350) and [`WebtoonPageHolder.kt:344-360`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt#L344-L360):
1. `loadPageAndProcessStatus()` receives `Page.State.READY` (for downloaded pages, immediately upon attach).
2. It invokes `setImage()`.
3. `setImage()` attempts to eagerly attach the stream:
   ```kotlin
   if (page.translatedStream == null && showTranslations) {
       viewer.activity.viewModel.attachTranslatedStreamForPage(page)
   }
   ```
4. `attachTranslatedStreamForPage(page)` calls `attachTranslatedStreamIfWarm(page, ...)`.
5. Because `chapterPageIndex == -1`, `isInTranslationWarmWindow(page)` returns `false`. `attachTranslatedStreamIfWarm` sets `page.translatedStream = null` and `page.showTranslatedImage = false`.
6. `setImage()` computes:
   ```kotlin
   if (!page.translationToggled) {
       page.showTranslatedImage = showTranslations && (page.translatedStream != null || page.translation?.shouldShowTranslationOverlay == true)
   }
   ```
   For online chapters, `page.translation` is null, so `showTranslatedImage` becomes `false`. For downloaded chapters, if `shouldShowTranslationOverlay` is true but `translatedStream` is null, `page.stream` falls back to `page.originalStream`.
7. `prepareTranslationImage(false)` is called, clearing any overlay.
8. `selectReaderTranslationOverlayBinding(false, page.translation)` returns `blocks = emptyList()`.
9. `ReaderPageImageView.setImage(originalStream)` is called, instantiating `SubsamplingScaleImageView` #1 to decode and display the **raw original image**.

---

### 3.2 Root Cause #2: Why Switching to Cleaned Image & Overlay Incurs Significant Delay

#### 3.2.1 Complete View Teardown and Second Full Decode in `ReaderPageImageView`
When `onPageSelected` or `observePageView` eventually succeeds in setting `page.translatedStream` and triggers [`refreshTranslation()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L416-L467), the holder launches `setImage()` a second time.

Look at what happens in [`ReaderPageImageView.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt):
1. **[`beginImageTransition()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L903-L918):**
   ```kotlin
   imageGeneration++
   previousPageView = pageView?.takeIf { it.isVisible } // Retains old SSIV #1 showing raw image
   pageView = null
   crossfadePending = true
   ```
2. **[`prepareNonAnimatedImageView()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L929-L955):**
   ```kotlin
   pageView = SubsamplingScaleImageView(context).apply { ... }
   pageView?.alpha = 0f
   addView(pageView, MATCH_PARENT, MATCH_PARENT)
   ```
   A completely new `SubsamplingScaleImageView` #2 is created and added to the view hierarchy.
3. **[`setNonAnimatedImage(source, config)`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L970-L1051):**
   `SubsamplingScaleImageView.setImage(ImageSource.inputStream(...))` initializes:
   - Allocates new `SkiaImageRegionDecoder` or `BitmapRegionDecoder` from the input stream.
   - Spawns background decoder worker tasks on `DecoderPool`.
   - Reads image header, extracts width/height, calculates sample size, partitions into grid tiles.
   - Decodes visible base tiles into Bitmaps.
4. **During Tile Decoding:**
   - The user continues to see the raw original image rendered by `previousPageView` (SSIV #1).
   - `translationOverlay` is hidden (`isVisible = false`) because `translationImageReady` is `false`.
5. **On Tile Ready ([`ReaderPageImageView.onImageLoaded()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L131-L158)):**
   - SSIV #2 fires `onReady()`.
   - [`finishImageTransition()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L172-L192) starts a 150ms alpha animation (`newView.animate().alpha(1f).setDuration(150)`).
   - Once animated, `removeAndRecyclePageView(previousPageView)` destroys SSIV #1 and recycles raw bitmaps.
   - `translationImageReady = true`.
   - `translationOverlay?.isVisible = true`.
   - `translationOverlay?.bind(imageView, blocks, pageWidth, pageHeight)` invokes [`TextLayoutPlanner.plan(...)`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt), measuring every block with `Paint.measureText()` and `Paint.fontMetrics`.
   - Choreographer posts a frame callback to draw text onto the overlay canvas in [`TranslationOverlayView.onDraw(canvas)`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt#L90-L105).

**Summary of Incurred Latency:**
The user experiences the combined time of:
$$\text{Time}_{\text{total}} = T_{\text{raw decode}} + T_{\text{store IO open}} + T_{\text{flow dispatch}} + T_{\text{cleaned disk read}} + T_{\text{SSIV2 decoder init}} + T_{\text{cleaned tile decode}} + T_{\text{crossfade (150ms)}} + T_{\text{text layout planning}}$$
This explains why the transition delay is so pronounced (often > 1.5 seconds on mobile storage).

---

### 3.3 Disk I/O, Storage Access Framework (SAF) & Threading Bottlenecks

1. **SAF Tree Iteration on Chapter Open:**  
   In [`CleanedImageLifecycleController.kt:100-130`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/manager/CleanedImageLifecycleController.kt#L100-L130), [`openOrCreateActiveChapterTranslationStoreImpl`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L868-L902) schedules `scheduleRetiredCleanedImageCleanup`. This calls `sweepOrphanedCleanedImages`, which executes `directory.listFiles()` on SAF `UniFile` directories, querying file names, `isFile`, and `lastModified()` over Android's ContentProvider IPC. On scoped storage with many files, SAF directory listings take hundreds of milliseconds.
2. **Cold Flow Resolution on `Dispatchers.IO`:**  
   [`ReaderViewModel.observePageView`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L2754-L2841) builds a cold flow that calls `openOrCreateActiveChapterTranslationStoreSuspend` on `Dispatchers.IO`. The page holder subscribes to this flow from `onAttachedToWindow` (Pager) or `bind` (Webtoon). Because it is a cold flow, the subscription must wait for coroutine scheduling, store retrieval, manifest lookup, and flow emission before `onEach` receives the update.
3. **Redundant Stream & Buffer Reading:**  
   Both `PagerPageHolder.setImage()` (lines 373–382) and `WebtoonPageHolder.setImage()` (lines 385–389) execute `streamFn().use { process(Buffer().readFrom(it)) }`. This reads the entire image stream into an Okio `Buffer` in memory on `Dispatchers.IO` before passing it to `ReaderPageImageView.setImage()`.

---

## 4. Edge Cases & Viewer Differences

### 4.1 Pager Viewer vs Webtoon Viewer
| Aspect | Pager Viewer (`PagerPageHolder`) | Webtoon Viewer (`WebtoonPageHolder`) |
| :--- | :--- | :--- |
| **View Architecture** | `ViewPager` with `PagerPageHolder` subclassing `ReaderPageImageView`. | `RecyclerView` with `WebtoonPageHolder` wrapping a `ReaderPageImageView` child. |
| **Lifecycle Hooks** | Scope created/cancelled in `onAttachedToWindow` / `onDetachedFromWindow`. | Reused across binds via `bind(page)` and `recycle()`. Scope recreated on bind. |
| **ImageView Subclass** | `SubsamplingScaleImageView` (standard). | `WebtoonSubsamplingImageView` (ignores touches) or `AppCompatImageView` (Coil). |
| **Image Processing** | Supports dual-page split (`splitInHalf`) and dual-page rotate-to-fit. | Supports vertical long-strip splitting and merging (`splitAndMerge`). |
| **Warm Window Radius** | Default radius = 2 pages ([`ReaderPageWarmWindow.kt:6`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt#L6)). | Webtoon radius = 4 pages ([`ReaderPageWarmWindow.kt:9`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt#L9)). |

### 4.2 Downloaded (Local) vs Online Streaming Chapters
- **Downloaded Chapters:**
  - `DownloadPageLoader` already loads the chapter's `PageTranslation` map during `getPages()`.
  - However, it **does not assign `translatedStream` or set `showTranslatedImage = true`** on the created `ReaderPage` objects ([`DownloadPageLoader.kt:100-105`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt#L100-L105)).
  - Because `page.status` is immediately `READY`, `setImage()` runs before any store flow can attach the stream.
  - Because `chapterPageIndex == -1`, `attachTranslatedStreamForPage` fails, guaranteeing the original image is decoded first.
- **Online Chapters:**
  - `HttpPageLoader` completely ignores the translation store in `getPages()`.
  - `page.translation` is null when `ReaderPage` is created.
  - The raw image is downloaded/cached by `HttpPageLoader.internalLoadPage`.
  - The holder must wait for both `HttpPageLoader` to finish downloading AND `observePageView` on `Dispatchers.IO` to resolve the manifest.

### 4.3 Pre-Translated vs Live-Translating vs Untranslated Pages
- **Pre-Translated:** All artifacts (`.cleaned.webp` and JSON translation) are present on disk. The system has everything needed to render the translated page on the very first frame, but fails to do so due to the `-1` warm-window bug and missing loader stream attachment.
- **Live-Translating:** Translation is actively in progress. The original image renders first (expected), and the holder transitions to the cleaned image once translation finishes.
- **Untranslated:** No translation exists. The original image renders and stays on screen (expected).

---

## 5. Formal Evidence Classification Matrix

In accordance with our technical standard, every major finding is classified with strict primary evidence:

| # | Finding & Claim | Classification | Evidence Reference |
| :--- | :--- | :--- | :--- |
| **E1** | `chapterPageIndex` initializes to `-1` and remains `-1` throughout initial `loadChapter()` and `observeLiveTranslationStore()`. | **VERIFIED** | [`ReaderViewModel.kt:437`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437), [`ReaderViewModel.kt:945-1022`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L945-L1022), [`ReaderViewModel.kt:1137`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L1137) |
| **E2** | `ReaderPageWarmWindow.contains()` returns `false` whenever `currentIndex < 0`, causing `isInTranslationWarmWindow()` to fail for every page on chapter open. | **VERIFIED** | [`ReaderPageWarmWindow.kt:17`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt#L17), [`ReaderViewModel.kt:857-865`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L857-L865) |
| **E3** | `attachTranslatedStreamIfWarm()` actively clears `page.translatedStream = null` and `page.showTranslatedImage = false` when `isInTranslationWarmWindow()` is false. | **VERIFIED** | [`ReaderViewModel.kt:798-802`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L798-L802) |
| **E4** | `DownloadPageLoader.getPagesFromDirectory()` populates `page.translation`, but leaves `translatedStream = null` and `showTranslatedImage = false`. | **VERIFIED** | [`DownloadPageLoader.kt:82-105`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt#L82-L105) |
| **E5** | `HttpPageLoader.getPages()` does not query translation metadata or populate `page.translation`. | **VERIFIED** | [`HttpPageLoader.kt:63-82`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt#L63-L82) |
| **E6** | Page holders call `setImage()` immediately on `Page.State.READY`, resolving `page.stream` to `originalStream` when `showTranslatedImage` is false. | **VERIFIED** | [`PagerPageHolder.kt:338-350`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L338-L350), [`ReaderPage.kt:31-33`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt#L31-L33) |
| **E7** | Switching to the cleaned image constructs a brand-new `SubsamplingScaleImageView`, re-decodes the image from scratch, and runs a 150ms crossfade while keeping the previous SSIV alive. | **VERIFIED** | [`ReaderPageImageView.kt:903-955`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L903-L955), [`ReaderPageImageView.kt:172-192`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L172-L192) |
| **E8** | Text overlay is hidden during image load and only measured and drawn after SSIV fires `onReady()`. | **VERIFIED** | [`ReaderPageImageView.kt:143-157`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L143-L157), [`TranslationOverlayView.kt:65-72`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt#L65-L72) |
| **E9** | SAF document tree traversal in `sweepOrphanedCleanedImages` blocks IO worker threads on chapter open. | **STRONG INFERENCE** | [`CleanedImageLifecycleController.kt:100-130`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/manager/CleanedImageLifecycleController.kt#L100-L130), [`TranslationManager.kt:868-902`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/investigate_slow_image_rendering/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L868-L902) |

---

## 6. Strategic Architectural Solution Blueprint

To resolve both the raw-image flash and the transition latency, future implementation should focus on the following targeted improvements:

### 6.1 Landing Page Index & Warm Window Alignment
- **Fix `chapterPageIndex` initialization:**  
  When `loadChapter(loader, chapter)` executes, immediately resolve the landing index:
  ```kotlin
  val landingIndex = chapter.requestedPage.coerceAtLeast(0)
  chapterPageIndex = landingIndex
  ```
- **Update `isInTranslationWarmWindow`:**  
  Fall back to `page.chapter.requestedPage` whenever `chapterPageIndex < 0`.

### 6.2 Eager Translation Stream Attachment in Loaders & Store Open
- **In `DownloadPageLoader` & `ArchivePageLoader`:**  
  When `translation` has `displayImageName != null`, immediately attach `translatedStream = resolveTranslatedStream(translation)` and set `showTranslatedImage = showTranslations` at page creation time.
- **In `HttpPageLoader` / `ReaderViewModel`:**  
  When `observeLiveTranslationStore` resolves the store, immediately populate `page.translatedStream` and `page.showTranslatedImage = true` for pre-translated pages before the holder runs `setImage()`.

### 6.3 Eliminate Double Image Decoding
- By ensuring `page.translatedStream != null` and `page.showTranslatedImage = true` on the very first `setImage()` call for pre-translated pages, the reader will decode **only the cleaned image once**, completely bypassing the initial raw image decode, the view destruction/re-creation, and the 150ms crossfade.

### 6.4 Async Text Layout Pre-Planning
- Cache the `TextLayoutPlanner.plan(...)` result inside `PageTranslation` or `ReaderTranslationOverlayBinding` during store deserialization so that `TranslationOverlayView.bind()` performs $O(1)$ canvas binding without re-measuring font metrics on the UI thread.

---

## 7. Conclusion

The slow rendering and flash of untranslated content are direct consequences of a logic disconnect between `chapterPageIndex = -1`, the `ReaderPageWarmWindow` check, and missing stream initialization in page loaders. This creates a state where pre-translated pages are forced to decode the original image first, followed by a costly tear-down and re-decode for the cleaned image. Aligning the landing page index and eagerly binding the translated stream will allow pre-translated pages to render the cleaned image immediately on first load.
