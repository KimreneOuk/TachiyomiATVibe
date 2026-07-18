package eu.kanade.translation.translator

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * TachiyomiAT: integration tests proving the Checkpoint 2 components are WIRED
 * into the live pipeline behavior. These exercise the real
 * [ChapterTranslationStore] atomic-patch API + [RevisionMerger] +
 * [RevisionCommitter] (the Pass-2 commit path extracted from
 * [eu.kanade.translation.TranslationPipeline]) and the
 * [TranslatorComputeClass] lane routing, without requiring real OCR/ONNX/network.
 *
 * Guards the invariants the integration task calls out:
 *  (f) revision merge via the store atomic patch rejects stale/edit/missing and
 *      only applied corrections clear the flag.
 *  (g) validation after merge preserves PARTIAL (a page is never forced READY).
 *  plus TranslatorComputeClass routing (ML Kit serialized with native, remote
 *  overlaps) and that the planner-driven InactivityFlusher preserves the chunk
 *  budget.
 */
class Checkpoint2IntegrationTest {

    // ---- (f) revision merge via store atomic patch ----

    @Test
    fun `applied correction commits through patchBlock and clears the flag`() = runTest {
        val store = storeWith(flaggedPage("p0", draft = "draft0"))
        val livePage = store.state.value["p0"]!!
        val target = RevisionPlanner.Target("p0", 0, livePage.blocks[0])
        val batch = batchFor(listOf(target), responses = { translated(it, "Corrected.") })

        val mergeLive = RevisionCommitter.mergeLiveSnapshot(store, listOf("p0"))
        val mergeResult = RevisionMerger.merge(mergeLive, batch, listOf(target), 1L, "ch")

        val outcome = RevisionCommitter.commit(
            store = store,
            mergeLive = mergeLive,
            groupTargets = listOf(target),
            batch = batch,
            orderedAnchorIds = RevisionCommitter.orderedAnchorIds(batch),
            mergeResult = mergeResult,
            chapterId = 1L,
            chapterName = "ch",
        )

        outcome.appliedCount shouldBe 1
        outcome.failedCount shouldBe 0
        // The live store now carries the corrected, sanitized draft + cleared flag.
        val committed = store.state.value["p0"]!!.blocks[0]
        committed.translation shouldBe "Corrected."
        (committed.needsRevision) shouldBe false
    }

    @Test
    fun `stale live block is rejected by the store patch and retains the flag`() = runTest {
        val store = storeWith(flaggedPage("p0", draft = "oldDraft"))
        val target = RevisionPlanner.Target("p0", 0, store.state.value["p0"]!!.blocks[0])
        val batch = batchFor(listOf(target), responses = { translated(it, "Fixed.") })

        // Concurrent edit AFTER the request snapshot: the live block's draft changes,
        // so the merge snapshot (taken at request time) is now stale relative to the
        // store. The store patch must reject on the changed fingerprint/draft.
        val mergeLive = RevisionCommitter.mergeLiveSnapshot(store, listOf("p0"))
        // Simulate the request-time snapshot being captured, then a concurrent edit
        // landing before the patch commits.
        store.updatePage("p0") { page ->
            page!!.apply { blocks[0] = blocks[0].copy(translation = "concurrentEdit") }
        }
        val mergeResult = RevisionMerger.merge(mergeLive, batch, listOf(target), 1L, "ch")

        val outcome = RevisionCommitter.commit(
            store,
            mergeLive,
            listOf(target),
            batch,
            RevisionCommitter.orderedAnchorIds(batch),
            mergeResult,
            1L,
            "ch",
        )

        outcome.appliedCount shouldBe 0
        outcome.failedCount shouldBe 1
        // The concurrent edit wins; the flag is retained on the live block.
        val live = store.state.value["p0"]!!.blocks[0]
        live.translation shouldContain "concurrentEdit"
        (live.needsRevision) shouldBe true
    }

    @Test
    fun `missing revision result retains the flag and is accounted failed`() = runTest {
        val store = storeWith(flaggedPage("p0", draft = "draft0"))
        val target = RevisionPlanner.Target("p0", 0, store.state.value["p0"]!!.blocks[0])
        // The response omits the id entirely (missing result).
        val batch = batchFor(listOf(target), responses = { null })

        val mergeLive = RevisionCommitter.mergeLiveSnapshot(store, listOf("p0"))
        val mergeResult = RevisionMerger.merge(mergeLive, batch, listOf(target), 1L, "ch")
        val outcome = RevisionCommitter.commit(
            store,
            mergeLive,
            listOf(target),
            batch,
            RevisionCommitter.orderedAnchorIds(batch),
            mergeResult,
            1L,
            "ch",
        )

        outcome.appliedCount shouldBe 0
        outcome.failedCount shouldBe 1
        (store.state.value["p0"]!!.blocks[0].needsRevision) shouldBe true
        (store.state.value["p0"]!!.blocks[0].translation) shouldBe "draft0"
    }

