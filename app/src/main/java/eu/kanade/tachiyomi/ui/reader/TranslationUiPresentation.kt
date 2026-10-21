package eu.kanade.tachiyomi.ui.reader

import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.PageLifecycle
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.lifecycle
import eu.kanade.translation.processing.TranslationStageSignal

/**
 * TachiyomiAT: reader-layer UI vocabulary for the adaptive translate button and
 * the floating translation status pill. Pure logic, no Android types — unit
 * tested directly. Translation-pipeline types stop here; view layers see only
 * these enums/objects.
 *
 * Stage vocabulary is FIXED and must never grow a stage the pipeline does not
 * actually have (spec §8): `queued · preparing · reading text · translating ·
 * cleaning · finishing`. There is deliberately no "rendering" stage: the
 * recognition engine's analyze call performs detection + OCR together, which
 * is READING, and final glyph rendering on the decoded image is part of the
 * reader holder's existing display path, not a pipeline stage.
 */
enum class TranslationPillStage {
    QUEUED,
    PREPARING,
    READING_TEXT,
    TRANSLATING,
    CLEANING,
    FINISHING,
}

/** Sealed pill presentation. Terminal states flash briefly then fade out. */
sealed interface TranslationPillUi {
    data class Running(val stage: TranslationPillStage) : TranslationPillUi

    /** The page is genuinely displayable + persisted: a committed image exists. */
    data object Ready : TranslationPillUi

    /** The pipeline completed but found no text to translate. */
    data object Textless : TranslationPillUi

    /** The user cancelled the attempt. */
    data object Cancelled : TranslationPillUi

    /** The attempt reached a typed terminal failure (already retried upstream). */
    data object Failed : TranslationPillUi

    /** Nothing worth surfacing: no work, no committed change to announce. */
    data object Idle : TranslationPillUi
}

/** Adaptive translate button presentation modes (settings toggle). */
enum class TranslateButtonMode {
    /**
     * Default. The button dims into a subtle ghost while reader controls are
     * hidden, and returns to full color the moment controls show.
     */
    ADAPTIVE,

    /** The button is fully visible only when reader controls are shown. */
    WITH_CONTROLS,
}

/**
 * Maps (transient pipeline stage join outcome) + durable page view into the
 * pill presentation.
 *
 * Priority rules:
 * - A live in-memory stage signal always wins: the durable store write trails
 *   the pipeline, so the pill would otherwise lag a full stage behind.
 * - Terminal states resolve from the durable view (it is the committed truth),
 *   falling back to the session outcome when no view exists yet.
 * - Attached/Rejected outcomes stay Idle: an attached page finishes via its
 *   owner and a rejected request never started real work (the existing
 *   rejection feedback on the translate action covers it).
 */
fun buildTranslationPillUi(
    stage: TranslationStageSignal?,
    outcome: SinglePageOutcome?,
    view: PageTranslationView?,
): TranslationPillUi {
    if (stage != null) {
        return TranslationPillUi.Running(stage.toPillStage())
    }
    if (view != null) {
        when (val lifecycle = view.lifecycle) {
            is PageLifecycle.Done -> return TranslationPillUi.Ready
            is PageLifecycle.Textless -> return TranslationPillUi.Textless
            is PageLifecycle.Cancelled -> return TranslationPillUi.Cancelled
            is PageLifecycle.Failed -> return TranslationPillUi.Failed
            is PageLifecycle.Running -> return TranslationPillUi.Running(lifecycle.stage.toPillStage())
            is PageLifecycle.NeedsDisplay -> return TranslationPillUi.Ready
            is PageLifecycle.Pending -> return pendingUi(outcome)
        }
    }
    return pendingUi(outcome)
}

private fun pendingUi(outcome: SinglePageOutcome?): TranslationPillUi = when (outcome) {
    null -> TranslationPillUi.Idle
    SinglePageOutcome.Admitted -> TranslationPillUi.Running(TranslationPillStage.QUEUED)
    SinglePageOutcome.Completed -> TranslationPillUi.Ready
    is SinglePageOutcome.Paused, is SinglePageOutcome.Stalled ->
        TranslationPillUi.Running(TranslationPillStage.QUEUED)
    is SinglePageOutcome.Failed -> TranslationPillUi.Failed
    else -> TranslationPillUi.Idle
}

private fun TranslationStageSignal.toPillStage(): TranslationPillStage = when (this) {
    TranslationStageSignal.PREPARING -> TranslationPillStage.PREPARING
    TranslationStageSignal.READING -> TranslationPillStage.READING_TEXT
    TranslationStageSignal.TRANSLATING -> TranslationPillStage.TRANSLATING
    TranslationStageSignal.CLEANING -> TranslationPillStage.CLEANING
    TranslationStageSignal.FINISHING -> TranslationPillStage.FINISHING
}

private fun PageStage.toPillStage(): TranslationPillStage = when (this) {
    PageStage.Ocr -> TranslationPillStage.READING_TEXT
    PageStage.Translation -> TranslationPillStage.TRANSLATING
    PageStage.Inpaint -> TranslationPillStage.CLEANING
    PageStage.Render -> TranslationPillStage.CLEANING
}

/**
 * Whether a TERMINAL pill state (Ready/Textless/Cancelled/Failed) should flash.
 * Terminal states are events, not conditions: replaying "Ready" for a page
 * that has been done for minutes (collector resubscription on rebind/flip)
 * would be false feedback, so a terminal state only flashes when observed as a
 * transition from some other state. Running states are shown unconditionally —
 * they describe something happening right now.
 */
fun shouldFlashTerminal(previous: TranslationPillUi?, next: TranslationPillUi): Boolean =
    previous != null && previous != next

/**
 * Adaptive ghost predicate for the translate/cancel button.
 * - Mode B (WITH_CONTROLS): plain show/hide with controls; never ghosted.
 * - Adaptive: ghost (dim to translucent silhouette) whenever the reader
 *   controls are hidden, EXCEPT while this page is mid-translation, where the
 *   button re-purposes into the cancel affordance and must stay clearly
 *   visible to remain usable.
 */
fun shouldGhostTranslateButton(
    mode: TranslateButtonMode,
    menuVisible: Boolean,
    translating: Boolean,
): Boolean = mode == TranslateButtonMode.ADAPTIVE && !menuVisible && !translating

/** What a manual translate tap on a page should do given its actual persisted/runtime state. */
enum class TranslatePageAction {
    /** Never translated, failed, cancelled, or only partially processed: normal translate path. */
    TRANSLATE,

    /** A stage is actively running: no new job (the queue owns dedup/cancellation). */
    IGNORE,

    /** Fully processed (translated or textless): key artifacts must be cleared before re-running. */
    RETRANSLATE,
}

fun resolveTranslatePageAction(view: PageTranslationView?): TranslatePageAction = when (view?.lifecycle) {
    null -> TranslatePageAction.TRANSLATE
    is PageLifecycle.Running -> TranslatePageAction.IGNORE
    PageLifecycle.Done, PageLifecycle.Textless -> TranslatePageAction.RETRANSLATE
    else -> TranslatePageAction.TRANSLATE
}
