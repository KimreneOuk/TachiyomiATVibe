package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class ChapterTranslationStore(
    private val translationFile: UniFile?,
    private val fileCreator: (() -> UniFile)?,
    initialPages: Map<String, PageTranslation> = emptyMap(),
) {
    private val mutex = Mutex()
    private val pages = LinkedHashMap<String, PageTranslation>()
    private val _state = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())

    val state: StateFlow<Map<String, PageTranslation>> = _state.asStateFlow()

    init {
        pages.putAll(initialPages)
        _state.value = pages.toMap()
    }

    suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) {
        mutex.withLock {
            val updated = update(pages[pageKey]).apply {
                sourceFileName = sourceFileName ?: pageKey
                updatedAt = System.currentTimeMillis()
            }
            pages[pageKey] = updated
            persistLocked()
            // Build a snapshot copy so MutableStateFlow always emits — even when
            // callers mutate a previously emitted PageTranslation in place.
            _state.value = pages.entries.associate { (key, page) -> key to page.copy() }
        }
    }

    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) {
        mutex.withLock {
            pages.clear()
            pages.putAll(updatedPages)
            persistLocked()
            _state.value = pages.entries.associate { (key, page) -> key to page.copy() }
        }
    }

    private fun persistLocked() {
        // Resolve the backing file lazily. When the store was opened for a
        // chapter with no existing translation file, translationFile is null and
        // fileCreator materializes it on the FIRST real write. This avoids
        // leaving empty translation files on disk for chapters that were merely
        // opened (which previously caused isChapterTranslated to report a false
        // TRANSLATED state on reopen).
        val target = translationFile ?: fileCreator?.invoke() ?: return
        // The translation store is SAF-backed (UniFile). A stale/revoked tree
        // URI or moved folder can make openOutputStream() throw IOException.
        // The in-memory state is still correct and the reader gets live updates
        // via the StateFlow below, so a write failure must NOT kill the whole
        // translation: log it and continue. The read path already degrades the
        // same way (see open()).
        try {
            target.openOutputStream().use { output ->
                Json.encodeToStream(pages, output)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to persist translation store; in-memory state retained" }
        }
    }

    companion object {
        /** Opens an existing on-disk translation file into a store. */
        fun open(translationFile: UniFile): ChapterTranslationStore {
            val existing = if (translationFile.exists()) {
                try {
                    Json.decodeFromStream<Map<String, PageTranslation>>(translationFile.openInputStream())
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to load existing translation store; starting empty" }
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            return ChapterTranslationStore(translationFile, fileCreator = null, existing)
        }

        /**
         * Creates a store whose on-disk file is created lazily on the first
         * [updatePage]/[replaceAll] write. Use this when a chapter has no
         * existing translation file yet, so that merely opening the chapter
         * does NOT leave an empty file behind.
         */
        fun lazy(fileCreator: () -> UniFile): ChapterTranslationStore =
            ChapterTranslationStore(translationFile = null, fileCreator = fileCreator, initialPages = emptyMap())
    }
}
