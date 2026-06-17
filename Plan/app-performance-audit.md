# App Performance Audit — Entry Jitter & Manga-Detail Stutter

> **Status:** Findings document (audit + recommendations). No code changes yet.
> **Scope:** General app performance outside the translation pipeline. Focuses on the
> two symptoms reported by the user:
> 1. Jitter/stutter when entering the app.
> 2. More noticeable stutter when entering a manga to see its chapter list.
> **Audience:** Engineers deciding what to optimize and in what order.

## Purpose

`optimization.md` in this folder is entirely about the *translation* pipeline.
This document covers the *rest of the app* — startup, library grid, manga detail,
chapter list, cover loading. Every finding below was read directly in source and
verified against the code paths that produce the reported symptoms.

---

## How to read this document

Findings are **tiered by likely contribution to the reported symptoms**:

- **Tier 1** — directly explains "entry jitter" or "chapter-list stutter." Fix these first.
- **Tier 2** — recomposition/allocation churn that amplifies the above, especially
  under scroll or on data refresh.
- **Tier 3** — startup-path contributors; lower severity, but compound the entry window.

A section at the end maps each reported symptom to its root causes and the suggested
fix order.

---

## Tier 1 — High impact (fix first)

### F1. `Migrator.awaitAndRelease()` runs `runBlocking` on the main thread inside `MainActivity.onCreate`

- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt:130`
  ```kotlin
  val didMigration = Migrator.awaitAndRelease()
  ```
- `app/src/main/java/mihon/core/migration/Migrator.kt:38`
  ```kotlin
  fun awaitAndRelease(): Boolean = runBlocking { await().also { release() } }
  ```

`await()` suspends on a `Deferred<Boolean>` that completes when the migration job
(running on `Dispatchers.IO`, `Migrator.kt:13`) finishes. Because `awaitAndRelease()`
is `runBlocking` and is called directly from `onCreate` (the main thread), **the main
thread is frozen for the entire migration duration**. The splash-screen
`setKeepOnScreenCondition` is only installed *after* this call returns
(`MainActivity.kt:255–260`), so on any post-update launch the app appears frozen
during migration. This is the single strongest cause of "jitter when entering the
app," and it is worst exactly when users are most likely to notice it (right after
an update).

**Why it matters most:** the cost is paid on the main thread, synchronously, before
the first frame can render. Every other optimization is irrelevant while this is
blocking.

**Suggested fix:** move the migration wait off the main thread. The migration itself
already runs on IO; the activity should `setKeepOnScreenCondition { ready }` (with
`ready` flipped when the migration `Deferred` completes) instead of `runBlocking` on
it. This is the same pattern the splash screen already wants to use.

---

### F2. Per-chapter disk IO (SAF traversal + full JSON decode) runs once per downloaded chapter, on every flow tick

This is a **TachiyomiAT-specific addition** and the most likely direct cause of the
manga-detail stutter.

- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt:573–583` —
  inside `toChapterListItems`, for every downloaded chapter:
  ```kotlin
  var translationState = Translation.State.NOT_TRANSLATED
  if (downloadState == Download.State.DOWNLOADED) {
      translationState = translationManager.getChapterTranslationStatus(
          chapter.id, chapter.name, chapter.scanlator, manga.title, manga.source,
      )
  }
  ```
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt:169–207` —
  `getChapterTranslationStatus` → `isChapterTranslated` → for **each chapter**:
  1. `provider.findTranslationFile(...)` (`TranslationProvider.kt:81`)
  2. which calls `findMangaDir` → `findSourceDir` → `getTranslationsDirectory()`
     followed by chained `.findFile(sourceDirName)` → `.findFile(mangaDirName)`
     → `.findFile(chapterFileName)` (SAF `findFile` = directory scan each time)
  3. `file.exists()` + `file.length()`
  4. **`Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())`** —
     fully parses the chapter's translation JSON just to check `.isNotEmpty()`

So a manga with 100 downloaded chapters does **100 SAF directory lookups + 100 full
JSON file decodes** every time the chapter list is built. And the list is rebuilt
repeatedly (see F3/F7):

- The combined flow at `MangaScreenModel.kt:173–188` emits on `downloadCache.changes`
  (which re-emits on app start, `DownloadCache.kt:75–76`) and on
  `downloadManager.queueState` and `translationManager.queueState`.
- `downloadCache.changes` also re-emits whenever `renewCache()` finishes
  (`DownloadCache.kt:374`), which can walk the entire downloads tree.

This runs on `Dispatchers.IO` via `launchIO`, so it doesn't *freeze* the UI thread —
but it produces a brand-new `List<ChapterList.Item>` (new object identities) every
emission, which forces the chapter `LazyColumn` to re-diff every row (the list items
do have stable keys, so it's not a full re-compose, but the diffing + the downstream
`chapterListItems` re-derivation below still cost a frame).

**Suggested fix:** this status is *durable* — it only changes when a translation is
started/finished/deleted for that chapter. Compute it once per chapter at list-load
time (or better: expose a single batched call `getChapterTranslationStatuses(manga,
chapterIds): Map<Long, State>` that does one directory listing of the manga dir, not
N SAF lookups). Then refresh only on `translationManager.statusFlow()` events
(see F7), which already carries the single changed chapter id — no need to re-walk
the whole list. The full JSON decode is especially wasteful; a chapter is translated
iff its translation file has ≥1 page, but you don't need to parse the whole map to
know that — check `file.length()` against a small threshold, or persist a 1-byte
"completed" sentinel/marker.

---

### F3. `chapterListItems` (with separator insertion + sort) is re-derived on every state copy

- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt:1215–1248`
  ```kotlin
  val processedChapters by lazy { chapters.applyFilters(manga).toList() }
  val chapterListItems by lazy {
      processedChapters.insertSeparators { ... calculateChapterGap(...) ... }
  }
  ```
  Both are `lazy` **per `State.Success` instance**. Because `State.Success` is a
  `data class` and `updateSuccessState { it.copy(...) }` creates a new instance on
  every change, these lazy derivations are recomputed on every state copy.

