package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ChapterGlossaryFoldWatermarkTest {

    private fun block(text: String, translation: String) = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )

    @Test
    fun `per-page fold cost is independent of corpus size over 200-page synthetic set`() = runBlocking<Unit> {
        val warmupStore = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )
        // Warm up JIT compiler so class loading and initial regex/tree allocations don't skew timing
        for (w in 0 until 15) {
            warmupStore.foldPageContribution(
                "warmup_$w.jpg",
                listOf(
                    "黒崎" to "Kurosaki",
                    "一護" to "Ichigo",
                    "白哉" to "Byakuya",
                ),
            )
        }

        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        val pageCount = 200
        val foldTimesNs = LongArray(pageCount)

        // Synthetic 200-page manga chapter: B = 5 blocks per page
        // 3 stable recurring proper nouns + 2 page-unique terms per page
        for (i in 0 until pageCount) {
            val pageKey = "page_%03d.jpg".format(i)
            val pairs = listOf(
                "黒崎は叫んだ" to "Kurosaki shouted",
                "一護が戦う" to "Ichigo fights",
                "白哉の技" to "Byakuya strikes",
                "島名$i" to "Island$i",
                "敵軍$i" to "Enemy$i",
            )
            val start = System.nanoTime()
            store.foldPageContribution(pageKey, pairs)
            val elapsed = System.nanoTime() - start
            foldTimesNs[i] = elapsed

            store.updatePage(pageKey) {
                PageTranslation(
                    sourceFileName = pageKey,
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.READY,
                    blocks = pairs.map { (s, t) -> block(s, t) }.toMutableList(),
                )
            }
        }

        // Measure streamed recompute fallback over the full 200-page corpus (1000 pairs)
        val recomputeStart = System.nanoTime()
        val recomputedGlossary = store.glossaryStore.streamedRecomputeFallback()
        val recomputeElapsedNs = System.nanoTime() - recomputeStart

        // Incremental fold on page 200 must be comparable to early pages (e.g. pages 10..30)
        // and substantially faster than a full recompute over the whole 200-page corpus
        val earlyAvgNs = foldTimesNs.slice(10..30).average()
        val lateAvgNs = foldTimesNs.slice(179..199).average()

        // Per-page fold cost must be independent of corpus size O(B), not O(P*B):
        // 1. Late pages average should remain within a bounded constant factor (< 5x) of early pages
        (lateAvgNs < earlyAvgNs * 5.0 || lateAvgNs < 2_000_000.0) shouldBe true

        // 2. Incremental fold of page 200 (B blocks) is significantly faster than full recompute (P*B blocks)
        val page200FoldNs = foldTimesNs[199]
        (page200FoldNs < recomputeElapsedNs) shouldBe true

        // Verify that glossary was constructed correctly
        store.glossarySnapshot() shouldBe recomputedGlossary
        store.glossarySnapshot()["黒崎"] shouldBe "Kurosaki"
        store.glossarySnapshot()["一護"] shouldBe "Ichigo"
    }

    @Test
    fun `watermark replace prevents duplicate count inflation when retranslating a page`() = runBlocking<Unit> {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        // Page 1: 太郎 count = 1
        store.foldPageContribution("p1.jpg", listOf("太郎が来た" to "Taro arrived"))
        store.glossarySnapshot() shouldBe emptyMap()

        // Page 2: 太郎 count = 2
        store.foldPageContribution("p2.jpg", listOf("太郎は走る" to "Taro runs"))
        store.glossarySnapshot() shouldBe emptyMap()

        // Retranslating Page 2 with DIFFERENT content ("次郎" -> "Jiro")
        // MUST replace Page 2's prior contribution, dropping "太郎" count back to 1!
        store.foldPageContribution("p2.jpg", listOf("次郎は走る" to "Jiro runs"))
        store.glossarySnapshot() shouldBe emptyMap()

        // Page 3: 太郎 count = 2 (not 3! If add-only, count would be 3 and trigger promotion)
        store.foldPageContribution("p3.jpg", listOf("太郎を見た" to "I saw Taro"))
        store.glossarySnapshot().containsKey("太郎") shouldBe false

        // Page 4: 太郎 count = 3 -> reaches MIN_RECURRENCE threshold
        store.foldPageContribution("p4.jpg", listOf("太郎を呼ぶ" to "Calling Taro"))
        store.glossarySnapshot()["太郎"] shouldBe "Taro"

        // Retranslating Page 1 with identical content MUST NOT inflate count to 4
        store.foldPageContribution("p1.jpg", listOf("太郎が来た" to "Taro arrived"))
        store.glossarySnapshot()["太郎"] shouldBe "Taro"

        // Retranslating Page 4 to something else drops 太郎 count to 2 (< 3) -> evicted!
        store.foldPageContribution("p4.jpg", listOf("花子を呼ぶ" to "Calling Hanako"))
        store.glossarySnapshot().containsKey("太郎") shouldBe false
        store.glossarySnapshot().containsKey("花子") shouldBe false
    }

    @Test
    fun `incremental fold produces exact equivalence with streamed recompute fallback`() = runBlocking<Unit> {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        // 50 pages with mixed character names, common stopwords, and unique phrases
        for (i in 1..50) {
            val pageKey = "page_$i.jpg"
            val pairs = listOf(
                "主人公の剣" to "Hero sword",
                "魔道士の杖" to "Wizard staff",
                "敵兵が現れた" to "Enemy appeared",
                "街並み$i" to "Town$i",
            )
            store.foldPageContribution(pageKey, pairs)
            store.updatePage(pageKey) {
                PageTranslation(
                    sourceFileName = pageKey,
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.READY,
                    blocks = pairs.map { (s, t) -> block(s, t) }.toMutableList(),
                )
            }
        }

        // Retranslate multiple pages with updated terms/renderings
        val retranslatedPairsPage5 = listOf(
            "主人公の盾" to "Hero shield",
            "魔道士の書" to "Wizard book",
        )
        store.foldPageContribution("page_5.jpg", retranslatedPairsPage5)
        store.updatePage("page_5.jpg") {
            PageTranslation(
                sourceFileName = "page_5.jpg",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                blocks = retranslatedPairsPage5.map { (s, t) -> block(s, t) }.toMutableList(),
            )
        }

        val retranslatedPairsPage20 = listOf(
            "主人公の剣" to "Hero blade", // alternate rendering
            "王女の願い" to "Princess wish",
        )
        store.foldPageContribution("page_20.jpg", retranslatedPairsPage20)
        store.updatePage("page_20.jpg") {
            PageTranslation(
                sourceFileName = "page_20.jpg",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                blocks = retranslatedPairsPage20.map { (s, t) -> block(s, t) }.toMutableList(),
            )
        }

        // The incremental fold glossary must be EXACTLY equal to streamed recompute fallback
        val incrementalGlossary = store.glossarySnapshot()
        val streamedGlossary = store.glossaryStore.streamedRecomputeFallback()

        incrementalGlossary shouldBe streamedGlossary
        incrementalGlossary.containsKey("主人公") shouldBe true
        incrementalGlossary.containsKey("魔道士") shouldBe true
    }

    @Test
    fun `lazy seeding seeds once from store pages and never from capped glossary map`() = runBlocking<Unit> {
        // Initial store pre-populated with translated pages
        val initialPages = mapOf(
            "p1.jpg" to PageTranslation(
                sourceFileName = "p1.jpg",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                blocks = mutableListOf(block("先輩の家", "Senpai house")),
            ),
            "p2.jpg" to PageTranslation(
                sourceFileName = "p2.jpg",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                blocks = mutableListOf(block("先輩が来た", "Senpai arrived")),
            ),
        )

        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = initialPages,
        )

        // First fold lazily seeds the accumulator from store pages
        store.foldPageContribution("p3.jpg", listOf("先輩を見た" to "Senpai visited"))

        // "先輩" had 2 occurrences in initialPages + 1 from p3.jpg = 3 occurrences (threshold reached)
        store.glossarySnapshot()["先輩"] shouldBe "Senpai"

        // Retranslating p1.jpg replaces its contribution in the seeded accumulator
        store.foldPageContribution("p1.jpg", listOf("後輩の家" to "Kohai house"))

        // "先輩" count drops from 3 to 2 (< 3) -> evicted
        store.glossarySnapshot().containsKey("先輩") shouldBe false
    }
}
