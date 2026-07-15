# Module Map — Complete Codebase Module Reference

> A comprehensive reference for every Gradle module in the TachiyomiAT project,
> including purpose, dependencies, package structure, and file counts.

---

## Module Inventory (13 modules)

| # | Module | Type | Kotlin Files | Description |
|---|--------|------|-------------|-------------|
| 1 | `:app` | Android app | ~500+ | Main application — UI, reader, translation pipeline |
| 2 | `:core:common` | Android lib | ~42 | Shared utilities: networking, preferences, storage, i18n |
| 3 | `:core:archive` | Android lib | ~6 | Archive reading/writing: CBZ, CBR, EPUB, ZIP |
| 4 | `:core-metadata` | Android lib | ~2 | Manga metadata: ComicInfo XML, JSON details |
| 5 | `:domain` | Android lib | ~87 | Business logic, use cases, repository interfaces |
| 6 | `:data` | Android lib | ~17 | Persistence: SQLDelight repositories, migrations |
| 7 | `:source-api` | KMP lib | ~23 | Extension API: Source interfaces, models, HTTP parsing |
| 8 | `:source-local` | KMP lib | ~10 | Local file source: CBZ/EPUB/directory reading |
| 9 | `:presentation-core` | Android lib + Compose | ~45 | Shared UI components, theming, custom Material widgets |
| 10 | `:presentation-widget` | Android lib + Compose | ~7 | Android app widgets (home/lock/cover screen) |
| 11 | `:i18n` | KMP lib | 0 (XML) | Internationalization: 66 languages |
| 12 | `:i18n-at` | KMP lib | 0 (XML) | TachiyomiAT-specific translation strings |
| 13 | `:macrobenchmark` | Benchmark | ~2 | App startup benchmark + baseline profile generation |

---

## 1. `:app` — Main Application Module

**Build:** `app/build.gradle.kts`
**Namespace:** `eu.kanade.tachiyomi` (legacy), `tachiyomi.*` (refactored), `mihon.*` (new)
**Package root:** `eu/kanade/tachiyomi/`

### Package Structure

```
eu.kanade.tachiyomi/
├── App.kt                         # Application class (DI init, Coil, notifications)
├── AppInfo.kt                     # App metadata constants
├── crash/                         # Crash handling (CrashActivity, GlobalExceptionHandler)
├── data/
│   ├── backup/                    # Full backup/restore system (.tachibk format)
│   │   ├── create/                # Backup creation (categories, manga, preferences, sources)
│   │   ├── models/                # Backup data models (JSON serializable)
│   │   └── restore/               # Backup restoration
│   ├── cache/                     # ChapterCache, CoverCache
│   ├── coil/                      # Custom Coil fetchers & decoders
│   ├── database/models/           # DB model interfaces & implementations
│   ├── download/                  # Download engine, manager, notifications
│   ├── library/                   # Library update job
│   ├── notification/              # Notification handler & receiver
│   ├── preference/                # SharedPreferences DataStore wrapper
│   ├── saver/                     # Image saver to gallery
│   ├── data/track/                # Tracking services (9 trackers)
│   └── updater/                   # App update checker & downloader
├── di/                            # Injekt DI modules
├── extension/                     # Extension management & installation
├── mihon/                         # Mihon-specific features
│   ├── core/designsystem/utils/   # Window size utilities
│   ├── core/migration/            # Schema/data migration framework
│   └── feature/upcoming/          # Upcoming releases calendar
├── presentation/                  # Compose UI screens
│   ├── browse/                    # Browse sources, extensions, migration screens
│   ├── category/                  # Category management
│   ├── components/                # Shared UI components (sheets, app bars, dialogs)
│   ├── crash/                     # Crash screen
│   ├── history/                   # Reading history
│   ├── library/                   # Library grid/list/tabs
│   ├── manga/                     # Manga detail screen
│   ├── more/                      # More tab, onboarding, settings
│   ├── reader/                    # Reader UI (settings, overlays, transitions)
│   ├── theme/                     # TachiyomiTheme, color schemes (11 themes)
│   ├── track/                     # Tracker search & info dialogs
│   ├── updates/                   # Recent updates screen
│   └── webview/                   # WebView screen
├── source/                        # Source management (AndroidSourceManager)
├── translation/                   # ★ TachiyomiAT exclusive ★ (package root: eu/kanade/translation/)
│   │                              # See docs/TRANSLATION_MODULE.md for the full file map,
│   │                              # test-coverage table, and dedup/structure notes.
│   ├── detection/                 # ONNX text detection (dedup math in recognition/BoxGeometry)
│   ├── inpainting/                # AOT-based bubble cleaning + BubbleMaskBuilder (pure masks)
│   ├── model/                     # PageTranslation state machine + status predicates
│   ├── ocr/                       # OCR engines (PaddleOCR, MangaOCR, ML Kit)
│   ├── recognition/               # Page recognition engines + BoxGeometry (shared IoU/dedupe)
│   ├── rendering/                 # Text rendering + RenderColorEstimator (pure colorPolicy)
│   ├── runtime/onnx/              # ONNX runtime & model management
│   ├── scheduling/                # Job lifecycle, executor, stream registry, lifecycle policy
│   ├── translator/                # AI/API translators + shared prompt parser
│   │                              #   (TranslationPrompts, OcrArtifactSanitizer) + engine kinds
│   └── util/                      # Task extensions, memory budget, ShortHash (FNV digest)
├── ui/                            # Legacy/activity-based UI
│   ├── base/                      # BaseActivity, delegates
│   ├── main/                      # MainActivity
│   └── settings/                  # OAuth login activities
└── util/                          # General utilities (crash logging, extensions)
```

