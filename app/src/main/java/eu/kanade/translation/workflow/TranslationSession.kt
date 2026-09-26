package eu.kanade.translation.workflow

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class TranslationSession internal constructor(
    val key: String,
    val manga: Manga,
    val chapter: Chapter,
    val source: HttpSource,
    internal val store: ChapterTranslationStore,
)
