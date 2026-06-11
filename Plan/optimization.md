# Manga Translation Optimization Plan

## Goals

- Reduce cases where detected text regions are not cleaned/inpainted.
- Add an explicit `Quality` / `Fast` inpainting mode choice.
- Improve real and perceived translation speed while keeping the implementation readable.
- Allow page-by-page reading while a chapter is still being translated.
- Add reader UX to start, cancel, and monitor translation without leaving the reader.
- Optimize for Android phones around 6 GB RAM while respecting Android app heap limits.
- Use CPU efficiently now, and defer GPU/NNAPI until provider support is verified and benchmarked.
- Avoid OpenCV dependency work in the first batch unless Kotlin smart/blank mode proves insufficient.

## Current Pipeline Summary

- `ChapterTranslator.translateChapter()` currently decodes pages serially, calls `recognitionEngine.recognize(bitmap)`, saves `cleaned.png`, later reloads it for rendering, translates all pages in one batch, renders all pages, and writes chapter JSON only at the end.
- `PageRecognitionEngine.recognize()` always runs `analyze()` followed by `inpaint()`.
- `OnnxPageRecognitionEngine.analyze()` runs bubble/text detection, OCR, dedupe/filtering, and stores only final `TranslationBlock`s.
- `OnnxPageRecognitionEngine.inpaint()` builds inpainting boxes from final blocks only, then calls `AOTInpainting.inpaintRegions()`.
- `AOTInpainting` already has two useful paths: smart/blank fill via `SmartBubbleTextCleaner` and neural AOT for free-text textured regions.
- `ChapterTranslationStore` already supports `updatePage()` and exposes `StateFlow<Map<String, PageTranslation>>`, but it is not wired into the translator loop or reader.
- `TextTranslator.translatePage()` already exists but is unused.
- `ReaderPage` already supports `translation`, `translatedStream`, and `showTranslatedImage`.
- `PagerPageHolder` and `WebtoonPageHolder` already switch streams when `readerPreferences.showTranslations()` changes.
- `DownloadPageLoader.getPages()` snapshots translation JSON once when opening the chapter, so the reader does not see pages translated after it was opened.
- The manga chapter list has the only translate entry point today: `ChapterTranslationIndicator` calls `MangaScreenModel.runChapterTranslationActions()`, which calls `TranslationManager.translateChapter(manga, chapter)` for downloaded chapters.
- The reader bottom bar currently has no translation action; it only has reading mode, orientation, crop, and settings.
- Translation settings already live in `SettingsTranslationScreen`, which is the correct home for quality/fast mode because it affects generated output globally.
- Pages and chapters are processed serially; ONNX sessions, smart-cleaner buffers, and mutable bitmaps are not currently safe for unbounded parallel page processing.

## First Implementation Batch

Included now:

- Reader-side translation start/cancel UX.
- Page-by-page translation persistence.
- Live reader replacement as pages finish.
- Translation settings selector for `Quality` vs `Fast` inpainting mode.
- Fast mode routing that skips AOT neural inpainting.
- Low-risk speed improvements: render from in-memory cleaned bitmap, smaller quality-mode AOT crops, and bounded execution.

Deferred:

- OpenCV dependency/integration.
- ONNX GPU/NNAPI execution provider work.
- Aggressive multi-page ONNX parallelism.
- Full cost/quality selector for per-page vs whole-chapter LLM batching.
- Reader support for translating non-downloaded HTTP chapters.

## UX Model

Target flow:

1. User chooses the global inpainting mode in Settings > Translation.
2. User opens a downloaded chapter in the reader.
3. Reader bottom bar shows a translation control next to the existing reading-mode/orientation/crop/settings controls.
4. If the chapter is not translated, tapping the control starts translation for the current chapter immediately using the selected inpainting mode.
5. The current page keeps showing the original image until its translated image is rendered.
6. As each page finishes, the reader updates `ReaderPage.translatedStream` and swaps the visible page if `showTranslations` is enabled.
7. Pages that are not finished continue showing originals, so the user can keep reading.
8. Page `n` can become translated while the user is reading it; page `n + 1`, `n + 2`, etc. switch automatically when their outputs arrive.
9. If the user jumps ahead, any untranslated page still opens normally and will replace itself later when ready.
10. Tapping the translation control while translating opens cancel/progress behavior, matching chapter-list indicator semantics.
11. If translations are hidden via reader settings, pages do not visually swap, but translated streams are prepared for instant toggle.

