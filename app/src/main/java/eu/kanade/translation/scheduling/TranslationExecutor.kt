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
 * The `force` flag: `false` resumes from the latest persisted stage (no
 * re-OCR when valid blocks exist, no re-translate when blocks are translated,
 * no re-inpaint when a cleaned image exists — see Tracks G/H); `true` redoes
 * the whole pipeline. The default is `false` so that a manual tap on a page
 * that was already translated (e.g. by auto-prefetch) resumes instead of
 * burning a full re-OCR + re-translate + re-inpaint + re-render. An explicit
 * `force = true` is reserved for a future "re-translate this page" UI
 * affordance. Both auto-prefetch and the manual per-page button pass the
 * default (`false`); the scheduler's [translatePage] threads an optional
 * `force` through for that future affordance.
 */
interface TranslationExecutor {

    suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean = false,
    )

    suspend fun translateSinglePageFromStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean = false,
    )
}
