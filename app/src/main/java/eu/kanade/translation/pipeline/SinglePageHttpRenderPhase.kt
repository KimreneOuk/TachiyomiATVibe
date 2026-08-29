package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.TranslationPipeline.OnnxPhaseResult
import eu.kanade.translation.TranslationPipeline.Companion.SINGLE_PAGE_PARTIAL_MAX_RETRIES
import eu.kanade.translation.batch.BatchDiagnosticDecision
import eu.kanade.translation.batch.BatchDiagnosticReason
import eu.kanade.translation.batch.BatchDiagnosticStage
import eu.kanade.translation.batch.BatchTranslationDiagnostics
import eu.kanade.translation.batch.ChunkCompletionOutcome
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.scheduling.TranslationStageEvent
import eu.kanade.translation.scheduling.TranslationStageListener
import eu.kanade.translation.translator.AiTranslationRetryPlanner
import eu.kanade.translation.translator.ChapterGlossaryBuilder
import eu.kanade.translation.translator.ContextualTextTranslator
import eu.kanade.translation.translator.LmStudioTranslator
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.RequestRetryBudget
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.translator.TranslationContextChunk
import eu.kanade.translation.translator.TranslationContextChunkPlanner
import eu.kanade.translation.translator.classifyProviderFailure
import eu.kanade.translation.translator.withRequestRetryBudget
import eu.kanade.translation.util.ShortHash
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import java.io.InputStream

/**
 * Single-page HTTP+render phase moved from `TranslationPipeline` (T909 Phase 12).
 * Runs OUTSIDE the native permit: HTTP translate (contextual or plain, with the
 * bounded PARTIAL retry loop), color recompute, Canvas render, and guarded commit.
 * `OnnxPhaseResult` crosses this phase's entry with the cleaned bitmap alive;
 * bitmap recycle/ownership points are unchanged from the pre-move pipeline body.
 */
