package eu.kanade.translation.persistence

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class ChapterTranslationStore internal constructor(
    initialPages: Map<String, PageTranslation> = emptyMap(),
    artifactStore: ChapterArtifactEngine? = null,
    initialCommittedPages: Map<String, PageTranslation> = emptyMap(),
    initialArtifactManifest: ChapterArtifactManifest? = null,
    initialRetiredCleanedImages: Map<String, Set<String>> = emptyMap(),
    internal val artifactParent: UniFile? = null,
    internal val artifactFileName: String? = null,
    internal val artifactParentResolver: (() -> UniFile)? = null,
    internal val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Explicit memory, lazy-durable, or opened-durable storage mode. */
    internal var engineMode: ChapterStoreEngineMode =
        artifactStore?.let { ChapterStoreEngineMode.Durable(it) }
            ?: if (artifactParentResolver != null || artifactParent != null || artifactFileName != null) {
                ChapterStoreEngineMode.LazyDurable(
                    artifactParent = artifactParent,
                    artifactFileName = artifactFileName,
                    artifactParentResolver = artifactParentResolver,
                )
            } else {
                ChapterStoreEngineMode.Memory
            }

    /** The durable engine when this mode has been opened; memory and lazy modes are explicit. */
    internal val artifactEngine: ChapterArtifactEngine?
        get() = (engineMode as? ChapterStoreEngineMode.Durable)?.artifact

    internal fun hasArtifactPersistenceTarget(): Boolean =
        artifactParent != null || artifactFileName != null || artifactParentResolver != null

    /**
     * Store-side lock hierarchy: every acquisition in a write path moves DOWN
     * this list; nothing ever acquires a lock higher in the list while
     * holding a lower one:
     *
     * 1. the store [mutex] — suspending, so holders may await lower locks;
     * 2. the per-artifact open lock (`withArtifactOpenLock`) — acquired only
     *    from inside [mutex] via publication admission, as a coroutine.
     *
     * The open path (`openArtifactSuspend`) takes ONLY level 2 and never the
     * store mutex, so the order cannot invert and a first write never blocks a
     * carrier thread under the store mutex. `runBlocking` is banned anywhere
     * below level 1.
     */
    internal val mutex = Mutex()

    /**
     * Consolidated test-seam collaborator (Phase 7 S8). Declared before the
     * init block because [publishPage] counts every publish through it.
     */
    internal val testSeams = ChapterStoreTestSeams(this)

    internal val chapterKey: String?
        get() = artifactEngine?.layout?.chapterKey ?: artifactFileName?.substringBeforeLast('.')

    /** Runs one external artifact transaction under the facade mutex. */
    internal suspend inline fun <T> withArtifactEngineLocked(
        crossinline block: (ChapterArtifactEngine) -> T,
    ): T? = mutex.withLock {
        artifactEngine?.let(block)
    }

    /** Read-only manifest projection for context/status consumers. */
    internal fun readArtifactManifest(): ChapterArtifactManifest? = artifactEngine?.readManifest()

    @Volatile
    internal var pages: PersistentMap<String, PublishedPageTranslation> = persistentMapOf()
    internal val stateFlow = MutableStateFlow<PersistentMap<String, PublishedPageTranslation>>(persistentMapOf())

    /**
     * The last-known-good committed display bundle per
     * page. A candidate retry may mutate the live [pages] entry freely —
     * clearing statuses, wiping the cleaned name, stranding stages — but it
     * can never hide or mutate the committed bundle observed through
     * [display]. Promotion happens atomically inside the store mutex after the
     * page reached [PageTranslation.hasRenderedResult] shape, so observers
     * never see a half-promoted combination.
     */
    @Volatile
    internal var committedDisplay: PersistentMap<String, CommittedPageDisplay> = persistentMapOf()

    /** Durable artifact manifest snapshot used by the live writer bridge. */
    @Volatile
    internal var artifactManifest: ChapterArtifactManifest? = initialArtifactManifest

    @Volatile
    internal var pendingExpectedPageCount: Int? = null

    @Volatile
    internal var pendingExpectedPageCountTrusted: Boolean = false

    /**
     * Cleaned-image names retained because the superseded committed bundle
     * still displays them; drained for deletion by the pipeline after a newer
     * bundles promote. Keeping all names until a drain prevents a rapid sequence
     * of promotions from losing an earlier retained file.
     */
    internal val retiredCleanedImages = ConcurrentHashMap<String, MutableSet<String>>()
    internal val pendingArtifactPageRegistrations = LinkedHashSet<String>()

    internal val publishedDisplay = MutableStateFlow<PersistentMap<String, PublishedPageTranslation>>(persistentMapOf())

    // Lease ownership stays in one table so the store and lease APIs share the
    // same map and synchronization boundary.
    private val pageStageLeaseTable = PageStageLeaseTable(this)

    /** Writer leases per page: one origin owns a page until it releases it. */
    internal val pageLeases get() = pageStageLeaseTable.pageLeases

    @Volatile
    internal var generation = 0L
    private var nextPageVersion = 0L

    /** Live candidate progress; readers that need display safety use [display]. */
    val state: StateFlow<Map<String, PageTranslationView>> = stateFlow.asStateFlow()

    /**
     * Committed-pointer display projection: each page resolves to
     * its immutable committed display bundle when one exists, otherwise the
     * live candidate entry. Candidate emissions can replace live entries but
     * never null or mutate a committed bundle.
     */
    val display: StateFlow<Map<String, PageTranslationView>> = publishedDisplay.asStateFlow()

    val currentGeneration: Long get() = generation

    /**
     * Frozen snapshot of the last displayable page state, plus the identity
     * that lets the store decide when a promotion supersedes an older bundle.
     */
    data class CommittedPageDisplay(
        val page: PublishedPageTranslation,
        val pageVersion: Long,
        val displayFingerprint: String,
        val promotedAtEpochMs: Long,
    )

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
    ) {
        fun toPrecondition() = PatchPrecondition(
            generation = generation,
            pageVersion = pageVersion,
            blockFingerprints = blockFingerprints,
            leaseToken = leaseToken,
            candidateGenerationId = candidateGenerationId,
            dependencyFingerprint = dependencyFingerprint,
            artifactPageVersion = artifactPageVersion,
        )
    }

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
        data class Rejected(
            val reason: String,
            val detail: Detail? = null,
        ) : PatchResult {
            sealed interface Detail {
                data object ArtifactPublicationFailed : Detail
            }
        }
    }

    /**
     * Result of the single bounded revalidation allowed after a batch
     * translation candidate was rejected only because its page version moved.
     */
    internal sealed interface TranslationPublicationReconciliation {
        data class AlreadyDurable(val snapshot: PageSnapshot) : TranslationPublicationReconciliation
        data class Rebased(val snapshot: PageSnapshot) : TranslationPublicationReconciliation
        data class Deferred(val reason: String) : TranslationPublicationReconciliation
        data class PublicationFailed(val reason: String) : TranslationPublicationReconciliation
    }

    internal class GenerationContext(
        val store: ChapterTranslationStore,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<GenerationContext>
    }

    /**
     * Publication collaborator (Phase 7 S4). Owns mutation admission, lazy
     * artifact-store initialization, the registration CAS, and the candidate
     * open/cancel/persist/promote/failure orchestration. Module-internal so
     * the store and its in-module mutator collaborators call the publication
     * path directly under [mutex] — no facade shells in between.
     */
    internal val publicationBridge = ChapterStorePublicationBridge(this)

    // Lazy-write queue state is stored by the drain coordinator, but every
    // mutation is serialized by this store's mutex. Store-to-drain scheduling
    // is a synchronous handoff with no second state lock; drain paths re-enter
    // through mutex.withLock before reading or changing store state.
    internal val writeDrain = StoreWriteDrainCoordinator(this, persistenceDispatcher)

    /**
     * Bulk-mutation collaborator (Phase 7 S7). Owns [deletePage],
     * [replaceAll], [rekeyPages], [preRegisterPages],
     * [clearTransientQueuePages], and the two-phase re-key planning machinery;
     * the store delegates every public entry point here.
     */
    private val bulkMutator = ChapterBulkMutator(this)

    internal val displayPublication = ChapterDisplayPublication(this)

    internal fun displaySnapshotLocked(changedPageKeys: Iterable<String> = emptyList()) {
        displayPublication.displaySnapshotLocked(changedPageKeys)
    }

    internal fun promoteDisplayIfReadyLocked(pageKey: String, updated: PageTranslation) {
        displayPublication.promoteDisplayIfReadyLocked(pageKey, updated)
    }
    internal val pageMutations = ChapterPageMutations(this)
    internal val stageMerges = ChapterStageMerges(this)

    /**
     * Typed result of [rekeyPages]. [Moved] with [Moved.skippedCollisions] > 0 is
     * explicitly partial: the caller must not treat it as a complete handoff.
     */
    sealed class PageRekeyOutcome {
        data class Moved(
            val moves: List<Pair<String, String>>,
            val skippedCollisions: Int,
        ) : PageRekeyOutcome()

        data object Noop : PageRekeyOutcome()

        /** [reason] names why the re-key was refused; the store is left untouched. */
        data class Rejected(val reason: String, val cause: Throwable? = null) : PageRekeyOutcome()
    }

    /**
     * Active reader stores publish live state first.  Their durable artifact
     * transactions are coalesced by [writeDrain] instead of running
     * synchronously in the stage-emission path.  Test/probe stores keep the
     * historical synchronous default unless the manager explicitly enables
     * this mode when registering the live chapter store.
     */
    @Volatile
    internal var lazyPersistenceEnabled = false

    /** Latest guarded page-write identity rejection, retained for failure diagnostics. */
    @Volatile
    internal var lastGuardedWriteRejectionDiagnostic: String? = null
        internal set

    @Volatile
    internal var lastBatchWriteGateRejectionDiagnostic: String? = null
        internal set

    internal fun recordBatchWriteGateRejectionDiagnostic(diagnostic: String) {
        lastBatchWriteGateRejectionDiagnostic = diagnostic
    }

    /** Enables memory-first publication for a registered production chapter. */
    internal fun enableLazyPersistence() {
        lazyPersistenceEnabled = true
    }

    internal fun isLazyGenerationCurrent(expectedGeneration: Long): Boolean =
        !defunct && generation == expectedGeneration

    @Volatile
    internal var defunct = false

    suspend fun markDefunct() {
        writeDrain.markDefunct(
            markGenerationDefunctLocked = {
                defunct = true
                generation++
                synchronized(pageLeases) { pageLeases.clear() }
            },
        )
        logcat(LogPriority.WARN) { "TachiyomiAT store marked defunct: generation=$generation" }
    }
    val isDefunct: Boolean
        get() = defunct

    init {
        initialRetiredCleanedImages.forEach { (pageKey, names) ->
            if (names.isNotEmpty()) {
                retiredCleanedImages[pageKey] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(names) }
            }
        }
        initialPages.forEach { (pageKey, page) ->
            val version = if (page.pageVersion > 0L) page.pageVersion else nextVersion()
            val owned = page.detachedCopy().apply {
                sourceFileName = sourceFileName ?: pageKey
                runGeneration = generation
                pageVersion = version
            }
            pages = pages.put(pageKey, publishPage(owned))
        }
        nextPageVersion = pages.values.maxOfOrNull(PublishedPageTranslation::pageVersion) ?: 0L
        // Seed the last-known-good display pointers from durable artifact
        // snapshots first. The mutable live map may be an incomplete candidate
        // after process death; it must never become the reader authority.
        initialCommittedPages.forEach { (pageKey, page) ->
            committedDisplay = committedDisplay.put(
                pageKey,
                CommittedPageDisplay(
                    page = publishPage(page),
                    pageVersion = page.pageVersion,
                    displayFingerprint = displayPublication.displayFingerprintOf(page),
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
                        page = page,
                        pageVersion = page.pageVersion,
                        displayFingerprint = displayPublication.displayFingerprintOf(page),
                        promotedAtEpochMs = page.updatedAt,
                    ),
                )
            }
        }
        stateFlow.value = snapshotPages()
        displayPublication.displaySnapshotLocked(pages.keys + committedDisplay.keys)
    }

    suspend fun snapshot(pageKey: String): PageSnapshot = mutex.withLock {
        snapshotLocked(pageKey)
    }

    // Durable status reads stay on the store API; the projector derives them
    // from the store's consistent manifest/state/display snapshot.
    private val statusProjector = StoreStatusProjector(this)

    /**
     * One consistent read of the status-projection inputs for
     * [StoreStatusProjector], captured in the same order the projection body
     * reads them (manifest → live pages flow → display flow). The flows are
     * handed over by reference so their `.value` reads keep landing at the
     * projection body's original points.
     */
    internal fun statusProjectionInputs(): StoreStatusInputs =
        StoreStatusInputs(
            manifest = artifactManifest,
            state = state,
            display = display,
        )

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? = statusProjector.durableFailure(pageKey, stage)

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(): Map<String, DurableFailureMetadata> =
        statusProjector.durableFailuresSnapshot()

    suspend fun beginGeneration(reason: String): Long = mutex.withLock {
        generation++
        logcat(LogPriority.INFO) { "TachiyomiAT store generation advanced: generation=$generation reason=$reason" }
        generation
    }

    // Lease entry points stay on the store so readers and pipeline writers use
    // one ownership boundary. The table uses the store mutex for mutations,
    // synchronized reads for lock-free inspection, and NonCancellable release
    // paths so cancellation cannot strand an owned lease.

    suspend fun tryAcquirePageStageLease(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = pageStageLeaseTable.tryAcquirePageStageLease(pageKey, stage, origin)

    internal suspend fun tryAcquirePageStageLeaseIfUnowned(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = pageStageLeaseTable.tryAcquirePageStageLeaseIfUnowned(pageKey, stage, origin)

    suspend fun releasePageStageLease(pageKey: String, origin: PageWriteOrigin) =
        pageStageLeaseTable.releasePageStageLease(pageKey, origin)

    /** Releases only the lease record held under [expectedToken]. */
    suspend fun releasePageStageLease(
        pageKey: String,
        origin: PageWriteOrigin,
        expectedToken: Long,
    ): Boolean = pageStageLeaseTable.releasePageStageLease(pageKey, origin, expectedToken)

    /** Cancels the active artifact candidate and releases its matching writer lease. */
    suspend fun cancelPageStageWork(pageKey: String, origin: PageWriteOrigin): Boolean =
        pageStageLeaseTable.cancelPageStageWork(pageKey, origin)

    /** Releases every lease held by [origin]; used at batch teardown so no lease outlives its run. */
    suspend fun releaseAllPageLeases(origin: PageWriteOrigin) =
        pageStageLeaseTable.releaseAllPageLeases(origin)

    fun pageLeaseOwner(pageKey: String): PageWriteOrigin? = pageStageLeaseTable.pageLeaseOwner(pageKey)

    suspend fun <T> withGeneration(generation: Long, block: suspend () -> T): T =
        withContext(GenerationContext(this, generation)) { block() }

    suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.patchPage(pageKey, expected, description, patch)

    suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) =
        pageMutations.updatePage(pageKey, update)

    suspend fun updatePageGuarded(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.updatePageGuarded(pageKey, expected, description, update)

    internal suspend fun reconcileBatchTranslationPageVersionDrift(
        pageKey: String,
        expected: PatchPrecondition,
        candidate: PageTranslation,
    ): TranslationPublicationReconciliation =
        pageMutations.reconcileBatchTranslationPageVersionDrift(pageKey, expected, candidate)

    suspend fun persistDurableStageFailure(
        pageKey: String,
        expected: PatchPrecondition,
        failure: DurableFailureMetadata,
        description: String = "durable stage failure",
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.persistDurableStageFailure(pageKey, expected, failure, description, update)

    suspend fun updatePageFromCurrentSnapshot(
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.updatePageFromCurrentSnapshot(pageKey, description, update)

    internal suspend fun patchBlock(
        pageKey: String,
        blockIndex: Int,
        expected: PatchPrecondition,
        expectedBlockFingerprint: String,
        expectedTranslation: String? = null,
        expectedUserEditedAt: Long? = null,
        description: String,
        patch: (TranslationBlock) -> TranslationBlock,
    ): PatchResult = pageMutations.patchBlock(
        pageKey,
        blockIndex,
        expected,
        expectedBlockFingerprint,
        expectedTranslation,
        expectedUserEditedAt,
        description,
        patch,
    )

    suspend fun mergeOcr(
        patch: OcrStagePatch,
        description: String = "stage:ocr",
    ): StagePatchResult = stageMerges.mergeOcr(patch, description)

    suspend fun mergeTranslation(
        patch: TranslationStagePatch,
        description: String = "stage:translation",
    ): StagePatchResult = stageMerges.mergeTranslation(patch, description)

    suspend fun mergeRender(
        patch: RenderStagePatch,
        description: String = "stage:render",
    ): StagePatchResult = stageMerges.mergeRender(patch, description)

    fun resolveDisplayPage(pageKey: String): PageTranslationView? =
        displayPublication.resolveDisplayPage(pageKey)

    suspend fun getOrLoadPageSnapshot(pageKey: String): PageTranslation? =
        displayPublication.getOrLoadPageSnapshot(pageKey)

    internal fun committedDisplayPage(pageKey: String): PageTranslationView? =
        displayPublication.committedDisplayPage(pageKey)

    fun mayDeleteCleanedImage(pageKey: String, name: String): Boolean =
        displayPublication.mayDeleteCleanedImage(pageKey, name)

    suspend fun authorizeCleanedImageDeletion(pageKey: String, name: String): Boolean =
        displayPublication.authorizeCleanedImageDeletion(pageKey, name)

    fun isCleanedImageReferencedByAnotherPage(pageKey: String, name: String): Boolean =
        displayPublication.isCleanedImageReferencedByAnotherPage(pageKey, name)

    fun referencedCleanedImageNames(): Set<String> =
        displayPublication.referencedCleanedImageNames()

    fun drainRetiredCleanedImages(pageKey: String): List<String> =
        displayPublication.drainRetiredCleanedImages(pageKey)

    suspend fun demoteCommittedDisplay(pageKey: String, reason: String): String? =
        displayPublication.demoteCommittedDisplay(pageKey, reason)

    internal fun cancelArtifactCandidateLocked(pageKey: String): Boolean =
        displayPublication.cancelArtifactCandidateLocked(pageKey)

    internal fun shouldPersistUpdate(previous: PageTranslation?, updated: PageTranslation): Boolean =
        pageMutations.shouldPersistUpdate(previous, updated)

    internal fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): PageWriteRejection? =
        pageMutations.pageWriteRejection(pageKey, expected)
    suspend fun deletePage(pageKey: String) = bulkMutator.deletePage(pageKey)
    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) =
        bulkMutator.replaceAll(updatedPages)

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): PageRekeyOutcome = bulkMutator.rekeyPages(onlineKeys, onDiskKeys)

    /**
     * Outcome of [preRegisterPages]. A rejection is observable so
     * the caller can fail the batch explicitly instead of running a tracker
     * whose totals silently stay zero.
     */
    sealed class PagePreRegistration {
        data object Accepted : PagePreRegistration()

        /** [reason] names why registration was refused (defunct store, artifact authority, ...). */
        data class Rejected(val reason: String) : PagePreRegistration()
    }

    /**
     * Registers the full ordered page set for a chapter before OCR starts.
     *
     * These placeholders are intentionally memory-only: they let progress UI show
     * the chapter total immediately, while the expected-page count is captured
     * in the artifact manifest when an artifact parent is already available.
     *
     * Returns [PagePreRegistration.Rejected] when the store refuses
     * registration (defunct store or artifact-authority failure), so the caller
     * can surface a typed terminal
     * error rather than a live zero tracker.
     *
     *  The trigger may attach its
     * admission-probe cross-check. When [sourceCountKnown] is true the
     * registered truth is honest about partiality:
     *  - a known SOURCE total ([probedSourcePageCount] != null) makes the
     *    trusted baseline the source total (progress shows 37/40, not 40/40)
     *    and records the delta as [PartialBatchInfo.DOWNLOAD_CROSSCHECK] —
     *    cleared again when a later cross-check derives missing == 0;
     *  - an unknown total (null count) keeps the found count but DEMOTES the
     *    trusted stamp and records `determinedFrom = UNKNOWN` — the durable
     *    record stops claiming trust it does not have.
     * Missing pages are never registered as page records; the manifest carries
     * the absence. Without context (both defaults) the self-derived
     * stamp below is byte-identical to its pre-existing behavior.
     */
    suspend fun preRegisterPages(
        pageKeys: List<String>,
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ): PagePreRegistration =
        bulkMutator.preRegisterPages(pageKeys, probedSourcePageCount, sourceCountKnown)

    /**
     * Clears queue-like entries after a user cancel/auto-window replacement.
     * Pages with a rendered/cleaned result are kept; transient running, pending,
     * cancelled, and failed entries without output are reset so the reader queue
     * reflects only the next active window.
     */
    suspend fun clearTransientQueuePages(reason: String? = null) =
        bulkMutator.clearTransientQueuePages(reason)
    internal fun nextVersion(): Long = ++nextPageVersion

    internal fun ownedPage(pageKey: String, candidate: PageTranslation): PageTranslation =
        candidate.detachedCopy().apply {
            sourceFileName = sourceFileName ?: pageKey
            updatedAt = System.currentTimeMillis()
            runGeneration = generation
            pageVersion = nextVersion()
        }

    internal fun snapshotLocked(pageKey: String): PageSnapshot {
        val page = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return PageSnapshot(
            page = page?.toDraft(),
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

    internal fun snapshotPages(): PersistentMap<String, PublishedPageTranslation> = pages

    internal fun publishPage(draft: PageTranslation): PublishedPageTranslation {
        testSeams.countPublishedPageCopy()
        return draft.toPublishedPage()
    }

    /** Durable translation status derived from the committed artifact state. */
    fun artifactStatus(): Translation.State? = statusProjector.artifactStatus()

    suspend fun flush() = writeDrain.flush()

    suspend fun closeAndFlush() {
        try {
            writeDrain.closeAndFlush()
        } finally {
            artifactEngine?.cancelRetentionWorkAndJoin()
        }
    }

    companion object {
        /** Open mechanics live on the opener collaborator; these shells keep existing callers unaffected. */

        /** Reads only the small manifest header; page snapshots stay unopened. */
        internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe =
            ChapterStoreOpener.probeArtifactManifest(parent, fileName)

        /** Opens an artifact and installs the state reconstructed from its manifest and snapshots. */
        internal suspend fun openArtifactSuspend(
            parent: UniFile,
            fileName: String,
        ): ChapterTranslationStore = ChapterStoreOpener.openArtifactSuspend(parent, fileName)

        internal suspend fun <T> withArtifactOpenLock(
            parent: UniFile?,
            fileName: String,
            block: suspend (ChapterStoreOpener.ArtifactOpenLock) -> T,
        ): T = ChapterStoreOpener.withArtifactOpenLock(parent, fileName, block)

        /**
         * Creates a store whose artifact manifest is created lazily on the first
         * artifact-backed [updatePage]/[replaceAll] write. When
         * [artifactParent] is null (chapter never translated), the optional
         * [artifactParentResolver] resolves the parent directory on the first mutation.
         */
        fun lazy(
            artifactParent: UniFile? = null,
            artifactFileName: String? = null,
            artifactParentResolver: (() -> UniFile)? = null,
        ): ChapterTranslationStore = ChapterStoreOpener.lazy(
            artifactParent = artifactParent,
            artifactFileName = artifactFileName,
            artifactParentResolver = artifactParentResolver,
        )
    }
}
