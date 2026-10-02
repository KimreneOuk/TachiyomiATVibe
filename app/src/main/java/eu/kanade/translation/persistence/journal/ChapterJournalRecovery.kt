package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.StageFingerprints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * One journal epoch supplied to the recovery reducer. Segments are kept as
 * immutable byte arrays so the reducer never holds a file handle while it is
 * decoding or applying records.
 */
internal data class ChapterJournalReplayEpoch(
    val order: ChapterJournalFormat.EpochOrderKey,
    val segments: List<ChapterJournalReplaySegment>,
)

internal data class ChapterJournalReplaySegment(
    val index: Long,
    val bytes: ByteArray,
)

/** Resolves the artifact identity named by one journal state record. */
internal fun interface ChapterJournalArtifactIdentityResolver {
    /** Return true only when the referenced artifact bytes re-derive [expectedHash]. */
    fun matches(
        pageKey: String,
        expectedHash: String,
        state: PublishedPageTranslation,
    ): Boolean
}

internal data class ChapterJournalReplayResult(
    /** Reconstructed pages; tombstones and invalid records are absent. */
    val pages: Map<String, PublishedPageTranslation>,
    /** Pages whose winning journal record could not be artifact-verified. */
    val invalidPageKeys: Set<String>,
    /** Last valid inventory declaration in the replayed prefix. */
    val inventory: ChapterJournalInventoryRecord?,
    /** Expected page keys include zero-block pages declared by inventory. */
    val expectedPageKeys: Set<String>,
    val expectedPageCount: Int,
    /** Inventory keys with neither a reconstructed page nor an invalid-page record. */
    val missingPageKeys: Set<String>,
    val appliedRecordCount: Int,
    val ignoredStaleRecordCount: Int,
    val validFrameCount: Int,
    val corruptEpochs: Set<ChapterJournalFormat.EpochOrderKey>,
) {
    val hasCompleteInventory: Boolean
        get() = inventory != null &&
            expectedPageCount >= expectedPageKeys.size &&
            missingPageKeys.isEmpty() &&
            pages.keys.containsAll(expectedPageKeys) &&
            pages.size == expectedPageCount
}

/**
 * Deterministic journal recovery. E16b deliberately keeps this reducer
 * separate from the live store: shadow-era legacy artifacts remain
 * authoritative until E16c explicitly activates journal recovery.
 */
