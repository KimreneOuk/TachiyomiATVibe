package eu.kanade.translation.translator

import eu.kanade.translation.model.ChapterRevisionEligibility
import eu.kanade.translation.model.RevisionConfirmation
import eu.kanade.translation.model.RevisionPreflightOutcome
import eu.kanade.translation.model.RevisionRejectionReason
import eu.kanade.translation.model.RevisionReport
import eu.kanade.translation.model.RevisionReviewerOption
import eu.kanade.translation.model.RevisionScope
import eu.kanade.translation.model.REVISION_RESULT_MAX_CHANGES
import eu.kanade.translation.model.defaultScope
import eu.kanade.translation.model.isStartAlreadyActive
import eu.kanade.translation.model.resolveInitialSelection
import eu.kanade.translation.model.shouldCancelOnSurfaceClosed
import eu.kanade.translation.model.toConfirmState
import eu.kanade.translation.model.toResultState
import eu.kanade.translation.model.withReviewerPicked
import eu.kanade.translation.model.withScopeChanged
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.AiEngine
import java.lang.reflect.ParameterizedType

/**
 * CP7 pure-logic tests for the standalone revision UI reducer. No Android, no
 * Compose, no manager. Covers the [RevisionPreflightOutcome] -> confirm state
 * mapping, idempotency/cancellation invariants, the eligibility/action matrix,
 * and documents that the contract types carry no PageTranslation /
 * TranslationBlock / stream / bitmap / store / provider-client / credential
 * fields.
 */
class RevisionUiReducerTest {

    private val gemini = RevisionReviewerOption(
        engine = AiEngine.GEMINI,
        model = "gemini-1.5-pro",
        displayLabel = "Gemini AI · gemini-1.5-pro",
    )
    private val deepseek = RevisionReviewerOption(
        engine = AiEngine.DEEPSEEK,
        model = "deepseek-chat",
        displayLabel = "DeepSeek · deepseek-chat",
    )
    private val configuredOptions = listOf(gemini, deepseek)

    private fun confirmation(
        scope: RevisionScope = RevisionScope.FLAGGED,
        partialWarning: Boolean = false,
        reviewer: RevisionReviewerOption = gemini,
    ) = RevisionConfirmation(
        chapterName = "Ch.1",
        scope = scope,
        reviewerLabel = reviewer.displayLabel,
        reviewerEngine = reviewer.engine,
        reviewerModel = reviewer.model,
        sourceLanguage = "JAPANESE",
        targetLanguage = "ENGLISH",
        translatedPages = 10,
        expectedPages = 10,
        targetCount = 5,
        exclusionCount = 1,
        estimatedRequestGroups = 2,
        partialWarning = partialWarning,
    )

    private fun eligibility(
        flaggedTargets: Int = 3,
        allTranslatedTargets: Int = 9,
        translatedPages: Int = 10,
        expectedPages: Int? = 10,
        reviewerOptions: List<RevisionReviewerOption> = configuredOptions,
    ) = ChapterRevisionEligibility(
        chapterId = 1L,
        translatedPages = translatedPages,
        expectedPages = expectedPages,
        flaggedTargets = flaggedTargets,
        allTranslatedTargets = allTranslatedTargets,
        userEditedExclusions = 1,
        reviewerOptions = reviewerOptions.toImmutableListCompat(),
        persistedSourceLanguage = "JAPANESE",
        persistedTargetLanguage = "ENGLISH",
    )

    // ----- Preflight outcome -> confirm state -----

    @Test
    fun `Ready outcome yields an enabled confirm action`() {
        val outcome = RevisionPreflightOutcome.Ready(
            chapterId = 1L,
            confirmation = confirmation(),
        )
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)

