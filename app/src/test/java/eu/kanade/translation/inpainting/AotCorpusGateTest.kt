package eu.kanade.translation.inpainting

import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Test
import java.io.DataInputStream

/**
 * Tier 3 guard-rejection corpus gate for the fixed-512 AOT model
 * (Plan/AOT_NPU_FIXED512_DESIGN_2026-07-12.md §155-170).
 *
 * This is the Kotlin half of the gate. It runs the REAL [AotOutputGuard] — the
 * production rejector — on pre-computed model outputs and asserts the gate
 * criterion: ZERO new rejections (a page where the dynamic model was accepted
 * but the static-512 model is now rejected is a regression and fails the gate).
 *
 * The model outputs themselves are produced offline by
 * `tools/aot_corpus/emit_corpus_outputs.py` (onnxruntime is Python-only, like
 * `tools/aot_conversion/`). That script does INFERENCE ONLY — no guard math
 * lives in Python, so there is no parity mirror to drift out of sync. This test
 * applies the verdict; the verdict code is the same object prod uses.
 *
 * Corpus layout (one folder per page under src/test/resources/corpus/aot/):
 *   <page>/dynamic_out.bin   raw ARGB int32 (width,height,pixels...) — dynamic model
 *   <page>/static_out.bin    raw ARGB int32 — static-512 model
 *   <page>/mask.bin          raw ARGB int32 (ALPHA8-style, 0xFF000000=erase)
 *   <page>/manifest.json     page metadata (category, expected_box_count)
 *
 * The .bin format is read with java.io.DataInputStream: Android unit tests stub
 * out java.awt, so javax.imageio is unavailable on the test classpath. The .bin
 * carries the exact ARGB IntArray prod feeds to AotOutputGuard, with no decoder.
 *
 * The current corpus is SYNTHETIC (5 pages, see tools/aot_corpus/README.md) —
 * it proves the harness works end-to-end but cannot substitute for the real
 * 20-page corpus the design requires before Wave 5.2 integration.
 */
class AotCorpusGateTest {

    @Test
    fun `gate produces zero new static-512 rejections on corpus`() {
        val pages = corpusPages()
        // Defensive: the corpus must actually exist on the test classpath.
        // An empty result means the resources were not packaged, not that the
        // gate passed on zero pages.
        pages shouldHaveSize EXPECTED_CORPUS_SIZE

        val failures = pages.mapNotNull { page ->
            val mask = readArgbBin("$page/mask.bin")
            val dynamicOut = readArgbBin("$page/dynamic_out.bin")
            val staticOut = readArgbBin("$page/static_out.bin")

            val dynamicRejected = AotOutputGuard.isSuspiciousUniformFill(
                dynamicOut.pixels, mask.pixels, mask.width, mask.height,
            )
            val staticRejected = AotOutputGuard.isSuspiciousUniformFill(
                staticOut.pixels, mask.pixels, mask.width, mask.height,
            )

            // Gate: a NEW rejection is dynamic=accept AND static=reject.
            if (!dynamicRejected && staticRejected) {
                "$page: dynamic=accept but static=REJECT (new rejection)"
            } else {
                null
            }
        }

        if (failures.isNotEmpty()) {
            error("Tier 3 gate FAILED — ${failures.size} new rejection(s):\n  - ${failures.joinToString("\n  - ")}")
        }
    }