- They are recomputed in particular on **every download-progress event**
  (`updateDownloadState`, `MangaScreenModel.kt:541–553`) and **every translation
  status event** (`updateTranslationState`, `527–539`). Download progress fires
  frequently during an active download — each tick mutates one chapter's state and
  re-triggers `applyFilters` (a `Sequence` filter + sort over all chapters) and
  `insertSeparators` (allocating new separator wrapper objects for the whole list).

**Suggested fix:** the only thing a download-progress tick changes is one chapter's
`downloadProgress`. Derive the filtered+sorted list with `derivedStateOf` keyed on the
actual inputs (manga flags + the chapters list identity), and update progress in a
way that doesn't allocate a new `chapters` list (or accept the re-derive but move it
off the hot path — e.g. only re-sort when the sort key actually changes). At minimum,
don't re-run `applyFilters` + `insertSeparators` on every progress percentage point.

---

### F4. Coil `ImageLoader` has no memory or disk cache configured

- `app/src/main/java/eu/kanade/tachiyomi/App.kt:182–208` (`newImageLoader`):
  builder sets components, crossfade, `allowRgb565`, coroutine contexts — **but no
  `.memoryCache { }` and no `.diskCache { }`**.
- In `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt`,
  `imageLoader.diskCache` is therefore `null`, so `readFromDiskCache()` (line ~248)
  and `writeToDiskCache()` (line ~256) short-circuit to `null`.
- The OkHttp client has a 5 MiB `network_cache` (`NetworkHelper.kt:28–33`), **but
  `MangaCoverFetcher.newRequest()` forces `CacheControl.noStore()` for network reads**
  (`MangaCoverFetcher.kt:~195`), so OkHttp's cache does **not** backstop covers.

Net effect: the only thing keeping a cover in memory is Coil's default memory cache
(~25% of app heap), which is small and evicts fast during grid scroll. On entry,
when the library grid first lays out 20–30 covers, any memory-cache miss for a cover
that isn't backed by a local file goes straight to the network — a decode round-trip
per miss. This is the entry/scroll jitter on the library grid.

**Suggested fix:** add an explicit `diskCache { }` to the Coil `ImageLoader`
(`App.kt:182`), sized e.g. 50–100 MiB. This is the single biggest library-scroll win
and is low-risk. Optionally also set an explicit `memoryCachePolicy` and size.

---

## Tier 2 — Library screen recomposition/allocation churn

### F5. Library `LazyVerticalGrid` / `LazyColumn` items have no `key`

All three library views pass `contentType` but never `key`:
- `app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt:33–36`
- `app/src/main/java/eu/kanade/presentation/library/components/LibraryComfortableGrid.kt:32–35`
- `app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt:42–45`

Because `LibraryScreenModel` rebuilds the entire `LibraryMap` (new `LibraryItem`
instances, new `List`s) on every emission of the `combine`
(`LibraryScreenModel.kt:104–121`, which also fires on `downloadCache.changes`),
the Lazy list has no stable identity to diff against and must re-compose every
visible item + re-issue Coil requests. **Contrast with the chapter list, which
correctly keys by id** (`MangaScreen.kt:761–768`).

