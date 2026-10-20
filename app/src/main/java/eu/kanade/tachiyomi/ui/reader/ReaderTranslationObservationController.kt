package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.diagnostics.NativeVisionTelemetry
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.model.shouldShowTranslationOverlay
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.repairStrandedPageWithAcquiredAutoLease
import eu.kanade.translation.pipeline.MemoryPressureClass
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.diagnostics.AutoSmoothnessTelemetry
import eu.kanade.translation.coordinator.ReaderPageWork
import eu.kanade.translation.coordinator.ReaderSessionToken
import eu.kanade.translation.coordinator.ReaderSubmission
import eu.kanade.translation.coordinator.ReaderTrigger
import eu.kanade.translation.workflow.ReaderSessionIntent
import eu.kanade.translation.workflow.SessionAdmission
import eu.kanade.translation.workflow.SessionRejection
import eu.kanade.translation.workflow.TranslationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.lang.ref.WeakReference

internal class ReaderTranslationObservationController(private val controller: ReaderTranslationController) {
    private val owner get() = controller.owner
    private val mutableState get() = owner.mutableState
    private val state get() = owner.state
    private val eventChannel get() = owner.eventChannel
    private val viewModelScope get() = owner.viewModelScope
    private val sourceManager get() = owner.sourceManager
    private val translationManager get() = owner.translationManager
    private val translationPreferences get() = owner.translationPreferences
    private val manga get() = owner.manga
    private val chapterPageIndex get() = owner.chapterPageIndex
    private val translationDiagnosticsEnabled get() = owner.translationDiagnosticsEnabled
    private var translationStoreJob: Job?
        get() = owner.translationStoreJob
        set(value) { owner.translationStoreJob = value }
    private var translationBatchProgressJob: Job?
        get() = owner.translationBatchProgressJob
        set(value) { owner.translationBatchProgressJob = value }
    private var translationStateJob: Job?
        get() = owner.translationStateJob
        set(value) { owner.translationStateJob = value }
    private var currentTranslationStore: ChapterTranslationStore?
        get() = owner.currentTranslationStore
        set(value) { owner.currentTranslationStore = value }
    private var batchTranslationState: Translation.State
        get() = owner.batchTranslationState
        set(value) { owner.batchTranslationState = value }
    private var liveTranslationState: Translation.State
        get() = owner.liveTranslationState
        set(value) { owner.liveTranslationState = value }

