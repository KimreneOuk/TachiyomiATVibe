package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.model.Translation
import eu.kanade.translation.coordinator.TranslationSession
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.BatchPageOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.api.get

/** Owns the shared processor and the reader/batch coordinators used by TranslationManager. */
internal class TranslationManagerCoordinators(
    context: Context,
    provider: TranslationFileProvider,
    private val preferences: TranslationPreferences,
    private val processorOverride: TranslationProcessor? = null,
    streamRegistry: TranslationStreamRegistry,
    downloadProvider: DownloadProvider,
    sourceManager: SourceManager,
    queueStore: TranslationQueueStore,
    batchStoreResolver: suspend (Translation) -> ChapterTranslationStore?,
    batchTrackerFactory: (Long, ChapterTranslationStore, List<String>) -> eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker?,
    onBatchClosed: suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit,
    batchCoordinatorOverride: BatchTranslationCoordinator? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    isBatchActive: (Long) -> Boolean,
    resolveReaderStore: suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore?,
    admitReaderSession: (Long) -> Boolean,
    beforeOpenReaderSession: suspend (Long) -> Unit,
    activeStoresProvider: () -> eu.kanade.translation.persistence.ActiveChapterStoreRegistry?,
    sessionCoordinator: TranslationSessionCoordinator,
    clearPendingRequests: () -> Unit,
    disposeBatchTracker: (Long) -> Unit,
    unregisterActiveStore: suspend (Long) -> Unit,
    retryPendingRekeysAfterReaderLeave: suspend () -> Unit,
    manualStreamProvider: (Manga, Chapter, HttpSource, String) -> (() -> java.io.InputStream)?,
    isAutoTranslateEnabled: () -> Boolean,
    readerCoordinatorOverride: ReaderTranslationCoordinator? = null,
) {
    val processor = processorOverride ?: TranslationProcessor(
        context = context,
        provider = provider,
        translationPreferences = preferences,
        streamRegistry = streamRegistry,
    )

    private val enginePreparationMutex = Mutex()

    private suspend fun prepareEngines() = enginePreparationMutex.withLock {
        val from = TextRecognizerLanguage.fromPref(preferences.translateFromLanguage())
        val to = TextTranslatorLanguage.fromPref(preferences.translateToLanguage())
        processor.prepareEngines(from, to)
    }

    val pageOperation = BatchPageOperation(
        processor = processor,
        recognitionEngineProvider = { processor.recognitionEngine },
        translatorProvider = { processor.textTranslator },
        prepareEngines = { prepareEngines() },
    )

    val batchCoordinator = batchCoordinatorOverride ?: BatchTranslationCoordinator(
        context = context,
        provider = provider,
        downloadProvider = downloadProvider,
        sourceManager = sourceManager,
        translationPreferences = preferences,
        queueStore = queueStore,
        processor = processor,
        pageOperation = pageOperation,
        resolveStore = batchStoreResolver,
        batchTrackerFactory = batchTrackerFactory,
        onBatchClosed = onBatchClosed,
        sessionCoordinator = sessionCoordinator,
    )

    val readerCoordinator = readerCoordinatorOverride ?: ReaderTranslationCoordinator(
        processor = processor,
        isBatchActiveForChapter = { chapterKey ->
            batchCoordinator.isChapterActive(chapterKey) ||
                chapterKey.toLongOrNull()?.let(isBatchActive) == true
        },
        resolveSessionStore = resolveReaderStore,
        admitReaderSession = admitReaderSession,
        beforeOpenSession = beforeOpenReaderSession,
        manualStreamProvider = manualStreamProvider,
        isAutoTranslateEnabled = isAutoTranslateEnabled,
        prepareEngines = { prepareEngines() },
        scope = scope,
        activeStoresProvider = activeStoresProvider,
        batchCoordinator = batchCoordinator,
        sessionCoordinator = sessionCoordinator,
        clearPendingRequests = clearPendingRequests,
        disposeBatchTracker = disposeBatchTracker,
        unregisterActiveStore = unregisterActiveStore,
        retryPendingRekeysAfterReaderLeave = retryPendingRekeysAfterReaderLeave,
    )

    fun close() {
        readerCoordinator.close()
        batchCoordinator.close()
    }
}
