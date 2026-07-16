package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.Translation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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
    val unresolvedRevisionCount: Int,
    val updatedAtMillis: Long,
    val latestRevisionReport: eu.kanade.translation.model.RevisionReport? = null,
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
            if (summary.formatVersion == ChapterTranslationSummary.FORMAT_VERSION) true else {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT chapter summary rejected: pageFile=${pageFile.name} reason=unsupported format=${summary.formatVersion}"
                }
                false
            }
        }
    }

    /** Writes, decodes, then replaces the adjacent sidecar. Returns false on any publication failure. */
    fun publish(summary: ChapterTranslationSummary): Boolean {
        val parent = pageFile.parentFile ?: return failure("page file has no parent")
        val targetName = summaryFileName(pageFile.name ?: return failure("page file has no name"))
        val temporary = parent.createFile("$targetName.tmp") ?: return failure("cannot create temporary summary")
        try {
            temporary.openOutputStream().use { Json.encodeToStream(summary, it) }
            val verified = temporary.openInputStream().use { Json.decodeFromStream<ChapterTranslationSummary>(it) }
            if (verified != summary) return failure("temporary summary verification mismatch")

            parent.findFile(targetName)?.let { existing ->
                if (!existing.delete()) return failure("cannot replace existing summary")
            }
            if (!temporary.renameTo(targetName)) return failure("temporary summary rename failed")
            return true
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) {
                "TachiyomiAT chapter summary publication failed: pageFile=${pageFile.name} reason=${error.message ?: error::class.java.simpleName}"
            }
            return false
        } finally {
            if (temporary.exists()) {
                runCatching { temporary.delete() }
            }
        }
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
