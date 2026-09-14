package eu.kanade.translation.coexistence

import eu.kanade.translation.model.StageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class ManualRenderProbeBaseline {
    @Test
    fun `simple manual tap renders under artifact authority (baseline)`() = runBlocking<Unit> {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            storeOverride = TranslationCoexistenceHarness.artifactAuthorityStore(listOf("p0"), preRegisterInStore = false),
            preRegisterInStore = false,
        )
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        h.stubChapterPages(listOf("p0"))
        try {
            h.tapManual("p0")
            val job = h.capturedManualJob("p0")
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                h.store.state.first { it["p0"]?.renderStatus == StageStatus.READY }
            }
            println("PROBE: render READY reached")
        } finally {
            h.removeGraphicsShims()
            h.unstubChapterPages()
            h.close()
        }
    }
}
