package eu.kanade.translation.diagnostics

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Debug-build trace writer. I/O runs on a bounded queue so tracing never waits on storage. */
internal class TranslationTraceFileSink internal constructor(
    private val directory: File,
) : TranslationTraceSink, Closeable {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, TRACE_DIRECTORY))

    private val currentFile = File(directory, TRACE_FILE)
    private val previousFile = File(directory, "$TRACE_FILE.1")
    private val droppedLines = AtomicLong()
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        ThreadFactory { runnable ->
            Thread(runnable, "translation-trace-file").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    override fun log(priority: Int, line: String) {
        enqueue(line)
    }

    fun writeSessionMarker(line: String) {
        enqueue(line)
    }

    /** A queue barrier used by debug-source tests to read the sink deterministically. */
    internal fun flushForTesting() {
        writer.submit {}.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        writer.shutdown()
        if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
            writer.shutdownNow()
            writer.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun prefixTimestamp(line: String): String {
        return try {
            if (line.startsWith("ts=")) {
                line
            } else {
                val dateStr = dateFormat.get()?.format(Date()) ?: System.currentTimeMillis().toString()
                "ts=$dateStr $line"
            }
        } catch (_: Throwable) {
            line
        }
    }

    private fun enqueue(line: String) {
        val lineToWrite = prefixTimestamp(line)
        try {
            writer.execute {
                try {
                    val dropped = droppedLines.getAndSet(0)
                    if (dropped > 0) {
                        appendDirect(prefixTimestamp("schema=translation_trace_v1 event=trace_lines_dropped count=$dropped"))
                    }
                    appendDirect(lineToWrite)
                } catch (_: Throwable) {
                    droppedLines.incrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            droppedLines.incrementAndGet()
        }
    }

    @Throws(IOException::class)
    private fun appendDirect(line: String) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create debug trace directory")
        }
        val bytes = (line.replace('\n', ' ').replace('\r', ' ') + "\n").toByteArray(StandardCharsets.UTF_8)
        if (currentFile.exists() && currentFile.length() + bytes.size > ROTATE_AT_BYTES) rotate()
        FileOutputStream(currentFile, true).use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    @Throws(IOException::class)
    private fun rotate() {
        if (previousFile.exists() && !previousFile.delete()) {
            throw IOException("Could not remove previous debug trace segment")
        }
        if (!currentFile.renameTo(previousFile)) {
            // Keep collection fail-open if the filesystem rejects rename.
            FileOutputStream(currentFile, false).use { it.flush() }
        }
    }

    private companion object {
        const val TRACE_DIRECTORY = "translation-trace"
        const val TRACE_FILE = "translation-trace.log"
        const val QUEUE_CAPACITY = 2_048
        const val ROTATE_AT_BYTES = 5L * 1024L * 1024L
    }
}