Recommended reader control states:

- `NOT_TRANSLATED`: translate icon, tap starts current chapter translation.
- `QUEUE`: queued indicator, tap opens/cancels queue action.
- `TRANSLATING`: spinner or progress text, tap opens cancel action.
- `PARTIAL` if implemented: filled/half-filled translate icon with progress, tap can continue/cancel/delete depending state.
- `TRANSLATED`: filled translate icon, tap toggles show translations or opens delete/retranslate menu.
- `ERROR`: error translate icon, tap retries.

Recommended status/progress display:

- Show lightweight progress in the reader app bar or bottom bar, for example `Translating 4/22`.
- Count a page as available when it has `renderedImageName` or, as fallback, `cleanedImageName`.
- Avoid noisy toast per page; use subtle inline progress.
- A toast/snackbar is acceptable when the current page first becomes translated, but not for every page.

## Settings UX For Quality/Fast Mode

Placement:

- Put the selector in `SettingsTranslationScreen`, inside the existing Translation settings tab/category.
- Preferred location: Translation > Setup, directly below source/target language.
- Title: `Inpainting mode`.

Options:

- `Quality`: best-quality pipeline. Bubble speech uses smart/blank cleaning; free text on textured art can use AOT neural inpainting.
- `Fast`: smart/blank cleaning for all regions. Skips AOT neural inpainting to reduce wait time and memory pressure.

Default:

- Default to `Quality` to preserve current behavior for existing users.

Reader behavior:

- Do not duplicate the quality/fast selector in reader settings.
- Reader translate action uses the global Translation setting automatically.
- Optional reader UI can show active mode as read-only text, for example `Mode: Quality`, with a shortcut to Translation settings later.
- Keep existing reader setting `Show translations` separate because it controls display, not translation generation.

## Main Speed Optimizations

1. Page-by-page pipeline

- Biggest perceived-speed win.
- Do not wait for whole chapter recognition, translation, rendering, and final JSON write.
- Process each page through decode -> OCR/inpaint -> translate -> render -> persist -> reader refresh.
- Reader can start showing translated page `n` while page `n + 1` is still processing.

2. Fast inpainting mode

- Biggest compute-speed win.
- `FAST` skips AOT neural inpainting entirely.
- Use smart/blank fill for all bubble, free-text, flat, and textured regions.
- Avoids AOT tensor allocation, neural inference, feather blending over large crops, and high peak memory.

3. Avoid cleaned PNG round trip

- Current flow saves `cleaned.png`, later decodes it again for rendering.
- Render from the in-memory cleaned bitmap immediately, then save `rendered.webp`.
- Keep cleaned image output initially only where compatibility requires it.
- Reduces disk IO, PNG compression/decompression, bitmap allocations, and GC pressure.

4. Cluster AOT crops in quality mode

- Current quality path can union scattered free-text boxes into one huge crop.
- Huge crops are slow and often skipped by the memory gate.
- Cluster nearby free-text boxes and run AOT per cluster.
- Improves quality and speed by keeping inference windows small.

5. Reuse buffers and reduce allocations

- Reuse pixel arrays, mask arrays, tensor buffers, and paint/layout helpers where safe.
- Avoid per-box `IntArray(localW * localH)` allocations.
- Keep reusable buffers single-thread-confined or protected by a mutex.
- Goal: fewer GC pauses and lower peak heap.

6. Bounded parallelism

- Do not use unbounded `Dispatchers.IO` for image/ONNX work.
- ONNX sessions already use multiple CPU threads internally.
- Use a small translation dispatcher/semaphore with explicit limits.
- Safe first target: one ONNX page pipeline at a time, plus one or two lower-risk IO/render/compression tasks.

7. Hardware acceleration path

