package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.CandidateGenerationMetadata
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterDocumentIo
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.GroupCommitConfiguration
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.internal.StorePersistenceScheduler
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

class PageSnapshotLostUpdateTest {

    private fun persistenceScheduler(store: ChapterTranslationStore): Any =
        ChapterTranslationStore::class.java.getDeclaredField("persistenceScheduler").apply {
            isAccessible = true
        }.get(store)

    private suspend fun stagedDebounceJob(store: ChapterTranslationStore): Job? =
        store.mutex.withLock {
            StorePersistenceScheduler::class.java.getDeclaredField("stagedDebounceJob").apply {
                isAccessible = true
            }.get(persistenceScheduler(store)) as? Job
        }

    @Test
    fun hydration_serializes_with_writers_and_preserves_the_newer_page_state() = runTest {
        val pageKey = "page-1.jpg"
        val layout = ChapterArtifactLayout("snapshot-lost-update")
        val snapshotName = layout.candidatePageSnapshotFile(pageKey, "generation-1")
        val staleSnapshot = pageSnapshot(pageKey)
        val backingIo = FakeChapterDocumentIo()
        AtomicChapterDocuments(backingIo).publishJson(
            snapshotName,
            staleSnapshot,
        ) shouldBe true

        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val blockingIo = BlockingSnapshotReadIo(backingIo, snapshotName, readStarted, releaseRead)
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                pageKey to PageArtifactRecord(
                    pageKey = pageKey,
                    candidate = CandidateGenerationMetadata(
                        generationId = "generation-1",
                        dependencyFingerprint = StageFingerprints.pageSnapshot(staleSnapshot),
                        pageSnapshotFileName = snapshotName,
                    ),
                ),
            ),
        )
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf(pageKey to PageTranslation(sourceFileName = pageKey)),
            artifactStore = ChapterArtifactEngine(AtomicChapterDocuments(blockingIo), layout),
            initialArtifactManifest = manifest,
        )
        val previousGroupCommit = GroupCommitConfiguration.enabled
        GroupCommitConfiguration.enabled = true
        var hydration: kotlinx.coroutines.Deferred<PageTranslation?>? = null
        var writer: kotlinx.coroutines.Deferred<Unit>? = null

        try {
            hydration = async(Dispatchers.IO) { store.getOrLoadPageSnapshot(pageKey) }
            withContext(Dispatchers.IO) { readStarted.await() }

            // The read signal fires inside the sidecar read, so the held mutex proves that
            // hydration owns the lock while durable storage is blocked.
            store.mutex.isLocked shouldBe true
            writer = async(start = CoroutineStart.UNDISPATCHED) {
                store.updatePage(pageKey) { current ->
                    current!!.copy(inpaintStatus = StageStatus.RUNNING)
                }
            }
            writer.isCompleted shouldBe false

            releaseRead.countDown()
            hydration.await()?.ocrStatus shouldBe StageStatus.READY
            writer.await()
            store.state.value.getValue(pageKey).ocrStatus shouldBe StageStatus.READY
            store.state.value.getValue(pageKey).inpaintStatus shouldBe StageStatus.RUNNING
        } finally {
            releaseRead.countDown()
            writer?.cancelAndJoin()
            hydration?.cancelAndJoin()
            val pendingStageFlush = stagedDebounceJob(store)
            store.markDefunct()
            pendingStageFlush?.join()
            store.closeAndFlush()
            GroupCommitConfiguration.enabled = previousGroupCommit
        }
    }

    private fun pageSnapshot(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        ocrStatus = StageStatus.READY,
    )

    private class BlockingSnapshotReadIo(
        private val delegate: ChapterDocumentIo,
        private val blockedName: String,
        private val readStarted: CountDownLatch,
        private val releaseRead: CountDownLatch,
    ) : ChapterDocumentIo by delegate {
        override fun read(name: String): ByteArray? {
            if (name == blockedName) {
                readStarted.countDown()
                releaseRead.await()
            }
            return delegate.read(name)
        }
    }
}
