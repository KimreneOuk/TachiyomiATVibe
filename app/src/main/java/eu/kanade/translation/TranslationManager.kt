package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {
    private val translator = ChapterTranslator(context, provider);

    init {
        // Make the translator use the same store instance the reader observes
        // so live updates do not need a chapter reload.
        translator.activeStoreResolver = { translation ->
            openOrCreateActiveChapterTranslationStore(
                translation.chapter.id!!,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )
        }
        translator.activeStoreUnregister = { translation ->
            unregisterActiveTranslationStore(translation.chapter.id!!)
        }
    }

    private val activeTranslationStores = mutableMapOf<Long, ChapterTranslationStore>()
    private val _activeStoreState = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())
    val activeStoreState: StateFlow<Map<String, PageTranslation>> = _activeStoreState.asStateFlow()

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    fun translatorStart() = translator.start()
    fun translatorStop(reason: String? = null) = translator.stop(reason)

    fun startTranslation() {
        if (translator.isRunning) return
        translator.start()
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

    fun translateChapter(manga: Manga, chapters: Chapter) {
        translator.queueChapter(manga, chapters);
        startTranslation();
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
        if (isChapterTranslated(chapterName, scanlator, title, sourceId)) return Translation.State.TRANSLATED
        return Translation.State.NOT_TRANSLATED
    }

    fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        val source = sourceManager.get(sourceId);
        if (source == null) return false
        val file = provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source);
        return file?.exists() == true
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
        } catch (_: Exception) {

        }
        return emptyMap()

    }

    fun getChapterTranslation(
        file: UniFile,
    ): Map<String, PageTranslation> {
        try {
            return Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
        } catch (e: Exception) {
            file.delete()
        }
        return emptyMap()
    }

    fun openChapterTranslationStore(file: UniFile): StateFlow<Map<String, PageTranslation>> {
        return ChapterTranslationStore.open(file).state
    }

    fun registerActiveTranslationStore(chapterId: Long, store: ChapterTranslationStore) {
        activeTranslationStores[chapterId] = store
        launchIO {
            store.state.collect { pages ->
                _activeStoreState.value = pages
            }
        }
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        activeTranslationStores.remove(chapterId)
        if (activeTranslationStores.isEmpty()) {
            _activeStoreState.value = emptyMap()
        }
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
    ): ChapterTranslationStore? {
        activeTranslationStores[chapterId]?.let { return it }
        val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
        val store = if (file != null && file.exists()) {
            ChapterTranslationStore.open(file)
        } else {
            val mangaDir = provider.getMangaDir(mangaTitle, source)
            val saveFile = provider.getTranslationFileName(chapterName, scanlator)
            val newFile = mangaDir?.createFile(saveFile) ?: return null
            ChapterTranslationStore.open(newFile)
        }
        registerActiveTranslationStore(chapterId, store)
        return store
    }

    fun openActiveChapterTranslationStore(chapterId: Long, chapterName: String, scanlator: String?, mangaTitle: String, sourceId: Long): StateFlow<Map<String, PageTranslation>>? {
        val source = sourceManager.get(sourceId) ?: return null
        return openOrCreateActiveChapterTranslationStore(chapterId, chapterName, scanlator, mangaTitle, source)?.state
    }

    fun observeActiveStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? {
        return activeTranslationStores[chapterId]?.state
    }

    fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        launchIO {
            removeFromTranslationQueue(chapter)
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source);
            file?.delete()
            provider.deleteCompanionImages(manga.title, source, chapter.name, chapter.scanlator)
        }
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

    fun getCleanedImageStream(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, cleanedImageName: String): (() -> java.io.InputStream)? {
        val file = provider.findPageCleanedImage(mangaTitle, source, chapterName, chapterScanlator, cleanedImageName)
        if (file?.exists() == true) {
            return { file.openInputStream() }
        }
        return null
    }

    fun getRenderedImageStream(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, renderedImageName: String): (() -> java.io.InputStream)? {
        val file = provider.findPageRenderedImage(mangaTitle, source, chapterName, chapterScanlator, renderedImageName)
        if (file?.exists() == true) {
            return { file.openInputStream() }
        }
        return null
    }

    fun getCompanionImageDirForChapter(chapterName: String, scanlator: String?, title: String, source: Source): UniFile? {
        return provider.findCompanionImageDir(title, source, chapterName, scanlator)
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        launchIO {
            translator.translateSinglePage(manga, chapter, source, pageKey)
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
