package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.CleanedFileState
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.LegacyChapterSnapshot
import eu.kanade.translation.artifact.LegacyPageFacts
import eu.kanade.translation.artifact.LegacySourceIdentity
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.stableFingerprint
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

class ChapterTranslationStore(
    // Mutable so persistLocked() caches the file materialized by fileCreator on
    // first write; as `val` it re-invoked fileCreator on every persist, leaking
    // orphaned files and splitting one translation across multiple documents.
    private var translationFile: UniFile?,
    private val fileCreator: (() -> UniFile)?,
    initialPages: Map<String, PageTranslation> = emptyMap(),
    private var artifactStore: ChapterArtifactStore? = null,
    initialCommittedPages: Map<String, PageTranslation> = emptyMap(),
    initialArtifactManifest: ChapterArtifactManifest? = null,
    initialRetiredCleanedImages: Map<String, Set<String>> = emptyMap(),
) {
    private val mutex = Mutex()

    @Volatile
    private var pages: PersistentMap<String, PageTranslation> = persistentMapOf()
    private val _state = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())

    /**
     * TachiyomiAT (Phase 3): the last-known-good committed display bundle per
     * page. A candidate retry may mutate the live [pages] entry freely —
     * clearing statuses, wiping the cleaned name, stranding stages — but it
     * can never hide or mutate the committed bundle observed through
     * [display]. Promotion happens atomically inside the store mutex after the
     * page reached [PageTranslation.hasRenderedResult] shape, so observers
     * never see a half-promoted combination.
     */
    @Volatile
    private var committedDisplay: PersistentMap<String, CommittedPageDisplay> = persistentMapOf()

    /** Durable artifact manifest snapshot used by the live writer bridge. */
    @Volatile
    private var artifactManifest: ChapterArtifactManifest? = initialArtifactManifest

    /**
     * Cleaned-image names retained because the superseded committed bundle
     * still displays them; drained for deletion by the pipeline after a newer
     * bundles promote. Keeping all names until a drain prevents a rapid sequence
     * of promotions from losing an earlier retained file.
     */
    private val retiredCleanedImages = ConcurrentHashMap<String, MutableSet<String>>()

    private val _display = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())

    /** Writer leases per page: one origin owns a page until it releases it. */
    private val pageLeases = ConcurrentHashMap<String, PageLeaseRecord>()
    private var nextLeaseToken = 0L

    @Volatile
    private var generation = 0L
    private var nextPageVersion = 0L

    /** Live candidate progress; readers that need display safety use [display]. */
    val state: StateFlow<Map<String, PageTranslation>> = _state.asStateFlow()

    /**
     * Committed-pointer display projection (Phase 3): each page resolves to
     * its immutable committed display bundle when one exists, otherwise the
     * live candidate entry. Candidate emissions can replace live entries but
     * never null or mutate a committed bundle.
     */
    val display: StateFlow<Map<String, PageTranslation>> = _display.asStateFlow()

    val currentGeneration: Long get() = generation

    /**
     * Frozen snapshot of the last displayable page state, plus the identity
     * that lets the store decide when a promotion supersedes an older bundle.
     */
    data class CommittedPageDisplay(
        val page: PageTranslation,
        val pageVersion: Long,
        val displayFingerprint: String,
        val promotedAtEpochMs: Long,
    )

    private data class PageLeaseRecord(
        val token: Long,
        val origin: PageWriteOrigin,
        val stage: PageStage,
        val generation: Long,
    )

    fun resetPreflight(): ChapterResetPreflight = chapterResetPreflight(state.value.values)

    data class PageSnapshot(
        val page: PageTranslation?,
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>,
        /** Lease fencing token held at snapshot time, when this writer owns the page. */
        val leaseToken: Long? = null,
        /** Artifact-manifest page version observed with this snapshot. */
        val artifactPageVersion: Long? = null,
        /** Candidate generation observed with this snapshot, when artifact authority owns it. */
        val candidateGenerationId: String? = null,
        /** Candidate dependency fingerprint observed with this snapshot. */
        val dependencyFingerprint: String? = null,
    )

    data class PatchPrecondition(
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>? = null,
        /** A late result carrying an old lease token is rejected after release. */
        val leaseToken: Long? = null,
        /** Artifact candidate generation observed by this writer. */
        val candidateGenerationId: String? = null,
        /** Artifact candidate dependency fingerprint observed by this writer. */
        val dependencyFingerprint: String? = null,
        /** Artifact-manifest page version observed by this writer. */
        val artifactPageVersion: Long? = null,
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
        persistJob?.let { pendingPersist ->
            val completed = runBlocking {
                withTimeoutOrNull(PERSIST_JOIN_TIMEOUT_MS) { pendingPersist.join() }
            }
            if (completed == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT store persist did not finish within $PERSIST_JOIN_TIMEOUT_MS ms before eviction"
                }
            }
            pendingPersist.cancel()
        }
        persistJob = null
        defunct = true
        generation++
        synchronized(pageLeases) { pageLeases.clear() }
        logcat(LogPriority.WARN) { "TachiyomiAT store marked defunct: generation=$generation" }
    }

    val isDefunct: Boolean
        get() = defunct

    internal var persistCount = 0
        private set

    init {
        initialRetiredCleanedImages.forEach { (pageKey, names) ->
            if (names.isNotEmpty()) {
                retiredCleanedImages[pageKey] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(names) }
            }
        }
        initialPages.forEach { (pageKey, page) ->
            val version = nextVersion()
            pages = pages.put(
                pageKey,
                page.detachedCopy().apply {
                    sourceFileName = sourceFileName ?: pageKey
                    runGeneration = generation
                    pageVersion = version
                },
            )
        }
        // Seed the last-known-good display pointers from durable artifact
        // snapshots first. The mutable live map may be an incomplete candidate
        // after process death; it must never become the reader authority.
        initialCommittedPages.forEach { (pageKey, page) ->
            committedDisplay = committedDisplay.put(
                pageKey,
                CommittedPageDisplay(
                    page = page.detachedCopy(),
                    pageVersion = page.pageVersion,
                    displayFingerprint = displayFingerprintOf(page),
                    promotedAtEpochMs = page.updatedAt,
                ),
            )
        }
        // Memory-only stores and legacy pages without a durable artifact
        // snapshot retain the compatibility seed from their display-ready
        // initial state.
        pages.forEach { (pageKey, page) ->
            if (committedDisplay.containsKey(pageKey)) return@forEach
            if (page.hasRenderedResult) {
                committedDisplay = committedDisplay.put(
                    pageKey,
                    CommittedPageDisplay(
                        page = page.detachedCopy(),
                        pageVersion = page.pageVersion,
                        displayFingerprint = displayFingerprintOf(page),
                        promotedAtEpochMs = page.updatedAt,
                    ),
                )
            }
        }
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
    }

    suspend fun snapshot(pageKey: String): PageSnapshot = mutex.withLock {
        snapshotLocked(pageKey)
    }

    suspend fun beginGeneration(reason: String): Long = mutex.withLock {
        generation++
        logcat(LogPriority.INFO) { "TachiyomiAT store generation advanced: generation=$generation reason=$reason" }
        generation
    }

    // ------------------------------------------------------------------
    // Phase 3 page/stage leases (lifecycle contract §12): one origin owns a
    // page at a time. A reader request on a batch-owned page attaches to the
    // batch result (observes store emissions) instead of opening a competing
    // writer, and vice versa. The lease binds the store generation and page
    // version the holder must present on every write.
    // ------------------------------------------------------------------

    suspend fun tryAcquirePageStageLease(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = mutex.withLock {
        if (defunct) return@withLock LeaseAcquisition.Denied("store is defunct", null)
        val existing = pageLeases[pageKey]
        if (existing != null && existing.origin != origin) {
            return@withLock LeaseAcquisition.Denied(
                "page owned by ${existing.origin} at stage ${existing.stage}",
                existing.origin,
            )
        }
        if (existing != null && existing.origin == origin) {
            val currentSnapshot = snapshotLocked(pageKey)
            return@withLock LeaseAcquisition.Granted(
                PageStageLease(
                    pageKey = pageKey,
                    stage = existing.stage,
                    origin = existing.origin,
                    generation = existing.generation,
                    pageVersion = currentSnapshot.pageVersion,
                    token = existing.token,
                    candidateGenerationId = currentSnapshot.candidateGenerationId,
                    dependencyFingerprint = currentSnapshot.dependencyFingerprint,
                    artifactPageVersion = currentSnapshot.artifactPageVersion,
                ),
            )
        }
        val current = pages[pageKey]
        val currentSnapshot = snapshotLocked(pageKey)
        val token = ++nextLeaseToken
        pageLeases[pageKey] = PageLeaseRecord(
            token = token,
            origin = origin,
            stage = stage,
            generation = generation,
        )
        LeaseAcquisition.Granted(
            PageStageLease(
                pageKey = pageKey,
                stage = stage,
                origin = origin,
                generation = generation,
                pageVersion = current?.pageVersion ?: 0L,
                token = token,
                candidateGenerationId = currentSnapshot.candidateGenerationId,
                dependencyFingerprint = currentSnapshot.dependencyFingerprint,
                artifactPageVersion = currentSnapshot.artifactPageVersion,
            ),
        )
    }

    suspend fun releasePageStageLease(pageKey: String, origin: PageWriteOrigin) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (pageLeases[pageKey]?.origin == origin) {
                    pageLeases.remove(pageKey)
                }
            }
        }
    }

    /** Releases every lease held by [origin]; used at batch teardown so no lease outlives its run. */
    suspend fun releaseAllPageLeases(origin: PageWriteOrigin) {
        withContext(NonCancellable) {
            mutex.withLock {
                pageLeases.values.removeAll { it.origin == origin }
            }
        }
    }

    fun pageLeaseOwner(pageKey: String): PageWriteOrigin? = synchronized(pageLeases) {
        pageLeases[pageKey]?.origin
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
                expected.artifactPageVersion != null &&
                    expected.artifactPageVersion != artifactManifest?.pages?.get(pageKey)?.pageVersion ->
                    "artifact pageVersion changed"
                expected.candidateGenerationId != null &&
                    expected.candidateGenerationId != artifactManifest?.pages?.get(pageKey)?.candidate?.generationId ->
                    "candidate generation changed"
                expected.dependencyFingerprint != null &&
                    expected.dependencyFingerprint != artifactManifest?.pages?.get(pageKey)?.candidate?.dependencyFingerprint ->
                    "candidate dependency fingerprint changed"
                expected.blockFingerprints != null &&
                    expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                    "block fingerprint changed"
                expected.leaseToken != null && pageLeases[pageKey]?.token != expected.leaseToken ->
                    "page lease token changed"
                expected.leaseToken == null && pageLeases[pageKey] != null ->
                    "page lease token required"
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
                publishLocked(current, updated, expected)
                PatchResult.Accepted(snapshotLocked(pageKey))
            }
        }
    }

    /**
     * Guarded whole-page candidate mutation for production stage writers. The
     * caller must carry the exact identity it observed before the asynchronous
     * stage began; a released/reacquired lease, page mutation, generation reset,
     * or artifact candidate replacement rejects the write before the candidate
     * bridge can publish anything durable.
     */
    suspend fun updatePageGuarded(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (defunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            val rejection = pageWriteRejection(pageKey, expected)
            if (rejection != null) {
                rejected(pageKey, description, rejection)
            } else {
                val previous = pages[pageKey]
                val updated = ownedPage(pageKey, update(previous?.detachedCopy()))
                pages = pages.put(pageKey, updated)
                publishLocked(previous, updated, expected)
                PatchResult.Accepted(snapshotLocked(pageKey))
            }
        }
    }

    /** Compatibility-shaped convenience for non-stage callers; still fenced by a snapshot. */
    suspend fun updatePageFromCurrentSnapshot(
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = updatePageGuarded(pageKey, snapshot(pageKey).toPrecondition(), description, update)

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
        if (expectedTranslation !=
            null
        ) {
            check(current.translation == expectedTranslation) { "block translation changed" }
        }
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
                is StagePatch.Ocr -> mergeOcrLocked(current, patch.value, description)
                is StagePatch.Translation -> mergeTranslationLocked(current, patch.value, description)
                is StagePatch.Inpaint -> mergeInpaintLocked(current, patch.value, description)
                is StagePatch.Render -> mergeRenderLocked(current, patch.value, description)
            }
        }
    }

    /**
     * Detection/OCR stage merge (Phase 3). The patch must carry the page
     * version and prior OCR identity observed before the native recognition
     * pass; anything that touched the page since makes the writer stale and
     * the merge is rejected, so a late recognition result can never clobber
     * newer work or drop translations merged in the meantime.
     *
     * Manual target edits are authoritative for their exact source block: a
     * new block whose OCR identity matches an edited block inherits its
     * translation and edit timestamp. On a failed recognition the merge keeps
     * the existing blocks and only records the failure diagnostics.
     */
    suspend fun mergeOcr(
        patch: OcrStagePatch,
        description: String = "detection/ocr stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Ocr(patch), description)

    private fun mergeOcrLocked(
        current: PageTranslation?,
        patch: OcrStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = when {
            patch.generation != generation ->
                "generation expected=${patch.generation} actual=$generation"
            current == null -> "page missing"
            patch.expectedPageVersion != current.pageVersion ->
                "pageVersion expected=${patch.expectedPageVersion} actual=${current.pageVersion}"
            patch.expectedArtifactPageVersion != null &&
                patch.expectedArtifactPageVersion != artifactManifest?.pages?.get(patch.pageKey)?.pageVersion ->
                "artifact pageVersion changed"
            patch.expectedCandidateGenerationId != null &&
                patch.expectedCandidateGenerationId != artifactManifest?.pages?.get(patch.pageKey)?.candidate?.generationId ->
                "candidate generation changed"
            patch.expectedDependencyFingerprint != null &&
                artifactManifest?.pages?.get(patch.pageKey)?.candidate?.let { candidate ->
                    patch.expectedDependencyFingerprint != candidate.dependencyFingerprint
                } == true ->
                "candidate dependency fingerprint changed"
            patch.expectedPriorOcrFingerprints != current.ocrBlockFingerprints() ->
                "prior OCR identity changed"
            patch.expectedLeaseToken != null && pageLeases[patch.pageKey]?.token != patch.expectedLeaseToken ->
                "page lease token changed"
            patch.expectedLeaseToken == null && pageLeases[patch.pageKey] != null ->
                "page lease token required"
            else -> null
        }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val result = patch.ocrResult
        val updated = current!!.detachedCopy()
        if (result.ocrStatus == StageStatus.FAILED) {
            // Failure diagnostics only: reusable blocks, masks, and any
            // committed display survive a failed recognition attempt.
            updated.ocrStatus = StageStatus.FAILED
            updated.errorMessage = patch.errorMessage ?: result.activeError
        } else {
            val editedByIdentity = current.blocks
                .filter { it.userEditedAt != null }
                .associateBy { it.ocrFingerprint() }
            updated.blocks = result.blocks.map { block ->
                editedByIdentity[block.ocrFingerprint()]?.let { edited ->
                    block.copy(translation = edited.translation, userEditedAt = edited.userEditedAt)
                } ?: block
            }.toMutableList()
            updated.inpaintMaskBoxes = result.inpaintMaskBoxes
            updated.allTextDetections = result.allTextDetections
            updated.ocrStatus = result.ocrStatus
            updated.inpaintStatus = result.inpaintStatus
            updated.renderStatus = result.renderStatus
            updated.detectionCount = result.detectionCount
            updated.ocrBlockCount = result.ocrBlockCount
            updated.recognitionEngine = result.recognitionEngine
            updated.decodeSampleSize = result.decodeSampleSize
            updated.imgWidth = result.imgWidth
            updated.imgHeight = result.imgHeight
            updated.originalImgWidth = result.originalImgWidth
            updated.originalImgHeight = result.originalImgHeight
            updated.cleanedImageName = result.cleanedImageName ?: updated.cleanedImageName
            updated.inpaintRevision = result.inpaintRevision
            updated.inpaintingModeUsed = result.inpaintingModeUsed
            patch.errorMessage?.let { updated.errorMessage = it }
        }
        val owned = ownedPage(patch.pageKey, updated)
        pages = pages.put(patch.pageKey, owned)
        publishLocked(current, owned, patch.toPrecondition())
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey))
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

    private fun mergeTranslationLocked(
        current: PageTranslation?,
        patch: TranslationStagePatch,
        description: String,
    ): StagePatchResult {
        val identityRejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
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
                else -> null
            }
            if (rejection != null) {
                rejectedTargets += rejection
            } else {
                block!!.translation = target.translation
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
        publishLocked(current, updated, patch.toPrecondition())
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), applied)
    }

    private fun mergeInpaintLocked(
        current: PageTranslation?,
        patch: InpaintStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, null)
            ?: current?.takeUnless { it.inpaintMaskFingerprint() == patch.expectedMaskFingerprint }
                ?.let { "inpaint mask identity changed" }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val updated = ownedPage(
            patch.pageKey,
            current!!.detachedCopy().apply {
                cleanedImageName = patch.cleanedImageName
                inpaintRevision = patch.inpaintRevision
                inpaintingModeUsed = patch.inpaintingModeUsed
                inpaintStatus = patch.inpaintStatus
                errorMessage = patch.errorMessage
            },
        )
        pages = pages.put(patch.pageKey, updated)
        publishLocked(current, updated, patch.toPrecondition())
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey))
    }

    private fun mergeRenderLocked(
        current: PageTranslation?,
        patch: RenderStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
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
        publishLocked(current, updated, patch.toPrecondition())
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), patch.blocks.map { it.blockIndex })
    }

    private fun stageIdentityRejection(
        page: PageTranslation?,
        expectedGeneration: Long,
        expectedPageVersion: Long? = null,
        expectedLeaseToken: Long? = null,
        pageKey: String? = null,
        expectedCandidateGenerationId: String? = null,
        expectedDependencyFingerprint: String? = null,
        expectedArtifactPageVersion: Long? = null,
    ): String? = when {
        expectedGeneration != generation -> "generation expected=$expectedGeneration actual=$generation"
        page == null -> "page missing"
        expectedPageVersion != null && expectedPageVersion != page.pageVersion ->
            "pageVersion expected=$expectedPageVersion actual=${page.pageVersion}"
        expectedArtifactPageVersion != null &&
            pageKey != null &&
            expectedArtifactPageVersion != artifactManifest?.pages?.get(pageKey)?.pageVersion ->
            "artifact pageVersion changed"
        expectedCandidateGenerationId != null &&
            pageKey != null &&
            expectedCandidateGenerationId != artifactManifest?.pages?.get(pageKey)?.candidate?.generationId ->
            "candidate generation changed"
        expectedDependencyFingerprint != null &&
            pageKey != null &&
            artifactManifest?.pages?.get(pageKey)?.candidate?.let { candidate ->
                expectedDependencyFingerprint != candidate.dependencyFingerprint
            } == true ->
            "candidate dependency fingerprint changed"
        expectedLeaseToken != null && pageKey != null && pageLeases[pageKey]?.token != expectedLeaseToken ->
            "page lease token changed"
        expectedLeaseToken == null && pageKey != null && pageLeases[pageKey] != null ->
            "page lease token required"
        else -> null
    }

    private fun OcrStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion,
        blockFingerprints = expectedPriorOcrFingerprints,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun TranslationStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun InpaintStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun RenderStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): String? {
        val current = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return when {
            expected.generation != generation ->
                "generation expected=${expected.generation} actual=$generation"
            expected.pageVersion != (current?.pageVersion ?: 0L) ->
                "pageVersion expected=${expected.pageVersion} actual=${current?.pageVersion ?: 0L}"
            expected.artifactPageVersion != null && expected.artifactPageVersion != artifactPage?.pageVersion ->
                "artifact pageVersion changed"
            expected.candidateGenerationId != null &&
                expected.candidateGenerationId != artifactPage?.candidate?.generationId ->
                "candidate generation changed"
            expected.dependencyFingerprint != null &&
                artifactPage?.candidate?.let { candidate ->
                    expected.dependencyFingerprint != candidate.dependencyFingerprint
                } == true ->
                "candidate dependency fingerprint changed"
            expected.blockFingerprints != null &&
                expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                "block fingerprint changed"
            expected.leaseToken != null && pageLeases[pageKey]?.token != expected.leaseToken ->
                "page lease token changed"
            expected.leaseToken == null && pageLeases[pageKey] != null ->
                "page lease token required"
            else -> null
        }
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
        val expected = mutex.withLock {
            if (context?.store === this && context.generation != generation) {
                null
            } else if (artifactManifest?.authority == ManifestAuthority.ARTIFACTS && pageLeases[pageKey] != null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT compatibility updatePage rejected while a page lease is active: pageKey=$pageKey"
                }
                null
            } else {
                snapshotLocked(pageKey).toPrecondition()
            }
        }
        if (expected == null) {
            if (context?.store === this && context.generation != generation) {
                rejected(
                    pageKey,
                    "updatePage",
                    "generation expected=${context.generation} actual=$generation",
                )
            }
            return
        }
        updatePageGuarded(pageKey, expected, "updatePage", update)
    }

    suspend fun deletePage(pageKey: String) {
        if (defunct) return
        mutex.withLock {
            if (pages.containsKey(pageKey)) {
                deleteArtifactPageLocked(pageKey)
                pages = pages.remove(pageKey)
                committedDisplay = committedDisplay.remove(pageKey)
                retiredCleanedImages.remove(pageKey)
                pageLeases.remove(pageKey)
                schedulePersist()
                _state.value = snapshotPages()
                _display.value = displaySnapshotLocked()
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
            if (artifactManifest?.authority == ManifestAuthority.ARTIFACTS) {
                artifactManifest?.pages?.keys?.toList().orEmpty().forEach { pageKey ->
                    deleteArtifactPageLocked(pageKey)
                }
            }
            pages = persistentMapOf()
            committedDisplay = persistentMapOf()
            retiredCleanedImages.clear()
            updatedPages.forEach { (pageKey, page) ->
                val owned = ownedPage(pageKey, page)
                pages = pages.put(pageKey, owned)
                if (persistArtifactMutationLocked(pageKey, null, owned)) {
                    promoteDisplayIfReadyLocked(pageKey, owned)
                }
            }
            dirty = !persistLocked()
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
        }
    }

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): List<Pair<String, String>> {
        if (defunct || onlineKeys.size != onDiskKeys.size) return emptyList()
        return mutex.withLock {
            if (pages.size != onlineKeys.size) return@withLock emptyList()
            val onDiskKeySet = onDiskKeys.toSet()
            if (pages.keys.all { it in onDiskKeySet }) return@withLock emptyList()

            val moves = pages.keys.mapNotNull { oldKey ->
                val index = onlineKeys.indexOf(oldKey)
                if (index < 0) return@mapNotNull null
                val newKey = onDiskKeys[index]
                if (newKey == oldKey || pages.containsKey(newKey)) return@mapNotNull null
                oldKey to newKey
            }
            if (moves.isEmpty()) return@withLock emptyList()

            val moveByOldKey = moves.toMap()
            var updatedPages = persistentMapOf<String, PageTranslation>()
            var updatedCommitted = persistentMapOf<String, CommittedPageDisplay>()
            pages.forEach { (oldKey, page) ->
                val newKey = moveByOldKey[oldKey] ?: oldKey
                val updated = if (newKey == oldKey) {
                    page
                } else {
                    page.detachedCopy().apply { sourceFileName = newKey }
                }
                updatedPages = updatedPages.put(newKey, ownedPage(newKey, updated))
                committedDisplay[oldKey]?.let { committed ->
                    updatedCommitted = updatedCommitted.put(newKey, committed)
                }
                retiredCleanedImages.remove(oldKey)?.let { retired ->
                    retiredCleanedImages[newKey] = retired
                }
            }
            pages = updatedPages
            committedDisplay = updatedCommitted
            rekeyArtifactPagesLocked(moveByOldKey)
            retiredCleanedImages.keys.toList().forEach { pageKey ->
                if (pageKey !in updatedPages) retiredCleanedImages.remove(pageKey)
            }
            dirty = true
            schedulePersist()
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
            moves
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
                            updatedAt = now,
                        ).also {
                            it.ocrError = reason
                            it.translationError = reason
                            it.inpaintError = reason
                            it.renderError = reason
                        },
                    )
                }
            }.toPersistentMap()
            if (changed) {
                // Cancel semantics: cancelled pages release their writer
                // leases; committed display bundles survive untouched.
                synchronized(pageLeases) {
                    pageLeases.keys.retainAll { pageKey ->
                        pages[pageKey]?.hasRenderedResult ?: false
                    }
                }
                pages.keys.forEach { pageKey ->
                    cancelArtifactCandidateLocked(pageKey)
                }
                dirty = !persistLocked()
                _state.value = snapshotPages()
                _display.value = displaySnapshotLocked()
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

    private fun publishLocked(
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition? = null,
    ) {
        val pageKey = updated.sourceFileName ?: ""
        val artifactAccepted = persistArtifactMutationLocked(pageKey, previous, updated, expected)
        if (artifactAccepted) {
            promoteDisplayIfReadyLocked(pageKey, updated)
        }
        if (shouldPersistUpdate(previous, updated) && artifactManifest?.authority != ManifestAuthority.ARTIFACTS) {
            schedulePersist()
        }
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
    }

    /**
     * Production writer bridge for the Phase 3 artifact authority. Every
     * mutable page emission is first written to a candidate snapshot. A
     * display-ready emission writes the immutable committed snapshot and moves
     * the manifest pointer in one final publication. The legacy flat file is
     * never rewritten once authority has cut over.
     */
    private fun persistArtifactMutationLocked(
        pageKey: String,
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition? = null,
    ): Boolean {
        if (artifactStore == null) {
            if (fileCreator == null && translationFile == null) return true
            if (!ensureArtifactStoreLocked()) return false
        }
        val store = artifactStore ?: return false
        var manifest = artifactManifest ?: return true
        if (pageKey.isEmpty()) return true
        val currentRecord = manifest.pages[pageKey]
        if (currentRecord == null) {
            val added = manifest.copy(
                pages = manifest.pages + (pageKey to eu.kanade.translation.artifact.PageArtifactRecord(pageKey = pageKey)),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            if (!store.publishManifest(added)) {
                logcat(LogPriority.WARN) { "TachiyomiAT artifact page registration failed: pageKey=$pageKey" }
                return false
            }
            manifest = added
            artifactManifest = manifest
        }
        val record = manifest.pages.getValue(pageKey)
        if (expected != null) {
            if (expected.artifactPageVersion != null && expected.artifactPageVersion != record.pageVersion) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=artifact pageVersion changed"
                }
                return false
            }
            if (expected.candidateGenerationId != null && expected.candidateGenerationId != record.candidate?.generationId) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=candidate generation changed"
                }
                return false
            }
            if (expected.dependencyFingerprint != null &&
                expected.dependencyFingerprint != record.candidate?.dependencyFingerprint &&
                record.candidate != null
            ) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=dependency fingerprint changed"
                }
                return false
            }
        } else if (manifest.authority == ManifestAuthority.ARTIFACTS && pageLeases[pageKey] != null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=unfenced active lease"
            }
            return false
        }
        // Compatibility writers may resume an on-disk candidate after process
        // death before a new page lease is attached. Preserve the durable
        // candidate provenance in that narrow no-lease window; otherwise a
        // READER_ADHOC candidate would be misclassified as BATCH and its next
        // snapshot could never be persisted.
        val origin = pageLeases[pageKey]?.origin?.toArtifactOrigin()
            ?: record.candidate?.origin
            ?: ArtifactOrigin.BATCH
        val candidate = record.candidate
        val dependencyFingerprint = expected?.dependencyFingerprint
            ?: candidate?.dependencyFingerprint
            ?: StageFingerprints.pageSnapshot(previous ?: updated)
        if (candidate == null) {
            val opened = store.openCandidate(
                manifest = manifest,
                pageKey = pageKey,
                origin = origin,
                expectedPageVersion = record.pageVersion,
                dependencyFingerprint = dependencyFingerprint,
            )
            manifest = when (opened) {
                is ChapterArtifactStore.TransactionOutcome.Committed -> opened.manifest
                is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact candidate open rejected: pageKey=$pageKey reason=${opened.reason}"
                    }
                    return false
                }
            }
            artifactManifest = manifest
        } else if (candidate.origin != origin) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate provenance conflict: pageKey=$pageKey " +
                    "candidate=${candidate.origin} writer=$origin"
            }
            return false
        }
        val currentCandidate = manifest.pages.getValue(pageKey).candidate ?: return false
        val expectedPageVersion = manifest.pages.getValue(pageKey).pageVersion
        val persisted = store.persistLiveCandidate(
            manifest = manifest,
            pageKey = pageKey,
            generationId = currentCandidate.generationId,
            expectedPageVersion = expectedPageVersion,
            expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
            pageSnapshot = updated,
            origin = origin,
        )
        manifest = when (persisted) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> persisted.manifest
            is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate persist rejected: pageKey=$pageKey reason=${persisted.reason}"
                }
                return false
            }
        }
        artifactManifest = manifest
        if (updated.hasRenderedResult || updated.isTextlessTerminal) {
            val promoted = store.promoteLiveCandidate(
                manifest = manifest,
                pageKey = pageKey,
                generationId = currentCandidate.generationId,
                expectedPageVersion = manifest.pages.getValue(pageKey).pageVersion,
                expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                pageSnapshot = updated,
                origin = origin,
            )
            manifest = when (promoted) {
                is ChapterArtifactStore.TransactionOutcome.Committed -> promoted.manifest
                is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact candidate promotion rejected: pageKey=$pageKey reason=${promoted.reason}"
                    }
                    return false
                }
            }
        }
        artifactManifest = manifest
        return true
    }

    /**
     * Lazily creates the artifact authority for a chapter that had no legacy
     * translation file. The flat file is materialized only as an empty
     * compatibility sibling; the first real page mutation immediately opens a
     * candidate and flips the durable manifest to ARTIFACTS.
     */
    private fun ensureArtifactStoreLocked(): Boolean {
        artifactStore?.let { return artifactManifest != null }
        // Only the lazy-store constructor is allowed to bootstrap a new
        // artifact tree here. A caller that explicitly supplied an existing
        // translation file without a migrated artifact store retains the
        // legacy compatibility behavior until it is opened through [open].
        if (fileCreator == null) return false
        val file = translationFile ?: runCatching { fileCreator?.invoke() }.getOrNull()?.also {
            translationFile = it
        } ?: return false
        val parent = file.parentFile ?: return false
        val fileName = file.name ?: DEFAULT_FILE_NAME
        val layout = ChapterArtifactLayout.fromTranslationFileName(fileName)
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
        val store = ChapterArtifactStore(documents, layout, artifactImageProbe)
        val manifest = synchronized(ARTIFACT_MIGRATION_LOCK) {
            store.loadOrMigrate(
                LegacyChapterSnapshot(
                    pages = emptyMap(),
                    glossary = emptyMap(),
                    translationFileCorrupt = false,
                    legacyIdentity = null,
                    migratedAtEpochMs = System.currentTimeMillis(),
                ),
            ).manifest
        }
        artifactStore = store
        artifactManifest = manifest
        return true
    }

    private fun PageWriteOrigin?.toArtifactOrigin(): ArtifactOrigin = when (this) {
        PageWriteOrigin.READER_ADHOC -> ArtifactOrigin.READER_ADHOC
        PageWriteOrigin.BATCH, null -> ArtifactOrigin.BATCH
    }

    /**
     * Atomic committed-display promotion: when the page just reached a fully
     * displayable state (cleaned image file published and verified upstream by
     * [CleanedImagePublisher], translation READY/PARTIAL, render READY, some
     * translated text), the committed pointer swaps to a frozen snapshot of
     * exactly that state. A superseded bundle's cleaned file is retained until
     * the pipeline drains it, never deleted under a live committed pointer.
     */
    private fun promoteDisplayIfReadyLocked(pageKey: String, updated: PageTranslation) {
        if (pageKey.isEmpty() || (!updated.hasRenderedResult && !updated.isTextlessTerminal)) return
        val fingerprint = displayFingerprintOf(updated)
        val existing = committedDisplay[pageKey]
        if (existing != null && existing.displayFingerprint == fingerprint) return
        if (existing != null) {
            existing.page.cleanedImageName
                ?.takeIf { it != updated.cleanedImageName && it.isNotEmpty() }
                ?.let {
                    retiredCleanedImages
                        .computeIfAbsent(pageKey) { ConcurrentHashMap.newKeySet() }
                        .add(it)
                }
        }
        committedDisplay = committedDisplay.put(
            pageKey,
            CommittedPageDisplay(
                page = updated.detachedCopy(),
                pageVersion = updated.pageVersion,
                displayFingerprint = fingerprint,
                promotedAtEpochMs = updated.updatedAt,
            ),
        )
    }

    private fun displaySnapshotLocked(): Map<String, PageTranslation> {
        if (committedDisplay.isEmpty()) return _state.value
        val live = snapshotPages()
        return buildMap {
            live.forEach { (pageKey, page) ->
                put(pageKey, committedDisplay[pageKey]?.page?.detachedCopy() ?: page)
            }
        }
    }

    private fun displayFingerprintOf(page: PageTranslation): String {
        val canonical = buildString {
            appendField(page.cleanedImageName)
            appendField(page.inpaintRevision)
            page.blockFingerprints().forEach { fingerprint -> appendField(fingerprint) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendField(value: Any?) {
        val text = value?.toString() ?: "<null>"
        append(text.length).append(':').append(text).append('|')
    }

    /** Resolves the reader-facing page state: committed bundle first, live candidate otherwise. */
    fun resolveDisplayPage(pageKey: String): PageTranslation? =
        committedDisplay[pageKey]?.page?.detachedCopy() ?: pages[pageKey]?.detachedCopy()

    /** The frozen committed display bundle for [pageKey], if one exists. */
    fun committedDisplayPage(pageKey: String): PageTranslation? =
        committedDisplay[pageKey]?.page?.detachedCopy()

    /**
     * True while [name] still backs the committed display bundle (or its
     * retained predecessor) for [pageKey]; such files must not be deleted
     * before a newer bundle promotes.
     */
    fun mayDeleteCleanedImage(pageKey: String, name: String): Boolean =
        committedDisplay[pageKey]?.page?.cleanedImageName != name &&
            name !in (retiredCleanedImages[pageKey] ?: emptySet())

    /**
     * Returns and forgets one cleaned-image name retained for [pageKey] after
     * a newer bundle promoted; retained names are drained as a set so rapid
     * promotions cannot orphan an earlier superseded file.
     */
    fun drainRetiredCleanedImage(pageKey: String): String? =
        retiredCleanedImages[pageKey]?.let { retired ->
            val name = retired.firstOrNull() ?: return@let null
            retired.remove(name)
            if (retired.isEmpty()) retiredCleanedImages.remove(pageKey, retired)
            name
        }

    /** Atomically takes every superseded cleaned-image name for [pageKey]. */
    fun drainRetiredCleanedImages(pageKey: String): List<String> =
        retiredCleanedImages.remove(pageKey)?.toList().orEmpty()

    /**
     * Explicitly drops the committed display pointer — reserved for user
     * resets (reset translation/inpaint data), never for candidate retries:
     * pause/cancel/failure keep the committed bundle visible.
     */
    suspend fun demoteCommittedDisplay(pageKey: String, reason: String) {
        if (defunct) return
        mutex.withLock {
            if (committedDisplay.containsKey(pageKey)) {
                committedDisplay = committedDisplay.remove(pageKey)
                retiredCleanedImages.remove(pageKey)
                demoteArtifactPageLocked(pageKey)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT committed display demoted: pageKey=$pageKey reason=$reason"
                }
                _display.value = displaySnapshotLocked()
            }
        }
    }

    private fun cancelArtifactCandidateLocked(pageKey: String) {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        val candidate = manifest.pages[pageKey]?.candidate ?: return
        val outcome = store.cancelLiveCandidate(manifest, pageKey, candidate.generationId)
        if (outcome is ChapterArtifactStore.TransactionOutcome.Committed) {
            artifactManifest = outcome.manifest
        } else if (outcome is ChapterArtifactStore.TransactionOutcome.Rejected) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate cancel rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
    }

    private fun demoteArtifactPageLocked(pageKey: String) {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        val outcome = store.demoteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> artifactManifest = outcome.manifest
            is ChapterArtifactStore.TransactionOutcome.Rejected -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact display demotion rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
    }

    private fun deleteArtifactPageLocked(pageKey: String) {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        val outcome = store.deleteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> artifactManifest = outcome.manifest
            is ChapterArtifactStore.TransactionOutcome.Rejected -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact page deletion rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
    }

    private fun rekeyArtifactPagesLocked(moveByOldKey: Map<String, String>) {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        val updatedPages = manifest.pages.entries.associate { (oldKey, record) ->
            (moveByOldKey[oldKey] ?: oldKey) to record.copy(
                pageKey = moveByOldKey[oldKey] ?: oldKey,
                pageVersion = record.pageVersion + if (oldKey in moveByOldKey) 1L else 0L,
            )
        }
        if (updatedPages == manifest.pages) return
        val updated = manifest.copy(
            pages = updatedPages,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (store.publishManifest(updated)) {
            artifactManifest = updated
        } else {
            logcat(LogPriority.WARN) { "TachiyomiAT artifact page re-key publication failed" }
        }
    }

    private fun snapshotLocked(pageKey: String): PageSnapshot {
        val page = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return PageSnapshot(
            page = page?.detachedCopy(),
            generation = generation,
            pageVersion = page?.pageVersion ?: 0L,
            blockFingerprints = page?.blockFingerprints().orEmpty(),
            leaseToken = pageLeases[pageKey]?.token,
            artifactPageVersion = artifactPage?.pageVersion,
            candidateGenerationId = artifactPage?.candidate?.generationId,
            dependencyFingerprint = artifactPage?.candidate?.dependencyFingerprint
                ?: page?.let(StageFingerprints::pageSnapshot),
        )
    }

    private fun PageSnapshot.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = pageVersion,
        blockFingerprints = blockFingerprints,
        leaseToken = leaseToken,
        candidateGenerationId = candidateGenerationId,
        dependencyFingerprint = dependencyFingerprint,
        artifactPageVersion = artifactPageVersion,
    )

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
                val artifact = artifactStore
                val manifest = artifactManifest
                if (artifact != null && manifest?.authority == ManifestAuthority.ARTIFACTS) {
                    val pointer = artifact.publishGlossary(glossary)
                    if (pointer != null) {
                        val next = manifest.copy(
                            glossary = pointer,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                        if (artifact.publishManifest(next)) {
                            artifactManifest = next
                            glossaryDirty = false
                        } else {
                            glossaryDirty = true
                        }
                    } else {
                        glossaryDirty = true
                    }
                } else {
                    glossaryDirty = true
                }
                if (glossaryDirty) schedulePersist(markPageDirty = false)
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

    private fun persistLocked(): Boolean {
        if (defunct) return false
        // Once the artifact manifest owns this chapter, the flat JSON is a
        // legacy compatibility snapshot only. Writing the mutable candidate
        // map back into it would erase the durable committed pointer on the
        // next reopen, so all page durability is handled by the artifact
        // bridge in publishLocked().
        if (artifactManifest?.authority == ManifestAuthority.ARTIFACTS) return true
        persistCount++
        // Resolve the backing file lazily on first write. Opening a chapter with
        // no existing file leaves translationFile null; materializing it on first
        // real write avoids leaving empty files on disk (which previously made
        // isChapterTranslated report false TRANSLATED on reopen).
        if (translationFile == null) {
            translationFile = fileCreator?.invoke()
        }
        val target = translationFile ?: return false
        try {
            // Snapshot PersistentMap into a plain Map before encoding. Serializing
            // it directly makes kotlinx.serialization treat PersistentMap
            // polymorphically and fail at runtime ("subclass 'PersistentOrderedMap'
            // not found"), breaking every persist and reopen.
            val snapshot: Map<String, PageTranslation> = pages.toMap()
            val jsonBytes = Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            target.openOutputStream().use { output ->
                output.write(jsonBytes)
            }
            return true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to persist translation store; in-memory state retained" }
            return false
        }
    }

    suspend fun flush() {
        mutex.withLock {
            flushDirtyLocked()
        }
    }

    private fun flushDirtyLocked() {
        if (dirty) {
            if (persistLocked()) dirty = false else dirty = true
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
            logcat(LogPriority.ERROR) {
                "TachiyomiAT chapter summary publication failed: reason=translation file unavailable"
            }
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
        // A memory-only store has no future persistence target. Avoid leaving a
        // delayed job behind for eviction to join; explicit flush() still
        // remains available for deterministic callers and preserves dirty state.
        if (translationFile == null && fileCreator == null) return
        if (persistJob?.isActive == true) return
        persistJob = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            mutex.withLock { flushDirtyLocked() }
            persistJob = null
        }
    }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 250L
        private const val PERSIST_JOIN_TIMEOUT_MS = 2_000L

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
            var legacyCorrupt = false
            val legacyBytes = if (translationFile.exists()) {
                runCatching { translationFile.openInputStream().use { it.readBytes() } }
                    .onFailure { legacyCorrupt = true }
                    .getOrNull()
            } else {
                null
            }
            val existing = if (legacyBytes != null) {
                try {
                    val map = Json.decodeFromStream<Map<String, PageTranslation>>(legacyBytes.inputStream())
                    map.values.forEach { page ->
                        if (page.errorMessage != null) {
                            if (page.ocrStatus == StageStatus.FAILED) page.ocrError = page.ocrError ?: page.errorMessage
                            if (page.translationStatus ==
                                StageStatus.FAILED
                            ) {
                                page.translationError = page.translationError ?: page.errorMessage
                            }
                            if (page.inpaintStatus ==
                                StageStatus.FAILED
                            ) {
                                page.inpaintError = page.inpaintError ?: page.errorMessage
                            }
                            if (page.renderStatus ==
                                StageStatus.FAILED
                            ) {
                                page.renderError = page.renderError ?: page.errorMessage
                            }
                        }
                    }
                    map
                } catch (e: Exception) {
                    legacyCorrupt = true
                    logcat(LogPriority.WARN, e) { "Failed to load existing translation store; starting empty" }
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            val artifactLayout = translationFile.name
                ?.let(ChapterArtifactLayout::fromTranslationFileName)
            val artifactManifestFileExists = artifactLayout?.let { layout ->
                translationFile.parentFile?.findFile(layout.manifestFileName)?.exists() == true
            } == true
            val artifactLoad = if (translationFile.exists() || artifactManifestFileExists) {
                runCatching {
                    migrateArtifactManifest(translationFile, legacyBytes, existing, legacyCorrupt)
                }.onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "TachiyomiAT artifact manifest migration skipped: reason=open failure"
                    }
                }.getOrNull()
            } else {
                null
            }
            return ChapterTranslationStore(
                translationFile = translationFile,
                fileCreator = null,
                initialPages = artifactLoad?.livePages ?: existing,
                artifactStore = artifactLoad?.store,
                initialCommittedPages = artifactLoad?.committedPages.orEmpty(),
                initialArtifactManifest = artifactLoad?.manifest,
                initialRetiredCleanedImages = artifactLoad?.retiredCleanedImages.orEmpty(),
            ).also {
                it.loadGlossary()
            }
        }

        private data class ArtifactLoad(
            val store: ChapterArtifactStore,
            val manifest: ChapterArtifactManifest,
            val committedPages: Map<String, PageTranslation>,
            val livePages: Map<String, PageTranslation>,
            val retiredCleanedImages: Map<String, Set<String>> = emptyMap(),
        )

        /**
         * TachiyomiAT: identity-aware, non-destructive migration/resync of the
         * legacy flat translation record into the chapter artifact manifest.
         * Reads the legacy file's exact identity and the companion cleaned
         * images (bounded decode probe), then publishes the manifest and
         * versioned glossary sidecar as sibling documents. Because the legacy
         * file remains authoritative until the store-transaction phase, every
         * open compares the stored identity and resyncs when the bytes
         * changed. The legacy file itself is never modified, so the live
         * reader keeps its current behavior. Chapters without a legacy
         * translation file are left untouched.
         */
        private fun migrateArtifactManifest(
            translationFile: UniFile,
            legacyBytes: ByteArray?,
            legacyPages: Map<String, PageTranslation>,
            legacyCorrupt: Boolean,
        ): ArtifactLoad {
            val parent = translationFile.parentFile ?: return ArtifactLoad(
                ChapterArtifactStore(
                    AtomicChapterDocuments(UniFileChapterDocumentIo(translationFile)),
                    ChapterArtifactLayout.fromTranslationFileName(translationFile.name ?: DEFAULT_FILE_NAME),
                ),
                ChapterArtifactManifest(),
                emptyMap(),
                legacyPages,
            )
            val fileName = translationFile.name ?: return ArtifactLoad(
                ChapterArtifactStore(
                    AtomicChapterDocuments(UniFileChapterDocumentIo(parent)),
                    ChapterArtifactLayout.fromTranslationFileName(DEFAULT_FILE_NAME),
                ),
                ChapterArtifactManifest(),
                emptyMap(),
                legacyPages,
            )
            val layout = ChapterArtifactLayout.fromTranslationFileName(fileName)
            val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
            val artifactStore = ChapterArtifactStore(documents, layout, artifactImageProbe)
            val identity = legacyIdentityOf(legacyBytes, translationFile.lastModified())
            val companionImages = parent.findFile("${layout.chapterKey}_images")
            val facts = legacyPages.mapValues { (_, page) ->
                val cleaned = cleanedFileValidationOf(page, companionImages)
                LegacyPageFacts(
                    page = page,
                    cleanedFileState = cleaned.state,
                    cleanedImageDimensions = cleaned.dimensions,
                )
            }
            val glossary = runCatching {
                parent.findFile(((fileName.substringBeforeLast('.')) + ".glossary.json"))?.let { file ->
                    file.openInputStream().use { input -> Json.decodeFromStream<Map<String, String>>(input) }
                }
            }.getOrNull().orEmpty()
            var loaded = synchronized(ARTIFACT_MIGRATION_LOCK) {
                artifactStore.loadOrMigrate(
                    LegacyChapterSnapshot(
                        pages = facts,
                        glossary = glossary,
                        translationFileCorrupt = legacyCorrupt,
                        legacyIdentity = identity,
                        migratedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            var manifest = loaded.manifest
            if (manifest.authority == ManifestAuthority.LEGACY) {
                legacyPages.forEach { (pageKey, page) ->
                    val record = manifest.pages[pageKey]
                    if (record?.committed != null && page.hasRenderedResult && record.committed.pageSnapshotFileName == null) {
                        val materialized = artifactStore.materializeLegacyCommittedSnapshot(manifest, pageKey, page)
                        if (materialized is ChapterArtifactStore.TransactionOutcome.Committed) {
                            manifest = materialized.manifest
                        }
                    }
                }
            }
            val committedPages = manifest.pages.mapNotNull { (pageKey, record) ->
                val committed = record.committed ?: return@mapNotNull null
                val snapshot = artifactStore.readPageSnapshot(committed.pageSnapshotFileName)
                    ?: legacyPages[pageKey]?.takeIf { it.hasRenderedResult }
                snapshot?.let { pageKey to it }
            }.toMap()
            val livePages = when (manifest.authority) {
                ManifestAuthority.LEGACY -> legacyPages
                ManifestAuthority.ARTIFACTS -> manifest.pages.mapNotNull { (pageKey, record) ->
                    val candidateSnapshot = artifactStore.readPageSnapshot(record.candidate?.pageSnapshotFileName)
                    val committedSnapshot = committedPages[pageKey]
                    val fallback = committedSnapshot ?: legacyPages[pageKey]
                    (candidateSnapshot ?: fallback)?.let { pageKey to it }
                }.toMap()
            }
            val retiredCleanedImages = manifest.pages.mapNotNull { (pageKey, record) ->
                val previous = record.previousCommitted ?: return@mapNotNull null
                val name = previous.displayBase.fileName
                    ?.takeIf { previous.displayBase.legacyLayout && it != record.committed?.displayBase?.fileName }
                    ?: return@mapNotNull null
                pageKey to setOf(name)
            }.toMap()
            return ArtifactLoad(artifactStore, manifest, committedPages, livePages, retiredCleanedImages)
        }

        private fun legacyIdentityOf(bytes: ByteArray?, lastModifiedMs: Long): LegacySourceIdentity? = runCatching {
            bytes ?: return null
            LegacySourceIdentity(
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { byte -> "%02x".format(byte) },
                lengthBytes = bytes.size.toLong(),
                lastModifiedMs = lastModifiedMs,
            )
        }.getOrNull()

        /**
         * Classifies a legacy cleaned-image reference with a bounded decode
         * probe: existence and non-emptiness alone never qualify as VALID;
         * the bytes must decode and match the page's recorded dimensions when
         * they are known.
         */
        private data class CleanedFileValidation(
            val state: CleanedFileState,
            val dimensions: eu.kanade.translation.artifact.ProbedImage? = null,
        )

        private fun cleanedFileValidationOf(
            page: PageTranslation,
            companionImages: UniFile?,
        ): CleanedFileValidation {
            val cleanedName = page.cleanedImageName
                ?: return CleanedFileValidation(CleanedFileState.NONE_RECORDED)
            val file = companionImages?.findFile(cleanedName)
            when {
                file == null || !file.exists() -> return CleanedFileValidation(CleanedFileState.MISSING)
                file.length() <= 0L -> return CleanedFileValidation(CleanedFileState.EMPTY)
            }
            val probed = runCatching {
                file.openInputStream().use { input -> artifactImageProbe.probe(input) }
            }.getOrNull() ?: return CleanedFileValidation(CleanedFileState.CORRUPT_BYTES)
            val expectedWidth = page.imgWidth
            val expectedHeight = page.imgHeight
            if (expectedWidth > 0f && expectedHeight > 0f) {
                val widthMatches = abs(probed.width - expectedWidth) <= IMAGE_DIMENSION_TOLERANCE_PX
                val heightMatches = abs(probed.height - expectedHeight) <= IMAGE_DIMENSION_TOLERANCE_PX
                if (!widthMatches || !heightMatches) {
                    return CleanedFileValidation(CleanedFileState.DIMENSION_MISMATCH, probed)
                }
            }
            return CleanedFileValidation(CleanedFileState.VALID, probed)
        }

        /**
         * Test seam for the bounded cleaned-image probe. Production uses the
         * BitmapFactory bounds-only probe; JVM tests inject a header-parsing
         * fake because android.graphics is unavailable there.
         */
        @Volatile
        internal var artifactImageProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe

        private const val IMAGE_DIMENSION_TOLERANCE_PX = 1

        private val ARTIFACT_MIGRATION_LOCK = Any()

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
