package eu.kanade.translation.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import eu.kanade.translation.runtime.onnx.HardwareDiscoveryEngine
import java.io.File
import java.security.MessageDigest

object BenchmarkDeviceMetadata {

    fun collectStart(context: Context): Snapshot {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val powerManager = context.getSystemService(PowerManager::class.java)
        return Snapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else "unavailable_api",
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unavailable_api",
            androidApi = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = memoryInfo.totalMem,
            memoryClassMb = activityManager?.memoryClass ?: 0,
            largeMemoryClassMb = activityManager?.largeMemoryClass ?: 0,
            packageName = context.packageName,
            packageVersionName = context.packageVersionName(),
            packageVersionCode = context.packageVersionCode(),
            requestedRoute = HardwareDiscoveryEngine.activeRoute.name,
            thermalStatus = powerManager?.let(ThermalStatus::describe) ?: "unavailable",
        )
    }

    fun finish(start: Snapshot, context: Context): DeviceMetadata {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val endThermal = powerManager?.let(ThermalStatus::describe) ?: "unavailable"
        val maxThermal = listOf(start.thermalStatus, endThermal)
            .maxByOrNull(ThermalStatus::severity)
            ?: "unknown"
        return DeviceMetadata(
            manufacturer = start.manufacturer,
            model = start.model,
            device = start.device,
            socManufacturer = start.socManufacturer,
            socModel = start.socModel,
            androidApi = start.androidApi,
            release = start.release,
            primaryAbi = start.primaryAbi,
            supportedAbis = start.supportedAbis,
            totalRamBytes = start.totalRamBytes,
            memoryClassMb = start.memoryClassMb,
            largeMemoryClassMb = start.largeMemoryClassMb,
            packageName = start.packageName,
            packageVersionName = start.packageVersionName,
            packageVersionCode = start.packageVersionCode,
            thermalStatusAtStart = start.thermalStatus,
            thermalStatusAtEnd = endThermal,
            thermalStatusMax = maxThermal,
        )
    }

    data class Snapshot(
        val manufacturer: String,
        val model: String,
        val device: String,
        val socManufacturer: String,
        val socModel: String,
        val androidApi: Int,
        val release: String,
        val primaryAbi: String,
        val supportedAbis: List<String>,
        val totalRamBytes: Long,
        val memoryClassMb: Int,
        val largeMemoryClassMb: Int,
        val packageName: String,
        val packageVersionName: String,
        val packageVersionCode: Long,
        val requestedRoute: String,
        val thermalStatus: String,
    )

    private fun Context.packageVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("unknown")

    private fun Context.packageVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)
}

object BenchmarkFileHasher {

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun hashIfPresent(file: File): String = if (file.isFile) sha256(file) else "missing"
}

object BenchmarkProviderLibraryCollector {

    fun collect(context: Context): Map<String, String> {
        val directory = File(context.applicationInfo.nativeLibraryDir)
        return directory.listFiles()
            .orEmpty()
            .filter { file ->
                file.name.contains("onnx", ignoreCase = true) ||
                    file.name.contains("qnn", ignoreCase = true) ||
                    file.name.contains("xnn", ignoreCase = true) ||
                    file.name.contains("nnapi", ignoreCase = true)
            }
            .sortedBy { it.name }
            .associate { it.name to BenchmarkFileHasher.hashIfPresent(it) }
    }
}
