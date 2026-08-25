package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.ChapterTranslationSummary
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.batch.TranslationBatchTrackerRegistry
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.ChapterQueuePreflight
import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.findRunningSameSourceConflict
import eu.kanade.translation.model.staleQueuedChaptersToEvict
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.model.toQueuedChapterView
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {
    private val pipeline = TranslationPipeline(context, provider)
    private val translator = ChapterTranslator(context, provider, pipeline = pipeline)

    // Held here (DI singleton) so deleteTranslation can evict stale reader page-stream closures pointing at the deleted rendered/cleaned PNGs.
    private val streamRegistry: TranslationStreamRegistry = Injekt.get()

    /**
     * Application-lifetime scope for one-off init work (queue rehydration). SupervisorJob so a
     * failure in restoreQueue does not cancel unrelated work; IO dispatcher because restoreQueue does DB reads.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes reader lifecycle teardown so pause/finish cannot race store eviction. */
    private val readerTeardownMutex = Mutex()

    private data class DurableChapterKey(
        val chapterId: Long?,
        val chapterName: String,
        val chapterScanlator: String?,
        val mangaTitle: String,
        val sourceId: Long,
    )

    private data class DurableStatus(val state: Translation.State?)

    private val durableStatusCache = ConcurrentHashMap<DurableChapterKey, DurableStatus>()

    /**
     * Owns single-page + auto-prefetch job scheduling, dedup, and cancellation. This manager
     * keeps the store lifecycle (open/evict/observe), chapter queue, and translation-file I/O.
     * The scheduler resolves the per-chapter store back through this manager via [storeResolver]
     * — store instances are shared between reader and translator, so they must not be owned by the scheduler.
     */
    val scheduler = eu.kanade.translation.scheduling.TranslationScheduler(
        executor = pipeline,
        storeResolver = eu.kanade.translation.scheduling.TranslationStoreResolver { chapterId ->
            activeStores.get(chapterId)
        },
        immediateStoreResolver = { chapterId -> activeStores.get(chapterId) },
    )

    init {
        // Share the store instance between reader and translator so live updates do not need a chapter reload.
        pipeline.activeStoreResolver = { translation ->
            openOrCreateActiveChapterTranslationStore(
                translation.chapter.id!!,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
                translation.manga.id,
            )
        }
        // NOTE: activeStoreUnregister is intentionally NOT wired. Evicting after
        // each single-page translation broke live updates (reader captured the
        // StateFlow once; next translate got a fresh unobserved store). Eviction
        // now happens only on chapter change / reader exit (cancelPageTranslations /
        // cancelAllPageTranslations callers).

        // Native quarantine reports timeout only after the underlying call exits;
        // evict the stale job so a subsequent request can be admitted safely.
        pipeline.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                scheduler.markPageJobStuck(chapterId, pageKey)
            }
        }

        // Batch tracker factory: lets the pipeline create and register a tracker per active batch (observable via observeBatchProgress).
        pipeline.batchTrackerFactory = { chapterId, store, orderedPageKeys ->
            createBatchTracker(chapterId, store, orderedPageKeys)
        }

        // Rehydrate persisted batch queue on IO so a crash mid-batch no longer loses it.
        // Entries get status QUEUE; user taps Start to resume — never auto-starts OCR/LLM on launch.
        applicationScope.launch {
            translator.queueState.collect { durableStatusCache.clear() }
        }
        applicationScope.launch { translator.restoreQueue() }
    }

    /**
     * Evicts a single-page translation job whose worker is stuck in uncancellable native
     * code past its deadline. Delegates to the scheduler, which owns the [activePageJobs] map.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        scheduler.markPageJobStuck(chapterId, pageKey)
    }

    private val activeStores = ActiveChapterStoreRegistry()
    private val batchTrackerRegistry = TranslationBatchTrackerRegistry()
    private val translateAfterDownload = ConcurrentHashMap<Long, TranslationRequest>()
    private val legacyPageJson = Json { ignoreUnknownKeys = true }

    private data class TranslationRequest(
        val manga: Manga,
        val chapter: Chapter,
    )

    /** Owns tracker reducer jobs; reader flows observe the selected store directly. */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any { it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING }

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        chapter.id?.let { translateAfterDownload[it] = TranslationRequest(manga, chapter) }
    }

    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) {
        val chapterId = chapter.id ?: return
        val request = translateAfterDownload.remove(chapterId) ?: return
        if (request.manga.id != manga.id) return
        translateChapter(request.manga, request.chapter)
    }

    fun stopReaderTranslations(reason: String) {
        // The cancellation path includes synchronous runBlocking bridges for durable store
        // cleanup and bounded persist joins. Keep the entire chain on the manager's IO scope so
        // ReaderActivity lifecycle callbacks return without touching those bridges on main.
        applicationScope.launch(start = CoroutineStart.DEFAULT) {
            readerTeardownMutex.withLock {
                cancelAllPageTranslations(cancelBatchQueue = false)
                if (!isAnyBatchTranslationActive) {
                    translatorStop(reason, closeEngines = false)
                }
            }
        }
    }

    /**
     * Starts reader-owned teardown on the manager lifetime rather than the
     * ReaderViewModel scope. The deferred completes only after scheduler
     * coordinator/native/page jobs have joined and reader stores are evicted.
     */
    fun requestReaderStop(reason: String): Deferred<Unit> =
        applicationScope.async(start = CoroutineStart.DEFAULT) {
            awaitReaderStop(reason)
        }

    /** Joined counterpart for callers that already own a non-cancelled scope. */
    suspend fun awaitReaderStop(reason: String) {
        // This method is also called directly by chapter-switch work. Enforce the same IO fence
        // here so a future lifecycle caller cannot reintroduce a main-thread synchronous prefix.
        withContext(Dispatchers.IO) {
            readerTeardownMutex.withLock {
                scheduler.awaitReaderStop()
                val chapterIdsToEvict = activeStores.chapterIds()
                    .filter { !isBatchTranslationActive(it) }
                chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
            }
        }
    }

    fun isTranslating(): Boolean = queueState.value.isNotEmpty()

    fun getActivePageKeys(chapterId: Long): List<String> {
        val store = activeStores.get(chapterId) ?: return emptyList()
        return store.state.value.keys.toList()
    }

    fun isPageActive(chapterId: Long, pageKey: String): Boolean {
        val store = activeStores.get(chapterId) ?: return false
        val page = store.state.value[pageKey] ?: return false
        return page.ocrStatus == StageStatus.RUNNING ||
            page.translationStatus == StageStatus.RUNNING ||
            page.inpaintStatus == StageStatus.RUNNING ||
            page.renderStatus == StageStatus.RUNNING
    }

    fun getTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot>? {
        return getBatchTracker(chapterId)?.snapshot
    }

    fun isTranslationActive(chapterId: Long): Boolean {
        return isBatchTranslationActive(chapterId)
    }

    fun translatorStart() = translator.start()
    fun translatorStop(reason: String? = null, closeEngines: Boolean = false) = translator.stop(reason, closeEngines)

    fun onMemoryPressure(level: Int) {
        val pressureClass = MemoryPressurePolicy.classify(level)
        translator.onMemoryPressure(level, pressureClass)
    }

    fun startTranslation() {
        if (!translator.isRunning) {
            translator.start()
        }
        if (isAnyBatchTranslationActive) {
            TranslationForegroundService.start(context)
        }
    }

    fun pauseTranslation() {
        translator.pause()
        translator.stop()
    }

    fun clearQueue() {
        translator.clearQueue()
        translator.stop()
    }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    fun isBatchTranslationActive(chapterId: Long): Boolean {
        return queueState.value.any { translation ->
            translation.chapter.id == chapterId &&
                (translation.status == Translation.State.QUEUE || translation.status == Translation.State.TRANSLATING)
        }
    }

    fun translateChapter(manga: Manga, chapters: Chapter) {
        val chapterId = chapters.id ?: return
        scheduler.shutdownAutoCoordinator(chapterId)
        evictStaleQueuedChapters(chapterId, manga.source)
        translator.queueChapter(manga, chapters)
        startTranslation()
    }

    fun translateChapters(manga: Manga, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        chapters.forEach { chapter ->
            val chapterId = chapter.id ?: return@forEach
            scheduler.shutdownAutoCoordinator(chapterId)
            translator.queueChapter(manga, chapter)
        }
        startTranslation()
    }

    /**
     * TachiyomiAT bug 3 fix: preflight check for an explicit Start Batch action.
     * Returns the running conflict (if any) so the UI can ask the user before
     * cancelling in-flight work on a different chapter of the same source.
     *
     * Stale QUEUE entries are NOT reported here; they are evicted automatically
     * by [translateChapter] since dropping a not-yet-started queue entry never
     * loses accepted artifacts. Only an actively TRANSLATING chapter needs user
     * confirmation because cancelling it mid-OCR/inpaint discards the in-flight
     * page's native work.
     */
    fun translateChapterPreflight(manga: Manga, chapter: Chapter): ChapterQueuePreflight {
        val chapterId = chapter.id
            ?: return ChapterQueuePreflight.NoConflict
        val view = queueState.value.map { it.toQueuedChapterView() }
        val conflict = findRunningSameSourceConflict(view, chapterId, manga.source)
            ?: return ChapterQueuePreflight.NoConflict
        return ChapterQueuePreflight.RunningConflict(
            chapterId = conflict.chapterId,
            chapterName = conflict.chapterName,
        )
    }

    /**
     * Evicts every queued (status == QUEUE) chapter of [sourceId] other than
     * [keepChapterId] from the batch queue. Preserves accepted artifacts:
     * [removeFromTranslationQueue] only drops the queue entry; the chapter's
     * ChapterTranslationStore and its persisted OCR/inpaint/translation data
     * stay intact, so a later Start Batch on that chapter resumes via the
     * BatchResumeGateDecider's artifact scan without redoing completed work.
     */
    private fun evictStaleQueuedChapters(keepChapterId: Long, sourceId: Long) {
        val staleIds = staleQueuedChaptersToEvict(
            queueState.value.map { it.toQueuedChapterView() },
            keepChapterId,
            sourceId,
        ).map { it.chapterId }.toSet()
        if (staleIds.isEmpty()) return
        val stale = queueState.value
            .filter { it.chapter.id != null && it.chapter.id in staleIds }
            .map { it.chapter }
        stale.forEach { chapter ->
            logcat(LogPriority.INFO) {
                "TachiyomiAT evicting stale queued chapter ${chapter.id} (source=$sourceId) in favor of $keepChapterId; artifacts preserved"
            }
            removeFromTranslationQueue(chapter)
        }
    }

    /**
     * TachiyomiAT bug 3 fix: cancels an actively running translation of
     * [chapterId] (same source) so a subsequent [translateChapter] can start on
     * a different chapter. Used after the UI confirms a [ChapterQueuePreflight.RunningConflict].
     * Cancels in-flight page jobs, durably clears transient queue pages
     * (preserving rendered/terminal artifacts), and drops the queue entry.
     */
    fun cancelRunningChapterForReplace(chapterId: Long) {
        val chapter = queueState.value
            .firstOrNull { it.chapter.id == chapterId }
            ?.chapter
            ?: return
        kotlinx.coroutines.runBlocking {
            scheduler.cancelPageTranslations(chapterId)
        }
        activeStores.get(chapterId)?.let { store ->
            kotlinx.coroutines.runBlocking {
                store.clearTransientQueuePages("Replaced by another chapter's batch")
            }
        }
        removeFromTranslationQueue(chapter)
    }

    fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State {
        val translation = getQueuedTranslationOrNull(chapterId)
        if (translation != null) return translation.status
        activeStores.get(chapterId)?.let { store ->
            // The reader-facing committed projection is authoritative. A live
            // OCR/translation candidate must not make the chapter appear ready
            // or hide an older committed bundle while it is being refreshed.
            val pages = store.display.value
            if (pages.values.any { it.toPageDisplayProjection().displayReady }) {
                val summary = kotlinx.coroutines.runBlocking(Dispatchers.IO) { store.readSummary() }
                return statusFromReadablePages(chapterName, pages, summary)
                    ?: Translation.State.READY_WITH_WARNINGS
            }
        }
        return persistedChapterStatus(chapterId, chapterName, scanlator, title, sourceId)
            ?: Translation.State.NOT_TRANSLATED
    }

    fun observeChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Flow<Translation.State> {
        val queueStatusFlow = queueState.map { queue ->
            queue.find { it.chapter.id == chapterId }?.status
        }.distinctUntilChanged()

        val activeStoreStateFlow = activeStores.snapshots.flatMapLatest { map ->
            val store = map[chapterId]
            if (store != null) {
                combine(store.state, store.display) { _, _ ->
                    getChapterTranslationStatus(chapterId, chapterName, scanlator, title, sourceId)
                }
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }.distinctUntilChanged()

        return kotlinx.coroutines.flow.combine(queueStatusFlow, activeStoreStateFlow) { qStatus, diskStatus ->
            qStatus ?: diskStatus ?: getChapterTranslationStatus(chapterId, chapterName, scanlator, title, sourceId)
        }.distinctUntilChanged()
    }

    /** True when persisted output is readable, including a retry/review-ready warning outcome. */
    fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean = persistedChapterStatus(null, chapterName, chapterScanlator, mangaTitle, sourceId)
        .let { it == Translation.State.TRANSLATED || it == Translation.State.READY_WITH_WARNINGS }

    private fun persistedChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? {
        val key = DurableChapterKey(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)
        durableStatusCache[key]?.let { return it.state }
        val state = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            resolveDurableChapterStatus(
                chapterId,
                chapterName,
                chapterScanlator,
                mangaTitle,
                sourceId,
            )
        }
        durableStatusCache[key] = DurableStatus(state)
        return state
    }

    private suspend fun resolveDurableChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? {
        val source = sourceManager.get(sourceId) ?: return null
        val file = provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source)
            ?: return null
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(file)
        val summary = ChapterTranslationSummaryStore(file).read()
        return when {
            manifestProbe.exists && manifestProbe.manifest?.authority == ManifestAuthority.ARTIFACTS -> {
                artifactSummaryStatus(manifestProbe.manifest, summary)
                    ?: openExistingChapterTranslationStore(
                        chapterId,
                        chapterName,
                        chapterScanlator,
                        mangaTitle,
                        source,
                    )?.let { store ->
                        statusFromReadablePages(chapterName, store.display.value, summary)
                    }
            }
            manifestProbe.exists && manifestProbe.manifest == null -> {
                // A present but unreadable manifest must not fall back to stale flat JSON.
                openExistingChapterTranslationStore(
                    chapterId,
                    chapterName,
                    chapterScanlator,
                    mangaTitle,
                    source,
                )?.let { store ->
                    statusFromReadablePages(chapterName, store.display.value, summary)
                }
            }
            else -> decodeLegacyChapterStatus(file, chapterName, summary)
        }
    }

    private fun artifactSummaryStatus(
        manifest: eu.kanade.translation.artifact.ChapterArtifactManifest?,
        summary: ChapterTranslationSummary?,
    ): Translation.State? {
        if (manifest == null || summary == null || summary.expectedPageCount <= 0) return null
        if (summary.expectedPageCount != manifest.pages.size) return null
        if (manifest.pages.values.none { PageDisplayProjection.from(it).displayReady }) return null
        return when (summary.outcome()) {
            Translation.State.TRANSLATED -> Translation.State.TRANSLATED
            Translation.State.READY_WITH_WARNINGS -> Translation.State.READY_WITH_WARNINGS
            Translation.State.ERROR -> Translation.State.ERROR
            else -> null
        }
    }

    private fun decodeLegacyChapterStatus(
        file: UniFile,
        chapterName: String,
        summary: ChapterTranslationSummary?,
    ): Translation.State? {
        if (!file.exists() || file.length() <= 2L) return null
        return runCatching {
            val pages = file.openInputStream().use {
                legacyPageJson.decodeFromStream<Map<String, PageTranslation>>(it)
            }
            statusFromReadablePages(chapterName, pages, summary)
        }.onFailure { error ->
            quarantineCorruptTranslationFile(file, error)
            logcat(LogPriority.WARN, error) {
                "Translation file for $chapterName unreadable; treating as not translated"
            }
        }.getOrNull()
    }

    private fun statusFromReadablePages(
        chapterName: String,
        pages: Map<String, PageTranslation>,
        summary: ChapterTranslationSummary?,
    ): Translation.State? {
        if (pages.values.none { it.toPageDisplayProjection().displayReady }) return null
        if (summary == null) return Translation.State.READY_WITH_WARNINGS
        if (summary.expectedPageCount != pages.size) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT chapter summary cannot certify completion: chapter=$chapterName " +
                    "reason=expected-count mismatch expected=${summary.expectedPageCount} actual=${pages.size}"
            }
            return Translation.State.READY_WITH_WARNINGS
        }
        return when (summary.outcome()) {
            Translation.State.TRANSLATED -> Translation.State.TRANSLATED
            Translation.State.READY_WITH_WARNINGS -> Translation.State.READY_WITH_WARNINGS
            Translation.State.ERROR -> Translation.State.ERROR
            else -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT chapter summary cannot certify completion: chapter=$chapterName reason=invalid terminal outcome"
                }
                Translation.State.READY_WITH_WARNINGS
            }
        }
    }

    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslation> {
        try {
            val file = provider.findTranslationFile(
                chapterName,
                scanlator,
                title,
                source,
            ) ?: return emptyMap()
            return getChapterTranslation(file)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to read chapter translation for $chapterName"
            }
        }
        return emptyMap()
    }

    suspend fun getChapterTranslationForReader(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): Map<String, PageTranslation> = withContext(Dispatchers.IO) {
        activeStores.get(chapterId)?.state?.value?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
            ?: return@withContext emptyMap()
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(file)
        if (manifestProbe.exists && manifestProbe.manifest?.authority != ManifestAuthority.LEGACY) {
            return@withContext openExistingChapterTranslationStore(
                chapterId,
                chapterName,
                scanlator,
                mangaTitle,
                source,
            )?.state?.value.orEmpty()
        }
        return@withContext decodeLegacyChapterTranslation(file, quarantineOnFailure = true)
    }

    fun getChapterTranslation(
        file: UniFile,
    ): Map<String, PageTranslation> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(file)
        if (manifestProbe.exists && manifestProbe.manifest?.authority != ManifestAuthority.LEGACY) {
            return@runBlocking activeStores.getOrCreateFile(file.registryKey()) {
                ChapterTranslationStore.open(file)
            }?.state?.value.orEmpty()
        }
        return@runBlocking decodeLegacyChapterTranslation(file, quarantineOnFailure = true)
    }

    private suspend fun openExistingChapterTranslationStore(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): ChapterTranslationStore? {
        val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
            ?.takeIf { it.exists() }
            ?: return null
        return if (chapterId != null) {
            activeStores.getOrCreate(chapterId, file.registryKey()) {
                ChapterTranslationStore.open(file)
            }
        } else {
            activeStores.getOrCreateFile(file.registryKey()) {
                ChapterTranslationStore.open(file)
            }
        }
    }

    private fun decodeLegacyChapterTranslation(
        file: UniFile,
        quarantineOnFailure: Boolean,
    ): Map<String, PageTranslation> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.openInputStream().use {
                legacyPageJson.decodeFromStream<Map<String, PageTranslation>>(it)
            }
        }.getOrElse { error ->
            if (quarantineOnFailure) quarantineCorruptTranslationFile(file, error)
            emptyMap()
        }
    }

    private fun quarantineCorruptTranslationFile(file: UniFile, error: Throwable) {
        val name = file.name ?: "translation.json"
        val corruptName = "$name.corrupt"
        val renamed = runCatching {
            file.parentFile?.findFile(corruptName)?.delete()
            file.renameTo(corruptName)
        }.getOrDefault(false)
        logcat(LogPriority.ERROR, error) {
            "TachiyomiAT quarantined corrupt translation file: " +
                "file=$name quarantine=$corruptName renamed=$renamed"
        }
    }

    private fun UniFile.registryKey(): String = filePath ?: uri.toString()

    /** Returns whether this chapter has an existing or active translation store. */
    fun hasTranslationStore(chapter: Chapter, manga: Manga, source: Source): Boolean {
        chapter.id?.let { activeStores.get(it) }?.let { return it.state.value.isNotEmpty() }
        return provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            ?.exists() == true
    }

    /** Re-keys source URL pages to the names written by a completed download. */
    suspend fun rekeyTranslationForCompletedDownload(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        onlineKeyByPageIndex: List<String>,
        onDiskKeyByPageIndex: List<String>,
    ) {
        val chapterId = chapter.id ?: return
        if (onlineKeyByPageIndex.size != onDiskKeyByPageIndex.size) return

        val activeStore = activeStores.get(chapterId)
        val store = activeStore ?: run {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
                ?.takeIf { it.exists() }
                ?: return
            activeStores.getOrCreate(chapterId, file.registryKey()) {
                ChapterTranslationStore.open(file)
            } ?: return
        }
        val pages = store.state.value
        if (pages.size != onlineKeyByPageIndex.size) return
        if (pages.keys.none { it in onlineKeyByPageIndex }) return
        if (pages.keys.all { it in onDiskKeyByPageIndex }) return

        // Match deleteTranslation's cancellation ordering while retaining the
        // active store instance so an open reader observes the new snapshot.
        // Joining here cannot deadlock with a running batch. This method is called from
        // the downloader's IO coroutine, making the bounded blocking bridge safe.
        kotlinx.coroutines.runBlocking { translator.cancelTranslatorJobAndJoin() }
        scheduler.cancelAutoTranslations(chapterId)
        scheduler.cancelPageTranslations(chapterId)
        store.beginGeneration("completed download re-key")
        val moves = store.rekeyPages(onlineKeyByPageIndex, onDiskKeyByPageIndex)
        if (moves.isEmpty()) return

        store.flush()
        val companionDir = provider.findCompanionImageDir(
            manga.title,
            source,
            chapter.name,
            chapter.scanlator,
        )
        moves.forEach { (oldKey, newKey) ->
            val oldImage = provider.companionImageNameForPage(oldKey)
            val newImage = provider.companionImageNameForPage(newKey)
            companionDir?.findFile(oldImage)?.renameTo(newImage)
        }
    }

    fun openChapterTranslationStore(file: UniFile): StateFlow<Map<String, PageTranslation>> {
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            activeStores.getOrCreateFile(file.registryKey()) {
                ChapterTranslationStore.open(file)
            }?.state ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        }
    }

    fun registerActiveTranslationStore(chapterId: Long, store: ChapterTranslationStore) {
        // Keep the existing instance if already registered so a reader keeps observing the same object.
        activeStores.register(chapterId, store)
        durableStatusCache.clear()
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        // Mark the evicted store defunct BEFORE removing it from the registry. A worker still
        // holding a reference has late writes rejected rather than recreating deleted output.
        activeStores.remove(chapterId)?.markDefunct()
        durableStatusCache.clear()
    }

    /**
     * Returns the existing active [ChapterTranslationStore] for [chapterId], or
     * opens one from disk if it is not yet registered. If neither an in-memory
     * store nor an on-disk translation file exists, this creates a fresh store
     * tied to the expected translation path and registers it, so a translator
     * starting now and a reader observing now share the same instance.
     */
    fun openOrCreateActiveChapterTranslationStore(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long? = null,
    ): ChapterTranslationStore? = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        openOrCreateActiveChapterTranslationStoreImpl(
            chapterId,
            chapterName,
            scanlator,
            mangaTitle,
            source,
            mangaId,
        )
    }

    /**
     * Non-blocking variant of [openOrCreateActiveChapterTranslationStore] for
     * coroutine callers (reader loadChapter / per-page view subscription).
     * Opening a store for the first time performs legacy artifact migration
     * with SAF binder I/O; callers must never runBlocking on that path from
     * the main thread (reader-entry ANR) — they should suspend on IO instead.
     */
    suspend fun openOrCreateActiveChapterTranslationStoreSuspend(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long? = null,
    ): ChapterTranslationStore? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        openOrCreateActiveChapterTranslationStoreImpl(
            chapterId,
            chapterName,
            scanlator,
            mangaTitle,
            source,
            mangaId,
        )
    }

    /**
     * Shared open-or-create body. The registry monitor is held only for the
     * map operations themselves — never across store open / artifact
     * migration / SAF I/O. A concurrent open for the same chapter resolves
     * through the registry's keep-existing [registerActiveTranslationStore]
     * semantics: exactly one instance survives and both callers observe it.
     */
    private suspend fun openOrCreateActiveChapterTranslationStoreImpl(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ): ChapterTranslationStore? {
        val existingFile = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
        val registered = activeStores.getOrCreate(
            chapterId,
            existingFile?.takeIf { it.exists() }?.registryKey(),
        ) {
            val file = existingFile
            if (file != null && file.exists()) {
                ChapterTranslationStore.open(file)
            } else {
                // Create a LAZY store: the on-disk file materializes only on the first real write
                // (persistLocked), so merely opening a chapter never leaves an empty file behind that
                // would make isChapterTranslated report a false TRANSLATED state.
                val saveFile = provider.getTranslationFileName(chapterName, scanlator)
                ChapterTranslationStore.lazy {
                    provider.getMangaDir(mangaTitle, source)?.createFile(saveFile)
                        ?: throw java.io.IOException("Cannot create translation file for $chapterName")
                }
            }
        } ?: return null
        scheduleRetiredCleanedImageCleanup(registered, chapterId, chapterName, scanlator, mangaTitle, source, mangaId)
        return registered
    }

    /**
     * Reclaims previous committed cleaned images discovered while opening a
     * chapter. The store only exposes names after its committed pointer is
     * reconstructed; deletion still goes through the stream registry so a
     * reader stream held across a reopen cannot be invalidated.
     *
     * Launched on the application IO scope: the registry executes a retired
     * image's delete callback inline when no lease is held, which is SAF
     * binder I/O — it must never run on the caller's thread (the reader
     * resolves stores from page binds).
     */
    private fun scheduleRetiredCleanedImageCleanup(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ) {
        val stableMangaId = mangaId ?: return
        applicationScope.launch {
            store.state.value.keys.forEach { pageKey ->
                store.drainRetiredCleanedImages(pageKey).forEach { imageName ->
                    streamRegistry.retireCleanedImage(
                        sourceId = source.id,
                        mangaId = stableMangaId,
                        chapterId = chapterId,
                        pageKey = pageKey,
                        imageName = imageName,
                    ) {
                        if (!store.mayDeleteCleanedImage(pageKey, imageName)) return@retireCleanedImage
                        val deleted = provider.findPageCleanedImage(
                            mangaTitle,
                            source,
                            chapterName,
                            scanlator,
                            imageName,
                        )?.delete() == true
                        logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                            "TachiyomiAT chapter-load retired cleaned image drain: " +
                                "pageKey=$pageKey file=$imageName deleted=$deleted"
                        }
                    }
                }
            }
        }
    }

    private fun retireChapterCompanionImages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ) {
        val chapterId = chapter.id ?: return
        val directory = provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
        val namesAtRetirement = directory
            ?.listFiles()
            ?.asSequence()
            ?.mapNotNull { it.name }
            ?.filterNot { it == ".nomedia" }
            ?.toSet()
            .orEmpty()
        streamRegistry.retireCleanedImagesForChapter(
            sourceId = source.id,
            mangaId = manga.id,
            chapterId = chapterId,
        ) {
            namesAtRetirement.forEach { imageName ->
                directory?.findFile(imageName)?.delete()
            }
        }
    }

    private fun retirePageCompanionImage(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageKey: String,
        imageName: String,
    ) {
        val chapterId = chapter.id ?: return
        streamRegistry.retireCleanedImage(
            sourceId = source.id,
            mangaId = manga.id,
            chapterId = chapterId,
            pageKey = pageKey,
            imageName = imageName,
        ) {
            provider.findPageCleanedImage(
                manga.title,
                source,
                chapter.name,
                chapter.scanlator,
                imageName,
            )?.delete()
        }
    }

    fun openActiveChapterTranslationStore(chapterId: Long, chapterName: String, scanlator: String?, mangaTitle: String, sourceId: Long): StateFlow<Map<String, PageTranslation>>? {
        val source = sourceManager.get(sourceId) ?: return null
        return openOrCreateActiveChapterTranslationStore(chapterId, chapterName, scanlator, mangaTitle, source)?.state
    }

    fun openTranslationSession(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): TranslationSession? {
        val chapterId = chapter.id ?: return null
        val store = openOrCreateActiveChapterTranslationStore(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
            manga.id,
        ) ?: return null
        val key = "${source.id}:${manga.id}:$chapterId"
        return TranslationSession(key, manga, chapter, source, store)
    }

    fun requestAutoWindow(
        session: TranslationSession,
        requests: List<TranslationPageRequest>,
    ) = scheduler.requestAutoWindow(session, requests)

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean =
        scheduler.cancelAutoTranslations(chapterId)

    /**
     * Ticket 03: live auto-translation snapshot from the rolling coordinator.
     * Null when no coordinator is active. The reader observes this to render
     * the compact ready-ahead status without polling the durable store.
     */
    val autoSnapshot: kotlinx.coroutines.flow.StateFlow<eu.kanade.translation.scheduling.AutoTranslationSnapshot?> =
        scheduler.autoSnapshot

    /** Ticket 03: rolling coordinator window update. The scheduler owns the coordinator. */
    fun updateAutoWindow(
        identity: eu.kanade.translation.scheduling.AutoChapterIdentity,
        visiblePageIndex: Int,
        configuredAheadTarget: Int,
        pageCount: Int,
        session: TranslationSession,
        pageResolver: (Int) -> eu.kanade.translation.scheduling.RollingAutoCoordinator.PageWorkItem?,
        computeClass: eu.kanade.translation.translator.TranslatorComputeClass,
    ) {
        scheduler.updateAutoWindow(
            identity,
            visiblePageIndex,
            configuredAheadTarget,
            pageCount,
            session,
            pageResolver,
            computeClass,
        )
    }

    fun shutdownAutoCoordinator() = scheduler.shutdownAutoCoordinator()

    /** Reconciles the active rolling window after a reader lifecycle/memory signal. */
    fun reconcileAutoWindow() {
        scheduler.reconcileAutoWindow()
    }

    fun observeActiveStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? = activeStores.observe(chapterId)

    /** Reader-facing projection with the committed display pointer applied. */
    fun observeActiveDisplayStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? =
        activeStores.get(chapterId)?.display

    /**
     * Chapter-keyed active page source for reader state. Unlike a global active-store stream,
     * this never emits another chapter's pages and becomes empty when this chapter is removed.
     */
    fun selectActiveStore(chapterId: Long): Flow<Map<String, PageTranslation>> = activeStores.select(chapterId)

    fun createBatchTracker(
        chapterId: Long,
        store: ChapterTranslationStore,
        orderedPageKeys: List<String>,
    ): TranslationBatchProgressTracker = batchTrackerRegistry.createTracker(
        chapterId = chapterId,
        store = store,
        orderedPageKeys = orderedPageKeys,
        scope = storeScope,
        permitHolderResolver = { pipeline.permitHolderPageKeySnapshot() },
    )

    fun disposeBatchTracker(chapterId: Long) {
        batchTrackerRegistry.dispose(chapterId)
    }

    internal fun terminalSnapshotCacheSize(): Int = batchTrackerRegistry.terminalSnapshotCacheSize()

    fun getBatchTracker(chapterId: Long): TranslationBatchProgressTracker? = batchTrackerRegistry.getLive(chapterId)

    /**
     * Live batch progress for [chapterId]: emits from the tracker's snapshot StateFlow when a
     * tracker is active, else falls back to store-derived progress. Switches reactively when a
     * tracker is created or disposed.
     */
    fun observeBatchProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
        return batchTrackerRegistry.live
            .flatMapLatest { trackers ->
                val tracker = trackers[chapterId]
                if (tracker != null) {
                    tracker.snapshot
                } else {
                    val terminal = batchTrackerRegistry.terminal.value[chapterId]
                    if (terminal != null) {
                        flowOf(terminal)
                    } else {
                        val queued = getQueuedTranslationOrNull(chapterId)
                        val state = queued?.status ?: Translation.State.NOT_TRANSLATED
                        val store = activeStores.get(chapterId)
                        if (store == null && queued != null) {
                            flow {
                                val s = openOrCreateActiveChapterTranslationStoreSuspend(
                                    chapterId = chapterId,
                                    chapterName = queued.chapter.name,
                                    scanlator = queued.chapter.scanlator,
                                    mangaTitle = queued.manga.title,
                                    source = queued.source,
                                    mangaId = queued.manga.id,
                                )
                                if (s == null) {
                                    emit(TranslationProgressSnapshot.empty(chapterId, state))
                                } else {
                                    emitAll(
                                        combine(s.state, s.display) { pages, display ->
                                            TranslationProgressSnapshot.compute(
                                                chapterId = chapterId,
                                                state = state,
                                                pageMap = pages,
                                                displayPageMap = display,
                                                permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
                                            )
                                        },
                                    )
                                }
                            }
                        } else if (store == null) {
                            flowOf(TranslationProgressSnapshot.empty(chapterId, state))
                        } else {
                            combine(store.state, store.display) { pages, display ->
                                TranslationProgressSnapshot.compute(
                                    chapterId = chapterId,
                                    state = state,
                                    pageMap = pages,
                                    displayPageMap = display,
                                    permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
                                )
                            }
                        }
                    }
                }
            }
            .distinctUntilChanged()
    }

    /**
     * Per-chapter batch progress (done/total) for the manga-screen chapter-list indicator, so
     * the user can watch pre-translation advance without opening the reader. Emits the active
     * store's page-count progress; empty when no active store exists (no batch in flight).
     */
    fun observeTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
        return activeStores.snapshots
            .flatMapLatest { stores ->
                val state = getQueuedTranslationOrNull(chapterId)?.status ?: Translation.State.NOT_TRANSLATED
                val store = stores[chapterId]
                if (store == null) {
                    flowOf(TranslationProgressSnapshot.empty(chapterId, state))
                } else {
                    combine(store.state, store.display) { pages, display ->
                        TranslationProgressSnapshot.compute(
                            chapterId = chapterId,
                            state = getQueuedTranslationOrNull(chapterId)?.status ?: state,
                            pageMap = pages,
                            displayPageMap = display,
                            permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
                        )
                    }
                }
            }
            .distinctUntilChanged()
    }

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? {
        return observeActiveDisplayStore(chapterId)
            ?.map { pages -> pages[pageKey].toPageView() }
            ?.distinctUntilChanged()
    }

    suspend fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        val chapterId = chapter.id ?: return
        // SYNCHRONOUS teardown (was fire-and-forget): a delete-then-retranslate let the reader
        // re-bind to the about-to-be-evicted store while the translator wrote to a fresh instance,
        // and a cancelled-but-not-joined batch worker kept writing into the old store after its
        // file/PNGs were deleted (recreating the JSON or stranding pages at RUNNING). Suspending
        // guarantees callers land on clean state.
        //
        // Ordering is load-bearing and strictly sequenced:
        //   1. cancelAutoTranslations bumps the auto generation so the window stops dispatching new pages.
        //   2. cancelPageTranslations cancels + JOINs each auto/single-page job so native work unwinds.
        //   3. removeFromTranslationQueue + cancelTranslatorJobAndJoin drop the batch entry and JOIN the
        //      batch worker (plain removeFrom only cancel()s) so it releases the translator permit before deletion.
        //   4. unregisterActiveTranslationStore marks the store defunct so a still-unwinding worker's late writes no-op.
        //   5. streamRegistry.clearChapter drops stale reader closures pointing at the about-to-be-deleted PNGs.
        //   6. Only once all work is wound down is it safe to delete the on-disk file + companion images.
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        disposeBatchTracker(chapterId)
        unregisterActiveTranslationStore(chapterId)
        streamRegistry.clearChapter(source.id, manga.id, chapterId)
        val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
        file?.delete()
        // Purge the bounded summary sidecar alongside the translation JSON.
        // deleteManga already removes the whole manga directory so the sidecar goes with it;
        // this per-chapter path previously only deleted the translation JSON + companion images.
        // Same lookup ChapterTranslationSummaryStore.findSummaryFile() uses (parent + summaryFileName).
        file?.let { nonNullFile ->
            val name = nonNullFile.name ?: return@let
            nonNullFile.parentFile?.findFile(ChapterTranslationSummaryStore.summaryFileName(name))?.delete()
        }
        retireChapterCompanionImages(manga, chapter, source)
    }

    suspend fun deletePageTranslation(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        resetOcrData(chapter, manga, source, pageKey)
    }

    suspend fun chapterResetPreflight(
        chapter: Chapter,
        manga: Manga,
        source: Source,
    ): ChapterResetPreflight {
        val chapterId = chapter.id
        val activeStore = chapterId?.let(activeStores::get)
        if (activeStore != null) return activeStore.resetPreflight()

        return openExistingChapterTranslationStore(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
        )?.resetPreflight() ?: ChapterResetPreflight(0, 0, 0, 0)
    }

    suspend fun resetChapterTranslationData(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        preserveEdits: Boolean,
    ) {
        resetChapterData(chapter, manga, source) { page ->
            val blocks = page.blocks.map { block ->
                if (preserveEdits && block.userEditedAt != null) {
                    block
                } else {
                    block.copy(
                        translation = "",
                        textColor = 0xFF000000,
                        strokeColor = 0xFFFFFFFF,
                        strokeWidth = 0f,
                    )
                }
            }.toMutableList()
            page.copy(
                blocks = blocks,
                translationStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            ).also {
                it.translationError = null
                it.renderError = null
            }
        }
    }

    suspend fun resetChapterInpaintData(chapter: Chapter, manga: Manga, source: Source) {
        resetChapterData(chapter, manga, source) { page ->
            page.copy(
                cleanedImageName = null,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            ).also {
                it.inpaintError = null
                it.renderError = null
            }
        }
        retireChapterCompanionImages(manga, chapter, source)
    }

    suspend fun resetChapterOcrData(chapter: Chapter, manga: Manga, source: Source) {
        deleteTranslation(chapter, manga, source)
    }

    private suspend fun resetChapterData(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        transform: (PageTranslation) -> PageTranslation,
    ) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        streamRegistry.clearChapter(source.id, manga.id, chapterId)

        val activeStore = activeStores.get(chapterId)
        if (activeStore != null) {
            activeStore.state.value.keys.forEach { pageKey ->
                activeStore.updatePageFromCurrentSnapshot(pageKey, "chapter data reset") { page -> page?.let(transform) ?: PageTranslation.EMPTY }
                // Phase 3: an explicit user reset drops the committed display
                // pointer too, so the reader stops showing the cleared bundle.
                activeStore.demoteCommittedDisplay(pageKey, "chapter data reset")
            }
            activeStore.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { store ->
                store.state.value.keys.forEach { pageKey ->
                    store.updatePageFromCurrentSnapshot(pageKey, "chapter data reset") { page -> page?.let(transform) ?: PageTranslation.EMPTY }
                    store.demoteCommittedDisplay(pageKey, "chapter data reset")
                }
                store.flush()
            }
        }
        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetTranslationData(chapter: Chapter, manga: Manga, source: Source, pageKey: String, preserveEdits: Boolean) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val store = activeStores.get(chapterId)
        if (store != null) {
            store.updatePageFromCurrentSnapshot(pageKey, "translation data reset") { page ->
                page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                val newBlocks = page.blocks.map { block ->
                    if (preserveEdits && block.userEditedAt != null) {
                        block
                    } else {
                        block.copy(
                            translation = "",
                            textColor = 0xFF000000,
                            strokeColor = 0xFFFFFFFF,
                            strokeWidth = 0f,
                        )
                    }
                }.toMutableList()

                page.copy(
                    blocks = newBlocks,
                    translationStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                ).also {
                    it.translationError = null
                    it.renderError = null
                }
            }
            store.demoteCommittedDisplay(pageKey, "translation data reset")
            store.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { s ->
                s.updatePageFromCurrentSnapshot(pageKey, "translation data reset") { page ->
                    page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                    val newBlocks = page.blocks.map { block ->
                        if (preserveEdits && block.userEditedAt != null) {
                            block
                        } else {
                            block.copy(
                                translation = "",
                                textColor = 0xFF000000,
                                strokeColor = 0xFFFFFFFF,
                                strokeWidth = 0f,
                            )
                        }
                    }.toMutableList()

                    page.copy(
                        blocks = newBlocks,
                        translationStatus = eu.kanade.translation.model.StageStatus.PENDING,
                        renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    ).also {
                        it.translationError = null
                        it.renderError = null
                    }
                }
                s.demoteCommittedDisplay(pageKey, "translation data reset")
                s.flush()
            }
        }

        // Reconcile batch progress so summary drops cleared data
        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetInpaintData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val store = activeStores.get(chapterId)
        val persistedCleanedName = store?.state?.value?.get(pageKey)?.cleanedImageName
        if (store != null) {
            store.updatePageFromCurrentSnapshot(pageKey, "inpaint data reset") { page ->
                page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                page.copy(
                    cleanedImageName = null,
                    inpaintStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                ).also {
                    it.inpaintError = null
                    it.renderError = null
                }
            }
            store.demoteCommittedDisplay(pageKey, "inpaint data reset")
            store.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { s ->
                s.updatePageFromCurrentSnapshot(pageKey, "inpaint data reset") { page ->
                    page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                    page.copy(
                        cleanedImageName = null,
                        inpaintStatus = eu.kanade.translation.model.StageStatus.PENDING,
                        renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    ).also {
                        it.inpaintError = null
                        it.renderError = null
                    }
                }
                s.demoteCommittedDisplay(pageKey, "inpaint data reset")
                s.flush()
            }
        }

        val safePageKey = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val retiredNames = buildSet {
            persistedCleanedName?.let(::add)
            add("$safePageKey.cleaned.png")
            add("$safePageKey.cleaned.jpg")
            add("$safePageKey.rendered.png")
            provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
                ?.listFiles()
                ?.asSequence()
                .orEmpty()
                .mapNotNull { it.name }
                .filter { it.startsWith("$safePageKey.cleaned.") }
                .forEach(::add)
        }
        retiredNames.forEach { imageName ->
            retirePageCompanionImage(manga, chapter, source, pageKey, imageName)
        }

        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetOcrData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()

        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val activeStore = activeStores.get(chapterId)
        val persistedCleanedName = activeStore?.state?.value?.get(pageKey)?.cleanedImageName
        if (activeStore != null) {
            activeStore.deletePage(pageKey)
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { store ->
                store.deletePage(pageKey)
                store.flush()
            }
        }

        val safePageKey = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val retiredNames = buildSet {
            persistedCleanedName?.let(::add)
            add("$safePageKey.cleaned.png")
            add("$safePageKey.cleaned.jpg")
            add("$safePageKey.rendered.png")
            // Versioned publication names are unique per replacement attempt;
            // remove any orphaned versions left after a deleted store entry.
            provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
                ?.listFiles()
                ?.asSequence()
                .orEmpty()
                .mapNotNull { it.name }
                .filter { it.startsWith("$safePageKey.cleaned.") }
                .forEach(::add)
        }
        retiredNames.forEach { imageName ->
            retirePageCompanionImage(manga, chapter, source, pageKey, imageName)
        }
    }

    private suspend fun reconcileBatchProgress(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ) {
        // Refresh the active-store summary after a stage reset so the chapter
        // list can drop stale progress data. Only runs when the store is open
        // (i.e. the reader is active for this chapter); persisted-only chapters
        // are unaffected because their summary is rebuilt on the next open.
        val store = activeStores.get(chapterId) ?: return
        store.flush()
    }

    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                translator.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
            }
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else if (queueState.value.isNotEmpty()) {
                translator.start()
            }
        }
    }

    fun getCleanedImageStream(
        mangaTitle: String,
        source: Source,
        chapterName: String,
        chapterScanlator: String?,
        cleanedImageName: String,
        pageKey: String? = null,
        mangaId: Long? = null,
        chapterId: Long? = null,
    ): (() -> java.io.InputStream)? {
        return {
            val file = provider.findPageCleanedImage(mangaTitle, source, chapterName, chapterScanlator, cleanedImageName)
            if (file?.exists() == true) {
                val raw = { file.openInputStream() }
                if (pageKey == null || mangaId == null || chapterId == null) {
                    raw()
                } else {
                    streamRegistry.openCleanedImageStream(
                        sourceId = source.id,
                        mangaId = mangaId,
                        chapterId = chapterId,
                        pageKey = pageKey,
                        imageName = cleanedImageName,
                        open = raw,
                    )
                }
            } else {
                throw java.io.FileNotFoundException("Cleaned image not found: $cleanedImageName")
            }
        }
    }

    fun getCompanionImageDirForChapter(chapterName: String, scanlator: String?, title: String, source: Source): UniFile? {
        return provider.findCompanionImageDir(title, source, chapterName, scanlator)
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) =
        scheduler.translatePage(manga, chapter, source, pageKey)

    /**
     * Cancels the in-flight single-page translation job for one [pageKey] within [chapterId] —
     * the per-page granularity [cancelPageTranslations] (chapter-scoped) is too coarse for.
     * Returns true if a job was actually cancelled, false if none was running for that page.
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        scheduler.cancelPageTranslation(chapterId, pageKey)

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and evicts the shared
     * [ChapterTranslationStore] so it does not leak across chapter navigations. Call this on
     * reader navigate-away so the previous chapter's work can no longer hold the executor's
     * single permit. Job cancellation is delegated to the scheduler; store eviction is manager-owned.
     */
    suspend fun cancelPageTranslations(chapterId: Long) {
        scheduler.cancelPageTranslations(chapterId)
        if (isBatchTranslationActive(chapterId)) {
            return
        }
        disposeBatchTracker(chapterId)
        activeStores.get(chapterId)?.clearTransientQueuePages("Translation cancelled")
        // Evict the store on chapter exit; the reader re-opens it via observeLiveTranslationStore on the next loadChapter.
        unregisterActiveTranslationStore(chapterId)
    }

    /**
     * Cancels every in-flight single-page translation job and drops all shared stores. Call this
     * when the reader is destroyed or the master toggle is switched off, so no orphaned work
     * keeps running and no collector outlives the session. Job cancellation is delegated to the
     * scheduler; store eviction + chapter queue clearing are manager-owned.
     */
    fun cancelAllPageTranslations(cancelBatchQueue: Boolean = false) {
        scheduler.cancelAllPageTranslations()
        val chapterIdsToEvict = activeStores.chapterIds()
            .filter { cancelBatchQueue || !isBatchTranslationActive(it) }
        val stores = chapterIdsToEvict.mapNotNull { activeStores.get(it) }
        if (stores.isNotEmpty()) {
            // TachiyomiAT bug 4 fix: the durable CANCELLED write MUST land before
            // unregisterActiveTranslationStore marks these stores defunct below.
            // The previous code launched clearTransientQueuePages on storeScope
            // and then synchronously called markDefunct in the same pass; the
            // async clear was rejected as defunct and the durable state was
            // silently dropped, leaving pages RUNNING in the next session's
            // rehydrated snapshot. Run the clear to completion here (bounded by
            // the small number of active chapter stores) before eviction.
            kotlinx.coroutines.runBlocking {
                stores.forEach { store ->
                    store.clearTransientQueuePages("All translation cancelled")
                }
            }
        }
        chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
        if (cancelBatchQueue) {
            translator.clearQueue()
        }
    }

    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation ->
                    translation.statusFlow.drop(1).map { translation }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { translation -> translation.status == Translation.State.TRANSLATING }.asFlow(),
            )
        }
}
