package eu.kanade.translation.persistence.chapter

import com.hippo.unifile.FakeUniFile
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterDocumentIo
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class StoreArtifactRetentionCloseTest {

    @Test
    fun `closeAndFlush joins a retention crawl before returning`(@TempDir tempDir: Path) {
        runTest {
            val layout = ChapterArtifactLayout("Chapter 1")
            val io = BlockingRetentionIo(FakeChapterDocumentIo())
            val engine = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
            val store = ChapterTranslationStore(
                artifactParentResolver = null,
                artifactStore = engine,
                initialArtifactManifest = ChapterArtifactManifest(chapterKey = layout.chapterKey),
                artifactParent = FakeUniFile(parent = null, backing = tempDir.toFile()),
                persistenceDispatcher = Dispatchers.IO,
            )

            try {
                store.reconcileArtifactRetentionAsync()
                io.awaitCrawlStart()

                val closeBarrier = async(start = CoroutineStart.UNDISPATCHED) {
                    store.closeAndFlush()
                }
                // The crawl is held inside list(); closeAndFlush must still be
                // suspended in the artifact-engine retention join until the crawl exits.
                closeBarrier.isCompleted shouldBe false

                io.releaseCrawl()
                closeBarrier.await()
                store.hasActivePersistenceChildrenForTests() shouldBe false
            } finally {
                io.releaseCrawl()
                store.closeAndFlush()
            }
        }
    }

    private class BlockingRetentionIo(
        private val delegate: ChapterDocumentIo,
    ) : ChapterDocumentIo by delegate {
        private val firstList = AtomicBoolean(true)
        private val crawlStarted = CompletableDeferred<Unit>()
        private val releaseCrawl = CountDownLatch(1)

        override fun list(directoryName: String): List<String>? {
            if (firstList.compareAndSet(true, false)) {
                crawlStarted.complete(Unit)
                releaseCrawl.await()
            }
            return delegate.list(directoryName)
        }

        suspend fun awaitCrawlStart() = crawlStarted.await()

        fun releaseCrawl() {
            releaseCrawl.countDown()
        }
    }
}
