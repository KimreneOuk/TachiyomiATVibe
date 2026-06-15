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
    // TachiyomiAT: mutable so persistLocked() can cache the file materialized
    // by fileCreator on the first lazy write. Previously this was `val`, which
    // meant the lazy store re-invoked fileCreator (creating a NEW translation
    // file) on every single persist — leaking orphaned files and splitting the
    // translation across multiple documents.
    private var translationFile: UniFile?,
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
        // TRANSLATED state on reopen). Cache the resolved file so subsequent
        // writes reuse it instead of creating a new one each time.
        if (translationFile == null) {
            translationFile = fileCreator?.invoke()
        }
        val target = translationFile ?: return
        // The translation store is SAF-backed (UniFile). A stale/revoked tree
        // URI or moved folder can make openOutputStream() throw IOException.
        // The in-memory state is still correct and the reader gets live updates
        // via the StateFlow below, so a write failure must NOT kill the whole
        // translation: log it and continue. The read path already degrades the
        // same way (see open()).
        //
        // TachiyomiAT: write atomically. openOutputStream(false) truncates the
        // target BEFORE encoding, so if Json.encodeToStream throws or the
        // process is killed mid-write (OOM, ANR, low-memory kill), the file is
        // left with a valid JSON prefix followed by NOTHING — a truncated,
        // unreadable store that on next open() silently wipes every page we
        // had already translated. Instead, encode into a sibling temp file and
        // rename it over the target only once the bytes are fully flushed; a
        // crash mid-write then leaves the previous good file untouched. This is
        // the same write-temp-then-rename pattern the Downloader already uses.
        try {
            val parent = target.parentFile
            if (parent == null) {
                // No parent (e.g. a single-document URI): fall back to a direct
                // truncating write. Atomicity isn't achievable without a sibling
                // location, so prefer liveness over corruption-risk here.
                target.openOutputStream().use { output -> Json.encodeToStream(pages, output) }
                return
            }
            val tempFile = parent.createFile(TEMP_FILE_NAME)
            if (tempFile == null) {
                target.openOutputStream().use { output -> Json.encodeToStream(pages, output) }
                return
            }
            try {
                tempFile.openOutputStream().use { output -> Json.encodeToStream(pages, output) }
            } catch (e: Exception) {
                try { tempFile.delete() } catch (_: Exception) {}
                throw e
            }
            // Replace the target with the completed temp file. SAF's
            // DocumentsContract.renameDocument refuses to overwrite an existing
            // name on most providers (no FLAG_SUPPORTS_RENAME_AND_OVERWRITE), so
            // we delete the target first, then rename the temp onto it. The
            // delete-then-rename window is sub-millisecond; if a crash lands in
            // it, open() already degrades a missing/corrupt file to empty, so the
            // reader keeps working — it just re-translates on the next run. If
            // the rename still fails (provider quirk), fall back to a truncating
            // copy of the already-encoded bytes so the in-memory state isn't lost.
            val targetName = target.name ?: DEFAULT_FILE_NAME
            val renamed = try {
                target.delete()
                tempFile.renameTo(targetName)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Atomic rename of translation store failed; falling back to copy" }
                false
            }
            if (!renamed) {
                try {
                    tempFile.openInputStream().use { input ->
                        target.openOutputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    try { tempFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to persist translation store; in-memory state retained" }
        }
    }

    companion object {
        /**
         * Sibling temp file used by [persistLocked] for the write-temp-then-
         * rename atomicity pattern. It is created in the same directory as the
         * target translation file and renamed over it once encoding completes.
         */
        private const val TEMP_FILE_NAME = ".translation.tmp"

        /** Fallback name for the rename target if [UniFile.getName] is null. */
        private const val DEFAULT_FILE_NAME = "translation.json"

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
