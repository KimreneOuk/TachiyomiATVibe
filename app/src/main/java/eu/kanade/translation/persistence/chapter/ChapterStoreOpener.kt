package eu.kanade.translation.persistence.chapter

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.ChapterDocumentIo
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.RenameResult
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.artifact.toArtifactPageFallback
import eu.kanade.translation.persistence.journal.ChapterJournalInventorySnapshot
import eu.kanade.translation.persistence.journal.ChapterJournalPageOutcome
import eu.kanade.translation.persistence.journal.ChapterJournalReplayReducer
import eu.kanade.translation.persistence.journal.ChapterJournalReplayResult
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Result of reading manifest metadata without opening page snapshots or sidecars. */
internal data class ArtifactManifestProbe(
    val exists: Boolean,
    val manifest: ChapterArtifactManifest?,
)

/**
 * Five-way durable read classification (P1 frozen discriminants). Unavailable
 * is not a variant: an environment I/O failure is rethrown to the caller so it
 * can never degrade into a cached absence or an empty chapter authority.
 */
internal sealed interface DurableChapterRead {
    /** Parsed current-format manifest with hydrated, verified page state. */
    data class Supported(val state: DurableChapterState) : DurableChapterRead

    /**
     * A non-supported schema owns the semantics; bytes were preserved
     * untouched and the chapter counts as ABSENT for planning, never as
     * cleanly new. The minimal version discriminator splits older (explicit
     * reset/retranslation territory) from newer (a newer build owns it).
     */
    sealed interface Unsupported : DurableChapterRead {
        val schemaVersion: Int

        data class Newer(override val schemaVersion: Int) : Unsupported

        data class Older(override val schemaVersion: Int) : Unsupported
    }

    /** No manifest bytes exist anywhere. Safe re-derivation. */
    data object Missing : DurableChapterRead

    /**
     * Manifest bytes exist but neither the primary nor the backup parses.
     * Evidence stays on disk untouched: a file-only read never quarantines,
     * never promotes, and never deletes.
     */
    data class Corrupt(val cause: Throwable?) : DurableChapterRead
}

/**
 * One publication-free durable chapter read. Every map mirrors what an open
 * would seed into a live store ([ChapterTranslationStore.state]/[ChapterTranslationStore.display]
 * and the opening status ledger), assembled read-only through the opener and
 * the pure status projector — no store, writer, or journal bridge is built.
 */
internal class DurableChapterState(
    val manifest: ChapterArtifactManifest,
    /** Opening status snapshot merged from the recovered journal prefix and the manifest; null without journal storage. */
    val journalStatus: StoreStatusSnapshot?,
    /** Live/live-state page projection (the store `state` flow equivalent). */
    val pages: Map<String, PageTranslationView>,
    /** Committed-first display projection (the store `display` flow equivalent). */
    val display: Map<String, PageTranslationView>,
    /** The durable active run record, when the manifest points at a usable one. */
    val activeRunRecord: ChapterRunRecord?,
) {
    /** Retryable/terminal failure truth the pause affordance and status project from. */
    val durableFailures: Map<String, DurableFailureMetadata>
        get() = journalStatus?.durableFailures ?: manifest.durableFailures

    /** Pure status-projection inputs for [ChapterStatusProjection]. */
    fun statusProjectionData(): StoreStatusProjectionData = StoreStatusProjectionData(
        manifest = manifest,
        pagesSnapshot = pages,
        displaySnapshot = display,
        journalStatus = journalStatus,
    )
}

/** Result of one classified manifest document read. */
private sealed interface ManifestDocumentRead {
    data class Parsed(val manifest: ChapterArtifactManifest) : ManifestDocumentRead
    data class Corrupt(val cause: Throwable) : ManifestDocumentRead
    data class Unavailable(val cause: Throwable) : ManifestDocumentRead
    data object Missing : ManifestDocumentRead
}

/**
 * [ChapterDocumentIo] whose mutations are structurally impossible. Reads flow
 * through unchanged, so the existing engine parse/validation/classification
 * code runs unmodified while quarantine, backup promotion, and writes become
 * no-ops — the read-only durability guarantee lives in the IO layer instead
 * of a duplicated read path.
 */
