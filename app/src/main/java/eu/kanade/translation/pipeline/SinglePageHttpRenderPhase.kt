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
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceStage
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
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.persistence.AttemptOrigin
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.persistence.ocrFingerprint
import eu.kanade.translation.persistence.toArtifactOrigin
import eu.kanade.translation.pipeline.TranslationPipeline.Companion.SINGLE_PAGE_PARTIAL_MAX_RETRIES
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
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
 * Runs HTTP translation outside the native permit. It handles contextual or
 * plain translation, bounded partial retries, and guarded artifact commit.
 */
internal class SinglePageHttpRenderPhase(
    private val translationPreferences: TranslationPreferences,
    private val provider: TranslationFileProvider,
    private val streamRegistry: eu.kanade.translation.pipeline.execution.TranslationStreamRegistry,
    private val engines: EngineLane,
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
                    logcat(LogPriority.ERROR, e) {
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
     * Commits translated page artifacts and retries cleaned-image publication
     * when the first inpaint output could not be produced.
     */
    suspend fun renderSinglePageTranslated(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        ctx: OnnxPhaseResult,
        owner: CleanedOwner,
        attempt: AttemptAuthority? = null,
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
    ): TranslationCompletionOutcome {
        val pageTranslation = ctx.pageTranslation
        val store = ctx.store
        var translationOutcome: TranslationCompletionOutcome = TranslationCompletionOutcome.Completed()
        var commitPrecondition = ctx.commitPrecondition ?: store.snapshot(pageKey).toPrecondition()

        var didRetryInpaint = false
        if (
            pageTranslation.blocks.isNotEmpty() &&
            pageTranslation.translationStatus in setOf(StageStatus.READY, StageStatus.PARTIAL)
        ) {
            val cleanedBitmap = owner.borrow()
            val hasCleanedOnDisk = pageTranslation.isCleanedImageReady ||
                attempt?.let { store.snapshot(pageKey).page?.isCleanedImageReady } == true
            if (cleanedBitmap != null || hasCleanedOnDisk) {
                if (cleanedBitmap != null) {
                    owner.closeBorrow()
                }
            } else {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT cleaned image unavailable for $pageKey; retrying inpaint at lower resolution"
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
                val published = if (attempt != null) {
                    val enrolled = cleanedPublication.enrollCleanedPersistence(
                        manga,
                        chapter,
                        source,
                        pageKey,
                        store,
                        retryResult,
                        owner,
                        attempt,
                    )
                    if (enrolled) store.flush()
                    if (enrolled && owner.awaitPersistence()) store.snapshot(pageKey) else null
                } else {
                    val companionDir = provider.getCompanionImageDir(
                        manga.title,
                        source,
                        chapter.name,
                        chapter.scanlator,
                    )
                    persistCleanedBitmap(
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
                }
                if (published != null) {
                    commitPrecondition = published.toPrecondition()
                } else {
                    val reason = "Cleaned image could not be published; retry inpainting"
                    pageTranslation.inpaintStatus = StageStatus.FAILED
                    pageTranslation.inpaintError = reason
                    pageTranslation.errorMessage = reason
                    pageTranslation.recordAttemptFailure()
                    translationOutcome = TranslationCompletionOutcome.Failed(
                        anchorPageKey = pageKey,
                        reason = reason,
                    )
                }
            } else {
                val reason = retryResult.inpaintError ?: retryResult.errorMessage ?: "inpaint unavailable"
                val message =
                    "Inpainting unavailable ($reason). Retry, or switch recognition mode."
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.inpaintError = message
                pageTranslation.errorMessage = message
                pageTranslation.recordAttemptFailure()
                translationOutcome = TranslationCompletionOutcome.Failed(
                    anchorPageKey = pageKey,
                    reason = message,
                )
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page inpaint retry failed for $pageKey: $reason"
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
        val commit = try {
            val result = if (attempt != null) {
                attempt.write("commit single-page translation artifacts") { current ->
                    current.apply {
                        blocks = pageTranslation.blocks
                        translationStatus = pageTranslation.translationStatus
                        translationError = pageTranslation.translationError
                        translationOrigin = pageTranslation.translationOrigin
                        translationFingerprint = pageTranslation.translationFingerprint
                        layoutFingerprint = pageTranslation.layoutFingerprint
                        if (didRetryInpaint) {
                            if (pageTranslation.cleanedImageName != null) {
                                cleanedImageName = pageTranslation.cleanedImageName
                                cleanedImageContentHash = pageTranslation.cleanedImageContentHash
                            }
                            if (pageTranslation.inpaintStatus != StageStatus.PENDING) {
                                inpaintStatus = pageTranslation.inpaintStatus
                                inpaintError = pageTranslation.inpaintError
                            }
                            if (pageTranslation.inpaintingModeUsed != null) {
                                inpaintingModeUsed = pageTranslation.inpaintingModeUsed
                            }
                            if (pageTranslation.inpaintRevision != 0) {
                                inpaintRevision = pageTranslation.inpaintRevision
                            }
                        }
                        if (pageTranslation.originalImageFallback) {
                            originalImageFallback = true
                        }
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
                    description = "commit single-page translation artifacts",
                ) { pageTranslation }
            }
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
