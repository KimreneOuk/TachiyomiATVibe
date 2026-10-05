package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Identity fence shared by delayed reader callbacks. Generation protects a
 * recycled holder; identity protects a replaced view within the same holder
 * generation.
 */
internal data class ReaderImageCallbackFence<T : Any>(
    val generation: Long,
    val view: T,
) {
    fun isCurrent(currentGeneration: Long, currentView: T?): Boolean =
        generation == currentGeneration && view === currentView

    /**
     * Dispatches a delayed callback only while its captured generation and
     * registered view are still current. Returning the dispatch result makes
     * this the production seam for callback tests rather than a predicate-only
     * assertion.
     */
    fun dispatchIfCurrent(currentGeneration: Long, currentView: T?, action: () -> Unit): Boolean {
        if (!isCurrent(currentGeneration, currentView)) return false
        action()
        return true
    }
}

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV().get()
    }

    // TachiyomiAT: the translate button is anchored to the decoded image rect,
    // which depends on this holder's measured size. Re-pin it whenever the
    // holder is laid out/resized so the margins stay correct (the rect clamp
    // in relayoutTranslateButton reads width/height, both 0 before first layout).
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayoutTranslateButton()
    }

    // TachiyomiAT: cancel an in-flight image transition when this view detaches.
    // A recycled holder must not keep a second decoded view or stale dimming
    // while it is outside the reader.
    override fun onDetachedFromWindow() {
        cancelLandscapeZoom()
        super.onDetachedFromWindow()
        imageGeneration++
        pageView?.animate()?.cancel()
        pageView?.alpha = 1.0f
        previousPageView?.animate()?.cancel()
        previousPageView?.let(::removeAndRecyclePageView)
        previousPageView = null
        crossfadePending = false
        clearTranslationDimScrim()
    }

    // TachiyomiAT : need this for textblock placements
    var pageView: View? = null

    private var config: Config? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: (() -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null

    // TachiyomiAT
    var onCenterChanged: ((newCenter: PointF) -> Unit)? = null

    var onViewClicked: (() -> Unit)? = null

    // TachiyomiAT: per-page translate button
    var onTranslateClicked: (() -> Unit)? = null

    // TachiyomiAT: invoked when the per-page button is tapped while a
    // translation is in flight for this page (the button re-purposes itself
    // into a cancel affordance via [setTranslating]). Lets the user cancel a
    // stuck/slow page instead of helplessly tapping a button that looks dead.
    var onCancelTranslateClicked: (() -> Unit)? = null

    // Tracks whether the per-page button is currently showing the "translating"
    // (cancel) affordance vs. the default "translate" affordance, so callers
    // can query state and so setOnClickListener is only re-pointed on change.
    private var translateButtonShowingCancel = false

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
        finishImageTransition()
        translationImageReady = translationImageSelected
        val hasTier1Blocks = !translationImageSelected && pendingTranslationBlocks.isNotEmpty()
        if (!translationImageSelected && !hasTier1Blocks) {
            translationOverlay?.isVisible = false
        }
        // TachiyomiAT: the image just decoded, so its on-screen rect is now
        // known — re-pin the translate button onto the image (not the holder).
        relayoutTranslateButton()
        if ((translationImageReady || hasTier1Blocks) && pendingTranslationBlocks.isNotEmpty()) {
            val imageView = pageView as? SubsamplingScaleImageView
            if (imageView != null) {
                ensureTranslationOverlay()
                translationOverlay?.isVisible = true
                translationOverlay?.bind(
                    imageView,
                    pendingTranslationBlocks,
                    pendingPageWidth,
                    pendingPageHeight,
                    pendingPageKey,
                )
            }
        }
        translationOverlay?.onImageTransformChanged()
    }

    @CallSuper
    open fun onImageLoadError() {
        cancelLandscapeZoom()
        restorePreviousImageAfterLoadError()
        translationImageReady = false
        pendingTranslationBlocks = emptyList()
        pendingPageWidth = 0
        pendingPageHeight = 0
        pendingPageKey = null
        translationOverlay?.bind(null, emptyList(), 0, 0)
        translationOverlay?.isVisible = false
        onImageLoadError?.invoke()
    }

    private fun finishImageTransition() {
        val oldView = previousPageView ?: run {
            pageView?.alpha = 1f
            return
        }
        val newView = pageView ?: return
        val transitionGeneration = imageGeneration
        val transitionFence = ReaderImageCallbackFence(transitionGeneration, oldView)
        crossfadePending = false
        newView.alpha = 0f
        newView.animate()
            .alpha(1f)
            .setDuration(TRANSLATION_CROSSFADE_DURATION_MS)
            .withEndAction {
                transitionFence.dispatchIfCurrent(imageGeneration, previousPageView) {
                    removeAndRecyclePageView(oldView)
                    previousPageView = null
                }
            }
            .start()
    }

    private fun restorePreviousImageAfterLoadError() {
        val newView = pageView
        val oldView = previousPageView ?: return
        newView?.animate()?.cancel()
        newView?.let(::removeAndRecyclePageView)
        pageView = oldView
        previousPageView = null
        pageView?.alpha = 1f
        crossfadePending = false
        restoreOverlayOrder()
    }

    private fun cancelLandscapeZoom() {
        landscapeZoomRunnable?.let { runnable ->
            landscapeZoomHandler?.removeCallbacks(runnable)
        }
        landscapeZoomRunnable = null
        landscapeZoomHandler = null
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
        // TachiyomiAT: zoom changes the image's rect within the holder; keep the
        // button anchored to the image's top-left so it doesn't drift into the
        // button anchored to the image's top-left so it doesn't drift into the
        // letterbox area as the user zooms.
        relayoutTranslateButton()
        translationOverlay?.onImageTransformChanged()
    }

    // TachiyomiAT
    @CallSuper
    open fun onCenterChanged(newCenter: PointF?) {
        if (newCenter != null) onCenterChanged?.invoke(newCenter)
        // TachiyomiAT: pan moves the image within the holder; follow it so the
        // button stays on the image instead of floating in empty space.
        relayoutTranslateButton()
        translationOverlay?.onImageTransformChanged()
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    private var translationDimScrim: View? = null

    // TachiyomiAT: per-page translate button at top-left corner
    private var translateButton: AppCompatImageView? = null

    // TachiyomiAT: renders translated text over the image
    private var translationOverlay: TranslationOverlayView? = null
    private var translationImageSelected = false
    private var translationImageReady = false

    // TachiyomiAT: last translation blocks passed to [setTranslationBlocks].
    // Cached until the selected image has decoded, then bound against the live
    // SSIV. Keeping this separate from the image lifecycle prevents a stale
    // overlay from surviving an original/translated image swap.
    private var pendingTranslationBlocks: List<eu.kanade.translation.model.TranslationBlockView> = emptyList()
    private var pendingPageWidth = 0
    private var pendingPageHeight = 0

    //  Stage 7: page key of the pending binding (see setTranslationBlocks).
    private var pendingPageKey: String? = null

    // Keep the previous decoded view underneath a replacement until the new
    // image reaches its ready callback. This makes the translated result
    // crossfade in without a blank frame, while retaining only two bounded
    // holder views during the short transition.
    private var previousPageView: View? = null
    private var imageGeneration: Long = 0L
    private var crossfadePending = false
    private var landscapeZoomRunnable: Runnable? = null
    private var landscapeZoomHandler: android.os.Handler? = null

    private fun ensureTranslationOverlay() {
        if (translationOverlay != null) return
        translationOverlay = TranslationOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        addView(translationOverlay)
        restoreOverlayOrder()
    }

    fun prepareTranslationImage(selected: Boolean) {
        if (translationImageSelected == selected) return
        translationImageSelected = selected
        translationImageReady = false
        translationOverlay?.isVisible = false
        if (!selected) {
            translationOverlay?.clear()
        }
    }

    fun setTranslationBlocks(
        blocks: List<eu.kanade.translation.model.TranslationBlockView>,
        pageWidth: Int,
        pageHeight: Int,
        //  Stage 7: the translation page key, propagated to the
        // overlay so the  chapter hydration source can resolve the
        // page's persisted plan. Null keeps the legacy planner-only path.
        pageKey: String? = null,
    ) {
        pendingTranslationBlocks = blocks
        pendingPageWidth = pageWidth
        pendingPageHeight = pageHeight
        pendingPageKey = pageKey
        val imageView = pageView as? SubsamplingScaleImageView
        if (blocks.isNotEmpty() && imageView != null) {
            ensureTranslationOverlay()
            translationOverlay?.isVisible = true
            translationOverlay?.bind(imageView, blocks, pageWidth, pageHeight, pageKey)
        } else {
            translationOverlay?.isVisible = false
            translationOverlay?.clear()
        }
    }

    private fun ensureTranslateButton() {
        if (translateButton != null) return
        val btn = AppCompatImageView(context).apply {
            setImageResource(eu.kanade.tachiyomi.R.drawable.ic_translate_circle)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val p = (5 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)

            // Circular ripple background with 40% transparent black
            val mask = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.WHITE)
            }
            val content = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x66000000.toInt())
            }
            val rippleColor = android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt())
            background = android.graphics.drawable.RippleDrawable(rippleColor, content, mask)

            isClickable = true
            isFocusable = true
            val btnSize = (32 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.TOP or Gravity.START
                val margin = (8 * resources.displayMetrics.density).toInt()
                setMargins(margin, margin, 0, 0)
            }
            setOnClickListener { onTranslateClicked?.invoke() }
        }
        addView(btn)
        btn.bringToFront()
        translateButton = btn
        // TachiyomiAT: place the button against the decoded image rect instead
        // of the holder frame. Without this the button floats in the holder's
        // letterbox/gutter area (e.g. a portrait image centered in a landscape
        // pager, or the side padding of a webtoon strip) instead of sitting on
        // the image. Re-run whenever the image is (re)loaded or the user
        // pans/zooms, since those move the image within the holder.
        relayoutTranslateButton()
    }

    fun showTranslateButton(visible: Boolean) {
        if (visible) {
            val wasCreated = translateButton == null
            ensureTranslateButton()
            // TachiyomiAT: only bringToFront() when the button was just created or
            // is currently hidden. Calling it unconditionally triggers a child-list
            // reorder + requestLayout on every refreshTranslation() — wasteful
            // during the frequent translation status updates.
            if (wasCreated || translateButton?.visibility != View.VISIBLE) {
                translateButton?.apply {
                    bringToFront()
                    visibility = View.VISIBLE
                }
            }
            // Invalidate the throttle cache so the first layout after show always
            // applies the correct margins (the cached position may be stale).
            lastButtonApplied = false
            // Pin against the current image rect now that it's visible.
            relayoutTranslateButton()
        } else {
            translateButton?.visibility = View.GONE
        }
    }

    /**
     * TachiyomiAT: computes the on-screen rect of the currently displayed page
     * image in this holder's view coordinates, or null if it can't be resolved
     * yet (no image / not ready / unsupported view type). The rect accounts for
     * letterboxing (CENTER_INSIDE), fit-to-width in webtoon mode, and the
     * user's current pan/zoom. Returning null lets the caller keep the button
     * at its default top-left margin rather than misplacing it.
     */
    private fun computeImageRect(): RectF? {
        val pv = pageView ?: return null
        return when (pv) {
            is SubsamplingScaleImageView -> {
                if (!pv.isReady) return null
                val sWidth = pv.sWidth
                val sHeight = pv.sHeight
                if (sWidth <= 0 || sHeight <= 0) return null
                // sourceToViewCoord returns null when vTranslate isn't set; both
                // corners are required to form the rect.
                val topLeft = pv.sourceToViewCoord(0f, 0f) ?: return null
                val bottomRight = pv.sourceToViewCoord(sWidth.toFloat(), sHeight.toFloat()) ?: return null
                RectF(
                    minOf(topLeft.x, bottomRight.x),
                    minOf(topLeft.y, bottomRight.y),
                    maxOf(topLeft.x, bottomRight.x),
                    maxOf(topLeft.y, bottomRight.y),
                )
            }
            is PhotoView -> {
                // PhotoView.getDisplayRect() returns the drawable's rect in view
                // coordinates, already accounting for scale/pan. May be empty if no
                // drawable is set.
                pv.displayRect?.takeIf { !it.isEmpty }
            }
            is AppCompatImageView -> computeImageViewDrawableRect(pv)
            else -> null
        }
    }

    /**
     * Fallback rect computation for a plain [AppCompatImageView] (the animated
     * path in webtoon mode): maps the drawable's bounds through the view's
     * image matrix to get the on-screen rect. Returns null when there is no
     * drawable or the matrix can't be resolved.
     */
    private fun computeImageViewDrawableRect(imageView: AppCompatImageView): RectF? {
        val drawable = imageView.drawable ?: return null
        val m = imageView.imageMatrix
        m.getValues(imageMatrixValues)
        val bounds = tempDrawableBounds
        drawable.copyBounds(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val scaleX = imageMatrixValues[android.graphics.Matrix.MSCALE_X]
        val scaleY = imageMatrixValues[android.graphics.Matrix.MSCALE_Y]
        val transX = imageMatrixValues[android.graphics.Matrix.MTRANS_X]
        val transY = imageMatrixValues[android.graphics.Matrix.MTRANS_Y]
        if (scaleX == 0f || scaleY == 0f) return null
        val left = bounds.left * scaleX + transX + imageView.paddingLeft
        val top = bounds.top * scaleY + transY + imageView.paddingTop
        return RectF(left, top, left + bounds.width() * scaleX, top + bounds.height() * scaleY)
    }

    // TachiyomiAT: scratch buffers reused across [relayoutTranslateButton] /
    // [computeImageViewDrawableRect] calls to avoid allocating a Rect + a 9-float
    // array on every pan/zoom frame (these run on every gesture callback).
    private val imageMatrixValues = FloatArray(9)
    private val tempDrawableBounds = android.graphics.Rect()

    /**
     * TachiyomiAT: repositions the per-page translate button so it hugs the
     * top-left corner of the decoded image rect instead of the holder frame.
     * Falls back to the holder's top-left (the legacy behaviour) when the rect
     * can't be resolved, so the button still appears even before the image is
     * ready. The button is clamped to stay within the visible holder so an
     * extreme zoom/pan can't push it fully off-screen.
     */
    fun relayoutTranslateButton() {
        val btn = translateButton ?: return
        val lp = btn.layoutParams as? FrameLayout.LayoutParams ?: return
        val density = resources.displayMetrics.density
        val inset = (8 * density).toInt()
        val rect = computeImageRect()
        if (rect == null) {
            // No image yet: keep the default holder top-left inset.
            val targetLeft = inset
            val targetTop = inset
            if (applyButtonMarginsIfChanged(lp, targetLeft, targetTop)) {
                lastButtonLeft = targetLeft
                lastButtonTop = targetTop
            }
            return
        }
        // Position at the image's top-left, inset by a small margin so the
        // button doesn't overlap the page's own top-left content. Clamp so the
        // button stays within the holder (a heavily panned/zoomed image may
        // place its top-left off the visible area).
        val targetLeft = rect.left.toInt().coerceIn(0, (width - btn.width).coerceAtLeast(0)) + inset
        val targetTop = rect.top.toInt().coerceIn(0, (height - btn.height).coerceAtLeast(0)) + inset
        // TachiyomiAT: pan/zoom fire this on every gesture frame. Only re-assign
        // layoutParams (which triggers requestLayout) when the position actually
        // moved by at least a pixel — sub-pixel jitter from continuous pan must
        // not cause a per-frame layout pass.
        if (applyButtonMarginsIfChanged(lp, targetLeft, targetTop)) {
            lastButtonLeft = targetLeft
            lastButtonTop = targetTop
        }
    }

    /**
     * TachiyomiAT: assigns the translate button's gravity+margins and triggers a
     * layout pass ONLY when the target position differs from the last applied
     * one (or none has been applied yet). Returns true if the assignment ran.
     * This is the throttle that stops [relayoutTranslateButton] from issuing a
     * requestLayout() on every pan/zoom frame when the position is unchanged.
     */
    private fun applyButtonMarginsIfChanged(
        lp: FrameLayout.LayoutParams,
        targetLeft: Int,
        targetTop: Int,
    ): Boolean {
        if (targetLeft == lastButtonLeft && targetTop == lastButtonTop && lastButtonApplied) {
            return false
        }
        lp.gravity = Gravity.TOP or Gravity.START
        lp.setMargins(targetLeft, targetTop, 0, 0)
        translateButton?.layoutParams = lp
        lastButtonApplied = true
        return true
    }

    // TachiyomiAT: last margins applied to the translate button, used by
    // [applyButtonMarginsIfChanged] to skip redundant requestLayout() calls.
    private var lastButtonLeft: Int = Int.MIN_VALUE
    private var lastButtonTop: Int = Int.MIN_VALUE
    private var lastButtonApplied: Boolean = false

    /**
     * TachiyomiAT: restores the intended child-view z-order after
     * [setImage] / second [setImage] re-adds the pageView as the LAST child,
     * pushing the dim scrim and translate button behind it.
     */
    private fun ensureDimScrim() {
        if (translationDimScrim != null) return
        translationDimScrim = View(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        addView(translationDimScrim)
        restoreOverlayOrder()
    }

    private fun restoreOverlayOrder() {
        // Scrim above pageView
        translationDimScrim?.bringToFront()
        // Overlay goes above the scrim
        translationOverlay?.bringToFront()
        // The translate button stays on top of the image and overlay.
        translateButton?.bringToFront()
    }

    /**
     * TachiyomiAT: re-purposes the per-page translate button into a cancel
     * affordance while a translation is running for this page, and back to the
     * translate affordance when idle. Without this the button keeps its static
     * icon during work, giving the user no feedback that the tap registered and
     * no way to cancel a slow/stuck page (taps just silently dedup in the
     * translator's inFlightPageKeys set).
     *
     * Callers (the page holders) drive this from the page status they already
     * receive via the live translation store.
     */
    fun setTranslating(running: Boolean) {
        // Lazy-create the button if needed so the swap can happen even before
        // an explicit showTranslateButton(true) (e.g. status arrives first).
        if (translateButton == null && running) ensureTranslateButton()
        val btn = translateButton ?: return
        if (running == translateButtonShowingCancel) return
        translateButtonShowingCancel = running
        if (running) {
            btn.setImageResource(eu.kanade.tachiyomi.R.drawable.ic_close_24dp)
            btn.setOnClickListener { onCancelTranslateClicked?.invoke() }
        } else {
            btn.setImageResource(eu.kanade.tachiyomi.R.drawable.ic_translate_circle)
            btn.setOnClickListener { onTranslateClicked?.invoke() }
        }
    }

    /** Dims the page only while its durable translation stages are running. */
    fun setTranslationDimmed(dimmed: Boolean) {
        translationDimScrim?.animate()?.cancel()
        if (dimmed) {
            ensureDimScrim()
            translationDimScrim?.isVisible = true
            translationDimScrim?.animate()?.alpha(0.28f)?.setDuration(250)?.start()
        } else {
            translationDimScrim?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
                translationDimScrim?.isVisible = false
            }?.start()
        }
    }

    private fun clearTranslationDimScrim() {
        translationDimScrim?.animate()?.cancel()
        translationDimScrim?.alpha = 0f
        translationDimScrim?.isVisible = false
    }

    open fun onPageSelected(forward: Boolean) {
        val selectedView = pageView as? SubsamplingScaleImageView ?: return
        val callbackFence = ReaderImageCallbackFence(imageGeneration, selectedView)
        if (selectedView.isReady) {
            selectedView.landscapeZoom(forward)
        } else {
            selectedView.setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        callbackFence.dispatchIfCurrent(imageGeneration, pageView as? SubsamplingScaleImageView) {
                            selectedView.setupZoom(this@ReaderPageImageView.config)
                            selectedView.landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }
                    }

                    override fun onImageLoadError(e: Exception) {
                        callbackFence.dispatchIfCurrent(imageGeneration, pageView as? SubsamplingScaleImageView) {
                            this@ReaderPageImageView.onImageLoadError()
                        }
                    }
                },
            )
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            val selectedView = this
            val selectedHandler = handler ?: return
            val callbackFence = ReaderImageCallbackFence(imageGeneration, selectedView)
            cancelLandscapeZoom()
            val runnable = Runnable {
                landscapeZoomRunnable = null
                landscapeZoomHandler = null
                callbackFence.dispatchIfCurrent(imageGeneration, pageView as? SubsamplingScaleImageView) {
                    val currentConfig = this@ReaderPageImageView.config
                    if (currentConfig != null) {
                        val point = when (currentConfig.zoomStartPosition) {
                            ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(selectedView.sWidth.toFloat(), 0F)
                            ZoomStartPosition.RIGHT -> if (forward) PointF(selectedView.sWidth.toFloat(), 0F) else PointF(0F, 0F)
                            ZoomStartPosition.CENTER -> selectedView.center
                        }

                        val targetScale = selectedView.height.toFloat() / selectedView.sHeight.toFloat()
                        selectedView.animateScaleAndCenter(targetScale, point)!!
                            .withDuration(500)
                            .withEasing(EASE_IN_OUT_QUAD)
                            .withInterruptible(true)
                            .start()
                    }
                }
            }
            landscapeZoomRunnable = runnable
            landscapeZoomHandler = selectedHandler
            selectedHandler.postDelayed(runnable, 500L)
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        beginImageTransition()
        translationImageReady = false
        translationOverlay?.isVisible = false
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
        // TachiyomiAT: prepare*ImageView() adds a new child at the end of the
        // FrameLayout child list. Re-bring controls and feedback to the front
        // so they remain visible and receive taps.
        restoreOverlayOrder()
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        beginImageTransition()
        translationImageReady = false
        translationOverlay?.isVisible = false
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
        // TachiyomiAT: keep the translate button on top after the new pageView
        // is added as the last child (see setImage(drawable) for details).
        restoreOverlayOrder()
    }

    fun recycle() {
        cancelLandscapeZoom()
        imageGeneration++
        pageView?.animate()?.cancel()
        previousPageView?.animate()?.cancel()
        translationImageSelected = false
        translationImageReady = false
        pendingTranslationBlocks = emptyList()
        pendingPageWidth = 0
        pendingPageHeight = 0
        pendingPageKey = null
        translationOverlay?.isVisible = false
        translationOverlay?.clear()
        clearTranslationDimScrim()
        pageView?.let(::removeAndRecyclePageView)
        previousPageView?.let(::removeAndRecyclePageView)
        pageView = null
        previousPageView = null
        crossfadePending = false
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun beginImageTransition() {
        cancelLandscapeZoom()
        imageGeneration++
        pageView?.animate()?.cancel()
        previousPageView?.animate()?.cancel()
        previousPageView?.let(::removeAndRecyclePageView)
        previousPageView = pageView?.takeIf { it.isVisible }
        if (previousPageView != null) {
            previousPageView?.alpha = 1f
            crossfadePending = true
        } else {
            pageView?.let(::removeAndRecyclePageView)
            crossfadePending = false
        }
        pageView = null
    }

    private fun removeAndRecyclePageView(view: View) {
        removeView(view)
        when (view) {
            is SubsamplingScaleImageView -> view.recycle()
            is AppCompatImageView -> view.dispose()
        }
        view.isVisible = false
    }

    private fun prepareNonAnimatedImageView() {
        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // TachiyomiAT
                        this@ReaderPageImageView.onCenterChanged(newCenter)
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        pageView?.alpha = if (crossfadePending) 0f else 1f
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        val generation = imageGeneration
        val selectedView = this
        val callbackFence = ReaderImageCallbackFence(generation, selectedView)
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    callbackFence.dispatchIfCurrent(imageGeneration, pageView as? SubsamplingScaleImageView) {
                        setupZoom(config)
                        if (isVisibleOnScreen()) landscapeZoom(true)
                        this@ReaderPageImageView.onImageLoaded()
                    }
                }

                override fun onImageLoadError(e: Exception) {
                    callbackFence.dispatchIfCurrent(imageGeneration, pageView as? SubsamplingScaleImageView) {
                        this@ReaderPageImageView.onImageLoadError()
                    }
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon || alwaysDecodeLongStripWithSSIV) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    // TachiyomiAT: enable the memory cache for the webtoon non-SSIV
                    // path. Previously both policies were DISABLED, which forced a
                    // full bitmap re-decode on every setImage() — combined with the
                    // (now fixed) redundant rebinds this was a major OOM/jank source
                    // on low-RAM devices. Translated/original webtoon sources are
                    // stable per content, so memoizing the decoded bitmap is safe.
                    // Disk stays disabled because the source bytes are already on
                    // disk (translated WEBP / downloaded page); we only want to
                    // avoid re-decoding them into a Bitmap.
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            callbackFence.dispatchIfCurrent(imageGeneration, pageView as? SubsamplingScaleImageView) {
                                val image = result as BitmapImage
                                setImage(ImageSource.bitmap(image.bitmap))
                                isVisible = true
                            }
                        },
                        onError = {
                            callbackFence.dispatchIfCurrent(imageGeneration, pageView as? SubsamplingScaleImageView) {
                                this@ReaderPageImageView.onImageLoadError()
                            }
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        pageView?.alpha = if (crossfadePending) 0f else 1f
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        val generation = imageGeneration
        val selectedView = this
        val callbackFence = ReaderImageCallbackFence(generation, selectedView)
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    callbackFence.dispatchIfCurrent(imageGeneration, pageView as? AppCompatImageView) {
                        val drawable = result.asDrawable(context.resources)
                        setImageDrawable(drawable)
                        (drawable as? Animatable)?.start()
                        isVisible = true
                        this@ReaderPageImageView.onImageLoaded()
                    }
                },
                onError = {
                    callbackFence.dispatchIfCurrent(imageGeneration, pageView as? AppCompatImageView) {
                        this@ReaderPageImageView.onImageLoadError()
                    }
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F

private const val TRANSLATION_CROSSFADE_DURATION_MS = 180L
