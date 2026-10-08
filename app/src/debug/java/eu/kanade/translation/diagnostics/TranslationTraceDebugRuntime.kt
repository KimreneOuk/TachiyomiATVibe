package eu.kanade.translation.diagnostics

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

internal data class BufferedTraceRecord(
    val line: String,
    val receivedAtNanos: Long,
)

/** Lock-free, bounded diagnostic tail. Readers only take a weakly consistent snapshot. */
internal class TranslationTraceBuffer : TranslationTraceSink {
    private val records = ConcurrentLinkedDeque<BufferedTraceRecord>()
    private val size = AtomicInteger()

    override fun log(priority: Int, line: String) {
        records.addLast(BufferedTraceRecord(line, SystemClock.elapsedRealtimeNanos()))
        if (size.incrementAndGet() > CAPACITY && records.pollFirst() != null) size.decrementAndGet()
    }

    fun snapshot(): List<BufferedTraceRecord> = records.toList()

    private companion object {
        const val CAPACITY = 512
    }
}

private class DebugTraceFanOutSink(
    private val buffer: TranslationTraceBuffer,
    private val fileSink: TranslationTraceFileSink,
) : TranslationTraceSink {
    override fun log(priority: Int, line: String) {
        buffer.log(priority, line)
        fileSink.log(priority, line)
        try {
            Log.println(priority, "TachiyomiAT.Translation", line)
        } catch (_: Throwable) {
            // Fail open
        }
    }
}

/** Debug-only startup and measurement-session state. */
internal object TranslationTraceDebugRuntime {
    @Volatile
    var buffer: TranslationTraceBuffer? = null
        private set

    @Volatile
    private var fileSink: TranslationTraceFileSink? = null

    private val sessionLifecycle = TranslationMeasurementSessionLifecycle<MeasurementSession>()

    @Synchronized
    fun install(context: Context) {
        if (buffer != null) return
        val appContext = context.applicationContext
        val traceBuffer = TranslationTraceBuffer()
        val traceFileSink = TranslationTraceFileSink(appContext)
        buffer = traceBuffer
        fileSink = traceFileSink
        TranslationPipelineDiagnostics.sink = DebugTraceFanOutSink(traceBuffer, traceFileSink)
    }

    @Synchronized
    fun startSession(context: Context, intent: Intent) {
        val session = MeasurementSession(
            id = UUID.randomUUID().toString().replace('-', '_'),
            startedAt = utcTimestamp(),
            deviceModel = safeToken("${Build.MANUFACTURER}_${Build.MODEL}"),
            androidVersion = safeToken(Build.VERSION.RELEASE ?: "unknown"),
            sdk = Build.VERSION.SDK_INT,
            thermalStart = thermalStatus(context),
            batteryStart = batteryPercent(context),
            engine = safeToken(intent.getStringExtra(EXTRA_ENGINE).orEmpty()).ifBlank { "unknown" },
            chapterId = opaqueChapterId(intent.getStringExtra(EXTRA_CHAPTER_ID).orEmpty()),
            pageCount = intent.getIntExtra(EXTRA_PAGE_COUNT, -1).takeIf { it >= 0 }?.toString() ?: "unknown",
        )
        sessionLifecycle.start(session) { previous -> writeSessionEnd(context, previous, "restarted") }
        fileSink?.writeSessionMarker(session.toStartLine())
    }

    @Synchronized
    fun endSession(context: Context) {
        sessionLifecycle.end { session -> writeSessionEnd(context, session, "complete") }
    }

    private fun writeSessionEnd(context: Context, session: MeasurementSession, outcome: String) {
        fileSink?.writeSessionMarker(
            session.toEndLine(
                timestamp = utcTimestamp(),
                thermalEnd = thermalStatus(context),
                batteryEnd = batteryPercent(context),
                outcome = outcome,
            ),
        )
    }

    private data class MeasurementSession(
        val id: String,
        val startedAt: String,
        val deviceModel: String,
        val androidVersion: String,
        val sdk: Int,
        val thermalStart: String,
        val batteryStart: String,
        val engine: String,
        val chapterId: String,
        val pageCount: String,
    ) {
        fun toStartLine(): String =
            "schema=translation_trace_v1 event=measurement_session_start session=$id " +
                "timestamp=$startedAt deviceModel=$deviceModel androidVersion=$androidVersion sdk=$sdk " +
                "thermalStart=$thermalStart thermalEnd=none batteryStartPct=$batteryStart batteryEndPct=none " +
                "engine=$engine chapterId=$chapterId pageCount=$pageCount"

        fun toEndLine(timestamp: String, thermalEnd: String, batteryEnd: String, outcome: String): String =
            "schema=translation_trace_v1 event=measurement_session_end session=$id " +
                "timestamp=$timestamp deviceModel=$deviceModel androidVersion=$androidVersion sdk=$sdk " +
                "thermalStart=$thermalStart thermalEnd=$thermalEnd batteryStartPct=$batteryStart " +
                "batteryEndPct=$batteryEnd engine=$engine chapterId=$chapterId pageCount=$pageCount outcome=$outcome"
    }

    private fun thermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported"
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "unknown"
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

    private fun batteryPercent(context: Context): String {
        val status = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "unknown"
        val level = status.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return "unknown"
        return ((level * 100f) / scale).toInt().toString()
    }

    private fun opaqueChapterId(rawChapterId: String): String {
        if (rawChapterId.isBlank()) return "unknown"
        val digest = MessageDigest.getInstance("SHA-256").digest(rawChapterId.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun safeToken(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('_').take(64)

    private fun utcTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).run {
        timeZone = TimeZone.getTimeZone("UTC")
        format(Date())
    }

    private const val EXTRA_ENGINE = "engine"
    private const val EXTRA_CHAPTER_ID = "chapter_id"
    private const val EXTRA_PAGE_COUNT = "page_count"
}

/** Manifest-installed only in the debug variant, before pipeline code can emit trace records. */
class TranslationTraceDebugProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        TranslationTraceDebugRuntime.install(appContext)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** adb-triggerable session delimiter; this receiver exists only in debug builds. */
class TranslationMeasurementSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("phase")) {
            "start" -> TranslationTraceDebugRuntime.startSession(context, intent)
            "end" -> TranslationTraceDebugRuntime.endSession(context)
        }
    }

    companion object {
        const val ACTION = "eu.kanade.translation.action.MEASUREMENT_SESSION"
    }
}
