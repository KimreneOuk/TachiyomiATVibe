package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream

private const val OPTIONAL_CORPUS_SKIP_REASON =
    "Optional corpus fixtures are not included in the public source snapshot."

/**
 * Optional guard-rejection corpus gate for the fixed-512 AOT model.
 * These tests run when the separately licensed corpus fixtures are supplied.
 *
 * This is the Kotlin half of the gate. It runs the REAL [AotOutputGuard] — the
 * production rejector — on pre-computed model outputs and asserts the gate
 * criterion: ZERO new rejections (a page where the dynamic model was accepted
 * but the static-512 model is now rejected is a regression and fails the gate).
 *
 * Model outputs are precomputed so these tests exercise the production guard
 * without requiring an inference runtime on the JVM test classpath.
 *
 * Corpus layout (one folder per page under the test resources):
 *   <page>/dynamic_out.bin   raw ARGB int32 (width,height,pixels...) — dynamic model
 *   <page>/static_out.bin    raw ARGB int32 — static-512 model
 *   <page>/mask.bin          raw ARGB int32 (ALPHA8-style, 0xFF000000=erase)
 *   <page>/manifest.json     page metadata (category, expected_box_count)
 *
 * The .bin format is read with java.io.DataInputStream: Android unit tests stub
 * out java.awt, so javax.imageio is unavailable on the test classpath. The .bin
 * carries the exact ARGB IntArray prod feeds to AotOutputGuard, with no decoder.
 *
 * The current corpus contains 42 independent free-text groups from 14 real
 * source pages. Each identity is `real_NNN__ft_NNN`: one detector-v4 free-text
 * ROI, independently refined by Paddle DET, cropped, masked, and inferred.
 */
class AotCorpusGateTest {

