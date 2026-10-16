package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.context.ChapterContextService
import eu.kanade.translation.context.ContextRequest
import eu.kanade.translation.context.LaneCapability
import eu.kanade.translation.diagnostics.BatchDiagnosticDecision
import eu.kanade.translation.diagnostics.BatchDiagnosticReason
import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.diagnostics.BatchTranslationDiagnostics
import eu.kanade.translation.diagnostics.DeviceStageNormalization
import eu.kanade.translation.diagnostics.NativeVisionTelemetry
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.TranslationBlockValidation
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.providers.LmStudioTranslator
import eu.kanade.translation.engines.translator.retry.AiTranslationRetryPlanner
import eu.kanade.translation.engines.translator.retry.RequestRetryBudget
import eu.kanade.translation.engines.translator.retry.classifyProviderFailure
import eu.kanade.translation.engines.translator.retry.withRequestRetryBudget
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.persistence.artifact.AttemptOrigin
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.persistence.chapter.ocrFingerprint
import eu.kanade.translation.persistence.chapter.toArtifactOrigin
import eu.kanade.translation.pipeline.TranslationPipeline.Companion.SINGLE_PAGE_PARTIAL_MAX_RETRIES
import eu.kanade.translation.pipeline.adaptive.DevicePagePermitGate
import eu.kanade.translation.pipeline.adaptive.DevicePermitPriority
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Runs HTTP translation outside the native permit, then reacquires the device
 * permit for Canvas rendering. It handles contextual or plain translation,
 * bounded partial retries, color recompute, and guarded commit.
 */
