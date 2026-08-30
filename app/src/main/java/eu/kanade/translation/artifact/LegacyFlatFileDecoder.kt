package eu.kanade.translation.artifact

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterDocumentIo
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.toPageDisplayProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The one legacy flat-file (`translation.json`) decoder and its quarantine
 * path, moved verbatim from `TranslationManager` (T909 Phase 3a). Owns the
 * single `ignoreUnknownKeys` [Json] configuration for manager-side legacy
 * decode, consolidating the duplicate instance that used to live on the
 * manager.
 */
internal object LegacyFlatFileDecoder {

    internal val legacyPageJson = Json { ignoreUnknownKeys = true }

    fun decodeLegacyChapterStatus(
        file: UniFile,
        chapterName: String,
    ): Translation.State? {
        if (!file.exists() || file.length() <= 2L) return null
        return runCatching {
            val pages = file.openInputStream().use {
                legacyPageJson.decodeFromStream<Map<String, PageTranslation>>(it)
            }
            statusFromReadablePages(pages)
        }.onFailure { error ->
            quarantineCorruptTranslationFile(file, error)
            logcat(LogPriority.WARN, error) {
                "Translation file for $chapterName unreadable; treating as not translated"
            }
        }.getOrNull()
    }

    fun statusFromReadablePages(
        pages: Map<String, PageTranslation>,
    ): Translation.State? {
        if (pages.values.none { it.toPageDisplayProjection().displayReady }) return null
        return Translation.State.READY_WITH_WARNINGS
    }

    fun decodeLegacyChapterTranslation(
        file: UniFile,
        quarantineOnFailure: Boolean,
    ): Map<String, PageTranslation> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.openInputStream().use {
                legacyPageJson.decodeFromStream<Map<String, PageTranslation>>(it)
            }
        }.getOrElse { error ->
            if (quarantineOnFailure) quarantineCorruptTranslationFile(file, error)
            emptyMap()
        }
    }

    fun quarantineCorruptTranslationFile(file: UniFile, error: Throwable) {
        val name = file.name ?: "translation.json"
        val targetName = file.parentFile?.let { parent ->
            quarantineCorruptDocument(UniFileChapterDocumentIo(parent), name)
        }
        val renamed = targetName != null
        logcat(LogPriority.ERROR, error) {
            "TachiyomiAT quarantined corrupt translation file: " +
                "file=$name quarantine=${targetName ?: "unavailable"} renamed=$renamed"
        }
    }

    fun quarantineCorruptDocument(io: ChapterDocumentIo, name: String): String? =
        AtomicChapterDocuments(io).quarantineCorrupt(name)
}
