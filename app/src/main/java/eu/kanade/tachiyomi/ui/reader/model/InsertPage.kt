package eu.kanade.tachiyomi.ui.reader.model

class InsertPage(val parent: ReaderPage) : ReaderPage(parent.index, parent.url, parent.imageUrl) {

    override var chapter: ReaderChapter = parent.chapter

    init {
        status = State.READY
        originalStream = parent.originalStream
        translatedStream = parent.translatedStream
        showTranslatedImage = parent.showTranslatedImage
        sourceFileName = parent.sourceFileName
        translationStorageKey = parent.translationStorageKey
        // TachiyomiAT: copy the PageTranslation so the split-page half also gets
        // the text overlay — refreshTranslation() gates the overlay on
        // page.translation != null, and without this the InsertPage half of a
        // wide page never shows translated text.
        translation = parent.translation
    }
}
