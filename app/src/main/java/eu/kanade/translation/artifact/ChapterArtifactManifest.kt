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
    /** Expected chapter page baseline captured at batch pre-registration or first durable write. */
    val expectedPageCount: Int? = null,
    /** True only when the baseline is the complete ordered batch page set. */
    val expectedPageCountTrusted: Boolean = false,
    /**
     * T917 Phase 4 (D10, phase4-design §3.3): the documented delta of a subset
     * admission over a partially-downloaded chapter. Missing pages are NEVER
     * registered as page records — the durable record carries the absence
     * here instead of faking stages, failures, or attempt entries. Null when
     * the last admission's cross-check proved the chapter complete (or no
     * partial admission ever ran). Additive nullable: tolerated in both
     * directions by `ignoreUnknownKeys` (D5 precedent).
     */
    val partialBatchInfo: PartialBatchInfo? = null,
    val activeCandidateGenerationIds: Set<String> = emptySet(),
    val durableFailures: Map<String, DurableFailureMetadata> = emptyMap(),
    val glossary: GlossaryPointer? = null,
    /**
     * Identity of the legacy flat translation file this manifest describes.
     * Null on manifests written before resync tracking existed; a null value
     * never matches a live identity, forcing one conservative resync.
     */
    val legacySource: LegacySourceIdentity? = null,
    /** Additive provenance and preservation state for a legacy rescue. */
    val legacyMigration: LegacyMigrationMetadata? = null,
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
        const val SCHEMA_VERSION = 2
    }
}

/**
 * T917 Phase 4 (D10, phase4-design §3.3): how a subset admission's missing-page
 * delta was determined. `DOWNLOAD_CROSSCHECK` — the downloader's fetched page
 * list proved a known source total; `UNKNOWN` — no trustworthy source total
 * existed, so the recorded count is only what was found.
 */
enum class PartialBatchDetermination { DOWNLOAD_CROSSCHECK, UNKNOWN }

/**
 * The durable partial-admission record for one chapter. [missingPageCount] is
 * meaningful only under [PartialBatchDetermination.DOWNLOAD_CROSSCHECK]; under
 * `UNKNOWN` the total is unknown, so no missing count is claimed.
 */
@Serializable
data class PartialBatchInfo(
    /** The known SOURCE total; null when [PartialBatchDetermination.UNKNOWN]. */
    val expectedSourcePageCount: Int? = null,
    val missingPageCount: Int = 0,
    val determinedFrom: PartialBatchDetermination,
    val recordedAtEpochMs: Long = 0L,
)

/**
 * Durable identity and preservation metadata for a one-way legacy rescue.
 * Missing, incomplete, or unsupported values are intentionally not eligible
 * for destructive cleanup decisions.
 */
