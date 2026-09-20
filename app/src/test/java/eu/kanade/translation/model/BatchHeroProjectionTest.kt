package eu.kanade.translation.model

import eu.kanade.tachiyomi.data.download.model.Download
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  slice 1: the batch drawer hero must never render an unknown translation
 * total as 0% / 0/0. While the work is owned by the pending request/downloader,
 * the projection maps the owning phase — joined with the chapter's live
 * download state/progress — instead of numeric zero. A real zero-page failure
 * stays a distinct error phase.
 */
class BatchHeroProjectionTest {

    private fun snapshot(
        state: Translation.State = Translation.State.NOT_TRANSLATED,
        requestPhase: TranslationRequestPhase? = null,
        donePages: Int = 0,
        totalPages: Int = 0,
        totalStages: Int = 0,
        batchPhase: TranslationBatchPhase = TranslationBatchPhase.IDLE,
        failedCount: Int = 0,
        aborted: Boolean = false,
    ): TranslationProgressSnapshot {
        val base = TranslationProgressSnapshot.empty(1L, state)
            .copy(
                donePages = donePages,
                totalPages = totalPages,
                totalStages = totalStages,
                batchPhase = batchPhase,
                failedCount = failedCount,
                aborted = aborted,
                //  Phase 5: these fixtures model REGISTERED batch totals —
                // a trusted page set. Untrusted (partial-download) totals have
                // their own unknown-total phase now.
                expectedPageCountTrusted = true,
            )
        return if (requestPhase == null) {
            base
        } else {
            base.copy(requestState = TranslationRequestState(chapterId = 1L, phase = requestPhase))
        }
    }

    @Test
    fun `waiting request with an actively downloading chapter exposes the download phase and progress`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(requestPhase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
            downloadState = Download.State.DOWNLOADING,
            downloadProgress = 45,
            downloadedPages = 9,
            totalDownloadPages = 20,
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.DOWNLOADING, hero.phase)
        assertEquals(0.45f, hero.fraction)
        assertEquals(9, hero.donePages)
        assertEquals(20, hero.totalPages)
        assertFalse(hero.isError)
    }

    @Test
    fun `waiting request with a queued download stays an indeterminate waiting phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(requestPhase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
            downloadState = Download.State.QUEUE,
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.WAITING_FOR_DOWNLOAD, hero.phase)
        assertNull(hero.fraction)
        assertNull(hero.donePages)
        assertNull(hero.totalPages)
    }

    @Test
    fun `waiting request with a failed download renders a distinct download-failed phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(requestPhase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
            downloadState = Download.State.ERROR,
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.DOWNLOAD_FAILED, hero.phase)
        assertTrue(hero.isError)
    }

    @Test
    fun `waiting request over finished files reports preparing during the handoff window`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(requestPhase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
            downloadState = Download.State.DOWNLOADED,
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.PREPARING, hero.phase)
    }

    @Test
    fun `accepted request shows the accepted phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(requestPhase = TranslationRequestPhase.STARTING),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.ACCEPTED, hero.phase)
        assertNull(hero.fraction)
    }

    @Test
    fun `preparing request shows the preparing phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(requestPhase = TranslationRequestPhase.PREPARING),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.PREPARING, hero.phase)
    }

    @Test
    fun `explicit download-failed request phase is an error`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(requestPhase = TranslationRequestPhase.DOWNLOAD_FAILED),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.DOWNLOAD_FAILED, hero.phase)
        assertTrue(hero.isError)
    }

    @Test
    fun `queued translation without tracker totals shows the queued phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(state = Translation.State.QUEUE),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.QUEUED, hero.phase)
        assertNull(hero.fraction)
    }

    @Test
    fun `translating without registered totals shows the preparing phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(state = Translation.State.TRANSLATING),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.PREPARING, hero.phase)
    }

    @Test
    fun `finalizing without registered totals shows the finalizing phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(
                state = Translation.State.TRANSLATING,
                batchPhase = TranslationBatchPhase.FINALIZING,
            ),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.FINALIZING, hero.phase)
    }

    @Test
    fun `paused without totals shows the paused phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(state = Translation.State.PAUSED),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.PAUSED, hero.phase)
    }

    @Test
    fun `real translation totals render numerically`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(
                state = Translation.State.TRANSLATING,
                donePages = 12,
                totalPages = 40,
                totalStages = 160,
            ),
        )

        assertTrue(hero is BatchHeroProjection.Numeric)
        hero as BatchHeroProjection.Numeric
        assertEquals(12, hero.donePages)
        assertEquals(40, hero.totalPages)
        assertFalse(hero.isError)
    }

    @Test
    fun `real zero-page failure stays a distinct error phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(
                state = Translation.State.ERROR,
                batchPhase = TranslationBatchPhase.FINISHED,
                failedCount = 1,
                totalPages = 0,
            ),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.FAILED_NO_PAGES, hero.phase)
        assertTrue(hero.isError)
        assertNull(hero.fraction)
    }

    @Test
    fun `aborted batch without totals is an error phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(aborted = true),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.FAILED_NO_PAGES, hero.phase)
        assertTrue(hero.isError)
    }

    @Test
    fun `terminal batch without totals and failures is completed without numeric zero`() {
        val hero = BatchHeroProjection.of(
            snapshot = snapshot(
                state = Translation.State.TRANSLATED,
                batchPhase = TranslationBatchPhase.FINISHED,
                totalPages = 0,
            ),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.COMPLETED, hero.phase)
        assertFalse(hero.isError)
    }

    @Test
    fun `a drawer opened before the first canonical emission shows the accepted phase`() {
        val hero = BatchHeroProjection.of(
            snapshot = TranslationProgressSnapshot.empty(1L),
        )

        assertTrue(hero is BatchHeroProjection.Phase)
        hero as BatchHeroProjection.Phase
        assertEquals(BatchHeroPhase.ACCEPTED, hero.phase)
        assertNull(hero.fraction)
        assertNull(hero.donePages)
        assertNull(hero.totalPages)
    }
}