private class ReadOnlyChapterDocumentIo(
    private val delegate: ChapterDocumentIo,
) : ChapterDocumentIo {
    override fun exists(name: String): Boolean = delegate.exists(name)
    override fun length(name: String): Long = delegate.length(name)
    override fun read(name: String): ByteArray? = delegate.read(name)
    override fun lastModified(name: String): Long = delegate.lastModified(name)
    override fun openInputStream(name: String) = delegate.openInputStream(name)
    override fun list(directoryName: String): List<String>? = delegate.list(directoryName)
    override fun isFileBacked(): Boolean = delegate.isFileBacked()
    override fun write(name: String, bytes: ByteArray, syncToDisk: Boolean): Boolean = false
    override fun renameNoReplace(from: String, to: String): RenameResult = RenameResult.UNSUPPORTED
    override fun renameOwned(from: String, to: String): Boolean = false
    override fun delete(name: String): Boolean = false
}

/**
 * Store-opening collaborator (Phase 7 S6). Owns the open mechanics that used to
 * live in [ChapterTranslationStore.Companion]: the per-artifact open-lock
 * registry, the durable open path with its recovery decision table
 * (authoritative journal vs union mode vs legacy manifest), the manifest
 * probe, the publication-free durable read, and the lazy memory-first factory.
 * The facade companion keeps delegating shells so existing callers and tests
 * are unaffected.
 *
 * Lock order: the open path takes ONLY the per-artifact open lock and never
 * the store mutex, so `store mutex -> artifact-open lock` (never reversed)
 * is preserved and a first write never parks a carrier thread under the
 * store mutex. The durable read takes no lock: it performs zero writes, so
 * it can never interleave with recovery, retention, or publication.
 */
internal object ChapterStoreOpener {

    /**
     * Reads only the small manifest header; page snapshots stay unopened.
     * A corrupt primary with a valid backup reports the backup manifest —
     * valid backup bytes may supply read truth without promotion. A corrupt
     * read still throws (as before) so it never becomes a cached absence.
     */
    internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe =
        when (val read = readManifestClassified(parent, ChapterArtifactLayout.fromArtifactFileName(fileName))) {
            is ManifestDocumentRead.Parsed -> ArtifactManifestProbe(exists = true, manifest = read.manifest)
            ManifestDocumentRead.Missing -> ArtifactManifestProbe(exists = false, manifest = null)
            is ManifestDocumentRead.Corrupt -> throw read.cause
            is ManifestDocumentRead.Unavailable -> throw read.cause
        }

    private fun readManifestClassified(parent: UniFile, layout: ChapterArtifactLayout): ManifestDocumentRead {
        val primary = parent.findFile(layout.manifestFileName)
        if (primary != null && primary.exists()) {
            return when (val read = readManifestDocumentClassified(primary)) {
                is ManifestDocumentRead.Corrupt ->
                    // A backup is persisted metadata too: a valid one may
                    // supply read truth without promotion; if neither copy
                    // parses the chapter stays corrupt evidence, never new.
                    when (val backup = readBackupManifestClassified(parent, layout)) {
                        is ManifestDocumentRead.Parsed -> backup
                        else -> read
                    }
                else -> read
            }
        }
        return readBackupManifestClassified(parent, layout)
    }

    private fun readBackupManifestClassified(parent: UniFile, layout: ChapterArtifactLayout): ManifestDocumentRead {
        val backup = parent.findFile(AtomicChapterDocuments.backupNameFor(layout.manifestFileName))
        if (backup == null || !backup.exists()) return ManifestDocumentRead.Missing
        return readManifestDocumentClassified(backup)
    }

    private fun readManifestDocumentClassified(file: UniFile): ManifestDocumentRead =
        try {
            ManifestDocumentRead.Parsed(readManifestFile(file))
        } catch (failure: SerializationException) {
            ManifestDocumentRead.Corrupt(failure)
        } catch (failure: java.io.IOException) {
            ManifestDocumentRead.Unavailable(failure)
        }

