package eu.kanade.translation.translator

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.RevisionScope
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RevisionManagerTest {

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
                needsRevision = target.block.needsRevision,
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

    @Test
    fun `RevisionDriver runs revision and commits to store`() = runTest {
        val store = storeWith(flaggedPage("p0", draft = "draft0"))
        
        // Mock contextual translator
        val mockTranslator = object : ContextualTextTranslator {
            override val contextualCapability = ContextualTranslationCapability.CONTEXTUAL_REVIEW
            override val fromLang = eu.kanade.translation.ocr.TextRecognizerLanguage.JAPANESE
            override val toLang = TextTranslatorLanguage.ENGLISH

            override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
                throw UnsupportedOperationException()
            }
            override fun close() {}

            override suspend fun translateContextual(chunk: TranslationContextChunk, isPass2: Boolean) {
                throw UnsupportedOperationException()
            }

            override suspend fun promptText(prompt: String): String {
                throw UnsupportedOperationException()
            }

            override suspend fun translateContextualStructured(
                chunk: TranslationContextChunk,
                isPass2: Boolean
            ): ContextualTranslationBatch {
                val targets = chunk.pages.flatMap { (pk, page) ->
                    page.blocks.mapIndexedNotNull { idx, b ->
                        if (b.needsRevision) RevisionPlanner.Target(pk, idx, b) else null
                    }
                }
                return batchFor(targets) { id -> translated(id, "Corrected stand-alone.") }
            }
        }

        val report = RevisionDriver.runRevision(
            store = store,
            contextualTranslator = mockTranslator,
            scope = RevisionScope.FLAGGED,
            requestedOutputTokens = 512,
            chapterId = 1L,
            chapterName = "Test Chapter",
        )

        report.correctedCount shouldBe 1
        report.unresolvedCount shouldBe 0
        
        val committed = store.state.value["p0"]!!.blocks[0]
        committed.translation shouldBe "Corrected stand-alone."
        committed.needsRevision shouldBe false
    }

    @Test
    fun `RevisionDriver respects ALL_TRANSLATED scope and revises unflagged blocks`() = runTest {
        val store = storeWith(
            PageTranslation(
                sourceFileName = "p0",
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "src", translation = "draft0",
                        width = 10f, height = 10f, x = 0f, y = 0f,
                        symHeight = 1f, symWidth = 1f, angle = 0f,
                        needsRevision = false, // Not flagged!
                    )
                )
            )
        )

        val mockTranslator = object : ContextualTextTranslator {
            override val contextualCapability = ContextualTranslationCapability.CONTEXTUAL_REVIEW
            override val fromLang = eu.kanade.translation.ocr.TextRecognizerLanguage.JAPANESE
            override val toLang = TextTranslatorLanguage.ENGLISH

            override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
                throw UnsupportedOperationException()
            }
            override fun close() {}

            override suspend fun translateContextual(chunk: TranslationContextChunk, isPass2: Boolean) {
                throw UnsupportedOperationException()
            }

            override suspend fun promptText(prompt: String): String {
                throw UnsupportedOperationException()
            }

            override suspend fun translateContextualStructured(
                chunk: TranslationContextChunk,
                isPass2: Boolean
            ): ContextualTranslationBatch {
                val targets = chunk.pages.flatMap { (pk, page) ->
                    page.blocks.mapIndexedNotNull { idx, b ->
                        RevisionPlanner.Target(pk, idx, b)
                    }
                }
                return batchFor(targets) { id -> translated(id, "Corrected ALL.") }
            }
        }

        val report = RevisionDriver.runRevision(
            store = store,
            contextualTranslator = mockTranslator,
            scope = RevisionScope.ALL_TRANSLATED,
            requestedOutputTokens = 512,
            chapterId = 1L,
            chapterName = "Test Chapter",
        )

        report.correctedCount shouldBe 1
        
        val committed = store.state.value["p0"]!!.blocks[0]
        committed.translation shouldBe "Corrected ALL."
        committed.needsRevision shouldBe false
    }
}
