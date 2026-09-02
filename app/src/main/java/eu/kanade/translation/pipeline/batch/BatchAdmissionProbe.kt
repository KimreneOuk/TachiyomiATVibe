package eu.kanade.translation.pipeline.batch

import eu.kanade.tachiyomi.source.model.Page

/**
 * T917 Phase 4 (D10, phase4-design §3.2): admission decision for a chapter
 * whose download directory exists. The trigger resolves the chapter's
 * `Download` from `downloadManager.queueState` (the list the UI already
 * observes — no network) and passes the cross-check here; the probe is pure
 * and store-free.
 *
 * `evaluate` has exactly three outcomes:
 *  - [BatchAdmissionDecision.Complete] — the fetched source page list is
 *    present and matches the on-disk page count;
 *  - [BatchAdmissionDecision.Partial] — mismatch: the delta is carried, never
 *    invented;
 *  - [BatchAdmissionDecision.UnknownCount] — no fetched source page list
 *    (a null list, or an empty one: a Download with no fetched pages is not a
 *    trustworthy known-total-of-zero — design §3.5 risk table).
 */
sealed class BatchAdmissionDecision {
    data object Complete : BatchAdmissionDecision()

    data class Partial(
        val expectedSourcePageCount: Int,
        val downloadedPageCount: Int,
    ) : BatchAdmissionDecision()

    data object UnknownCount : BatchAdmissionDecision()
}

internal object BatchAdmissionProbe {

    /**
     * Cross-checks the on-disk page count against the (possibly absent)
     * fetched source page list. A `pages.size == downloadedPageCount` match is
     * [BatchAdmissionDecision.Complete]; a mismatch is
     * [BatchAdmissionDecision.Partial] carrying the SOURCE total and the
     * downloaded count; an absent or empty list degrades to
     * [BatchAdmissionDecision.UnknownCount].
     */
    fun evaluate(
        downloadedPageCount: Int,
        sourcePageList: List<Page>?,
    ): BatchAdmissionDecision = when {
        sourcePageList.isNullOrEmpty() -> BatchAdmissionDecision.UnknownCount
        downloadedPageCount >= sourcePageList.size -> BatchAdmissionDecision.Complete
        else -> BatchAdmissionDecision.Partial(
            expectedSourcePageCount = sourcePageList.size,
            downloadedPageCount = downloadedPageCount,
        )
    }
}

/**
 * The PLAN-mandated partial-download choice, typed so the screen model's
 * dialog and the admission routing cannot drift apart (phase4-design §3.2;
 * the dialog's copy is Phase 5 / D13).
 */
enum class PartialDownloadChoice { FINISH_DOWNLOAD_FIRST, TRANSLATE_WHAT_EXISTS }

/**
 * Per-chapter probe outcome carried into queue admission (D10): the resolved
 * cross-check the queued [eu.kanade.translation.model.Translation] stamps so
 * pre-registration can record honest totals. An absent context
 * (`sourceCountKnown = false`) is the legacy no-cross-check path.
 */
data class BatchAdmissionContext(
    val probedSourcePageCount: Int?,
    val sourceCountKnown: Boolean,
)

internal object BatchAdmissionRouting {

    /** Where a probed chapter goes instead of a silent admission. */
    sealed class Route {
        /** Cross-check proved complete (or the probe was never needed): admit unchanged. */
        data object AdmitBatch : Route()

        /** Admit the found subset; the batch records its partial truth. */
        data object AdmitSubset : Route()

        /** The EXISTING fenced WAITING_FOR_DOWNLOAD path — zero batch work starts. */
        data object WaitForDownload : Route()

        /** Present the PLAN-mandated finish/translate choice for this chapter. */
        data object AskUser : Route()
    }

    fun route(
        decision: BatchAdmissionDecision,
        choice: PartialDownloadChoice?,
    ): Route = when (decision) {
        BatchAdmissionDecision.Complete -> Route.AdmitBatch
        is BatchAdmissionDecision.Partial -> when (choice) {
            PartialDownloadChoice.FINISH_DOWNLOAD_FIRST -> Route.WaitForDownload
            PartialDownloadChoice.TRANSLATE_WHAT_EXISTS -> Route.AdmitSubset
            null -> Route.AskUser
        }
        BatchAdmissionDecision.UnknownCount -> when (choice) {
            PartialDownloadChoice.FINISH_DOWNLOAD_FIRST -> Route.WaitForDownload
            PartialDownloadChoice.TRANSLATE_WHAT_EXISTS -> Route.AdmitSubset
            null -> Route.AskUser
        }
    }
}
