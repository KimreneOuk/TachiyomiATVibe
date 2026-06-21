# Data Flow & Architecture Patterns

> How data flows through the TachiyomiAT application, from user interaction to
> persistence, and through the automatic manga translation pipeline.

---

## 1. Clean Architecture Layers

The project follows a **layered architecture** with dependency inversion:

```
┌──────────────────────────────────────────────────┐
│              Presentation Layer                   │
│  (Compose UI, Activities, ViewModels/ScreenModels)│
│       Depends on: Domain (interactors)            │
├──────────────────────────────────────────────────┤
│              Domain Layer                         │
│  (Use cases/interactors, models, repository iface)│
│       Depends on: Nothing (pure Kotlin)           │
├──────────────────────────────────────────────────┤
│              Data Layer                           │
│  (Repository implementations, DB, network, cache) │
│       Depends on: Domain (implements interfaces)  │
├──────────────────────────────────────────────────┤
│              Core Layer                           │
│  (Networking, preferences, storage, archives)     │
│       Depends on: Nothing (shared utilities)      │
└──────────────────────────────────────────────────┘
```

### Dependency Rule
Dependencies point **inward**: Presentation → Domain ← Data. The Domain layer never depends on outer layers.

---

## 2. Key Data Flows

### 2.1 Browsing Manga from a Source

```
User taps "Browse" → Source selected
    │
    ▼
BrowseSourceScreenModel (app/ui/browse/source/browse/)
    │  Calls SourcePagingSource (data/source/)
    │      │
    │      ▼
    │  SourceRepositoryImpl (data/source/)
    │      │  Calls HttpSource (source-api/online/)
    │      │      │
    │      │      ▼
    │      │  OkHttp request → Remote server
    │      │      │
    │      │      ◄── HTML/JSON response
    │      │      │
    │      │      ▼
    │      │  ParsedHttpSource parses into SManga list
    │      │      │
    │      ◄──────┘
    │      │
    ▼      ▼
BrowseSourceScreen renders manga list (Compose)
```

### 2.2 Adding Manga to Library

```
User clicks "Add to Library" on manga detail
    │
    ▼
MangaScreenModel (app/ui/manga/)
    │  Calls GetManga interactor (domain/manga/interactor/)
    │      │
    │      ▼
    │  MangaRepositoryImpl (data/manga/)
    │      │  Inserts into SQLDelight `mangas` table
    │      │  Categories saved via `mangas_categories` join
    │      │
    ▼      ▼
UI shows manga in Library tab
```

### 2.3 Reading a Chapter

```
User taps chapter → ReaderActivity opens
    │
    ▼
ReaderViewModel (app/ui/reader/)
    │  Calls ChapterLoader (app/ui/reader/loader/)
    │      │
    │      ▼
    │  Selects PageLoader implementation:
    │  ├── HttpPageLoader (online, not downloaded)
    │  ├── DownloadPageLoader (local files)
    │  ├── ArchivePageLoader (CBZ/CBR files)
    │  ├── DirectoryPageLoader (folder)
    │  └── EpubPageLoader (EPUB files)
    │      │
    │      ▼
    │  Loads pages → Page objects with image URLs/paths
    │      │
    ▼      ▼
Viewer displays pages (Pager or Webtoon mode)
    │
    ▼
Chapter marked as read → UpsertHistory (domain/history/)
    │
    ▼
HistoryRepositoryImpl updates `history` table
```

### 2.4 Chapter Download Flow

```
User taps "Download" on a chapter
    │
    ▼
DownloadManager (app/data/download/)
    │  Enqueues download → DownloadJob (WorkManager)
    │      │
    │      ▼
    │  Downloader fetches pages via HttpSource
    │      │  Progress tracked via ProgressListener
    │      │
    │      ▼
    │  DownloadProvider saves to device storage
    │  ChapterCache updates page list
    │      │
    ▼      ▼
DownloadNotifier shows progress notification
```

### 2.5 Library Update (Check for New Chapters)

```
Periodic task or manual trigger → LibraryUpdateJob (WorkManager)
    │
    ▼
For each manga in library:
    │  GetManga interactor → MangaRepository
    │      │
    │      ▼
    │  GetRemoteManga interactor (domain/source/interactor/)
    │      │  Calls HttpSource.getChapterList()
    │      │
    │      ▼
    │  SyncChaptersWithSource (app domain: chapter/interactor/)
    │      │  Compares remote vs local chapters
    │      │  Inserts new chapters
    │      │
    ▼      ▼
LibraryUpdateNotifier shows update count
```

---

## 3. Tracking Service Flow

