package eu.kanade.translation.benchmark

import android.os.Debug
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PssSampler(
    private val powerManager: PowerManager,
    private val intervalMs: Long = 75L,
) {

    private val started = AtomicBoolean(false)
    private val samples = mutableListOf<PssSample>()
    private var executor: ScheduledExecutorService? = null
    private var startedAtNanos = 0L

    @Synchronized
    fun start() {
        check(started.compareAndSet(false, true)) { "PSS sampler can only be started once" }
        startedAtNanos = System.nanoTime()
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "paddle-benchmark-pss").apply { isDaemon = true }
        }.also { scheduled ->
            scheduled.scheduleAtFixedRate(
                { sample() },
                0L,
                intervalMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    @Synchronized
    fun stop(): PssSummary {
        if (started.compareAndSet(true, false)) {
            executor?.shutdownNow()
            executor = null
            sample()
        }
        val snapshot = synchronized(samples) { samples.toList() }
        val peak = snapshot.maxByOrNull { it.pssKb }
        return PssSummary(
            intervalMs = intervalMs,
            sampleCount = snapshot.size,
            peakPssKb = peak?.pssKb ?: 0,
            peakJavaHeapBytes = snapshot.maxOfOrNull { it.javaHeapBytes } ?: 0L,
            firstPssKb = snapshot.firstOrNull()?.pssKb,
            lastPssKb = snapshot.lastOrNull()?.pssKb,
            thermalStatusAtPeak = peak?.thermalStatus ?: "unknown",
            samples = snapshot,
        )
    }

    private fun sample() {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val javaHeapBytes = runtime.totalMemory() - runtime.freeMemory()
        val elapsedMs = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
        val sample = PssSample(
            elapsedMs = elapsedMs,
            pssKb = memoryInfo.totalPss,
            javaHeapBytes = javaHeapBytes,
            thermalStatus = ThermalStatus.describe(powerManager),
        )
        synchronized(samples) { samples += sample }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

object ThermalStatus {

    fun describe(powerManager: PowerManager): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return "unavailable_api"
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    fun severity(status: String): Int = when (status) {
        "none" -> 0
        "light" -> 1
        "moderate" -> 2
        "severe" -> 3
        "critical" -> 4
        "emergency" -> 5
        "shutdown" -> 6
        else -> -1
    }
}
