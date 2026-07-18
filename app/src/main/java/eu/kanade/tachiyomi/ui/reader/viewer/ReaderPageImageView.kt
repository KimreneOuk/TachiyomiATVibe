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
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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

    // TachiyomiAT: clean up when this view detaches. The fade animator introduced
    // in [showProcessingOverlay] runs on the pageView via View.animate(), which
    // targets the view's Handler; if the holder is recycled/detached mid-fade
    // the animator would otherwise keep running against a detached view (wasted
    // work + potential jank when re-attached). Cancel it, restore full alpha,
    // and drop the overlay so a recycled holder doesn't briefly show a stale
    // dim+spinner. Idempotent state is also reset so the next attach starts clean.
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pageView?.animate()?.cancel()
        pageView?.alpha = 1.0f
        processingOverlayVisible = false
        processingScrim?.isVisible = false
        processingIndicator?.hide()
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
        translationImageReady = translationImageSelected
        if (!translationImageSelected) {
            translationOverlay?.isVisible = false
        }
        // TachiyomiAT: the image just decoded, so its on-screen rect is now
        // known — re-pin the translate button onto the image (not the holder).
        relayoutTranslateButton()
        // Bind only after the selected image has decoded. This prevents cached
        // translated blocks from drawing over a newly selected original image.
        if (translationImageReady && pendingTranslationBlocks.isNotEmpty()) {
            val imageView = pageView as? SubsamplingScaleImageView
            if (imageView != null) {
                ensureTranslationOverlay()
                translationOverlay?.isVisible = true
                translationOverlay?.bind(
                    imageView,
                    pendingTranslationBlocks,
                    pendingPageWidth,
                    pendingPageHeight,
                )
            }
        }
        translationOverlay?.onImageTransformChanged()
    }

    @CallSuper
    open fun onImageLoadError() {
        translationImageReady = false
        pendingTranslationBlocks = emptyList()
        pendingPageWidth = 0
        pendingPageHeight = 0
        translationOverlay?.bind(null, emptyList(), 0, 0)
        translationOverlay?.isVisible = false
        onImageLoadError?.invoke()
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

    private var processingIndicator: ReaderProgressIndicator? = null
    private var processingScrim: View? = null

    // TachiyomiAT: tracks the current processing-overlay state so
    // [showProcessingOverlay] can short-circuit duplicate calls (translation
    // status re-emits the same RUNNING state repeatedly) instead of re-firing
    // the fade animator each time.
    private var processingOverlayVisible: Boolean = false

    // TachiyomiAT: small error text shown below the spinner when a translation
    // fails, so the user gets meaningful feedback instead of silently seeing
    // "nothing happened" on skipped/failed pages.
    private var errorText: android.widget.TextView? = null

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
    private var pendingTranslationBlocks: List<eu.kanade.translation.model.TranslationBlock> = emptyList()
    private var pendingPageWidth = 0
    private var pendingPageHeight = 0

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
            pendingTranslationBlocks = emptyList()
            pendingPageWidth = 0
            pendingPageHeight = 0
            translationOverlay?.clear()
        }
    }

    fun setTranslationBlocks(blocks: List<eu.kanade.translation.model.TranslationBlock>, pageWidth: Int, pageHeight: Int) {
        pendingTranslationBlocks = blocks
        pendingPageWidth = pageWidth
        pendingPageHeight = pageHeight
        if (blocks.isNotEmpty() && translationImageReady) {
            val imageView = pageView as? SubsamplingScaleImageView
            if (imageView != null) {
                ensureTranslationOverlay()
                translationOverlay?.isVisible = true
                translationOverlay?.bind(imageView, blocks, pageWidth, pageHeight)
            }
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
     * repushing the translate button and any active processing overlay (scrim,
     * spinner, error text) behind it. Callers who show the overlay before
     * setting the image would otherwise see no dimming and no spinner, and the
     * button would be invisible to touch.
     */
    private fun restoreOverlayOrder() {
        // Overlay goes above the pageView
        translationOverlay?.bringToFront()
        // Button: must always be on top so it can receive taps.
        translateButton?.bringToFront()
        // Processing overlay: the scrim dims the image, the spinner gives
        // progress feedback, and the error text surfaces failures.
        processingScrim?.bringToFront()
        processingIndicator?.bringToFront()
        errorText?.bringToFront()
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

    private fun ensureProcessingOverlay() {
        if (processingIndicator != null && processingScrim != null) return
        // Scrim: a transparent click-passthrough view used to dim the image.
        val scrim = View(context)
        scrim.setBackgroundColor(0x66000000.toInt())
        scrim.isClickable = false
        scrim.isFocusable = false
        scrim.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        addView(scrim)
        processingScrim = scrim

        val indicator = ReaderProgressIndicator(context)
        indicator.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        addView(indicator)
        processingIndicator = indicator

        // TachiyomiAT: error text shown below the spinner when a page's
        // translation finished with an error (OOM, bad AI response, timeout,
        // etc.). Previously these were silent — the page just looked like it
        // never got translated.
        val tv = android.widget.TextView(context).apply {
            setTextColor(0xFF_FF6B6B.toInt())
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            gravity = Gravity.CENTER
            isVisible = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                topMargin = (64 * resources.displayMetrics.density).toInt()
            }
        }
        addView(tv)
        errorText = tv

        // Make sure the overlay is rendered above the page image.
        scrim.bringToFront()
        indicator.bringToFront()
        tv.bringToFront()
    }

    fun showTranslationError(message: String?) {
        if (message.isNullOrBlank()) {
            errorText?.isVisible = false
            return
        }
        ensureProcessingOverlay()
        errorText?.apply {
            text = message
            isVisible = true
            bringToFront()
        }
    }

    fun showProcessingOverlay(visible: Boolean) {
        // TachiyomiAT: idempotent — no-op when the requested state already matches
        // the current overlay state. Translation status re-emits (RUNNING stage
        // transitions) can call this repeatedly; without this guard each call
        // would re-trigger the fade and the scrim/indicator toggle.
        if (visible == processingOverlayVisible) return
        processingOverlayVisible = visible

        // Dim the page image with a short fade instead of an instant alpha snap,
        // so the overlay appearing/disappearing feels smooth rather than blinking.
        // Cancel any in-flight alpha animator first so rapid toggles don't stack.
        pageView?.animate()?.cancel()
        val targetAlpha = if (visible) DIMMED_ALPHA else 1.0f
        pageView
            ?.animate()
            ?.alpha(targetAlpha)
            ?.setDuration(OVERLAY_FADE_DURATION_MS)
            ?.start()

        if (visible) {
            ensureProcessingOverlay()
            processingScrim?.isVisible = true
            processingIndicator?.show()
        } else {
            processingScrim?.isVisible = false
            processingIndicator?.hide()
        }
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError()
                        }
                    },
                )
            }
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
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
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
        // TachiyomiAT: prepare*ImageView() removes the old pageView and adds a
        // new one, which pushes it to the END of the FrameLayout child list —
        // putting the SSIV/PhotoView ON TOP of the translate button AND any
        // active processing overlay (scrim + spinner) for touch dispatch and
        // drawing, even though bringToFront was called earlier. Re-bring
        // those child views to the front so they actually appear/detect taps.
        restoreOverlayOrder()
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
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
        // TachiyomiAT: keep overlay children on top after the new pageView is
        // added as the last child (see setImage(drawable) for details).
        restoreOverlayOrder()
    }

    fun recycle() {
        translationImageSelected = false
        translationImageReady = false
        pendingTranslationBlocks = emptyList()
        pendingPageWidth = 0
        pendingPageHeight = 0
        translationOverlay?.isVisible = false
        translationOverlay?.clear()
        pageView?.let {
            when (it) {
                is SubsamplingScaleImageView -> it.recycle()
                is AppCompatImageView -> it.dispose()
            }
            it.isVisible = false
        }
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

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

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
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError()
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
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                        onError = {
                            onImageLoadError()
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
        if (pageView is AppCompatImageView) return
        removeView(pageView)

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
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
                onError = {
                    this@ReaderPageImageView.onImageLoadError()
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

// TachiyomiAT: animation tuning for the translation processing overlay.
private const val DIMMED_ALPHA = 0.4f
private const val OVERLAY_FADE_DURATION_MS = 150L
