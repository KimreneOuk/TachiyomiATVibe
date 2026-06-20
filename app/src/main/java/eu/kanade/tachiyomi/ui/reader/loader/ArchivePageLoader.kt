package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.model.PageTranslation
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(
    private val reader: ArchiveReader,
    private val translations: Map<String, PageTranslation>,
) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> = reader.useEntries { entries ->
        entries
            // The `!!` on getInputStream is safe here: ImageUtil.isImage internally
            // calls findImageType() which catches Exception (incl. NPE), so a null
            // or corrupt archive entry simply returns false and the entry is skipped.
            .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .mapIndexed { i, entry ->
                ReaderPage(i).apply {
                    sourceFileName = entry.name
                    translation = translations[entry.name]
                    // TachiyomiAT: null-safe stream — if the entry vanished or
                    // the archive is corrupt, throw an explicit IOException
                    // instead of an NPE so the reader's error-handling can
                    // surface it gracefully at page load time.
                    originalStream = {
                        reader.getInputStream(entry.name)
                            ?: throw java.io.IOException("Archive entry '${entry.name}' could not be opened")
                    }
                    status = Page.State.READY
                }
            }
            .toList()
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