### Key Features
- Full manga reader with multiple viewing modes (pager LTR/RTL, vertical, webtoon)
- AI-powered automatic manga translation pipeline
- 9 tracking services (MyAnimeList, Anilist, Kitsu, Bangumi, Shikimori, MangaUpdates, Kavita, Komga, Suwayomi)
- Backup/restore (full app state serialization)
- Extension system (install 3rd-party source APKs)
- Global search across all sources
- Deep link handling
- Crash reporting with dedicated crash activity
- 11 color themes with dark mode support

---

## 2. `:core:common` — Shared Core Utilities

**Build:** `core/common/build.gradle.kts`
**Namespace:** `tachiyomi.core.common`, `eu.kanade.tachiyomi`
**Dependencies:** OkHttp, Okio, RxJava, Coroutines, Kotlinx Serialization, PreferenceKTX, Jsoup

```
core/common/src/main/kotlin/
├── tachiyomi/core/common/
│   ├── Constants.kt               # Global app constants
│   ├── i18n/Localize.kt           # Localization helpers
│   ├── preference/                # Preference abstraction (7 files)
│   │   ├── Preference.kt, PreferenceStore.kt
│   │   ├── AndroidPreference.kt, AndroidPreferenceStore.kt
│   │   ├── InMemoryPreferenceStore.kt
│   │   ├── CheckboxState.kt, TriState.kt
│   ├── storage/                   # File/folder storage (3 files)
│   │   ├── FolderProvider.kt, AndroidStorageFolderProvider.kt
│   │   └── UniFileExtensions.kt
│   └── util/
│       ├── lang/                  # Boolean, Coroutines, Rx bridge, Sort
│       └── system/                # ImageUtil, LogcatExtensions
└── eu/kanade/tachiyomi/
    ├── core/security/             # PrivacyPreferences, SecurityPreferences
    ├── network/                   # Networking layer (10+ files)
    │   ├── NetworkHelper.kt       # Central OkHttp client
    │   ├── JavaScriptEngine.kt    # Cloudflare challenge solver
    │   ├── AndroidCookieJar.kt    # Persistent cookie store
    │   └── interceptor/           # 7 OkHttp interceptors
    └── util/                      # Lang, storage, system utilities
```

---

## 3. `:core:archive` — Archive Reading/Writing

**Build:** `core/archive/build.gradle.kts`
**Namespace:** `mihon.core.archive`
**Dependencies:** Jsoup, libarchive, UniFile

```
core/archive/src/main/kotlin/mihon/core/archive/
├── ArchiveEntry.kt                # Single archive entry model
├── ArchiveInputStream.kt          # Sequential entry input stream
├── ArchiveReader.kt               # Main archive reader (CBZ, CBR, RAR, 7Z)
├── EpubReader.kt                  # EPUB format reader
├── UniFileExtensions.kt           # UniFile archive operations
└── ZipWriter.kt                   # ZIP file writer
```

