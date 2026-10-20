package eu.kanade.translation.workflow

import android.content.Context
import androidx.annotation.VisibleForTesting
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.translationQueueAdmissionFailureKind
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.persistence.ArtifactManifestProbe
import eu.kanade.translation.persistence.ChapterAttemptLedgerDocument
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterResetPreflight
import eu.kanade.translation.persistence.ChapterStoreOpener
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.DurableChapterRead
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.coordinator.ReaderSessionToken
import eu.kanade.translation.coordinator.ReaderSubmission
import eu.kanade.translation.coordinator.ReaderTrigger
import eu.kanade.translation.coordinator.TranslationSession
import eu.kanade.translation.coordinator.TranslationStoreResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal suspend fun TranslationManager.reconcilePendingDownloadRekeyForAdmissionImpl(chapterId: Long): Boolean {
        val pending = pendingDownloadRekeys[chapterId] ?: return true
        val outcome = attemptDownloadRekey(pending)
        if (!outcome.isFullyReconciled()) return false
        return consumeReconciledMapping(chapterId, pending)
    }


/**
 * Retries every retained download re-key after a FULL joined reader leave
 * (teardown mutex released, page/native jobs joined, stores evicted).
 * Rechecks live ownership before each attempt; a racing new reader bind or
 * an in-flight batch re-defers the mapping (retained + observable).
 */

internal suspend fun TranslationManager.retryPendingDownloadRekeysAfterReaderLeaveImpl() {
        pendingDownloadRekeys.keys.toList().forEach { chapterId ->
            val pending = pendingDownloadRekeys[chapterId] ?: return@forEach
            val outcome = attemptDownloadRekey(pending)
            if (outcome.isFullyReconciled() && consumeReconciledMapping(chapterId, pending)) {
                pending.requestGeneration?.let { expectedGeneration ->
                    val autoStart = chapterId !in restoreAdmittedChapterIds
                    val admitted = requestCoordinator.retryRekeyFailedAdmissionIfCurrent(
                        manga = pending.manga,
                        chapter = pending.chapter,
                        expectedGeneration = expectedGeneration,
                        autoStart = autoStart,
                    )
                    if (admitted && !autoStart) restoreAdmittedChapterIds.remove(chapterId)
                }
            } else {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT download re-key retry retained for chapter $chapterId: ${outcome::class.simpleName}"
                }
            }
        }
    }


internal suspend fun TranslationManager.rekeyTranslationForCompletedDownloadImpl(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        onlineKeyByPageIndex: List<String>,
        onDiskKeyByPageIndex: List<String>,
    ): TranslationManager.DownloadRekeyOutcome {
        val chapterId = chapter.id ?: return TranslationManager.DownloadRekeyOutcome.Rejected("chapter id missing")
        if (onlineKeyByPageIndex.size != onDiskKeyByPageIndex.size || onlineKeyByPageIndex.isEmpty()) {
            return TranslationManager.DownloadRekeyOutcome.Rejected("mapping size mismatch")
        }
        val pending = TranslationManager.PendingDownloadRekey(
            chapter = chapter,
            manga = manga,
            source = source,
            onlineKeyByPageIndex = onlineKeyByPageIndex.toList(),
            onDiskKeyByPageIndex = onDiskKeyByPageIndex.toList(),
            revision = pendingDownloadRekeyRevision.incrementAndGet(),
            requestGeneration = requestCoordinator.downloadAttachGeneration(chapterId),
        )
        stashPendingDownloadRekey(chapterId, pending)
        val outcome = attemptDownloadRekey(pending)
        if (!outcome.isFullyReconciled()) return outcome
        if (consumeReconciledMapping(chapterId, pending)) return outcome
        return if (pendingDownloadRekeys[chapterId]?.revision?.let { it > pending.revision } == true) {
            TranslationManager.DownloadRekeyOutcome.Deferred("a newer completed-download mapping needs reconciliation")
        } else {
            // A concurrent attempt already consumed this revision.
            outcome
        }
    }


internal fun TranslationManager.stashPendingDownloadRekeyImpl(chapterId: Long, pending: TranslationManager.PendingDownloadRekey) {
        pendingDownloadRekeys.compute(chapterId) { _, current ->
            if (current == null || pending.revision > current.revision) pending else current
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT download re-key mapping retained for chapter $chapterId revision=${pending.revision}"
        }
    }


internal fun TranslationManager.consumeReconciledMappingImpl(chapterId: Long, pending: TranslationManager.PendingDownloadRekey): Boolean {
        if (pendingDownloadRekeys.remove(chapterId, pending)) return true
        return pendingDownloadRekeys[chapterId] == null
    }


/**
 * The guarded migration itself. The chapter admission lock covers manager
 * reader/batch binding; the registry's per-file opening lock covers chapter,
 * file, and probe opens. Existing owners are checked before this temporary
 * instance opens, and every reconcile entry takes these same guards.
 */