```
The app supports 9 tracking services for syncing reading progress:

Common flow:
    │
    ▼
User authenticates via OAuth (TrackLoginActivity)
    │  OAuth flow: app → tracker site → redirect → token
    │
    ▼
TrackerManager stores auth token
    │
    ▼
Reading a chapter → TrackChapter interactor (domain/track/interactor/)
    │  Iterates all enabled trackers
    │      │
    │      ▼
    │  Calls tracker.updateProgress(mangaId, chapter)
    │  e.g., AnilistApi via GraphQL mutation
    │      │
    ▼      ▼
Remote tracking service updated

Trackers available:
├── Anilist (GraphQL API)
├── MyAnimeList (REST API)
├── Kitsu (REST API)
├── Bangumi (REST API)
├── Shikimori (REST API)
├── MangaUpdates (REST API)
├── Kavita (self-hosted, REST API)
├── Komga (self-hosted, REST API)
└── Suwayomi/Tachidesk (self-hosted, REST API)
```

---

## 4. Automatic Translation Pipeline (TachiyomiAT Exclusive)

This is the defining feature of the fork. The pipeline processes each page through
5 stages:

```
ChapterTranslator.translate(chapter)
    │
    ├── Stage 1: TEXT DETECTION
    │   │  OnnxPageTextDetector
    │   │  └── Runs ONNX model → finds text bounding boxes
    │   │
    ├── Stage 2: OCR (Text Recognition)
    │   │  TextRecognizer (selected by settings)
    │   │  ├── PaddleOcrV6SmallEngine (PaddleOCR v6 small ONNX model)
    │   │  ├── MangaOcrEngine (purpose-built for Japanese manga)
    │   │  ├── MlKitRoiOcrEngine (Google ML Kit)
    │   │  └── RoiOcrEngine (generic ROI-based)
    │   │
    ├── Stage 3: TEXT TRANSLATION
    │   │  TextTranslator (selected by settings)
    │   │  ├── GeminiTranslator (Google Gemini API)
    │   │  ├── DeepSeekTranslator (DeepSeek API)
    │   │  ├── GoogleTranslator (Google Translate)
    │   │  ├── OpenRouterTranslator (OpenRouter API)
    │   │  ├── MLKitTranslator (on-device ML Kit)
    │   │  └── LmStudioTranslator (local LLM)
    │   │
    ├── Stage 4: INPAINTING (Bubble Cleaning)
    │   │  AOTInpainting
    │   │  └── Removes original text from bubble
    │   │      └── SmartBubbleTextCleaner refines masks
    │   │
    └── Stage 5: RENDERING
        │  PageTextRenderer
        │  └── Renders translated text onto cleaned image
        │      └── RenderColorEstimator picks text color
        │
        ▼
Note: shared pure logic used across stages lives in focused helpers, not inline:
  • recognition/BoxGeometry        — bbox IoU + geometric dedupe (Stages 1 & 2)
  • inpainting/BubbleMaskBuilder   — mask construction + morphology (Stage 4)
  • translator/NumberedLineResponseParser + OcrArtifactSanitizer — LLM output parsing (Stage 3)
  • rendering/RenderColorEstimator.colorPolicy — text/stroke color decision (Stage 5)
See docs/TRANSLATION_MODULE.md for the full map.
        ▼
PageTranslationState updated through states:
  Pending → Detecting → Detected → Recognizing → Recognized
  → Translating → Translated → Inpainting → Inpainted
  → Rendering → Rendered → Complete
```

Rendered image quality is an invariant, not a best effort. Before decode, the
pipeline reclaims bitmap pools, OCR/inpaint native pools, and Coil memory cache
if a normal page would otherwise be heap-downsampled. Additionally, to prevent
chronic native memory pressure accumulation across consecutive page translations,
the pipeline immediately calls native/off-heap memory reclamation at the end of
each page. If full-quality decode is still unsafe, the page becomes retryable
instead of saving blurry output. Only hard source-size limits may produce
sampled output, and those pages are marked `RenderQuality.SIZE_LIMITED`.

Reader-side translated image streams are on demand. `ReaderPageWarmWindow`
attaches translated streams only for the current page plus two pages on either
side; cold pages keep only persisted metadata and reopen translated images from
disk when they enter the warm window. A 200-page chapter therefore does not keep
200 translated stream factories or compressed source byte arrays in memory.

### State Machine (`PageTranslationState`)

