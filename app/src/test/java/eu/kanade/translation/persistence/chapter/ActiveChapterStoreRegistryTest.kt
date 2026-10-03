package eu.kanade.translation.persistence.chapter

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
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
                    ChapterTranslationStore()
                }
            },
            async {
                registry.getOrCreate(101) {
                    createCount++
                    ChapterTranslationStore()
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
                    ChapterTranslationStore()
                }
            },
            async {
                registry.getOrCreateFile(fileKey) {
                    createCount++
                    ChapterTranslationStore()
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
        val first = ChapterTranslationStore()
        val competing = ChapterTranslationStore()

        registry.register(101, first) shouldBe true
        registry.register(101, competing) shouldBe false
        registry.get(101) shouldBe first
    }

    @Test
    fun `chapter selection remains isolated across alternating active stores and clears after removal`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val chapterA = ChapterTranslationStore().apply { preRegisterPages(listOf("a-1.jpg")) }
        val chapterB = ChapterTranslationStore().apply { preRegisterPages(listOf("b-1.jpg")) }
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

    @Test
    fun `durable probe is evicted unless an active chapter adopts it`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val fileKey = "probe-file"
        val probe = registry.getOrCreateProbe(fileKey) { ChapterTranslationStore() }!!

        registry.releaseProbe(fileKey, probe.store) shouldBe true
        registry.getByFile(fileKey) shouldBe null
        // Per-creation, not per-store: after release the next probe pass is a
        // real creation again and must re-arm the invalidating wipe (F5.2).
        val recreated = registry.getOrCreateProbe(fileKey) { ChapterTranslationStore() }!!
        recreated.store shouldNotBe probe.store
        recreated.created shouldBe true

        val adopted = registry.getOrCreateProbe("adopted-file") { ChapterTranslationStore() }!!
        registry.getOrCreate(909, "adopted-file") { error("probe should be promoted") } shouldBe adopted.store
        registry.releaseProbe("adopted-file", adopted.store) shouldBe false
        registry.get(909) shouldBe adopted.store
    }

    @Test
    fun `probe created flag is true only for a freshly created probe`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val fileKey = "created-flag"

        val first = registry.getOrCreateProbe(fileKey) { ChapterTranslationStore() }!!
        first.owned shouldBe true
        first.created shouldBe true

        // Still-registered probe handed out again: owned, but created only
        // once — the invalidating wipe belongs to the creation pass alone.
        val reused = registry.getOrCreateProbe(fileKey) { error("must not create") }!!
        (reused.store === first.store) shouldBe true
        reused.owned shouldBe true
        reused.created shouldBe false

        // After promotion to an active file store the probe path hands out
        // the live store unowned and uncreated.
        registry.getOrCreateFile(fileKey) { error("should adopt the probe") } shouldBe first.store
        val active = registry.getOrCreateProbe(fileKey) { error("must not create") }!!
        (active.store === first.store) shouldBe true
        active.owned shouldBe false
        active.created shouldBe false
    }

    @Test
    fun `file-keyed open adopts durable probe before it can be evicted`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val fileKey = "file-probe"
        val probe = registry.getOrCreateProbe(fileKey) { ChapterTranslationStore() }!!

        registry.getOrCreateFile(fileKey) { error("file open should adopt the probe") } shouldBe probe.store
        registry.releaseProbe(fileKey, probe.store) shouldBe false
        registry.getByFile(fileKey) shouldBe probe.store
    }

    @Test
    fun `concurrent file open adopts a probe created under the shared opening lock`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val fileKey = "concurrent-file-probe"
        val creationStarted = CompletableDeferred<Unit>()
        val created = CompletableDeferred<ChapterTranslationStore>()

        val probeDeferred = async {
            registry.getOrCreateProbe(fileKey) {
                creationStarted.complete(Unit)
                created.await()
            }!!
        }
        creationStarted.await()
        val fileOpen = async {
            registry.getOrCreateFile(fileKey) {
                error("file open must adopt the probe")
            }
        }
        val probeStore = ChapterTranslationStore()
        created.complete(probeStore)

        val probe = probeDeferred.await()
        fileOpen.await() shouldBe probe.store
        probe.store shouldBe probeStore
        registry.releaseProbe(fileKey, probe.store) shouldBe false
        registry.getByFile(fileKey) shouldBe probeStore
    }
}
