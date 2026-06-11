package eu.kanade.tachiyomi.ui.reader.model

class InsertPage(val parent: ReaderPage) : ReaderPage(parent.index, parent.url, parent.imageUrl) {

    override var chapter: ReaderChapter = parent.chapter

    init {
        status = State.READY
        originalStream = parent.originalStream
        translatedStream = parent.translatedStream
        showTranslatedImage = parent.showTranslatedImage
    }
}
