package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class BatchContextFrontierTest {

    @Test
    fun `resume seeds only the contiguous natural predecessor prefix`() {
        val frontier = BatchContextFrontier(mapOf("p0" to 0, "p1" to 1, "p2" to 2))
        frontier.seed(
            mapOf(
                "p0" to translatedPage("zero", "ZERO"),
                "p2" to translatedPage("future", "FUTURE"),
            ),
        )

        frontier.frontierIndex shouldBe 0
        frontier.rollingContext shouldContain "zero => ZERO"
        frontier.rollingContext.contains("future => FUTURE") shouldBe false
        frontier.blocksLaterAi("p2") shouldBe false
    }

    @Test
    fun `non-textless terminal gap blocks later AI while preserving later pages`() {
        val frontier = BatchContextFrontier(mapOf("p0" to 0, "p1" to 1, "p2" to 2))
        frontier.seed(mapOf("p0" to translatedPage("zero", "ZERO")))
        frontier.record("p1", failedPage("missing"), terminalFailure = true)
        frontier.record("p2", translatedPage("future", "FUTURE"))

        frontier.gapIndex shouldBe 1
        frontier.blocksLaterAi("p2") shouldBe true
        frontier.rollingContext shouldContain "zero => ZERO"
        frontier.rollingContext.contains("future => FUTURE") shouldBe false
    }

    @Test
    fun `later reused pages fold only after traversal reaches their missing predecessors`() {
        val frontier = BatchContextFrontier(mapOf("p0" to 0, "p1" to 1, "p2" to 2))
        frontier.seed(mapOf("p2" to translatedPage("future", "FUTURE")))

        frontier.frontierIndex shouldBe -1
        frontier.rollingContext shouldBe ""

        frontier.record("p2", translatedPage("future", "FUTURE"))
        frontier.record("p0", translatedPage("zero", "ZERO"))
        frontier.frontierIndex shouldBe 0
        frontier.rollingContext.contains("future => FUTURE") shouldBe false

        frontier.record("p1", translatedPage("one", "ONE"))
        frontier.frontierIndex shouldBe 2
        frontier.rollingContext shouldContain "future => FUTURE"
    }

    @Test
    fun `textless terminal page advances the context frontier without blocking`() {
        val frontier = BatchContextFrontier(mapOf("p0" to 0, "p1" to 1, "p2" to 2))
        frontier.seed(
            mapOf(
                "p0" to translatedPage("zero", "ZERO"),
                "p1" to PageTranslation(
                    ocrStatus = StageStatus.TEXTLESS,
                    translationStatus = StageStatus.SKIPPED,
                    inpaintStatus = StageStatus.SKIPPED,
                    renderStatus = StageStatus.SKIPPED,
                ),
                "p2" to translatedPage("two", "TWO"),
            ),
        )

        frontier.frontierIndex shouldBe 2
        frontier.gapIndex shouldBe null
        frontier.blocksLaterAi("p2") shouldBe false
        frontier.rollingContext shouldContain "two => TWO"
    }

    @Test
    fun `partial page never advances the context frontier`() {
        val frontier = BatchContextFrontier(mapOf("p0" to 0, "p1" to 1))
        frontier.record(
            "p0",
            translatedPage("zero", "ZERO").copy(translationStatus = StageStatus.PARTIAL),
        )

        frontier.frontierIndex shouldBe -1
        frontier.rollingContext shouldBe ""
    }

    private fun translatedPage(source: String, translation: String) = PageTranslation(
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        blocks = mutableListOf(
            TranslationBlock(
                text = source,
                translation = translation,
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
    )

    private fun failedPage(source: String) = PageTranslation(
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.FAILED,
        blocks = mutableListOf(
            TranslationBlock(
                text = source,
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
    )
}