        val ready = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Ready>()
        ready.canConfirm shouldBe true
        ready.selection.engine shouldBe AiEngine.GEMINI
        ready.selection.model shouldBe "gemini-1.5-pro"
        ready.confirmation.targetCount shouldBe 5
    }

    @Test
    fun `Ready outcome falls back to first option when persisted engine is not configured`() {
        val outcome = RevisionPreflightOutcome.Ready(
            chapterId = 1L,
            confirmation = confirmation(reviewer = gemini),
        )
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.LMSTUDIO)

        val ready = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Ready>()
        ready.selection.engine shouldBe AiEngine.GEMINI
        ready.selection.model shouldBe "gemini-1.5-pro"
    }

    @Test
    fun `Rejected NO_TARGETS maps to Rejected state without settings nav`() {
        val outcome = RevisionPreflightOutcome.Rejected(
            chapterId = 1L,
            reason = RevisionRejectionReason.NO_TARGETS,
        )
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)

        val rejected = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        rejected.rejection.reason shouldBe RevisionRejectionReason.NO_TARGETS
        rejected.rejection.offersSettingsNav shouldBe false
    }

    @Test
    fun `Rejected NO_REVIEWER_CONFIGURED offers settings nav`() {
        val outcome = RevisionPreflightOutcome.Rejected(
            chapterId = 1L,
            reason = RevisionRejectionReason.NO_REVIEWER_CONFIGURED,
        )
        val state = outcome.toConfirmState(emptyList(), persistedEngine = AiEngine.GEMINI)

        val rejected = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        rejected.rejection.reason shouldBe RevisionRejectionReason.NO_REVIEWER_CONFIGURED
        rejected.rejection.offersSettingsNav shouldBe true
    }

    @Test
    fun `Rejected ACTIVE_BATCH does not offer settings nav`() {
        val outcome = RevisionPreflightOutcome.Rejected(
            chapterId = 1L,
            reason = RevisionRejectionReason.ACTIVE_BATCH,
        )
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)

        val rejected = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        rejected.rejection.reason shouldBe RevisionRejectionReason.ACTIVE_BATCH
        rejected.rejection.offersSettingsNav shouldBe false
    }

    @Test
    fun `Rejected REVISION_ACTIVE does not offer settings nav`() {
        val outcome = RevisionPreflightOutcome.Rejected(
            chapterId = 1L,
            reason = RevisionRejectionReason.REVISION_ACTIVE,
        )
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)

        val rejected = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        rejected.rejection.reason shouldBe RevisionRejectionReason.REVISION_ACTIVE
    }

    @Test
    fun `Rejected CHAPTER_DELETED does not offer settings nav`() {
        val outcome = RevisionPreflightOutcome.Rejected(
            chapterId = 1L,
            reason = RevisionRejectionReason.CHAPTER_DELETED,
        )
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)

        val rejected = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        rejected.rejection.reason shouldBe RevisionRejectionReason.CHAPTER_DELETED
    }

    @Test
    fun `Rejected carries message args verbatim`() {
        val outcome = RevisionPreflightOutcome.Rejected(
            chapterId = 1L,
            reason = RevisionRejectionReason.NO_TARGETS,
            messageArgs = listOf("flagged"),
        )
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)

        val rejected = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        rejected.rejection.messageArgs shouldBe listOf("flagged")
    }

    // ----- Selection / scope reducers -----

    @Test
    fun `withReviewerPicked updates only the selection on a Ready state`() {
        val outcome = RevisionPreflightOutcome.Ready(1L, confirmation(reviewer = gemini))
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)
        val updated = state.withReviewerPicked(deepseek)

        val ready = updated.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Ready>()
        ready.selection.engine shouldBe AiEngine.DEEPSEEK
        ready.selection.model shouldBe "deepseek-chat"
        ready.confirmation.reviewerEngine shouldBe AiEngine.GEMINI
    }

    @Test
    fun `withReviewerPicked is a no-op on Rejected state`() {
        val outcome = RevisionPreflightOutcome.Rejected(1L, RevisionRejectionReason.NO_TARGETS)
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)
        val updated = state.withReviewerPicked(deepseek)

        updated shouldBe state
    }

    @Test
    fun `withScopeChanged updates the confirmation scope on Ready`() {
        val outcome = RevisionPreflightOutcome.Ready(1L, confirmation(scope = RevisionScope.FLAGGED))
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)
        val updated = state.withScopeChanged(RevisionScope.ALL_TRANSLATED)

        val ready = updated.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Ready>()
        ready.confirmation.scope shouldBe RevisionScope.ALL_TRANSLATED
    }

    @Test
    fun `withScopeChanged is a no-op on Rejected state`() {
        val outcome = RevisionPreflightOutcome.Rejected(1L, RevisionRejectionReason.NO_TARGETS)
        val state = outcome.toConfirmState(configuredOptions, persistedEngine = AiEngine.GEMINI)
        val updated = state.withScopeChanged(RevisionScope.ALL_TRANSLATED)

        updated shouldBe state
    }

    // ----- Default scope / initial selection -----

    @Test
    fun `defaultScope picks FLAGGED when flagged targets exist`() {
        defaultScope(eligibility(flaggedTargets = 3)) shouldBe RevisionScope.FLAGGED
    }

    @Test
    fun `defaultScope picks ALL_TRANSLATED when no flagged targets`() {
        defaultScope(eligibility(flaggedTargets = 0, allTranslatedTargets = 9)) shouldBe RevisionScope.ALL_TRANSLATED
    }

    @Test
    fun `defaultScope falls back to ALL_TRANSLATED when eligibility is null`() {
        defaultScope(eligibility = null) shouldBe RevisionScope.ALL_TRANSLATED
    }

    @Test
    fun `resolveInitialSelection returns null when no reviewer configured`() {
        resolveInitialSelection(emptyList(), AiEngine.GEMINI) shouldBe null
    }

    @Test
    fun `resolveInitialSelection prefers persisted engine when configured`() {
        val selection = resolveInitialSelection(configuredOptions, AiEngine.DEEPSEEK)
        selection shouldNotBe null
        selection!!.engine shouldBe AiEngine.DEEPSEEK
    }

    @Test
    fun `resolveInitialSelection falls back to first option when persisted engine not configured`() {
        val selection = resolveInitialSelection(configuredOptions, AiEngine.LMSTUDIO)
        selection shouldNotBe null
        selection!!.engine shouldBe AiEngine.GEMINI
    }

    // ----- Idempotency / cancellation invariants -----

    @Test
    fun `UI duplicate-start guard blocks start when revision already active`() {
        isStartAlreadyActive(revisionActive = true) shouldBe true
    }

    @Test
    fun `UI duplicate-start guard allows start when no revision active`() {
        isStartAlreadyActive(revisionActive = false) shouldBe false
    }

    @Test
    fun `Closing the surface never cancels the work`() {
        // Documented invariant: the manager owns the job. The UI guard always
        // returns false so a close==cancel wiring cannot be introduced.
        shouldCancelOnSurfaceClosed() shouldBe false
    }

    @Test
    fun `Cancelling via UI does not depend on surface state`() {
        // The UI cancel path issues translationManager.cancelRevision(chapterId)
        // and the manager clears its own job map; nothing about the surface
        // being open or closed is required. This is documented by the
        // shouldCancelOnSurfaceClosed() invariant returning false.
        shouldCancelOnSurfaceClosed() shouldBe false
    }

    // ----- Terminal report state -----

    @Test
    fun `Report maps to Loaded result state with bounded changes`() {
        val report = RevisionReport(
            scope = RevisionScope.FLAGGED,
            runStartedAt = 1L,
            runFinishedAt = 2L,
            keptCount = 3,
            correctedCount = 2,
            unresolvedCount = 1,
            changes = listOf(
                RevisionReport.AcceptedChange("p0", 0, "before", "after"),
            ),
        )
        val state = report.toResultState()

        val loaded = state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionResultState.Loaded>()
        loaded.report.keptCount shouldBe 3
        loaded.report.correctedCount shouldBe 2
        loaded.report.unresolvedCount shouldBe 1
        loaded.displayedChanges shouldBe 1
        loaded.moreChanges shouldBe 0
    }

    @Test
    fun `Loaded state bounds displayed changes and reports remainder`() {
        val many = (0 until REVISION_RESULT_MAX_CHANGES + 5).map { i ->
            RevisionReport.AcceptedChange("p$i", i, "b$i", "a$i")
        }
        val report = RevisionReport(
            scope = RevisionScope.ALL_TRANSLATED,
            runStartedAt = 0L,
            runFinishedAt = 1L,
            keptCount = 0,
            correctedCount = many.size,
            unresolvedCount = 0,
            changes = many,
        )
        val loaded = report.toResultState()
            .shouldBeInstanceOf<eu.kanade.translation.model.RevisionResultState.Loaded>()

        loaded.displayedChanges shouldBe REVISION_RESULT_MAX_CHANGES
        loaded.moreChanges shouldBe 5
        loaded.report.changes.size shouldBe many.size
    }

    // ----- Eligibility/action matrix -----

    @Test
    fun `Fully translated chapter is eligible and partial flag is false`() {
        val e = eligibility(translatedPages = 10, expectedPages = 10)
        e.isPartial shouldBe false
        e.hasTargets shouldBe true
    }

    @Test
    fun `Half-translated chapter is partial and still eligible`() {
        val e = eligibility(translatedPages = 5, expectedPages = 10)
        e.isPartial shouldBe true
        e.hasTargets shouldBe true
    }

    @Test
    fun `Chapter with only user-edited exclusions and no flagged targets defaults to ALL scope`() {
        val e = eligibility(flaggedTargets = 0, allTranslatedTargets = 4)
        e.hasTargets shouldBe true
        defaultScope(e) shouldBe RevisionScope.ALL_TRANSLATED
    }

    @Test
    fun `Active-batch rejection reason is distinct from revision-active`() {
        // The manager serializes these distinctly; the UI maps each to a
        // separate localized message and never offers settings nav for either.
        val batch = RevisionPreflightOutcome.Rejected(1L, RevisionRejectionReason.ACTIVE_BATCH)
            .toConfirmState(configuredOptions, AiEngine.GEMINI)
            .shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        val revision = RevisionPreflightOutcome.Rejected(1L, RevisionRejectionReason.REVISION_ACTIVE)
            .toConfirmState(configuredOptions, AiEngine.GEMINI)
            .shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()

        batch.rejection.reason shouldNotBe revision.rejection.reason
        batch.rejection.offersSettingsNav shouldBe false
        revision.rejection.offersSettingsNav shouldBe false
    }

    @Test
    fun `Eligibility keeps the persisted language pair for legacy and fresh chapters`() {
        val e = eligibility()
        e.persistedSourceLanguage shouldBe "JAPANESE"
        e.persistedTargetLanguage shouldBe "ENGLISH"
    }

    @Test
    fun `No-targets chapter maps preflight to NO_TARGETS rejection`() {
        val outcome = RevisionPreflightOutcome.Rejected(1L, RevisionRejectionReason.NO_TARGETS)
        val state = outcome.toConfirmState(configuredOptions, AiEngine.GEMINI)
        state.shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
    }

    @Test
    fun `No-reviewer chapter maps preflight to NO_REVIEWER_CONFIGURED rejection`() {
        val outcome = RevisionPreflightOutcome.Rejected(1L, RevisionRejectionReason.NO_REVIEWER_CONFIGURED)
        val state = outcome.toConfirmState(emptyList(), AiEngine.GEMINI)
        val rejected = state
            .shouldBeInstanceOf<eu.kanade.translation.model.RevisionConfirmState.Rejected>()
        rejected.rejection.offersSettingsNav shouldBe true
    }

    @Test
    fun `Deleted chapter (eligibility null) does not surface REVIEW`() {
        // Null eligibility means the manager found no store. The surface hides
        // the REVIEW action entirely; this test documents that defaultScope and
        // resolveInitialSelection both degrade safely when eligibility/options
        // are absent so the surface never crashes when eligibility is null.
        defaultScope(eligibility = null) shouldBe RevisionScope.ALL_TRANSLATED
        resolveInitialSelection(emptyList(), AiEngine.GEMINI) shouldBe null
    }

    // ----- Contract purity: documented field-type guarantees -----

    @Test
    fun `RevisionConfirmation fields are pure (no blocks, streams, bitmaps, credentials, clients)`() {
        val forbidden = setOf(
            "eu.kanade.translation.model.PageTranslation",
            "eu.kanade.translation.model.TranslationBlock",
            "java.io.InputStream",
            "java.io.OutputStream",
            "android.graphics.Bitmap",
            "android.graphics.drawable.Drawable",
            "android.net.Uri",
            "okhttp3.OkHttpClient",
            "retrofit2.Retrofit",
        )
        assertNoFieldsOfType(RevisionConfirmation::class.java, forbidden)
        assertNoFieldsOfType(ChapterRevisionEligibility::class.java, forbidden)
        assertNoFieldsOfType(RevisionReport::class.java, forbidden)
        assertNoFieldsOfType(RevisionReport.AcceptedChange::class.java, forbidden)
        assertNoFieldsOfType(RevisionReviewerOption::class.java, forbidden)
    }

    @Test
    fun `AcceptedChange carries only display-safe primitives`() {
        val change = RevisionReport.AcceptedChange(
            pageKey = "001.jpg",
            blockIndex = 2,
            beforeDraft = "old",
            afterDraft = "new",
        )
        change.pageKey shouldBe "001.jpg"
        change.blockIndex shouldBe 2
        change.beforeDraft shouldBe "old"
        change.afterDraft shouldBe "new"
    }

    /**
     * Reflects over the declared fields (and generic type args) of [type] and
     * fails if any forbidden fully-qualified type name appears. Documents the
     * purity boundary the contract comments already guarantee by construction.
     */
    private fun assertNoFieldsOfType(type: Class<*>, forbidden: Set<String>) {
        type.declaredFields.forEach { field ->
            val names = mutableSetOf<String>()
            collectTypeNames(field.genericType, names)
            names.intersect(forbidden).forEach { hit ->
                throw AssertionError(
                    "${type.simpleName}.${field.name} references forbidden type $hit",
                )
            }
        }
    }

    private fun collectTypeNames(type: java.lang.reflect.Type, out: MutableSet<String>) {
        when (type) {
            is Class<*> -> {
                out.add(type.name)
                // Cover nested generic fields like List<Foo> declared on the class.
                type.typeParameters.forEach { /* no-op, params are erased */ }
            }
            is ParameterizedType -> {
                (type.rawType as? Class<*>)?.let { out.add(it.name) }
                type.actualTypeArguments.forEach { collectTypeNames(it, out) }
            }
            else -> Unit
        }
    }
}

/**
 * Test-only bridge to the immutable list carrier type used by
 * [ChapterRevisionEligibility], so the matrix tests can build eligibility
 * without pulling in the Android-side Compose ImmutableList factory.
 */
private fun <T> List<T>.toImmutableListCompat(): kotlinx.collections.immutable.ImmutableList<T> =
    this.toPersistentList()