internal suspend fun TranslationManager.attemptDownloadRekeyImpl(pending: TranslationManager.PendingDownloadRekey): TranslationManager.DownloadRekeyOutcome {
        val chapterId = pending.chapter.id ?: return TranslationManager.DownloadRekeyOutcome.Rejected("chapter id missing")
        val directoryLookup = try {
            provider.findMangaDirForCompletedDownload(pending.manga.title, pending.source)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return TranslationManager.DownloadRekeyOutcome.Rejected("manga artifact directory lookup failed: ${error.message}", error)
        }
        val parent = when (directoryLookup) {
            is TranslationFileProvider.MangaDirectoryLookup.Found -> directoryLookup.directory
            TranslationFileProvider.MangaDirectoryLookup.NoPriorTranslationRecords -> {
                return withChapterStoreAdmissionLock(chapterId) {
                    if (activeStores.get(chapterId) != null) {
                        deferForRegisteredReaderStore(
                            pending,
                            chapterId,
                            "active chapter ownership exists while manga artifacts are unavailable",
                        )
                    } else if (
                        translator.isChapterTranslationInFlight(chapterId) ||
                        isChapterBatchActive(chapterId, includePaused = true)
                    ) {
                        TranslationManager.DownloadRekeyOutcome.Deferred(
                            "active chapter ownership exists while manga artifacts are unavailable",
                        )
                    } else {
                        TranslationManager.DownloadRekeyOutcome.NoTranslationRecords
                    }
                }
            }
            TranslationFileProvider.MangaDirectoryLookup.Unavailable ->
                return TranslationManager.DownloadRekeyOutcome.Rejected("manga artifact storage directory is unavailable")
        }
        val fileName = provider.getTranslationFileName(pending.chapter.name, pending.chapter.scanlator)
        val registryKey = "${parent.filePath ?: parent.uri}:$fileName"
        return try {
            withChapterStoreAdmissionLock(chapterId) {
                activeStores.withFileOpeningLock(registryKey) {
                    // Capture pre-existing chapter, file, or probe ownership
                    // before resolving our own store. Existing readers defer.
                    if (activeStores.get(chapterId) != null || activeStores.hasStoreForFile(registryKey)) {
                        return@withFileOpeningLock deferForRegisteredReaderStore(
                            pending,
                            chapterId,
                            "translation store registered for chapter or artifact",
                        )
                    }
                    if (translator.isChapterTranslationInFlight(chapterId)) {
                        return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Deferred(
                            "batch translation in flight for chapter",
                        )
                    }
                    val manifestProbe = try {
                        ChapterTranslationStore.probeArtifactManifest(parent, fileName)
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Rejected(
                            "translation manifest probe failed: ${error.message}",
                            error,
                        )
                    }
                    if (!manifestProbe.exists) {
                        return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.NoTranslationRecords
                    }
                    val store = try {
                        ChapterTranslationStore.openArtifactSuspend(
                            parent,
                            fileName,
                        )
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Rejected(
                            "translation artifact open failed: ${error.message}",
                            error,
                        )
                    }
                    var primaryFailure: Throwable? = null
                    try {
                        // Recheck before mutation: a batch may claim this chapter
                        // while its resolver waits on the shared open lock.
                        if (activeStores.get(chapterId) != null || activeStores.hasStoreForFile(registryKey)) {
                            return@withFileOpeningLock deferForRegisteredReaderStore(
                                pending,
                                chapterId,
                                "translation store registered during handoff admission",
                            )
                        }
                        if (translator.isChapterTranslationInFlight(chapterId)) {
                            return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Deferred(
                                "batch translation became in flight during handoff admission",
                            )
                        }
                        val artifactManifest = store.readArtifactManifest()
                        if (manifestProbe.exists && artifactManifest == null) {
                            return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Rejected(
                                "translation manifest disappeared or could not be recovered after a positive probe",
                            )
                        }
                        val pages = store.state.value
                        if (pages.isEmpty()) {
                            val manifestHasPriorEvidence = artifactManifest?.let { manifest ->
                                manifest.pages.isNotEmpty() ||
                                    manifest.sourceShaByPageKey.isNotEmpty() ||
                                    manifest.durableFailures.isNotEmpty() ||
                                    manifest.expectedPageCount != null ||
                                    manifest.expectedPageCountTrusted ||
                                    manifest.partialBatchInfo != null ||
                                    manifest.activeCandidateGenerationIds.isNotEmpty() ||
                                    manifest.activeRun != null ||
                                    manifest.ocrCheckpoints.isNotEmpty() ||
                                    manifest.envelopePlan != null ||
                                    manifest.cutoverAtEpochMs != null
                            } == true
                            if (manifestHasPriorEvidence) {
                                return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Rejected(
                                    "translation manifest contains prior evidence but no live page records were recovered",
                                )
                            }
                            return@withFileOpeningLock if (!manifestProbe.exists) {
                                TranslationManager.DownloadRekeyOutcome.NoTranslationRecords
                            } else {
                                TranslationManager.DownloadRekeyOutcome.Noop
                            }
                        }
                        if (pages.keys.all { it in pending.onDiskKeyByPageIndex }) {
                            return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Noop
                        }
                        if (pages.keys.none { it in pending.onlineKeyByPageIndex }) {
                            return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Rejected(
                                "no stored online page keys match the completed-download mapping",
                            )
                        }

                        val reserved = synchronized(requestCoordinator.pendingRequestMutationLock) {
                            if (
                                translator.isChapterTranslationInFlight(chapterId) ||
                                isChapterBatchActive(chapterId, includePaused = true)
                            ) {
                                false
                            } else {
                                chaptersUnderDownloadRekey.add(chapterId)
                            }
                        }
                        if (!reserved) {
                            return@withFileOpeningLock TranslationManager.DownloadRekeyOutcome.Deferred(
                                "chapter batch became active before handoff reservation",
                            )
                        }
                        try {
                            // The reservation rejects fresh page dispatch while
                            // the reader coordinator retires its page session.
                            readerCoordinator.cancelChapterAsync(chapterId.toString())
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
                                    TranslationManager.DownloadRekeyOutcome.Performed(outcome.moves.size, outcome.skippedCollisions)
                                }
                                ChapterTranslationStore.PageRekeyOutcome.Noop -> TranslationManager.DownloadRekeyOutcome.Noop
                                is ChapterTranslationStore.PageRekeyOutcome.Rejected ->
                                    TranslationManager.DownloadRekeyOutcome.Rejected(outcome.reason, outcome.cause)
                            }
                        } finally {
                            chaptersUnderDownloadRekey.remove(chapterId)
                        }
                    } catch (error: Throwable) {
                        primaryFailure = error
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        TranslationManager.DownloadRekeyOutcome.Rejected("translation re-key failed: ${error.message}", error)
                    } finally {
                        try {
                            withContext(kotlinx.coroutines.NonCancellable) { store.closeAndFlush() }
                        } catch (closeFailure: Throwable) {
                            primaryFailure?.addSuppressed(closeFailure) ?: throw closeFailure
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            TranslationManager.DownloadRekeyOutcome.Rejected("translation re-key transaction failed: ${error.message}", error)
        }
    }


internal fun TranslationManager.deferForRegisteredReaderStoreImpl(
        pending: TranslationManager.PendingDownloadRekey,
        chapterId: Long,
        reason: String,
    ): TranslationManager.DownloadRekeyOutcome.Deferred {
        val requestPreserved = pending.requestGeneration?.let { expectedGeneration ->
            requestCoordinator.markRegisteredReaderStoreWaitIfCurrent(chapterId, expectedGeneration)
        } ?: false
        return TranslationManager.DownloadRekeyOutcome.Deferred(
            reason = reason,
            waitingForRegisteredStoreRelease = true,
            requestPreserved = requestPreserved,
        )
    }


internal suspend fun TranslationManager.unregisterActiveTranslationStoreImpl(chapterId: Long) {
        // Remove it before joining persistence so new lookups cannot bind to the store being
        // evicted. Once the bounded join ends, markDefunct fences workers that still hold it.
        activeStores.remove(chapterId)?.markDefunct()
        durableStatusResolver.clearDurableStatusCache()
    }


/**
 * Shared open-or-create body. The registry monitor is held only for the
 * map operations themselves — never during store opening or artifact
 * probing through SAF. A concurrent open for the same chapter resolves
 * through the registry's keep-existing semantics: exactly one instance
 * survives and both callers observe it.
 */

internal suspend fun TranslationManager.openOrCreateActiveChapterTranslationStoreImplImpl(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ): ChapterTranslationStore? {
        // Registry fast path: the live store for this chapter needs no
        // document walk or manifest probe — both ran when it was opened and
        // their result is already baked into the registry entry.
        activeStores.get(chapterId)?.let { registered ->
            registered.enableLazyPersistence()
            scheduleRetiredCleanedImageCleanup(registered, chapterId, chapterName, scanlator, mangaTitle, source, mangaId)
            return registered
        }
        val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source)
        val fileName = document?.fileName ?: provider.getTranslationFileName(chapterName, scanlator)
        val manifestProbe = document?.let {
            ChapterTranslationStore.probeArtifactManifest(it.parent, it.fileName)
        }
        val registered = activeStores.getOrCreate(
            chapterId,
            document?.registryKey,
        ) {
            if (manifestProbe?.exists == true) {
                ChapterTranslationStore.openArtifactSuspend(
                    document.parent,
                    fileName,
                )
            } else {
                // Create a lazy artifact store: opening a chapter never
                // creates an empty manifest; the first real write materializes it.
                ChapterTranslationStore.lazy(
                    artifactParent = document?.parent,
                    artifactFileName = fileName,
                    artifactParentResolver = { provider.getMangaDir(mangaTitle, source) },
                )
            }
        } ?: return null
        registered.enableLazyPersistence()
        scheduleRetiredCleanedImageCleanup(registered, chapterId, chapterName, scanlator, mangaTitle, source, mangaId)
        return registered
    }
