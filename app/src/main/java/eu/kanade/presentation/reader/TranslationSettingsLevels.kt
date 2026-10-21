package eu.kanade.presentation.reader

/**
 * In-sheet navigation levels of the translation settings sheet. Depth drives
 * the forward/back transition direction; parent defines system-back behavior
 * so a category always returns to the Advanced directory.
 */
internal enum class TranslationSettingsLevel(val depth: Int, val parent: TranslationSettingsLevel?) {
    General(depth = 0, parent = null),
    Translator(depth = 1, parent = General),
    Advanced(depth = 1, parent = General),
    Engine(depth = 2, parent = Advanced),
    Ocr(depth = 2, parent = Advanced),
    Inpaint(depth = 2, parent = Advanced),
    Activity(depth = 2, parent = Advanced),
    Data(depth = 2, parent = Advanced),
}
