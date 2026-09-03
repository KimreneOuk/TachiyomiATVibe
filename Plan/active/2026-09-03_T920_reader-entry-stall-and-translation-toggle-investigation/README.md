# Task T920 — Reader Chapter Entry Latency, 200-Page Stall/Exit, and Auto-Translation Drawer Toggle Freeze Investigation

## Status

INVESTIGATION ONLY. No production or test code modifications are authorized.

## Director Report & Problem Statement (2026-09-03)

Symptoms described:
1. **Slow Chapter Entry Latency & 200-Page Exit**:
   - Entering a chapter in the reader page has noticeable latency.
   - If a chapter has 200 fully translated pages, it simply stalls and exits from the reader back to the manga details view.
2. **Translation Drawer Toggle Freeze / Crash**:
   - Opening the translation drawer in the reader and attempting to turn off auto-translation causes the app to freeze (ANR), exit, or crash.
3. **Director's Hypothesis & Architectural Proposals**:
   - "My hypothesis is that it is intensively trying to do write i/o all at once."
   - "For reader, when first opening the chapter why not just load and calculate text render overlay on a wide area in a safe performance of the page we are loading on to view? Then do a slow/lazy load/render as user scroll. We also need to counter for cases where user do a rapid scroll."

## Objective

Conduct a comprehensive, evidence-backed technical investigation into:
1. Why entering a chapter into the reader experiences high latency.
2. Why a chapter with 200 fully translated pages stalls and exits from the reader (tracing `loadChapter`, `DownloadPageLoader`, `LegacyChapterMigrationSource`, SAF directory traversals, 400x JSON deserializations, and `ReaderActivity.setInitialChapterError` exit triggers).
3. Why toggling off auto-translation in the reader drawer freezes (ANRs) or crashes the app (tracing `setAutoTranslate`, `cancelAutoTranslations`, `markChapterCancelledSync`, `runBlocking`, and `ChapterTranslationStore.mutex` / atomic write chains).
4. Evaluate the Director's write I/O hypothesis against primary code evidence.
5. Formulate architectural options for lazy loading, background text layout planning off the UI thread, sliding window rendering, and rapid scroll protection.

## Scope

- Reader Activity & ViewModel:
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt`
- Page Loaders & Artifact Migration:
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt`
  `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
  `app/src/main/java/eu/kanade/translation/artifact/LegacyChapterMigrationSource.kt`
  `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt`
  `app/src/main/java/eu/kanade/translation/artifact/ArtifactRetention.kt`
  `app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt`
- Scheduler & Auto-Translation Cancellation:
  `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt`
  `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt`
  `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
- Viewers & Layout / Rendering:
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonAdapter.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonLayoutManager.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt`
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt`
  `app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt`

## Constraints

- Android 8.0+
- Bounded memory
- Target devices have at least 6 GB RAM
- Preserve reader stability
- Normal manga must not regress because of specialized manhwa behavior
- Strictly no production code edits during this investigation task

## Deliverables

1. Technical Lead report: `Plan/active/2026-09-03_T920_reader-entry-stall-and-translation-toggle-investigation/engineering/code-investigation.md`
2. Architectural options: `Plan/active/2026-09-03_T920_reader-entry-stall-and-translation-toggle-investigation/engineering/architecture-options.md`
3. Main Leader Executive Summary & Synthesis for the Director
