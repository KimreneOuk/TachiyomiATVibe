package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.Translation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
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
    private val pageFile: UniFile,
) {
    fun read(): ChapterTranslationSummary? {
        val summaryFile = findSummaryFile() ?: return null
        return runCatching {
            summaryFile.openInputStream().use { Json.decodeFromStream<ChapterTranslationSummary>(it) }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) {
                "TachiyomiAT chapter summary read failed: pageFile=${pageFile.name} summaryFile=${summaryFile.name}"
            }
        }.getOrNull()?.takeIf { summary ->
            if (summary.formatVersion == ChapterTranslationSummary.FORMAT_VERSION) {
                true
            } else {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT chapter summary rejected: pageFile=${pageFile.name} reason=unsupported format=${summary.formatVersion}"
                }
                false
            }
        }
    }

    /** Writes adjacent sidecar. Returns false on any publication failure. */
    fun publish(summary: ChapterTranslationSummary): Boolean {
        val parent = pageFile.parentFile ?: return failure("page file has no parent")
        val targetName = summaryFileName(pageFile.name ?: return failure("page file has no name"))
        return runCatching {
            val bytes = Json.encodeToString(summary).toByteArray(Charsets.UTF_8)
            val target = parent.findFile(targetName) ?: parent.createFile(targetName)
                ?: return failure("cannot create summary file")
            target.openOutputStream().use { it.write(bytes) }
            true
        }.onFailure { error ->
            logcat(LogPriority.ERROR, error) {
                "TachiyomiAT chapter summary publication failed: pageFile=${pageFile.name} reason=${error.message ?: error::class.java.simpleName}"
            }
        }.getOrDefault(false)
    }

    private fun findSummaryFile(): UniFile? {
        val pageFileName = pageFile.name ?: return null
        return pageFile.parentFile?.findFile(summaryFileName(pageFileName))
    }

    private fun failure(reason: String): Boolean {
        logcat(LogPriority.ERROR) {
            "TachiyomiAT chapter summary publication failed: pageFile=${pageFile.name} reason=$reason"
        }
        return false
    }

    companion object {
        fun summaryFileName(pageFileName: String): String =
            pageFileName.substringBeforeLast('.', pageFileName) + ".summary.json"
    }
}
