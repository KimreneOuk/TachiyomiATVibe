package eu.kanade.translation.diagnostics

import eu.kanade.tachiyomi.BuildConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32

/**
 * Manual replay-cost scaffold for the pre-journal-integration phase. The
 * synthetic record format measures full framed-file replay, not the future
 * production journal implementation.
 */
@Tag("manual-bench")
class SyntheticJournalReplayBenchTest {

    @field:TempDir
    lateinit var directory: File

    @Test
    @EnabledIfSystemProperty(named = "measurement.replayBench", matches = "true")
    fun synthetic200PageJournalFullReplayBenchmark() {
        val journal = File(directory, "synthetic-200-page.journal")
        writeSyntheticJournal(journal, pageCount = PAGE_COUNT)

        // One unrecorded pass warms filesystem and class-loading paths.
        replay(journal).size shouldBe PAGE_COUNT
        val samplesMs = List(SAMPLE_COUNT) {
            val startedAt = System.nanoTime()
            val pages = replay(journal)
            val elapsedNanos = System.nanoTime() - startedAt
            pages.size shouldBe PAGE_COUNT
            elapsedNanos / NANOS_PER_MS.toDouble()
        }.sorted()

        println(
            "JOURNAL_REPLAY_BENCH pages=$PAGE_COUNT bytes=${journal.length()} buildCommit=${BuildConfig.COMMIT_SHA} " +
                "hostOs=${System.getProperty("os.name")} " +
                "samples=$SAMPLE_COUNT medianMs=${samplesMs[SAMPLE_COUNT / 2].formatMillis()} " +
                "p10Ms=${samplesMs[percentileIndex(0.10)].formatMillis()} " +
                "p90Ms=${samplesMs[percentileIndex(0.90)].formatMillis()}",
        )
    }

    private fun writeSyntheticJournal(file: File, pageCount: Int) {
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(file.toPath()))).use { output ->
            output.writeInt(MAGIC)
            repeat(pageCount) { pageIndex ->
                val payload = ByteArray(PAYLOAD_BYTES) { offset -> ((pageIndex + offset) % 251).toByte() }
                val checksum = CRC32().apply { update(payload) }.value
                output.writeInt(payload.size)
                output.write(payload)
                output.writeLong(checksum)
            }
        }
    }

    private fun replay(file: File): List<Int> = DataInputStream(BufferedInputStream(Files.newInputStream(file.toPath()))).use { input ->
        input.readInt() shouldBe MAGIC
        buildList {
            while (true) {
                val size = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                require(size == PAYLOAD_BYTES) { "Unexpected synthetic page size: $size" }
                val payload = ByteArray(size)
                input.readFully(payload)
                val expectedCrc = input.readLong()
                val actualCrc = CRC32().apply { update(payload) }.value
                require(expectedCrc == actualCrc) { "Synthetic journal checksum mismatch" }
                add(payload.first().toInt() and 0xff)
            }
        }
    }

    private fun percentileIndex(fraction: Double): Int = (fraction * (SAMPLE_COUNT - 1)).toInt()

    private fun Double.formatMillis(): String = "%.3f".format(java.util.Locale.ROOT, this)

    private companion object {
        const val MAGIC = 0x4D4A4E31
        const val PAGE_COUNT = 200
        const val PAYLOAD_BYTES = 2_048
        const val SAMPLE_COUNT = 5
        const val NANOS_PER_MS = 1_000_000L
    }
}
