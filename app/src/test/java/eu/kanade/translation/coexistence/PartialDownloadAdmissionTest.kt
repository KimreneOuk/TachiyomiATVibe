package eu.kanade.translation.coexistence

import eu.kanade.translation.artifact.loadArtifact

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.ArtifactSeed
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.pipeline.PageDecode
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * T917 Phase 4 — D10 partial-download admission + honest missing-page
 * accounting (phase4-design §3).
 *
 * Drives the REAL production graph over an ARTIFACT-authority store
 * (FakeChapterDocumentIo + the production fresh-chapter recipe, D9 harness
 * precedent) so the manifest — the durable record the design makes carry the
 * partial truth — is observable. The batch enumerates a partially-downloaded
 * directory (1 of 2 source pages), and the trigger's admission-probe
 * cross-check (`Download.pages` vs the found files) travels with the batch
 * through the harness `launchBatch` context seam.
 *
 * Fixture-size note (design §3.4 asks for a 2-page subset run): the harness's
 * per-page lane serialization makes a multi-page FRESH standard batch
 * uncompletable by construction — a later page's translation is statically
 * dependency-skipped (PRIOR_PAGE_INCOMPLETE) because its predecessor has no
 * translation yet, so its transport stage never starts and the harness's
 * inpaint wait can never release; every prior full-completion test in this
 * suite is single-page or rescan-driven. The honesty semantics under test are
 * page-count independent; the re-run test (e) DOES pick the previously-missing
 * page up over a two-page directory (its first page reopens fully terminal, so
 * the planner has no static dependency block).
 *
 * RED (phase4-design §3.4): the trigger stamps `expectedPageCount` from its
 * own directory enumeration as TRUSTED (audit M-08) — a half-downloaded
 * chapter silently "succeeds" at 100%. The probe, the manifest
 * `partialBatchInfo`, and the unknown-total honesty do not exist yet. Where an
 * assertion targets a GREEN seam that cannot exist yet (the probe, the
 * routing), it goes through the reflection bridge below, which raises an
 * assertion naming the missing defect — never a timeout and never a
 * compile-time dependency on the GREEN commit.
 */
class PartialDownloadAdmissionTest {

    companion object {
        const val MANIFEST_FILE = "D10 Chapter.manifest.json"
        const val LEDGER_FILE = "D10 Chapter_artifacts/attempts/ledger.json"
        const val AWAIT_TIMEOUT_MS = TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS
    }

