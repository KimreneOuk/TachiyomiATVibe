package eu.kanade.translation.engines.runtime.onnx

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks and negotiates model-level accelerator compatibility.
 *
 * Even when hardware probing confirms that Qualcomm QNN HTP or Adreno GPU is functional
 * on the physical device, specific models may have operators, dynamic quantization patterns,
 * or tiling dimensions incompatible with that accelerator backend.
 *
 * This engine tracks 4 compatibility states per model and execution provider:
 * - UNKNOWN: Model has not yet been attempted on this EP in this process.
 * - SUPPORTED: Model creates and executes successfully on this EP.
 * - UNSUPPORTED: Model graph contains operators rejected by EP (e.g. CPU EP fallback
 *   disabled violation or QNN error 6020/6033). Subsequent calls automatically route
 *   to CPU/XNNPACK without incurring multi-second stalls or crashes.
 * - TEMPORARY_FAILURE: Model encountered an ephemeral failure (e.g. FastRPC Subsystem Restart / SSR).
 *   Allows one session recreation attempt before demoting to UNSUPPORTED.
 */
object ModelRoutingEngine {

    private const val TAG = "[model_routing]"

    enum class Status {
        UNKNOWN,
        SUPPORTED,
        UNSUPPORTED,
        TEMPORARY_FAILURE,
    }

    private val modelStatusMap = ConcurrentHashMap<String, Status>()
    private val ssrRetryCount = ConcurrentHashMap<String, Int>()

    fun resolveModelId(modelPathOrName: String): String {
        val normalized = modelPathOrName.replace('\\', '/')
        val modelsIdx = normalized.lastIndexOf("/models/")
        if (modelsIdx != -1) {
            return normalized.substring(modelsIdx + "/models/".length)
        }
        val file = java.io.File(modelPathOrName)
        val parent = file.parentFile
        return when {
            parent != null && parent.name.isNotEmpty() && (file.name == "inference.onnx" || parent.name == "det") -> {
                val grandParent = parent.parentFile
                if (grandParent != null && grandParent.name.isNotEmpty() && parent.name == "det") {
                    "${grandParent.name}/${parent.name}/${file.name}"
                } else {
                    "${parent.name}/${file.name}"
                }
            }
            else -> file.name
        }
    }

    private fun makeKey(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute): String {
        val id = resolveModelId(modelName)
        return "${id}_${route.name}"
    }

    fun getStatus(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute): Status {
        return modelStatusMap[makeKey(modelName, route)] ?: Status.UNKNOWN
    }

    fun isSupported(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute): Boolean {
        val status = getStatus(modelName, route)
        return status == Status.SUPPORTED || status == Status.UNKNOWN
    }

    /**
     *  Phase 5 (plan §3.3): the coherent accelerator ATTEMPT gate used by
     * session-creation routing. Returns true for [Status.UNKNOWN] and
     * [Status.SUPPORTED], and for [Status.TEMPORARY_FAILURE] exactly while the
     * documented single recreation attempt is still available —
     * [recordFailure] increments the SSR retry counter to 1 when it grants
     * that recreation, so the counter permits while it is <= 1; a second
     * consecutive failure demotes the model to UNSUPPORTED, where this gate
     * returns false. [isSupported] keeps its strict proven-or-unattempted
     * meaning for callers with their own recovery policy (AOT-GAN); this gate
     * exists so the TEMPORARY_FAILURE recreation that [recordFailure] promises
     * can actually happen — previously the attempt gate excluded
     * TEMPORARY_FAILURE, making that promise unreachable.
     */
    fun isAcceleratorAttemptAllowed(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute): Boolean {
        val id = resolveModelId(modelName)
        return when (val status = getStatus(id, route)) {
            Status.UNSUPPORTED -> false
            Status.TEMPORARY_FAILURE -> ssrRetryCount.getOrDefault(makeKey(id, route), 0) <= 1
            Status.UNKNOWN, Status.SUPPORTED -> true
        }
    }

