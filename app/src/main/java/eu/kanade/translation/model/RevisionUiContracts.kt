package eu.kanade.translation.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.translation.AiEngine

/**
 * CP7 pure communication boundary for standalone revision UI/UX.
 *
 * Every type here is immutable and free of Android resources, bitmaps, input
 * streams, store-owned [PageTranslation]/[TranslationBlock] objects, provider
 * clients, and API credentials. The backend ([eu.kanade.translation.TranslationManager])
 * builds these from durable store state; the manga/reader UI only renders them
 * and dispatches scope/reviewer/language/opaque-token intents back.
 *
 * The UI never computes target counts, coverage, or eligibility — all numbers
 * arrive backend-computed so a stale display can never approve a billed request.
 */

/**
 * One configured contextual reviewer the user may select. Built only from
 * providers that have a non-blank credential (API key, or LM Studio base URL)
 * and a non-blank model. The reviewer reuses that provider's existing
 * credential preference; no reviewer credential is stored separately.
 */
@Immutable
data class RevisionReviewerOption(
    val engine: AiEngine,
    val model: String,
    val displayLabel: String,
)

/**
 * Manager-derived revision eligibility for one chapter. Independent of the
 * aggregate [Translation.State]: a partial or legacy chapter may still be
 * eligible. Null at the UI layer means "eligibility not known / not computed".
 */
@Immutable
data class ChapterRevisionEligibility(
    val chapterId: Long,
    val translatedPages: Int,
    val expectedPages: Int?,
    val flaggedTargets: Int,
    val allTranslatedTargets: Int,
    val userEditedExclusions: Int,
    val reviewerOptions: ImmutableList<RevisionReviewerOption>,
    val persistedSourceLanguage: String,
    val persistedTargetLanguage: String,
) {
    /** A contextual reviewer with credentials is configured. */
    val hasReviewer: Boolean get() = reviewerOptions.isNotEmpty()

    /** Any target exists for either scope. */
    val hasTargets: Boolean get() = flaggedTargets > 0 || allTranslatedTargets > 0

    /** True when only part of the chapter is translated. */
    val isPartial: Boolean get() = expectedPages != null && translatedPages < expectedPages
}

enum class RevisionRejectionReason {
    NO_TARGETS,
    NO_REVIEWER_CONFIGURED,
    ACTIVE_BATCH,
    REVISION_ACTIVE,
    CHAPTER_DELETED,
}

/**
 * Display-safe confirmation a preflight `Ready` outcome carries. Contains the
 * reviewer/model display identity, scope, language pair, coverage, target and
 * exclusion counts, and the deterministic bounded-request estimate. No
 * credentials, no blocks, no opaque token (the token is held backend-side and
 * re-validated at start).
 */
@Immutable
data class RevisionConfirmation(
    val chapterName: String,
    val scope: RevisionScope,
    val reviewerLabel: String,
    val reviewerEngine: AiEngine,
    val reviewerModel: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatedPages: Int,
    val expectedPages: Int?,
    val targetCount: Int,
    val exclusionCount: Int,
    val estimatedRequestGroups: Int,
    /** Warn (do not reject) when only part of the chapter is translated. */
    val partialWarning: Boolean,
)

/**
 * Typed preflight result. The UI maps [RevisionRejectionReason] to localized
 * strings using the non-localized arguments when present.
 */
sealed interface RevisionPreflightOutcome {
    val chapterId: Long

    data class Ready(
        override val chapterId: Long,
        val confirmation: RevisionConfirmation,
    ) : RevisionPreflightOutcome

    data class Rejected(
        override val chapterId: Long,
        val reason: RevisionRejectionReason,
        val messageArgs: List<String> = emptyList(),
    ) : RevisionPreflightOutcome
}
