package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OrtException
import android.content.Context
import eu.kanade.translation.diagnostics.TelemetryTrace
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderResolution
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrSessionFactory
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.pipeline.EngineLane
import eu.kanade.translation.pipeline.execution.NativeRunQuarantine
import eu.kanade.translation.util.ShortHash
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.PaddleOcrExecutionProvider
import tachiyomi.domain.translation.PaddleOcrRecognitionBatch
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.TranslationReadingOrder

/**
 * Verifies ONNX Runtime hardware execution provider session creation auditing and
 * stale engine preference detection.
 */
class HardwareSessionAuditTest {

    private val capturedLines = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        capturedLines.clear()
        TelemetryTrace.setTestSink { capturedLines.add(it) }
        TelemetryTrace.enabled = true
    }

    @AfterEach
    fun tearDown() {
        TelemetryTrace.setTestSink(null)
    }

    @Test
    fun `session_create_attempt logs correct model, requestedProvider, and isProbe false for normal models`() {
        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK,
            canUseAccelerator = false,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { "session" },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "manga_rec_v0.2_fp16.onnx",
            isProbe = false,
        )

        val attemptEvent = capturedLines.find { it.contains("event=session_create_attempt") }
        assertNotNull(attemptEvent, "session_create_attempt must be logged")
        assertTrue(attemptEvent!!.contains("domain=hardware"))
        assertTrue(attemptEvent.contains("model=manga_rec_v0.2_fp16.onnx"))
        assertTrue(attemptEvent.contains("requestedProvider=cpu"))
        assertTrue(attemptEvent.contains("isProbe=false"))
    }

    @Test
    fun `session_create_attempt logs isProbe true for probe session`() {
        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("htp-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_HTP)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { "session" },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "probe.onnx",
            isProbe = true,
        )

        val attemptEvent = capturedLines.find { it.contains("event=session_create_attempt") }
        assertNotNull(attemptEvent)
        assertTrue(attemptEvent!!.contains("domain=hardware"))
        assertTrue(attemptEvent.contains("model=probe.onnx"))
        assertTrue(attemptEvent.contains("requestedProvider=qnn_htp"))
        assertTrue(attemptEvent.contains("isProbe=true"))
    }

    @Test
    fun `session_create_success logs actualProvider and durationMs on successful creation`() {
        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("gpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { "session" },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "manga_ocr.onnx",
            isProbe = false,
        )

        val successEvent = capturedLines.find { it.contains("event=session_create_success") }
        assertNotNull(successEvent, "session_create_success must be logged")
        assertTrue(successEvent!!.contains("domain=hardware"))
        assertTrue(successEvent.contains("model=manga_ocr.onnx"))
        assertTrue(successEvent.contains("actualProvider=qnn_gpu"))
        assertTrue(successEvent.contains("durationMs="))
    }

    @Test
    fun `session_create_failure logs errorType, sanitized errorReason, and fallbackProvider=cpu when accelerator throws`() {
        val ortError = OrtException(OrtException.OrtErrorCode.ORT_FAIL, "Dynamic shapes not supported by QNN EP\nLine 2 info")

        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("gpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                if (opts == "gpu-options") throw ortError
                "cpu-session"
            },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "manga_rec_v0.2_fp16.onnx",
            isProbe = false,
        )

        val failEvent = capturedLines.find { it.contains("event=session_create_failure") }
        assertNotNull(failEvent, "session_create_failure must be logged on accelerator exception")
        assertTrue(failEvent!!.contains("domain=hardware"))
        assertTrue(failEvent.contains("model=manga_rec_v0.2_fp16.onnx"))
        assertTrue(failEvent.contains("requestedProvider=qnn_gpu"))
        assertTrue(failEvent.contains("errorType=OrtException"))
        assertTrue(failEvent.contains("errorReason=") && failEvent.contains("Dynamic shapes not supported by QNN EP Line 2 info"))
        assertTrue(failEvent.contains("fallbackProvider=cpu"))

        // Also check that CPU retry was attempted and succeeded
        val attempts = capturedLines.filter { it.contains("event=session_create_attempt") }
        assertTrue(attempts.size >= 2, "Expected accelerator attempt and CPU retry attempt")
        assertTrue(attempts[0].contains("requestedProvider=qnn_gpu"))
        assertTrue(attempts[1].contains("requestedProvider=cpu"))

        val successes = capturedLines.filter { it.contains("event=session_create_success") }
        assertTrue(successes.size == 1, "Only CPU retry should succeed")
        assertTrue(successes[0].contains("actualProvider=cpu"))
    }

    @Test
    fun `PaddleOcrSessionFactory end-to-end session creation logs attempt, failure, and fallback`() {
        val resolution = PaddleOcrProviderResolution(
            requested = PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU,
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU,
        )

        PaddleOcrSessionFactory.createSessionWithHonestLabel<String, String>(
            modelPath = "manga_rec_v0.2_fp16.onnx",
            resolution = resolution,
            buildRequested = { canUseAccelerator ->
                OnnxRuntimeProvider.ProviderOptionsBuild("gpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                if (opts == "gpu-options") {
                    throw OrtException(OrtException.OrtErrorCode.ORT_FAIL, "QNN EP rejected dynamic shapes")
                }
                "cpu-session"
            },
            closeOptions = {},
            providerSink = {},
            recordModelFailure = {},
        )

        val failEvent = capturedLines.find { it.contains("event=session_create_failure") }
        assertNotNull(failEvent)
        assertTrue(failEvent!!.contains("model=manga_rec_v0.2_fp16.onnx"))
        assertTrue(failEvent.contains("requestedProvider=qnn_gpu"))
        assertTrue(failEvent.contains("errorType=OrtException"))
        assertTrue(failEvent.contains("fallbackProvider=cpu"))

        val successEvent = capturedLines.find { it.contains("event=session_create_success") }
        assertNotNull(successEvent)
        assertTrue(successEvent!!.contains("actualProvider=cpu"))
    }

    @Test
    fun `stale_engine_detected logs when paddle provider preference changes and rebuild is bypassed`() = runBlocking {
        val preferences = TranslationPreferences(MutableTestPreferenceStore())
        preferences.paddleOcrExecutionProvider().set(PaddleOcrExecutionProvider.CPU)
        preferences.paddleOcrRecognitionBatch().set(PaddleOcrRecognitionBatch.B1)

        val lane = createTestEngineLane(
            preferences = preferences,
            initialProvider = PaddleOcrExecutionProvider.CPU,
            initialBatch = PaddleOcrRecognitionBatch.B1,
        )

        // User modifies live preference to QUALCOMM_QNN_GPU without rebuilding the engine
        preferences.paddleOcrExecutionProvider().set(PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU)

        lane.ensureEnginesBuiltFor(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
        )

        val staleEvent = capturedLines.find { it.contains("event=stale_engine_detected") }
        assertNotNull(staleEvent, "stale_engine_detected must be logged when provider changed")
        assertTrue(staleEvent!!.contains("domain=hardware"))
        assertTrue(staleEvent.contains("currentProvider=cpu"))
        assertTrue(staleEvent.contains("userPrefProvider=qnn_gpu"))
        assertTrue(staleEvent.contains("action=ignored_until_restart"))
    }

    @Test
    fun `stale_engine_detected logs when paddle batch size preference changes and rebuild is bypassed`() = runBlocking {
        val preferences = TranslationPreferences(MutableTestPreferenceStore())
        preferences.paddleOcrExecutionProvider().set(PaddleOcrExecutionProvider.CPU)
        preferences.paddleOcrRecognitionBatch().set(PaddleOcrRecognitionBatch.B1)

        val lane = createTestEngineLane(
            preferences = preferences,
            initialProvider = PaddleOcrExecutionProvider.CPU,
            initialBatch = PaddleOcrRecognitionBatch.B1,
        )

        // User changes batch size from B1 to B4 while provider remains CPU
        preferences.paddleOcrRecognitionBatch().set(PaddleOcrRecognitionBatch.B4)

        lane.ensureEnginesBuiltFor(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
        )

        val staleEvent = capturedLines.find { it.contains("event=stale_engine_detected") }
        assertNotNull(staleEvent, "stale_engine_detected must be logged when batch size changed")
        assertTrue(staleEvent!!.contains("domain=hardware"))
        assertTrue(staleEvent.contains("currentProvider=cpu"))
        assertTrue(staleEvent.contains("userPrefProvider=cpu"))
        assertTrue(staleEvent.contains("action=ignored_until_restart"))
    }

    @Test
    fun `stale_engine_detected is not emitted when preferences match active configuration`() = runBlocking {
        val preferences = TranslationPreferences(MutableTestPreferenceStore())
        preferences.paddleOcrExecutionProvider().set(PaddleOcrExecutionProvider.CPU)
        preferences.paddleOcrRecognitionBatch().set(PaddleOcrRecognitionBatch.B1)

        val lane = createTestEngineLane(
            preferences = preferences,
            initialProvider = PaddleOcrExecutionProvider.CPU,
            initialBatch = PaddleOcrRecognitionBatch.B1,
        )

        lane.ensureEnginesBuiltFor(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
        )

        val staleEvent = capturedLines.find { it.contains("event=stale_engine_detected") }
        assertTrue(staleEvent == null, "stale_engine_detected must NOT be emitted when preferences match")
    }

    private fun createTestEngineLane(
        preferences: TranslationPreferences,
        initialProvider: PaddleOcrExecutionProvider = PaddleOcrExecutionProvider.CPU,
        initialBatch: PaddleOcrRecognitionBatch = PaddleOcrRecognitionBatch.B1,
    ): EngineLane {
        val mockContext = mockk<Context>(relaxed = true)
        val dummyRecognition = object : PageRecognitionEngine {
            override suspend fun analyze(bitmap: android.graphics.Bitmap): PageTranslation {
                throw UnsupportedOperationException("test fake")
            }
            override fun close() {}
        }
        val dummyTranslator = object : TextTranslator {
            override val fromLang = TextRecognizerLanguage.JAPANESE
            override val toLang = TextTranslatorLanguage.ENGLISH
            override suspend fun translate(pages: MutableMap<String, PageTranslation>) {}
            override fun close() {}
        }
        preferences.translationOcrModel(TextRecognizerLanguage.JAPANESE.name).set(OcrModel.PADDLEOCR_V6_SMALL)
        preferences.translationReadingOrder().set(TranslationReadingOrder.RTL_MANGA)
        preferences.translationInpaintingMode().set("FAST")

        val fromLang = TextRecognizerLanguage.JAPANESE
        val toLang = TextTranslatorLanguage.ENGLISH
        val ocrModel = OcrModelCatalog.selectedModel(preferences, fromLang)
        val readingOrder = preferences.translationReadingOrder().get()
        val inpaintingMode = InpaintingMode.FAST
        val aiEngine = preferences.translationAiEngine().get()
        val sig = EngineLane.EngineSignature(
            category = preferences.translationEngineCategory().get(),
            standardEngine = preferences.translationStandardEngine().get(),
            aiEngine = aiEngine,
            apiKeyHash = ShortHash.hash(preferences.translationAiApiKey(aiEngine).get()),
            baseUrl = preferences.translationAiBaseUrlLmStudio().get(),
            modelName = preferences.translationAiModel(aiEngine).get(),
            temperature = preferences.translationAiTemperature().get(),
            maxTokens = preferences.translationAiOutputTokens().get(),
            readingOrder = readingOrder,
            fromLang = fromLang,
            toLang = toLang,
        )
        val state = EngineLane.TestState(
            fromLang = fromLang,
            ocrModel = ocrModel,
            readingOrder = readingOrder,
            inpaintingMode = inpaintingMode,
            translator = dummyTranslator,
            recognitionEngine = dummyRecognition,
            translatorSignature = sig,
            paddleOcrProvider = initialProvider,
            paddleOcrBatch = initialBatch,
        )
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return EngineLane.createForTesting(
            context = mockContext,
            translationPreferences = preferences,
            nativeRunQuarantine = NativeRunQuarantine(scope = testScope),
            inFlightPageKeys = mutableSetOf(),
            onPageStuck = { null },
            drainGraceMs = 0L,
            drainScope = testScope,
            translatorFactory = { _, _ -> dummyTranslator },
            state = state,
        )
    }

    private class MutableTestPreferenceStore : tachiyomi.core.common.preference.PreferenceStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private inner class MutablePref<T>(
            private val key: String,
            private val defaultValue: T,
        ) : tachiyomi.core.common.preference.Preference<T> {
            override fun key(): String = key
            @Suppress("UNCHECKED_CAST")
            override fun get(): T = (map[key] as? T) ?: defaultValue
            override fun isSet(): Boolean = map.containsKey(key)
            override fun delete() { map.remove(key) }
            override fun defaultValue(): T = defaultValue
            override fun changes(): kotlinx.coroutines.flow.Flow<T> = kotlinx.coroutines.flow.flow { emit(get()) }
            override fun stateIn(scope: CoroutineScope): kotlinx.coroutines.flow.StateFlow<T> =
                kotlinx.coroutines.flow.MutableStateFlow(get())
            override fun set(value: T) {
                if (value != null) map[key] = value else map.remove(key)
            }
        }

        override fun getString(key: String, defaultValue: String): tachiyomi.core.common.preference.Preference<String> =
            MutablePref(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long): tachiyomi.core.common.preference.Preference<Long> =
            MutablePref(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int): tachiyomi.core.common.preference.Preference<Int> =
            MutablePref(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float): tachiyomi.core.common.preference.Preference<Float> =
            MutablePref(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean): tachiyomi.core.common.preference.Preference<Boolean> =
            MutablePref(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>): tachiyomi.core.common.preference.Preference<Set<String>> =
            MutablePref(key, defaultValue)
        override fun <T> getObject(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): tachiyomi.core.common.preference.Preference<T> = MutablePref(key, defaultValue)
        override fun getAll(): Map<String, *> = map
    }
}
