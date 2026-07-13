package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.translation.model.shouldSurfaceError
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.shouldShowTranslationOverlay
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    // TachiyomiAT
    private val readerPreferences: ReaderPreferences = Injekt.get(),
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    // TachiyomiAT
    private var showTranslations = true

    // TachiyomiAT: master gate for the per-page translate button. Updated live
    // from the preference (collected in holderScope below) so the button
    // appears/disappears the instant the user toggles translation, instead of
    // only on the next setImage() pass.
    private var translationEnabled =
        Injekt.get<tachiyomi.domain.translation.TranslationPreferences>().translationEnabled().get()

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    // TachiyomiAT: var so [onAttachedToWindow] can replace it with a fresh scope
    // after [onDetachedFromWindow] cancels the previous one (see lifecycle note
    // on the first-page translation animation fix).
    private var holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * TachiyomiAT: the rendered/cleaned image FILE NAME this holder is currently
     * displaying, so [refreshTranslation] can tell whether a refresh actually
     * needs to reload the image (new/different translated image) or only sync
     * overlays (the displayed image is already correct). Set whenever [setImage]
     * / [refreshTranslation] actually feeds a new image to the view.
     *
     * This replaces an earlier guard that keyed on `===` referential identity of
     * the translated-stream lambda. That was fragile: the stream factories
     * ([getRenderedImageStream]/[getCleanedImageStream]) allocate a fresh lambda
     * on every call, so identity only held by coincidence across status-only
     * re-emissions. Keying on the stable file NAME (rendered wins over cleaned,
     * matching the ViewModel collector's precedence) makes the dedup content-
     * based and robust to any path that re-resolves the stream.
     *
     * Null means "we have not yet rendered a translated image for this holder".
     */
    private var lastShownImageName: String? = null

    init {
        // TachiyomiAT: init only seeds durable state + wires click handlers.
        // The long-running collectors (status load, showTranslations /
        // translationEnabled reactivity, observePageView) are (re)established in
        // [onAttachedToWindow]: ViewPager detaches holder views during layout
        // passes and on activity resume, [onDetachedFromWindow] cancels
        // [holderScope] to avoid leaks, and there is no logic to revive the scope
        // or its collectors on re-attach. Keeping the collectors here left them
        // permanently dead after the first re-attach — the first page never
        // received the observePageView emission that swaps in the translated
        // image, so no replace animation fired.
        showTranslations = readerPreferences.showTranslations().get()
        // Per-page translate button
        onTranslateClicked = {
            viewer.activity.viewModel.translateSinglePage(page)
        }
        // TachiyomiAT: cancel affordance shown while a translation is running
        // for this page (the button re-purposes itself via setTranslating).
        onCancelTranslateClicked = {
            viewer.activity.viewModel.cancelSinglePageTranslation(page)
        }
        // TachiyomiAT: seed the processing overlay from the page's DURABLE
        // translation status at construction. A PagerPageHolder is freshly created
        // whenever the ViewPager rebuilds its offscreen holder set (scroll/navigate
        // back to a page whose holder was destroyed). ReaderPageImageView starts
        // with the ephemeral overlay state cleared, and it would otherwise only be
        // re-shown once loadPageAndProcessStatus()'s statusFlow reaches READY and
        // setImage() runs — leaving a window (long, for an already-decoded page
        // that's mid-translation) with no animation despite the page's stages still
        // being "RUNNING". syncTranslationStatus() reads the durable stage status
        // and shows the scrim/spinner immediately; it only touches the overlay +
        // button, never re-decodes, so it's safe to call before the image loads.
        syncTranslationStatus()
    }

    /**
     * TachiyomiAT: (re)create the holder scope and re-establish its long-running
     * collectors whenever the view attaches. [onDetachedFromWindow] cancels
     * [holderScope]; ViewPager routinely detaches and re-attaches holder views
     * during layout passes and on activity resume. Because init no longer
     * subscribes (it only seeds state), this is the single place subscriptions
     * are created — once on the first attach, and again after every re-attach
     * that followed a scope-cancelling detach. This is what keeps the first
     * page's observePageView collector (which drives the translated-image
     * replace animation) alive instead of permanently cancelled.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!holderScope.isActive) {
            holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        loadJob = holderScope.launch { loadPageAndProcessStatus() }
        readerPreferences.showTranslations().changes().onEach {
            showTranslations = it
            page.showTranslatedImage = it && page.translatedStream != null
            if (page.originalStream != null && page.translatedStream != null) {
                setImage()
            }
        }.launchIn(holderScope)
        // Reactively update the translate button when the master toggle flips,
        // so it shows/hides immediately rather than waiting for the next
        // setImage() pass. While a page is mid-translation the processing
        // overlay stays up and the button stays hidden.
        Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()
            .translationEnabled().changes().onEach { enabled ->
                translationEnabled = enabled
                // Re-evaluate the button: while running it shows the cancel
                // affordance regardless of the toggle; when idle it follows the
                // master enable preference.
                if (!isPageBeingTranslated()) {
                    showTranslateButton(enabled)
                    setTranslating(false)
                }
            }.launchIn(holderScope)
        viewer.activity.viewModel.observePageView(page)
            ?.onEach { refreshTranslation() }
            ?.launchIn(holderScope)
    }

    /**
     * TachiyomiAT: true when this page has a RUNNING OCR/inpaint/translate/render
     * stage and no rendered/cleaned result yet. Centralised here so the init
     * collector, [setImage] and [refreshTranslation] all agree on the
     * "is this page mid-translation" predicate that decides whether to show the
     * cancel affordance vs. the translate affordance.
     */
    private fun isPageBeingTranslated(): Boolean = page.translation?.let { t ->
        t.ocrStatus == "RUNNING" ||
            t.inpaintStatus == "RUNNING" ||
            t.translationStatus == "RUNNING" ||
            t.renderStatus == "RUNNING"
    } ?: false

    /**
     * TachiyomiAT: pushes the current translation status of this page to the
     * per-page button: cancel affordance while running, translate affordance
     * (visible only when translation is enabled) when idle. Replaces the prior
     * "hide the button entirely during work" behaviour, which gave the user no
     * feedback and no way to cancel a slow page.
     */
    private fun syncTranslateButtonState() {
        if (isPageBeingTranslated()) {
            showProcessingOverlay(true)
            setTranslating(true)
        } else {
            showProcessingOverlay(false)
            showTranslateButton(translationEnabled)
            setTranslating(false)
        }
    }

    /**
     * TachiyomiAT: lightweight status-only sync. Counterpart to [refreshTranslation]
     * for the case where a page's stage transitioned (RUNNING/FAILED) but the
     * displayed IMAGE did not change. Updates ONLY the processing overlay and the
     * translate/cancel button + error text — it must never re-decode or re-set
     * the image. Holder-level page-view updates call this for status-only changes,
     * which avoids the redundant image work that caused the auto-translate blink.
     */
    fun syncTranslationStatus() {
        val isBeingTranslated = isPageBeingTranslated()
        if (isBeingTranslated) {
            showProcessingOverlay(true)
            setTranslating(true)
        } else {
            showProcessingOverlay(false)
            showTranslateButton(translationEnabled)
            setTranslating(false)
        }
        // Surface errors only for a genuine terminal failure (matches
        // refreshTranslation's guard). PARTIAL/Cancelled/Textless pages carry
        // an explanatory errorMessage that must NOT be painted red.
        val translation = page.translation
        val errorMsg = if (translation != null && translation.shouldSurfaceError) {
            translation.errorMessage
        } else {
            null
        }
        showTranslationError(errorMsg)
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        holderScope.cancel()
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.QUEUE -> setQueued()
                    Page.State.LOAD_PAGE -> setLoading()
                    Page.State.DOWNLOAD_IMAGE -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.READY -> {
                        setImage()
                    }
                    Page.State.ERROR -> setError()
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        // Eagerly resolve the translated stream to avoid original-then-translated flash on load.
        if (page.translatedStream == null && showTranslations) {
            viewer.activity.viewModel.attachTranslatedStreamForPage(page)
        }
        if (!page.translationToggled) {
            page.showTranslatedImage = showTranslations && page.translatedStream != null
        }
        val streamFn = page.stream ?: return

        // Record the rendered/cleaned image file name to avoid no-op decodes on refresh.
        lastShownImageName = if (page.showTranslatedImage) {
            page.translation?.displayImageName
        } else {
            null
        }

        val isBeingTranslated = isPageBeingTranslated()
        if (isBeingTranslated) {
            showProcessingOverlay(true)
            // Show the cancel affordance instead of hiding the button, so the
            // user gets feedback that translation is running and can cancel it.
            setTranslating(true)
        } else {
            showTranslateButton(translationEnabled)
            setTranslating(false)
        }

        try {
            val (source, isAnimated, background) = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                Triple(source, isAnimated, background)
            }
            // TachiyomiAT: if the holder was detached (scope cancelled) while we
            // decoded off-thread, drop the result instead of writing into a dead
            // view. Cooperative cancellation only fires at suspension points.
            if (!holderScope.isActive) return
            withUIContext {
                if (!holderScope.isActive) return@withUIContext
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                if (isBeingTranslated) {
                    showProcessingOverlay(true)
                }
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                showProcessingOverlay(false)
                setError()
            }
        }
    }

    fun refreshTranslation() {
        val streamAvailable = page.translatedStream != null
        val isBeingTranslated = isPageBeingTranslated()
        val newName = page.translation?.displayImageName

        val wantTranslated = if (page.translationToggled) {
            page.showTranslatedImage && streamAvailable
        } else {
            showTranslations && streamAvailable
        }

        val alreadyShowingCorrectImage = if (wantTranslated) {
            newName != null && newName == lastShownImageName
        } else {
            lastShownImageName == null
        }

        when {
            isBeingTranslated -> {
                showProcessingOverlay(true)
                setTranslating(true)
            }
            wantTranslated -> {
                if (alreadyShowingCorrectImage) {
                    showProcessingOverlay(false)
                    showTranslateButton(translationEnabled)
                    setTranslating(false)
                } else {
                    page.showTranslatedImage = true
                    showProcessingOverlay(false)
                    showTranslateButton(translationEnabled)
                    setTranslating(false)
                    loadJob?.cancel()
                    loadJob = holderScope.launch { setImage() }
                }
            }
            else -> {
                if (alreadyShowingCorrectImage) {
                    showProcessingOverlay(false)
                    showTranslateButton(translationEnabled)
                    setTranslating(false)
                } else {
                    page.showTranslatedImage = false
                    showProcessingOverlay(false)
                    showTranslateButton(translationEnabled)
                    setTranslating(false)
                    loadJob?.cancel()
                    loadJob = holderScope.launch { setImage() }
                }
            }
        }
        // Remember the image name we're now showing so the next
        // refreshTranslation() can short-circuit if nothing changed. When showing
        // the original (not a translated stream) there's no name to track.
        lastShownImageName = if (page.showTranslatedImage) newName else null
        // TachiyomiAT: surface translation errors to the user — but only for a
        // genuine terminal failure. Cancellation, the stranded-page sweep, and
        // PARTIAL all write an explanatory errorMessage that is NOT a failure;
        // painting those red over a page that produced output (or was stopped
        // deliberately) is misleading. shouldSurfaceError admits only real
        // FAILED stages with no rendered/cleaned result to show instead.
        val translation = page.translation
        val errorMsg = if (translation != null && translation.shouldSurfaceError) {
            translation.errorMessage
        } else {
            null
        }
        showTranslationError(errorMsg)

        val wantOverlay = page.showTranslatedImage && translation?.shouldShowTranslationOverlay == true
        val blocksToDraw = if (wantOverlay) translation!!.blocks else emptyList()
        val w = if (wantOverlay) translation!!.imgWidth.toInt() else 0
        val h = if (wantOverlay) translation!!.imgHeight.toInt() else 0
        viewer.activity.runOnUiThread {
            setTranslationBlocks(blocksToDraw, w, h)
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError() {
        progressIndicator?.hide()
        showErrorLayout()
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError() {
        super.onImageLoadError()
        setError()
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    // TachiyomiAT
    override fun onCenterChanged(newCenter: PointF?) {
        super.onCenterChanged(newCenter)
    }

    private fun showErrorLayout(): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
