package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.TranslationBlock
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
    // Mutable so persistLocked() caches the file materialized by fileCreator on
    // first write; as `val` it re-invoked fileCreator on every persist, leaking
    // orphaned files and splitting one translation across multiple documents.
    private var translationFile: UniFile?,
    private val fileCreator: (() -> UniFile)?,
    initialPages: Map<String, PageTranslation> = emptyMap(),
) {
    private val mutex = Mutex()
    private var pages: PersistentMap<String, PageTranslation> = persistentMapOf()
    private val _state = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())
    @Volatile
    private var generation = 0L
    private var nextPageVersion = 0L

    val state: StateFlow<Map<String, PageTranslation>> = _state.asStateFlow()

    data class PageSnapshot(
        val page: PageTranslation?,
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>,
    )

    data class PatchPrecondition(
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>? = null,
    )

    sealed interface PatchResult {
        data class Accepted(val snapshot: PageSnapshot) : PatchResult
        data class Rejected(val reason: String) : PatchResult
    }

    private class GenerationContext(
        val store: ChapterTranslationStore,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<GenerationContext>
    }

    // TachiyomiAT: chapter-level term→target glossary for cross-chunk translator
    // continuity. Persisted to sibling JSON (additive; failure degrades to empty).
    @Volatile
    private var glossary: Map<String, String> = emptyMap()

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dirty = false
    private var glossaryDirty = false
    private var persistJob: Job? = null

    /**
     * TachiyomiAT: set by [markDefunct] when the store is evicted
     * ([TranslationManager.unregisterActiveTranslationStore], on delete / chapter
     * change). Once defunct, every mutator becomes a no-op and logs at WARN. This
     * guards the delete-then-retranslate race: a batch worker mid-uncancellable
     * ONNX when cancel() was requested can still reach a suspension point AFTER
     * its store was evicted and the on-disk file + images deleted; without this
     * guard it would recreate the deleted file or strand a page at RUNNING.
     * Volatile: written under mutex by markDefunct, read by mutators lock-free.
     */
    @Volatile
    private var defunct = false

    fun markDefunct() {
        defunct = true
        generation++
        logcat(LogPriority.WARN) { "TachiyomiAT store marked defunct: generation=$generation" }
    }

    val isDefunct: Boolean
        get() = defunct

    internal var persistCount = 0
        private set

    init {
        initialPages.forEach { (pageKey, page) ->
            val version = nextVersion()
            pages = pages.put(pageKey, page.detachedCopy().apply {
                sourceFileName = sourceFileName ?: pageKey
                runGeneration = generation
                pageVersion = version
            })
        }
        _state.value = snapshotPages()
    }

    suspend fun snapshot(pageKey: String): PageSnapshot = mutex.withLock {
        snapshotLocked(pageKey)
    }

    suspend fun beginGeneration(reason: String): Long = mutex.withLock {
        generation++
        logcat(LogPriority.INFO) { "TachiyomiAT store generation advanced: generation=$generation reason=$reason" }
        generation
    }

    suspend fun invalidateGeneration(reason: String): Long = beginGeneration(reason)

    suspend fun <T> withGeneration(generation: Long, block: suspend () -> T): T =
        withContext(GenerationContext(this, generation)) { block() }

    suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (defunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            val current = pages[pageKey]
            val rejection = when {
                expected.generation != generation -> "generation expected=${expected.generation} actual=$generation"
                expected.pageVersion != (current?.pageVersion ?: 0L) ->
                    "pageVersion expected=${expected.pageVersion} actual=${current?.pageVersion ?: 0L}"
                expected.blockFingerprints != null && expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                    "block fingerprint changed"
                else -> null
            }
            if (rejection != null) {
                rejected(pageKey, description, rejection)
            } else {
                val candidate = try {
                    patch(current?.detachedCopy())
                } catch (failure: IllegalArgumentException) {
                    return@withLock rejected(
                        pageKey,
                        description,
                        failure.message ?: failure::class.java.simpleName,
                    )
                } catch (failure: IllegalStateException) {
                    return@withLock rejected(
                        pageKey,
                        description,
                        failure.message ?: failure::class.java.simpleName,
                    )
                }
                val updated = ownedPage(pageKey, candidate)
                pages = pages.put(pageKey, updated)
                publishLocked(current, updated)
                PatchResult.Accepted(snapshotLocked(pageKey))
            }
        }
    }

    suspend fun patchBlock(
        pageKey: String,
        blockIndex: Int,
        expected: PatchPrecondition,
        expectedBlockFingerprint: String,
        expectedTranslation: String? = null,
        expectedUserEditedAt: Long? = null,
        description: String,
        patch: (eu.kanade.translation.model.TranslationBlock) -> eu.kanade.translation.model.TranslationBlock,
    ): PatchResult = patchPage(pageKey, expected, description) { page ->
        requireNotNull(page) { "page missing" }
        val current = page.blocks.getOrNull(blockIndex)
            ?: throw IllegalStateException("block index $blockIndex missing")
        check(current.stableFingerprint() == expectedBlockFingerprint) { "block fingerprint changed" }
        if (expectedTranslation != null) check(current.translation == expectedTranslation) { "block translation changed" }
        check(current.userEditedAt == expectedUserEditedAt) { "block edit timestamp changed" }
        page.apply { blocks[blockIndex] = patch(current.detachedCopy()).detachedCopy() }
    }

    /** Apply a stage patch while checking only the identities owned by that stage. */
    suspend fun applyStagePatch(
        patch: StagePatch,
        description: String,
    ): StagePatchResult {
        if (defunct) return rejectedStage(patch.pageKey, description, "store is defunct")
        return mutex.withLock {
            val current = pages[patch.pageKey]
            when (patch) {
                is StagePatch.Translation -> mergeTranslationLocked(current, patch.value, description)
                is StagePatch.Inpaint -> mergeInpaintLocked(current, patch.value, description)
                is StagePatch.Render -> mergeRenderLocked(current, patch.value, description)
                is StagePatch.Revision -> mergeRevisionLocked(current, patch.value, description)
            }
        }
    }

    suspend fun mergeTranslation(
        patch: TranslationStagePatch,
        description: String = "translation stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Translation(patch), description)

    suspend fun mergeInpaint(
        patch: InpaintStagePatch,
        description: String = "inpaint stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Inpaint(patch), description)

    suspend fun mergeRender(
        patch: RenderStagePatch,
        description: String = "render stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Render(patch), description)

    suspend fun mergeRevision(
        patch: RevisionStagePatch,
        description: String = "revision stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Revision(patch), description)

    private fun mergeTranslationLocked(
        current: PageTranslation?,
        patch: TranslationStagePatch,
        description: String,
    ): StagePatchResult {
        val identityRejection = stageIdentityRejection(current, patch.generation)
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, patch.expectedSourceTexts)
        if (identityRejection != null) {
            return rejectedStage(patch.pageKey, description, identityRejection)
        }
        val page = current!!.detachedCopy()
        val applied = mutableListOf<Int>()
        val rejectedTargets = mutableListOf<String>()
        patch.blocks.forEach { target ->
            val block = page.blocks.getOrNull(target.blockIndex)
            val rejection = when {
                block == null -> "block index ${target.blockIndex} missing"
                block.ocrFingerprint() != target.expectedOcrFingerprint ->
                    "block ${target.blockIndex} OCR identity changed"
                block.text != target.expectedSourceText ->
                    "block ${target.blockIndex} source changed"
                block.translation != target.expectedTranslation ->
                    "block ${target.blockIndex} translation changed"
                block.userEditedAt != target.expectedUserEditedAt ->
                    "block ${target.blockIndex} user edit changed"
                block.needsRevision != target.expectedNeedsRevision ->
                    "block ${target.blockIndex} revision flag changed"
                else -> null
            }
            if (rejection != null) {
                rejectedTargets += rejection
            } else {
                block!!.translation = target.translation
                block.needsRevision = target.needsRevision
                applied += target.blockIndex
            }
        }
        if (applied.isEmpty()) {
            return rejectedStage(
                patch.pageKey,
                description,
                rejectedTargets.firstOrNull() ?: "no translation targets",
            )
        }
        page.translationStatus = patch.translationStatus
        page.errorMessage = patch.errorMessage
        val updated = ownedPage(patch.pageKey, page)
        pages = pages.put(patch.pageKey, updated)
        publishLocked(current, updated)
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), applied)
    }

    private fun mergeInpaintLocked(
        current: PageTranslation?,
        patch: InpaintStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(current, patch.generation)
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, null)
            ?: current?.takeUnless { it.inpaintMaskFingerprint() == patch.expectedMaskFingerprint }
                ?.let { "inpaint mask identity changed" }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val updated = ownedPage(patch.pageKey, current!!.detachedCopy().apply {
            cleanedImageName = patch.cleanedImageName
            inpaintRevision = patch.inpaintRevision
            inpaintingModeUsed = patch.inpaintingModeUsed
            inpaintStatus = patch.inpaintStatus
            errorMessage = patch.errorMessage
        })
        pages = pages.put(patch.pageKey, updated)
        publishLocked(current, updated)
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey))
    }

    private fun mergeRenderLocked(
        current: PageTranslation?,
        patch: RenderStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(current, patch.generation)
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, null)
            ?: current?.takeUnless { it.cleanedImageName == patch.expectedCleanedImageName }
                ?.let { "cleaned image identity changed" }
            ?: current?.takeUnless { it.inpaintRevision == patch.expectedInpaintRevision }
                ?.let { "inpaint revision changed" }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val page = current!!.detachedCopy()
        patch.blocks.forEach { target ->
            val block = page.blocks.getOrNull(target.blockIndex)
            if (block == null) {
                return rejectedStage(patch.pageKey, description, "block index ${target.blockIndex} missing")
            }
            if (block.stableFingerprint() != target.expectedBlockFingerprint) {
                return rejectedStage(patch.pageKey, description, "render block identity changed")
            }
        }
        patch.blocks.forEach { target ->
            val block = page.blocks[target.blockIndex]
            block.textColor = target.textColor
            block.strokeColor = target.strokeColor
            block.strokeWidth = target.strokeWidth
        }
        page.renderStatus = patch.renderStatus
        page.errorMessage = patch.errorMessage
        val updated = ownedPage(patch.pageKey, page)
        pages = pages.put(patch.pageKey, updated)
        publishLocked(current, updated)
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), patch.blocks.map { it.blockIndex })
    }

    private fun mergeRevisionLocked(
        current: PageTranslation?,
        patch: RevisionStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(current, patch.generation)
            ?: current?.blocks?.getOrNull(patch.blockIndex)?.let { block ->
                when {
                    block.stableFingerprint() != patch.expectedBlockFingerprint -> "revision block identity changed"
                    block.text != patch.expectedSourceText -> "revision source changed"
                    block.translation != patch.expectedDraft -> "revision draft changed"
                    block.needsRevision != patch.expectedNeedsRevision -> "revision flag changed"
                    block.userEditedAt != patch.expectedUserEditedAt -> "revision user edit changed"
                    patch.replacementTranslation != null && patch.replacementTranslation.isBlank() ->
                        "blank revision replacement"
                    else -> null
                }
            } ?: "block index ${patch.blockIndex} missing"
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val updated = ownedPage(patch.pageKey, current!!.detachedCopy().apply {
            val block = blocks[patch.blockIndex]
            patch.replacementTranslation?.let { block.translation = it }
            block.needsRevision = patch.needsRevision
        })
        pages = pages.put(patch.pageKey, updated)
        publishLocked(current, updated)
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), listOf(patch.blockIndex))
    }

    private fun stageIdentityRejection(page: PageTranslation?, expectedGeneration: Long): String? = when {
        expectedGeneration != generation -> "generation expected=$expectedGeneration actual=$generation"
        page == null -> "page missing"
        else -> null
    }

    private fun ocrIdentityRejection(
        page: PageTranslation?,
        expectedFingerprints: List<String>,
        expectedSources: List<String>?,
    ): String? = when {
        page == null -> "page missing"
        page.ocrBlockFingerprints() != expectedFingerprints -> "OCR block identity changed"
        expectedSources != null && page.blocks.map { it.text } != expectedSources -> "OCR source changed"
        else -> null
    }

    private fun rejectedStage(pageKey: String, description: String, reason: String): StagePatchResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT stage patch rejected: pageKey=$pageKey generation=$generation " +
                "operation=$description reason=$reason"
        }
        return StagePatchResult.Rejected(reason)
    }

    suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) {
        if (defunct) {
            rejected(pageKey, "updatePage", "store is defunct")
            return
        }
        val context = currentCoroutineContext()[GenerationContext]
        mutex.withLock {
            if (context?.store === this && context.generation != generation) {
                rejected(
                    pageKey,
                    "updatePage",
                    "generation expected=${context.generation} actual=$generation",
                )
                return@withLock
            }
            val previous = pages[pageKey]
            // Legacy callers receive detached input; the store always adopts a
            // second detached copy so neither input nor returned objects remain live.
            val updated = ownedPage(pageKey, update(previous?.detachedCopy()))
            pages = pages.put(pageKey, updated)
            publishLocked(previous, updated)
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
            pages = persistentMapOf()
            updatedPages.forEach { (pageKey, page) ->
                pages = pages.put(pageKey, ownedPage(pageKey, page))
            }
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
                    pages = pages.put(pageKey, ownedPage(pageKey, PageTranslation(sourceFileName = pageKey)))
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
            generation++
            logcat(LogPriority.INFO) {
                "TachiyomiAT store generation invalidated: generation=$generation reason=${reason ?: "clear transient queue"}"
            }
            var changed = false
            val now = System.currentTimeMillis()
            pages = pages.mapValues { (_, page) ->
                if (page.hasRenderedResult || !page.isQueueVisibleTransient()) {
                    page
                } else {
                    changed = true
                    ownedPage(
                        page.sourceFileName.orEmpty(),
                        page.copy(
                            ocrStatus = page.ocrStatus.cancelIfTransient(),
                            translationStatus = page.translationStatus.cancelIfTransient(),
                            inpaintStatus = page.inpaintStatus.cancelIfTransient(),
                            renderStatus = page.renderStatus.cancelIfTransient(),
                            errorMessage = reason,
                            updatedAt = now,
                        ),
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

    private fun nextVersion(): Long = ++nextPageVersion

    private fun ownedPage(pageKey: String, candidate: PageTranslation): PageTranslation =
        candidate.detachedCopy().apply {
            sourceFileName = sourceFileName ?: pageKey
            updatedAt = System.currentTimeMillis()
            runGeneration = generation
            pageVersion = nextVersion()
        }

    private fun publishLocked(previous: PageTranslation?, updated: PageTranslation) {
        if (shouldPersistUpdate(previous, updated)) schedulePersist()
        _state.value = snapshotPages()
    }

    private fun snapshotLocked(pageKey: String): PageSnapshot {
        val page = pages[pageKey]
        return PageSnapshot(
            page = page?.detachedCopy(),
            generation = generation,
            pageVersion = page?.pageVersion ?: 0L,
            blockFingerprints = page?.blockFingerprints().orEmpty(),
        )
    }

    private fun rejected(pageKey: String, description: String, reason: String): PatchResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT store patch rejected: pageKey=$pageKey generation=$generation " +
                "operation=$description reason=$reason"
        }
        return PatchResult.Rejected(reason)
    }

    private fun snapshotPages(): Map<String, PageTranslation> =
        pages.entries.associate { (key, page) -> key to page.detachedCopy() }

    fun glossarySnapshot(): Map<String, String> = glossary.toMap()

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
            if (glossary != updated) {
                glossary = updated.toMap()
                glossaryDirty = true
                schedulePersist(markPageDirty = false)
            }
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

    private fun persistGlossaryLocked(): Boolean {
        val file = ensureGlossaryFile()
        if (file == null) {
            logcat(LogPriority.ERROR) { "TachiyomiAT glossary persistence failed: reason=file unavailable" }
            return false
        }
        val snapshot = glossary
        return runCatching {
            file.openOutputStream().use { output ->
                Json.encodeToStream(snapshot, output)
            }
        }.onFailure { error ->
            logcat(LogPriority.ERROR, error) { "TachiyomiAT glossary persistence failed: reason=write error" }
        }.isSuccess
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
        // Persist only DURABLE progress worth surviving a chapter reopen, not
        // transient placeholders; transient bookkeeping stays in-memory (the
        // reader observes the live StateFlow). Critical because the stranded-page
        // sweep (ReaderViewModel.sweepStrandedPageStatus) writes exactly the
        // placeholder shape — CANCELLED stages + errorMessage, no blocks, no
        // image — via updatePage. The old logic (errorMessage != null -> persist,
        // and `!isStageRunning` -> persist) saved those placeholders to JSON,
        // where under the old pages.isNotEmpty() check a single such entry made
        // the chapter falsely read TRANSLATED forever. clearTransientQueuePages()
        // bypasses this gate via persistLocked(), so legitimate cancels still hit disk.
        // 1. A final rendered image (actual translation output).
        if (updated.hasRenderedResult) return true
        // 2. Recognized text blocks (real OCR work).
        if (updated.blocks.isNotEmpty()) return true
        // 3. Cleaned image; lets a later render resume without redoing the neural pass.
        if (updated.cleanedImageName != null) return true
        // 4. Stage failures so retry-exhaustion (hasExhaustedRetries) survives
        //    reopen and the scheduler can skip permanently-failing pages.
        if (updated.isStageFailed) return true
        // 5. Transition away from a previously-rendered result (e.g. forced retry
        //    cleared the image) so the reader stops showing the stale one.
        if (previous?.hasRenderedResult == true && !updated.hasRenderedResult) return true
        // Transient placeholder (RUNNING/PENDING/CANCELLED with no content and no
        // failure): keep it in memory only.
        return false
    }

    private fun persistLocked() {
        persistCount++
        // Resolve the backing file lazily on first write. Opening a chapter with
        // no existing file leaves translationFile null; materializing it on first
        // real write avoids leaving empty files on disk (which previously made
        // isChapterTranslated report false TRANSLATED on reopen).
        if (translationFile == null) {
            translationFile = fileCreator?.invoke()
        }
        val target = translationFile ?: return
        // SAF-backed (UniFile): a stale/revoked tree URI or moved folder can make
        // openOutputStream() throw IOException. In-memory state is still correct
        // and the reader gets live StateFlow updates, so a write failure must NOT
        // kill the translation — log and continue (read path degrades the same way).
        //
        // Write atomically: openOutputStream(false) truncates BEFORE encoding, so
        // a throw or low-memory kill mid-write leaves a truncated, unreadable store
        // that on next open() wipes every page. Encode into a sibling temp and
        // rename over the target only once bytes are fully flushed; a crash then
        // leaves the previous good file untouched (same pattern as the Downloader).
        try {
            // Snapshot PersistentMap into a plain Map before encoding. Serializing
            // it directly makes kotlinx.serialization treat PersistentMap
            // polymorphically and fail at runtime ("subclass 'PersistentOrderedMap'
            // not found"), breaking every persist and reopen.
            val snapshot: Map<String, PageTranslation> = pages.toMap()
            val parent = target.parentFile
            if (parent == null) {
                // Single-document URI (no sibling location): fall back to a direct
                // truncating write — prefer liveness over corruption-risk.
                target.openOutputStream().use { output -> Json.encodeToStream(snapshot, output) }
                return
            }
            val tempFile = parent.createFile(tempFileNameFor(target))
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
            // SAF DocumentsContract.renameDocument won't overwrite an existing name
            // on most providers, so delete target first then rename. The
            // delete-then-rename window is sub-millisecond; open() degrades a
            // missing/corrupt file to empty, so the reader keeps working. If the
            // rename still fails, fall back to a truncating copy of the already-
            // encoded bytes so in-memory state isn't lost.
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
            flushDirtyLocked()
        }
    }

    private fun flushDirtyLocked() {
        if (dirty) {
            dirty = false
            persistLocked()
        }
        if (glossaryDirty) {
            // Keep dirty on failure so a later completion/close flush can retry.
            if (persistGlossaryLocked()) glossaryDirty = false
        }
    }

    suspend fun readSummary(): ChapterTranslationSummary? = mutex.withLock {
        translationFile?.let(::ChapterTranslationSummaryStore)?.read()
    }

    /** Publishes the completion sidecar only after the page snapshot is durable. */
    suspend fun publishSummary(summary: ChapterTranslationSummary): Boolean = mutex.withLock {
        flushDirtyLocked()
        if (translationFile == null) {
            translationFile = fileCreator?.invoke()
        }
        val file = translationFile
        if (file == null) {
            logcat(LogPriority.ERROR) { "TachiyomiAT chapter summary publication failed: reason=translation file unavailable" }
            return@withLock false
        }
        ChapterTranslationSummaryStore(file).publish(summary)
    }

    suspend fun closeAndFlush() {
        mutex.withLock { flushDirtyLocked() }
        persistScope.cancel()
    }

    fun close() {
        persistScope.launch {
            mutex.withLock { flushDirtyLocked() }
        }.invokeOnCompletion {
            persistScope.cancel()
        }
    }

    private fun schedulePersist(markPageDirty: Boolean = true) {
        if (markPageDirty) dirty = true
        if (persistJob?.isActive == true) return
        persistJob = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            mutex.withLock { flushDirtyLocked() }
            persistJob = null
        }
    }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 250L

        /** Fallback name for the rename target if [UniFile.getName] is null. */
        private const val DEFAULT_FILE_NAME = "translation.json"

        /**
         * Creates a sibling temp-file name scoped to its target translation file.
         * This avoids concurrent stores overwriting a shared temporary document.
         */
        private fun tempFileNameFor(target: UniFile): String =
            "${target.name ?: DEFAULT_FILE_NAME}.tmp"

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
