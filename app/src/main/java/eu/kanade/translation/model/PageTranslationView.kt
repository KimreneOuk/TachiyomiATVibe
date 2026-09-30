package eu.kanade.translation.model

import eu.kanade.translation.engines.vision.segmentation.BubbleMaskRle
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Collections

/** Read-only surface shared by mutable pipeline drafts and immutable store publications. */
interface PageTranslationView {
    val blocks: List<TranslationBlockView>
    val imgWidth: Float
    val imgHeight: Float
    val cleanedImageName: String?
    val ocrArtifactId: String?
    val recognitionEngine: String?
    val detectionCount: Int
    val ocrBlockCount: Int
    val decodeSampleSize: Int
    val originalImgWidth: Float
    val originalImgHeight: Float
    val ocrStatus: String
    val translationStatus: String
    val inpaintStatus: String
    val renderStatus: String
    val ocrError: String?
    val translationError: String?
    val inpaintError: String?
    val renderError: String?
    val updatedAt: Long
    val sourceFileName: String?
    val runGeneration: Long
    val pageVersion: Long
    val retryCount: Int
    val inpaintRevision: Int
    val inpaintingModeUsed: String?
    val sourceFingerprint: String?
    val detectionFingerprint: String?
    val ocrFingerprint: String?
    val inpaintFingerprint: String?
    val translationFingerprint: String?
    val layoutFingerprint: String?
    val translationOrigin: String?
    val originalImageFallback: Boolean
    val inpaintMaskBoxes: List<InpaintMaskBox>
    val allTextDetections: List<DetectionView>
    val attemptCount: Int
    val attemptCharged: Boolean
    val errorMessage: String?
    val activeError: String?
}

/** Read-only block surface. Pipeline drafts keep their mutable [TranslationBlock] objects local. */
interface TranslationBlockView {
    val blockId: String?
    val text: String
    val translation: String
    val width: Float
    val height: Float
    val x: Float
    val y: Float
    val symHeight: Float
    val symWidth: Float
    val angle: Float
    val label: Int
    val score: Float
    val parentX: Float
    val parentY: Float
    val parentWidth: Float
    val parentHeight: Float
    val textColor: Long
    val strokeColor: Long
    val strokeWidth: Float
    val direction: String
    val panelIndex: Int?
    val panelAssignment: String
    val panelContainment: Float
    val bubbleIndex: Int?
    val segmentationMask: BubbleMaskRle?
    val userEditedAt: Long?
}

/** Detection geometry without exposing its mutable IntArray backing storage. */
interface DetectionView {
    /** A fresh array for compatibility; mutations can never reach the published detection. */
    val bbox: IntArray
    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
    val label: Int
    val score: Float
    val className: String
}

/**
 * Immutable page value held by ChapterTranslationStore and captured by the journal.
 * Lists are copied into unmodifiable wrappers here, and block/detection objects are
 * replaced with scalar-only immutable values, so no mutable draft alias crosses the
 * publication boundary. `cleanedBitmap` deliberately remains on the pipeline draft.
 */
