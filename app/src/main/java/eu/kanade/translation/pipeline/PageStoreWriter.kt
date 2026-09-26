package eu.kanade.translation.pipeline

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.TranslationPipeline.Companion.ONNX_PHASE_TIMEOUT_MS
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.ui.TranslationUiTruth
import eu.kanade.translation.util.TranslationMemoryBudget
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/**
 * Store-patch/failure-writer helpers moved from `TranslationPipeline`
 * ( Phase 6). Stateless over the pipeline's store resolver (injected
 * as a getter, it is re-wired by [eu.kanade.translation.orchestration.TranslationManager]),
 * the stream registry, and the pipeline's critical-OOM handler.
 */
internal class PageStoreWriter(
    private val activeStoreResolver: () -> ((Translation) -> ChapterTranslationStore?)?,
    private val streamRegistry: TranslationStreamRegistry,
    private val handleCriticalTranslationOom: (stage: String, oom: OutOfMemoryError) -> Unit,
) {

    fun peekReaderPageStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> InputStream)? = streamRegistry.peek(manga, chapter, source, pageKey)

    fun createFailedPagePlaceholder(
        fileName: String,
        errorMessage: String?,
        imgWidth: Float = 0f,
        imgHeight: Float = 0f,
        originalImgWidth: Float = 0f,
        originalImgHeight: Float = 0f,
        decodeSampleSize: Int = 1,
        retryCount: Int = 0,
        // Per-attempt exhaustion counter. Merge callers pass (existing.attemptCount + 1);
        // standalone first-failure callers pass the default 1.
        attemptCount: Int = 1,
    ): PageTranslation {
        return PageTranslation(
            sourceFileName = fileName,
            imgWidth = imgWidth,
            imgHeight = imgHeight,
            originalImgWidth = originalImgWidth,
            originalImgHeight = originalImgHeight,
            decodeSampleSize = decodeSampleSize,
            ocrStatus = StageStatus.FAILED,
            translationStatus = StageStatus.PENDING,
            inpaintStatus = StageStatus.FAILED,
            renderStatus = StageStatus.PENDING,
            updatedAt = System.currentTimeMillis(),
            retryCount = retryCount,
        ).apply {
            this.attemptCount = attemptCount
            this.translationError = errorMessage
            this.ocrError = errorMessage
            this.inpaintError = errorMessage
        }
    }

    /**
     * Writes a FAILED placeholder for [pageKey] into the chapter's shared store
     * when the single-page translation times out. This is the single-page path's
     * counterpart to the batch path's timeout handling: without it the page is
     * stranded as ocrStatus=RUNNING (the first thing [translateSinglePageOnnx]
     * writes) for the rest of the session, which keeps the reader's
     * anyRunning flag true — pinning the TRANSLATING state, disabling the
     * translate icon, and making auto-translate skip the page forever.
     *
     * Resolves the store through [activeStoreResolver] (the shared instance the
     * reader is observing); if none is registered for this chapter the page's
     * RUNNING status will be cleared on the next chapter open instead. Never
     * clobbers an already-completed page — only overwrites entries that are still
     * in a non-terminal (RUNNING/PENDING) state.
     */
    fun resolveActiveStore(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): ChapterTranslationStore? {
        val syntheticTranslation = Translation(
            source,
            manga,
            chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        return activeStoreResolver()?.invoke(syntheticTranslation)
    }

    suspend fun markPageTimedOut(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        @Suppress("UNUSED_PARAMETER") timeoutMs: Long = ONNX_PHASE_TIMEOUT_MS,
        nativeTimer: Boolean = true,
    ) {
        // Use SAFE language fallbacks, not the throwing fromPref: this runs in an
        // error/timeout path, so re-throwing here would mask the original failure.
        // The store is keyed on manga/chapter (not language).
        val syntheticTranslation = Translation(
            source,
            manga,
            chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val store = activeStoreResolver()?.invoke(syntheticTranslation) ?: return
        store.invalidateGeneration("timeout chapter=${chapter.name} pageKey=$pageKey")
        val snapshot = store.snapshot(pageKey)
        store.patchPage(pageKey, snapshot.toPrecondition(), "mark page timed out") { existing ->
            val timeoutMessage = TranslationUiTruth.timeoutCopy(nativeTimer)
            when {
                // Don't overwrite a page with no intermediate progress.
                existing == null || existing.cleanedImageName == null -> {
                    createFailedPagePlaceholder(
                        pageKey,
                        //  Phase 5  (spec §3.2): the placeholder names the
                        // ACTUAL result timer that fired — the native lane and the
                        // HTTP+render lane run DIFFERENT timers — and omits
                        // unmeasured durations entirely (supersedes the old
                        // "after 0s" second-division rendering).
                        timeoutMessage,
                        imgWidth = existing?.imgWidth ?: 0f,
                        imgHeight = existing?.imgHeight ?: 0f,
                        originalImgWidth = existing?.originalImgWidth ?: 0f,
                        originalImgHeight = existing?.originalImgHeight ?: 0f,
                        decodeSampleSize = existing?.decodeSampleSize ?: 1,
                        retryCount = (existing?.retryCount ?: 0) + 1,
                        attemptCount = (existing?.attemptCount ?: 0) + 1,
                    )
                }
                //  Phase 5  the page holds INTERMEDIATE durable
                // artifacts (a cleaned image from the FAST-inpaint lane) but no
                // rendered result — it is mid-pipeline. Keeping it silently
                // RUNNING strands the reader in TRANSLATING forever; preserve
                // the artifacts and mark every still-open stage failed with
                // the timer-named truth.
                else -> existing.apply {
                    if (ocrStatus != StageStatus.READY) {
                        ocrStatus = StageStatus.FAILED
                        ocrError = timeoutMessage
                    }
                    if (translationStatus != StageStatus.READY &&
                        translationStatus != StageStatus.PARTIAL
                    ) {
                        translationStatus = StageStatus.FAILED
                        translationError = timeoutMessage
                    }
                    if (renderStatus != StageStatus.READY) {
                        renderStatus = StageStatus.FAILED
                        renderError = timeoutMessage
                    }
                    retryCount += 1
                    attemptCount += 1
                }
            }
        }
    }

    suspend fun updatePageFromCurrentSnapshot(
        store: ChapterTranslationStore,
        pageKey: String,
        description: String,
        expectedGeneration: Long? = null,
        update: (PageTranslation?) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult =
        store.snapshot(pageKey).let { snapshot ->
            store.updatePageGuarded(
                pageKey,
                snapshot.toPrecondition().copy(generation = expectedGeneration ?: snapshot.generation),
                description,
                update,
            )
        }

    suspend fun markPageFailed(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        error: Throwable,
    ) {
        // SAFE language fallbacks (see markPageTimedOut); re-throwing here would mask the cause.
        val syntheticTranslation = Translation(
            source,
            manga,
            chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val store = activeStoreResolver()?.invoke(syntheticTranslation) ?: return
        updatePageFromCurrentSnapshot(store, pageKey, "single-page failure") { existing ->
            // Don't overwrite a page that already produced a result (rendered/
            // cleaned) — a late error after a successful persist would erase it.
            if (existing != null &&
                existing.cleanedImageName != null
            ) {
                existing
            } else {
                val errorMsg = when (error) {
                    is OutOfMemoryError -> "Out of memory: ${error.message ?: "low memory"}"
                    else -> error.message ?: error.javaClass.simpleName
                }
                createFailedPagePlaceholder(
                    pageKey,
                    errorMsg,
                    imgWidth = existing?.imgWidth ?: 0f,
                    imgHeight = existing?.imgHeight ?: 0f,
                    originalImgWidth = existing?.originalImgWidth ?: 0f,
                    originalImgHeight = existing?.originalImgHeight ?: 0f,
                    decodeSampleSize = existing?.decodeSampleSize ?: 1,
                    retryCount = (existing?.retryCount ?: 0) + 1,
                    attemptCount = (existing?.attemptCount ?: 0) + 1,
                )
            }
        }
    }

    suspend fun persistPageWithOomRecovery(
        store: ChapterTranslationStore,
        fileName: String,
        pageTranslation: PageTranslation,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    ): ChapterTranslationStore.PatchResult {
        suspend fun persist(
            description: String,
            update: (PageTranslation?) -> PageTranslation,
        ): ChapterTranslationStore.PatchResult {
            if (expectedPrecondition != null) {
                return store.updatePageGuarded(fileName, expectedPrecondition, description, update)
            } else {
                return updatePageFromCurrentSnapshot(store, fileName, description, update = update)
            }
        }
        return try {
            if (TranslationMemoryBudget.isCriticalHeap()) {
                handleCriticalTranslationOom("persisting $fileName", OutOfMemoryError("critical heap before persist"))
            }
            persist("persist page metadata") { pageTranslation }
        } catch (oom: OutOfMemoryError) {
            handleCriticalTranslationOom("persisting $fileName", oom)
            pageTranslation.cleanedBitmap = null
            try {
                persist("retry persist page metadata") { pageTranslation }
            } catch (retryOom: OutOfMemoryError) {
                handleCriticalTranslationOom("persisting lightweight failure $fileName", retryOom)
                persist("persist page OOM failure") {
                    createFailedPagePlaceholder(
                        fileName,
                        "OOM while saving translation metadata: ${retryOom.message}",
                        imgWidth = pageTranslation.imgWidth,
                        imgHeight = pageTranslation.imgHeight,
                        originalImgWidth = pageTranslation.originalImgWidth,
                        originalImgHeight = pageTranslation.originalImgHeight,
                        decodeSampleSize = pageTranslation.decodeSampleSize,
                    )
                }
            }
        }
    }
}

/**
 * Writer-identity snapshot → fenced-write precondition. Top-level ( Phase 6)
 * so the [PageStoreWriter] bodies and the pipeline's own call sites resolve the
 * same declaration.
 */
internal fun ChapterTranslationStore.PageSnapshot.toPrecondition() =
    ChapterTranslationStore.PatchPrecondition(
        generation = generation,
        pageVersion = pageVersion,
        blockFingerprints = blockFingerprints,
        leaseToken = leaseToken,
        candidateGenerationId = candidateGenerationId,
        dependencyFingerprint = dependencyFingerprint,
        artifactPageVersion = artifactPageVersion,
    )