- Current project uses `onnxruntime-android`; no explicit GPU/NNAPI execution provider is wired.
- First optimize CPU pipeline and memory because it is predictable and clean.
- Add optional ONNX Runtime NNAPI provider later only after checking docs and benchmarking.
- GPU/NNAPI should be feature-detected and fallback-safe because Android support varies heavily by SoC, Android version, model ops, and precision.

## 6 GB RAM Target

Assume a 6 GB phone still gives the app a much smaller Android heap than physical RAM. Optimize for heap budget, not total device RAM.

Recommended runtime policy:

- Treat 6 GB RAM devices as eligible for full-resolution decode when the raw page fits the heap budget.
- Keep neural inpainting peak memory below a conservative per-page budget.
- Use `TranslationMemoryBudget` to gate decode sample size and neural crop execution.
- Prefer multiple small AOT crops over one large crop.
- Release/recycle bitmaps promptly after each page stage.
- Avoid keeping multiple full-page ARGB bitmaps alive at once.

Suggested defaults for 6 GB devices:

- ONNX pipeline concurrency: `1` page.
- Render/compress concurrency: `1-2` pages max.
- AOT neural crop max dimension: keep current `768` initially.
- Fast mode: no AOT neural, allowing smoother reading while translating.
- Quality mode: AOT enabled only for clustered textured free text and only if memory gate passes.

## CPU/GPU Utilization Strategy

CPU now:

- Use CPU aggressively but predictably.
- Keep ONNX intra-op threads bounded. Current `OnnxRuntimeProvider` uses `min(availableProcessors, 4)`.
- Avoid running multiple ONNX page pipelines concurrently unless benchmark logs prove it helps.
- Use CPU cores for overlapping IO/render after ONNX finishes a page.
- Tune with real timing logs instead of hardcoding high thread counts.

Clean implementation pattern:

- Add a small `TranslationExecutionPolicy` or equivalent helper later if limits grow.
- Centralize limits: ONNX concurrency, render concurrency, max inference dim, and memory thresholds.
- Avoid scattering `limitedParallelism()` and magic numbers across call sites.

GPU/NNAPI later:

- Do not assume GPU acceleration is automatically faster.
- Add a hardware acceleration preference later if needed: `Auto`, `CPU`, `NNAPI`.
- In `Auto`, attempt NNAPI only when available and supported.
- Fall back to CPU on session creation failure or poor compatibility.
- Log provider used per model/session.
- Benchmark detector, OCR, and AOT separately because different models may benefit differently.

Documentation needed before GPU/NNAPI work:

- ONNX Runtime Android execution provider APIs for the exact dependency version.
- Android NNAPI provider support and limitations.
- Device-specific behavior for target phones/chipsets.

## Implementation Order

### 1. Add Inpainting Mode Preference

Files:

- `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt`
- New enum/config under `app/src/main/java/eu/kanade/translation/inpainting/`

Steps:

- Add `translationInpaintingMode()` preference, defaulting to `QUALITY`.
- Add `Inpainting mode` list preference under Settings > Translation > Setup, below language selection.
- Entries: `Quality`, `Fast`.
- Keep reader settings unchanged; reader uses this global translation setting automatically.

### 2. Thread Mode Into Recognition/Inpainting

Files:

- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- `app/src/main/java/eu/kanade/translation/recognizer/OnnxPageRecognitionEngine.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/AOTInpainting.kt`

Steps:

- Pass selected mode when creating `OnnxPageRecognitionEngine`.
- Store mode in `OnnxPageRecognitionEngine` and pass it to `AOTInpainting.inpaintRegions()`.
- In `FAST`, skip `inpaintFreeRegions()` and route all free-text regions through `SmartBubbleTextCleaner`.
- In `QUALITY`, preserve current AOT behavior initially.

### 3. Convert Translation To Page-By-Page Persistence

Files:

- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
- `app/src/main/java/eu/kanade/translation/translator/TextTranslator.kt`

Steps:

