package eu.kanade.translation.coordinator

import eu.kanade.translation.model.Translation
import eu.kanade.translation.engines.translator.TranslationEngineBuilder
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal fun <T> mergeRestoredQueueEntries(
    durableIds: List<Long>,
    restoredById: Map<Long, T>,
    liveById: Map<Long, T>,
): List<T> = durableIds.distinct().mapNotNull { chapterId ->
    // A live entry may have been requeued after the restore lookup. Prefer it
    // over the stale status captured before that mutation.
    liveById[chapterId] ?: restoredById[chapterId]
}

internal fun isTranslationQueueConfigValid(preferences: TranslationPreferences): Boolean = runCatching {
    if (
        TranslationEngineBuilder.isMlKitActive(preferences) &&
        !TextTranslatorLanguage.mlkitSupportedLanguages()
            .contains(TextTranslatorLanguage.fromPref(preferences.translateToLanguage()))
    ) {
        return false
    }
    TextRecognizerLanguage.fromPref(preferences.translateFromLanguage())
    TextTranslatorLanguage.fromPref(preferences.translateToLanguage())
    true
}.getOrDefault(false)

internal suspend fun resolveTranslationFromChapterId(
    chapterId: Long,
    getChapter: GetChapter = Injekt.get(),
    getManga: GetManga = Injekt.get(),
    sourceManager: SourceManager = Injekt.get(),
): Translation? {
    val chapter = getChapter.await(chapterId) ?: return null
    val manga = getManga.await(chapter.mangaId) ?: return null
    val source = sourceManager.get(manga.source) as? HttpSource ?: return null

    return Translation(source, manga, chapter)
}

/**
 * The exact wave selection body driven by [BatchTranslationCoordinator.launchTranslatorJob]:
 * QUEUE/TRANSLATING candidates only, the first TRANSLATING entry kept stable
 * and optionally paired with the next queued entry of the same source, then
 * the first source group capped at two. Co-located verbatim so tests can pin
 * the policy on real [Translation] instances without recreating the selection.
 */
internal fun selectChapterWave(queue: List<Translation>): List<Translation> {
    val candidates = queue.asSequence().filter {
        it.status == Translation.State.QUEUE ||
            it.status == Translation.State.TRANSLATING
    }.toList()
    // A rearmed paused entry must not preempt a chapter that is
    // already translating. The queue still serializes by source;
    // this preference only keeps a live worker stable while the
    // rearm is idempotently recorded.
    val active = candidates.filter { it.status == Translation.State.TRANSLATING }
    val activeSource = active.firstOrNull()?.source
    val selected = if (active.isNotEmpty()) {
        // Allow the next queued chapter from the active
        // source to run native OCR/inpaint while provider
        // waits are in flight for the active chapter.
        val nextQueue = candidates.firstOrNull {
            it.status == Translation.State.QUEUE &&
                it !in active &&
                (activeSource == null || it.source == activeSource)
        }
        if (nextQueue != null) {
            active.take(1) + listOf(nextQueue)
        } else {
            active.take(1)
        }
    } else {
        candidates.take(1)
    }
    return selected.asSequence()
        .groupBy { it.source }
        .toList()
        .take(1)
        .flatMap { (_, translations) -> translations.take(2) }
}