---

## 4. `:core-metadata` — Manga Metadata

**Build:** `core-metadata/build.gradle.kts`
**Namespace:** `tachiyomi.core.metadata`
**Dependencies:** `:source-api`

```
core-metadata/src/main/java/tachiyomi/core/metadata/
├── comicinfo/ComicInfo.kt         # ComicRack XML metadata serialization
└── tachiyomi/MangaDetails.kt      # JSON manga details data class
```

---

## 5. `:domain` — Business Logic Layer

**Build:** `domain/build.gradle.kts`
**Namespace:** `tachiyomi.domain`, `mihon.domain`
**Dependencies:** `:source-api`, `:core:common`, SQLDelight Paging
**Total:** 87 Kotlin source files + 5 test files

### Package Map

| Package | Files | Purpose |
|---------|-------|---------|
| `mihon.domain.chapter.interactor` | 1 | FilterChaptersForDownload |
| `mihon.domain.extensionrepo.*` | 11 | Extension repo CRUD, model, repository, service, exception |
| `mihon.domain.upcoming.interactor` | 1 | GetUpcomingManga |
| `tachiyomi.domain.backup.service` | 1 | BackupPreferences |
| `tachiyomi.domain.category.*` | 13 | Category CRUD interactors, model, repository |
| `tachiyomi.domain.chapter.*` | 13 | Chapter CRUD, model, repository, sorting, recognition |
| `tachiyomi.domain.download.service` | 1 | DownloadPreferences |
| `tachiyomi.domain.history.*` | 9 | History CRUD interactors, model, repository |
| `tachiyomi.domain.library.*` | 5 | Library display/sort modes, model, preferences |
| `tachiyomi.domain.manga.*` | 15 | Manga CRUD, model, repository |
| `tachiyomi.domain.release.*` | 3 | App release check interactor, model, service |
| `tachiyomi.domain.source.*` | 9 | Source interactors, model, repository, SourceManager |
| `tachiyomi.domain.storage.service` | 2 | StorageManager, StoragePreferences |
| `tachiyomi.domain.track.*` | 6 | Track CRUD interactors, model, repository |
| `tachiyomi.domain.translation.*` | 5 | Keystore API key manager, TranslationPreferences, BitmapPool, DirectBufferPool, PreferenceValidator |

---

## 6. `:data` — Persistence Layer

**Build:** `data/build.gradle.kts`
**Namespace:** `tachiyomi.data`, `mihon.data.repository`
**Dependencies:** `:source-api`, `:domain`, `:core:common`, SQLDelight
**Database engine:** SQLDelight (SQLite)

### Repository Implementations

| File | Description |
|------|-------------|
| `DatabaseHandler.kt` | Interface: all DB operations (suspend, Flow, Paging) |
| `AndroidDatabaseHandler.kt` | SQLDelight-backed implementation |
| `TransactionContext.kt` | Coroutine-safe transaction management |
| `DatabaseAdapter.kt` | Custom column adapters (Date, StringList, Enum) |
| `QueryPagingSource.kt` | Paging 3 source backed by SQLDelight queries |
| `MangaRepositoryImpl.kt` | Manga CRUD + library queries |
| `ChapterRepositoryImpl.kt` | Chapter CRUD + scanlator filtering |
| `HistoryRepositoryImpl.kt` | Read history with session duration tracking |
| `CategoryRepositoryImpl.kt` | Category CRUD |
| `TrackRepositoryImpl.kt` | External tracking sync storage |
| `UpdatesRepositoryImpl.kt` | Recent updates queries |
| `SourceRepositoryImpl.kt` | Source + manga count queries |
| `StubSourceRepositoryImpl.kt` | Stub sources for uninstalled extensions |
| `ReleaseServiceImpl.kt` | GitHub release API fetcher |
| `ExtensionRepoRepositoryImpl.kt` | Extension repo CRUD |

### SQLDelight Schema (10 `.sq` files)

