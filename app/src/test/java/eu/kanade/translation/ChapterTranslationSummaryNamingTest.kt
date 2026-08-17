package eu.kanade.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Locks the summary sidecar naming contract that
 * [TranslationManager.deleteTranslation] relies on to purge the sidecar on chapter
 * delete. A rename of [ChapterTranslationSummaryStore.summaryFileName] would otherwise
 * silently strand the `.summary.json` file on disk. The same helper backs
 * [ChapterTranslationSummaryStore.findSummaryFile], so this guarantees the two lookups
 * stay in lockstep.
 */
class ChapterTranslationSummaryNamingTest {

    @Test
    fun `appends summary suffix after stripping the final extension`() {
        ChapterTranslationSummaryStore.summaryFileName("chapter.json") shouldBe "chapter.summary.json"
    }

    @Test
    fun `appends summary suffix when there is no extension`() {
        // substringBeforeLast('.', default) returns the default (the whole string) when the
        // delimiter is absent, so the basename is preserved.
        ChapterTranslationSummaryStore.summaryFileName("chapter") shouldBe "chapter.summary.json"
    }

    @Test
    fun `strips only the last extension`() {
        ChapterTranslationSummaryStore.summaryFileName("a.b.c.json") shouldBe "a.b.c.summary.json"
    }

    @Test
    fun `dotfile with no other extension strips to empty basename`() {
        // ".hidden" -> substringBeforeLast('.', ".hidden"): the only '.' is at index 0, so the
        // substring before it is "" (empty). Result is "" + ".summary.json" = ".summary.json".
        // This mirrors publish()/findSummaryFile() behavior for any leading-dot filename.
        ChapterTranslationSummaryStore.summaryFileName(".hidden") shouldBe ".summary.json"
    }
}