    // Shared document IO across the two batch runs of the re-run test: the
    // "disk" survives, a NEW store instance reopens it.
    private val io = FakeChapterDocumentIo()

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Production fresh-chapter recipe over the shared IO (D9 harness precedent). */
    private fun freshStore(
        pageKeys: List<String>,
        pageOverrides: Map<String, PageTranslation.() -> Unit> = emptyMap(),
    ): ChapterTranslationStore {
        val artifactStore = ChapterArtifactEngine(
            AtomicChapterDocuments(io),
            ChapterArtifactLayout("D10 Chapter"),
        )
        val manifest = artifactStore
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        return ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = pageKeys.associateWith { key ->
                PageTranslation(sourceFileName = key).apply {
                    pageOverrides[key]?.invoke(this)
                }
            },
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    /**
     * The REAL engine identity the harness graph runs under, probed from a
     * throwaway harness's real EngineLane cache (no preference replication).
     */
    private fun expectedFingerprints(): BatchExpectedFingerprints {
        val probe = TranslationCoexistenceHarness.create(listOf("p0"))
        try {
            val lane = probe.engineLane
            val signature = lane.currentTranslatorSignature
            return PageDecode.batchExpectedFingerprints(
                signature,
                lane.currentOcrModel,
                lane.currentReadingOrder,
                lane.currentInpaintingMode,
                signature.fromLang,
                signature.toLang,
            )
        } finally {
            probe.close()
        }
    }

    /** The source fingerprint the REAL batch computes for the fixture stream. */
    private suspend fun realSourceFingerprint(pageKey: String): String =
        checkNotNull(
            PageDecode.computeSourceFingerprint {
                ByteArrayInputStream("page-$pageKey".toByteArray())
            },
        ) { "D10 fixture: computeSourceFingerprint returned null" }

    /**
     * The run-1 terminal state of p0, reopened for the post-completion re-run:
     * provenance matches the current engine exactly (D5 reuse-fixture recipe),
     * so the batch re-run has every reason to plan REUSE/SKIP_ALL for it and
     * spend its work on the newly downloaded p1.
     */
    private fun terminalP0(
        expected: BatchExpectedFingerprints,
        sourceFingerprint: String,
    ): PageTranslation.() -> Unit = {
        ocrStatus = StageStatus.READY
        translationStatus = StageStatus.READY
        inpaintStatus = StageStatus.READY
        renderStatus = StageStatus.READY
        inpaintingModeUsed = "FAST"
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        cleanedImageName = "p0.cleaned.jpg"
        this.sourceFingerprint = sourceFingerprint
        detectionFingerprint = expected.detection
        ocrFingerprint = expected.ocr
        inpaintFingerprint = expected.inpaint
        translationFingerprint = expected.translation
        layoutFingerprint = expected.layout
        blocks += TranslationBlock(
            text = "hello-p0",
            translation = "tr-hello-p0",
            width = 10f,
            height = 10f,
            x = 0f,
            y = 0f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
        )
    }

    /**
     * Runs one unhindered real batch over [pageKeys] with the trigger's
     * probe cross-check attached, and awaits its reconciliation.
     */
    private fun runBatch(
        store: ChapterTranslationStore,
        pageKeys: List<String>,
        sourcePageCount: Int?,
        sourceCountKnown: Boolean,
        cleanedImagesOnDisk: Set<String> = emptySet(),
    ): TranslationCoexistenceHarness.BatchRun {
        val harness = TranslationCoexistenceHarness.create(
            pageKeys,
            storeOverride = store,
            cleanedImagesOnDisk = cleanedImagesOnDisk,
        )
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(pageKeys)
            val batch = harness.launchBatch(
                pageKeys,
                sourcePageCount = sourcePageCount,
                sourceCountKnown = sourceCountKnown,
            )
            runBlocking {
                withTimeout(AWAIT_TIMEOUT_MS) { batch.reconciliation.await() }
            }
            return batch
        } finally {
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }

    // ------------------------------------------------------------------
    // durable-document observation (schema pinned from the design note)
    // ------------------------------------------------------------------

    @Serializable
    private data class PartialInfoMirror(
        val expectedSourcePageCount: Int? = null,
        val missingPageCount: Int = 0,
        val determinedFrom: String = "",
        val recordedAtEpochMs: Long = 0L,
    )

    @Serializable
    private data class ManifestMirror(
        val expectedPageCount: Int? = null,
        val expectedPageCountTrusted: Boolean = false,
        val partialBatchInfo: PartialInfoMirror? = null,
        val pages: Map<String, JsonElement> = emptyMap(),
    )

    @Serializable
    private data class EntryMirror(val pageKey: String = "")

    @Serializable
    private data class LedgerMirror(
        val entries: List<EntryMirror> = emptyList(),
        val consecutiveUnresolved: Map<String, Int> = emptyMap(),
    )

    private fun readManifest(): ManifestMirror? =
        io.read(MANIFEST_FILE)?.let { bytes -> json.decodeFromString<ManifestMirror>(bytes.decodeToString()) }

    private fun readLedger(): LedgerMirror? =
        io.read(LEDGER_FILE)?.let { bytes -> json.decodeFromString<LedgerMirror>(bytes.decodeToString()) }

    // ------------------------------------------------------------------
    // GREEN-seam reflection bridge — named failure, never a timeout.
    // ------------------------------------------------------------------

    private fun probeClass(): Class<*> = try {
        Class.forName("eu.kanade.translation.pipeline.batch.BatchAdmissionProbe")
    } catch (e: ClassNotFoundException) {
        throw AssertionError(
            "T917 D10 RED defect: BatchAdmissionProbe is missing — batch admission cannot " +
                "distinguish complete vs partial vs unknown-total downloads (phase4-design §3.2)",
            e,
        )
    }

    private fun probeEvaluate(downloadedPageCount: Int, sourcePageList: List<Page>?): Any? {
        val cls = probeClass()
        val instance = cls.declaredFields.firstOrNull { it.name == "INSTANCE" }
            ?.apply { isAccessible = true }?.get(null)
            ?: throw AssertionError("T917 D10 RED defect: BatchAdmissionProbe is not a Kotlin object")
        val method = cls.declaredMethods.firstOrNull {
            it.name == "evaluate" && !it.name.contains('$')
        } ?: throw AssertionError(
            "T917 D10 RED defect: BatchAdmissionProbe.evaluate(downloadedPageCount, sourcePageList) is missing",
        )
        method.isAccessible = true
        return method.invoke(instance, downloadedPageCount, sourcePageList)
    }

    /** The probe outcome class name: `Complete`, `Partial`, or `UnknownCount`. */
    private fun Any.decisionName(): String = javaClass.simpleName

    private fun Any.partialExpected(): Int =
        javaClass.declaredFields.firstOrNull { it.name == "expectedSourcePageCount" }
            ?.apply { isAccessible = true }?.getInt(this)
            ?: throw AssertionError("T917 D10 RED defect: Partial(expectedSourcePageCount) is missing")

    private fun Any.partialDownloaded(): Int =
        javaClass.declaredFields.firstOrNull { it.name == "downloadedPageCount" }
            ?.apply { isAccessible = true }?.getInt(this)
            ?: throw AssertionError("T917 D10 RED defect: Partial(downloadedPageCount) is missing")

    private fun routeDecision(decision: Any?, choice: String?): Any? {
        val routingCls = try {
            Class.forName("eu.kanade.translation.pipeline.batch.BatchAdmissionRouting")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "T917 D10 RED defect: BatchAdmissionRouting is missing — the trigger cannot route " +
                    "finish-download-first vs translate-what-exists (phase4-design §3.2)",
                e,
            )
        }
        val instance = routingCls.declaredFields.firstOrNull { it.name == "INSTANCE" }
            ?.apply { isAccessible = true }?.get(null)
            ?: throw AssertionError("T917 D10 RED defect: BatchAdmissionRouting is not a Kotlin object")
        val choiceInstance = choice?.let { name ->
            // Top-level enum in the D10 seam file (with a nested fallback).
            val resolved = try {
                Class.forName("eu.kanade.translation.pipeline.batch.PartialDownloadChoice")
            } catch (e: ClassNotFoundException) {
                routingCls.declaredClasses.firstOrNull { it.simpleName == "PartialDownloadChoice" }
            } ?: throw AssertionError(
                "T917 D10 RED defect: PartialDownloadChoice is missing — the finish/translate " +
                    "choice has no typed value",
            )
            java.lang.Enum.valueOf(
                @Suppress("UNCHECKED_CAST")
                (resolved as Class<out Enum<*>>),
                name,
            )
        }
        val method = routingCls.declaredMethods.firstOrNull {
            it.name == "route" && !it.name.contains('$')
        } ?: throw AssertionError("T917 D10 RED defect: BatchAdmissionRouting.route is missing")
        method.isAccessible = true
        return method.invoke(instance, decision, choiceInstance)
    }

