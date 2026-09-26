package eu.kanade.translation.orchestration

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.chapter.TranslationProvider
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

private const val MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP = 64
private const val ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS = 30_000L

internal fun isFreshOrphanedCleanedImage(lastModified: Long, nowEpochMs: Long): Boolean =
    lastModified <= 0L || nowEpochMs - lastModified < ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS

/** Owns cleaned-image lookup, retirement, and cleanup for translation artifacts. */
internal class CleanedImageLifecycleController(
    private val applicationScopeProvider: () -> CoroutineScope,
    private val streamRegistryProvider: () -> TranslationStreamRegistry,
    private val providerProvider: () -> TranslationProvider,
) {

    // Resolve lifecycle dependencies on demand so unused cleanup paths do not create scopes.
    private val applicationScope get() = applicationScopeProvider()

    private val streamRegistry get() = streamRegistryProvider()

    private val provider get() = providerProvider()

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
    fun scheduleRetiredCleanedImageCleanup(
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
            sweepOrphanedCleanedImages(
                store = store,
                chapterId = chapterId,
                chapterName = chapterName,
                scanlator = scanlator,
                mangaTitle = mangaTitle,
                source = source,
                mangaId = stableMangaId,
            )
        }
    }

    fun sweepOrphanedCleanedImages(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long,
    ) {
        val directory = provider.findCompanionImageDir(mangaTitle, source, chapterName, scanlator) ?: return
        val referenced = store.referencedCleanedImageNames()
        val pageKeys = store.state.value.keys
        val now = System.currentTimeMillis()
        directory.listFiles()
            ?.asSequence()
            ?.mapNotNull { file -> file.name?.let { it to file } }
            ?.filter { (name, file) -> file.isFile && name.contains(".cleaned.") }
            ?.filterNot { (name, file) ->
                name in referenced ||
                    streamRegistry.activeCleanedImageReadersForChapter(source.id, mangaId, chapterId, name) > 0 ||
                    pageKeys.any { pageKey -> !store.mayDeleteCleanedImage(pageKey, name) } ||
                    isFreshOrphanedCleanedImage(file.lastModified(), now)
            }
            ?.take(MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP)
            ?.forEach { (name, file) ->
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                    "TachiyomiAT orphaned cleaned image sweep: chapter=$chapterName file=$name deleted=$deleted"
                }
            }
    }

    fun retireChapterCompanionImages(
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

    fun retirePageCompanionImage(
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
}
