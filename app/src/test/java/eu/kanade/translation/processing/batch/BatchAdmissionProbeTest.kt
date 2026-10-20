package eu.kanade.translation.processing.batch

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchAdmissionProbeTest {
    @Test
    fun `probe distinguishes complete partial and unknown totals`() {
        val pages = listOf(Page(0), Page(1))

        BatchAdmissionProbe.evaluate(2, pages) shouldBe BatchAdmissionDecision.Complete
        BatchAdmissionProbe.evaluate(1, pages) shouldBe BatchAdmissionDecision.Partial(
            expectedSourcePageCount = 2,
            downloadedPageCount = 1,
        )
        BatchAdmissionProbe.evaluate(1, null) shouldBe BatchAdmissionDecision.UnknownCount
        BatchAdmissionProbe.evaluate(1, emptyList()) shouldBe BatchAdmissionDecision.UnknownCount
    }

    @Test
    fun `routing preserves explicit partial download choices`() {
        val partial = BatchAdmissionDecision.Partial(expectedSourcePageCount = 2, downloadedPageCount = 1)

        BatchAdmissionRouting.route(partial, null) shouldBe BatchAdmissionRouting.Route.AskUser
        BatchAdmissionRouting.route(partial, PartialDownloadChoice.FINISH_DOWNLOAD_FIRST) shouldBe
            BatchAdmissionRouting.Route.WaitForDownload
        BatchAdmissionRouting.route(partial, PartialDownloadChoice.TRANSLATE_WHAT_EXISTS) shouldBe
            BatchAdmissionRouting.Route.AdmitSubset
        BatchAdmissionRouting.route(BatchAdmissionDecision.Complete, null) shouldBe
            BatchAdmissionRouting.Route.AdmitBatch
    }
}
