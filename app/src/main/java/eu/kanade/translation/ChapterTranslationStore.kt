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
    private val translationFile: UniFile,
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
        translationFile.openOutputStream().use { output ->
            Json.encodeToStream(pages, output)
        }
    }

    companion object {
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
            return ChapterTranslationStore(translationFile, existing)
        }
    }
}
