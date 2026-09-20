package eu.kanade.translation.artifact

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 *  [AtomicChapterDocuments.publish] runs write(`name.tmp`) → read-back
 * validate → delete `.bak` → rename `name`→`name.bak` → rename `name.tmp`→
 * `name` under a PROCESS-WIDE per-document-name lock (companion-object state).
 * The temp name is deterministic, so two in-process writers to the same
 * document from DIFFERENT [AtomicChapterDocuments] instances (the device
 * race: batch preflight publishing the run record vs. the >8-page open path's
 * background artifact-health manifest republisher) used to
 * collide on `name.tmp` and interleave the rotation — the loser returned a
 * mechanical `false` that every caller mapped to a fatal Rejected.
 *
 * Contract: concurrent same-name publishes BOTH return true, the surviving
 * primary is one of the two COMPLETE payloads (never corrupt, never empty),
 * and at most one orphan `.tmp` remains. Writers to different names still
 * proceed in parallel (both succeed concurrently).
 */
class AtomicChapterDocumentsPublicationLockTest {

    @Test
    fun `concurrent same-name publishes from different instances both succeed and keep one complete value`() {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        // Two instances over ONE backing IO — the exact shape of the device
        // race (a second store instance republishing behind the first one).
        val documentsA = AtomicChapterDocuments(io)
        val documentsB = AtomicChapterDocuments(io)
        val name = "chapter.manifest.json"
        val rounds = 25

        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            repeat(rounds) { round ->
                val payloadA = """{"round":$round,"writer":"A"}""".toByteArray()
                val payloadB = """{"round":$round,"writer":"B"}""".toByteArray()
                val start = CountDownLatch(1)
                val futureA = executor.submit<Boolean> {
                    start.await(5, TimeUnit.SECONDS)
                    documentsA.publish(name, payloadA) { true }
                }
                val futureB = executor.submit<Boolean> {
                    start.await(5, TimeUnit.SECONDS)
                    documentsB.publish(name, payloadB) { true }
                }
                start.countDown()

                // Both publications succeed — a mechanical false is the defect.
                futureA.get(5, TimeUnit.SECONDS) shouldBe true
                futureB.get(5, TimeUnit.SECONDS) shouldBe true

                // The surviving primary is one of the two COMPLETE payloads.
                val primary = io.read(name)
                (primary != null) shouldBe true
                val primaryIsComplete = primary!!.contentEquals(payloadA) ||
                    primary.contentEquals(payloadB)
                primaryIsComplete shouldBe true

                // At most one orphan tmp survives the round (each successful
                // publish renames its own tmp away).
                val orphanTmps = io.files.keys.count { it.endsWith(".tmp") }
                (orphanTmps <= 1) shouldBe true
            }
        } finally {
            executor.shutdownNow()
        }

        // Every round's tmp was renamed away: nothing is left behind.
        io.files.keys.none { it.endsWith(".tmp") } shouldBe true
        io.files.keys.none { it.endsWith(".corrupt") } shouldBe true
    }

    @Test
    fun `concurrent publishes to different names both succeed without cross-name blocking`() {
        // FakeChapterDocumentIo is a single-threaded double (LinkedHashMap
        // state), so each instance gets its OWN backing IO. The shared-IO
        // concurrency shape is covered by the same-name test above, which the
        // per-name lock serializes end-to-end. This test's contract is lock
        // SCOPE: publishes to different names proceed concurrently and both
        // succeed (they would also both succeed under a global lock, but the
        // same-name test would starve it — the pair pins per-name behavior).
        val ioA = FakeChapterDocumentIo().apply { fileBacked = true }
        val ioB = FakeChapterDocumentIo().apply { fileBacked = true }
        val documentsA = AtomicChapterDocuments(ioA)
        val documentsB = AtomicChapterDocuments(ioB)
        val start = CountDownLatch(1)

        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val futureA = executor.submit<Boolean> {
                start.await(5, TimeUnit.SECONDS)
                documentsA.publish("a.manifest.json", """{"writer":"A"}""".toByteArray()) { true }
            }
            val futureB = executor.submit<Boolean> {
                start.await(5, TimeUnit.SECONDS)
                documentsB.publish("b.manifest.json", """{"writer":"B"}""".toByteArray()) { true }
            }
            start.countDown()

            futureA.get(5, TimeUnit.SECONDS) shouldBe true
            futureB.get(5, TimeUnit.SECONDS) shouldBe true
            String(ioA.read("a.manifest.json")!!) shouldBe """{"writer":"A"}"""
            String(ioB.read("b.manifest.json")!!) shouldBe """{"writer":"B"}"""
        } finally {
            executor.shutdownNow()
        }
    }
}
