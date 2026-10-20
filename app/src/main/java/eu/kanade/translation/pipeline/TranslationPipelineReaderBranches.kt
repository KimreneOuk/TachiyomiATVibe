package eu.kanade.translation.pipeline

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.LeaseAcquisition
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import eu.kanade.translation.pipeline.adaptive.DevicePermitPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

internal sealed interface InpaintLaneResult {
    object Produced : InpaintLaneResult
    object SoftNull : InpaintLaneResult
}

internal sealed interface NativeBranchOutcome {
    object Success : NativeBranchOutcome
    object SoftNull : NativeBranchOutcome
    object TimedOut : NativeBranchOutcome
    data class Failed(val throwable: Throwable) : NativeBranchOutcome
}

internal suspend fun TranslationPipeline.releaseSinglePageResources(
    manga: Manga,
    chapter: Chapter,
    source: HttpSource,
    pageKey: String,
    deferredPublications: DeferredPagePublications,
    sourceCustody: SourceBitmapCustody,
    owner: CleanedOwner,
) {
    try {
        drainDeferredPublications(deferredPublications)
    } finally {
        try {
            chapter.id?.let { chapterId ->
                streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
            }
        } finally {
            try {
                sourceCustody.close()
            } finally {
                try {
                    owner.release()
                } finally {
                    try {
                        engines.endTranslatorUse()
                    } finally {
                        try {
                            recognitionEngine.reclaimPooledMemory()
                        } catch (e: Throwable) {
                            logcat(LogPriority.WARN, e) {
                                "Failed to reclaim pooled recognition memory for $pageKey"
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Segment-2 child of the shared single-page T3: native inpaint lane + enrollment. */
internal suspend fun TranslationPipeline.runNativeInpaintBranch(
    traceRun: TranslationRunTrace?,
    manga: Manga,
    chapter: Chapter,
    source: HttpSource,
    pageKey: String,
    store: ChapterTranslationStore,
    nativeDraft: PageTranslation,
    sourceCustody: SourceBitmapCustody,
    owner: CleanedOwner,
    attempt: AttemptAuthority,
    deferredPublications: DeferredPagePublications,
    stageListener: TranslationStageListener?,
): NativeBranchOutcome {
    try {
        val laneResult: InpaintLaneResult? = withNativeLane(
            timeoutMs = nativeTimeoutMs,
            chapterId = chapter.id,
            chapterName = chapter.name,
            pageKey = pageKey,
            onTimeout = {
                deferredPublications.orphaned = true
                logcat(LogPriority.WARN) {
                    "TachiyomiAT Segment 2 native inpaint timed out for $pageKey"
                }
            },
            priority = DevicePermitPriority.FOREGROUND,
        ) {
            val inpaintSpan = traceRun?.beginStage(
                TranslationTraceStage.INPAINT,
                lane = TranslationTraceLane.NATIVE,
                provider = TranslationTraceProvider.CPU,
            )
            val s = sourceCustody.take()
            if (s == null) {
                inpaintSpan?.end(TranslationTraceOutcome.CANCELLED)
                return@withNativeLane InpaintLaneResult.SoftNull
            }
            try {
                stageListener?.onStageEntered(pageKey, TranslationStageEvent.CLEANING)
                val cleaned = try {
                    recognitionEngine.inpaint(s, nativeDraft)
                } finally {
                    inpaintSpan?.end()
                }
                if (cleaned != null) {
                    owner.produce(cleaned, CleanedProvenance.INPAINT)
                    InpaintLaneResult.Produced
                } else {
                    InpaintLaneResult.SoftNull
                }
            } finally {
                if (!owner.owns(s)) {
                    try {
                        s.recycle()
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) {
                            "TachiyomiAT segment 2 source bitmap recycle failed: pageKey=$pageKey"
                        }
                    }
                }
            }
        }
        if (laneResult != null && owner.isAvailable) {
            cleanedPublication.enrollCleanedPersistence(
                manga = manga,
                chapter = chapter,
                source = source,
                pageKey = pageKey,
                store = store,
                pageTranslation = nativeDraft,
                owner = owner,
                attempt = attempt,
                deferredPublications = deferredPublications,
            )
        }
        return when (laneResult) {
            is InpaintLaneResult.Produced -> NativeBranchOutcome.Success
            is InpaintLaneResult.SoftNull -> NativeBranchOutcome.SoftNull
            null -> NativeBranchOutcome.TimedOut
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        logcat(LogPriority.ERROR, t) { "TachiyomiAT native inpaint branch failed: pageKey=$pageKey" }
        return NativeBranchOutcome.Failed(t)
    }
}

/** Post-join native-outcome resolution: terminal markers, failure writes, render. */
internal suspend fun TranslationPipeline.resolveNativeCompletion(
    nativeResult: NativeBranchOutcome,
    httpOutcome: TranslationCompletionOutcome,
    nativeDraft: PageTranslation,
    manga: Manga,
    chapter: Chapter,
    source: HttpSource,
    pageKey: String,
    httpCtx: OnnxPhaseResult,
    owner: CleanedOwner,
    attempt: AttemptAuthority,
    stageListener: TranslationStageListener?,
    origin: PageWriteOrigin,
): TranslationCompletionOutcome {
    if (nativeResult is NativeBranchOutcome.TimedOut) {
        val translationDurable = httpOutcome is TranslationCompletionOutcome.Completed
        attempt.markTimeout { current ->
            val msg = PageStoreWriter.timeoutFailureMessage(nativeTimer = true)
            current.apply {
                if (inpaintStatus != StageStatus.READY) {
                    inpaintStatus = StageStatus.FAILED
                    inpaintError = msg
                }
                if (!translationDurable) {
                    if (translationStatus != StageStatus.READY && translationStatus != StageStatus.PARTIAL) {
                        translationStatus = StageStatus.FAILED
                        translationError = msg
                    }
                }
                errorMessage = msg
                retryCount += 1
                attemptCount += 1
                updatedAt = System.currentTimeMillis()
            }
        }
        return TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = "native phase timed out")
    }

    if (nativeResult is NativeBranchOutcome.SoftNull && nativeDraft.blocks.isEmpty()) {
        val message = "Masked textless inpainting produced no cleaned image"
        attempt.write("masked textless inpaint failure") { current ->
            current.apply {
                inpaintStatus = StageStatus.FAILED
                inpaintError = message
                errorMessage = message
                updatedAt = System.currentTimeMillis()
            }
        }
        return TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = message)
    }

    if (nativeResult is NativeBranchOutcome.Failed) {
        val nativeError = nativeResult.throwable.message ?: "native inpaint failed"
        attempt.write("native inpaint failure") { current ->
            current.apply {
                inpaintStatus = StageStatus.FAILED
                inpaintError = nativeError
                errorMessage = nativeError
                updatedAt = System.currentTimeMillis()
            }
        }
        return TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = nativeError)
    }

    if (httpOutcome !is TranslationCompletionOutcome.Completed) {
        return httpOutcome
    }

    return singlePageHttpRenderPhase.renderSinglePageTranslated(
        manga = manga,
        chapter = chapter,
        source = source,
        pageKey = pageKey,
        ctx = httpCtx,
        owner = owner,
        attempt = attempt,
        origin = origin,
    )
}

/** Lease admission for reader-originated single-page work. */
internal suspend fun TranslationPipeline.acquireReaderPageLease(
    store: ChapterTranslationStore,
    pageKey: String,
    stage: PageStage,
    origin: PageWriteOrigin,
    site: TranslationTraceSite,
): LeaseAcquisition = TranslationTrace.withLeaseWait(
    site = site,
    leaseKind = when (stage) {
        PageStage.Ocr -> TranslationTraceLeaseKind.OCR
        PageStage.Translation -> TranslationTraceLeaseKind.TRANSLATION
        PageStage.Inpaint -> TranslationTraceLeaseKind.INPAINT
        PageStage.Render -> TranslationTraceLeaseKind.RENDER
    },
) {
    store.tryAcquirePageStageLease(pageKey, stage, origin)
}

internal suspend fun TranslationPipeline.releaseReaderPageLease(
    store: ChapterTranslationStore?,
    pageKey: String,
    origin: PageWriteOrigin,
) {
    if (store == null) return
    store.releasePageStageLease(pageKey, origin)
}
