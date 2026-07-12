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
    /**
     * TachiyomiAT: optional YOLO26-nano manga panel detector model
     * (`manga_panel_detector_int8.onnx`). Nullable because the panel detector
     * is best-effort context — when absent or corrupt, panel assignment is
     * skipped and translation proceeds panel-less. Copied from
     * `models/detection/manga_panel_detector_int8.onnx` alongside the text
     * detector; a failed copy leaves this null rather than throwing.
     */
    val panelDetectorModel: File?,
    /**
     * TachiyomiAT: YOLO11-seg manga bubble segmenter model (`manga_bubble_segmenter_int8.onnx`).
     */
    val bubbleSegmenterModel: File?,
)

data class PaddleOcrV6SmallPaths(
    val recognitionModel: File,
    val dictionary: File,
)

/**
 * TachiyomiAT: resolved paths for the PP-OCRv6 small **detection** (det) model.
 * The det model runs inside each ROI crop from Stage-1 detection to find
 * individual text lines (polygons), replacing the ink-gap column heuristic for
 * the PaddleOCR rec path. See
 * `docs/superpowers/specs/2026-06-23-paddleocr-v6-det-onnx-integration-design.md`.
 */
data class PaddleOcrV6DetPaths(
    val detectionModel: File,
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

        // Panel detector is best-effort context (mirrors inpaint copy): a missing
        // asset or failed copy leaves it null so panel assignment is skipped, not crashed.
        val panelDetectorFile = try {
            copyIfNeeded(dir, "panel_detector.onnx", "models/detection/manga_panel_detector_int8.onnx")
        } catch (_: Exception) {
            logcat(LogPriority.WARN) { "Panel detector model not found in assets, skipping" }
            null
        }
        
        val bubbleSegmenterFile = try {
            copyIfNeeded(dir, "bubble_segmenter.onnx", "models/segmentation/manga109_bubble_int8.onnx")
        } catch (_: Exception) {
            logcat(LogPriority.WARN) { "Bubble segmenter model not found in assets, skipping" }
            null
        }

        return ModelPaths(
            detectorModel = detectorFile,
            ocrEncoder = encoderFile,
            ocrDecoderInit = decoderInitFile,
            ocrDecoderStep = decoderStepFile,
            ocrVocab = vocabFile,
            inpaintModel = inpaintFile,
            panelDetectorModel = panelDetectorFile,
            bubbleSegmenterModel = bubbleSegmenterFile,
        )
    }

    fun paddleOcrV6SmallAvailable(): Boolean {
        val dir = File(modelsDir, "paddle-v6-small")
        return listOf(
            "inference.onnx",
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
                "inference.onnx",
                "models/ocr/paddle-v6-small/inference.onnx",
            ),
            dictionary = copyIfNeeded(
                dir,
                "PP-OCRv6_small_rec.txt",
                "models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt",
            ),
        )
    }

    /**
     * TachiyomiAT: PP-OCRv6 small **detection** model availability.
     *
     * The det model is an optional refinement of the PaddleOCR rec path: when
     * present it replaces the ink-gap vertical-column heuristic with a learned
     * text-line detector. The pipeline must therefore tolerate its absence
     * (graceful fallback) — [paddleOcrV6DetAvailable] / [assetsAvailable] gate
     * whether the det engine is built.
     */
    fun paddleOcrV6DetAvailable(): Boolean {
        val dir = File(modelsDir, "paddle-v6-small/det")
        return File(dir, "inference.onnx").exists()
    }

    fun paddleOcrV6DetAssetsAvailable(): Boolean {
        return try {
            context.assets.list("models/ocr/paddle-v6-small/det")?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    fun ensurePaddleOcrV6Det(): PaddleOcrV6DetPaths {
        val dir = File(modelsDir, "paddle-v6-small/det").also { if (!it.exists()) it.mkdirs() }
        return PaddleOcrV6DetPaths(
            detectionModel = copyIfNeeded(
                dir,
                "inference.onnx",
                "models/ocr/paddle-v6-small/det/inference.onnx",
            ),
        )
    }

    private fun copyIfNeeded(dir: File, name: String, assetPath: String): File {
        val dest = File(dir, name)
        val stampFile = File(dir, "$name.version")
        val expectedStamp = "$MODEL_ASSET_VERSION:$assetPath"
        if (dest.exists() && dest.length() > 0) {
            val stampMatches = stampFile.isFile && runCatching {
                stampFile.readText(Charsets.UTF_8) == expectedStamp
            }.getOrDefault(false)
            if (stampMatches && name.endsWith(".onnx")) {
                if (looksLikeValidOnnx(dest)) return dest
                // Cached copy is structurally invalid (truncated/corrupt). A bad
                // .onnx previously passed the old 0x08 check and produced garbage
                // all-gray inpaint or an opaque OrtException; delete to re-copy.
                logcat(LogPriority.WARN) {
                    "Cached $name failed ONNX integrity check (size=${dest.length()}); re-copying from assets"
                }
                if (!dest.delete()) {
                    logcat(LogPriority.WARN) { "Could not delete corrupt cached $name; attempting overwrite" }
                }
            } else if (stampMatches) {
                return dest
            }
            logcat(LogPriority.INFO) {
                "Cached $name has no current asset stamp; replacing bundled model copy"
            }
            if (!dest.delete()) {
                logcat(LogPriority.WARN) { "Could not delete stale cached $name; attempting overwrite" }
            }
            stampFile.delete()
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
            runCatching { stampFile.writeText(expectedStamp, Charsets.UTF_8) }
                .onFailure { e ->
                    logcat(LogPriority.WARN, e) {
                        "Copied $assetPath but could not write its cache stamp"
                    }
                }
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

    /**
     * TachiyomiAT: lightweight structural validity check for a cached .onnx,
     * replacing the old single-byte `0x08` heuristic which any truncated file
     * could pass. A corrupt/garbage model that passes the old check either
     * throws an opaque OrtException at session creation (surfaces as a generic
     * "Inpainting failed") or, worse, loads a session that emits near-zero
     * output → uniform 128-gray inpaint. Catching it here forces a clean
     * re-copy from assets instead.
     *
     * Verifies, without a protobuf parser:
     *  1. Size floor: a real ONNX model for this app is multi-MB. A file below
     *     [MIN_VALID_ONNX_BYTES] is certainly truncated/empty. (The AOT model
     *     is ~23MB; this floor is deliberately permissive for future smaller
     *     models while still rejecting garbage.)
     *  2. Protobuf header shape: an onnx ModelProto's first field is
     *     `ir_version` (field 1, varint), so byte 0 is 0x08 and byte 1 is a
     *     single-byte varint ir_version (1..15 → high bit clear). Field 7
     *     (`producer_name`, length-delimited, tag 0x3a) commonly follows.
     *  3. Magic NOT matching: reject files that are clearly something else
     *     (PNG `89 50 4E 47`, ZIP/PK `50 4B`, gzip `1F 8B`).
     *
     * Returns true only if all cheap checks pass; a true validation is the
     * downstream OrtSession creation (in [AOTInpainting.initialize]).
     */
    private fun looksLikeValidOnnx(file: File): Boolean {
        if (file.length() < MIN_VALID_ONNX_BYTES) return false
        val header = ByteArray(2)
        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Could not read ${file.name} header: ${e.message}" }
            return false
        }
        if (read < 2) return false
        // Field 1 (ir_version), varint wire type → tag 0x08.
        if (header[0] != 0x08.toByte()) return false
        // ir_version is a single-byte varint (values 1..15 have the high bit
        // clear; current ONNX ir_version is 8). A high-bit-set first varint byte
        // with no continuation is not a valid ModelProto opener.
        if (header[1].toInt() and 0x80 != 0) return false
        return true
    }

    private companion object {
        // Bump whenever bundled model bytes change. Existing caches without this
        // stamp are deliberately recopied once after the deployment fix ships.
        const val MODEL_ASSET_VERSION = "quality-2026-07-12-v1"
        // Real models are multi-MB (AOT ~23MB); below 64 KiB is certainly truncated.
        const val MIN_VALID_ONNX_BYTES = 64L * 1024L
    }
}
