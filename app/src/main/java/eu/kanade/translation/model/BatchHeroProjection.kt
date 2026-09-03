package eu.kanade.translation.model

import eu.kanade.tachiyomi.data.download.model.Download

/**
 * T911 slice 1: phase-aware hero projection for the manga-screen batch drawer.
 *
 * The batch snapshot only knows translation pages; while the work is still
 * owned by the pending request / downloader (accepted, waiting for download,
 * downloading, finalizing, queued, pre-registration), no real translation page
 * total exists. Rendering that unknown total as `0%` / `0/0` is misleading, so
 * this pure mapping decides whether the drawer hero shows real numeric
 * progress or a phase label — optionally joined with the chapter's live
 * download state/progress (read-only display join; the downloader never owns
 * translation state).
 */
enum class BatchHeroPhase {
    /** Request acknowledged; no work owner yet. */
    ACCEPTED,

    /** Chapter is not downloaded yet; the batch waits for the downloader. */
    WAITING_FOR_DOWNLOAD,

    /** The downloader is actively fetching the chapter; fraction is download progress. */
    DOWNLOADING,

    /** The download failed; the batch cannot proceed until retried. */
    DOWNLOAD_FAILED,

    /** Preparing: handoff/admission or page pre-registration is in progress. */
    PREPARING,

    /** Admitted to the translation queue; tracker not built yet. */
    QUEUED,

    /** Batch explicitly paused with no page totals known yet. */
    PAUSED,

    /** Finalizing a batch whose totals were never registered. */
    FINALIZING,

    /** Terminal batch without page data and without failures. */
    COMPLETED,

    /** Terminal failure of a chapter that has no translation pages at all. */
    FAILED_NO_PAGES,

    /** T911 slice 2: the download was cancelled/removed/cleared/stopped. */
    CANCELLED,

    /** T911 slice 2 (R10): the translation queue refused admission (not a download failure). */
    ADMISSION_FAILED,

    /**
     * T917 Phase 5 (spec §2.1/D10): real committed pages exist, but the page
     * set is not the trusted source total (partial download). The available
     * count must never render as a percentage or a complete chapter.
     */
    UNKNOWN_TOTAL,
}

sealed interface BatchHeroProjection {

    /**
     * Real translation totals exist — render the classic numeric hero
     * (percent + "Page x of y"). A zero-page failure is never numeric: it is a
     * distinct error phase below.
     */
    data class Numeric(
        val fraction: Float,
        val donePages: Int,
        val totalPages: Int,
        val isError: Boolean,
    ) : BatchHeroProjection

    /**
     * No real translation total exists — render the owning phase with either a
     * determinate [fraction] (download percent) or an indeterminate bar, and
     * no fake page counts.
     */
    data class Phase(
        val phase: BatchHeroPhase,
        val fraction: Float? = null,
        val isError: Boolean = false,
        val donePages: Int? = null,
        val totalPages: Int? = null,
    ) : BatchHeroProjection

    companion object {

        fun of(
            snapshot: TranslationProgressSnapshot,
            downloadState: Download.State? = null,
            downloadProgress: Int = 0,
            downloadedPages: Int? = null,
            totalDownloadPages: Int? = null,
        ): BatchHeroProjection {
            val request = snapshot.requestState
            val isTerminal = snapshot.aborted || snapshot.batchPhase == TranslationBatchPhase.FINISHED
            val hasRealTotals =
                snapshot.totalPages > 0 || snapshot.totalStages > 0 || snapshot.pages.isNotEmpty()
            val isErrorState = snapshot.state == Translation.State.ERROR ||
                snapshot.aborted ||
                (snapshot.batchPhase == TranslationBatchPhase.FINISHED && snapshot.failedCount > 0)

            // A real zero-page failure is a distinct error state, never an
            // unknown-total phase and never a numeric 0/0.
            if (isErrorState && !hasRealTotals) {
                return Phase(phase = BatchHeroPhase.FAILED_NO_PAGES, isError = true)
            }

            // Real translation totals stay numeric (terminal outcome included)
            // — but only when the page set is the trusted source total. A
            // partially downloaded chapter's available pages are documented
            // absence, never a fabricated complete chapter (D10).
            if (hasRealTotals) {
                if (!snapshot.expectedPageCountTrusted) {
                    return Phase(
                        phase = BatchHeroPhase.UNKNOWN_TOTAL,
                        donePages = snapshot.terminalPages,
                    )
                }
                return Numeric(
                    fraction = snapshot.fraction.coerceIn(0f, 1f),
                    donePages = snapshot.donePages,
                    totalPages = snapshot.totalPages,
                    isError = isErrorState,
                )
            }

            // Terminal batch without page data and without failures: show the
            // completed phase, not a numeric 0/0.
            if (isTerminal) {
                return Phase(phase = BatchHeroPhase.COMPLETED)
            }

            // Unknown translation total: show the owning phase, never 0/0.
            if (request != null) {
                return when (request.phase) {
                    TranslationRequestPhase.DOWNLOAD_FAILED ->
                        Phase(phase = BatchHeroPhase.DOWNLOAD_FAILED, isError = true)
                    TranslationRequestPhase.CANCELLED ->
                        Phase(phase = BatchHeroPhase.CANCELLED, isError = true)
                    TranslationRequestPhase.ADMISSION_FAILED ->
                        Phase(phase = BatchHeroPhase.ADMISSION_FAILED, isError = true)
                    TranslationRequestPhase.STARTING -> Phase(phase = BatchHeroPhase.ACCEPTED)
                    TranslationRequestPhase.PREPARING -> Phase(phase = BatchHeroPhase.PREPARING)
                    TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> when (downloadState) {
                        Download.State.DOWNLOADING -> Phase(
                            phase = BatchHeroPhase.DOWNLOADING,
                            fraction = downloadProgress.coerceIn(0, 100) / 100f,
                            donePages = downloadedPages,
                            totalPages = totalDownloadPages,
                        )
                        Download.State.ERROR ->
                            Phase(phase = BatchHeroPhase.DOWNLOAD_FAILED, isError = true)
                        // The downloader finished; the request has not been admitted
                        // yet (finalization/rekey/handoff window).
                        Download.State.DOWNLOADED -> Phase(phase = BatchHeroPhase.PREPARING)
                        Download.State.QUEUE, Download.State.NOT_DOWNLOADED, null ->
                            Phase(phase = BatchHeroPhase.WAITING_FOR_DOWNLOAD)
                    }
                }
            }

            return when (snapshot.state) {
                Translation.State.QUEUE -> Phase(phase = BatchHeroPhase.QUEUED)
                Translation.State.TRANSLATING ->
                    if (snapshot.batchPhase == TranslationBatchPhase.FINALIZING) {
                        Phase(phase = BatchHeroPhase.FINALIZING)
                    } else {
                        // Tracker not built yet (pre-registration window).
                        Phase(phase = BatchHeroPhase.PREPARING)
                    }
                Translation.State.PAUSED -> Phase(phase = BatchHeroPhase.PAUSED)
                Translation.State.ERROR -> Phase(phase = BatchHeroPhase.FAILED_NO_PAGES, isError = true)
                // The drawer only opens in a translation context; before the first
                // canonical emission arrives, show the accepted phase instead of 0/0.
                else -> Phase(phase = BatchHeroPhase.ACCEPTED)
            }
        }
    }
}
