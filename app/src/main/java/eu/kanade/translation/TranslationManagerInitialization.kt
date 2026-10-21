package eu.kanade.translation

import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.ReaderSessionIntent
import eu.kanade.translation.coordinator.SessionAdmission
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.coordinator.TranslationProgressProjection
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.coordinator.TranslationRequestCoordinator
import eu.kanade.translation.coordinator.reconstructDurableTerminalSnapshot
import eu.kanade.translation.coordinator.resolveTranslationFromChapterId
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ChapterDataResetController
import eu.kanade.translation.persistence.CleanedImageLifecycleController
import eu.kanade.translation.persistence.DurableChapterStatusResolver
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import uy.kohesive.injekt.api.get

internal fun TranslationManager.initializeTranslationManager() {
    if (isProductionConstruction) {
        // Share the store instance between reader and translator so live updates do not need a chapter reload.
        // NOTE: activeStoreUnregister is intentionally NOT wired. Evicting after
        // each single-page translation broke live updates (reader captured the
        // StateFlow once; next translate got a fresh unobserved store). Eviction
        // now happens only on chapter change / reader exit (cancelPageTranslations /
        // cancelAllPageTranslations callers).

        // Native quarantine reports timeout only after the underlying call exits;
        // evict the stale job so a subsequent request can be admitted safely.
        processor.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                readerCoordinator.cancelPageAsync(chapterId.toString(), pageKey)
            }
        }

        // Rehydrate persisted batch queue on IO so a crash mid-batch no longer loses it.
        // Entries get status QUEUE; user taps Start to resume — never auto-starts OCR/LLM on launch.
        applicationScope.launch {
            // Any queue emission can follow a durable transition (completion,
            // failure, cancel). Membership-set comparison is not enough: a
            // conflated remove→re-add of the same chapter reproduces the same
            // membership set while durable truth changed in between, and the
            // arm-after-pause path re-emits the same list verbatim. Wipe on
            // every emission — the clear is two map clears, and emissions only
            // fire on queue mutations, never on batch progress ticks.
            batchCoordinator.queueState.collect {
                durableStatusResolver.clearDurableStatusCache()
            }
        }
        applicationScope.launch {
            // A paused chapter is not an active foreground job, but its durable
            // outcome still deserves a visible notification after process
            // restart. The service owns only the active QUEUE/TRANSLATING
            // predicate; this collector owns the detached paused projection.
            batchCoordinator.statusFlow().collect { translation ->
                if (translation.status == Translation.State.PAUSED && !batchCoordinator.isAnyBatchTranslationActive) {
                    val snapshot = translation.chapter.id?.let { chapterId ->
                        progressProjection.observeBatchProgress(chapterId).firstOrNull()
                    }
                    TranslationForegroundService.showPaused(
                        context = context,
                        chapterName = translation.chapter.name,
                        chapterId = translation.chapter.id,
                        snapshot = snapshot,
                    )
                }
            }
        }
        applicationScope.launch {
            // After the downloader reports its restore too, run the one-shot
            // pending-request reconciliation pass.
            batchCoordinator.restoreQueue()
            requestCoordinator.runStartupReconciliationIfReady()
        }.also { requestCoordinator.translationQueueRestoreStarted(it) }
    }
}

internal fun TranslationManager.createCoordinatorRuntime(): TranslationManagerCoordinators = TranslationManagerCoordinators(
    context = context,
    provider = provider,
    preferences = translationPreferences,
    processorOverride = processor,
    streamRegistry = streamRegistry,
    downloadProvider = downloadProvider,
    sourceManager = sourceManager,
    queueStore = TranslationQueueStore(context),
    batchStoreResolver = { translation ->
        translation.chapter.id?.let { chapterId ->
            chapterTranslationAccess.openOrCreateActiveChapterTranslationStore(
                chapterId,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
                translation.manga.id,
            )
        }
    },
    batchTrackerFactory = { chapterId, store, pageKeys ->
        batchTrackerRegistry.createTracker(
            chapterId = chapterId,
            store = store,
            orderedPageKeys = pageKeys,
            scope = storeScope,
            permitHolderResolver = { processor.permitHolderPageKeySnapshot() },
        )
    },
    onBatchClosed = { manga, chapter, source, store ->
        chapter.id?.let { chapterId ->
            cleanedImageLifecycle.sweepOrphanedCleanedImages(
                store = store,
                chapterId = chapterId,
                chapterName = chapter.name,
                scanlator = chapter.scanlator,
                mangaTitle = manga.title,
                source = source,
                mangaId = manga.id,
            )
        }
    },
    batchCoordinatorOverride = testConstruction?.batchCoordinator,
    scope = applicationScope,
    isBatchActive = { chapterId -> batchCoordinator.isChapterBatchActive(chapterId, includePaused = false) },
    resolveReaderStore = testReaderStoreResolver ?: { manga, chapter, source ->
        chapter.id?.let { chapterId ->
            chapterTranslationAccess.openOrCreateActiveChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
                manga.id,
            )
        }
    },
    admitReaderSession = { chapterId ->
        if (completedDownloadRekeyCoordinator.isChapterRekeying(chapterId) ||
            batchCoordinator.isChapterBatchActive(chapterId, includePaused = true)
        ) {
            false
        } else {
            sessionCoordinator.requestReaderSession(ReaderSessionIntent(chapterId)) !is SessionAdmission.Rejected
        }
    },
    beforeOpenReaderSession = { chapterId -> requestCoordinator.clearStaleDownloadFailedRequest(chapterId) },
    activeStoresProvider = { activeStores },
    sessionCoordinator = sessionCoordinator,
    clearPendingRequests = requestCoordinator::clearAllPendingTranslationRequests,
    disposeBatchTracker = batchTrackerRegistry::dispose,
    unregisterActiveStore = { chapterId -> chapterTranslationAccess.unregisterActiveTranslationStore(chapterId) },
    retryPendingRekeysAfterReaderLeave = { completedDownloadRekeyCoordinator.retryPendingAfterReaderLeave() },
    manualStreamProvider = { manga, chapter, source, pageKey ->
        streamRegistry.peek(manga, chapter, source, pageKey)
            ?: downloadedPageStreams.resolve(manga, chapter, source, pageKey)
    },
    isAutoTranslateEnabled = { translationPreferences.autoTranslate().get() },
    readerCoordinatorOverride = testReaderCoordinator,
)