internal class SinglePageHttpRenderPhase(
    private val translationPreferences: TranslationPreferences,
    private val provider: TranslationProvider,
    private val streamRegistry: eu.kanade.translation.scheduling.TranslationStreamRegistry,
    private val engines: EngineLane,
    private val cleanedPublication: CleanedPublication,
    // Engine-cache reads (translator signature/model/mode change on rebuild),
    // resolved through the pipeline's own helper at each call.
    private val expectedBatchFingerprints: (TextRecognizerLanguage, TextTranslatorLanguage) -> BatchExpectedFingerprints,
    // The downscaled-inpaint retry is an ONNX-phase collaborator (T909 Phase 14
    // region); until then the pipeline injects its own implementation.
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

    // Same-name engine reads the moved body uses; resolved through [engines]
    // so rebuilds are observed exactly as the in-class getters did.
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
     * Captures a local [activeTranslator] reference at entry so a concurrent
     * [closeEngines] from a language/config change does not race the in-flight
     * HTTP call. The old translator may be closed mid-flight, causing this page
     * to fail and retry with the new instance — an accepted trade-off without
     * the complexity of drain logic.
     */
    suspend fun translateSinglePageHttpRender(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        ctx: OnnxPhaseResult,
        stageListener: TranslationStageListener? = null,
    ): ChunkCompletionOutcome {
        val pageTranslation = ctx.pageTranslation
        // Reader-ad-hoc output is displayable; a later batch reuses it when
        // its fingerprints still match.
        pageTranslation.translationOrigin = PageWriteOrigin.READER_ADHOC.name
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

        val activeTranslator = textTranslator
        // The reader path is outside the batch coordinator, so it owns one
        // envelope budget explicitly. This budget is inherited by provider
        // transport retries and is shared by the contextual call plus any
        // bounded partial retries below.
        val retryBudget = RequestRetryBudget()
        var translationOutcome: ChunkCompletionOutcome = ChunkCompletionOutcome.Completed()
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

        // AI translators use translateContextual with the chapter glossary so on-demand
        // single-page translation reuses established terms/pronouns (same continuity the
        // batch path gets). Standard translators keep plain translatePage.
        val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
            ?: TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
        val singlePageProfile = if (activeTranslator is LmStudioTranslator) {
            TranslationContextChunkPlanner.Profile.LM_STUDIO
        } else {
            TranslationContextChunkPlanner.Profile.DEFAULT
        }

        suspend fun runTranslate(targetPage: PageTranslation) {
            val ct = activeTranslator as? ContextualTextTranslator
            if (ct != null) {
                val glossaryText = ChapterGlossaryBuilder.formatGlossary(store.glossarySnapshot())
                val estPrompt = TranslationContextChunkPlanner.PROMPT_OVERHEAD_TOKENS +
                    targetPage.blocks.sumOf { TranslationContextChunkPlanner.estimateTokens(it.text) }
                val baseChunk = TranslationContextChunk(
                    pages = linkedMapOf(pageKey to targetPage),
                    blockCount = targetPage.blocks.count { it.text.isNotBlank() },
                    rollingContext = "",
                    estimatedPromptTokens = estPrompt,
                    maxOutputTokens = requestedOutputTokens,
                    protocol = eu.kanade.translation.translator.ContextualRequestProtocol.LEGACY,
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
                val chunk = TranslationContextChunkPlanner.withRollingContext(
                    chunk = baseChunk,
                    rollingContext = recentPairs,
                    requestedOutputTokens = requestedOutputTokens,
                    profile = singlePageProfile,
                    glossary = glossaryText,
                )
                ct.translateContextual(chunk)
            } else {
                activeTranslator.translatePage(pageKey, targetPage)
            }
        }

        try {
            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty()) {
                val nonEmptyBlocks = pageTranslation.blocks.count { it.text.isNotBlank() }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT translate step START: pageHash=${ShortHash.hash(pageKey)} " +
                        "translator=${activeTranslator::class.simpleName} " +
                        "blocks=${pageTranslation.blocks.size} nonEmptyText=$nonEmptyBlocks"
                }
                BatchTranslationDiagnostics.stageDecision(
                    stage = BatchDiagnosticStage.TRANSLATION,
                    pageKey = pageKey,
                    decision = BatchDiagnosticDecision.EXECUTE,
                    reason = BatchDiagnosticReason.REFERENCE_READY,
                    itemCount = pageTranslation.blocks.size,
                )
                try {
                    val readingOrder = translationPreferences.translationReadingOrder().get()
                    pageTranslation.blocks = eu.kanade.translation.util.TranslationBlockSorter.sort(
                        pageTranslation.blocks,
                        fromLang,
                        readingOrder,
                    )
                    pageTranslation.translationStatus = StageStatus.RUNNING
                    stageListener?.onStageEntered(pageKey, TranslationStageEvent.TRANSLATING)
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
                            logcat(LogPriority.INFO) {
                                "TachiyomiAT single-page PARTIAL retry $singlePageRetry/$SINGLE_PAGE_PARTIAL_MAX_RETRIES: " +
                                    "pageKey=$pageKey missing=${missing.size}"
                            }
                            pageTranslation.translationStatus = StageStatus.RUNNING
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
                        val stats = ChapterGlossaryBuilder.Stats().also { s ->
                            store.translatedPairs().forEach { (src, tgt) -> s.add(src, tgt) }
                        }
                        pageTranslation.blocks.forEach { b -> stats.add(b.text, b.translation) }
                        store.updateGlossary(stats.build())
                    }
                    val translatedCount = pageTranslation.blocks.count { !it.translation.isNullOrBlank() }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT translate step DONE: pageHash=${ShortHash.hash(pageKey)} " +
                            "translated=$translatedCount/${pageTranslation.blocks.size} " +
                            "status=${pageTranslation.translationStatus}" +
                            (if (singlePageRetry > 0) " partialRetries=$singlePageRetry" else "")
                    }
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
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
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
                    try {
                        pageTranslation.renderStatus = StageStatus.RUNNING
                        stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                        if (cleanedBitmap != null) {
                            RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
                        }
                        pageTranslation.renderStatus = StageStatus.READY
                        pageTranslation.updatedAt = System.currentTimeMillis()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        markRenderFailure()
                        logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                    } finally {
                        if (cleanedBitmap != null) {
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
                        try {
                            if (published == null) {
                                markRenderFailure()
                                pageTranslation.errorMessage =
                                    "Cleaned image could not be published; translated text was not rendered."
                            } else {
                                pageTranslation.renderStatus = StageStatus.RUNNING
                                stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                                RenderColorEstimator.recomputeFor(retriedCleaned, pageTranslation.blocks)
                                pageTranslation.renderStatus = StageStatus.READY
                                pageTranslation.updatedAt = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
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
            val commit = store.patchPage(
                pageKey = pageKey,
                expected = commitPrecondition,
                description = "commit single-page translation and render",
            ) { pageTranslation }
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
            store.flush()
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
        }
        return translationOutcome
    }
}
