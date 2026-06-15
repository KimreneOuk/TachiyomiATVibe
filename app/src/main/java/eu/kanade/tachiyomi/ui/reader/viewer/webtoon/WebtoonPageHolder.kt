package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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

class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
) : WebtoonBaseHolder(frame, viewer) {

    // TachiyomiAT: these two app-global preferences are now read from the single
    // shared observer on the owning WebtoonViewer (viewer.translationPrefs),
    // which is cancelled when the viewer is destroyed. Previously each holder
    // launched its own .changes() collector on holderScope (which was never
    // cancelled), leaking one collector per recycled holder for the process
    // lifetime. Delegate reads to the viewer; values refresh via
    // refreshTranslation() which the viewer calls on visible holders.
    private val showTranslations get() = viewer.translationPrefs.showTranslations
    private val translationEnabled get() = viewer.translationPrefs.translationEnabled

    private val progressIndicator = createProgressIndicator()

    private lateinit var progressContainer: ViewGroup

    private var errorLayout: ReaderErrorBinding? = null

    private val parentHeight
        get() = viewer.recycler.height

    private var page: ReaderPage? = null

    private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var loadJob: Job? = null

    init {
        refreshLayoutParams()

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { setError() }
        frame.onScaleChanged = { viewer.activity.hideMenu() }

        // TachiyomiAT: the showTranslations() / translationEnabled() collectors
        // used to live here per-holder and leaked (holderScope was never
        // cancelled). They have been hoisted to WebtoonViewer.translationPrefs,
        // which calls refreshTranslation() on visible holders on change.
        // Per-page translate button
        frame.onTranslateClicked = {
            page?.let { viewer.activity.viewModel.translateSinglePage(it) }
        }
        // TachiyomiAT: cancel affordance shown while a translation is running
        // for this page (the button re-purposes itself via setTranslating).
        frame.onCancelTranslateClicked = {
            page?.let { viewer.activity.viewModel.cancelSinglePageTranslation(it) }
        }
    }

    /**
     * TachiyomiAT: true when the bound page has a RUNNING OCR/inpaint/translate/
     * render stage and no rendered/cleaned result yet. Centralised here so
     * [setImage] and [refreshTranslation] agree on the "is this page
     * mid-translation" predicate that decides whether to show the cancel
     * affordance vs. the translate affordance.
     */
    private fun isPageBeingTranslated(): Boolean = page?.translation?.let { t ->
        (t.ocrStatus == "RUNNING" || t.inpaintStatus == "RUNNING" ||
            t.translationStatus == "RUNNING" || t.renderStatus == "RUNNING") &&
            t.renderedImageName == null && t.cleanedImageName == null
    } ?: false

    fun bind(page: ReaderPage) {
        this.page = page
        loadJob?.cancel()
        loadJob = holderScope.launch { loadPageAndProcessStatus() }
        refreshLayoutParams()
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
            marginEnd = margin.toInt()
            marginStart = margin.toInt()
        }
    }

    override fun recycle() {
        loadJob?.cancel()
        loadJob = null

        removeErrorLayout()
        frame.recycle()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
    }

    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
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
                            progressIndicator.setProgress(value)
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

    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    private suspend fun setImage() {
        progressIndicator.setProgress(0)

        page?.showTranslatedImage = showTranslations && page?.translatedStream != null
        val streamFn = page?.stream ?: return

        val isBeingTranslated = isPageBeingTranslated()
        if (isBeingTranslated) {
            frame.showProcessingOverlay(true)
            // Show the cancel affordance instead of hiding the button, so the
            // user gets feedback that translation is running and can cancel it.
            frame.setTranslating(true)
        } else {
            frame.showTranslateButton(translationEnabled)
            frame.setTranslating(false)
        }

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            withUIContext {
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        cropBorders = viewer.config.imageCropBorders,
                    ),
                )
                if (isBeingTranslated) {
                    frame.showProcessingOverlay(true)
                }
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                frame.showProcessingOverlay(false)
                setError()
            }
        }
    }

    fun refreshTranslation() {
        val currentPage = page ?: return
        val streamAvailable = currentPage.translatedStream != null
        val isBeingTranslated = isPageBeingTranslated()
        when {
            isBeingTranslated -> {
                frame.showProcessingOverlay(true)
                // Cancel affordance while running, instead of hiding the button.
                frame.setTranslating(true)
            }
            showTranslations && streamAvailable -> {
                currentPage.showTranslatedImage = true
                frame.showProcessingOverlay(false)
                frame.showTranslateButton(translationEnabled)
                frame.setTranslating(false)
                loadJob?.cancel()
                loadJob = holderScope.launch { setImage() }
            }
            else -> {
                frame.showProcessingOverlay(false)
                frame.showTranslateButton(translationEnabled)
                frame.setTranslating(false)
            }
        }
        // TachiyomiAT: surface translation errors — but only when the page is NOT
        // currently running, to avoid showing stale errors from a prior failed
        // attempt alongside the RUNNING overlay.
        val errorMsg = if (!isBeingTranslated) currentPage.translation?.errorMessage else null
        frame.showTranslationError(errorMsg)
    }

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }

        return imageSource
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

    private fun setError() {
        progressContainer.isVisible = false
        initErrorLayout()
    }

    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(context).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    private fun initErrorLayout(): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl)
                    context.startActivity(intent)
                }
            }
        }

        return errorLayout!!
    }

    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