```
                  ┌──────────┐
                  │  Pending  │
                  └─────┬────┘
                        │ detect()
                  ┌─────▼──────┐
                  │  Detecting  │
                  └─────┬──────┘
                        │ detected()
                  ┌─────▼──────┐
                  │  Detected   │
                  └─────┬──────┘
                        │ recognize()
                  ┌──────▼───────┐
                  │  Recognizing │
                  └──────┬───────┘
                         │ recognized()
                  ┌──────▼────────┐
                  │  Recognized   │
                  └──────┬────────┘
                         │ translate()
                  ┌───────▼────────┐
                  │  Translating   │
                  └───────┬────────┘
                          │ translated()
                  ┌───────▼─────────┐
                  │  Translated     │
                  └───────┬─────────┘
                          │ inpaint()
                  ┌───────▼──────────┐
                  │  Inpainting      │
                  └───────┬──────────┘
                          │ inpainted()
                  ┌───────▼─────────┐
                  │  Inpainted       │
                  └───────┬─────────┘
                          │ render()
                  ┌───────▼────────┐
                  │  Rendering     │
                  └───────┬────────┘
                          │ rendered()
                  ┌───────▼────────┐
                  │  Complete      │
                  └────────────────┘
         Any error → Error state
         Cancellation → Cancelled state
```

### Translation Managers & Coordination

```
TranslationManager (central coordinator)
    │  Manages queue of Translation objects
    │  Handles auto-prefetch (pre-translate next chapters)
    │  Cancellation of in-progress translations
    │  Lifecycle of ChapterTranslationStore
    │
    ├── ChapterTranslator (per-chapter pipeline)
    │      Orchestrates 5-stage pipeline for one chapter
    │      Stores results in ChapterTranslationStore
    │
    ├── ChapterTranslationStore (persistence)
    │      JSON file per chapter storing page translation states
    │      Allows resuming interrupted translations
    │
    └── TranslationSession (active session)
           Tracks current page being translated
           Provides status callbacks to UI
```

Batch pre-translation ownership:

- Manga-screen chapter translation opens/registers a shared
  `ChapterTranslationStore`, pre-registers all ordered page keys, then emits
  `TranslationProgressSnapshot` updates to the chapter row and progress sheet.
  The START action is gated behind a read-only confirmation popup
  (`MangaScreenModel.Dialog.ConfirmTranslation` → `ConfirmTranslationDialog`)
  unless the `translationConfirmPretranslate` preference is off. The popup
  renders a `TranslationSettingsSummary` (source/target language, engine/model,
  OCR model, output tokens, inpaint mode), offers a "Don't show this again"
  checkbox bound to that preference, and an "Open settings" link to
  `SettingsScreen.Destination.Translation`. The reader per-page path is not
  affected.
- If the reader opens the same chapter while the batch is active, reader
  auto/manual page scheduling is suppressed for that chapter and the reader only
  observes the shared store. Reader pause/close cancels reader jobs and streams
  without clearing active batch queues or unregistering active batch stores.
- AI_MODEL batch translation chunks OCR text with
  `TranslationContextChunkPlanner` under an 8192-token context budget before
  inpaint/render. Standard translators keep the per-page translate path.

---

## 5. Backup/Restore Flow

```
BACKUP (.tachibk JSON file):

User taps "Create Backup" → BackupOptions selected
    │
    ▼
BackupCreateJob (WorkManager)
    │
    ▼
BackupCreator orchestrates:
    ├── MangaBackupCreator → serializes manga/chapters/history
    ├── CategoriesBackupCreator → serializes categories
    ├── SourcesBackupCreator → serializes source metadata
    ├── PreferenceBackupCreator → serializes app preferences
    └── ExtensionRepoBackupCreator → serializes extension repos
    │
    ▼
Compressed JSON written to device storage

RESTORE:

User selects backup file → RestoreOptions
    │
    ▼
BackupRestoreJob (WorkManager)
    │
    ▼
BackupRestorer orchestrates:
    ├── MangaRestorer → inserts/updates manga, chapters, history
    ├── CategoriesRestorer → recreates categories
    ├── PreferenceRestorer → applies saved preferences
    └── ExtensionRepoRestorer → adds saved repos
    │
    ▼
Database updated, library rebuilt
```

---

## 6. Extension System Flow

```
EXTENSION INSTALLATION:

User browses Extension repos → Downloads extension APK
    │
    ▼
ExtensionInstaller (extension/util/)
    ├── PackageInstallerInstaller (standard Android installer)
    └── ShizukuInstaller (privileged, no user prompt)
    │
    ▼
ExtensionInstallService (foreground service)
    │
    ▼
ExtensionInstallReceiver receives install result
    │
    ▼
ExtensionLoader loads APK into custom classloader
    │
    ▼
AndroidSourceManager registers new sources
    │
    ▼
SourceFactory.createSources() → Source instances available

EXTENSION API CONTRACT (source-api/):
    │
    Source interface → getMangaDetails(), getChapterList(), getPageList()
    ├── CatalogueSource → getPopularManga(), getSearchManga(), getLatestUpdates()
    ├── ConfigurableSource → per-source preferences
    ├── HttpSource → OkHttp-based implementation base
    └── ParsedHttpSource → Jsoup HTML parsing extension
```

---

## 7. Database Schema Relationships

