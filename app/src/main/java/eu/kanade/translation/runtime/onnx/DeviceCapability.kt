package eu.kanade.translation.runtime.onnx

import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object DeviceCapability {

    private val socManufacturer: String by lazy { readSocManufacturer() }
    private val socModel: String by lazy { readSocModel() }

    val isQualcommSnapdragon: Boolean by lazy {
        socManufacturer.contains("qualcomm", ignoreCase = true) ||
            socManufacturer.contains("qcom", ignoreCase = true) ||
            socManufacturer.contains("qti", ignoreCase = true) ||
            Build.HARDWARE.orEmpty().contains("qcom", ignoreCase = true) ||
            Build.BOARD.orEmpty().contains("qcom", ignoreCase = true) ||
            socModel.startsWith("sm", ignoreCase = true) ||
            socModel.startsWith("sdm", ignoreCase = true)
    }

    /**
     * QNN_SOC_MODEL enum value (QnnTypes.h, QAIRT SDK) for this SoC, or null when unknown.
     * ORT 1.27's QNN EP parses soc_model as a plain integer (std::stoi), so string
     * names like "SM8650" throw. P-variants map to their base family (SM8750P -> SM8750).
     * The enum is deprecated upstream and has no entry for SoCs newer than SM8850,
     * so newer chips return null and rely on the htp_arch fallback or QNN autodetection.
     */
    val qnnSocModel: String? by lazy { qnnSocModelFor(socModel) }

    /**
     * HTP arch number for this SoC, or null when it cannot be expressed.
     * ORT 1.27 parses htp_arch as one of 0/68/69/73/75/81. v79 (SM8750) is
     * rejected by the parser (unmerged onnxruntime PR #31638), so SM8750
     * deliberately omits the arch and the probe falls through to the
     * soc_model-only and autodetect combos instead.
     */
    val qnnHtpArch: String? by lazy { qnnHtpArchFor(socModel) }

    internal fun qnnSocModelFor(soc: String): String? =
        when (soc.trim().uppercase().trimEnd('P')) {
            "SDM845" -> "1"
            "SDM835" -> "2"
            "SM8350" -> "30"
            "SM8450" -> "36"
            "SM8475" -> "42"
            "SM8550" -> "43"
            "SM6450" -> "50"
            "SM7435" -> "61"
            "SM7550" -> "64"
            "SM8635" -> "68"
            "SM8650" -> "57"
            "SM8750" -> "69"
            "SM7675" -> "70"
            "SM8735" -> "85"
            "SM8850" -> "87"
            else -> null
        }

    internal fun qnnHtpArchFor(soc: String): String? =
        when (soc.trim().uppercase().trimEnd('P')) {
            "SM8350" -> "68"
            "SM8450", "SM8475" -> "69"
            "SM8550" -> "73"
            "SM8650" -> "75"
            else -> null
        }

    val isProbablyEmulator: Boolean by lazy {
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("sdk_gphone") ||
            model.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    fun describe(): String =
        "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "abis=${Build.SUPPORTED_ABIS.joinToString()} socManufacturer=$socManufacturer " +
            "socModel=$socModel emulator=$isProbablyEmulator qualcomm=$isQualcommSnapdragon"

    private fun readSocManufacturer(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            getSystemProperty("ro.soc.manufacturer") ?: ""
        }.trim()
    } catch (_: Throwable) {
        ""
    }

    private fun readSocModel(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            getSystemProperty("ro.soc.model") ?: ""
        }.trim()
    } catch (_: Throwable) {
        ""
    }

    private fun getSystemProperty(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java)
        method.invoke(null, name) as? String
    } catch (e: Throwable) {
        logcat(LogPriority.WARN) { "Could not read system property $name: ${e.message}" }
        null
    }
}