internal fun TranslationManager.createRequestCoordinator(): TranslationRequestCoordinator = TranslationRequestCoordinator(
    pendingRequestStore = testConstruction?.pendingRequestStore ?: TranslationPendingRequestStore(context),
    initialPendingRequests = testConstruction?.pendingRequests,
    storeScopeProvider = { storeScope },
    batchCoordinatorProvider = { batchCoordinator },
    startBatchTranslation = { batchCoordinator.startWithSessionAdmission() },
    sessionCoordinator = sessionCoordinator,
    cancelReaderChapterAsync = { chapterId -> readerCoordinator.cancelChapterAsync(chapterId.toString()) },
    sourceIsHttp = { manga -> sourceManager.get(manga.source) is HttpSource },
    resolveTranslation = { chapterId -> resolveTranslationFromChapterId(chapterId, sourceManager = sourceManager) },
    hasDownloadedFiles = { translation ->
        downloadProvider.findChapterDir(
            translation.chapter.name,
            translation.chapter.scanlator,
            translation.manga.title,
            translation.source,
        ) != null
    },
    hasPendingDownloadRekeyFn = { chapterId -> completedDownloadRekeyCoordinator.hasPendingRekey(chapterId) },
    reconcilePendingDownloadRekeyFn = { chapterId ->
        completedDownloadRekeyCoordinator.reconcilePendingRekeyForAdmission(chapterId)
    },
)

internal fun TranslationManager.createProgressProjection(): TranslationProgressProjection = TranslationProgressProjection(
    activeStoresProvider = { activeStores },
    batchTrackerRegistryProvider = { batchTrackerRegistry },
    queueStateProvider = { batchCoordinator.queueState },
    pendingTranslationRequestsProvider = { requestCoordinator.pendingTranslationRequests },
    permitHolderPageKeyProvider = { processor.permitHolderPageKeySnapshot() },
    getQueuedTranslationOrNull = batchCoordinator::queuedTranslationOrNull,
    persistedChapterStatus = { chapterId, chapterName, chapterScanlator, mangaTitle, sourceId ->
        durableStatusResolver.persistedChapterStatus(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)
    },
    openOrCreateStoreSuspend = chapterTranslationAccess::openOrCreateActiveChapterTranslationStore,
    observeActiveDisplayStore = { chapterId -> activeStores.get(chapterId)?.display },
    reconstructDurableTerminalSnapshot = { chapterId ->
        reconstructDurableTerminalSnapshot(
            chapterId,
            resolveTranslation = { id -> resolveTranslationFromChapterId(id, sourceManager = sourceManager) },
            durableStatusResolver = durableStatusResolver,
        )
    },
)

internal fun TranslationManager.createDurableStatusResolver(): DurableChapterStatusResolver = DurableChapterStatusResolver(
    providerProvider = { provider },
    sourceManagerProvider = { sourceManager },
    activeStoresProvider = { activeStores },
)

internal fun TranslationManager.createCleanedImageLifecycle(): CleanedImageLifecycleController = CleanedImageLifecycleController(
    applicationScopeProvider = { applicationScope },
    streamRegistryProvider = { streamRegistry },
    providerProvider = { provider },
)

internal fun TranslationManager.createChapterDataReset(): ChapterDataResetController = ChapterDataResetController(
    findTranslationDocumentFn = chapterTranslationAccess::findTranslationDocument,
    cancelPageTranslationsFn = coordinatorRuntime.readerCoordinator::cancelPageTranslations,
    cancelPageTranslationFn = coordinatorRuntime.readerCoordinator::cancelPageTranslation,
    removeFromTranslationQueueFn = batchCoordinator::removeChapterAndResume,
    translatorProvider = { batchCoordinator },
    disposeBatchTrackerFn = batchTrackerRegistry::dispose,
    unregisterActiveTranslationStoreFn = chapterTranslationAccess::unregisterActiveTranslationStore,
    streamRegistryProvider = { streamRegistry },
    providerProvider = { provider },
    retireChapterCompanionImagesFn = cleanedImageLifecycle::retireChapterCompanionImages,
    retirePageCompanionImageFn = cleanedImageLifecycle::retirePageCompanionImage,
    durableStatusResolverProvider = { durableStatusResolver },
    activeStoresProvider = { activeStores },
    openExistingChapterTranslationStoreFn = chapterTranslationAccess::openExistingChapterTranslationStore,
)