**Suggested fix:** `items(items, key = { it.libraryManga.id }, contentType = ...)`.
Low-risk, high-value.

---

### F6. Per-item `selection.fastAny { it.id == ... }` — O(n) scan inside every visible item

- `LibraryCompactGrid.kt:39`, `LibraryComfortableGrid.kt:38`, `LibraryList.kt:48`.

`selection` is a `PersistentList<LibraryManga>` passed down through
`LibraryContent → LibraryPager → each grid`. Every visible item, on every
recomposition, linearly scans the selection list. Individually small, but multiplied
across a key-less grid (F5) it amplifies recomposition cost.

**Suggested fix:** pass a `Set<Long>` of selected manga ids; membership becomes O(1).

---

### F7. `MangaChapterListItem` consumes provider lambdas during composition; `chapterListItems` rebuilds separators on every refresh

- `app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt:73–88` —
  `getSwipeAction(...)` is invoked twice unconditionally at the top of the composable,
  each calling `downloadStateProvider()`. The lambda is deferred elsewhere but
  consumed here in the body, so swipe actions are recomputed for every visible row on
  every recomposition.
- Combined with F3: each state copy rebuilds `chapterListItems` (separator wrappers),
  feeding the LazyColumn a new list to diff.

**Suggested fix:** hoist the swipe-action computation out of composition (compute
once per item state change), and ensure `chapterListItems` isn't rebuilt on
progress-only updates (see F3).

---

### F8. `LibraryItem` and `MangaCover` are unstable types passed into composables

- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryItem.kt:9–35` — `data class
  LibraryItem(..., private val sourceManager: SourceManager = Injekt.get())`. Captures
  a service reference, is not `@Immutable`/`@Stable`.
- `domain/src/main/java/tachiyomi/domain/manga/model/MangaCover.kt` — a plain
  `data class` with **no `@Immutable`**, yet it is the `coverData` argument to
  `MangaCompactGridItem` / `MangaComfortableGridItem` / `MangaListItem`.

Because these types are unstable, Compose cannot treat the parameter lists of the
item composables as stable, defeating **skipability** for the whole item subtree —
every parent recomposition re-runs the item body even when the item's data didn't
change. And `MangaCover` is newly allocated per item per emission (the grids build it
fresh), which forces the item to recompose rather than skip.

**Suggested fix:** annotate `MangaCover` and `LibraryItem` (or a presentation-layer
copy of it without the service reference) as `@Immutable`. This is a one-line-per-type
change with real recomposition savings once F5's keys are in place.

---

### F9. `getDownloadCount(manga)` is called per library manga when the download badge is on

- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt:358–383` —
  inside the `combine` with `downloadCache.changes`, when `prefs.downloadBadge` is
  true, every library manga triggers `getDownloadCount` → `DownloadCache.getDownloadCount`
  → `renewCache()` check. On `downloadCache.changes` re-emit (startup, every download
  batch, and after `renewCache` completes) the whole library is re-counted.

**Suggested fix:** gate the badge work behind a distinct change signal (don't recount
when nothing relevant changed), or accept the cost but ensure F5/F8 are fixed so the
resulting re-map doesn't trigger full recomposition.

---

## Tier 3 — Startup-path contributors (lower severity)

### F10. Eager singleton construction posted to the main executor in `AppModule`

- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt:143–152`:
  ```kotlin
  ContextCompat.getMainExecutor(app).execute {
      get<NetworkHelper>(); get<SourceManager>(); get<Database>(); get<DownloadManager>()
  }
  ```
  Runs during `Injekt.importModule(AppModule(this))` inside `App.onCreate`
  (`App.kt:99`). `NetworkHelper.client` is built eagerly (OkHttp + CloudflareInterceptor
  + DoH), and `AndroidSourceManager`/`ExtensionManager` begin loading extensions. It's
  posted (not synchronous), so it won't block `onCreate`'s return, but it contends for
  main-thread time during the entry window.

### F11. `DownloadCache` decodes a protobuf index on first access; `renewCache` can walk the whole tree

- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt:102–119` —
  `init { scope.launch { ... } }` reads `dl_index_cache_v3` and `ProtoBuf.decode...`
  on IO, triggered when `DownloadCache` is first injected (`MainActivity` resolves it
  via `injectLazy` at line 110, and `downloadCache.isInitializing.collectAsState()` at
  line 143 forces resolution). `renewCache()` (lines 303–380) can subsequently list
  the entire downloads tree on first library load if the index was missing/expired,
  and emits `notifyChanges()` at line 374 — which re-triggers F2, F3, F9.