    @Test
    fun `user edited block wins over correction and retains the flag`() = runTest {
        val store = storeWith(flaggedPage("p0", draft = "draft0"))
        val target = RevisionPlanner.Target("p0", 0, store.state.value["p0"]!!.blocks[0])
        val batch = batchFor(listOf(target), responses = { translated(it, "Fixed.") })

        val mergeLive = RevisionCommitter.mergeLiveSnapshot(store, listOf("p0"))
        // A user edit lands after the request snapshot.
        store.updatePage("p0") { page ->
            page!!.apply {
                blocks[0] = blocks[0].copy(translation = "userEdit", userEditedAt = 99L)
            }
        }
        val mergeResult = RevisionMerger.merge(mergeLive, batch, listOf(target), 1L, "ch")
        val outcome = RevisionCommitter.commit(
            store,
            mergeLive,
            listOf(target),
            batch,
            RevisionCommitter.orderedAnchorIds(batch),
            mergeResult,
            1L,
            "ch",
        )

        outcome.appliedCount shouldBe 0
        outcome.failedCount shouldBe 1
        val live = store.state.value["p0"]!!.blocks[0]
        live.translation shouldBe "userEdit"
        (live.userEditedAt) shouldBe 99L
        (live.needsRevision) shouldBe true
    }

    // ---- (g) validation after merge preserves PARTIAL ----

    @Test
    fun `page with a still-untranslated block stays PARTIAL after merge`() = runTest {
        // Two flagged blocks; one gets corrected, the other's result is missing AND its
        // draft is blank (genuinely untranslated). After commit + re-validation the page
        // must be PARTIAL, never forced READY.
        val store = storeWith(
            PageTranslation(
                sourceFileName = "p0",
                blocks = mutableListOf(
                    flaggedBlock("src0", "draft0"),
                    flaggedBlock("src1", ""), // blank draft -> untranslated
                ),
            ),
        )
        val page = store.state.value["p0"]!!
        val t0 = RevisionPlanner.Target("p0", 0, page.blocks[0])
        val t1 = RevisionPlanner.Target("p0", 1, page.blocks[1])
        // Only the first block gets a correction; the second is missing.
        val batch = batchFor(
            targets = listOf(t0, t1),
            responses = { id -> if (id == "p0_b0") translated(id, "Fixed.") else null },
        )

        val mergeLive = RevisionCommitter.mergeLiveSnapshot(store, listOf("p0"))
        val mergeResult = RevisionMerger.merge(mergeLive, batch, listOf(t0, t1), 1L, "ch")
        val outcome = RevisionCommitter.commit(
            store,
            mergeLive,
            listOf(t0, t1),
            batch,
            RevisionCommitter.orderedAnchorIds(batch),
            mergeResult,
            1L,
            "ch",
        )

        outcome.appliedCount shouldBe 1
        outcome.failedCount shouldBe 1
        outcome.touchedPages shouldBe setOf("p0")
        // Re-validation derived PARTIAL (one block still untranslated) — never READY.
        store.state.value["p0"]!!.translationStatus shouldBe StageStatus.PARTIAL
    }

    @Test
    fun `all blocks corrected yields READY after validation`() = runTest {
        val store = storeWith(
            PageTranslation(
                sourceFileName = "p0",
                blocks = mutableListOf(flaggedBlock("src0", "draft0")),
            ),
        )
        val page = store.state.value["p0"]!!
        val t0 = RevisionPlanner.Target("p0", 0, page.blocks[0])
        val batch = batchFor(listOf(t0), responses = { translated(it, "Fixed.") })

        val mergeLive = RevisionCommitter.mergeLiveSnapshot(store, listOf("p0"))
        val mergeResult = RevisionMerger.merge(mergeLive, batch, listOf(t0), 1L, "ch")
        RevisionCommitter.commit(
            store,
            mergeLive,
            listOf(t0),
            batch,
            RevisionCommitter.orderedAnchorIds(batch),
            mergeResult,
            1L,
            "ch",
        )

        store.state.value["p0"]!!.translationStatus shouldBe StageStatus.READY
    }

    // ---- TranslatorComputeClass lane routing (Task 4) ----

