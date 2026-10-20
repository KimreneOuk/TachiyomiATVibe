package eu.kanade.translation.workflow

import android.content.Context
import androidx.annotation.VisibleForTesting
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.translationQueueAdmissionFailureKind
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.persistence.ArtifactManifestProbe
import eu.kanade.translation.persistence.ChapterAttemptLedgerDocument
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterResetPreflight
import eu.kanade.translation.persistence.ChapterStoreOpener
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.DurableChapterRead
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.coordinator.ReaderSessionToken
import eu.kanade.translation.coordinator.ReaderSubmission
import eu.kanade.translation.coordinator.ReaderTrigger
import eu.kanade.translation.coordinator.TranslationSession
import eu.kanade.translation.coordinator.TranslationStoreResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun TranslationManager.initializeTranslationManager() {
    if (isProductionConstruction) {
        // Share the store instance between reader and translator so live updates do not need a chapter reload.
        pipeline.activeStoreResolver = { translation ->
            openOrCreateActiveChapterTranslationStoreSuspend(
                translation.chapter.id!!,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
                translation.manga.id,
            )
        }
        pipeline.onBatchClosed = { manga, chapter, source, store ->
            chapter.id?.let { chapterId ->
                sweepOrphanedCleanedImages(
                    store = store,
                    chapterId = chapterId,
                    chapterName = chapter.name,
                    scanlator = chapter.scanlator,
                    mangaTitle = manga.title,
                    source = source,
                    mangaId = manga.id,
                )
            }
            if (!isAnyBatchTranslationActive && queueState.value.none { it.status == Translation.State.PAUSED }) {
                sessionCoordinator.finishSession(TranslationSessionState.BATCH_SESSION)
            }
        }
        // NOTE: activeStoreUnregister is intentionally NOT wired. Evicting after
        // each single-page translation broke live updates (reader captured the
        // StateFlow once; next translate got a fresh unobserved store). Eviction
        // now happens only on chapter change / reader exit (cancelPageTranslations /
        // cancelAllPageTranslations callers).

        // Native quarantine reports timeout only after the underlying call exits;
        // evict the stale job so a subsequent request can be admitted safely.
        pipeline.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                readerCoordinator.cancelPageAsync(chapterId.toString(), pageKey)
            }
        }

        // Batch tracker factory: lets the pipeline create and register a tracker per active batch (observable via observeBatchProgress).
        pipeline.batchTrackerFactory = { chapterId, store, orderedPageKeys ->
            createBatchTracker(chapterId, store, orderedPageKeys)
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
            translator.queueState.collect {
                durableStatusResolver.clearDurableStatusCache()
            }
        }
        applicationScope.launch {
            // A paused chapter is not an active foreground job, but its durable
            // outcome still deserves a visible notification after process
            // restart. The service owns only the active QUEUE/TRANSLATING
            // predicate; this collector owns the detached paused projection.
            statusFlow().collect { translation ->
                if (translation.status == Translation.State.PAUSED && !isAnyBatchTranslationActive) {
                    val snapshot = translation.chapter.id?.let { chapterId ->
                        getTranslationProgress(chapterId)?.firstOrNull()
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
            translator.restoreQueue()
            runStartupReconciliationIfReady()
        }.also { translationQueueRestoreJob = it }
    }

}

internal fun TranslationManager.createCoordinatorRuntime(): TranslationManagerCoordinators = TranslationManagerCoordinators(
        context = context,
        provider = provider,
        preferences = translationPreferences,
        pipeline = pipeline,
        streamRegistry = streamRegistry,
        scope = applicationScope,
        isBatchActive = { chapterId -> isChapterBatchActive(chapterId, includePaused = false) },
        resolveReaderStore = testReaderStoreResolver ?: { manga, chapter, source ->
            chapter.id?.let { chapterId ->
                openOrCreateActiveChapterTranslationStoreSuspend(
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
            if (chapterId in chaptersUnderDownloadRekey || isChapterBatchActive(chapterId, includePaused = true)) {
                false
            } else {
                sessionCoordinator.requestReaderSession(ReaderSessionIntent(chapterId)) !is SessionAdmission.Rejected
            }
        },
        beforeOpenReaderSession = { chapterId -> clearStaleDownloadFailedRequest(chapterId) },
        manualStreamProvider = { manga, chapter, source, pageKey ->
            streamRegistry.peek(manga, chapter, source, pageKey)
                ?: downloadedPageStreamFactory(manga, chapter, source, pageKey)
        },
        isAutoTranslateEnabled = { translationPreferences.autoTranslate().get() },
        readerCoordinatorOverride = testReaderCoordinator,
    )


internal fun TranslationManager.createRequestCoordinator(): TranslationRequestCoordinator = TranslationRequestCoordinator(
            pendingRequestStore = createPendingRequestStoreForCoordinator(),
            initialPendingRequests = initialPendingRequestsForCoordinator(),
            storeScopeProvider = { storeScope },
            queueStateProvider = { queueState },
            translatorProvider = { translator },
            getQueuedTranslationOrNull = { chapterId -> getQueuedTranslationOrNull(chapterId) },
            translateChapter = { manga, chapter, expectedRequestGeneration, autoStart ->
                translateChapter(manga, chapter, expectedRequestGeneration, autoStart = autoStart)
            },
            hasPendingDownloadRekeyFn = { chapterId -> hasPendingDownloadRekey(chapterId) },
            reconcilePendingDownloadRekeyFn = { chapterId ->
                reconcilePendingDownloadRekeyForAdmission(chapterId)
            },
        )


internal fun TranslationManager.createReaderTeardown(): ReaderTeardownCoordinator = ReaderTeardownCoordinator(
            applicationScopeProvider = { applicationScope },
            readerTeardownMutexProvider = { readerTeardownMutex },
            activeStoresProvider = { activeStores },
            translatorProvider = { translator },
            readerCoordinatorProvider = { readerCoordinator },
            sessionCoordinatorProvider = { sessionCoordinator },
            isAnyBatchTranslationActiveProvider = { isAnyBatchTranslationActive },
            isChapterBatchActiveFn = { chapterId, includePaused ->
                isChapterBatchActive(chapterId, includePaused)
            },
            unregisterActiveTranslationStoreFn = { chapterId -> unregisterActiveTranslationStore(chapterId) },
            disposeBatchTrackerFn = { chapterId -> disposeBatchTracker(chapterId) },
            clearAllPendingTranslationRequestsFn = { clearAllPendingTranslationRequests() },
            retryPendingDownloadRekeysAfterReaderLeaveFn = { retryPendingDownloadRekeysAfterReaderLeave() },
        )


internal fun TranslationManager.createProgressProjection(): TranslationProgressProjection = TranslationProgressProjection(
            activeStoresProvider = { activeStores },
            batchTrackerRegistryProvider = { batchTrackerRegistry },
            queueStateProvider = { queueState },
            pendingTranslationRequestsProvider = { pendingTranslationRequests },
            pipelineProvider = { pipeline },
            getQueuedTranslationOrNull = { chapterId -> getQueuedTranslationOrNull(chapterId) },
            persistedChapterStatus = { chapterId, chapterName, chapterScanlator, mangaTitle, sourceId ->
                persistedChapterStatus(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)
            },
            openOrCreateStoreSuspend = { chapterId, chapterName, scanlator, mangaTitle, source, mangaId ->
                openOrCreateActiveChapterTranslationStoreSuspend(
                    chapterId,
                    chapterName,
                    scanlator,
                    mangaTitle,
                    source,
                    mangaId,
                )
            },
            observeActiveDisplayStore = { chapterId -> observeActiveDisplayStore(chapterId) },
            reconstructDurableTerminalSnapshot = { chapterId ->
                reconstructDurableTerminalSnapshot(chapterId)
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
            findTranslationDocumentFn = { chapterName, scanlator, mangaTitle, source ->
                findTranslationDocument(chapterName, scanlator, mangaTitle, source)
            },
            cancelPageTranslationsFn = { chapterId -> cancelPageTranslations(chapterId) },
            cancelPageTranslationFn = { chapterId, pageKey -> cancelPageTranslation(chapterId, pageKey) },
            removeFromTranslationQueueFn = { chapter -> removeFromTranslationQueue(chapter) },
            translatorProvider = { translator },
            disposeBatchTrackerFn = { chapterId -> disposeBatchTracker(chapterId) },
            unregisterActiveTranslationStoreFn = { chapterId -> unregisterActiveTranslationStore(chapterId) },
            streamRegistryProvider = { streamRegistry },
            providerProvider = { provider },
            retireChapterCompanionImagesFn = { manga, chapter, source ->
                retireChapterCompanionImages(manga, chapter, source)
            },
            retirePageCompanionImageFn = { manga, chapter, source, pageKey, imageName, isReferencedElsewhere ->
                retirePageCompanionImage(manga, chapter, source, pageKey, imageName, isReferencedElsewhere)
            },
            durableStatusResolverProvider = { durableStatusResolver },
            activeStoresProvider = { activeStores },
            openExistingChapterTranslationStoreFn = { chapterId, chapterName, scanlator, mangaTitle, source ->
                openExistingChapterTranslationStore(chapterId, chapterName, scanlator, mangaTitle, source)
            },
        )
