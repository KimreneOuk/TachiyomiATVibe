package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable
import java.security.MessageDigest

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
 *  Increment 2: Durable unified chapter context snapshot sidecar document.
 * Represents the immutable snapshot of context facts, character sheets,
 * and recent pairs used or committed for a chapter.
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
     * Retains existing stage fingerprints and glossary compatibility version.
     */
    val reuseCompatibility: String = "v1",
    val selectionPolicyVersion: Int = 1,
    val serializationPolicyVersion: Int = 1,
    val glossaryPointer: SidecarPointer? = null,
    val glossaryFingerprint: String? = null,
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

    /**
     * Identity 2: canonical identity of the exact selected/truncated payload sent.
     * Excludes publication timestamp, run ID, and chapterContextRevision.
     */
    fun computeRequestContextFingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val payload = buildString {
            append("v:").append(serializationPolicyVersion).append('\n')
            append("tLang:").append(targetLang).append('\n')
            append("sLang:").append(sourceLang.orEmpty()).append('\n')
            append("compat:").append(reuseCompatibility).append('\n')
            append("glossaryFp:").append(glossaryFingerprint.orEmpty()).append('\n')
            append("profileFp:").append(profileInputFingerprint.orEmpty()).append('\n')
            append("sheet:").append(characterAndTermSheet).append('\n')
            append("rolling:").append(rollingContext).append('\n')
            selectedTerms.forEach { (s, t) -> append("term:").append(s).append('=').append(t).append('\n') }
            selectedPairs.forEach { (s, t) -> append("pair:").append(s).append('=').append(t).append('\n') }
        }
        val bytes = md.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "CHAPTER_CONTEXT_SNAPSHOT"

        fun computeContentFingerprint(
            chapterKey: String,
            targetLang: String,
            sourceLang: String?,
            revision: Long,
            sheet: String,
            rolling: String,
        ): String {
            val md = MessageDigest.getInstance("SHA-256")
            val payload = "ctx:$chapterKey:$targetLang:${sourceLang.orEmpty()}:$revision:$sheet:$rolling"
            val bytes = md.digest(payload.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
