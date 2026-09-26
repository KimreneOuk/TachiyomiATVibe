package eu.kanade.translation.pipeline
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.context.ContextRequest
import eu.kanade.translation.context.LaneCapability
import eu.kanade.translation.diagnostics.BatchDiagnosticDecision
import eu.kanade.translation.diagnostics.BatchDiagnosticReason
import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.diagnostics.BatchTranslationDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.translator.TranslationBlockValidation
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.translator.contextual.ChapterGlossaryBuilder
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.providers.LmStudioTranslator
import eu.kanade.translation.engines.translator.retry.AiTranslationRetryPlanner
import eu.kanade.translation.engines.translator.retry.RequestRetryBudget
import eu.kanade.translation.engines.translator.retry.classifyProviderFailure
import eu.kanade.translation.engines.translator.retry.withRequestRetryBudget
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.persistence.artifact.AttemptOrigin
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.TranslationProvider
import eu.kanade.translation.persistence.chapter.ocrFingerprint
import eu.kanade.translation.persistence.chapter.toArtifactOrigin
import eu.kanade.translation.pipeline.TranslationPipeline.Companion.SINGLE_PAGE_PARTIAL_MAX_RETRIES
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Runs HTTP translation and rendering outside the native permit. It handles
 * contextual or plain translation, bounded partial retries, color recompute,
 * Canvas rendering, and guarded commit. The cleaned bitmap in
 * [OnnxPhaseResult] remains owned by this stage until its commit or cleanup.
 */
