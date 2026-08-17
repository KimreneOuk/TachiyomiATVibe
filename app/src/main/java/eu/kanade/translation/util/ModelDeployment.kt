package eu.kanade.translation.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * TachiyomiAT: pure, Android-free model-deployment helpers extracted from
 * `OnnxModelStore.copyIfNeeded` so the deployment-integrity invariants are
 * unit-testable without an Android `Context`.
 *
 * The integrity stamp is `<versionMarker>:<assetPath>:<sha256-of-bytes>`. This
 * catches two classes of the original Wave 4 deployment bug that a hand-typed
 * version string alone misses:
 *
 * 1. Cached-file corruption (partial install, disk error, a truncated write):
 *    the cached file's bytes no longer hash to the value stored in the stamp,
 *    so the stamp no longer matches and the file is re-copied.
 * 2. Legacy / missing stamp: treated as "always re-copy once" so users on the
 *    pre-stamp build get the current asset on first run after upgrade.
 *
 * It does NOT auto-detect an asset change that ships without bumping
 * [versionMarker] — the new asset's hash is never compared to the old because
 * the old stamp is overwritten on copy. Detecting that requires a build-time
 * hash (a Gradle task writing the asset hash into BuildConfig); that is a
 * documented follow-up, not in scope here.
 *
 * Style mirrors [ShortHash] and [TranslationSafetyPrimitives]: a small `object`
 * of pure functions in `eu.kanade.translation.util`, tested in-package under
 * `app/src/test`.
 */
object ModelDeployment {

    /**
     * Computes the deployment stamp for a model asset: a stable string that
     * changes when the [versionMarker] changes OR when the asset's bytes
     * change. The [versionMarker] is the app-level "bundled model generation"
     * label; the SHA-256 is computed over the full asset stream so any byte
     * drift (corruption, partial write) flips the stamp.
     *
     * Pure: takes the stream, consumes it fully, returns the stamp. Caller
     * closes the stream.
     */
    fun computeStamp(versionMarker: String, assetPath: String, assetBytes: InputStream): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = assetBytes.read(buffer)
            if (read <= 0) break
            sha.update(buffer, 0, read)
        }
        val hashHex = sha.digest().joinToString("") { "%02x".format(it) }
        return "$versionMarker:$assetPath:$hashHex"
    }

    /**
     * Reads the cached stamp from [stampFile], returning null if the file is
     * missing, not a regular file, or unreadable. A null return means "no
     * stamp recorded" — callers must treat the cached model as stale and
     * re-copy (the "always re-copy once" rule for legacy caches).
     */
    fun readStamp(stampFile: File): String? {
        if (!stampFile.isFile) return null
        return runCatching { stampFile.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * True iff the [stampFile] exists and its content exactly equals [expected].
     * Pure aside from the file read; returns false on any read failure rather
     * than throwing (deployment must degrade to re-copy, not crash).
     */
    fun stampMatches(stampFile: File, expected: String): Boolean {
        val actual = readStamp(stampFile) ?: return false
        return actual == expected
    }

    /**
     * Writes [stamp] to [stampFile] atomically-ish (write then flush). Best
     * effort: returns false on failure rather than throwing, because a missing
     * stamp only means the next start re-copies — a safe degradation, not an
     * error worth aborting model deployment over.
     */
    fun writeStamp(stampFile: File, stamp: String): Boolean {
        return runCatching {
            stampFile.writeText(stamp, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /**
     * Computes the SHA-256 of a cached model file, returning null if the file
     * cannot be read. Used by [cachedFileMatchesStamp] to detect corruption of
     * the cached bytes vs the hash recorded in the stamp.
     */
    fun hashOfFile(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { stream ->
                val sha = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    sha.update(buffer, 0, read)
                }
                sha.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
    }

    /**
     * True iff the cached file's current SHA-256 matches the hash embedded in
     * [expectedStamp]. Catches corruption of an already-copied model: the
     * stamp was written at copy time with the correct hash, so a later change
     * to the cached bytes (disk error, external modification, a partial write
     * from a previous run) flips this to false and forces a re-copy.
     *
     * Returns true if either (a) the hashes match, or (b) [expectedStamp] has
     * no hash segment (defensive — old-format stamps without a hash segment
     * defer to [stampMatches] instead of being treated as corrupt).
     */
    fun cachedFileMatchesStamp(cachedFile: File, expectedStamp: String): Boolean {
        val expectedHash = expectedStamp.substringAfterLast(':', missingDelimiterValue = "")
        if (expectedHash.isEmpty()) return true
        val actualHash = hashOfFile(cachedFile) ?: return false
        return actualHash == expectedHash
    }
}
