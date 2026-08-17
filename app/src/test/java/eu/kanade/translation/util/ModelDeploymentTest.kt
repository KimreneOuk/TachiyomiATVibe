package eu.kanade.translation.util

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Regression guards for the Wave 4 model-deployment integrity helpers in
 * [ModelDeployment]. Each test RED-firsts a class of the original bug: a
 * stale or corrupt cached model surviving an asset update.
 *
 * The helpers are pure (take streams/files, no Android `Context`), so the test
 * exercises them directly with temp-dir files and in-memory streams.
 */
class ModelDeploymentTest {

    @TempDir
    lateinit var tempDir: File

    // ---- computeStamp ----

    @Test
    fun `computeStamp is deterministic for identical bytes`() {
        val bytes = "model-bytes".toByteArray()
        val a = ModelDeployment.computeStamp("v1", "aot.onnx", ByteArrayInputStream(bytes))
        val b = ModelDeployment.computeStamp("v1", "aot.onnx", ByteArrayInputStream(bytes))
        a shouldBe b
    }

    @Test
    fun `computeStamp changes when the version marker changes`() {
        // The bug class: a model swap ships but the stamp doesn't flip, so the
        // stale cache survives. The version marker is one of two flip signals.
        val bytes = "model-bytes".toByteArray()
        val v1 = ModelDeployment.computeStamp("v1", "aot.onnx", ByteArrayInputStream(bytes))
        val v2 = ModelDeployment.computeStamp("v2", "aot.onnx", ByteArrayInputStream(bytes))
        v1 shouldNotBe v2
    }

    @Test
    fun `computeStamp changes when the asset bytes change`() {
        // The second flip signal: same version + path, different bytes (model
        // re-export, partial commit). The SHA-256 component must catch this.
        val v1 = ModelDeployment.computeStamp("v1", "aot.onnx", ByteArrayInputStream("bytes-a".toByteArray()))
        val v2 = ModelDeployment.computeStamp("v1", "aot.onnx", ByteArrayInputStream("bytes-b".toByteArray()))
        v1 shouldNotBe v2
    }

    @Test
    fun `computeStamp changes when the asset path changes`() {
        val bytes = "same-bytes".toByteArray()
        val a = ModelDeployment.computeStamp("v1", "aot.onnx", ByteArrayInputStream(bytes))
        val b = ModelDeployment.computeStamp("v1", "aot-512.onnx", ByteArrayInputStream(bytes))
        a shouldNotBe b
    }

    @Test
    fun `computeStamp format is version_path_hash`() {
        val stamp = ModelDeployment.computeStamp("v1", "aot.onnx", ByteArrayInputStream("x".toByteArray()))
        val parts = stamp.split(":")
        parts shouldHaveSize 3
        parts[0] shouldBe "v1"
        parts[1] shouldBe "aot.onnx"
        // SHA-256 of a single byte "x" is a fixed 64-char hex string.
        parts[2].length shouldBe 64
        parts[2].matches(Regex("^[0-9a-f]{64}$")) shouldBe true
    }

    // ---- stampMatches ----

    @Test
    fun `stampMatches returns false when stamp file is missing (legacy cache)`() {
        // The "always re-copy once" rule: a cache from before stamps shipped
        // must not be treated as fresh.
        val missing = File(tempDir, "never-written.version")
        ModelDeployment.stampMatches(missing, "any") shouldBe false
    }

    @Test
    fun `stampMatches returns true only when content exactly equals expected`() {
        val stampFile = File(tempDir, "model.version")
        ModelDeployment.writeStamp(stampFile, "v1:aot.onnx:abc123")
        ModelDeployment.stampMatches(stampFile, "v1:aot.onnx:abc123") shouldBe true
        ModelDeployment.stampMatches(stampFile, "v1:aot.onnx:different") shouldBe false
        ModelDeployment.stampMatches(stampFile, "v2:aot.onnx:abc123") shouldBe false
    }

    @Test
    fun `stampMatches returns false when stamp file is a directory (corrupt cache state)`() {
        val dir = File(tempDir, "model.version")
        dir.mkdirs()
        ModelDeployment.stampMatches(dir, "any") shouldBe false
    }

    // ---- readStamp / writeStamp round-trip ----

    @Test
    fun `writeStamp then readStamp round-trips`() {
        val stampFile = File(tempDir, "round.version")
        val ok = ModelDeployment.writeStamp(stampFile, "v1:path:hash")
        ok shouldBe true
        ModelDeployment.readStamp(stampFile) shouldBe "v1:path:hash"
    }

    @Test
    fun `readStamp returns null for a missing file`() {
        val missing = File(tempDir, "absent.version")
        ModelDeployment.readStamp(missing) shouldBe null
    }

    // ---- cachedFileMatchesStamp (corruption detection) ----

    @Test
    fun `cachedFileMatchesStamp is true when file hash equals stamp hash`() {
        val bytes = "cached-model-bytes".toByteArray()
        val file = File(tempDir, "model.onnx").apply { writeBytes(bytes) }
        val stamp = ModelDeployment.computeStamp("v1", "model.onnx", ByteArrayInputStream(bytes))
        ModelDeployment.cachedFileMatchesStamp(file, stamp) shouldBe true
    }

    @Test
    fun `cachedFileMatchesStamp is false when cached file was corrupted after copy`() {
        // The bug class: the original copy wrote the right bytes + stamp, but a
        // later event (disk error, external modification) changed the cached
        // bytes. The hash mismatch must force a re-copy.
        val original = "original-bytes".toByteArray()
        val file = File(tempDir, "model.onnx").apply { writeBytes(original) }
        val stamp = ModelDeployment.computeStamp("v1", "model.onnx", ByteArrayInputStream(original))
        // Now corrupt the cached file after the stamp was computed.
        file.writeBytes("corrupted-bytes!!".toByteArray())
        ModelDeployment.cachedFileMatchesStamp(file, stamp) shouldBe false
    }

    @Test
    fun `cachedFileMatchesStamp returns false when cached file is missing`() {
        val missing = File(tempDir, "absent.onnx")
        ModelDeployment.cachedFileMatchesStamp(missing, "v1:p:abcdef") shouldBe false
    }
}
