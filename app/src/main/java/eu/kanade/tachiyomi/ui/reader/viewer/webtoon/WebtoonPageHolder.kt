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

    // TachiyomiAT: the holder's coroutine scope. Unlike PagerPageHolder (which
    // cancels its scope once in onDetachedFromWindow, the end of the view's
    // life), a WebtoonPageHolder is REUSED after recycle() — RecyclerView
    // returns it to the pool and rebinds it. So the scope must be cancelled on
    // recycle AND recreated on bind, otherwise the holder would carry a
    // cancelled scope forever and never load again. Recreation is cheap
    // (SupervisorJob + Main.immediate) and is what makes recycle() safe.
    private var holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var loadJob: Job? = null

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
    private var lastShownRenderRevision: Long = -1L

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
    private fun isPageBeingTranslated(): Boolean = page?.translation?.let { t ->
        (
            t.ocrStatus == "RUNNING" ||
                t.inpaintStatus == "RUNNING" ||
                t.translationStatus == "RUNNING" ||
                t.renderStatus == "RUNNING"
            ) &&
            t.renderedImageName == null &&
            (t.cleanedImageName == null || t.blocks.isNotEmpty())
    } ?: false

    /**
     * TachiyomiAT: lightweight status-only sync. Counterpart to [refreshTranslation]
     * for the case where a page's stage transitioned (RUNNING/FAILED) but the
     * displayed IMAGE did not change. Updates ONLY the processing overlay and the
     * translate/cancel button + error text — it must never re-decode or re-set
     * the image. Holder-level page-view updates call this for status-only changes,
     * which avoids the redundant image work that caused the auto-translate blink.
     */
    fun syncTranslationStatus() {
        val currentPage = page ?: return
        val isBeingTranslated = isPageBeingTranslated()
        if (isBeingTranslated) {
            frame.showProcessingOverlay(true)
            frame.setTranslating(true)
        } else {
            frame.showProcessingOverlay(false)
            frame.showTranslateButton(translationEnabled)
            frame.setTranslating(false)
        }
        // Surface errors only when idle (matches refreshTranslation's guard).
        val errorMsg = if (!isBeingTranslated) currentPage.translation?.errorMessage else null
        frame.showTranslationError(errorMsg)
    }

    fun bind(page: ReaderPage) {
        // TachiyomiAT: invalidate any in-flight setImage() so it can't write the
        // previous page's image into this rebound holder (see [bindGeneration]).
        bindGeneration++
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
        // TachiyomiAT: seed the processing overlay from the page's DURABLE
        // translation status the instant it is bound. Previously the overlay was
        // only (re)shown when [setImage]/[refreshTranslation]/[syncTranslationStatus]
        // ran — and those are only driven by the page-load statusFlow reaching READY
        // or by a page-view update reaching an ATTACHED holder. So a
        // page that scrolled off-screen (holder recycled → onDetachedFromWindow
        // cleared the ephemeral overlay state) and scrolled back WHILE still
        // mid-translation had a window — sometimes a long one — with no animation,
        // even though PageTranslation.*Status was still "RUNNING". Re-deriving the
        // overlay here from the durable stage status makes it consistent: any bound
        // (visible) translating page animates immediately, independent of event or
        // decode timing. syncTranslationStatus() only touches the overlay/button
        // (never re-decodes), so it's safe before loadPageAndProcessStatus() runs.
        syncTranslationStatus()
        viewer.activity.viewModel.observePageView(page)
            ?.onEach { refreshTranslation() }
            ?.launchIn(holderScope)
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
        // this check a refresh job past its withIOContext could still call
        // frame.setImage() with the OLD page's bytes into a rebound holder.
        val myGeneration = bindGeneration
        val boundPage = page ?: return

        boundPage.showTranslatedImage = showTranslations && boundPage.translatedStream != null
        val streamFn = boundPage.stream ?: return

        // TachiyomiAT: record the rendered/cleaned image file name we're about to
        // display (rendered wins over cleaned, matching the ViewModel collector's
        // precedence) so refreshTranslation() can skip a redundant re-decode when
        // the same image is already on screen. See [lastShownImageName].
        lastShownImageName = if (boundPage.showTranslatedImage) {
            boundPage.translation?.renderedImageName ?: boundPage.translation?.cleanedImageName
        } else {
            null
        }
        lastShownRenderRevision = if (boundPage.showTranslatedImage) {
            boundPage.translation?.renderRevision ?: -1L
        } else {
            -1L
        }

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
                if (isBeingTranslated) {
                    frame.showProcessingOverlay(true)
                }
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            if (myGeneration != bindGeneration) return
            logcat(LogPriority.ERROR, e)
            withUIContext {
                if (myGeneration != bindGeneration) return@withUIContext
                frame.showProcessingOverlay(false)
                setError()
            }
        }
    }

    fun refreshTranslation() {
        val currentPage = page ?: return
        val streamAvailable = currentPage.translatedStream != null
        val isBeingTranslated = isPageBeingTranslated()
        // TachiyomiAT: only relaunch setImage() when the translated image we'd
        // render is DIFFERENT from the one already on screen. The dedup is keyed
        // on the stable rendered/cleaned file NAME (rendered wins over cleaned,
        // matching the ViewModel collector) rather than the stream lambda's
        // referential identity, which was fragile because the stream factories
        // return a fresh lambda on every call. A refresh for a status-only
        // change (RUNNING→READY re-emitted, no new image) must NOT re-decode &
        // re-set the image — that's the visible flash.
        val newName = currentPage.translation?.renderedImageName ?: currentPage.translation?.cleanedImageName
        val newRevision = currentPage.translation?.renderRevision ?: -1L
        val alreadyShowingThisImage =
            currentPage.showTranslatedImage &&
                newName != null &&
                newName == lastShownImageName &&
                newRevision == lastShownRenderRevision
        when {
            isBeingTranslated -> {
                frame.showProcessingOverlay(true)
                // Cancel affordance while running, instead of hiding the button.
                frame.setTranslating(true)
            }
            showTranslations && streamAvailable -> {
                if (alreadyShowingThisImage) {
                    // Same translated image already on screen — sync overlays
                    // only, do NOT re-decode & re-set the image.
                    frame.showProcessingOverlay(false)
                    frame.showTranslateButton(translationEnabled)
                    frame.setTranslating(false)
                } else {
                    currentPage.showTranslatedImage = true
                    frame.showProcessingOverlay(false)
                    frame.showTranslateButton(translationEnabled)
                    frame.setTranslating(false)
                    loadJob?.cancel()
                    loadJob = holderScope.launch { setImage() }
                }
            }
            else -> {
                frame.showProcessingOverlay(false)
                frame.showTranslateButton(translationEnabled)
                frame.setTranslating(false)
            }
        }
        // Record the image name we're now showing so the next refresh can
        // short-circuit if nothing changed. When showing the original (not a
        // translated stream) there's no name to track.
        lastShownImageName = if (currentPage.showTranslatedImage) newName else null
        lastShownRenderRevision = if (currentPage.showTranslatedImage) newRevision else -1L
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
