package eu.kanade.translation.model

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

data class Translation(
    val source: HttpSource,
    val manga: Manga,
    val chapter: Chapter,
    val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.CHINESE,
    val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH,
) {
    @Transient
    private val _statusFlow = MutableStateFlow(State.NOT_TRANSLATED)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()
    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    /**
     *  The trigger's admission-probe
     * cross-check, carried with the queued chapter so the batch's
     * pre-registration can stamp honest totals. [probedSourcePageCount] is the
     * SOURCE total when the downloader's fetched page list proved it, and null
     * when the total is unknown; [sourceCountKnown] distinguishes "no
     * cross-check ran" (legacy default) from "cross-check ran and found no
     * trustworthy total" (true + null count). Set only by the trigger path;
     * both defaults keep every other construction site byte-identical.
     */
    var probedSourcePageCount: Int? = null
    var sourceCountKnown: Boolean = false

    enum class State(val value: Int) {
        NOT_TRANSLATED(0),
        QUEUE(1),
        TRANSLATING(2),
        TRANSLATED(3),
        ERROR(4),

        /**
         * The chapter is readable, but one or more drafts remain partial or need review.
         * Kept distinct from [TRANSLATED] so callers can offer retry/review without hiding it.
         */
        READY_WITH_WARNINGS(5),

        /** Retryable provider work remains durable but is not currently running. */
        PAUSED(6),
    }
}
