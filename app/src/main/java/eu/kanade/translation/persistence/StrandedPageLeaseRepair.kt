package eu.kanade.translation.persistence

import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toDraft

/**
 * Reader stranded-page sweep repair (P0B writer fence).
 *
 * The sweep is reader-side AUTOMATIC maintenance: it acquires an AUTO page
 * lease, then repairs one stranded page. Acquisition and mutation are two
 * separate store operations, so a MANUAL reader tap can preempt the sweep in
 * between (MANUAL is the one cross-origin eviction â€” see
 * [PageStageLeaseTable]). The mutation must therefore fence on the EXACT
 * evidence captured at acquisition â€” token, generation, page version and
 * candidate identity from the granted [PageStageLease] â€” never on a fresh
 * snapshot taken at mutation time. A fresh snapshot after a MANUAL eviction
 * would carry the replacement MANUAL owner's token/version and admit the
 * stale AUTO write with borrowed authority, flipping MANUAL's in-flight
 * stages to CANCELLED or mutating its freshly published state.
 *
 * With the acquired-evidence fence:
 *  - a preempted sweep's write is rejected fail-closed (lease token or page
 *    version no longer matches the acquisition), and the token-fenced release
 *    removes only the acquired AUTO record â€” never the MANUAL owner's lease;
 *  - an uncontested sweep (no preemption) still repairs the stranded page;
 *  - denial/cancellation/failure releases nothing it did not acquire.
 */
internal const val STRANDED_PAGE_SWEEP_DESCRIPTION = "reader stranded-page sweep"

/**
 * The sweep's repair mutation for one stranded page, applied under whatever
 * write fence the caller holds. Flips every non-terminal stage of the current
 * page state to CANCELLED. Never touches a page that already produced a
 * result (rendered/cleaned) or already reached FAILED. A page from another
 * run generation is left unchanged (its owning run, not the sweep, decides).
 * [stranded] is the sweep-start snapshot used only when the page vanished.
 */
internal fun strandedSweepPageUpdate(
    store: ChapterTranslationStore,
    stranded: PageTranslation,
): (PageTranslation?) -> PageTranslation {
    fun update(existing: PageTranslation?): PageTranslation {
        val safe = existing ?: return stranded.toDraft()
        if (safe.runGeneration != store.currentGeneration) return safe
        val safeTerminal = safe.ocrStatus == StageStatus.FAILED ||
            safe.inpaintStatus == StageStatus.FAILED ||
            safe.translationStatus == StageStatus.FAILED ||
            safe.renderStatus == StageStatus.FAILED ||
            safe.displayImageName != null ||
            safe.isCleanedImageReady ||
            safe.isTextlessTerminal
        if (safeTerminal) return safe
        return safe.apply {
            if (ocrStatus == StageStatus.RUNNING || ocrStatus == StageStatus.PENDING) {
                ocrStatus = StageStatus.CANCELLED
            }
            if (inpaintStatus == StageStatus.RUNNING || inpaintStatus == StageStatus.PENDING) {
                inpaintStatus = StageStatus.CANCELLED
            }
            if (translationStatus == StageStatus.RUNNING || translationStatus == StageStatus.PENDING) {
                translationStatus = StageStatus.CANCELLED
            }
            if (renderStatus == StageStatus.RUNNING || renderStatus == StageStatus.PENDING) {
                renderStatus = StageStatus.CANCELLED
            }
            errorMessage = activeError ?: "Page was stranded mid-translation; reset as cancelled on chapter reopen"
            updatedAt = System.currentTimeMillis()
        }
    }
    return ::update
}

/**
 * The sweep's AUTO lease acquisition (traced as READER_STRANDED_SWEEP).
 * Returns the granted lease, or null when denied (another origin owns the
 * page and AUTO never preempts).
 */
internal suspend fun acquireStrandedSweepLease(
    store: ChapterTranslationStore,
    pageKey: String,
): PageStageLease? {
    val acquisition = TranslationTrace.withLeaseWait(
        site = TranslationTraceSite.READER_STRANDED_SWEEP,
        leaseKind = TranslationTraceLeaseKind.OCR,
    ) {
        store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.AUTO)
    }
    return (acquisition as? LeaseAcquisition.Granted)?.lease
}

/**
 * The resumed half of the sweep: mutates [pageKey] fencing on the EXACT
 * [lease] evidence captured at acquisition, then releases only that acquired
 * token. Returns
 * true only when the guarded write was accepted.
 */
internal suspend fun mutatePageUnderAcquiredSweepLease(
    store: ChapterTranslationStore,
    pageKey: String,
    lease: PageStageLease,
    update: (PageTranslation?) -> PageTranslation,
): Boolean {
    try {
        val expected = ChapterTranslationStore.PatchPrecondition(
            generation = lease.generation,
            pageVersion = lease.pageVersion,
            leaseToken = lease.token,
            candidateGenerationId = lease.candidateGenerationId,
            dependencyFingerprint = lease.dependencyFingerprint,
            artifactPageVersion = lease.artifactPageVersion,
        )
        return store.updatePageGuarded(pageKey, expected, STRANDED_PAGE_SWEEP_DESCRIPTION, update) is
            ChapterTranslationStore.PatchResult.Accepted
    } finally {
        // Token-fenced release removes only the lease acquired by this sweep.
        store.releasePageStageLease(pageKey, PageWriteOrigin.AUTO, lease.token)
    }
}

/**
 * Full single-page sweep repair: acquire AUTO, mutate fenced on the acquired
 * evidence, release only the acquired token. Returns true when the page was
 * repaired.
 */
internal suspend fun repairStrandedPageWithAcquiredAutoLease(
    store: ChapterTranslationStore,
    pageKey: String,
    stranded: PageTranslation,
): Boolean {
    val lease = acquireStrandedSweepLease(store, pageKey) ?: return false
    return mutatePageUnderAcquiredSweepLease(
        store = store,
        pageKey = pageKey,
        lease = lease,
        update = strandedSweepPageUpdate(store, stranded),
    )
}