internal object ChapterJournalReplayReducer {
    private val DEFAULT_JSON = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /**
     * Replay every valid frame prefix. Epochs are ordered by persisted ordinal;
     * state frames within an epoch are already ordered by their physical frame
     * sequence, which is equivalent to commitSeq order for durable frames.
     * The persisted epoch ordinal is the primary winner boundary across store
     * instances. Generation and fencing tokens may reset on restart, so they
     * only order records within one epoch; an old epoch's late append cannot
     * replace a record from a successor epoch.
     */
    fun replay(
        epochs: Collection<ChapterJournalReplayEpoch>,
        artifactResolver: ChapterJournalArtifactIdentityResolver? = null,
        expectedChapterIdentityHash: String? = null,
        json: Json = DEFAULT_JSON,
    ): ChapterJournalReplayResult {
        val orderedEpochs = epochs.sortedBy { it.order.epochOrdinal }
        require(orderedEpochs.map { it.order.epochOrdinal }.distinct().size == orderedEpochs.size) {
            "duplicate journal epoch ordinal"
        }

        val pages = LinkedHashMap<String, PublishedPageTranslation>()
        val winners = HashMap<String, Winner>()
        val invalidPages = LinkedHashSet<String>()
        val corruptEpochs = LinkedHashSet<ChapterJournalFormat.EpochOrderKey>()
        var inventory: ChapterJournalInventoryRecord? = null
        var applied = 0
        var ignoredStale = 0
        var validFrames = 0

        for (epoch in orderedEpochs) {
            val scan = scanEpoch(epoch)
            if (scan.corrupt) corruptEpochs += epoch.order
            validFrames += scan.frames.size
            var semanticCorruption = false
            // Inventory is epoch-local and must be inside this epoch's valid
            // prefix before any data or terminal frame can claim completeness.
            var epochInventorySeen = false
            for (frame in scan.frames) {
                if (frame.kind != ChapterJournalFormat.RecordKind.INVENTORY && !epochInventorySeen) {
                    semanticCorruption = true
                    break
                }
                when (frame.kind) {
                    ChapterJournalFormat.RecordKind.INVENTORY -> {
                        val root = versionedPayload(frame.payload, ChapterJournalInventoryRecord.SCHEMA_VERSION, json)
                        val decoded = root?.let {
                            runCatching { json.decodeFromJsonElement<ChapterJournalInventoryRecord>(it) }.getOrNull()
                        }
                        if (decoded == null ||
                            !decoded.isSemanticallyValid() ||
                            (
                                expectedChapterIdentityHash != null &&
                                    decoded.chapterIdentityHash != expectedChapterIdentityHash
                                )
                        ) {
                            semanticCorruption = true
                            break
                        }
                        inventory = decoded
                        epochInventorySeen = true
                    }

                    ChapterJournalFormat.RecordKind.FREE_STATE,
                    ChapterJournalFormat.RecordKind.PAID_STATE,
                    -> {
                        val root = versionedPayload(frame.payload, ChapterJournalRecord.SUPPORTED_SCHEMA_VERSIONS, json)
                        val record = root?.let {
                            runCatching { json.decodeFromJsonElement<ChapterJournalRecord>(it) }.getOrNull()
                        }
                        if (record == null || !record.isSemanticallyValid()) {
                            semanticCorruption = true
                            break
                        }
                        if (!record.isPageMutationSemanticallyValid()) {
                            semanticCorruption = true
                            break
                        }
                        if (applyRecord(
                                epoch = epoch,
                                commitSeq = checkNotNull(frame.commitSeq),
                                record = record,
                                pages = pages,
                                winners = winners,
                                invalidPages = invalidPages,
                                artifactResolver = artifactResolver,
                            )
                        ) {
                            applied++
                        } else {
                            ignoredStale++
                        }
                    }

                    ChapterJournalFormat.RecordKind.BULK_REPLACE,
                    ChapterJournalFormat.RecordKind.BULK_REKEY,
                    -> {
                        val root = versionedPayload(frame.payload, ChapterJournalBulkRecord.SCHEMA_VERSION, json)
                        val mutationRoots = root?.get("mutations")
                            ?.let { it as? JsonArray }
                        val mutationsValid = mutationRoots != null &&
                            mutationRoots.all { mutation ->
                                val mutationRoot = mutation as? JsonObject ?: return@all false
                                versionedPayload(mutationRoot, ChapterJournalRecord.SUPPORTED_SCHEMA_VERSIONS) != null
                            }
                        val bulk = root?.takeIf { mutationsValid }?.let {
                            runCatching { json.decodeFromJsonElement<ChapterJournalBulkRecord>(it) }.getOrNull()
                        }
                        val operationMatchesKind = bulk != null &&
                            when (frame.kind) {
                                ChapterJournalFormat.RecordKind.BULK_REPLACE -> bulk.operation == "replace_all"
                                ChapterJournalFormat.RecordKind.BULK_REKEY -> bulk.operation == "rekey_pages"
                                else -> false
                            }
                        if (bulk == null || !bulk.isSemanticallyValid() || !operationMatchesKind) {
                            semanticCorruption = true
                            break
                        }
                        for (record in bulk.mutations) {
                            if (applyRecord(
                                    epoch = epoch,
                                    commitSeq = checkNotNull(frame.commitSeq),
                                    record = record,
                                    pages = pages,
                                    winners = winners,
                                    invalidPages = invalidPages,
                                    artifactResolver = artifactResolver,
                                )
                            ) {
                                applied++
                            } else {
                                ignoredStale++
                            }
                        }
                    }

                    // Metadata markers never mutate page state. DEFUNCT is not
                    // a replay cutoff; later credited frames are processed.
                    ChapterJournalFormat.RecordKind.TERMINAL_LAG,
                    ChapterJournalFormat.RecordKind.DEFUNCT,
                    ChapterJournalFormat.RecordKind.TERMINAL_PAYLOAD,
                    -> {
                        val root = versionedPayload(frame.payload, ChapterJournalRecord.SUPPORTED_SCHEMA_VERSIONS, json)
                        val record = root?.let {
                            runCatching { json.decodeFromJsonElement<ChapterJournalRecord>(it) }.getOrNull()
                        }
                        if (record == null || !record.isSemanticallyValid()) {
                            semanticCorruption = true
                            break
                        }
                    }
                }
            }
            if (semanticCorruption) corruptEpochs += epoch.order
        }

        val expectedKeys = inventory?.expectedPageKeys?.toSet().orEmpty()
        val expectedCount = inventory?.expectedPageCount ?: 0
        val missing = expectedKeys - pages.keys - invalidPages
        return ChapterJournalReplayResult(
            pages = pages.toMap(),
            invalidPageKeys = invalidPages,
            inventory = inventory,
            expectedPageKeys = expectedKeys,
            expectedPageCount = expectedCount,
            missingPageKeys = missing,
            appliedRecordCount = applied,
            ignoredStaleRecordCount = ignoredStale,
            validFrameCount = validFrames,
            corruptEpochs = corruptEpochs,
        )
    }

