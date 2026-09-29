package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable

/**
 *  Increment 2 (schemas contract): reference to a committed page's
 * contribution to chapter context.
 */
@Serializable
data class PageContextReference(
    val pageKey: String,
    val generation: Long = 0L,
    val sourceFingerprint: String = "",
    val selectedPairDigest: String = "",
)

/**
 * Read-compatibility model for legacy chapter context sidecars. Translation
 * requests now build history directly from committed page snapshots.
 */
@Serializable
data class ChapterContextSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    val chapterKey: String,
    val targetLang: String,
    val sourceLang: String? = null,
    /**
     * Identity 1: durable publication ordering / diagnostics only.
     * Advances on effective committed context changes; equal content updates are no-ops.
     */
    val chapterContextRevision: Long = 1L,
    /** SHA-256 over canonical snapshot content. */
    val contentFingerprint: String = "",
    /**
     * Identity 3: reuse and compatibility policy identity.
     */
    val reuseCompatibility: String = "v1",
    val selectionPolicyVersion: Int = 1,
    val serializationPolicyVersion: Int = 1,
    val profilePointer: ProfilePointer? = null,
    val profileInputFingerprint: String? = null,
    val committedPageReferences: List<PageContextReference> = emptyList(),
    val characterAndTermSheet: String = "",
    val rollingContext: String = "",
    val selectedTerms: List<Pair<String, String>> = emptyList(),
    val selectedPairs: List<Pair<String, String>> = emptyList(),
    val estimatedContextTokens: Int = 0,
    val budgetDecision: String? = null,
    val omissionReasons: List<String> = emptyList(),
    val createdAtEpochMs: Long = 0L,
) {
    fun validationError(): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion: $schemaVersion"
        if (kind != KIND) return "wrong kind: $kind"
        if (chapterKey.isBlank()) return "blank chapterKey"
        if (targetLang.isBlank()) return "blank targetLang"
        if (chapterContextRevision <= 0L) return "non-positive chapterContextRevision"
        if (contentFingerprint.isNotBlank() && !contentFingerprint.isSha256Hex()) {
            return "contentFingerprint is not sha256 hex"
        }
        return null
    }

    val isSemanticallyValid: Boolean get() = validationError() == null

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "CHAPTER_CONTEXT_SNAPSHOT"
    }
}
