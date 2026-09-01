package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
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
        stageListener: TranslationStageListener? = null,
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
    ): SinglePageOutcome

    suspend fun translateSinglePageFromStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean = false,
        stageListener: TranslationStageListener? = null,
    )

    /**
     * Native phase of the prepared-page boundary: decode → detect/OCR → inpaint
     * → persist the cleaned image and required metadata durably. Returns a
     * lightweight [PreparedPage] reference carrying durable identifiers only —
     * never a decoded bitmap — so a caller may begin another native page while
     * a remote translator processes this prepared page.
     *
     * Returns null only when the prepared reference is stale, the active store
     * disappeared, or the cleaned image is unavailable. Typed completion
     * outcomes carry provider pauses, terminal failures, and persistence
     * rejections; callers must not treat those outcomes as successful
     * completion.
     *
     * When [PreparedPage.isTerminal] is true the page needs no further work
     * (textless terminal, render-only resume that already completed, or an
     * already-fully-translated page); callers must skip [translatePreparedPage]
     * in that case.
     *
     * Stage events fire at actual execution entry on the supplied
     * [stageListener]: READING before OCR, CLEANING before a standalone inpaint
     * (the fused single-page recognize path emits READING for the combined
     * detect+OCR+inpaint native call).
     */
    suspend fun prepareSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)? = null,
        force: Boolean = false,
        stageListener: TranslationStageListener? = null,
    ): PreparedPage?

    /**
     * Translation/render phase of the prepared-page boundary: load the durable
     * cleaned image produced by [prepareSinglePage], translate recognized text,
     * render the display result, and persist stage writes. Runs outside the
     * native permit so a caller's other native page may overlap this page's
     * remote translation.
     *
     * @return [ChunkCompletionOutcome.Completed] when translate/render work
     *   completed (including textless terminal no-ops), [ChunkCompletionOutcome.Paused]
     *   or [ChunkCompletionOutcome.Failed] when the page was actually attempted
     *   but produced a typed provider outcome, and null ONLY when the prepared
     *   reference no longer matches the durable store (generation / pageVersion
     *   / fingerprint mismatch, missing page, or missing cleaned image) — a
     *   stale/race outcome the caller may retry. An inpaint failure during
     *   preparation (no cleaned image produced) also surfaces as null: the
     *   caller should re-prepare the page rather than mark the slot Failed.
     * @throws Throwable on a genuine translate/render failure or timeout (after
     *   durable failure writes), matching the legacy [translateSinglePage]
     *   propagation so callers can attribute failures correctly instead of
     *   mistaking them for a race loss.
     *
     * TRANSLATING fires before the provider request; RENDERING fires before
     * compositing the translated result.
     */
    suspend fun translatePreparedPage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        prepared: PreparedPage,
        stageListener: TranslationStageListener? = null,
    ): ChunkCompletionOutcome?
}

/**
 * T917 D2 (design note §2.4): typed outcome of one single-page intent. Replaces
 * the previous silent `Unit` return so a denied lease can never again look like
 * a completed intent (audit C-01): the scheduler records the outcome and its
 * cancel path can tell "owned the page" from "only observed the owner".
 */
sealed interface SinglePageOutcome {
    /** The executor ran the page itself (including resume-skip soft exits). */
    data object Completed : SinglePageOutcome

    /** The page was owned by [owner]; the executor attached to the owner's terminal commit. */
    data class Attached(val owner: PageWriteOrigin) : SinglePageOutcome

    /**
     * The executor attached but never observed the owner's terminal commit
     * (wait bound hit, or cancellation while observing). No page write happened.
     */
    data class AttachedUnresolved(val owner: PageWriteOrigin, val reason: String) : SinglePageOutcome

    /** The intent could not run or attach (defunct store, unknown owner). */
    data class Rejected(val owner: PageWriteOrigin?, val reason: String) : SinglePageOutcome
}

/**
 * Lightweight, serializable reference to a page whose native preparation is
 * durably complete. Carries only durable identifiers and metadata — never a
 * decoded bitmap — so it is safe to retain across the native/translation lane
 * boundary and across coroutine cancellations.
 *
 * The handoff is honest: [cleanedImageName] is non-null only after the cleaned
 * image file is verified durable on disk and the store records
 * `inpaintStatus == READY` at the current inpaint revision. Callers must not
 * treat a null [cleanedImageName] as a failure — a textless page legitimately
 * produces a terminal [PreparedPage] with no cleaned image.
 */
data class PreparedPage(
    val pageKey: String,
    val chapterId: Long?,
    val mangaId: Long,
    val sourceId: Long,
    val cleanedImageName: String?,
    val generation: Long,
    val pageVersion: Long,
    val blockFingerprints: List<String>,
    val isTerminal: Boolean,
)

/**
 * User-facing pipeline stages, emitted at actual execution entry (not while a
 * page is waiting in a queue). The labels map to the live reader experience:
 * READING = detection and OCR, CLEANING = segmentation and inpainting,
 * TRANSLATING = provider/local translator request, RENDERING = compositing the
 * translated result.
 */
enum class TranslationStageEvent {
    READING,
    CLEANING,
    TRANSLATING,
    RENDERING,
}

/**
 * Narrow callback invoked by [TranslationExecutor] implementations when a
 * stage actually begins executing for a page. Methods are synchronous and
 * must not block on the translation pipeline: collectors should snapshot the
 * event and dispatch any heavy work asynchronously.
 */
fun interface TranslationStageListener {
    fun onStageEntered(pageKey: String, stage: TranslationStageEvent)
}
