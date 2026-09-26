package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.StagePatchResult
import eu.kanade.translation.persistence.chapter.TranslationBlockPatch
import eu.kanade.translation.persistence.chapter.TranslationStagePatch
import eu.kanade.translation.persistence.chapter.ocrBlockFingerprints
import eu.kanade.translation.persistence.chapter.ocrFingerprint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 20 (Stage-6 slice A): the ADDITIVE NULLABLE provenance fields on
 * [TranslationStagePatch]. Pins:
 *
 *  - a patch constructed WITHOUT the new fields keeps byte-identical legacy
 *    merge behavior (accepted, no manifest reads, no new rejection class);
 *  - a non-null `profileContentFingerprint` that does not match the
 *    manifest's currently frozen profile REJECTS the whole page patch
 *    (stale-profile protection) without mutating any block;
 *  - a non-null `envelopePlanFingerprint` that does not match the manifest's
 *    `envelopePlan` pointer REJECTS the same way.
 *
 * The in-memory store has NO artifact authority here — exactly the condition
 * under which the pre- merge path already worked, which makes the
 * null-vs-non-null contrast direct.
 */
class TranslationProvenanceMergeTest {

    private fun store() = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = emptyMap(),
    )

    private fun block(text: String, translation: String = "") = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(vararg texts: String) = PageTranslation(
        blocks = texts.map { block(it) }.toMutableList(),
    )

    private fun patch(
        pageKey: String,
        snapshot: ChapterTranslationStore.PageSnapshot,
        translation: String = "ONE",
        profileContentFingerprint: String? = null,
        envelopePlanFingerprint: String? = null,
    ): TranslationStagePatch {
        val page = snapshot.page!!
        return TranslationStagePatch(
            pageKey = pageKey,
            generation = snapshot.generation,
            expectedOcrBlockFingerprints = page.ocrBlockFingerprints(),
            expectedSourceTexts = page.blocks.map { it.text },
            blocks = listOf(
                TranslationBlockPatch(
                    blockIndex = 0,
                    expectedOcrFingerprint = page.blocks[0].ocrFingerprint(),
                    expectedSourceText = page.blocks[0].text,
                    expectedTranslation = page.blocks[0].translation,
                    expectedUserEditedAt = page.blocks[0].userEditedAt,
                    translation = translation,
                ),
            ),
            translationStatus = StageStatus.READY,
            expectedPageVersion = snapshot.pageVersion,
            profileContentFingerprint = profileContentFingerprint,
            envelopePlanFingerprint = envelopePlanFingerprint,
        )
    }

    @Test
    fun `legacy patch without the new fields keeps byte-identical behavior`() = runTest {
        val store = store()
        store.updatePage("p1") { ocrPage("one", "two") }
        val snapshot = store.snapshot("p1")

        // Named-argument construction WITHOUT the new fields: the exact
        // legacy call shape. No artifact authority exists in this store.
        val legacyPatch = TranslationStagePatch(
            pageKey = "p1",
            generation = snapshot.generation,
            expectedOcrBlockFingerprints = snapshot.page!!.ocrBlockFingerprints(),
            expectedSourceTexts = snapshot.page!!.blocks.map { it.text },
            blocks = listOf(
                TranslationBlockPatch(
                    blockIndex = 0,
                    expectedOcrFingerprint = snapshot.page!!.blocks[0].ocrFingerprint(),
                    expectedSourceText = "one",
                    expectedTranslation = "",
                    expectedUserEditedAt = null,
                    translation = "ONE",
                ),
            ),
            translationStatus = StageStatus.READY,
            expectedPageVersion = snapshot.pageVersion,
        )

        val result = store.mergeTranslation(legacyPatch)
        result.shouldBeInstanceOf<StagePatchResult.Accepted>()
        store.state.value["p1"]!!.blocks[0].translation shouldBe "ONE"
        store.state.value["p1"]!!.translationStatus shouldBe StageStatus.READY
    }

    @Test
    fun `stale profile fingerprint rejects the whole page patch`() = runTest {
        val store = store()
        store.updatePage("p1") { ocrPage("one", "two") }
        val snapshot = store.snapshot("p1")

        val result = store.mergeTranslation(
            patch(
                "p1",
                snapshot,
                profileContentFingerprint = "a".repeat(64),
            ),
        )
        val rejected = result.shouldBeInstanceOf<StagePatchResult.Rejected>()
        rejected.reason shouldContain "translation provenance rejected: frozen profile changed"
        // Nothing committed: the translation never landed.
        store.state.value["p1"]!!.blocks[0].translation shouldBe ""
    }

    @Test
    fun `stale envelope plan fingerprint rejects the whole page patch`() = runTest {
        val store = store()
        store.updatePage("p1") { ocrPage("one", "two") }
        val snapshot = store.snapshot("p1")

        val result = store.mergeTranslation(
            patch(
                "p1",
                snapshot,
                envelopePlanFingerprint = "b".repeat(64),
            ),
        )
        val rejected = result.shouldBeInstanceOf<StagePatchResult.Rejected>()
        rejected.reason shouldContain "translation provenance rejected: envelope plan changed"
        store.state.value["p1"]!!.blocks[0].translation shouldBe ""
    }
}