    private fun readManifestFile(file: UniFile): ChapterArtifactManifest =
        file.openInputStream().use { input ->
            ArtifactDocumentJson.decodeFromStream<ChapterArtifactManifest>(input)
        }

    /**
     * Publication-free durable chapter read (P3D). Classifies the chapter's
     * durable truth through the same engine parsers the store open path uses —
     * over a read-only document layer, so supported/absent/corrupt/backup/
     * unsupported/future reads perform ZERO writes, deletes, quarantine
     * renames, backup promotions, retention sweeps, journal seeding, or GC.
     * The journal prefix is replayed read-only as projection evidence; no
     * journal writer or store is ever constructed.
     *
     * [Unavailable] is thrown, never returned: an environment failure must
     * stay visible and uncached instead of looking like a missing chapter.
     */
    internal suspend fun readDurableChapter(
        parent: UniFile,
        fileName: String,
        privateStorageRoot: File?,
        privateStorageIdentity: String?,
    ): DurableChapterRead {
        val layout = ChapterArtifactLayout.fromArtifactFileName(fileName)
        val manifest = when (val read = readManifestClassified(parent, layout)) {
            is ManifestDocumentRead.Parsed -> read.manifest
            ManifestDocumentRead.Missing -> return DurableChapterRead.Missing
            is ManifestDocumentRead.Corrupt -> return DurableChapterRead.Corrupt(read.cause)
            is ManifestDocumentRead.Unavailable -> throw read.cause
        }
        if (manifest.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) {
            return DurableChapterRead.Unsupported.Newer(manifest.schemaVersion)
        }
        if (manifest.schemaVersion < ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION) {
            return DurableChapterRead.Unsupported.Older(manifest.schemaVersion)
        }
        val documents = AtomicChapterDocuments(ReadOnlyChapterDocumentIo(UniFileChapterDocumentIo(parent)))
        val engine = ChapterArtifactEngine(documents, layout)
        val journalReplay = replayJournal(engine, manifest, parent, fileName, privateStorageRoot, privateStorageIdentity)
        val hydration = hydrateChapterState(engine, manifest, journalReplay)
        val statePages: Map<String, PageTranslationView> =
            applyCheckpointRecoveryFallback(
                engine = engine,
                manifest = manifest,
                projectedPages = hydration.recoveredPages?.toMap()
                    ?: hydration.legacyPages.mapValues { (_, page) -> page.toPublishedPage() },
                journalOutcomes = journalReplay?.pageOutcomes.orEmpty(),
            )
        // Mirror the store's open-time display seed: committed bundles first,
        // then the live page. Keys are the live state's keys, exactly like
        // displaySnapshotLocked(pages.keys + committedDisplay.keys): a
        // committed entry without a live row is dropped there too.
        val committedDisplay = hydration.committedPages.mapValues { (_, page) -> page.toPublishedPage() }.toMutableMap()
        statePages.forEach { (pageKey, page) ->
            if (!committedDisplay.containsKey(pageKey) && page.hasRenderedResult) {
                committedDisplay[pageKey] = page.toPublishedPage()
            }
        }
        val display = statePages.mapValues { (pageKey, page) ->
            committedDisplay[pageKey] ?: page
        }
        val activeRunRecord = manifest.activeRun
            ?.takeIf { it.isWellFormed() }
            ?.let { pointer ->
                (engine.readRunRecord(pointer) as? ChapterArtifactEngine.RunRecordRead.Usable)?.record
            }
        return DurableChapterRead.Supported(
            DurableChapterState(
                manifest = manifest,
                journalStatus = hydration.openingStatus,
                pages = statePages,
                display = display,
                activeRunRecord = activeRunRecord,
            ),
        )
    }

