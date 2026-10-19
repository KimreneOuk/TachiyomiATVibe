package eu.kanade.translation.persistence

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.GatedReadUniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Phase 7 S2 acceptance probes: the first durable write on a LazyDurable store
 * must SUSPEND while it acquires the artifact-open lock (store mutex →
 * artifact-open lock, never reversed) instead of parking its carrier thread in
 * `runBlocking` under the store mutex.
 *
 * Detection mechanism: a `GatedReadUniFile` parks whichever thread reads the
 * artifact manifest INSIDE the per-artifact open lock until the test opens the
 * gate. While that hold is in flight, a trivial probe coroutine queued on the
 * write's own single-threaded carrier must still run. The pre-S2
 * `runBlocking(Dispatchers.IO)` parked the carrier for the whole gated read,
 * so the probe could never fire; the suspending implementation releases the
 * carrier at the `withContext(Dispatchers.IO)` boundary and the probe
 * completes while the write is still in flight.
 */
class ChapterTranslationStoreSuspendingFirstWriteTest {

    private val artifactFileName = "Chapter 1.json"

    /** The durable manifest document the lazy first write must READ (gated). */
    private val manifestFileName =
        ChapterArtifactLayout.fromArtifactFileName(artifactFileName).manifestFileName

    @TempDir
    lateinit var mangaDir: File

    /** Pre-publishes the artifact document so the lazy first write READS it (gated). */
    private fun seedArtifactDocument(backing: File) {
        val seedRoot = FakeUniFile(parent = null, backing = backing)
        runBlocking {
            val seed = ChapterTranslationStore.lazy(
                artifactParent = seedRoot,
                artifactFileName = artifactFileName,
            )
            seed.updatePageGuarded(
                pageKey = "seed.jpg",
                expected = seed.snapshot("seed.jpg").toPrecondition(),
                description = "s2 seed write",
            ) { PageTranslation(ocrStatus = StageStatus.RUNNING) }
                .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            seed.closeAndFlush()
        }
        File(backing, manifestFileName).isFile shouldBe true
    }

    private fun gatedRoot(
        backing: File,
        beforeGate: CompletableDeferred<Unit>,
        gate: CountDownLatch,
    ): GatedReadUniFile = GatedReadUniFile(
        parent = null,
        backing = backing,
        gatedFileName = manifestFileName,
        beforeGate = beforeGate,
        gate = gate,
    )

    private fun singleThreadCarrier(name: String) =
        Executors.newSingleThreadExecutor { task -> Thread(task, name) }.asCoroutineDispatcher()

    @Test
    fun `lazy first write suspends instead of blocking its carrier thread`() {
        seedArtifactDocument(mangaDir)
        val beforeGate = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val root = gatedRoot(mangaDir, beforeGate, gate)
        val store = ChapterTranslationStore.lazy(
            artifactParent = root,
            artifactFileName = artifactFileName,
        )
        val carrier = singleThreadCarrier("s2-carrier-liveness")
        try {
            runBlocking {
                val probeFired = CompletableDeferred<Unit>()
                val writeResult = CompletableDeferred<ChapterTranslationStore.PatchResult>()
                launch(carrier) {
                    writeResult.complete(
                        store.updatePageGuarded(
                            pageKey = "p1.jpg",
                            expected = store.snapshot("p1.jpg").toPrecondition(),
                            description = "s2 first write",
                        ) { PageTranslation(ocrStatus = StageStatus.RUNNING) },
                    )
                }
                launch(carrier) { probeFired.complete(Unit) }

                // The write is now parked inside the gated manifest read, which
                // runs inside the artifact-open lock under the store mutex.
                withTimeout(10_000) { beforeGate.await() }
                // The carrier thread stayed free: the probe queued BEHIND the
                // write on the same single thread must have run.
                withTimeout(10_000) { probeFired.await() }
                // The write cannot finish until the gate opens, so this proves
                // the probe ran while the write was still in flight.
                writeResult.isCompleted shouldBe false

                gate.countDown()
                val result = withTimeout(15_000) { writeResult.await() }
                result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
                store.closeAndFlush()
            }
        } finally {
            gate.countDown()
            carrier.close()
        }
    }

    @Test
    fun `concurrent suspend open and first write on the same artifact do not deadlock`() {
        seedArtifactDocument(mangaDir)
        val beforeGate = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val root = gatedRoot(mangaDir, beforeGate, gate)
        val store = ChapterTranslationStore.lazy(
            artifactParent = root,
            artifactFileName = artifactFileName,
        )
        val carrier = singleThreadCarrier("s2-carrier-open-race")
        try {
            runBlocking {
                // The suspend open takes the artifact-open lock first and parks
                // inside the gated manifest read while HOLDING that lock.
                val opened = async(Dispatchers.IO) {
                    ChapterTranslationStore.openArtifactSuspend(root, artifactFileName)
                }
                withTimeout(10_000) { beforeGate.await() }

                // The first write must now wait for the open lock as a
                // coroutine. Its carrier stays live (probe below) — the pre-S2
                // runBlocking parked the carrier here and starved the probe.
                val probeFired = CompletableDeferred<Unit>()
                val writeResult = CompletableDeferred<ChapterTranslationStore.PatchResult>()
                launch(carrier) {
                    writeResult.complete(
                        store.updatePageGuarded(
                            pageKey = "p1.jpg",
                            expected = store.snapshot("p1.jpg").toPrecondition(),
                            description = "s2 concurrent first write",
                        ) { PageTranslation(ocrStatus = StageStatus.RUNNING) },
                    )
                }
                launch(carrier) { probeFired.complete(Unit) }
                withTimeout(10_000) { probeFired.await() }
                writeResult.isCompleted shouldBe false

                gate.countDown()
                val result = withTimeout(15_000) { writeResult.await() }
                result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

                // Both sides finished: the reversed-order deadlock window stays
                // closed, and a fresh open observes the first write durably.
                opened.await().closeAndFlush()
                val reopened = ChapterTranslationStore.openArtifactSuspend(root, artifactFileName)
                reopened.snapshot("p1.jpg").page.shouldNotBeNull()
                reopened.closeAndFlush()
                store.closeAndFlush()
            }
        } finally {
            gate.countDown()
            carrier.close()
        }
    }
}
