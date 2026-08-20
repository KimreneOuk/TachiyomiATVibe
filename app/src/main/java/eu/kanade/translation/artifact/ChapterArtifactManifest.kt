package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageDisplayState
import kotlinx.serialization.Serializable

/**
 * TachiyomiAT: chapter artifact manifest (lifecycle contract §15).
 *
 * `chapter.translation.manifest.json`-equivalent sibling of the legacy flat
 * translation file. Holds schema version, page records with committed and
 * candidate metadata, active candidate ids, durable failures, and the glossary
 * pointer. Large payloads stay immutable files under the artifact tree; the
 * manifest never duplicates image bytes.
 */
@Serializable
data class ChapterArtifactManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Chapter base name (translation file name without extension) this manifest covers. */
    val chapterKey: String = "",
    val pages: Map<String, PageArtifactRecord> = emptyMap(),
    val activeCandidateGenerationIds: Set<String> = emptySet(),
    val durableFailures: Map<String, DurableFailureMetadata> = emptyMap(),
    val glossary: GlossaryPointer? = null,
    /**
     * Identity of the legacy flat translation file this manifest describes.
     * Null on manifests written before resync tracking existed; a null value
     * never matches a live identity, forcing one conservative resync.
     */
    val legacySource: LegacySourceIdentity? = null,
    /**
     * Which document owns this manifest's metadata (Phase 3 cutover). While
     * [ManifestAuthority.LEGACY], opens may resync from the authoritative
     * legacy bytes; once a Phase 3 transaction flips this to
     * [ManifestAuthority.ARTIFACTS], legacy resync never rewrites the
     * manifest.
     */
    val authority: ManifestAuthority = ManifestAuthority.LEGACY,
    val cutoverAtEpochMs: Long? = null,
    val migratedFromLegacyAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = 0L,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** One page's artifact records, committed/candidate pointers (lifecycle contract §1). */
@Serializable
data class PageArtifactRecord(
    val pageKey: String,
    /**
     * Zero-based natural page index. Null when it cannot be proven from the
     * legacy key set (URL-derived names); never silently guessed.
     */
    val naturalPageIndex: Int? = null,
    val pageVersion: Long = 0L,
    val source: SourceIdentity? = null,
    val detection: StageArtifactRecord? = null,
    val ocr: StageArtifactRecord? = null,
    val inpaint: StageArtifactRecord? = null,
    val translation: StageArtifactRecord? = null,
    val layout: StageArtifactRecord? = null,
    /** The last complete displayable generation, if any (lifecycle contract §9). */
    val committed: CommittedBundleMetadata? = null,
    /** Retained immediately-previous committed bundle; bounded to one generation. */
    val previousCommitted: CommittedBundleMetadata? = null,
    /** The current work generation, if any. */
    val candidate: CandidateGenerationMetadata? = null,
    /**
     * Last-known-good display base the legacy reader can still show even
     * though the page is not strictly complete (e.g. a legacy PARTIAL page).
     * Compatibility visibility only: never counts as strict ready and never
     * feeds promotion or batch completeness.
     */
    val legacyVisible: DisplayBaseReference? = null,
    /**
     * Canonical file name of this page's committed trusted context checkpoint
     * under `context/`, when one exists. Legacy pages have none (unknown).
     */
    val contextCheckpointFileName: String? = null,
    /** Last known display state; the migration-time initial state when legacy. */
    val displayState: PageDisplayState = PageDisplayState.ORIGINAL_ONLY,
)

/**
 * Immutable committed display bundle (lifecycle contract §9). A legacy
 * displayable page migrates into a provisional committed bundle whose
 * provenance is unknown until a candidate refresh establishes it.
 */
@Serializable
data class CommittedBundleMetadata(
    val generationId: String,
    val bundleFingerprint: String? = null,
    val displayBase: DisplayBaseReference,
    val translationFingerprint: String? = null,
    val layoutFingerprint: String? = null,
    val origin: ArtifactOrigin = ArtifactOrigin.UNKNOWN,
    /** True for a legacy snapshot: reusable provenance is not yet established. */
    val provisional: Boolean = false,
    /** True when any block in the bundle carries a manual target edit. */
    val hasManualEdits: Boolean = false,
    val promotedAtEpochMs: Long = 0L,
    /** Complete live-store page snapshot for durable reader reconstruction. */
    val pageSnapshotFileName: String? = null,
)

/** Candidate work generation attached to one page (lifecycle contract §§1, 13). */
@Serializable
data class CandidateGenerationMetadata(
    val generationId: String,
    val origin: ArtifactOrigin = ArtifactOrigin.BATCH,
    /**
     * Dependency fingerprint the candidate was opened against. A candidate
     * write is stale when the page's dependency fingerprint no longer matches
     * this value (lifecycle contract §13 candidate-write preconditions).
     */
    val dependencyFingerprint: String? = null,
    val createdAtEpochMs: Long = 0L,
    /** Display state to restore if this candidate is canceled before promotion. */
    val priorDisplayState: PageDisplayState? = null,
    /** Complete live-store candidate snapshot; never used as the display pointer. */
    val pageSnapshotFileName: String? = null,
)

/** Pointer to the versioned vocabulary glossary sidecar (lifecycle contract §14.8). */
@Serializable
data class GlossaryPointer(
    /** File name under the chapter glossary directory, e.g. `chapter.glossary.1.json`. */
    val fileName: String,
    val version: Int,
    val versionFingerprint: String,
)

/**
 * Versioned chapter glossary sidecar. Entries are user/provider vocabulary
 * hints only — never speaker, gender, relationship, or narrative evidence.
 */
@Serializable
data class ChapterGlossary(
    val schemaVersion: Int = SCHEMA_VERSION,
    val version: Int,
    val versionFingerprint: String,
    val kind: String = KIND_VOCABULARY_HINTS,
    val entries: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND_VOCABULARY_HINTS = "VOCABULARY_HINTS"
    }
}

/**
 * Run ownership and lifecycle state of one generation
 * (`generations/<generationId>.json`, lifecycle contract §15).
 */
@Serializable
data class GenerationRecord(
    val generationId: String,
    val pageKey: String,
    val origin: ArtifactOrigin = ArtifactOrigin.BATCH,
    val lifecycle: GenerationLifecycle = GenerationLifecycle.ACTIVE,
    val createdAtEpochMs: Long = 0L,
    val closedAtEpochMs: Long? = null,
)