    @Test
    fun `compute class routing classifies ML Kit as serialized and remote as overlapping`() {
        // The pipeline derives the lane routing from TranslatorComputeClass; ML Kit must
        // NOT overlap native (LOCAL_COMPUTE), every remote provider MAY overlap.
        (TranslatorComputeClass.LOCAL_COMPUTE.mayOverlapNative) shouldBe false
        (TranslatorComputeClass.REMOTE_IO.mayOverlapNative) shouldBe true
    }

    @Test
    fun `compute class routes the configured standard engines correctly`() {
        // Mirrors the pipeline's lane decision: standard ML Kit -> serialized, anything
        // else -> remote overlap. AI model is always remote overlap.
        val mlKit = TranslatorComputeClass.forConfiguration(
            tachiyomi.domain.translation.TranslationEngineCategory.STANDARD,
            StandardTranslatorKind.MLKIT.name,
            null,
        )
        val remoteStd = TranslatorComputeClass.forConfiguration(
            tachiyomi.domain.translation.TranslationEngineCategory.STANDARD,
            StandardTranslatorKind.DEEPL.name,
            null,
        )
        val ai = TranslatorComputeClass.forConfiguration(
            tachiyomi.domain.translation.TranslationEngineCategory.AI_MODEL,
            null,
            "gemini",
        )
        mlKit shouldBe TranslatorComputeClass.LOCAL_COMPUTE
        (mlKit.mayOverlapNative) shouldBe false
        remoteStd shouldBe TranslatorComputeClass.REMOTE_IO
        ai shouldBe TranslatorComputeClass.REMOTE_IO
    }

    // ---- InactivityFlusher preserves chunk budget when wired to the planner (Task 3) ----

    @Test
    fun `inactivity flush of the streaming planner stays within the context budget`() = runTest {
        // The pipeline wires InactivityFlusher to StreamingChunkPlanner.flushRemaining;
        // the flushed chunk must respect the planner's token/page budget.
        val planner = StreamingChunkPlanner(8192, TranslationContextChunkPlanner.Profile.DEFAULT)
        val big = "\u65e5".repeat(3000)
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = big, translation = "",
                    width = 10f, height = 10f, x = 0f, y = 0f,
                    symHeight = 1f, symWidth = 1f, angle = 0f,
                ),
            ),
        )
        planner.accept("p0", page)
        val flushed = planner.flushRemaining().finalChunk!!
        (
            flushed.estimatedPromptTokens + flushed.maxOutputTokens +
                TranslationContextChunkPlanner.SAFETY_MARGIN <=
                TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
            ) shouldBe true
    }

    // ---- helpers ----

    private fun storeWith(vararg pages: PageTranslation): ChapterTranslationStore {
        val map = pages.associateBy { it.sourceFileName!! }
        return ChapterTranslationStore(null, null, map)
    }

    private fun flaggedPage(key: String, draft: String): PageTranslation = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(flaggedBlock("src", draft)),
    )

    private fun flaggedBlock(text: String, draft: String) = TranslationBlock(
        text = text,
        translation = draft,
        width = 10f, height = 10f, x = 0f, y = 0f,
        symHeight = 1f, symWidth = 1f, angle = 0f,
        needsRevision = true,
    )

    private fun translated(id: String, text: String) = ContextualTranslationResult(
        id = id,
        targetKey = null,
        text = text,
        status = ContextualTranslationResult.Status.TRANSLATED,
        qualityTag = null,
    )

    /**
     * Build a Pass-2 [ContextualTranslationBatch] for the given targets, assigning
     * anchored ids p0_b0..p0_bN (single-page) and capturing the live preconditions.
     * [responses] returns the structured result for an anchored id (or null to omit).
     */
    private fun batchFor(
        targets: List<RevisionPlanner.Target>,
        responses: (String) -> ContextualTranslationResult?,
    ): ContextualTranslationBatch {
        val locations = LinkedHashMap<String, TargetLocation>()
        val preconditions = HashMap<String, TargetPrecondition>()
        val results = mutableListOf<ContextualTranslationResult>()
        targets.forEachIndexed { seq, target ->
            val id = AnchoredBlockId.format(0, seq)
            locations[id] = TargetLocation(target.pageKey, target.blockIndex)
            preconditions[id] = TargetPrecondition(
                draft = target.block.translation,
                fingerprint = target.block.stableFingerprint(),
                needsRevision = true,
                userEditedAt = target.block.userEditedAt,
            )
            responses(id)?.let { results += it }
        }
        return ContextualTranslationBatch(
            idToBlockIndex = locations,
            results = results,
            preconditions = preconditions,
            isPass2 = true,
        )
    }
}
