# Source Code Layout

> Normalized tree reference for the TachiyomiAT codebase.
> Package roots are listed exactly as they exist in source, even when they differ
> from the module directory name.

---

## Root

```text
TachiyomiAT-1.16.8-dev/
├─ _build_install.bat
├─ _build.bat
├─ _compile.bat
├─ _verify.bat
├─ .editorconfig
├─ .gitattributes
├─ .gitignore
├─ AGENT.md
├─ README.md
├─ CHANGELOG.md
├─ CODE_OF_CONDUCT.md
├─ CONTRIBUTING.md
├─ LICENSE
├─ build.gradle.kts
├─ gradle.properties
├─ local.properties
├─ settings.gradle.kts
├─ gradlew
├─ gradlew.bat
├─ prefs.xml
├─ prefs-device.xml
├─ build/
├─ buildSrc/
├─ core/
├─ core-metadata/
├─ data/
├─ docs/
├─ domain/
├─ gradle/
├─ i18n/
├─ i18n-at/
├─ macrobenchmark/
├─ Plan/
├─ presentation-core/
├─ presentation-widget/
├─ source-api/
└─ source-local/
```

---

## Plan

```text
Plan/
├─ README.md
├─ active/
│  └─ <YYYY-MM-DD>-<short-topic>/
├─ rolling-context-and-prepare-chapter.md
└─ translation_issue.md
```

---

## .github/workflows

```text
.github/workflows/
├─ build_pull_request.yml
├─ build_push.yml
└─ lock.yml
```

---

## buildSrc

```text
buildSrc/src/main/kotlin/
├─ mihon.android.application.compose.gradle.kts
├─ mihon.android.application.gradle.kts
├─ mihon.benchmark.gradle.kts
├─ mihon.code.lint.gradle.kts
├─ mihon.library.compose.gradle.kts
├─ mihon.library.gradle.kts
└─ mihon/
   └─ buildlogic/
      ├─ AndroidConfig.kt
      ├─ Commands.kt
      ├─ ProjectExtensions.kt
      └─ tasks/
         └─ LocalesConfigTask.kt
```

---

## app/src/main/java

