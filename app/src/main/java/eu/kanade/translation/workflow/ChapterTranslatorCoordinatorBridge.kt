package eu.kanade.translation.workflow

import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.batch.progress.BatchProgressReconciler
import eu.kanade.translation.processing.batch.progress.ReconciliationResult
import eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker
import android.util.Log
import java.io.InputStream
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Bridges queued chapter execution into the ordered batch coordinator. */
internal suspend fun executeQueuedBatchWithCoordinator(
    coordinator: BatchTranslationCoordinator,
    onBatchClosed: suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit = { _, _, _, _ -> },
    provider: TranslationFileProvider,
    translation: Translation,
    orderedStreams: List<Pair<String, () -> InputStream>>,
    orderedPageKeys: List<String>,
    store: ChapterTranslationStore,
    tracker: TranslationBatchProgressTracker?,
): ReconciliationResult? {
    val chapterId = translation.chapter.id
    try {
        if (orderedStreams.isEmpty()) {
            translation.status = Translation.State.ERROR
            tracker?.abort(emptySet(), "Chapter has no readable pages to translate")
            return null
        }
        val streamsByKey = orderedStreams.associate { it.first to it.second }
        val result = coordinator.translateChapter(
            chapterKey = chapterId?.toString() ?: translation.chapter.name,
            orderedPageKeys = orderedPageKeys,
            streamProvider = { pageKey ->
                streamsByKey[pageKey]?.invoke()
                    ?: throw java.io.IOException("Page stream is missing for $pageKey")
            },
            store = store,
            companionDir = provider.getCompanionImageDir(
                translation.manga.title,
                translation.source,
                translation.chapter.name,
                translation.chapter.scanlator,
            ),
            onProgress = { tracker?.rebuildFromStore() },
        )
        if (result.error != null) {
            tracker?.abort(orderedPageKeys.toSet(), result.error)
            translation.status = Translation.State.ERROR
            return null
        }
        val reconciliation = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = store.state.value,
            orderedKeys = orderedPageKeys,
            activeGeneration = store.currentGeneration,
        )
        tracker?.finish(reconciliation)
        return reconciliation
    } finally {
        try {
            onBatchClosed(
                translation.manga,
                translation.chapter,
                translation.source,
                store,
            )
        } catch (closeError: Throwable) {
            Log.w("Translation", "Batch coordinator teardown callback failed for ${translation.chapter.name}", closeError)
        }
    }
}
