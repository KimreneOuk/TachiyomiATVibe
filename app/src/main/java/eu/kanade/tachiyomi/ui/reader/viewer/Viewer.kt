package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean

    fun refreshTranslationPages(pages: Set<ReaderPage>) {}

    /**
     * TachiyomiAT: the reader page currently underfoot (null on chapter
     * transitions / before layout). Used to gate per-holder feedback (status
     * pill, ghost button) to the ACTIVE page only, so prefetched and recycled
     * pages can never render status for the page the user is not reading.
     */
    val currentReaderPage: ReaderPage? get() = null

    /**
     * TachiyomiAT: consumer of the current page's zoom state, pushed by
     * viewers that support pinch zoom (true while the page is zoomed past
     * its base scale). The reader overlay uses it to show a reset
     * affordance.
     */
    var onZoomStateChanged: ((Boolean) -> Unit)?
        get() = null
        set(value) {}

    fun resetZoom() {}
}
