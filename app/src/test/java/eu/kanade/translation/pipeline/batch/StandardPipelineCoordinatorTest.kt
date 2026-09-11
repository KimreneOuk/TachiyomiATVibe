package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T924 Phase 4 Wave A — the STANDARD-engine lane inside the flagged
 * coordinator (STANDARD_PIPELINE). The Director design: both AI and standard
 * engines do the same OCR; the standard engine continues with per-page batch
 * translation exactly like the AI lane minus everything AI-specific (no
 * glossary, no analysis/profile/envelope work), then the shared engine-
 * agnostic Stage-7 FINALIZE (translation-terminal completion, single
 * COMPLETE).
 */
class StandardPipelineCoordinatorTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (Stage7FinalizeCoordinatorTest idioms).
    // ------------------------------------------------------------------

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String) = TranslationBlock(
        blockId = "b1",
        text = text,
        translation = "",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(
        pageKey: String,
        text: String?,
        textless: Boolean = false,
        maskedTextless: Boolean = false,
    ) = PageTranslation(
        sourceFileName = pageKey,
        // A "no translatable text" page models the real lane's trigger: blocks
        // whose text is BLANK (the worker's textless branch counts
        // `it.text.isNotBlank()`). Fully EMPTY blocks never persist a candidate
        // (shouldPersistUpdate) and the preflight checkpoint CLOSE rejects
        // them — a pre-existing Stage-3 gap, out of Wave A scope.
        blocks = if (textless) mutableListOf(block(" ")) else mutableListOf(block(text!!)),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        // finalizePostOcrStage parity: a blank-text page's render is SKIPPED,
        // and its inpaint is SKIPPED only WITHOUT mask boxes — a masked
        // blank-text page keeps inpaint PENDING for the inpaint drain.
        renderStatus = if (textless) StageStatus.SKIPPED else StageStatus.PENDING,
        inpaintStatus = if (textless && !maskedTextless) StageStatus.SKIPPED else StageStatus.PENDING,
        inpaintMaskBoxes = if (textless && !maskedTextless) emptyList() else listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactStore =
        ChapterArtifactStore(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        fileCreator = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    /**
     * A GENUINELY blank page (Wave B Task 1): OCR completes with ZERO blocks.
     * Mirrors the real lane's post-OCR store shape: `mergeOcrLocked` copies
     * render/inpaint status (so the [finalizePostOcrStage] SKIPPED values
     * land) but NEVER `translationStatus` — the store page keeps PENDING until
     * the translate tail's textless commit — and an empty-block write is
     * transient per `shouldPersistUpdate` (no artifact candidate).
     */
    private fun ocrPageWithNoBlocks(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        renderStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = emptyList(),
    )

    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
        private val textlessPages: Set<String> = emptySet(),
        private val maskedTextlessPages: Set<String> = emptySet(),
        private val emptyTextPages: Set<String> = emptySet(),
        private val onFirstOcr: (() -> Unit)? = null,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            if (ocrPages.isEmpty()) onFirstOcr?.invoke()
            ocrPages += pageKey
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = when {
                        pageKey in emptyTextPages -> ocrPageWithNoBlocks(pageKey)
                        else -> ocrPage(
                            pageKey,
                            "source-$pageKey",
                            textless = pageKey in textlessPages || pageKey in maskedTextlessPages,
                            maskedTextless = pageKey in maskedTextlessPages,
                        )
                    },
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 wave-a fake preflight ocr",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
            val after = store.snapshot(pageKey)
            return OcrReadyPageRef(
                pageKey = pageKey,
                pageIndex = pageIndex,
                generation = after.generation,
                blockFingerprints = emptyList(),
                leaseToken = lease.token,
                candidateGenerationId = after.candidateGenerationId,
                dependencyFingerprint = after.dependencyFingerprint,
                artifactPageVersion = after.artifactPageVersion,
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {}
    }

    /** Fake native inpaint lane through the scheduler's guarded identity path. */
    private inner class FakeOverlapInpaintLane(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        private val onFirstInpaint: (() -> Unit)? = null,
    ) : NativeLaneWorker {
        val inpainted = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null

        override suspend fun runInpaintStage(pageKey: String) {
            runInpaintStage(pageKey, null)
        }

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            if (inpainted.isEmpty()) onFirstInpaint?.invoke()
            // Same-page discipline: a page is only ever inpainted AFTER its
            // translation committed (the scheduler's candidacy rule) — never
            // while its translate call is still in flight.
            store.snapshot(pageKey).page.shouldNotBeNull().let { live ->
                if (live.translationStatus != StageStatus.READY &&
                    live.translationStatus != StageStatus.PARTIAL
                ) {
                    error("inpaint ran for non-translated page $pageKey")
                }
            }
            inpainted += pageKey
            val identity = identities[pageKey]
                ?: error("overlap scheduler must register the write identity for $pageKey")
            store.updatePageGuarded(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = identity.generation,
                    pageVersion = identity.pageVersion,
                    leaseToken = identity.leaseToken,
                    candidateGenerationId = identity.candidateGenerationId,
                    dependencyFingerprint = identity.dependencyFingerprint,
                    artifactPageVersion = identity.artifactPageVersion,
                ),
                description = "t924 wave-a fake overlap inpaint",
            ) { page ->
                page!!.apply { inpaintStatus = StageStatus.READY }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
    }

    /**
     * T924 Phase 4 Wave A: a plain per-page [TextTranslator] fake (the
     * standard-engine shape — no contextual protocol). Stamps every non-blank
     * block `tr-<text>`; [onTranslate] observes each page call (test latch).
     */
    private inner class FakeStandardTranslator(
        private val onTranslate: (suspend (String) -> Unit)? = null,
    ) : TextTranslator {
        val calls = mutableListOf<String>()

        override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
            pages.forEach { (key, page) ->
                calls += key
                page.blocks.forEach { block ->
                    if (block.text.isNotBlank()) block.translation = "tr-" + block.text
                }
                onTranslate?.invoke(key)
            }
        }

        override fun close() {}
    }

    /**
     * The test twin of the shell's injected seam + the legacy worker's
     * standard branch: fresh BATCH lease + write identity, textless skip,
     * provider call, `TranslationBlockValidation`, guarded commit, lease
     * release in `finally`.
     */
    private inner class StandardSeam(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        val translator: FakeStandardTranslator,
        private val onFirstInvoke: (() -> Unit)? = null,
        /** Typed PAUSE before any page work: the interrupted mid-run shape. */
        private val pauseOnPages: Set<String> = emptySet(),
    ) {
        val invoked = mutableListOf<String>()

        suspend fun translateOutcome(ref: OcrReadyPageRef): ChunkCompletionOutcome {
            if (invoked.isEmpty()) onFirstInvoke?.invoke()
            val pageKey = ref.pageKey
            invoked += pageKey
            if (pageKey in pauseOnPages) {
                return ChunkCompletionOutcome.Paused(
                    anchorPageKey = pageKey,
                    reason = "test pause at $pageKey",
                )
            }
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val identity = BatchWriteIdentity(
                generation = lease.generation,
                pageVersion = lease.pageVersion,
                leaseToken = lease.token,
                candidateGenerationId = lease.candidateGenerationId,
                dependencyFingerprint = lease.dependencyFingerprint,
                artifactPageVersion = lease.artifactPageVersion,
            )
            identities[pageKey] = identity
            try {
                val p = store.state.value[pageKey]?.detachedCopy()
                    ?: return ChunkCompletionOutcome.Completed(emptySet())
                val sourceBlocks = p.blocks.count { it.text.isNotBlank() }
                if (sourceBlocks == 0) {
                    // Legacy textless branch: a durable SKIPPED terminal, no
                    // provider call.
                    p.translationStatus = StageStatus.SKIPPED
                    p.renderStatus = StageStatus.SKIPPED
                    commit(pageKey, p)
                    return ChunkCompletionOutcome.Completed(setOf(pageKey))
                }
                translator.translatePage(pageKey, p)
                TranslationBlockValidation.applyTo(p)
                return when (p.translationStatus) {
                    StageStatus.READY -> {
                        commit(pageKey, p)
                        ChunkCompletionOutcome.Completed(setOf(pageKey))
                    }
                    StageStatus.PARTIAL -> ChunkCompletionOutcome.Paused(
                        anchorPageKey = pageKey,
                        reason = "translation output is partial",
                    )
                    else -> ChunkCompletionOutcome.Failed(
                        anchorPageKey = pageKey,
                        reason = "translation returned an unknown state",
                    )
                }
            } finally {
                store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
            }
        }

        private suspend fun commit(pageKey: String, p: PageTranslation) {
            val identity = identities.getValue(pageKey)
            store.updatePageGuarded(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = identity.generation,
                    pageVersion = identity.pageVersion,
                    leaseToken = identity.leaseToken,
                    candidateGenerationId = identity.candidateGenerationId,
                    dependencyFingerprint = identity.dependencyFingerprint,
                    artifactPageVersion = identity.artifactPageVersion,
                ),
                description = "t924 wave-a standard seam commit",
            ) { current ->
                (current ?: p).apply {
                    translationStatus = p.translationStatus
                    translationError = null
                    renderStatus = p.renderStatus
                    inpaintStatus = p.inpaintStatus
                    blocks = p.blocks.toMutableList()
                    updatedAt = System.currentTimeMillis()
                }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
    }

    private fun durableRunRecord(store: ChapterTranslationStore): eu.kanade.translation.artifact.ChapterRunRecord? {
        val artifact = artifactStore()
        val pointer = artifact.readManifest().shouldNotBeNull().activeRun ?: return null
        return (artifact.readRunRecord(pointer) as? ChapterArtifactStore.RunRecordRead.Usable)?.record
    }

    private fun activeRunPointer(store: ChapterTranslationStore): SidecarPointer =
        artifactStore().readManifest().shouldNotBeNull().activeRun.shouldNotBeNull()

    private fun standardCoordinator(
        store: ChapterTranslationStore,
        worker: NativeLaneWorker,
        pages: List<PageKey>,
        seam: StandardSeam,
        overlapScheduler: OverlapScheduler?,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "standard:google",
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        textTranslator = seam.translator,
        overlapScheduler = overlapScheduler,
        renderJoin = null,
        standardLane = true,
        standardTranslateOutcome = { ref -> seam.translateOutcome(ref) },
    )

    // ------------------------------------------------------------------
    // T2 — the end-to-end standard run.
    // ------------------------------------------------------------------

    @Test
    fun `standard run translates in order, skips textless, and completes with zero AI pointers`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2", "p3", "p4")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val observedStates = mutableListOf<ChapterRunState>()
        val glossaryBefore = store.glossarySnapshot()

        val translator = FakeStandardTranslator()
        val seam = StandardSeam(
            store = store,
            identities = identities,
            translator = translator,
            onFirstInvoke = {
                // The tail's TRANSLATE phase record is durable before the
                // first provider call, and COMPLETE has not been published.
                val record = durableRunRecord(store).shouldNotBeNull()
                observedStates += record.state
                record.state shouldBe ChapterRunState.TRANSLATE
            },
        )
        val inpaintLane = FakeOverlapInpaintLane(store, identities, onFirstInpaint = {
            // The FINALIZE record is durable before the inpaint drain.
            val record = durableRunRecord(store).shouldNotBeNull()
            observedStates += record.state
            record.state shouldBe ChapterRunState.FINALIZE
        })
        val scheduler = OverlapScheduler(
            store = store,
            nativeWorker = inpaintLane,
            orderedPageKeys = pageKeys,
            batchWriteIdentities = identities,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        val ocrWorker = FakePreflightOcrWorker(
            store,
            textlessPages = setOf("p2"),
            onFirstOcr = {
                val record = durableRunRecord(store).shouldNotBeNull()
                observedStates += record.state
            },
        )

        val outcome = standardCoordinator(store, ocrWorker, pages, seam, scheduler)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)

        // Stage-7 completion semantics: translation-terminal, single COMPLETE.
        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        val record = durableRunRecord(store).shouldNotBeNull()
        record.state shouldBe ChapterRunState.COMPLETE
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE] shouldBe 1
        // Every page the seam completed this run (the textless skip commit
        // included) — the provider-call parity is pinned below.
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PAGES_TRANSLATED] shouldBe 4

        // Record sequence: the first durable state observable at OCR time is
        // OCR_PLAN (RUN_SNAPSHOT is published before the OCR loop begins and
        // is pinned by the preflight-completion assertions) → TRANSLATE →
        // FINALIZE; COMPLETE was never published before FINALIZE.
        observedStates.first() shouldBe ChapterRunState.OCR_PLAN
        observedStates shouldContainExactly listOf(
            ChapterRunState.OCR_PLAN,
            ChapterRunState.TRANSLATE,
            ChapterRunState.FINALIZE,
        )

        // Pages: translated (READY, render PENDING) or no-text SKIPPED.
        pageKeys.forEach { key ->
            val page = store.snapshot(key).page.shouldNotBeNull()
            if (key == "p2") {
                page.translationStatus shouldBe StageStatus.SKIPPED
                page.renderStatus shouldBe StageStatus.SKIPPED
            } else {
                page.translationStatus shouldBe StageStatus.READY
                page.renderStatus shouldBe StageStatus.PENDING
                page.inpaintStatus shouldBe StageStatus.READY
                page.blocks.forEach { block ->
                    block.translation shouldBe "tr-" + block.text
                }
            }
        }

        // Provider-call parity: exactly the pages WITH translations; the
        // textless page never reached the translator.
        seam.invoked shouldContainExactly pageKeys
        translator.calls shouldContainExactly listOf("p1", "p3", "p4")

        // Zero AI-side manifest pointers and zero glossary writes.
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.profile.shouldBeNull()
        manifest.envelopePlan.shouldBeNull()
        manifest.analysisChunks shouldBe emptyList()
        store.glossarySnapshot() shouldBe glossaryBefore
        store.glossarySnapshot() shouldBe emptyMap()

        // LI-2 evidence: every page's translated snapshot stays durably
        // addressable through its open candidate pointer.
        pageKeys.forEach { key ->
            manifest.pages.getValue(key).candidate.shouldNotBeNull()
        }
    }

    // ------------------------------------------------------------------
    // T3 — checkpoint resume parity (zero-work COMPLETE).
    // ------------------------------------------------------------------

    @Test
    fun `re-dispatch over a completed standard run finishes with zero OCR and zero translation`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2", "p3")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val firstIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val firstSeam = StandardSeam(store, firstIdentities, FakeStandardTranslator())
        val firstOutcome = standardCoordinator(
            store,
            FakePreflightOcrWorker(store, textlessPages = setOf("p2")),
            pages,
            firstSeam,
            null,
        ).runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)
        firstOutcome.status shouldBe BatchPass1Status.COMPLETED
        val pointerAfterRun1 = activeRunPointer(store)

        // ---- Pass 2: a FRESH coordinator over the SAME store/config. ----
        val resumeIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val resumeOcrWorker = FakePreflightOcrWorker(store)
        val resumeTranslator = FakeStandardTranslator()
        val resumeSeam = StandardSeam(store, resumeIdentities, resumeTranslator)
        val resumeOutcome = standardCoordinator(
            store,
            resumeOcrWorker,
            pages,
            resumeSeam,
            null,
        ).runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)

        // Zero-work COMPLETE via the LI-2 evidence gate: no re-OCR, no
        // re-translation, no new record publication.
        resumeOutcome.status shouldBe BatchPass1Status.COMPLETED
        resumeOutcome.reason shouldBe ChapterProfileBatchCoordinator.RESUME_COMPLETE_REASON
        resumeOcrWorker.ocrPages shouldBe emptyList()
        resumeSeam.invoked shouldBe emptyList()
        resumeTranslator.calls shouldBe emptyList()
        activeRunPointer(store) shouldBe pointerAfterRun1
        durableRunRecord(store).shouldNotBeNull().state shouldBe ChapterRunState.COMPLETE
    }

    // ------------------------------------------------------------------
    // T4 — frozenConfig snapshot identity.
    // ------------------------------------------------------------------

    @Test
    fun `standard lane records freeze the standard provider identity and default policies`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val seam = StandardSeam(store, identities, FakeStandardTranslator())
        standardCoordinator(store, FakePreflightOcrWorker(store), pages, seam, null)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)

        val record = durableRunRecord(store).shouldNotBeNull()
        val frozen = record.frozenConfig
        frozen.providerKey shouldBe "standard:google"
        frozen.credentialId shouldBe ""
        // Valid sha256 shape for the schema-required policy fingerprints
        // (default policies — never consulted on the standard lane).
        record.analysisPolicyFingerprint shouldNotBe ""
        record.analysisPolicyFingerprint.length shouldBe 64
        record.envelopePolicyFingerprint.length shouldBe 64
        record.analysisPolicyFingerprint shouldBe ChapterProfileBatchCoordinator.policyFingerprint(
            "analysis-policy-v1",
            frozen.analysisPolicy.overlapPages,
        )
        record.envelopePolicyFingerprint shouldBe ChapterProfileBatchCoordinator.policyFingerprint(
            "envelope-policy-v1",
            frozen.envelopePolicy.maxBlocks,
            frozen.envelopePolicy.maxPages,
        )
        record.ocrCorpusFingerprint.shouldNotBeNull()

        // The fingerprint separates standard engines and credentials: a DeepL
        // key freezes as a one-way 16-hex signature, and any config change
        // (engine, credential, flag) changes the run-config fingerprint.
        val deeplCredential = ChapterProfileBatchCoordinator.sha256Hex("deepl-key").take(16)
        val google = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "standard:google",
        )
        val deepl = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "standard:deepl",
            credentialId = deeplCredential,
        )
        deepl.credentialId shouldBe deeplCredential
        deepl.credentialId.length shouldBe 16
        val googleFingerprint = ChapterProfileBatchCoordinator.runConfigFingerprint(google)
        val deeplFingerprint = ChapterProfileBatchCoordinator.runConfigFingerprint(deepl)
        googleFingerprint shouldNotBe deeplFingerprint
        googleFingerprint shouldBe ChapterProfileBatchCoordinator.runConfigFingerprint(google.copy())
        googleFingerprint shouldNotBe
            ChapterProfileBatchCoordinator.runConfigFingerprint(google.copy(flagProfilePipeline = false))
    }

    // ------------------------------------------------------------------
    // T5 — overlap window discipline.
    // ------------------------------------------------------------------

    @Test
    fun `no inpaint runs while the first translate is in flight and inpaint drains by finalize`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2", "p3")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val releaseP1 = CompletableDeferred<Unit>()
        val translator = FakeStandardTranslator(
            onTranslate = { pageKey ->
                if (pageKey == "p1") {
                    // Hold the FIRST translate call in flight: no page has a
                    // committed translation yet, so the open window must not
                    // produce ANY inpaint.
                    releaseP1.await()
                }
            },
        )
        val seam = StandardSeam(store, identities, translator)
        val inpaintLane = FakeOverlapInpaintLane(store, identities)
        val scheduler = OverlapScheduler(
            store = store,
            nativeWorker = inpaintLane,
            orderedPageKeys = pageKeys,
            batchWriteIdentities = identities,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )

        var outcome: BatchPass1Outcome? = null
        val ocrWorker = FakePreflightOcrWorker(store)
        // A TestScope child (NOT backgroundScope): the run must be driven by
        // advanceUntilIdle up to the held latch and joined after release.
        val run = launch {
            outcome = standardCoordinator(store, ocrWorker, pages, seam, scheduler)
                .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)
        }
        advanceUntilIdle()

        // p1's translate call is in flight inside its open window; ZERO
        // inpaint has executed (nothing committed yet — the scheduler's
        // candidacy rule leaves it only the window drain).
        translator.calls shouldContainExactly listOf("p1")
        scheduler.counters.snapshot()["overlapWindowsCount"].shouldNotBeNull().let { windows ->
            (windows >= 1L) shouldBe true
        }
        (scheduler.counters.snapshot()["overlapInpaintsExecuted"] == 0L) shouldBe true
        (scheduler.counters.snapshot()["serialInpaintsExecuted"] == 0L) shouldBe true
        inpaintLane.inpainted shouldBe emptyList()

        releaseP1.complete(Unit)
        advanceUntilIdle()
        run.join()

        // The run completes; every page translated; ALL inpaint drained by
        // the FINALIZE phase (overlap windows + serial drain combined).
        outcome.shouldNotBeNull().status shouldBe BatchPass1Status.COMPLETED
        translator.calls shouldContainExactly listOf("p1", "p2", "p3")
        inpaintLane.inpainted shouldContainExactly pageKeys
        val counters = scheduler.counters.snapshot()
        (counters["overlapInpaintsExecuted"]!! + counters["serialInpaintsExecuted"]!!) shouldBe 3L
        pageKeys.forEach { key ->
            val page = store.snapshot(key).page.shouldNotBeNull()
            page.translationStatus shouldBe StageStatus.READY
            page.inpaintStatus shouldBe StageStatus.READY
        }
    }

    // ------------------------------------------------------------------
    // T6 — genuinely blank pages (Wave B Task 1). Real chapters have them:
    // a ZERO-block OCR result must checkpoint, complete, and resume — never
    // fail the preflight. Shared by the AI lane (same preflight loop).
    // ------------------------------------------------------------------

    @Test
    fun `genuinely empty textless page checkpoints, completes, and never re-OCRs`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val translator = FakeStandardTranslator()
        val seam = StandardSeam(store, identities, translator)
        val ocrWorker = FakePreflightOcrWorker(store, emptyTextPages = setOf("p2"))

        val outcome = standardCoordinator(store, ocrWorker, pages, seam, null)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)

        // A healthy blank page never fails the preflight. RED (pre-fix): the
        // empty-block OCR write is transient per shouldPersistUpdate, so p2
        // opens no candidate; with no committed bundle either, the checkpoint
        // CLOSE adopt branch rejected and the run died mid-preflight with a
        // CHECKPOINT_REJECTED failure-ledger charge.
        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        val record = durableRunRecord(store).shouldNotBeNull()
        record.state shouldBe ChapterRunState.COMPLETE

        // p2: the tail's legacy textless commit — SKIPPED terminal, zero
        // provider calls (parity with the whitespace-textless page of T2).
        seam.invoked shouldContainExactly pageKeys
        translator.calls shouldContainExactly listOf("p1")
        val p2 = store.snapshot("p2").page.shouldNotBeNull()
        p2.translationStatus shouldBe StageStatus.SKIPPED
        p2.renderStatus shouldBe StageStatus.SKIPPED

        // The blank page's OCR evidence is durably checkpointed like any other
        // page — the ST-05/ST-06 reuse shape.
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.ocrCheckpoints.keys shouldBe pageKeys.toSet()

        // ---- Pass 2: zero-work COMPLETE; the blank page is never re-OCRed. ----
        val resumeOcrWorker = FakePreflightOcrWorker(store)
        val resumeSeam = StandardSeam(store, ConcurrentHashMap(), FakeStandardTranslator())
        val resumeOutcome = standardCoordinator(store, resumeOcrWorker, pages, resumeSeam, null)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)
        resumeOutcome.status shouldBe BatchPass1Status.COMPLETED
        resumeOutcome.reason shouldBe ChapterProfileBatchCoordinator.RESUME_COMPLETE_REASON
        resumeOcrWorker.ocrPages shouldBe emptyList()
        resumeSeam.invoked shouldBe emptyList()
    }

    // ------------------------------------------------------------------
    // T7 — flag-ON re-dispatch over a stale non-FINALIZE standard record
    // (Wave B Task 2c, investigator risk 7): `resumeFinalizeOrComplete`
    // returns null, the SAME run id continues with a fresh RUN_SNAPSHOT
    // (ST-15 fingerprint match), the preflight reuses its checkpoints, and
    // the run closes with exactly ONE COMPLETE. No crash, no double closure.
    // ------------------------------------------------------------------

    @Test
    fun `re-dispatch over an interrupted standard run continues the same run id and completes exactly once`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2", "p3")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

        // Run 1: a typed PAUSE in the middle of the translate tail — the
        // durable record is a non-FINALIZE TRANSLATE record.
        val pausingSeam = StandardSeam(
            store,
            ConcurrentHashMap(),
            FakeStandardTranslator(),
            pauseOnPages = setOf("p2"),
        )
        val interrupted = standardCoordinator(store, FakePreflightOcrWorker(store), pages, pausingSeam, null)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)
        interrupted.status shouldBe BatchPass1Status.PAUSED
        val interruptedRecord = durableRunRecord(store).shouldNotBeNull()
        interruptedRecord.state shouldBe ChapterRunState.TRANSLATE
        interruptedRecord.runId.shouldNotBeNull()
        val interruptedRunId = interruptedRecord.runId

        // Run 2: flag-ON re-dispatch under the SAME configuration.
        val resumeOcrWorker = FakePreflightOcrWorker(store)
        val resumeSeam = StandardSeam(store, ConcurrentHashMap(), FakeStandardTranslator())
        val outcome = standardCoordinator(store, resumeOcrWorker, pages, resumeSeam, null)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        // Checkpoint reuse: the interrupted run's preflight work is never
        // re-paid.
        resumeOcrWorker.ocrPages shouldBe emptyList()
        // Only p2 and p3 needed the seam: p1's committed READY terminal is
        // never re-entered (standardPageTerminalAtTranslate).
        resumeSeam.invoked shouldContainExactly listOf("p2", "p3")
        // Single closure under the SAME run id — no fresh run, no double
        // COMPLETE.
        val finalRecord = durableRunRecord(store).shouldNotBeNull()
        finalRecord.state shouldBe ChapterRunState.COMPLETE
        finalRecord.runId shouldBe interruptedRunId
        finalRecord.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE] shouldBe 1
        pageKeys.forEach { key ->
            store.snapshot(key).page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        }
    }

    // ------------------------------------------------------------------
    // T8 — flag-ON zero-work resume over the SKIPPED no-text evidence shape
    // (Wave B Task 2d): a masked blank-text page ends translation SKIPPED
    // with inpaint PENDING — NOT a full `isTextlessTerminal` — so the LI-2
    // gate must accept it through the `isNoTextTerminal` extension
    // (translationStatus SKIPPED alone), exactly like the unmasked twin.
    // ------------------------------------------------------------------

    @Test
    fun `flag-on zero-work resume accepts SKIPPED textless pages that are not full textless terminals`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2", "p3")
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // p2: unmasked blank-text (full textless terminal: inpaint SKIPPED);
        // p3: masked blank-text (SKIPPED translation, inpaint PENDING).
        val ocrWorker = FakePreflightOcrWorker(
            store,
            textlessPages = setOf("p2"),
            maskedTextlessPages = setOf("p3"),
        )
        val firstSeam = StandardSeam(store, ConcurrentHashMap(), FakeStandardTranslator())
        val firstOutcome = standardCoordinator(store, ocrWorker, pages, firstSeam, null)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)
        firstOutcome.status shouldBe BatchPass1Status.COMPLETED

        // The SKIPPED no-text evidence shapes, pinned: a blank-TEXT page
        // (whitespace block present) is NEVER a full `isTextlessTerminal`
        // (that requires ZERO blocks — T6's genuinely blank page) — its
        // resume evidence is `isNoTextTerminal` (translationStatus SKIPPED
        // alone), with or without a pending inpaint drain.
        val p2 = store.snapshot("p2").page.shouldNotBeNull()
        p2.translationStatus shouldBe StageStatus.SKIPPED
        p2.inpaintStatus shouldBe StageStatus.SKIPPED
        p2.isTextlessTerminal shouldBe false
        val p3 = store.snapshot("p3").page.shouldNotBeNull()
        p3.translationStatus shouldBe StageStatus.SKIPPED
        p3.inpaintStatus shouldBe StageStatus.PENDING
        p3.isTextlessTerminal shouldBe false

        val pointerAfterRun1 = activeRunPointer(store)

        // Flag-ON re-dispatch through `resumeFinalizeOrComplete`: the
        // COMPLETE record's per-page LI-2 evidence gate accepts p3 through
        // the isNoTextTerminal extension (and p2 through the textless
        // terminal) — zero-work COMPLETE.
        val resumeOcrWorker = FakePreflightOcrWorker(store)
        val resumeSeam = StandardSeam(store, ConcurrentHashMap(), FakeStandardTranslator())
        val resumeOutcome = standardCoordinator(store, resumeOcrWorker, pages, resumeSeam, null)
            .runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)

        resumeOutcome.status shouldBe BatchPass1Status.COMPLETED
        resumeOutcome.reason shouldBe ChapterProfileBatchCoordinator.RESUME_COMPLETE_REASON
        resumeOcrWorker.ocrPages shouldBe emptyList()
        resumeSeam.invoked shouldBe emptyList()
        activeRunPointer(store) shouldBe pointerAfterRun1
    }
}
