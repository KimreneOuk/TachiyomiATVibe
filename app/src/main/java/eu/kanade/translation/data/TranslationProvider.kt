package eu.kanade.translation.data
import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationProvider(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
) {

    private val translationDir: UniFile?
        get() = storageManager.getTranslationsDirectory()

    /**
     * Returns the translation directory for a manga. For internal use only.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    internal fun getMangaDir(mangaTitle: String, source: Source): UniFile {
        try {
            DiskUtil.createNoMediaFile(translationDir, context)
            val sourceDir = translationDir!!
                .createDirectory(getSourceDirName(source))!!
                .also { DiskUtil.createNoMediaFile(it, context) }
            return sourceDir
                .createDirectory(getMangaDirName(mangaTitle))!!
                .also { DiskUtil.createNoMediaFile(it, context) }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Invalid translation directory" }
            throw Exception(
                context.stringResource(
                    MR.strings.invalid_location,
                    translationDir?.displayablePath ?: "",
                ),
            )
        }
    }

    /**
     * Returns the translation directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        val sourceDir=translationDir?.findFile(getSourceDirName(source))
        return sourceDir;
    }

    /**
     * Returns the translation directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(mangaTitle: String, source: Source): UniFile? {
        val sourceDir = findSourceDir(source)
        return sourceDir?.findFile(getMangaDirName(mangaTitle))
    }

    /**
     * Returns the translation file for a chapter if it exists.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */

    fun findTranslationFile(chapterName: String, chapterScanlator: String?, mangaTitle: String, source: Source): UniFile? {
        val mangaDir = findMangaDir(mangaTitle, source)
        return mangaDir?.findFile(getTranslationFileName(chapterName, chapterScanlator))
    }

    /**
     * Returns a list of translation directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterFiles(chapters: List<Chapter>, manga: Manga, source: Source): Pair<UniFile?, List<UniFile>> {
        val mangaDir = findMangaDir(manga.title, source) ?: return null to emptyList()
        return mangaDir to chapters.mapNotNull { chapter ->
            mangaDir.findFile(getTranslationFileName(chapter.name, chapter.scanlator))
        }
    }

    /**
     * Returns the translation directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(source.toString())
    }

    /**
     * Returns the translation directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
    fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(mangaTitle)
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     */
    fun getTranslationFileName(chapterName: String, chapterScanlator: String?): String {
        val newChapterName = sanitizeChapterName(chapterName)
        return DiskUtil.buildValidFilename(
            when {
                !chapterScanlator.isNullOrBlank() -> "${chapterScanlator}_$newChapterName.json"
                else -> "$newChapterName.json"
            },
        )
    }

    /**
     * Return the new name for the chapter (in case it's empty or blank)
     *
     * @param chapterName the name of the chapter
     */
    private fun sanitizeChapterName(chapterName: String): String {
        return chapterName.ifBlank {
            "Chapter"
        }
    }

    fun getCompanionImageDir(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?): UniFile? {
        val mangaDir = getMangaDir(mangaTitle, source)
        val translationFileName = getTranslationFileName(chapterName, chapterScanlator)
        val companionDirName = translationFileName.removeSuffix(".json") + "_images"
        return mangaDir.createDirectory(companionDirName)?.also {
            DiskUtil.createNoMediaFile(it, context)
        }
    }

    fun findCompanionImageDir(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?): UniFile? {
        val mangaDir = findMangaDir(mangaTitle, source) ?: return null
        val translationFileName = getTranslationFileName(chapterName, chapterScanlator)
        val companionDirName = translationFileName.removeSuffix(".json") + "_images"
        return mangaDir.findFile(companionDirName)
    }

    fun findPageCleanedImage(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, pageImageName: String): UniFile? {
        val dir = findCompanionImageDir(mangaTitle, source, chapterName, chapterScanlator) ?: return null
        return dir.findFile(pageImageName)
    }

    fun findPageRenderedImage(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, pageImageName: String): UniFile? {
        val dir = findCompanionImageDir(mangaTitle, source, chapterName, chapterScanlator) ?: return null
        return dir.findFile(pageImageName)
    }

    fun deleteCompanionImages(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?) {
        findCompanionImageDir(mangaTitle, source, chapterName, chapterScanlator)?.delete()
    }

    fun companionImageNameForPage(pageKey: String, type: String = "cleaned"): String {
        val sanitized = pageKey.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_")
        return "$sanitized.$type.png"
    }

}