@Serializable(with = PublishedPageTranslationSerializer::class)
class PublishedPageTranslation private constructor(
    private val blocksData: List<PublishedTranslationBlock>,
    override val imgWidth: Float,
    override val imgHeight: Float,
    override val cleanedImageName: String?,
    override val ocrArtifactId: String?,
    override val recognitionEngine: String?,
    override val detectionCount: Int,
    override val ocrBlockCount: Int,
    override val decodeSampleSize: Int,
    override val originalImgWidth: Float,
    override val originalImgHeight: Float,
    override val ocrStatus: String,
    override val translationStatus: String,
    override val inpaintStatus: String,
    override val renderStatus: String,
    override val ocrError: String?,
    override val translationError: String?,
    override val inpaintError: String?,
    override val renderError: String?,
    override val updatedAt: Long,
    override val sourceFileName: String?,
    override val runGeneration: Long,
    override val pageVersion: Long,
    override val retryCount: Int,
    override val inpaintRevision: Int,
    override val inpaintingModeUsed: String?,
    override val sourceFingerprint: String?,
    override val detectionFingerprint: String?,
    override val ocrFingerprint: String?,
    override val inpaintFingerprint: String?,
    override val translationFingerprint: String?,
    override val layoutFingerprint: String?,
    override val translationOrigin: String?,
    override val originalImageFallback: Boolean,
    private val inpaintMaskBoxesData: List<InpaintMaskBox>,
    private val allTextDetectionsData: List<PublishedDetection>,
    override val attemptCount: Int,
    override val attemptCharged: Boolean,
    override val errorMessage: String?,
) : PageTranslationView {
    override val blocks: List<TranslationBlockView> = blocksData
    override val inpaintMaskBoxes: List<InpaintMaskBox> = inpaintMaskBoxesData
    override val allTextDetections: List<DetectionView> = allTextDetectionsData

    override val activeError: String? get() = ocrError ?: translationError ?: inpaintError ?: renderError ?: errorMessage

    override fun equals(other: Any?): Boolean = pageTranslationValueEquals(this, other)

    override fun hashCode(): Int = pageTranslationValueHashCode(this)

    override fun toString(): String = pageTranslationValueToString(this)

    companion object {
        internal fun fromDraft(page: PageTranslation): PublishedPageTranslation = PublishedPageTranslation(
            blocksData = immutableList(page.blocks.map(PublishedTranslationBlock::fromDraft)),
            imgWidth = page.imgWidth,
            imgHeight = page.imgHeight,
            cleanedImageName = page.cleanedImageName,
            ocrArtifactId = page.ocrArtifactId,
            recognitionEngine = page.recognitionEngine,
            detectionCount = page.detectionCount,
            ocrBlockCount = page.ocrBlockCount,
            decodeSampleSize = page.decodeSampleSize,
            originalImgWidth = page.originalImgWidth,
            originalImgHeight = page.originalImgHeight,
            ocrStatus = page.ocrStatus,
            translationStatus = page.translationStatus,
            inpaintStatus = page.inpaintStatus,
            renderStatus = page.renderStatus,
            ocrError = page.ocrError,
            translationError = page.translationError,
            inpaintError = page.inpaintError,
            renderError = page.renderError,
            updatedAt = page.updatedAt,
            sourceFileName = page.sourceFileName,
            runGeneration = page.runGeneration,
            pageVersion = page.pageVersion,
            retryCount = page.retryCount,
            inpaintRevision = page.inpaintRevision,
            inpaintingModeUsed = page.inpaintingModeUsed,
            sourceFingerprint = page.sourceFingerprint,
            detectionFingerprint = page.detectionFingerprint,
            ocrFingerprint = page.ocrFingerprint,
            inpaintFingerprint = page.inpaintFingerprint,
            translationFingerprint = page.translationFingerprint,
            layoutFingerprint = page.layoutFingerprint,
            translationOrigin = page.translationOrigin,
            originalImageFallback = page.originalImageFallback,
            inpaintMaskBoxesData = immutableList(page.inpaintMaskBoxes),
            allTextDetectionsData = immutableList(page.allTextDetections.map(PublishedDetection::fromDraft)),
            attemptCount = page.attemptCount,
            attemptCharged = page.attemptCharged,
            errorMessage = page.errorMessage,
        )
    }
}

/** Immutable block value. Its mask is independently frozen during construction. */
class PublishedTranslationBlock private constructor(
    override val blockId: String?,
    override val text: String,
    override val translation: String,
    override val width: Float,
    override val height: Float,
    override val x: Float,
    override val y: Float,
    override val symHeight: Float,
    override val symWidth: Float,
    override val angle: Float,
    override val label: Int,
    override val score: Float,
    override val parentX: Float,
    override val parentY: Float,
    override val parentWidth: Float,
    override val parentHeight: Float,
    override val textColor: Long,
    override val strokeColor: Long,
    override val strokeWidth: Float,
    override val direction: String,
    override val panelIndex: Int?,
    override val panelAssignment: String,
    override val panelContainment: Float,
    override val bubbleIndex: Int?,
    segmentationMask: BubbleMaskRle?,
    override val userEditedAt: Long?,
) : TranslationBlockView {
    override val segmentationMask: BubbleMaskRle? = segmentationMask?.immutableCopy()
    internal val cachedStableFingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        calculateStableFingerprint(this)
    }

    override fun equals(other: Any?): Boolean = translationBlockValueEquals(this, other)

    override fun hashCode(): Int = translationBlockValueHashCode(this)

    override fun toString(): String = translationBlockValueToString(this)

    internal fun toDraft(): TranslationBlock = TranslationBlock(
        blockId = blockId,
        text = text,
        translation = translation,
        width = width,
        height = height,
        x = x,
        y = y,
        symHeight = symHeight,
        symWidth = symWidth,
        angle = angle,
        label = label,
        score = score,
        parentX = parentX,
        parentY = parentY,
        parentWidth = parentWidth,
        parentHeight = parentHeight,
        textColor = textColor,
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
        direction = direction,
        panelIndex = panelIndex,
        panelAssignment = panelAssignment,
        panelContainment = panelContainment,
        bubbleIndex = bubbleIndex,
        segmentationMask = segmentationMask?.mutableCopy(),
        userEditedAt = userEditedAt,
    )

    companion object {
        internal fun fromDraft(block: TranslationBlock): PublishedTranslationBlock = PublishedTranslationBlock(
            blockId = block.blockId,
            text = block.text,
            translation = block.translation,
            width = block.width,
            height = block.height,
            x = block.x,
            y = block.y,
            symHeight = block.symHeight,
            symWidth = block.symWidth,
            angle = block.angle,
            label = block.label,
            score = block.score,
            parentX = block.parentX,
            parentY = block.parentY,
            parentWidth = block.parentWidth,
            parentHeight = block.parentHeight,
            textColor = block.textColor,
            strokeColor = block.strokeColor,
            strokeWidth = block.strokeWidth,
            direction = block.direction,
            panelIndex = block.panelIndex,
            panelAssignment = block.panelAssignment,
            panelContainment = block.panelContainment,
            bubbleIndex = block.bubbleIndex,
            segmentationMask = block.segmentationMask,
            userEditedAt = block.userEditedAt,
        )
    }
}