```text
app/src/main/java/
├─ eu/
│  └─ kanade/
│     ├─ core/
│     │  ├─ preference/
│     │  │  ├─ CheckboxState.kt
│     │  │  └─ PreferenceMutableState.kt
│     │  └─ util/
│     │     ├─ CollectionUtils.kt
│     │     └─ SourceUtil.kt
│     ├─ domain/
│     │  └─ ui/
│     │     ├─ UiPreferences.kt
│     │     └─ model/
│     │        ├─ AppTheme.kt
│     │        ├─ TabletUiMode.kt
│     │        └─ ThemeMode.kt
│     ├─ presentation/
│     │  ├─ history/
│     │  │  ├─ HistoryScreen.kt
│     │  │  ├─ HistoryScreenModelStateProvider.kt
│     │  │  └─ components/
│     │  │     └─ HistoryWithRelationsProvider.kt
│     │  ├─ theme/
│     │  │  └─ TachiyomiTheme.kt
│     │  ├─ updates/
│     │  │  ├─ UpdatesDialog.kt
│     │  │  ├─ UpdatesScreen.kt
│     │  │  └─ UpdatesUiItem.kt
│     │  ├─ util/
│     │  │  ├─ ChapterNumberFormatter.kt
│     │  │  ├─ ExceptionFormatter.kt
│     │  │  ├─ FastScrollAnimateItem.kt
│     │  │  ├─ Navigator.kt
│     │  │  ├─ Permissions.kt
│     │  │  ├─ Resources.kt
│     │  │  ├─ TimeUtils.kt
│     │  │  └─ WindowSize.kt
│     │  └─ webview/
│     │     └─ WebViewScreenContent.kt
│     └─ tachiyomi/
│        ├─ App.kt
│        ├─ AppInfo.kt
│        ├─ data/
│        │  ├─ download/
│        │  │  ├─ DownloadCache.kt
│        │  │  ├─ DownloadJob.kt
│        │  │  ├─ DownloadManager.kt
│        │  │  ├─ DownloadNotifier.kt
│        │  │  ├─ DownloadPendingDeleter.kt
│        │  │  ├─ DownloadProvider.kt
│        │  │  ├─ DownloadStore.kt
│        │  │  └─ model/
│        │  │     └─ Download.kt
│        │  ├─ library/
│        │  │  ├─ LibraryUpdateJob.kt
│        │  │  ├─ LibraryUpdateNotifier.kt
│        │  │  └─ MetadataUpdateJob.kt
│        │  ├─ notification/
│        │  │  ├─ NotificationHandler.kt
│        │  │  ├─ NotificationReceiver.kt
│        │  │  └─ Notifications.kt
│        │  ├─ preference/
│        │  │  └─ SharedPreferencesDataStore.kt
│        │  ├─ track/
│        │  │  ├─ BaseTracker.kt
│        │  │  ├─ DeletableTracker.kt
│        │  │  ├─ EnhancedTracker.kt
│        │  │  ├─ Tracker.kt
│        │  │  ├─ TrackerManager.kt
│        │  │  ├─ model/
│        │  │  │  └─ TrackSearch.kt
│        │  │  ├─ anilist/
│        │  │  ├─ bangumi/
│        │  │  ├─ kavita/
│        │  │  ├─ kitsu/
│        │  │  ├─ komga/
│        │  │  ├─ mangaupdates/
│        │  │  ├─ myanimelist/
│        │  │  ├─ shikimori/
│        │  │  └─ suwayomi/
│        │  └─ updater/
│        │     ├─ AppUpdateChecker.kt
│        │     ├─ AppUpdateDownloadJob.kt
│        │     └─ AppUpdateNotifier.kt
│        └─ util/
│           ├─ chapter/
│           │  ├─ ChapterFilterDownloaded.kt
│           │  ├─ ChapterGetNextUnread.kt
│           │  └─ ChapterRemoveDuplicates.kt
│           ├─ lang/
│           │  ├─ CloseableExtensions.kt
│           │  ├─ DateExtensions.kt
│           │  └─ RectFExtensions.kt
│           ├─ storage/
│           │  ├─ FileExtensions.kt
│           │  └─ OkioExtensions.kt
│           ├─ system/
│           │  ├─ AnimationExtensions.kt
│           │  ├─ AuthenticatorUtil.kt
│           │  ├─ BuildConfig.kt
│           │  ├─ ChildFirstPathClassLoader.kt
│           │  ├─ ContextExtensions.kt
│           │  ├─ DeviceUtilExtensions.kt
│           │  ├─ DisplayExtensions.kt
│           │  ├─ DrawableExtensions.kt
│           │  ├─ IntentExtensions.kt
│           │  ├─ InternalResourceHelper.kt
│           │  ├─ LocaleHelper.kt
│           │  ├─ NetworkExtensions.kt
│           │  ├─ NetworkStateTracker.kt
│           │  ├─ NotificationExtensions.kt
│           │  └─ WorkManagerExtensions.kt
│           └─ view/
│              ├─ EditTextPreferenceExtensions.kt
│              ├─ ViewExtensions.kt
│              └─ WindowExtensions.kt
├─ mihon/
│  ├─ core/
│  │  ├─ designsystem/
│  │  │  └─ utils/
│  │  │     └─ WindowSize.kt
│  │  └─ migration/
│  │     ├─ Migration.kt
│  │     ├─ MigrationCompletedListener.kt
│  │     ├─ MigrationContext.kt
│  │     ├─ MigrationJobFactory.kt
│  │     ├─ MigrationStrategy.kt
│  │     ├─ MigrationStrategyFactory.kt
│  │     ├─ Migrator.kt
│  │     └─ migrations/
│  │        ├─ Migrations.kt
│  │        ├─ SetupBackupCreateMigration.kt
│  │        ├─ SetupLibraryUpdateMigration.kt
│  │        └─ TrustExtensionRepositoryMigration.kt
│  └─ feature/
│     └─ upcoming/
│        ├─ UpcomingScreen.kt
│        ├─ UpcomingScreenContent.kt
│        ├─ UpcomingScreenModel.kt
│        ├─ UpcomingUIModel.kt
│        └─ components/
│           ├─ UpcomingItem.kt
│           └─ calendar/
│              ├─ Calendar.kt
│              ├─ CalendarDay.kt
│              ├─ CalendarHeader.kt
│              └─ CalendarIndicator.kt
└─ test/
   └─ DummyTracker.kt
```