    // ------------------------------------------------------------------
    // a. probe matrix (pure)
    // ------------------------------------------------------------------

    @Test
    fun `probe distinguishes complete partial and unknown-total downloads`() {
        val sourcePages = listOf(Page(0), Page(1))

        withClue("D10 (design §3.2): Download pages == downloaded files is Complete") {
            probeEvaluate(2, sourcePages).shouldNotBeNull().decisionName() shouldBe "Complete"
        }
        withClue("D10: a mismatch is Partial carrying the SOURCE total and the downloaded count") {
            val decision = probeEvaluate(1, sourcePages).shouldNotBeNull()
            decision.decisionName() shouldBe "Partial"
            decision.partialExpected() shouldBe 2
            decision.partialDownloaded() shouldBe 1
        }
        withClue("D10: no Download object (offline truth unavailable) is UnknownCount") {
            probeEvaluate(1, null).shouldNotBeNull().decisionName() shouldBe "UnknownCount"
        }
        withClue(
            "D10 (risk table): a Download with no fetched page list is not a trustworthy " +
                "known-total-of-zero — an empty list degrades to UnknownCount like a null list",
        ) {
            probeEvaluate(1, emptyList<Page>()).shouldNotBeNull().decisionName() shouldBe "UnknownCount"
        }
    }

    // ------------------------------------------------------------------
    // b. subset run records honest partial truth in the manifest
    // ------------------------------------------------------------------