/** Immutable detection geometry, intentionally storing no array reference. */
class PublishedDetection private constructor(
    override val left: Int,
    override val top: Int,
    override val right: Int,
    override val bottom: Int,
    override val label: Int,
    override val score: Float,
    override val className: String,
) : DetectionView {
    override val bbox: IntArray get() = intArrayOf(left, top, right, bottom)

    override fun equals(other: Any?): Boolean = detectionValueEquals(this, other)

    override fun hashCode(): Int = detectionValueHashCode(this)

    override fun toString(): String = detectionValueToString(this)

    internal fun toDraft(): Detection = Detection(
        bbox = intArrayOf(left, top, right, bottom),
        label = label,
        score = score,
        className = className,
    )

    companion object {
        internal fun fromDraft(detection: Detection): PublishedDetection = PublishedDetection(
            left = detection.left,
            top = detection.top,
            right = detection.right,
            bottom = detection.bottom,
            label = detection.label,
            score = detection.score,
            className = detection.className,
        )
    }
}

/** Converts a single pipeline draft into its immutable published value. */
internal fun PageTranslation.toPublishedPage(): PublishedPageTranslation =
    PublishedPageTranslation.fromDraft(this)

/** Promotes a read-only page value, reusing immutable publications by identity. */
internal fun PageTranslationView.toPublishedPage(): PublishedPageTranslation = when (this) {
    is PublishedPageTranslation -> this
    is PageTranslation -> toPublishedPage()
    else -> error("Unsupported PageTranslationView implementation: ${this::class.qualifiedName}")
}

/** Materializes a private mutable draft for a single-page store operation. */
internal fun PublishedPageTranslation.toDraft(): PageTranslation = PageTranslation(
    blocks = blocks.map { (it as PublishedTranslationBlock).toDraft() }.toMutableList(),
    imgWidth = imgWidth,
    imgHeight = imgHeight,
    cleanedImageName = cleanedImageName,
    ocrArtifactId = ocrArtifactId,
    recognitionEngine = recognitionEngine,
    detectionCount = detectionCount,
    ocrBlockCount = ocrBlockCount,
    decodeSampleSize = decodeSampleSize,
    originalImgWidth = originalImgWidth,
    originalImgHeight = originalImgHeight,
    ocrStatus = ocrStatus,
    translationStatus = translationStatus,
    inpaintStatus = inpaintStatus,
    renderStatus = renderStatus,
    ocrError = ocrError,
    translationError = translationError,
    inpaintError = inpaintError,
    renderError = renderError,
    updatedAt = updatedAt,
    sourceFileName = sourceFileName,
    runGeneration = runGeneration,
    pageVersion = pageVersion,
    retryCount = retryCount,
    inpaintRevision = inpaintRevision,
    inpaintingModeUsed = inpaintingModeUsed,
    sourceFingerprint = sourceFingerprint,
    detectionFingerprint = detectionFingerprint,
    ocrFingerprint = ocrFingerprint,
    inpaintFingerprint = inpaintFingerprint,
    translationFingerprint = translationFingerprint,
    layoutFingerprint = layoutFingerprint,
    translationOrigin = translationOrigin,
    originalImageFallback = originalImageFallback,
    inpaintMaskBoxes = inpaintMaskBoxes.toMutableList(),
).also { draft ->
    draft.attemptCount = attemptCount
    draft.attemptCharged = attemptCharged
    draft.allTextDetections = allTextDetections.map { (it as PublishedDetection).toDraft() }
    draft.setErrorMessageRaw(errorMessage)
}