| Table | Purpose |
|-------|---------|
| `mangas` | Core manga entity with metadata, cover, flags, versioning |
| `chapters` | Chapter entities with FK to manga, progress tracking |
| `history` | Per-chapter read sessions (time accumulated) |
| `categories` | Library categories with sort order and flags |
| `mangas_categories` | Many-to-many join table |
| `manga_sync` | External tracking service sync state |
| `sources` | Cached extension/source metadata |
| `extension_repos` | Extension repository URLs and signing keys |
| `excluded_scanlators` | Per-manga scanlator exclusion list |

### Views (3 `.sq` files)

| View | Purpose |
|------|---------|
| `libraryView` | Rich library listing: manga + aggregated chapter stats |
| `historyView` | Read history with manga/chapter joins |
| `updatesView` | Recent chapter updates for library manga |

### Migrations (3 `.sqm` files)

| Migration | Change |
|-----------|--------|
| `1.sqm` | Fix negative scores in manga_sync |
| `2.sqm` | Add version + is_syncing columns with triggers |
| `3.sqm` | Create extension_repos table |

---

## 7. `:source-api` — Extension API (KMP)

**Build:** `source-api/build.gradle.kts`
**Type:** Kotlin Multiplatform (commonMain + androidMain)
**Namespace:** `eu.kanade.tachiyomi.source`
**Purpose:** Defines the contract between Tachiyomi and content extensions

### Core Interfaces (Hierarchy)

```
Source (root interface)
├── CatalogueSource (adds search/popular/latest/filters)
├── ConfigurableSource (adds per-source preferences)
│
Source.online/
├── HttpSource (OkHttp-based CatalogueSource implementation)
│   └── ParsedHttpSource (Jsoup-based HTML parsing)
├── ResolvableSource (URI resolution for deep links)
```

### Data Models

| Model | Description |
|-------|-------------|
| `SManga` | Manga entry (title, author, artist, status, genres, cover, update strategy) |
| `SChapter` | Chapter entry (url, name, upload date, chapter number, scanlator) |
| `Page` | Image page in a chapter (with status enum + progress tracking) |
| `MangasPage` | Paginated manga list result |
| `Filter` (sealed) | Search filter hierarchy (Header, Select, Text, CheckBox, TriState, Group, Sort) |
| `UpdateStrategy` | Enum: ALWAYS_UPDATE / ONLY_FETCH_ONCE |

---

## 8. `:source-local` — Local Source (KMP)

**Build:** `source-local/build.gradle.kts`
**Type:** Kotlin Multiplatform
**Namespace:** `tachiyomi.source.local`
**Purpose:** Read manga from local device storage (CBZ, CBR, EPUB, directories)

### Architecture

```
commonMain/                   androidMain/
├── expect declarations       └── actual implementations
│   ├── LocalSource           ├── LocalSource (reads via UniFile)
│   ├── LocalCoverManager    ├── LocalCoverManager (bitmap caching)
│   └── LocalSourceFileSystem └── LocalSourceFileSystem (UniFile I/O)
```

---

## 9. `:presentation-core` — Shared UI Components

**Build:** `presentation-core/build.gradle.kts`
**Namespace:** `tachiyomi.presentation.core`
**Purpose:** Reusable Compose components, custom Material widgets, theme colors

### Component Catalog

| Component Category | Examples |
|-------------------|----------|
| **Layout** | AdaptiveSheet, CollapsibleBox, TwoPanelBox, WheelPicker |
| **Navigation** | NavigationBar, NavigationRail, Tabs, Scaffold |
| **Input** | Slider, Button, FloatingActionButton, IconToggleButton |
| **Display** | Badges, Pill, SectionCard, ListGroupHeader, LinkIcon |
| **Feedback** | AlertDialog, CircularProgressIndicator, PullRefresh |
| **Lists** | LazyGrid, LazyList, LazyColumnWithAction, VerticalFastScroller |
| **Screens** | EmptyScreen, InfoScreen, LoadingScreen |
| **Icons** | CustomIcons, Discord, Facebook, Github, Reddit, X |

### Theming — 11 Color Schemes

