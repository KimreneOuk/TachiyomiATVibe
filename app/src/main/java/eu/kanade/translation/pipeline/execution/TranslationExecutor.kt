package eu.kanade.translation.pipeline.execution

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/**
 * The per-page translation contract used by the scheduler, decoupled from the
 * concrete executor.
 *
 * Today this is satisfied by [eu.kanade.translation.pipeline.TranslationPipeline],
 * which owns the decode → OCR → translate → inpaint → render stages. The
 * scheduler only cares that the executor runs one page to completion (or
 * failure) under its own single permit, with stage-resume + native-run
 * quarantine already handled.
 *
 * The `force` flag: `false` resumes from the latest persisted stage (no
 * re-OCR when valid blocks exist, no re-translate when blocks are translated,
 * and no re-inpaint when a cleaned image exists); `true` redoes
 * the whole pipeline AND calls [PageTranslation.prepareForcedRetry], which
 * resets the per-attempt exhaustion counter ([PageTranslation.attemptCount])
 * and clears prior FAILED/cleaned/rendered state. That reset is load-bearing:
 * without it, a manual re-translate on a page that failed inpaint ran with
 * `force=false`, never cleared the FAILED bookkeeping, and the page stayed
 * blacklisted by [PageTranslation.hasExhaustedRetries] — the "cannot reprocess
 * / retranslate" bug.
 *
 * The per-page translate button resolves `force` from the page's live state:
 * `true` when a stage is FAILED
 * (so [PageTranslation.prepareForcedRetry] resets the bookkeeping and the page
 * can be reprocessed), `false` otherwise (resume optimization for healthy /
 * partially-translated pages). The scheduler passes that decision through to
 * this executor.
 */
interface TranslationExecutor {

    /**
     * @param readerStreamFn captured fresh-stream factory; each invocation must open a new stream, never an open stream.
     * @param readerPriority pure live supplier sampled at both the admission and execution gates; waiting governor/native promotion lands in S2.
     */
    suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean = false,
        stageListener: TranslationStageListener? = null,
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
        readerStreamFn: (() -> InputStream)? = null,
        readerPriority: (() -> AdmissionPriority)? = null,
    ): SinglePageOutcome
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
    val cleanedImageContentHash: String? = null,
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

/** Outcome of translation work shared by reader and chapter-batch execution. */
sealed interface TranslationCompletionOutcome {
    data class Completed(
        val completedPageKeys: Set<String> = emptySet(),
    ) : TranslationCompletionOutcome

    data class Paused(
        val anchorPageKey: String,
        val completedPageKeys: Set<String> = emptySet(),
        val retryablePageKeys: Set<String> = setOf(anchorPageKey),
        val failure: ProviderFailure? = null,
        val nextEligibleRetryAtEpochMs: Long? = failure?.retryAfterAtEpochMs,
        val reason: String = failure?.safeSummary ?: "Translation paused; retryable provider work remains",
    ) : TranslationCompletionOutcome

    data class Failed(
        val anchorPageKey: String? = null,
        val completedPageKeys: Set<String> = emptySet(),
        val terminalPageKeys: Set<String> = anchorPageKey?.let(::setOf).orEmpty(),
        val failure: ProviderFailure? = null,
        val reason: String = failure?.safeSummary ?: "Translation failed",
    ) : TranslationCompletionOutcome

    /**
     * A worker failed outside the typed provider-failure contract. Keep this
     * distinct from [Failed] so a programming or persistence error cannot be
     * mistaken for a durable provider failure. Later pages remain pending.
     */
    data class Unexpected(
        val anchorPageKey: String,
        val stage: TranslationStageEvent,
        val completedPageKeys: Set<String> = emptySet(),
        val terminalPageKeys: Set<String> = setOf(anchorPageKey),
        val reason: String = "Unexpected ${stage.name.lowercase()} stage failure",
    ) : TranslationCompletionOutcome

    /**
     * A guarded page publication was rejected. The affected page is not
     * terminal; the caller stops and reconciles against the current store state.
     */
    data class PersistenceRejected(
        val anchorPageKey: String,
        val stage: TranslationStageEvent,
        val completedPageKeys: Set<String> = emptySet(),
        val reason: String = "Batch persistence publication rejected",
    ) : TranslationCompletionOutcome
}
