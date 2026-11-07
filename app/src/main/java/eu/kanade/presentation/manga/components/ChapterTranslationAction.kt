package eu.kanade.presentation.manga.components

/** Actions the manga screen can request from the translation coordinator for a chapter. */
enum class ChapterTranslationAction {
    START,
    DETAILS,
    CANCEL,
    DELETE,

    /** Colorize-only run on the original pages: no OCR, translation, or inpaint. */
    COLORIZE,
}
