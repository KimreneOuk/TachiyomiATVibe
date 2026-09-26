package eu.kanade.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.OcrModel
import java.io.InputStream
import java.security.MessageDigest

/**
 * Decodes source pages and computes their fingerprints. Memory reclaim and
 * decode logging use [MemoryGovernance]; the recognition engine is supplied
 * by the pipeline when needed.
 */
internal object PageDecode {

    fun decodePageBitmapAtSize(fileName: String, sampleSize: Int, streams: List<Pair<String, () -> InputStream>>): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return try {
            val entry = streams.find { it.first == fileName } ?: return null
            entry.second().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun decodePageBitmapForTranslation(
        context: Context,
        recognitionEngine: () -> PageRecognitionEngine,
        fileName: String,
        streamFn: () -> InputStream,
    ): DecodedPage? = withContext(Dispatchers.IO) {
        val buffered: ByteArray = try {
            streamFn().use { it.readBytes() }
        } catch (oom: OutOfMemoryError) {
            MemoryGovernance.reclaimTranslationMemory(context, recognitionEngine, "decode bounds $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) { "Out of memory reading page bytes for $fileName" }
            return@withContext null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed reading page bytes for $fileName" }
            return@withContext null
        }
        val sourceFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(buffered)
            .joinToString("") { byte -> "%02x".format(byte) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (oom: OutOfMemoryError) {
            MemoryGovernance.reclaimTranslationMemory(context, recognitionEngine, "decode bounds $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) { "Out of memory reading page bounds for $fileName" }
            return@withContext null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed reading page bounds for $fileName" }
            return@withContext null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var decision = TranslationMemoryBudget.chooseDecodeDecision(bounds.outWidth, bounds.outHeight, buffered.size.toLong())
        if (decision.kind == DecodeDecisionKind.HEAP_CONSTRAINED) {
            val before = decision
            MemoryGovernance.reclaimTranslationMemory(context, recognitionEngine, "decode preflight $fileName", trimImageCache = true)
            decision = TranslationMemoryBudget.chooseDecodeDecision(bounds.outWidth, bounds.outHeight, buffered.size.toLong())
            MemoryGovernance.logDecodeDecision(fileName, bounds.outWidth, bounds.outHeight, before, decision)
        } else {
            MemoryGovernance.logDecodeDecision(fileName, bounds.outWidth, bounds.outHeight, null, decision)
        }

        if (decision.kind == DecodeDecisionKind.HEAP_CONSTRAINED) {
            throw LowMemoryDecodeDeferredException(fileName, bounds.outWidth, bounds.outHeight, decision)
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = decision.sampleSize
        }
        val bitmap = try {
            java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (oom: OutOfMemoryError) {
            MemoryGovernance.reclaimTranslationMemory(context, recognitionEngine, "decode bitmap $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) {
                "Out of memory decoding accepted bitmap for $fileName " +
                    "sample=${decision.sampleSize} reason=${decision.kind}"
            }
            throw LowMemoryDecodeDeferredException(fileName, bounds.outWidth, bounds.outHeight, decision)
        } ?: return@withContext null

        DecodedPage(
            bitmap = bitmap,
            sampleSize = decision.sampleSize,
            originalWidth = bounds.outWidth,
            originalHeight = bounds.outHeight,
            decodeDecision = decision,
            sourceBytesSize = buffered.size.toLong(),
            sourceFingerprint = sourceFingerprint,
        )
    }

    suspend fun computeSourceFingerprint(streamFn: () -> InputStream): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            streamFn().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed hashing translation source bytes" }
            null
        }
    }

    fun batchExpectedFingerprints(
        currentTranslatorSignature: Any,
        currentOcrModel: OcrModel,
        currentReadingOrder: tachiyomi.domain.translation.TranslationReadingOrder,
        currentInpaintingMode: eu.kanade.translation.engines.inpainting.InpaintingMode,
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): BatchExpectedFingerprints {
        val translationInput = currentTranslatorSignature.toString()
        return BatchExpectedFingerprints(
            detection = StageFingerprints.configuration(
                ArtifactStage.DETECTION,
                currentOcrModel.name,
                currentReadingOrder.name,
            ),
            ocr = StageFingerprints.configuration(
                ArtifactStage.OCR,
                currentOcrModel.name,
                fromLang.name,
            ),
            inpaint = StageFingerprints.configuration(
                ArtifactStage.INPAINT,
                currentInpaintingMode.name,
                PageTranslation.CURRENT_INPAINT_REVISION,
            ),
            translation = StageFingerprints.configuration(
                ArtifactStage.TRANSLATION,
                translationInput,
                fromLang.name,
                toLang.name,
            ),
            layout = StageFingerprints.configuration(
                ArtifactStage.LAYOUT,
                currentReadingOrder.name,
            ),
        )
    }
}

internal data class DecodedPage(
    val bitmap: Bitmap,
    val sampleSize: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val decodeDecision: DecodeDecision,
    val sourceBytesSize: Long,
    val sourceFingerprint: String? = null,
)

internal class LowMemoryDecodeDeferredException(
    fileName: String,
    val width: Int,
    val height: Int,
    val decision: DecodeDecision,
) : RuntimeException(
    "Low memory translating $fileName: released caches, but full-quality decode is still unsafe " +
        "(page=${width}x$height raw=${decision.rawBitmapBytes / (1024L * 1024L)}MiB " +
        "available=${decision.snapshot.availableHeapBytes / (1024L * 1024L)}MiB). Retry when memory recovers.",
)

internal class LowMemoryRecognitionDeferredException(
    val fileName: String,
    val width: Int,
    val height: Int,
    val reason: String,
) : RuntimeException(
    "Low memory translating $fileName: $reason (page=${width}x$height). Retry when memory recovers.",
)