    /** Read epochs from the app-private journal root without retaining handles. */
    fun readAppPrivateEpochs(
        filesDir: File,
        chapterIdentity: String,
    ): List<ChapterJournalReplayEpoch> {
        val chapterHash = StageFingerprints.sha256Hex(chapterIdentity.toByteArray(Charsets.UTF_8))
        val root = File(File(filesDir, "translation-journal-v1"), chapterHash)
        if (!root.exists()) return emptyList()
        if (!root.isDirectory) throw IOException("chapter journal path is not a directory")
        val rootChildren = root.listFiles() ?: throw IOException("unable to list chapter journal epochs")
        val rootCanonical = root.canonicalFile.toPath()
        return rootChildren
            .asSequence()
            .filter(File::isDirectory)
            .map { directory ->
                val match = EPOCH_NAME.matchEntire(directory.name)
                    ?: throw IOException("unrecognized journal epoch directory: ${directory.name}")
                val ordinal = match.groupValues[1].toLongOrNull()
                    ?: throw IOException("invalid journal epoch ordinal: ${directory.name}")
                val generation = match.groupValues[2].toLongOrNull()
                    ?: throw IOException("invalid journal epoch generation: ${directory.name}")
                val sessionId = runCatching { UUID.fromString(match.groupValues[3]) }.getOrNull()
                    ?: throw IOException("invalid journal epoch session: ${directory.name}")
                if (!directory.canonicalFile.toPath().startsWith(rootCanonical)) {
                    throw IOException("journal epoch escaped chapter root: ${directory.name}")
                }
                val epochCanonical = directory.canonicalFile.toPath()
                val children = directory.listFiles()
                // An unreadable directory is not an empty, never-written
                // epoch. Represent it as an invalid first segment so scanning
                // reports corruption while successor epochs remain replayable.
                val segments = if (children == null) {
                    listOf(ChapterJournalReplaySegment(0L, byteArrayOf()))
                } else {
                    val validSegments = children
                        .asSequence()
                        .filter { it.isFile }
                        .mapNotNull { segment ->
                            val segmentMatch = SEGMENT_NAME.matchEntire(segment.name) ?: return@mapNotNull null
                            val index = segmentMatch.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                            if (!segment.canonicalFile.toPath().startsWith(epochCanonical)) {
                                return@mapNotNull ChapterJournalReplaySegment(index, byteArrayOf())
                            }
                            ChapterJournalReplaySegment(
                                index,
                                runCatching { segment.readBytes() }.getOrDefault(byteArrayOf()),
                            )
                        }
                        .sortedBy(ChapterJournalReplaySegment::index)
                        .toList()
                    val unrecognizedEntries = children.filter { child ->
                        !child.isFile || !SEGMENT_NAME.matches(child.name)
                    }
                    if (unrecognizedEntries.isEmpty()) {
                        validSegments
                    } else {
                        // Preserve the recognized prefix, then fail closed at
                        // the first unknown entry. A malformed segment name
                        // must not silently shorten the recovered history.
                        val afterLast = (validSegments.maxOfOrNull { it.index } ?: -1L) + 1L
                        validSegments + ChapterJournalReplaySegment(afterLast, byteArrayOf())
                    }
                }
                ChapterJournalReplayEpoch(
                    order = ChapterJournalFormat.EpochOrderKey(generation, ordinal, sessionId),
                    segments = segments,
                )
            }
            .sortedBy { it.order.epochOrdinal }
            .toList()
    }