/**
 * Materializes a consumer-private mutable copy from the read-only page surface.
 * Store publications take the immutable branch; legacy in-memory drafts are
 * detached so a consumer can never mutate the instance held by its caller.
 */
internal fun PageTranslationView.toDraft(): PageTranslation = when (this) {
    is PublishedPageTranslation -> toDraft()
    is PageTranslation -> detachedCopy()
    else -> error("Unsupported PageTranslationView implementation: ${this::class.qualifiedName}")
}

/**
 * Journal serialization intentionally reuses PageTranslation's wire schema. The immutable value
 * adds in-memory record metadata, but those fields were transient in the shadow writer schema.
 */
object PublishedPageTranslationSerializer : KSerializer<PublishedPageTranslation> {
    override val descriptor = PageTranslation.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PublishedPageTranslation) {
        PageTranslation.serializer().serialize(encoder, value.toDraft())
    }

    override fun deserialize(decoder: Decoder): PublishedPageTranslation =
        PublishedPageTranslation.fromDraft(PageTranslation.serializer().deserialize(decoder))
}

private fun BubbleMaskRle.immutableCopy(): BubbleMaskRle = copy(
    bounds = immutableList(bounds),
    runs = immutableList(runs),
)

private fun BubbleMaskRle.mutableCopy(): BubbleMaskRle = copy(
    bounds = bounds.toMutableList(),
    runs = runs.toMutableList(),
)

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Structural semantics shared by mutable drafts and immutable publications. */
internal fun pageTranslationValueEquals(page: PageTranslationView, other: Any?): Boolean {
    if (page === other) return true
    if (other !is PageTranslationView) return false
    return page.blocks == other.blocks &&
        sameValue(page.imgWidth, other.imgWidth) &&
        sameValue(page.imgHeight, other.imgHeight) &&
        page.cleanedImageName == other.cleanedImageName &&
        page.ocrArtifactId == other.ocrArtifactId &&
        page.recognitionEngine == other.recognitionEngine &&
        page.detectionCount == other.detectionCount &&
        page.ocrBlockCount == other.ocrBlockCount &&
        page.decodeSampleSize == other.decodeSampleSize &&
        sameValue(page.originalImgWidth, other.originalImgWidth) &&
        sameValue(page.originalImgHeight, other.originalImgHeight) &&
        page.ocrStatus == other.ocrStatus &&
        page.translationStatus == other.translationStatus &&
        page.inpaintStatus == other.inpaintStatus &&
        page.renderStatus == other.renderStatus &&
        page.ocrError == other.ocrError &&
        page.translationError == other.translationError &&
        page.inpaintError == other.inpaintError &&
        page.renderError == other.renderError &&
        page.updatedAt == other.updatedAt &&
        page.sourceFileName == other.sourceFileName &&
        page.runGeneration == other.runGeneration &&
        page.pageVersion == other.pageVersion &&
        page.retryCount == other.retryCount &&
        page.inpaintRevision == other.inpaintRevision &&
        page.inpaintingModeUsed == other.inpaintingModeUsed &&
        page.sourceFingerprint == other.sourceFingerprint &&
        page.detectionFingerprint == other.detectionFingerprint &&
        page.ocrFingerprint == other.ocrFingerprint &&
        page.inpaintFingerprint == other.inpaintFingerprint &&
        page.translationFingerprint == other.translationFingerprint &&
        page.layoutFingerprint == other.layoutFingerprint &&
        page.translationOrigin == other.translationOrigin &&
        page.originalImageFallback == other.originalImageFallback &&
        page.inpaintMaskBoxes == other.inpaintMaskBoxes
}

