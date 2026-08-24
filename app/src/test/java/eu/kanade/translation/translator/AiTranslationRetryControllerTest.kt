package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AiTranslationRetryControllerTest {

    @Test
    fun `structural failure keeps the page envelope intact without throwing`() = runTest {
        val translator = StructuralFailureTranslator()
        val page = PageTranslation(
            blocks = mutableListOf(block("first"), block("second")),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 2,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageIndexes = mapOf("page.jpg" to 0),
        )

        val deltas = translateAiChunkWithAdaptiveRetry(
            translator = translator,
            chunk = chunk,
            requestedOutputTokens = 256,
            profile = TranslationContextChunkPlanner.Profile.DEFAULT,
            label = "test",
            retryDepth = 0,
        )

        translator.calls shouldBe 1
        deltas.isEmpty() shouldBe true
        page.blocks.forEach { it.translation shouldBe "" }
    }

    @Test
    fun `terminal single-block failure returns quietly for per-page validation`() = runTest {
        val translator = StructuralFailureTranslator()
        val page = PageTranslation(
            blocks = mutableListOf(block("only")),
        )
        val chunk = TranslationContextChunk(
            pages = linkedMapOf("page.jpg" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageIndexes = mapOf("page.jpg" to 0),
        )

        val deltas = translateAiChunkWithAdaptiveRetry(
            translator = translator,
            chunk = chunk,
            requestedOutputTokens = 256,
            profile = TranslationContextChunkPlanner.Profile.DEFAULT,
            label = "test",
            retryDepth = 0,
        )

        translator.calls shouldBe 1
        deltas.isEmpty() shouldBe true
        page.blocks.single().translation shouldBe ""
    }

    private class StructuralFailureTranslator : AITranslator() {
        var calls = 0

        override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch {
            calls++
            val idMap = linkedMapOf(
                "p0000_b0000" to TargetLocation("page.jpg", 0),
                "p0000_b0001" to TargetLocation("page.jpg", 1),
            )
            return ContextualTranslationBatch(
                idToBlockIndex = idMap,
                results = emptyList(),
                strictValidation = true,
                protocolVersion = BatchTranslationProtocol.VERSION,
                validationErrors = listOf("Missing translation for 'p0000_b0000'"),
            )
        }

        override suspend fun promptText(prompt: String): String = ""
    }

    private fun block(text: String): TranslationBlock = TranslationBlock(
        text = text,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )
}
