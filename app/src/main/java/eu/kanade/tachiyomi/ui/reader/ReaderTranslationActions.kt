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

internal class ReaderTranslationActions(private val controller: ReaderTranslationController) {
    private val owner get() = controller.owner
    private val mutableState get() = owner.mutableState
    private val state get() = owner.state
    private val eventChannel get() = owner.eventChannel
    private val viewModelScope get() = owner.viewModelScope
    private val sourceManager get() = owner.sourceManager
    private val downloadManager get() = owner.downloadManager
    private val translationManager get() = owner.translationManager
    private val readerCoordinator get() = controller.readerCoordinator
    private val streamRegistry get() = owner.streamRegistry
    private val chapterCache get() = owner.chapterCache
    private val translationPreferences get() = owner.translationPreferences
    private val manga get() = owner.manga
    private val chapterPageIndex get() = owner.chapterPageIndex
    private var batchTranslationState
        get() = owner.batchTranslationState
        set(value) { owner.batchTranslationState = value }
    private var liveTranslationState
        get() = owner.liveTranslationState
        set(value) { owner.liveTranslationState = value }

    private fun recomputeTranslationState() = owner.recomputeTranslationState()
    private fun getCurrentChapter() = owner.getCurrentChapter()
    private fun resolvePageKey(page: ReaderPage) = owner.resolvePageKey(page)
    private suspend fun ensureQueueSession(chapter: ReaderChapter) = controller.ensureQueueSession(chapter)
    private fun resetAutoTranslationState() = controller.resetAutoTranslationState()
    private suspend fun observeLiveTranslationStore() = controller.observeLiveTranslationStore()
    internal suspend fun cancelTranslationForChapter(chapter: ReaderChapter) {
        val manga = manga ?: return
        val chapterId = chapter.chapter.id ?: return
        resetAutoTranslationState()
        // Chapter navigation must join reader-owned work before the old chapter's loader is
        // released by ViewerChapters.unref(). Batch/revision ownership remains manager-gated.
        translationManager.awaitReaderStop("chapter switch")
        streamRegistry.clearChapter(
            sourceId = manga.source,
            mangaId = manga.id,
            chapterId = chapterId,
        )
        // TachiyomiAT: clear the per-page translation fields on the departing
        // chapter's pages. The translator jobs are cancelled above and the
        // registered streams evicted, but the ReaderPage objects themselves keep
        // their translatedStream / translation / showTranslatedImage fields set.
        // Because InsertPage (in the pager viewer) wraps the SAME underlying
        // ReaderPage, a holder reused across the chapter boundary could briefly
        // render the old chapter's translated image in the new chapter's slot
        // during the cancel→re-subscribe window. Null these out so a stale
        // stream closure (which also captured page bitmaps) is released and any
        // holder still pointing at these pages falls back to the original image.
        chapter.pages?.forEach { page ->
            page.translatedStream = null
            page.translation = null
            page.showTranslatedImage = false
        }
        // TachiyomiAT: reset the merged translation state to neutral. Without
        // this, an ERROR (or TRANSLATING) from the chapter we're leaving stays
        // sticky: recomputeTranslationState() treats ERROR as terminal-wins, so
        // the bottom-bar icon stayed red (and auto/manual translation on the
        // NEXT chapter was masked by the stale error) even though the failing
        // chapter's work had just been cancelled. observeLiveTranslationStore()
        // re-subscribes to the new chapter's store and resets liveTranslationState,
        // but batchTranslationState (driven by the queued-translation statusFlow)
        // was never cleared here — so a chapter-1 batch ERROR bled into chapter 2.
        // Mirror the reset that "Stop all translation" and "disable translation"
        // already perform.
        batchTranslationState = Translation.State.NOT_TRANSLATED
        liveTranslationState = Translation.State.NOT_TRANSLATED
        recomputeTranslationState()
    }

    internal fun deleteCurrentChapterTranslation() {
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        // Delete tears down the manager/store asynchronously; invalidate the active resolver and
        // collector before any manager cancellation can retain the old page resources.
        runAfterReaderAutoReset(::resetAutoTranslationState) {
            // TachiyomiAT: delete is now SUSPEND and fully tears down (cancels +
            // joins single/auto AND batch jobs, evicts + defuncts the shared store,
            // clears the reader stream registry, then deletes the on-disk file +
            // companion images) before returning. Split into two phases:
            //   - SYNCHRONOUS: clear the per-page translated fields + emit a refresh
            //     so holders fall back to the original image immediately and release
            //     captured translated-stream bitmaps instead of showing the
            //     about-to-be-deleted rendered image. This must reflect on the UI now.
            //   - ASYNC: run deleteTranslation, then re-subscribe + reset state ONLY
            //     after teardown completes. The previous code re-subscribed
            //     synchronously while delete's eviction still ran on a background
            //     coroutine, so the cache-first openOrCreateActiveChapterTranslationStoreSuspend
            //     could re-bind the reader to the about-to-be-evicted store while the
            //     translator wrote to a different instance — leaving the reader on
            //     ORIGINAL images with a stuck global spinner on delete-then-retranslate.
            getCurrentChapter()?.pages?.forEach { page ->
                page.translatedStream = null
                page.translation = null
                page.showTranslatedImage = false
                (page as? ReaderPage)?.translationToggled = false
            }

            val readerPages = getCurrentChapter()?.pages
                ?.filterIsInstance<ReaderPage>()
                ?.toSet()
                ?: emptySet()
            if (readerPages.isNotEmpty()) {
                eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
                mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }
            }
        }

