package eu.kanade.translation.orchestration

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.storage.ChapterTranslationStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

data class TranslationPageId(
    val sourceId: Long,
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
) {
    override fun toString(): String = "$sourceId:$mangaId:$chapterId:$pageIndex"
}

enum class TranslationWorkKind {
    Auto,
    Manual,
}

data class TranslationPageRequest(
    val id: TranslationPageId,
    val storageKey: String,
    val streamProvider: suspend () -> (() -> InputStream)?,
    val priority: Int,
    val kind: TranslationWorkKind,
    val streamAvailable: Boolean,
)

class TranslationSession internal constructor(
    val key: String,
    val manga: Manga,
    val chapter: Chapter,
    val source: HttpSource,
    internal val store: ChapterTranslationStore,
)
