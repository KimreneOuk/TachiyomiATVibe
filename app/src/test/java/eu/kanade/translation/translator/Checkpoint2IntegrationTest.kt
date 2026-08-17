package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class Checkpoint2IntegrationTest {

    // ---- TranslatorComputeClass lane routing ----

    @Test
    fun `compute class routing classifies ML Kit as serialized and remote as overlapping`() {
        (TranslatorComputeClass.LOCAL_COMPUTE.mayOverlapNative) shouldBe false
        (TranslatorComputeClass.REMOTE_IO.mayOverlapNative) shouldBe true
    }

    @Test
    fun `compute class routes the configured standard engines correctly`() {
        val mlKit = TranslatorComputeClass.forConfiguration(
            tachiyomi.domain.translation.TranslationEngineCategory.STANDARD,
            StandardTranslatorKind.MLKIT.name,
            null,
        )
        val remoteStd = TranslatorComputeClass.forConfiguration(
            tachiyomi.domain.translation.TranslationEngineCategory.STANDARD,
            StandardTranslatorKind.DEEPL.name,
            null,
        )
        val ai = TranslatorComputeClass.forConfiguration(
            tachiyomi.domain.translation.TranslationEngineCategory.AI_MODEL,
            null,
            "gemini",
        )
        mlKit shouldBe TranslatorComputeClass.LOCAL_COMPUTE
        (mlKit.mayOverlapNative) shouldBe false
        remoteStd shouldBe TranslatorComputeClass.REMOTE_IO
        ai shouldBe TranslatorComputeClass.REMOTE_IO
    }

    // ---- InactivityFlusher preserves chunk budget when wired to the planner ----

    @Test
    fun `inactivity flush of the streaming planner stays within the context budget`() = runTest {
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