        viewModelScope.launchIO {
            translationManager.deleteTranslation(chapter.toDomainChapter()!!, manga, source)
            // Re-subscribe to the (now fresh) store AFTER teardown. deleteTranslation
            // has already evicted the old store, so openOrCreate... resolves a new
            // lazy store that the next translate also resolves — reader and
            // translator share the same instance and live updates flow again.
            observeLiveTranslationStore()
            batchTranslationState = Translation.State.NOT_TRANSLATED
            liveTranslationState = Translation.State.NOT_TRANSLATED
            mutableState.update { it.copy(translationState = Translation.State.NOT_TRANSLATED) }
            recomputeTranslationState()
        }
    }

    internal fun resetTranslationData(preserveEdits: Boolean) {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetTranslationData(chapter.toDomainChapter()!!, manga, source, pageKey, preserveEdits)
            recomputeTranslationState()
        }
    }

    internal fun resetInpaintData() {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetInpaintData(chapter.toDomainChapter()!!, manga, source, pageKey)
            recomputeTranslationState()
        }
    }

    internal fun resetOcrData() {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetOcrData(chapter.toDomainChapter()!!, manga, source, pageKey)
            recomputeTranslationState()
        }
    }

    internal fun resetEverything() {
        resetOcrData()
    }

    internal fun translateSinglePage(page: ReaderPage, force: Boolean? = null) {
        val tapTimeNs = System.nanoTime()
        when (
            val admission = translationManager.sessionCoordinator.requestReaderSession(
                ReaderSessionIntent(chapterId = page.chapter.chapter.id),
            )
        ) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> translateSinglePageAfterAdmission(page, force, tapTimeNs)

            is SessionAdmission.Rejected -> when (admission.reason) {
                SessionRejection.BATCH_ACTIVE -> mutableState.update {
                    it.copy(dialog = ReaderViewModel.Dialog.BatchReaderSwitch(page = page, force = force))
                }

                SessionRejection.PAUSING_IN_PROGRESS -> {
                    Injekt.get<Application>().toast(ATMR.strings.reader_batch_switch_wait)
                }

                SessionRejection.READER_ACTIVE -> {
                    // The existing reader session owns this request; the
                    // scheduler's idempotent admission will continue it.
                    translateSinglePageAfterAdmission(page, force, tapTimeNs)
                }
            }
        }
    }

    /**
     * Completes the batch-to-reader handoff from the controller so the session
     * admission seam remains alongside the manual translation entry point.
     */
    internal fun confirmBatchReaderSwitch(request: ReaderViewModel.Dialog.BatchReaderSwitch) {
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launchIO {
            when (val admission = translationManager.switchReaderSession(request.page.chapter.chapter.id)) {
                is SessionAdmission.Admitted,
                is SessionAdmission.Switched,
                -> translateSinglePage(request.page, request.force)

                is SessionAdmission.Rejected -> {
                    withUIContext {
                        val message = when (admission.reason) {
                            SessionRejection.PAUSING_IN_PROGRESS -> ATMR.strings.reader_batch_switch_wait
                            SessionRejection.BATCH_ACTIVE -> ATMR.strings.reader_batch_switch_cancelled
                            SessionRejection.READER_ACTIVE -> ATMR.strings.reader_batch_switch_reader_active
                        }
                        Injekt.get<Application>().toast(message)
                    }
                }
            }
        }
    }

    private fun translateSinglePageAfterAdmission(
        page: ReaderPage,
        force: Boolean? = null,
        tapTimeNs: Long = System.nanoTime(),
    ) {
        val chapter = page.chapter.chapter
        val pageKey = resolvePageKey(page)
        val manga = manga ?: run {
            logcat(LogPriority.WARN) { "translateSinglePage: manga is null, cannot translate" }
            return
        }
        val source = sourceManager.get(manga.source) as? HttpSource ?: run {
            logcat(LogPriority.WARN) { "translateSinglePage: invalid HttpSource, cannot translate" }
            return
        }
        // TachiyomiAT: manual entry drops a stale DOWNLOAD_FAILED batch
        // request so its failed projection cannot shadow this manual work.
        chapter.id?.let(translationManager::clearStaleDownloadFailedRequest)
        // Resolve force from OCR/translation failures only. Inpaint is a
        // display operation, so its failure cannot consume translation retries.
        val effectiveForce = force ?: run {
            val t = page.translation
            t != null &&
                (
                    t.ocrStatus == "FAILED" ||
                        t.translationStatus == "FAILED"
                    )
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT manual translate page request: index=${page.index} pageKey=$pageKey " +
                "sourceFileName=${page.sourceFileName} imageUrl=${page.imageUrl} " +
                "loader=${page.chapter.pageLoader?.javaClass?.simpleName} force=$effectiveForce"
        }
        // TachiyomiAT: resolve a stream for the translator. A page is
        // translatable when ANY of these byte sources is available:
        //   1. page.originalStream  — already-cached/downloaded page bytes
        //   2. a downloaded chapter dir (translator opens the file directly)
        //   3. page.imageUrl        — lazy on-demand fetch from the source
        // Previously the guard below rejected (1)==null && (2)==false outright,
        // which is exactly the streamed-chapter case that (3) exists to cover —
        // so the lazy-HTTP fallback was unreachable dead code and tapping
        // translate on an online chapter silently did nothing ("Download the
        // chapter before translating"). Only bail when NONE of the three are
        // available. Check the page's own chapter (not getCurrentChapter()) so
        // the guard is correct when a cross-chapter transition fires before
        // loadNewChapter completes.
        val pageChapterDownloaded = downloadManager.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            manga.title,
            manga.source,
        )
        if (page.originalStream == null && !pageChapterDownloaded && page.imageUrl == null) {
            logcat(LogPriority.WARN) {
                "translateSinglePage: no stream available for page $pageKey (originalStream=null, chapterDownloaded=$pageChapterDownloaded, imageUrl=${page.imageUrl != null})"
            }
            return
        }
        viewModelScope.launchIO {
            val token = ensureQueueSession(page.chapter) ?: run {
                logcat(LogPriority.WARN) {
                    "translateSinglePage: no reader queue session for $pageKey (admission denied or store unavailable)"
                }
                return@launchIO
            }
            val streamFn = resolveManualStreamFn(page, token) ?: return@launchIO
            val delayMs = (System.nanoTime() - tapTimeNs) / 1_000_000.0
            NativeVisionTelemetry.logTapToDispatch(pageKey, delayMs)
            // One submission path for every byte source: the queue owns dedup,
            // promotion and the ordered replacement of a forced retry.
            when (
                val submission = readerCoordinator.submitPage(
                    token = token,
                    work = ReaderPageWork(
                        pageKey = pageKey,
                        readerPageIndex = page.index,
                        streamFn = streamFn,
                    ),
                    trigger = ReaderTrigger.MANUAL_TAP,
                    force = effectiveForce,
                )
            ) {
                is ReaderSubmission.Rejected -> logcat(LogPriority.WARN) {
                    "translateSinglePage: submission rejected for $pageKey (${submission.outcome})"
                }

                else -> Unit
            }
        }
    }

    /**
     * Resolves the tap's stream factory from the page's live byte sources and
     * registers it with the reader stream registry. A late online download
     * revalidates the captured session token (which also fences a compute-epoch
     * rebind) and the page's chapter identity before registration.
     */
    private suspend fun resolveManualStreamFn(
        page: ReaderPage,
        token: ReaderSessionToken,
    ): (() -> InputStream)? {
        val manga = manga ?: return null
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null
        val pageKey = resolvePageKey(page)
        page.originalStream?.let { streamFn ->
            streamRegistry.register(
                manga,
                page.chapter.chapter.toDomainChapter()!!,
                source,
                pageKey,
                streamFn,
            )
            return streamFn
        }
        page.imageUrl?.let { imageUrl ->
            // Online pages not yet cached download their bytes in this
            // cancellable coroutine; only then are the stream registered and
            // the page submitted. The registration uses the same cache-backed
            // factory that was submitted.
            val streamFn = try {
                createLazyHttpStream(source, page, imageUrl)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN) {
                    "translateSinglePage: lazy download failed for $pageKey: ${e.message}"
                }
                return null
            }
            if (readerCoordinator.sessionFor(token) == null) {
                logcat(LogPriority.INFO) {
                    "translateSinglePage: dropping late download for $pageKey (reader session replaced)"
                }
                return null
            }
            if (getCurrentChapter() !== page.chapter) {
                logcat(LogPriority.INFO) {
                    "translateSinglePage: dropping late download for $pageKey (chapter no longer active)"
                }
                return null
            }
            streamRegistry.register(
                manga,
                page.chapter.chapter.toDomainChapter()!!,
                source,
                pageKey,
                streamFn,
            )
            return streamFn
        }
        // Downloaded chapters may not expose a live ReaderPage.originalStream
        // after holder rebinding. The factory rescans the chapter dir on the
        // worker and opens a fresh stream per invocation.
        logcat(LogPriority.INFO) {
            "TachiyomiAT manual translate page request using downloaded chapter fallback: pageKey=$pageKey"
        }
        val domainChapter = page.chapter.chapter.toDomainChapter() ?: return null
        return translationManager.downloadedChapterPageStream(
            manga,
            domainChapter,
            source,
            pageKey,
        )
    }

    /**
     * TachiyomiAT: downloads the page image from [source] and returns a stream
     * closure backed by the chapter disk cache. Used when
     * [ReaderPage.originalStream] is null (the page hasn't been cached yet by
     * HttpPageLoader) but the page has an [imageUrl].
     *
     * This is a [suspend] function (called from a tracked coroutine scope) so the
     * download is cancellable — if the reader switches chapters mid-download, the
     * coroutine is cancelled and the partially-downloaded bytes are dropped.
     * Previously this was a non-suspending closure whose body was `runBlocking {
     * source.getImage(...) }`; that blocked an IO dispatcher thread for the entire
     * download (thread-pool starvation) AND could not be cancelled, so a chapter
     * switch left the download running to completion against a recycled page.
     *
     * The bytes are downloaded eagerly, then stored in the chapter disk cache.
     */
    private suspend fun createLazyHttpStream(
        source: HttpSource,
        page: ReaderPage,
        imageUrl: String,
    ): () -> InputStream {
        val sPage = Page(page.index, page.url, imageUrl)
        if (!chapterCache.isImageInCache(imageUrl)) {
            val response = source.getImage(sPage)
            chapterCache.putImageToCache(imageUrl, response)
        }
        return { chapterCache.getImageFile(imageUrl).inputStream() }
    }

    /**
     * TachiyomiAT: cancels the in-flight translation job for a single [page].
     * Backs the per-page button's cancel affordance — lets the user stop one
     * slow/stuck page without abandoning the whole chapter. No-op if nothing is
     * running for that page.
     */
    internal fun cancelSinglePageTranslation(page: ReaderPage) {
        // TachiyomiAT: use the page's own chapter ID so the cancel lands on
        // the correct activePageJobs key — otherwise the cancel would be a
        // no-op for a page whose chapter differs from getCurrentChapter().
        val chapterId = page.chapter.chapter.id ?: return
        val pageKey = resolvePageKey(page)
        translationManager.cancelPageTranslation(chapterId, pageKey)
    }

    /**
     * TachiyomiAT: cancels ALL in-flight translation work (single-page, auto and
     * batch), stops the translator engines, and resets the merged translation
     * state to neutral. Backs the "Stop all translation" control in the reader
     * — previously there was no way for the user to stop translation at all
     * short of navigating away or disabling the master toggle.
     */
    internal fun stopAllTranslation() {
        resetAutoTranslationState()
        batchTranslationState = Translation.State.NOT_TRANSLATED
        liveTranslationState = Translation.State.NOT_TRANSLATED
        recomputeTranslationState()
        viewModelScope.launchIO {
            translationManager.cancelAllPageTranslationsOffMain(
                cancelBatchQueue = true,
                reason = "Manual stop requested from reader",
            )
            // TachiyomiAT: closeEngines = true so the cached textTranslator /
            // recognitionEngine are torn down and enginesClosed is set. Without this,
            // any config change made after stopping (engine, provider, API key, model,
            // OCR, language) was ignored on the next run — the rebuild gate never
            // fired because the old engine instances stayed cached.
            translationManager.translatorStop("user stop", closeEngines = true)
            // TachiyomiAT: evict all registered reader page streams so their captured
            // ReaderPage / ByteArray references are released. Without this, the
            // process-lifetime readerPageStreams map keeps page bytes alive after the
            // user explicitly stops translation.
            streamRegistry.clearAll()
        }
    }

    /** Re-arms the current durable batch; provider cooldowns are enforced by the manager. */
    internal fun retryCurrentBatch(force: Boolean = false) {
        val chapterId = getCurrentChapter()?.chapter?.id ?: return
        viewModelScope.launchIO {
            val resumed = translationManager.requeueTranslation(chapterId, force)
            if (resumed) {
                translationManager.startTranslation()
            } else {
                withUIContext {
                    Injekt.get<Application>().toast(
                        "Translation is still paused; retry when the provider cooldown expires",
                    )
                }
            }
        }
    }

}
