package eu.kanade.translation.persistence

import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/** Builds durable run config fixtures without depending on batch orchestration. */
internal fun frozenRunConfigSnapshot(
    sourceLang: String = "ja",
    targetLang: String = "en",
    ocrEngine: String = "FakeOcrEngine",
    inpaintMode: String = "OFF",
    providerKey: String = "fake:provider",
    ocrModelHash: String = "unspecified",
    detectorModelHash: String = "unspecified",
    protocolVersion: Int = 1,
    readingOrderVersion: Int = 1,
    credentialId: String = "",
): RunConfigSnapshot = RunConfigSnapshot(
    sourceLang = sourceLang,
    targetLang = targetLang,
    ocrEngine = ocrEngine,
    ocrModelHash = ocrModelHash,
    detectorModelHash = detectorModelHash,
    inpaintMode = inpaintMode,
    providerKey = providerKey,
    credentialId = credentialId,
    protocolVersion = protocolVersion,
    readingOrderVersion = readingOrderVersion,
    flagProfilePipeline = true,
)

internal fun RunConfigSnapshot.fixtureFingerprint(): String {
    val encoded = ArtifactDocumentJson.encodeToString(this)
    val lengthPrefixed = "run-config-v1".let { prefix ->
        "${prefix.encodeToByteArray().size}:$prefix" + "${encoded.encodeToByteArray().size}:$encoded"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(lengthPrefixed.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