@Serializable
data class LegacyMigrationMetadata(
    val formatVersion: Int = FORMAT_VERSION,
    val sourceFileName: String = "",
    val sourcePreservation: LegacyPreservationState = LegacyPreservationState.INTENT,
    val requestedSourceFileName: String? = null,
    val resolvedSourceFileName: String? = null,
    val sourcePreservedAtEpochMs: Long? = null,
    val glossaryPreservation: LegacyPreservationState = LegacyPreservationState.NONE,
    val requestedGlossaryFileName: String? = null,
    val resolvedGlossaryFileName: String? = null,
    val sourceIdentity: LegacySourceIdentity? = null,
    val glossaryIdentity: LegacySourceIdentity? = null,
    val sourcePageCount: Int = 0,
    val sourcePageKeyDigest: String = "",
    val migratedByVersionCode: Long = 0L,
    val migratedAtEpochMs: Long = 0L,
    val health: LegacyMigrationHealth = LegacyMigrationHealth.INITIAL_CUTOVER,
    val lastVerifiedByVersionCode: Long? = null,
    val lastVerifiedAtEpochMs: Long? = null,
) {
    /** Only complete schema-1 metadata may participate in later cleanup. */
    val isSupported: Boolean
        get() = formatVersion == FORMAT_VERSION &&
            sourceFileName.isNotBlank() &&
            sourceIdentity?.isValidForCleanup() == true &&
            sourcePreservationIsSupported() &&
            glossaryPreservationIsSupported() &&
            verificationIsSupported() &&
            sourcePageCount >= 0 &&
            sourcePageKeyDigest.length == SHA256_HEX_LENGTH &&
            sourcePageKeyDigest.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
            migratedByVersionCode > 0L &&
            migratedAtEpochMs > 0L

    private fun sourcePreservationIsSupported(): Boolean = when (sourcePreservation) {
        LegacyPreservationState.NONE -> false
        LegacyPreservationState.INTENT -> requestedSourceFileName.isPresent() &&
            resolvedSourceFileName == null &&
            sourcePreservedAtEpochMs == null
        LegacyPreservationState.PRESERVED -> requestedSourceFileName.isPresent() &&
            resolvedSourceFileName.isPresent() &&
            sourcePreservedAtEpochMs.isValidTimestamp()
        LegacyPreservationState.DELETED -> requestedSourceFileName.isPresent() &&
            resolvedSourceFileName.isPresent() &&
            sourcePreservedAtEpochMs.isValidTimestamp() &&
            health == LegacyMigrationHealth.VERIFIED
    }

    private fun glossaryPreservationIsSupported(): Boolean = when (glossaryPreservation) {
        LegacyPreservationState.NONE ->
            requestedGlossaryFileName == null &&
                resolvedGlossaryFileName == null &&
                glossaryIdentity == null
        LegacyPreservationState.INTENT -> requestedGlossaryFileName.isPresent() &&
            resolvedGlossaryFileName == null &&
            glossaryIdentity?.isValidForCleanup() == true
        LegacyPreservationState.PRESERVED -> requestedGlossaryFileName.isPresent() &&
            resolvedGlossaryFileName.isPresent() &&
            glossaryIdentity?.isValidForCleanup() == true
        LegacyPreservationState.DELETED -> requestedGlossaryFileName.isPresent() &&
            resolvedGlossaryFileName.isPresent() &&
            glossaryIdentity?.isValidForCleanup() == true &&
            health == LegacyMigrationHealth.VERIFIED
    }

    private fun verificationIsSupported(): Boolean = when (health) {
        LegacyMigrationHealth.INITIAL_CUTOVER ->
            lastVerifiedByVersionCode == null && lastVerifiedAtEpochMs == null
        LegacyMigrationHealth.VERIFIED_WITH_WARNINGS,
        LegacyMigrationHealth.VERIFIED,
        -> lastVerifiedByVersionCode != null &&
            lastVerifiedByVersionCode > migratedByVersionCode &&
            lastVerifiedAtEpochMs.isValidTimestamp()
    }

    private fun String?.isPresent(): Boolean = !isNullOrBlank()

    private fun Long?.isValidTimestamp(): Boolean = this != null &&
        this > 0L &&
        this >= migratedAtEpochMs

    private fun LegacySourceIdentity.isValidForCleanup(): Boolean =
        sha256.length == SHA256_HEX_LENGTH &&
            sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
            lengthBytes >= 0L &&
            lastModifiedMs >= 0L

    companion object {
        const val FORMAT_VERSION = 1
        const val SHA256_HEX_LENGTH = 64
    }
}

/** Preservation lifecycle for a legacy source or glossary sidecar. */
enum class LegacyPreservationState {
    NONE,
    INTENT,
    PRESERVED,
    DELETED,
}

/** Verification health recorded by a migration transaction. */
enum class LegacyMigrationHealth {
    INITIAL_CUTOVER,
    VERIFIED_WITH_WARNINGS,
    VERIFIED,
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
 * T917 Phase 3 (D9, phase3-design §3): which lane started a paid provider
 * attempt. The crash-loop cap binds auto-retry loops only — never the user.
 */
enum class AttemptOrigin { MANUAL, AUTO, BATCH }

/**
 * One durable started provider attempt (`attempts/ledger.json`). Written
 * BEFORE the paid call so a process death mid-call leaves a trace; consumed by
 * startup reconciliation as a counted interrupted attempt.
 */
@Serializable
data class AttemptLedgerEntry(
    val pageKey: String,
    val providerKeyHash: String,
    val origin: AttemptOrigin,
    val generation: Long,
    val startedAtEpochMs: Long,
)

/**
 * The chapter's single durable attempt-ledger document: bounded started-attempt
 * entries (oldest evicted) plus the per-page count of CONSECUTIVE unresolved
 * attempts that drives the crash-loop cap. A completed call (commit success OR
 * typed provider failure) resolves the page's entries and resets its counter;
 * only a process death leaves an entry behind.
 */
@Serializable
data class ChapterAttemptLedgerDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND_ATTEMPT_LEDGER,
    val entries: List<AttemptLedgerEntry> = emptyList(),
    val consecutiveUnresolved: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND_ATTEMPT_LEDGER = "ATTEMPT_LEDGER"

        /** Oldest entries are evicted past this bound (bounded memory/disk). */
        const val MAX_ENTRIES = 64

        /** Consecutive unresolved attempts after which the page is capped. */
        const val MAX_CONSECUTIVE_UNRESOLVED = 3
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