    /**
     * P3B reader fallback: restores saved OCR from a page's verified
     * recovery checkpoint into the projected page state — publication-free
     * and writer-free (no store, no lease, no journal bridge; the P3C store
     * hydration's provenance verification runs identically through
     * [ChapterArtifactEngine.readRecoveryCheckpoint], the one classified
     * checkpoint read). Eligibility is deliberately narrow:
     *
     * - the CURRENT manifest still points at the checkpoint;
     * - the manifest recorded the page's source SHA (checkpoint publications
     *   stamp it atomically with the pointer), so identity is verifiable
     *   without re-reading source bytes;
     * - the page has NO content-backed candidate or committed snapshot —
     *   newer/committed work always wins, the checkpoint is the recovery
     *   authority only when nothing else carries content;
     * - the journal replay recorded no outcome for the page — a tombstoned
     *   or invalidated page is never resurrected from checkpoint bytes.
     *
     * An unusable checkpoint never fails the read: the page keeps its
     * placeholder projection and the chapter stays supported.
     */
    private fun applyCheckpointRecoveryFallback(
        engine: ChapterArtifactEngine,
        manifest: ChapterArtifactManifest,
        projectedPages: Map<String, PageTranslationView>,
        journalOutcomes: Map<String, ChapterJournalPageOutcome>,
    ): Map<String, PageTranslationView> {
        var pages = projectedPages
        manifest.ocrCheckpoints.forEach { (pageKey, pointer) ->
            if (pageKey in journalOutcomes) return@forEach
            val record = manifest.pages[pageKey]
            if (record?.candidate?.pageSnapshotFileName != null) return@forEach
            if (record?.committed?.pageSnapshotFileName != null) return@forEach
            val recordedSha = manifest.sourceShaByPageKey[pageKey] ?: return@forEach
            when (val recovery = engine.readRecoveryCheckpoint(pointer, recordedSha)) {
                is ChapterArtifactEngine.CheckpointRecoveryRead.Usable ->
                    pages = pages + (pageKey to recovery.ocrSnapshot.toPublishedPage())
                is ChapterArtifactEngine.CheckpointRecoveryRead.Unusable -> logcat(LogPriority.WARN) {
                    "TachiyomiAT durable reader checkpoint recovery unavailable: " +
                        "pageKey=$pageKey reason=${recovery.reason}"
                }
            }
        }
        return pages
    }

    /**
     * Journal replay evidence shared by both authorities. Replay itself never
     * writes; snapshot verification through the engine's document layer heals
     * only for the writer open (real IO) and is a structural no-op for the
     * publication-free durable read (read-only IO).
     */
    private suspend fun replayJournal(
        engine: ChapterArtifactEngine,
        manifest: ChapterArtifactManifest,
        parent: UniFile,
        fileName: String,
        privateStorageRoot: File?,
        privateStorageIdentity: String?,
    ): ChapterJournalReplayResult? {
        val root = privateStorageRoot ?: return null
        val chapterIdentity = privateStorageIdentity ?: buildString {
            append(parent.filePath ?: parent.uri?.toString() ?: "<no-parent>")
            append(':')
            append(fileName)
        }
        val chapterIdentityHash = StageFingerprints.sha256Hex(chapterIdentity.toByteArray(Charsets.UTF_8))
        return withContext(Dispatchers.IO) {
            ChapterJournalReplayReducer.replay(
                epochs = ChapterJournalReplayReducer.readAppPrivateEpochs(root, chapterIdentity),
                artifactResolver = ChapterJournalReplayReducer.artifactResolver(engine, manifest),
                expectedChapterIdentityHash = chapterIdentityHash,
            )
        }
    }

    /** Opens an artifact and installs only the state reconstructed from its ACKed journal prefix. */
    internal suspend fun openArtifactSuspend(
        parent: UniFile,
        fileName: String,
        privateStorageRoot: File? = null,
        privateStorageIdentity: String? = null,
        journalStorageFactory: ((File, File) -> ChapterJournalStorage)? = null,
    ): ChapterTranslationStore = withContext(Dispatchers.IO) {
        withArtifactOpenLock(parent, fileName) { _ ->
            openArtifactOnly(
                parent,
                fileName,
                privateStorageRoot,
                privateStorageIdentity,
                journalStorageFactory,
            )
        }
    }

    internal class ArtifactOpenLock {
        val mutex = Mutex()
        var references = 0
    }

    private val ARTIFACT_OPEN_LOCKS = ConcurrentHashMap<String, ArtifactOpenLock>()

