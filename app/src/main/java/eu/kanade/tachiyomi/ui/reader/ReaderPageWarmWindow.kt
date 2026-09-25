package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode

object ReaderPageWarmWindow {
    const val DEFAULT_RADIUS = 2

    const val DEFAULT_ATTACH_RADIUS = 2
    const val DEFAULT_EVICTION_RADIUS = 5

    const val WEBTOON_ATTACH_RADIUS = 4
    const val WEBTOON_EVICTION_RADIUS = 10

    fun radiusFor(mode: ReadingMode): Int =
        if (mode.type is ReadingMode.ViewerType.Webtoon) WEBTOON_ATTACH_RADIUS else DEFAULT_ATTACH_RADIUS

    fun attachRadiusFor(mode: ReadingMode): Int =
        if (mode.type is ReadingMode.ViewerType.Webtoon) WEBTOON_ATTACH_RADIUS else DEFAULT_ATTACH_RADIUS

    fun evictionRadiusFor(mode: ReadingMode): Int =
        if (mode.type is ReadingMode.ViewerType.Webtoon) WEBTOON_EVICTION_RADIUS else DEFAULT_EVICTION_RADIUS

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
