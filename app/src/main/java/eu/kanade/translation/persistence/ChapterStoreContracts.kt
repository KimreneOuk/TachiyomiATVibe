package eu.kanade.translation.persistence

import com.hippo.unifile.UniFile

/** Durable admission result for a page mutation. */
sealed interface MutationAdmission {
    data object Granted : MutationAdmission

    data class Rejected(
        val code: Code,
        val retryable: Boolean = true,
        val message: String,
    ) : MutationAdmission {
        enum class Code {
            ARTIFACT_PUBLICATION_FAILED,
            STORE_DEFUNCT,
            FENCE_REJECTED,
        }
    }
}

/**
 * Explicit storage configuration for a chapter store.
 *
 * The mode is deliberately separate from the public [ChapterTranslationStore]
 * seam: memory-only stores are a supported test/runtime mode, while durable
 * stores may be eager or lazy.  Keeping the mode explicit lets the merge
 * remove nullable engine state without changing the durable artifact schema.
 */
internal sealed interface ChapterStoreEngineMode {
    /** No filesystem target; mutations remain in memory and retain dirty probes. */
    data object Memory : ChapterStoreEngineMode

    /** Filesystem target known, but the artifact engine is opened on first write. */
    data class LazyDurable(
        val artifactParent: UniFile?,
        val artifactFileName: String?,
        val artifactParentResolver: (() -> UniFile)?,
    ) : ChapterStoreEngineMode

    /** Eagerly opened artifact engine owned by the facade. */
    data class Durable(val artifact: ChapterArtifactEngine) : ChapterStoreEngineMode
}
