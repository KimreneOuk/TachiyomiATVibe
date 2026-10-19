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
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * [updateReaderViewport], [setReaderAutoEnabled], [readerOutcomes] and
 * [reconcileReaderQueue] — which resolves the admitted session token and
 * delegates once.
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
        runBlocking {
            when (val drain = queue.closeAndDrain()) {
                ReaderDrainResult.Completed -> Unit
                is ReaderDrainResult.PendingNativeDrain ->
                    // Bounded join: a wedged native call must not hang teardown; the
                    // scope cancel below still stops the workers cooperatively.
                    withTimeoutOrNull(CLOSE_DRAIN_JOIN_TIMEOUT_MS) { drain.completion.await() }
            }
        }
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
    private val sessionLifecycleLock = Any()

    /**
     * Cancellation is attributed before queue sessions are cancelled because
     * the same-chapter suppression window must cover the whole signalling
     * path, not just the control mutation.
     */
    private val chapterCancellationEpochs = mutableMapOf<Long, Long>()
    private var globalReaderCancellationInFlight = false
    private var readerCancellationEpoch = 0L
    private var globalReaderCancellationEpoch = 0L
    private var readerStopInFlight = false

    /**
     * Binds (or reuses) the queue session for [chapterId] and returns its token.
     * Reuse requires the same admitted session key, the same store instance and
     * the same compute class — a changed value rebinds, which cancels the old
     * binding's demand set under the queue's epoch drain. Suppressed during an
     * in-flight reader stop, a global reader cancellation, or a chapter
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
                "TachiyomiAT reader admission rejected at scheduler gate: " +
                    "reason=$rejectionReason chapterId=$chapterId"
            }
            return null
        }
        synchronized(sessionLifecycleLock) {
            if (readerStopInFlight || globalReaderCancellationInFlight) return null
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

    /** Typed manual or viewport page submission for a captured reader session. */
    fun submitReaderPage(
        token: ReaderSessionToken,
        work: ReaderPageWork,
        trigger: ReaderTrigger,
        force: Boolean = false,
    ): ReaderSubmission = queue.submitCurrent(token, work, trigger, force)

    /** Arms or refreshes the viewport demand set (exactly N, N+1) for the captured session. */
    fun updateReaderViewport(
        token: ReaderSessionToken,
        pageIndex: Int,
        pageCount: Int,
        resolve: (Int) -> ReaderPageWork?,
    ) {
        queue.updateViewport(token, pageIndex, pageCount, isAutoEnabled = true, resolve = resolve)
    }

    /** Queue-native auto toggle; disabling retains explicit demand. */
    fun setReaderAutoEnabled(token: ReaderSessionToken, enabled: Boolean): Boolean =
        queue.setAutoEnabled(token, enabled)

    /** Typed queue outcomes for reader projection, bounded by the queue's retention cap. */
    fun readerOutcomes(token: ReaderSessionToken): StateFlow<Map<String, SinglePageOutcome>> =
        queue.outcomes(token)

    /** Synchronously fences the chapter's queue session for admission/re-key control paths. */
    fun invalidateQueueSession(chapterId: Long) {
        val removed = synchronized(sessionLifecycleLock) {
            sessions.remove(chapterId)?.also { active ->
                if (currentToken.get() == active.token) currentToken.set(null)
            }
        } ?: return
        queue.cancelSessionAndForget(removed.token)
    }

    /** Invalidates a chapter session and joins its physical workers before a destructive store action. */
    suspend fun cancelReaderQueueSession(chapterId: Long) {
        val cancellation = beginReaderQueueCancellation(chapterId)
        try {
            cancellation?.let(::performReaderQueueCancellation)
            cancellation?.tokens.orEmpty().forEach { awaitPhysicalDrain(it) }
        } finally {
            finishReaderQueueCancellation(cancellation)
        }
    }

    /** Reconciles retained queue sessions after a memory or provider availability event. */
    fun reconcileReaderQueues(admissionGuard: (Long) -> Boolean = { true }) {
        val active = synchronized(sessionLifecycleLock) {
            if (readerStopInFlight || globalReaderCancellationInFlight) return
            sessions.values.toList()
        }
        active.filter { admissionGuard(it.chapterId) }.forEach { queue.reconcile(it.token) }
    }

    /** Re-drives queued demands for the captured session after a source/memory/lifecycle event. */
    fun reconcileReaderQueue(
        token: ReaderSessionToken,
        resolve: (Int) -> ReaderPageWork?,
    ) = queue.reconcile(token, resolve)

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
     * Returns true if a queue operation was actually cancelled or legacy page state was
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
        // Legacy batch-owned page state still flips synchronously so
        // the UI cannot remain stuck on RUNNING while the worker unwinds.
        immediateStoreResolver?.invoke(chapterId)?.let { store ->
            runBlocking { markPageCancelled(store, pageKey, reason) }
        }
        return false
    }

    /**
     * Cancels the queue session for [chapterId] and evicts the reader page
     * streams for that chapter. Call on reader navigating away so the previous
     * chapter can't keep holding the executor permit.
     *
     * Store cleanup follows only after every returned physical drain handle
     * completes; the bounded control wait never authorizes early eviction.
     *
     * NOTE: store eviction is NOT done here; the store lifecycle is owned by
     * TranslationManager. The caller evicts the store if needed.
     */
    suspend fun cancelPageTranslations(
        chapterId: Long,
        reason: String = "Reader chapter navigation",
    ) {
        val cancellation = beginReaderQueueCancellation(chapterId)
        try {
            cancellation?.let(::performReaderQueueCancellation)
            // Chapter teardown also drops the outcomes retained for its pages.
            manualOutcomes.keys.removeAll { it.startsWith("$chapterId:") }
            cancellation?.tokens.orEmpty().forEach { awaitPhysicalDrain(it) }
        } finally {
            finishReaderQueueCancellation(cancellation)
        }
    }

    /**
     * Cancels every queue session. Call on reader destroy
     * or master translation toggle off so no orphaned work keeps running.
     */
    suspend fun cancelAllPageTranslations(reason: String = "Reader translation stop") {
        val cancellation = beginReaderQueueCancellation(null)
        try {
            cancellation?.let(::performReaderQueueCancellation)
            manualOutcomes.clear()
            cancellation?.tokens.orEmpty().forEach { awaitPhysicalDrain(it) }
        } finally {
            finishReaderQueueCancellation(cancellation)
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
        synchronized(sessionLifecycleLock) {
            readerStopInFlight = true
        }
        try {
            val cancellation = beginReaderQueueCancellation(null)
            val tokensToDrain = cancellation?.tokens ?: emptyList()
            try {
                cancellation?.let(::performReaderQueueCancellation)
                manualOutcomes.clear()
            } finally {
                finishReaderQueueCancellation(cancellation)
            }
            tokensToDrain.forEach { awaitPhysicalDrain(it) }
        } finally {
            synchronized(sessionLifecycleLock) {
                readerStopInFlight = false
            }
        }
    }

    private suspend fun awaitPhysicalDrain(token: ReaderSessionToken) {
        when (val drain = queue.cancelSession(token)) {
            ReaderDrainResult.Completed -> Unit
            is ReaderDrainResult.PendingNativeDrain -> drain.completion.await()
        }
    }

    private fun beginReaderQueueCancellation(chapterId: Long?): QueueSessionCancellation? =
        synchronized(sessionLifecycleLock) {
            val tokens = sessions.values
                .filter { chapterId == null || it.chapterId == chapterId }
                .map { it.token }
            if (chapterId != null && tokens.isEmpty()) return@synchronized null
            val epoch = ++readerCancellationEpoch
            val ownsGlobal = chapterId == null && !globalReaderCancellationInFlight
            if (ownsGlobal) {
                globalReaderCancellationInFlight = true
                globalReaderCancellationEpoch = epoch
            }
            val ownsChapterEpoch = chapterId != null && chapterId !in chapterCancellationEpochs
            if (ownsChapterEpoch) {
                chapterCancellationEpochs[chapterId] = epoch
            }
            QueueSessionCancellation(
                chapterId = chapterId,
                tokens = tokens,
                ownsChapterEpoch = ownsChapterEpoch,
                ownsGlobalCancellation = ownsGlobal,
                epoch = epoch,
            )
        }

    private fun performReaderQueueCancellation(cancellation: QueueSessionCancellation) {
        cancellation.tokens.forEach { token ->
            sessions.values.removeIf { it.token == token }
            if (currentToken.get() == token) currentToken.set(null)
            queue.cancelSessionAndForget(token)
        }
    }

    private fun finishReaderQueueCancellation(cancellation: QueueSessionCancellation?) {
        if (cancellation == null) return
        synchronized(sessionLifecycleLock) {
            if (cancellation.ownsChapterEpoch) {
                cancellation.chapterId?.let { chapterId ->
                    if (chapterCancellationEpochs[chapterId] == cancellation.epoch) {
                        chapterCancellationEpochs.remove(chapterId)
                    }
                }
            }
            if (cancellation.ownsGlobalCancellation && globalReaderCancellationEpoch == cancellation.epoch) {
                globalReaderCancellationInFlight = false
            }
        }
    }

    private class QueueSessionCancellation(
        val chapterId: Long?,
        val tokens: List<ReaderSessionToken>,
        val ownsChapterEpoch: Boolean,
        val ownsGlobalCancellation: Boolean,
        val epoch: Long,
    )

    companion object {
        /** Maximum number of recent manual page outcomes retained for the reader. */
        private const val MANUAL_OUTCOME_MAP_CAP = 32

        /**
         * Bound for close()'s join on physically draining work the queue retained;
         * generous for honest native tails, finite for parked or wedged ones.
         */
        private const val CLOSE_DRAIN_JOIN_TIMEOUT_MS = 10_000L
    }
}
