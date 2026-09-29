package eu.kanade.translation.diagnostics

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/**
 * Explicit, non-gating device measurements. The normal AndroidJUnitRunner
 * excludes this class through its [ManualMeasurementBench] annotation filter.
 */
@RunWith(AndroidJUnit4::class)
@ManualMeasurementBench
class JournalMicrobenchInstrumentedTest {

    @Test
    fun measureAppPrivateJournalStorageCosts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.filesDir
        val record = ByteArray(2 * KIB) { index -> (index % 251).toByte() }
        val thermalStart = thermalStatus(context)
        val batteryStart = batteryPercent(context)

        val throughput2k = appendThroughput(File(directory, "journal-bench-append-2k.dat"), record, APPEND_COUNT)
        val throughput8k = appendThroughput(
            File(directory, "journal-bench-append-8k.dat"),
            ByteArray(8 * KIB) { index -> (index % 251).toByte() },
            APPEND_COUNT,
        )
        val fsyncSamplesUs = fsyncSamples(File(directory, "journal-bench-fsync.dat"), record, SYNC_SAMPLE_COUNT)
        val directoryFsyncSamplesUs = directoryFsyncSamples(directory, SYNC_SAMPLE_COUNT)
        val freeIntervalSamplesUs = freeOnlyIntervalSamples(
            File(directory, "journal-bench-free-interval.dat"),
            ByteArray(256) { index -> (index % 251).toByte() },
        )

        val resultFile = File(directory, RESULTS_FILE)
        resultFile.writeText(
            buildString {
                appendLine("JOURNAL_MICROBENCH_V1")
                appendLine("deviceModel=${Build.MANUFACTURER}_${Build.MODEL}")
                appendLine("androidVersion=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                appendLine("thermalStart=$thermalStart thermalEnd=${thermalStatus(context)}")
                appendLine("batteryStartPct=$batteryStart batteryEndPct=${batteryPercent(context)}")
                appendLine("buildCommit=${BuildConfig.COMMIT_SHA}")
                appendLine("append2kBytesPerSecond=${throughput2k.roundToLong()} records=$APPEND_COUNT recordBytes=${2 * KIB}")
                appendLine("append8kBytesPerSecond=${throughput8k.roundToLong()} records=$APPEND_COUNT recordBytes=${8 * KIB}")
                appendLine("fsyncSamples=$SYNC_SAMPLE_COUNT ${distribution("fsyncUs", fsyncSamplesUs)}")
                appendLine("directoryFsyncSamples=$SYNC_SAMPLE_COUNT ${distribution("directoryFsyncUs", directoryFsyncSamplesUs)}")
                appendLine("freeOnlyIntervalMs=1000 samples=${freeIntervalSamplesUs.size} ${distribution("syncGroupUs", freeIntervalSamplesUs)}")
            },
        )

        // Only the sample count is a structural assertion; measured timings are data, never a gate.
        assertEquals(SYNC_SAMPLE_COUNT, fsyncSamplesUs.size)
        assertEquals(SYNC_SAMPLE_COUNT, directoryFsyncSamplesUs.size)
        assertEquals(FREE_INTERVAL_COUNT, freeIntervalSamplesUs.size)
    }

    private fun appendThroughput(file: File, record: ByteArray, count: Int): Double {
        val elapsedSeconds = RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            val startedAt = System.nanoTime()
            repeat(count) { output.write(record) }
            val elapsed = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
            output.fd.sync()
            elapsed
        }
        return record.size.toDouble() * count / elapsedSeconds
    }

    private fun fsyncSamples(file: File, record: ByteArray, count: Int): List<Long> =
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            List(count) {
                output.write(record)
                val startedAt = System.nanoTime()
                output.fd.sync()
                (System.nanoTime() - startedAt) / NANOS_PER_MICROSECOND
            }
        }

    private fun directoryFsyncSamples(directory: File, count: Int): List<Long> {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY or O_DIRECTORY_FLAG,
            0,
        )
        return try {
            List(count) {
                val startedAt = System.nanoTime()
                Os.fsync(descriptor)
                (System.nanoTime() - startedAt) / NANOS_PER_MICROSECOND
            }
        } finally {
            Os.close(descriptor)
        }
    }

    private fun freeOnlyIntervalSamples(file: File, record: ByteArray): List<Long> {
        val thread = HandlerThread("journal-bench-free-sync").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val samples = mutableListOf<Long>()
        val output = RandomAccessFile(file, "rw").apply { setLength(0) }
        val recordStart = SystemClock.elapsedRealtime()
        var lastSyncAt = recordStart

        val appendUntilSync = object : Runnable {
            override fun run() {
                try {
                    output.write(record)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSyncAt >= FREE_INTERVAL_MS) {
                        val startedAt = System.nanoTime()
                        output.fd.sync()
                        samples += (System.nanoTime() - startedAt) / NANOS_PER_MICROSECOND
                        lastSyncAt = SystemClock.elapsedRealtime()
                        if (samples.size == FREE_INTERVAL_COUNT) {
                            finished.countDown()
                            return
                        }
                    }
                    handler.postDelayed(this, APPEND_CADENCE_MS)
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                    finished.countDown()
                }
            }
        }

        try {
            handler.post(appendUntilSync)
            finished.await()
            failure.get()?.let { throw AssertionError("Free-only interval sample failed", it) }
            return samples.toList()
        } finally {
            handler.removeCallbacks(appendUntilSync)
            thread.quitSafely()
            thread.join()
            output.close()
        }
    }

    private fun distribution(name: String, samples: List<Long>): String {
        val sorted = samples.sorted()
        return "$name.median=${sorted[sorted.size / 2]} $name.p10=${sorted[percentileIndex(sorted.size, 0.10)]} " +
            "$name.p90=${sorted[percentileIndex(sorted.size, 0.90)]}"
    }

    private fun percentileIndex(size: Int, percentile: Double): Int = (percentile * (size - 1)).toInt()

    private fun thermalStatus(context: android.content.Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported"
        val manager = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager ?: return "unknown"
        return when (manager.currentThermalStatus) {
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

    private fun batteryPercent(context: android.content.Context): String {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?: return "unknown"
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return "unknown"
        return ((level * 100f) / scale).toInt().toString()
    }

    private companion object {
        private const val O_DIRECTORY_FLAG = 0x10000 // bionic O_DIRECTORY, hidden from public SDK
        const val KIB = 1024
        const val APPEND_COUNT = 1_024
        const val SYNC_SAMPLE_COUNT = 120
        const val FREE_INTERVAL_COUNT = 5
        const val FREE_INTERVAL_MS = 1_000L
        const val APPEND_CADENCE_MS = 100L
        const val RESULTS_FILE = "journal-microbench-results.txt"
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}
