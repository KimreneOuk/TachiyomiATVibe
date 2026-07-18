package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** Small immutable reference queued after OCR has committed and native resources are released. */
data class OcrReadyPageRef(
    val pageKey: String,
    val pageIndex: Int,
    val generation: Long,
    val blockFingerprints: List<String>,
)

/** One detached Pass-1 target and the preconditions captured before the request. */
data class TranslationBlockPatch(
    val blockIndex: Int,
    val expectedOcrFingerprint: String,
    val expectedSourceText: String,
    val expectedTranslation: String,
    val expectedUserEditedAt: Long?,
    val expectedNeedsRevision: Boolean,
    val translation: String,
    val needsRevision: Boolean,
)

/** Translation-owned fields and target-specific merge preconditions. */
data class TranslationStagePatch(
    val pageKey: String,
    val generation: Long,
    val expectedOcrBlockFingerprints: List<String>,
    val expectedSourceTexts: List<String>,
    val blocks: List<TranslationBlockPatch>,
    val translationStatus: String,
    val errorMessage: String? = null,
)

/** Inpaint-owned fields and the durable OCR/mask identity they were derived from. */
data class InpaintStagePatch(
    val pageKey: String,
    val generation: Long,
    val expectedOcrBlockFingerprints: List<String>,
    val expectedMaskFingerprint: String,
    val cleanedImageName: String,
    val inpaintRevision: Int,
    val inpaintingModeUsed: String?,
    val inpaintStatus: String,
    val errorMessage: String? = null,
)

/** Render-owned colors for one block. No image or bitmap is retained. */
data class RenderBlockPatch(
    val blockIndex: Int,
    val expectedBlockFingerprint: String,
    val textColor: Long,
    val strokeColor: Long,
    val strokeWidth: Float,
)

/** Render-owned fields and the cleaned-image identity they join against. */
data class RenderStagePatch(
    val pageKey: String,
    val generation: Long,
    val expectedCleanedImageName: String,
    val expectedInpaintRevision: Int,
    val expectedOcrBlockFingerprints: List<String>,
    val blocks: List<RenderBlockPatch>,
    val renderStatus: String,
    val errorMessage: String? = null,
)

/** Text/flag-only revision patch. A null replacement keeps the current draft (K). */
data class RevisionStagePatch(
    val pageKey: String,
    val generation: Long,
    val blockIndex: Int,
    val expectedBlockFingerprint: String,
    val expectedSourceText: String,
    val expectedDraft: String,
    val expectedNeedsRevision: Boolean,
    val expectedUserEditedAt: Long?,
    val replacementTranslation: String?,
    val needsRevision: Boolean,
)

sealed interface StagePatch {
    val pageKey: String
    val generation: Long

    data class Translation(val value: TranslationStagePatch) : StagePatch {
        override val pageKey: String get() = value.pageKey
        override val generation: Long get() = value.generation
    }

    data class Inpaint(val value: InpaintStagePatch) : StagePatch {
        override val pageKey: String get() = value.pageKey
        override val generation: Long get() = value.generation
    }

    data class Render(val value: RenderStagePatch) : StagePatch {
        override val pageKey: String get() = value.pageKey
        override val generation: Long get() = value.generation
    }

    data class Revision(val value: RevisionStagePatch) : StagePatch {
        override val pageKey: String get() = value.pageKey
        override val generation: Long get() = value.generation
    }
}

sealed interface StagePatchResult {
    data class Accepted(
        val snapshot: ChapterTranslationStore.PageSnapshot,
        val appliedBlockIndices: List<Int> = emptyList(),
    ) : StagePatchResult

    data class Rejected(val reason: String) : StagePatchResult
}

/**
 * Pure request admission. `withRequest` owns the lock for the entire provider
 * call, and `withLock` guarantees release when the caller or provider is
 * cancelled or fails.
 */
class ProviderRequestAdmission {
    private val mutex = Mutex()

    suspend fun <T> withRequest(block: suspend () -> T): T = mutex.withLock { block() }
}

/** Process-wide provider lane shared by manual, auto, batch, and revision callers. */
object SharedProviderRequestAdmission {
    private val admission = ProviderRequestAdmission()

    suspend fun <T> withRequest(block: suspend () -> T): T = admission.withRequest(block)
}

/** OCR identity excludes mutable translation, render colors, revision flags, and edits. */
fun TranslationBlock.ocrFingerprint(): String {
    val canonical = buildString {
        appendField(text)
        appendField(width.toRawBits())
        appendField(height.toRawBits())
        appendField(x.toRawBits())
        appendField(y.toRawBits())
        appendField(symHeight.toRawBits())
        appendField(symWidth.toRawBits())
        appendField(angle.toRawBits())
        appendField(label)
        appendField(score.toRawBits())
        appendField(parentX.toRawBits())
        appendField(parentY.toRawBits())
        appendField(parentWidth.toRawBits())
        appendField(parentHeight.toRawBits())
        appendField(direction)
        appendField(panelIndex)
        appendField(panelAssignment)
        appendField(panelContainment.toRawBits())
        appendField(bubbleIndex)
        segmentationMask?.let { mask ->
            appendField(mask.width)
            appendField(mask.height)
            mask.bounds.forEach(::appendField)
            mask.runs.forEach(::appendField)
            appendField(mask.score.toRawBits())
        } ?: appendField(null)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun PageTranslation.ocrBlockFingerprints(): List<String> = blocks.map { it.ocrFingerprint() }

fun PageTranslation.inpaintMaskFingerprint(): String =
    fingerprint(
        inpaintMaskBoxes.joinToString("|") { box ->
            "${box.x1},${box.y1},${box.x2},${box.y2},${box.label}"
        },
    )

private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun StringBuilder.appendField(value: Any?) {
    val text = value?.toString() ?: "<null>"
    append(text.length).append(':').append(text).append('|')
}
