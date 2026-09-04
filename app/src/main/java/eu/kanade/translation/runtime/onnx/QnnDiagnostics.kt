package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object QnnDiagnostics {

    private const val TAG = "[qnn_diagnostics]"

    @Volatile
    private var ran = false

    data class Stats(
        val min: Long,
        val median: Long,
        val mean: Double,
        val p95: Long,
        val max: Long,
        val count: Int,
    ) {
        companion object {
            fun of(timesMs: List<Long>): Stats {
                if (timesMs.isEmpty()) return Stats(0, 0, 0.0, 0, 0, 0)
                val sorted = timesMs.sorted()
                val min = sorted.first()
                val max = sorted.last()
                val median = sorted[sorted.size / 2]
                val mean = sorted.average()
                val p95Index = ((sorted.size - 1) * 0.95).toInt()
                val p95 = sorted[p95Index]
                return Stats(min, median, mean, p95, max, sorted.size)
            }
        }
    }

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
        val app = Injekt.get<Application>()
        val libDir = app.applicationInfo.nativeLibraryDir

        val startTemp = getBatteryTemp(app)
        val startThermal = getThermalStatus(app)
        logcat(LogPriority.INFO) { "$TAG [THERMAL_START] batteryTemp=${startTemp}C thermalStatus=$startThermal" }

        testDirectLibraryLoads()
        logQnnLibraryInventory(libDir)

        // ==========================================
        // 1. E3A ISOLATION TEST: NO EXPLICIT ADSP_LIBRARY_PATH
        // ==========================================
        runE3AIsolationTest(libDir)

        // ==========================================
        // 2. R1: REAL AOT-GAN BENCHMARK ON QNN GPU VS CPU
        // ==========================================
        runR1AotGpuBenchmark(app)

        // ==========================================
        // 3. R2: REAL DETECTOR BENCHMARK ON HTP VS CPU
        // ==========================================
        runR2DetectorHtpBenchmark(app)

        // ==========================================
        // 4. GPU & HTP OPERATOR ISOLATION SWEEP
        // ==========================================
        runGpuOperatorIsolation(app)

        val endTemp = getBatteryTemp(app)
        val endThermal = getThermalStatus(app)
        val tempDelta = if (startTemp > 0 && endTemp > 0) String.format("%+.1fC", endTemp - startTemp) else "N/A"
        logcat(LogPriority.INFO) { "$TAG [THERMAL_END] batteryTemp=${endTemp}C delta=$tempDelta thermalStatus=$endThermal" }
    }

    private fun getBatteryTemp(app: Application): Float {
        return try {
            val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0f
        } catch (_: Throwable) {
            -1.0f
        }
    }

    private fun getThermalStatus(app: Application): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (powerManager?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        } else {
            "N/A"
        }
    }

    private fun testDirectLibraryLoads() {
        listOf("cdsprpc", "OpenCL", "QnnHtp", "QnnGpu", "QnnSystem").forEach { name ->
            try {
                System.loadLibrary(name)
                logcat(LogPriority.INFO) { "$TAG directLoad lib=$name verdict=OK" }
            } catch (t: Throwable) {
                logcat(LogPriority.WARN) { "$TAG directLoad lib=$name verdict=FAIL(${t.javaClass.simpleName}:${t.message})" }
            }
        }
    }

    private fun logQnnLibraryInventory(libDirPath: String) {
        runCatching {
            val libDir = File(libDirPath)
            val allLibs = libDir.listFiles()
                ?.sortedBy { it.name }
                .orEmpty()
            logcat(LogPriority.INFO) {
                "$TAG nativeLibraryDir=${libDir.absolutePath} count=${allLibs.size} libs(" +
                    allLibs.joinToString(",") { "${it.name}:${it.length()}B" } + ")"
            }
        }.onFailure { t ->
            logcat(LogPriority.WARN, t) { "$TAG qnnLibs=unavailable: ${t.message}" }
        }
    }

    /**
     * E3A Isolation:
     * Does NOT set ADSP_LIBRARY_PATH.
     * Tests if FastRPC automatically discovers libQnnHtpV75Skel.so in nativeLibraryDir.
     */
    private fun runE3AIsolationTest(libDir: String) {
        val currentAdsp = System.getenv("ADSP_LIBRARY_PATH")
        logcat(LogPriority.INFO) { "$TAG [E3A] nativeLibraryDir=$libDir ADSP_LIBRARY_PATH_ENV=$currentAdsp" }

        val env = OnnxRuntimeProvider.environment
        val startNs = System.nanoTime()
        val verdict = try {
            val opts = OrtSession.SessionOptions().apply {
                addQnn(mapOf("backend_type" to "htp"))
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
            }
            env.createSession(QnnProbeModel.MODEL_BYTES, opts).use { session ->
                val inputFloats = floatArrayOf(-1.0f, 2.0f, -3.0f, 4.0f)
                val buffer = FloatBuffer.wrap(inputFloats)
                val inputTensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1, 4))
                val results = session.run(mapOf(session.inputNames.first() to inputTensor))
                val outputTensor = results.get(0) as OnnxTensor
                val outputBuffer = outputTensor.floatBuffer
                val outputValues = FloatArray(4) { outputBuffer.get(it) }
                val isCorrect = Math.abs(outputValues[0] - 0.0f) < 1e-4 &&
                    Math.abs(outputValues[1] - 2.0f) < 1e-4 &&
                    Math.abs(outputValues[2] - 0.0f) < 1e-4 &&
                    Math.abs(outputValues[3] - 4.0f) < 1e-4
                "OK(output=${outputValues.toList()}, verifiedRelu=$isCorrect)"
            }
        } catch (t: Throwable) {
            "FAIL(${t.message})"
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        logcat(LogPriority.INFO) { "$TAG [E3A] HTP Probe (no explicit ADSP path) verdict=$verdict elapsedMs=$elapsedMs" }
    }

    /**
     * R1: Real AOT-GAN Benchmark on QNN GPU vs CPU
     */
    private fun runR1AotGpuBenchmark(app: Application) {
        val model = File(app.noBackupFilesDir, "tachiyomiat-models/aot-512.onnx")
        if (!model.exists()) {
            logcat(LogPriority.WARN) { "$TAG [R1] aot-512.onnx missing at ${model.absolutePath}" }
            return
        }
        val env = OnnxRuntimeProvider.environment
        logcat(LogPriority.INFO) { "$TAG [R1] Starting AOT-GAN 512x512 GPU vs CPU Benchmark (Model: ${model.name}, size=${model.length()}B)" }

        val imgDirect = ByteBuffer.allocateDirect(1 * 3 * 512 * 512 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until (3 * 512 * 512)) imgDirect.put(0.5f)
        imgDirect.flip()

        val maskDirect = ByteBuffer.allocateDirect(1 * 1 * 512 * 512 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until (512 * 512)) maskDirect.put(if (i % 2 == 0) 1.0f else 0.0f)
        maskDirect.flip()

        // 1. QNN GPU Benchmark (test strict first, then fallback allowed)
        var gpuCreateMs = -1L
        var gpuSession: OrtSession? = null
        val gpuRuns = mutableListOf<Long>()
        var gpuShape = ""
        var gpuValidFinite = false
        var gpuStrictOk = false
        var gpuStrictErr = ""

        try {
            val t0 = System.nanoTime()
            val strictOpts = OrtSession.SessionOptions().apply {
                addQnn(mapOf("backend_type" to "gpu"))
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
            }
            env.createSession(model.absolutePath, strictOpts).use { gpuStrictOk = true }
        } catch (t: Throwable) {
            gpuStrictErr = t.message ?: t.javaClass.simpleName
        }
        logcat(LogPriority.INFO) { "$TAG [R1_GPU_STRICT] ok=$gpuStrictOk err=$gpuStrictErr" }

        try {
            val t0 = System.nanoTime()
            val opts = OrtSession.SessionOptions().apply {
                addQnn(mapOf("backend_type" to "gpu"))
                addConfigEntry("session.disable_cpu_ep_fallback", if (gpuStrictOk) "1" else "0")
            }
            gpuSession = env.createSession(model.absolutePath, opts)
            gpuCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            maskDirect.rewind()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 512, 512))
            val maskTensor = OnnxTensor.createTensor(env, maskDirect, longArrayOf(1, 1, 512, 512))
            val feed = mapOf("image" to imgTensor, "mask" to maskTensor)

            // 3 Warmups
            repeat(3) {
                gpuSession.run(feed).close()
            }

            // 10 Steady state
            repeat(10) {
                val tStart = System.nanoTime()
                gpuSession.run(feed).use { res ->
                    val outTensor = res[0] as OnnxTensor
                    if (gpuRuns.isEmpty()) {
                        gpuShape = outTensor.info.shape.contentToString()
                        val buf = outTensor.floatBuffer
                        var hasNanOrInf = false
                        for (idx in 0 until minOf(2000, buf.remaining())) {
                            val v = buf.get(idx)
                            if (v.isNaN() || v.isInfinite()) hasNanOrInf = true
                        }
                        gpuValidFinite = !hasNanOrInf
                    }
                }
                gpuRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
            maskTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R1] GPU execution failed: ${t.message}" }
        } finally {
            runCatching { gpuSession?.close() }
        }

        // 2. CPU Benchmark (XNNPACK / Default)
        var cpuCreateMs = -1L
        var cpuSession: OrtSession? = null
        val cpuRuns = mutableListOf<Long>()
        try {
            val t0 = System.nanoTime()
            val opts = OnnxRuntimeProvider.createRequiredXnnpackSessionOptions()
            cpuSession = env.createSession(model.absolutePath, opts)
            cpuCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            maskDirect.rewind()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 512, 512))
            val maskTensor = OnnxTensor.createTensor(env, maskDirect, longArrayOf(1, 1, 512, 512))
            val feed = mapOf("image" to imgTensor, "mask" to maskTensor)

            repeat(3) {
                cpuSession.run(feed).close()
            }
            repeat(10) {
                val tStart = System.nanoTime()
                cpuSession.run(feed).close()
                cpuRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
            maskTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R1] CPU execution failed: ${t.message}" }
        } finally {
            runCatching { cpuSession?.close() }
        }

        // 3. NNAPI Benchmark (Alternative GPU/Accelerator route)
        var nnapiCreateMs = -1L
        var nnapiSession: OrtSession? = null
        val nnapiRuns = mutableListOf<Long>()
        try {
            val t0 = System.nanoTime()
            val opts = OrtSession.SessionOptions().apply {
                addNnapi()
            }
            nnapiSession = env.createSession(model.absolutePath, opts)
            nnapiCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            maskDirect.rewind()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 512, 512))
            val maskTensor = OnnxTensor.createTensor(env, maskDirect, longArrayOf(1, 1, 512, 512))
            val feed = mapOf("image" to imgTensor, "mask" to maskTensor)

            repeat(3) { nnapiSession.run(feed).close() }
            repeat(10) {
                val tStart = System.nanoTime()
                nnapiSession.run(feed).close()
                nnapiRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
            maskTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R1] NNAPI execution failed: ${t.message}" }
        } finally {
            runCatching { nnapiSession?.close() }
        }

        val gpuStats = Stats.of(gpuRuns)
        val cpuStats = Stats.of(cpuRuns)
        val nnapiStats = Stats.of(nnapiRuns)
        val gpuSpeedup = if (gpuStats.median > 0) String.format("%.2fx", cpuStats.median.toDouble() / gpuStats.median.toDouble()) else "N/A"
        val nnapiSpeedup = if (nnapiStats.median > 0) String.format("%.2fx", cpuStats.median.toDouble() / nnapiStats.median.toDouble()) else "N/A"

        logcat(LogPriority.INFO) {
            "$TAG [R1_AOT_GPU] createMs=$gpuCreateMs min=${gpuStats.min} median=${gpuStats.median} " +
                "mean=${String.format("%.1f", gpuStats.mean)} p95=${gpuStats.p95} max=${gpuStats.max} " +
                "speedup=$gpuSpeedup shape=$gpuShape validFinite=$gpuValidFinite runs=${gpuRuns.joinToString()}"
        }
        logcat(LogPriority.INFO) {
            "$TAG [R1_AOT_NNAPI] createMs=$nnapiCreateMs min=${nnapiStats.min} median=${nnapiStats.median} " +
                "mean=${String.format("%.1f", nnapiStats.mean)} p95=${nnapiStats.p95} max=${nnapiStats.max} " +
                "speedup=$nnapiSpeedup runs=${nnapiRuns.joinToString()}"
        }
        logcat(LogPriority.INFO) {
            "$TAG [R1_AOT_CPU] createMs=$cpuCreateMs min=${cpuStats.min} median=${cpuStats.median} " +
                "mean=${String.format("%.1f", cpuStats.mean)} p95=${cpuStats.p95} max=${cpuStats.max} " +
                "runs=${cpuRuns.joinToString()}"
        }

        // 4. QNN HTP Benchmark on aot-512.onnx
        var aotHtpStrictOk = false
        var aotHtpStrictErr = ""
        try {
            val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(strictCpuFallbackDisabled = true)
            env.createSession(model.absolutePath, opts).use { aotHtpStrictOk = true }
        } catch (t: Throwable) {
            aotHtpStrictErr = t.message ?: t.javaClass.simpleName
        }
        logcat(LogPriority.INFO) { "$TAG [R1_AOT_HTP_STRICT] ok=$aotHtpStrictOk err=$aotHtpStrictErr" }

        var aotHtpCreateMs = -1L
        var aotHtpSession: OrtSession? = null
        val aotHtpRuns = mutableListOf<Long>()
        var aotHtpShape = ""
        var aotHtpValidFinite = false
        try {
            val t0 = System.nanoTime()
            val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(strictCpuFallbackDisabled = false)
            aotHtpSession = env.createSession(model.absolutePath, opts)
            aotHtpCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            maskDirect.rewind()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 512, 512))
            val maskTensor = OnnxTensor.createTensor(env, maskDirect, longArrayOf(1, 1, 512, 512))
            val feed = mapOf("image" to imgTensor, "mask" to maskTensor)

            repeat(3) { aotHtpSession.run(feed).close() }
            repeat(10) {
                val tStart = System.nanoTime()
                aotHtpSession.run(feed).use { res ->
                    val outTensor = res[0] as OnnxTensor
                    if (aotHtpRuns.isEmpty()) {
                        aotHtpShape = outTensor.info.shape.contentToString()
                        val buf = outTensor.floatBuffer
                        var hasNanOrInf = false
                        for (idx in 0 until minOf(2000, buf.remaining())) {
                            val v = buf.get(idx)
                            if (v.isNaN() || v.isInfinite()) hasNanOrInf = true
                        }
                        aotHtpValidFinite = !hasNanOrInf
                    }
                }
                aotHtpRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
            maskTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R1_AOT_HTP] execution failed: ${t.message}" }
        } finally {
            runCatching { aotHtpSession?.close() }
        }

        val aotHtpStats = Stats.of(aotHtpRuns)
        val aotHtpSpeedup = if (aotHtpStats.median > 0) String.format("%.2fx", cpuStats.median.toDouble() / aotHtpStats.median.toDouble()) else "N/A"
        logcat(LogPriority.INFO) {
            "$TAG [R1_AOT_HTP] createMs=$aotHtpCreateMs min=${aotHtpStats.min} median=${aotHtpStats.median} " +
                "mean=${String.format("%.1f", aotHtpStats.mean)} p95=${aotHtpStats.p95} max=${aotHtpStats.max} " +
                "speedup=$aotHtpSpeedup shape=$aotHtpShape validFinite=$aotHtpValidFinite runs=${aotHtpRuns.joinToString()}"
        }

        // 5. Sustained 20-run test on GPU (if functional) or NNAPI/CPU
        if (gpuRuns.isNotEmpty()) {
            try {
                val opts = OrtSession.SessionOptions().apply {
                    addQnn(mapOf("backend_type" to "gpu"))
                }
                env.createSession(model.absolutePath, opts).use { sustainedGpuSession ->
                    imgDirect.rewind()
                    maskDirect.rewind()
                    val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 512, 512))
                    val maskTensor = OnnxTensor.createTensor(env, maskDirect, longArrayOf(1, 1, 512, 512))
                    val feed = mapOf("image" to imgTensor, "mask" to maskTensor)
                    val sustainedRuns = mutableListOf<Long>()
                    repeat(20) {
                        val t = System.nanoTime()
                        sustainedGpuSession.run(feed).close()
                        sustainedRuns.add((System.nanoTime() - t) / 1_000_000)
                    }
                    val sustStats = Stats.of(sustainedRuns)
                    logcat(LogPriority.INFO) {
                        "$TAG [R1_AOT_GPU_SUSTAINED] 20 runs: first=${sustainedRuns.first()} last=${sustainedRuns.last()} " +
                            "median=${sustStats.median} min=${sustStats.min} max=${sustStats.max}"
                    }
                    imgTensor.close()
                    maskTensor.close()
                }
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "$TAG [R1] Sustained GPU test failed: ${t.message}" }
            }
        }
    }

    /**
     * R2: Real Detector Benchmark on HTP vs CPU
     */
    private fun runR2DetectorHtpBenchmark(app: Application) {
        val model = File(app.noBackupFilesDir, "tachiyomiat-models/detector.onnx")
        if (!model.exists()) {
            logcat(LogPriority.WARN) { "$TAG [R2] detector.onnx missing at ${model.absolutePath}" }
            return
        }
        val env = OnnxRuntimeProvider.environment
        logcat(LogPriority.INFO) { "$TAG [R2] Starting Text Detector Benchmark (Model: ${model.name}, size=${model.length()}B)" }

        val imgDirect = ByteBuffer.allocateDirect(1 * 3 * 640 * 640 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until (3 * 640 * 640)) imgDirect.put(0.5f)
        imgDirect.flip()

        val sizesDirect = ByteBuffer.allocateDirect(2 * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        sizesDirect.put(1080L)
        sizesDirect.put(1920L)
        sizesDirect.flip()

        // 1. Check strict fallback (disable_cpu_ep_fallback = 1)
        var strictOk = false
        var strictErr = ""
        try {
            val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(strictCpuFallbackDisabled = true)
            env.createSession(model.absolutePath, opts).use { strictOk = true }
        } catch (t: Throwable) {
            strictErr = t.message ?: t.javaClass.simpleName
        }
        logcat(LogPriority.INFO) { "$TAG [R2_DETECTOR_HTP_STRICT] ok=$strictOk err=$strictErr" }

        // 2. Measure HTP (fallback allowed if needed)
        var htpCreateMs = -1L
        var htpSession: OrtSession? = null
        val htpRuns = mutableListOf<Long>()
        try {
            val t0 = System.nanoTime()
            val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(strictCpuFallbackDisabled = false)
            htpSession = env.createSession(model.absolutePath, opts)
            htpCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            sizesDirect.rewind()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 640, 640))
            val sizesTensor = OnnxTensor.createTensor(env, sizesDirect, longArrayOf(1, 2))
            val feed = mapOf("images" to imgTensor, "orig_target_sizes" to sizesTensor)

            repeat(3) { htpSession.run(feed).close() }
            repeat(10) {
                val tStart = System.nanoTime()
                htpSession.run(feed).close()
                htpRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
            sizesTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R2] HTP run failed: ${t.message}" }
        } finally {
            runCatching { htpSession?.close() }
        }

        // 3. CPU Baseline
        var cpuCreateMs = -1L
        var cpuSession: OrtSession? = null
        val cpuRuns = mutableListOf<Long>()
        try {
            val t0 = System.nanoTime()
            val opts = OnnxRuntimeProvider.createRequiredXnnpackSessionOptions()
            cpuSession = env.createSession(model.absolutePath, opts)
            cpuCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            sizesDirect.rewind()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 640, 640))
            val sizesTensor = OnnxTensor.createTensor(env, sizesDirect, longArrayOf(1, 2))
            val feed = mapOf("images" to imgTensor, "orig_target_sizes" to sizesTensor)

            repeat(3) { cpuSession.run(feed).close() }
            repeat(10) {
                val tStart = System.nanoTime()
                cpuSession.run(feed).close()
                cpuRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
            sizesTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R2] CPU run failed: ${t.message}" }
        } finally {
            runCatching { cpuSession?.close() }
        }

        val htpStats = Stats.of(htpRuns)
        val cpuStats = Stats.of(cpuRuns)
        val speedup = if (htpStats.median > 0) String.format("%.2fx", cpuStats.median.toDouble() / htpStats.median.toDouble()) else "N/A"

        logcat(LogPriority.INFO) {
            "$TAG [R2_DETECTOR_HTP] createMs=$htpCreateMs min=${htpStats.min} median=${htpStats.median} " +
                "mean=${String.format("%.1f", htpStats.mean)} p95=${htpStats.p95} max=${htpStats.max} runs=${htpRuns.joinToString()}"
        }
        logcat(LogPriority.INFO) {
            "$TAG [R2_DETECTOR_CPU] createMs=$cpuCreateMs min=${cpuStats.min} median=${cpuStats.median} " +
                "mean=${String.format("%.1f", cpuStats.mean)} p95=${cpuStats.p95} max=${cpuStats.max} " +
                "speedup=$speedup runs=${cpuRuns.joinToString()}"
        }

        runPanelDetectorBenchmark(app)

        // 4. HTP Context Caching Benchmark on detector
        val cacheDir = File(model.parentFile, "qnn-cache-diag")
        val ctxFile = File(cacheDir, "${model.name}.qnnctx.bin")
        ctxFile.delete()
        File(cacheDir, "${model.name}.qnnctx_qnn.bin").delete()

        var ctxGenMs = -1L
        try {
            val t0 = System.nanoTime()
            val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(contextCacheFile = ctxFile, strictCpuFallbackDisabled = false)
            env.createSession(model.absolutePath, opts).close()
            ctxGenMs = (System.nanoTime() - t0) / 1_000_000
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R2] Context gen failed: ${t.message}" }
        }

        val ctxSize = ctxFile.length()
        val qnnBin = File(cacheDir, "${model.name}.qnnctx_qnn.bin")
        val qnnBinSize = qnnBin.length()

        var ctxReloadMs = -1L
        val ctxReloadRuns = mutableListOf<Long>()
        if (ctxFile.exists()) {
            try {
                val t0 = System.nanoTime()
                val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(contextCacheFile = null, strictCpuFallbackDisabled = false)
                env.createSession(ctxFile.absolutePath, opts).use { reloadedSession ->
                    ctxReloadMs = (System.nanoTime() - t0) / 1_000_000
                    imgDirect.rewind()
                    sizesDirect.rewind()
                    val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 640, 640))
                    val sizesTensor = OnnxTensor.createTensor(env, sizesDirect, longArrayOf(1, 2))
                    val feed = mutableMapOf<String, OnnxTensor>("images" to imgTensor)
                    if (reloadedSession.inputNames.contains("orig_target_sizes")) {
                        feed["orig_target_sizes"] = sizesTensor
                    }
                    repeat(3) { reloadedSession.run(feed).close() }
                    repeat(10) {
                        val t = System.nanoTime()
                        reloadedSession.run(feed).close()
                        ctxReloadRuns.add((System.nanoTime() - t) / 1_000_000)
                    }
                    imgTensor.close()
                    sizesTensor.close()
                }
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "$TAG [R2] Context reload failed: ${t.message}" }
            }
        }
        val ctxReloadStats = Stats.of(ctxReloadRuns)
        logcat(LogPriority.INFO) {
            "$TAG [R2_CONTEXT_CACHE] genMs=$ctxGenMs ctxFileSize=${ctxSize}B qnnBinSize=${qnnBinSize}B " +
                "reloadMs=$ctxReloadMs reloadedInferenceMedian=${ctxReloadStats.median}ms min=${ctxReloadStats.min} max=${ctxReloadStats.max}"
        }
    }

    private fun runPanelDetectorBenchmark(app: Application) {
        val model = File(app.noBackupFilesDir, "tachiyomiat-models/panel_detector.onnx")
        if (!model.exists()) {
            logcat(LogPriority.WARN) { "$TAG [R2_PANEL] panel_detector.onnx missing" }
            return
        }
        val env = OnnxRuntimeProvider.environment
        logcat(LogPriority.INFO) { "$TAG [R2_PANEL] Starting Panel Detector Benchmark (Model: ${model.name}, size=${model.length()}B)" }

        val imgDirect = ByteBuffer.allocateDirect(1 * 3 * 640 * 640 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until (3 * 640 * 640)) imgDirect.put(0.5f)
        imgDirect.flip()

        // 1. Check strict fallback
        var strictOk = false
        var strictErr = ""
        try {
            val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(strictCpuFallbackDisabled = true)
            env.createSession(model.absolutePath, opts).use { strictOk = true }
        } catch (t: Throwable) {
            strictErr = t.message ?: t.javaClass.simpleName
        }
        logcat(LogPriority.INFO) { "$TAG [R2_PANEL_HTP_STRICT] ok=$strictOk err=$strictErr" }

        // 2. HTP Benchmark
        var htpCreateMs = -1L
        var htpSession: OrtSession? = null
        val htpRuns = mutableListOf<Long>()
        try {
            val t0 = System.nanoTime()
            val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(strictCpuFallbackDisabled = false)
            htpSession = env.createSession(model.absolutePath, opts)
            htpCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            val inName = htpSession.inputNames.first()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 640, 640))
            val feed = mapOf(inName to imgTensor)

            repeat(3) { htpSession.run(feed).close() }
            repeat(10) {
                val tStart = System.nanoTime()
                htpSession.run(feed).close()
                htpRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R2_PANEL] HTP failed: ${t.message}" }
        } finally {
            runCatching { htpSession?.close() }
        }

        // 3. CPU Benchmark
        var cpuCreateMs = -1L
        var cpuSession: OrtSession? = null
        val cpuRuns = mutableListOf<Long>()
        try {
            val t0 = System.nanoTime()
            val opts = OnnxRuntimeProvider.createRequiredXnnpackSessionOptions()
            cpuSession = env.createSession(model.absolutePath, opts)
            cpuCreateMs = (System.nanoTime() - t0) / 1_000_000

            imgDirect.rewind()
            val inName = cpuSession.inputNames.first()
            val imgTensor = OnnxTensor.createTensor(env, imgDirect, longArrayOf(1, 3, 640, 640))
            val feed = mapOf(inName to imgTensor)

            repeat(3) { cpuSession.run(feed).close() }
            repeat(10) {
                val tStart = System.nanoTime()
                cpuSession.run(feed).close()
                cpuRuns.add((System.nanoTime() - tStart) / 1_000_000)
            }
            imgTensor.close()
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "$TAG [R2_PANEL] CPU failed: ${t.message}" }
        } finally {
            runCatching { cpuSession?.close() }
        }

        val htpStats = Stats.of(htpRuns)
        val cpuStats = Stats.of(cpuRuns)
        val speedup = if (htpStats.median > 0) String.format("%.2fx", cpuStats.median.toDouble() / htpStats.median.toDouble()) else "N/A"

        logcat(LogPriority.INFO) {
            "$TAG [R2_PANEL_HTP] createMs=$htpCreateMs min=${htpStats.min} median=${htpStats.median} " +
                "mean=${String.format("%.1f", htpStats.mean)} p95=${htpStats.p95} max=${htpStats.max} runs=${htpRuns.joinToString()}"
        }
        logcat(LogPriority.INFO) {
            "$TAG [R2_PANEL_CPU] createMs=$cpuCreateMs min=${cpuStats.min} median=${cpuStats.median} " +
                "mean=${String.format("%.1f", cpuStats.mean)} p95=${cpuStats.p95} max=${cpuStats.max} " +
                "speedup=$speedup runs=${cpuRuns.joinToString()}"
        }
    }

    private fun runGpuOperatorIsolation(app: Application) {
        val reproDir = File(app.noBackupFilesDir, "qnn_repro")
        if (!reproDir.exists() || !reproDir.isDirectory) {
            logcat(LogPriority.WARN) { "$TAG [ISO] Repro dir missing at ${reproDir.absolutePath}" }
            return
        }
        val models = reproDir.listFiles { f -> f.extension == "onnx" }?.sortedBy { it.name }.orEmpty()
        if (models.isEmpty()) {
            logcat(LogPriority.WARN) { "$TAG [ISO] No .onnx models found in ${reproDir.absolutePath}" }
            return
        }

        val env = OnnxRuntimeProvider.environment
        logcat(LogPriority.INFO) { "$TAG [ISO] Running operator isolation on ${models.size} models in ${reproDir.absolutePath}" }

        for (modelFile in models) {
            val modelName = modelFile.name
            logcat(LogPriority.INFO) { "$TAG [ISO] ================= Model: $modelName (${modelFile.length()}B) =================" }

            // 1. GPU Strict (disable_cpu_ep_fallback = 1)
            var gpuStrictOk = false
            var gpuStrictErr = ""
            var gpuStrictInferMs = -1L
            try {
                val opts = OrtSession.SessionOptions().apply {
                    addQnn(mapOf("backend_type" to "gpu"))
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                }
                env.createSession(modelFile.absolutePath, opts).use { session ->
                    gpuStrictOk = true
                    gpuStrictInferMs = testIsolationInference(env, session, modelName)
                }
            } catch (t: Throwable) {
                gpuStrictErr = t.message ?: t.javaClass.simpleName
            }
            logcat(LogPriority.INFO) {
                "$TAG [ISO_RESULT] model=$modelName backend=GPU strict=1 ok=$gpuStrictOk inferMs=$gpuStrictInferMs err=$gpuStrictErr"
            }

            // 2. GPU Non-strict (disable_cpu_ep_fallback = 0)
            var gpuFallbackOk = false
            var gpuFallbackErr = ""
            var gpuFallbackInferMs = -1L
            try {
                val opts = OrtSession.SessionOptions().apply {
                    addQnn(mapOf("backend_type" to "gpu"))
                    addConfigEntry("session.disable_cpu_ep_fallback", "0")
                }
                env.createSession(modelFile.absolutePath, opts).use { session ->
                    gpuFallbackOk = true
                    gpuFallbackInferMs = testIsolationInference(env, session, modelName)
                }
            } catch (t: Throwable) {
                gpuFallbackErr = t.message ?: t.javaClass.simpleName
            }
            logcat(LogPriority.INFO) {
                "$TAG [ISO_RESULT] model=$modelName backend=GPU strict=0 ok=$gpuFallbackOk inferMs=$gpuFallbackInferMs err=$gpuFallbackErr"
            }

            // 3. HTP Strict (disable_cpu_ep_fallback = 1)
            var htpStrictOk = false
            var htpStrictErr = ""
            var htpStrictInferMs = -1L
            try {
                val opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(strictCpuFallbackDisabled = true)
                env.createSession(modelFile.absolutePath, opts).use { session ->
                    htpStrictOk = true
                    htpStrictInferMs = testIsolationInference(env, session, modelName)
                }
            } catch (t: Throwable) {
                htpStrictErr = t.message ?: t.javaClass.simpleName
            }
            logcat(LogPriority.INFO) {
                "$TAG [ISO_RESULT] model=$modelName backend=HTP strict=1 ok=$htpStrictOk inferMs=$htpStrictInferMs err=$htpStrictErr"
            }
        }
    }

    private fun testIsolationInference(env: OrtEnvironment, session: OrtSession, modelName: String): Long {
        return try {
            val inName = session.inputNames.first()
            val shape = when (modelName) {
                "test_pad_reflect_512.onnx" -> longArrayOf(1, 4, 512, 512)
                "test_resize_align_corners.onnx", "test_resize_half_pixel.onnx" -> longArrayOf(1, 16, 32, 32)
                "test_tanh_clip.onnx" -> longArrayOf(1, 3, 64, 64)
                else -> longArrayOf(1, 4, 64, 64)
            }
            val elementCount = shape.fold(1L) { acc, d -> acc * d }.toInt()
            val buf = ByteBuffer.allocateDirect(elementCount * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until elementCount) buf.put(0.5f)
            buf.flip()

            val tensor = OnnxTensor.createTensor(env, buf, shape)
            val feed = mapOf(inName to tensor)
            val t0 = System.nanoTime()
            session.run(feed).close()
            val inferMs = (System.nanoTime() - t0) / 1_000_000
            tensor.close()
            inferMs
        } catch (t: Throwable) {
            logcat(LogPriority.WARN) { "$TAG [ISO] Inference run error for $modelName: ${t.message}" }
            -999L
        }
    }
}
