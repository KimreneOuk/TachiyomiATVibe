package eu.kanade.translation.workflow

import android.content.Context
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.pipeline.CleanedPublication
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.processing.TranslationProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.api.get

/** Owns the shared processor and the reader/batch coordinators used by TranslationManager. */
internal class TranslationManagerCoordinators(
    context: Context,
    provider: TranslationFileProvider,
    private val preferences: TranslationPreferences,
    private val pipeline: TranslationPipeline,
    streamRegistry: TranslationStreamRegistry,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    isBatchActive: (Long) -> Boolean,
) {
    val processor = TranslationProcessor(
        context = context,
        cleanedPublication = CleanedPublication(
            provider = provider,
            streamRegistry = streamRegistry,
            currentInpaintingMode = { pipeline.engines.currentInpaintingMode },
            currentNeuralInpaintModel = { pipeline.engines.currentNeuralInpaintModel },
        ),
    )

    private val enginePreparationMutex = Mutex()

    private suspend fun prepareEngines(useReaderPaddleOverride: Boolean) = enginePreparationMutex.withLock {
        val from = TextRecognizerLanguage.fromPref(preferences.translateFromLanguage())
        val to = TextTranslatorLanguage.fromPref(preferences.translateToLanguage())
        pipeline.engines.ensureEnginesBuiltFor(from, to, useReaderPaddleOverride = useReaderPaddleOverride)
    }

    val batchCoordinator = BatchTranslationCoordinator(
        processor = processor,
        recognitionEngineProvider = { pipeline.engines.recognitionEngine },
        translatorProvider = { pipeline.engines.textTranslator },
        prepareEngines = { prepareEngines(useReaderPaddleOverride = false) },
        scope = scope,
    )

    val readerCoordinator = ReaderTranslationCoordinator(
        processor = processor,
        recognitionEngineProvider = { pipeline.engines.recognitionEngine },
        translatorProvider = { pipeline.engines.textTranslator },
        isBatchActiveForChapter = { chapterKey ->
            batchCoordinator.isChapterActive(chapterKey) ||
                chapterKey.toLongOrNull()?.let(isBatchActive) == true
        },
        prepareEngines = { prepareEngines(useReaderPaddleOverride = true) },
        scope = scope,
    )

    fun close() {
        readerCoordinator.close()
        batchCoordinator.close()
    }
}