### F12. Root-composable preference collectors + edge-to-edge re-application

- `MainActivity.kt:141–143` — `incognitoMode`, `downloadOnly`,
  `downloadCache.isInitializing` all `collectAsState()` at the root composable; any
  change re-runs the root composable. `downloadCache.isInitializing` is debounced 1s
  (`DownloadCache.kt:93`) so mostly benign, but the `LaunchedEffect(...)` at
  `MainActivity.kt:~152` re-calls `enableEdgeToEdge` on each change, adding avoidable
  root-level recomposition during entry.

---

## What is already correct (do not regress)

- **DB access is reactive, not N+1.** `GetLibraryManga.subscribe()` returns a `Flow`;
  `GetMangaWithChapters.subscribe` combines two flows; `GetChaptersByMangaId.await`
  is a single query. No `.awaitSingle()` in loops on these paths.
- **Chapter `LazyColumn` items have stable keys** (`MangaScreen.kt:761–768`).
- The library pager limits offscreen composition to ±1 page (`LibraryPager.kt:49–52`).
- `MangaScreen` derives chapters/list/isAnySelected via `remember(state)`
  (`MangaScreen.kt:267–273`) and uses `derivedStateOf` for scroll-driven alpha
  (`MangaScreen.kt:289–294`).
- `State.Success`, `ChapterList.Item`, and `ChapterList.MissingCount` are `@Immutable`
  (`MangaScreenModel.kt:1198, 1275–1294`).

---

## Symptom → root cause → fix map

| Symptom | Root cause(s) | Fix |
|---|---|---|
| Jitter when entering the app | F1 (`runBlocking` migration on main thread); F4 (no cover disk cache → network on grid layout); F10/F11 (eager init + DownloadCache warm-up) | Move migration wait off main (F1); add Coil disk cache (F4) |
| Stutter when entering a manga to see chapters | F2 (per-chapter SAF lookup + full JSON decode × N chapters, on every flow tick); F3 (filter+sort+separators re-derived per state copy, incl. every download-progress tick); F7 | Batch the translation-status lookup + cache it, refresh only on the single-chapter status event (F2); gate `chapterListItems` re-derivation so progress ticks don't re-sort (F3) |
| Library grid scroll/entry jitter | F4 (covers re-fetched from network); F5 (no Lazy keys); F8 (unstable item types → no skipping); F6 (O(n) selection scan) | Coil disk cache (F4); add keys (F5); `@Immutable` (F8); selection Set (F6) |

---

## Recommended fix order

Ordered by ratio of (symptom impact) ÷ (risk + effort).

1. **F4 — add Coil disk cache** (`App.kt:182`). One-location change, biggest
   library-scroll and entry-cover win, low risk.
2. **F1 — move `Migrator.awaitAndRelease()` off the main thread**
   (`MainActivity.kt:130`). Biggest entry-jank win, especially post-update. Use the
   splash `setKeepOnScreenCondition { ready }` pattern.
3. **F2 — batch + cache chapter translation status** (the TachiyomiAT-specific
   per-chapter disk IO). Biggest manga-detail win. Replace N SAF lookups + N JSON
   decodes with one directory listing + a cheap existence/completion check, and only
   re-evaluate the single changed chapter on `translationManager.statusFlow()`.
4. **F3 — stop re-deriving `chapterListItems` on download-progress ticks.**
   Eliminates the per-progress-percent filter/sort/separator allocation.
5. **F5 — add `key` to the three library `items()` calls.** Low-risk, high-value
   once F4/F8 land.
6. **F8 — annotate `MangaCover` / `LibraryItem` as `@Immutable`.** One-line-per-type
   recomposition win.
7. **F6 — selection as `Set<Long>`.** Small but free.
8. **F9, F7, F10, F11, F12** — address as follow-ups; they compound the above but
   are not primary causes.

---

## Appendix — files in scope

**Startup / entry**
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`
- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
- `app/src/main/java/mihon/core/migration/Migrator.kt`

**Library / home**
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryItem.kt`
- `app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt`
- `app/src/main/java/eu/kanade/presentation/library/components/LibraryComfortableGrid.kt`
- `app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt`

**Manga detail + chapter list**
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
- `app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt`

**Cover loading**
- `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt`
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`

**Downloads cache (warm-up + change signal)**
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt`

**Translation status (TachiyomiAT-specific manga-detail cost)**
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/translation/data/TranslationProvider.kt`

**Models**
- `domain/src/main/java/tachiyomi/domain/manga/model/MangaCover.kt`
