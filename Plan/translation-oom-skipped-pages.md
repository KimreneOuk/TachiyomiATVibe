# Translation OOM And Skipped Pages Plan

## Problem Summary

- Long chapters, especially chapters with more than 15 pages, can run out of memory during translation.
- Some pages are skipped or left unprocessed after decode, OCR, inpainting, translation, or render failures.
- Live reader updates can miss completed pages because the reader may observe a different translation store instance than the translator writes to.
- While reading during translation, the user currently has weak feedback about which page is being processed.
- New translated pages may feel locked until exiting and re-entering the chapter.
- The currently visible raw page must not be skipped just because it is already loaded in the reader while the translator is processing it.
- If one page fails or remains untranslated, the user has no direct per-image action to retry just that page from the reader.

## Root Causes Found

- `ChapterTranslator.translateChapter()` skips decode/OOM failures with `continue`, so no failed page record is persisted.
- Decoded page bitmaps are recycled on the happy path only; non-OOM recognition/fallback failures can bypass `bitmap.recycle()`.
- `cleanedBitmap` cleanup is not protected by a per-page `finally`, so exceptions after inpainting can retain large bitmaps longer than intended.
- `AOTInpainting.inpaintRegions()` starts with a full-page mutable ARGB copy, then neural crop processing adds multiple bitmap, float, and int buffers.
- `TranslationMemoryBudget.chooseDecodeSampleSize()` mostly budgets for decode, not the later OCR, inpaint, render, and serialization peak.
- `ChapterTranslationStore.updatePage()` rewrites the full chapter JSON after every page; cost grows with chapter length.
- Directory chapter pages are not naturally sorted, unlike archive pages.
- `TranslationManager.openActiveChapterTranslationStore()` can create a separate store from disk while `ChapterTranslator` writes to a local store, so live reader updates may not see changes.
- `ReaderViewModel.observeLiveTranslationStore()` only maps updates when `readerPage.translation?.sourceFileName` already exists, and it skips cleaned-only updates when rendered names are both null.
- The current live reader model tracks completed image outputs, but it does not expose a clear per-page `currentlyProcessing` state for visible page overlays.
- Reader-loaded raw image streams and translator input streams are independent enough in concept, but page-key matching bugs can make the currently viewed page appear skipped or never refreshed.

## Implementation Plan

### 1. Make Per-Page Processing Exception-Safe

- Refactor the page loop in `ChapterTranslator.translateChapter()` into a per-page helper.
- Wrap each decoded bitmap in `try/finally` so `bitmap.recycle()` always runs.
- Wrap `pageTranslation.cleanedBitmap` in `try/finally` so it is always recycled and nulled before the next page.
- Always call `BitmapPool.releaseAll()` in the per-page `finally`.
- Handle `OutOfMemoryError` per page: release pools, call GC, persist a failed page placeholder, then continue.

### 2. Persist Placeholder Records For Skipped Pages

- Add a helper that creates a failed lightweight `PageTranslation` with:
  - `sourceFileName`
  - `ocrStatus`, `translationStatus`, `inpaintStatus`, and `renderStatus` set to `FAILED` or `PENDING` as appropriate
  - `errorMessage`
  - image dimensions when available
  - no bitmap references
- If `decodePageBitmap()` returns null or throws OOM, write this placeholder through `store.updatePage(fileName)`.
- This makes skipped pages visible in the translation JSON instead of disappearing.

### 3. Reduce Decode And Inpainting Peak Memory

- Make `TranslationMemoryBudget.chooseDecodeSampleSize()` more conservative under low available heap.
- Add a retry path: if a page OOMs during recognition/inpainting, retry once at a larger sample size before marking it failed.
- In `AOTInpainting`, catch OOM per neural cluster and fall back to `SmartBubbleTextCleaner.cleanRegions()` for that cluster.
- Lower the neural inpaint memory gate for constrained heaps.
- After repeated OOMs in a chapter, automatically fallback to fast/smart cleaning for remaining pages while preserving the user's global mode preference for future chapters.