- Open/create the chapter translation file at the start of `translateChapter()`.
- Use `ChapterTranslationStore.open(file)` as the per-page persisted store.
- For each page, process recognition/inpaint, call `textTranslator.translatePage(fileName, pageTranslation)`, render the page, then `store.updatePage(fileName) { pageTranslation }`.
- Preserve final `translation.status = TRANSLATED` only after all pages complete.
- Keep status fields updated per page: OCR, inpaint, translation, render.

API-call tradeoff:

- Per-page translation greatly improves reader UX but may increase API calls for Gemini/OpenRouter/DeepSeek and can reduce context consistency across pages.
- Add a preference later if needed: `translationBatchMode = PAGE_BY_PAGE | WHOLE_CHAPTER`.
- Recommended first behavior: use page-by-page because the reader UX depends on it.

### 4. Render Without Reloading Cleaned PNG

Files:

- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- `app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt` if needed only at call site.

Steps:

- After `pageTranslation.cleanedBitmap` is produced, translate the page and render directly onto that bitmap.
- Save `rendered.webp` immediately.
- Continue saving cleaned image initially for compatibility if current reader paths require it.
- Avoid decoding cleaned PNG again in the same translation run.

### 5. Live Reader Translation Store Observation

Files:

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` only if a new event is required.

Steps:

- Add/update helper in `DownloadPageLoader` to resolve rendered/cleaned streams for an existing `ReaderPage`.
- In `ReaderViewModel`, collect the current downloaded chapter's `ChapterTranslationStore.state`.
- On updates, match page file keys to `ReaderPage`s and set `page.translation` plus `page.translatedStream`.
- Trigger targeted refresh for affected pages by re-emitting `Page.State.READY` or adding `TranslationPageReady(pageIndex)` if needed.
- Do not full reload the viewer unless targeted refresh is not enough.

Real-time replacement sequence:

1. Translator finishes render for page key `003.png` and writes `renderedImageName` through `ChapterTranslationStore.updatePage()`.
2. Reader store collector receives the new map.
3. Reader finds `ReaderPage` with the same source file key.
4. Reader updates `page.translation` and `page.translatedStream` to the rendered image stream.
5. If `readerPreferences.showTranslations()` is true, the holder refreshes and `ReaderPage.stream` now resolves to the translated image.
6. If page `003.png` is not visible, it simply becomes ready; when user navigates to it, it opens translated immediately.
7. Repeat for page `004.png`, `005.png`, and so on as they complete.

### 6. Add Reader Translation Action

Files:

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt`
- `app/src/main/java/eu/kanade/presentation/reader/appbars/BottomReaderBar.kt`

Steps:

- Inject/use `TranslationManager` in `ReaderViewModel`.
- Add `startCurrentChapterTranslation()` and `cancelCurrentChapterTranslation()`.
- Track current chapter translation state from `TranslationManager.queueState` or `statusFlow()`.
- Add a translate button to the reader bottom bar.
- States: not translated, queued/translating, translated, error.
- For non-downloaded HTTP chapters, hide or disable with a short message; current translation pipeline requires downloaded/local pages.

### 7. Reduce Missed Inpainted Regions

Files:

- `app/src/main/java/eu/kanade/translation/recognizer/OnnxPageRecognitionEngine.kt`
- `app/src/main/java/eu/kanade/translation/model/PageTranslation.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/SmartBubbleTextCleaner.kt`

Steps:

- Retain pre-OCR filtered detector text boxes.
- Use retained boxes for inpainting along with final OCR blocks.
- Expand text boxes slightly before cleaning.
- Deduplicate combined boxes geometrically.
- Keep tuning conservative to avoid false-positive erasure.

### 8. Quality-Mode Crop Clustering

Files:

- `app/src/main/java/eu/kanade/translation/inpainting/AOTInpainting.kt`
- `app/src/main/java/eu/kanade/translation/util/TranslationMemoryBudget.kt`

Steps:

- Cluster nearby free neural boxes instead of unioning all into one crop.
- Run existing memory gate per cluster.
- Keep `maxInferenceDim = 768` initially.
- Log cluster count, crop sizes, and skipped clusters.

### 9. Bounded Execution Policy

Files:

- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- `app/src/main/java/eu/kanade/translation/onnx/OnnxRuntimeProvider.kt`
- Optional small helper under `app/src/main/java/eu/kanade/translation/util/`

Steps:

- Keep ONNX page pipeline serial for this batch.
- Do not add multi-page ONNX concurrency.
- If render/compress parallelism is added, bound it to `1-2` tasks and ensure bitmap ownership is clear.
- Keep ONNX intra-op threads capped as currently done unless benchmark logs indicate otherwise.

## Translation Queue and Progress Semantics

- Keep chapter status as `TRANSLATING` until all pages are complete.
- Let the reader derive page progress from the live translation store count.
- Avoid treating partially translated chapters as fully translated merely because the JSON file exists.
- Add a chapter-level completion marker or derive completion from all page `renderStatus/translationStatus` values and known page count.
- If adding a marker is too invasive initially, keep the public chapter status as `TRANSLATING` while active and only set `TRANSLATED` at completion.
- Consider `PARTIALLY_TRANSLATED` later only if the chapter list UI needs a distinct badge.

## OpenCV Decision

- No OpenCV usage was found in the current translation pipeline.
- First implement `FAST` using the existing Kotlin smart/blank cleaner.
- Treat OpenCV Telea/Navier-Stokes as a later optional backend behind the same mode abstraction.
- Reason: adding OpenCV affects APK size, native ABI packaging, initialization, and build complexity.

## Verification

Run:

- `gradlew.bat :app:compileDebugKotlin`

Manual checks:

- Settings > Translation shows `Inpainting mode` with `Quality` and `Fast`.
- Changing the mode affects newly started translations from both the manga chapter list and reader translate action.
- Starting translation from chapter list still works.
- Starting translation from reader works for downloaded chapters.
- Reader page `n` swaps to translated image as soon as its render output is written.
- Page `n + 1` and onward continue showing originals until each page becomes ready.
- Both pager and webtoon viewers support live replacement.
- Toggle `Show translations` while a chapter is actively translating and verify translated streams are retained.
- Cancel from reader works and does not delete already finished partial pages.
- Partial translation does not incorrectly mark the chapter as fully translated unless all pages complete.
- `Fast` mode skips AOT neural path in logs.
- `Quality` mode still uses AOT for eligible free-text textured regions.
- No full viewer reload or position jump during live replacement.
- Compare logs for decode, detection, OCR, inpaint route counts, render save time, API call count, heap snapshots, first translated page latency, and total chapter time.

Validation cases:

- Bubble text on white background.
- Bubble text on non-white colored background.
- Free text over textured art.
- Sparse free text far apart on a page.
- OCR failure or blank OCR text where detector still finds a text box.
- Long-strip webtoon page with forced sample size.
- Low-memory conditions on a 6 GB RAM target device/emulator.

## Risks

- Per-page API calls may increase cost/rate-limit pressure for LLM translators compared with batch chapter translation.
- Page-by-page translation can reduce cross-page wording consistency.
- Store updates for every page rewrite the JSON file; acceptable for typical chapter sizes, but can be optimized later with throttling or atomic writes if needed.
- Reader refresh events can disturb scroll/page position if implemented as a full viewer reload; prefer targeted page refresh.
- Automatically swapping the current visible image may be visually jarring; keep it controlled by `showTranslations` and consider a subtle progress indicator instead of animation.
- Reader translate action must handle non-downloaded HTTP chapters clearly because current translation requires downloaded/local source pages.
- Larger masks in fast mode may erase surrounding artwork.
- Raw detector boxes may clean false positives if confidence thresholds are too low.
- Parallel ONNX work can oversubscribe CPU and spike memory.
- Skipping or changing cleaned image output can affect reader paths that expect `cleanedImageName`.
- OpenCV integration is higher risk than a Kotlin fast path and should not be part of the first pass.
- NNAPI/GPU acceleration may be slower or unsupported on some devices and must remain optional with CPU fallback.

## Out Of Scope For This Batch

- NNAPI/GPU provider setup.
- OpenCV inpainting backend.
- Full cost/quality selector for per-page vs whole-chapter LLM batching.
- Reader support for translating non-downloaded HTTP chapters.
