package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private var pages: PersistentMap<String, PageTranslation> = persistentMapOf()
    private val _state = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())

    val state: StateFlow<Map<String, PageTranslation>> = _state.asStateFlow()

    // TachiyomiAT: chapter-level term→target glossary for AI-translator
    // continuity (names/places rendered consistently across chunks and across
    // batch resume). Persisted to a sibling JSON file (additive; a decode or
    // write failure degrades to empty = today's behaviour). Advisory only.
    @Volatile
    private var glossary: Map<String, String> = emptyMap()

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dirty = false
    private var persistJob: Job? = null

    /**
     * TachiyomiAT: lifecycle flag set by [markDefunct] when the store is evicted
     * by [TranslationManager.unregisterActiveTranslationStore] (on delete /
     * chapter change). Once defunct, every mutator (updatePage / replaceAll /
     * preRegisterPages / clearTransientQueuePages) becomes a no-op and logs at
     * WARN. This is the belt-and-suspenders for the delete-then-retranslate race:
     * a batch worker that was mid-uncancellable ONNX when cancel() was requested
     * may still reach a suspension point AFTER its store was evicted and the
     * on-disk file + companion images were deleted. Without this guard it would
     * recreate the deleted file or strand a page at RUNNING on a store the reader
     * no longer observes — exactly the "original image + stuck spinner" symptom.
     * Volatile because it is written under mutex by markDefunct but read by
     * mutators without re-entering the lock.
     */
    @Volatile
    private var defunct = false

    fun markDefunct() {
        defunct = true
    }

    val isDefunct: Boolean
        get() = defunct

    internal var persistCount = 0
        private set

    init {
        pages = initialPages.toPersistentMap()
        _state.value = snapshotPages()
    }

    suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) {
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store updatePage rejected: store is defunct pageKey=$pageKey"
            }
            return
        }
        mutex.withLock {
            val previous = pages[pageKey]
            val updated = update(pages[pageKey]).apply {
                sourceFileName = sourceFileName ?: pageKey
                updatedAt = System.currentTimeMillis()
            }
            pages = pages.put(pageKey, updated)
            if (shouldPersistUpdate(previous, updated)) {
                schedulePersist()
            }
            // Build a snapshot copy so MutableStateFlow always emits — even when
            // callers mutate a previously emitted PageTranslation in place.
            _state.value = snapshotPages()
        }
    }

    suspend fun deletePage(pageKey: String) {
        if (defunct) return
        mutex.withLock {
            if (pages.containsKey(pageKey)) {
                pages = pages.remove(pageKey)
                schedulePersist()
                _state.value = snapshotPages()
            }
        }
    }

    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) {
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store replaceAll rejected: store is defunct"
            }
            return
        }
        mutex.withLock {
            pages = updatedPages.toPersistentMap()
            dirty = false
            persistLocked()
            _state.value = snapshotPages()
        }
    }

    /**
     * Registers the full ordered page set for a chapter before OCR starts.
     *
     * These placeholders are intentionally memory-only: they let progress UI show
     * the chapter total immediately, but they do not create/dirty the on-disk
     * translation file until real OCR/inpaint/translation work lands.
     */
    suspend fun preRegisterPages(pageKeys: List<String>) {
        if (pageKeys.isEmpty()) return
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store preRegisterPages rejected: store is defunct"
            }
            return
        }
        mutex.withLock {
            var changed = false
            pageKeys.forEach { pageKey ->
                if (!pages.containsKey(pageKey)) {
                    pages = pages.put(pageKey, PageTranslation(sourceFileName = pageKey))
                    changed = true
                }
            }
            if (changed) {
                _state.value = snapshotPages()
            }
        }
    }

    /**
     * Clears queue-like entries after a user cancel/auto-window replacement.
     * Pages with a rendered/cleaned result are kept; transient running, pending,
     * cancelled, and failed entries without output are reset so the reader queue
     * reflects only the next active window.
     */
    suspend fun clearTransientQueuePages(reason: String? = null) {
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store clearTransientQueuePages rejected: store is defunct"
            }
            return
        }
        mutex.withLock {
            var changed = false
            val now = System.currentTimeMillis()
            pages = pages.mapValues { (_, page) ->
                if (page.hasRenderedResult || !page.isQueueVisibleTransient()) {
                    page
                } else {
                    changed = true
                    page.copy(
                        ocrStatus = page.ocrStatus.cancelIfTransient(),
                        translationStatus = page.translationStatus.cancelIfTransient(),
                        inpaintStatus = page.inpaintStatus.cancelIfTransient(),
                        renderStatus = page.renderStatus.cancelIfTransient(),
                        errorMessage = reason,
                        updatedAt = now,
                    )
                }
            }.toPersistentMap()
            if (changed) {
                dirty = false
                persistLocked()
                _state.value = snapshotPages()
            }
        }
    }

    private fun snapshotPages(): Map<String, PageTranslation> =
        pages.entries.associate { (key, page) -> key to page.copy() }

    // ── Glossary (chapter-level term continuity) ─────────────────────────────

    fun glossarySnapshot(): Map<String, String> = glossary

    /**
     * All translated (source => target) pairs in the chapter so far — used to
     * (re)build the glossary. Reads the in-memory pages map (no I/O).
     */
    fun translatedPairs(): List<Pair<String, String>> =
        pages.values.flatMap { page ->
            page.blocks.mapNotNull { block ->
                val s = block.text.trim()
                val t = block.translation.trim()
                if (s.isBlank() || t.isBlank() || t == s) null else s to t
            }
        }

    suspend fun updateGlossary(updated: Map<String, String>) {
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store updateGlossary rejected: store is defunct"
            }
            return
        }
        mutex.withLock {
            glossary = updated
            persistGlossaryLocked()
        }
    }

    private fun loadGlossary() {
        val file = existingGlossaryFile() ?: return
        runCatching {
            file.openInputStream().use { input ->
                glossary = Json.decodeFromStream<Map<String, String>>(input)
            }
        }.onFailure {
            glossary = emptyMap()
        }
    }

    private fun persistGlossaryLocked() {
        val file = ensureGlossaryFile() ?: return
        val snapshot = glossary
        runCatching {
            file.openOutputStream().use { output ->
                Json.encodeToStream(snapshot, output)
            }
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "Failed to persist glossary; in-memory state retained" }
        }
    }

    private fun glossaryName(tf: UniFile): String =
        ((tf.name ?: "translation").substringBeforeLast('.')) + ".glossary.json"

    private fun existingGlossaryFile(): UniFile? {
        val tf = translationFile ?: return null
        val parent = tf.parentFile ?: return null
        return parent.findFile(glossaryName(tf))
    }

    private fun ensureGlossaryFile(): UniFile? {
        if (translationFile == null) {
            translationFile = fileCreator?.invoke()
        }
        val tf = translationFile ?: return null
        val parent = tf.parentFile ?: return null
        val name = glossaryName(tf)
        return parent.findFile(name) ?: runCatching { parent.createFile(name) }.getOrNull()
    }

    private fun PageTranslation.isQueueVisibleTransient(): Boolean {
        return ocrStatus.isQueueTransient() ||
            translationStatus.isQueueTransient() ||
            inpaintStatus.isQueueTransient() ||
            renderStatus.isQueueTransient()
    }

    private fun String.isQueueTransient(): Boolean {
        return this == StageStatus.PENDING ||
            this == StageStatus.RUNNING ||
            this == StageStatus.FAILED ||
            this == StageStatus.CANCELLED
    }

    private fun String.cancelIfTransient(): String {
        return if (isQueueTransient()) StageStatus.CANCELLED else this
    }

    internal fun shouldPersistUpdate(previous: PageTranslation?, updated: PageTranslation): Boolean {
        // Persist only states that represent DURABLE progress worth surviving a
        // chapter reopen — not transient placeholders. Each rule below maps to
        // real work the pipeline performed; anything else (RUNNING/PENDING/
        // CANCELLED with no blocks, no image, no failure) is a transient
        // bookkeeping update that only needs to live in the in-memory StateFlow
        // (which the reader observes live) and must NOT hit disk.
        //
        // Why this matters: the stranded-page sweep
        // (ReaderViewModel.sweepStrandedPageStatus) runs on every chapter open
        // and writes exactly the placeholder shape — CANCELLED stages plus an
        // explanatory errorMessage, with no blocks and no rendered image — via
        // updatePage. The previous logic (`errorMessage != null` -> persist, and
        // `return !isStageRunning` -> persist any non-running state) saved those
        // placeholders to the translation JSON, where — under the old
        // `pages.isNotEmpty()` translated-state check — a single such entry made
        // the chapter falsely read as fully TRANSLATED forever after. Note that
        // the explicit cancel snapshot path clearTransientQueuePages() writes
        // via persistLocked() directly and bypasses this gate, so legitimate
        // user cancels are still recorded on disk.
        // 1. A final rendered/displayable image (the actual translation output).
        if (updated.hasRenderedResult) return true
        // 2. Recognized text blocks (real OCR work done).
        if (updated.blocks.isNotEmpty()) return true
        // 3. An inpainted cleaned image (real inpaint work; lets a later render
        //    resume without redoing the neural pass).
        if (updated.cleanedImageName != null) return true
        // 4. Stage failures — persisted so retry-exhaustion bookkeeping
        //    (hasExhaustedRetries) survives a reopen and the scheduler can skip
        //    permanently-failing pages instead of re-attempting them forever.
        if (updated.isStageFailed) return true
        // 5. Transition away from a previously-rendered result (e.g. a forced
        //    retry cleared the image) so the reader stops showing the stale one.
        if (previous?.hasRenderedResult == true && !updated.hasRenderedResult) return true
        // Transient placeholder (RUNNING/PENDING/CANCELLED with no content and
        // no failure): keep it in memory only.
        return false
    }

    private fun persistLocked() {
        persistCount++
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
            // TachiyomiAT: snapshot the persistent map into a plain Map<String,
            // PageTranslation> before encoding. `pages` is a kotlinx.collections
            // `PersistentMap`; serializing it directly makes kotlinx.serialization
            // treat PersistentMap polymorphically and then fail at runtime with
            // "Serializer for subclass 'PersistentOrderedMap' is not found in the
            // polymorphic scope of 'PersistentMap'" — which broke EVERY persist
            // (and the matching open() decode below) so no translation.json was
            // ever written and every reopen started empty. The persistent map is
            // an in-memory structure; only its plain contents belong on disk.
            val snapshot: Map<String, PageTranslation> = pages.toMap()
            val parent = target.parentFile
            if (parent == null) {
                // No parent (e.g. a single-document URI): fall back to a direct
                // truncating write. Atomicity isn't achievable without a sibling
                // location, so prefer liveness over corruption-risk here.
                target.openOutputStream().use { output -> Json.encodeToStream(snapshot, output) }
                return
            }
            val tempFile = parent.createFile(TEMP_FILE_NAME)
            if (tempFile == null) {
                target.openOutputStream().use { output -> Json.encodeToStream(snapshot, output) }
                return
            }
            try {
                tempFile.openOutputStream().use { output -> Json.encodeToStream(snapshot, output) }
            } catch (e: Exception) {
                try {
                    tempFile.delete()
                } catch (_: Exception) {}
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
                    try {
                        tempFile.delete()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to persist translation store; in-memory state retained" }
        }
    }

    suspend fun flush() {
        mutex.withLock {
            if (dirty) {
                dirty = false
                persistLocked()
            }
        }
    }

    fun close() {
        persistScope.cancel()
    }

    private fun schedulePersist() {
        if (dirty) return
        dirty = true
        persistJob = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            mutex.withLock {
                if (dirty) {
                    dirty = false
                    persistLocked()
                }
            }
            persistJob = null
        }
    }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 250L

        /**
         * Sibling temp file used by [persistLocked] for the write-temp-then-
         * rename atomicity pattern. It is created in the same directory as the
         * target translation file and renamed over it once encoding completes.
         */
        private const val TEMP_FILE_NAME = "translation.tmp"

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
            return ChapterTranslationStore(translationFile, fileCreator = null, existing).also {
                it.loadGlossary()
            }
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