### 4. Fix Active Store Sharing For Live Reader Updates

- Move active store creation/registration into `TranslationManager`.
- Ensure `ChapterTranslator` uses the same active `ChapterTranslationStore` instance that `ReaderViewModel` observes.
- Register the store before processing the first page, even for newly created translation files.
- Ensure emitted store state never contains transient bitmap references.
- Remove or retain the active store only after translation finishes/cancels and no large transient data remains.

### 5. Fix Reader Page Mapping And Cleaned-Only Updates

- In `ReaderViewModel.observeLiveTranslationStore()`, match pages by stable source filename from the reader/download page, not only `readerPage.translation?.sourceFileName`.
- Do not `continue` just because rendered image names are both null; still check cleaned image changes.
- Count processed progress from persisted page entries and stage state, not only from rendered/cleaned file presence.
- Failed placeholder pages should count as processed but not translated.

### 6. Sort Directory Chapter Pages

- Apply the same natural case-insensitive filename sort to directory pages that archive pages already use.
- This prevents ordering mismatches and pages appearing skipped due to inconsistent page keys/order.

### 7. Reduce Store Persistence Churn

- Keep the current full JSON format for compatibility in the first fix.
- Before emitting/persisting each page, ensure transient bitmap fields are null.
- If memory pressure remains, add a second pass using batched/debounced writes or per-page sidecar JSON files.

### 8. Clear Long-Lived Working Buffers

- Add a cleanup method to `SmartBubbleTextCleaner` to clear retained working arrays.
- Call that cleanup method from `AOTInpainting.close()`.
- Add clear/release support to OCR direct buffer pools if feasible, and call it from `OnnxOcrEngine.close()`.

### 9. Add In-Reader Processing Overlay

- Add a lightweight per-page processing state to the live translation store or reader state.
- When the translator starts a page, persist or emit a state update before decode/OCR begins:
  - `sourceFileName`
  - `ocrStatus = RUNNING` or a dedicated current-stage value
  - `updatedAt`
- In the reader, if the visible `ReaderPage` source key matches the currently running page key, dim the raw image and show a centered circular indeterminate loading animation.
- The overlay should only appear when the page is actively being processed and no rendered/cleaned translated image is available yet.
- Once `renderedImageName` or `cleanedImageName` is available, remove the overlay and swap to the translated stream when `showTranslations` is enabled.
- If translation fails for that page, remove the spinner and optionally show a subtle failed state rather than blocking the raw image.
- Keep the overlay purely visual; it must not block page gestures, scrolling, or reader navigation.

### 10. Unlock New Translations Without Leaving Reader

- Treat the active store as the source of truth for live reader updates.
- Ensure the reader subscribes before or immediately after translation starts, even if the translation JSON file is newly created.
- When a page output becomes available, update the corresponding `ReaderPage.translation` and `ReaderPage.translatedStream` in-place.
- Trigger a targeted page refresh for the affected page holder instead of requiring a full chapter reload.
- Verify both pager and webtoon holders react to the stream change while staying on the same page.
- Avoid relying on chapter re-entry to reload translation JSON from disk.

### 11. Protect The Currently Viewed Raw Page From Being Skipped

- Ensure translator page processing uses stable page keys from the downloaded chapter file list, not reader object state.
- The page being viewed in the reader must remain eligible for translation even if it is already loaded as a raw image.
- When processing begins for the currently visible page, emit the running state so the overlay appears.
- If decode fails because the stream cannot be opened, retry by reopening the source page stream once before marking it failed.
- If the reader has an open stream for the same image, do not reuse or steal that stream; translator should open its own independent stream from `DownloadProvider`/archive reader.
- If the page is visible and an OOM occurs, retry with a larger sample size before writing a failed placeholder.
- Persist a failed placeholder only after retry attempts, so the page is not silently dropped.
- Preserve the raw image display while processing or retrying; never blank the page just because translation is running.