---

## app/src/main/res

```text
app/src/main/res/
├─ anim/
├─ anim-v33/
├─ color/
├─ drawable/
├─ drawable-nodpi/
├─ font/
├─ layout/
├─ menu/
├─ mipmap/
├─ mipmap-hdpi/
├─ mipmap-mdpi/
├─ mipmap-xhdpi/
├─ mipmap-xxhdpi/
├─ mipmap-xxxhdpi/
├─ values/
├─ values-night/
├─ values-v27/
├─ values-v31/
└─ xml/
```

---

## core/common/src/main/kotlin

```text
core/common/src/main/kotlin/
├─ eu/kanade/tachiyomi/
│  ├─ core/security/
│  │  ├─ PrivacyPreferences.kt
│  │  └─ SecurityPreferences.kt
│  ├─ network/
│  │  ├─ AndroidCookieJar.kt
│  │  ├─ DohProviders.kt
│  │  ├─ JavaScriptEngine.kt
│  │  ├─ NetworkHelper.kt
│  │  ├─ NetworkPreferences.kt
│  │  ├─ OkHttpExtensions.kt
│  │  ├─ ProgressListener.kt
│  │  ├─ ProgressResponseBody.kt
│  │  ├─ Requests.kt
│  │  └─ interceptor/
│  │     ├─ CloudflareInterceptor.kt
│  │     ├─ IgnoreGzipInterceptor.kt
│  │     ├─ RateLimitInterceptor.kt
│  │     ├─ SpecificHostRateLimitInterceptor.kt
│  │     ├─ UncaughtExceptionInterceptor.kt
│  │     ├─ UserAgentInterceptor.kt
│  │     └─ WebViewInterceptor.kt
│  └─ util/
│     ├─ lang/
│     │  ├─ Hash.kt
│     │  └─ StringExtensions.kt
│     └─ system/
│        ├─ DensityExtensions.kt
│        ├─ DeviceUtil.kt
│        ├─ GLUtil.kt
│        ├─ ToastExtensions.kt
│        └─ WebViewUtil.kt
└─ tachiyomi/core/common/
   ├─ Constants.kt
   ├─ i18n/
   │  └─ Localize.kt
   ├─ preference/
   │  ├─ AndroidPreference.kt
   │  ├─ AndroidPreferenceStore.kt
   │  ├─ CheckboxState.kt
   │  ├─ InMemoryPreferenceStore.kt
   │  ├─ Preference.kt
   │  ├─ PreferenceStore.kt
   │  └─ TriState.kt
   ├─ storage/
   │  ├─ AndroidStorageFolderProvider.kt
   │  ├─ FolderProvider.kt
   │  └─ UniFileExtensions.kt
   └─ util/
      ├─ lang/
      │  ├─ BooleanExtensions.kt
      │  ├─ CoroutinesExtensions.kt
      │  ├─ RxCoroutineBridge.kt
      │  └─ SortUtil.kt
      └─ system/
         ├─ ImageUtil.kt
         └─ LogcatExtensions.kt
```