    @Test
    fun `gate produces zero new static-512 rejections on corpus`() {
        val pages = corpusPagesOrSkip()
        pages shouldHaveSize EXPECTED_CORPUS_SIZE

        val failures = pages.mapNotNull { page ->
            val mask = readArgbBin("$page/mask.bin")
            val dynamicOut = readArgbBin("$page/dynamic_out.bin")
            val staticOut = readArgbBin("$page/static_out.bin")

            val dynamicRejected = AotOutputGuard.isSuspiciousUniformFill(
                dynamicOut.pixels,
                mask.pixels,
                mask.width,
                mask.height,
            )
            val staticRejected = AotOutputGuard.isSuspiciousUniformFill(
                staticOut.pixels,
                mask.pixels,
                mask.width,
                mask.height,
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
        val pages = corpusPagesOrSkip()
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
    fun `corpus manifests prove identity source and Paddle provenance without fallback`() {
        val pages = corpusPagesOrSkip()
        pages shouldHaveSize EXPECTED_CORPUS_SIZE
        val identities = pages.map { it.substringAfterLast('/') }
        identities.distinct().size shouldBe EXPECTED_CORPUS_SIZE
        val malformed = identities.filterNot { CORPUS_IDENTITY.matches(it) }
        if (malformed.isNotEmpty()) {
            error("Malformed corpus identities (expected real_NNN__ft_NNN): ${malformed.joinToString()}")
        }
        val provenanceFailures = pages.mapNotNull { page ->
            val identity = page.substringAfterLast('/')
            val sourcePage = identity.substringBefore("__ft_")
            val manifest = readTextResource("$page/manifest.json")
            val problems = buildList {
                if (jsonString(manifest, "identity") != identity) add("identity")
                if (jsonString(manifest, "page") != identity) add("page")
                if (jsonString(manifest, "source_page") != sourcePage) add("source_page")
                if (jsonString(manifest, "source_file") !=
                    "page-${sourcePage.removePrefix("real_")}.jpg"
                ) {
                    add("source_file")
                }
                if (jsonString(manifest, "generator") != "generate_masks_faithful.py") add("generator")
                if (jsonBoolean(manifest, "fallback_used") != false) add("fallback_used")
                val lineCount = jsonInt(manifest, "paddle_line_count")
                if (lineCount == null || lineCount < 1) add("paddle_line_count")
                if (!PADDLE_LINES_WITH_ENTRY.containsMatchIn(manifest)) add("paddle_lines")
            }
            if (problems.isEmpty()) null else "$identity: invalid ${problems.joinToString()}"
        }
        if (provenanceFailures.isNotEmpty()) {
            error("Corpus manifest provenance failures:\n  - ${provenanceFailures.joinToString("\n  - ")}")
        }
        val sourcePages = identities.map { it.substringBefore("__ft_") }.distinct()
        if (sourcePages.size < MIN_SOURCE_PAGE_COUNT) {
            error("Corpus covers only ${sourcePages.size} source pages; expected at least $MIN_SOURCE_PAGE_COUNT")
        }
    }

    @Test
    fun `sub-512 production pad fixtures pass the real guard gate`() {
        val fixtures = sub512Fixtures()
        assumeTrue(fixtures.isNotEmpty(), OPTIONAL_CORPUS_SKIP_REASON)
        fixtures.map { it.side } shouldBe listOf(300, 400, 480, 511)

        val newRejections = fixtures.mapNotNull { fixture ->
            val root = "corpus/aot_sub512/${fixture.identity}"
            val mask = readArgbBin("$root/mask.bin")
            val dynamic = readArgbBin("$root/dynamic_out.bin")
            val static = readArgbBin("$root/static_out.bin")
            val manifest = readTextResource("$root/manifest.json")
            val expectedPixels = fixture.side * fixture.side
            val malformed = buildList {
                if (mask.width != fixture.side || mask.height != fixture.side) add("mask dimensions")
                if (dynamic.width != fixture.side || dynamic.height != fixture.side) add("dynamic dimensions")
                if (static.width != fixture.side || static.height != fixture.side) add("static dimensions")
                if (mask.pixels.size != expectedPixels ||
                    dynamic.pixels.size != expectedPixels ||
                    static.pixels.size != expectedPixels
                ) {
                    add("pixel count")
                }
                if (jsonString(manifest, "fixture_kind") !=
                    "deterministic_transformed_real_corpus_center_crop"
                ) {
                    add("fixture_kind")
                }
                if (jsonInt(manifest, "fixture_side") != fixture.side) add("fixture_side")
                if (jsonString(manifest, "fixture_source_identity") != fixture.identity) add("fixture_source_identity")
                if (jsonString(manifest, "fixture_transform") !=
                    "center_crop_from_512_to_${fixture.side}_no_resize"
                ) {
                    add("fixture_transform")
                }
                if (jsonString(manifest, "dynamic_route") !=
                    "native_${fixture.side}x${fixture.side}"
                ) {
                    add("dynamic_route")
                }
                if (jsonString(manifest, "static_route") !=
                    "production_center_background_pad_zero_mask_512_crop_back"
                ) {
                    add("static_route")
                }
                if (jsonBoolean(manifest, "synthetic_color_used") != false) add("synthetic_color_used")
            }
            if (malformed.isNotEmpty()) {
                error("${fixture.identity}: malformed sub-512 fixture ${malformed.joinToString()}")
            }
            val dynamicStats = AotOutputGuard.inspect(dynamic.pixels, mask.pixels, mask.width, mask.height)
            val staticStats = AotOutputGuard.inspect(static.pixels, mask.pixels, mask.width, mask.height)
            if (!AotOutputGuard.classify(dynamicStats) && AotOutputGuard.classify(staticStats)) {
                "${fixture.identity}@${fixture.side}: dynamic=accept static=REJECT " +
                    "dynamic=$dynamicStats static=$staticStats"
            } else {
                null
            }
        }
        if (newRejections.isNotEmpty()) {
            error("Sub-512 real guard gate FAILED:\n  - ${newRejections.joinToString("\n  - ")}")
        }
    }

    @Test
    fun `mask is non-empty on every corpus page`() {
        // A page with an empty mask cannot exercise the guard (it returns early
        // on maskedCount < MIN_MASKED_PIXELS). Such a page is corpus noise, not
        // a gate input. This also guards against an all-zero mask from a botched
        // resize/binarize in the offline fixture-generation pipeline.
        val pages = corpusPagesOrSkip()
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

    /** Skips corpus-only checks when the optional fixtures are absent. */
    private fun corpusPagesOrSkip(): List<String> {
        val pages = corpusPages()
        assumeTrue(pages.isNotEmpty(), OPTIONAL_CORPUS_SKIP_REASON)
        return pages
    }

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
    private data class Sub512Fixture(val identity: String, val side: Int)

    private fun sub512Fixtures(): List<Sub512Fixture> {
        val loader = javaClass.classLoader ?: return emptyList()
        val root = loader.getResource("corpus/aot_sub512") ?: return emptyList()
        val dir = java.io.File(root.toURI())
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isDirectory }
            ?.map { file ->
                val manifest = readTextResource("corpus/aot_sub512/${file.name}/manifest.json")
                Sub512Fixture(file.name, jsonInt(manifest, "fixture_side") ?: -1)
            }
            ?.sortedBy { it.side }
            ?: emptyList()
    }

    private fun readTextResource(resourcePath: String): String {
        val loader = javaClass.classLoader ?: error("no ClassLoader on test thread")
        return loader.getResourceAsStream(resourcePath)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("corpus resource not found: $resourcePath")
    }

    // Manifests are generated, flat for scalar fields, and validated structurally by
    // Python before emission. These dependency-free extractors intentionally handle
    // JSON whitespace and escapes without introducing an Android JSON dependency.
    private fun jsonString(json: String, key: String): String? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
            .find(json)?.groupValues?.get(1)
            ?.replace("\\\\\"", "\"")
            ?.replace("\\\\\\\\", "\\")

    private fun jsonInt(json: String, key: String): Int? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)")
            .find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun jsonBoolean(json: String, key: String): Boolean? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false)")
            .find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

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

        // One sample per detector-v4 free-text ROI. Paddle DET runs independently
        // on each ROI padded by 12 px, then each resulting group gets its own crop,
        // mask, and stable real_NNN__ft_NNN identity. This prevents separate groups
        // on one source page from being incorrectly merged into one gate input.
        const val EXPECTED_CORPUS_SIZE = 42
        const val MIN_SOURCE_PAGE_COUNT = 2
        val CORPUS_IDENTITY = Regex("real_\\d{3}__ft_\\d{3}")
        val PADDLE_LINES_WITH_ENTRY = Regex("\\\"paddle_lines\\\"\\s*:\\s*\\[\\s*\\[")
    }
}
