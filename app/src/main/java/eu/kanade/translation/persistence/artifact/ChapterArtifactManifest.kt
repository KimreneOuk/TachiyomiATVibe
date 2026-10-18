package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.PageDisplayState
import kotlinx.serialization.Serializable

/**
 * chapter artifact manifest (lifecycle contract §15).
 *
 * `chapter.translation.manifest.json`-equivalent sibling keyed by the chapter's
 * established file-name stem. Holds schema version, page records with committed
 * and candidate metadata, active candidate ids, durable failures, and current
 * run metadata. Large payloads stay immutable files under the artifact tree;
 * the manifest never duplicates image bytes.
 */
@Serializable
data class ChapterArtifactManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Chapter artifact base name this manifest covers. */
    val chapterKey: String = "",
    val pages: Map<String, PageArtifactRecord> = emptyMap(),
    /** Expected chapter page baseline captured at batch pre-registration or first durable write. */
    val expectedPageCount: Int? = null,
    /** True only when the baseline is the complete ordered batch page set. */
    val expectedPageCountTrusted: Boolean = false,
    /**
     *  The documented delta of a subset
     * admission over a partially-downloaded chapter. Missing pages are NEVER
     * registered as page records — the durable record carries the absence
     * here instead of faking stages, failures, or attempt entries. Null when
     * the last admission's cross-check proved the chapter complete (or no
     * partial admission ever ran). Additive nullable: tolerated in both
     * directions by `ignoreUnknownKeys` ( precedent).
     */
    val partialBatchInfo: PartialBatchInfo? = null,
    val activeCandidateGenerationIds: Set<String> = emptySet(),
    val durableFailures: Map<String, DurableFailureMetadata> = emptyMap(),
    val cutoverAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = 0L,
    // 04 additive pointer extensions: appended at the END of the
    // declaration  with neutral defaults so schemaVersion 2
    // manifests decode unchanged. A v3 rewrite stamps the current
    // [SCHEMA_VERSION] so a rolled-back build refuses the chapter read-only
    // instead of silently stripping these pointers on rewrite.
    /** The one Batch run record attempt currently owning the chapter, if any. */
    val activeRun: SidecarPointer? = null,
    /** Origin-neutral per-page OCR checkpoints; key = pageKey. */
    val ocrCheckpoints: Map<String, SidecarPointer> = emptyMap(),
    /** Validated analysis chunk results in chunk-ordinal order. */
    val analysisChunks: List<SidecarPointer> = emptyList(),
    /** The frozen chapter translation profile. */
    val profile: ProfilePointer? = null,
    /** The durable whole-chapter envelope plan. */
    val envelopePlan: SidecarPointer? = null,
    /** Persisted per-page layout draw plans; key = pageKey. */
    val layoutPlans: Map<String, SidecarPointer> = emptyMap(),
    /** Persisted per-page color/style preparations; key = pageKey. */
    val colorPreparations: Map<String, SidecarPointer> = emptyMap(),
    /**
     *  R2a (write-time digests): the durable per-page source SHA-256
     * (lowercase 64-hex), recorded AT FIRST ADMISSION — every OCR checkpoint
     * publication (CLOSE, REBASE, adopt) stamps the page's source identity in
     * the SAME atomic manifest transaction that moves the checkpoint pointer,
     * never at run end. Run-start identity and checkpoint-reuse decisions
     * consume this record without re-reading page bytes; a page with no
     * record falls back to the dispatch-time observation. Additive map with
     * a neutral default, tolerated in both decode directions by
     * `ignoreUnknownKeys` ( precedent): a rolled-back build that strips it
     * only loses the optimization (the fallback re-observes), never
     * correctness — so no schema bump is owed. Bounded to one entry per
     * manifest page record.
     */
    val sourceShaByPageKey: Map<String, String> = emptyMap(),
    /**
     * P3B schema5 preparation: the manifest-owned ordered page inventory
     * (successor of the journal inventory's `expectedPageKeys`). Empty in
     * every ordinary schema4 emission — the journal owns the live inventory
     * until P4 activates the sole-manifest status projection. Neutral
     * default, tolerated in both decode directions by `ignoreUnknownKeys`.
     */
    val expectedPageKeys: Set<String> = emptySet(),
) {
    companion object {
        /**
         *  Increment 2: bumped 3 → 4 for the since-pruned context pointer;
         * `ignoreUnknownKeys` keeps older v4 manifests decoding after the prune.
         * New code reads v2, v3, and v4 and writes v4; the verified future-schema
         * guard refuses versions greater than this read-only.
         */
        const val SCHEMA_VERSION = 4

        /**
         * P3B: oldest schema version this build can still decode and write.
         * The v2/v3 lineage stays readable today; P4 raises both this floor
         * and [SCHEMA_VERSION] to 5 together, refusing every older format
         * read-only (the explicit reset workflow then replaces it).
         */
        const val MIN_SUPPORTED_SCHEMA_VERSION = 2
    }
}

