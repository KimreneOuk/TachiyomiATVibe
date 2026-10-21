package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface DownloadRekeyOutcome {
    data class Performed(val moved: Int, val skippedCollisions: Int) : DownloadRekeyOutcome
    data object Noop : DownloadRekeyOutcome
    data object NoTranslationRecords : DownloadRekeyOutcome
    data class Deferred(
        val reason: String,
        val waitingForRegisteredStoreRelease: Boolean = false,
        val requestPreserved: Boolean = false,
    ) : DownloadRekeyOutcome
    data class Rejected(val reason: String, val cause: Throwable? = null) : DownloadRekeyOutcome
}

/** Owns pending page-key migrations until active reader and batch work releases the chapter. */
internal class CompletedDownloadRekeyCoordinator(
    private val provider: TranslationFileProvider,
    private val activeStores: ActiveChapterStoreRegistry,
    private val batchCoordinator: BatchTranslationCoordinator,
    private val requestCoordinator: () -> TranslationRequestCoordinator,
    private val readerCoordinator: () -> ReaderTranslationCoordinator,
) {
    private data class PendingDownloadRekey(
        val chapter: Chapter,
        val manga: Manga,
        val source: Source,
        val onlineKeyByPageIndex: List<String>,
        val onDiskKeyByPageIndex: List<String>,
        val revision: Long,
        val requestGeneration: Long?,
    )

    private val pendingRekeys = ConcurrentHashMap<Long, PendingDownloadRekey>()
    private val revision = AtomicLong()

    fun hasPendingRekey(chapterId: Long): Boolean = pendingRekeys.containsKey(chapterId)

    fun isChapterRekeying(chapterId: Long): Boolean =
        requestCoordinator().isChapterDownloadRekeying(chapterId)

    suspend fun reconcilePendingRekeyForAdmission(chapterId: Long): Boolean {
        val pending = pendingRekeys[chapterId] ?: return true
        val outcome = attempt(pending)
        if (!outcome.isFullyReconciled()) return false
        return consume(chapterId, pending)
    }

    suspend fun retryPendingAfterReaderLeave() {
        pendingRekeys.keys.toList().forEach { chapterId ->
            val pending = pendingRekeys[chapterId] ?: return@forEach
            val outcome = attempt(pending)
            if (outcome.isFullyReconciled() && consume(chapterId, pending)) {
                pending.requestGeneration?.let { expectedGeneration ->
                    val autoStart = !requestCoordinator().isRestoreAdmitted(chapterId)
                    val admitted = requestCoordinator().retryRekeyFailedAdmissionIfCurrent(
                        manga = pending.manga,
                        chapter = pending.chapter,
                        expectedGeneration = expectedGeneration,
                        autoStart = autoStart,
                    )
                    if (admitted && !autoStart) requestCoordinator().consumeRestoreAdmission(chapterId)
                }
            } else {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT download re-key retry retained for chapter $chapterId: ${outcome::class.simpleName}"
                }
            }
        }
    }

    suspend fun rekeyTranslationForCompletedDownload(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        onlineKeyByPageIndex: List<String>,
        onDiskKeyByPageIndex: List<String>,
    ): DownloadRekeyOutcome {
        val chapterId = chapter.id ?: return DownloadRekeyOutcome.Rejected("chapter id missing")
        if (onlineKeyByPageIndex.size != onDiskKeyByPageIndex.size || onlineKeyByPageIndex.isEmpty()) {
            return DownloadRekeyOutcome.Rejected("mapping size mismatch")
        }
        val pending = PendingDownloadRekey(
            chapter = chapter,
            manga = manga,
            source = source,
            onlineKeyByPageIndex = onlineKeyByPageIndex.toList(),
            onDiskKeyByPageIndex = onDiskKeyByPageIndex.toList(),
            revision = revision.incrementAndGet(),
            requestGeneration = requestCoordinator().downloadAttachGeneration(chapterId),
        )
        pendingRekeys.compute(chapterId) { _, current ->
            if (current == null || pending.revision > current.revision) pending else current
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT download re-key mapping retained for chapter $chapterId revision=${pending.revision}"
        }

        val outcome = attempt(pending)
        if (!outcome.isFullyReconciled()) return outcome
        if (consume(chapterId, pending)) return outcome
        return if (pendingRekeys[chapterId]?.revision?.let { it > pending.revision } == true) {
            DownloadRekeyOutcome.Deferred("a newer completed-download mapping needs reconciliation")
        } else {
            // A concurrent attempt already consumed this revision.
            outcome
        }
    }

    private fun consume(chapterId: Long, pending: PendingDownloadRekey): Boolean {
        if (pendingRekeys.remove(chapterId, pending)) return true
        return pendingRekeys[chapterId] == null
    }

    private fun DownloadRekeyOutcome.isFullyReconciled(): Boolean =
        this is DownloadRekeyOutcome.Noop ||
            this is DownloadRekeyOutcome.NoTranslationRecords ||
            (this is DownloadRekeyOutcome.Performed && skippedCollisions == 0)

    /** Performs the guarded page-key transaction under chapter and artifact-open admission. */
    private suspend fun attempt(pending: PendingDownloadRekey): DownloadRekeyOutcome {
        val chapterId = pending.chapter.id ?: return DownloadRekeyOutcome.Rejected("chapter id missing")
        val directoryLookup = try {
            provider.findMangaDirForCompletedDownload(pending.manga.title, pending.source)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return DownloadRekeyOutcome.Rejected("manga artifact directory lookup failed: ${error.message}", error)
        }
        val parent = when (directoryLookup) {
            is TranslationFileProvider.MangaDirectoryLookup.Found -> directoryLookup.directory
            TranslationFileProvider.MangaDirectoryLookup.NoPriorTranslationRecords -> {
                return activeStores.withChapterAdmissionLock(chapterId) {
                    when {
                        activeStores.get(chapterId) != null -> deferForRegisteredReaderStore(
                            pending,
                            chapterId,
                            "active chapter ownership exists while manga artifacts are unavailable",
                        )
                        batchCoordinator.isChapterTranslationInFlight(chapterId) ||
                            batchCoordinator.isChapterBatchActive(chapterId, includePaused = true) ->
                            DownloadRekeyOutcome.Deferred(
                                "active chapter ownership exists while manga artifacts are unavailable",
                            )
                        else -> DownloadRekeyOutcome.NoTranslationRecords
                    }
                }
            }
            TranslationFileProvider.MangaDirectoryLookup.Unavailable ->
                return DownloadRekeyOutcome.Rejected("manga artifact storage directory is unavailable")
        }
        val fileName = provider.getTranslationFileName(pending.chapter.name, pending.chapter.scanlator)
        val registryKey = "${parent.filePath ?: parent.uri}:$fileName"
        return try {
            activeStores.withChapterAdmissionLock(chapterId) {
                activeStores.withFileOpeningLock(registryKey) {
                    if (activeStores.get(chapterId) != null || activeStores.hasStoreForFile(registryKey)) {
                        return@withFileOpeningLock deferForRegisteredReaderStore(
                            pending,
                            chapterId,
                            "translation store registered for chapter or artifact",
                        )
                    }
                    if (batchCoordinator.isChapterTranslationInFlight(chapterId)) {
                        return@withFileOpeningLock DownloadRekeyOutcome.Deferred("batch translation in flight for chapter")
                    }
                    val manifestProbe = try {
                        ChapterTranslationStore.probeArtifactManifest(parent, fileName)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        return@withFileOpeningLock DownloadRekeyOutcome.Rejected(
                            "translation manifest probe failed: ${error.message}",
                            error,
                        )
                    }
                    if (!manifestProbe.exists) return@withFileOpeningLock DownloadRekeyOutcome.NoTranslationRecords
                    val store = try {
                        ChapterTranslationStore.openArtifactSuspend(parent, fileName)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        return@withFileOpeningLock DownloadRekeyOutcome.Rejected(
                            "translation artifact open failed: ${error.message}",
                            error,
                        )
                    }
                    var primaryFailure: Throwable? = null
                    try {
                        if (activeStores.get(chapterId) != null || activeStores.hasStoreForFile(registryKey)) {
                            return@withFileOpeningLock deferForRegisteredReaderStore(
                                pending,
                                chapterId,
                                "translation store registered during handoff admission",
                            )
                        }
                        if (batchCoordinator.isChapterTranslationInFlight(chapterId)) {
                            return@withFileOpeningLock DownloadRekeyOutcome.Deferred(
                                "batch translation became in flight during handoff admission",
                            )
                        }
                        val artifactManifest = store.readArtifactManifest()
                        if (artifactManifest == null) {
                            return@withFileOpeningLock DownloadRekeyOutcome.Rejected(
                                "translation manifest disappeared or could not be recovered after a positive probe",
                            )
                        }
                        val pages = store.state.value
                        if (pages.isEmpty()) {
                            val manifestHasPriorEvidence = artifactManifest.pages.isNotEmpty() ||
                                artifactManifest.durableFailures.isNotEmpty() ||
                                artifactManifest.expectedPageCount != null ||
                                artifactManifest.expectedPageCountTrusted ||
                                artifactManifest.partialBatchInfo != null ||
                                artifactManifest.activeCandidateGenerationIds.isNotEmpty() ||
                                artifactManifest.envelopePlan != null ||
                                artifactManifest.cutoverAtEpochMs != null
                            if (manifestHasPriorEvidence) {
                                return@withFileOpeningLock DownloadRekeyOutcome.Rejected(
                                    "translation manifest contains prior evidence but no live page records were recovered",
                                )
                            }
                            return@withFileOpeningLock DownloadRekeyOutcome.Noop
                        }
                        if (pages.keys.all { it in pending.onDiskKeyByPageIndex }) {
                            return@withFileOpeningLock DownloadRekeyOutcome.Noop
                        }
                        if (pages.keys.none { it in pending.onlineKeyByPageIndex }) {
                            return@withFileOpeningLock DownloadRekeyOutcome.Rejected(
                                "no stored online page keys match the completed-download mapping",
                            )
                        }

                        val reserved = requestCoordinator().tryBeginDownloadRekey(chapterId) {
                            batchCoordinator.isChapterBatchActive(chapterId, includePaused = true)
                        }
                        if (!reserved) {
                            return@withFileOpeningLock DownloadRekeyOutcome.Deferred(
                                "chapter batch became active before handoff reservation",
                            )
                        }
                        try {
                            readerCoordinator().cancelChapterAsync(chapterId.toString())
                            store.beginGeneration("completed download re-key")
                            when (
                                val outcome = store.rekeyPages(
                                    pending.onlineKeyByPageIndex,
                                    pending.onDiskKeyByPageIndex,
                                )
                            ) {
                                is ChapterTranslationStore.PageRekeyOutcome.Moved -> {
                                    if (outcome.moves.isNotEmpty()) {
                                        store.flush()
                                        val companionDir = provider.findCompanionImageDir(
                                            pending.manga.title,
                                            pending.source,
                                            pending.chapter.name,
                                            pending.chapter.scanlator,
                                        )
                                        outcome.moves.forEach { (oldKey, newKey) ->
                                            val oldImage = provider.companionImageNameForPage(oldKey)
                                            val newImage = provider.companionImageNameForPage(newKey)
                                            companionDir?.findFile(oldImage)?.renameTo(newImage)
                                        }
                                    }
                                    DownloadRekeyOutcome.Performed(outcome.moves.size, outcome.skippedCollisions)
                                }
                                ChapterTranslationStore.PageRekeyOutcome.Noop -> DownloadRekeyOutcome.Noop
                                is ChapterTranslationStore.PageRekeyOutcome.Rejected ->
                                    DownloadRekeyOutcome.Rejected(outcome.reason, outcome.cause)
                            }
                        } finally {
                            requestCoordinator().finishDownloadRekey(chapterId)
                        }
                    } catch (error: Throwable) {
                        primaryFailure = error
                        if (error is CancellationException) throw error
                        DownloadRekeyOutcome.Rejected("translation re-key failed: ${error.message}", error)
                    } finally {
                        try {
                            withContext(NonCancellable) { store.closeAndFlush() }
                        } catch (closeFailure: Throwable) {
                            primaryFailure?.addSuppressed(closeFailure) ?: throw closeFailure
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            DownloadRekeyOutcome.Rejected("translation re-key transaction failed: ${error.message}", error)
        }
    }

    private fun deferForRegisteredReaderStore(
        pending: PendingDownloadRekey,
        chapterId: Long,
        reason: String,
    ): DownloadRekeyOutcome.Deferred {
        val requestPreserved = pending.requestGeneration?.let { expectedGeneration ->
            requestCoordinator().markRegisteredReaderStoreWaitIfCurrent(chapterId, expectedGeneration)
        } ?: false
        return DownloadRekeyOutcome.Deferred(
            reason = reason,
            waitingForRegisteredStoreRelease = true,
            requestPreserved = requestPreserved,
        )
    }
}
