package eu.kanade.translation.translator

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContextualResponseParserTest {

    @Test
    fun `complete response translates every known id`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val lines = listOf("b0|Hello.", "b1|World!")
        val results = ContextualResponseParser.parse(lines, idMap)

        results shouldHaveSize 2
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "Hello."
        results[1].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[1].text shouldBe "World!"
    }

    @Test
    fun `unknown id is rejected and never applied`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b9|Ghost."), idMap)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].targetKey shouldBe null
    }

    @Test
    fun `blank payload is rejected`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("b0|   "), idMap)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
    }

    @Test
    fun `malformed line is rejected`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(listOf("this has no id prefix"), idMap)
        results[0].status shouldBe ContextualTranslationResult.Status.REJECTED
        results[0].targetKey shouldBe null
    }

    @Test
    fun `duplicate id keeps first and rejects the second as ambiguous`() {
        val idMap = linkedIdMap("b0" to 0)
        val results = ContextualResponseParser.parse(
            listOf("b0|First.", "b0|Second."),
            idMap,
        )
        results shouldHaveSize 2
        results[0].status shouldBe ContextualTranslationResult.Status.TRANSLATED
        results[0].text shouldBe "First."
        results[1].status shouldBe ContextualTranslationResult.Status.REJECTED
    }

    @Test
    fun `missing id in response leaves that block untouched`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val results = ContextualResponseParser.parse(listOf("b0|Only."), idMap)
        results shouldHaveSize 1
        results[0].id shouldBe "b0"
    }

    @Test
    fun `batch accepted and rejected partition correctly`() {
        val idMap = linkedIdMap("b0" to 0, "b1" to 1)
        val results = ContextualResponseParser.parse(
            listOf("b0|Good.", "b1|   ", "xx|garbage"),
            idMap,
        )
        results.filter { it.status == ContextualTranslationResult.Status.TRANSLATED } shouldHaveSize 1
        results.filter { it.status == ContextualTranslationResult.Status.REJECTED } shouldHaveSize 2
    }

    private fun linkedIdMap(vararg entries: Pair<String, Int>): LinkedHashMap<String, AnchoredTargetKey> {
        val map = LinkedHashMap<String, AnchoredTargetKey>()
        entries.forEach { (id, blockIdx) -> map[id] = AnchoredTargetKey(0, blockIdx) }
        return map
    }
}
