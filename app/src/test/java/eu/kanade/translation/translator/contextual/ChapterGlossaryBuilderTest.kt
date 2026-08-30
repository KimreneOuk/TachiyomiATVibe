package eu.kanade.translation.translator.contextual

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterGlossaryBuilderTest {

    @Test
    fun `a recurring name with a stable rendering is captured`() {
        val stats = ChapterGlossaryBuilder.Stats()
        stats.add("太郎は走る", "Taro runs")
        stats.add("太郎が来た", "Taro came")
        stats.add("太郎を見た", "I saw Taro")

        val glossary = stats.build()
        glossary["太郎"] shouldBe "Taro"
    }

    @Test
    fun `terms below the recurrence threshold are not captured`() {
        val stats = ChapterGlossaryBuilder.Stats()
        stats.add("太郎は走る", "Taro runs")
        stats.add("太郎が来た", "Taro came")

        stats.build() shouldBe emptyMap()
    }

    @Test
    fun `CJK function words and particles are excluded even when recurring`() {
        val stats = ChapterGlossaryBuilder.Stats()
        // の is a particle; it recurs but must never become a glossary entry.
        stats.add("私の本", "My book")
        stats.add("彼の車", "His car")
        stats.add("猫の尾", "A cat's tail")

        val glossary = stats.build()
        glossary.containsKey("の") shouldBe false
        glossary shouldBe emptyMap()
    }

    @Test
    fun `a common English sentence-starter is not mistaken for a rendering`() {
        val stats = ChapterGlossaryBuilder.Stats()
        // "学校" recurs but its translations start with the sentence-starter
        // "The"; the discriminative filter must reject "The" as its rendering.
        stats.add("学校に行く", "The school is far")
        stats.add("学校が好き", "The school is nice")
        stats.add("学校を出た", "The school closed")

        stats.build() shouldBe emptyMap()
    }

    @Test
    fun `blank, source-equal, and empty translations contribute nothing`() {
        val stats = ChapterGlossaryBuilder.Stats()
        stats.add("太郎", "")
        stats.add("太郎", "   ")
        stats.add("東京", "東京") // source-equal

        stats.build() shouldBe emptyMap()
    }

    @Test
    fun `formatGlossary renders source-arrow-target lines`() {
        val text = ChapterGlossaryBuilder.formatGlossary(
            linkedMapOf("太郎" to "Taro", "東京" to "Tokyo"),
        )
        text shouldBe "太郎 => Taro\n東京 => Tokyo"
    }
}
