package eu.kanade.translation.workflow

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationSession
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import eu.kanade.translation.processing.TranslationProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.translation.TranslationPreferences
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
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    isBatchActive: (Long) -> Boolean,
    resolveReaderStore: suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore?,
    admitReaderSession: (Long) -> Boolean,
    beforeOpenReaderSession: suspend (Long) -> Unit,
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

    private suspend fun prepareEngines(useReaderPaddleOverride: Boolean) = enginePreparationMutex.withLock {
        val from = TextRecognizerLanguage.fromPref(preferences.translateFromLanguage())
        val to = TextTranslatorLanguage.fromPref(preferences.translateToLanguage())
        processor.prepareEngines(from, to, useReaderPaddleOverride)
    }

    val batchCoordinator = BatchTranslationCoordinator(
        processor = processor,
        recognitionEngineProvider = { processor.recognitionEngine },
        translatorProvider = { processor.textTranslator },
        prepareEngines = { prepareEngines(useReaderPaddleOverride = false) },
        scope = scope,
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
        prepareEngines = { prepareEngines(useReaderPaddleOverride = true) },
        scope = scope,
    )

    fun close() {
        readerCoordinator.close()
        batchCoordinator.close()
    }
}
