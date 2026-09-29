package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.ArtifactOrigin
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.CleanedImageProbe
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.GroupCommitConfiguration
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.ProbedImage
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefunctFlushWindowTest {

    private val layout = ChapterArtifactLayout("defunct-flush-window")

    private fun stagedDebounceJobField(store: ChapterTranslationStore) =
        ChapterTranslationStore::class.java.getDeclaredField("stagedDebounceJob").apply {
            isAccessible = true
        }

    private fun intermediatePage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.RUNNING,
        renderStatus = StageStatus.PENDING,
    )

    @Test
    fun `a delayed staged flush after eviction performs no artifact write`() = runTest {
        val previousFlag = GroupCommitConfiguration.enabled
        GroupCommitConfiguration.enabled = true
        var store: ChapterTranslationStore? = null
        var delayedFlush: Job? = null

        try {
            val pageKey = "p0.jpg"
            val io = FakeChapterDocumentIo().apply { fileBacked = true }
            val documents = AtomicChapterDocuments(io)
            val initialManifest = ChapterArtifactManifest(
                chapterKey = layout.chapterKey,
                pages = mapOf(pageKey to PageArtifactRecord(pageKey = pageKey)),
                updatedAtEpochMs = 1L,
            )
            documents.publishJson(layout.manifestFileName, initialManifest) shouldBe true
            val artifact = ChapterArtifactEngine(
                documents,
                layout,
                displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) },
            )
            val candidateManifest = artifact.openCandidate(
                manifest = initialManifest,
                pageKey = pageKey,
                origin = ArtifactOrigin.READER_ADHOC,
                expectedPageVersion = 0L,
                dependencyFingerprint = "defunct-window-test",
                nowEpochMs = 2L,
            ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>().manifest
            store = ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = mapOf(pageKey to intermediatePage(pageKey)),
                artifactStore = artifact,
                initialArtifactManifest = candidateManifest,
            )

            val writesBeforeMutation = io.writtenNames.toList()
            store.mutex.withLock {
                store.stagePageMutationLocked(pageKey, intermediatePage(pageKey))
            }
            (stagedDebounceJobField(store).get(store) as Job).cancel()
            store.hasStagedMutations() shouldBe true
            io.writtenNames shouldBe writesBeforeMutation

            // Replace the IO-backed timer with a virtual-time equivalent so this
            // test can advance past the debounce without sleeping.
            delayedFlush = backgroundScope.launch(start = CoroutineStart.LAZY) {
                delay(GroupCommitConfiguration.DEBOUNCE_MS)
                store.mutex.withLock {
                    store.flushStagedMutationsLocked()
                }
            }
            stagedDebounceJobField(store).set(store, delayedFlush)
            delayedFlush.start()
            store.markDefunct()

            delayedFlush.isCancelled shouldBe true
            advanceTimeBy(GroupCommitConfiguration.DEBOUNCE_MS + 1L)
            runCurrent()
            delayedFlush.join()

            io.writtenNames shouldBe writesBeforeMutation
            store.hasStagedMutations() shouldBe true
            store.mutex.withLock { store.flushStagedMutationsLocked() } shouldBe true
            io.writtenNames shouldBe writesBeforeMutation
        } finally {
            delayedFlush?.cancelAndJoin()
            store?.markDefunct()
            GroupCommitConfiguration.enabled = previousFlag
        }
    }

    @Test
    fun `eviction joins the active persist before flipping the store defunct`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )
        val joinStarted = CompletableDeferred<Unit>()
        val releaseJoin = CompletableDeferred<Unit>()
        val evictionReturned = CompletableDeferred<Unit>()
        val firstSignal = CompletableDeferred<Boolean>()
        joinStarted.invokeOnCompletion { firstSignal.complete(true) }
        evictionReturned.invokeOnCompletion { firstSignal.complete(false) }
        val pendingPersist = mockk<Job>(relaxed = true)
        coEvery { pendingPersist.join() } coAnswers {
            joinStarted.complete(Unit)
            releaseJoin.await()
        }
        store.persistJob = pendingPersist

        val eviction = async(Dispatchers.IO) {
            try {
                store.markDefunct()
            } finally {
                evictionReturned.complete(Unit)
            }
        }
        try {
            val firstSignalWasJoin = firstSignal.await()
            firstSignalWasJoin shouldBe true
            store.isDefunct shouldBe false
            evictionReturned.isCompleted shouldBe false
        } finally {
            releaseJoin.complete(Unit)
        }

        eviction.await()
        store.isDefunct shouldBe true
        evictionReturned.isCompleted shouldBe true
    }
}
