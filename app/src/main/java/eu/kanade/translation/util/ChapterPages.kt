package eu.kanade.translation.util

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import logcat.LogPriority
import logcat.logcat
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import java.io.InputStream

/**
 * canonical chapter page enumeration shared by `ChapterTranslator`
 * and `TranslationPipeline` (previously two ~45-line line-for-line identical
 * copies that only drifted in comments).
 *
 * Returns the image entries of [chapterPath] as ordered
 * `(name, streamFactory)` pairs. For directories the stream factory opens the
 * file lazily; for archives it re-opens the archive and buffers the entry
 * bytes eagerly (the entry handle is not valid after the enumeration closes).
 */
internal fun getChapterPages(
    context: Context,
    chapterPath: UniFile,
): List<Pair<String, () -> InputStream>> {
    if (chapterPath.isFile) {
        chapterPath.archiveReader(context).use { reader ->
            return reader.useEntries { entries ->
                entries.filter { entry ->
                    // Null-safe: a corrupt/revoked archive can make
                    // getInputStream return null; ImageUtil.isImage handles
                    // a null name. Skip unreadable entries instead of NPE'ing.
                    entry.isFile &&
                        ImageUtil.isImage(entry.name) {
                            reader.getInputStream(entry.name)
                                ?: throw java.io.IOException("Archive entry '${entry.name}' could not be opened")
                        }
                }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                        Pair(entry.name) {
                            chapterPath.archiveReader(context).use { archive ->
                                // Null-safe stream: throw an explicit, loggable
                                // IOException instead of an NPE if the entry
                                // vanished or the archive is corrupt, so the
                                // caller's try/catch reports the real cause.
                                val stream = archive.getInputStream(entry.name)
                                    ?: throw java.io.IOException(
                                        "Archive entry '${entry.name}' could not be opened",
                                    )
                                stream.use { it.readBytes() }.inputStream()
                            }
                        }
                    }.toList()
            }
        }
    } else {
        // listFiles() returns null on I/O error or a revoked SAF tree URI;
        // return empty (the caller treats "no pages" as a clean no-op) instead
        // of NPE'ing.
        val files = chapterPath.listFiles() ?: run {
            logcat(tag = "ChapterPages", priority = LogPriority.WARN) {
                "TachiyomiAT getChapterPages: listFiles() returned null for ${chapterPath.filePath}"
            }
            return emptyList()
        }
        return files.mapNotNull { entry ->
            // entry.name is nullable on some SAF providers; skip nameless
            // entries instead of NPE'ing.
            val name = entry.name ?: return@mapNotNull null
            if (!ImageUtil.isImage(name)) return@mapNotNull null
            Pair(name) { entry.openInputStream() }
        }.sortedWith { f1, f2 -> f1.first.compareToCaseInsensitiveNaturalOrder(f2.first) }.toList()
    }
}
