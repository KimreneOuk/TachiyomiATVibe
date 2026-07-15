package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class DeepSeekTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temperature: Float,
) : OpenAiCompatibleTranslator() {

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        val chunk = TranslationContextChunk(
            pages = linkedPages,
            blockCount = blockCount,
            rollingContext = "",
            glossary = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = maxOutputToken
        )
        translateContextual(chunk, isPass2 = false)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk, isPass2: Boolean) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("DeepSeek API key is required")
        }

        val blocksToTranslate = mutableListOf<TranslationBlock>()
        for (page in chunk.pages.values) {
            for (block in page.blocks) {
                val isNonBlank = block.text.isNotBlank()
                val shouldTranslate = if (isPass2) {
                    isNonBlank && block.needsRevision && block.userEditedAt == null
                } else {
                    isNonBlank
                }
                if (shouldTranslate) {
                    blocksToTranslate.add(block)
                }
            }
        }

        if (blocksToTranslate.isEmpty()) {
            return
        }

        val idToBlock = blocksToTranslate.mapIndexed { index, block -> "b$index" to block }.toMap()

        try {
            val systemPrompt = if (isPass2) {
                TranslationPrompts.pass2SystemPrompt(fromLang, toLang)
            } else {
                TranslationPrompts.pass1SystemPrompt(fromLang, toLang)
            }

            val contextPrefix = TranslationPrompts.contextPrefix(chunk.rollingContext, chunk.glossary)
            val promptLines = blocksToTranslate.mapIndexed { index, block ->
                val id = "b$index"
                if (isPass2) {
                    "$id|Source: ${block.text} | Draft: ${block.translation}"
                } else {
                    TranslationPrompts.idMappedSourceLine(id, block)
                }
            }
            val promptBody = promptLines.joinToString("\n")
            val finalPrompt = if (contextPrefix.isEmpty()) promptBody else contextPrefix + promptBody

            val jsonObject = buildJsonObject {
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
                put("temperature", temperature)
                put("max_tokens", chunk.maxOutputTokens)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", finalPrompt)
                    }
                }
            }.toString()

            val rawOutput = postChatCompletion(
                url = "https://api.deepseek.com/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                payloadJson = jsonObject,
            )

            val lines = rawOutput.split("\n")
            for (line in lines) {
                val parsed = TranslationPrompts.parseLine(line) ?: continue
                val block = idToBlock[parsed.id] ?: continue
                if (block.userEditedAt != null) {
                    continue
                }
                block.translation = OcrArtifactSanitizer.sanitize(parsed.text)
                block.needsRevision = if (isPass2) {
                    false
                } else {
                    parsed.needsRevision ?: false
                }
            }
            TranslationBlockFilters.removeWatermarkBlocks(chunk.pages)
        } catch (e: Exception) {
            logcat { "DeepSeek Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override suspend fun promptText(prompt: String): String {
        return try {
            val jsonObject = buildJsonObject {
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
                put("temperature", temperature)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
            }.toString()

            postChatCompletion(
                url = "https://api.deepseek.com/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                payloadJson = jsonObject,
            )
        } catch (e: Exception) {
            logcat { "DeepSeek promptText Error : ${e.stackTraceToString()}" }
            ""
        }
    }
}
