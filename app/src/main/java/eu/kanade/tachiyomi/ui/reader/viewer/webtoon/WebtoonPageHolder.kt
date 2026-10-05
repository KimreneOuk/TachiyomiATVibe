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
import eu.kanade.tachiyomi.ui.reader.viewer.selectReaderTranslationOverlayBinding
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.shouldShowTranslationOverlay
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

/**
 * Identity fence for callbacks that outlive a Webtoon holder bind. A recycled
 * holder can keep the same view instance while its page changes, so generation
 * and page identity are both required.
 */
internal data class ReaderHolderBindFence<T : Any>(
    val generation: Int,
    val page: T,
) {
    fun isCurrent(currentGeneration: Int, currentPage: T?): Boolean =
        generation == currentGeneration && page === currentPage

    /**
     * Dispatches an emission only while the captured bind and page are current.
     * This is the production seam used by the Auto/page-view collectors and
     * lets rebind tests assert that a stale callback did not mutate the holder.
     */
    fun dispatchIfCurrent(currentGeneration: Int, currentPage: T?, action: () -> Unit): Boolean {
        if (!isCurrent(currentGeneration, currentPage)) return false
        action()
        return true
    }
}

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
        get() = viewer.recycler.height.takeIf { it > 0 }
            ?: itemView.resources.displayMetrics.heightPixels

    private var page: ReaderPage? = null

    // TachiyomiAT: the holder's coroutine scope. Unlike PagerPageHolder (which
    // cancels its scope once in onDetachedFromWindow, the end of the view's
    // life), a WebtoonPageHolder is REUSED after recycle() — RecyclerView
    // returns it to the pool and rebinds it. So the scope must be cancelled on
    // recycle AND recreated on bind, otherwise the holder would carry a
    // cancelled scope forever and never load again. Recreation is cheap
    // (SupervisorJob + Main.immediate) and is what makes recycle() safe.
    private var holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var loadJob: Job? = null
    private var pageViewJob: Job? = null

    /**
     * TachiyomiAT: the rendered/cleaned image FILE NAME currently displayed by
     * this holder, so [refreshTranslation] can skip a redundant re-decode when
     * the same translated image is already on screen.
     *
     * This replaces an earlier guard that keyed on `===` referential identity of
     * the translated-stream lambda. That was fragile: the stream factories
     * ([getRenderedImageStream]/[getCleanedImageStream]) return a brand-new
     * lambda on every call, so identity only happened to be stable across
     * status-only re-emissions. Keying on the stable file name (rendered wins
     * over cleaned, matching the ViewModel collector's precedence) makes the
     * dedup content-based and robust to any path that re-resolves the stream.
     */
    private var lastShownImageName: String? = null

    /**
     * TachiyomiAT: a generation counter bumped on every [bind]. In-flight
     * [setImage] jobs capture the generation they were launched with and bail
     * before touching the view if a newer bind has landed — cooperative
     * cancellation only fires at suspension points, so a refresh job past its
     * [withIOContext] could otherwise set the OLD page's image into a holder
     * that has since been rebound to a new page.
     */
    private var bindGeneration: Int = 0

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
    private fun isPageBeingTranslated(): Boolean = page?.translation?.isStageRunning == true

    /** Syncs the kept translate/cancel button and active-work dim scrim. */
    private fun syncTranslateButtonState() {
        val isBeingTranslated = isPageBeingTranslated()
        frame.setTranslationDimmed(isBeingTranslated)
        if (isBeingTranslated) {
            frame.setTranslating(true)
        } else {
            frame.showTranslateButton(translationEnabled)
            frame.setTranslating(false)
        }
    }

    fun bind(page: ReaderPage) {
        // TachiyomiAT: invalidate any in-flight setImage() so it can't write the
        // previous page's image into this rebound holder (see [bindGeneration]).
        bindGeneration++
        val generation = bindGeneration
        val boundPage = page
        pageViewJob?.cancel()
        pageViewJob = null
        loadJob?.cancel()
        loadJob = null
        // A bind can happen without recycle() when RecyclerView reuses an
        // attached holder. Reset both decoded views and presentation state at
        // this boundary so page A cannot remain visible behind page B.
        frame.recycle()
        removeErrorLayout()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
        // TachiyomiAT: a freshly bound page hasn't rendered anything yet — reset
        // the dedup cache so the first refresh after rebind always applies.
        lastShownImageName = null
        // TachiyomiAT: recreate the scope. [recycle] cancels it (to release the
        // holder's references while pooled), so a reused holder arrives here
        // with a dead scope and must get a fresh one before launching jobs.
        if (!holderScope.isActive) {
            holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        this.page = page
        val bindFence = ReaderHolderBindFence(generation, boundPage)
        syncTranslateButtonState()
        pageViewJob = viewer.activity.viewModel.observePageView(boundPage)
            ?.onEach {
                bindFence.dispatchIfCurrent(bindGeneration, this.page) { refreshTranslation() }
            }
            ?.launchIn(holderScope)
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
        pageViewJob?.cancel()
        pageViewJob = null

        // TachiyomiAT: cancel the holder's coroutine scope. Previously this was
        // never done — the SupervisorJob + Main.immediate context leaked for the
        // process lifetime of every recycled holder, pinning the holder → frame
        // (ReaderPageImageView) → Activity-themed context → Compose indicator.
        // The per-holder preference collectors that motivated the original leak
        // were hoisted to WebtoonViewer, but the scope itself still leaked.
        holderScope.cancel()

        // TachiyomiAT: drop references so a holder sitting in the RecyclerView
        // pool doesn't keep the previous page (and its stream lambdas) alive,
        // and doesn't carry stale dedup state into its next binding.
        page = null
        lastShownImageName = null
        bindGeneration++

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

        // TachiyomiAT: capture the generation this setImage() was launched for.
        // If a newer bind() lands before we reach the UI update, bail —
        // cooperative cancellation only fires at suspension points, so without
        val myGeneration = bindGeneration
        val boundPage = page ?: return

        // Eagerly resolve the translated stream to avoid original-then-translated flash on load.
        if (boundPage.translatedStream == null && showTranslations) {
            viewer.activity.viewModel.attachTranslatedStreamForPage(boundPage)
        }
        if (!boundPage.translationToggled) {
            boundPage.showTranslatedImage = showTranslations && (boundPage.translatedStream != null || boundPage.translation?.shouldShowTranslationOverlay == true)
        }
        val streamFn = boundPage.stream ?: return
        frame.prepareTranslationImage(boundPage.showTranslatedImage)
        selectReaderTranslationOverlayBinding(boundPage.showTranslatedImage, boundPage.translation).let { overlay ->
            frame.setTranslationBlocks(overlay.blocks, overlay.pageWidth, overlay.pageHeight, overlay.pageKey)
        }

        // Record the rendered/cleaned image file name to avoid no-op decodes on refresh.
        lastShownImageName = if (boundPage.showTranslatedImage) {
            boundPage.translation?.displayImageName
        } else {
            null
        }

        // Keep the button and scrim aligned with the durable running-stage state.
        syncTranslateButtonState()

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            // TachiyomiAT: rebind guard — if a newer page was bound while we
            // decoded off-thread, drop this result instead of flashing the wrong
            // page. Also bail if the coroutine was cancelled.
            if (myGeneration != bindGeneration || !holderScope.isActive) return
            withUIContext {
                if (myGeneration != bindGeneration) return@withUIContext
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        cropBorders = viewer.config.imageCropBorders,
                    ),
                )
                syncTranslateButtonState()
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            if (myGeneration != bindGeneration) return
            logcat(LogPriority.ERROR, e)
            withUIContext {
                if (myGeneration != bindGeneration) return@withUIContext
                setError()
            }
        }
    }

    fun refreshTranslation() {
        val currentPage = page ?: return
        val streamAvailable = currentPage.translatedStream != null
        val isBeingTranslated = isPageBeingTranslated()
        val newName = currentPage.translation?.displayImageName

        val wantTranslated = if (currentPage.translationToggled) {
            currentPage.showTranslatedImage && streamAvailable
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
                syncTranslateButtonState()
                frame.setTranslating(true)
            }
            wantTranslated -> {
                if (alreadyShowingCorrectImage) {
                    syncTranslateButtonState()
                    frame.showTranslateButton(translationEnabled)
                    frame.setTranslating(false)
                } else {
                    currentPage.showTranslatedImage = true
                    syncTranslateButtonState()
                    frame.showTranslateButton(translationEnabled)
                    frame.setTranslating(false)
                    loadJob?.cancel()
                    loadJob = holderScope.launch { setImage() }
                }
            }
            else -> {
                if (alreadyShowingCorrectImage) {
                    syncTranslateButtonState()
                    frame.showTranslateButton(translationEnabled)
                    frame.setTranslating(false)
                } else {
                    currentPage.showTranslatedImage = false
                    syncTranslateButtonState()
                    frame.showTranslateButton(translationEnabled)
                    frame.setTranslating(false)
                    loadJob?.cancel()
                    loadJob = holderScope.launch { setImage() }
                }
            }
        }
        // Record the image name we're now showing so the next refresh can
        // short-circuit if nothing changed. When showing the original (not a
        // translated stream) there's no name to track.
        lastShownImageName = if (currentPage.showTranslatedImage) newName else null
        syncTranslateButtonState()

        val overlay = selectReaderTranslationOverlayBinding(currentPage.showTranslatedImage, currentPage.translation)
        frame.setTranslationBlocks(overlay.blocks, overlay.pageWidth, overlay.pageHeight, overlay.pageKey)
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
        frame.setTranslationDimmed(false)
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