/** Minimal version discrimination over a parsed manifest header (P3B). */
enum class FormatSchemaSupport {
    /** Decodable and writable by this build. */
    SUPPORTED,

    /**
     * Older than [ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION]:
     * bytes preserved untouched, never decoded further, never reported as
     * missing data or a completed chapter.
     */
    UNSUPPORTED_OLDER,

    /**
     * Newer than [ChapterArtifactManifest.SCHEMA_VERSION]: a newer build owns
     * the semantics; bytes preserved untouched, never downgraded.
     */
    UNSUPPORTED_NEWER,
}

/** Classifies one parsed schema version; null is not decodable evidence here. */
fun schemaSupport(schemaVersion: Int): FormatSchemaSupport = when {
    schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION -> FormatSchemaSupport.UNSUPPORTED_NEWER
    schemaVersion < ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION -> FormatSchemaSupport.UNSUPPORTED_OLDER
    else -> FormatSchemaSupport.SUPPORTED
}

/**
 *  How a subset admission's missing-page
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
     * Canonical file name of this page's committed trusted context checkpoint
     * under `context/`, when one exists. Legacy pages have none (unknown).
     */
    /** Last known display state; the migration-time initial state when legacy. */
    val displayState: PageDisplayState = PageDisplayState.ORIGINAL_ONLY,
    /**
     * P3B schema5 preparation: monotonic page/outcome/tombstone evidence
     * (successor of the journal's TOMBSTONED/INVALID outcomes once P4 removes
     * the journal). Null in every ordinary schema4 emission — the journal owns
     * live outcomes until activation. A recorded tombstone makes the page
     * durable-absent for planning: it is never resurrected from orphan bytes
     * or an older pointer state.
     */
    val tombstone: PageTombstoneEvidence? = null,
)

/** Terminal page evidence kinds the sole manifest carries after journal retirement. */
enum class PageTombstoneOutcome {
    /** The page was explicitly removed from the chapter's durable page set. */
    TOMBSTONED,

    /** The page's durable record was proven invalid; it counts as absent, not new. */
    INVALID,
}

/**
 * One monotonic page/outcome evidence record. [pageVersion] is the page's
 * version at recording time and is the monotonic guard: a newer evidence
 * never lowers it, and a writer may only install evidence whose
 * [pageVersion] is greater than or equal to the recorded one (a same-version
 * re-record with a different outcome is refused as a contradiction).
 */
@Serializable
data class PageTombstoneEvidence(
    val outcome: PageTombstoneOutcome,
    val pageVersion: Long,
    val recordedAtEpochMs: Long = 0L,
) {
    /**
     * True when [this] may replace [previous] without regressing monotonic
     * evidence. A different outcome at the same version contradicts the
     * earlier recording and is refused; identical re-records are idempotent.
     */
    fun supersedes(previous: PageTombstoneEvidence?): Boolean = when (previous) {
        null -> true
        else -> pageVersion > previous.pageVersion ||
            (pageVersion == previous.pageVersion && outcome == previous.outcome)
    }
}

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
    /** SHA-256 semantic identity of the exact snapshot named by [pageSnapshotFileName]. */
    val pageSnapshotFingerprint: String? = null,
)

/**
 *  Which lane started a paid provider
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
    val requestContextFingerprint: String? = null,
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
 * 03: a pointer to one immutable, content-addressed sidecar document.
 * [contentFingerprint] is
 * the semantic content identity of the pointed document (64 lowercase hex);
 * the file name is `f-<sha256(contentFingerprint)>.json` under its stage
 * directory.
 */
@Serializable
data class SidecarPointer(
    /** File name under the chapter artifact tree (a managed relative path). */
    val fileName: String,
    /** Schema version of the pointed document at pointer-install time. */
    val schemaVersion: Int = 1,
    val contentFingerprint: String,
) {
    /** Structural well-formedness; reachability is the store's concern. */
    fun isWellFormed(): Boolean =
        fileName.isNotBlank() && schemaVersion > 0 && contentFingerprint.isSha256Hex()
}

/**
 * 03: [SidecarPointer] extended with the profile's monotonic
 * [version] (operational ordering only, ) and its input identity.
 * Flat data class with the profile's input identity.
 */
@Serializable
data class ProfilePointer(
    val fileName: String,
    val schemaVersion: Int = 1,
    val contentFingerprint: String,
    /** Monotonic per chapter; operational ordering ONLY. */
    val version: Int,
    /** `ProfileInputFingerprint`. */
    val profileInputFingerprint: String,
) {
    fun isWellFormed(): Boolean =
        fileName.isNotBlank() &&
            schemaVersion > 0 &&
            version >= 1 &&
            contentFingerprint.isSha256Hex() &&
            profileInputFingerprint.isSha256Hex()

    fun toSidecarPointer(): SidecarPointer = SidecarPointer(
        fileName = fileName,
        schemaVersion = schemaVersion,
        contentFingerprint = contentFingerprint,
    )
}

/** True when [value] is 64 lowercase hexadecimal characters (a SHA-256 form). */
internal fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

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
