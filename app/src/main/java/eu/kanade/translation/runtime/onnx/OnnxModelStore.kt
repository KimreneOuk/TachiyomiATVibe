package eu.kanade.translation.runtime.onnx

import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class ModelPaths(
    val detectorModel: File,
    val ocrEncoder: File,
    val ocrDecoderInit: File,
    val ocrDecoderStep: File,
    val ocrVocab: File,
    val inpaintModel: File?,
)

data class PaddleOcrV6SmallPaths(
    val recognitionModel: File,
    val dictionary: File,
)

class OnnxModelStore(private val context: Context) {

    private val modelsDir: File by lazy {
        File(context.noBackupFilesDir, "tachiyomiat-models").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                logcat(LogPriority.INFO) { "Created models directory: ${dir.absolutePath}" }
            }
        }
    }

    fun modelsAvailable(): Boolean {
        val dir = modelsDir
        return listOf(
            "detector.onnx",
            "encoder.onnx",
            "decoder_init.onnx",
            "decoder_step.onnx",
            "vocab.txt",
        ).all { File(dir, it).exists() }
    }

    fun assetsAvailable(): Boolean {
        return try {
            context.assets.list("models/ocr")?.isNotEmpty() == true ||
                context.assets.list("models/detection")?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ensureModels(): ModelPaths {
        val dir = modelsDir
        val detectorFile = copyIfNeeded(dir, "detector.onnx", "models/detection/detector-v4-s_int8.onnx")
        val encoderFile = copyIfNeeded(dir, "encoder.onnx", "models/ocr/encoder.onnx")
        val decoderInitFile = copyIfNeeded(dir, "decoder_init.onnx", "models/ocr/decoder_init.onnx")
        val decoderStepFile = copyIfNeeded(dir, "decoder_step.onnx", "models/ocr/decoder_step.onnx")
        val vocabFile = copyIfNeeded(dir, "vocab.txt", "models/ocr/vocab.txt")

        val inpaintFile = try {
            copyIfNeeded(dir, "aot.onnx", "models/inpainting/aot.onnx")
        } catch (_: Exception) {
            logcat(LogPriority.WARN) { "Inpainting model not found in assets, skipping" }
            null
        }

        return ModelPaths(
            detectorModel = detectorFile,
            ocrEncoder = encoderFile,
            ocrDecoderInit = decoderInitFile,
            ocrDecoderStep = decoderStepFile,
            ocrVocab = vocabFile,
            inpaintModel = inpaintFile,
        )
    }

    fun paddleOcrV6SmallAvailable(): Boolean {
        val dir = File(modelsDir, "paddle-v6-small")
        return listOf(
            "PP-OCRv6_small_rec.onnx",
            "PP-OCRv6_small_rec.txt",
        ).all { File(dir, it).exists() }
    }

    fun paddleOcrV6SmallAssetsAvailable(): Boolean {
        return try {
            context.assets.list("models/ocr/paddle-v6-small")?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    fun ensurePaddleOcrV6Small(): PaddleOcrV6SmallPaths {
        val dir = File(modelsDir, "paddle-v6-small").also { if (!it.exists()) it.mkdirs() }
        return PaddleOcrV6SmallPaths(
            recognitionModel = copyIfNeeded(
                dir,
                "PP-OCRv6_small_rec.onnx",
                "models/ocr/paddle-v6-small/PP-OCRv6_small_rec.onnx",
            ),
            dictionary = copyIfNeeded(
                dir,
                "PP-OCRv6_small_rec.txt",
                "models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt",
            ),
        )
    }

    private fun copyIfNeeded(dir: File, name: String, assetPath: String): File {
        val dest = File(dir, name)
        if (dest.exists() && dest.length() > 0) {
            if (name.endsWith(".onnx")) {
                val buffer = ByteArray(1)
                dest.inputStream().use { it.read(buffer) }
                if (buffer[0] == 0x08.toByte()) return dest
            } else {
                return dest
            }
        }

        logcat(LogPriority.INFO) { "Copying model from assets: $assetPath -> ${dest.absolutePath}" }
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            inputStream = context.assets.open(assetPath)
            outputStream = FileOutputStream(dest)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            logcat(LogPriority.INFO) { "Copied $assetPath (${dest.length()} bytes)" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to copy $assetPath" }
            throw e
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
        return dest
    }
}
