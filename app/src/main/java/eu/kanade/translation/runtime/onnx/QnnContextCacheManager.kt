package eu.kanade.translation.runtime.onnx

import android.content.Context
import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * Manages the lifecycle of compiled Qualcomm QNN EP context caches.
 *
 * ORT's QNN EP compiles neural network graphs for the Hexagon DSP/NPU (HTP).
 * Cold graph compilation takes ~40-60 seconds on large models (e.g. AOT-GAN).
 * Persisting the compiled context binary reduces subsequent session startup to ~300ms.
 *
 * Atomicity & Pairing Rule:
 * When ep.context_embed_mode=0 (ORT default), ORT writes two files:
 * 1. An EPContext wrapper model (<name>.qnnctx.bin)
 * 2. A companion native binary (<name>.qnnctx_qnn.bin)
 * The wrapper references the companion binary by relative filename. Renaming individual
 * files breaks this internal link. Therefore, cache promotion MUST be performed
 * by atomically renaming the containing directory.
 *
 * Cache entries are keyed by:
 * - Model path, length, and timestamp
 * - ORT version ("1.28.0")
 * - QNN runtime version ("2.42.0")
 * - Device OS build fingerprint (invalidates on system OTA updates)
 * - Provider configuration options
 */
object QnnContextCacheManager {

    private const val TAG = "[qnn_cache]"
    const val ORT_VERSION = "1.28.0"
    const val QNN_VERSION = "2.42.0"

    private fun getCacheRootDir(context: Context): File {
        return File(context.noBackupFilesDir, "qnn_context_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Computes a unique, deterministic cache key for a model and its configuration.
     */
    fun computeCacheKey(modelFile: File, options: Map<String, String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val modelId = ModelRoutingEngine.resolveModelId(modelFile.absolutePath)
        val input = buildString {
            append("model_id=").append(modelId).append(";")
            append("model_size=").append(modelFile.length()).append(";")
            append("model_mtime=").append(modelFile.lastModified()).append(";")
            append("ort_version=").append(ORT_VERSION).append(";")
            append("qnn_version=").append(QNN_VERSION).append(";")
            append("build_fingerprint=").append(Build.FINGERPRINT).append(";")
            options.toSortedMap().forEach { (k, v) ->
                append(k).append("=").append(v).append(";")
            }
        }
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Checks if a valid, complete context cache exists for the given model.
     * Both the wrapper model and the companion QNN binary must be present and non-empty.
     */
    fun getValidCachedModel(context: Context, modelFile: File, options: Map<String, String>): File? {
        val key = computeCacheKey(modelFile, options)
        val entryDir = File(getCacheRootDir(context), key)
        if (!entryDir.exists() || !entryDir.isDirectory) return null

        val wrapperFile = File(entryDir, "${modelFile.name}.qnnctx.bin")
        val companionBin = File(entryDir, "${modelFile.name}.qnnctx_qnn.bin")

        if (wrapperFile.exists() && wrapperFile.length() > 0 &&
            companionBin.exists() && companionBin.length() > 0
        ) {
            logcat(LogPriority.INFO) {
                "$TAG Found valid context cache for ${modelFile.name} (wrapper=${wrapperFile.length()}B bin=${companionBin.length()}B key=$key)"
            }
            return wrapperFile
        }

        logcat(LogPriority.WARN) {
            "$TAG Incomplete or corrupt context cache entry for ${modelFile.name} in $entryDir (purging)"
        }
        entryDir.deleteRecursively()
        return null
    }

    /**
     * Prepares an isolated staging directory for context generation.
     */
    fun prepareStaging(context: Context, modelFile: File, options: Map<String, String>): File {
        val key = computeCacheKey(modelFile, options)
        val stagingDir = File(getCacheRootDir(context), "staging_$key")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        return File(stagingDir, "${modelFile.name}.qnnctx.bin")
    }

    /**
     * Promotes the staged context cache to active status via an atomic directory move.
     */
    fun commitStaging(context: Context, modelFile: File, options: Map<String, String>): Boolean {
        val key = computeCacheKey(modelFile, options)
        val root = getCacheRootDir(context)
        val stagingDir = File(root, "staging_$key")
        val targetDir = File(root, key)

        if (!stagingDir.exists()) {
            logcat(LogPriority.WARN) { "$TAG Staging directory does not exist: ${stagingDir.absolutePath}" }
            return false
        }

        val wrapperFile = File(stagingDir, "${modelFile.name}.qnnctx.bin")
        val companionBin = File(stagingDir, "${modelFile.name}.qnnctx_qnn.bin")

        if (!wrapperFile.exists() || wrapperFile.length() == 0L ||
            !companionBin.exists() || companionBin.length() == 0L
        ) {
            logcat(LogPriority.WARN) {
                "$TAG Context generation incomplete in staging; wrapper=${wrapperFile.length()}B bin=${companionBin.length()}B"
            }
            stagingDir.deleteRecursively()
            return false
        }

        targetDir.deleteRecursively()
        val success = stagingDir.renameTo(targetDir)
        if (success) {
            logcat(LogPriority.INFO) {
                "$TAG Context cache committed successfully for ${modelFile.name} -> $targetDir"
            }
        } else {
            logcat(LogPriority.WARN) {
                "$TAG Failed to promote staging directory to $targetDir"
            }
            stagingDir.deleteRecursively()
        }
        return success
    }

    /**
     * Invalidates and deletes the context cache entry for a given model.
     */
    fun invalidate(context: Context, modelFile: File, options: Map<String, String>) {
        val key = computeCacheKey(modelFile, options)
        val entryDir = File(getCacheRootDir(context), key)
        if (entryDir.exists()) {
            logcat(LogPriority.INFO) { "$TAG Invalidating context cache at ${entryDir.absolutePath}" }
            entryDir.deleteRecursively()
        }
    }
}
