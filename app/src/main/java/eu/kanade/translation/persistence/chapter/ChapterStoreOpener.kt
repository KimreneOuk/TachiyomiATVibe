package eu.kanade.translation.persistence.chapter

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.artifact.toArtifactPageFallback
import eu.kanade.translation.persistence.journal.ChapterJournalInventorySnapshot
import eu.kanade.translation.persistence.journal.ChapterJournalPageOutcome
import eu.kanade.translation.persistence.journal.ChapterJournalReplayReducer
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Result of reading manifest metadata without opening page snapshots or sidecars. */
internal data class ArtifactManifestProbe(
    val exists: Boolean,
    val manifest: ChapterArtifactManifest?,
)

/**
 * Store-opening collaborator (Phase 7 S6). Owns the open mechanics that used to
 * live in [ChapterTranslationStore.Companion]: the per-artifact open-lock
 * registry, the durable open path with its recovery decision table
 * (authoritative journal vs union mode vs legacy manifest), the manifest
 * probe, and the lazy memory-first factory. The facade companion keeps
 * delegating shells so existing callers and tests are unaffected.
 *
 * Lock order: the open path takes ONLY the per-artifact open lock and never
 * the store mutex, so `store mutex -> artifact-open lock` (never reversed)
 * is preserved and a first write never parks a carrier thread under the
 * store mutex.
 */
internal object ChapterStoreOpener {

    /** Reads only the small manifest header; page snapshots stay unopened. */
    internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe {
        val layout = ChapterArtifactLayout.fromArtifactFileName(fileName)
        val primary = parent.findFile(layout.manifestFileName)
        if (primary != null && primary.exists()) {
            return ArtifactManifestProbe(exists = true, manifest = readManifestFile(primary))
        }

        val backup = parent.findFile(AtomicChapterDocuments.backupNameFor(layout.manifestFileName))
        if (backup == null || !backup.exists()) return ArtifactManifestProbe(exists = false, manifest = null)
        // A backup is persisted metadata too. If it exists but cannot be read,
        // propagate that failure instead of treating the chapter as cleanly new.
        return ArtifactManifestProbe(exists = true, manifest = readManifestFile(backup))
    }

    private fun readManifestFile(file: UniFile): ChapterArtifactManifest =
        file.openInputStream().use { input ->
            ArtifactDocumentJson.decodeFromStream<ChapterArtifactManifest>(input)
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
        val chapterIdentity = privateStorageIdentity ?: buildString {
            append(parent.filePath ?: parent.uri?.toString() ?: "<no-parent>")
            append(':')
            append(fileName)
        }
        val journalReplay = privateStorageRoot?.let { root ->
            val chapterIdentityHash = StageFingerprints.sha256Hex(chapterIdentity.toByteArray(Charsets.UTF_8))
            withContext(Dispatchers.IO) {
                ChapterJournalReplayReducer.replay(
                    epochs = ChapterJournalReplayReducer.readAppPrivateEpochs(root, chapterIdentity),
                    artifactResolver = ChapterJournalReplayReducer.artifactResolver(artifact, manifest),
                    expectedChapterIdentityHash = chapterIdentityHash,
                )
            }
        }

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
        val store = ChapterTranslationStore(
            initialPages = legacyPages,
            artifactStore = artifact,
            initialCommittedPages = committedPages,
            initialArtifactManifest = manifest,
            journalReplayResult = journalReplay,
            initialRecoveredPages = recoveredPages,
            initialUnverifiedManifestFallbackPageKeys = unverifiedFallbackKeys,
            initialRetiredCleanedImages = retiredCleanedImages,
            artifactParent = parent,
            artifactFileName = fileName,
            privateStorageRoot = privateStorageRoot,
            privateStorageIdentity = privateStorageIdentity,
            journalStorageFactoryForOpen = journalStorageFactory,
        )
        if (journalReplay != null) {
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
            store.statusLedger.installOpeningStatus(
                snapshot = StoreStatusSnapshot(
                    inventory = StoreStatusInventory(
                        expectedPageKeys = expectedKeys,
                        expectedPageCount = expectedCount,
                        expectedPageCountTrusted = authoritativeJournal ||
                            (manifest.expectedPageCountTrusted && unverifiedFallbackKeys.isEmpty()),
                    ),
                    durableFailures = manifestFailures + journalReplay.durableFailures,
                    pageOutcomes = journalReplay.pageOutcomes.toMap(),
                ),
                trustEstablished = authoritativeJournal,
            )
            if (!authoritativeJournal) {
                val seedPages = artifactPageStates.mapNotNull { (pageKey, opened) ->
                    if (opened.hasContentBackedSnapshot && pageKey !in outcomes) {
                        pageKey to opened.livePage
                    } else {
                        null
                    }
                }.toMap()
                val seedInventory = ChapterJournalInventorySnapshot(
                    expectedPageKeys = expectedKeys,
                    expectedPageCount = expectedCount,
                    sourceShaByPageKey = manifest.sourceShaByPageKey,
                    expectedPageCountTrusted = manifest.expectedPageCountTrusted && unverifiedFallbackKeys.isEmpty(),
                )
                store.seedJournalFromManifest(seedPages, seedInventory)
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
