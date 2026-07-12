package eu.kanade.translation.runtime.onnx

import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object DeviceCapability {

    private val socManufacturer: String by lazy { readSocManufacturer() }
    private val socModel: String by lazy { readSocModel() }

    val isQualcommSnapdragon: Boolean by lazy {
        socManufacturer.contains("qualcomm", ignoreCase = true) ||
            socManufacturer.equals("qcom", ignoreCase = true)
    }

    fun describe(): String =
        "socManufacturer=$socManufacturer socModel=$socModel sdk=${Build.VERSION.SDK_INT} " +
            "qualcomm=$isQualcommSnapdragon"

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
