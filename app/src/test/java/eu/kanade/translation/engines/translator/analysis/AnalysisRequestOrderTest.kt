package eu.kanade.translation.engines.translator.analysis

import eu.kanade.translation.engines.translator.contextual.PlannedAnalysisChunk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 *  wave-2 review GAP-3 / F4 (BINDING): the request-builder pin. The wire
 * `pages` array MUST reproduce the contributing-set order exactly — CORE
 * pages first, CONTEXT overlap pages after — because the persisted
 * `contributingCorpusFingerprint`  hashes the same set in that
 * order with `naturalOrderProven=true`, and the  request payload is
 * the convention the whole pipeline references.
 */
class AnalysisRequestOrderTest {

    private fun chunk(
        core: List<String> = listOf("0001.jpg", "0002.jpg", "0003.jpg"),
        overlap: List<String> = listOf("0004.jpg"),
    ) = PlannedAnalysisChunk(
        chunkOrdinal = 0,
        chunkId = "chunk-0-abcdef12",
        corePageKeys = core,
        contextOverlapPageKeys = overlap,
        contributingCorpusFingerprint = "a".repeat(64),
        coreBlockCount = 3,
        contributingBlockCount = 4,
        estimatedInputTokens = 40,
        contributingBlockIds = mapOf(
            "0001.jpg" to listOf("p0_b0"),
            "0002.jpg" to listOf("p1_b0"),
            "0003.jpg" to listOf("p2_b0"),
            "0004.jpg" to listOf("p3_b0"),
        ),
    )

    private fun evidence(storageToWire: Map<String, String>) = AnalysisEvidenceTexts(
        blockIdsByPage = storageToWire.entries.associate { (storage, wire) ->
            wire to listOf(wire + "_b0")
        },
        textByBlockId = storageToWire.entries.associate { (storage, wire) ->
            (wire + "_b0") to "text of $storage"
        },
        wirePageKeyByStorageKey = storageToWire,
    )

    private fun identity() = AnalysisRunIdentity(
        runId = "run-1-abcdef12",
        mangaKeyHash = AnalysisRunIdentity.SCOPE_ABSENT,
        chapterKeyHash = "sha256:" + "b".repeat(64),
        sourceLanguage = "ja",
        targetLanguage = "en",
        analysisPolicyFingerprint = "c".repeat(64),
        ocrCorpusFingerprint = "d".repeat(64),
        maxOutputTokens = 8192,
    )

    @Test
    fun `request pages are emitted in core-then-context order (gap-3 pin)`() {
        val storageToWire = mapOf(
            "0001.jpg" to "p0",
            "0002.jpg" to "p1",
            "0003.jpg" to "p2",
            "0004.jpg" to "p3",
        )
        val request = AnalysisRequestBuilder.buildChunkRequest(
            chunk = chunk(),
            pages = evidence(storageToWire).toRequestPages(chunk()),
            identity = identity(),
        )

        // The exact convention pinned by wave-2 F4 / schemas contract §1.3.
        request.orderedPageKeys shouldBe listOf("p0", "p1", "p2", "p3")

        val parsed = Json.parseToJsonElement(request.requestJson).jsonObject
        val pages = parsed["pages"]!!.jsonArray
        pages.size shouldBe 4
        pages.map { it.jsonObject["pageKey"]!!.jsonPrimitive.content } shouldBe
            listOf("p0", "p1", "p2", "p3")
        pages.map { it.jsonObject["role"]!!.jsonPrimitive.content } shouldBe
            listOf("CORE", "CORE", "CORE", "CONTEXT")

        // Envelope identity rides along.
        val envelope = parsed["envelope"]!!.jsonObject
        envelope["protocol"]!!.jsonPrimitive.content shouldBe AnalysisRequestBuilder.PROTOCOL
        envelope["schemaVersion"]!!.jsonPrimitive.content shouldBe "1"
        envelope["requestKind"]!!.jsonPrimitive.content shouldBe "CHUNK"
        envelope["chunkId"]!!.jsonPrimitive.content shouldBe "chunk-0-abcdef12"
        parsed["existingCanon"]!!.jsonObject["userAuthority"]!!
            .jsonPrimitive.content shouldBe "null"
    }

    @Test
    fun `request document is byte-deterministic for equal inputs`() {
        val storageToWire = mapOf("0001.jpg" to "p0", "0002.jpg" to "p1")
        val first = AnalysisRequestBuilder.buildChunkRequest(
            chunk = chunk(core = listOf("0001.jpg", "0002.jpg"), overlap = emptyList()),
            pages = evidence(storageToWire).toRequestPages(
                chunk(core = listOf("0001.jpg", "0002.jpg"), overlap = emptyList()),
            ),
            identity = identity(),
        )
        val second = AnalysisRequestBuilder.buildChunkRequest(
            chunk = chunk(core = listOf("0001.jpg", "0002.jpg"), overlap = emptyList()),
            pages = evidence(storageToWire).toRequestPages(
                chunk(core = listOf("0001.jpg", "0002.jpg"), overlap = emptyList()),
            ),
            identity = identity(),
        )
        first.requestJson shouldBe second.requestJson
    }

    @Test
    fun `a CORE page after a CONTEXT page fails fast instead of reordering`() {
        val broken = listOf(
            AnalysisRequestBuilder.RequestPage("p0", AnalysisRequestBuilder.ROLE_CONTEXT, emptyList()),
            AnalysisRequestBuilder.RequestPage("p1", AnalysisRequestBuilder.ROLE_CORE, emptyList()),
        )
        val error = assertThrows<IllegalStateException> {
            AnalysisRequestBuilder.buildChunkRequest(
                chunk = chunk(),
                pages = broken,
                identity = identity(),
            )
        }
        error.message!! shouldContain "core-then-context"
    }

    @Test
    fun `existing canon chapter facts ride the request and authorities stay null`() {
        val storageToWire = mapOf("0001.jpg" to "p0")
        val request = AnalysisRequestBuilder.buildChunkRequest(
            chunk = chunk(core = listOf("0001.jpg"), overlap = emptyList()),
            pages = evidence(storageToWire).toRequestPages(
                chunk(core = listOf("0001.jpg"), overlap = emptyList()),
            ),
            identity = identity(),
            existingCanonChapterFacts = listOf("deterministic pre-merge fact"),
        )
        val parsed = Json.parseToJsonElement(request.requestJson).jsonObject
        val canon = parsed["existingCanon"]!!.jsonObject
        canon["chapterFacts"]!!.jsonArray.single().jsonPrimitive.content shouldBe
            "deterministic pre-merge fact"
    }
}
