package eu.kanade.translation

import eu.kanade.translation.pipeline.*

import eu.kanade.translation.storage.*

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T930 Slice A2: Writer registry (N2) tests.
 *
 * Asserts:
 * 1. Process-wide writer registry records writer origins.
 * 2. Observability-only semantics while flag OFF: multiple writers can register without exclusion.
 * 3. Token close unregisters the active writer.
 * 4. ActiveChapterStoreRegistry integrates MAIN_STORE and PROBE_STORE registrations.
 */
class ActiveChapterStoreWriterRegistryTest {

    @BeforeEach
    fun setUp() {
        ActiveChapterStoreRegistry.clearGlobalWriters()
    }

    @Test
    fun `writer origins contains all 6 required origins`() {
        val expected = listOf(
            WriterOrigin.MAIN_STORE,
            WriterOrigin.PROBE_STORE,
            WriterOrigin.STATUS_RESOLVER,
            WriterOrigin.MIGRATION_SOURCE,
            WriterOrigin.HEALTH_VERIFY,
            WriterOrigin.GLOSSARY_LANE,
        )
        WriterOrigin.values().toList() shouldContainExactlyInAnyOrder expected
    }

    @Test
    fun `manual writer registration and unregistration via AutoCloseable token`() {
        val chapterId = 12345L
        val chapterKey = "Chapter 1"

        ActiveChapterStoreRegistry.hasActiveWriter(chapterId = chapterId, origin = WriterOrigin.STATUS_RESOLVER) shouldBe false

        val token = ActiveChapterStoreRegistry.registerWriter(
            chapterId = chapterId,
            chapterKey = chapterKey,
            origin = WriterOrigin.STATUS_RESOLVER,
            tag = "probe-test",
        )

        ActiveChapterStoreRegistry.hasActiveWriter(chapterId = chapterId, origin = WriterOrigin.STATUS_RESOLVER) shouldBe true
        ActiveChapterStoreRegistry.hasActiveWriter(chapterKey = chapterKey, origin = WriterOrigin.STATUS_RESOLVER) shouldBe true
        val active = ActiveChapterStoreRegistry.getActiveWriters(chapterId = chapterId)
        active shouldHaveSize 1
        active.first().origin shouldBe WriterOrigin.STATUS_RESOLVER
        active.first().tag shouldBe "probe-test"

        token.close()

        ActiveChapterStoreRegistry.hasActiveWriter(chapterId = chapterId, origin = WriterOrigin.STATUS_RESOLVER) shouldBe false
        ActiveChapterStoreRegistry.getActiveWriters(chapterId = chapterId) shouldHaveSize 0
    }

    @Test
    fun `observability-only allows concurrent writers without exclusion`() {
        val chapterId = 999L
        val chapterKey = "Chapter 999"

        val token1 = ActiveChapterStoreRegistry.registerWriter(
            chapterId = chapterId,
            chapterKey = chapterKey,
            origin = WriterOrigin.MAIN_STORE,
        )
        val token2 = ActiveChapterStoreRegistry.registerWriter(
            chapterId = chapterId,
            chapterKey = chapterKey,
            origin = WriterOrigin.HEALTH_VERIFY,
        )
        val token3 = ActiveChapterStoreRegistry.registerWriter(
            chapterId = chapterId,
            chapterKey = chapterKey,
            origin = WriterOrigin.GLOSSARY_LANE,
        )

        val writers = ActiveChapterStoreRegistry.getActiveWriters(chapterId = chapterId)
        writers shouldHaveSize 3
        writers.map { it.origin } shouldContainExactlyInAnyOrder listOf(
            WriterOrigin.MAIN_STORE,
            WriterOrigin.HEALTH_VERIFY,
            WriterOrigin.GLOSSARY_LANE,
        )

        token2.close()
        ActiveChapterStoreRegistry.getActiveWriters(chapterId = chapterId) shouldHaveSize 2
        ActiveChapterStoreRegistry.hasActiveWriter(chapterId = chapterId, origin = WriterOrigin.HEALTH_VERIFY) shouldBe false
        ActiveChapterStoreRegistry.hasActiveWriter(chapterId = chapterId, origin = WriterOrigin.MAIN_STORE) shouldBe true

        token1.close()
        token3.close()
        ActiveChapterStoreRegistry.getActiveWriters(chapterId = chapterId) shouldHaveSize 0
    }

    @Test
    fun `ActiveChapterStoreRegistry registers and unregisters MAIN_STORE and PROBE_STORE`() = runBlocking<Unit> {
        val registry = ActiveChapterStoreRegistry()
        val chapterId = 42L
        val store = ChapterTranslationStore(translationFile = null, fileCreator = null)

        registry.register(chapterId, store) shouldBe true
        ActiveChapterStoreRegistry.hasActiveWriter(chapterId = chapterId, origin = WriterOrigin.MAIN_STORE) shouldBe true

        val probeKey = "probe:chapter42"
        val probeResult = registry.getOrCreateProbe(probeKey) {
            ChapterTranslationStore(translationFile = null, fileCreator = null)
        }
        probeResult shouldBe probeResult // non-null
        ActiveChapterStoreRegistry.hasActiveWriter(chapterKey = probeKey, origin = WriterOrigin.PROBE_STORE) shouldBe true

        registry.releaseProbe(probeKey, probeResult!!.store) shouldBe true
        ActiveChapterStoreRegistry.hasActiveWriter(chapterKey = probeKey, origin = WriterOrigin.PROBE_STORE) shouldBe false

        registry.remove(chapterId) shouldBe store
        ActiveChapterStoreRegistry.hasActiveWriter(chapterId = chapterId, origin = WriterOrigin.MAIN_STORE) shouldBe false
    }
}