---

## core/archive/src/main/kotlin

```text
core/archive/src/main/kotlin/mihon/core/archive/
├─ ArchiveEntry.kt
├─ ArchiveInputStream.kt
├─ ArchiveReader.kt
├─ EpubReader.kt
├─ UniFileExtensions.kt
└─ ZipWriter.kt
```

---

## core-metadata/src/main/java

```text
core-metadata/src/main/java/tachiyomi/core/metadata/
├─ comicinfo/ComicInfo.kt
└─ tachiyomi/MangaDetails.kt
```

---

## domain/src/main/java

```text
domain/src/main/java/tachiyomi/domain/
├─ category/
│  ├─ interactor/
│  │  ├─ CreateCategoryWithName.kt
│  │  ├─ DeleteCategory.kt
│  │  ├─ GetCategories.kt
│  │  ├─ RenameCategory.kt
│  │  ├─ ReorderCategory.kt
│  │  ├─ ResetCategoryFlags.kt
│  │  ├─ SetDisplayMode.kt
│  │  ├─ SetMangaCategories.kt
│  │  ├─ SetSortModeForCategory.kt
│  │  └─ UpdateCategory.kt
│  ├─ model/
│  │  ├─ Category.kt
│  │  └─ CategoryUpdate.kt
│  └─ repository/
│     └─ CategoryRepository.kt
├─ chapter/
│  ├─ interactor/
│  │  ├─ GetChapter.kt
│  │  ├─ GetChapterByUrlAndMangaId.kt
│  │  ├─ GetChaptersByMangaId.kt
│  │  ├─ SetMangaDefaultChapterFlags.kt
│  │  ├─ ShouldUpdateDbChapter.kt
│  │  └─ UpdateChapter.kt
│  ├─ model/
│  │  ├─ Chapter.kt
│  │  ├─ ChapterUpdate.kt
│  │  └─ NoChaptersException.kt
│  ├─ repository/
│  │  └─ ChapterRepository.kt
│  └─ service/
│     ├─ ChapterRecognition.kt
│     ├─ ChapterSort.kt
│     └─ MissingChapters.kt
├─ download/
│  └─ service/
│     └─ DownloadPreferences.kt
├─ history/
│  ├─ interactor/
│  │  ├─ GetHistory.kt
│  │  ├─ GetNextChapters.kt
│  │  ├─ GetTotalReadDuration.kt
│  │  ├─ RemoveHistory.kt
│  │  └─ UpsertHistory.kt
│  ├─ model/
│  │  ├─ History.kt
│  │  ├─ HistoryUpdate.kt
│  │  └─ HistoryWithRelations.kt
│  └─ repository/
│     └─ HistoryRepository.kt
├─ library/
│  ├─ model/
│  │  ├─ Flag.kt
│  │  ├─ LibraryDisplayMode.kt
│  │  ├─ LibraryManga.kt
│  │  └─ LibrarySortMode.kt
│  └─ service/
│     └─ LibraryPreferences.kt
├─ manga/
│  ├─ interactor/
│  │  ├─ FetchInterval.kt
│  │  ├─ GetDuplicateLibraryManga.kt
│  │  ├─ GetFavorites.kt
│  │  ├─ GetLibraryManga.kt
│  │  ├─ GetManga.kt
│  │  ├─ GetMangaByUrlAndSourceId.kt
│  │  ├─ GetMangaWithChapters.kt
│  │  ├─ NetworkToLocalManga.kt
│  │  ├─ ResetViewerFlags.kt
│  │  └─ SetMangaChapterFlags.kt
│  ├─ model/
│  │  ├─ Manga.kt
│  │  ├─ MangaCover.kt
│  │  ├─ MangaUpdate.kt
│  │  └─ TriState.kt
│  └─ repository/
│     └─ MangaRepository.kt
├─ release/
│  ├─ interactor/
│  │  └─ GetApplicationRelease.kt
│  ├─ model/
│  │  └─ Release.kt
│  └─ service/
│     └─ ReleaseService.kt
├─ source/
│  ├─ interactor/
│  │  ├─ GetRemoteManga.kt
│  │  └─ GetSourcesWithNonLibraryManga.kt
│  ├─ model/
│  │  ├─ Pin.kt
│  │  ├─ Source.kt
│  │  ├─ SourceWithCount.kt
│  │  └─ StubSource.kt
│  ├─ repository/
│  │  ├─ SourceRepository.kt
│  │  └─ StubSourceRepository.kt
│  └─ service/
│     └─ SourceManager.kt
├─ storage/
│  └─ service/
│     ├─ StorageManager.kt
│     └─ StoragePreferences.kt
├─ track/
│  ├─ interactor/
│  │  ├─ DeleteTrack.kt
│  │  ├─ GetTracks.kt
│  │  ├─ GetTracksPerManga.kt
│  │  └─ InsertTrack.kt
│  ├─ model/
│  │  └─ Track.kt
│  └─ repository/
│     └─ TrackRepository.kt
├─ translation/
│  ├─ KeystoreApiKeyManager.kt
│  ├─ TranslationPreferences.kt
│  ├─ pools/
│  │  ├─ BitmapPool.kt
│  │  └─ DirectBufferPool.kt
│  └─ validation/
│     └─ PreferenceValidator.kt
├─ updates/
│  ├─ interactor/
│  │  └─ GetUpdates.kt
│  ├─ model/
│  │  └─ UpdatesWithRelations.kt
│  └─ repository/
│     └─ UpdatesRepository.kt
└─ DomainModule.kt
```

