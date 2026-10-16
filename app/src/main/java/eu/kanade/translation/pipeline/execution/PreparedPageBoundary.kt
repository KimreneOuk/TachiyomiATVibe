package eu.kanade.translation.pipeline.execution

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.OcrStagePatch
import eu.kanade.translation.persistence.chapter.StagePatchResult
import eu.kanade.translation.persistence.chapter.mergeOcrFields
import eu.kanade.translation.persistence.chapter.ocrBlockFingerprints
import eu.kanade.translation.pipeline.AttemptAuthority
import eu.kanade.translation.pipeline.CleanedOwner
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/**
 * Persists the in-memory OCR result onto [store] via a preconditioned OCR merge
 * (stale worker cannot clobber a newer writer; rejection logged, winner returned),
 * releases the cleaned bitmap, and builds [PreparedPage] from the durable snapshot.
 * Top-level so pure-JVM tests drive the real durability write.
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
    owner: CleanedOwner? = null,
    attempt: AttemptAuthority? = null,
): PreparedPage {
    val rejectionReason = if (attempt != null) {
        when (
            val result = attempt.write("prepared-page detection/ocr persist") { current ->
                mergeOcrFields(current, ocrResult)
            }
        ) {
            is ChapterTranslationStore.PatchResult.Accepted -> null
            is ChapterTranslationStore.PatchResult.Rejected -> result.reason
        }
    } else {
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
        (merge as? StagePatchResult.Rejected)?.reason
    }
    if (rejectionReason != null) {
        logcat(tag = "PreparedPageBoundary", priority = LogPriority.WARN) {
            "TachiyomiAT prepared-page OCR persist rejected (stale writer): " +
                "pageKey=$pageKey reason=$rejectionReason"
        }
    }

    // Flush queued image writes before releasing owned pixels, using the same
    // durability barrier as reader rendering.
    if (store.isLazyPersistenceEnabled()) {
        store.flush()
    }
    if (owner != null) {
        owner.release()
    } else {
        try {
            ocrResult.cleanedBitmap?.recycle()
        } catch (e: Exception) {
            logcat(tag = "PreparedPageBoundary", priority = LogPriority.ERROR) {
                "Cleaned bitmap recycle failed at prepared boundary pageKey=$pageKey\n${e.asLog()}"
            }
        }
    }
    ocrResult.cleanedBitmap = null

    val snapshot = store.snapshot(pageKey)
    val durablePage = store.state.value[pageKey]
    val lazyDurable = if (store.isLazyPersistenceEnabled()) {
        store.mutex.withLock {
            store.isLazyGenerationCurrent(snapshot.generation) &&
                !store.hasPendingLazyPersistenceLocked()
        }
    } else {
        true
    }
    return PreparedPage(
        pageKey = pageKey,
        chapterId = chapterId,
        mangaId = mangaId,
        sourceId = sourceId,
        cleanedImageName = durablePage?.cleanedImageName,
        cleanedImageContentHash = durablePage?.cleanedImageContentHash,
        generation = snapshot.generation,
        pageVersion = snapshot.pageVersion,
        blockFingerprints = snapshot.blockFingerprints,
        isTerminal = lazyDurable && durablePage != null && isPreparedPageTerminal(durablePage),
    )
}

/**
 * Failed pages remain retryable. Rendered or genuinely textless pages are terminal;
 * masked textless pages also require completed inpainting.
 */
internal fun isPreparedPageTerminal(page: PageTranslationView): Boolean {
    val failed = page.ocrStatus == StageStatus.FAILED ||
        page.inpaintStatus == StageStatus.FAILED ||
        page.translationStatus == StageStatus.FAILED ||
        page.renderStatus == StageStatus.FAILED
    if (failed) return false
    return page.hasRenderedResult ||
        (
            page.isTextlessTerminal &&
                (page.inpaintMaskBoxes.isEmpty() || page.inpaintStatus == StageStatus.READY)
            )
}
