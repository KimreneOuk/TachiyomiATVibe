# Task T912 — Slow Image Rendering and Delayed Cleaned Image/Overlay Transition Investigation

## Status

INVESTIGATION ONLY. No production or test code changes are authorized.

## Director report (2026-09-01)

Symptom described:
1. When opening a fresh chapter that has been fully or partially translated:
2. It takes a long time to load an image that has already been saved / cleaned on disk.
3. Once it loads, it first loads the original image and takes quite a long time to fully switch to the cleaned image and the translated text overlay.

Director requests a deep, systematic code investigation:
- Trace the actual code flow before, during, and after page load in the Reader.
- Identify why a translated/cleaned chapter page does not immediately load the cleaned image from disk.
- Identify why the original image is displayed first and why the transition to cleaned image + translated text overlay has a noticeable delay / lag.
- Produce an evidence-backed report detailing Symptom, Root Cause, Proposed Solution, and Decision Criteria.

## Objective

Determine the current committed behavior, execution flow, thread hops, caching layers, and root causes for:
1. Why opening a fresh pre-translated chapter exhibits slow initial page loading.
2. Why the reader loads and renders the original raw image first even when the cleaned image and translation metadata are already persisted on disk.
3. Why switching from the original image to the cleaned image and binding the text overlay takes a significant amount of time.
4. Any architectural, caching, lifecycle, or decoding bottlenecks in `ReaderPage`, `ReaderViewModel`, `TranslationManager`, `PagerPageHolder`, `WebtoonPageHolder`, `ReaderPageImageView`, `SubsamplingScaleImageView`, Coil, and translation store reconciliation.

## Required investigation

Trace the complete lifecycle from chapter load / page creation to full translated rendering:
1. **Chapter & Page Initialization**:
   - How `ReaderChapter` and `ReaderPage` are created when opening a chapter.
   - When and how translation metadata / cleaned image presence is discovered and populated into `ReaderPage`.
   - Initial state of `ReaderPage.showTranslatedImage`, `stream`, `translatedStream`, `translationStatus`.
2. **Image Loading & Decode Pipeline**:
   - How `PagerPageHolder` and `WebtoonPageHolder` initiate image loading (e.g. `setImage()`, `PageLoader`, `ReaderPageImageView`, `SubsamplingScaleImageView`, Coil decoder).
   - What image stream is resolved at initial `setImage()` vs after translation state updates.
   - Cache behavior: memory cache, disk cache, bitmap recycling, tile decoding.
3. **Translation State Discovery & Notification**:
   - How and when `ReaderViewModel` / `TranslationManager` checks disk storage for pre-translated chapters/pages.
   - Is storage check synchronous, asynchronous (coroutine/flow), or on-demand when page is active?
   - How many disk I/O operations / JSON deserializations / bitmap existence checks occur per page or chapter.
4. **Cleaned Image Switch & Overlay Binding**:
   - The trigger mechanism that transitions `ReaderPage.showTranslatedImage` from false to true.
   - What happens inside the view holder when `showTranslatedImage` changes: does it tear down the entire `SubsamplingScaleImageView` / tile decoder and re-decode from disk?
   - How the overlay layout (`ReaderTranslationOverlayBinding`, `TranslationOverlayView`) is measured, scaled, and rendered.
   - Threading model: which dispatchers (Main, IO, Default) are used, where blocking I/O or JSON parsing might be scheduled, and where race conditions or thread context switching overhead occurs.

## Edge cases to cover

- Fully pre-translated chapter (all pages have cleaned images & JSON translations on disk).
- Partially pre-translated chapter (some pages translated, some not).
- Online streaming vs offline downloaded chapters.
- Pager viewer (Single / Double page) vs Webtoon viewer (continuous vertical scroll).
- Rapid scrolling / page flipping across multiple pre-translated pages.
- Memory pressure, large image sizes (high-res manhwa/webtoon images), tile decoder initialization cost.
- Android 8.0+, bounded memory constraints, target devices with at least 6 GB RAM.

## Deliverables

- Repository Steward: `REPO_HEALTH.md`
- Technical Lead: `engineering/code-investigation-slow-image-rendering.md`
- Reviewer / Auditor: `review/failure-mode-and-performance-audit.md`
- Main Leader synthesis: Executive report to the Director.
