package eu.kanade.translation.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class RemotePageTranslationEngine(
    baseUrl: String,
    private val authToken: String?,
    private val toLang: TextTranslatorLanguage,
    private val inpaintingMode: InpaintingMode,
    maxConcurrency: Int,
    private val client: OkHttpClient = defaultClient(),
) : PageRecognitionEngine {

    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    init {
        require(normalizedBaseUrl.isNotBlank()) { "Desktop backend requires a base URL" }
        require(maxConcurrency > 0) { "Desktop maxConcurrency must be positive" }
    }

    override suspend fun analyze(bitmap: Bitmap): PageTranslation {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("target_lang", toLang.name)
            .addFormDataPart("mode", inpaintingMode.name)
            .addFormDataPart(
                "image",
                "page.${if (bitmap.hasAlpha()) "png" else "jpg"}",
                bitmap.toUploadBytes().toRequestBody(if (bitmap.hasAlpha()) PNG else JPEG),
            )
            .build()
        val request = requestBuilder("/v1/translate").post(body).build()
        val responseBody = execute(request)
        return parseTranslateResponse(responseBody).apply {
            recognitionEngine = "desktop"
        }
    }

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        val masks = pageTranslation.inpaintMaskBoxes.joinToString(prefix = "[", postfix = "]") { box ->
            "{\"x1\":${box.x1},\"y1\":${box.y1},\"x2\":${box.x2},\"y2\":${box.y2},\"label\":${box.label}}"
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("mode", inpaintingMode.name)
            .addFormDataPart("mask_boxes", masks)
            .addFormDataPart(
                "image",
                "page.${if (bitmap.hasAlpha()) "png" else "jpg"}",
                bitmap.toUploadBytes().toRequestBody(if (bitmap.hasAlpha()) PNG else JPEG),
            )
            .build()
        val bytes = executeBytes(requestBuilder("/v1/inpaint").post(body).build())
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    suspend fun translateBatch(bitmaps: List<Bitmap>): List<PageTranslation> =
        bitmaps.map { analyze(it) }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun requestBuilder(path: String): Request.Builder {
        val builder = Request.Builder().url("$normalizedBaseUrl$path")
        if (!authToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $authToken")
        }
        return builder
    }

    private suspend fun execute(request: Request): String =
        executeBytes(request).toString(Charsets.UTF_8)

    private suspend fun executeBytes(request: Request): ByteArray {
        try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    throw RemotePageTranslationException("Desktop server returned HTTP ${response.code}")
                }
                return response.body?.bytes()
                    ?: throw RemotePageTranslationException("Desktop server returned an empty response")
            }
        } catch (e: RemotePageTranslationException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw RemotePageTranslationException(RemotePageTranslationException.DESKTOP_UNREACHABLE, e)
        } catch (e: Exception) {
            throw RemotePageTranslationException(RemotePageTranslationException.DESKTOP_UNREACHABLE, e)
        }
    }

    private fun Bitmap.toUploadBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val format = if (hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (hasAlpha()) 100 else 90
        check(compress(format, quality, output)) { "Failed to encode bitmap for desktop backend" }
        return output.toByteArray()
    }

    companion object {
        private val PNG = "image/png".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()

        fun parseTranslateResponse(rawJson: String): PageTranslation {
            val json = Json.parseToJsonElement(rawJson).jsonObject
            val protocolVersion = json.intOrDefault("protocol_version", -1)
            if (protocolVersion != PageTranslation.CURRENT_INPAINT_REVISION) {
                throw RemotePageTranslationException("Update the companion server")
            }
            val blocksJson = json.arrayOrEmpty("blocks")
            val blocks = MutableList(blocksJson.size) { index ->
                val block = blocksJson[index].jsonObject
                TranslationBlock(
                    text = block.string("text"),
                    translation = block.stringOrDefault("translation", ""),
                    x = block.float("x"),
                    y = block.float("y"),
                    width = block.float("width"),
                    height = block.float("height"),
                    symHeight = block.floatOrDefault("sym_height", block.float("height")),
                    symWidth = block.floatOrDefault("sym_width", block.float("width")),
                    angle = block.floatOrDefault("angle", 0f),
                    label = block.intOrDefault("label", 1),
                    direction = block.stringOrDefault("direction", "LTR"),
                )
            }
            val masksJson = json.arrayOrEmpty("inpaint_mask_boxes")
            val masks = List(masksJson.size) { index ->
                val mask = masksJson[index].jsonObject
                InpaintMaskBox(
                    x1 = mask.int("x1"),
                    y1 = mask.int("y1"),
                    x2 = mask.int("x2"),
                    y2 = mask.int("y2"),
                    label = mask.int("label"),
                )
            }
            val imgWidth = json.float("img_width")
            val imgHeight = json.float("img_height")
            return PageTranslation(
                blocks = blocks,
                imgWidth = imgWidth,
                imgHeight = imgHeight,
                originalImgWidth = imgWidth,
                originalImgHeight = imgHeight,
                detectionCount = blocks.size,
                ocrBlockCount = blocks.size,
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
                inpaintMaskBoxes = masks,
            )
        }

        private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
            get(name)?.jsonArray ?: JsonArray(emptyList())

        private fun JsonObject.string(name: String): String =
            getValue(name).jsonPrimitive.content

        private fun JsonObject.stringOrDefault(name: String, default: String): String =
            get(name)?.jsonPrimitive?.content ?: default

        private fun JsonObject.float(name: String): Float =
            getValue(name).jsonPrimitive.float

        private fun JsonObject.floatOrDefault(name: String, default: Float): Float =
            get(name)?.jsonPrimitive?.floatOrNull ?: default

        private fun JsonObject.int(name: String): Int =
            getValue(name).jsonPrimitive.int

        private fun JsonObject.intOrDefault(name: String, default: Int): Int =
            get(name)?.jsonPrimitive?.intOrNull ?: default
    }
}
