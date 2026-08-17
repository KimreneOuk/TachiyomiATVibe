package eu.kanade.translation.scheduling

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus

/**
 * Production boundary helper: persists the in-memory OCR result (blocks,
 * geometry, ocrStatus, inpaint status) onto the durable [store] entry, recycles
 * the cleaned bitmap so it does NOT cross the lane boundary, and constructs the
 * lightweight [PreparedPage] reference from the resulting durable snapshot.
 *
 * This is the exact code path [TranslationPipeline.prepareSinglePage] runs
 * after the cleaned image is published. It is extracted as a top-level internal
 * function so pure-JVM regression tests can drive the REAL durability write
 * without instantiating the full Android-bound pipeline. A test that removes
 * the `store.updatePage` call below will fail because the durable snapshot will
 * have empty blocks and `ocrStatus = RUNNING` instead of `READY`.
 */
internal suspend fun publishPreparedPageFromOcr(
    store: ChapterTranslationStore,
    pageKey: String,
    ocrResult: PageTranslation,
    chapterId: Long?,
    mangaId: Long,
    sourceId: Long,
): PreparedPage {
    store.updatePage(pageKey) { existing ->
        (existing ?: ocrResult).apply {
            sourceFileName = ocrResult.sourceFileName
            blocks = ocrResult.blocks
            allTextDetections = ocrResult.allTextDetections
            inpaintMaskBoxes = ocrResult.inpaintMaskBoxes
            ocrStatus = ocrResult.ocrStatus
            inpaintStatus = ocrResult.inpaintStatus
            renderStatus = ocrResult.renderStatus
            recognitionEngine = ocrResult.recognitionEngine
            decodeSampleSize = ocrResult.decodeSampleSize
            originalImgWidth = ocrResult.originalImgWidth
            originalImgHeight = ocrResult.originalImgHeight
            imgWidth = ocrResult.imgWidth
            imgHeight = ocrResult.imgHeight
            inpaintingModeUsed = ocrResult.inpaintingModeUsed
            cleanedImageName = ocrResult.cleanedImageName ?: cleanedImageName
            inpaintRevision = ocrResult.inpaintRevision
            updatedAt = System.currentTimeMillis()
        }
    }

    try {
        ocrResult.cleanedBitmap?.recycle()
    } catch (_: Exception) {}
    ocrResult.cleanedBitmap = null

    val snapshot = store.snapshot(pageKey)
    val durablePage = store.state.value[pageKey]
    return PreparedPage(
        pageKey = pageKey,
        chapterId = chapterId,
        mangaId = mangaId,
        sourceId = sourceId,
        cleanedImageName = durablePage?.cleanedImageName,
        generation = snapshot.generation,
        pageVersion = snapshot.pageVersion,
        blockFingerprints = snapshot.blockFingerprints,
        isTerminal = durablePage != null && isPreparedPageTerminal(durablePage),
    )
}

/**
 * Production terminal-classification predicate shared by the fresh and resume
 * branches of the prepared-page boundary. A page is terminal when it has a
 * rendered result (READY or SKIPPED) OR is genuinely textless (inpaint TEXTLESS,
 * or OCR READY with no detected blocks). Failed pages are never terminal —
 * [TranslationPipeline.prepareSinglePage] returns null for them so failure
 * cannot be mistaken for a prepared page.
 */
internal fun isPreparedPageTerminal(page: PageTranslation): Boolean {
    val failed = page.ocrStatus == StageStatus.FAILED ||
        page.inpaintStatus == StageStatus.FAILED ||
        page.translationStatus == StageStatus.FAILED
    if (failed) return false
    val rendered = page.renderStatus == StageStatus.READY || page.renderStatus == StageStatus.SKIPPED
    val textless = page.inpaintStatus == StageStatus.TEXTLESS ||
        (page.blocks.isEmpty() && page.ocrStatus == StageStatus.READY)
    return rendered || textless
}