    @Test
    fun `subset batch run records partial truth and never counts missing pages as work`() = runBlocking<Unit> {
        val found = listOf("p0")
        val store = freshStore(found)
        val batch = runBatch(store, found, sourcePageCount = 2, sourceCountKnown = true)
        try {
            val manifest = withClue(
                "D10 (design §3.3): the manifest must survive the subset run with the partial " +
                    "truth. RED defect (audit M-08): expectedPageCount is the self-derived found " +
                    "count stamped trusted=true — a half-downloaded chapter silently reads as a " +
                    "complete 1/1 success",
            ) {
                readManifest().shouldNotBeNull()
            }
            withClue("D10: the trusted total is the SOURCE total (2), not the found count (1)") {
                manifest.expectedPageCount shouldBe 2
                manifest.expectedPageCountTrusted shouldBe true
            }
            withClue(
                "D10: the manifest carries the delta as partialBatchInfo " +
                    "(expected=2, missing=1, DOWNLOAD_CROSSCHECK) — documented absence, " +
                    "never fake page records",
            ) {
                val partial = manifest.partialBatchInfo.shouldNotBeNull()
                partial.expectedSourcePageCount shouldBe 2
                partial.missingPageCount shouldBe 1
                partial.determinedFrom shouldBe "DOWNLOAD_CROSSCHECK"
            }
            withClue("D10: missing pages are never registered as page records (no fake stages)") {
                manifest.pages.keys shouldBe setOf("p0")
            }
            withClue(
                "D10: the found page's real work commits — translation READY and the cleaned " +
                    "image published in the store. (The final display-promotion publication is " +
                    "rejected on this fixture — the documented T909 §4 candidate-grace gap + the " +
                    "phase-log fixture fidelity note — so terminal-count claims are asserted at " +
                    "the page-commit level the fixture can prove.)",
            ) {
                val p0 = store.snapshot("p0").page.shouldNotBeNull()
                p0.translationStatus shouldBe StageStatus.READY
                p0.inpaintStatus shouldBe StageStatus.READY
                p0.cleanedImageName shouldBe "p0.cleaned.jpg"
            }
            withClue("D10: no stranded and no failed pages — the missing page invents no work") {
                val reconciliation = batch.reconciliation.await()
                reconciliation.shouldNotBeNull()
                reconciliation.failedCount shouldBe 0
                reconciliation.strandedPages shouldBe emptyMap()
            }
            withClue("D10 (design §3.3): zero ledger entries for never-attempted pages (p1)") {
                readLedger().shouldNotBeNull().entries.none { it.pageKey == "p1" } shouldBe true
            }
        } finally {
            batch.job.cancel()
        }
    }

    // ------------------------------------------------------------------
    // c. unknown-total run keeps the found count but stops claiming trust
    // ------------------------------------------------------------------

    @Test
    fun `unknown-total run proceeds honestly without a fake expected total`() = runBlocking<Unit> {
        val found = listOf("p0")
        val store = freshStore(found)
        val batch = runBatch(store, found, sourcePageCount = null, sourceCountKnown = true)
        try {
            val manifest = withClue(
                "D10 (design §3.3): the unknown-total run must still record its honesty. " +
                    "RED defect: the found count is stamped trusted=true",
            ) {
                readManifest().shouldNotBeNull()
            }
            withClue("D10: the found count is kept (no fake expected total)") {
                manifest.expectedPageCount shouldBe 1
            }
            withClue("D10: the durable record stops claiming trust it does not have") {
                manifest.expectedPageCountTrusted shouldBe false
            }
            withClue("D10: the manifest records determinedFrom = UNKNOWN") {
                val partial = manifest.partialBatchInfo.shouldNotBeNull()
                partial.expectedSourcePageCount.shouldBeNull()
                partial.determinedFrom shouldBe "UNKNOWN"
            }
            withClue("D10: zero ledger entries for never-attempted pages") {
                readLedger().shouldNotBeNull().entries.none { it.pageKey == "p1" } shouldBe true
            }
        } finally {
            batch.job.cancel()
        }
    }

    // ------------------------------------------------------------------
    // d. finish-first routing (pure)
    // ------------------------------------------------------------------