---

## data/src/main/java

```text
data/src/main/java/
├─ mihon/data/repository/
│  └─ ExtensionRepoRepositoryImpl.kt
└─ tachiyomi/data/
   ├─ AndroidDatabaseHandler.kt
   ├─ DatabaseAdapter.kt
   ├─ DatabaseHandler.kt
   ├─ QueryPagingSource.kt
   ├─ TransactionContext.kt
   ├─ category/
   │  └─ CategoryRepositoryImpl.kt
   ├─ chapter/
   │  ├─ ChapterRepositoryImpl.kt
   │  └─ ChapterSanitizer.kt
   ├─ history/
   │  ├─ HistoryMapper.kt
   │  └─ HistoryRepositoryImpl.kt
   ├─ manga/
   │  ├─ MangaMapper.kt
   │  └─ MangaRepositoryImpl.kt
   ├─ release/
   │  ├─ GithubRelease.kt
   │  └─ ReleaseServiceImpl.kt
   ├─ source/
   │  ├─ SourcePagingSource.kt
   │  ├─ SourceRepositoryImpl.kt
   │  └─ StubSourceRepositoryImpl.kt
   ├─ track/
   │  ├─ TrackMapper.kt
   │  └─ TrackRepositoryImpl.kt
   └─ updates/
      └─ UpdatesRepositoryImpl.kt
```

### data/src/main/sqldelight

```text
data/src/main/sqldelight/tachiyomi/
├─ data/
│  ├─ categories.sq
│  ├─ chapters.sq
│  ├─ excluded_scanlators.sq
│  ├─ extension_repos.sq
│  ├─ history.sq
│  ├─ manga_sync.sq
│  ├─ mangas.sq
│  ├─ mangas_categories.sq
│  └─ sources.sq
├─ migrations/
│  ├─ 1.sqm
│  ├─ 2.sqm
│  └─ 3.sqm
└─ view/
   ├─ historyView.sq
   ├─ libraryView.sq
   └─ updatesView.sq
```

---

## source-api/src

