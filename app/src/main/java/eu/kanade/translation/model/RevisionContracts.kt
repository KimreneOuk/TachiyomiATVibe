package eu.kanade.translation.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class RevisionScope {
    FLAGGED,
    ALL_TRANSLATED
}

@Immutable
data class RevisionPreflightToken(
    val chapterId: Long,
    val storeGeneration: Long,
    val scope: RevisionScope,
    val orderedTargetFingerprints: List<String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val reviewerConfigFingerprint: String,
)

@Immutable
data class RevisionPreflightResult(
    val token: RevisionPreflightToken,
    val eligibleTargetCount: Int,
    val estimatedRequestGroups: Int,
)

@Serializable
data class RevisionReport(
    val scope: RevisionScope,
    val runStartedAt: Long,
    val runFinishedAt: Long,
    val keptCount: Int,
    val correctedCount: Int,
    val unresolvedCount: Int,
    val changes: List<AcceptedChange>,
) {
    @Serializable
    data class AcceptedChange(
        val pageKey: String,
        val blockIndex: Int,
        val beforeDraft: String,
        val afterDraft: String,
    )
}