internal class SinglePageHttpRenderPhase(
    private val translationPreferences: TranslationPreferences,
    private val provider: TranslationProvider,
    private val streamRegistry: eu.kanade.translation.pipeline.execution.TranslationStreamRegistry,
    private val engines: EngineLane,
    private val cleanedPublication: CleanedPublication,
    // Engine-cache reads (translator signature/model/mode change on rebuild),
    // resolved through the pipeline's own helper at each call.
    private val expectedBatchFingerprints: (TextRecognizerLanguage, TextTranslatorLanguage) -> BatchExpectedFingerprints,
    // The pipeline injects the downscaled-inpaint retry so this phase can
    // request it without owning ONNX setup.
    private val retryInpaintDownscaledFn: suspend (
        Manga,
        Chapter,
        HttpSource,
        String,
        List<Pair<String, () -> InputStream>>,
        DecodedPage,
        PageTranslation,
    ) -> PageTranslation,
) {

    // Read live engine state so rebuilds are observed.
    private val textTranslator get() = engines.textTranslator

    private val recognitionEngine get() = engines.recognitionEngine

    private fun batchExpectedFingerprints(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): BatchExpectedFingerprints = expectedBatchFingerprints(fromLang, toLang)

    private suspend fun retryInpaintDownscaled(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streams: List<Pair<String, () -> InputStream>>,
        decoded: DecodedPage,
        pageTranslation: PageTranslation,
    ): PageTranslation = retryInpaintDownscaledFn(manga, chapter, source, pageKey, streams, decoded, pageTranslation)

    private suspend fun persistCleanedBitmap(
        pageTranslation: PageTranslation,
        cleanedBitmap: Bitmap,
        companionDir: UniFile?,
        pageKey: String,
        chapterName: String,
        store: ChapterTranslationStore,
        sourceId: Long,
        mangaId: Long,
        chapterId: Long?,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    ): ChapterTranslationStore.PageSnapshot? = cleanedPublication.persistCleanedBitmap(
        pageTranslation,
        cleanedBitmap,
        companionDir,
        pageKey,
        chapterName,
        store,
        sourceId,
        mangaId,
        chapterId,
        expectedPrecondition,
    )

    private suspend fun deleteRetiredCleanedFile(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) {
        cleanedPublication.deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
    }

    /**
     * Phase HTTP+Render of the reader single-page path: runs OUTSIDE the permit.
     *
     * Takes the [OnnxPhaseResult] from [translateSinglePageOnnx] (cleanedBitmap
     * alive on [PageTranslation]), translates text blocks via HTTP, renders
     * translated text onto the cleaned bitmap via Canvas, and persists the result.
     *
     *  Epoch/drain contract: this is the
     * SINGLE translator borrow site. The phase registers the borrow on
     * [engines] (`beginTranslatorUse`, released in `finally`) so a concurrent
     * [EngineLane.closeEngines] (stop / toggle-off / queue emptied) can DRAIN it
     * inside a bounded grace instead of closing the translator mid-call. The
     * borrow is paired with an epoch capture: if the HTTP call fails and the
     * engine epoch has moved, the close DID race this call — the phase rebuilds
     * the closed translator once (`ensureTranslatorRebuiltForEpochRetry`), re-reads
     * the chapter glossary, and retries INSIDE the same ledger-wrapped call (one
     *  entry). A second epoch mismatch fails the page honestly; there is no
     * retry loop, and the close path itself never touches the ledger.
     */
    suspend fun translateSinglePageHttpRender(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        ctx: OnnxPhaseResult,
        stageListener: TranslationStageListener? = null,
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
    ): ChunkCompletionOutcome {
        val pageTranslation = ctx.pageTranslation
        // Reader-ad-hoc output is displayable; a later batch reuses it when
        // its fingerprints still match.   two-vocabulary rule: the lease
        // origin (MANUAL/AUTO) maps back onto the stable durable vocabulary —
        // never stamp the lease-layer string itself.
        pageTranslation.translationOrigin = origin.toArtifactOrigin().name
        val store = ctx.store
        val fromLang = ctx.fromLang
        val syntheticTranslation = ctx.syntheticTranslation
        val streams = ctx.streams
        val decoded = ctx.decoded
        val batchFingerprints = batchExpectedFingerprints(
            fromLang,
            TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage()),
        )
        var commitPrecondition = requireNotNull(ctx.commitPrecondition) {
            "single-page commit precondition missing for $pageKey"
        }

        // A fresh reader decode has just run the native stages under the current
        // configuration. Record that provenance so a later ordered batch can
        // reuse detection/OCR/inpaint while still retranslating this ad-hoc
        // result. Cleaned-only resume paths retain their existing provenance;
        // unknown legacy evidence is intentionally not upgraded here.
        if (decoded.sourceBytesSize > 0L) {
            pageTranslation.sourceFingerprint = decoded.sourceFingerprint
            pageTranslation.detectionFingerprint = batchFingerprints.detection
            pageTranslation.ocrFingerprint = batchFingerprints.ocr
            pageTranslation.inpaintFingerprint = batchFingerprints.inpaint
        }
        pageTranslation.translationFingerprint = batchFingerprints.translation
        pageTranslation.layoutFingerprint = batchFingerprints.layout

        // Borrow the translator and capture its epoch. `activeTranslator` is
        // the page's working reference; an epoch-guard retry re-captures it
        // from the lane after a targeted rebuild.
        var activeTranslator = textTranslator
        val epochAtCapture = engines.currentEngineEpoch()
        engines.beginTranslatorUse()
        // The reader path is outside the batch coordinator, so it owns one
        // envelope budget explicitly. This budget is inherited by provider
        // transport retries and is shared by the contextual call plus any
        // bounded partial retries below.
        val retryBudget = RequestRetryBudget()
        // Provider-governor wait opens at phase entry (the
        // translator borrow above is the admission gate) and settled by the
        // first translator invocation; when no translation runs, the outer
        // finally settles it. Queue-class stage: feeds schedule maxQueueMs.
        val governorSpan = TranslationTrace.beginStage(
            TranslationTraceStage.PROVIDER_GOVERNOR_WAIT,
            lane = TranslationTraceLane.PROVIDER,
        )
        var governorSpanSettled = false
        var translationOutcome: ChunkCompletionOutcome = ChunkCompletionOutcome.Completed()
        // A lazy cleaned-image worker may still be encoding this bitmap while
        // the live page reaches its terminal commit. Keep the ownership
        // reference until the existing final store.flush() barrier has joined
        // the worker; the display path must not await the disk write itself.
        var deferredCleanedBitmap: Bitmap? = ctx.pendingCleanedPublication?.let {
            pageTranslation.cleanedBitmap
        }
        val renderFailure = ProviderFailure(
            kind = ProviderFailureKind.PROTOCOL,
            retryability = ProviderFailureRetryability.RETRY_NOW,
            safeSummary = "rendering failed; retry required",
        )

        fun markRenderFailure() {
            pageTranslation.renderStatus = StageStatus.FAILED
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = renderFailure.safeSummary
            translationOutcome = ChunkCompletionOutcome.Failed(
                anchorPageKey = pageKey,
                terminalPageKeys = emptySet(),
                failure = renderFailure,
                reason = renderFailure.safeSummary,
            )
        }

        suspend fun publishLiveStage(
            description: String,
            update: (PageTranslation) -> Unit,
        ) {
            val result = store.updatePageFromCurrentSnapshot(pageKey, description) { current ->
                (current ?: pageTranslation.copy()).apply {
                    sourceFileName = pageKey
                    update(this)
                    updatedAt = System.currentTimeMillis()
                }
            }
            if (result is ChapterTranslationStore.PatchResult.Rejected) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT live stage publication rejected: pageKey=$pageKey " +
                        "description=$description reason=${result.reason}"
                }
            }
        }

        // AI translators use translateContextual with the chapter glossary so on-demand
        // single-page translation reuses established terms/pronouns (same continuity the
        // batch path gets). Standard translators keep plain translatePage.
        val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
            ?: TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
        fun singlePageProfile(translator: Any): TranslationContextChunkPlanner.Profile =
            if (translator is LmStudioTranslator) {
                TranslationContextChunkPlanner.Profile.LM_STUDIO
            } else {
                TranslationContextChunkPlanner.Profile.DEFAULT
            }

        // The manual paid call is wrapped by
        // the durable attempt ledger — entry written BEFORE the call, resolved
        // on any completed call (success or typed provider failure); only
        // cancellation (process death / scope kill) leaves the entry for the
        // startup reconcile. Write failures are fail-open.
        suspend fun runLedgerWrapped(call: suspend () -> Unit) {
            runCatching {
                store.recordAttemptStart(
                    pageKey = pageKey,
                    providerKeyHash = ShortHash.hash(activeTranslator.javaClass.name),
                    origin = AttemptOrigin.valueOf(origin.name),
                    generation = store.currentGeneration,
                )
            }.onFailure {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT D9: manual attempt-ledger record failed (fail-open): pageKey=$pageKey"
                }
            }
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                runCatching { store.resolveAttempt(pageKey) }
                throw t
            }
            runCatching { store.resolveAttempt(pageKey) }
        }

        // One translate round against the CURRENT [activeTranslator]: builds the
        // contextual chunk (glossary snapshot + rolling pairs are re-read per
        // round, so an epoch retry naturally picks up a fresh glossary) or makes
        // the plain translatePage call.
        suspend fun translateOnce(targetPage: PageTranslation) {
            // The first translator invocation is the moment the
            // governor/admission wait ends.
            if (!governorSpanSettled) {
                governorSpanSettled = true
                governorSpan.end()
            }
            val ct = activeTranslator as? ContextualTextTranslator
            if (ct != null) {
                val targetLang = translationPreferences.translateToLanguage().get()
                val sourceLang = translationPreferences.translateFromLanguage().get()
                val laneCap = if (origin == PageWriteOrigin.AUTO) {
                    LaneCapability.AUTO
                } else {
                    LaneCapability.MANUAL
                }
                val estPrompt = TranslationContextChunkPlanner.PROMPT_OVERHEAD_TOKENS +
                    targetPage.blocks.sumOf { TranslationContextChunkPlanner.estimateTokens(it.text) }
                val baseChunk = TranslationContextChunk(
                    pages = linkedMapOf(pageKey to targetPage),
                    blockCount = targetPage.blocks.count { it.text.isNotBlank() },
                    rollingContext = "",
                    estimatedPromptTokens = estPrompt,
                    maxOutputTokens = requestedOutputTokens,
                    protocol = eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol.LEGACY,
                )
                // Recent translated pairs give on-demand single-page translation the
                // same voice/speaker continuity the batch path gets.
                val recentPairs = store.translatedPairs()
                    .mapNotNull { (src, tgt) ->
                        val t = tgt.trim()
                        if (t.isBlank() || t == src.trim()) null else "$src => $t"
                    }
                    .takeLast(TranslationContextChunkPlanner.MAX_ROLLING_PAIRS)
                    .joinToString("\n")
                val glossaryText = ChapterGlossaryBuilder.formatGlossary(store.glossarySnapshot())
                val prepared = store.contextService.prepare(
                    ContextRequest(
                        pageKeys = listOf(pageKey),
                        targetLang = targetLang,
                        sourceLang = sourceLang,
                        requestedOutputTokens = requestedOutputTokens,
                        profile = singlePageProfile(activeTranslator),
                        laneCapability = laneCap,
                        rollingPairs = recentPairs,
                    ),
                )
                val effectiveGlossary = prepared.characterAndTermSheet.ifBlank { glossaryText }
                val effectiveRolling = prepared.rollingContext.ifBlank { recentPairs }
                val chunk = TranslationContextChunkPlanner.withRollingContext(
                    chunk = baseChunk,
                    rollingContext = effectiveRolling,
                    requestedOutputTokens = requestedOutputTokens,
                    profile = singlePageProfile(activeTranslator),
                    glossary = effectiveGlossary,
                )
                ct.translateContextual(chunk)
            } else {
                activeTranslator.translatePage(pageKey, targetPage)
            }
        }

        // One ledger entry covers the original call
        // AND the epoch retry (a close that raced the call is not a second
        // attempt; `resolveAttempt` fires once on the final outcome). The retry
        // is gated on the engine epoch moving + at-most-once; a second mismatch
        // rethrows into the caller's typed-failure handling — no loop.
        var epochRetryUsed = false
        suspend fun runTranslate(targetPage: PageTranslation) {
            runLedgerWrapped {
                try {
                    translateOnce(targetPage)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (!epochRetryUsed && engines.currentEngineEpoch() != epochAtCapture) {
                        epochRetryUsed = true
                        TranslationTrace.currentRun()?.recordRetry()
                        logcat(LogPriority.WARN) {
                            "T917 D7: engine close raced the in-flight HTTP call (epoch " +
                                "$epochAtCapture -> ${engines.currentEngineEpoch()}); rebuilding the " +
                                "translator and retrying once: pageKey=$pageKey"
                        }
                        engines.ensureTranslatorRebuiltForEpochRetry(
                            fromLang,
                            TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage()),
                        )
                        activeTranslator = textTranslator
                        translateOnce(targetPage)
                    } else {
                        throw e
                    }
                }
            }
        }

        try {
            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty()) {
                // Admit the visible stage before sorting, diagnostics, the
                // governor, or any provider preparation. Truth reflects the
                // work the user just entered, not the first network call.
                pageTranslation.translationStatus = StageStatus.RUNNING
                publishLiveStage("single-page translation admission") {
                    it.translationStatus = StageStatus.RUNNING
                }
                stageListener?.onStageEntered(pageKey, TranslationStageEvent.TRANSLATING)
                val nonEmptyBlocks = pageTranslation.blocks.count { it.text.isNotBlank() }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT translate step START: pageHash=${ShortHash.hash(pageKey)} " +
                        "translator=${activeTranslator::class.simpleName} " +
                        "blocks=${pageTranslation.blocks.size} nonEmptyText=$nonEmptyBlocks"
                }
                // Correlated translate stage. `provider` is the
                // remote/local execution fact from the shared compute
                // classification; model stays none (plan §4.2).
                val translateSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.TRANSLATE,
                    provider = if (
                        TranslatorComputeClass.forTranslator(activeTranslator) == TranslatorComputeClass.LOCAL_COMPUTE
                    ) {
                        TranslationTraceProvider.LOCAL
                    } else {
                        TranslationTraceProvider.REMOTE
                    },
                )
                BatchTranslationDiagnostics.stageDecision(
                    stage = BatchDiagnosticStage.TRANSLATION,
                    pageKey = pageKey,
                    decision = BatchDiagnosticDecision.EXECUTE,
                    reason = BatchDiagnosticReason.REFERENCE_READY,
                    itemCount = pageTranslation.blocks.size,
                )
                try {
                    val readingOrder = translationPreferences.translationReadingOrder().get()
                    pageTranslation.blocks = eu.kanade.translation.engines.vision.ocr.TranslationBlockSorter.sort(
                        pageTranslation.blocks,
                        fromLang,
                        readingOrder,
                    )
                    var singlePageRetry = 0
                    withRequestRetryBudget(retryBudget) {
                        runTranslate(pageTranslation)
                        TranslationBlockValidation.applyTo(pageTranslation)
                        while (pageTranslation.translationStatus == StageStatus.PARTIAL &&
                            singlePageRetry < SINGLE_PAGE_PARTIAL_MAX_RETRIES
                        ) {
                            val missing = AiTranslationRetryPlanner.untranslatedBlocks(pageTranslation)
                            if (missing.isEmpty()) break
                            singlePageRetry++
                            TranslationTrace.currentRun()?.recordRetry()
                            logcat(LogPriority.INFO) {
                                "TachiyomiAT single-page PARTIAL retry $singlePageRetry/$SINGLE_PAGE_PARTIAL_MAX_RETRIES: " +
                                    "pageKey=$pageKey missing=${missing.size}"
                            }
                            pageTranslation.translationStatus = StageStatus.RUNNING
                            publishLiveStage("single-page translation retry running") {
                                it.translationStatus = StageStatus.RUNNING
                            }
                            val retryPage = PageTranslation(blocks = missing.toMutableList())
                            runTranslate(retryPage)
                            TranslationBlockValidation.applyTo(pageTranslation)
                        }
                    }
                    if (translationOutcome is ChunkCompletionOutcome.Completed &&
                        pageTranslation.translationStatus == StageStatus.PARTIAL
                    ) {
                        // A translator may report a partial response without throwing.
                        // Keep that outcome typed as a pause so rolling auto does not
                        // promote a partially translated page to Ready.
                        val partialFailure = ProviderFailure(
                            kind = ProviderFailureKind.PROTOCOL,
                            retryability = ProviderFailureRetryability.PAUSE,
                            safeSummary = "translation output is partial",
                        )
                        pageTranslation.translationError = partialFailure.safeSummary
                        translationOutcome = ChunkCompletionOutcome.Paused(
                            anchorPageKey = pageKey,
                            retryablePageKeys = setOf(pageKey),
                            failure = partialFailure,
                            nextEligibleRetryAtEpochMs = partialFailure.retryAfterAtEpochMs,
                            reason = partialFailure.safeSummary,
                        )
                    }
                    // Fold this page's translated pairs into the chapter glossary so later
                    // on-demand/batch translations reuse its established terms.
                    if (activeTranslator is ContextualTextTranslator) {
                        val pairs = pageTranslation.blocks.mapNotNull { block ->
                            val s = block.text.trim()
                            val t = block.translation.trim()
                            if (s.isBlank() || t.isBlank() || t == s) null else s to t
                        }
                        store.foldPageContribution(pageKey, pairs)
                    }
                    // Stamp the live glossary version AFTER
                    // this page's own pairs folded, before the durable write — a pre-fold
                    // stamp would record the version BELOW the one this page's own fold
                    // creates, guaranteeing one wasted batch repair per manually translated
                    // page after every session. A concurrent mode's fold between request
                    // build and commit is claimed but unseen: rare, converging, accepted.
                    // Absence of a pointer stamps 0 (gate-off semantics: `0 > recorded` can
                    // only repair chapters where a glossary exists and matured).
                    pageTranslation.translationGlossaryVersion = store.currentGlossaryVersion() ?: 0
                    val translatedCount = pageTranslation.blocks.count { !it.translation.isNullOrBlank() }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT translate step DONE: pageHash=${ShortHash.hash(pageKey)} " +
                            "translated=$translatedCount/${pageTranslation.blocks.size} " +
                            "status=${pageTranslation.translationStatus}" +
                            (if (singlePageRetry > 0) " partialRetries=$singlePageRetry" else "")
                    }
                    translateSpan.end(TranslationTraceOutcome.SUCCESS)
                } catch (e: ProviderFailureException) {
                    val failure = e.failure
                    val retryable = failure.retryability != ProviderFailureRetryability.TERMINAL
                    if (retryable) {
                        pageTranslation.translationStatus = StageStatus.PARTIAL
                        pageTranslation.translationError = failure.safeSummary
                        translationOutcome = ChunkCompletionOutcome.Paused(
                            anchorPageKey = pageKey,
                            retryablePageKeys = setOf(pageKey),
                            failure = failure,
                            nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
                            reason = failure.safeSummary,
                        )
                    } else {
                        pageTranslation.translationStatus = StageStatus.FAILED
                        pageTranslation.errorMessage = failure.safeSummary
                        translationOutcome = ChunkCompletionOutcome.Failed(
                            anchorPageKey = pageKey,
                            terminalPageKeys = setOf(pageKey),
                            failure = failure,
                            reason = failure.safeSummary,
                        )
                    }
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT single-page provider outcome: pageHash=${ShortHash.hash(pageKey)} " +
                            "kind=${failure.kind} retryable=$retryable"
                    }
                    translateSpan.end(
                        if (retryable) TranslationTraceOutcome.PAUSE else TranslationTraceOutcome.FAILURE,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        translateSpan.end(TranslationTraceOutcome.CANCELLED, error = e)
                        throw e
                    }
                    val failure = classifyProviderFailure(e)
                    val retryable = failure.retryability != ProviderFailureRetryability.TERMINAL
                    if (retryable) {
                        pageTranslation.translationStatus = StageStatus.PARTIAL
                        pageTranslation.translationError = failure.safeSummary
                        translationOutcome = ChunkCompletionOutcome.Paused(
                            anchorPageKey = pageKey,
                            retryablePageKeys = setOf(pageKey),
                            failure = failure,
                            nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
                            reason = failure.safeSummary,
                        )
                    } else {
                        pageTranslation.translationStatus = StageStatus.FAILED
                        pageTranslation.errorMessage = failure.safeSummary
                        translationOutcome = ChunkCompletionOutcome.Failed(
                            anchorPageKey = pageKey,
                            terminalPageKeys = setOf(pageKey),
                            failure = failure,
                            reason = failure.safeSummary,
                        )
                    }
                    logcat(LogPriority.ERROR) {
                        "Failed to translate text for single page pageHash=${ShortHash.hash(pageKey)}: " +
                            "kind=${failure.kind} retryable=$retryable"
                    }
                    translateSpan.end(
                        if (retryable) TranslationTraceOutcome.PAUSE else TranslationTraceOutcome.FAILURE,
                    )
                }
            }

            coroutineContext.ensureActive()

            if (translationOutcome is ChunkCompletionOutcome.Completed &&
                pageTranslation.blocks.isNotEmpty() &&
                (
                    pageTranslation.translationStatus == StageStatus.READY ||
                        pageTranslation.translationStatus == StageStatus.PARTIAL
                    )
            ) {
                val hasCleanedBitmap = pageTranslation.cleanedBitmap != null
                val hasCleanedOnDisk = pageTranslation.cleanedImageName != null && pageTranslation.inpaintStatus == StageStatus.READY
                if (hasCleanedBitmap || hasCleanedOnDisk) {
                    val cleanedBitmap = pageTranslation.cleanedBitmap
                    // Layout (color estimation) is a sub-interval
                    // of the render attempt, so its duration is included in
                    // the render stage sum.
                    val layoutSpan = TranslationTrace.beginStage(
                        TranslationTraceStage.LAYOUT,
                        lane = TranslationTraceLane.RENDER,
                    )
                    val renderSpan = TranslationTrace.beginStage(
                        TranslationTraceStage.RENDER,
                        lane = TranslationTraceLane.RENDER,
                    )
                    try {
                        pageTranslation.renderStatus = StageStatus.RUNNING
                        publishLiveStage("single-page render running") {
                            it.renderStatus = StageStatus.RUNNING
                        }
                        stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                        if (cleanedBitmap != null) {
                            RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
                        }
                        layoutSpan.end()
                        pageTranslation.renderStatus = StageStatus.READY
                        pageTranslation.updatedAt = System.currentTimeMillis()
                        renderSpan.end()
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            layoutSpan.end(TranslationTraceOutcome.CANCELLED)
                            renderSpan.end(TranslationTraceOutcome.CANCELLED, error = e)
                            throw e
                        }
                        layoutSpan.end(TranslationTraceOutcome.FAILURE)
                        renderSpan.end(TranslationTraceOutcome.FAILURE, error = e)
                        markRenderFailure()
                        logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                    } finally {
                        if (cleanedBitmap != null && ctx.pendingCleanedPublication == null) {
                            try {
                                cleanedBitmap.recycle()
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT render blocked for $pageKey: inpaint produced no cleaned bitmap; " +
                            "retrying at lower resolution"
                    }
                    val retryResult = retryInpaintDownscaled(
                        manga = manga,
                        chapter = chapter,
                        source = source,
                        pageKey = pageKey,
                        streams = streams,
                        decoded = decoded,
                        pageTranslation = pageTranslation,
                    )
                    val retriedCleaned = retryResult.cleanedBitmap
                    if (retriedCleaned != null) {
                        val companionDir = provider.getCompanionImageDir(
                            manga.title,
                            source,
                            chapter.name,
                            chapter.scanlator,
                        )
                        val published = persistCleanedBitmap(
                            pageTranslation,
                            retriedCleaned,
                            companionDir,
                            pageKey,
                            chapter.name,
                            store,
                            source.id,
                            manga.id,
                            chapter.id,
                            expectedPrecondition = commitPrecondition,
                        )
                        if (published != null) {
                            commitPrecondition = published.toPrecondition()
                        }
                        // Spans are hoisted above the try so every
                        // outcome (skip/failure/cancel/success) settles them.
                        val retryLayoutSpan = TranslationTrace.beginStage(
                            TranslationTraceStage.LAYOUT,
                            lane = TranslationTraceLane.RENDER,
                        )
                        val retryRenderSpan = TranslationTrace.beginStage(
                            TranslationTraceStage.RENDER,
                            lane = TranslationTraceLane.RENDER,
                        )
                        try {
                            if (published == null) {
                                retryLayoutSpan.end(TranslationTraceOutcome.SKIP)
                                retryRenderSpan.end(TranslationTraceOutcome.SKIP)
                                markRenderFailure()
                                pageTranslation.errorMessage =
                                    "Cleaned image could not be published; translated text was not rendered."
                            } else {
                                pageTranslation.renderStatus = StageStatus.RUNNING
                                publishLiveStage("single-page render retry running") {
                                    it.renderStatus = StageStatus.RUNNING
                                }
                                stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                                RenderColorEstimator.recomputeFor(retriedCleaned, pageTranslation.blocks)
                                retryLayoutSpan.end()
                                pageTranslation.renderStatus = StageStatus.READY
                                pageTranslation.updatedAt = System.currentTimeMillis()
                                retryRenderSpan.end()
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                retryLayoutSpan.end(TranslationTraceOutcome.CANCELLED)
                                retryRenderSpan.end(TranslationTraceOutcome.CANCELLED, error = e)
                                throw e
                            }
                            retryLayoutSpan.end(TranslationTraceOutcome.FAILURE)
                            retryRenderSpan.end(TranslationTraceOutcome.FAILURE, error = e)
                            markRenderFailure()
                            logcat(LogPriority.ERROR, e) { "Failed to render text for single page (retry path) $pageKey" }
                        } finally {
                            try {
                                retriedCleaned.recycle()
                            } catch (_: Exception) {}
                        }
                    } else {
                        markRenderFailure()
                        val reason = pageTranslation.errorMessage ?: "inpaint unavailable"
                        pageTranslation.errorMessage =
                            "Inpainting unavailable ($reason) — original text would show through, so the " +
                            "translated text was not rendered. Retry, or switch recognition mode."
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT single-page render BLOCKED for $pageKey: inpaint unavailable after retry " +
                                "(reason=$reason). Showing original image with error instead of a half-translated overlay."
                        }
                    }
                }
            } else {
                pageTranslation.cleanedBitmap?.let {
                    try {
                        it.recycle()
                    } catch (_: Exception) {}
                }
            }
            pageTranslation.cleanedBitmap = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            //   a batch that STARTED while this boundary was parked
            // mid-flight (e.g. a reader tap held across the batch's engine
            // setup) advances the store generation AND legitimately re-plans
            // the chapter candidates (page identity + dependency fingerprints)
            // without ever touching this page — the page's live lease still
            // fences every other WRITER out. The run-level fences would then
            // discard the owner's finished result as "late", stranding the
            // page non-terminal even though nobody else wrote it. When this
            // boundary STILL owns the page lease, re-derive the commit
            // precondition from a fresh snapshot, keeping the writer fences
            // (generation, pageVersion, lease token, block fingerprints) but
            // dropping the batch-run plan identity (candidate generation /
            // dependency fingerprint / artifact page version) — per  the
            // lease holder is the page's exclusive writer, so plan-level
            // identity re-planning must not invalidate its in-flight result.
            if (store.pageLeaseOwner(pageKey) == origin) {
                val fresh = store.snapshot(pageKey)
                //  ( flake): refresh on ANY store drift under our own
                // lease, not only a generation change — a deferred group-commit
                // publication can bump pageVersion at the SAME generation
                // between the entry capture and this commit, and per  the
                // lease holder is the page's exclusive writer, so its own
                // side-effect must not reject its commit (the same
                // refresh-before-persist idiom as guardedBatchUpdate).
                if (fresh.generation != commitPrecondition.generation ||
                    fresh.pageVersion != commitPrecondition.pageVersion
                ) {
                    commitPrecondition = fresh.toPrecondition().copy(
                        candidateGenerationId = null,
                        dependencyFingerprint = null,
                        artifactPageVersion = null,
                    )
                }
            }
            // Guarded commit boundary. The span settles on every exit,
            // including when patchPage throws, so no stage start is left open.
            val commitSpan = TranslationTrace.beginStage(
                TranslationTraceStage.STORE_COMMIT,
                lane = TranslationTraceLane.STORAGE,
            )
            val commit = try {
                val result = store.patchPage(
                    pageKey = pageKey,
                    expected = commitPrecondition,
                    description = "commit single-page translation and render",
                ) { pageTranslation }
                commitSpan.end(
                    if (result is ChapterTranslationStore.PatchResult.Rejected) {
                        TranslationTraceOutcome.FAILURE
                    } else {
                        TranslationTraceOutcome.SUCCESS
                    },
                )
                result
            } catch (t: Throwable) {
                commitSpan.end(TranslationTraceOutcome.FAILURE, error = t)
                throw t
            }
            if (commit is ChapterTranslationStore.PatchResult.Rejected) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page late result rejected: chapter=${chapter.name} " +
                        "pageKey=$pageKey reason=${commit.reason}"
                }
                translationOutcome = ChunkCompletionOutcome.PersistenceRejected(
                    anchorPageKey = pageKey,
                    stage = BatchDiagnosticStage.TRANSLATION,
                    reason = "Batch persistence publication rejected",
                )
            } else {
                // A newer committed display bundle may have just promoted; the
                // file it superseded is now deletable.
                deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
            }
        } finally {
            // Settle the governor wait when no translation ran,
            // and measure the durable flush (storage lane). All non-suspending.
            if (!governorSpanSettled) {
                governorSpanSettled = true
                governorSpan.end()
            }
            val flushSpan = TranslationTrace.beginStage(
                TranslationTraceStage.STORE_FLUSH,
                lane = TranslationTraceLane.STORAGE,
            )
            // Settle the flush span even when store.flush() throws. The
            // original exception still propagates.
            try {
                store.flush()
            } finally {
                flushSpan.end()
                // The flush above is the durability barrier for the lazy
                // cleaned image. Release the bitmap even when the barrier
                // reports an error; the live page commit did not wait for this
                // disk path.
                deferredCleanedBitmap?.let {
                    try {
                        it.recycle()
                    } catch (_: Exception) {}
                }
            }
            // Defensive recycle: a cancel/timeout can unwind here from before render, where
            // cleanedBitmap (the inpainted full-page bitmap, ~10–48 MB) was never recycled.
            pageTranslation.cleanedBitmap?.let {
                try {
                    it.recycle()
                } catch (_: Exception) {}
            }
            pageTranslation.cleanedBitmap = null
            chapter.id?.let { chapterId ->
                streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
            }
            try {
                recognitionEngine.reclaimPooledMemory()
            } catch (_: Exception) {}
            // The translator borrow ends here. This release is the
            // event a pending engine close DRAINS on before tearing the (snapshot
            // of the) engines down.
            engines.endTranslatorUse()
        }
        return translationOutcome
    }
}
