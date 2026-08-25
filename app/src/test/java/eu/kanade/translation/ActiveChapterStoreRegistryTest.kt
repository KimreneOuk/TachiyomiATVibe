package eu.kanade.translation

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ActiveChapterStoreRegistryTest {
    @Test
    fun `concurrent reader and batch open share one chapter store`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        var createCount = 0

        val stores = listOf(
            async {
                registry.getOrCreate(101) {
                    createCount++
                    delay(10)
                    ChapterTranslationStore(null, null)
                }
            },
            async {
                registry.getOrCreate(101) {
                    createCount++
                    ChapterTranslationStore(null, null)
                }
            },
        ).awaitAll()

        createCount shouldBe 1
        (stores[0] === stores[1]) shouldBe true
        stores[0] shouldBe registry.get(101)
    }

    @Test
    fun `chapter and file keyed opens share one store`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val fileKey = "chapter-file"
        var createCount = 0

        val stores = listOf(
            async {
                registry.getOrCreate(101, fileKey) {
                    createCount++
                    delay(10)
                    ChapterTranslationStore(null, null)
                }
            },
            async {
                registry.getOrCreateFile(fileKey) {
                    createCount++
                    ChapterTranslationStore(null, null)
                }
            },
        ).awaitAll()

        createCount shouldBe 1
        (stores[0] === stores[1]) shouldBe true
        stores[0] shouldBe registry.get(101)
        stores[0] shouldBe registry.getByFile(fileKey)
    }

    @Test
    fun `register never replaces an existing chapter authority`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val first = ChapterTranslationStore(null, null)
        val competing = ChapterTranslationStore(null, null)

        registry.register(101, first) shouldBe true
        registry.register(101, competing) shouldBe false
        registry.get(101) shouldBe first
    }

    @Test
    fun `chapter selection remains isolated across alternating active stores and clears after removal`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val chapterA = ChapterTranslationStore(null, null).apply { preRegisterPages(listOf("a-1.jpg")) }
        val chapterB = ChapterTranslationStore(null, null).apply { preRegisterPages(listOf("b-1.jpg")) }
        val selectedA = mutableListOf<Set<String>>()
        val selectedB = mutableListOf<Set<String>>()

        val collectorA = launch { registry.select(101).onEach { selectedA += it.keys }.collect {} }
        val collectorB = launch { registry.select(202).onEach { selectedB += it.keys }.collect {} }
        runCurrent()

        registry.register(101, chapterA)
        runCurrent()
        selectedA.last() shouldContainExactly setOf("a-1.jpg")
        selectedB.last() shouldBe emptySet()

        registry.register(202, chapterB)
        runCurrent()
        selectedA.last() shouldContainExactly setOf("a-1.jpg")
        selectedB.last() shouldContainExactly setOf("b-1.jpg")

        chapterA.preRegisterPages(listOf("a-2.jpg"))
        runCurrent()
        selectedA.last() shouldContainExactly setOf("a-1.jpg", "a-2.jpg")
        selectedB.last() shouldContainExactly setOf("b-1.jpg")

        chapterB.preRegisterPages(listOf("b-2.jpg"))
        runCurrent()
        selectedA.last() shouldContainExactly setOf("a-1.jpg", "a-2.jpg")
        selectedB.last() shouldContainExactly setOf("b-1.jpg", "b-2.jpg")

        registry.remove(101)
        runCurrent()
        selectedA.last() shouldBe emptySet()

        // A late update from a removed source must not resurrect stale reader data.
        chapterA.preRegisterPages(listOf("a-stale.jpg"))
        runCurrent()
        selectedA.last() shouldBe emptySet()
        selectedB.last() shouldContainExactly setOf("b-1.jpg", "b-2.jpg")

        collectorA.cancel()
        collectorB.cancel()
    }
}
