# TachiyomiAT Translation Pipeline: Comprehensive Investigation Report

This report provides a detailed, end-to-end technical analysis of the translation module in TachiyomiAT. It details how the pipeline coordinates storage, caches, rendering, error handling, and concurrency, and how these systems scale to support large chapters (up to 200 pages) on compact mobile screens.

---

## Table of Contents
1. [State Persistence & Translation Store](#1-state-persistence--translation-store)
2. [Pre-Translation Lifecycle & Overwrite Behavior](#2-pre-translation-lifecycle--overwrite-behavior)
3. [Cache and Artifact Deletion Mechanics](#3-cache-and-artifact-deletion-mechanics)
4. [Failure Recovery & Resuming Translation](#4-failure-recovery--resuming-translation)
5. [Concurrency, Race Conditions & Synchronization](#5-concurrency-race-conditions--synchronization)
6. [File Path Sanitization & Collision Risks](#6-file-path-sanitization--collision-risks)
7. [Inpaint Caching & Intermediate Files](#7-inpaint-caching--internal-intermediate-files)
8. [Scale & Performance Bottlenecks of JSON Storage](#8-scale--performance-bottlenecks-of-json-storage)
9. [Cache Deletion Granularity](#9-cache-deletion-granularity)
10. [AI Context Chunking & Adaptive Retries](#10-ai-context-chunking--adaptive-retries)
11. [Single-Page Failure Gates & Recovery Path](#11-single-page-failure-gates--recovery-path)
12. [Minimal Mobile UI Design for 200-Page Chapters](#12-minimal-mobile-ui-design-for-200-page-chapters)
13. [Toggle Bug: Show Original vs. Translated](#13-toggle-bug-show-original-vs-translated)
14. [PaddleOCR v6 Garbage Output Bug](#14-paddleocr-v6-garbage-output-bug)
15. [Reader UI Improvements: Translation Management](#15-reader-ui-improvements-translation-management)
16. [Auto-Translate Page Re-Flash on Navigation](#16-auto-translate-page-re-flash-on-navigation)
17. [Resume & Redundancy Audit (2026-06-22)](#17-resume--redundancy-audit-2026-06-22)
18. [Inpainting Quality Audit (2026-06-22)](#18-inpainting-quality-audit-2026-06-22)

---

## 1. State Persistence & Translation Store

The application uses a unified class, [ChapterTranslationStore](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt), to manage the active translation states of a chapter's pages.

* **Unified State:** Every translation path (manual click, auto-translation on scroll, or batch pre-translation) operates on this store. The pipeline interacts with the store via a delegate resolved by `activeStoreResolver` in [TranslationManager.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L83-L91).
* **Page State Schema:** Page states are modeled in [PageTranslation.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt) using four status strings:
  - `ocrStatus` (OCR/detection state)
  - `translationStatus` (Text translation state)
  - `inpaintStatus` (Speech bubble cleanup state)
  - `renderStatus` (Text overlay rendering state)
* **Durable vs. Transient State:** Updates to the store do not write to the disk unconditionally. The store determines what is worth writing using `shouldPersistUpdate()`:
  - **Durable (Written to Disk):** Rendered image names (`renderedImageName`), recognized text blocks (`blocks`), cleaned bubble images (`cleanedImageName`), failed stages, or transition away from a completed state.
  - **Transient (Memory Only):** Page states that represent transient queue states (like `PENDING` or `RUNNING` with no text blocks or images yet) are kept only in the memory flow. This prevents temporary reader queues from writing empty placeholders that would otherwise mark the chapter as "translated" on disk.

---

## 2. Pre-Translation Lifecycle & Overwrite Behavior

When a user initiates the batch pre-translation of a chapter (by clicking "Translate chapter" from the manga screen):

1. **The Purge Phase:** The application calls `queueChapter` in [ChapterTranslator.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L273-L277). This method checks for an existing translation JSON file and companion image directory and **unconditionally deletes them** to guarantee a clean slate:
   ```kotlin
   provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)?.let { existing ->
       existing.delete()
   }
   provider.deleteCompanionImages(manga.title, source, chapter.name, chapter.scanlator)
   ```
2. **The Batch Execution Phases:** If a translation JSON is present (for example, if the batch was resumed from the queue screen rather than initiated fresh from the manga screen):
   - **Stage 1 (OCR/Detection):** The pipeline loops over the pages. It checks `ocrStatus == StageStatus.READY` and the presence of `inpaintMaskBoxes` ([TranslationPipeline.kt#L740-L757](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L740-L757)). If they exist, it **skips OCR/detection** for that page.
   - **Stage 2 (Inpaint & Translate):** For standard engines (ML Kit/Google), the pipeline re-translates the page and re-inpaints the image. For AI engines, the chunk planner does not check if blocks already have translations and re-sends all blocks to the LLM. **No Stage 2 resume is implemented for batch pre-translation.**
   - **Stage 3 (Render):** The pipeline re-renders the translated text onto the newly cleaned images and saves the final `.rendered.png`, overwriting any existing render caches.

---

## 3. Cache and Artifact Deletion Mechanics

When a user taps "Delete translation" in the UI, the system runs `deleteTranslation()` in [TranslationManager.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L411-L451):

1. **Job Cancellation:** The scheduler cancels all auto-prefetch and single-page jobs for the chapter and waits for them to join (bounded by `JOIN_TIMEOUT_MS`).
2. **Eviction:** The chapter is removed from the translator's queue and its active `ChapterTranslationStore` is unregistered from memory.
3. **JSON Deletion:** The metadata file `<Scanlator>_<SanitizedChapterName>.json` is deleted.
4. **Image Directory Deletion:** The companion directory (`<Scanlator>_<SanitizedChapterName>_images/`) is deleted. Since the directory is represented as a `UniFile`, calling `delete()` executes a recursive file cleanup on Android's DocumentProvider, wiping all `.cleaned.png` and `.rendered.png` files from physical storage.

---

## 4. Failure Recovery & Resuming Translation

If a translation process fails (due to service issues, NPU memory limits, or app crash), the recovery behavior depends on how the user restarts it:

* **Tapping "Translate chapter" again (Manga Details screen):** Since `queueChapter` deletes the old store, all progress is wiped and it **starts over from scratch**.
* **Resuming the queue (Queue screen):** If the chapter remains in the queue, clicking "Start" resumes the queue without calling `queueChapter`. Stage 1 (OCR) checks the store and **skips** already-analyzed pages. However, Stage 2 (Translation/Inpainting) and Stage 3 (Rendering) run again for the entire chapter.
* **Opening the Reader:** The reader's single-page on-demand path (`translateSinglePageInternal` in [TranslationPipeline.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt)) is **fully resume-aware**:
  - If a page has `hasRenderedResult == true` (a completed render file exists), it is **completely skipped**.
  - If a page has translated blocks but is missing the rendered image, and a `.cleaned.png` image exists, it **skips OCR, translation, and inpainting**, invoking `resumeInpaintAndRender()` to only render the text overlay on the existing cleaned image.

---

## 5. Concurrency, Race Conditions & Synchronization

If two translation flows (e.g., reader auto-translate and manual pre-translate batch) target the same chapter simultaneously:

* **Inference Lock:** The pipeline uses a singleton `translatorPermit` (a `Semaphore(1)`) to serialize all CPU/NPU intensive tasks. Only one thread can perform OCR, text detection, or neural inpainting at a time.
* **Store Synchronization:** The `ChapterTranslationStore` wraps all updates in a Kotlin `Mutex` lock, serializing memory map writes and JSON file persistence.
* **Atomic JSON Renaming:** Inside `persistLocked()`, the store writes the JSON to a temporary file (`translation.tmp`) first, then deletes the target and renames the temp file over it. This prevents the JSON file from becoming corrupted or truncated in the event of an unexpected crash or process kill.
* **File Overwrites:** Because the execution path is serialized, threads write to the same `.cleaned.png` and `.rendered.png` files sequentially. The slower thread will simply overwrite the faster thread's files. Since they produce identical images, this prevents corrupt files (though it executes redundant I/O).

---

## 6. File Path Sanitization & Collision Risks

Files and folders are organized under the source and manga subfolders:
`Translations / <SourceDirName> / <MangaDirName> /`
where each parent folder name is sanitized using `DiskUtil.buildValidFilename(name)`.

* **Sanitization:** Replaces OS reserved characters (`/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`) with underscores or safe equivalents.
* **Scanlators:** Chapters for different scanlators contain the scanlator name in their files and directories (`${chapterScanlator}_$newChapterName.json` and `${chapterScanlator}_${newChapterName}_images`), eliminating scanlator collisions.
* **Duplicate Chapter Names:** If a manga contains two chapters with the exact same name and scanlator (e.g., two chapters named "Afterword" in different volumes grouped under the same manga entry), they will write to the same JSON file and companion image directory. This will cause their translations to overwrite each other.

---

## 7. Inpaint Caching & Intermediate Files

* **No Automatic Cleanup:** After a page is rendered (`.rendered.png` created), the intermediate `.cleaned.png` (inpainted image with bubbles cleared of text) is **not deleted**. It remains in the companion folder.
* **Architectural Purpose:** Keeping the cleaned bubble background on disk is a performance optimization. Neural inpainting is a very slow, resource-heavy model inference. Storing the cleaned image allows the reader to quickly re-render the text overlay (e.g. if the user modifies font size, text color, style, or forces a retry on translation blocks) without running the bubble cleaning model again.

---

## 8. Scale & Performance Bottlenecks of JSON Storage

* **JSON Size:** A single page's translation metadata (blocks, coordinates, translations, inpaint mask coordinates) is small (~3–5 KB). A 50-page chapter results in a JSON file of ~200 KB, and a large 100-page chapter is ~400 KB.
* **CPU and I/O Bottlenecks:** Kotlinx.serialization (`Json.decodeFromStream` and `Json.encodeToStream`) parses a 400 KB file in under 5ms on modern Android devices. Disk I/O is offloaded to the background thread (`Dispatchers.IO`), keeping the main thread free.
* **Write Throttling:** `shouldPersistUpdate` limits I/O by only persisting completed stages (OCR done, clean done, render done) and ignoring transient state updates (like `PENDING` or `RUNNING`). This keeps JSON file I/O lightweight and prevents it from becoming a performance bottleneck, even for 200-page chapters.

---

## 9. Cache Deletion Granularity

At the UI level, translation deletion is **all-or-nothing**. 
If a user manually deletes the companion images directory (`_images/`) using an external file manager but leaves the JSON file intact:
* The store state continues to read `renderedImageName = "<page>.rendered.png"`.
* When the reader opens, the system assumes the translation is complete and skips scheduling work.
* The image loader attempts to load the rendered file, encounters a `FileNotFoundException`, and displays a blank page or error. The user must tap the page's translate button to force a reload (`force = true`), which regenerates the files.

---

## 10. AI Context Chunking & Adaptive Retries

The LLM-based translation path handles page blocks via [TranslationContextChunkPlanner](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/TranslationContextChunkPlanner.kt):

* **Initial Planning:** The initial `plan()` collects all non-blank blocks for all pages in the run. It does not check if blocks already have translations (batch runs always re-translate everything).
* **Adaptive Retries:** If a chunk fails or returns partial results, [AiTranslationRetryPlanner.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/AiTranslationRetryPlanner.kt) extracts the remaining untranslated blocks using `untranslatedPages()`:
  ```kotlin
  val missing = page.blocks.filter { block ->
      block.text.isNotBlank() &&
          (block.translation.isBlank() || block.translation.trim() == block.text.trim())
  }
  ```
  It then splits this subset into smaller chunks and re-submits only the failed blocks to the LLM (up to 2 retries).

---

## 11. Single-Page Failure Gates & Recovery Path

> **Status note (2026-06-22 audit):** Items #1 (PARTIAL rendering) and #2 (local adaptive retries) are **implemented** in Track C of the audit-driven fixes spec. Item #3 (selective source overlay) is **rejected** — it reverts a deliberate fix; see "Rejected" below. The code-level facts beneath this header are preserved for history; the resolution is recorded here.

In the reader/single-page path ([TranslationPipeline.kt#L1641](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1641)), rendering was gated on:
```kotlin
if (pageTranslation.blocks.isNotEmpty() && pageTranslation.translationStatus == StageStatus.READY)
```
If a single block failed (was blank or source-equal), the page was marked `PARTIAL`. Because `PARTIAL` was not `READY`, the reader skipped rendering the entire page and showed a red error overlay.

### Resolution (implemented — Track C)
1. **PARTIAL rendering in the reader:** DONE. The gate now admits `READY || PARTIAL`, aligning the single-page path with the batch path and docs contract #14b. A PARTIAL page renders translated bubbles + blank bubbles (the renderer draws only `block.translation`, which is blank for failed blocks) instead of a red error overlay.
2. **Local Adaptive Retries:** DONE. After validation, a PARTIAL single page retries up to 2 times, re-requesting only the still-untranslated blocks via a new pure helper `AiTranslationRetryPlanner.untranslatedBlocks` (single-page analog of `untranslatedPages`). The retry budget invariant is preserved: PARTIAL does NOT bump the page's persistent `retryCount` (contract #14b).
3. **Selective Source Overlay:** REJECTED. This proposal would draw the original source text (e.g. Japanese) only inside bubbles where translation failed. It conflicts with the deliberate no-source-fallback design — `PageTextRenderer.renderSourceText` defaults to `false`, and `TranslationPipeline.kt:1690-1698` actively removed the `renderOverOriginalSinglePage` path because users reported the deceptive "source visible under translation" result. Reintroducing it would revert contract #14b.

---

## 12. Minimal Mobile UI Design for 200-Page Chapters

To align with modern mobile design principles and avoid visual overload on compact screens, we replace detailed page lists and status matrices with a highly condensed **Pipeline Stage Summary**.

```
+--------------------------------------------------------+
|  Translation Progress                                  |
|  Chapter 45 - The Final Stand                          |
|                                                        |
|  [=======================>                      ] 53%   |
|  Pages: 106/200 completed                              |
|                                                        |
|  Pipeline Stages                                       |
|  - OCR/Detection: [=============>              ] 140/200|
|  - Cleaning:      [========>                   ] 110/200|
|  - Translation:   [=======>                    ] 106/200|
|  - Canvas Render: [=======>                    ] 106/200|
|                                                        |
|  Failures Summary (Grouped)                            |
|  • HTTP 429: Rate Limit (Pages: 12, 13, 14)            |
|  • ONNX Inference OOM (Page: 87)                       |
|                                                        |
|                                   [CLOSE]  [CANCEL]    |
+--------------------------------------------------------+
```

### Design Highlights:
* **Minimal Pipeline Rows:** The UI displays exactly four progress bars mapping directly to the stages of the pipeline. It works identically for 10 pages or 200+ pages, remaining perfectly clean.
* **No Page Lists:** Page numbers and individual status nodes are removed entirely to respect phone screen space.
* **Semantic Error Grouping:** Duplicate failures are grouped by their messages, listing counts and affected page ranges to keep error logs brief and informative.

---

## 13. Toggle Bug: Show Original vs. Translated

### Current Implementation Flow
1. **Model:** [ReaderPage.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt) exposes a per-page state boolean `showTranslatedImage`. Its `stream` property resolves the active stream:
   ```kotlin
   val stream: (() -> InputStream)?
       get() = if (showTranslatedImage && translatedStream != null) translatedStream else originalStream
   ```
2. **Trigger:** Tapping "Show original" or "Show translated" in the side compare handle calls `viewModel.setCurrentPageShowTranslated(showTranslated)` in [ReaderViewModel.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L519). This method mutates the target page's `showTranslatedImage` property and fires a `RefreshTranslationPages` event.
3. **Dispatch:** The reader activity intercepts the event and forwards it to the active viewer, which in turn calls `refreshTranslation()` on the matching visible [PagerPageHolder](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L377) or [WebtoonPageHolder](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt#L359).

### The Root Cause of the Bug

> **Reachability caveat (2026-06-22 audit):** the code quote below is exact and the structural issue is real, but the user impact ("locked out of seeing the original image") is **narrower than originally stated**. The actual original→translated toggle runs through a separate preference collector in the holder `init` block (`PagerPageHolder.kt:120-127`), which correctly reads the `showTranslations` preference and calls `setImage()`. Whether the `refreshTranslation()` line forces `showTranslatedImage = true` as a *live, reachable* bug depends on a path reaching it with `showTranslations` locally true but the user's per-page intent false — a narrower window than "the user is locked out." The proposed corrected implementation below remains a sound refactor (it removes the implicit dependency on the global preference), but it is not yet implemented and the severity should be re-examined before acting on it.
Inside the `refreshTranslation()` method of both `PagerPageHolder` and `WebtoonPageHolder`, the conditional block is evaluated as follows:
```kotlin
when {
    isBeingTranslated -> { ... }
    showTranslations && streamAvailable -> {
        if (alreadyShowingThisImage) {
            ...
        } else {
            page.showTranslatedImage = true // <--- THE BUG
            ...
            loadJob = holderScope.launch { setImage() }
        }
    }
    else -> { ... }
}
```
1. **The Overwrite:** If translations are enabled globally (`showTranslations == true`) and a translated stream is available, the `when` matches `showTranslations && streamAvailable`. If `alreadyShowingThisImage` is false (which it is, because we just flipped `showTranslatedImage` to false), the code enters the `else` branch of this block. It then **unconditionally resets `page.showTranslatedImage = true`**, overriding the user's per-page choice. It then triggers `setImage()` to reload the translated image. The user is locked out of seeing the original image.
2. **Missing Reload on Toggle-Off:** If `showTranslations` is false globally, the loop hits the outer `else` block. However, this block contains no call to `setImage()`, meaning the holder does not trigger a reload and continues to show whatever image was last decoded (the translated one).

### The Corrected Implementation
Instead of relying on the global `showTranslations` preference inside `refreshTranslation()`, the condition must evaluate `page.showTranslatedImage` directly and reload the image on change:

```kotlin
val targetImageName = if (currentPage.showTranslatedImage && streamAvailable) newName else null
val targetRenderRevision = if (currentPage.showTranslatedImage && streamAvailable) newRevision else -1L
val alreadyShowingTargetImage = targetImageName == lastShownImageName && targetRenderRevision == lastShownRenderRevision

when {
    isBeingTranslated -> {
        showProcessingOverlay(true)
        setTranslating(true)
    }
    currentPage.showTranslatedImage && streamAvailable -> {
        showProcessingOverlay(false)
        showTranslateButton(translationEnabled)
        setTranslating(false)
        if (!alreadyShowingTargetImage) {
            loadJob?.cancel()
            loadJob = holderScope.launch { setImage() }
        }
    }
    else -> {
        showProcessingOverlay(false)
        showTranslateButton(translationEnabled)
        setTranslating(false)
        if (!alreadyShowingTargetImage) {
            loadJob?.cancel()
            loadJob = holderScope.launch { setImage() }
        }
    }
}
lastShownImageName = targetImageName
lastShownRenderRevision = targetRenderRevision
```
This forces a clean reload of the image using either the translated stream or the original stream (based on `page.showTranslatedImage`) only when the target configuration actually transitions.

---

## 14. PaddleOCR v6 Garbage Output Bug

> **Status note (2026-06-22 audit):** this proposal is **pending empirical validation** (Track B of the audit-driven fixes spec). The current code at `PaddleOcrV6SmallEngine.kt:155-157` writes BGR planes and carries an explicit 4-line comment asserting this is deliberate to match PaddleOCR's OpenCV/BGR training pipeline. The original rationale below ("PaddleOCR models are exclusively trained on RGB") is a general claim with **no evidence about this specific exported ONNX model's expected channel order**. Applying the swap unvalidated could *introduce* the garbage output described. Track B gates the swap behind an on-device A/B comparison (run the same text crops through both orderings, compare recognition output); it ships only if RGB empirically wins. The actual documented garbage-output fixes already in this file are the three `preprocess()` changes at `L103-120` (pad to min width 320, pad with normalization-mean gray, aspect-ratio-preserving resize).

### The Issue
The PaddleOCR v6 model (`PP-OCRv6_small_rec`) returns garbage or empty string values on correctly cropped text lines. The root cause lies in how the input image tensor channels are structured in [PaddleOcrV6SmallEngine.kt](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6SmallEngine.kt). 

The implementation incorrectly assumed that because OpenCV normally outputs `BGR` images, the PaddleOCR engine expects `BGR` input tensors:
```kotlin
// PaddleOCR's standard rec path receives OpenCV images,
// which are BGR. Android bitmaps are ARGB, so write the
// tensor planes as B, G, R to match the exported model's
// training/inference pipeline.
result[offset] = normalize(pixel and 0xFF) // Blue
result[planeSize + offset] = normalize(pixel shr 8 and 0xFF) // Green
result[planeSize * 2 + offset] = normalize(pixel shr 16 and 0xFF) // Red
```

However, **PaddleOCR models are exclusively trained and optimized for RGB images**. In the official PaddleOCR inference pipeline, images loaded via OpenCV are immediately converted from `BGR` to `RGB` (`cv2.cvtColor(img, cv2.COLOR_BGR2RGB)`) before being fed into the recognition network. By passing the channels in `BGR` order, the model's learned weights process mismatched colors, completely destroying character recognition accuracy.

### Proposed Fix (pending Track B A/B validation)
The fix is to simply extract and write the tensor planes in standard **RGB** order (Red, Green, Blue):
```kotlin
// Android bitmaps are ARGB, so write the tensor planes as R, G, B.
result[offset] = normalize(pixel shr 16 and 0xFF)            // Red
result[planeSize + offset] = normalize(pixel shr 8 and 0xFF) // Green
result[planeSize * 2 + offset] = normalize(pixel and 0xFF)   // Blue
```
This correctly feeds RGB data to the model and instantly restores its recognition accuracy. **Track B will validate this against the actual exported ONNX model on-device before shipping** — if BGR empirically wins, the item is closed as misdiagnosed and this section updated accordingly.

---

## 15. Reader UI Improvements: Translation Management

### Current Limitations
1. **No In-Reader Delete Action**: Currently, if a user translates a chapter while inside the reader, there is no direct way to delete or clear the translation without leaving the reader or navigating back to the Manga details screen.
2. **Translation Button Overload**: The translate button inside the reader's `BottomReaderBar` serves dual purposes (progress tracking and translation triggering) and its behavior feels non-intuitive when translation state changes, particularly for triggering a full deletion.
3. **Static Compare Handle**: The `TranslationCompareHandle` (the floating side button used to toggle between original and translated images) is permanently visible at full opacity. This can be visually intrusive during idle reading.

### Proposed UI Fixes

#### 1. In-Reader "Delete Translation" Action
Instead of overloading the bottom translation button, the "Delete translation" action should be cleanly integrated into the existing **TranslationCompareHandle** dropdown menu.
- Add a new `MenuRow` for "Delete translation" featuring a trash or delete icon (`Icons.Outlined.Delete`).
- Tint the row with the theme's `error` color (red) to signify a destructive action.
- When tapped, invoke the existing `viewModel.deleteCurrentChapterTranslation()` logic, instantly clearing the files and returning the reader to the non-translated state.

#### 2. Auto-Hiding Translation Handle (Idle State)
The `TranslationCompareHandle` should fade out and move partially off-screen when the user is not actively interacting with it, maintaining a distraction-free reading experience.
- Track an `isIdle` state that sets to `true` after ~2.5 seconds of no interaction while the menu is collapsed.
- Animate the handle's `alpha` property to `0.4f` (semi-transparent) when idle.
- Animate the handle's horizontal offset (`offsetX`) by `-20.dp` to slide it halfway off the left edge of the screen.
- Any tap on the screen or handle instantly resets `isIdle` to `false`, bringing the handle back to full opacity and fully into view.

---

## 16. Auto-Translate Page Re-Flash on Navigation

### The Symptom
With auto-translation enabled, page n+1 has already been fully translated and rendered in the background. When the user navigates to page n+1, a visible processing animation (image flash/re-decode) briefly appears before the translated page is shown.

### Root Cause: Race Between Holder Init and Stream Attachment

The bug is a **timing race** between two independent async flows that start when a `PagerPageHolder` is freshly created for the target page.

#### The Sequence

1. **Holder created** → `init` block fires three concurrent operations:
   - **A)** `loadJob = holderScope.launch { loadPageAndProcessStatus() }` — begins observing page download status. Immediately calls `setQueued()` which shows the page-loading progress indicator.
   - **B)** `syncTranslationStatus()` — reads `page.translation` (which may be `null` or stale at this moment) and sets overlay state.
   - **C)** `observePageView(page)?.onEach { refreshTranslation() }?.launchIn(holderScope)` — subscribes to the translation store's `StateFlow`.

2. **Flow A reaches `Page.State.READY`** → calls `setImage()`. Inside `setImage()` at [PagerPageHolder.kt#L303](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L303):
   ```kotlin
   page.showTranslatedImage = showTranslations && page.translatedStream != null
   ```
   At this moment, `page.translatedStream` is **still `null`** because `attachTranslatedStreamIfWarm()` hasn't been called yet (it runs inside the `observePageView` `onEach` or `updateTranslationWorkingSet`). So `page.showTranslatedImage = false` and the **original image** is decoded and displayed.

3. **Flow C emits** (the store's `StateFlow` immediately replays the current value). The `onEach` in [observePageView](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L2116-L2125) sets:
   ```kotlin
   page.translation = updated              // sets the completed PageTranslation
   attachTranslatedStreamIfWarm(page, ...)  // attaches the rendered image stream
   ```
   NOW `page.translatedStream` is non-null.

4. **`refreshTranslation()` fires** (the outer `onEach` on the holder). It enters [PagerPageHolder.kt#L377](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L377):
   - `streamAvailable = page.translatedStream != null` → `true`.
   - `isBeingTranslated` → `false` (all stages are READY).
   - `alreadyShowingThisImage` → `false` because `lastShownImageName` was set by step 2 to `null` (we showed the original, not the translated image).
   - **Enters the `showTranslations && streamAvailable` branch** → sets `page.showTranslatedImage = true`, cancels `loadJob`, relaunches `setImage()` to decode the **translated** image.

5. **User sees**: Original image appears (step 2) → brief re-decode/flash → translated image appears (step 4). The re-decode may show the loading progress indicator (from `setQueued()` in step 1-A, never hidden because `loadJob` was cancelled before `onImageLoaded()` ran) during the gap.

### Why This Only Affects Auto-Translated Pages
When the user manually taps the translate button while viewing the page, the holder is already alive. The `page.translatedStream` is attached in-place and `refreshTranslation()` transitions smoothly from original to translated within the same holder lifecycle. No double-decode occurs.

With auto-translate, the translation completes while the holder for page n+1 does NOT exist (it's destroyed/recycled by the ViewPager). When the user navigates, a **new** holder is created. At construction, `page.translatedStream` is `null` (it's only set lazily by the warm-window logic), creating the race described above.

### Proposed Fix

> **Diagnosis correction (2026-06-22 audit):** the original framing below overstated the race as universal. Re-verification found that `PagerPageHolder.holderScope` uses `Dispatchers.Main.immediate` (`PagerPageHolder.kt:78`), so the per-holder `observePageView` collector delivers the store's `StateFlow` value **synchronously** inside the holder `init` block. For a pre-translated page where the store already holds a value, the stream attaches *before* `setImage()` runs in the common case. The flash is real but **narrower** — it manifests in: (a) holders built just outside the warm window, where `attachTranslatedStreamIfWarm` *actively nulls* the stream (`ReaderViewModel.kt:589-592`) until scrolling makes the page warm; (b) `observePageView` bails on null store/source (`ReaderViewModel.kt:2091-2094`); (c) a possible `resolvePageKey` mismatch the in-code diagnostic hints at (`ReaderViewModel.kt:2118-2120`). The bug is confirmed real by the in-code diagnostic at `ReaderViewModel.kt:2096-2099` ("Remove once the display-path bug is fixed").

Inside `setImage()` at line 303, instead of checking `page.translatedStream` (which hasn't been attached yet), perform a synchronous stream attachment:
```kotlin
// Eagerly resolve the translated stream before deciding which image to show,
// so a pre-translated page doesn't briefly flash the original.
if (page.translatedStream == null && showTranslations) {
    viewer.activity.viewModel.attachTranslatedStreamForPage(page)
}
page.showTranslatedImage = showTranslations && page.translatedStream != null
```
This ensures that when `setImage()` runs from `loadPageAndProcessStatus`, the translated stream is already available (if the page is in the warm window and has a rendered result), eliminating the double-decode entirely. The page goes straight from READY → translated image in a single decode pass.

### Resolution (implemented — Track D)
**DONE.** `ReaderViewModel.attachTranslatedStreamForPage(page)` added as a public wrapper around `attachTranslatedStreamIfWarm`, and called eagerly at the top of `setImage()` in both `PagerPageHolder` and `WebtoonPageHolder` when the translated stream is null and translations are enabled. The eager attach is main-thread safe: the stream factory is lazy (`TranslationManager.getRenderedImageStream` / `getCleanedImageStream` return `(() -> InputStream)?`), so no disk I/O happens at attach time — only when Coil invokes the lambda on its decoder thread.

---

## 17. Resume & Redundancy Audit (2026-06-22)

A deeper audit of the batch + reader paths found that the pipeline recomputes expensive work it already finished, and that the batch queue is lost entirely on crash. These are the highest-impact performance gaps for pre-translation. Tracked as Tracks G/H/I/J in the audit-driven fixes spec.

### G. Neural inpaint re-runs when `.cleaned.png` already exists on disk
`inpaintPage` (`TranslationPipeline.kt:2270`) has **no** `cleanedImageName != null` short-circuit. The expensive neural inpainter (AOT-GAN, one of the 3 most expensive stages) re-runs on every resumed page even when the cleaned image is sitting on disk. Batch Stage 2 loops (`L837` non-AI, `L1133` AI) gate only on `translationStatus`, never on `inpaintStatus`/`cleanedImageName`. **Cost: EXPENSIVE.** Track G adds the short-circuit (requires `cleanedImageName != null && inpaintStatus == READY && hasCurrentInpaintResult`).

### H. AI planner re-translates already-translated blocks on resume
`TranslationContextChunkPlanner.plan()` at `L72` only skips blank *source* blocks — never checks `block.translation.isNotBlank()`. On a resumed AI batch, every already-translated block is re-sent to the LLM. **Cost: EXPENSIVE** (wasted LLM tokens + HTTP per finished block). Track H adds the skip filter; the edge case (all blocks already translated → empty chunk) must mark the page READY, not failed.

### I. Manual translate bypasses all resume logic
`TranslationExecutor.translateSinglePage` defaults `force = true` (`TranslationExecutor.kt:31`). The interface comment says this is "by design" — but the consequence is that a user tapping translate on a page auto-prefetch already completed burns a full re-OCR + re-translate + re-inpaint + re-render. The resume branches at `TranslationPipeline.kt:1369-1543` are effectively dead for the manual path. Track I changes the default to `force = false` and keeps `force = true` only for an explicit "re-translate" affordance.

### J. Batch queue is in-memory only — lost on crash
`ChapterTranslator.kt:131` — `_queueState = MutableStateFlow<List<Translation>>(emptyList())`. `ChapterTranslator` has no `init` block; on app launch the queue is always empty. **A crash mid-batch loses the entire queue.** Track J adds a `TranslationQueueStore` mirroring the existing `DownloadStore` pattern (SharedPreferences file `"translation_queue"`, ordered list of chapter ids, rehydrated via `Translation.fromChapterId` on launch). Rehydrated entries get `status = QUEUE` — the user must tap Start to resume (no auto-start of background OCR/LLM work on launch). The `chapters` SQLDelight table is NOT modified (its UPDATE triggers would cause version/sync churn).

### Doc/code drift also found
`docs/TRANSLATION_MODULE.md` contract #14b claims "render gates admit READY and PARTIAL alike," but the single-page gate at `TranslationPipeline.kt:1641` was strict `== READY`. **Resolved by Track C** — the gate now admits `READY || PARTIAL`.

---

## 18. Inpainting Quality Audit (2026-06-22)

An owner-provided artifact image showed visible defects on small/tight speech bubbles: sharp/angular corners, over-erasure/halos, and (worst on small bubbles) screentone mismatch. Tracked as Tracks K1/K2/K4s in the audit-driven fixes spec.

### Docs contradict the code on rounded-corner masking
`docs/TRANSLATION_MODULE.md` contract #16 implies rounded-corner masking is applied. **It is dead code.** `BubbleMaskBuilder.roundedAllowedMask` (`BubbleMaskBuilder.kt:24`) and `insideRoundedRect` (`BubbleMaskBuilder.kt:78`) have **zero production call sites** — only referenced in `BubbleMaskBuilderTest`. Every erase mask in both the FAST and neural paths is a sharp rectangle softened only by a box-blur chamfer. This directly explains the "corners too sharp" symptom.

### K1. Corner rounding (sharp/angular corners)
Two causes: (a) the rounded-corner masking described above is dead code; (b) `BubbleMaskBuilder.dilateMask` (`:175-200`) is a 4-neighbourhood (Manhattan-diamond) grower producing 45° chamfers, and the neural path's `AOTInpainting.dilateMask` (`:717-751`) uses a square (Chebyshev) kernel. Track K1 switches dilation to a disk structuring element and wires in `roundedAllowedMask` on the FAST path. **Easy, low-risk** — pure morphology, existing tests cover it.

### K2. Scale morphology by bubble size (over-erasure on small bubbles)
All morphology constants in `SmartBubbleTextCleaner` are **absolute pixels**: `featherRadius=6`, `dilationIterations=3`, `mp≥8` (`:14-24`). On a 40px bubble the 6px feather ring consumes ~15% of each side → over-erasure/halo. Track K2 makes them functions of `min(bubbleW, bubbleH)`. **Easy, low-risk.**

### K4-small. Small bubbles force-routed to flat-fill before the neural path
`AOTInpainting.inpaintRegions` (`:223-249`) force-routes small boxes (`pageArea/200 ≈ 128×128`) to the flat-fill `cleanRegions` path *before* `isFlatBackgroundRegion` can route them to the neural model — regardless of QUALITY mode. So a user in QUALITY mode expecting neural reconstruction on a small bubble silently gets the flat color-average fill, which destroys screentone (averaging dots → flat gray). Track K4s reorders the checks so `isFlatBackgroundRegion` runs first and gates the small-box bypass on the memory budget (`TranslationMemoryBudget.canRunNeuralInpaint`) instead of absolute area.

### Out of scope (owner decision)
- **K3 — organic bubble-shape masks for the neural path.** The proper fix for rectangular borders, but deferred — K1's corner rounding handles the visible symptom.
- **K4-general — patch-based texture synthesis for screentone.** Fundamentally hard (averaging destroys dot frequency); the owner accepted the limitation. Only K4-small is in scope.
- **"Render source under translation" escape hatch** — would reverse the deliberate no-source-fallback design (contract #14b). Rejected.