    private fun artifactOpenKey(parent: UniFile?, fileName: String): String {
        val parentKey = parent?.filePath ?: parent?.uri?.toString() ?: "<unknown>"
        return "$parentKey:$fileName"
    }

    internal suspend fun <T> withArtifactOpenLock(
        parent: UniFile?,
        fileName: String,
        block: suspend (ArtifactOpenLock) -> T,
    ): T {
        val key = artifactOpenKey(parent, fileName)
        val openLock = checkNotNull(
            ARTIFACT_OPEN_LOCKS.compute(key) { _, current ->
                (current ?: ArtifactOpenLock()).also { it.references++ }
            },
        )
        return try {
            openLock.mutex.withLock { block(openLock) }
        } finally {
            ARTIFACT_OPEN_LOCKS.compute(key) { _, current ->
                check(current === openLock)
                openLock.references--
                openLock.takeIf { it.references > 0 }
            }
        }
    }

    /**
     * One verified hydration of a manifest plus its recovered journal prefix.
     * Shared by the WRITER open (which seeds a live store from it) and the
     * PUBLICATION-FREE read (which projects status/display from it) so the
     * two can never disagree about candidate/committed verification.
     */
    private class ChapterHydration(
        val legacyPages: Map<String, PageTranslation>,
        val committedPages: Map<String, PageTranslation>,
        val recoveredPages: Map<String, PublishedPageTranslation>?,
        val unverifiedFallbackKeys: Set<String>,
        val retiredCleanedImages: Map<String, Set<String>>,
        val openingStatus: StoreStatusSnapshot?,
        val authoritativeJournal: Boolean,
        val seedPages: Map<String, PageTranslation>,
        val seedInventory: ChapterJournalInventorySnapshot?,
    )

