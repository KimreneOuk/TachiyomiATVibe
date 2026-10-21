package eu.kanade.translation.processing

import android.content.Context
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.processing.execution.NativeRunQuarantine
import eu.kanade.translation.util.ShortHash
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.TranslationPreferences

/**
 * Guards the vision-GPU rebuild gate against the double-init defect: the lane
 * builds its recognition engine honoring the live
 * translation_vision_gpu_acceleration preference, and the stale shadow flag
 * must agree with that build on the first admission instead of forcing a
 * full teardown+rebuild of freshly created ONNX sessions.
 */
class EngineLaneVisionGpuTest {

    @Test
    fun `engine built with vision gpu does not rebuild on the first admission`() = runTest {
        val fixture = Fixture(backgroundScope, visionGpu = true)
        fixture.visionPref.set(true)
        fixture.admit()
        verify(exactly = 0) { fixture.recognition.close() }
        assertEquals(true, fixture.lane.currentVisionGpu)
    }

    @Test
    fun `engine built without vision gpu does not rebuild on the first admission`() = runTest {
        val fixture = Fixture(backgroundScope, visionGpu = false)
        fixture.admit()
        verify(exactly = 0) { fixture.recognition.close() }
        assertEquals(false, fixture.lane.currentVisionGpu)
    }

    @Test
    fun `vision gpu preference change after construction rebuilds recognition once`() = runTest {
        val fixture = Fixture(backgroundScope, visionGpu = false)
        fixture.admit()
        verify(exactly = 0) { fixture.recognition.close() }

        fixture.visionPref.set(true)
        fixture.admit()
        assertEquals(true, fixture.lane.currentVisionGpu)
        verify(exactly = 1) { fixture.recognition.close() }

        fixture.visionPref.set(false)
        fixture.admit()
        assertEquals(false, fixture.lane.currentVisionGpu)
        verify(exactly = 2) { fixture.recognition.close() }
    }

    private class Fixture(scope: CoroutineScope, private val visionGpu: Boolean) {
        val visionPref = InMemoryPreferenceStore.InMemoryPreference(
            "translation_vision_gpu_acceleration",
            false,
            false,
        )
        private val preferences = spyk(TranslationPreferences(InMemoryPreferenceStore())).also {
            every { it.translationVisionGpuAcceleration() } returns visionPref
        }
        val recognition = mockk<PageRecognitionEngine>(relaxed = true)
        private val translator = mockk<TextTranslator>(relaxed = true)
        val lane = EngineLane.createForTesting(
            context = mockk<Context>(relaxed = true),
            translationPreferences = preferences,
            nativeRunQuarantine = NativeRunQuarantine(scope),
            inFlightPageKeys = mutableSetOf(),
            onPageStuck = { null },
            drainGraceMs = 0L,
            drainScope = scope,
            translatorFactory = { _, _ -> translator },
            state = EngineLane.TestState(
                fromLang = TextRecognizerLanguage.JAPANESE,
                ocrModel = OcrModel.MLKIT,
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
                visionGpu = visionGpu,
            ),
        )

        suspend fun admit() {
            lane.ensureEnginesBuiltFor(
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
                runScopedOcrModel = OcrModel.MLKIT,
            )
        }
    }
}
