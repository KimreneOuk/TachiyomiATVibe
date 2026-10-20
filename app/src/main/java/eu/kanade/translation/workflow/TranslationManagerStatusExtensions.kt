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
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterResetPreflight
import eu.kanade.translation.persistence.ChapterStoreOpener
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.DurableChapterRead
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.pipeline.MemoryPressurePolicy
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

/**
 * Read-through terminal reconstruction.
 * When the bounded tracker registry misses (process death, 20-entry
 * eviction) and no queue owner exists, the projection rebuilds a
 * completed/failed chapter's terminal snapshot from the durable read —
 * active store first, otherwise the opener's publication-free durable
 * read through the pure projector. Read-only by design: it never creates
 * an active store and adds no new cache — the existing durable-status
 * cache is the only intermediary.
 *
 * [resolveTranslation] is injectable so focused unit tests can drive the
 * gate without the database.
 */

internal suspend fun TranslationManager.reconstructDurableTerminalSnapshotImpl(
        chapterId: Long,
        resolveTranslation: suspend (Long) -> Translation?,
    ): TranslationProgressSnapshot? {
        val translation = resolveTranslation(chapterId) ?: return null
        val chapter = translation.chapter
        val sourceId = translation.manga.source
        val state = persistedChapterStatus(
            chapterId,
            chapter.name,
            chapter.scanlator,
            translation.manga.title,
            sourceId,
        ) ?: return null
        if (!isReconstructibleDurableState(state)) return null
        return durableStatusResolver.withDurableRead(
            chapterId = chapterId,
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            mangaTitle = translation.manga.title,
            sourceId = sourceId,
        ) { access ->
            when (access) {
                is DurableChapterAccess.ActiveStore ->
                    access.store.terminalProgressSnapshot(chapterId, state)
                is DurableChapterAccess.DurableRead ->
                    access.read.terminalProgressSnapshot(chapterId, state)
            }
        }
    }


internal fun TranslationManager.getChapterTranslationImpl(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslationView> {
        try {
            return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val parent = provider.findMangaDir(title, source) ?: return@runBlocking emptyMap()
                val fileName = provider.getTranslationFileName(chapterName, scanlator)
                // File-only fallback reads are publication-free: the opener's
                // durable read supplies the verified page truth and no writer
                // store, registry entry, or journal bridge is created. No
                // durable truth can advance here, so no status-cache clear
                // fires; an active chapter keeps its live store semantics.
                when (
                    val read = ChapterStoreOpener.readDurableChapter(
                        parent,
                        fileName,
                    )
                ) {
                    is DurableChapterRead.Supported -> read.state.pages
                    is DurableChapterRead.Missing,
                    is DurableChapterRead.Unsupported,
                    is DurableChapterRead.Corrupt,
                    -> emptyMap()
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to read chapter translation for $chapterName"
            }
        }
        return emptyMap()
    }


internal suspend fun TranslationManager.getChapterTranslationForReaderImpl(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): Map<String, PageTranslationView> {
        val entryStage = ReaderEntryTrace.begin("translation.getForReader", chapterId)
        return try {
            withContext(Dispatchers.IO) {
                activeStores.get(chapterId)?.state?.value?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
                val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source)
                    ?: return@withContext emptyMap()
                val manifestProbe = ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
                if (manifestProbe.exists) {
                    return@withContext openExistingChapterTranslationStore(
                        chapterId,
                        chapterName,
                        scanlator,
                        mangaTitle,
                        source,
                        prefoundDocument = document,
                        preflightManifestProbe = manifestProbe,
                    )?.state?.value.orEmpty()
                }
                return@withContext emptyMap()
            }
        } finally {
            entryStage.end()
        }
    }


internal suspend fun TranslationManager.openExistingChapterTranslationStoreLockedImpl(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        prefoundDocument: TranslationDocument? = null,
        preflightManifestProbe: ArtifactManifestProbe? = null,
    ): ChapterTranslationStore? {
        // Callers that already resolved the document and probed the manifest
        // (getChapterTranslationForReader) hand them through — re-running the
        // SAF walks here doubled the cost on every artifact-chapter open.
        val document = prefoundDocument ?: findTranslationDocument(chapterName, scanlator, mangaTitle, source)
            ?: return null
        val manifestProbe = preflightManifestProbe
            ?: ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
        if (!manifestProbe.exists) return null
        val hadActive = if (chapterId != null) {
            activeStores.get(chapterId) != null
        } else {
            activeStores.getByFile(document.registryKey) != null
        }
        val store = if (chapterId != null) {
            activeStores.getOrCreate(chapterId, document.registryKey) {
                ChapterTranslationStore.openArtifactSuspend(
                    document.parent,
                    document.fileName,
                )
            }
        } else {
            activeStores.getOrCreateFile(document.registryKey) {
                ChapterTranslationStore.openArtifactSuspend(
                    document.parent,
                    document.fileName,
                )
            }
        }
        if (!hadActive) {
            // See the hadActive comment above: only a fresh open can have
            // advanced durable truth through recovery or seeding.
            durableStatusResolver.clearDurableStatusCache()
        }
        return store
    }


internal fun TranslationManager.hasTranslationStoreImpl(chapter: Chapter, manga: Manga, source: Source): Boolean {
        chapter.id?.let { activeStores.get(it) }?.let { return it.state.value.isNotEmpty() }
        val parent = provider.findMangaDir(manga.title, source) ?: return false
        val fileName = provider.getTranslationFileName(chapter.name, chapter.scanlator)
        return ChapterTranslationStore.probeArtifactManifest(parent, fileName).exists
    }
