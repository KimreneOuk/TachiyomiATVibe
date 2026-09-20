package eu.kanade.translation.scheduling

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for the thread-safe [ConcurrentHashMap.newKeySet] used by
 * [eu.kanade.translation.pipeline.TranslationPipeline]'s inFlightPageKeys.
 *
 * These verify the data structure guarantees under the actual concurrent access
 * patterns (watchdog, worker finally, closeEngines) without constructing the
 * heavy pipeline object.
 */
class TranslationPipelineConcurrencyTest {

    @Test
    fun `inFlightPageKeys_watchdogAndFinallyConcurrentRemove_safe`() {
        val keys = ConcurrentHashMap.newKeySet<String>()
        // Pre-populate: simulate pages that are mid-flight
        val pageKeys = (1..50).map { "page_$it.jpg" }
        keys.addAll(pageKeys)

        val executor = Executors.newFixedThreadPool(4)
        try {
            val barrier = CyclicBarrier(4)

            // Thread A: watchdog-style removal (onForceRelease)
            // Thread B: worker finally-style removal
            // Thread C & D: more concurrent removals
            val latch = CountDownLatch(4)

            repeat(4) { threadId ->
                executor.submit {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        // Each thread removes all keys concurrently
                        for (key in pageKeys) {
                            keys.remove(key)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(10, TimeUnit.SECONDS)

            // All removes are idempotent; set is empty or consistent
            keys.size shouldBe 0
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `inFlightPageKeys_closeClearConcurrentWithRemove_safe`() {
        val keys = ConcurrentHashMap.newKeySet<String>()
        val pageKeys = (1..200).map { "page_$it.jpg" }
        keys.addAll(pageKeys)

        val executor = Executors.newFixedThreadPool(4)
        try {
            val barrier = CyclicBarrier(4)
            val latch = CountDownLatch(4)

            // Two threads repeatedly clear (simulating closeEngines), two
            // threads repeatedly remove individual keys (simulating worker
            // finally / watchdog).
            repeat(2) {
                executor.submit {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        repeat(20) {
                            // Re-add some keys so clear() has work to do
                            keys.addAll(pageKeys.take(50))
                            keys.clear()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            repeat(2) {
                executor.submit {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        repeat(20) {
                            for (key in pageKeys.take(100)) {
                                keys.remove(key)
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(10, TimeUnit.SECONDS)

            // No exception thrown; set is in a valid state (may be empty or
            // have leftover entries from the race — both are safe outcomes).
            keys.size shouldBe 0
        } finally {
            executor.shutdownNow()
        }
    }
}
