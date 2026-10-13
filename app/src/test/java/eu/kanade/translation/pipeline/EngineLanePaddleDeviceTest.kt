package eu.kanade.translation.pipeline

import android.content.Context
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.pipeline.execution.NativeRunQuarantine
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
import tachiyomi.domain.translation.PaddleOcrComputeDevice
import tachiyomi.domain.translation.TranslationPreferences

class EngineLanePaddleDeviceTest {

    @Test
    fun `saved device changes rebuild once on the next OCR admission`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.admit()
        verify(exactly = 0) { fixture.recognition.close() }

        fixture.device.set(PaddleOcrComputeDevice.CPU)
        fixture.admit()
        fixture.admit()
        assertEquals(PaddleOcrComputeDevice.CPU, fixture.lane.currentPaddleOcrComputeDevice)
        verify(exactly = 1) { fixture.recognition.close() }

        fixture.device.set(PaddleOcrComputeDevice.GPU)
        fixture.admit()
        assertEquals(PaddleOcrComputeDevice.GPU, fixture.lane.currentPaddleOcrComputeDevice)
        verify(exactly = 2) { fixture.recognition.close() }
    }

    @Test
    fun `reader override does not persist or affect chapter batches and clearing restores default`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.lane.setReaderPaddleOcrComputeDeviceOverride(PaddleOcrComputeDevice.CPU)
        verify(exactly = 0) { fixture.recognition.close() }
        fixture.admit(reader = true)
        assertEquals(PaddleOcrComputeDevice.CPU, fixture.lane.currentPaddleOcrComputeDevice)
        assertEquals(PaddleOcrComputeDevice.GPU, fixture.device.get())

        fixture.admit(reader = false)
        assertEquals(PaddleOcrComputeDevice.GPU, fixture.lane.currentPaddleOcrComputeDevice)
        fixture.admit(reader = true)
        assertEquals(PaddleOcrComputeDevice.CPU, fixture.lane.currentPaddleOcrComputeDevice)

        fixture.lane.setReaderPaddleOcrComputeDeviceOverride(null)
        fixture.admit(reader = true)
        assertEquals(PaddleOcrComputeDevice.GPU, fixture.lane.currentPaddleOcrComputeDevice)
        verify(exactly = 4) { fixture.recognition.close() }
    }

    @Test
    fun `Paddle device override leaves other OCR engines alone`() = runTest {
        val fixture = Fixture(backgroundScope, OcrModel.MLKIT)
        fixture.lane.setReaderPaddleOcrComputeDeviceOverride(PaddleOcrComputeDevice.CPU)
        fixture.admit(reader = true)
        verify(exactly = 0) { fixture.recognition.close() }
    }

    private class Fixture(scope: CoroutineScope, private val model: OcrModel = OcrModel.PADDLEOCR_V6_SMALL) {
        val device = InMemoryPreferenceStore.InMemoryPreference(
            "translation_paddle_ocr_compute_device",
            PaddleOcrComputeDevice.GPU,
            PaddleOcrComputeDevice.GPU,
        )
        private val preferences = spyk(TranslationPreferences(InMemoryPreferenceStore())).also {
            every { it.paddleOcrComputeDevice() } returns device
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
                ocrModel = model,
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

        suspend fun admit(reader: Boolean = false) {
            lane.ensureEnginesBuiltFor(
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
                runScopedOcrModel = model,
                useReaderPaddleOverride = reader,
            )
        }
    }
}