```text
source-api/src/
├─ commonMain/kotlin/eu/kanade/tachiyomi/
│  ├─ source/
│  │  ├─ CatalogueSource.kt
│  │  ├─ ConfigurableSource.kt
│  │  ├─ PreferenceScreen.kt
│  │  ├─ Source.kt
│  │  ├─ SourceFactory.kt
│  │  ├─ UnmeteredSource.kt
│  │  ├─ model/
│  │  │  ├─ Filter.kt
│  │  │  ├─ FilterList.kt
│  │  │  ├─ MangasPage.kt
│  │  │  ├─ Page.kt
│  │  │  ├─ SChapter.kt
│  │  │  ├─ SChapterImpl.kt
│  │  │  ├─ SManga.kt
│  │  │  ├─ SMangaImpl.kt
│  │  │  └─ UpdateStrategy.kt
│  │  └─ online/
│  │     ├─ HttpSource.kt
│  │     ├─ ParsedHttpSource.kt
│  │     └─ ResolvableSource.kt
│  └─ util/
│     ├─ JsoupExtensions.kt
│     └─ RxExtension.kt
└─ androidMain/kotlin/eu/kanade/tachiyomi/
   ├─ source/
   │  └─ PreferenceScreen.kt
   └─ util/
      └─ RxExtension.kt
```

---

## source-local/src

```text
source-local/src/
├─ commonMain/kotlin/tachiyomi/source/local/
│  ├─ LocalSource.kt
│  ├─ image/
│  │  └─ LocalCoverManager.kt
│  └─ io/
│     ├─ Archive.kt
│     ├─ Format.kt
│     └─ LocalSourceFileSystem.kt
└─ androidMain/kotlin/tachiyomi/source/local/
   ├─ LocalSource.kt
   ├─ filter/
   │  └─ OrderBy.kt
   ├─ image/
   │  └─ LocalCoverManager.kt
   ├─ io/
   │  └─ LocalSourceFileSystem.kt
   └─ metadata/
      └─ EpubReaderExtensions.kt
```

---

## presentation-core/src/main

```text
presentation-core/src/main/
├─ java/
│  ├─ mihon/presentation/core/util/PagingDataUtil.kt
│  └─ tachiyomi/presentation/core/
│     ├─ components/
│     │  ├─ ActionButton.kt
│     │  ├─ AdaptiveSheet.kt
│     │  ├─ Badges.kt
│     │  ├─ CircularProgressIndicator.kt
│     │  ├─ CollapsibleBox.kt
│     │  ├─ LabeledCheckbox.kt
│     │  ├─ LazyColumnWithAction.kt
│     │  ├─ LazyGrid.kt
│     │  ├─ LazyList.kt
│     │  ├─ LinkIcon.kt
│     │  ├─ ListGroupHeader.kt
│     │  ├─ Pill.kt
│     │  ├─ SectionCard.kt
│     │  ├─ SettingsItems.kt
│     │  ├─ TwoPanelBox.kt
│     │  ├─ VerticalFastScroller.kt
│     │  ├─ WheelPicker.kt
│     │  └─ material/
│     │     ├─ AlertDialog.kt
│     │     ├─ Button.kt
│     │     ├─ Constants.kt
│     │     ├─ FloatingActionButton.kt
│     │     ├─ IconButtonTokens.kt
│     │     ├─ IconToggleButton.kt
│     │     ├─ NavigationBar.kt
│     │     ├─ NavigationRail.kt
│     │     ├─ PullRefresh.kt
│     │     ├─ Scaffold.kt
│     │     ├─ Slider.kt
│     │     ├─ Surface.kt
│     │     └─ Tabs.kt
│     ├─ i18n/Localize.kt
│     ├─ icons/
│     │  ├─ CustomIcons.kt
│     │  ├─ Discord.kt
│     │  ├─ Facebook.kt
│     │  ├─ Github.kt
│     │  ├─ Reddit.kt
│     │  └─ X.kt
│     ├─ screens/
│     │  ├─ EmptyScreen.kt
│     │  ├─ InfoScreen.kt
│     │  └─ LoadingScreen.kt
│     ├─ theme/
│     │  ├─ Color.kt
│     │  └─ Typography.kt
│     └─ util/
│        ├─ Elevation.kt
│        ├─ LazyListState.kt
│        ├─ Modifier.kt
│        ├─ PaddingValues.kt
│        ├─ Preference.kt
│        └─ Scrollbar.kt
└─ res/
   ├─ values/
   │  ├─ colors.xml
   │  ├─ colors_greenapple.xml
   │  ├─ colors_lavender.xml
   │  ├─ colors_midnightdusk.xml
   │  ├─ colors_nord.xml
   │  ├─ colors_strawberry.xml
   │  ├─ colors_tachiyomi.xml
   │  ├─ colors_tako.xml
   │  ├─ colors_tealturqoise.xml
   │  ├─ colors_tidalwave.xml
   │  ├─ colors_yinyang.xml
   │  └─ colors_yotsuba.xml
   └─ values-night/
      ├─ colors.xml
      ├─ colors_greenapple.xml
      ├─ colors_lavender.xml
      ├─ colors_midnightdusk.xml
      ├─ colors_nord.xml
      ├─ colors_strawberry.xml
      ├─ colors_tachiyomi.xml
      ├─ colors_tako.xml
      ├─ colors_tealturqoise.xml
      ├─ colors_tidalwave.xml
      ├─ colors_yinyang.xml
      └─ colors_yotsuba.xml
```

