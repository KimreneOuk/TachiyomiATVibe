package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.ui.reader.TranslationPillStage
import eu.kanade.tachiyomi.ui.reader.TranslationPillUi
import eu.kanade.tachiyomi.ui.reader.shouldFlashTerminal
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR

/**
 * TachiyomiAT: floating translation status pill for the reader.
 *
 * A single global indicator docked to the bottom-center of the reader viewport
 * (added to the reader container at activity scope), showing the ACTIVE page's
 * pipeline stage and flashing terminal results. Design contract:
 * - One pill for the active page only; it never multiplies per prefetched
 *   page and never scales with zoom.
 * - Fixed stage vocabulary with no fake percentages (spec §8):
 *   [TranslationPillStage] maps 1:1 onto real pipeline boundaries; nothing is
 *   invented (there is deliberately no "rendering" claim — glyph drawing onto
 *   the decoded bitmap belongs to the holder display path, not the pipeline).
 * - Indeterminate spinner only, because the pipeline exposes no progress.
 * - Not clickable/focusable, so taps pass through to the page underneath; the
 *   re-purposed corner button stays the single cancel affordance (no
 *   duplicate cancel controls).
 */
class ReaderTranslationStatusPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density

    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateDrawable?.setTint(Color.WHITE)
        layoutParams = LayoutParams((density * 14).toInt(), (density * 14).toInt())
    }

    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        maxLines = 1
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = (density * 8).toInt() }
    }

    private var activePageKey: String? = null
    private var currentUi: TranslationPillUi? = null
    private var hidePending: Runnable? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hPad = (density * 12).toInt()
        val vPad = (density * 7).toInt()
        setPadding(hPad, vPad, hPad, vPad)
        minimumHeight = (density * 36).toInt()
        background = GradientDrawable().apply {
            cornerRadius = density * 18f
            // 85% black capsule (spec: 75–85% dark translucent).
            setColor(0xD9000000.toInt())
        }
        addView(spinner)
        addView(label)
        elevation = density * 2f
        isClickable = false
        isFocusable = false
        visibility = GONE
        alpha = 0f
    }

    /**
     * Re-points the pill to a new active page. A pending terminal flash is
     * dropped and the pill hides immediately without fading — a page switch is
     * not a translation event.
     */
    fun setActivePageKey(pageKey: String?) {
        if (pageKey == activePageKey) return
        activePageKey = pageKey
        currentUi = null
        cancelPendingHide()
        animate().cancel()
        isVisible = false
        alpha = 1f
    }

    fun render(pageKey: String, ui: TranslationPillUi) {
        if (pageKey != activePageKey) return
        val previous = currentUi
        if (previous == ui) return
        currentUi = ui
        when {
            ui is TranslationPillUi.Running -> {
                cancelPendingHide()
                spinner.isVisible = true
                label.text = labelFor(ui)
                show()
            }

            ui is TranslationPillUi.Idle -> {
                cancelPendingHide()
                hide()
            }

            else -> {
                // Terminal: Ready / Textless / Cancelled / Failed. These are
                // transition events — a resubscription that replays an old
                // terminal state must not flash (Ready fades ~1s after the
                // event; a replay is not an event).
                if (!shouldFlashTerminal(previous, ui)) {
                    hide()
                    return
                }
                spinner.isVisible = false
                label.text = labelFor(ui)
                show()
                scheduleHide()
            }
        }
    }

    private fun show() {
        cancelPendingHide()
        animate().cancel()
        if (context.animatorDurationScale > 0f) {
            animate().alpha(1f).setDuration(FADE_MS).setStartDelay(0L).start()
        } else {
            alpha = 1f
        }
        if (visibility != VISIBLE) visibility = VISIBLE
    }

    private fun hide() {
        animate().cancel()
        if (!isVisible && alpha <= 0f) {
            cancelPendingHide()
            return
        }
        if (context.animatorDurationScale > 0f) {
            animate().alpha(0f).setDuration(FADE_MS).setStartDelay(0L)
                .withEndAction { if (currentUi == null || currentUi is TranslationPillUi.Idle) visibility = GONE }
                .start()
        } else {
            alpha = 0f
            visibility = GONE
        }
    }

    private fun scheduleHide() {
        cancelPendingHide()
        val runnable = Runnable {
            hidePending = null
            // Only hide if the content was not replaced while waiting.
            hide()
        }
        hidePending = runnable
        postDelayed(runnable, TERMINAL_FLASH_MS)
    }

    private fun cancelPendingHide() {
        hidePending?.let(::removeCallbacks)
        hidePending = null
    }

    override fun onDetachedFromWindow() {
        cancelPendingHide()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun labelFor(ui: TranslationPillUi): String = when (ui) {
        is TranslationPillUi.Running -> when (ui.stage) {
            TranslationPillStage.QUEUED -> context.stringResource(ATMR.strings.reader_translation_pill_queued)
            TranslationPillStage.PREPARING -> context.stringResource(ATMR.strings.reader_translation_pill_preparing)
            TranslationPillStage.READING_TEXT -> context.stringResource(ATMR.strings.reader_translation_pill_reading)
            TranslationPillStage.TRANSLATING -> context.stringResource(ATMR.strings.reader_translation_pill_translating)
            TranslationPillStage.CLEANING -> context.stringResource(ATMR.strings.reader_translation_pill_cleaning)
            TranslationPillStage.FINISHING -> context.stringResource(ATMR.strings.reader_translation_pill_finishing)
        }
        TranslationPillUi.Ready -> context.stringResource(ATMR.strings.reader_translation_pill_ready)
        TranslationPillUi.Textless -> context.stringResource(ATMR.strings.reader_translation_pill_textless)
        TranslationPillUi.Cancelled -> context.stringResource(ATMR.strings.reader_translation_pill_cancelled)
        TranslationPillUi.Failed -> context.stringResource(ATMR.strings.reader_translation_pill_failed)
        TranslationPillUi.Idle -> ""
    }

    private companion object {
        const val TERMINAL_FLASH_MS = 1000L
        const val FADE_MS = 150L
    }
}
