package eu.kanade.translation.processing

import android.content.Context
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.processing.execution.NativeRunQuarantine
import eu.kanade.translation.util.ShortHash
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.TranslationPreferences

/**
 * S3 whole-chain adapter proofs at the [EngineLane.withNativeLane] choke
 * point: a throwing timeout marker, a throwing onPageStuck getter, and a
 * throwing listener invoke must each surface [NativeRunQuarantine.Outcome]
 * TimedOut (null to the caller) WITHOUT releasing native admission before the
 * timed-out block's ACTUAL exit, and a caller cancelled while the native block
 * is held must not release admission early either. Deterministic latches only
 * — no sleeps, no polling.
 *
 * Cleanup contract: ONE try spans the ENTIRE gate-holding lifetime — from
 * before the first child launch through every scheduler advance and
 * assertion. The finally opens any still-closed gate FIRST (so the parked
 * block can exit) and only then cancels any still-active child (cancellation
 * is safe at any point: the block child completes its own [NativeRunQuarantine]
 * exit signal in a finally, and the quarantine's real-exit wait is
 * NonCancellable). No join in the finally: a join would suspend on the virtual
 * scheduler mid-cleanup; runTest's own teardown cancels and awaits the
 * cancelled children.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineLaneTimeoutAdapterTest {

    private class Fixture(
        scope: CoroutineScope,
        private val onPageStuckProvider: () -> ((chapterId: Long?, pageKey: String) -> Unit)? = { null },
    ) {
        private val preferences = spyk(TranslationPreferences(InMemoryPreferenceStore()))
        private val recognition = mockk<PageRecognitionEngine>(relaxed = true)
        private val translator = mockk<TextTranslator>(relaxed = true)
        val lane = EngineLane.createForTesting(
            context = mockk<Context>(relaxed = true),
            translationPreferences = preferences,
            nativeRunQuarantine = NativeRunQuarantine(scope),
            inFlightPageKeys = mutableSetOf(),
            onPageStuck = { onPageStuckProvider() },
            drainGraceMs = 0L,
            drainScope = scope,
            translatorFactory = { _, _ -> translator },
            state = EngineLane.TestState(
                fromLang = TextRecognizerLanguage.JAPANESE,
                ocrModel = tachiyomi.domain.translation.OcrModel.MLKIT,
                readingOrder = preferences.translationReadingOrder().get(),
                inpaintingMode = InpaintingMode.fromPref(preferences.translationInpaintingMode().get()),
                translator = translator,
                recognitionEngine = recognition,
                translatorSignature = EngineLane.EngineSignature(
                    category = preferences.translationEngineCategory().get(),
                    standardEngine = preferences.translationStandardEngine().get(),
                    aiEngine = preferences.translationAiEngine().get(),
                    apiKeyHash = ShortHash.hash(preferences.translationAiApiKey(preferences.translationAiEngine().get()).get()),
                    baseUrl = preferences.translationAiBaseUrlLmStudio().get(),
                    modelName = preferences.translationAiModel(preferences.translationAiEngine().get()).get(),
                    temperature = preferences.translationAiTemperature().get(),
                    maxTokens = preferences.translationAiOutputTokens().get(),
                    readingOrder = preferences.translationReadingOrder().get(),
                    fromLang = TextRecognizerLanguage.JAPANESE,
                    toLang = TextTranslatorLanguage.ENGLISH,
                ),
            ),
        )
    }

    @Test
    fun `throwing marker still times out, still notifies, and holds admission until actual block exit`() = runTest {
        val listenerSeen = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, onPageStuckProvider = { { _, _ -> listenerSeen.complete(Unit) } })
        val firstMayExit = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var first: Deferred<String?>? = null
        var second: Deferred<String?>? = null
        try {
            first = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 100L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p1",
                    onTimeout = { throw IllegalStateException("marker boom") },
                    block = {
                        firstMayExit.await()
                        "late"
                    },
                )
            }
            advanceTimeBy(101L)
            testScheduler.runCurrent()

            // The chain continued past the throwing marker to the stuck listener.
            listenerSeen.isCompleted shouldBe true

            // Admission is still held by the alive timed-out block.
            second = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 100L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p2",
                    onTimeout = {},
                    block = {
                        secondEntered.complete(Unit)
                        "second"
                    },
                )
            }
            testScheduler.runCurrent()
            secondEntered.isCompleted shouldBe false

            firstMayExit.complete(Unit)
            testScheduler.runCurrent()
            // TimedOut surfaced as null — the marker's failure never escaped.
            first.await() shouldBe null
            secondEntered.isCompleted shouldBe true
            second.await() shouldBe "second"
        } finally {
            if (!firstMayExit.isCompleted) firstMayExit.complete(Unit)
            first?.let { if (it.isActive) it.cancel() }
            second?.let { if (it.isActive) it.cancel() }
        }
    }

    @Test
    fun `throwing onPageStuck getter still times out and holds admission until actual block exit`() = runTest {
        val fixture = Fixture(
            backgroundScope,
            onPageStuckProvider = { throw IllegalStateException("getter boom") },
        )
        val firstMayExit = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val timeoutSeen = CompletableDeferred<Unit>()
        var first: Deferred<String?>? = null
        var second: Deferred<String?>? = null
        try {
            first = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 100L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p1",
                    onTimeout = { timeoutSeen.complete(Unit) },
                    block = {
                        firstMayExit.await()
                        "late"
                    },
                )
            }
            advanceTimeBy(101L)
            testScheduler.runCurrent()
            timeoutSeen.isCompleted shouldBe true

            second = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 100L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p2",
                    onTimeout = {},
                    block = {
                        secondEntered.complete(Unit)
                        "second"
                    },
                )
            }
            testScheduler.runCurrent()
            secondEntered.isCompleted shouldBe false

            firstMayExit.complete(Unit)
            testScheduler.runCurrent()
            first.await() shouldBe null
            secondEntered.isCompleted shouldBe true
            second.await() shouldBe "second"
        } finally {
            if (!firstMayExit.isCompleted) firstMayExit.complete(Unit)
            first?.let { if (it.isActive) it.cancel() }
            second?.let { if (it.isActive) it.cancel() }
        }
    }

    @Test
    fun `throwing onPageStuck invoke still times out and holds admission until actual block exit`() = runTest {
        val fixture = Fixture(
            backgroundScope,
            onPageStuckProvider = { { _, _ -> throw IllegalStateException("listener boom") } },
        )
        val firstMayExit = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var first: Deferred<String?>? = null
        var second: Deferred<String?>? = null
        try {
            first = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 100L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p1",
                    onTimeout = {},
                    block = {
                        firstMayExit.await()
                        "late"
                    },
                )
            }
            advanceTimeBy(101L)
            testScheduler.runCurrent()

            // The lane is still occupied by the alive timed-out block (the permit
            // holder snapshot is the entry still in flight).
            fixture.lane.permitHolderPageKeySnapshot() shouldBe "p1"

            second = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 100L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p2",
                    onTimeout = {},
                    block = {
                        secondEntered.complete(Unit)
                        "second"
                    },
                )
            }
            testScheduler.runCurrent()
            secondEntered.isCompleted shouldBe false

            firstMayExit.complete(Unit)
            testScheduler.runCurrent()
            first.await() shouldBe null
            secondEntered.isCompleted shouldBe true
            second.await() shouldBe "second"
        } finally {
            if (!firstMayExit.isCompleted) firstMayExit.complete(Unit)
            first?.let { if (it.isActive) it.cancel() }
            second?.let { if (it.isActive) it.cancel() }
        }
    }

    @Test
    fun `caller cancelled while native block held waits for actual exit before releasing admission`() = runTest {
        val fixture = Fixture(backgroundScope)
        val firstMayExit = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var caller: Deferred<String?>? = null
        var second: Deferred<String?>? = null
        try {
            caller = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 10_000L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p1",
                    onTimeout = {},
                    block = {
                        firstMayExit.await()
                        "late"
                    },
                )
            }
            testScheduler.runCurrent()

            caller.cancel()
            testScheduler.runCurrent()

            // The cancelled caller is now parked in the quarantine's NonCancellable
            // real-exit wait; admission must still be held.
            second = async {
                fixture.lane.withNativeLane(
                    timeoutMs = 100L,
                    chapterId = 1L,
                    chapterName = "chapter",
                    pageKey = "p2",
                    onTimeout = {},
                    block = {
                        secondEntered.complete(Unit)
                        "second"
                    },
                )
            }
            testScheduler.runCurrent()
            secondEntered.isCompleted shouldBe false

            firstMayExit.complete(Unit)
            testScheduler.runCurrent()
            caller.join()
            secondEntered.isCompleted shouldBe true
            second.await() shouldBe "second"
        } finally {
            if (!firstMayExit.isCompleted) firstMayExit.complete(Unit)
            caller?.let { if (it.isActive) it.cancel() }
            second?.let { if (it.isActive) it.cancel() }
        }
    }
}
