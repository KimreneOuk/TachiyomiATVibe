package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.Downloader.PublishedPageFiles
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.translation.diagnostics.BatchDownloadCause
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.diagnostics.BatchDownloadResult
import eu.kanade.translation.diagnostics.BatchDownloadStage
import eu.kanade.translation.diagnostics.BatchDownloadTraceBoundary
import eu.kanade.translation.diagnostics.BatchDownloadTraceContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.job
import logcat.LogPriority
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import java.io.File
import java.io.IOException
import java.util.Locale

/** Maps download stage and error to high-level diagnostic cause. */
internal fun causeForStage(stage: BatchDownloadStage, error: Throwable? = null): BatchDownloadCause = when (stage) {
    BatchDownloadStage.TEMP_LOOKUP,
    BatchDownloadStage.TEMP_DELETE,
    BatchDownloadStage.DIRECTORY_LIST,
    -> BatchDownloadCause.STORAGE
    BatchDownloadStage.RESOLVE_IMAGE_URL -> BatchDownloadCause.SOURCE
    BatchDownloadStage.HTTP_FETCH -> if (error?.javaClass?.simpleName?.contains("Http", ignoreCase = true) == true) {
        BatchDownloadCause.HTTP
    } else {
        BatchDownloadCause.NETWORK
    }
    BatchDownloadStage.CREATE_TEMP,
    BatchDownloadStage.WRITE_TEMP,
    BatchDownloadStage.RENAME_TEMP,
    BatchDownloadStage.CACHE_COPY,
    BatchDownloadStage.ARCHIVE,
    BatchDownloadStage.RENAME,
    BatchDownloadStage.CACHE,
    -> BatchDownloadCause.STORAGE
    BatchDownloadStage.DETECT_TYPE,
    BatchDownloadStage.SPLIT,
    -> BatchDownloadCause.INVALID_PAGE
    BatchDownloadStage.METADATA,
    BatchDownloadStage.REKEY,
    BatchDownloadStage.ADMISSION,
    -> BatchDownloadCause.UNKNOWN
}

/** Publishes temporary page file to its final destination name; throws if rename fails. */
internal fun publishDownloadedFile(file: UniFile, finalName: String): UniFile {
    val parent = runCatching { file.parentFile }.getOrNull()
    runCatching {
        parent?.findFile(finalName)?.let { existing ->
            if (existing.uri != file.uri) {
                existing.delete()
            }
        }
    }
    if (!file.renameTo(finalName)) {
        throw IOException("Unable to publish downloaded page")
    }
    return runCatching { parent?.findFile(finalName) }.getOrNull() ?: file
}