---

## presentation-widget/src/main

```text
presentation-widget/src/main/
├─ java/tachiyomi/presentation/widget/
│  ├─ BaseUpdatesGridGlanceWidget.kt
│  ├─ UpdatesGridCoverScreenGlanceReceiver.kt
│  ├─ UpdatesGridCoverScreenGlanceWidget.kt
│  ├─ UpdatesGridGlanceReceiver.kt
│  ├─ UpdatesGridGlanceWidget.kt
│  ├─ WidgetManager.kt
│  ├─ components/
│  │  ├─ LockedWidget.kt
│  │  ├─ UpdatesMangaCover.kt
│  │  └─ UpdatesWidget.kt
│  └─ util/
│     └─ GlanceUtils.kt
└─ res/
   ├─ drawable-nodpi/
   │  ├─ updates_grid_coverscreen_widget_preview.webp
   │  └─ updates_grid_widget_preview.webp
   ├─ drawable/
   │  ├─ appwidget_background.xml
   │  ├─ appwidget_cover_error.xml
   │  └─ appwidget_coverscreen_background.xml
   ├─ layout/
   │  ├─ appwidget_coverscreen_loading.xml
   │  └─ appwidget_loading.xml
   ├─ values/
   │  ├─ colors_appwidget.xml
   │  └─ dimens.xml
   ├─ values-v31/
   │  ├─ colors_appwidget.xml
   │  └─ dimens.xml
   ├─ values-night-v31/
   │  └─ colors_appwidget.xml
   └─ xml/
      ├─ updates_grid_homescreen_widget_info.xml
      ├─ updates_grid_lockscreen_widget_info.xml
      └─ updates_grid_samsung_cover_widget_info.xml
```

---

## i18n

```text
i18n/src/commonMain/moko-resources/
├─ base/{strings.xml, plurals.xml}
├─ am/{strings.xml, plurals.xml}
├─ ar/{strings.xml, plurals.xml}
├─ ...
├─ zh-rCN/{strings.xml, plurals.xml}
└─ zh-rTW/{strings.xml, plurals.xml}
```

---

## i18n-at

```text
i18n-at/src/commonMain/moko-resources/
└─ base/strings.xml
```

---

## macrobenchmark

```text
macrobenchmark/src/main/java/tachiyomi/macrobenchmark/
├─ BaselineProfileGenerator.kt
└─ StartupBenchmark.kt
```
