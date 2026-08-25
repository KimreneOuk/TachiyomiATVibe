package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.Translation
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Small, adjacent completion record. Page JSON deliberately remains untouched so
 * legacy translations stay readable. A missing or unreadable summary never certifies
 * a chapter as completely translated.
 */
@Serializable
data class ChapterTranslationSummary(
    val formatVersion: Int = FORMAT_VERSION,
    val expectedPageCount: Int,
    val terminalOutcome: Int,
    val updatedAtMillis: Long,
) {
    fun outcome(): Translation.State? = Translation.State.entries.firstOrNull { it.value == terminalOutcome }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

class ChapterTranslationSummaryStore(
    private val parent: UniFile?,
    private val pageFileName: String?,
) {
    constructor(pageFile: UniFile) : this(pageFile.parentFile, pageFile.name)

    fun read(): ChapterTranslationSummary? {
        val parent = parent ?: return null
        val pageFileName = pageFileName ?: return null
        val summaryName = summaryFileName(pageFileName)
        return AtomicChapterDocuments(UniFileChapterDocumentIo(parent)).readValidated<ChapterTranslationSummary>(summaryName) { summary ->
            summary.formatVersion == ChapterTranslationSummary.FORMAT_VERSION
        }
    }

    /** Writes adjacent sidecar. Returns false on any publication failure. */
    fun publish(summary: ChapterTranslationSummary): Boolean {
        val parent = parent ?: return failure("page file has no parent")
        val pageFileName = pageFileName ?: return failure("page file has no name")
        val targetName = summaryFileName(pageFileName)
        return runCatching {
            AtomicChapterDocuments(UniFileChapterDocumentIo(parent)).publishJson(targetName, summary)
        }.onFailure { error ->
            logcat(LogPriority.ERROR, error) {
                "TachiyomiAT chapter summary publication failed: pageFile=$pageFileName reason=${error.message ?: error::class.java.simpleName}"
            }
        }.getOrDefault(false)
    }

    private fun failure(reason: String): Boolean {
        logcat(LogPriority.ERROR) {
            "TachiyomiAT chapter summary publication failed: pageFile=$pageFileName reason=$reason"
        }
        return false
    }

    companion object {
        fun summaryFileName(pageFileName: String): String =
            pageFileName.substringBeforeLast('.', pageFileName) + ".summary.json"
    }
}
