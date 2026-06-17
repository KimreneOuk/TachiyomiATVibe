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
     * TachiyomiAT: lightweight status-only refresh. Called when a page's
     * translation stage transitioned (RUNNING/FAILED/etc.) but the displayed
     * image did NOT change. Implementations should only sync the processing
     * overlay + translate/cancel button — never re-decode or rebind (unlike
     * [refreshTranslationPages], which may feed a new image). This keeps bare
     * RUNNING stage transitions from re-triggering image work on the visible
     * page (the source of the "blink during auto-translate" bug).
     */
    fun refreshTranslationStatus(pages: Set<ReaderPage>) {}
}
