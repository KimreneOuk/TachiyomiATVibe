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
 * delegates to [eu.kanade.translation.TranslationPipeline]'s
 * decode → OCR → translate → inpaint → render pipeline. The scheduler only
 * cares that the executor runs one page to completion (or failure) under its
 * own single permit, with stage-resume + native-run quarantine already handled.
 *
 * The `force` flag: `false` resumes from the latest persisted stage (no
 * re-OCR when valid blocks exist, no re-translate when blocks are translated,
 * no re-inpaint when a cleaned image exists — see Tracks G/H); `true` redoes
 * the whole pipeline AND calls [PageTranslation.prepareForcedRetry], which
 * resets the per-attempt exhaustion counter ([PageTranslation.attemptCount])
 * and clears prior FAILED/cleaned/rendered state. That reset is load-bearing:
 * without it, a manual re-translate on a page that failed inpaint ran with
 * `force=false`, never cleared the FAILED bookkeeping, and the page stayed
 * blacklisted by [PageTranslation.hasExhaustedRetries] — the "cannot reprocess
 * / retranslate" bug.
 *
 * The MANUAL per-page translate button (ReaderViewModel.translateSinglePage)
 * resolves `force` from the page's live state: `true` when a stage is FAILED
 * (so [PageTranslation.prepareForcedRetry] resets the bookkeeping and the page
 * can be reprocessed), `false` otherwise (resume optimization for healthy /
 * partially-translated pages). The AUTO-prefetch path
 * (TranslationScheduler.requestAutoWindow) does NOT go through that method and
 * keeps `force=false` (resume) semantics; the scheduler's [translatePage]
 * threads the caller's `force` value through to this executor.
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
