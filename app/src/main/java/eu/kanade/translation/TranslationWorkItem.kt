package eu.kanade.translation

enum class TranslationWorkState {
    InProgress,
    Queued,
    Recent,
    Failed,
}

data class TranslationWorkItem(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val state: TranslationWorkState,
    val progress: Float,
)