    fun markSupported(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute) {
        val id = resolveModelId(modelName)
        val key = makeKey(id, route)
        modelStatusMap[key] = Status.SUPPORTED
        ssrRetryCount.remove(key)
        logcat(LogPriority.INFO) { "$TAG Model '$id' marked SUPPORTED on route $route" }
    }

    /**
     *  Phase 5 (plan §3.3, amendment §10.8): records one successful
     * EXECUTED inference on [route]. SUPPORTED now means the model both
     * created AND executed — session creation alone must not mark support
     * (the creation-time [markSupported] call was removed from
     * OnnxRuntimeProvider; AOT-GAN keeps its own proven-route telemetry).
     * Clears the SSR retry counter like [markSupported].
     */
    fun recordSuccessfulInference(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute) {
        val id = resolveModelId(modelName)
        val key = makeKey(id, route)
        modelStatusMap[key] = Status.SUPPORTED
        ssrRetryCount.remove(key)
        logcat(LogPriority.INFO) { "$TAG Model '$id' proven SUPPORTED on route $route (inference executed)" }
    }

    fun markUnsupported(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute, reason: String) {
        val id = resolveModelId(modelName)
        val key = makeKey(id, route)
        modelStatusMap[key] = Status.UNSUPPORTED
        logcat(LogPriority.WARN) { "$TAG Model '$id' marked UNSUPPORTED on route $route: $reason" }
    }

    /**
     * Records a runtime exception. If it is an SSR (Subsystem Restart) or ephemeral FastRPC error,
     * marks TEMPORARY_FAILURE and permits one retry. If it is an operator mismatch or repeated failure,
     * marks UNSUPPORTED.
     *
     *  Phase 5 (plan §3.3): a QNN graph execute failure with error code
     * 1100 is a hard model-route EXECUTION failure — demoted to UNSUPPORTED,
     * never an SSR retry — even when the message text lacks "ENGINE_ERROR".
     * This is checked BEFORE the SSR heuristic because an OrtException built
     * from OrtErrorCode.ORT_ENGINE_ERROR leaks "ENGINE_ERROR" into the
     * message text and used to misclassify real 1100 execute failures as a
     * recoverable subsystem restart.
     */
    fun recordFailure(modelName: String, route: HardwareDiscoveryEngine.HardwareRoute, error: Throwable): Boolean {
        val id = resolveModelId(modelName)
        val key = makeKey(id, route)
        val msg = error.message.orEmpty()

        // QNN graph execute error code 1100 (e.g. "QNN graph execute error.
        // Error code: 1100"): hard accelerated-route failure for this model.
        if (error is ai.onnxruntime.OrtException && QNN_GRAPH_EXECUTE_1100.containsMatchIn(msg)) {
            markUnsupported(id, route, "QNN graph execute error 1100")
            return false // cannot retry on this route, fallback to CPU
        }

        // Check for FastRPC Subsystem Restart (SSR) / ENGINE_ERROR
        val isSsr = msg.contains("ENGINE_ERROR", ignoreCase = true) ||
            msg.contains("subsystem restart", ignoreCase = true) ||
            msg.contains("remote error", ignoreCase = true) ||
            msg.contains("FastRPC", ignoreCase = true)

        if (isSsr) {
            val retries = ssrRetryCount.getOrDefault(key, 0)
            if (retries < 1) {
                ssrRetryCount[key] = retries + 1
                modelStatusMap[key] = Status.TEMPORARY_FAILURE
                logcat(LogPriority.WARN, error) {
                    "$TAG FastRPC Subsystem Restart (SSR) detected for '$id' on $route (attempting recovery)"
                }
                return true // can retry
            }
        }

        markUnsupported(id, route, msg)
        return false // cannot retry on this route, fallback to CPU
    }

    fun reset() {
        modelStatusMap.clear()
        ssrRetryCount.clear()
    }

    /** Matches the QNN graph execute failure surfaced by ORT on HTP: "Error code: 1100". */
    private val QNN_GRAPH_EXECUTE_1100 = Regex("(?i)error\\s*code\\s*[:=]?\\s*1100\\b")
}
