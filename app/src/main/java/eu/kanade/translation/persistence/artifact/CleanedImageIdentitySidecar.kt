package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.security.MessageDigest

/** Stable byte identity for a cleaned image whose legacy name is not content-addressed. */
@Serializable
internal data class CleanedImageIdentitySidecar(
    val schemaVersion: Int = SCHEMA_VERSION,
    val pageKey: String,
    val imageName: String,
    val contentSha256: String,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal object CleanedImageIdentity {
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    fun sidecarName(imageName: String): String = "$imageName.identity.json"

    fun create(pageKey: String, imageName: String, input: InputStream): CleanedImageIdentitySidecar =
        CleanedImageIdentitySidecar(
            pageKey = pageKey,
            imageName = imageName,
            contentSha256 = sha256(input),
        )

    fun encode(sidecar: CleanedImageIdentitySidecar): ByteArray =
        ArtifactDocumentJson.encodeToString(sidecar).encodeToByteArray()

    /** Verifies the exact sidecar lookup and the bytes it names, closing both streams. */
    fun verify(
        io: ChapterDocumentIo,
        chapterBaseName: String,
        imageName: String,
        expectedContentSha256: String,
    ): Boolean {
        if (!SHA256_HEX.matches(expectedContentSha256) || !isSafeFileName(imageName)) return false
        val imageDirectory = "${chapterBaseName}_images"
        val sidecarBytes = io.read("$imageDirectory/${sidecarName(imageName)}") ?: return false
        return verifyExisting(sidecarBytes, imageName, expectedContentSha256) {
            io.openInputStream("$imageDirectory/$imageName")
        }
    }

    /**
     * Verifies a same-name retry against the sidecar and exact image bytes.
     * The caller supplies the independently anchored hash of the content it
     * intended to write; an existing name is reusable only for those bytes.
     */
    fun verifyExisting(
        sidecarBytes: ByteArray?,
        imageName: String,
        expectedContentSha256: String,
        openImage: () -> InputStream?,
    ): Boolean {
        if (sidecarBytes == null || !SHA256_HEX.matches(expectedContentSha256) || !isSafeFileName(imageName)) return false
        val sidecar = runCatching {
            ArtifactDocumentJson.decodeFromString<CleanedImageIdentitySidecar>(sidecarBytes.decodeToString())
        }.getOrNull() ?: return false
        if (!hasExplicitSupportedSchema(sidecarBytes) ||
            sidecar.schemaVersion != CleanedImageIdentitySidecar.SCHEMA_VERSION ||
            sidecar.imageName != imageName ||
            !SHA256_HEX.matches(sidecar.contentSha256) ||
            sidecar.contentSha256 != expectedContentSha256
        ) {
            return false
        }
        val image = runCatching(openImage).getOrNull() ?: return false
        return runCatching { image.use { sha256(it) == sidecar.contentSha256 } }.getOrDefault(false)
    }

    private fun hasExplicitSupportedSchema(bytes: ByteArray): Boolean = runCatching {
        val root = ArtifactDocumentJson.parseToJsonElement(bytes.decodeToString()).jsonObject
        root["schemaVersion"]?.jsonPrimitive?.intOrNull == CleanedImageIdentitySidecar.SCHEMA_VERSION
    }.getOrDefault(false)

    private fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name &&
            name.none(Char::isISOControl)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