    private fun recomputeTranslationState() = owner.recomputeTranslationState()
    private fun getCurrentChapter() = owner.getCurrentChapter()
    private fun resolvePageKey(page: ReaderPage) = owner.resolvePageKey(page)
    private fun recordArrivalWait(pageIndex: Int, pageKey: String) = controller.recordArrivalWait(pageIndex, pageKey)
    private suspend fun updateTranslationWorkingSet(chapter: ReaderChapter, currentIndex: Int, dispatchRefresh: Boolean) =
        controller.updateTranslationWorkingSet(chapter, currentIndex, dispatchRefresh)
    private suspend fun attachTranslatedStreamIfWarm(page: ReaderPage, manga: Manga, chapter: ReaderChapter, source: HttpSource) =
        controller.attachTranslatedStreamIfWarm(page, manga, chapter, source)
    internal fun observeTranslationState() {
        // TachiyomiAT: cancel any prior collector first. loadChapter() calls
        // this, so without cancelling

        // each chapter change stacked another statusFlow().launchIn(viewModelScope)
        // collector (the translationStateJob field was declared but never
        // assigned). They filtered by a captured chapterId and so no-op'd for
        // old chapters, but accumulated for the life of the ViewModel.
        translationStateJob?.cancel()
        val chapterId = getCurrentChapter()?.chapter?.id ?: return
        translationStateJob = translationManager.statusFlow()
            .filterNotNull()
            .onEach { translation ->
                if (translation.chapter.id == chapterId) {
                    // Merge the batch-queue status with live (per-page/auto)
                    // status instead of overwriting it. Overwriting here would
                    // erase a TRANSLATING signal coming from per-page work.
                    batchTranslationState = translation.status
                    recomputeTranslationState()
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * TachiyomiAT: heals pages left stranded in a non-terminal (RUNNING/PENDING)
     * status by a prior crash, an OOM-kill, or the native-code permit leak.
     * Such a page otherwise keeps the reader's liveTranslationState pinned at
     * TRANSLATING forever (spinner never stops, translate icon disabled,
     * auto-translate never retries it). Any non-terminal entry older than the
     * per-page timeout is treated as abandoned and flipped to FAILED. Never
     * touches a page that already produced a result (rendered/cleaned) or
     * already reached FAILED. Idempotent.
     */
    private suspend fun sweepStrandedPageStatus(store: ChapterTranslationStore, chapterId: Long) {
        if (translationManager.isChapterBatchActive(chapterId, includePaused = true)) {
            return
        }
        val currentGen = store.currentGeneration
        val now = System.currentTimeMillis()
        val staleAfterMs = TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS
        val snapshot = store.state.value
        for ((pageKey, pt) in snapshot) {
            if (pt.runGeneration == currentGen) continue
            val isTerminal = pt.ocrStatus == StageStatus.FAILED ||
                pt.inpaintStatus == StageStatus.FAILED ||
                pt.translationStatus == StageStatus.FAILED ||
                pt.renderStatus == StageStatus.FAILED ||
                pt.displayImageName != null ||
                //  no-render batch design: a page whose cleaned image is
                // published and current already produced its durable result —
                // the batch leaves renderStatus PENDING forever (overlay is
                // drawn on demand), and treating that as stranded healed healthy
                // pages one by one (2026-09-16 field report).
                pt.isCleanedImageReady ||
                pt.isTextlessTerminal
            if (isTerminal) continue
            val isNonTerminal = pt.ocrStatus == StageStatus.RUNNING ||
                pt.ocrStatus == StageStatus.PENDING ||
                pt.inpaintStatus == StageStatus.RUNNING ||
                pt.inpaintStatus == StageStatus.PENDING ||
                pt.translationStatus == StageStatus.RUNNING ||
                pt.translationStatus == StageStatus.PENDING ||
                pt.renderStatus == StageStatus.RUNNING ||
                pt.renderStatus == StageStatus.PENDING
            if (!isNonTerminal) continue
            val age = now - pt.updatedAt
            if (age < staleAfterMs) continue
            logcat(LogPriority.WARN) {
                "TachiyomiAT stranded-page sweep: healing $pageKey " +
                    "(ocr=${pt.ocrStatus} inpaint=${pt.inpaintStatus} age=${age / 1000}s)"
            }
            //   the sweep is reader-side AUTOMATIC maintenance — its
            // lease is AUTO (never preempts; a reader tap evicts it). The
            // mutation fences on the EXACT lease evidence captured at
            // acquisition (token/generation/version), never a fresh snapshot:
            // a MANUAL tap preempting between acquire and mutate would
            // otherwise lend the stale sweep its own write authority. Release
            // is token-fenced to the acquired record only.
            val repaired = withStrandedSweepTrace(chapterId, pageKey) {
                repairStrandedPageWithAcquiredAutoLease(
                    store = store,
                    pageKey = pageKey,
                    stranded = pt.toDraft(),
                )
            }
            if (!repaired) continue
        }
    }

    private suspend fun <T> withStrandedSweepTrace(
        chapterId: Long,
        pageKey: String,
        block: suspend () -> T,
    ): T {
        if (!TranslationPipelineDiagnostics.detailedTracingEnabled) return block()
        val schedule = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.AUTO,
            origin = TranslationTraceMode.AUTO,
            chapterRaw = chapterId.toString(),
            pages = 1,
        )
        val run = TranslationPipelineDiagnostics.startRun(
            schedule = schedule,
            pageRaw = pageKey,
            plan = TranslationTracePlan.RESUME,
        )
        var outcome = TranslationTraceOutcome.SUCCESS
        return try {
            withContext(TranslationTrace.elementFor(run)) { block() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            outcome = TranslationTraceOutcome.CANCELLED
            throw cancelled
        } catch (failure: Throwable) {
            outcome = TranslationTraceOutcome.FAILURE
            throw failure
        } finally {
            run.end(outcome)
            schedule.end(outcome)
        }
    }

    internal suspend fun observeLiveTranslationStore() {
        translationStoreJob?.cancel()
        translationBatchProgressJob?.cancel()
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        // A collector belongs to one reader chapter. Clear only when binding a
        // different chapter; re-entry keeps the last shared snapshot visible
        // while its collector reconnects, so lifecycle transitions do not hide
        // a running or paused batch.
        if (state.value.translationBatchProgress?.chapterId != chapter.id) {
            mutableState.update { it.copy(translationBatchProgress = null) }
        }
        val chapterId = chapter.id ?: return
        // Subscribe to the manager projection before opening the chapter store. A pending
        // request (for example, one waiting for a download) has no store yet, but it still
        // needs to be visible in the reader and survive an enter/exit cycle.
        translationBatchProgressJob = viewModelScope.launchIO {
            translationManager.observeBatchProgress(chapterId).collect { progress ->
                mutableState.update { it.copy(translationBatchProgress = progress) }
            }
        }
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        // TachiyomiAT: use openOrCreate... instead of openActive.... The latter
        // returns null and bails out when no translation file exists yet (a
        // fresh chapter). That left the reader subscribed to *nothing* while the
        // translator wrote to a *different* store instance it created via its
        // own activeStoreResolver — so the very first translate click showed no
        // overlay/dim/spinner even though the pipeline ran. openOrCreate...
        // returns the exact same shared instance the translator resolves to,
        // so the reader observes the RUNNING write live.
        // TachiyomiAT (ANR fix): suspend variant — a first open runs legacy
        // artifact migration with SAF I/O and must not runBlocking a
        // dispatcher thread while holding readers waiting on the same store.
        val storeStage = ReaderEntryTrace.begin("vm.openActiveStore", chapterId)
        val store = translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
            manga.id,
        )
        storeStage.end()
        if (store == null) return
        currentTranslationStore = store
        val storeState = store.state
        translationStoreJob = viewModelScope.launchIO {
            // TachiyomiAT: heal stranded RUNNING/PENDING pages left behind by a
            // prior crash, an OOM-kill, or the native-code permit leak this build
            // fixes — BEFORE the first collect emission is processed. A page
            // stuck at ocrStatus=RUNNING with no result keeps the observer's
            // anyRunning=true forever (so liveTranslationState never leaves
            // TRANSLATING, the spinner never stops, the translate icon stays
            // disabled, and auto-translate's isRunning skip never re-enqueues
            // it). Any non-terminal entry older than the per-page timeout is
            // treated as abandoned and flipped to CANCELLED. Runs once per
            // chapter open. Never clobbers a page that produced a result or
            // reached FAILED.
            sweepStrandedPageStatus(store, chapterId)
            storeState.collect { pageMap ->
                val pages = state.value.viewerChapters?.currChapter?.pages ?: return@collect
                var translatedCount = 0
                val totalPages = pages.size
                // Track whether ANY page currently has a RUNNING stage or a
                // FAILED stage across this emission, so we can derive a
                // liveTranslationState that reflects per-page/auto work (the
                // batch-queue status alone never flips for these).
                var anyRunning = false
                var anyError = false
                // Lowest 1-based index of a page currently mid-translation. Used
                // for the "Translating page N of M" label so it tracks the page
                // the spinner animates on. 0 means no page is running.
                var runningPageIndex = 0
                for ((pageIndex, page) in pages.withIndex()) {
                    val readerPage = page as? ReaderPage ?: continue
                    val pageKey = resolvePageKey(readerPage)
                    val updated = pageMap[pageKey] ?: continue
                    if (updated.ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.inpaintStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.translationStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.renderStatus == eu.kanade.translation.model.StageStatus.RUNNING
                    ) {
                        anyRunning = true
                        // Pages may be emitted out of order; keep the lowest
                        // running index for a stable label.
                        val oneBased = pageIndex + 1
                        if (runningPageIndex == 0 || oneBased < runningPageIndex) {
                            runningPageIndex = oneBased
                        }
                    }
                    // The committed display projection is the reader authority;
                    // the live candidate still drives stage/error feedback.
                    val resolvedDisplay = store.resolveDisplayPage(pageKey) ?: updated
                    val display = resolvedDisplay.toPageDisplayProjection()
                    val hasCleaned = display.displayReady
                    val isFailed = updated.ocrStatus == eu.kanade.translation.model.StageStatus.FAILED ||
                        updated.inpaintStatus == eu.kanade.translation.model.StageStatus.FAILED ||
                        updated.translationStatus == eu.kanade.translation.model.StageStatus.FAILED ||
                        updated.renderStatus == eu.kanade.translation.model.StageStatus.FAILED
                    if (isFailed && !hasCleaned) anyError = true
                    // TachiyomiAT: this per-page INFO log fires on every RUNNING
                    // stage of every page during a batch run — thousands of string
                    // interpolations + log dispatches on a busy chapter. Gate it
                    // behind the opt-in translation_diagnostics pref so the hot
                    // path stays quiet unless the user is actively debugging.
                    if (translationDiagnosticsEnabled &&
                        (
                            updated.ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.inpaintStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.translationStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.renderStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.activeError != null
                            )
                    ) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT live translation update: pageKey=$pageKey " +
                                "ocr=${updated.ocrStatus} inpaint=${updated.inpaintStatus} " +
                                "translate=${updated.translationStatus} render=${updated.renderStatus} " +
                                "cleaned=${updated.cleanedImageName} " +
                                "error=${updated.activeError}"
                        }
                    }
                    // TachiyomiAT (Phase 3): the reader page observes the
                    // committed display bundle when one exists — a candidate
                    // retry emission can replace the live entry but never
                    // nulls the committed translated page.
                    if (display.displayReady || display.isTextless) translatedCount++
                    if (isFailed && !display.displayReady && !display.isTextless) translatedCount++
                    readerPage.translation = resolvedDisplay
                }
                state.value.viewerChapters?.currChapter?.let { current ->
                    val targetIndex = if (chapterPageIndex >= 0) chapterPageIndex else current.requestedPage
                    updateTranslationWorkingSet(current, targetIndex, dispatchRefresh = false)
                }
                // Derive the live state from what we just observed, then merge
                // with the batch state. TRANSLATING wins; then ERROR; then
                // TRANSLATED only when every page has produced a result; else
                // NOT_TRANSLATED (so the icon goes back to neutral when idle).
                liveTranslationState = when {
                    anyRunning -> Translation.State.TRANSLATING
                    anyError -> Translation.State.ERROR
                    totalPages > 0 && translatedCount >= totalPages -> Translation.State.TRANSLATED
                    else -> Translation.State.NOT_TRANSLATED
                }
                recomputeTranslationState()
                // TachiyomiAT: only push a State update when the progress OR the
                // currently-running page index actually changed. The old code used
                // System.currentTimeMillis() as a refresh token, which always
                // differs and forces Compose recomposition on every single
                // translation-stage emission (OCR → inpaint → translate → render
                // per page per prefetch), causing visible "blinking" on
                // auto-translate chapters.
                val newProgress = Pair(translatedCount, totalPages)
                val currentState = mutableState.value
                if (newProgress != currentState.translationProgress ||
                    runningPageIndex != currentState.translationCurrentPage
                ) {
                    mutableState.update {
                        it.copy(
                            translationProgress = newProgress,
                            translationCurrentPage = runningPageIndex,
                            translationRefreshToken = System.currentTimeMillis(),
                        )
                    }
                }
                // Holder-level observePageView(page) collectors consume page
                // image/status changes directly. This collector only keeps the
                // global translation state and ReaderPage snapshots current.
            }
        }
    }

