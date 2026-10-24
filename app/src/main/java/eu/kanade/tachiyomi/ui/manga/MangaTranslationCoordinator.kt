package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel.Dialog
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel.State
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.coordinator.ChapterQueuePreflight
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.ChapterResetReceipt
import eu.kanade.translation.processing.batch.BatchAdmissionDecision
import eu.kanade.translation.processing.batch.BatchAdmissionProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences

/** Coordinates translation batching, acknowledgement, confirmation, and resets for MangaScreenModel. */
internal class MangaTranslationCoordinator(
    private val translationManager: TranslationManager,
    private val downloadManager: DownloadManager,
    private val translationPreferences: TranslationPreferences,
    private val translationSnapshots: ChapterTranslationSnapshotRegistry,
    private val scope: CoroutineScope,
    private val getSuccessState: () -> State.Success?,
    private val updateSuccessState: ((State.Success) -> State.Success) -> Unit,
    private val dismissDialog: () -> Unit,
    private val observeTranslationProgress: (Long) -> Unit,
    private val updateTranslationRequests: (Map<Long, TranslationRequestState>) -> Unit,
    private val updateTranslationState: (Translation) -> Unit,
    private val onTranslationsQueuedBehindDownloads: () -> Unit = {},
    private val onTranslationCancelled: (onUndo: () -> Unit) -> Unit = {},
    private val onTranslationResumePaused: () -> Unit = {},
    private val inpaintStartBlocked: () -> Boolean = { false },
) {
    internal var pendingTranslationGroup: List<ChapterList.Item> = emptyList()

    /**
     * True when the start may proceed; otherwise shows the model-missing
     * dialog whose continue action re-runs [continueTranslation] after the
     * user switches to FAST.
     */
    private fun ensureInpaintModelAvailable(continueTranslation: () -> Unit): Boolean {
        if (!inpaintStartBlocked()) return true
        val modeName = InpaintingMode.fromPref(translationPreferences.translationInpaintingMode().get()).name
        val modelName = LiteRTMangaInpaintingEngine.displayNameFor(
            translationPreferences.translationInpaintingNeuralModel().get(),
        )
        updateSuccessState {
            it.copy(dialog = Dialog.InpaintModelMissing(modeName, modelName, continueTranslation))
        }
        return false
    }

    fun runChapterTranslationActions(
        item: ChapterList.Item,
        action: ChapterTranslationAction,
    ) {
        runChapterTranslationActions(listOf(item), action)
    }

    private fun openTranslationProgressDrawer(item: ChapterList.Item) {
        val chapterId = item.chapter.id ?: return
        observeTranslationProgress(chapterId)
        updateSuccessState { it.copy(dialog = Dialog.TranslationProgress(chapterId)) }
    }

    fun runChapterTranslationActions(
        items: List<ChapterList.Item>,
        action: ChapterTranslationAction,
    ) {
        if (items.isEmpty()) return
        val item = items.first()
        when (action) {
            ChapterTranslationAction.START -> {
                pendingTranslationGroup = items
                if (translationPreferences.translationConfirmPretranslate().get()) {
                    showConfirmTranslationDialog(items)
                } else {
                    confirmChapterTranslation(item)
                }
            }
            ChapterTranslationAction.DETAILS -> openTranslationProgressDrawer(item)
            ChapterTranslationAction.CANCEL -> {
                items.forEach { targetItem ->
                    val activeTranslation = translationManager.getQueuedTranslationOrNull(targetItem.chapter.id)
                    val wasPendingRequest = activeTranslation == null &&
                        translationManager.isChapterTranslationProtected(targetItem.chapter.id)
                    if (activeTranslation != null) {
                        translationManager.cancelQueuedTranslation(activeTranslation)
                        updateTranslationState(activeTranslation.apply { status = Translation.State.NOT_TRANSLATED })
                    } else if (!translationManager.cancelTranslationRequest(targetItem.chapter.id)) {
                        return@forEach
                    } else {
                        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
                    }
                    val manga = getSuccessState()?.manga
                    onTranslationCancelled {
                        if (wasPendingRequest) {
                            confirmChapterTranslation(targetItem)
                        } else if (manga != null) {
                            ensureInpaintModelAvailable {
                                translationManager.translateChapter(manga, targetItem.chapter)
                            }
                        }
                    }
                }
            }
            ChapterTranslationAction.DELETE -> showChapterResetDialog(item)
        }
    }

    fun retryBatchTranslation(chapterId: Long) {
        val manga = getSuccessState()?.manga
        val item = getSuccessState()?.chapters?.firstOrNull { it.id == chapterId }
        if (manga == null || item == null) {
            logcat(LogPriority.WARN) { "TachiyomiAT batch retry aborted: chapter=$chapterId missing from screen state" }
            return
        }
        ensureInpaintModelAvailable {
            translationManager.translateChapter(manga, item.chapter)
            // Retry used to be a silent no-op whenever admission or the queue
            // rejected the chapter; surface the resulting queue state.
            val queued = translationManager.getQueuedTranslationOrNull(chapterId)
            logcat(LogPriority.INFO) {
                "TachiyomiAT batch retry chapter=${item.chapter.name} queueStatus=${queued?.status ?: "not-enqueued"}"
            }
            translationManager.startTranslation()
        }
    }

    fun showChapterResetDialog(item: ChapterList.Item) {
        updateSuccessState { it.copy(dialog = Dialog.ChapterReset(item)) }
    }

    fun resetChapter(item: ChapterList.Item) = runChapterReset(item) {
        deleteTranslation(item.chapter, it.manga, it.source)
    }

    private fun runChapterReset(
        item: ChapterList.Item,
        action: suspend TranslationManager.(State.Success) -> ChapterResetReceipt,
    ) {
        scope.launchNonCancellable {
            try {
                val state = getSuccessState() ?: return@launchNonCancellable
                val receipt = action(translationManager, state)
                if (!receipt.completed) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT chapter reset incomplete: chapter=${item.chapter.name} failures=${receipt.failures}"
                    }
                    return@launchNonCancellable
                }
                item.chapter.id?.let(translationSnapshots::forget)
                updateSuccessState { current ->
                    current.updateChapterItem(item.chapter.id) { it.copy(translationState = Translation.State.NOT_TRANSLATED) }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun showConfirmTranslationDialog(item: ChapterList.Item) {
        showConfirmTranslationDialog(listOf(item))
    }

    fun showConfirmTranslationDialog(items: List<ChapterList.Item>) {
        val primary = items.first()
        updateSuccessState {
            it.copy(dialog = Dialog.ConfirmTranslation(primary, items))
        }
    }

    fun confirmChapterTranslation(item: ChapterList.Item) {
        val manga = getSuccessState()?.manga ?: return
        // pendingTranslationGroup stays pending while blocked so the dialog's
        // continue action re-enters with the original full group.
        if (!ensureInpaintModelAvailable({ confirmChapterTranslation(item) })) return
        val group = pendingTranslationGroup
            .takeIf { it.any { candidate -> candidate.chapter.id == item.chapter.id } }
            ?: listOf(item)
        pendingTranslationGroup = emptyList()

        translationManager.acknowledgeTranslationRequests(chapters = group.map { it.chapter })
        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
        openTranslationProgressDrawer(item)

        scope.launch {
            val generations = group.mapNotNull { candidate ->
                candidate.chapter.id?.let { chapterId ->
                    chapterId to translationManager.pendingRequestGeneration(chapterId)
                }
            }.filter { (_, generation) -> generation != null }
                .associate({ (chapterId, generation) -> chapterId to generation!! })
            val requestedGroup = group.filter { candidate ->
                candidate.chapter.id?.let { chapterId -> generations.containsKey(chapterId) } == true
            }
            if (requestedGroup.isEmpty()) return@launch
            val (downloaded, awaitingDownload) = withIOContext {
                requestedGroup.partition {
                    downloadManager.isChapterDownloaded(
                        it.chapter.name,
                        it.chapter.scanlator,
                        manga.title,
                        manga.source,
                        skipCache = true,
                    )
                }
            }
            queueAwaitingDownloadTranslations(manga, awaitingDownload) { generations[it] }

            val pendingDownloaded = downloaded.filter { candidate ->
                val chapterId = candidate.chapter.id ?: return@filter false
                val generation = generations[chapterId] ?: return@filter false
                translationManager.isTranslationRequestCurrent(chapterId, generation)
            }
            if (pendingDownloaded.isEmpty()) return@launch

            val partialProbes: Map<Long, BatchAdmissionDecision> =
                pendingDownloaded.mapNotNull { candidate ->
                    val chapterId = candidate.chapter.id ?: return@mapNotNull null
                    val queuedDownload = downloadManager.getQueuedDownloadOrNull(chapterId)
                        ?: return@mapNotNull null
                    val decision = BatchAdmissionProbe.evaluate(
                        downloadedPageCount = queuedDownload.pages?.count {
                            it.status == eu.kanade.tachiyomi.source.model.Page.State.READY
                        } ?: 0,
                        sourcePageList = queuedDownload.pages,
                    )
                    if (decision == BatchAdmissionDecision.Complete) null else chapterId to decision
                }.toMap()

            suspend fun admitDownloaded(candidates: List<ChapterList.Item>) {
                if (candidates.isEmpty()) return
                if (candidates.size > 1) {
                    translationManager.translateChaptersIfCurrent(
                        manga,
                        candidates.map { it.chapter },
                        generations,
                    )
                    return
                }
                val target = candidates.single()
                val targetGeneration = generations[target.chapter.id]
                target.chapter.id?.let { chapterId ->
                    targetGeneration?.let { generation ->
                        translationManager.markTranslationRequestPreparingIfCurrent(chapterId, generation)
                    }
                }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT translate START: chapter=${target.chapter.name} manga=${manga.title} lastPageRead=${target.chapter.lastPageRead}"
                }
                when (val preflight = translationManager.translateChapterPreflight(manga, target.chapter)) {
                    is ChapterQueuePreflight.NoConflict ->
                        launchTranslateChapter(manga, target.chapter, targetGeneration)
                    is ChapterQueuePreflight.RunningConflict -> {
                        updateSuccessState {
                            it.copy(dialog = Dialog.RunningTranslationConflict(target, preflight))
                        }
                    }
                }
            }

            if (partialProbes.isNotEmpty()) {
                admitDownloaded(
                    pendingDownloaded.filter { candidate ->
                        candidate.chapter.id !in partialProbes
                    },
                )
                val partialItems = pendingDownloaded.filter { candidate ->
                    candidate.chapter.id in partialProbes
                }
                updateSuccessState {
                    it.copy(
                        dialog = Dialog.PartialDownloadTranslation(
                            group = partialItems,
                            decisions = partialProbes,
                        ),
                    )
                }
                return@launch
            }
            admitDownloaded(pendingDownloaded)
        }
    }

    fun resumeChapterTranslation(item: ChapterList.Item, force: Boolean = false) {
        val chapterId = item.chapter.id ?: return
        ensureInpaintModelAvailable {
            scope.launchIO {
                val requeued = translationManager.requeueTranslation(chapterId, force)
                logcat(LogPriority.INFO) { "TachiyomiAT resume chapter=${item.chapter.name} requeue=$requeued" }
                if (requeued) {
                    translationManager.startTranslation()
                } else {
                    onTranslationResumePaused()
                }
            }
        }
    }

    /** Un-pauses the whole batch queue; guarded like every other batch start. */
    fun resumeTranslationQueue() {
        ensureInpaintModelAvailable {
            translationManager.startTranslation()
        }
    }

    fun cancelPendingTranslationRequest(item: ChapterList.Item) {
        item.chapter.id?.let { chapterId ->
            if (translationManager.cancelTranslationRequest(chapterId)) {
                updateTranslationRequests(translationManager.pendingTranslationRequests.value)
            }
        }
        dismissDialog()
    }

    fun confirmReplaceRunningChapter(item: ChapterList.Item) {
        val manga = getSuccessState()?.manga ?: return
        // Captured before the guard replaces the dialog state.
        val preflight = (getSuccessState()?.dialog as? Dialog.RunningTranslationConflict)?.conflict ?: return
        ensureInpaintModelAvailable {
            dismissDialog()
            scope.launchNonCancellable {
                translationManager.cancelRunningChapterForReplace(preflight.chapterId)
                translationManager.translateChapter(manga, item.chapter)
            }
        }
    }

    private fun launchTranslateChapter(
        manga: Manga,
        chapter: Chapter,
        expectedRequestGeneration: Long? = null,
    ) {
        scope.launchNonCancellable {
            translationManager.translateChapter(manga, chapter, expectedRequestGeneration)
        }
    }

    fun finishDownloadBeforeTranslation(dialog: Dialog.PartialDownloadTranslation) {
        val manga = getSuccessState()?.manga
        ensureInpaintModelAvailable {
            dismissDialog()
            if (manga == null) return@ensureInpaintModelAvailable
            scope.launchNonCancellable {
                queueAwaitingDownloadTranslations(manga, dialog.group, translationManager::pendingRequestGeneration)
            }
        }
    }

    fun translatePartialDownloadNow(dialog: Dialog.PartialDownloadTranslation) {
        ensureInpaintModelAvailable {
            dismissDialog()
            val manga = getSuccessState()?.manga ?: return@ensureInpaintModelAvailable
            scope.launchNonCancellable {
                val generations = mutableMapOf<Long, Long>()
                val contexts = mutableMapOf<Long, eu.kanade.translation.processing.batch.BatchAdmissionContext>()
                val chapters = mutableListOf<Chapter>()
                dialog.group.forEach { item ->
                    val chapterId = item.chapter.id ?: return@forEach
                    val generation = translationManager.pendingRequestGeneration(chapterId) ?: return@forEach
                    val decision = dialog.decisions[chapterId] ?: return@forEach
                    generations[chapterId] = generation
                    contexts[chapterId] = when (decision) {
                        is BatchAdmissionDecision.Partial ->
                            eu.kanade.translation.processing.batch.BatchAdmissionContext(
                                probedSourcePageCount = decision.expectedSourcePageCount,
                                sourceCountKnown = true,
                            )
                        BatchAdmissionDecision.UnknownCount ->
                            eu.kanade.translation.processing.batch.BatchAdmissionContext(
                                probedSourcePageCount = null,
                                sourceCountKnown = true,
                            )
                        BatchAdmissionDecision.Complete ->
                            eu.kanade.translation.processing.batch.BatchAdmissionContext(null, false)
                    }
                    chapters += item.chapter
                }
                if (chapters.isNotEmpty()) {
                    translationManager.translateChaptersIfCurrent(manga, chapters, generations, contexts)
                }
            }
        }
    }

    fun setConfirmPretranslate(show: Boolean) {
        translationPreferences.translationConfirmPretranslate().set(show)
    }

    fun translationConfirmPretranslate(): Boolean =
        translationPreferences.translationConfirmPretranslate().get()

    private fun queueAwaitingDownloadTranslations(
        manga: Manga,
        items: List<ChapterList.Item>,
        getGeneration: (Long) -> Long?,
    ) {
        val admitted = items.mapNotNull { item ->
            val chapterId = item.chapter.id ?: return@mapNotNull null
            val generation = getGeneration(chapterId) ?: return@mapNotNull null
            if (translationManager.queueTranslationAfterDownloadIfCurrent(manga, item.chapter, generation)) {
                item.chapter
            } else {
                null
            }
        }
        if (admitted.isNotEmpty()) {
            enqueueTranslationDownloads(downloadManager, manga, admitted)
            onTranslationsQueuedBehindDownloads()
        }
    }
}
