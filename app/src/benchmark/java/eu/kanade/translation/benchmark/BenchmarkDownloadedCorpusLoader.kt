package eu.kanade.translation.benchmark

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.translation.util.getChapterPages
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Enumerates the installed app's configured download tree without copying it. */
class BenchmarkDownloadedCorpusLoader(private val context: Context) {

    data class Discovery(
        val pages: List<BenchmarkPage>,
        val status: String,
    )

    fun discover(maxPages: Int = 0): Discovery {
        val root = runCatching { Injekt.get<StorageManager>().getDownloadsDirectory() }
            .getOrNull()
            ?: return Discovery(emptyList(), "unavailable:storage_manager")
        if (!root.exists()) return Discovery(emptyList(), "missing:${root.uri}")

        var chapterCount = 0
        val pages = mutableListOf<BenchmarkPage>()
        val sourceDirectories = listChildren(root)
            .filter { it.isDirectory }
            .sortedBy { it.safeName() }
        for (sourceDirectory in sourceDirectories) {
            if (maxPages > 0 && pages.size >= maxPages) break
            val mangaDirectories = listChildren(sourceDirectory)
                .filter { it.isDirectory }
                .sortedBy { it.safeName() }
            for (mangaDirectory in mangaDirectories) {
                if (maxPages > 0 && pages.size >= maxPages) break
                val chapters = listChildren(mangaDirectory)
                    .filter { it.isDirectory || it.isCbz() }
                    .sortedBy { it.safeName() }
                for (chapter in chapters) {
                    if (maxPages > 0 && pages.size >= maxPages) break
                    val chapterPages = runCatching {
                        getChapterPages(context, chapter)
                    }.getOrDefault(emptyList())
                    if (chapterPages.isEmpty()) continue
                    chapterCount++
                    for ((index, page) in chapterPages.withIndex()) {
                        if (maxPages > 0 && pages.size >= maxPages) break
                        val (name, streamFactory) = page
                        pages += BenchmarkPage(
                            id = listOf(
                                sourceDirectory.safeName(),
                                mangaDirectory.safeName(),
                                chapter.safeName(),
                                "${index + 1}-$name",
                            ).joinToString("/"),
                            imageStreamFactory = streamFactory,
                            source = "downloaded",
                        )
                    }
                }
            }
        }
        val status = if (pages.isEmpty()) {
            "empty:${root.uri}"
        } else {
            "loaded:${root.uri}:chapters=$chapterCount:pages=${pages.size}"
        }
        return Discovery(pages, status)
    }

    private fun listChildren(directory: UniFile): List<UniFile> =
        runCatching { directory.listFiles().orEmpty().toList() }.getOrDefault(emptyList())

    private fun UniFile.isCbz(): Boolean = safeName().endsWith(".cbz", ignoreCase = true)

    private fun UniFile.safeName(): String = name ?: uri.toString().substringAfterLast('/')
}