| Scheme | Light XML | Night XML |
|--------|-----------|-----------|
| Default Tachiyomi | `colors_tachiyomi.xml` | `colors_tachiyomi.xml` |
| Green Apple | `colors_greenapple.xml` | `colors_greenapple.xml` |
| Lavender | `colors_lavender.xml` | `colors_lavender.xml` |
| Midnight Dusk | `colors_midnightdusk.xml` | `colors_midnightdusk.xml` |
| Monet (dynamic) | via code | via code |
| Nord | `colors_nord.xml` | `colors_nord.xml` |
| Strawberry | `colors_strawberry.xml` | `colors_strawberry.xml` |
| Tako | `colors_tako.xml` | `colors_tako.xml` |
| Teal Turquoise | `colors_tealturqoise.xml` | `colors_tealturqoise.xml` |
| Tidal Wave | `colors_tidalwave.xml` | `colors_tidalwave.xml` |
| Yin Yang | `colors_yinyang.xml` | `colors_yinyang.xml` |
| Yotsuba | `colors_yotsuba.xml` | `colors_yotsuba.xml` |

---

## 10. `:presentation-widget` — Android App Widgets

**Build:** `presentation-widget/build.gradle.kts`
**Namespace:** `tachiyomi.presentation.widget`
**Framework:** Compose Glance

**Widget types:**
- **Home screen widget** — grid of recent manga updates
- **Lock screen widget** — Android 12+ lock screen updates
- **Cover screen widget** — Samsung cover screen support

---

## 11. `:i18n` — Internationalization

**Build:** `i18n/build.gradle.kts`
**Framework:** moko-resources (KMP)
**Base strings:** `strings.xml` (975 lines, 190+ resources)
**Plurals:** `plurals.xml` (14 plural definitions)
**Languages:** 66 (including Amharic, Arabic, Bengali, Chinese, Japanese, Korean, etc.)

---

## 12. `:i18n-at` — TachiyomiAT Translation Strings

**Build:** `i18n-at/build.gradle.kts`
**Framework:** moko-resources (KMP)
**Base strings:** `strings.xml` (89 lines, 60+ resources)
**Content:** TachiyomiAT-specific UI strings for translation settings, reader, and pipeline

---

## 13. `:macrobenchmark` — Performance Benchmarks

**Build:** `macrobenchmark/build.gradle.kts`
**Namespace:** `tachiyomi.macrobenchmark`

```
macrobenchmark/src/main/java/tachiyomi/macrobenchmark/
├── BaselineProfileGenerator.kt    # AOT baseline profile for Play Store
└── StartupBenchmark.kt            # Cold/warm/hot startup measurement
```

---

## Build Infrastructure (`buildSrc/`)

**Type:** Pre-compiled Gradle script plugins

### Convention Plugins

| Plugin File | Applies To |
|-------------|-----------|
| `mihon.android.application.gradle.kts` | App module |
| `mihon.android.application.compose.gradle.kts` | App module (with Compose) |
| `mihon.library.gradle.kts` | Library modules |
| `mihon.library.compose.gradle.kts` | Library modules with Compose |
| `mihon.code.lint.gradle.kts` | All modules (Spotless) |
| `mihon.benchmark.gradle.kts` | Macrobenchmark module |

### Build Logic Constants (`mihon/buildlogic/`)

| File | Content |
|------|---------|
| `AndroidConfig.kt` | compileSdk=35, minSdk=26, targetSdk=34, NDK=27.1.12297006 |
| `Commands.kt` | Git command helpers (commit count, SHA, build time) |
| `ProjectExtensions.kt` | Gradle Project extension functions |
| `LocalesConfigTask.kt` | Locale config XML generation task |

---

## Gradle Version Catalogs

| Catalog File | Contents |
|-------------|----------|
| `gradle/libs.versions.toml` | Core dependencies (OkHttp, Coil, Injekt, etc.) |
| `gradle/kotlinx.versions.toml` | Kotlin + kotlinx libraries (coroutines, serialization) |
| `gradle/androidx.versions.toml` | AndroidX + Compose libraries |
| `gradle/compose.versions.toml` | Compose BOM and compiler |

---

## CI/CD (`.github/workflows/`)

| Workflow | Trigger | Actions |
|----------|---------|---------|
| `build_pull_request.yml` | PRs | Build, lint, test, upload debug APK |
| `build_push.yml` | Push to main + tags | Build + sign release APKs, create GitHub Release |
| `lock.yml` | Daily cron | Lock stale issues/PRs after 2 days |
