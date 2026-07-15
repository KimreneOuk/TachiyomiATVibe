package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Guards [RevisionMerger] — the strict Pass-2 merge (Checkpoint 2 §6).
 */
class RevisionMergerTest {

    @Test
    fun `applied correction clears the flag and updates the draft`() {
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0),
            responses = { id -> translated(id, "Corrected.") },
        )
        val result = RevisionMerger.merge(live, batch, targets, chapterId = 1L, chapterName = "ch")

        result.appliedCount shouldBe 1
        result.retainedCount shouldBe 0
        result.requestFailed shouldBe false
        live.getValue("p0").blocks[0].translation shouldBe "Corrected."
        (live.getValue("p0").blocks[0].needsRevision) shouldBe false
    }

    @Test
    fun `stale draft is rejected and retains the flag`() {
        // The request snapshot captured draft "oldDraft", but the live block's
        // draft was concurrently changed to "newDraft" -> merge must reject.
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0),
            liveDraftOverride = Hold.Forced("newDraft"),
            snapshotDraftOverride = Hold.Forced("oldDraft"),
            responses = { id -> translated(id, "Corrected.") },
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.appliedCount shouldBe 0
        result.retainedCount shouldBe 1
        result.retained[0].reason shouldContain "draft changed"
        live.getValue("p0").blocks[0].translation shouldBe "newDraft"
        (live.getValue("p0").blocks[0].needsRevision) shouldBe true
    }

    @Test
    fun `concurrent user edit wins over the correction`() {
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0),
            // The live block was user-edited AFTER the request snapshot.
            liveUserEditedAtOverride = Hold.Forced(999L),
            snapshotUserEditedAtOverride = Hold.Forced(null),
            responses = { id -> translated(id, "Corrected.") },
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.appliedCount shouldBe 0
        result.retained[0].reason shouldContain "userEditedAt"
        live.getValue("p0").blocks[0].needsRevision shouldBe true
    }

    @Test
    fun `missing result for a target retains the flag`() {
        // The response omits the id entirely.
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0),
            responses = { _ -> null },
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.appliedCount shouldBe 0
        result.retainedCount shouldBe 1
        result.retained[0].reason shouldContain "missing"
        live.getValue("p0").blocks[0].needsRevision shouldBe true
    }

    @Test
    fun `blank correction is rejected`() {
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0),
            responses = { id -> rejectedBlank(id) },
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.appliedCount shouldBe 0
        result.retained[0].reason shouldContain "rejected"
        live.getValue("p0").blocks[0].needsRevision shouldBe true
    }

    @Test
    fun `null batch (request failure) leaves every flag intact`() {
        val live = livePage("p0", flaggedBlock("text", "draft"))
        val targets = listOf(revisionTarget("p0", 0, live.getValue("p0").blocks[0]))

        val result = RevisionMerger.merge(live, batch = null, targets, 1L, "ch")

        result.requestFailed shouldBe true
        result.appliedCount shouldBe 0
        result.retainedCount shouldBe 1
        live.getValue("p0").blocks[0].needsRevision shouldBe true
    }

    @Test
    fun `only applied corrections increment completed and partial page stays PARTIAL`() {
        // Two flagged blocks on one page. One corrected, one missing whose draft
        // is blank (genuinely untranslated) -> the page is PARTIAL after
        // validation, never forced READY.
        val (live, targets, batch) = scenarioWithDrafts(
            flags = listOf("p0" to 0, "p0" to 1),
            drafts = listOf("draft0", ""),
            responses = { id -> if (id == "p0_b0") translated(id, "Fixed.") else null },
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.appliedCount shouldBe 1
        result.retainedCount shouldBe 1
        // The touched page status is PARTIAL (one block still untranslated).
        result.pageStatuses["p0"] shouldBe StageStatus.PARTIAL
        live.getValue("p0").translationStatus shouldBe StageStatus.PARTIAL
    }

    @Test
    fun `all blocks corrected yields READY after validation`() {
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0, "p0" to 1),
            responses = { id -> translated(id, "Fixed $id.") },
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.appliedCount shouldBe 2
        result.pageStatuses["p0"] shouldBe StageStatus.READY
    }

    @Test
    fun `stale fingerprint is rejected`() {
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0),
            // Corrupt the snapshot fingerprint so it cannot match the live block.
            snapshotFingerprintOverride = Hold.Forced("deadbeef"),
            responses = { id -> translated(id, "Fixed.") },
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.appliedCount shouldBe 0
        result.retained[0].reason shouldContain "fingerprint"
        live.getValue("p0").blocks[0].needsRevision shouldBe true
    }

    // ---- scenario builder ----

    private data class Scenario(
        val live: LinkedHashMap<String, PageTranslation>,
        val targets: List<RevisionPlanner.Target>,
        val batch: ContextualTranslationBatch,
    )

    /**
     * Build a deterministic merge scenario. [flags] lists (pageKey, blockIndex)
     * pairs that are the revision targets. [responses] returns the structured
     * result for a given anchored id (or null to omit it). Override holders let a
     * test inject staleness (live vs snapshot mismatch); a [Set] value (even null)
     * overrides the live snapshot, while [Unset] captures the live value.
     */
    private fun scenario(
        flags: List<Pair<String, Int>>,
        responses: (String) -> ContextualTranslationResult?,
        liveDraftOverride: Hold<String> = Hold.Unset,
        snapshotDraftOverride: Hold<String> = Hold.Unset,
        liveUserEditedAtOverride: Hold<Long?> = Hold.Unset,
        snapshotUserEditedAtOverride: Hold<Long?> = Hold.Unset,
        snapshotFingerprintOverride: Hold<String> = Hold.Unset,
    ): Scenario {
        val live = linkedMapOf<String, PageTranslation>()
        val targets = mutableListOf<RevisionPlanner.Target>()
        // Group flags by page so each page's blocks list is consistent.
        val byPage = flags.groupBy({ it.first }, { it.second })
        val locations = LinkedHashMap<String, TargetLocation>()
        val preconditions = HashMap<String, TargetPrecondition>()
        val results = mutableListOf<ContextualTranslationResult>()

        // Build one page at a time, blocks indexed by their position in flags.
        byPage.forEach { (pageKey, blockIndices) ->
            val maxIdx = blockIndices.max()
            val blocks = mutableListOf<TranslationBlock>()
            repeat(maxIdx + 1) { blocks += flaggedBlock("src$it", "draft$it") }
            live[pageKey] = PageTranslation(blocks = blocks)
        }

        byPage.entries.sortedBy { it.key }.forEachIndexed { pageIndex, (pageKey, blockIndices) ->
            blockIndices.sorted().forEachIndexed { seq, blockIndex ->
                val liveBlock = live.getValue(pageKey).blocks[blockIndex]
                if (liveDraftOverride is Hold.Forced<String>) liveBlock.translation = liveDraftOverride.value
                if (liveUserEditedAtOverride is Hold.Forced<Long?>) liveBlock.userEditedAt = liveUserEditedAtOverride.value

                val id = AnchoredBlockId.format(pageIndex, seq)
                val target = RevisionPlanner.Target(pageKey, blockIndex, liveBlock)
                targets += target
                locations[id] = TargetLocation(pageKey, blockIndex)
                preconditions[id] = TargetPrecondition(
                    draft = if (snapshotDraftOverride is Hold.Forced<String>) snapshotDraftOverride.value else liveBlock.translation,
                    fingerprint = if (snapshotFingerprintOverride is Hold.Forced<String>) snapshotFingerprintOverride.value else liveBlock.stableFingerprint(),
                    needsRevision = true,
                    userEditedAt = if (snapshotUserEditedAtOverride is Hold.Forced<Long?>) snapshotUserEditedAtOverride.value else liveBlock.userEditedAt,
                )
                responses(id)?.let { results += it }
            }
        }

        val batch = ContextualTranslationBatch(
            idToBlockIndex = locations,
            results = results,
            preconditions = preconditions,
            isPass2 = true,
        )
        return Scenario(live, targets, batch)
    }

    /** Override holder: [Unset] captures the live value; [Forced] forces one. */
    private sealed interface Hold<out T> {
        data object Unset : Hold<Nothing>
        data class Forced<T>(val value: T) : Hold<T>
    }

    /**
     * Variant of [scenario] that lets each flagged block carry an explicit draft
     * (including blank), so post-merge validation reflects real translation
     * completeness rather than always-present drafts.
     */
    private fun scenarioWithDrafts(
        flags: List<Pair<String, Int>>,
        drafts: List<String>,
        responses: (String) -> ContextualTranslationResult?,
    ): Scenario {
        val live = linkedMapOf<String, PageTranslation>()
        val targets = mutableListOf<RevisionPlanner.Target>()
        val byPage = flags.groupBy({ it.first }, { it.second })
        val locations = LinkedHashMap<String, TargetLocation>()
        val preconditions = HashMap<String, TargetPrecondition>()
        val results = mutableListOf<ContextualTranslationResult>()

        byPage.forEach { (pageKey, blockIndices) ->
            val maxIdx = blockIndices.max()
            val blocks = mutableListOf<TranslationBlock>()
            repeat(maxIdx + 1) { blocks += flaggedBlock("src$it", drafts.getOrElse(it) { "draft$it" }) }
            live[pageKey] = PageTranslation(blocks = blocks)
        }

        var flatIdx = 0
        byPage.entries.sortedBy { it.key }.forEachIndexed { pageIndex, (pageKey, blockIndices) ->
            blockIndices.sorted().forEachIndexed { seq, blockIndex ->
                val liveBlock = live.getValue(pageKey).blocks[blockIndex]
                val id = AnchoredBlockId.format(pageIndex, seq)
                targets += RevisionPlanner.Target(pageKey, blockIndex, liveBlock)
                locations[id] = TargetLocation(pageKey, blockIndex)
                preconditions[id] = TargetPrecondition(
                    draft = liveBlock.translation,
                    fingerprint = liveBlock.stableFingerprint(),
                    needsRevision = true,
                    userEditedAt = liveBlock.userEditedAt,
                )
                responses(id)?.let { results += it }
                flatIdx += 1
            }
        }

        val batch = ContextualTranslationBatch(
            idToBlockIndex = locations,
            results = results,
            preconditions = preconditions,
            isPass2 = true,
        )
        return Scenario(live, targets, batch)
    }

    private fun translated(id: String, text: String) = ContextualTranslationResult(
        id = id,
        targetKey = null,
        text = text,
        status = ContextualTranslationResult.Status.TRANSLATED,
        qualityTag = null,
    )

    private fun rejectedBlank(id: String) = ContextualTranslationResult(
        id = id,
        targetKey = null,
        text = "   ",
        status = ContextualTranslationResult.Status.REJECTED,
    )

    private fun livePage(key: String, vararg blocks: TranslationBlock): LinkedHashMap<String, PageTranslation> {
        val map = linkedMapOf<String, PageTranslation>()
        map[key] = PageTranslation(blocks = blocks.toMutableList())
        return map
    }

    private fun flaggedBlock(text: String, draft: String) = TranslationBlock(
        text = text,
        translation = draft,
        width = 10f, height = 10f, x = 0f, y = 0f,
        symHeight = 1f, symWidth = 1f, angle = 0f,
        needsRevision = true,
    )

    private fun revisionTarget(pageKey: String, blockIndex: Int, block: TranslationBlock) =
        RevisionPlanner.Target(pageKey, blockIndex, block)
}