internal fun pageTranslationValueHashCode(page: PageTranslationView): Int {
    var result = page.blocks.hashCode()
    result = 31 * result + page.imgWidth.toBits()
    result = 31 * result + page.imgHeight.toBits()
    result = 31 * result + (page.cleanedImageName?.hashCode() ?: 0)
    result = 31 * result + (page.ocrArtifactId?.hashCode() ?: 0)
    result = 31 * result + (page.recognitionEngine?.hashCode() ?: 0)
    result = 31 * result + page.detectionCount
    result = 31 * result + page.ocrBlockCount
    result = 31 * result + page.decodeSampleSize
    result = 31 * result + page.originalImgWidth.toBits()
    result = 31 * result + page.originalImgHeight.toBits()
    result = 31 * result + page.ocrStatus.hashCode()
    result = 31 * result + page.translationStatus.hashCode()
    result = 31 * result + page.inpaintStatus.hashCode()
    result = 31 * result + page.renderStatus.hashCode()
    result = 31 * result + (page.ocrError?.hashCode() ?: 0)
    result = 31 * result + (page.translationError?.hashCode() ?: 0)
    result = 31 * result + (page.inpaintError?.hashCode() ?: 0)
    result = 31 * result + (page.renderError?.hashCode() ?: 0)
    result = 31 * result + page.updatedAt.hashCode()
    result = 31 * result + (page.sourceFileName?.hashCode() ?: 0)
    result = 31 * result + page.runGeneration.hashCode()
    result = 31 * result + page.pageVersion.hashCode()
    result = 31 * result + page.retryCount
    result = 31 * result + page.inpaintRevision
    result = 31 * result + (page.inpaintingModeUsed?.hashCode() ?: 0)
    result = 31 * result + (page.sourceFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.detectionFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.ocrFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.inpaintFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.translationFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.layoutFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.translationOrigin?.hashCode() ?: 0)
    result = 31 * result + page.originalImageFallback.hashCode()
    result = 31 * result + page.inpaintMaskBoxes.hashCode()
    return result
}

internal fun pageTranslationValueToString(page: PageTranslationView): String =
    "PageTranslation(" +
        "blocks=${page.blocks}, " +
        "imgWidth=${page.imgWidth}, " +
        "imgHeight=${page.imgHeight}, " +
        "cleanedImageName=${page.cleanedImageName}, " +
        "ocrArtifactId=${page.ocrArtifactId}, " +
        "recognitionEngine=${page.recognitionEngine}, " +
        "detectionCount=${page.detectionCount}, " +
        "ocrBlockCount=${page.ocrBlockCount}, " +
        "decodeSampleSize=${page.decodeSampleSize}, " +
        "originalImgWidth=${page.originalImgWidth}, " +
        "originalImgHeight=${page.originalImgHeight}, " +
        "ocrStatus=${page.ocrStatus}, " +
        "translationStatus=${page.translationStatus}, " +
        "inpaintStatus=${page.inpaintStatus}, " +
        "renderStatus=${page.renderStatus}, " +
        "ocrError=${page.ocrError}, " +
        "translationError=${page.translationError}, " +
        "inpaintError=${page.inpaintError}, " +
        "renderError=${page.renderError}, " +
        "updatedAt=${page.updatedAt}, " +
        "sourceFileName=${page.sourceFileName}, " +
        "runGeneration=${page.runGeneration}, " +
        "pageVersion=${page.pageVersion}, " +
        "retryCount=${page.retryCount}, " +
        "inpaintRevision=${page.inpaintRevision}, " +
        "inpaintingModeUsed=${page.inpaintingModeUsed}, " +
        "sourceFingerprint=${page.sourceFingerprint}, " +
        "detectionFingerprint=${page.detectionFingerprint}, " +
        "ocrFingerprint=${page.ocrFingerprint}, " +
        "inpaintFingerprint=${page.inpaintFingerprint}, " +
        "translationFingerprint=${page.translationFingerprint}, " +
        "layoutFingerprint=${page.layoutFingerprint}, " +
        "translationOrigin=${page.translationOrigin}, " +
        "originalImageFallback=${page.originalImageFallback}, " +
        "inpaintMaskBoxes=${page.inpaintMaskBoxes})"

internal fun translationBlockValueEquals(block: TranslationBlockView, other: Any?): Boolean {
    if (block === other) return true
    if (other !is TranslationBlockView) return false
    return block.blockId == other.blockId &&
        block.text == other.text &&
        block.translation == other.translation &&
        sameValue(block.width, other.width) &&
        sameValue(block.height, other.height) &&
        sameValue(block.x, other.x) &&
        sameValue(block.y, other.y) &&
        sameValue(block.symHeight, other.symHeight) &&
        sameValue(block.symWidth, other.symWidth) &&
        sameValue(block.angle, other.angle) &&
        block.label == other.label &&
        sameValue(block.score, other.score) &&
        sameValue(block.parentX, other.parentX) &&
        sameValue(block.parentY, other.parentY) &&
        sameValue(block.parentWidth, other.parentWidth) &&
        sameValue(block.parentHeight, other.parentHeight) &&
        block.textColor == other.textColor &&
        block.strokeColor == other.strokeColor &&
        sameValue(block.strokeWidth, other.strokeWidth) &&
        block.direction == other.direction &&
        block.panelIndex == other.panelIndex &&
        block.panelAssignment == other.panelAssignment &&
        sameValue(block.panelContainment, other.panelContainment) &&
        block.bubbleIndex == other.bubbleIndex &&
        block.segmentationMask == other.segmentationMask &&
        block.userEditedAt == other.userEditedAt
}