    internal fun observePageView(page: ReaderPage): Flow<eu.kanade.translation.model.PageView>? {
        val manga = manga ?: run {
            logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: manga null (pageIdx=${page.index})" }
            return null
        }
        val chapter = page.chapter.chapter
        val chapterId = chapter.id ?: run {
            logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: chapterId null (pageIdx=${page.index})" }
            return null
        }
        val source = sourceManager.get(manga.source) as? HttpSource ?: run {
            logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: source null (pageIdx=${page.index})" }
            return null
        }
        val pageKey = resolvePageKey(page)
        // TachiyomiAT (diagnostic): log the lookup so a chapter-open capture can
        // name exactly why a pre-translated page shows its original image. Gated
        // behind translation_diagnostics (already a cached pref read) so there's
        // zero overhead in production. Remove once the display-path bug is fixed.
        val diag = translationDiagnosticsEnabled
        // TachiyomiAT (ANR fix): the store is resolved lazily inside this cold
        // flow on Dispatchers.IO. observePageView is called from page-holder
        // bind on the MAIN thread; the first open of a chapter store performs
        // legacy artifact migration with synchronous SAF binder I/O, so a
        // blocking resolution here froze RecyclerView layout and ANR'd the
        // reader on entry. Holders subscribe exactly as before; the first
        // emission simply arrives after the IO resolution completes.
        return flow {
            val store = translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
                manga.id,
            )
            if (store == null) {
                logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: store null (pageIdx=${page.index})" }
                return@flow
            }
            if (diag) {
                val storeSize = store.state.value.size
                val hasKey = store.state.value.containsKey(pageKey)
                val sampleKeys = store.state.value.keys.take(3)
                val entry = store.state.value[pageKey]
                logcat(LogPriority.INFO) {
                    "[reader_translate_diag] observePageView pageIdx=${page.index} pageKey=$pageKey " +
                        "sourceFileName=${page.sourceFileName} storeSize=$storeSize hasKey=$hasKey " +
                        "sampleKeys=$sampleKeys " +
                        "entryCleaned=${entry?.cleanedImageName} entryStatus=${entry?.let { "ocr=${it.ocrStatus} render=${it.renderStatus}" }}"
                }
            }
            // TachiyomiAT (Phase 3): the page view observes the store's committed
            // display projection. Each emission resolves to the immutable
            // committed display bundle when one exists (candidate retries never
            // null it) and to the live candidate entry otherwise, so the holder's
            // overlay binding can never flip a translated page back to the
            // original mid-refresh.
            emitAll(
                store.display
                    .map { pages -> pages[pageKey] }
                    .distinctUntilChanged(),
            )
        }
            .flowOn(Dispatchers.IO)
            .onEach { updated ->
                if (updated == null) {
                    if (diag) {
                        logcat(LogPriority.WARN) {
                            "[reader_translate_diag] store emitted NULL for pageKey=$pageKey (no matching entry)"
                        }
                    }
                    return@onEach
                }
                val tier2Finished = updated.displayImageName != null && page.translatedStream == null
                val wantsToShowOverlay = updated.toPageDisplayProjection().displayReady && !page.showTranslatedImage
                page.translation = updated
                attachTranslatedStreamIfWarm(page, manga, page.chapter, source)
                if (translationPreferences.translationEnabled().get()) {
                    if (tier2Finished || wantsToShowOverlay) {
                        page.showTranslatedImage = true
                        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(setOf(page)))
                        NativeVisionTelemetry.logDisplayAttach(
                            pageKey = pageKey,
                            streamAttached = page.translatedStream != null,
                            showTranslatedImage = page.showTranslatedImage,
                            displayImageName = page.translation?.displayImageName != null,
                        )
                    }
                    if (updated.isTranslationDisplayReady) {
                        recordArrivalWait(page.index, pageKey)
                    }
                }
            }
            .map { it.toPageView() }
            .distinctUntilChanged()
    }

}
