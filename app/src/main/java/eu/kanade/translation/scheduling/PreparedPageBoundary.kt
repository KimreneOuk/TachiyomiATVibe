package eu.kanade.translation.scheduling

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.ocrBlockFingerprints
import logcat.LogPriority
import logcat.logcat

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
 * the durable write below will fail because the durable snapshot will have
 * empty blocks and `ocrStatus = RUNNING` instead of `READY`.
 *
 * Phase 3: the persist is a preconditioned detection/OCR stage merge carrying
 * the page version observed before the native pass, so a stale worker cannot
 * clobber a page a newer writer owns. A rejection is logged and the durable
 * winner's snapshot is returned — the reader observes the winner's progress.
 */
internal suspend fun publishPreparedPageFromOcr(
    store: ChapterTranslationStore,
    pageKey: String,
    ocrResult: PageTranslation,
    chapterId: Long?,
    mangaId: Long,
    sourceId: Long,
    expectedPageVersion: Long,
    expectedGeneration: Long,
    expectedLeaseToken: Long? = null,
): PreparedPage {
    val prior = store.snapshot(pageKey)
    val merge = store.mergeOcr(
        OcrStagePatch(
            pageKey = pageKey,
            generation = expectedGeneration,
            expectedPageVersion = expectedPageVersion,
            expectedPriorOcrFingerprints = prior.page?.ocrBlockFingerprints().orEmpty(),
            ocrResult = ocrResult,
            expectedLeaseToken = expectedLeaseToken,
            expectedCandidateGenerationId = prior.candidateGenerationId,
            expectedDependencyFingerprint = prior.dependencyFingerprint,
            expectedArtifactPageVersion = prior.artifactPageVersion,
        ),
        description = "prepared-page detection/ocr persist",
    )
    if (merge is StagePatchResult.Rejected) {
        logcat(tag = "PreparedPageBoundary", priority = LogPriority.WARN) {
            "TachiyomiAT prepared-page OCR persist rejected (stale writer): " +
                "pageKey=$pageKey reason=${merge.reason}"
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
