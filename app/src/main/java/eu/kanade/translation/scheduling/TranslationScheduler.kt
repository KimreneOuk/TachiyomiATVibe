package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.cancelInFlightStages
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reader scheduling facade over the single [ReaderTranslationQueue].
 *
 * Since the Phase 4 S2 cutover the queue owns job scheduling, deduplication,
 * promotion, and cancellation; since S3 the reader callers submit through the
 * typed facade — [bindReaderQueueSession], [submitReaderPage],
 * [updateReaderViewport], [setReaderAutoEnabled] and [reconcileReaderQueue] —
 * which resolves the admitted session token and delegates once. The temporary
 * [ReaderQueueSnapshotAdapter] projection ([updateAutoWindow]/[autoSnapshot])
 * lost its last reader-UI consumer in S3 and is removed in S4. The rolling
 * coordinator is no longer constructed or referenced; its class remains
 * compiled until S4 retirement.
 */
class TranslationScheduler(
    private val executor: TranslationExecutor,
    private val storeResolver: TranslationStoreResolver,
    private val immediateStoreResolver: ((Long) -> ChapterTranslationStore?)? = null,
    private val readerSessionRejectionReason: (Long?) -> String? = { null },

    /**
     * Source factory resolver for manual taps (registry.peek in production).
     * The returned factory may itself resolve to null at invocation time; the
     * pipeline then falls back to its own registry/downloaded-dir resolution
     * on the worker. When absent, manual taps dispatch with a pipeline-resolved
     * factory.
     */
    private val manualStreamProvider: ((Manga, Chapter, HttpSource, String) -> (() -> InputStream)?)? = null,
) : java.io.Closeable {

    override fun close() {
        // Bounded drain: queued work is cancelled with typed terminals; paid
        // workers already admitted retain their original Job contract and the
        // drain handle is bounded by the queue's cancellation wait.
        runBlocking { queue.closeAndDrain() }
        scope.cancel()
    }

    // the prior implementation launched jobs on GlobalScope and
    // discarded the Job, so orphaned jobs from a previous chapter kept holding
    // the translator's single permit, starving all later work. Keeping the scope
    // lets auto off / chapter switch / reader close cancel actually-running work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queue = ReaderTranslationQueue(
        executor = executor,
        scope = scope,
        workerDispatcher = Dispatchers.IO,
        storeResolver = storeResolver,
        nowEpochMs = System::currentTimeMillis,
    )

    /** Recent manual page outcomes let the reader distinguish failures from running jobs. */
    private val manualOutcomes: MutableMap<String, SinglePageOutcome> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, SinglePageOutcome>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SinglePageOutcome>): Boolean =
                    size > MANUAL_OUTCOME_MAP_CAP
            },
        )

    /** Returns the last outcome for this page; the caller maps it to reader-facing UI truth. */
    fun manualOutcomeFor(chapterId: Long, pageKey: String): SinglePageOutcome? {
        sessions[chapterId]?.let { session ->
            queue.outcomes(session.token).value[pageKey]?.let { return it }
        }
        return synchronized(manualOutcomes) { manualOutcomes["$chapterId:$pageKey"] }
    }

    fun recordManualOutcome(chapterId: Long, pageKey: String, outcome: SinglePageOutcome) {
        synchronized(manualOutcomes) { manualOutcomes["$chapterId:$pageKey"] = outcome }
    }

    private class ActiveQueueSession(
        val token: ReaderSessionToken,
        val chapterId: Long,
        val sessionKey: String,
        val store: ChapterTranslationStore?,
        val computeClass: TranslatorComputeClass,
    )

    // One queue binding exists at a time; this registry tracks the token last
    // bound per chapter so the manual facade can resolve the admitted token and
    // lifecycle paths can invalidate sessions without touching the queue's
    // internal state.
    private val sessions = ConcurrentHashMap<Long, ActiveQueueSession>()

    private val currentToken = AtomicReference<ReaderSessionToken?>(null)
    private val readerGeneration = AtomicLong(0L)
    private val ownerVersions = AtomicLong(0L)
    private val windowVersions = AtomicLong(0L)

    private val autoCoordinatorLock = Any()

    /**
     * Cancellation is attributed before queue sessions are cancelled because
     * the same-chapter suppression window must cover the whole signalling
     * path, not just the control mutation.
     */
    private val chapterCancellationEpochs = mutableMapOf<Long, Long>()
    private var globalAutoCancellationInFlight = false
    private var autoCancellationEpoch = 0L
    private var globalAutoCancellationEpoch = 0L
    private var readerStopInFlight = false

    private val queueWindow = MutableStateFlow<ReaderQueueWindowSpec?>(null)

    /**
     * Live auto-translation snapshot projected from queue demand/outcome truth
     * plus the durable store. Null when no window is armed. Observed by the
     * reader through [TranslationManager].
     */
    val autoSnapshot: StateFlow<AutoTranslationSnapshot?> = queueWindow
        .flatMapLatest { spec ->
            if (spec == null) {
                flowOf(null)
            } else {
                combine(queue.slotEventFlow, spec.session.store.state) { _, storePages ->
                    val view = queue.observeSlots(spec.token)
                    ReaderQueueSnapshotAdapter.project(spec, view, storePages, System.currentTimeMillis())
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Accepts a desired-window update (visible page + ahead target). Rebinds
     * the queue session when the owner identity changed and delegates demand
     * reconciliation to [ReaderTranslationQueue.updateViewport].
     *
     * Manual arbitration: the queue owns it natively — a manual tap promotes
     * the same demand (explicit priority) instead of hiding the page from a
     * separate coordinator, so the resolver is passed through unwrapped.
     *
     * Reader-UI callers moved to the typed facade in S3
     * ([bindReaderQueueSession]/[updateReaderViewport]); this entry remains for
     * the manager window gate tests until S4 retirement.
     */
    fun updateAutoWindow(
        identity: AutoChapterIdentity,
        visiblePageIndex: Int,
        configuredAheadTarget: Int,
        pageCount: Int,
        session: TranslationSession,
        pageResolver: (Int) -> ReaderPageWork?,
        computeClass: TranslatorComputeClass,
    ) {
        val chapterId = session.chapter.id ?: return
        val existing = sessions[chapterId]
        val reuse = existing != null &&
            existing.token == currentToken.get() &&
            existing.sessionKey == session.key &&
            existing.store === session.store &&
            existing.computeClass == computeClass
        val token = bindReaderQueueSession(chapterId, session, computeClass) ?: return
        val ownerVersion = if (reuse) {
            queueWindow.value?.ownerVersion ?: ownerVersions.incrementAndGet()
        } else {
            ownerVersions.incrementAndGet()
        }
        val windowVersion = windowVersions.incrementAndGet()
        queueWindow.value = ReaderQueueWindowSpec(
            identity = identity,
            session = session,
            token = token,
            visiblePageIndex = visiblePageIndex,
            configuredAheadTarget = configuredAheadTarget,
            pageCount = pageCount,
            ownerVersion = ownerVersion,
            windowVersion = windowVersion,
            pageKeysByIndex = ConcurrentHashMap(),
        )
        queue.updateViewport(
            session = token,
            pageIndex = visiblePageIndex,
            pageCount = pageCount,
            isAutoEnabled = true,
            resolve = pageResolver,
        )
    }

    /**
     * Binds (or reuses) the queue session for [chapterId] and returns its token.
     * Reuse requires the same admitted session key, the same store instance and
     * the same compute class — a changed value rebinds, which cancels the old
     * binding's demand set under the queue's epoch drain. Suppressed during an
     * in-flight reader stop, a global auto cancellation, or a chapter
     * cancellation epoch; returns null in those cases.
     */
    fun bindReaderQueueSession(
        chapterId: Long,
        session: TranslationSession,
        computeClass: TranslatorComputeClass,
    ): ReaderSessionToken? {
        val rejectionReason = readerSessionRejectionReason(chapterId)
        if (rejectionReason != null) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT reader auto admission rejected at scheduler gate: " +
                    "reason=$rejectionReason chapterId=$chapterId"
            }
            return null
        }
        synchronized(autoCoordinatorLock) {
            if (readerStopInFlight || globalAutoCancellationInFlight) return null
            if (chapterId in chapterCancellationEpochs) return null
        }
        val existing = sessions[chapterId]
        val reuse = existing != null &&
            existing.token == currentToken.get() &&
            existing.sessionKey == session.key &&
            existing.store === session.store &&
            existing.computeClass == computeClass
        if (reuse && existing != null) return existing.token
        val fresh = ReaderSessionToken(
            chapterIdentity = session.key,
            readerGeneration = readerGeneration.incrementAndGet(),
        )
        queue.bindSession(fresh, session.manga, session.chapter, session.source, computeClass)
        currentToken.set(fresh)
        sessions[chapterId] = ActiveQueueSession(fresh, chapterId, session.key, session.store, computeClass)
        return fresh
    }

    /** Typed manual/auto page submission for a captured reader session. */
    fun submitReaderPage(
        token: ReaderSessionToken,
        work: ReaderPageWork,
        trigger: ReaderTrigger,
        force: Boolean = false,
    ): ReaderSubmission = queue.submitCurrent(token, work, trigger, force)

    /** Arms or refreshes the auto window demand set (exactly N, N+1) for the captured session. */
    fun updateReaderViewport(
        token: ReaderSessionToken,
        pageIndex: Int,
        pageCount: Int,
        resolve: (Int) -> ReaderPageWork?,
    ) {
        queue.updateViewport(token, pageIndex, pageCount, isAutoEnabled = true, resolve = resolve)
    }

    /** Queue-native auto toggle: prunes automatic demand when disabled, arms viewport updates when enabled. */
    fun setReaderAutoEnabled(token: ReaderSessionToken, enabled: Boolean) {
        queue.setAutoEnabled(token, enabled)
    }

    /** Re-drives queued demands for the captured session after a source/memory/lifecycle event. */
    fun reconcileReaderQueue(
        token: ReaderSessionToken,
        resolve: (Int) -> ReaderPageWork?,
    ) {
        queue.reconcile(token, resolve)
    }

    /**
     * Stops the queue session for [chapterId] (or every session when null) and
     * clears the window so [autoSnapshot] settles to null. The rolling
     * coordinator shutdown this method used to drive is retired; the name
     * remains for compatibility until S4.
     */
    fun shutdownAutoCoordinator(chapterId: Long? = null) {
        if (chapterId == null) {
            queueWindow.value = null
            sessions.keys.forEach { invalidateQueueSession(it) }
        } else {
            invalidateQueueSession(chapterId)
        }
    }

    /**
     * Invalidates the queue session bound to [chapterId]: cancels its demand
     * set (retaining admitted paid work under its own lifecycle contract) and
     * detaches the window projection. Batch admission, restore admission, and
     * the download re-key path route here instead of coordinator shutdown.
     */
    fun invalidateQueueSession(chapterId: Long) {
        val removed = sessions.remove(chapterId) ?: return
        if (currentToken.get() == removed.token) currentToken.set(null)
        queue.cancelSessionAndForget(removed.token)
        synchronized(autoCoordinatorLock) {
            val window = queueWindow.value
            if (window != null && window.identity.chapterId == chapterId) {
                queueWindow.value = null
            }
        }
    }

    /**
     * Re-opens admission for the armed window after an external signal such as
     * memory recovery, reader foreground resume, or source readiness.
     */
    fun reconcileAutoWindow(admissionGuard: (Long) -> Boolean = { true }) {
        val spec = queueWindow.value ?: return
        synchronized(autoCoordinatorLock) {
            if (readerStopInFlight || globalAutoCancellationInFlight) return
        }
        if (!admissionGuard(spec.identity.chapterId)) return
        queue.reconcile(spec.token)
    }

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean {
        // Reserve cancellation under the lifecycle lock, then invoke it
        // outside that lock. Matching window updates either complete before
        // this reservation or observe it and are suppressed.
        val cancellation = beginAutoCancellation(chapterId)
        var cancelled = cancellation != null
        try {
            cancellation?.let(::performAutoCancellation)
            // fast non-blocking in-memory flip on the store first so the
            // reader overlay/dim clears immediately on the current frame. The
            // AUTO-fenced write never touches queue-owned (MANUAL-leased)
            // pages; durable persistence is dispatched asynchronously to IO.
            if (chapterId != null) {
                val inMemoryFlipped = immediateStoreResolver?.invoke(chapterId)?.fastCancelInFlightStagesInMemory(
                    origin = PageWriteOrigin.AUTO,
                    cancellationReason = "Auto translation cancelled",
                ) ?: 0
                if (inMemoryFlipped > 0) {
                    cancelled = true
                }
                if (cancelled) {
                    scope.launch {
                        try {
                            markChapterCancelledAsync(
                                chapterId,
                                origin = PageWriteOrigin.AUTO,
                                reason = "Auto translation cancelled",
                            )
                        } catch (e: Throwable) {
                            logcat(LogPriority.WARN, e) { "Failed to drain cancellation for $chapterId" }
                        }
                    }
                }
            }
            return cancelled
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String, force: Boolean = false) {
        val rejectionReason = readerSessionRejectionReason(chapter.id)
        if (rejectionReason != null) {
            chapter.id?.let { chapterId ->
                recordManualOutcome(
                    chapterId,
                    pageKey,
                    SinglePageOutcome.Rejected(
                        null,
                        PageRejectionKind.SESSION_DENIED,
                        "reader session rejected: $rejectionReason",
                    ),
                )
            }
            logcat(LogPriority.INFO) {
                "TachiyomiAT reader manual admission rejected at scheduler gate: " +
                    "reason=$rejectionReason chapterId=${chapter.id} pageKey=$pageKey"
            }
            return
        }
        val chapterId = chapter.id ?: return
        // Resolve the admitted token: reuse the chapter's live binding, or bind
        // a manual-only session so a tap without an armed auto window still
        // dispatches through the queue (no second worker path).
        val active = sessions[chapterId]
        val token = if (active != null && active.token == currentToken.get()) {
            active.token
        } else {
            val fresh = ReaderSessionToken(
                chapterIdentity = "${source.id}:${manga.id}:$chapterId",
                readerGeneration = readerGeneration.incrementAndGet(),
            )
            queue.bindSession(fresh, manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
            currentToken.set(fresh)
            sessions[chapterId] = ActiveQueueSession(fresh, chapterId, fresh.chapterIdentity, null, TranslatorComputeClass.REMOTE_IO)
            fresh
        }
        // The controller registers the reader stream in the stream registry
        // right before tapping; the provider peeks it. A null factory follows
        // the settled queue contract: the demand stays pending and a later
        // tap or viewport event refreshes it. readerPageIndex uses the
        // documented -1 manual sentinel (null trace index).
        val streamFn = manualStreamProvider?.invoke(manga, chapter, source, pageKey)
        when (
            val submission = queue.submitCurrent(
                token,
                ReaderPageWork(pageKey, -1, streamFn),
                ReaderTrigger.MANUAL_TAP,
                force,
            )
        ) {
            is ReaderSubmission.Rejected -> recordManualOutcome(chapterId, pageKey, submission.outcome)
            else -> Unit
        }
    }

    /**
     * Clears a non-terminal RUNNING/PENDING status for [pageKey] left by a
     * mid-flight cancellation. Cancellation is not a stage failure and must not
     * increment retryCount.
     */
    private suspend fun markPageCancelled(
        chapter: Chapter,
        pageKey: String,
        reason: String = "Translation cancelled",
        requiredOrigin: PageWriteOrigin? = null,
    ) {
        val chapterId = chapter.id ?: return
        val store = storeResolver.resolve(chapterId) ?: return
        markPageCancelled(store, pageKey, reason, requiredOrigin)
    }

    private suspend fun markPageCancelled(
        store: ChapterTranslationStore,
        pageKey: String,
        reason: String = "Translation cancelled",
        requiredOrigin: PageWriteOrigin? = null,
    ) {
        // Peek first: if no entry or already terminal, nothing is stranded — skip
        // the write rather than creating a spurious FAILED entry.
        val existing = store.state.value[pageKey] ?: return
        if (existing.hasRenderedResult || existing.isStageFailed) return
        if (requiredOrigin != null && store.pageLeaseOwner(pageKey) != requiredOrigin) return
        //   while a batch run holds this page's BATCH-origin stage
        // lease, the batch owns the page's stage state. The cancel write below
        // builds its precondition from the CURRENT snapshot — which carries the
        // batch's own lease token — and would punch straight through the fence,
        // bumping the page version mid-OCR/translate: the flagged lane's
        // `checkpointOcr` then rejects (CHECKPOINT_REJECTED) and charges a
        // healthy page to the failure ledger, or a mid-TRANSLATE write fails
        // `mergeTranslation` and pauses the whole run after the provider call
        // was paid. Skip the write; the chapter-level "stop translation" action
        // cancels the batch translator job itself (ChapterTranslator.stop →
        // translationJob.cancel), whose teardown releases every BATCH lease —
        // so this skip never strands a cancelled page's state.
        if (store.hasActiveBatchStageLease(pageKey)) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT cancel skipped: page holds an active BATCH stage lease " +
                    "writer=markPageCancelled pageKey=$pageKey"
            }
            return
        }
        // Flip in-flight stages to CANCELLED so the reader clears the overlay and
        // auto can reschedule the page later.
        store.updatePageFromCurrentSnapshot(pageKey, reason) { current ->
            // Re-check inside the lock in case it changed between peek and write.
            val cur = current ?: PageTranslation(
                sourceFileName = pageKey,
                ocrStatus = StageStatus.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            ).also { it.ocrError = reason }
            if (cur.hasRenderedResult || cur.isStageFailed) return@updatePageFromCurrentSnapshot cur
            cur.apply {
                cancelInFlightStages()
                ocrError = reason
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * TachiyomiAT bug 4 fix: synchronously flip every in-flight page in [chapterId]'s
     * store to CANCELLED so the reader clears the dim/overlay immediately. The auto/
     * master-toggle-off paths previously only cancelled the jobs (and relied on each
     * job's finally block to reset the status), which left pages visibly RUNNING until
     * the coroutine unwound — sometimes never, if the worker was past its suspension
     * point in uncancellable native/HTTP code.
     *
     * Mirrors the proven per-page pattern in [cancelPageTranslation]: runBlocking on
     * [immediateStoreResolver] is acceptable because callers run on the reader/UI
     * scope and the store's own mutex is the only inner lock (no nested UI-thread
     * concerns). Pages that already reached a rendered or failed terminal state are
     * left untouched so accepted artifacts survive.
     */
    suspend fun markChapterCancelledAsync(
        chapterId: Long,
        origin: PageWriteOrigin? = null,
        reason: String = "Translation cancelled",
    ): Int = withContext(Dispatchers.IO) {
        val store = immediateStoreResolver?.invoke(chapterId) ?: return@withContext 0
        val runningKeys = store.state.value.entries
            .asSequence()
            .filter { (pageKey, page) ->
                page != null &&
                    (page!!.isStageRunning || page.ocrError == "Translation cancelled") &&
                    (origin == null || store.pageLeaseOwner(pageKey)?.let { it == origin } != false)
            }
            .map { it.key }
            .toList()
        if (runningKeys.isEmpty()) return@withContext 0
        var flipped = 0
        runningKeys.forEach { key ->
            try {
                markPageCancelled(store, key, reason, origin)
                flipped++
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Failed to mark page cancelled: $key" }
            }
        }
        flipped
    }

    fun markChapterCancelledSync(
        chapterId: Long,
        reason: String = "Translation cancelled",
        origin: PageWriteOrigin? = null,
    ): Int {
        val store = immediateStoreResolver?.invoke(chapterId) ?: return 0
        val runningKeys = store.state.value.entries
            .asSequence()
            .filter { (pageKey, page) ->
                page != null &&
                    page!!.isStageRunning &&
                    (origin == null || store.pageLeaseOwner(pageKey)?.let { it == origin } != false)
            }
            .map { it.key }
            .toList()
        if (runningKeys.isEmpty()) return 0
        store.fastCancelInFlightStagesInMemory(origin = origin, cancellationReason = reason)
        var flipped = 0
        runBlocking {
            runningKeys.forEach { key ->
                try {
                    markPageCancelled(store, key, reason, origin)
                    flipped++
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "Failed to mark page cancelled: $key" }
                }
            }
        }
        return flipped
    }

    /**
     * Evicts a stuck page demand so the page becomes eligible again. The
     * queue-owned mapping cancels the stuck operation (its attempt-fenced
     * boundary cleanup owns the page write); the eviction is idempotent.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        val active = sessions[chapterId]
        if (active != null && queue.cancelPage(active.token, pageKey)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT evicted stuck page demand: chapterId=$chapterId pageKey=$pageKey " +
                    "(worker abandoned in native/HTTP code)"
            }
            return
        }
        logcat(LogPriority.WARN) {
            "TachiyomiAT stuck page report had no live queue demand: " +
                "chapterId=$chapterId pageKey=$pageKey"
        }
    }

    /**
     * Cancels the in-flight queue operation for one [pageKey] in [chapterId],
     * if any. This is the per-page granularity [cancelPageTranslations]
     * (chapter-scoped) is too coarse for; it backs the per-page cancel button.
     *
     * Returns true if a queue operation was actually cancelled or auto work was
     * stopped, false if none was running (already finished or deduped).
     */
    fun cancelPageTranslation(
        chapterId: Long,
        pageKey: String,
        reason: String = "Manual page cancellation requested",
    ): Boolean {
        val active = sessions[chapterId]
        val queueCancelled = active != null && queue.cancelPage(active.token, pageKey)
        if (queueCancelled) {
            // Queue-owned operation: the attempt-fenced boundary cleanup owns
            // the page write; a snapshot reset here would reintroduce the
            // removed per-operation sweep contract.
            return true
        }
        val autoCancelled = cancelAutoTranslations(chapterId)
        // Legacy (batch/coordinator-era) page state still flips synchronously so
        // the UI cannot remain stuck on RUNNING while the worker unwinds.
        immediateStoreResolver?.invoke(chapterId)?.let { store ->
            runBlocking { markPageCancelled(store, pageKey, reason) }
        }
        return autoCancelled
    }

    /**
     * Cancels the queue session for [chapterId] and evicts the reader page
     * streams for that chapter. Call on reader navigating away so the previous
     * chapter can't keep holding the executor permit.
     *
     * The queue's session cancellation bounds the drain at the queue's
     * cancellation wait, matching the previous job-join timeout; physical
     * stragglers drain under the teardown drain-handle contract instead.
     *
     * NOTE: store eviction is NOT done here; the store lifecycle is owned by
     * TranslationManager. The caller evicts the store if needed.
     */
    suspend fun cancelPageTranslations(
        chapterId: Long,
        reason: String = "Reader chapter navigation",
    ) {
        val cancellation = beginAutoCancellation(chapterId)
        val active = sessions[chapterId]
        try {
            cancellation?.let(::performAutoCancellation)
            // Chapter teardown also drops the outcomes retained for its pages.
            manualOutcomes.keys.removeAll { it.startsWith("$chapterId:") }
            if (active != null) {
                sessions.remove(chapterId, active)
                if (currentToken.get() == active.token) currentToken.set(null)
                // Bounded drain of the outgoing chapter's operations.
                queue.cancelSession(active.token)
            }
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    /**
     * Cancels every queue session and the armed window. Call on reader destroy
     * or master translation toggle off so no orphaned work keeps running.
     *
     * Cancellation is fire-and-forget here (the historical contract: no join on
     * this path); page state settles through the attempt-fenced boundary
     * cleanup and the manager-owned store clearing that follows.
     */
    fun cancelAllPageTranslations(reason: String = "Reader translation stop") {
        val cancellation = beginAutoCancellation(null)
        try {
            cancellation?.let(::performAutoCancellation)
            shutdownAutoCoordinator()
            manualOutcomes.clear()
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    /**
     * Reader-owned teardown boundary. Cancels and detaches every queue session,
     * then awaits each session's bounded drain handle to PHYSICAL completion
     * before returning — a [ReaderDrainResult.PendingNativeDrain] handle
     * completes only when every registered worker, including native
     * stragglers, has exited. Store eviction (owned by the teardown
     * coordinator) must not precede that. The scheduler scope itself remains
     * available for the next reader session.
     */
    suspend fun awaitReaderStop(reason: String = "Reader stopped") {
        synchronized(autoCoordinatorLock) {
            readerStopInFlight = true
        }
        try {
            queueWindow.value = null
            val cancellation = beginAutoCancellation(null)
            val tokensToDrain = cancellation?.tokens ?: emptyList()
            try {
                cancellation?.let(::performAutoCancellation)
                manualOutcomes.clear()
            } finally {
                finishAutoCancellation(cancellation)
            }
            tokensToDrain.forEach { token ->
                when (val drain = queue.cancelSession(token)) {
                    ReaderDrainResult.Completed -> Unit
                    is ReaderDrainResult.PendingNativeDrain -> drain.completion.await()
                }
            }
            sessions.clear()
            currentToken.set(null)
        } finally {
            synchronized(autoCoordinatorLock) {
                readerStopInFlight = false
            }
        }
    }

    private fun beginAutoCancellation(chapterId: Long?): QueueAutoCancellation? =
        synchronized(autoCoordinatorLock) {
            val tokens = sessions.values
                .filter { chapterId == null || it.chapterId == chapterId }
                .map { it.token }
            if (chapterId != null && tokens.isEmpty()) return@synchronized null
            val epoch = ++autoCancellationEpoch
            val ownsGlobal = chapterId == null && !globalAutoCancellationInFlight
            if (ownsGlobal) {
                globalAutoCancellationInFlight = true
                globalAutoCancellationEpoch = epoch
            }
            val ownsChapterEpoch = chapterId != null && chapterId !in chapterCancellationEpochs
            if (ownsChapterEpoch) {
                chapterCancellationEpochs[chapterId] = epoch
            }
            QueueAutoCancellation(
                chapterId = chapterId,
                tokens = tokens,
                invokeSessions = true,
                ownsChapterEpoch = ownsChapterEpoch,
                ownsGlobalCancellation = ownsGlobal,
                epoch = epoch,
            )
        }

    private fun performAutoCancellation(cancellation: QueueAutoCancellation) {
        cancellation.tokens.forEach { token ->
            sessions.values.removeIf { it.token == token }
            if (currentToken.get() == token) currentToken.set(null)
            queue.cancelSessionAndForget(token)
        }
    }

    private fun finishAutoCancellation(cancellation: QueueAutoCancellation?) {
        if (cancellation == null) return
        synchronized(autoCoordinatorLock) {
            if (cancellation.ownsChapterEpoch) {
                cancellation.chapterId?.let { chapterId ->
                    if (chapterCancellationEpochs[chapterId] == cancellation.epoch) {
                        chapterCancellationEpochs.remove(chapterId)
                    }
                }
            }
            if (cancellation.ownsGlobalCancellation && globalAutoCancellationEpoch == cancellation.epoch) {
                globalAutoCancellationInFlight = false
            }
        }
    }

    private class QueueAutoCancellation(
        val chapterId: Long?,
        val tokens: List<ReaderSessionToken>,
        val invokeSessions: Boolean,
        val ownsChapterEpoch: Boolean,
        val ownsGlobalCancellation: Boolean,
        val epoch: Long,
    )

    companion object {
        /** Maximum number of recent manual page outcomes retained for the reader. */
        private const val MANUAL_OUTCOME_MAP_CAP = 32
    }
}
