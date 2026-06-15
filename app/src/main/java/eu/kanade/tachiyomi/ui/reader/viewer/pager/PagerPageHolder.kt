package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.view.isVisible
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    //TachiyomiAT
    readerPreferences: ReaderPreferences = Injekt.get(),
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    //TachiyomiAT
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

    private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    init {
        loadJob = holderScope.launch { loadPageAndProcessStatus() }
        //TachiyomiAT
        showTranslations = readerPreferences.showTranslations().get()
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
                val isBeingTranslated = page.translation?.let { t ->
                    (t.ocrStatus == "RUNNING" || t.inpaintStatus == "RUNNING" ||
                        t.translationStatus == "RUNNING" || t.renderStatus == "RUNNING") &&
                        t.renderedImageName == null && t.cleanedImageName == null
                } ?: false
                if (!isBeingTranslated) showTranslateButton(enabled)
            }.launchIn(holderScope)
        // Per-page translate button
        onTranslateClicked = {
            viewer.activity.viewModel.translateSinglePage(page)
        }
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

        page.showTranslatedImage = showTranslations && page.translatedStream != null
        val streamFn = page.stream ?: return

        val isBeingTranslated = page.translation?.let { t ->
            (t.ocrStatus == "RUNNING" || t.inpaintStatus == "RUNNING" ||
                t.translationStatus == "RUNNING" || t.renderStatus == "RUNNING") &&
            t.renderedImageName == null && t.cleanedImageName == null
        } ?: false
        if (isBeingTranslated) {
            showProcessingOverlay(true)
            showTranslateButton(false)
        } else {
            showTranslateButton(translationEnabled)
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
            withUIContext {
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
        val isBeingTranslated = page.translation?.let { t ->
            (t.ocrStatus == "RUNNING" || t.inpaintStatus == "RUNNING" ||
                t.translationStatus == "RUNNING" || t.renderStatus == "RUNNING") &&
            t.renderedImageName == null && t.cleanedImageName == null
        } ?: false
        when {
            isBeingTranslated -> {
                showProcessingOverlay(true)
                showTranslateButton(false)
            }
            showTranslations && streamAvailable -> {
                page.showTranslatedImage = true
                showProcessingOverlay(false)
                showTranslateButton(translationEnabled)
                loadJob?.cancel()
                loadJob = holderScope.launch { setImage() }
            }
            else -> {
                showProcessingOverlay(false)
                showTranslateButton(translationEnabled)
            }
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

    //TachiyomiAT
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
