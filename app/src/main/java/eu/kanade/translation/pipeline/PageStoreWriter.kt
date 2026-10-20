package eu.kanade.translation.pipeline

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.pipeline.memory.TranslationMemoryBudget
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/**
 * Helpers for guarded page-store updates and verified persistence.
 */
internal class PageStoreWriter(
    private val activeStoreResolver: () -> (suspend (Translation) -> ChapterTranslationStore?)?,
    private val streamRegistry: TranslationStreamRegistry,
    private val handleCriticalTranslationOom: (stage: String, oom: OutOfMemoryError) -> Unit,
) {

    companion object {
        fun timeoutFailureMessage(nativeTimer: Boolean): String =
            if (nativeTimer) {
                "ONNX/native result timer expired; translation failed."
            } else {
                "Single-page operation timer expired; translation failed."
            }
    }

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

    suspend fun resolveActiveStore(
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
        // SAFE language fallbacks; re-throwing here would mask the original cause.
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
 * Converts a writer-identity snapshot into a fenced-write precondition. The
 * top-level extension is shared by [PageStoreWriter] and pipeline call sites.
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
