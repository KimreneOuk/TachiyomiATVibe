package eu.kanade.translation.context

import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.contextual.TranslationPrompts
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.CommittedBundleMetadata
import eu.kanade.translation.persistence.artifact.DisplayBaseKind
import eu.kanade.translation.persistence.artifact.DisplayBaseReference
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.EvidenceStrength
import eu.kanade.translation.persistence.artifact.FactConflictState
import eu.kanade.translation.persistence.artifact.FactProvenance
import eu.kanade.translation.persistence.artifact.FactScope
import eu.kanade.translation.persistence.artifact.FactType
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.ProfileFact
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class RollingHistoryContextContractTest {

    @Test
    fun `T1 committed pairs follow natural page order then block order`() {
        val fixture = fixture(
            pages = listOf(
                historyPage("p2", 2, "source-2", "target-2"),
                historyPage("current", 3, "current-source", "current-target"),
                historyPage("p0", 0, "source-0a", "target-0a", "source-0b" to "target-0b"),
                historyPage("p1", 1, "source-1", "target-1"),
            ),
        )

        val prepared = fixture.prepare("current", LaneCapability.MANUAL)

        prepared.selectedPairs shouldContainExactly listOf(
            "source-0a" to "target-0a",
            "source-0b" to "target-0b",
            "source-1" to "target-1",
            "source-2" to "target-2",
        )
    }

    @Test
    fun `T2 history keeps only the newest 32 valid pairs`() {
        val pages = (0..40).map { index ->
            historyPage("p$index", index, "source-$index", "target-$index")
        }
        val fixture = fixture(pages)

        val pairs = fixture.prepare("p40", LaneCapability.MANUAL).selectedPairs

        pairs.size shouldBe TranslationContextChunkPlanner.MAX_ROLLING_PAIRS
        pairs.first() shouldBe ("source-8" to "target-8")
        pairs.last() shouldBe ("source-39" to "target-39")
    }

    @Test
    fun `T3 future committed pages never appear in earlier context`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "before", "BEFORE"),
                historyPage("current", 1, "current", "CURRENT"),
                historyPage("future", 2, "future", "FUTURE"),
            ),
        )

        val prepared = fixture.prepare("current", LaneCapability.MANUAL)

        prepared.selectedPairs shouldContainExactly listOf("before" to "BEFORE")
        prepared.rollingContext.contains("future => FUTURE") shouldBe false
    }

    @Test
    fun `T4 retry excludes the current unit even when its prior output is committed`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "prior", "PRIOR"),
                historyPage("current", 1, "current", "OLD CURRENT"),
            ),
        )

        val prepared = fixture.prepare("current", LaneCapability.MANUAL)

        prepared.selectedPairs shouldContainExactly listOf("prior" to "PRIOR")
        prepared.rollingContext.contains("current => OLD CURRENT") shouldBe false
    }

    @Test
    fun `T5 batch history fences at the first incomplete or failed predecessor`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage("p1", 1, "gap", "", committed = false),
                historyPage("p2", 2, "post-gap", "POST GAP"),
                historyPage("current", 3, "current", "CURRENT"),
            ),
        )

        val prepared = fixture.prepare("current", LaneCapability.PROFILE_BATCH)

        prepared.selectedPairs shouldContainExactly listOf("zero" to "ZERO")
        prepared.rollingContext.contains("post-gap => POST GAP") shouldBe false
    }

    @Test
    fun `T5 failed predecessor fences post-gap committed pages`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage("failed", 1, "failed", "", translationStatus = StageStatus.FAILED),
                historyPage("p2", 2, "post-gap", "POST GAP"),
                historyPage("current", 3, "current", "CURRENT"),
            ),
        )

        fixture.prepare("current", LaneCapability.PROFILE_BATCH).selectedPairs shouldContainExactly
            listOf("zero" to "ZERO")
    }

    @Test
    fun `batch partial predecessor never contributes and fences later pages`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage("partial", 1, "partial", "PARTIAL", translationStatus = StageStatus.PARTIAL),
                historyPage("p2", 2, "post-gap", "POST GAP"),
                historyPage("current", 3, "current", "CURRENT"),
            ),
        )

        fixture.prepare("current", LaneCapability.PROFILE_BATCH).selectedPairs shouldContainExactly
            listOf("zero" to "ZERO")
    }

    @Test
    fun `batch textless predecessor counts as present but contributes no pair`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage(
                    "textless",
                    1,
                    "",
                    "",
                    translationStatus = StageStatus.SKIPPED,
                    inpaintStatus = StageStatus.SKIPPED,
                    renderStatus = StageStatus.SKIPPED,
                    emptyBlocks = true,
                ),
                historyPage("p2", 2, "two", "TWO"),
                historyPage("current", 3, "current", "CURRENT"),
            ),
        )

        fixture.prepare("current", LaneCapability.PROFILE_BATCH).selectedPairs shouldContainExactly
            listOf("zero" to "ZERO", "two" to "TWO")
    }

    @Test
    fun `T6 reader history allows committed pages across untranslated gaps`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage("gap", 1, "gap", "", committed = false),
                historyPage("p2", 2, "two", "TWO"),
                historyPage("current", 3, "current", "CURRENT"),
            ),
        )

        val prepared = fixture.prepare("current", LaneCapability.MANUAL)

        prepared.selectedPairs shouldContainExactly listOf("zero" to "ZERO", "two" to "TWO")
    }

    @Test
    fun `T7 identical committed state produces identical history on repeated preparation`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage("current", 1, "current", "CURRENT"),
            ),
        )

        fixture.prepare("current", LaneCapability.MANUAL).rollingContext shouldBe
            fixture.prepare("current", LaneCapability.MANUAL).rollingContext
    }

    @Test
    fun `T8 retranslation uses only the replacement committed contribution`() {
        val replacement = historyPage("p0", 0, "source", "NEW TARGET")
        val fixture = fixture(
            pages = listOf(
                replacement,
                historyPage("current", 1, "current", "CURRENT"),
            ),
            liveOverrides = mapOf("p0" to page("source", "STALE TARGET")),
        )

        val prepared = fixture.prepare("current", LaneCapability.MANUAL)

        prepared.selectedPairs shouldContainExactly listOf("source" to "NEW TARGET")
        prepared.rollingContext.contains("STALE TARGET") shouldBe false
    }

    @Test
    fun `T9 restart reconstruction returns the same history from durable snapshots`() {
        val source = listOf(
            historyPage("p0", 0, "zero", "ZERO"),
            historyPage("current", 1, "current", "CURRENT"),
        )
        val first = fixture(source)
        val beforeRestart = first.prepare("current", LaneCapability.MANUAL)
        val reopened = first.reopen()

        reopened.prepare("current", LaneCapability.MANUAL).rollingContext shouldBe beforeRestart.rollingContext
    }

    @Test
    fun `T10 empty history is valid and adds no prompt section`() {
        val fixture = fixture(listOf(historyPage("current", 0, "current", "CURRENT")))

        val prepared = fixture.prepare("current", LaneCapability.MANUAL)

        prepared.selectedPairs shouldBe emptyList()
        prepared.rollingContext shouldBe ""
        TranslationPrompts.contextPrefix(prepared.rollingContext) shouldBe ""
    }

    @Test
    fun `T12 standard and AI batch share the same committed history construction`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage("current", 1, "current", "CURRENT"),
            ),
        )

        val ai = fixture.prepare("current", LaneCapability.PROFILE_BATCH)
        val standard = fixture.prepare("current", LaneCapability.STANDARD_BATCH)

        standard.selectedPairs shouldContainExactly ai.selectedPairs
        standard.rollingContext shouldBe ai.rollingContext
    }

    @Test
    fun `N4 glossary terms outside the rolling window do not enter prepared context`(): Unit = runBlocking {
        val fixture = fixture(
            (0..40).map { index -> historyPage("p$index", index, "source-$index", "target-$index") } +
                historyPage("current", 41, "current", "CURRENT"),
        )
        fixture.store.updateGlossary(mapOf("source-0" to "LEGACY GLOSSARY TERM"))
        fixture.store.glossarySnapshot() shouldBe mapOf("source-0" to "LEGACY GLOSSARY TERM")

        val prepared = fixture.prepare("current", LaneCapability.MANUAL)

        prepared.rollingContext.contains("LEGACY GLOSSARY TERM") shouldBe false
        prepared.rollingContext.contains("source-0 => target-0") shouldBe false
        prepared.selectedPairs.none { it.first == "source-0" } shouldBe true
        Unit
    }

    @Test
    fun `N3 series profile registry state does not affect request history`() {
        val fixture = fixture(
            listOf(
                historyPage("p0", 0, "zero", "ZERO"),
                historyPage("current", 1, "current", "CURRENT"),
            ),
        )
        val before = fixture.prepare("current", LaneCapability.MANUAL)

        try {
            SeriesProfileRegistry.register(
                seriesKey = "series-1",
                profile = seriesProfile(),
                sourceLang = "ja",
                targetLang = "en",
                providerKey = "ai:test",
                nowEpochMs = 10L,
            )

            fixture.prepare("current", LaneCapability.MANUAL).rollingContext shouldBe before.rollingContext
            fixture.prepare("current", LaneCapability.MANUAL).selectedPairs shouldContainExactly before.selectedPairs
        } finally {
            SeriesProfileRegistry.clear()
        }
    }

    private fun fixture(
        pages: List<HistoryPage>,
        liveOverrides: Map<String, PageTranslation> = emptyMap(),
    ): Fixture {
        val io = FakeChapterDocumentIo()
        val layout = ChapterArtifactLayout("Rolling history")
        val docs = AtomicChapterDocuments(io)
        val artifact = ChapterArtifactEngine(docs, layout)
        val records = linkedMapOf<String, PageArtifactRecord>()
        for (entry in pages) {
            val page = entry.toPage()
            val snapshotName = if (entry.committed) {
                layout.committedPageSnapshotFile(entry.key, "g${entry.index}").also { name ->
                    docs.publishJson(name, page) shouldBe true
                }
            } else {
                null
            }
            records[entry.key] = PageArtifactRecord(
                pageKey = entry.key,
                naturalPageIndex = entry.index,
                committed = snapshotName?.let {
                    CommittedBundleMetadata(
                        generationId = "g${entry.index}",
                        displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE),
                        pageSnapshotFileName = it,
                    )
                },
            )
        }
        val manifest = ChapterArtifactManifest(chapterKey = layout.chapterKey, pages = records)
        docs.publishJson(layout.manifestFileName, manifest) shouldBe true
        val livePages = linkedMapOf<String, PageTranslation>()
        pages.forEach { entry -> livePages[entry.key] = entry.toPage() }
        liveOverrides.forEach { (key, page) -> livePages[key] = page }
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = livePages,
            artifactStore = artifact,
            initialArtifactManifest = manifest,
        )
        return Fixture(store, artifact, layout, manifest, pages)
    }

    private data class HistoryPage(
        val key: String,
        val index: Int,
        val source: String,
        val target: String,
        val extraPairs: List<Pair<String, String>> = emptyList(),
        val committed: Boolean = true,
        val translationStatus: String = if (target.isBlank()) StageStatus.PENDING else StageStatus.READY,
        val inpaintStatus: String = StageStatus.PENDING,
        val renderStatus: String = StageStatus.PENDING,
        val emptyBlocks: Boolean = false,
    ) {
        fun toPage(): PageTranslation = if (emptyBlocks) {
            PageTranslation(
                ocrStatus = StageStatus.READY,
                translationStatus = translationStatus,
                inpaintStatus = inpaintStatus,
                renderStatus = renderStatus,
                blocks = mutableListOf(),
            )
        } else {
            page(source, target, extraPairs).copy(
                translationStatus = translationStatus,
                inpaintStatus = inpaintStatus,
                renderStatus = renderStatus,
            )
        }
    }

    private data class Fixture(
        val store: ChapterTranslationStore,
        val artifact: ChapterArtifactEngine,
        val layout: ChapterArtifactLayout,
        val manifest: ChapterArtifactManifest,
        val pages: List<HistoryPage>,
    ) {
        fun prepare(pageKey: String, lane: LaneCapability): PreparedContext =
            ChapterContextService(store).prepare(
                ContextRequest(
                    pageKeys = listOf(pageKey),
                    targetLang = "en",
                    sourceLang = "ja",
                    requestedOutputTokens = 2048,
                    profile = TranslationContextChunkPlanner.Profile.DEFAULT,
                    laneCapability = lane,
                ),
            )

        fun reopen(): Fixture {
            val reopened = ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = pages.associate { it.key to it.toPage() },
                artifactStore = artifact,
                initialArtifactManifest = artifact.readManifest(),
            )
            return copy(store = reopened)
        }
    }

    private companion object {
        fun historyPage(
            key: String,
            index: Int,
            source: String,
            target: String,
            vararg extraPairs: Pair<String, String>,
            committed: Boolean = true,
            translationStatus: String = if (target.isBlank()) StageStatus.PENDING else StageStatus.READY,
            inpaintStatus: String = StageStatus.PENDING,
            renderStatus: String = StageStatus.PENDING,
            emptyBlocks: Boolean = false,
        ): HistoryPage = HistoryPage(
            key,
            index,
            source,
            target,
            extraPairs.toList(),
            committed,
            translationStatus,
            inpaintStatus,
            renderStatus,
            emptyBlocks,
        )

        fun seriesProfile(): ChapterTranslationProfile {
            val fact = ProfileFact(
                factId = "series-name",
                type = FactType.ENTITY_IDENTITY,
                canonicalSourceForm = "source-name",
                canonicalTargetForm = "Target Name",
                evidenceStrength = EvidenceStrength.EXPLICIT,
                evidenceRefs = listOf(
                    EvidenceRef("p0", "b0", sha256Hex("source-name")),
                ),
                scope = FactScope.CANONICAL_CHAPTER_WIDE,
                provenance = FactProvenance.CHAPTER_ANALYSIS,
                conflictState = FactConflictState.RESOLVED,
            )
            val draft = ChapterTranslationProfile(
                version = 1,
                contentFingerprint = "",
                profileInputFingerprint = sha256Hex("profile-input"),
                sourceRunId = "series-run",
                analyzerProvenance = AnalyzerProvenance("provider", "model", 1, 1),
                entities = listOf(fact),
                frozenAtEpochMs = 1L,
            )
            return draft.copy(contentFingerprint = StageFingerprints.profileContentFingerprint(draft))
        }

        private fun sha256Hex(value: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun page(source: String, target: String, extraPairs: List<Pair<String, String>> = emptyList()): PageTranslation =
            PageTranslation(
                ocrStatus = StageStatus.READY,
                translationStatus = if (target.isBlank()) StageStatus.PENDING else StageStatus.READY,
                blocks = (listOf(source to target) + extraPairs).map { (src, tgt) ->
                    TranslationBlock(
                        text = src,
                        translation = tgt,
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                    )
                }.toMutableList(),
            )
    }
}