internal class SinglePageHttpRenderPhase(
    private val translationPreferences: TranslationPreferences,
    private val provider: TranslationFileProvider,
    private val streamRegistry: eu.kanade.translation.pipeline.execution.TranslationStreamRegistry,
    private val engines: EngineLane,
    private val devicePagePermitGate: DevicePagePermitGate,
    private val cleanedPublication: CleanedPublication,
    private val expectedBatchFingerprints: (TextRecognizerLanguage, TextTranslatorLanguage) -> BatchExpectedFingerprints,
    private val retryInpaintDownscaledFn: suspend (
        Manga,
        Chapter,
        HttpSource,
        String,
        List<Pair<String, () -> InputStream>>,
        DecodedPage,
        PageTranslation,
        CleanedOwner?,
        AttemptAuthority?,
    ) -> PageTranslation,
) {

    private suspend fun withRenderPermit(
        origin: PageWriteOrigin,
        block: suspend (permitWaitMs: Double) -> Unit,
    ): Boolean {
        val waitStart = System.nanoTime()
        val waitSpan = TranslationTrace.beginStage(
            stage = TranslationTraceStage.RENDER_PERMIT_WAIT,
            lane = TranslationTraceLane.SCHEDULER,
            site = if (origin == PageWriteOrigin.MANUAL) {
                TranslationTraceSite.READER_SINGLE_PAGE
            } else {
                TranslationTraceSite.ROLLING_AUTO_TRANSLATION
            },
            leaseKind = TranslationTraceLeaseKind.RENDER,
        )
        val permit = try {
            devicePagePermitGate.acquire(
                if (origin == PageWriteOrigin.MANUAL) DevicePermitPriority.FOREGROUND else DevicePermitPriority.BACKGROUND,
            )
        } catch (t: Throwable) {
            waitSpan.end(
                if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                error = t,
            )
            throw t
        }
        val permitWaitMs = (System.nanoTime() - waitStart) / 1_000_000.0
        waitSpan.end(if (permit == null) TranslationTraceOutcome.TIMEOUT else TranslationTraceOutcome.SUCCESS)
        if (permit == null) return false

        try {
            block(permitWaitMs)
        } finally {
            permit.release()
        }
        return true
    }

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
        owner: CleanedOwner? = null,
        attempt: AttemptAuthority? = null,
    ): PageTranslation = retryInpaintDownscaledFn(manga, chapter, source, pageKey, streams, decoded, pageTranslation, owner, attempt)

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

    private fun singlePageProfile(translator: Any): TranslationContextChunkPlanner.Profile =
        if (translator is LmStudioTranslator) {
            TranslationContextChunkPlanner.Profile.LM_STUDIO
        } else {
            TranslationContextChunkPlanner.Profile.DEFAULT
        }

    private fun prepareTranslationContext(
        store: ChapterTranslationStore,
        pageKey: String,
        targetPage: PageTranslation,
        translator: Any,
        origin: PageWriteOrigin,
        requestedOutputTokens: Int,
    ): Pair<eu.kanade.translation.context.PreparedContext, TranslationContextChunk> {
        val targetLang = translationPreferences.translateToLanguage().get()
        val sourceLang = translationPreferences.translateFromLanguage().get()
        val laneCap = if (origin == PageWriteOrigin.AUTO) LaneCapability.AUTO else LaneCapability.MANUAL
        val profile = singlePageProfile(translator)
        val prepared = ChapterContextService(store).prepare(
            ContextRequest(
                pageKeys = listOf(pageKey),
                targetLang = targetLang,
                sourceLang = sourceLang,
                profile = profile,
                laneCapability = laneCap,
            ),
        )
        val baseChunk = TranslationContextChunk(
            pages = linkedMapOf(pageKey to targetPage),
            blockCount = targetPage.blocks.count { it.text.isNotBlank() },
            rollingContext = "",
            estimatedPromptTokens = TranslationContextChunkPlanner.PROMPT_OVERHEAD_TOKENS +
                targetPage.blocks.sumOf { TranslationContextChunkPlanner.estimateTokens(it.text) },
            maxOutputTokens = requestedOutputTokens,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        val finalizedChunk = TranslationContextChunkPlanner.withRollingContext(
            chunk = baseChunk,
            rollingContext = prepared.rollingContext,
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
        )
        return prepared to finalizedChunk
    }

    /**
     * Translates single-page text blocks via HTTP provider outside the native permit.
     * Settles governorSpan on first provider invocation, handles bounded epoch-guard
     * retry, and publishes translation status via [attempt] authority when provided.
     */
    suspend fun translateSinglePageHttpOnly(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        ctx: OnnxPhaseResult,
        attempt: AttemptAuthority? = null,
        stageListener: TranslationStageListener? = null,
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
    ): TranslationCompletionOutcome {
        val pageTranslation = ctx.pageTranslation
        pageTranslation.translationOrigin = origin.toArtifactOrigin().name
        val store = ctx.store
        val fromLang = ctx.fromLang
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val decoded = ctx.decoded
        val batchFingerprints = batchExpectedFingerprints(fromLang, toLang)

        if (decoded.sourceBytesSize > 0L) {
            pageTranslation.sourceFingerprint = decoded.sourceFingerprint
            pageTranslation.detectionFingerprint = batchFingerprints.detection
            pageTranslation.ocrFingerprint = batchFingerprints.ocr
            pageTranslation.inpaintFingerprint = batchFingerprints.inpaint
        }
        pageTranslation.translationFingerprint = batchFingerprints.translation
        pageTranslation.layoutFingerprint = batchFingerprints.layout

        var activeTranslator = textTranslator
        val epochAtCapture = engines.currentEngineEpoch()

        val retryBudget = RequestRetryBudget()
        var governorSpan: eu.kanade.translation.diagnostics.TranslationStageSpan? = null
        var governorSpanSettled = false
        var translationOutcome: TranslationCompletionOutcome = TranslationCompletionOutcome.Completed()

        suspend fun publishLiveStage(
            description: String,
            update: (PageTranslation) -> Unit,
        ) {
            if (attempt != null) {
                attempt.write(description) { current ->
                    current.apply {
                        update(this)
                        updatedAt = System.currentTimeMillis()
                    }
                }
            } else {
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
        }

        val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
            ?: TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS

        suspend fun runLedgerWrapped(requestContextFingerprint: String, call: suspend () -> Unit) {
            runCatching {
                store.recordAttemptStart(
                    pageKey = pageKey,
                    providerKeyHash = ShortHash.hash(activeTranslator.javaClass.name),
                    origin = AttemptOrigin.valueOf(origin.name),
                    generation = store.currentGeneration,
                    requestContextFingerprint = requestContextFingerprint,
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

        suspend fun translateOnce(targetPage: PageTranslation, chunk: TranslationContextChunk) {
            if (!governorSpanSettled) {
                governorSpanSettled = true
                governorSpan?.end()
            }
            val providerLaneToken = TranslationTrace.currentRun()?.schedule?.enterLane(TranslationTraceLane.PROVIDER)
            try {
                val ct = activeTranslator as? ContextualTextTranslator
                if (ct != null) {
                    ct.translateContextual(chunk)
                } else {
                    activeTranslator.translatePage(pageKey, targetPage)
                }
            } finally {
                providerLaneToken?.close()
            }
        }

        var epochRetryUsed = false
        suspend fun runTranslate(targetPage: PageTranslation) {
            val (preparedContext, contextChunk) = prepareTranslationContext(
                store,
                pageKey,
                targetPage,
                activeTranslator,
                origin,
                requestedOutputTokens,
            )
            val requestContextFingerprint = preparedContext.computeRequestContextFingerprint(
                targetLang = translationPreferences.translateToLanguage().get(),
                sourceLang = translationPreferences.translateFromLanguage().get(),
                finalizedRollingContext = contextChunk.rollingContext,
            )
            runLedgerWrapped(requestContextFingerprint) {
                try {
                    translateOnce(targetPage, contextChunk)
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
                        translateOnce(targetPage, contextChunk)
                    } else {
                        throw e
                    }
                }
            }
        }

        try {
            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty()) {
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
                val translateSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.TRANSLATE,
                    provider = if (
                        TranslatorComputeClass.forTranslator(activeTranslator) == TranslatorComputeClass.LOCAL_COMPUTE
                    ) {
                        TranslationTraceProvider.LOCAL
                    } else {
                        TranslationTraceProvider.REMOTE
                    },
                    normalizationUnits = DeviceStageNormalization.textVolume(pageTranslation.blocks),
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
                    if (governorSpan == null) {
                        governorSpan = TranslationTrace.beginStage(
                            TranslationTraceStage.PROVIDER_GOVERNOR_WAIT,
                            lane = TranslationTraceLane.PROVIDER,
                        )
                    }
                    withRequestRetryBudget(retryBudget) {
                        runTranslate(pageTranslation)
                        TranslationBlockValidation.applyTo(
                            pageTranslation,
                            sourceLanguageCode = fromLang.code,
                            targetLanguageCode = toLang.code,
                        )
                        while (pageTranslation.translationStatus == StageStatus.PARTIAL &&
                            singlePageRetry < SINGLE_PAGE_PARTIAL_MAX_RETRIES
                        ) {
                            val missing = AiTranslationRetryPlanner.untranslatedBlocks(
                                pageTranslation,
                                sourceLanguageCode = fromLang.code,
                                targetLanguageCode = toLang.code,
                            )
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
                            TranslationBlockValidation.applyTo(
                                pageTranslation,
                                sourceLanguageCode = fromLang.code,
                                targetLanguageCode = toLang.code,
                            )
                        }
                    }
                    if (translationOutcome is TranslationCompletionOutcome.Completed &&
                        pageTranslation.translationStatus == StageStatus.PARTIAL
                    ) {
                        val partialFailure = ProviderFailure(
                            kind = ProviderFailureKind.PROTOCOL,
                            retryability = ProviderFailureRetryability.PAUSE,
                            safeSummary = "translation output is partial",
                        )
                        pageTranslation.translationError = partialFailure.safeSummary
                        translationOutcome = TranslationCompletionOutcome.Paused(
                            anchorPageKey = pageKey,
                            retryablePageKeys = setOf(pageKey),
                            failure = partialFailure,
                            nextEligibleRetryAtEpochMs = partialFailure.retryAfterAtEpochMs,
                            reason = partialFailure.safeSummary,
                        )
                    }
                    val translatedCount = pageTranslation.blocks.count { !it.translation.isNullOrBlank() }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT translate step DONE: pageHash=${ShortHash.hash(pageKey)} " +
                            "translated=$translatedCount/${pageTranslation.blocks.size} " +
                            "status=${pageTranslation.translationStatus}" +
                            (if (singlePageRetry > 0) " partialRetries=$singlePageRetry" else "")
                    }
                    translateSpan.end(TranslationTraceOutcome.SUCCESS)

                    publishLiveStage("single-page translation result") { current ->
                        current.blocks = pageTranslation.blocks
                        current.translationStatus = pageTranslation.translationStatus
                        current.translationError = pageTranslation.translationError
                        current.translationOrigin = pageTranslation.translationOrigin
                        current.translationFingerprint = pageTranslation.translationFingerprint
                        current.layoutFingerprint = pageTranslation.layoutFingerprint
                        if (pageTranslation.errorMessage != null) {
                            current.errorMessage = pageTranslation.errorMessage
                        }
                    }
                } catch (e: ProviderFailureException) {
                    val failure = e.failure
                    val retryable = failure.retryability != ProviderFailureRetryability.TERMINAL
                    if (retryable) {
                        pageTranslation.translationStatus = StageStatus.PARTIAL
                        pageTranslation.translationError = failure.safeSummary
                        translationOutcome = TranslationCompletionOutcome.Paused(
                            anchorPageKey = pageKey,
                            retryablePageKeys = setOf(pageKey),
                            failure = failure,
                            nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
                            reason = failure.safeSummary,
                        )
                    } else {
                        pageTranslation.translationStatus = StageStatus.FAILED
                        pageTranslation.errorMessage = failure.safeSummary
                        translationOutcome = TranslationCompletionOutcome.Failed(
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
                    publishLiveStage("single-page translation failure") { current ->
                        current.translationStatus = pageTranslation.translationStatus
                        current.translationError = pageTranslation.translationError
                        current.errorMessage = pageTranslation.errorMessage
                    }
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
                        translationOutcome = TranslationCompletionOutcome.Paused(
                            anchorPageKey = pageKey,
                            retryablePageKeys = setOf(pageKey),
                            failure = failure,
                            nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
                            reason = failure.safeSummary,
                        )
                    } else {
                        pageTranslation.translationStatus = StageStatus.FAILED
                        pageTranslation.errorMessage = failure.safeSummary
                        translationOutcome = TranslationCompletionOutcome.Failed(
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
                    publishLiveStage("single-page translation failure") { current ->
                        current.translationStatus = pageTranslation.translationStatus
                        current.translationError = pageTranslation.translationError
                        current.errorMessage = pageTranslation.errorMessage
                    }
                }
            }
        } finally {
            if (!governorSpanSettled) {
                governorSpanSettled = true
                governorSpan?.end()
            }
        }
        return translationOutcome
    }

    /**
     * Renders translated text onto the cleaned bitmap via Canvas and performs
     * the final guarded commit to the store via [attempt].
     */
    suspend fun renderSinglePageTranslated(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        ctx: OnnxPhaseResult,
        owner: CleanedOwner,
        attempt: AttemptAuthority? = null,
        stageListener: TranslationStageListener? = null,
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
    ): TranslationCompletionOutcome {
        val pageTranslation = ctx.pageTranslation
        val store = ctx.store
        var translationOutcome: TranslationCompletionOutcome = TranslationCompletionOutcome.Completed()
        var lastPermitWaitMs = 0.0
        var lastColorEstimateMs = 0.0
        var commitPrecondition = ctx.commitPrecondition ?: store.snapshot(pageKey).toPrecondition()

        val renderFailure = ProviderFailure(
            kind = ProviderFailureKind.PROTOCOL,
            retryability = ProviderFailureRetryability.RETRY_NOW,
            safeSummary = "rendering failed; retry required",
        )

        fun markRenderFailure() {
            pageTranslation.renderStatus = StageStatus.FAILED
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = renderFailure.safeSummary
            translationOutcome = TranslationCompletionOutcome.Failed(
                anchorPageKey = pageKey,
                terminalPageKeys = emptySet(),
                failure = renderFailure,
                reason = renderFailure.safeSummary,
            )
        }

        fun deferRenderForPermit() {
            val failure = ProviderFailure(
                kind = ProviderFailureKind.REFUSAL,
                retryability = ProviderFailureRetryability.PAUSE,
                safeSummary = "render permit unavailable; retry required",
            )
            pageTranslation.renderStatus = StageStatus.PENDING
            translationOutcome = TranslationCompletionOutcome.Paused(
                anchorPageKey = pageKey,
                retryablePageKeys = setOf(pageKey),
                failure = failure,
                nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
                reason = failure.safeSummary,
            )
        }

        suspend fun publishLiveStage(
            description: String,
            update: (PageTranslation) -> Unit,
        ) {
            if (attempt != null) {
                attempt.write(description) { current ->
                    current.apply {
                        update(this)
                        updatedAt = System.currentTimeMillis()
                    }
                }
            } else {
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
        }

        if (pageTranslation.blocks.isNotEmpty() &&
            (
                pageTranslation.translationStatus == StageStatus.READY ||
                    pageTranslation.translationStatus == StageStatus.PARTIAL
                )
        ) {
            val cleanedBitmap = owner.borrow()
            val hasCleanedOnDisk = pageTranslation.isCleanedImageReady
            if (cleanedBitmap != null || hasCleanedOnDisk) {
                pageTranslation.renderStatus = StageStatus.RUNNING
                val liveRenderPublished = try {
                    publishLiveStage("single-page render running") {
                        it.renderStatus = StageStatus.RUNNING
                    }
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    markRenderFailure()
                    logcat(LogPriority.ERROR, e) { "Failed to publish render start for single page $pageKey" }
                    false
                }
                try {
                    if (liveRenderPublished) {
                        val renderStageAdmitted = try {
                            stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                            true
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            markRenderFailure()
                            logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                            false
                        }
                        if (renderStageAdmitted) {
                            val rendered = withRenderPermit(origin) { permitWaitMs ->
                                lastPermitWaitMs = permitWaitMs
                                val layoutSpan = TranslationTrace.beginStage(
                                    TranslationTraceStage.LAYOUT,
                                    lane = TranslationTraceLane.RENDER,
                                    normalizationUnits = DeviceStageNormalization.regionArea(pageTranslation.blocks),
                                )
                                try {
                                    if (cleanedBitmap != null) {
                                        val renderSpan = TranslationTrace.beginStage(
                                            TranslationTraceStage.RENDER,
                                            lane = TranslationTraceLane.RENDER,
                                            items = 0,
                                            normalizationUnits = DeviceStageNormalization.regionArea(pageTranslation.blocks),
                                        )
                                        try {
                                            val renderLaneToken =
                                                TranslationTrace.currentRun()?.schedule?.enterLane(TranslationTraceLane.RENDER)
                                            try {
                                                val colorEstimateStart = System.nanoTime()
                                                RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
                                                lastColorEstimateMs = (System.nanoTime() - colorEstimateStart) / 1_000_000.0
                                            } finally {
                                                renderLaneToken?.close()
                                            }
                                        } catch (e: Exception) {
                                            renderSpan.end(
                                                if (e is CancellationException) {
                                                    TranslationTraceOutcome.CANCELLED
                                                } else {
                                                    TranslationTraceOutcome.FAILURE
                                                },
                                                error = e,
                                            )
                                            throw e
                                        }
                                        renderSpan.end()
                                    }
                                    layoutSpan.end()
                                    pageTranslation.renderStatus = StageStatus.READY
                                    pageTranslation.updatedAt = System.currentTimeMillis()
                                } catch (e: Exception) {
                                    if (e is CancellationException) {
                                        layoutSpan.end(TranslationTraceOutcome.CANCELLED)
                                        throw e
                                    }
                                    layoutSpan.end(TranslationTraceOutcome.FAILURE)
                                    markRenderFailure()
                                    logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                                }
                            }
                            if (!rendered) deferRenderForPermit()
                        }
                    }
                } finally {
                    if (cleanedBitmap != null) {
                        owner.closeBorrow()
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
                    streams = ctx.streams,
                    decoded = ctx.decoded,
                    pageTranslation = pageTranslation,
                    owner = owner,
                    attempt = attempt,
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
                    if (published == null) {
                        markRenderFailure()
                        pageTranslation.errorMessage =
                            "Cleaned image could not be published; translated text was not rendered."
                    } else {
                        val borrowedRetried = owner?.borrow()
                        if (borrowedRetried != null) {
                            try {
                                pageTranslation.renderStatus = StageStatus.RUNNING
                                publishLiveStage("single-page render retry running") {
                                    it.renderStatus = StageStatus.RUNNING
                                }
                                val renderStageAdmitted = try {
                                    stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                                    true
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    markRenderFailure()
                                    logcat(LogPriority.ERROR, e) {
                                        "Failed to render text for single page (retry path) $pageKey"
                                    }
                                    false
                                }
                                if (renderStageAdmitted) {
                                    val rendered = withRenderPermit(origin) { permitWaitMs ->
                                        lastPermitWaitMs = permitWaitMs
                                        val retryLayoutSpan = TranslationTrace.beginStage(
                                            TranslationTraceStage.LAYOUT,
                                            lane = TranslationTraceLane.RENDER,
                                            normalizationUnits = DeviceStageNormalization.regionArea(pageTranslation.blocks),
                                        )
                                        try {
                                            val retryRenderSpan = TranslationTrace.beginStage(
                                                TranslationTraceStage.RENDER,
                                                lane = TranslationTraceLane.RENDER,
                                                items = 0,
                                                normalizationUnits = DeviceStageNormalization.regionArea(pageTranslation.blocks),
                                            )
                                            try {
                                                val renderLaneToken =
                                                    TranslationTrace.currentRun()?.schedule?.enterLane(TranslationTraceLane.RENDER)
                                                try {
                                                    val colorEstimateStart = System.nanoTime()
                                                    RenderColorEstimator.recomputeFor(borrowedRetried, pageTranslation.blocks)
                                                    lastColorEstimateMs = (System.nanoTime() - colorEstimateStart) / 1_000_000.0
                                                } finally {
                                                    renderLaneToken?.close()
                                                }
                                            } catch (e: Exception) {
                                                retryRenderSpan.end(
                                                    if (e is CancellationException) {
                                                        TranslationTraceOutcome.CANCELLED
                                                    } else {
                                                        TranslationTraceOutcome.FAILURE
                                                    },
                                                    error = e,
                                                )
                                                throw e
                                            }
                                            retryRenderSpan.end()
                                            retryLayoutSpan.end()
                                            pageTranslation.renderStatus = StageStatus.READY
                                            pageTranslation.updatedAt = System.currentTimeMillis()
                                        } catch (e: Exception) {
                                            if (e is CancellationException) {
                                                retryLayoutSpan.end(TranslationTraceOutcome.CANCELLED)
                                                throw e
                                            }
                                            retryLayoutSpan.end(TranslationTraceOutcome.FAILURE)
                                            markRenderFailure()
                                            logcat(LogPriority.ERROR, e) {
                                                "Failed to render text for single page (retry path) $pageKey"
                                            }
                                        }
                                    }
                                    if (!rendered) deferRenderForPermit()
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                markRenderFailure()
                                logcat(LogPriority.ERROR, e) { "Failed to render text for single page (retry path) $pageKey" }
                            } finally {
                                owner.closeBorrow()
                            }
                        }
                    }
                } else {
                    markRenderFailure()
                    val reason = pageTranslation.errorMessage ?: "inpaint unavailable"
                    pageTranslation.errorMessage =
                        "Inpainting unavailable ($reason) - original text would show through, so the " +
                        "translated text was not rendered. Retry, or switch recognition mode."
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT single-page render BLOCKED for $pageKey: inpaint unavailable after retry " +
                            "(reason=$reason). Showing original image with error instead of a half-translated overlay."
                    }
                }
            }
        }

        pageTranslation.cleanedBitmap = null
        pageTranslation.updatedAt = System.currentTimeMillis()

        val commitSpan = TranslationTrace.beginStage(
            TranslationTraceStage.STORE_COMMIT,
            lane = TranslationTraceLane.STORAGE,
        )
        val commitStart = System.nanoTime()
        val commit = try {
            val result = if (attempt != null) {
                attempt.write("commit single-page translation and render") { current ->
                    current.apply {
                        renderStatus = pageTranslation.renderStatus
                        if (pageTranslation.errorMessage != null) {
                            errorMessage = pageTranslation.errorMessage
                        }
                        updatedAt = System.currentTimeMillis()
                    }
                }
            } else {
                if (store.pageLeaseOwner(pageKey) == origin) {
                    val fresh = store.snapshot(pageKey)
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
                store.patchPage(
                    pageKey = pageKey,
                    expected = commitPrecondition,
                    description = "commit single-page translation and render",
                ) { pageTranslation }
            }
            val commitMs = (System.nanoTime() - commitStart) / 1_000_000.0
            NativeVisionTelemetry.logRenderTailBreakdown(
                pageKey = pageKey,
                permitWaitMs = lastPermitWaitMs,
                colorEstimateMs = lastColorEstimateMs,
                commitMs = commitMs,
                totalTailMs = lastPermitWaitMs + lastColorEstimateMs + commitMs,
            )
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
            translationOutcome = TranslationCompletionOutcome.PersistenceRejected(
                anchorPageKey = pageKey,
                stage = TranslationStageEvent.TRANSLATING,
                reason = "Batch persistence publication rejected",
            )
        } else {
            deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
        }

        return translationOutcome
    }
}
