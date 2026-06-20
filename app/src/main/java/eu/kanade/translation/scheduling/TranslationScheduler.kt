package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.TranslationPageRequest
import eu.kanade.translation.TranslationSession
import eu.kanade.translation.model.PageLifecycle
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.cancelInFlightStages
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.lifecycle
import eu.kanade.translation.model.shouldSkipAutoScheduling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TachiyomiAT: owns single-page (and auto) translation jobs so they can be
 * cancelled on chapter change, reader exit, or translation disable.
 *
 * Extracted from [eu.kanade.translation.TranslationManager] to give the job-
 * scheduling + dedup + cancel surface its own type. Behavior is byte-for-byte
 * identical to the previous inline implementation on TranslationManager:
 *
 *  - current-page-first auto window with generation-bounded staleness,
 *  - dedup via [queuedPageKeys] (queued/running inside a batch) and
 *    [activePageJobs] (independently-launched single-page jobs),
 *  - cancel paths that reset a stranded RUNNING status on a NonCancellable
 *    child so cancellation never leaves a page stuck RUNNING forever,
 *  - soft-skip (no store write, no retryCount bump) when a stream is
 *    unavailable for an online page.
 *
 * The actual per-page work (decode/OCR/translate/inpaint/render) is delegated
 * to [TranslationExecutor]; the store the scheduler reads/writes is resolved
 * through [TranslationStoreResolver] (still owned by TranslationManager, which
 * also owns store lifecycle).
 */
