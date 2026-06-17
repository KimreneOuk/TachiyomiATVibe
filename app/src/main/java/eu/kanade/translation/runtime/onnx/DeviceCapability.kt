package eu.kanade.translation.runtime.onnx

import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * TachiyomiAT: hardware/SoC capability detection for ONNX Runtime execution-
 * provider selection.
 *
 * Why this exists
 * ---------------
 * The ONNX pipeline (detector + manga-ocr encoder/decoder + AOT inpainting) was
 * previously CPU-only with a hardcoded 2-thread limit. Modern Snapdragon SoCs
 * ship an NPU (the Qualcomm HTP) that can run quantized models far faster and
 * cooler than the CPU — but only via a specific execution provider (QNN for the
 * HTP, or NNAPI which lets Android's driver layer route to whatever accelerator
 * exists). This object answers: "is this device likely to benefit from a non-
 * CPU execution provider, and which one is safe to try?"
 *
 * Detection approach
 * ------------------
 * - Build.SOC_MANUFACTURER / Build.SOC_MODEL (API 31+, Android 12): the primary
 *   signal. Qualcomm SoCs report manufacturer "Qualcomm" and a model like "SM8550"
 *   (Snapdragon 8 Gen 2). We map known model prefixes to a generation.
 * - For API < 31, fall back to reading ro.soc.* system properties (read-only,
 *   no permissions) which carry the same info on most OEM builds.
 * - NNAPI (the safe, built-in EP) is gated on API ≥ 29 (Android 10) since that's
 *   when the NNAPI Execution Provider stabilized in onnxruntime-android.
 *
 * What this does NOT do
 * ---------------------
 * It does not detect the NPU directly — there is no public Android API for that.
 * It infers NPU presence from the SoC model. A device could report a Snapdragon
 * SoC whose NPU is disabled by the OEM; the caller must therefore always wrap
 * EP registration in try/catch and fall back to CPU on failure (see
 * OnnxRuntimeProvider).
 */
object DeviceCapability {

    /** Execution provider to request from ONNX Runtime. */
    enum class EpStrategy { CPU, NNAPI, QNN }

    /** Best-effort Snapdragon generation, or null for non-Qualcomm/unknown. */
    enum class SnapdragonGen { GEN1, GEN2, GEN3, OTHER_QUALCOMM }

    // Lazily computed once per process — Build.* reads never change at runtime.
    private val socManufacturer: String by lazy { readSocManufacturer() }
    private val socModel: String by lazy { readSocModel() }

    val isQualcommSnapdragon: Boolean by lazy {
        // "Qualcomm" (SOC_MANUFACTURER on API 31+) or "qcom"/"qualcomm" on older
        // builds via ro.soc. Case-insensitive contains to tolerate OEM variants.
        socManufacturer.contains("qualcomm", ignoreCase = true) ||
            socManufacturer.equals("qcom", ignoreCase = true)
    }

    /**
     * The Snapdragon generation inferred from the SoC model string, or null if
     * this is not a recognized Qualcomm SoC. Used to decide whether QNN/HTP is
     * worth attempting (only Gen 1+/8-series have a usable NPU for our models).
     */
    val snapdragonGen: SnapdragonGen? by lazy {
        if (!isQualcommSnapdragon) return@lazy null
        // Qualcomm model codes: SM8550 = 8 Gen 3, SM8475 = 8+ Gen 1,
        // SM8450 = 8 Gen 1/2, SM8650 = 8 Gen 3 refresh, SM8750 = 8 Elite/Gen 4.
        // Match by prefix to be tolerant of minor suffixes.
        val m = socModel.uppercase()
        when {
            m.startsWith("SM8750") || m.startsWith("SM8650") || m.startsWith("SM8550") -> SnapdragonGen.GEN3
            m.startsWith("SM8475") || m.startsWith("SM8450") -> SnapdragonGen.GEN2
            m.startsWith("SM8350") || m.startsWith("SM8250") -> SnapdragonGen.GEN1
            m.startsWith("SM8") -> SnapdragonGen.OTHER_QUALCOMM
            else -> null
        }
    }

    /**
     * Whether the device is likely to benefit from the NNAPI Execution Provider.
     * Conservative: NNAPI is in the base onnxruntime-android artifact (no new
     * dependency), so enabling it is low-risk. We gate on API ≥ 29 (stable NNAPI)
     * and rely on the caller's try/catch to fall back to CPU if the device's
     * NNAPI driver is broken (the documented failure mode on some low-end SoCs).
     */
    fun supportsNnapi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Whether the device should be considered for the QNN Execution Provider
     * (Qualcomm HTP/NPU). This requires a special onnxruntime-android-qnn build
     * AND quantized QNN-format models — neither of which we ship yet — so this
     * is purely a capability gate for the future experimental toggle. Returns
     * true only for recognized Snapdragon Gen 2+ SoCs (Gen 1's HTP has limited
     * operator support and is more crash-prone).
     */
    fun supportsQnnCandidate(): Boolean = when (snapdragonGen) {
        SnapdragonGen.GEN2, SnapdragonGen.GEN3, SnapdragonGen.OTHER_QUALCOMM -> true
        else -> false
    }

    /**
     * Choose the default EP strategy for this device under the AUTO preference.
     * AUTO means "do the safe thing": NNAPI where supported, else CPU. QNN is
     * never chosen automatically — it lives behind an explicit experimental flag
     * (see TranslationPreferences.translationExperimentalQnn) and only after the
     * models are converted to QNN format.
     */
    fun defaultStrategy(): EpStrategy = if (supportsNnapi()) EpStrategy.NNAPI else EpStrategy.CPU

    /** Human-readable capability summary for diagnostics logging. */
    fun describe(): String =
        "socManufacturer=$socManufacturer socModel=$socModel sdk=${Build.VERSION.SDK_INT} " +
            "qualcomm=$isQualcommSnapdragon snapGen=$snapdragonGen nnapi=${supportsNnapi()} " +
            "qnnCandidate=${supportsQnnCandidate()}"

    private fun readSocManufacturer(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            getSystemProperty("ro.soc.manufacturer") ?: ""
        }.trim()
    } catch (e: Throwable) {
        ""
    }

    private fun readSocModel(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            getSystemProperty("ro.soc.model") ?: ""
        }.trim()
    } catch (e: Throwable) {
        ""
    }

    /**
     * Read a read-only system property via reflection on android.os.SystemProperties.
     * No permissions required for ro.* properties. Returns null if unavailable
     * (non-Android or restricted context).
     */
    private fun getSystemProperty(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java)
        method.invoke(null, name) as? String
    } catch (e: Throwable) {
        logcat(LogPriority.WARN) { "Could not read system property $name: ${e.message}" }
        null
    }
}
