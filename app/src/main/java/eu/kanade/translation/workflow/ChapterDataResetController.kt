package eu.kanade.translation.workflow

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.artifact.ChapterArtifactDeletionPlan
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterResetPreflight
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.scheduling.TranslationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Owns chapter and page deletion/reset operations. Deletion first cancels and joins page and
 * batch work, then defuncts the store, clears reader streams, and removes artifacts and
 * companion images. The ordering is covered by [TranslationManagerDeleteResetOrderingTest].
 */
internal class ChapterDataResetController(
    private val findTranslationDocumentFn: (
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ) -> TranslationDocument?,
    private val schedulerProvider: () -> TranslationScheduler,
    private val cancelPageTranslationsFn: suspend (Long) -> Unit,
    private val cancelPageTranslationFn: (Long, String) -> Boolean,
    private val removeFromTranslationQueueFn: (Chapter) -> Unit,
    private val translatorProvider: () -> ChapterTranslator,
    private val disposeBatchTrackerFn: (Long) -> Unit,
    private val unregisterActiveTranslationStoreFn: suspend (Long) -> Unit,
    private val streamRegistryProvider: () -> TranslationStreamRegistry,
    private val providerProvider: () -> TranslationFileProvider,
    private val retireChapterCompanionImagesFn: (Manga, Chapter, Source) -> Unit,
    private val retirePageCompanionImageFn: (Manga, Chapter, Source, String, String, () -> Boolean) -> Unit,
    private val durableStatusResolverProvider: () -> DurableChapterStatusResolver,
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry,
    private val openExistingChapterTranslationStoreFn: suspend (
        Long?,
        String,
        String?,
        String,
        Source,
    ) -> ChapterTranslationStore?,
) {

    // Resolve manager-owned dependencies at call time without retaining second owners.
    private val scheduler get() = schedulerProvider()

    private val translator get() = translatorProvider()

    private val streamRegistry get() = streamRegistryProvider()

    private val provider get() = providerProvider()

    private val durableStatusResolver get() = durableStatusResolverProvider()

    private val activeStores get() = activeStoresProvider()

    private fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? = findTranslationDocumentFn(chapterName, scanlator, mangaTitle, source)

    private suspend fun cancelPageTranslations(chapterId: Long) = cancelPageTranslationsFn(chapterId)

    private fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        cancelPageTranslationFn(chapterId, pageKey)

    private fun removeFromTranslationQueue(chapter: Chapter) = removeFromTranslationQueueFn(chapter)

    private fun disposeBatchTracker(chapterId: Long) = disposeBatchTrackerFn(chapterId)

    private suspend fun unregisterActiveTranslationStore(chapterId: Long) = unregisterActiveTranslationStoreFn(chapterId)

    private fun retireChapterCompanionImages(manga: Manga, chapter: Chapter, source: Source) =
        retireChapterCompanionImagesFn(manga, chapter, source)

    private fun retirePageCompanionImage(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageKey: String,
        imageName: String,
        isReferencedElsewhere: () -> Boolean,
    ) = retirePageCompanionImageFn(manga, chapter, source, pageKey, imageName, isReferencedElsewhere)

    private suspend fun openExistingChapterTranslationStore(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): ChapterTranslationStore? = openExistingChapterTranslationStoreFn(chapterId, chapterName, scanlator, mangaTitle, source)

    suspend fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        val chapterId = chapter.id ?: return
        // Capture the validated authority/legacy-preservation marker before any
        // teardown can evict the only store that still knows the exact names.
        // This is read-only and runs off the caller thread because SAF reads can
        // block; the cancellation ordering below remains unchanged.
        val deletionDocument = withContext(Dispatchers.IO) {
            findTranslationDocument(chapter.name, chapter.scanlator, manga.title, source)
        }
        val artifactDeletionPlan = deletionDocument?.let { document ->
            withContext(Dispatchers.IO) {
                ChapterArtifactDeletionPlan.capture(
                    UniFileChapterDocumentIo(document.parent),
                    document.fileName,
                )
            }
        }
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
        withContext(Dispatchers.IO) {
            unregisterActiveTranslationStore(chapterId)
        }
        streamRegistry.clearChapter(source.id, manga.id, chapterId)
        var authorityRemoved = true
        artifactDeletionPlan?.let { plan ->
            val result = withContext(Dispatchers.IO) { plan.delete() }
            authorityRemoved = result.manifestRemoved
            logcat(if (result.complete) LogPriority.INFO else LogPriority.ERROR) {
                "TachiyomiAT chapter artifact deletion: chapter=${chapter.name} " +
                    "manifestRemoved=${result.manifestRemoved} " +
                    "artifactTreeRemoved=${result.artifactTreeRemoved} " +
                    "deletedLegacy=${result.deletedLegacyNames.size} " +
                    "retainedLegacy=${result.retainedLegacyNames.size} " +
                    "failures=${result.failures.size}"
            }
            // Legacy flat files are deliberately retained; deletion only owns manifest artifacts.
        }
        if (authorityRemoved) retireChapterCompanionImages(manga, chapter, source)
        durableStatusResolver.clearDurableStatusCache()
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
                cleanedImageContentHash = null,
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
        durableStatusResolver.clearDurableStatusCache()
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        streamRegistry.clearChapter(source.id, manga.id, chapterId)

        val activeStore = activeStores.get(chapterId)
        if (activeStore != null) {
            activeStore.state.value.keys.forEach { pageKey ->
                activeStore.updatePageFromCurrentSnapshot(pageKey, "chapter data reset") { page -> page?.let(transform) ?: PageTranslation.EMPTY }
                // An explicit user reset drops the committed display
                // pointer too, so the reader stops showing the cleared bundle.
                activeStore.demoteCommittedDisplay(pageKey, "chapter data reset")
            }
            activeStore.flush()
            //   the reset must also retire the recorded run so a
            // future dispatch can never short-circuit on its COMPLETE record.
            activeStore.retireActiveRun("chapter data reset")
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
                //   same retirement for the persisted-only store.
                store.retireActiveRun("chapter data reset")
            }
        }
        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetTranslationData(chapter: Chapter, manga: Manga, source: Source, pageKey: String, preserveEdits: Boolean) {
        val chapterId = chapter.id ?: return
        durableStatusResolver.clearDurableStatusCache()
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
        durableStatusResolver.clearDurableStatusCache()
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        var ownershipStore = activeStores.get(chapterId)
        val store = ownershipStore
        val persistedCleanedName = store?.state?.value?.get(pageKey)?.cleanedImageName
        if (store != null) {
            store.updatePageFromCurrentSnapshot(pageKey, "inpaint data reset") { page ->
                page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                page.copy(
                    cleanedImageName = null,
                    cleanedImageContentHash = null,
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
                ownershipStore = s
                s.updatePageFromCurrentSnapshot(pageKey, "inpaint data reset") { page ->
                    page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                    page.copy(
                        cleanedImageName = null,
                        cleanedImageContentHash = null,
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
            retirePageCompanionImage(manga, chapter, source, pageKey, imageName) {
                ownershipStore?.isCleanedImageReferencedByAnotherPage(pageKey, imageName) == true
            }
        }

        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetOcrData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return
        durableStatusResolver.clearDurableStatusCache()

        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        var ownershipStore = activeStores.get(chapterId)
        val activeStore = ownershipStore
        val persistedCleanedName = activeStore?.state?.value?.get(pageKey)?.cleanedImageName
        if (activeStore != null) {
            activeStore.deletePage(pageKey)
            activeStore.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { store ->
                ownershipStore = store
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
            retirePageCompanionImage(manga, chapter, source, pageKey, imageName) {
                ownershipStore?.isCleanedImageReferencedByAnotherPage(pageKey, imageName) == true
            }
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
}