    /** Build the production artifact identity adapter without trusting metadata alone. */
    fun artifactResolver(
        engine: ChapterArtifactEngine,
        manifest: ChapterArtifactManifest,
    ): ChapterJournalArtifactIdentityResolver = ChapterJournalArtifactIdentityResolver { pageKey, expectedHash, state ->
        // The frame carries the state that replay will apply. Bind that exact
        // value to the same semantic identity as the manifest reference; a
        // valid artifact pointer must not authenticate a different payload.
        if (StageFingerprints.pageSnapshot(state) != expectedHash) {
            return@ChapterJournalArtifactIdentityResolver false
        }
        val page = manifest.pages[pageKey] ?: return@ChapterJournalArtifactIdentityResolver false
        // Metadata narrows the lookup to a pointer that claims this exact
        // semantic identity; the bytes are still re-read and re-hashed below.
        // Never fall back to an arbitrary current pointer when a record names
        // an older or missing artifact.
        val references = listOfNotNull(
            page.candidate?.let { it.pageSnapshotFileName to it.pageSnapshotFingerprint },
            page.committed?.let { it.pageSnapshotFileName to it.translationFingerprint },
            page.previousCommitted?.let { it.pageSnapshotFileName to it.translationFingerprint },
        ).filter { (_, fingerprint) -> fingerprint == expectedHash }
            .mapNotNull { (fileName, _) -> fileName }
            .distinct()
        references.any { fileName ->
            runCatching {
                val artifact = engine.readPageSnapshot(fileName) ?: return@runCatching false
                if (StageFingerprints.pageSnapshot(artifact) != expectedHash) return@runCatching false
                if (artifact.sourceFileName != pageKey ||
                    artifact.cleanedImageName != state.cleanedImageName ||
                    artifact.cleanedImageContentHash != state.cleanedImageContentHash
                ) {
                    return@runCatching false
                }
                val cleanedImageName = artifact.cleanedImageName
                val cleanedImageHash = artifact.cleanedImageContentHash
                if (cleanedImageName == null) {
                    cleanedImageHash == null
                } else {
                    cleanedImageHash != null &&
                        engine.hasVerifiedCleanedImageIdentity(cleanedImageName, cleanedImageHash)
                }
            }.getOrDefault(false)
        }
    }

    private data class Winner(
        val generation: Long,
        val fencingToken: Long,
        val epochOrdinal: Long,
        val commitSeq: Long,
    )

    private data class ReplayFrame(
        val frameSeq: Long,
        val commitSeq: Long?,
        val kind: ChapterJournalFormat.RecordKind,
        val payload: ByteArray,
    )

    private data class EpochScan(
        val frames: List<ReplayFrame>,
        val corrupt: Boolean,
    )

    private fun scanEpoch(epoch: ChapterJournalReplayEpoch): EpochScan {
        var expectedFrameSeq = 1L
        var expectedCommitSeq = 1L
        var lastCommitSeq = 0L
        var terminalLagSeen = false
        var terminalPayloadSeen = false
        var expectedSegmentIndex = 0L
        var corrupt = false
        val frames = ArrayList<ReplayFrame>()

        for (segment in epoch.segments) {
            if (segment.index != expectedSegmentIndex) {
                corrupt = true
                break
            }
            val scan = ChapterJournalFormat.scanSegment(
                bytes = segment.bytes,
                expectedSegmentIndex = segment.index,
                expectedGeneration = epoch.order.storeGeneration,
                expectedEpochOrdinal = epoch.order.epochOrdinal,
                expectedSessionId = epoch.order.sessionId,
                firstExpectedFrameSeq = expectedFrameSeq,
                firstExpectedCommitSeq = expectedCommitSeq,
                lastCommitSeq = lastCommitSeq,
                terminalLagSeen = terminalLagSeen,
                terminalPayloadSeen = terminalPayloadSeen,
            )
            if (!scan.validHeader) {
                corrupt = true
                break
            }
            frames += scan.frames.map { frame ->
                ReplayFrame(frame.frameSeq, frame.commitSeq, frame.kind, frame.payload)
            }
            expectedFrameSeq = scan.nextFrameSeq
            expectedCommitSeq = scan.nextCommitSeq
            lastCommitSeq = scan.frames.asReversed().firstNotNullOfOrNull { it.commitSeq } ?: lastCommitSeq
            terminalLagSeen = scan.terminalLagSeen
            terminalPayloadSeen = scan.terminalPayloadSeen
            if (scan.stoppedAtInvalidFrame) {
                corrupt = true
                break
            }
            expectedSegmentIndex++
        }
        return EpochScan(frames, corrupt)
    }

