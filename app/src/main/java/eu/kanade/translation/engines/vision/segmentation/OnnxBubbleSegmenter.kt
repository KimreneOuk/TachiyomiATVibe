package eu.kanade.translation.engines.vision.segmentation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.ModelRoutingEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.model.BubbleMaskRle
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.File
import java.nio.FloatBuffer

/** ONNX session owner for the AGPL-3.0 manga109 YOLO11 bubble segmenter. */
class OnnxBubbleSegmenter(
    // TachiyomiAT  §6.1: injectable session-creation seam so JVM tests can
    // drive the session-swap/recovery policy without the native ORT runtime.
    // Production callers keep using the no-arg constructor.
    private val sessionFactory: SessionFactory = ProductionSessionFactory,
) {
    /**
     * TachiyomiAT  §6.1: minimal seam over one live ORT session. A handle
     * is single-attempt: [run] executes exactly one inference, validates the
     * output contract, and closes every intermediate ORT object it created
     * before returning (or throwing), so a failed partial result never
     * survives into a retry. Public only because it appears in the public
     * constructor's seam type; not intended for external use.
     */
    interface SegmenterSessionHandle : AutoCloseable {
        val inputNames: Set<String>
        val outputNames: Set<String>

        /** Runs one inference on the CHW input buffer; returns (predictions, prototypes). */
        fun run(input: FloatBuffer): Pair<FloatArray, FloatArray>
    }

    /** Factory seam for one [SegmenterSessionHandle] (session creation only). */
    interface SessionFactory {
        fun create(
            modelPath: String,
            useAccelerator: Boolean,
            useXnnpack: Boolean,
            providerSink: (String) -> Unit,
            requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute? = null,
        ): SegmenterSessionHandle
    }

    private object ProductionSessionFactory : SessionFactory {
        override fun create(
            modelPath: String,
            useAccelerator: Boolean,
            useXnnpack: Boolean,
            providerSink: (String) -> Unit,
            requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute?,
        ): SegmenterSessionHandle = OrtSessionHandle(
            OnnxRuntimeProvider.createSessionWithFallback(
                modelPath,
                useAccelerator = useAccelerator,
                useXnnpack = useXnnpack,
                providerSink = providerSink,
                requestedHardwareRoute = requestedHardwareRoute,
            ),
        )
    }

    /**
     * Production adapter. Tensor/result ownership stays INSIDE [run]: the
     * result closes in the inner finally (before any value escapes or error
     * propagates) and the input tensor in the outer finally — each exactly
     * once. The caller-side `finally` in [segment] remains the single cleanup
     * point for the pooled buffer and the temporary bitmap.
     */
    private class OrtSessionHandle(private val session: OrtSession) : SegmenterSessionHandle {
        override val inputNames: Set<String> = session.inputNames
        override val outputNames: Set<String> = session.outputNames

        override fun run(input: FloatBuffer): Pair<FloatArray, FloatArray> {
            val tensor = OnnxTensor.createTensor(OnnxRuntimeProvider.environment, input, longArrayOf(1, 3, 640, 640))
            try {
                val result = session.run(mapOf("images" to tensor))
                try {
                    val prediction = result[0] as? OnnxTensor ?: error("Bubble segmenter output0 is not a tensor")
                    val prototype = result[1] as? OnnxTensor ?: error("Bubble segmenter output1 is not a tensor")
                    val pShape = prediction.info.shape
                    val mShape = prototype.info.shape
                    require(pShape.contentEquals(longArrayOf(1, 37, 8400))) {
                        "Unsupported bubble output0 shape=${pShape.contentToString()}; expected [1,37,8400]"
                    }
                    require(mShape.contentEquals(longArrayOf(1, 32, 160, 160))) {
                        "Unsupported bubble output1 shape=${mShape.contentToString()}; expected [1,32,160,160]"
                    }
                    val predictions = FloatArray(37 * 8400).also { prediction.floatBuffer.get(it) }
                    val prototypes = FloatArray(32 * 160 * 160).also { prototype.floatBuffer.get(it) }
                    return predictions to prototypes
                } finally {
                    result.close()
                }
            } finally {
                tensor.close()
            }
        }

        override fun close() {
            session.close()
        }
    }

    private var session: SegmenterSessionHandle? = null

    // TachiyomiAT  §3.1: normalized model path retained so a failed
    // accelerated session can be rebuilt explicitly on default CPU.
    private var modelPath: String? = null

    /** Owns the session swap during one-shot accelerated→CPU recovery (§3.2). */
    private val sessionSwapLock = Any()

    /** Provider that actually serves this segmenter ("qnn_htp"/"nnapi"/"cpu"), for honest perf logging. */
    var executionProviderLabel: String = "uninitialized"
        private set

    // pooled DIRECT buffer for the fixed 1x3x640x640 tensor; ORT
    // consumes it in place so it MUST outlive the tensor. maxPoolSize=2 bounds
    // resident native memory. Same contract as OnnxPageTextDetector.
    private val inputBufferPool = DirectBufferPool(
        bufferCapacityBytes = SEGMENTER_INPUT_FLOATS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    fun initialize(
        modelFile: File,
        requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute? = null,
    ) {
        modelPath = modelFile.absolutePath
        val useAcc = requestedHardwareRoute != null &&
            requestedHardwareRoute != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
        val created = sessionFactory.create(
            modelPath = modelFile.absolutePath,
            useAccelerator = useAcc,
            useXnnpack = false,
            providerSink = { executionProviderLabel = it },
            requestedHardwareRoute = requestedHardwareRoute,
        )
        session = created
        validateContract(created)
        logcat(LogPriority.INFO) {
            "Bubble segmenter initialized: inputs=${created.inputNames} outputs=${created.outputNames} provider=$executionProviderLabel"
        }
    }

    private fun validateContract(current: SegmenterSessionHandle) {
        require(current.inputNames == setOf("images")) {
            "Bubble segmenter input contract changed: ${current.inputNames}"
        }
        require(current.outputNames.size == 2) { "Bubble segmenter expected two outputs, got ${current.outputNames}" }
    }

    fun segment(bitmap: Bitmap): List<BubbleMaskRle> {
        val current = session ?: throw IllegalStateException("Bubble segmenter session is not initialized")
        val transform = BubbleSegmentationDecoder.letterboxFor(bitmap.width, bitmap.height)
        val input = BitmapPool.getARGB8888(
            BubbleSegmentationDecoder.INPUT_SIZE,
            BubbleSegmentationDecoder.INPUT_SIZE,
        )
        var inputBuffer: FloatBuffer? = null
        try {
            Canvas(input).apply {
                drawColor(0xFF727272.toInt())
                drawBitmap(
                    bitmap,
                    null,
                    RectF(
                        transform.padX.toFloat(),
                        transform.padY.toFloat(),
                        transform.padX + bitmap.width * transform.ratio,
                        transform.padY + bitmap.height * transform.ratio,
                    ),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            // ORT consumes the direct buffer in place (no native copy),
            // so it MUST outlive the tensor — keep referenced until the finally.
            inputBuffer = inputBufferPool.acquire()
            val buffer = inputBuffer
            buffer.clear()
            val pixels = IntArray(BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE)
            input.getPixels(
                pixels,
                0,
                BubbleSegmentationDecoder.INPUT_SIZE,
                0,
                0,
                BubbleSegmentationDecoder.INPUT_SIZE,
                BubbleSegmentationDecoder.INPUT_SIZE,
            )
            val plane = BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE
            for (i in pixels.indices) {
                buffer.put(i, (pixels[i] shr 16 and 0xFF) / 255f)
                buffer.put(plane + i, (pixels[i] shr 8 and 0xFF) / 255f)
                buffer.put(2 * plane + i, (pixels[i] and 0xFF) / 255f)
            }
            buffer.limit(3 * plane)
            buffer.position(0)
            // TachiyomiAT  §3.2: inference plus one-shot accelerated→CPU
            // recovery. Each attempt's tensor/result is owned and closed inside
            // the session handle, so a failed partial result never survives into
            // a retry; this finally remains the single cleanup point for the
            // pooled input buffer and the temporary bitmap.
            val t0 = System.nanoTime()
            val (predictions, prototypes) = runInferenceWithRecovery(current, buffer)
            val inferMs = (System.nanoTime() - t0) / 1_000_000.0
            logcat(LogPriority.INFO) {
                "[segmentation_perf] model=bubble_segmenter provider=$executionProviderLabel inference=${inferMs}ms"
            }
            return BubbleSegmentationDecoder.decodeRle(
                predictions,
                37,
                8400,
                prototypes,
                160,
                160,
                bitmap.width,
                bitmap.height,
            )
        } finally {
            inputBuffer?.let { inputBufferPool.release(it) }
            BitmapPool.putARGB8888(input)
        }
    }

    /**
     * TachiyomiAT  §3.2: one-shot accelerated→CPU runtime recovery.
     *
     * Only [OrtException] thrown by the session run is treated as a possible
     * provider failure — decoder/output-contract errors, invalid shapes,
     * cancellation, OOM, and arbitrary exceptions propagate unchanged. If the
     * current label is not an accelerated route, CPU is the terminal route and
     * the error is rethrown immediately. Otherwise the failed session is
     * closed and discarded under the segmenter-local lock, an explicit
     * default-CPU session is created and contract-validated, and the same
     * inference is retried exactly once. There is never a third run. Internal
     * (not private) so §6.1 JVM tests can drive the policy via the factory
     * seam.
     */
    internal fun runInferenceWithRecovery(
        current: SegmenterSessionHandle,
        input: FloatBuffer,
    ): Pair<FloatArray, FloatArray> = try {
        current.run(input).also {
            // A completed
            // accelerated run is the execution proof — SUPPORTED now means
            // created AND executed. CPU/primary runs never touch the
            // accelerator route (CPU is the terminal route, never marked).
            markAcceleratedRouteProven()
        }
    } catch (error: OrtException) {
        if (executionProviderLabel !in ACCELERATED_PROVIDER_LABELS) throw error
        val cpu = rebuildOnCpuAfterAcceleratedFailure(current, error)
        try {
            cpu.run(input)
        } catch (cpuError: Throwable) {
            // CPU retry failure: propagate it with the first accelerator
            // failure suppressed. There is never a third run.
            throw cpuError.apply { addSuppressed(error) }
        }
    }

    /**
     * Only an accelerated label
     * maps to a routing-engine route; a successful run on that route records
     * the execution proof via [ModelRoutingEngine.recordSuccessfulInference].
     */
    private fun markAcceleratedRouteProven() {
        val path = modelPath ?: return
        val route = hardwareRouteForLabel(executionProviderLabel) ?: return
        ModelRoutingEngine.recordSuccessfulInference(
            ModelRoutingEngine.resolveModelId(path),
            route,
        )
    }

    private fun rebuildOnCpuAfterAcceleratedFailure(
        failed: SegmenterSessionHandle,
        acceleratorError: OrtException,
    ): SegmenterSessionHandle {
        // Record the runtime failure against the model and its
        // actual route. Read-only use of the routing engine's existing public
        // API; this does not modify the routing-engine state machine.
        hardwareRouteForLabel(executionProviderLabel)?.let { route ->
            ModelRoutingEngine.recordFailure(
                ModelRoutingEngine.resolveModelId(modelPath.orEmpty()),
                route,
                acceleratorError,
            )
        }
        return try {
            synchronized(sessionSwapLock) {
                if (session === failed) {
                    try {
                        failed.close()
                    } catch (closeError: Throwable) {
                        acceleratorError.addSuppressed(closeError)
                    }
                    session = null
                }
                val path = requireNotNull(modelPath) { "Bubble segmenter model path is not recorded" }
                val cpu = sessionFactory.create(
                    modelPath = path,
                    useAccelerator = false,
                    useXnnpack = false,
                    providerSink = { executionProviderLabel = it },
                )
                validateContract(cpu)
                session = cpu
                logcat(LogPriority.WARN, acceleratorError) {
                    "Bubble segmenter accelerated session failed; rebuilt once on default CPU (provider=$executionProviderLabel)"
                }
                cpu
            }
        } catch (swapError: Throwable) {
            // §3.2 step 7: propagate the CPU-side failure with the accelerator
            // failure suppressed; no third run exists on any path.
            swapError.addSuppressed(acceleratorError)
            throw swapError
        }
    }

    private fun hardwareRouteForLabel(label: String): HardwareDiscoveryEngine.HardwareRoute? = when (label) {
        "qnn_htp" -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        "qnn_gpu" -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU
        "nnapi" -> HardwareDiscoveryEngine.HardwareRoute.NNAPI
        else -> null
    }

    fun close() {
        // TachiyomiAT  §6.1: serialized against the recovery session swap
        // and idempotent — after this runs, session is null, so a second call
        // closes nothing and can never double-release a session.
        synchronized(sessionSwapLock) {
            session?.close()
            session = null
        }
        inputBufferPool.clear()
    }

    // frees the pooled direct buffer without tearing down the ONNX
    // session; wired into per-page OOM relief. Registered in
    // RoiPageRecognitionEngine's reclaim fan-outs like the sibling engines.
    fun reclaimPooledMemory() {
        inputBufferPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        inputBufferPool.clear()
    }

    companion object {
        private const val SEGMENTER_INPUT_FLOATS = 3 * BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE

        /** Labels from which the §3.2 one-shot CPU recovery may fire. CPU is terminal. */
        private val ACCELERATED_PROVIDER_LABELS = setOf("qnn_htp", "qnn_gpu", "nnapi")
    }
}
