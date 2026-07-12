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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Job-scheduling + dedup + cancel surface extracted from
 * [eu.kanade.translation.TranslationManager]. Per-page work is delegated to
 * [TranslationExecutor]; the store is resolved through
 * [TranslationStoreResolver] (still owned by TranslationManager).
 */
class TranslationScheduler(
    private val executor: TranslationExecutor,
    private val storeResolver: TranslationStoreResolver,
    private val immediateStoreResolver: ((Long) -> ChapterTranslationStore?)? = null,
) : java.io.Closeable {

    override fun close() {
        scope.cancel()
    }

    companion object {
        /**
         * Max wait for cancelled jobs to unwind (their finally blocks reset a
         * stranded RUNNING status on a NonCancellable child). Bounded so chapter
         * navigation stays responsive.
         */
        private const val JOIN_TIMEOUT_MS = 2_000L
    }

    // TachiyomiAT: the prior implementation launched jobs on GlobalScope and
    // discarded the Job, so orphaned jobs from a previous chapter kept holding
    // the translator's single permit, starving all later work. Keeping the scope
    // lets auto off / chapter switch / reader close cancel actually-running work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Tracks independently-launched single-page jobs by "$chapterId:$pageKey".
    // Sequential auto-prefetch is deduped separately via [queuedPageKeys]
    // (it uses no per-page coroutine).
    private val activePageJobs = ConcurrentHashMap<String, Job>()

    // Pages queued/executing inside an ordered auto-prefetch batch. Closes the
    // gap where overlapping selection windows enqueue the same page (e.g. page 6
    // in [4,5,6], [5,6,7], and again [4,5,6]); without this, a page could run 2-4
    // times before any persisted "done" state is visible to later batches.
    private val queuedPageKeys = ConcurrentHashMap.newKeySet<String>()

    // Manager-owned auto-prefetch windows; kept here (not on the reader's
    // ViewModel scope) so they can be cancelled on chapter switch / reader close.
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
        // The prior cancelAutoJobsForChapter(chapterId) here tore down the
        // in-flight job on EVERY page-change. Because each translation is
        // serialized behind a single permit and takes seconds, eager cancel meant
        // scrolling n->n+1->n+2 cancelled each job mid-OCR, so the window could
        // never get ahead of the reader. Instead bump the generation below: that
        // marks already-running jobs stale, they finish the page they started
        // (work not wasted), then bail before the next page.
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
                // TachiyomiAT: do NOT clearTransientQueuePages here. The prior
                // call flipped every non-rendered PENDING/RUNNING/CANCELLED/FAILED
                // page to CANCELLED on every scroll — including pages a sibling
                // job from the prior generation was still translating. Staleness
                // is already handled by the generation guard below + the per-page
                // shouldSkipAutoScheduling check, so a store-wide clear is
                // redundant. Explicit cancels still do it where a full reset is wanted.
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
                        current.renderStatus != StageStatus.READY &&
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
            if (page.renderStatus == StageStatus.READY && page.hasCurrentInpaintResult) return@updatePage page
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
            if (page.renderStatus == StageStatus.READY) return@updatePage page
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
                "cleaned=${page?.cleanedImageName != null}" +
                (reason?.let { " reason=$it" } ?: "")
        }
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String, force: Boolean = false) {
        val jobKey = "${chapter.id}:$pageKey"
        // TachiyomiAT: do NOT cancel an in-flight job for this page on a
        // duplicate request. The prior activePageJobs[jobKey]?.cancel() made
        // every repeated page-selection event (scroll, re-bind, double-tap) tear
        // down and restart the same translation, blinking the overlay and
        // re-decoding the bitmap each time. The translator dedups via
        // inFlightPageKeys once the permit is acquired, so a second launch is a
        // no-op for the expensive work.
        synchronized(activePageJobs) {
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
                    executor.translateSinglePage(manga, chapter, source, pageKey, force = force)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelled (chapter switch / reader exit / Stop all) after the
                    // executor set ocrStatus=RUNNING but before a terminal state: the
                    // page is stranded RUNNING because the store is never updated as
                    // the coroutine tears down. Flag for the finally to reset it.
                    cancelledMidFlight = true
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) {
                        "TachiyomiAT single-page translation failed: pageKey=$pageKey " +
                            "chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                    }
                } finally {
                    synchronized(activePageJobs) {
                        activePageJobs.remove(jobKey)
                    }
                    if (cancelledMidFlight) {
                        // Reset stranded RUNNING on a NonCancellable child so the
                        // reset can't be torn down by the cancellation that triggered
                        // it. Guarded so a reset failure never masks the original CancellationException.
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
    }

    /**
     * Clears a non-terminal RUNNING/PENDING status for [pageKey] left by a
     * mid-flight cancellation. Cancellation is not a stage failure and must not
     * increment retryCount.
     */
    private suspend fun markPageCancelled(chapter: Chapter, pageKey: String) {
        val chapterId = chapter.id ?: return
        val store = storeResolver.resolve(chapterId) ?: return
        markPageCancelled(store, pageKey)
    }

    private suspend fun markPageCancelled(store: ChapterTranslationStore, pageKey: String) {
        // Peek first: if no entry or already terminal, nothing is stranded — skip
        // the write rather than creating a spurious FAILED entry.
        val existing = store.state.value[pageKey] ?: return
        if (existing.hasRenderedResult || existing.isStageFailed) return
        // Flip in-flight stages to CANCELLED so the reader clears the overlay and
        // auto can reschedule the page later.
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
     * TachiyomiAT: evicts a single-page job its worker abandoned (stuck in
     * uncancellable native code past its deadline). The coroutine can't be
     * interrupted, but its [activePageJobs] entry reads "active" and so blocks
     * retries via dedup. Remove it (best-effort cancel first) so the page becomes
     * eligible again. Idempotent.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        val jobKey = "$chapterId:$pageKey"
        val job = synchronized(activePageJobs) { activePageJobs.remove(jobKey) }
        try { job?.cancel() } catch (_: Throwable) {}
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
        logcat(LogPriority.WARN) {
            "TachiyomiAT evicted stuck page job: jobKey=$jobKey (worker abandoned in native/HTTP code)"
        }
    }

    /**
     * TachiyomiAT: cancels the in-flight single-page job for one [pageKey] in
     * [chapterId], if any. This is the per-page granularity [cancelPageTranslations]
     * (chapter-scoped) is too coarse for; it backs the per-page cancel button.
     *
     * Returns true if a job was actually cancelled, false if none was running
     * (already finished or deduped).
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean {
        val jobKey = "$chapterId:$pageKey"
        queuedPageKeys.remove(jobKey)
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
        val job = synchronized(activePageJobs) { activePageJobs.remove(jobKey) }
        job?.cancel()
        val autoCancelled = cancelAutoTranslations(chapterId)
        // The reader's stop action is synchronous. Flip the shared store before
        // returning so the UI cannot remain stuck on RUNNING while the cancelled
        // worker is still unwinding its coroutine finally block.
        immediateStoreResolver?.invoke(chapterId)?.let { store ->
            runBlocking { markPageCancelled(store, pageKey) }
        }
        return job != null || autoCancelled
    }

    /**
     * Cancels all in-flight single-page jobs for [chapterId] and evicts the
     * reader page streams for that chapter. Call on reader navigating away so the
     * previous chapter can't keep holding the executor's single permit.
     *
     * TachiyomiAT: suspend + bounded join. After cancelling, waits (up to
     * [JOIN_TIMEOUT_MS]) for the jobs' finally blocks. translatePage's finally
     * resets a stranded RUNNING status on a NonCancellable child, which needs the
     * coroutine to unwind. Without waiting, the previous chapter's reset could
     * land AFTER the reader subscribed to the new chapter's store — briefly
     * surfacing chapter 1's RUNNING overlay while chapter 2 is on screen.
     *
     * NOTE: store eviction is NOT done here; the store lifecycle is owned by
     * TranslationManager. The caller evicts the store if needed.
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
        synchronized(activePageJobs) {
            val iterator = activePageJobs.entries.iterator()
            while (iterator.hasNext()) {
                val (key, job) = iterator.next()
                if (key.startsWith(prefix)) {
                    job.cancel()
                    toJoin.add(job)
                    iterator.remove()
                }
            }
        }
        if (toJoin.isNotEmpty()) {
            withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                toJoin.joinAll()
            }
        }
    }

    /**
     * Cancels every in-flight single-page job and every auto-prefetch window.
     * Call on reader destroy or master translation toggle off so no orphaned work
     * keeps running.
     */
    fun cancelAllPageTranslations() {
        queuedPageKeys.clear()
        val autoIterator = activeAutoJobs.entries.iterator()
        while (autoIterator.hasNext()) {
            val (_, job) = autoIterator.next()
            job.cancel()
            autoIterator.remove()
        }
        synchronized(activePageJobs) {
            val iterator = activePageJobs.entries.iterator()
            while (iterator.hasNext()) {
                val (_, job) = iterator.next()
                job.cancel()
                iterator.remove()
            }
        }
    }
}
