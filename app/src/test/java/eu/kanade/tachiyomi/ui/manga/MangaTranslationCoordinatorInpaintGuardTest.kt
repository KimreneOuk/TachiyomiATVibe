package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.TranslationPreferences

/**
 * The model-missing guard must intercept a batch start when the probe reports
 * the selected BALANCE/QUALITY model absent: the dialog replaces whatever is
 * showing, nothing is admitted, and the pending group survives so the dialog's
 * continue action (after the caller switches the mode pref to FAST) re-enters
 * with the original selection.
 */
class MangaTranslationCoordinatorInpaintGuardTest {

    private val manga = Manga.create().copy(id = 1L, title = "Manga", source = 1L, favorite = true)

    private fun chapterFixture(id: Long) = Chapter.create().copy(id = id, mangaId = 1L, name = "Chapter $id")

    private fun item(id: Long) = ChapterList.Item(
        chapter = chapterFixture(id),
        downloadState = Download.State.NOT_DOWNLOADED,
        translationState = Translation.State.NOT_TRANSLATED,
        downloadProgress = 0,
    )

    private val translationManager = mockk<TranslationManager>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)

    init {
        every { translationManager.pendingTranslationRequests } returns
            kotlinx.coroutines.flow.MutableStateFlow(emptyMap<Long, eu.kanade.translation.model.TranslationRequestState>())
    }

    private var modePrefValue = "BALANCE"
    private var blocked = true
    private val modePref = mockk<Preference<String>> {
        every { get() } answers { modePrefValue }
    }
    private val neuralModelPref = mockk<Preference<NeuralInpaintModel>> {
        every { get() } returns NeuralInpaintModel.LAMA_LITERT_GPU
    }
    private val translationPreferences = mockk<TranslationPreferences> {
        every { translationInpaintingMode() } returns modePref
        every { translationInpaintingNeuralModel() } returns neuralModelPref
        every { translationConfirmPretranslate() } returns mockk {
            every { get() } returns false
        }
    }

    private var currentState = MangaScreenModel.State.Success(
        manga = manga,
        source = mockk<Source>(relaxed = true),
        isFromSource = false,
        chapters = listOf(item(5L), item(6L)),
        availableScanlators = emptySet(),
        excludedScanlators = emptySet(),
    )

    private fun coordinator() = MangaTranslationCoordinator(
        translationManager = translationManager,
        downloadManager = downloadManager,
        translationPreferences = translationPreferences,
        translationSnapshots = ChapterTranslationSnapshotRegistry(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        getSuccessState = { currentState },
        updateSuccessState = { reducer -> currentState = reducer(currentState) },
        dismissDialog = {},
        observeTranslationProgress = {},
        updateTranslationRequests = {},
        updateTranslationState = {},
        inpaintStartBlocked = { blocked },
    )

    // confirmPretranslate is stubbed false, so START confirms the group itself.
    private fun startBatch(coordinator: MangaTranslationCoordinator) {
        coordinator.runChapterTranslationActions(listOf(item(5L), item(6L)), ChapterTranslationAction.START)
    }

    @Test
    fun `blocked BALANCE start shows dialog and admits nothing`() = runTest {
        val coordinator = coordinator()

        startBatch(coordinator)

        val dialog = currentState.dialog as? MangaScreenModel.Dialog.InpaintModelMissing
        dialog?.modeName shouldBe "BALANCE"
        dialog?.modelName shouldBe "Manga LaMa (LiteRT GPU)"
        verify(exactly = 0) { translationManager.acknowledgeTranslationRequests(any()) }
        coordinator.pendingTranslationGroup.map { it.chapter.id } shouldBe listOf(5L, 6L)
    }

    @Test
    fun `continue action after switching to FAST admits the preserved group`() = runTest {
        val coordinator = coordinator()
        startBatch(coordinator)
        val dialog = checkNotNull(currentState.dialog as? MangaScreenModel.Dialog.InpaintModelMissing)

        modePrefValue = "FAST"
        blocked = false
        dialog.onContinue()

        val acknowledged = mutableListOf<List<Chapter>>()
        verify(exactly = 1) { translationManager.acknowledgeTranslationRequests(capture(acknowledged)) }
        acknowledged.single().map { it.id } shouldBe listOf(5L, 6L)
        coordinator.pendingTranslationGroup shouldBe emptyList()
    }

    @Test
    fun `unblocked start admits the group directly`() = runTest {
        val coordinator = coordinator()
        blocked = false

        startBatch(coordinator)

        verify(exactly = 1) { translationManager.acknowledgeTranslationRequests(any()) }
    }
}
