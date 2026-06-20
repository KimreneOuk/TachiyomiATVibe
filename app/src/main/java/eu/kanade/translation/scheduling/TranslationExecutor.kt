package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/**
 * TachiyomiAT: the per-page translation contract that [TranslationScheduler]
 * depends on, decoupled from the concrete executor.
 *
 * Today this is satisfied by [eu.kanade.translation.ChapterTranslator], which
 * owns the decode → OCR → translate → inpaint → render pipeline. After the
 * pipeline extraction it will be satisfied by
 * [eu.kanade.translation.TranslationPipeline]. Either way the scheduler only
 * cares that the executor runs one page to completion (or failure) under its
 * own single permit, with stage-resume + watchdog semantics already handled.
 *
 * The `force` flag mirrors the existing contract: `false` resumes from the
 * latest persisted stage (no re-OCR when valid blocks exist); `true` redoes the
 * whole pipeline. Auto-prefetch always passes `false`; the manual per-page
 * button passes `true`.
 */
interface TranslationExecutor {

    suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean = true,
    )

    suspend fun translateSinglePageFromStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean = true,
    )
}