class TranslationScheduler(
    private val executor: TranslationExecutor,
    private val storeResolver: TranslationStoreResolver,
) {

    companion object {
        /**
         * Maximum time [cancelPageTranslations] will wait for a chapter's
         * cancelled jobs to finish unwinding (their finally blocks reset a
         * stranded page's RUNNING status on a NonCancellable child). Bounded so
         * chapter navigation stays responsive even if a job is slow to tear down.
         */
        private const val JOIN_TIMEOUT_MS = 2_000L
    }

    /**
     * TachiyomiAT: the previous implementation launched single-page jobs on
     * `GlobalScope` and discarded the returned [Job], which meant orphaned jobs
     * from a previous chapter kept running (and kept holding the translator's
     * single permit), starving all later work — appearing as "translate does
     * nothing". Keeping the scope here makes auto off, chapter switch, memory
     * pressure, and reader close able to cancel the work that is actually
     * running.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Tracks independently-launched single-page jobs by `"$chapterId:$pageKey"`.
     * Sequential auto-prefetch does not create one coroutine per page, so it is
     * deduped separately by [queuedPageKeys] below.
     */
    private val activePageJobs = ConcurrentHashMap<String, Job>()

    /**
     * Tracks pages that are queued or currently executing inside an ordered
     * auto-prefetch batch. This closes the gap where page selection events
     * enqueue overlapping windows:
     *
     * page 4 -> [4,5,6]
     * page 5 -> [5,6,7]
     * page 4 again -> [4,5,6]
     *
     * Before this set, only the actively executing page was protected, so page
     * 6 could sit in multiple pending batches and run two to four times before
     * any persisted "done" state was visible to later batches.
     */
    private val queuedPageKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * Manager-owned auto-prefetch windows. These are the jobs the reader used to
     * launch on its own ViewModel scope; keeping them here makes auto off,
     * chapter switch, memory pressure, and reader close able to cancel the work
     * that is actually running.
     */
    private val activeAutoJobs = ConcurrentHashMap<String, Job>()
    private val autoWindowIds = AtomicLong(0L)
    private val autoGenerations = ConcurrentHashMap<Long, AtomicLong>()

    fun requestAutoWindow(
        session: TranslationSession,
        requests: List<TranslationPageRequest>,
    ) {
        if (requests.isEmpty()) return
        val chapterId = session.chapter.id ?: return
        // TachiyomiAT: do NOT eagerly cancel prior auto jobs for this chapter.
        // The previous implementation called `cancelAutoJobsForChapter(chapterId)`
        // here, which tore down the in-flight translation job on EVERY page-change
        // event. Because each translation (OCR + translate + inpaint + render) is
        // serialized behind a single permit and takes seconds, eagerly cancelling
        // meant the window could never get ahead of the reader: scrolling from
        // page n to n+1 cancelled the job mid-OCR on page n, the next window
        // started n+1, scrolling to n+2 cancelled that, and so on — translation
        // appeared to stall and never prefetched past the current page.
        //
        // Instead, rely on the generation mechanism (unchanged from the original
        // TranslationManager design): bumping the generation below marks every
        // already-running job stale; each such job finishes the page it already
        // started (the work is not wasted), then detects the stale generation at
        // the top of its loop and bails before starting the next page. The new
        // window's pages are reserved fresh because queuedPageKeys is cleared.
        val generation = autoGenerations.computeIfAbsent(chapterId) { AtomicLong(0L) }.incrementAndGet()
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") }

        val accepted = mutableListOf<Pair<String, TranslationPageRequest>>()
        val distinctRequests = requests.distinctBy { it.id }
            .sortedWith(compareBy<TranslationPageRequest> { it.priority }.thenBy { it.id.pageIndex })

        for (request in distinctRequests) {
            val current = session.store.state.value[request.storageKey]
            if (current != null && current.shouldSkipAutoScheduling) {
                logAutoDecision("skipped", request, current, request.streamAvailable)
                continue
            }

            val manualJobKey = "${request.id.chapterId}:${request.storageKey}"
            val manualJob = activePageJobs[manualJobKey]
            if (manualJob != null && manualJob.isActive) {
                logAutoDecision("skipped", request, current, request.streamAvailable, "manual-active")
                continue
            }
            if (manualJob != null) activePageJobs.remove(manualJobKey)

            val reservationKey = autoReservationKey(request, generation)
            if (!queuedPageKeys.add(reservationKey)) {
                logAutoDecision("skipped", request, current, request.streamAvailable, "already-queued")
                continue
            }

            accepted.add(reservationKey to request)
            logAutoDecision("scheduled", request, current, request.streamAvailable)
        }

        if (accepted.isEmpty()) return

        val jobKey = "auto:$chapterId:$generation:${session.key}:${autoWindowIds.incrementAndGet()}"
        val job = scope.launch {
            val completedReservations = mutableSetOf<String>()
            try {
                // TachiyomiAT: do NOT clear transient queue pages here. The
                // previous implementation called `clearTransientQueuePages(...)`
                // as the first step of every auto-window job, which flipped any
                // PENDING/RUNNING/CANCELLED/FAILED page (without a rendered
                // result) to CANCELLED — including pages a sibling job from the
                // prior generation was still actively translating. Combined with
                // the now-removed eager cancel, this churned every page's status
                // on every scroll event. Staleness here is already handled
                // correctly by (a) the generation guard below and (b) the
                // per-page `shouldSkipAutoScheduling` check, so an explicit
                // store-wide clear is both redundant and disruptive. Explicit
                // user cancels (cancelPageTranslations / cancelAllPageTranslations)
                // still call clearTransientQueuePages where a full reset is wanted.
                for ((reservationKey, request) in accepted) {
                    coroutineContext.ensureActive()
                    if (autoGenerations[chapterId]?.get() != generation) {
                        logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], request.streamAvailable, "stale-generation")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        break
                    }
                    val current = session.store.state.value[request.storageKey]
                    if (current != null && current.shouldSkipAutoScheduling) {
                        logAutoDecision("skipped", request, current, request.streamAvailable, "completed-while-queued")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }
                    val manualJobKey = "${request.id.chapterId}:${request.storageKey}"
                    val manualJob = activePageJobs[manualJobKey]
                    if (manualJob != null && manualJob.isActive) {
                        logAutoDecision("skipped", request, current, request.streamAvailable, "manual-active-while-queued")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }
                    if (manualJob != null) activePageJobs.remove(manualJobKey)

                    markAutoPageStarting(session.store, request.storageKey, current)

                    if (current != null &&
                        current.hasRecognizedTranslation &&
                        current.renderedImageName == null &&
                        current.cleanedImageName != null &&
                        current.hasCurrentInpaintResult
                    ) {
                        try {
                            executor.translateSinglePage(
                                session.manga,
                                session.chapter,
                                session.source,
                                request.storageKey,
                                force = false,
                            )
                        } catch (e: CancellationException) {
                            markPageCancelled(session.chapter, request.storageKey)
                            logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], request.streamAvailable)
                            throw e
                        } catch (e: Throwable) {
                            logAutoDecision("failed:resume-render", request, session.store.state.value[request.storageKey], request.streamAvailable)
                            logcat(LogPriority.ERROR, e) {
                                "TachiyomiAT auto resume render failed: id=${request.id} storageKey=${request.storageKey}"
                            }
                        } finally {
                            completedReservations.add(reservationKey)
                            queuedPageKeys.remove(reservationKey)
                        }
                        continue
                    }

                    val streamFn = try {
                        request.streamProvider()
                    } catch (e: CancellationException) {
                        logAutoDecision("cancelled", request, current, request.streamAvailable)
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.WARN, e) {
                            "TachiyomiAT auto stream provider failed: id=${request.id} " +
                                "index=${request.id.pageIndex} storageKey=${request.storageKey}"
                        }
                        null
                    }

                    if (streamFn == null) {
                        logAutoDecision("soft-skip:no-stream", request, current, false)
                        markPageAutoSoftSkipped(session.store, request.storageKey)
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        break
                    }

                    try {
                        executor.translateSinglePageFromStream(
                            session.manga,
                            session.chapter,
                            session.source,
                            request.storageKey,
                            streamFn,
                            force = false,
                        )
                    } catch (e: CancellationException) {
                        markPageCancelled(session.chapter, request.storageKey)
                        logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], true)
                        throw e
                    } catch (e: Throwable) {
                        logAutoDecision("failed:pipeline", request, session.store.state.value[request.storageKey], true)
                        logcat(LogPriority.ERROR, e) {
                            "TachiyomiAT auto page translation failed: id=${request.id} " +
                                "storageKey=${request.storageKey} chapter=${session.chapter.name} " +
                                "manga=${session.manga.title} source=${session.source.id}"
                        }
                        break
                    } finally {
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                    }
                    if (autoGenerations[chapterId]?.get() != generation) {
                        break
                    }
                }
            } catch (e: CancellationException) {
                for ((reservationKey, request) in accepted) {
                    if (reservationKey !in completedReservations) {
                        logAutoDecision(
                            "cancelled",
                            request,
                            session.store.state.value[request.storageKey],
                            request.streamAvailable,
                        )
                    }
                }
                throw e
            } finally {
                accepted.forEach { (reservationKey, _) -> queuedPageKeys.remove(reservationKey) }
                activeAutoJobs.remove(jobKey)
            }
        }
        activeAutoJobs[jobKey] = job
    }

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean {
        val jobPrefix = chapterId?.let { "auto:$it:" }
        val queuePrefix = chapterId?.let { "auto:$it:" }
        var cancelled = false

        if (chapterId == null) {
            autoGenerations.values.forEach { it.incrementAndGet() }
        } else {
            autoGenerations.computeIfAbsent(chapterId) { AtomicLong(0L) }.incrementAndGet()
        }

        val jobIterator = activeAutoJobs.entries.iterator()
        while (jobIterator.hasNext()) {
            val (key, job) = jobIterator.next()
            if (jobPrefix == null || key.startsWith(jobPrefix)) {
                job.cancel()
                jobIterator.remove()
                cancelled = true
            }
        }

        if (queuePrefix == null) {
            queuedPageKeys.removeIf { it.startsWith("auto:") }
        } else {
            queuedPageKeys.removeIf { it.startsWith(queuePrefix) }
        }

        return cancelled
    }

    private fun autoReservationKey(request: TranslationPageRequest, generation: Long): String {
        val safeStorageKey = request.storageKey.replace(':', '_')
        return "auto:${request.id.chapterId}:$generation:${request.id.sourceId}:${request.id.mangaId}:${request.id.pageIndex}:$safeStorageKey"
    }

    private suspend fun markAutoPageStarting(
        store: ChapterTranslationStore,
        pageKey: String,
        current: PageTranslation?,
    ) {
        if (current != null && current.shouldSkipAutoScheduling) return
        store.updatePage(pageKey) { existing ->
            val page = existing ?: PageTranslation(sourceFileName = pageKey)
            if (page.renderedImageName != null && page.hasCurrentInpaintResult) return@updatePage page
            page.apply {
                sourceFileName = pageKey
                errorMessage = null
                when {
                    hasRecognizedTranslation && cleanedImageName != null -> {
                        renderStatus = StageStatus.RUNNING
                    }
                    hasRecognizedTranslation -> {
                        inpaintStatus = StageStatus.RUNNING
                        renderStatus = StageStatus.PENDING
                    }
                    ocrStatus != StageStatus.READY -> {
                        ocrStatus = StageStatus.RUNNING
                        translationStatus = StageStatus.PENDING
                        inpaintStatus = StageStatus.PENDING
                        renderStatus = StageStatus.PENDING
                    }
                    translationStatus != StageStatus.READY -> {
                        translationStatus = StageStatus.RUNNING
                    }
                    else -> {
                        renderStatus = StageStatus.RUNNING
                    }
                }
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    private suspend fun markPageAutoSoftSkipped(store: ChapterTranslationStore, pageKey: String) {
        store.updatePage(pageKey) { existing ->
            val page = existing ?: return@updatePage PageTranslation(
                sourceFileName = pageKey,
                ocrStatus = StageStatus.CANCELLED,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
            if (page.renderedImageName != null) return@updatePage page
            page.apply {
                cancelInFlightStages()
                errorMessage = null
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    private fun logAutoDecision(
        decision: String,
        request: TranslationPageRequest,
        page: PageTranslation?,
        streamAvailable: Boolean?,
        reason: String? = null,
    ) {
        logcat(LogPriority.INFO) {
            "TachiyomiAT auto page $decision: id=${request.id} index=${request.id.pageIndex} " +
                "storageKey=${request.storageKey} lifecycle=${page?.lifecycle ?: PageLifecycle.Pending} " +
                "retry=${page?.retryCount ?: 0} streamAvailable=${streamAvailable ?: "unknown"} " +
                "rendered=${page?.renderedImageName != null} cleaned=${page?.cleanedImageName != null}" +
                (reason?.let { " reason=$it" } ?: "")
        }
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) {
        val jobKey = "${chapter.id}:$pageKey"
        // TachiyomiAT: do NOT cancel an in-flight job for this same page on a
        // duplicate request. The previous `activePageJobs[jobKey]?.cancel()`
        // here meant every repeated page-selection event (auto-mode firing on
        // scroll, a page-holder re-binding, or a rapid double-tap) tore down and
        // restarted the same translation — oscillating between cancel/restart,
        // visibly blinking the processing overlay, and re-decoding the bitmap
        // each time. The translator already dedups via inFlightPageKeys once the
        // permit is acquired, so a second launch for the same page is a no-op
        // for the expensive work. We only clear/replace a prior entry when it is
        // no longer active (completed or already cancelled), keeping the
        // map free of dead jobs while leaving a genuine in-flight job alone.
        val existing = activePageJobs[jobKey]
        if (existing != null && existing.isActive) {
            logcat(LogPriority.DEBUG) { "translatePage: skipping $jobKey (already active)" }
            return
        }
        if (existing != null) {
            activePageJobs.remove(jobKey)
        }
        logcat(LogPriority.DEBUG) { "translatePage: launching $jobKey" }
        val job = scope.launch {
            var cancelledMidFlight = false
            try {
                executor.translateSinglePage(manga, chapter, source, pageKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The job was cancelled (chapter switch, reader exit, Stop all).
                // If this happened after the executor wrote ocrStatus=RUNNING
                // but before it reached a terminal state, the page is stranded
                // RUNNING — the store never gets updated because the coroutine
                // is torn down. Flag it so the finally can reset the status,
                // matching the single-page timeout handling in the executor.
                cancelledMidFlight = true
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiAT single-page translation failed: pageKey=$pageKey " +
                        "chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                }
            } finally {
                activePageJobs.remove(jobKey)
                if (cancelledMidFlight) {
                    // Best-effort: clear a stranded RUNNING status so the reader's
                    // overlay/spinner and disabled translate icon recover. Runs on
                    // a non-cancellable child launch so the reset itself can't be
                    // torn down by the cancellation that triggered it. Guarded so
                    // a reset failure never masks the original CancellationException.
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            markPageCancelled(chapter, pageKey)
                        }
                    } catch (resetError: Throwable) {
                        logcat(LogPriority.WARN, resetError) {
                            "TachiyomiAT reset of stranded page status failed: pageKey=$pageKey"
                        }
                    }
                }
            }
        }
        activePageJobs[jobKey] = job
    }

    /**
     * Clears a non-terminal RUNNING/PENDING status for [pageKey] left behind
     * when translation was cancelled mid-flight. Normal cancellation is not a
     * stage failure and must not increment retryCount.
     */
    private suspend fun markPageCancelled(chapter: Chapter, pageKey: String) {
        val chapterId = chapter.id ?: return
        val store = storeResolver.resolve(chapterId) ?: return
        // Peek the live state first. If the page has no entry (it was never
        // tracked) or is already terminal, there is nothing stranded to reset —
        // skip the write entirely rather than creating a spurious FAILED entry.
        val existing = store.state.value[pageKey] ?: return
        if (existing.hasRenderedResult || existing.isStageFailed) return
        // Stranded RUNNING/PENDING: flip the active stage(s) to CANCELLED so the
        // reader clears the overlay and auto can schedule the page again later.
        store.updatePage(pageKey) { current ->
            // Re-check inside the lock in case it changed between peek and write.
            val cur = current ?: return@updatePage PageTranslation(
                sourceFileName = pageKey,
                ocrStatus = StageStatus.CANCELLED,
                errorMessage = "Translation cancelled",
                updatedAt = System.currentTimeMillis(),
            )
            if (cur.hasRenderedResult || cur.isStageFailed) return@updatePage cur
            cur.apply {
                cancelInFlightStages()
                errorMessage = "Translation cancelled"
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * TachiyomiAT: evicts a single-page translation job that its worker has
     * abandoned (stuck in uncancellable native code past its deadline). The
     * coroutine itself can't be interrupted, but its [activePageJobs] entry is
     * stale: it reads as "active" so [translatePage]'s dedup keeps rejecting
     * retries for this page. Remove it (best-effort cancel first) so the page
     * becomes eligible for translation again. Idempotent.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        val jobKey = "$chapterId:$pageKey"
        val job = activePageJobs.remove(jobKey)
        try { job?.cancel() } catch (_: Throwable) {}
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
        logcat(LogPriority.WARN) {
            "TachiyomiAT evicted stuck page job: jobKey=$jobKey (worker abandoned in native/HTTP code)"
        }
    }

    /**
     * TachiyomiAT: cancels the in-flight single-page translation job for one
     * specific [pageKey] within [chapterId], if any. This is the per-page
     * granularity that [cancelPageTranslations] (chapter-scoped) is too coarse
     * for: it backs the per-page button's cancel affordance, so a user can stop
     * a single slow/stuck page without abandoning the whole chapter.
     *
     * Returns true if a job was actually cancelled, false if none was running
     * for that page (e.g. it already finished or the translator deduped it).
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean {
        val jobKey = "$chapterId:$pageKey"
        queuedPageKeys.remove(jobKey)
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
        val job = activePageJobs.remove(jobKey)
        job?.cancel()
        val autoCancelled = cancelAutoTranslations(chapterId)
        return job != null || autoCancelled
    }

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and
     * evicts the reader page streams registered for that chapter. Also drops the
     * shared [ChapterTranslationStore] for this chapter so it doesn't leak
     * across chapter navigations. Call this when the reader navigates away from
     * a chapter so the previous chapter's work can no longer hold the
     * executor's single permit.
     *
     * TachiyomiAT: suspend + bounded join. After cancelling, it waits (up to
     * [JOIN_TIMEOUT_MS]) for those jobs to actually finish their finally blocks.
     * translatePage's finally resets a stranded page's RUNNING status on a
     * NonCancellable child, which itself needs the coroutine to unwind. Without
     * waiting, the previous chapter's reset could land AFTER the reader
     * subscribed to the new chapter's store — briefly surfacing chapter 1's
     * state (or its RUNNING overlay) while chapter 2 is on screen. The timeout
     * keeps chapter navigation responsive even if a job is slow to unwind.
     *
     * NOTE: store eviction is NOT done here — the store lifecycle is still owned
     * by TranslationManager (it also unregisters stores on chapter close). The
     * caller is responsible for evicting the store if needed.
     */
    suspend fun cancelPageTranslations(chapterId: Long) {
        val prefix = "$chapterId:"
        queuedPageKeys.removeIf { it.startsWith(prefix) }
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") }
        val toJoin = mutableListOf<Job>()
        val autoIterator = activeAutoJobs.entries.iterator()
        while (autoIterator.hasNext()) {
            val (key, job) = autoIterator.next()
            if (key.startsWith("auto:$chapterId:")) {
                job.cancel()
                toJoin.add(job)
                autoIterator.remove()
            }
        }
        val iterator = activePageJobs.entries.iterator()
        while (iterator.hasNext()) {
            val (key, job) = iterator.next()
            if (key.startsWith(prefix)) {
                job.cancel()
                toJoin.add(job)
                iterator.remove()
            }
        }
        // Wait for the cancelled jobs to finish their finally blocks (including
        // the stranded-status reset) so the previous chapter is fully wound down
        // before the caller subscribes to the next chapter's store. Bounded so a
        // stuck unwind can't hang chapter navigation.
        if (toJoin.isNotEmpty()) {
            withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                toJoin.joinAll()
            }
        }
    }

    /**
     * Cancels every in-flight single-page translation job and every auto-prefetch
     * window. Call this when the reader is destroyed or the master translation
     * toggle is switched off, so no orphaned work keeps running in the
     * background.
     */
    fun cancelAllPageTranslations() {
        queuedPageKeys.clear()
        val autoIterator = activeAutoJobs.entries.iterator()
        while (autoIterator.hasNext()) {
            val (_, job) = autoIterator.next()
            job.cancel()
            autoIterator.remove()
        }
        val iterator = activePageJobs.entries.iterator()
        while (iterator.hasNext()) {
            val (_, job) = iterator.next()
            job.cancel()
            iterator.remove()
        }
    }
}
