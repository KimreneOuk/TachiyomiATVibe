package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AiTranslationRetryControllerTest {

    @Test
    fun `strict structural failure stops live retry before missing split`() {
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

        assertThrows(ContextualStructuralFailureException::class.java) {
            runTest {
                translateAiChunkWithAdaptiveRetry(
                    translator = translator,
                    chunk = chunk,
                    requestedOutputTokens = 256,
                    profile = TranslationContextChunkPlanner.Profile.DEFAULT,
                    allowFailureSplit = true,
                    label = "test",
                    retryDepth = 0,
                )
            }
        }
        translator.calls shouldBe 1
        page.blocks.forEach { it.translation shouldBe "" }
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
                validationErrors = listOf("Missing batch response footer"),
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
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
