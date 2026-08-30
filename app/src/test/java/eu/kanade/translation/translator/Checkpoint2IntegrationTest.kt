package eu.kanade.translation.translator
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.contextual.StreamingChunkPlanner

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class Checkpoint2IntegrationTest {

    // ---- Streaming planner tail flush stays within the context budget ----

    @Test
    fun `tail flush of the streaming planner stays within the context budget`() = runTest {
        val planner = StreamingChunkPlanner(8192, TranslationContextChunkPlanner.Profile.DEFAULT)
        val big = "\u65e5".repeat(3000)
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = big, translation = "",
                    width = 10f, height = 10f, x = 0f, y = 0f,
                    symHeight = 1f, symWidth = 1f, angle = 0f,
                ),
            ),
        )
        planner.accept("p0", page)
        val flushed = planner.flushRemaining().finalChunk!!
        (
            flushed.estimatedPromptTokens + flushed.maxOutputTokens +
                TranslationContextChunkPlanner.SAFETY_MARGIN <=
                TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
            ) shouldBe true
    }
}