```
┌─────────────┐       ┌──────────────────┐
│   mangas    │1────N→│    chapters      │
│             │       │                  │
│ PK: manga_id│       │ PK: chapter_id   │
│ source, url │       │ manga_id (FK)    │
│ title, etc. │       │ url, name, num   │
│ thumbnail   │       │ read, bookmark   │
│ favorite    │       │ date_fetch       │
│ version     │       │ page_count       │
└──────┬──────┘       └────────┬─────────┘
       │                       │
       │1                      │1
       │                       │
       │               ┌───────▼─────────┐
       │               │    history      │
       │               │                 │
       │               │ chapter_id (FK) │
       │               │ last_read       │
       │               │ time_read       │
       │               └─────────────────┘
       │1
       │
┌──────▼──────────┐    ┌──────────────────┐
│ mangas_categories│    │   categories     │
│                  │    │                  │
│ manga_id (FK)   │    │ PK: category_id  │
│ category_id (FK)│N──1│ name, order      │
└─────────────────┘    │ flags            │
                       └──────────────────┘

┌─────────────┐       ┌──────────────────┐
│   mangas    │1────N→│   manga_sync     │
│             │       │   (tracking)     │
│             │       │                  │
│             │       │ manga_id (FK)    │
│             │       │ sync_id (tracker)│
│             │       │ remote_id, score │
│             │       │ status, progress │
└─────────────┘       └──────────────────┘

┌─────────────┐       ┌──────────────────────┐
│   mangas    │1────N→│ excluded_scanlators  │
│             │       │                      │
│             │       │ manga_id (FK)        │
│             │       │ scanlator name       │
└─────────────┘       └──────────────────────┘

┌─────────────────────┐
│   extension_repos   │
│                     │
│ PK: base_url        │
│ name, short_name    │
│ signing_key_fp (UQ) │
└─────────────────────┘

┌──────────┐
│ sources  │ (cached extension metadata)
│          │
│ PK: id   │
│ lang     │
│ name     │
└──────────┘
```

### Key Views (Virtual Tables)

**`libraryView`** — joins mangas + chapters + history + categories for the library screen
**`historyView`** — joins mangas + chapters + history for the history screen
**`updatesView`** — joins mangas + chapters for recent updates

---

## 8. Reader Viewing Modes

```
ReaderActivity
    │
    ├── PAGER MODES (RecyclerView-based)
    │   ├── Left to Right (LTR)
    │   ├── Right to Left (RTL)
    │   └── Vertical Scrolling (page-by-page)
    │
    └── WEBTOON MODE (RecyclerView + custom layout manager)
        └── Continuous vertical scroll with subsampling

Navigation Modes:
├── Edge (tap left/right edges)
├── L-Shaped (zones mapped like "L")
├── Left and Right (dedicated zones on both sides)
├── Kindlish (Kindle-style zones)
└── Disabled (swipe only)

Page Loaders (strategy pattern):
├── HttpPageLoader (online streaming)
├── DownloadPageLoader (cached chapters)
├── ArchivePageLoader (CBZ/CBR files)
├── DirectoryPageLoader (extracted folder)
└── EpubPageLoader (EPUB ebooks)
```

---

## 9. Dependency Injection (Injekt)

The project uses **Injekt** (compile-time DI, no reflection) instead of Dagger/Hilt/Koin:

```
App.kt
    │
    ├── AppModule (eu.kanade.tachiyomi.di)
    │   ├── NetworkHelper (OkHttp client)
    │   ├── DownloadManager
    │   ├── AndroidSourceManager
    │   ├── TrackerManager
    │   ├── ExtensionManager
    │   ├── TranslationManager ★
    │   └── Coil ImageLoader
    │
    └── PreferenceModule (eu.kanade.tachiyomi.di)
        ├── SharedPreferences → PreferenceStore
        └── All typed preferences registered

DomainModule (eu.kanade.domain)
    └── All interactors registered for injection
```

---

## 10. App Startup Sequence

```
1. Application.onCreate() [App.kt]
    │
    ├── Injekt modules initialization
    ├── Coil ImageLoader setup
    │   └── Custom MangaCoverFetcher & TachiyomiImageDecoder
    ├── Notification channels creation
    ├── AppUpdateChecker (if enabled)
    ├── Incognito mode setup
    ├── Firebase initialization (standard flavor only)
    ├── Mihon migration runner
    │
    ▼
2. MainActivity.onCreate()
    │
    ├── Theme setup (ThemingDelegate)
    ├── Security check (SecureActivityDelegate)
    ├── Deep link handling
    │
    ▼
3. Main screen renders (HomeScreen with bottom nav)
    ├── Library tab (default)
    ├── Browse tab
    ├── Updates tab
    ├── History tab
    └── More tab
```
