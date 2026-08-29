package eu.kanade.translation.artifact

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.legacy.LegacyFlatFileDecoder
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Legacy input gathering + migration orchestration moved verbatim from the
 * `ChapterTranslationStore` companion (T909 Phase 3b). The store's
 * `open`/`openArtifact` factories and the migration lock accessor delegate
 * here; the cleaned-image probe test seam stays on
 * [ChapterTranslationStore.artifactImageProbe].
 */
internal object LegacyChapterMigrationSource {

    /**
     * The flat page map predates the artifact schema and may contain fields
     * removed by a later refactor. Unknown keys are additive compatibility
     * data here, so they must not make an otherwise valid page unreadable.
     */
    private val legacyPageJson = LegacyFlatFileDecoder.legacyPageJson

    private val artifactImageProbe get() = ChapterTranslationStore.artifactImageProbe

    internal fun openInternal(
        translationFile: UniFile?,
        parent: UniFile?,
        fileName: String,
    ): ChapterTranslationStore {
        var legacyCorrupt = false
        val legacyBytes = if (translationFile?.exists() == true) {
            runCatching { translationFile.openInputStream().use { it.readBytes() } }
                .onFailure { legacyCorrupt = true }
                .getOrNull()
        } else {
            null
        }
        val existing = if (legacyBytes != null) {
            try {
                val map = legacyPageJson.decodeFromStream<Map<String, PageTranslation>>(legacyBytes.inputStream())
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
        val artifactLayout = ChapterArtifactLayout.fromTranslationFileName(fileName)
        val artifactManifestFileExists = parent?.findFile(artifactLayout.manifestFileName)?.exists() == true
        val artifactLoad = if (translationFile?.exists() == true || artifactManifestFileExists) {
            val migrationLock = artifactMigrationLock(parent, fileName)
            synchronized(migrationLock) {
                runCatching {
                    migrateArtifactManifest(translationFile, parent, fileName, legacyBytes, existing, legacyCorrupt)
                }.onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "TachiyomiAT artifact manifest migration skipped: reason=open failure"
                    }
                }.getOrNull()
            }
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
            artifactParent = parent,
            artifactFileName = fileName,
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
     * TachiyomiAT: identity-aware, non-destructive lazy rescue of the
     * legacy flat translation record into the chapter artifact manifest.
     * Reads the legacy file's exact identity and the companion cleaned
     * images (bounded decode probe), then publishes and re-reads the
     * complete graph before one final ARTIFACTS authority switch. Once
     * switched, the flat file is never consulted for page rehydration;
     * preservation is retried from the durable migration marker. Chapters
     * without a legacy translation file are left untouched.
     */
    private fun migrateArtifactManifest(
        translationFile: UniFile?,
        parent: UniFile?,
        fileName: String,
        legacyBytes: ByteArray?,
        legacyPages: Map<String, PageTranslation>,
        legacyCorrupt: Boolean,
    ): ArtifactLoad {
        val parent = parent ?: return ArtifactLoad(
            ChapterArtifactStore(
                AtomicChapterDocuments(UniFileChapterDocumentIo(translationFile ?: error("translation parent unavailable"))),
                ChapterArtifactLayout.fromTranslationFileName(fileName),
            ),
            ChapterArtifactManifest(),
            emptyMap(),
            legacyPages,
        )
        val layout = ChapterArtifactLayout.fromTranslationFileName(fileName)
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
        val artifactStore = ChapterArtifactStore(documents, layout, artifactImageProbe)
        val identity = legacyIdentityOf(legacyBytes, translationFile?.lastModified() ?: 0L)
        val companionImages = parent.findFile("${layout.chapterKey}_images")
        val facts = legacyPages.mapValues { (_, page) ->
            val cleaned = cleanedFileValidationOf(page, companionImages)
            LegacyPageFacts(
                page = page,
                cleanedFileState = cleaned.state,
                cleanedImageDimensions = cleaned.dimensions,
            )
        }
        val glossaryFileName = legacyGlossaryName(fileName)
        val glossaryFile = parent.findFile(glossaryFileName)
        val glossaryFilePresent = glossaryFile?.exists() == true
        val glossaryBytes = runCatching {
            glossaryFile?.takeIf { it.exists() }?.openInputStream()?.use { input -> input.readBytes() }
        }.getOrNull()
        val glossaryLastModified = runCatching { glossaryFile?.lastModified() ?: 0L }.getOrDefault(0L)
        var glossaryFileCorrupt = false
        val glossary = if (glossaryFilePresent) {
            runCatching {
                legacyPageJson.decodeFromStream<Map<String, String>>(
                    glossaryBytes?.inputStream() ?: error("glossary bytes unavailable"),
                )
            }.onFailure { glossaryFileCorrupt = true }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val migratedAtEpochMs = System.currentTimeMillis()
        val loaded = artifactStore.loadOrMigrate(
            LegacyChapterSnapshot(
                pages = facts,
                glossary = glossary,
                translationFileCorrupt = legacyCorrupt,
                glossaryFileCorrupt = glossaryFileCorrupt,
                legacyIdentity = identity,
                sourceFileName = fileName,
                glossaryFileName = glossaryFileName,
                glossaryIdentity = legacyIdentityOf(
                    glossaryBytes,
                    glossaryLastModified,
                ),
                migratedByVersionCode = BuildConfig.VERSION_CODE.toLong(),
                migratedAtEpochMs = migratedAtEpochMs,
            ),
        )
        var manifest = loaded.manifest
        if (manifest.authority == ManifestAuthority.ARTIFACTS) {
            manifest = artifactStore.reconcileLegacyPreservation(manifest)
            // Verification is chapter-open scoped. The first rescue is
            // stamped with this version and therefore cannot delete its
            // recovery source; only a later open can pass the health gate.
            val migration = manifest.legacyMigration
            val needsHealthVerification = migration == null ||
                migration.health != eu.kanade.translation.artifact.LegacyMigrationHealth.VERIFIED ||
                migration.sourcePreservation in setOf(
                    eu.kanade.translation.artifact.LegacyPreservationState.INTENT,
                    eu.kanade.translation.artifact.LegacyPreservationState.PRESERVED,
                ) ||
                migration.glossaryPreservation in setOf(
                    eu.kanade.translation.artifact.LegacyPreservationState.INTENT,
                    eu.kanade.translation.artifact.LegacyPreservationState.PRESERVED,
                )
            if (needsHealthVerification) {
                manifest = artifactStore.verifyLegacyArtifactHealth(
                    manifest = manifest,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    hasActiveLease = false,
                ).manifest
            }
        }
        val committedPages = manifest.pages.mapNotNull { (pageKey, record) ->
            val committed = record.committed ?: return@mapNotNull null
            val snapshot = artifactStore.readPageSnapshot(committed.pageSnapshotFileName)
                ?: legacyPages[pageKey]?.takeIf { manifest.authority == ManifestAuthority.LEGACY }
            snapshot?.let { pageKey to it }
        }.toMap()
        val livePages = when (manifest.authority) {
            ManifestAuthority.LEGACY -> legacyPages
            ManifestAuthority.ARTIFACTS -> manifest.pages.mapNotNull { (pageKey, record) ->
                val candidateSnapshot = artifactStore.readPageSnapshot(record.candidate?.pageSnapshotFileName)
                val committedSnapshot = committedPages[pageKey]
                (candidateSnapshot ?: committedSnapshot)?.let { pageKey to it }
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

    internal fun legacyGlossaryName(fileName: String): String =
        fileName.substringBeforeLast('.') + ".glossary.json"

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

    private const val IMAGE_DIMENSION_TOLERANCE_PX = 1

    private val ARTIFACT_MIGRATION_LOCKS = ConcurrentHashMap<String, Any>()

    internal fun artifactMigrationLock(parent: UniFile?, fileName: String): Any {
        val parentKey = parent?.filePath ?: parent?.uri?.toString() ?: "<unknown>"
        return ARTIFACT_MIGRATION_LOCKS.computeIfAbsent("$parentKey:$fileName") { Any() }
    }
}
