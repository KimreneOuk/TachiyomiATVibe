package eu.kanade.translation.workflow

import android.content.Context
import com.hippo.unifile.FakeUniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

class TranslationManagerCompletedDownloadRekeyTest {

    @TempDir
    lateinit var translationRoot: File

    @Test
    fun `fresh chapter without a manga artifact directory proceeds to first batch admission`() = runTest {
        val source = mockk<HttpSource>(relaxed = true) { every { id } returns 1L }
        val manga = mockk<Manga>(relaxed = true) {
            every { id } returns 2L
            every { title } returns "fresh manga"
        }
        every { manga.source } returns 1L
        val chapter = mockk<Chapter>(relaxed = true) {
            every { id } returns 10L
            every { mangaId } returns 2L
            every { name } returns "chapter 1"
            every { scanlator } returns null
        }
        val root = FakeUniFile(parent = null, backing = translationRoot)
        val storageManager = mockk<StorageManager> {
            every { getTranslationsDirectory() } returns root
        }
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns File(translationRoot, "private-journal").apply { mkdirs() }
        }
        val provider = TranslationFileProvider(context, storageManager)
        File(translationRoot, ".nomedia").createNewFile()
        File(translationRoot, provider.getSourceDirName(source)).mkdirs()
        provider.findMangaDirForCompletedDownload(manga.title, source) shouldBe
            TranslationFileProvider.MangaDirectoryLookup.NoPriorTranslationRecords

