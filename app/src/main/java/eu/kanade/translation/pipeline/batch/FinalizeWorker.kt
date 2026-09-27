package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.RunConfigSnapshot
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.recovery.RecoveryWorker
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class FinalizeWorkerContext(
    val store: ChapterTranslationStore,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val overlapScheduler: OverlapScheduler?,
    val renderJoin: BatchRenderJoin?,
    val publishRecord: suspend (
        ChapterArtifactEngine,
        ChapterRunRecord,
    ) -> ChapterArtifactEngine.TransactionOutcome?,
    val record: (
        String,
        ChapterRunState,
        String,
        String,
        Map<String, Int>,
        String?,
    ) -> ChapterRunRecord,
    val drainDisplayTailBeforeComplete: suspend (
        List<String>,
    ) -> RecoveryWorker.DisplayTailDrain,
    val t924PageTerminalAtFinalize: (PageTranslation?, Long) -> Boolean,
    val strandedPageReason: (PageTranslation?) -> String,
    val persistEnvelopeStructuralFailure: suspend (String, String, String) -> Unit,
)

internal class FinalizeWorker(
    private val context: FinalizeWorkerContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val frozenConfig: RunConfigSnapshot
        get() = context.frozenConfig
    private val effectiveSourcePairs: List<Pair<String, String>>
        get() = context.effectiveSourcePairs
    private val overlapScheduler: OverlapScheduler?
        get() = context.overlapScheduler
    private val renderJoin: BatchRenderJoin?
        get() = context.renderJoin

    private suspend fun publishRecord(
        artifact: ChapterArtifactEngine,
        record: ChapterRunRecord,
    ): ChapterArtifactEngine.TransactionOutcome? =
        context.publishRecord(artifact, record)

    private fun record(
        runId: String,
        state: ChapterRunState,
        frozenFingerprint: String,
        sourceDigest: String,
        counters: Map<String, Int>,
        ocrCorpusFingerprint: String? = null,
    ): ChapterRunRecord = context.record(
        runId,
        state,
        frozenFingerprint,
        sourceDigest,
        counters,
        ocrCorpusFingerprint,
    )

    private suspend fun drainDisplayTailBeforeComplete(
        orderedPageKeys: List<String>,
    ): RecoveryWorker.DisplayTailDrain =
        context.drainDisplayTailBeforeComplete(orderedPageKeys)

    private fun t924PageTerminalAtFinalize(
        page: PageTranslation?,
        activeGeneration: Long,
    ): Boolean = context.t924PageTerminalAtFinalize(page, activeGeneration)

    private fun strandedPageReason(page: PageTranslation?): String =
        context.strandedPageReason(page)

    private suspend fun persistEnvelopeStructuralFailure(
        pageKey: String,
        reason: String,
        carrier: String,
    ) = context.persistEnvelopeStructuralFailure(pageKey, reason, carrier)

    private fun runConfigFingerprint(config: RunConfigSnapshot): String =
        ChapterProfileBatchCoordinator.runConfigFingerprint(config)

    private fun orderedSourceDigest(pairs: List<Pair<String, String>>): String =
        ChapterProfileBatchCoordinator.orderedSourceDigest(pairs)

    suspend fun runPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)

        //  entry: the FINALIZE phase pointer (resume re-runs finalize —
        // every step below is an idempotent re-run).
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.FINALIZE,
                frozenFingerprint,
                sourceDigest,
                baseCounters + mapOf(ChapterProfileBatchCoordinator.COUNTER_FINALIZE to 1) +
                    // Overlap counters ride the FINALIZE record. The schema
                    // caps phaseCounters at 32 keys, so adding the full overlap
                    // snapshot to base and finalize counters could reject the
                    // COMPLETE record. Counters are trimmed before publication.
                    (
                        overlapScheduler?.let { scheduler ->
                            scheduler.counters.snapshot().mapValues { it.value.toInt() }
                        } ?: emptyMap()
                        ),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )
        return drainFinalizeAndComplete(
            artifact = artifact,
            runId = runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = baseCounters,
        )
    }

    /**
     * Steps 2-6 of the  FINALIZE phase — the idempotent drain shared by
     * the fresh entry ([runFinalizeAndComplete]) and the  FINALIZE
     * resume ([resumeFinalizeOrComplete]):
     *
     *  2. Serial inpaint drain through the overlap scheduler — pages whose
     *     inpaint already committed are skipped by the scheduler's candidate
     *     rule (never re-inpainted); with no scheduler this is a no-op.
     *  2b.  display-tail drain: pages whose translate+inpaint work is
     *     done but whose committed display (render-terminal stamp →
     *     promotion) never landed are drained to completion here, bounded;
     *     a page the drain cannot finish takes a SPECIFIC typed terminal and
     *     the run still completes (as a warning).
     *  3. Persisted-layout publication sweep (idempotent per page).
     *  4. Stranded-page reconciliation (safe re-run: terminal pages skip).
     *  5. NonCancellable flush + retention reconciliation.
     *  6. The run's FIRST and ONLY `COMPLETE` publication.
     */

    suspend fun drainPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = drainFinalizeAndComplete(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
    )

    private suspend fun drainFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val allPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first }

        // 2. Serial post-translate inpaint drain (overlap-fallback arm).
        overlapScheduler?.drainSerial()

        // 2b.  display-tail drain: COMPLETE means "every page readable",
        //     not "every ingredient done". The inpaint lane's render-terminal
        //     stamp only fires when the page's translation was ALREADY
        //     terminal at inpaint time, so order-inverted pages (inpaint
        //     committed before the envelope translation — the decoupled
        //     candidacy norm) stay render-PENDING with ALL work done and are
        //     invisible to the stranded sweep below (translation READY is
        //     terminal there). Drain that tail to completion here — bounded
        //     passes, each a fresh snapshot so a stale-write rejection
        //     retries — and give a page the drain genuinely cannot finish a
        //     SPECIFIC typed terminal instead of publishing COMPLETE over a
        //     silently frozen ORIGINAL_ONLY page.
        val displayTail = drainDisplayTailBeforeComplete(orderedPages.map { it.first })

        // 3. Persisted-layout publication sweep for any page the per-page
        //    hook missed (idempotent — pages with a published plan skip).
        var layoutsPublished = 0
        val renderJoin = context.renderJoin
        if (renderJoin != null) {
            for ((pageKey, _) in orderedPages) {
                if (renderJoin.publishPersistedLayoutForCompletedPage(pageKey)) {
                    layoutsPublished++
                }
            }
        }

        // 4. Stranded-page reconciliation: pages that are not durably
        //    terminal get a durable failure + a reported reason. The
        //    terminal predicate is NOT the legacy reconciler's one: the legacy
        //    schedule renders + promotes display in-pass (hasRenderedResult),
        //    while the flagged pipeline's committed-translation state is
        //    terminal WITHOUT an in-pass render — reusing the legacy
        //    definition here would mark every healthy page stranded.
        val stateNow = store.state.value
        var strandedReconciled = 0
        for (pageKey in orderedPages.map { it.first }) {
            val page = stateNow[pageKey]
            if (t924PageTerminalAtFinalize(page, store.currentGeneration)) continue
            //  stranded-page fix: the reason must name the page's actual
            // work state (all-translated, textless, user-owned, or genuinely
            // unfinished) — a bare status is what made these pages show as a
            // generic "Unknown error" class in the progress sheet.
            val reason = strandedPageReason(page)
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 stranded page at FINALIZE pageHash=${ShortHash.hash(pageKey)} reason=$reason"
            }
            persistEnvelopeStructuralFailure(
                pageKey,
                reason,
                carrier = "stranded page reconciled at FINALIZE",
            )
            strandedReconciled++
        }

        // 5. Durable teardown: NonCancellable flush + retention reconciliation
        //    (the BatchChapterTranslator :781-794 idiom). The sweep itself runs
        //    fire-and-forget: its crawl is minutes of SAF round-trips and must
        //    neither delay run closure nor hold the store mutex (2026-09-15
        //    jdb-proven 20+ minute stall).
        withContext(NonCancellable) {
            store.flush()
        }
        store.reconcileArtifactRetentionAsync()

        // 6. Run closure: the single COMPLETE publication of the run. The
        //    overlap counters live on the FINALIZE record (see above — the
        //    32-key phaseCounters bound). The outcome is NOT advisory here:
        //    a rejected (or unpublishable) closure leaves the run durably at
        //    FINALIZE, so the coordinator pauses instead of reporting a
        //    completion the record disagrees with (ChapterProfileBatchCoordinator.RUN_CLOSURE_REJECTED_REASON).
        //
        //     round 3: the publication CASes against the façade manifest
        //    snapshot while the drain steps above move durable state through
        //    their own store transactions. One fresh-baseline retry — re-read
        //    the durable manifest into the façade, then re-publish — keeps
        //    the closure off the typed pause when the snapshot is merely
        //    stale (the same one-shot rebase every artifact-store seam gets
        //    from retryOnStaleManifest); a genuine rejection still pauses.
        fun completeRecord() = record(
            runId,
            ChapterRunState.COMPLETE,
            frozenFingerprint,
            sourceDigest,
            baseCounters +
                mapOf(
                    ChapterProfileBatchCoordinator.COUNTER_FINALIZE to 1,
                    ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE to 1,
                    ChapterProfileBatchCoordinator.COUNTER_LAYOUTS_PUBLISHED to layoutsPublished,
                    ChapterProfileBatchCoordinator.COUNTER_STRANDED_RECONCILED to strandedReconciled,
                    ChapterProfileBatchCoordinator.COUNTER_DISPLAY_TAIL_DRAINED to displayTail.drained,
                    ChapterProfileBatchCoordinator.COUNTER_DISPLAY_TAIL_FAILED to displayTail.failed.size,
                ),
            ocrCorpusFingerprint = corpusFingerprint,
        )
        var closure = publishRecord(artifact, completeRecord())
        if (closure !is ChapterArtifactEngine.TransactionOutcome.Committed) {
            store.withArtifactEngineLocked { artifact ->
                artifact.readManifest()
            }?.let { store.artifactManifest = it }
            closure = publishRecord(artifact, completeRecord())
        }
        when (closure) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 run COMPLETE pages=${allPageKeys.size} stranded=$strandedReconciled " +
                        "layouts=$layoutsPublished displayTailDrained=${displayTail.drained} " +
                        "displayTailFailed=${displayTail.failed.size} " +
                        "overlap=${overlapScheduler?.counters?.snapshot() ?: emptyMap()}"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.COMPLETED,
                    completedPageKeys = allPageKeys,
                    reason = ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON,
                )
            }
            else -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 run COMPLETE publication rejected " +
                        "(outcome=${closure?.javaClass?.simpleName ?: "no-manifest"}); " +
                        "pausing at FINALIZE"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    completedPageKeys = allPageKeys,
                    reason = ChapterProfileBatchCoordinator.RUN_CLOSURE_REJECTED_REASON,
                )
            }
        }
    }

    /**
     * Runs standard-engine page translation after OCR preflight confirms the
     * complete corpus. It shares chapter completion semantics with the AI
     * lane but does not use glossary, analysis, profile, or envelope work.
     *
     * The run record carries the OCR corpus fingerprint. Translation proceeds in page order
     * through [standardTranslateOutcome]. Already terminal pages are not
     * translated again. Every call is bracketed by the overlap window so
     * native inpaint never overlaps translation.
     *
     * Typed outcomes map to the shared chapter semantics: completed work
     * advances, paused work records a pause, failures remain failures, and a
     * rejected persistence write records PERSISTENCE_REJECTED. When the loop
     * drains, [runFinalizeAndComplete] performs the serial inpaint drain,
     * layout sweep, stranded-page reconciliation, and one COMPLETE
     * publication. Completion is translation-terminal without an in-pass
     * render; display uses the live overlay and candidate snapshots, and
     * renderStatus remains PENDING.
     */
}