    @Test
    fun `finish-first routing sends a partial chapter to the fenced wait path`() {
        val sourcePages = listOf(Page(0), Page(1))
        val partial = probeEvaluate(1, sourcePages).shouldNotBeNull()

        withClue(
            "D10 (design §3.2): the finish-download-first choice routes through the EXISTING " +
                "fenced WAITING_FOR_DOWNLOAD path — zero batch work starts",
        ) {
            routeDecision(partial, "FINISH_DOWNLOAD_FIRST").shouldNotBeNull()
                .decisionName() shouldBe "WaitForDownload"
        }
        withClue("D10: the translate-what-exists choice admits the subset (labeled partial)") {
            routeDecision(partial, "TRANSLATE_WHAT_EXISTS").shouldNotBeNull()
                .decisionName() shouldBe "AdmitSubset"
        }
        withClue("D10: no choice yet asks the user (the PLAN-mandated dialog)") {
            routeDecision(partial, null).shouldNotBeNull().decisionName() shouldBe "AskUser"
        }
        withClue("D10: a complete download admits unchanged — no dialog") {
            routeDecision(probeEvaluate(2, sourcePages), null).shouldNotBeNull()
                .decisionName() shouldBe "AdmitBatch"
        }
    }

    // ------------------------------------------------------------------
    // e. post-completion re-run clears the partial info
    // ------------------------------------------------------------------

    @Test
    fun `rerun after the download completes translates the missing pages and clears the partial info`() =
        runBlocking<Unit> {
            val partialStore = freshStore(listOf("p0"))
            val firstRun = runBatch(partialStore, listOf("p0"), sourcePageCount = 2, sourceCountKnown = true)
            firstRun.job.cancel()
            withClue(
                "D10 precondition: run 1 recorded the partial truth. RED defect: no partial " +
                    "info exists to clear (audit M-08)",
            ) {
                readManifest().shouldNotBeNull().partialBatchInfo.shouldNotBeNull()
            }

            // The "download" fills: the same durable manifest now reopens with
            // both pages present and a full cross-check (Download.pages == 2).
            // p0 reopens at its run-1 terminal state (planned SKIP_ALL; its
            // cleaned image is the fixture's on-disk file), p1 is the newly
            // downloaded page.
            val expected = expectedFingerprints()
            val p0Source = realSourceFingerprint("p0")
            val fullStore = freshStore(
                listOf("p0", "p1"),
                pageOverrides = mapOf("p0" to terminalP0(expected, p0Source)),
            )
            val secondRun = runBatch(
                fullStore,
                listOf("p0", "p1"),
                sourcePageCount = 2,
                sourceCountKnown = true,
                cleanedImagesOnDisk = setOf("p0.cleaned.jpg"),
            )
            try {
                val manifest = withClue(
                    "D10 (design §3.3): a full cross-check with missingPageCount == 0 must CLEAR " +
                        "the partial info",
                ) {
                    readManifest().shouldNotBeNull()
                }
                manifest.partialBatchInfo.shouldBeNull()
                manifest.expectedPageCount shouldBe 2
                manifest.expectedPageCountTrusted shouldBe true
                withClue("D10: the previously-missing page was picked up by the planner registration") {
                    manifest.pages.keys shouldBe setOf("p0", "p1")
                }
                withClue(
                    "D10: the previously-missing page was actually WORKED, not just registered — " +
                        "its translation and cleaned image commit in the store (display publication " +
                        "is the documented fixture gap, see test (b))",
                ) {
                    val p1 = fullStore.snapshot("p1").page.shouldNotBeNull()
                    p1.translationStatus shouldBe StageStatus.READY
                    p1.cleanedImageName shouldBe "p1.cleaned.jpg"
                }
                withClue("D10: the reopened terminal p0 counts done; the run strands and fails nothing") {
                    val reconciliation = secondRun.reconciliation.await().shouldNotBeNull()
                    // T924 zero-legacy (D1): the flagged COMPLETED projection
                    // counts EVERY expected page done — the reopened terminal
                    // p0 (its run coverage) and the freshly worked p1 alike.
                    reconciliation.doneCount shouldBe 2
                    reconciliation.failedCount shouldBe 0
                    reconciliation.strandedPages shouldBe emptyMap()
                }
                withClue("D10: zero ledger entries survive for the previously-missing page") {
                    readLedger().shouldNotBeNull().entries.none { it.pageKey == "p1" } shouldBe true
                }
            } finally {
                secondRun.job.cancel()
            }
        }
}
