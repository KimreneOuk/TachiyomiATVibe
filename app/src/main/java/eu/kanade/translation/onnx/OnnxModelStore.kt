package eu.kanade.translation.onnx

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

data class ModelPaths(
    val detectorModel: File,
    val ocrEncoder: File,
    val ocrDecoderInit: File,
    val ocrDecoderStep: File,
    val ocrVocab: File,
    val inpaintModel: File? = null,
)

class OnnxModelStore(private val context: Context) {

    private val modelsDir = File(context.noBackupFilesDir, "tachiyomiat-models")

    private val allModelRelativePaths = listOf(
        "detection/detector-v4-s_int8.onnx",
        "ocr/encoder.onnx",
        "ocr/decoder_init.onnx",
        "ocr/decoder_step.onnx",
        "ocr/vocab.txt",
    )

    private val optionalModelRelativePaths = listOf(
        "inpainting/aot.onnx",
    )

    suspend fun ensureModels(): ModelPaths = withContext(Dispatchers.IO) {
        val startTime = System.nanoTime()
        logcat(LogPriority.INFO) { "Copying models from assets to ${modelsDir.absolutePath}" }
        val subDirs = listOf("detection", "ocr", "inpainting")
        for (subDir in subDirs) {
            val assetDir = "models/$subDir"
            val files = context.assets.list(assetDir) ?: continue
            val targetDir = File(modelsDir, subDir)
            if (!targetDir.exists()) targetDir.mkdirs()
            for (fileName in files) {
                val targetFile = File(targetDir, fileName)
                if (targetFile.exists()) {
                    val assetSize = context.assets.open("$assetDir/$fileName").use { it.available() }
                    if (targetFile.length() == assetSize.toLong()) continue
                }
                context.assets.open("$assetDir/$fileName").use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) { "Models copied successfully in ${elapsedMs}ms" }
        ModelPaths(
            detectorModel = File(modelsDir, "detection/detector-v4-s_int8.onnx"),
            ocrEncoder = File(modelsDir, "ocr/encoder.onnx"),
            ocrDecoderInit = File(modelsDir, "ocr/decoder_init.onnx"),
            ocrDecoderStep = File(modelsDir, "ocr/decoder_step.onnx"),
            ocrVocab = File(modelsDir, "ocr/vocab.txt"),
            inpaintModel = File(modelsDir, "inpainting/aot.onnx").takeIf { it.exists() },
        )
    }

    fun modelsAvailable(): Boolean {
        return allModelRelativePaths.all { relativePath ->
            File(modelsDir, relativePath).exists()
        }
    }

    fun assetsAvailable(): Boolean {
        return try {
            for (relativePath in allModelRelativePaths) {
                context.assets.open("models/$relativePath").close()
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Required ONNX model asset is missing" }
            false
        }
    }

    fun totalSizeBytes(): Long {
        return allModelRelativePaths.sumOf { relativePath ->
            val file = File(modelsDir, relativePath)
            if (file.exists()) file.length() else 0L
        }
    }
}
