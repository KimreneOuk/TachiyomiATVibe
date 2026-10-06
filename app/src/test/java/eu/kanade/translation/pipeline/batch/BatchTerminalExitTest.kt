package eu.kanade.translation.pipeline.batch
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchHeroPhase
import eu.kanade.translation.model.BatchHeroProjection
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every exceptional exit of the batch pipeline
 * terminates the tracker with a typed terminal snapshot — no live nonterminal
 * `0/0` tracker may survive. The zero-page failure stays DISTINCT (empty work
 * set + aborted reason), never a generic failure or a numeric 0/0.
 */
class BatchTerminalExitTest {

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { title } returns "fixture"
    }
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns 7L
        every { name } returns "Chapter 1"
    }
    private val source = mockk<HttpSource>(relaxed = true)

    private fun prefs() = mockk<TranslationPreferences>(relaxed = true) {
        every { translateFromLanguage() } returns mockk {
            every { get() } returns "ENGLISH"
        }
        every { translateToLanguage() } returns mockk {
            every { get() } returns "ENGLISH"
        }
    }

    /** Translator whose heavy collaborators are never expected on these exits. */
    private fun translator(nativeLane: NativeLaneRunner): BatchChapterTranslator =
        BatchChapterTranslator(
            provider = mockk(relaxed = true),
            translationPreferences = prefs(),
            nativeLane = nativeLane,
            engineRebuildMutex = Mutex(),
            ensureEnginesBuiltFor = { _, _ -> },
            recognitionEngineFn = { error("recognition engine not expected on this exit") },
            textTranslatorFn = { error("text translator not expected on this exit") },
            computeSourceFingerprintFn = { error("fingerprint not expected on this exit") },
            batchExpectedFingerprintsFn = { _, _ -> error("fingerprints not expected on this exit") },
            inpaintingModeFromPref = { error("inpainting mode not expected on this exit") },
            releaseBatchPageLease = { _, _ -> error("lease release not expected on this exit") },
            persistPageWithOomRecovery = { _, _, _, _ -> error("persist not expected on this exit") },
            loadPersistedCleanedBitmap = { _, _, _, _ -> error("bitmap load not expected on this exit") },
            deleteRetiredCleanedFile = { _, _, _, _, _ -> error("retired delete not expected on this exit") },
            markPageTimedOut = { _, _, _, _ -> error("page timeout not expected on this exit") },
            analyzePage = { _, _, _, _, _ -> error("analyze not expected on this exit") },
            decodePageBitmapForTranslation = { _, _ -> error("decode not expected on this exit") },
            preflightInpaintGate = { _, _ -> error("inpaint gate not expected on this exit") },
            inpaintPage = { _, _, _, _, _ -> error("inpaint not expected on this exit") },
            retryInpaintDownscaled = { _, _, _, _, _, _, _ -> error("retry not expected on this exit") },
            persistCleanedBitmap = { _, _, _, _, _, _, _, _, _, _ ->
                error("persist cleaned not expected on this exit")
            },
            updatePageFromCurrentSnapshotFn = { _, _, _, _ -> error("patch not expected on this exit") },
            onBatchClosedFn = { null },
        )

    private fun TestScope.registryTracker(
        store: ChapterTranslationStore,
        orderedKeys: List<String>,
    ): Pair<TranslationBatchTrackerRegistry, TranslationBatchProgressTracker> {
        val registry = TranslationBatchTrackerRegistry()
        val tracker = registry.createTracker(
            chapterId = chapter.id,
            store = store,
            orderedPageKeys = orderedKeys,
            scope = this,
        )
        return registry to tracker
    }

    @Test
    fun `recorded source digests satisfy run identity without whole chapter fingerprint reads`() = runTest {
        val pageKeys = listOf("001.jpg", "002.jpg")
        val recorded = pageKeys.associateWith { key ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("source-$key".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        val store = ChapterTranslationStore(
            initialArtifactManifest = ChapterArtifactManifest(sourceShaByPageKey = recorded),
        )
        val preferenceStore = prefs().also { preferences ->
            every { preferences.translationAiOutputTokens() } returns mockk {
                every { get() } returns "8192"
            }
            every { preferences.translationEngineCategory() } returns mockk {
                every { get() } returns tachiyomi.domain.translation.TranslationEngineCategory.STANDARD
            }
            every { preferences.translationStandardEngine() } returns mockk {
                every { get() } returns tachiyomi.domain.translation.StandardEngine.values().first()
            }
        }
        val fingerprintCalls = AtomicInteger()
        val batch = BatchChapterTranslator(
            provider = mockk(relaxed = true),
            translationPreferences = preferenceStore,
            nativeLane = object : NativeLaneRunner {
                private var setupCall = true

                override suspend fun <T> run(
                    timeoutMs: Long,
                    chapterId: Long?,
                    chapterName: String,
                    pageKey: String,
                    onTimeout: suspend () -> Unit,
                    block: suspend () -> T,
                ): T? = if (setupCall) {
                    setupCall = false
                    block()
                } else {
                    null
                }
            },
            engineRebuildMutex = Mutex(),
            ensureEnginesBuiltFor = { _, _ -> },
            recognitionEngineFn = { mockk(relaxed = true) },
            textTranslatorFn = { mockk(relaxed = true) },
            computeSourceFingerprintFn = {
                fingerprintCalls.incrementAndGet()
                recorded.getValue(pageKeys.first())
            },
            batchExpectedFingerprintsFn = { _, _ -> BatchExpectedFingerprints() },
            inpaintingModeFromPref = { InpaintingMode.FAST },
            releaseBatchPageLease = { _, _ -> },
            persistPageWithOomRecovery = { _, _, _, _ -> error("page persistence not expected") },
            loadPersistedCleanedBitmap = { _, _, _, _ -> error("bitmap load not expected") },
            deleteRetiredCleanedFile = { _, _, _, _, _ -> error("retired delete not expected") },
            markPageTimedOut = { _, _, _, _ -> error("page timeout not expected") },
            analyzePage = { _, _, _, _, _ -> error("analysis not expected") },
            decodePageBitmapForTranslation = { _, _ -> error("page decode not expected") },
            preflightInpaintGate = { _, _ -> error("inpaint gate not expected") },
            inpaintPage = { _, _, _, _, _ -> error("inpaint not expected") },
            retryInpaintDownscaled = { _, _, _, _, _, _, _ -> error("inpaint retry not expected") },
            persistCleanedBitmap = { _, _, _, _, _, _, _, _, _, _ -> error("cleaned persistence not expected") },
            updatePageFromCurrentSnapshotFn = { _, _, _, _ -> error("page patch not expected") },
            onBatchClosedFn = { null },
        )

        batch.translateBatch(
            manga = manga,
            chapter = chapter,
            source = source,
            store = store,
            orderedStreams = pageKeys.map { key -> key to { ByteArrayInputStream("source-$key".toByteArray()) } },
        )

        // With no in-memory page state, the planner has no reuse probe to make.
        // The durable digests are enough to form run identity; coordinator setup
        // must not open every page source before the first page is admitted.
        fingerprintCalls.get() shouldBe 0
    }

    @Test
    fun `zero-page chapter aborts its tracker with a distinct typed reason`() = runTest {
        val store = ChapterTranslationStore()
        val (registry, tracker) = registryTracker(store, orderedKeys = emptyList())
        val batch = translator(
            nativeLane = object : NativeLaneRunner {
                override suspend fun <T> run(
                    timeoutMs: Long,
                    chapterId: Long?,
                    chapterName: String,
                    pageKey: String,
                    onTimeout: suspend () -> Unit,
                    block: suspend () -> T,
                ): T? = error("native lane not expected for a zero-page chapter")
            },
        )

        val result = batch.translateBatch(
            manga = manga,
            chapter = chapter,
            source = source,
            store = store,
            orderedStreams = emptyList(),
            tracker = tracker,
        )

        result shouldBe null
        runCurrent()
        // No live nonterminal tracker survives; the terminal snapshot is cached.
        registry.live.value shouldBe emptyMap()
        val terminal = registry.terminal.value.getValue(chapter.id)
        terminal.state shouldBe Translation.State.ERROR
        terminal.aborted shouldBe true
        terminal.abortedReason shouldBe "Chapter has no readable pages to translate"
        // Distinct zero-page failure: empty work set — the hero renders
        // FAILED_NO_PAGES for exactly this shape, never a numeric 0/0.
        terminal.totalPages shouldBe 0
        BatchHeroProjection.of(terminal) shouldBe BatchHeroProjection.Phase(
            phase = BatchHeroPhase.FAILED_NO_PAGES,
            isError = true,
        )
    }

    @Test
    fun `engine-setup failure aborts the tracker keeping the known totals and reason`() = runTest {
        val store = ChapterTranslationStore()
        val (registry, tracker) = registryTracker(store, orderedKeys = listOf("001.jpg", "002.jpg"))
        val batch = translator(
            nativeLane = object : NativeLaneRunner {
                override suspend fun <T> run(
                    timeoutMs: Long,
                    chapterId: Long?,
                    chapterName: String,
                    pageKey: String,
                    onTimeout: suspend () -> Unit,
                    block: suspend () -> T,
                ): T? = null
            },
        )

        val result = batch.translateBatch(
            manga = manga,
            chapter = chapter,
            source = source,
            store = store,
            orderedStreams = listOf(
                "001.jpg" to { error("no stream expected before engine setup fails") },
                "002.jpg" to { error("no stream expected before engine setup fails") },
            ),
            tracker = tracker,
        )

        result shouldBe null
        runCurrent()
        registry.live.value shouldBe emptyMap()
        val terminal = registry.terminal.value.getValue(chapter.id)
        terminal.state shouldBe Translation.State.ERROR
        terminal.aborted shouldBe true
        terminal.abortedReason shouldBe "Translation could not start: batch engine setup failed or timed out"
        // Known ordered work keys still produce the real total (never 0/0).
        terminal.totalPages shouldBe 2
    }

    @Test
    fun `remaining abort keys exclude durably terminal pages`() {
        val store = ChapterTranslationStore(
            initialPages = mapOf(
                // Durably terminal: a textless page (OCR ready, nothing to
                // translate, skipped inpaint/render).
                "001.jpg" to PageTranslation(
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.SKIPPED,
                    inpaintStatus = StageStatus.SKIPPED,
                    renderStatus = StageStatus.SKIPPED,
                ),
                // Durably failed.
                "002.jpg" to PageTranslation(ocrStatus = StageStatus.FAILED),
                // Still pending / running.
                "003.jpg" to PageTranslation(),
                "004.jpg" to PageTranslation(ocrStatus = StageStatus.RUNNING),
            ),
        )
        val ordered: List<Pair<String, () -> InputStream>> = listOf(
            "001.jpg",
            "002.jpg",
            "003.jpg",
            "004.jpg",
            "005.jpg",
        ).map { key -> key to { error("no stream expected in a pure abort-key computation") } }

        val remaining = BatchChapterTranslator.remainingAbortKeys(ordered, store)

        remaining shouldBe setOf("003.jpg", "004.jpg", "005.jpg")
    }
}
