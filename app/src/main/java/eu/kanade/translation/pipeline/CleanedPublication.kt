package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.storage.CleanedImagePublisher
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Cleaned-image publication moved from `TranslationPipeline` (T909 Phase 7).
 * Wraps the already-extracted [CleanedImagePublisher]; the pipeline's
 * `currentInpaintingMode` is injected as a getter (it is re-wired by engine
 * rebuilds).
 */
internal class CleanedPublication(
    private val provider: TranslationProvider,
    private val streamRegistry: TranslationStreamRegistry,
    private val currentInpaintingMode: () -> InpaintingMode,
) {

    /**
     * Deletes cleaned-image files retained by the store after newer committed
     * display bundles promoted over them. Draining the complete set prevents a
     * rapid sequence of promotions from orphaning an earlier superseded file.
     */
    suspend fun deleteRetiredCleanedFile(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) {
        val chapterId = chapter.id ?: return
        val retired = store.drainRetiredCleanedImages(pageKey)
        if (retired.isEmpty()) return
        retired.forEach { name ->
            streamRegistry.retireCleanedImage(
                sourceId = source.id,
                mangaId = manga.id,
                chapterId = chapterId,
                pageKey = pageKey,
                imageName = name,
            ) {
                if (!store.mayDeleteCleanedImage(pageKey, name)) return@retireCleanedImage
                val deleted = provider.findPageCleanedImage(
                    manga.title,
                    source,
                    chapter.name,
                    chapter.scanlator,
                    name,
                )?.delete() == true
                logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                    "TachiyomiAT retired cleaned image drain: pageKey=$pageKey file=$name deleted=$deleted"
                }
            }
        }
    }

    suspend fun loadPersistedCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        cleanedImageName: String,
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = provider.findPageCleanedImage(
                manga.title,
                source,
                chapter.name,
                chapter.scanlator,
                cleanedImageName,
            )?.takeIf { it.exists() && it.length() > 0L }
                ?: return@withContext null
            file.openInputStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to load cleaned image for resume: cleaned=$cleanedImageName"
            }
            null
        }
    }

    suspend fun persistCleanedBitmap(
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
    ): ChapterTranslationStore.PageSnapshot? = withContext(Dispatchers.IO) {
        val directory = companionDir
        val previousName = pageTranslation.cleanedImageName
        val precondition = expectedPrecondition ?: store.snapshot(pageKey).let { snapshot ->
            ChapterTranslationStore.PatchPrecondition(
                generation = snapshot.generation,
                pageVersion = snapshot.pageVersion,
                blockFingerprints = snapshot.blockFingerprints,
                leaseToken = snapshot.leaseToken,
                candidateGenerationId = snapshot.candidateGenerationId,
                dependencyFingerprint = snapshot.dependencyFingerprint,
                artifactPageVersion = snapshot.artifactPageVersion,
            )
        }
        val publisher = CleanedImagePublisher(object : CleanedImagePublisher.Files {
            override fun writeVerifiedVersionedFile(): String {
                check(directory != null) { "translation output folder is unavailable" }
                val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val version = System.currentTimeMillis().toString(36) + "-" + System.nanoTime().toString(36).takeLast(6)
                val finalName = "$safeName.cleaned.$version.jpg"
                val finalFile = directory.findFile(finalName) ?: directory.createFile(finalName)
                check(finalFile != null) { "could not create final cleaned image" }
                finalFile.openOutputStream().use { output ->
                    check(cleanedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "JPEG encoding returned false" }
                }
                check(finalFile.exists() && finalFile.length() > 0L) { "published cleaned image is unavailable" }
                return finalName
            }

            override fun delete(name: String): Boolean = directory?.findFile(name)?.delete() ?: true
        })
        when (
            val result = publisher.publish(
                chapterName,
                pageKey,
                previousName,
                commit = { newName ->
                    store.patchPage(pageKey, precondition, "publish cleaned image") { current ->
                        (current ?: pageTranslation).apply {
                            cleanedImageName = newName
                            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                            inpaintingModeUsed = currentInpaintingMode().name
                            inpaintFingerprint = pageTranslation.inpaintFingerprint
                            inpaintStatus = StageStatus.READY
                            if (pageTranslation.ocrStatus == StageStatus.READY) {
                                ocrStatus = StageStatus.READY
                            }
                            if (blocks.isEmpty() && pageTranslation.blocks.isNotEmpty()) {
                                blocks = pageTranslation.blocks.map { it.copy() }.toMutableList()
                            }
                            if (inpaintMaskBoxes.isEmpty() && pageTranslation.inpaintMaskBoxes.isNotEmpty()) {
                                inpaintMaskBoxes = pageTranslation.inpaintMaskBoxes
                            }
                            if (imgWidth == 0f && pageTranslation.imgWidth != 0f) {
                                imgWidth = pageTranslation.imgWidth
                                imgHeight = pageTranslation.imgHeight
                            }
                            errorMessage = null
                        }
                    }
                },
                mayDeletePrevious = { name -> store.mayDeleteCleanedImage(pageKey, name) },
                retirePrevious = { name, delete ->
                    chapterId?.let { stableChapterId ->
                        streamRegistry.retireCleanedImage(
                            sourceId = sourceId,
                            mangaId = mangaId,
                            chapterId = stableChapterId,
                            pageKey = pageKey,
                            imageName = name,
                        ) {
                            if (store.mayDeleteCleanedImage(pageKey, name)) delete()
                        }
                    }
                },
            )
        ) {
            is CleanedImagePublisher.Result.Published -> {
                pageTranslation.cleanedImageName = result.name
                pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                pageTranslation.inpaintingModeUsed = currentInpaintingMode().name
                pageTranslation.inpaintStatus = StageStatus.READY
                pageTranslation.errorMessage = null
                result.snapshot
            }
            is CleanedImagePublisher.Result.Rejected -> null
            is CleanedImagePublisher.Result.WriteFailed -> {
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.recordAttemptFailure()
                pageTranslation.errorMessage = "Could not save cleaned image — translation output folder is unavailable. Grant storage permission to the app and retry."
                null
            }
        }
    }

    suspend fun persistOnnxCleanedImage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        result: OnnxPhaseResult,
    ): OnnxPhaseResult? {
        val page = result.pageTranslation
        val cleaned = page.cleanedBitmap
        if (cleaned == null) {
            val snapshot = result.store.snapshot(pageKey)
            return result.copy(commitPrecondition = snapshot.toPrecondition())
        }
        val companionDir = provider.getCompanionImageDir(
            manga.title,
            source,
            chapter.name,
            chapter.scanlator,
        )
        val published = persistCleanedBitmap(
            page,
            cleaned,
            companionDir,
            pageKey,
            chapter.name,
            result.store,
            source.id,
            manga.id,
            chapter.id,
            expectedPrecondition = result.commitPrecondition,
        )
        if (published == null) {
            // Do not let HTTP/render consume an in-memory result when the reader
            // cannot reopen it after publication or a newer page won the race.
            try {
                cleaned.recycle()
            } catch (_: Exception) {}
            page.cleanedBitmap = null
            return null
        }
        return result.copy(commitPrecondition = published.toPrecondition())
    }
}

/**
 * Resume copy hygiene moved with the cleaned-publication region (T909 Phase 7;
 * original position: between the pipeline's HTTP/render body and
 * [CleanedPublication.loadPersistedCleanedBitmap]). Top-level so the pipeline's
 * remaining resume-path call sites resolve the same declaration.
 */
internal fun PageTranslation.copyForResume(): PageTranslation {
    return copy(blocks = blocks.map { it.copy() }.toMutableList()).also {
        it.cleanedBitmap = null
        it.allTextDetections = emptyList()
    }
}
