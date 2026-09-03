package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    /**
     * TachiyomiAT: app-global translation preferences, collected ONCE on this
     * viewer's scope instead of once per [WebtoonPageHolder]. Previously every
     * holder launched its own `showTranslations().changes()` and
     * `translationEnabled().changes()` collector on a holderScope that was
     * never cancelled — so a long webtoon accumulated N leaked collectors for
     * the life of the process. These are global prefs, so one observer on the
     * viewer (which IS cancelled in [destroy]) is the right granularity.
     *
     * Holders read [showTranslations] / [translationEnabled] directly; the
     * [onChanged] callback routes to [refreshTranslationPages] so visible
     * holders re-evaluate their button + image when a pref flips.
     */
    val translationPrefs = WebtoonTranslationPrefs(this, scope)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    private val threshold: Int =
        Injekt.get<ReaderPreferences>()
            .readerHideThreshold()
            .get()
            .threshold

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val pageCount = chapters.currChapter.pages?.size ?: 0
        val requestedPage = chapters.currChapter.requestedPage
        val oldAdapterCount = adapter.items.size
        val firstLayout = recycler.isGone
        var adapterNanos = 0L
        var moveToPageNanos = 0L
        var visibilityNanos = 0L
        var errorClass = "none"

        try {
            val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
            val adapterStartedAt = SystemClock.elapsedRealtimeNanos()
            adapter.setChapters(chapters, forceTransition)
            adapterNanos = SystemClock.elapsedRealtimeNanos() - adapterStartedAt

            if (recycler.isGone) {
                logcat { "Recycler first layout" }
                val pages = chapters.currChapter.pages ?: return
                val moveToPageStartedAt = SystemClock.elapsedRealtimeNanos()
                moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
                moveToPageNanos = SystemClock.elapsedRealtimeNanos() - moveToPageStartedAt
                val visibilityStartedAt = SystemClock.elapsedRealtimeNanos()
                recycler.isVisible = true
                visibilityNanos = SystemClock.elapsedRealtimeNanos() - visibilityStartedAt
            }
        } catch (error: Throwable) {
            errorClass = error.javaClass.simpleName
            throw error
        } finally {
            logcat(LogPriority.INFO) {
                "[reader_entry] WebtoonViewer.setChapters " +
                    "pageCount=$pageCount requestedPage=$requestedPage oldAdapterCount=$oldAdapterCount " +
                    "newAdapterCount=${adapter.items.size} adapterMs=${adapterNanos.toMillis()} " +
                    "moveMs=${moveToPageNanos.toMillis()} visibilityMs=${visibilityNanos.toMillis()} " +
                    "totalMs=${(SystemClock.elapsedRealtimeNanos() - startedAt).toMillis()} " +
                    "firstLayout=$firstLayout error=$errorClass"
            }
        }
    }

    private fun Long.toMillis(): Double = this / 1_000_000.0

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }

    override fun refreshTranslationPages(pages: Set<ReaderPage>) {
        if (pages.isEmpty()) return
        // TachiyomiAT: only refresh holders bound to a page that actually
        // changed. The previous implementation iterated every attached child
        // holder and called refreshTranslation() on each — so the page the user
        // was reading reloaded its image whenever ANY other page advanced a
        // translation stage (the same "blink" bug the pager viewer had). Since
        // WebtoonPageHolder.page is private, resolve each changed page to its
        // adapter position and refresh only the holder at that position.
        val changedPositions = pages.mapNotNullTo(HashSet()) { page ->
            val idx = adapter.items.indexOf(page)
            if (idx == -1) null else idx
        }
        // TachiyomiAT: deliberately do NOT call adapter.refreshTranslationPages()
        // here. That path issued notifyItemChanged(position) with NO payload, which
        // forces a full onBindViewHolder() -> holder.bind() -> setImage() that
        // completely bypasses the lastShownImageName guard in refreshTranslation()
        // and re-decodes/re-binds the page on every status emission (the visible
        // "blink"). The holder loop above is the only refresh path and it IS
        // guarded, so the adapter call was pure redundant destruction.
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) ?: continue
            val holder = recycler.getChildViewHolder(child)
            if (holder is WebtoonPageHolder &&
                holder.bindingAdapterPosition in changedPositions
            ) {
                holder.refreshTranslation()
            }
        }
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4

/**
 * TachiyomiAT: single shared observer for the app-global translation
 * preferences that [WebtoonPageHolder] previously each collected on their own
 * (leaking one collector per holder for the process lifetime because
 * holderScope was never cancelled). Lives on the owning [WebtoonViewer]'s
 * scope, which IS cancelled in [WebtoonViewer.destroy].
 *
 * Values are [Volatile] because holders read them from recycler layout passes
 * while the collector writes from the viewer scope; both are main-thread in
 * practice, but Volatile keeps it correct without assumption.
 */
class WebtoonTranslationPrefs(
    private val viewer: WebtoonViewer,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    @Volatile
    var showTranslations: Boolean =
        Injekt.get<ReaderPreferences>().showTranslations().get()
        private set

    @Volatile
    var translationEnabled: Boolean =
        Injekt.get<tachiyomi.domain.translation.TranslationPreferences>().translationEnabled().get()
        private set

    init {
        val readerPrefs = Injekt.get<ReaderPreferences>()
        val translationPrefs = Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()

        readerPrefs.showTranslations().changes()
            .onEach {
                showTranslations = it
                refreshVisibleHolders()
            }
            .launchIn(scope)

        translationPrefs.translationEnabled().changes()
            .onEach {
                translationEnabled = it
                refreshVisibleHolders()
            }
            .launchIn(scope)
    }

    private fun refreshVisibleHolders() {
        val recycler = viewer.recycler
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i))
            if (holder is WebtoonPageHolder) {
                holder.refreshTranslation()
            }
        }
    }
}
