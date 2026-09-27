package eu.kanade.translation.context

import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.BatchContextFrontier
import java.security.MessageDigest

enum class LaneCapability {
    MANUAL,
    AUTO,
    PROFILE_BATCH,
    STANDARD_BATCH,
}

/**
 * The requested page keys define the current unit. Rolling history is always
 * rebuilt from committed artifact snapshots before the earliest current page.
 */
data class ContextRequest(
    val pageKeys: List<String>,
    val targetLang: String,
    val sourceLang: String? = null,
    val profile: TranslationContextChunkPlanner.Profile,
    val laneCapability: LaneCapability,
)

/** A finalized, prompt-ready rolling history selection. */
data class PreparedContext(
    val rollingContext: String,
    val selectedPairs: List<Pair<String, String>>,
    val estimatedContextTokens: Int,
) {
    /**
     * Stable identity of the finalized rolling-context section. Other former
     * profile/glossary compatibility inputs are deliberately absent.
     */
    fun computeRequestContextFingerprint(
        targetLang: String,
        sourceLang: String?,
        finalizedRollingContext: String = rollingContext,
    ): String {
        val payload = buildString {
            append("rolling-history-context:v1\n")
            append("target:").append(targetLang).append('\n')
            append("source:").append(sourceLang.orEmpty()).append('\n')
            append("history:\n").append(finalizedRollingContext.trim())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        val EMPTY = PreparedContext(
            rollingContext = "",
            selectedPairs = emptyList(),
            estimatedContextTokens = 0,
        )
    }
}

/**
 * The single rolling-history builder shared by reader, auto, AI batch and
 * standard batch. It reads manifest pointers and committed page snapshots;
 * live page maps, glossary state, profiles and execution queues are not inputs.
 */
class ChapterContextService(
    val store: ChapterTranslationStore,
) {

    companion object {
        const val MAX_ROLLING_PAIRS = TranslationContextChunkPlanner.MAX_ROLLING_PAIRS
    }

    fun prepare(request: ContextRequest): PreparedContext {
        val artifact = store.artifactEngine ?: return PreparedContext.EMPTY
        val manifest = artifact.readManifest() ?: return PreparedContext.EMPTY
        val indexedRecords = manifest.pages.values
            .mapNotNull { record -> record.naturalPageIndex?.let { it to record } }
            .sortedWith(compareBy<Pair<Int, PageArtifactRecord>> { it.first }.thenBy { it.second.pageKey })
        val currentIndex = request.pageKeys
            .mapNotNull { key -> manifest.pages[key]?.naturalPageIndex }
            .minOrNull()
            ?: return PreparedContext.EMPTY
        val predecessorRecords = indexedRecords.filter { (index, _) -> index < currentIndex }

        val durablePages = linkedMapOf<String, PageTranslation>()
        for ((_, record) in predecessorRecords) {
            val committed = record.committed?.pageSnapshotFileName?.let(artifact::readPageSnapshot)
            val page = committed ?: PageTranslation(sourceFileName = record.pageKey)
            val failedTranslation = manifest.durableFailures.values.any { failure ->
                failure.pageKey == record.pageKey &&
                    failure.stage == ArtifactStage.TRANSLATION &&
                    failure.status.isFailure()
            }
            durablePages[record.pageKey] = if (failedTranslation) {
                page.copy(translationStatus = StageStatus.FAILED)
            } else {
                page
            }
        }

        val orderedPairs = when (request.laneCapability) {
            LaneCapability.MANUAL, LaneCapability.AUTO ->
                predecessorRecords
                    .asSequence()
                    .flatMap { (_, record) ->
                        durablePages[record.pageKey].orEmptyPairs().asSequence()
                    }
                    .toList()

            LaneCapability.PROFILE_BATCH, LaneCapability.STANDARD_BATCH ->
                batchPredecessorPairs(indexedRecords, durablePages, currentIndex)
        }

        val boundedPairs = orderedPairs.takeLast(MAX_ROLLING_PAIRS)
        val constraints = TranslationContextChunkPlanner.constraintsFor(request.profile)
        val selectedPairs = boundedPairs.toMutableList()
        while (selectedPairs.isNotEmpty() &&
            TranslationContextChunkPlanner.estimateTokens(renderPairs(selectedPairs)) > constraints.maxRollingContextTokens
        ) {
            selectedPairs.removeAt(0)
        }
        val rolling = renderPairs(selectedPairs)
        return PreparedContext(
            rollingContext = rolling,
            selectedPairs = selectedPairs,
            estimatedContextTokens = if (rolling.isBlank()) 0 else TranslationContextChunkPlanner.estimateTokens(rolling),
        )
    }

    private fun batchPredecessorPairs(
        indexedRecords: List<Pair<Int, PageArtifactRecord>>,
        durablePages: Map<String, PageTranslation>,
        currentIndex: Int,
    ): List<Pair<String, String>> {
        val indexes = indexedRecords.associate { (index, record) -> record.pageKey to index }
        val frontier = BatchContextFrontier(indexes)
        frontier.seed(
            pages = durablePages,
            eligible = { pageKey, _ -> (indexes[pageKey] ?: Int.MAX_VALUE) < currentIndex },
            terminalFailure = { _, page -> page.translationStatus == StageStatus.FAILED },
        )
        return indexedRecords.asSequence()
            .filter { (index, record) -> index < currentIndex && index <= frontier.frontierIndex && record.pageKey in durablePages }
            .flatMap { (_, record) -> durablePages[record.pageKey].orEmptyPairs().asSequence() }
            .toList()
    }

    private fun PageTranslation?.orEmptyPairs(): List<Pair<String, String>> =
        this?.blocks.orEmpty().mapNotNull { block ->
            val source = block.text.normalizedHistoryText()
            val target = block.translation.normalizedHistoryText()
            if (source.isBlank() || target.isBlank() || source == target) null else source to target
        }

    private fun String.normalizedHistoryText(): String =
        replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim()

    private fun renderPairs(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("\n") { (source, target) -> "$source => $target" }

    private fun ArtifactStageStatus.isFailure(): Boolean =
        this == ArtifactStageStatus.FAILED_RETRYABLE || this == ArtifactStageStatus.FAILED_TERMINAL
}
