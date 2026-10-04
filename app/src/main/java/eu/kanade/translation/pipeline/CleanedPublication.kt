package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.CleanedImageIdentity
import eu.kanade.translation.persistence.artifact.CleanedImageIdentitySidecar
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.CleanedImagePublisher
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Publishes cleaned images and retires files superseded by committed display
 * bundles. The current inpainting mode is read through a getter so engine
 * rebuilds are reflected.
 */
internal class CleanedPublication(
    private val provider: TranslationFileProvider,
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
                val imageFile = provider.findPageCleanedImage(
                    manga.title,
                    source,
                    chapter.name,
                    chapter.scanlator,
                    name,
                )
                val deleted = imageFile?.let { !it.exists() || it.delete() } ?: true
                if (deleted) {
                    provider.findPageCleanedImage(
                        manga.title,
                        source,
                        chapter.name,
                        chapter.scanlator,
                        CleanedImageIdentity.sidecarName(name),
                    )?.delete()
                }
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
        var writtenImage: CleanedImageWriteResult? = null
        val publisher = CleanedImagePublisher(object : CleanedImagePublisher.Files {
            override fun writeVerifiedVersionedFile(): String {
                check(directory != null) { "translation output folder is unavailable" }
                val written = writeVerifiedCleanedImage(directory, pageKey, cleanedBitmap)
                writtenImage = written
                return written.name
            }

            override fun delete(name: String): Boolean {
                val imageFile = directory?.findFile(name)
                val imageDeleted = imageFile == null || !imageFile.exists() || imageFile.delete()
                if (imageDeleted) directory?.findFile(CleanedImageIdentity.sidecarName(name))?.delete()
                return imageDeleted
            }
        })
        when (
            val result = publisher.publish(
                chapterName,
                pageKey,
                previousName,
                commit = { newName ->
                    val writtenHash = writtenImage?.contentSha256
                    store.patchPage(
                        pageKey,
                        precondition,
                        "publish cleaned image",
                    ) { current ->
                        (current ?: pageTranslation).apply {
                            cleanedImageName = newName
                            cleanedImageContentHash = writtenHash
                            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                            inpaintingModeUsed = currentInpaintingMode().name
                            inpaintFingerprint = pageTranslation.inpaintFingerprint
                            inpaintStatus = StageStatus.READY
                            originalImageFallback = false
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
                pageTranslation.cleanedImageContentHash = writtenImage?.contentSha256
                pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                pageTranslation.inpaintingModeUsed = currentInpaintingMode().name
                pageTranslation.inpaintStatus = StageStatus.READY
                pageTranslation.originalImageFallback = false
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
        if (result.store.isLazyPersistenceEnabled()) {
            return persistLazyCleanedBitmap(
                manga = manga,
                chapter = chapter,
                source = source,
                pageKey = pageKey,
                result = result,
                cleanedBitmap = cleaned,
            )
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

    /**
     * Memory-first cleaned-image publication for active reader stores.  The
     * page metadata is visible immediately (the current pipeline can render
     * from [cleanedBitmap]); JPEG/SAF work runs on the store persistence worker
     * and is joined only by the existing final flush/batch durability barrier.
     */
    private suspend fun persistLazyCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        result: OnnxPhaseResult,
        cleanedBitmap: Bitmap,
    ): OnnxPhaseResult? {
        val store = result.store
        val pageTranslation = result.pageTranslation
        val previousName = pageTranslation.cleanedImageName
        val precondition = result.commitPrecondition ?: store.snapshot(pageKey).toPrecondition()
        val finalName = newCleanedImageName(pageKey)
        val livePatch = store.patchPage(
            pageKey,
            precondition,
            "publish cleaned image live",
        ) { current ->
            (current ?: pageTranslation).apply {
                cleanedImageName = finalName
                cleanedImageContentHash = null
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                inpaintingModeUsed = currentInpaintingMode().name
                inpaintFingerprint = pageTranslation.inpaintFingerprint
                inpaintStatus = StageStatus.READY
                originalImageFallback = false
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
        val accepted = livePatch as? ChapterTranslationStore.PatchResult.Accepted
            ?: return null
        pageTranslation.cleanedImageName = finalName
        pageTranslation.cleanedImageContentHash = null
        pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        pageTranslation.inpaintingModeUsed = currentInpaintingMode().name
        pageTranslation.inpaintStatus = StageStatus.READY
        pageTranslation.originalImageFallback = false
        pageTranslation.errorMessage = null
        val generation = accepted.snapshot.generation
        val completion: Deferred<Boolean> = store.enqueueLazyPersistence(generation) {
            val companionDir = provider.getCompanionImageDir(
                manga.title,
                source,
                chapter.name,
                chapter.scanlator,
            )
            var writtenImage: CleanedImageWriteResult? = null
            val publisher = CleanedImagePublisher(object : CleanedImagePublisher.Files {
                override fun writeVerifiedVersionedFile(): String {
                    check(companionDir != null) { "translation output folder is unavailable" }
                    val written = writeVerifiedCleanedImage(
                        directory = companionDir,
                        pageKey = pageKey,
                        bitmap = cleanedBitmap,
                        name = finalName,
                    )
                    writtenImage = written
                    return finalName
                }

                override fun delete(name: String): Boolean {
                    val imageFile = companionDir?.findFile(name)
                    val imageDeleted = imageFile == null || !imageFile.exists() || imageFile.delete()
                    if (imageDeleted) companionDir?.findFile(CleanedImageIdentity.sidecarName(name))?.delete()
                    return imageDeleted
                }
            })
            when (
                val publication = publisher.publish(
                    chapter = chapter.name,
                    pageKey = pageKey,
                    previousName = previousName,
                    commit = {
                        if (store.isLazyGenerationCurrent(generation)) {
                            val hash = writtenImage?.contentSha256
                            if (hash != null) {
                                pageTranslation.cleanedImageContentHash = hash
                                store.patchPage(
                                    pageKey = pageKey,
                                    expected = store.snapshot(pageKey).toPrecondition(),
                                    description = "record lazy cleaned image content hash",
                                ) { current ->
                                    (current ?: pageTranslation).apply { cleanedImageContentHash = hash }
                                }
                            }
                            ChapterTranslationStore.PatchResult.Accepted(accepted.snapshot)
                        } else {
                            ChapterTranslationStore.PatchResult.Rejected("store generation changed")
                        }
                    },
                    mayDeletePrevious = { name -> store.mayDeleteCleanedImage(pageKey, name) },
                    retirePrevious = { name, delete ->
                        chapter.id?.let { stableChapterId ->
                            streamRegistry.retireCleanedImage(
                                sourceId = source.id,
                                mangaId = manga.id,
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
                is CleanedImagePublisher.Result.Published -> true
                else -> {
                    // A write failure must not leave the live page pointing at
                    // a name that never reached storage. This guarded update
                    // is itself memory-first and will be coalesced into the
                    // next store drain.
                    store.patchPage(
                        pageKey = pageKey,
                        expected = store.snapshot(pageKey).toPrecondition(),
                        description = "record lazy cleaned publication failure",
                    ) { current -> markOriginalImageFallback(current ?: pageTranslation) }
                    false
                }
            }
        }
        return result.copy(
            pageTranslation = pageTranslation,
            commitPrecondition = accepted.snapshot.toPrecondition(),
            pendingCleanedPublication = completion,
        )
    }

    internal data class CleanedImageWriteResult(
        val name: String,
        val contentSha256: String,
    )

    private fun writeVerifiedCleanedImage(
        directory: UniFile,
        pageKey: String,
        bitmap: Bitmap,
        name: String = newCleanedImageName(pageKey),
    ): CleanedImageWriteResult {
        val identityName = CleanedImageIdentity.sidecarName(name)
        val existingImage = directory.findFile(name)?.takeIf(UniFile::exists)
        val existingIdentity = directory.findFile(identityName)?.takeIf(UniFile::exists)
        if (existingImage != null || existingIdentity != null) {
            val sidecarBytes = runCatching {
                existingIdentity?.openInputStream()?.use { it.readBytes() }
            }.getOrNull()
            val sidecar = runCatching {
                sidecarBytes?.let { ArtifactDocumentJson.decodeFromString<CleanedImageIdentitySidecar>(it.decodeToString()) }
            }.getOrNull()
            val matches = sidecar != null &&
                CleanedImageIdentity.verifyExisting(
                    sidecarBytes = sidecarBytes,
                    imageName = name,
                    expectedContentSha256 = sidecar.contentSha256,
                ) { existingImage?.openInputStream() }
            check(matches) { "cleaned image name is already bound to different or unverified bytes" }
            return CleanedImageWriteResult(name, sidecar!!.contentSha256)
        }
        val imageFile = directory.createFile(name)
        check(imageFile != null) { "could not create final cleaned image" }
        var identityFile: UniFile? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            imageFile.openOutputStream().use { fileOut ->
                DigestOutputStream(fileOut, digest).use { digestOut ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, digestOut)) {
                        "JPEG encoding returned false"
                    }
                    digestOut.flush()
                }
            }
            check(imageFile.exists() && imageFile.length() > 0L) { "published cleaned image is unavailable" }
            val contentSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val identity = CleanedImageIdentitySidecar(
                pageKey = pageKey,
                imageName = name,
                contentSha256 = contentSha256,
            )
            val createdIdentityFile = directory.createFile(identityName)
            check(createdIdentityFile != null) { "could not create cleaned image identity" }
            identityFile = createdIdentityFile
            createdIdentityFile.openOutputStream().use { output ->
                output.write(CleanedImageIdentity.encode(identity))
                output.flush()
            }
            check(createdIdentityFile.exists() && createdIdentityFile.length() > 0L) {
                "cleaned image identity is unavailable"
            }
            return CleanedImageWriteResult(name, contentSha256)
        } catch (failure: Throwable) {
            identityFile?.delete()
            imageFile.delete()
            throw failure
        }
    }

    private fun newCleanedImageName(pageKey: String): String {
        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val version = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().replace("-", "")}"
        return "$safeName.cleaned.$version.jpg"
    }
}

/**
 * A cleaned-image write is a display-artifact failure, not a failed OCR,
 * translation, or inpaint attempt. Keep the translated blocks usable and let
 * the reader draw them over the original image until a later retry can publish
 * a cleaned base.
 */
internal fun markOriginalImageFallback(page: PageTranslation): PageTranslation = page.apply {
    cleanedImageName = null
    cleanedImageContentHash = null
    originalImageFallback = true
    inpaintStatus = StageStatus.READY
    renderStatus = StageStatus.READY
    errorMessage = null
}

/**
 * Copies page data for resume without retaining transient bitmaps or text
 * detections.
 */
internal fun PageTranslation.copyForResume(): PageTranslation {
    return copy(blocks = blocks.map { it.copy() }.toMutableList()).also {
        it.cleanedBitmap = null
        it.allTextDetections = emptyList()
    }
}
