package eu.kanade.translation.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Computes SHA-256 digests for model deployment and persisted model identity. */
internal object Sha256 {

    fun digest(input: InputStream): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            sha.update(buffer, 0, read)
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    fun digest(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use(::digest)
        }.getOrNull()
    }
}
