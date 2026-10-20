package eu.kanade.translation.coordinator

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.util.getChapterPages
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.io.InputStream

/** Opens fresh streams for page work started from a downloaded chapter. */
internal class DownloadPageStreamResolver(
    private val context: Context,
    private val downloadProvider: DownloadProvider,
) {
    fun resolve(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> InputStream)? {
        val chapterId = chapter.id ?: return null
        return {
            val chapterPath = downloadProvider.findChapterDir(
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            ) ?: throw IOException(
                "downloaded chapter dir not found for manual tap: chapterId=$chapterId pageKey=$pageKey",
            )
            getChapterPages(context, chapterPath)
                .firstOrNull { it.first == pageKey }
                ?.second?.invoke()
                ?: throw IOException("downloaded page not found for manual tap: chapterId=$chapterId pageKey=$pageKey")
        }
    }
}