### 12. Add Per-Image Manual Translate/Retry Button

- Add a small button at the top-left of each visible image when that image is untranslated, failed, or has no translated output.
- Button states:
  - `Translate page`: page has no translation record or no rendered/cleaned output.
  - `Retry page`: page has a failed placeholder record.
  - `Processing`: page is currently running; show spinner/disabled state instead of allowing duplicate work.
- Tapping the button should enqueue only that page for translation, not the entire chapter.
- The page-specific translation action should use the same global translation settings as chapter translation, including Quality/Fast mode.
- If the chapter translation is already running, the page action should either prioritize that page next or mark it for retry after the current page finishes.
- Avoid launching a second ONNX pipeline concurrently; preserve bounded execution with one active page pipeline.
- If the page succeeds, update `ReaderPage.translation` and `ReaderPage.translatedStream` immediately and remove the button.
- If it fails again, keep the raw page visible and leave the retry button available.
- Only show this button when `showTranslations` is enabled or when the reader is in translation-aware mode; avoid cluttering normal raw reading if translations are intentionally hidden.
- Keep the button overlay lightweight and non-intrusive: top-left corner, small circular/rounded background, readable over bright/dark images.
- The button must not interfere with normal page gestures more than necessary; its tap target should be clear but not cover content heavily.

Implementation notes:

- Add a page-level translation entry point in `TranslationManager`/`ChapterTranslator`, for example `translatePage(manga, chapter, pageKey)`.
- Reuse the same per-page helper introduced for exception-safe chapter translation.
- Store page retry state in the same active `ChapterTranslationStore` so reader UI updates through the existing live observer.
- Page retry should overwrite only that page's translation record and companion images, leaving other translated pages untouched.
- If old failed companion files exist for that page, delete/replace only that page's cleaned/rendered images.

## Verification Plan

- Run `gradlew.bat :app:compileDevDebugKotlin`.
- Run `gradlew.bat :app:assembleDevDebug`.
- Test a downloaded 20+ page chapter on a 6 GB RAM Android phone.
- Confirm QUALITY mode completes without an app crash.
- Confirm pages that fail due to OOM/decode errors are written as failed records instead of missing from JSON.
- Confirm reader progress updates for every processed page.
- Confirm rendered/cleaned completed pages appear live in reader without chapter reload.
- While viewing a chapter during translation, confirm the currently processed raw page is dimmed with a centered circular loading animation.
- Confirm the spinner disappears when that page succeeds, fails, or is canceled.
- Confirm translated pages unlock while staying inside the reader; do not exit/re-enter the chapter.
- Confirm the currently visible raw page is translated when its turn arrives and is not skipped because it was already being viewed.
- Confirm the raw image remains visible underneath the dim overlay during processing and retry attempts.
- Confirm failed/untranslated pages show a top-left manual translate/retry button.
- Confirm tapping the per-image button processes only that image and updates it live in the reader.
- Confirm tapping the per-image button during an active chapter translation does not start a second concurrent ONNX pipeline.
- Confirm successful per-image retry removes the button and preserves other translated pages.
- Test a long-strip page and confirm decode sampling or neural fallback prevents crash.
- Inspect logs for memory snapshots, OOM fallback, per-page completion, and store update events.

## Expected Outcome

- Chapters no longer crash solely because they have more than 15 pages.
- Failed/OOM pages are represented as failed page records instead of missing or silently unprocessed.
- Large bitmaps are reclaimed deterministically after every page.
- Live reader updates observe the same active store as the translator and do not miss pages created during translation.
- Reader shows a clear dimmed-image processing overlay for the page currently being translated.
- New translated pages become available live without leaving and re-entering the chapter.
- The currently visible raw page remains part of the translation queue and is retried before being marked failed.
- Failed or untranslated individual pages can be manually translated/retried from the reader without rerunning the whole chapter.
