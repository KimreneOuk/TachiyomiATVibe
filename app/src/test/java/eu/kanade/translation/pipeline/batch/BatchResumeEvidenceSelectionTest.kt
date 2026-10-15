package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.InputStream

/**
 * Regression coverage for the production fingerprint-evidence selection that
 * feeds the synchronous resume planner: the selection must read the CURRENT
 * store state at invocation time (after a real alias/rekey), return keys in
 * canonical orderedStreams order, and select nothing for a pristine chapter.
 */
class BatchResumeEvidenceSelectionTest {

    private fun store(vararg keys: String): ChapterTranslationStore = ChapterTranslationStore(
        artifactParentResolver = null,
        initialPages = keys.associateWith { PageTranslation(sourceFileName = it) },
    )

    private fun orderedStreams(vararg keys: String): List<Pair<String, () -> InputStream>> =
        keys.map { key ->
            key to { throw UnsupportedOperationException("streams are not opened by selection") }
        }

    @Test
    fun `evidence selection reads current store state after real legacy rekey`() = runTest {
        val s = store("https://legacy/1.jpg", "https://legacy/2.jpg")
        val streams = orderedStreams("page_1", "page_2")

        val outcome = s.rekeyPages(
            onlineKeys = listOf("https://legacy/1.jpg", "https://legacy/2.jpg"),
            onDiskKeys = listOf("page_1", "page_2"),
        )
        outcome shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            listOf(
                "https://legacy/1.jpg" to "page_1",
                "https://legacy/2.jpg" to "page_2",
            ),
            0,
        )
        // The rekey really moved the persisted pages onto the canonical keys.
        s.state.value.keys shouldBe setOf("page_1", "page_2")

        // The production selection runs AFTER the rekey block and reads the
        // store state at invocation time: the rekeyed natural keys must be
        // selected, which a pre-rekey snapshot cannot do.
        persistedEvidencePageKeys(s, streams) shouldBe listOf("page_1", "page_2")
    }

    @Test
    fun `evidence selection returns canonical stream order regardless of persisted insertion order`() = runTest {
        val s = store("page_3", "page_1", "page_2")
        val streams = orderedStreams("page_1", "page_2", "page_3")

        persistedEvidencePageKeys(s, streams) shouldBe listOf("page_1", "page_2", "page_3")
    }

    @Test
    fun `pristine chapter selects no evidence keys`() = runTest {
        val s = store()
        val streams = orderedStreams("page_1", "page_2")

        persistedEvidencePageKeys(s, streams) shouldBe emptyList()
    }
}
