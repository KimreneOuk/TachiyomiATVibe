package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.pipeline.TranslationPipeline.Companion.UNKNOWN_SOURCE_FINGERPRINT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class SourceFingerprintCacheTest {

    private fun streamsOf(vararg pages: Pair<String, String>): Map<String, () -> InputStream> =
        pages.associate { (key, content) -> key to { ByteArrayInputStream(content.toByteArray()) } }

    @Test
    fun `repeat lookup reuses one compute`() = runTest {
        val calls = AtomicInteger(0)
        val cache = SourceFingerprintCache(streamsOf("page_1" to "content")) { streamFn ->
            calls.incrementAndGet()
            streamFn().use { "hash_" + it.bufferedReader().readText() }
        }

        cache.get("page_1") shouldBe "hash_content"
        cache.get("page_1") shouldBe "hash_content"
        calls.get() shouldBe 1
    }

    @Test
    fun `concurrent same-key lookups compute once`() = runTest {
        val calls = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = SourceFingerprintCache(streamsOf("page_1" to "content")) { streamFn ->
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            streamFn().use { "hash_shared" }
        }

        // The first caller enters compute and parks on the release gate while
        // holding the mutex.
        val first = async { cache.get("page_1") }
        entered.await()
        // Only now does the second caller start; it must run to the Mutex
        // wait without computing, and both stay incomplete before release.
        val second = async { cache.get("page_1") }
        advanceUntilIdle()

        first.isCompleted shouldBe false
        second.isCompleted shouldBe false
        calls.get() shouldBe 1

        release.complete(Unit)
        advanceUntilIdle()

        first.await() shouldBe "hash_shared"
        second.await() shouldBe "hash_shared"
        calls.get() shouldBe 1
    }

    @Test
    fun `distinct keys serialize and a cancelled waiter never blocks the compute`() = runTest {
        val order = StringBuilder()
        val startedB = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val cache = SourceFingerprintCache(
            streamsOf("a" to "a", "b" to "b"),
        ) { streamFn ->
            val key = streamFn().use { it.bufferedReader().readText() }
            order.append(key)
            if (key == "b") {
                startedB.complete(Unit)
                releaseB.await()
            }
            "hash_$key"
        }

        val computing = launch { cache.get("b") }
        startedB.await()
        // While b's compute holds the mutex, a distinct-key waiter queues
        // behind it: the misses are deliberately serialized.
        val waitingA = async { cache.get("a") }
        advanceUntilIdle()
        order.toString() shouldBe "b"
        waitingA.isActive shouldBe true
        // A cancelled waiter leaves the mutex wait immediately and never
        // disturbs the in-flight compute.
        waitingA.cancelAndJoin()
        releaseB.complete(Unit)
        advanceUntilIdle()
        computing.join()

        order.toString() shouldBe "b"
        computing.isCompleted shouldBe true
        cache.get("a") shouldBe "hash_a"
        order.toString() shouldBe "ba"
    }

    @Test
    fun `computed null installs UNKNOWN once and never recomputes`() = runTest {
        val calls = AtomicInteger(0)
        val cache = SourceFingerprintCache(streamsOf("page_1" to "content")) { _ ->
            calls.incrementAndGet()
            null
        }

        cache.get("page_1") shouldBe UNKNOWN_SOURCE_FINGERPRINT
        cache.get("page_1") shouldBe UNKNOWN_SOURCE_FINGERPRINT
        calls.get() shouldBe 1
    }

    @Test
    fun `unknown page key returns null without any compute`() = runTest {
        val calls = AtomicInteger(0)
        val cache = SourceFingerprintCache(streamsOf("page_1" to "content")) { _ ->
            calls.incrementAndGet()
            "hash"
        }

        cache.get("no_such_page") shouldBe null
        cache.keys shouldBe setOf("page_1")
        cache.size shouldBe 1
        calls.get() shouldBe 0
    }

    @Test
    fun `ordinary thrown error installs nothing and next lookup retries`() = runTest {
        val calls = AtomicInteger(0)
        val failFirst = AtomicInteger(0)
        val cache = SourceFingerprintCache(streamsOf("page_1" to "content")) { streamFn ->
            if (failFirst.incrementAndGet() == 1) {
                calls.incrementAndGet()
                throw IllegalStateException("transient read failure")
            }
            calls.incrementAndGet()
            streamFn().use { "hash_retry" }
        }

        shouldThrow<IllegalStateException> { cache.get("page_1") }
        calls.get() shouldBe 1
        // Nothing was installed by the thrown error; the retry succeeds.
        cache.get("page_1") shouldBe "hash_retry"
        calls.get() shouldBe 2
        cache.get("page_1") shouldBe "hash_retry"
        calls.get() shouldBe 2
    }

    @Test
    fun `cancelled compute installs nothing and next lookup recomputes`() = runTest {
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = SourceFingerprintCache(streamsOf("page_1" to "content")) { streamFn ->
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            streamFn().use { "hash_after_cancel" }
        }

        val caller = launch { cache.get("page_1") }
        started.await()
        caller.cancelAndJoin()
        release.complete(Unit)
        advanceUntilIdle()

        calls.get() shouldBe 1
        cache.get("page_1") shouldBe "hash_after_cancel"
        calls.get() shouldBe 2
    }

    @Test
    fun `compute that returns after caller cancellation is not installed`() = runTest {
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The fake compute resumes inside NonCancellable and returns normally
        // even though the caller is already cancelled — the cache's own
        // post-compute caller-activity checkpoint must keep the result from
        // being installed as run evidence.
        val cache = SourceFingerprintCache(streamsOf("page_1" to "content")) { streamFn ->
            calls.incrementAndGet()
            started.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                streamFn().use { "late_hash" }
            }
        }

        val caller = async {
            cache.get("page_1")
        }
        advanceUntilIdle()
        started.isCompleted shouldBe true
        caller.cancel()
        release.complete(Unit)
        advanceUntilIdle()

        shouldThrow<CancellationException> { caller.await() }
        calls.get() shouldBe 1
        // Nothing was installed for the cancelled caller; a fresh caller
        // recomputes and installs normally.
        cache.get("page_1") shouldBe "late_hash"
        calls.get() shouldBe 2
    }

    @Test
    fun `resolveAll maps resolved evidence with UNKNOWN for computed null`() = runTest {
        val cache = SourceFingerprintCache(
            streamsOf("persisted" to "p", "nullpage" to "n"),
        ) { streamFn ->
            streamFn().use { stream ->
                val text = stream.bufferedReader().readText()
                if (text == "n") null else "hash_$text"
            }
        }

        val evidence = cache.resolveAll(listOf("persisted", "nullpage"))
        evidence shouldBe mapOf(
            "persisted" to "hash_p",
            "nullpage" to UNKNOWN_SOURCE_FINGERPRINT,
        )
        cache.resolveAll(emptyList()) shouldBe emptyMap()
    }
}
