package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.StageFingerprints
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class ChapterJournalRecoveryTest {
    @TempDir
    lateinit var tempRoot: File

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    private fun replay(
        epochs: Collection<ChapterJournalReplayEpoch>,
        artifactResolver: ChapterJournalArtifactIdentityResolver = ChapterJournalArtifactIdentityResolver { _, hash, state ->
            StageFingerprints.pageSnapshot(state) == hash
        },
        expectedChapterIdentityHash: String? = null,
    ) = ChapterJournalReplayReducer.replay(
        epochs = epochs,
        artifactResolver = artifactResolver,
        expectedChapterIdentityHash = expectedChapterIdentityHash,
        json = json,
    )

    @Test
    fun `successor epoch beats higher generation and token including an old epoch late append`() {
        val old = page("page", "old")
        val lateOldAppend = page("page", "late-old-append")
        val current = page("page", "current")
        val epochs = listOf(
            epoch(
                generation = 9L,
                ordinal = 20L,
                frames = listOf(
                    stateFrame(1L, old, generation = 9L, fencingToken = 11L),
                    defunctFrame(2L),
                    stateFrame(2L, lateOldAppend, generation = 9L, fencingToken = 12L),
                ),
            ),
            epoch(
                generation = 1L,
                ordinal = 21L,
                frames = listOf(stateFrame(1L, current, generation = 1L, fencingToken = 0L)),
            ),
        )

        val result = replay(epochs)

        result.pages["page.jpg"] shouldBe current
        result.invalidPageKeys shouldBe emptySet()
        result.ignoredStaleRecordCount shouldBe 0
    }

    @Test
    fun `older generation and fencing token are stale within the same epoch`() {
        val current = page("page", "current")
        val staleGeneration = page("page", "stale-generation")
        val staleToken = page("page", "stale-token")
        val result = replay(
            listOf(
                epoch(
                    generation = 3L,
                    ordinal = 1L,
                    frames = listOf(
                        stateFrame(
                            1L,
                            current,
                            generation = 3L,
                            fencingToken = 5L,
                            hash = StageFingerprints.pageSnapshot(current),
                        ),
                        stateFrame(
                            2L,
                            staleGeneration,
                            generation = 2L,
                            fencingToken = 99L,
                            hash = "0".repeat(64),
                        ),
                        stateFrame(
                            3L,
                            staleToken,
                            generation = 3L,
                            fencingToken = 4L,
                            hash = "0".repeat(64),
                        ),
                    ),
                ),
            ),
            artifactResolver = resolver(mapOf("page.jpg" to current)),
        )

        result.pages["page.jpg"] shouldBe current
        result.invalidPageKeys shouldBe emptySet()
        result.ignoredStaleRecordCount shouldBe 2
    }

    @Test
    fun `latest mismatched artifact invalidates page instead of falling back to older state`() {
        val old = page("page", "old")
        val latest = page("page", "latest")
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(stateFrame(1L, old, generation = 0L, hash = StageFingerprints.pageSnapshot(old))),
                ),
                epoch(
                    generation = 0L,
                    ordinal = 2L,
                    frames = listOf(stateFrame(1L, latest, generation = 0L, hash = "0".repeat(64))),
                ),
            ),
            artifactResolver = resolver(mapOf("page.jpg" to old)),
        )

        result.pages shouldBe emptyMap()
        result.invalidPageKeys shouldBe setOf("page.jpg")
        result.missingPageKeys shouldBe emptySet()
    }

    @Test
    fun `rekey tombstones old key and preserves record bound image identity at new key`() {
        val imageBytes = "same image across rekey".encodeToByteArray()
        val imageHash = StageFingerprints.sha256Hex(imageBytes)
        val oldPage = PageTranslation(
            sourceFileName = "online/page.jpg",
            cleanedImageName = "page.cleaned.immutable.jpg",
            cleanedImageContentHash = imageHash,
            ocrStatus = StageStatus.READY,
        ).toPublishedPage()
        val newPage = oldPage.toDraft().apply { sourceFileName = "downloaded/page.jpg" }.toPublishedPage()
        val rekey = ChapterJournalBulkRecord(
            operation = "rekey_pages",
            mapping = mapOf("online/page.jpg" to "downloaded/page.jpg"),
            mutations = listOf(
                ChapterJournalRecord(
                    pageKey = "online/page.jpg",
                    generation = 1L,
                    fencingToken = 0L,
                    pageVersion = 2L,
                    state = null,
                ),
                ChapterJournalRecord(
                    pageKey = "downloaded/page.jpg",
                    generation = 1L,
                    fencingToken = 0L,
                    pageVersion = 3L,
                    state = newPage,
                    cleanedImageName = newPage.cleanedImageName,
                    cleanedImageContentHash = newPage.cleanedImageContentHash,
                    artifactContentHash = StageFingerprints.pageSnapshot(newPage),
                ),
            ),
        )
        val result = replay(
            listOf(
                epoch(
                    1L,
                    9L,
                    listOf(
                        bulkFrame(
                            1L,
                            rekey,
                            kind = ChapterJournalFormat.RecordKind.BULK_REKEY,
                        ),
                    ),
                ),
            ),
            artifactResolver = ChapterJournalArtifactIdentityResolver { pageKey, expectedHash, state ->
                pageKey == "downloaded/page.jpg" &&
                    state.sourceFileName == pageKey &&
                    state.cleanedImageName == "page.cleaned.immutable.jpg" &&
                    state.cleanedImageContentHash == imageHash &&
                    StageFingerprints.pageSnapshot(state) == expectedHash
            },
        )

        result.pages.keys shouldContainExactly listOf("downloaded/page.jpg")
        result.pages["downloaded/page.jpg"] shouldBe newPage
        result.invalidPageKeys shouldBe emptySet()
    }

    @Test
    fun `cleaned image without anchored digest is retryable page invalidity not epoch corruption`() {
        val legacyImagePage = PageTranslation(
            sourceFileName = "legacy-image.jpg",
            cleanedImageName = "legacy-image.cleaned.old.jpg",
            ocrStatus = StageStatus.READY,
        ).toPublishedPage()
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(stateFrame(1L, legacyImagePage, generation = 0L)),
                ),
            ),
        )

        result.pages shouldBe emptyMap()
        result.invalidPageKeys shouldBe setOf("legacy-image.jpg")
        result.pageOutcomes shouldBe mapOf("legacy-image.jpg" to ChapterJournalPageOutcome.INVALID)
        result.corruptEpochs shouldBe emptySet()
    }

    @Test
    fun `permissive artifact resolver cannot authenticate tampered embedded state`() {
        val embeddedState = page("page", "journal payload")
        val referencedArtifact = page("page", "artifact payload")
        val record = stateFrame(
            commitSeq = 1L,
            page = embeddedState,
            generation = 0L,
            hash = StageFingerprints.pageSnapshot(referencedArtifact),
        )

        val result = replay(
            listOf(epoch(generation = 0L, ordinal = 1L, frames = listOf(record))),
            artifactResolver = ChapterJournalArtifactIdentityResolver { _, _, _ -> true },
        )

        result.pages shouldBe emptyMap()
        result.invalidPageKeys shouldBe setOf("page.jpg")
    }

    @Test
    fun `tombstone prevents an older state from resurrecting a page`() {
        val old = page("old")
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(stateFrame(1L, old, generation = 0L)),
                ),
                epoch(
                    generation = 0L,
                    ordinal = 2L,
                    frames = listOf(tombstoneFrame(1L, generation = 1L)),
                ),
            ),
        )

        result.pages shouldBe emptyMap()
        result.ignoredStaleRecordCount shouldBe 0
        result.pageOutcomes shouldBe mapOf("old.jpg" to ChapterJournalPageOutcome.TOMBSTONED)
    }

    @Test
    fun `v1 inventory count equal to key count never implies trusted completeness`() {
        val legacyInventory = ChapterJournalInventoryRecord(
            schemaVersion = ChapterJournalInventoryRecord.LEGACY_SCHEMA_VERSION,
            chapterIdentityHash = "chapter",
            expectedPageKeys = listOf("same-count.jpg"),
            expectedPageCount = 1,
            sourceFingerprint = "legacy-v1-fingerprint",
            // Even a stray field cannot promote a v1 record to trusted.
            expectedPageCountTrusted = true,
        )
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(inventoryFrame(legacyInventory)),
                ),
            ),
            expectedChapterIdentityHash = "chapter",
        )

        result.expectedPageCount shouldBe result.expectedPageKeys.size
        result.inventory?.hasTrustedExpectedPageCount shouldBe false
        result.hasCompleteInventory shouldBe false
    }

    @Test
    fun `v2 inventory trust flag round trips and changes its fingerprint`() {
        val base = ChapterJournalInventorySnapshot(
            expectedPageKeys = setOf("same-count.jpg"),
            expectedPageCount = 1,
            sourceShaByPageKey = mapOf("same-count.jpg" to "source-sha"),
        )
        val untrusted = base.record("chapter")
        val trusted = base.copy(expectedPageCountTrusted = true).record("chapter")

        untrusted.schemaVersion shouldBe ChapterJournalInventoryRecord.SCHEMA_VERSION
        trusted.schemaVersion shouldBe ChapterJournalInventoryRecord.SCHEMA_VERSION
        untrusted.sourceFingerprint shouldNotBe trusted.sourceFingerprint
        untrusted.expectedPageCountTrusted shouldBe false
        trusted.expectedPageCountTrusted shouldBe true

        val replayUntrusted = replay(
            listOf(epoch(0L, 1L, listOf(inventoryFrame(untrusted)))),
            expectedChapterIdentityHash = "chapter",
        )
        val replayTrusted = replay(
            listOf(epoch(0L, 2L, listOf(inventoryFrame(trusted)))),
            expectedChapterIdentityHash = "chapter",
        )
        replayUntrusted.inventory?.hasTrustedExpectedPageCount shouldBe false
        replayTrusted.inventory?.hasTrustedExpectedPageCount shouldBe true
    }

    @Test
    fun `inventory keeps zero block pages and identifies missing retry tail`() {
        val textless = page("textless", blocks = emptyList())
        val inventory = ChapterJournalInventoryRecord(
            chapterIdentityHash = "chapter",
            expectedPageKeys = listOf("textless.jpg", "missing.jpg"),
            expectedPageCount = 2,
            sourceFingerprint = "inventory",
        )
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        inventoryFrame(inventory),
                        stateFrame(1L, textless, generation = 0L),
                    ),
                ),
            ),
            expectedChapterIdentityHash = "chapter",
        )

        result.pages.keys shouldContainExactly listOf("textless.jpg")
        result.expectedPageKeys shouldContainExactly listOf("textless.jpg", "missing.jpg")
        result.missingPageKeys shouldBe setOf("missing.jpg")
        result.expectedPageCount shouldBe 2
    }

    @Test
    fun `completed bulk applies mutations and ignores summary mapping`() {
        val page = page("bulk")
        val bulk = ChapterJournalBulkRecord(
            operation = "replace_all",
            mapping = emptyMap(),
            mutations = listOf(
                ChapterJournalRecord(
                    pageKey = "bulk.jpg",
                    generation = 0L,
                    fencingToken = 0L,
                    pageVersion = 1L,
                    state = page,
                    artifactContentHash = StageFingerprints.pageSnapshot(page),
                ),
            ),
        )
        val result = replay(
            listOf(epoch(0L, 1L, listOf(bulkFrame(1L, bulk)))),
        )

        result.pages["bulk.jpg"] shouldBe page
        result.appliedRecordCount shouldBe 1
    }

    @Test
    fun `defunct is not a replay cutoff for an already accepted late append`() {
        val beforeEviction = page("page", "before")
        val afterEviction = page("page", "after")
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        stateFrame(1L, beforeEviction, generation = 0L),
                        defunctFrame(2L),
                        stateFrame(2L, afterEviction, generation = 0L),
                    ),
                ),
            ),
        )

        result.pages["page.jpg"] shouldBe afterEviction
    }

    @Test
    fun `missing bulk frame leaves the pre-bulk prefix without fabricating mutations`() {
        val prefix = page("page", "prefix")
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(stateFrame(1L, prefix, generation = 0L)),
                ),
            ),
        )

        result.pages["page.jpg"] shouldBe prefix
        result.appliedRecordCount shouldBe 1
    }

    @Test
    fun `bad crc leaves the valid contiguous prefix and marks epoch corrupt`() {
        val first = stateFrame(1L, page("first"), generation = 0L)
        val second = stateFrame(2L, page("second"), generation = 0L)
        val corrupted = epoch(0L, 1L, listOf(first, second)).let { source ->
            val segment = source.segments.single()
            val bytes = segment.bytes.copyOf()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x7f).toByte()
            source.copy(segments = listOf(segment.copy(bytes = bytes)))
        }

        val result = replay(listOf(corrupted))

        result.pages.keys shouldContainExactly listOf("first.jpg")
        result.corruptEpochs.size shouldBe 1
        result.validFrameCount shouldBe 2
    }

    @Test
    fun `missing state schema stops only the affected epoch at its valid prefix`() {
        val prefix = page("prefix")
        val sameEpochSuffix = page("ignored-suffix")
        val successor = page("successor")
        val malformed = rawFrame(
            kind = ChapterJournalFormat.RecordKind.FREE_STATE,
            commitSeq = 2L,
            payload = """{"pageKey":"bad.jpg","generation":0,"fencingToken":0,"pageVersion":2,"state":null}""",
        )

        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        stateFrame(1L, prefix, generation = 0L),
                        malformed,
                        stateFrame(3L, sameEpochSuffix, generation = 0L),
                    ),
                ),
                epoch(0L, 2L, listOf(stateFrame(1L, successor, generation = 0L))),
            ),
        )

        result.pages.keys.toList() shouldContainExactly listOf("prefix.jpg", "successor.jpg")
        result.corruptEpochs shouldBe setOf(ChapterJournalFormat.EpochOrderKey(0L, 1L, UUID.nameUUIDFromBytes("0:1".toByteArray())))
        result.appliedRecordCount shouldBe 2
    }

    @Test
    fun `durable state before the epoch inventory is rejected at that prefix`() {
        val state = page("uninventoried")
        val source = epoch(
            generation = 0L,
            ordinal = 1L,
            frames = listOf(stateFrame(1L, state, generation = 0L)),
            includeInventory = false,
        )

        val result = replay(listOf(source))

        result.pages shouldBe emptyMap()
        result.corruptEpochs shouldBe setOf(source.order)
    }

    @Test
    fun `malformed state payload does not skip ahead within its epoch`() {
        val prefix = page("prefix")
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        stateFrame(1L, prefix, generation = 0L),
                        rawFrame(ChapterJournalFormat.RecordKind.FREE_STATE, 2L, "{not-json"),
                        stateFrame(3L, page("ignored"), generation = 0L),
                    ),
                ),
            ),
        )

        result.pages.keys shouldContainExactly listOf("prefix.jpg")
        result.corruptEpochs.size shouldBe 1
    }

    @Test
    fun `unsupported state and bulk schemas stop their own valid prefixes`() {
        val prefix = page("prefix")
        val unsupportedState = ChapterJournalRecord(
            schemaVersion = 99,
            pageKey = "unsupported-state.jpg",
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 2L,
            state = page("unsupported-state"),
        )
        val stateResult = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        stateFrame(1L, prefix, generation = 0L),
                        rawFrame(
                            ChapterJournalFormat.RecordKind.FREE_STATE,
                            2L,
                            json.encodeToString(unsupportedState),
                        ),
                        stateFrame(3L, page("ignored-state"), generation = 0L),
                    ),
                ),
            ),
        )

        val bulkMutation = ChapterJournalRecord(
            pageKey = "unsupported-bulk.jpg",
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 2L,
            state = page("unsupported-bulk"),
        )
        val unsupportedBulk = ChapterJournalBulkRecord(
            schemaVersion = 99,
            operation = "replace_all",
            mapping = mapOf("unsupported-bulk.jpg" to "unsupported-bulk.jpg"),
            mutations = listOf(bulkMutation),
        )
        val bulkResult = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 2L,
                    frames = listOf(
                        stateFrame(1L, prefix, generation = 0L),
                        rawFrame(
                            ChapterJournalFormat.RecordKind.BULK_REPLACE,
                            2L,
                            json.encodeToString(unsupportedBulk),
                        ),
                        stateFrame(3L, page("ignored-bulk"), generation = 0L),
                    ),
                ),
            ),
        )

        stateResult.pages.keys shouldContainExactly listOf("prefix.jpg")
        stateResult.corruptEpochs.size shouldBe 1
        bulkResult.pages.keys shouldContainExactly listOf("prefix.jpg")
        bulkResult.corruptEpochs.size shouldBe 1
    }

    @Test
    fun `unsupported inventory schema is corruption and does not replace prior inventory`() {
        val validInventory = ChapterJournalInventoryRecord(
            chapterIdentityHash = "chapter",
            expectedPageKeys = listOf("kept.jpg"),
            expectedPageCount = 1,
            sourceFingerprint = "valid-inventory",
        )
        val unsupported = json.encodeToString(validInventory.copy(schemaVersion = 99)).encodeToByteArray()
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        inventoryFrame(validInventory),
                        rawFrame(ChapterJournalFormat.RecordKind.INVENTORY, null, unsupported),
                        stateFrame(1L, page("ignored"), generation = 0L),
                    ),
                ),
            ),
            expectedChapterIdentityHash = "chapter",
        )

        result.inventory shouldBe validInventory
        result.expectedPageKeys shouldContainExactly listOf("kept.jpg")
        result.pages shouldBe emptyMap()
        result.corruptEpochs.size shouldBe 1
    }

    @Test
    fun `malformed inventory stops replay at that epoch frame`() {
        val inventory = ChapterJournalInventoryRecord(
            chapterIdentityHash = "chapter",
            expectedPageKeys = listOf("expected.jpg"),
            expectedPageCount = 1,
            sourceFingerprint = "inventory",
        )
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        inventoryFrame(inventory),
                        rawFrame(ChapterJournalFormat.RecordKind.INVENTORY, null, "{malformed"),
                        stateFrame(1L, page("ignored"), generation = 0L),
                    ),
                ),
            ),
            expectedChapterIdentityHash = "chapter",
        )

        result.inventory shouldBe inventory
        result.pages shouldBe emptyMap()
        result.corruptEpochs.size shouldBe 1
    }

    @Test
    fun `bulk mutation without explicit supported schema stops before applying the aggregate`() {
        val prefix = page("prefix")
        val bulk = ChapterJournalBulkRecord(
            operation = "replace_all",
            mapping = mapOf("bulk.jpg" to "bulk.jpg"),
            mutations = listOf(
                ChapterJournalRecord(
                    pageKey = "bulk.jpg",
                    generation = 0L,
                    fencingToken = 0L,
                    pageVersion = 2L,
                    state = page("bulk"),
                ),
            ),
        )
        val encoded = json.encodeToString(bulk)
        val withoutMutationSchema = encoded.replace(
            "\"mutations\":[{\"schemaVersion\":${ChapterJournalRecord.SCHEMA_VERSION},",
            "\"mutations\":[{",
        )
        (withoutMutationSchema != encoded) shouldBe true
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        stateFrame(1L, prefix, generation = 0L),
                        rawFrame(ChapterJournalFormat.RecordKind.BULK_REPLACE, 2L, withoutMutationSchema),
                        stateFrame(3L, page("ignored"), generation = 0L),
                    ),
                ),
            ),
        )

        result.pages.keys shouldContainExactly listOf("prefix.jpg")
        result.corruptEpochs.size shouldBe 1
    }

    @Test
    fun `malformed bulk payload stops the epoch without partially applying mutations`() {
        val prefix = page("prefix")
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        stateFrame(1L, prefix, generation = 0L),
                        rawFrame(ChapterJournalFormat.RecordKind.BULK_REPLACE, 2L, "{broken-bulk"),
                        stateFrame(3L, page("ignored"), generation = 0L),
                    ),
                ),
            ),
        )

        result.pages.keys shouldContainExactly listOf("prefix.jpg")
        result.corruptEpochs.size shouldBe 1
    }

    @Test
    fun `unknown state fields remain compatible when schema is explicitly supported`() {
        val state = page("forward-compatible")
        val record = ChapterJournalRecord(
            pageKey = "forward-compatible.jpg",
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 1L,
            state = state,
            artifactContentHash = StageFingerprints.pageSnapshot(state),
        )
        val jsonWithUnknownField = JsonObject(
            json.encodeToJsonElement(record).jsonObject + ("futureField" to JsonPrimitive(true)),
        )
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        rawFrame(
                            ChapterJournalFormat.RecordKind.FREE_STATE,
                            1L,
                            json.encodeToString(jsonWithUnknownField),
                        ),
                    ),
                ),
            ),
        )

        result.pages["forward-compatible.jpg"] shouldBe state
        result.corruptEpochs shouldBe emptySet()
    }

    @Test
    fun `E16a record schema remains readable while cleaned images without an anchored digest stay invalid`() {
        val legacyPlain = page("legacy-plain")
        val plainRecord = ChapterJournalRecord(
            schemaVersion = ChapterJournalRecord.LEGACY_SCHEMA_VERSION,
            pageKey = checkNotNull(legacyPlain.sourceFileName),
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 1L,
            state = legacyPlain,
            artifactContentHash = StageFingerprints.pageSnapshot(legacyPlain),
        )
        val legacyBulkPage = page("legacy-bulk")
        val bulkRecord = ChapterJournalRecord(
            schemaVersion = ChapterJournalRecord.LEGACY_SCHEMA_VERSION,
            pageKey = checkNotNull(legacyBulkPage.sourceFileName),
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 2L,
            state = legacyBulkPage,
            artifactContentHash = StageFingerprints.pageSnapshot(legacyBulkPage),
        )
        val bulk = ChapterJournalBulkRecord(
            operation = "replace_all",
            mapping = mapOf("legacy-bulk.jpg" to "legacy-bulk.jpg"),
            mutations = listOf(bulkRecord),
        )
        val legacyCleanedPage = page("legacy-cleaned", cleanedImageName = "cleaned.jpg")
        val cleanedRecord = ChapterJournalRecord(
            schemaVersion = ChapterJournalRecord.LEGACY_SCHEMA_VERSION,
            pageKey = checkNotNull(legacyCleanedPage.sourceFileName),
            generation = 0L,
            fencingToken = 0L,
            pageVersion = 3L,
            state = legacyCleanedPage,
            cleanedImageName = legacyCleanedPage.cleanedImageName,
            artifactContentHash = StageFingerprints.pageSnapshot(legacyCleanedPage),
        )

        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        rawFrame(
                            ChapterJournalFormat.RecordKind.FREE_STATE,
                            1L,
                            json.encodeToString(plainRecord),
                        ),
                        rawFrame(
                            ChapterJournalFormat.RecordKind.BULK_REPLACE,
                            2L,
                            json.encodeToString(bulk),
                        ),
                        rawFrame(
                            ChapterJournalFormat.RecordKind.FREE_STATE,
                            3L,
                            json.encodeToString(cleanedRecord),
                        ),
                    ),
                ),
            ),
        )

        result.pages.keys.toList() shouldContainExactly listOf("legacy-plain.jpg", "legacy-bulk.jpg")
        result.invalidPageKeys shouldBe setOf("legacy-cleaned.jpg")
        result.corruptEpochs shouldBe emptySet()
    }

    @Test
    fun `segment rollover preserves frame and commit order`() {
        val first = page("first")
        val second = page("second")
        val source = epochWithSegments(
            generation = 0L,
            ordinal = 1L,
            segments = listOf(
                listOf(stateFrame(1L, first, generation = 0L)),
                listOf(stateFrame(2L, second, generation = 0L)),
            ),
        )

        val result = replay(listOf(source))

        result.pages.keys.toSet() shouldBe setOf("first.jpg", "second.jpg")
        result.validFrameCount shouldBe 3
    }

    @Test
    fun `persisted epoch directories are read in ordinal order after restart`() {
        val chapterIdentity = "restart-chapter"
        val digest = StageFingerprints.sha256Hex(chapterIdentity.toByteArray(Charsets.UTF_8))
        val chapter = File(File(tempRoot, "translation-journal-v1"), digest).also { it.mkdirs() }
        val first = epoch(0L, 7L, listOf(stateFrame(1L, page("first"), generation = 0L)))
        val second = epoch(0L, 8L, listOf(stateFrame(1L, page("second"), generation = 0L)))
        listOf(first, second).forEach { source ->
            val dir = File(chapter, "epoch-${source.order.epochOrdinal.toString().padStart(20, '0')}-g0-${source.order.sessionId}")
            dir.mkdirs()
            dir.resolve("segment-00000000.tjr").writeBytes(source.segments.single().bytes)
        }

        val sources = ChapterJournalReplayReducer.readAppPrivateEpochs(tempRoot, chapterIdentity)

        sources.map { it.order.epochOrdinal } shouldContainExactly listOf(7L, 8L)
        sources.size shouldBe 2
    }

    @Test
    fun `unrecognized app-private journal directories are skipped without blocking valid epochs`() {
        val chapterIdentity = "stray-directory-chapter"
        val digest = StageFingerprints.sha256Hex(chapterIdentity.toByteArray(Charsets.UTF_8))
        val chapter = File(File(tempRoot, "translation-journal-v1"), digest).also { it.mkdirs() }
        File(chapter, "unrelated-directory").mkdirs()
        val valid = epoch(0L, 12L, listOf(stateFrame(1L, page("valid"), generation = 0L)))
        val validDir = File(
            chapter,
            "epoch-${valid.order.epochOrdinal.toString().padStart(20, '0')}-g${valid.order.storeGeneration}-${valid.order.sessionId}",
        ).also { it.mkdirs() }
        validDir.resolve("segment-00000000.tjr").writeBytes(valid.segments.single().bytes)

        val sources = ChapterJournalReplayReducer.readAppPrivateEpochs(tempRoot, chapterIdentity)

        sources.map { it.order.epochOrdinal } shouldContainExactly listOf(12L)
        replay(sources).pages.keys shouldBe setOf("valid.jpg")
    }

    @Test
    fun `epoch allocator resumes after the greatest persisted ordinal`() {
        val chapterIdentity = "restart-allocation-${UUID.randomUUID()}"
        val chapterHash = StageFingerprints.sha256Hex(chapterIdentity.toByteArray(Charsets.UTF_8))
        val chapter = File(File(tempRoot, "translation-journal-v1"), chapterHash).also { it.mkdirs() }
        val previousSession = UUID.randomUUID()
        File(
            chapter,
            "epoch-${7L.toString().padStart(20, '0')}-g4-$previousSession",
        ).mkdirs()

        val nextSession = UUID.randomUUID()
        val next = ChapterJournalWriter.allocateAppPrivateEpoch(
            filesDir = tempRoot,
            chapterIdentity = chapterIdentity,
            generation = 5L,
            sessionId = nextSession,
        )

        next.ordinal shouldBe 8L
        next.directory.name shouldBe "epoch-${8L.toString().padStart(20, '0')}-g5-$nextSession"
    }

    @Test
    fun `crash before completed bulk frame preserves only the prior prefix`() {
        val prior = page("prior", "before replace")
        val legacyAfterInterruptedBulk = mapOf(
            "prior.jpg" to page("prior", "partially replaced"),
            "other.jpg" to page("other", "partially added"),
        )
        val result = replay(
            listOf(
                epoch(
                    generation = 0L,
                    ordinal = 1L,
                    frames = listOf(
                        inventoryFrame(
                            ChapterJournalInventoryRecord(
                                chapterIdentityHash = "chapter",
                                expectedPageKeys = listOf("prior.jpg", "other.jpg", "missing.jpg"),
                                expectedPageCount = 3,
                                sourceFingerprint = "inventory",
                            ),
                        ),
                        stateFrame(1L, prior, generation = 0L),
                        // A crash during replaceAll occurs before the aggregate
                        // completion frame is handed to the writer.
                    ),
                ),
            ),
            expectedChapterIdentityHash = "chapter",
        )

        result.pages["prior.jpg"] shouldBe prior
        result.pages.containsKey("other.jpg") shouldBe false
        result.missingPageKeys shouldBe setOf("other.jpg", "missing.jpg")
        result.hasCompleteInventory shouldBe false
        (result.pages != legacyAfterInterruptedBulk) shouldBe true
    }

    private fun resolver(artifacts: Map<String, eu.kanade.translation.model.PublishedPageTranslation>) =
        ChapterJournalArtifactIdentityResolver { pageKey, expectedHash, _ ->
            artifacts[pageKey]?.let { StageFingerprints.pageSnapshot(it) == expectedHash } == true
        }

    private fun page(
        key: String,
        tag: String = key,
        blocks: List<TranslationBlock> = listOf(block(tag)),
        cleanedImageName: String? = null,
    ): eu.kanade.translation.model.PublishedPageTranslation =
        PageTranslation(
            sourceFileName = "$key.jpg",
            cleanedImageName = cleanedImageName,
            blocks = blocks.toMutableList(),
            ocrStatus = if (blocks.isEmpty()) StageStatus.READY else StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
        ).toPublishedPage()

    private fun block(tag: String) = TranslationBlock(
        text = "source-$tag",
        translation = "target-$tag",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private data class EncodedFrame(
        val kind: ChapterJournalFormat.RecordKind,
        val commitSeq: Long?,
        val payload: ByteArray,
    )

    private fun stateFrame(
        commitSeq: Long,
        page: eu.kanade.translation.model.PublishedPageTranslation,
        generation: Long,
        fencingToken: Long = 0L,
        hash: String? = StageFingerprints.pageSnapshot(page),
    ) = EncodedFrame(
        kind = ChapterJournalFormat.RecordKind.FREE_STATE,
        commitSeq = commitSeq,
        payload = json.encodeToString(
            ChapterJournalRecord(
                pageKey = page.sourceFileName ?: "page.jpg",
                generation = generation,
                fencingToken = fencingToken,
                pageVersion = commitSeq,
                state = page,
                cleanedImageName = page.cleanedImageName,
                cleanedImageContentHash = page.cleanedImageContentHash,
                artifactContentHash = hash,
            ),
        ).encodeToByteArray(),
    )

    private fun tombstoneFrame(commitSeq: Long, generation: Long) = EncodedFrame(
        kind = ChapterJournalFormat.RecordKind.FREE_STATE,
        commitSeq = commitSeq,
        payload = json.encodeToString(
            ChapterJournalRecord(
                pageKey = "old.jpg",
                generation = generation,
                fencingToken = 0L,
                pageVersion = commitSeq,
                state = null,
            ),
        ).encodeToByteArray(),
    )

    private fun defunctFrame(frameSeq: Long) = EncodedFrame(
        kind = ChapterJournalFormat.RecordKind.DEFUNCT,
        commitSeq = null,
        payload = json.encodeToString(
            ChapterJournalRecord(
                pageKey = "",
                generation = 0L,
                fencingToken = 0L,
                pageVersion = frameSeq,
                state = null,
                terminalReason = "store_evicted",
                terminalCommitSeq = 1L,
            ),
        ).encodeToByteArray(),
    )

    private fun inventoryFrame(record: ChapterJournalInventoryRecord) = EncodedFrame(
        kind = ChapterJournalFormat.RecordKind.INVENTORY,
        commitSeq = null,
        payload = json.encodeToString(record).encodeToByteArray(),
    )

    private fun rawFrame(
        kind: ChapterJournalFormat.RecordKind,
        commitSeq: Long?,
        payload: String,
    ) = rawFrame(kind, commitSeq, payload.encodeToByteArray())

    private fun rawFrame(
        kind: ChapterJournalFormat.RecordKind,
        commitSeq: Long?,
        payload: ByteArray,
    ) = EncodedFrame(kind, commitSeq, payload)

    private fun bulkFrame(
        commitSeq: Long,
        record: ChapterJournalBulkRecord,
        kind: ChapterJournalFormat.RecordKind = ChapterJournalFormat.RecordKind.BULK_REPLACE,
    ) = EncodedFrame(
        kind = kind,
        commitSeq = commitSeq,
        payload = json.encodeToString(record).encodeToByteArray(),
    )

    private fun epoch(
        generation: Long,
        ordinal: Long,
        frames: List<EncodedFrame>,
        includeInventory: Boolean = true,
    ): ChapterJournalReplayEpoch = epochWithSegments(
        generation = generation,
        ordinal = ordinal,
        segments = listOf(frames),
        includeInventory = includeInventory,
    )

    private fun epochWithSegments(
        generation: Long,
        ordinal: Long,
        segments: List<List<EncodedFrame>>,
        includeInventory: Boolean = true,
    ): ChapterJournalReplayEpoch {
        val session = UUID.nameUUIDFromBytes("$generation:$ordinal".toByteArray())
        var frameSeq = 1L
        val firstSegmentStartsWithInventory =
            segments.firstOrNull()?.firstOrNull()?.kind == ChapterJournalFormat.RecordKind.INVENTORY
        val seededSegments = if (includeInventory && !firstSegmentStartsWithInventory) {
            val pageKeys = segments.flatten().flatMap(::statePageKeys).distinct()
            val inventory = inventoryFrame(
                ChapterJournalInventoryRecord(
                    chapterIdentityHash = "chapter",
                    expectedPageKeys = pageKeys,
                    expectedPageCount = pageKeys.size,
                    sourceFingerprint = "fixture-inventory",
                ),
            )
            listOf(listOf(inventory) + segments.firstOrNull().orEmpty()) + segments.drop(1)
        } else {
            segments
        }
        val encodedSegments = seededSegments.mapIndexed { index, segmentFrames ->
            val output = ByteArrayOutputStream()
            output.write(ChapterJournalFormat.segmentHeader(index.toLong(), generation, ordinal, session))
            segmentFrames.forEach { frame ->
                output.write(ChapterJournalFormat.encodeFrame(frameSeq++, frame.commitSeq, frame.kind, frame.payload))
            }
            ChapterJournalReplaySegment(index.toLong(), output.toByteArray())
        }
        return ChapterJournalReplayEpoch(
            order = ChapterJournalFormat.EpochOrderKey(generation, ordinal, session),
            segments = encodedSegments,
        )
    }

    private fun statePageKeys(frame: EncodedFrame): List<String> = when (frame.kind) {
        ChapterJournalFormat.RecordKind.FREE_STATE,
        ChapterJournalFormat.RecordKind.PAID_STATE,
        -> runCatching {
            json.decodeFromString<ChapterJournalRecord>(frame.payload.decodeToString()).let { record ->
                listOfNotNull(record.pageKey.takeIf { record.state != null })
            }
        }.getOrDefault(emptyList())

        ChapterJournalFormat.RecordKind.BULK_REPLACE,
        ChapterJournalFormat.RecordKind.BULK_REKEY,
        -> runCatching {
            json.decodeFromString<ChapterJournalBulkRecord>(frame.payload.decodeToString()).mutations
                .mapNotNull { mutation -> mutation.pageKey.takeIf { mutation.state != null } }
        }.getOrDefault(emptyList())

        else -> emptyList()
    }
}
