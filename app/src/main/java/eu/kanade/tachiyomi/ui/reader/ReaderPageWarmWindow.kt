package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode

object ReaderPageWarmWindow {
    const val DEFAULT_RADIUS = 2

    fun radiusFor(mode: ReadingMode): Int =
        if (mode.type is ReadingMode.ViewerType.Webtoon) 4 else DEFAULT_RADIUS

    fun contains(
        pageIndex: Int,
        currentIndex: Int,
        lastIndex: Int,
        radius: Int = DEFAULT_RADIUS,
    ): Boolean {
        if (pageIndex < 0 || currentIndex < 0 || lastIndex < 0) return false
        val start = (currentIndex - radius).coerceAtLeast(0)
        val end = (currentIndex + radius).coerceAtMost(lastIndex)
        return pageIndex in start..end
    }

    fun indices(
        currentIndex: Int,
        lastIndex: Int,
        radius: Int = DEFAULT_RADIUS,
    ): IntRange {
        if (currentIndex < 0 || lastIndex < 0) return IntRange.EMPTY
        val start = (currentIndex - radius).coerceAtLeast(0)
        val end = (currentIndex + radius).coerceAtMost(lastIndex)
        return start..end
    }
}
