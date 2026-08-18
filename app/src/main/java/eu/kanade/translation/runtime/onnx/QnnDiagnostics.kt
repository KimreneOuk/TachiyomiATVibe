package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.FloatBuffer

/**
 * TachiyomiAT: debug-only QNN/HTP diagnostics. Runs once per process when the
 * hardware route first resolves and dumps everything needed to answer "why is
 * the NPU not engaging" from a single logcat capture (tag prefix below):
 * device/SoC facts, the artifact's compiled-in ORT providers, the packaged QNN
 * native libraries, a strict-probe matrix over soc_model/htp_arch option
 * combos, and — when models are already deployed — a real-model strict session
 * with QNN context-binary caching plus cold/warm timings.
 *
 * Release builds skip all of this via [BuildConfig.DEBUG].
 */
object QnnDiagnostics {

    private const val TAG = "[qnn_diagnostics]"

    @Volatile
    private var ran = false

    fun runOnce() {
        if (!BuildConfig.DEBUG || ran) return
        synchronized(this) {
            if (ran) return
            ran = true
            runCatching { run() }.onFailure { t ->
                logcat(LogPriority.WARN, t) { "$TAG aborted: ${t.message}" }
            }
        }
    }

    private fun run() {
        logcat(LogPriority.INFO) {
            "$TAG device=${DeviceCapability.describe()} board=${Build.BOARD} hardware=${Build.HARDWARE} " +
                "qnnSocModel=${DeviceCapability.qnnSocModel} qnnHtpArch=${DeviceCapability.qnnHtpArch}"
        }
        val providers = try {
            OrtEnvironment.getAvailableProviders()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG ortProviders=query_failed" }
            null
        }
        logcat(LogPriority.INFO) { "$TAG ortProviders=$providers" }
        logQnnLibraryInventory()
        runProbeComboMatrix()
        runRealModelCheck()
    }

    private fun logQnnLibraryInventory() {
        runCatching {
            val app = Injekt.get<Application>()
            val libDir = File(app.applicationInfo.nativeLibraryDir)
            val qnnLibs = libDir.listFiles()
                ?.filter { it.name.contains("qnn", ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()
            logcat(LogPriority.INFO) {
                "$TAG qnnLibs(" + qnnLibs.joinToString(",") { "${it.name}:${it.length()}B" } + ")"
            }
        }.onFailure { t ->
            logcat(LogPriority.WARN, t) { "$TAG qnnLibs=unavailable: ${t.message}" }
        }
    }

    private fun runProbeComboMatrix() {
        val env = OnnxRuntimeProvider.environment
        for ((soc, arch) in HardwareDiscoveryEngine.qnnProbeCombos(DeviceCapability.qnnSocModel, DeviceCapability.qnnHtpArch)) {
            val startNs = System.nanoTime()
            val verdict = try {
                val opts = OrtSession.SessionOptions()
                try {
                    opts.addQnn(OnnxRuntimeProvider.buildQnnProviderOptions(socModel = soc, htpArch = arch))
                    opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    env.createSession(QnnProbeModel.MODEL_BYTES, opts).use { "OK" }
                } finally {
                    runCatching { opts.close() }
                }
            } catch (t: Throwable) {
                "FAIL(${t.message})"
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            logcat(LogPriority.INFO) { "$TAG probeCombo soc_model=$soc htp_arch=$arch verdict=$verdict elapsedMs=$elapsedMs" }
        }
    }

    /**
     * Strict-create the smallest deployed detection model (panel detector,
     * int8) with QNN context caching enabled, run it twice, then recreate the
     * session from the persisted context binary. Skipped on first launch
     * before the model store has copied assets to disk.
     */
    private fun runRealModelCheck() {
        runCatching {
            val app = Injekt.get<Application>()
            val model = File(app.noBackupFilesDir, "tachiyomiat-models/panel_detector.onnx")
            if (!model.exists()) {
                logcat(LogPriority.INFO) { "$TAG realModel=skipped reason=not_deployed_yet path=${model.absolutePath}" }
                return
            }
            val env = OnnxRuntimeProvider.environment
            val cacheDir = File(model.parentFile, "qnn-cache-diag")
            val contextFile = File(cacheDir, "${model.name}.qnnctx.bin")

            val coldStartNs = System.nanoTime()
            var inputName: String? = null
            val coldOk = createStrictQnnSession(env, model, contextFile)?.use { session ->
                inputName = session.inputNames.firstOrNull()
                runInferenceOnce(env, session, inputName)
                runInferenceOnce(env, session, inputName)
                true
            } ?: false
            val coldMs = (System.nanoTime() - coldStartNs) / 1_000_000

            val reloadStartNs = System.nanoTime()
            val reloadOk = createStrictQnnSession(env, model, contextFile)?.use { true } ?: false
            val reloadMs = (System.nanoTime() - reloadStartNs) / 1_000_000

            if (!coldOk) {
                logcat(LogPriority.INFO) { "$TAG realModel=strict_create_failed" }
            }
            val cacheFiles = cacheDir.listFiles()?.sortedBy { it.name }.orEmpty()
            logcat(LogPriority.INFO) {
                "$TAG realModel=model:${model.name} input=$inputName coldCreatePlusTwoRunsMs=$coldMs " +
                    "contextReloadMs=$reloadMs reloadOk=$reloadOk " +
                    "contextFiles=" + cacheFiles.joinToString(",") { "${it.name}:${it.length()}B" }
            }
        }.onFailure { t ->
            logcat(LogPriority.WARN, t) { "$TAG realModel=error: ${t.message}" }
        }
    }

    private fun createStrictQnnSession(
        env: OrtEnvironment,
        model: File,
        contextFile: File,
    ): OrtSession? = try {
        var opts: OrtSession.SessionOptions? = null
        try {
            opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(
                contextCacheFile = contextFile,
                strictCpuFallbackDisabled = true,
            )
            env.createSession(model.absolutePath, opts)
        } finally {
            runCatching { opts?.close() }
        }
    } catch (t: Throwable) {
        logcat(LogPriority.INFO) { "$TAG realModel strict session failed: ${t.message}" }
        null
    }

    private fun runInferenceOnce(env: OrtEnvironment, session: OrtSession, inputName: String?) {
        val buffer = FloatBuffer.allocate(1 * 3 * 640 * 640)
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            tensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, 640, 640))
            result = session.run(mapOf((inputName ?: "images") to tensor))
        } finally {
            runCatching { result?.close() }
            runCatching { tensor?.close() }
        }
    }
}