internal fun translationBlockValueHashCode(block: TranslationBlockView): Int {
    var result = block.blockId?.hashCode() ?: 0
    result = 31 * result + block.text.hashCode()
    result = 31 * result + block.translation.hashCode()
    result = 31 * result + block.width.toBits()
    result = 31 * result + block.height.toBits()
    result = 31 * result + block.x.toBits()
    result = 31 * result + block.y.toBits()
    result = 31 * result + block.symHeight.toBits()
    result = 31 * result + block.symWidth.toBits()
    result = 31 * result + block.angle.toBits()
    result = 31 * result + block.label
    result = 31 * result + block.score.toBits()
    result = 31 * result + block.parentX.toBits()
    result = 31 * result + block.parentY.toBits()
    result = 31 * result + block.parentWidth.toBits()
    result = 31 * result + block.parentHeight.toBits()
    result = 31 * result + block.textColor.hashCode()
    result = 31 * result + block.strokeColor.hashCode()
    result = 31 * result + block.strokeWidth.toBits()
    result = 31 * result + block.direction.hashCode()
    result = 31 * result + (block.panelIndex?.hashCode() ?: 0)
    result = 31 * result + block.panelAssignment.hashCode()
    result = 31 * result + block.panelContainment.toBits()
    result = 31 * result + (block.bubbleIndex?.hashCode() ?: 0)
    result = 31 * result + (block.segmentationMask?.hashCode() ?: 0)
    result = 31 * result + (block.userEditedAt?.hashCode() ?: 0)
    return result
}

internal fun translationBlockValueToString(block: TranslationBlockView): String =
    "TranslationBlock(" +
        "blockId=${block.blockId}, " +
        "text=${block.text}, " +
        "translation=${block.translation}, " +
        "width=${block.width}, " +
        "height=${block.height}, " +
        "x=${block.x}, " +
        "y=${block.y}, " +
        "symHeight=${block.symHeight}, " +
        "symWidth=${block.symWidth}, " +
        "angle=${block.angle}, " +
        "label=${block.label}, " +
        "score=${block.score}, " +
        "parentX=${block.parentX}, " +
        "parentY=${block.parentY}, " +
        "parentWidth=${block.parentWidth}, " +
        "parentHeight=${block.parentHeight}, " +
        "textColor=${block.textColor}, " +
        "strokeColor=${block.strokeColor}, " +
        "strokeWidth=${block.strokeWidth}, " +
        "direction=${block.direction}, " +
        "panelIndex=${block.panelIndex}, " +
        "panelAssignment=${block.panelAssignment}, " +
        "panelContainment=${block.panelContainment}, " +
        "bubbleIndex=${block.bubbleIndex}, " +
        "segmentationMask=${block.segmentationMask}, " +
        "userEditedAt=${block.userEditedAt})"

internal fun detectionValueEquals(detection: DetectionView, other: Any?): Boolean {
    if (detection === other) return true
    if (other !is DetectionView) return false
    return detection.left == other.left &&
        detection.top == other.top &&
        detection.right == other.right &&
        detection.bottom == other.bottom &&
        detection.label == other.label &&
        sameValue(detection.score, other.score) &&
        detection.className == other.className
}

internal fun detectionValueHashCode(detection: DetectionView): Int {
    var result = detection.left
    result = 31 * result + detection.top
    result = 31 * result + detection.right
    result = 31 * result + detection.bottom
    result = 31 * result + detection.label
    result = 31 * result + detection.score.toBits()
    result = 31 * result + detection.className.hashCode()
    return result
}

internal fun detectionValueToString(detection: DetectionView): String =
    "Detection(bbox=${listOf(detection.left, detection.top, detection.right, detection.bottom)}, " +
        "label=${detection.label}, score=${detection.score}, className=${detection.className})"

private fun sameValue(first: Float, second: Float): Boolean = first.toBits() == second.toBits()