    @Test
    fun `corpus pages are 512x512 and outputs match mask dimensions`() {
        // Catches a malformed corpus (wrong resize, mismatched models) before
        // the gate test runs and produces a confusing ArrayIndexOutOfBoundsException.
        val pages = corpusPages()
        pages shouldHaveSize EXPECTED_CORPUS_SIZE

        val bad = pages.mapNotNull { page ->
            val mask = readArgbBin("$page/mask.bin")
            val dynamic = readArgbBin("$page/dynamic_out.bin")
            val static = readArgbBin("$page/static_out.bin")
            val dims = listOf(mask, dynamic, static)
            val wrongSize = dims.any { it.width != MODEL_INPUT_SIZE || it.height != MODEL_INPUT_SIZE }
            val mismatch = dims.any { it.width != mask.width || it.height != mask.height }
            if (wrongSize || mismatch) {
                "$page: mask=${mask.width}x${mask.height} dyn=${dynamic.width}x${dynamic.height} stat=${static.width}x${static.height}"
            } else {
                null
            }
        }
        if (bad.isNotEmpty()) {
            error("Malformed corpus pages (expected 512x512, matching dims):\n  - ${bad.joinToString("\n  - ")}")
        }
    }

    @Test
    fun `mask is non-empty on every corpus page`() {
        // A page with an empty mask cannot exercise the guard (it returns early
        // on maskedCount < MIN_MASKED_PIXELS). Such a page is corpus noise, not
        // a gate input. This also guards against an all-zero mask from a botched
        // resize/binarize in emit_corpus_outputs.py.
        val pages = corpusPages()
        pages shouldHaveSize EXPECTED_CORPUS_SIZE

        val empty = pages.mapNotNull { page ->
            val mask = readArgbBin("$page/mask.bin")
            // maskValue = max(byte0, alpha); ALPHA8 packing puts the signal in
            // alpha (0xFF000000), so alpha > 127 means "erase."
            val maskedCount = mask.pixels.count { maskValue(it) > 127 }
            if (maskedCount < 16) "$page: only $maskedCount masked pixels (< 16)" else null
        }
        if (empty.isNotEmpty()) {
            error("Corpus pages with empty masks (would skip the guard):\n  - ${empty.joinToString("\n  - ")}")
        }
    }

    // --- helpers ---

    /**
     * Lists corpus page folders from the test classpath. Returns empty if the
     * resource root is missing (so the size assertion above surfaces it loudly
     * rather than silently passing on zero pages).
     */
    private fun corpusPages(): List<String> {
        val loader = javaClass.classLoader ?: return emptyList()
        val root = loader.getResource("corpus/aot") ?: return emptyList()
        // Resource URIs are filesystem paths under build/.../resources/test on
        // the JVM (no jar nesting for unit tests). List the child folders.
        val dir = java.io.File(root.toURI())
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.map { "corpus/aot/${it.name}" }
            ?: emptyList()
    }

    private data class ArgbImage(val width: Int, val height: Int, val pixels: IntArray)

    /** Reads a .bin resource (big-endian: int32 width, int32 height, int32[px] ARGB). */
    private fun readArgbBin(resourcePath: String): ArgbImage {
        val loader = javaClass.classLoader ?: error("no ClassLoader on test thread")
        return loader.getResourceAsStream(resourcePath).use { input ->
            if (input == null) error("corpus resource not found: $resourcePath")
            val dis = DataInputStream(input)
            val width = dis.readInt()
            val height = dis.readInt()
            val n = width * height
            val pixels = IntArray(n)
            for (i in 0 until n) pixels[i] = dis.readInt()
            ArgbImage(width, height, pixels)
        }
    }

    /** Mirrors [AotOutputGuard]'s private maskValue = max(byte0, alpha). */
    private fun maskValue(pixel: Int): Int = maxOf(pixel and 0xFF, pixel ushr 24)

    private companion object {
        const val MODEL_INPUT_SIZE = 512
        // Faithful free-text corpus: 12 pages that actually route to the AOT-512
        // path in prod (free-text boxes, no parent bubble). Produced by
        // generate_masks_faithful.py which runs detector-v4 + bubble segmenter
        // + paddle det and routes exactly like AOTInpainting.inpaintRegions.
        // The other 18/30 pages in the source chapter use only the bubble path
        // (inpaintReportBubbles, classical fill - no AOT model) and are not
        // AOT-512 inputs. See tools/aot_corpus/CURATION_REPORT.md.
        const val EXPECTED_CORPUS_SIZE = 12
    }
}
