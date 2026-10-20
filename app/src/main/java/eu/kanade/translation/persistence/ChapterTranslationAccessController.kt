package eu.kanade.translation.persistence

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.model.PageTranslationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Owns reads and active-store binding for a chapter's translation artifact. */
internal class ChapterTranslationAccessController(
    private val provider: TranslationFileProvider,
    private val activeStores: ActiveChapterStoreRegistry,
    private val durableStatusResolver: DurableChapterStatusResolver,
    private val cleanedImageLifecycle: CleanedImageLifecycleController,
) {

    fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? = durableStatusResolver.findTranslationDocument(chapterName, scanlator, mangaTitle, source)

    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslationView> = try {
        runBlocking(Dispatchers.IO) {
            val parent = provider.findMangaDir(title, source) ?: return@runBlocking emptyMap<String, PageTranslationView>()
            val fileName = provider.getTranslationFileName(chapterName, scanlator)
            when (val read = ChapterStoreOpener.readDurableChapter(parent, fileName)) {
                is DurableChapterRead.Supported -> read.state.pages
                DurableChapterRead.Missing,
                is DurableChapterRead.Unsupported,
                is DurableChapterRead.Corrupt,
                -> emptyMap()
            }
        }
    } catch (error: Exception) {
        logcat(LogPriority.WARN, error) {
            "TachiyomiAT failed to read chapter translation for $chapterName"
        }
        emptyMap()
    }

    suspend fun getChapterTranslationForReader(
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
                if (!manifestProbe.exists) return@withContext emptyMap()
                openExistingChapterTranslationStore(
                    chapterId,
                    chapterName,
                    scanlator,
                    mangaTitle,
                    source,
                    prefoundDocument = document,
                    preflightManifestProbe = manifestProbe,
                )?.state?.value.orEmpty()
            }
        } finally {
            entryStage.end()
        }
    }

    suspend fun openExistingChapterTranslationStore(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        prefoundDocument: TranslationDocument? = null,
        preflightManifestProbe: ArtifactManifestProbe? = null,
    ): ChapterTranslationStore? {
        val open = suspend {
            openExistingChapterTranslationStoreLocked(
                chapterId,
                chapterName,
                scanlator,
                mangaTitle,
                source,
                prefoundDocument,
                preflightManifestProbe,
            )
        }
        return if (chapterId != null) activeStores.withChapterAdmissionLock(chapterId, open) else open()
    }

    private suspend fun openExistingChapterTranslationStoreLocked(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        prefoundDocument: TranslationDocument?,
        preflightManifestProbe: ArtifactManifestProbe?,
    ): ChapterTranslationStore? {
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
                ChapterTranslationStore.openArtifactSuspend(document.parent, document.fileName)
            }
        } else {
            activeStores.getOrCreateFile(document.registryKey) {
                ChapterTranslationStore.openArtifactSuspend(document.parent, document.fileName)
            }
        }
        if (!hadActive) durableStatusResolver.clearDurableStatusCache()
        return store
    }

    fun hasTranslationStore(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        source: Source,
        chapterId: Long?,
    ): Boolean {
        chapterId?.let { activeStores.get(it) }?.let { return it.state.value.isNotEmpty() }
        val document = findTranslationDocument(chapterName, chapterScanlator, mangaTitle, source) ?: return false
        return ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName).exists
    }

    suspend fun openOrCreateActiveChapterTranslationStore(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ): ChapterTranslationStore? = withContext(Dispatchers.IO) {
        activeStores.withChapterAdmissionLock(chapterId) {
            openOrCreateActiveChapterTranslationStoreLocked(
                chapterId,
                chapterName,
                scanlator,
                mangaTitle,
                source,
                mangaId,
            )
        }
    }

    private suspend fun openOrCreateActiveChapterTranslationStoreLocked(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ): ChapterTranslationStore? {
        activeStores.get(chapterId)?.let { registered ->
            registered.enableLazyPersistence()
            scheduleRetiredCleanedImageCleanup(registered, chapterId, chapterName, scanlator, mangaTitle, source, mangaId)
            return registered
        }
        val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source)
        val fileName = document?.fileName ?: provider.getTranslationFileName(chapterName, scanlator)
        val manifestProbe = document?.let { ChapterTranslationStore.probeArtifactManifest(it.parent, it.fileName) }
        val registered = activeStores.getOrCreate(chapterId, document?.registryKey) {
            if (manifestProbe?.exists == true) {
                ChapterTranslationStore.openArtifactSuspend(document.parent, fileName)
            } else {
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

    suspend fun unregisterActiveTranslationStore(chapterId: Long) {
        activeStores.remove(chapterId)?.markDefunct()
        durableStatusResolver.clearDurableStatusCache()
    }

    private fun scheduleRetiredCleanedImageCleanup(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ) = cleanedImageLifecycle.scheduleRetiredCleanedImageCleanup(
        store,
        chapterId,
        chapterName,
        scanlator,
        mangaTitle,
        source,
        mangaId,
    )
}