    private fun applyRecord(
        epoch: ChapterJournalReplayEpoch,
        commitSeq: Long,
        record: ChapterJournalRecord,
        pages: MutableMap<String, PublishedPageTranslation>,
        winners: MutableMap<String, Winner>,
        invalidPages: MutableSet<String>,
        artifactResolver: ChapterJournalArtifactIdentityResolver?,
    ): Boolean {
        val previous = winners[record.pageKey]
        if (previous != null) {
            val epochOrdinal = epoch.order.epochOrdinal
            val staleWithinEpoch = epochOrdinal == previous.epochOrdinal &&
                (
                    record.generation < previous.generation ||
                        (record.generation == previous.generation && record.fencingToken < previous.fencingToken)
                    )
            if (epochOrdinal < previous.epochOrdinal || staleWithinEpoch) return false
        }
        val winner = Winner(record.generation, record.fencingToken, epoch.order.epochOrdinal, commitSeq)
        val state = record.state
        if (state != null) {
            val hash = record.artifactContentHash
            val cleanedImageIdentityMatches = record.cleanedImageName == state.cleanedImageName &&
                record.cleanedImageContentHash == state.cleanedImageContentHash &&
                when (state.cleanedImageName) {
                    null -> state.cleanedImageContentHash == null
                    else -> state.cleanedImageContentHash?.let(SHA256_HEX::matches) == true
                }
            // Bind the payload replay will apply to the identity claimed by
            // the record before consulting any artifact lookup. This remains
            // mandatory even for injected resolvers: a valid pointer cannot
            // authenticate a different embedded state.
            val embeddedStateMatches = cleanedImageIdentityMatches &&
                hash != null &&
                runCatching { StageFingerprints.pageSnapshot(state) == hash }.getOrDefault(false)
            val artifactMatches = hash != null &&
                embeddedStateMatches &&
                artifactResolver != null &&
                runCatching { artifactResolver.matches(record.pageKey, hash, state) }.getOrDefault(false)
            if (!artifactMatches) {
                pages.remove(record.pageKey)
                winners[record.pageKey] = winner
                invalidPages += record.pageKey
                return true
            }
            pages[record.pageKey] = state
            winners[record.pageKey] = winner
            invalidPages.remove(record.pageKey)
        } else {
            pages.remove(record.pageKey)
            winners[record.pageKey] = winner
            invalidPages.remove(record.pageKey)
        }
        return true
    }

    private fun versionedPayload(
        bytes: ByteArray,
        expectedVersion: Int,
        json: Json = DEFAULT_JSON,
    ): JsonObject? = versionedPayload(bytes, setOf(expectedVersion), json)

    private fun versionedPayload(
        bytes: ByteArray,
        supportedVersions: Set<Int>,
        json: Json = DEFAULT_JSON,
    ): JsonObject? {
        val root = runCatching {
            json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        }.getOrNull() ?: return null
        // A serializer default must not make a legacy frame with no declared schema look current.
        val schemaVersion = root["schemaVersion"]?.let {
            runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
        } ?: return null
        return root.takeIf { schemaVersion in supportedVersions }
    }

    private fun versionedPayload(root: JsonObject, expectedVersion: Int): JsonObject? {
        return versionedPayload(root, setOf(expectedVersion))
    }

    private fun versionedPayload(root: JsonObject, supportedVersions: Set<Int>): JsonObject? {
        val schemaVersion = root["schemaVersion"]?.let {
            runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
        } ?: return null
        return root.takeIf { schemaVersion in supportedVersions }
    }

    private fun ChapterJournalInventoryRecord.isSemanticallyValid(): Boolean =
        chapterIdentityHash.isNotBlank() &&
            expectedPageCount >= 0 &&
            expectedPageCount >= expectedPageKeys.size &&
            expectedPageKeys.all(String::isNotBlank) &&
            expectedPageKeys.distinct().size == expectedPageKeys.size &&
            sourceFingerprint.isNotBlank()

    private fun ChapterJournalRecord.isSemanticallyValid(): Boolean =
        generation >= 0L &&
            fencingToken >= 0L &&
            pageVersion >= 0L &&
            artifactContentHash?.let(SHA256_HEX::matches) != false &&
            cleanedImageContentHash?.let(SHA256_HEX::matches) != false &&
            cleanedImageName?.let { it.isNotBlank() && '/' !in it && '\\' !in it } != false &&
            terminalCommitSeq?.let { it >= 0L } != false &&
            terminalObservedPayloadBytes?.let { it >= 0 } != false &&
            (state == null || (pageKey.isNotBlank() && state.sourceFileName == pageKey))

    private fun ChapterJournalRecord.isPageMutationSemanticallyValid(): Boolean = pageKey.isNotBlank()

    private fun ChapterJournalBulkRecord.isSemanticallyValid(): Boolean =
        operation.isNotBlank() &&
            mutations.all { it.isSemanticallyValid() && it.isPageMutationSemanticallyValid() } &&
            mutations.map { it.pageKey }.distinct().size == mutations.size

    private val EPOCH_NAME = Regex("epoch-([0-9]{20})-g([0-9]+)-([0-9a-fA-F-]{36})")
    private val SEGMENT_NAME = Regex("segment-([0-9]{8})\\.tjr")

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
}