        val queue = MutableStateFlow<List<Translation>>(emptyList())
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue
        every { translator.isQueueConfigValid() } returns true
        every { translator.queueChapter(any(), any(), any(), any()) } answers {
            val queuedChapter = secondArg<Chapter>()
            queue.value = queue.value + Translation(source, manga, queuedChapter).apply {
                status = Translation.State.QUEUE
            }
        }
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(1L) } returns source
        }
        val scheduler = TranslationScheduler(
            executor = mockk<TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            immediateStoreResolver = { null },
        )
        val manager = TranslationManager.createForTesting(
            context = context,
            provider = provider,
            sourceManager = sourceManager,
            translationPreferences = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            pipeline = mockk<TranslationPipeline>(relaxed = true),
            translator = translator,
            pendingRequestStore = mockk<TranslationPendingRequestStore>(relaxed = true),
            pendingRequests = MutableStateFlow<Map<Long, TranslationRequestState>>(emptyMap()),
            scheduler = scheduler,
            sessionCoordinator = TranslationSessionCoordinator(),
        )

        try {
            manager.queueTranslationAfterDownload(manga, chapter)
            manager.rekeyTranslationForCompletedDownload(
                chapter = chapter,
                manga = manga,
                source = source,
                onlineKeyByPageIndex = listOf("https://example/page-1.jpg"),
                onDiskKeyByPageIndex = listOf("000.jpg"),
            ) shouldBe TranslationManager.DownloadRekeyOutcome.NoTranslationRecords

            manager.pendingTranslationRequests.value[10L]?.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
            manager.startTranslationAfterDownloadIfRequested(manga, chapter)

            queue.value.mapNotNull { it.chapter.id } shouldBe listOf(10L)
            manager.pendingTranslationRequests.value[10L] shouldBe null
            verify(exactly = 1) { translator.queueChapter(manga, chapter, null, false) }

            // A valid manga directory with a cleanly absent manifest is also
            // a fresh chapter; open succeeds with no prior records to reuse.
            val mangaDirectory = File(
                translationRoot,
                "${provider.getSourceDirName(source)}/${provider.getMangaDirName(manga.title)}",
            ).apply { mkdirs() }
            manager.rekeyTranslationForCompletedDownload(
                chapter = chapter,
                manga = manga,
                source = source,
                onlineKeyByPageIndex = listOf("https://example/page-1.jpg"),
                onDiskKeyByPageIndex = listOf("000.jpg"),
            ) shouldBe TranslationManager.DownloadRekeyOutcome.NoTranslationRecords

            val artifactFileName = provider.getTranslationFileName(chapter.name, chapter.scanlator)
            val manifestFileName = ChapterArtifactLayout.fromArtifactFileName(artifactFileName).manifestFileName
            File(mangaDirectory, manifestFileName).writeText("{ malformed manifest")
            val malformedManifest = manager.rekeyTranslationForCompletedDownload(
                chapter = chapter,
                manga = manga,
                source = source,
                onlineKeyByPageIndex = listOf("https://example/page-1.jpg"),
                onDiskKeyByPageIndex = listOf("000.jpg"),
            ).shouldBeInstanceOf<TranslationManager.DownloadRekeyOutcome.Rejected>()
            malformedManifest.reason.contains("manifest probe failed") shouldBe true
            checkNotNull(malformedManifest.cause)

            File(mangaDirectory, manifestFileName).delete() shouldBe true
            File(
                mangaDirectory,
                AtomicChapterDocuments.backupNameFor(manifestFileName),
            ).writeText("{ malformed backup manifest")
            val malformedBackup = manager.rekeyTranslationForCompletedDownload(
                chapter = chapter,
                manga = manga,
                source = source,
                onlineKeyByPageIndex = listOf("https://example/page-1.jpg"),
                onDiskKeyByPageIndex = listOf("000.jpg"),
            ).shouldBeInstanceOf<TranslationManager.DownloadRekeyOutcome.Rejected>()
            malformedBackup.reason.contains("manifest probe failed") shouldBe true
            checkNotNull(malformedBackup.cause)

            File(
                mangaDirectory,
                AtomicChapterDocuments.backupNameFor(manifestFileName),
            ).delete() shouldBe true
            queue.value = emptyList()
            manager.queueTranslationAfterDownload(manga, chapter)
            checkNotNull(
                manager.openOrCreateActiveChapterTranslationStoreSuspend(
                    chapterId = 10L,
                    chapterName = chapter.name,
                    scanlator = chapter.scanlator,
                    mangaTitle = manga.title,
                    source = source,
                    mangaId = manga.id,
                ),
            )
            val registeredArtifactStore = manager.rekeyTranslationForCompletedDownload(
                chapter = chapter,
                manga = manga,
                source = source,
                onlineKeyByPageIndex = listOf("https://example/page-1.jpg"),
                onDiskKeyByPageIndex = listOf("000.jpg"),
            ).shouldBeInstanceOf<TranslationManager.DownloadRekeyOutcome.Deferred>()
            registeredArtifactStore.waitingForRegisteredStoreRelease shouldBe true
            registeredArtifactStore.requestPreserved shouldBe true
            val artifactWait = manager.pendingTranslationRequests.value.getValue(10L)
            artifactWait.phase shouldBe TranslationRequestPhase.PREPARING
            artifactWait.reason shouldBe REGISTERED_READER_STORE_WAIT_REASON
            manager.unregisterActiveTranslationStore(10L)

            val noArtifactManga = mockk<Manga>(relaxed = true) {
                every { id } returns 3L
                every { title } returns "unmaterialized manga"
            }
            every { noArtifactManga.source } returns 1L
            val noArtifactChapter = mockk<Chapter>(relaxed = true) {
                every { id } returns 11L
                every { mangaId } returns 3L
                every { name } returns "chapter without artifacts"
                every { scanlator } returns null
            }
            manager.queueTranslationAfterDownload(noArtifactManga, noArtifactChapter)
            checkNotNull(
                manager.openOrCreateActiveChapterTranslationStoreSuspend(
                    chapterId = 11L,
                    chapterName = noArtifactChapter.name,
                    scanlator = noArtifactChapter.scanlator,
                    mangaTitle = noArtifactManga.title,
                    source = source,
                    mangaId = noArtifactManga.id,
                ),
            )
            val registeredChapterStore = manager.rekeyTranslationForCompletedDownload(
                chapter = noArtifactChapter,
                manga = noArtifactManga,
                source = source,
                onlineKeyByPageIndex = listOf("https://example/page-1.jpg"),
                onDiskKeyByPageIndex = listOf("000.jpg"),
            ).shouldBeInstanceOf<TranslationManager.DownloadRekeyOutcome.Deferred>()
            registeredChapterStore.waitingForRegisteredStoreRelease shouldBe true
            registeredChapterStore.requestPreserved shouldBe true
            val chapterWait = manager.pendingTranslationRequests.value.getValue(11L)
            chapterWait.phase shouldBe TranslationRequestPhase.PREPARING
            chapterWait.reason shouldBe REGISTERED_READER_STORE_WAIT_REASON
            manager.unregisterActiveTranslationStore(11L)
        } finally {
            scheduler.close()
        }
    }
}