    private fun hydrateChapterState(
        artifact: ChapterArtifactEngine,
        manifest: ChapterArtifactManifest,
        journalReplay: ChapterJournalReplayResult?,
    ): ChapterHydration {
        fun readVerifiedSnapshot(fileName: String?, expectedFingerprint: String?): PageTranslation? {
            if (fileName == null || expectedFingerprint.isNullOrBlank()) return null
            val page = artifact.readPageSnapshot(fileName) ?: return null
            return page.takeIf { StageFingerprints.pageSnapshot(it) == expectedFingerprint }
        }

        val artifactPageStates = manifest.pages.mapValues { (_, record) ->
            val candidate = record.candidate?.let { metadata ->
                readVerifiedSnapshot(metadata.pageSnapshotFileName, metadata.pageSnapshotFingerprint)
            }
            val committed = record.committed?.let { metadata ->
                readVerifiedSnapshot(metadata.pageSnapshotFileName, metadata.translationFingerprint)
            }
            OpenArtifactPageState(
                livePage = candidate ?: committed ?: record.toArtifactPageFallback(),
                committedPage = committed,
                hasContentBackedSnapshot = candidate != null || committed != null,
            )
        }
        val outcomes = journalReplay?.pageOutcomes.orEmpty()
        val authoritativeJournal = journalReplay?.inventory?.hasTrustedExpectedPageCount == true
        val blockedManifestKeys = outcomes.filterValues { outcome ->
            outcome == ChapterJournalPageOutcome.TOMBSTONED || outcome == ChapterJournalPageOutcome.INVALID
        }.keys
        val unverifiedFallbackKeys = if (journalReplay != null && !authoritativeJournal) {
            artifactPageStates.filter { (pageKey, opened) ->
                !opened.hasContentBackedSnapshot && pageKey !in outcomes
            }.keys
        } else {
            emptySet()
        }
        // A trusted journal can lose capture after terminal lag. A verified committed snapshot may fill only a silent key; journal outcomes and candidate metadata remain blockers.
        val authoritativeSilentCommittedPages: Map<String, PublishedPageTranslation> = if (authoritativeJournal) {
            artifactPageStates.mapNotNull { (pageKey, opened) ->
                if (pageKey in outcomes || pageKey in blockedManifestKeys || manifest.pages[pageKey]?.candidate != null) {
                    null
                } else {
                    opened.committedPage?.let { pageKey to it.toPublishedPage() }
                }
            }.toMap()
        } else {
            emptyMap()
        }
        val recoveredPages: Map<String, PublishedPageTranslation>? = when {
            journalReplay == null -> null
            authoritativeJournal -> buildMap {
                putAll(journalReplay.pages)
                putAll(authoritativeSilentCommittedPages)
            }
            else -> buildMap<String, PublishedPageTranslation> {
                putAll(journalReplay.pages)
                artifactPageStates.forEach { (pageKey, opened) ->
                    if (pageKey !in outcomes) put(pageKey, opened.livePage.toPublishedPage())
                }
            }
        }
        val legacyPages = if (journalReplay == null) {
            artifactPageStates.mapValues { it.value.livePage }
        } else {
            emptyMap()
        }
        val committedPages = artifactPageStates.mapNotNull { (pageKey, opened) ->
            val hasJournalWinnerForDisplay = !authoritativeJournal ||
                outcomes[pageKey] == ChapterJournalPageOutcome.RECORDED ||
                pageKey in authoritativeSilentCommittedPages
            opened.committedPage
                ?.takeIf { pageKey !in blockedManifestKeys && hasJournalWinnerForDisplay }
                ?.let { pageKey to it }
        }.toMap()
        val retiredCleanedImages = manifest.pages.mapNotNull { (pageKey, record) ->
            val previous = record.previousCommitted ?: return@mapNotNull null
            val name = previous.displayBase.fileName
                ?.takeIf { it != record.committed?.displayBase?.fileName }
                ?: return@mapNotNull null
            pageKey to setOf(name)
        }.toMap()

        if (journalReplay == null) {
            return ChapterHydration(
                legacyPages = legacyPages,
                committedPages = committedPages,
                recoveredPages = null,
                unverifiedFallbackKeys = emptySet(),
                retiredCleanedImages = retiredCleanedImages,
                openingStatus = null,
                authoritativeJournal = false,
                seedPages = emptyMap(),
                seedInventory = null,
            )
        }
        val manifestKeys = manifest.pages.keys + manifest.sourceShaByPageKey.keys
        val inventory = journalReplay.inventory
        val expectedKeys = buildSet {
            addAll(manifestKeys)
            inventory
                ?.takeIf { it.hasTrustedExpectedPageCount }
                ?.expectedPageKeys
                ?.let(::addAll)
        }
        val expectedCount = maxOf(
            journalReplay.expectedPageCount,
            manifest.expectedPageCount ?: 0,
            expectedKeys.size,
        )
        val manifestFailures = manifest.durableFailures.filterValues { failure ->
            failure.pageKey !in outcomes
        }
        val seedPages = if (!authoritativeJournal) {
            artifactPageStates.mapNotNull { (pageKey, opened) ->
                if (opened.hasContentBackedSnapshot && pageKey !in outcomes) {
                    pageKey to opened.livePage
                } else {
                    null
                }
            }.toMap()
        } else {
            emptyMap()
        }
        // Mirror of the open path's seed adoption (seedJournalFromManifest ->
        // adoptReplayedPageOutcomesLocked): the seed is all-or-nothing — the
        // writer adopts the replayed outcomes only when EVERY content-backed
        // seed page re-verifies against the manifest, so the publication-free
        // read reproduces exactly that gate without writing any journal.
        val seedOutcomes: Map<String, ChapterJournalPageOutcome> = if (seedPages.isEmpty()) {
            emptyMap()
        } else {
            val identityResolver = ChapterJournalReplayReducer.artifactResolver(artifact, manifest)
            val allSeedsVerified = seedPages.all { (pageKey, page) ->
                identityResolver.matches(pageKey, StageFingerprints.pageSnapshot(page), page.toPublishedPage())
            }
            if (allSeedsVerified) {
                seedPages.keys.associateWith { ChapterJournalPageOutcome.RECORDED }
            } else {
                emptyMap()
            }
        }
        val openingStatus = StoreStatusSnapshot(
            inventory = StoreStatusInventory(
                expectedPageKeys = expectedKeys,
                expectedPageCount = expectedCount,
                expectedPageCountTrusted = authoritativeJournal ||
                    (manifest.expectedPageCountTrusted && unverifiedFallbackKeys.isEmpty()),
            ),
            durableFailures = manifestFailures + journalReplay.durableFailures,
            pageOutcomes = journalReplay.pageOutcomes.toMap() + seedOutcomes,
        )
        val seedInventory = ChapterJournalInventorySnapshot(
            expectedPageKeys = expectedKeys,
            expectedPageCount = expectedCount,
            sourceShaByPageKey = manifest.sourceShaByPageKey,
            expectedPageCountTrusted = manifest.expectedPageCountTrusted && unverifiedFallbackKeys.isEmpty(),
        )
        return ChapterHydration(
            legacyPages = legacyPages,
            committedPages = committedPages,
            recoveredPages = recoveredPages,
            unverifiedFallbackKeys = unverifiedFallbackKeys,
            retiredCleanedImages = retiredCleanedImages,
            openingStatus = openingStatus,
            authoritativeJournal = authoritativeJournal,
            seedPages = seedPages,
            seedInventory = seedInventory,
        )
    }