/** Manages single-page retrieval, network download, cache fallback, and image splitting. */
internal class PageDownloadPipeline(
    private val context: Context,
    private val chapterCache: ChapterCache,
    private val downloadPreferences: DownloadPreferences,
    private val notifier: DownloadNotifier,
) {

    /** Resolves page file from filesystem, chapter cache, or network download. */
    suspend fun getOrDownloadImage(
        page: Page,
        download: Download,
        tmpDir: UniFile,
        traceContext: BatchDownloadTraceContext,
    ): PublishedPageFiles? {
        if (page.imageUrl == null) {
            return page.uri?.let { uri ->
                UniFile.fromUri(context, uri)?.let { PublishedPageFiles(listOf(it)) }
            }
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
        val tmpFile = try {
            tmpDir.findFile("$filename.tmp")
        } catch (error: Throwable) {
            tracePathOperation(
                download.chapter.id,
                traceContext,
                page,
                BatchDownloadStage.TEMP_LOOKUP,
                BatchDownloadResult.THREW,
                error,
            )
            throw error
        }

        if (tmpFile != null) {
            try {
                val deleted = tmpFile.delete()
                if (!deleted) {
                    tracePathOperation(
                        download.chapter.id,
                        traceContext,
                        page,
                        BatchDownloadStage.TEMP_DELETE,
                        BatchDownloadResult.FALSE,
                    )
                }
            } catch (error: Throwable) {
                tracePathOperation(
                    download.chapter.id,
                    traceContext,
                    page,
                    BatchDownloadStage.TEMP_DELETE,
                    BatchDownloadResult.THREW,
                    error,
                )
                throw error
            }
        }

        var stage = BatchDownloadStage.TEMP_LOOKUP
        try {
            val imageFile = findExistingPageFile(tmpDir, filename)

            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(page.imageUrl!!) -> {
                    stage = BatchDownloadStage.CACHE_COPY
                    copyImageFromCache(
                        chapterCache.getImageFile(page.imageUrl!!),
                        tmpDir,
                        filename,
                        download.chapter.id,
                        page,
                        traceContext,
                    )
                }
                else -> {
                    stage = BatchDownloadStage.HTTP_FETCH
                    downloadImage(
                        page,
                        download.source,
                        tmpDir,
                        filename,
                        download.chapter.id,
                        traceContext,
                    )
                }
            }

            stage = BatchDownloadStage.SPLIT
            val publishedPage = splitTallImageIfNeeded(
                page = page,
                tmpDir = tmpDir,
                imageFile = file,
                filename = filename,
                chapterId = download.chapter.id,
                traceContext = traceContext,
            )

            page.uri = publishedPage.primary?.uri
            page.progress = 100
            page.status = Page.State.READY
            return publishedPage
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (stage != BatchDownloadStage.HTTP_FETCH) {
                tracePageFailure(download.chapter.id, traceContext, page, stage, e)
            }
            page.progress = 0
            page.status = Page.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return null
        }
    }

    /** Downloads image over network with retry into a temporary file, then publishes it. */
    private suspend fun downloadImage(
        page: Page,
        source: HttpSource,
        tmpDir: UniFile,
        filename: String,
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
    ): UniFile {
        page.status = Page.State.DOWNLOAD_IMAGE
        page.progress = 0
        var attempt = 0
        var stage = BatchDownloadStage.HTTP_FETCH
        return try {
            flow {
                attempt++
                stage = BatchDownloadStage.HTTP_FETCH
                val response = try {
                    source.getImage(page)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, error)
                    throw error
                }

                val published = response.use { resp ->
                    stage = BatchDownloadStage.CREATE_TEMP
                    val file = try {
                        tmpDir.createFile("$filename.tmp")!!
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, error)
                        throw error
                    }

                    // Abort active socket read immediately on coroutine cancellation to avoid pool exhaustion
                    val cancellationHook = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true) {
                        runCatching { resp.close() }
                    }

                    var publishedFile: UniFile? = null
                    try {
                        stage = BatchDownloadStage.WRITE_TEMP
                        resp.body.source().saveTo(file.openOutputStream())
                        stage = BatchDownloadStage.DETECT_TYPE
                        val extension = getImageExtension(resp, file)
                        stage = BatchDownloadStage.RENAME_TEMP
                        val finalName = "$filename.$extension"
                        publishedFile = publishDownloadedFile(file, finalName)
                    } catch (e: Throwable) {
                        file.delete()
                        if (e is IOException) {
                            tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, null)
                            tracePageTerminalFailure(chapterId, traceContext, page, stage, null)
                        } else if (e !is CancellationException) {
                            tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, e)
                        }
                        throw e
                    } finally {
                        cancellationHook.dispose()
                        if (publishedFile == null) {
                            file.delete()
                        }
                    }
                    publishedFile
                }
                emit(published)
            }
                .retryWhen { _, retry ->
                    if (retry < 3) {
                        delay((2L shl retry.toInt()) * 1000)
                        true
                    } else {
                        false
                    }
                }
                .first()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            traceContext.generation(BatchDownloadTraceBoundary.PAGE)?.let { generation ->
                BatchDownloadDiagnostics.pageTerminalFailed(
                    chapterId,
                    generation,
                    page.index,
                    page.number,
                    stage,
                    causeForStage(stage, error),
                    error,
                )
            }
            throw error
        }
    }

    /** Copies image from cache to temporary file in tmpDir and detects its type. */
    private fun copyImageFromCache(
        cacheFile: File,
        tmpDir: UniFile,
        filename: String,
        chapterId: Long,
        page: Page,
        traceContext: BatchDownloadTraceContext,
    ): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: run {
            tracePageFailure(chapterId, traceContext, page, BatchDownloadStage.DETECT_TYPE, null)
            tmpFile.delete()
            throw IOException("Unable to parse image type")
        }
        return try {
            publishDownloadedFile(tmpFile, "$filename.$extension")
        } catch (error: IOException) {
            tracePageFailure(chapterId, traceContext, page, BatchDownloadStage.RENAME_TEMP, null)
            tmpFile.delete()
            throw error
        } finally {
            cacheFile.delete()
        }
    }

    /** Returns image extension from response content-type or by inspecting file bytes. */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    /** Splits tall webtoon images into separate parts if splitting preference is enabled. */
    private suspend fun splitTallImageIfNeeded(
        page: Page,
        tmpDir: UniFile,
        imageFile: UniFile,
        filename: String,
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
    ): PublishedPageFiles {
        if (!downloadPreferences.splitTallImages().get()) {
            return PublishedPageFiles(listOf(imageFile))
        }

        val resolvedImageFile = findExistingPageFile(tmpDir, filename) ?: imageFile

        try {
            if (resolvedImageFile.name.orEmpty().startsWith("${filename}__")) {
                val splitFiles = findSplitFiles(tmpDir, filename)
                return PublishedPageFiles(splitFiles.ifEmpty { listOf(resolvedImageFile) })
            }

            val splitSucceeded = runCatching {
                ImageUtil.splitTallImage(tmpDir, resolvedImageFile, filename)
            }.getOrDefault(false)

            if (!splitSucceeded) {
                resolveFallback(tmpDir, filename, resolvedImageFile)?.let { return it }
                error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))
            }

            val splitFiles = findSplitFiles(tmpDir, filename)
            if (splitFiles.isNotEmpty()) return PublishedPageFiles(splitFiles)

            resolveFallback(tmpDir, filename, resolvedImageFile)?.let { return it }
            error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))
        } catch (e: Exception) {
            tracePageAttemptFailure(chapterId, traceContext, page, 1, BatchDownloadStage.SPLIT, e)
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
            resolveFallback(tmpDir, filename, resolvedImageFile)?.let { return it }
            throw e
        }
    }

    private suspend fun resolveFallback(tmpDir: UniFile, filename: String, resolvedImageFile: UniFile): PublishedPageFiles? {
        val fallback = findExistingPageFile(tmpDir, filename) ?: resolvedImageFile
        return if (isPublishedFile(fallback)) PublishedPageFiles(listOf(fallback)) else null
    }

    private suspend fun findSplitFiles(tmpDir: UniFile, filename: String): List<UniFile> {
        val splitFiles = mutableListOf<UniFile>()
        for (index in 1..MAX_SPLIT_PARTS) {
            val splitName = "${filename}__${"%03d".format(Locale.ENGLISH, index)}.jpg"
            val splitFile = findFileWithRetry(tmpDir, splitName) ?: break
            if (isPublishedFile(splitFile)) {
                splitFiles += splitFile
            }
        }
        return splitFiles
    }

    private suspend fun findExistingPageFile(tmpDir: UniFile, filename: String): UniFile? {
        val listedFiles = runCatching { tmpDir.listFiles() }.getOrNull()
        listedFiles?.firstOrNull { file ->
            (file.name.orEmpty().startsWith("$filename.") || file.name.orEmpty().startsWith("${filename}__001")) &&
                isPublishedFile(file)
        }?.let { return it }

        findFileWithRetry(tmpDir, "${filename}__001.jpg")?.takeIf(::isPublishedFile)?.let { return it }
        IMAGE_EXTENSIONS.forEach { extension ->
            runCatching { tmpDir.findFile("$filename.$extension") }
                .getOrNull()
                ?.takeIf(::isPublishedFile)
                ?.let { return it }
        }
        return null
    }

    private suspend fun findFileWithRetry(tmpDir: UniFile, name: String): UniFile? {
        repeat(FILE_LOOKUP_ATTEMPTS) { attempt ->
            val file = tmpDir.findFile(name)
            if (file != null) return file
            if (attempt + 1 < FILE_LOOKUP_ATTEMPTS) delay(FILE_LOOKUP_DELAY_MS)
        }
        return null
    }

    internal fun isPublishedFile(file: UniFile): Boolean = runCatching {
        file.exists() && file.isFile && file.length() > 0
    }.getOrDefault(false)

    private fun tracePageFailure(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        page: Page,
        stage: BatchDownloadStage,
        error: Throwable?,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.PAGE) ?: return
        val cause = causeForStage(stage, error)
        BatchDownloadDiagnostics.pageAttemptFailed(chapterId, generation, page.index, page.number, 1, stage, cause, error)
        BatchDownloadDiagnostics.pageTerminalFailed(chapterId, generation, page.index, page.number, stage, cause, error)
    }

    private fun tracePageAttemptFailure(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        page: Page,
        attempt: Int,
        stage: BatchDownloadStage,
        error: Throwable?,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.PAGE) ?: return
        BatchDownloadDiagnostics.pageAttemptFailed(
            chapterId = chapterId,
            generation = generation,
            pageIndex = page.index,
            pageNumber = page.number,
            attempt = attempt,
            stage = stage,
            cause = causeForStage(stage, error),
            error = error,
        )
    }

    private fun tracePageTerminalFailure(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        page: Page,
        stage: BatchDownloadStage,
        error: Throwable?,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.PAGE) ?: return
        BatchDownloadDiagnostics.pageTerminalFailed(
            chapterId = chapterId,
            generation = generation,
            pageIndex = page.index,
            pageNumber = page.number,
            stage = stage,
            cause = causeForStage(stage, error),
            error = error,
        )
    }

    private fun tracePathOperation(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        page: Page?,
        stage: BatchDownloadStage,
        result: BatchDownloadResult,
        error: Throwable? = null,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.PAGE) ?: return
        BatchDownloadDiagnostics.pathOperation(
            chapterId = chapterId,
            generation = generation,
            pageIndex = page?.index,
            pageNumber = page?.number,
            stage = stage,
            result = result,
            error = error,
        )
    }

    companion object {
        private const val FILE_LOOKUP_ATTEMPTS = 3
        private const val FILE_LOOKUP_DELAY_MS = 50L
        private const val MAX_SPLIT_PARTS = 1024
        private val IMAGE_EXTENSIONS = arrayOf("avif", "gif", "heif", "jpg", "jxl", "png", "webp")
    }
}