    private data class OpenArtifactPageState(
        val livePage: PageTranslation,
        val committedPage: PageTranslation?,
        val hasContentBackedSnapshot: Boolean,
    )

    private suspend fun openArtifactOnly(
        parent: UniFile?,
        fileName: String,
        privateStorageRoot: File?,
        privateStorageIdentity: String?,
        journalStorageFactory: ((File, File) -> ChapterJournalStorage)?,
    ): ChapterTranslationStore {
        if (parent == null) {
            return ChapterTranslationStore(
                artifactParent = null,
                artifactFileName = fileName,
                privateStorageRoot = privateStorageRoot,
                privateStorageIdentity = privateStorageIdentity,
                journalStorageFactoryForOpen = journalStorageFactory,
            )
        }
        val layout = ChapterArtifactLayout.fromArtifactFileName(fileName)
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
        val artifact = ChapterArtifactEngine(documents, layout)
        val manifest = artifact.load().manifest
        val journalReplay = replayJournal(artifact, manifest, parent, fileName, privateStorageRoot, privateStorageIdentity)
        val hydration = hydrateChapterState(artifact, manifest, journalReplay)
        val store = ChapterTranslationStore(
            initialPages = hydration.legacyPages,
            artifactStore = artifact,
            initialCommittedPages = hydration.committedPages,
            initialArtifactManifest = manifest,
            journalReplayResult = journalReplay,
            initialRecoveredPages = hydration.recoveredPages,
            initialUnverifiedManifestFallbackPageKeys = hydration.unverifiedFallbackKeys,
            initialRetiredCleanedImages = hydration.retiredCleanedImages,
            artifactParent = parent,
            artifactFileName = fileName,
            privateStorageRoot = privateStorageRoot,
            privateStorageIdentity = privateStorageIdentity,
            journalStorageFactoryForOpen = journalStorageFactory,
        )
        if (journalReplay != null) {
            store.statusLedger.installOpeningStatus(
                snapshot = hydration.openingStatus,
                trustEstablished = hydration.authoritativeJournal,
            )
            if (!hydration.authoritativeJournal) {
                store.seedJournalFromManifest(
                    hydration.seedPages,
                    checkNotNull(hydration.seedInventory),
                )
            }
        }
        return store
    }

    /**
     * Creates a store whose artifact manifest is created lazily on the first
     * artifact-backed write. When [artifactParent] is null (chapter never
     * translated), the optional [artifactParentResolver] resolves the parent
     * directory on the first mutation.
     */
    internal fun lazy(
        artifactParent: UniFile? = null,
        artifactFileName: String? = null,
        privateStorageRoot: File? = null,
        privateStorageIdentity: String? = null,
        artifactParentResolver: (() -> UniFile)? = null,
    ): ChapterTranslationStore =
        ChapterTranslationStore(
            initialPages = emptyMap(),
            artifactParent = artifactParent,
            artifactFileName = artifactFileName,
            artifactParentResolver = artifactParentResolver,
            privateStorageRoot = privateStorageRoot,
            privateStorageIdentity = privateStorageIdentity,
        )
}
