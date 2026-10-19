package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.ChapterDocumentIo
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat




/** File-system-independent publication ordering used by both translation paths. */
class CleanedImagePublisher(
    private val files: Files,
) {
    interface Files {
        fun writeVerifiedVersionedFile(): String
        fun delete(name: String): Boolean
    }

    /**
     * Publishes one verified cleaned-image file, then commits its name to the
     * store. The previous file is deleted only after the commit is accepted
     * AND [mayDeletePrevious] allows it: a file that still backs a committed
     * display bundle (last-known-good) must survive until a newer bundle
     * promotes, so the reader never resolves a deleted file (lifecycle
     * contract §15).
     *
     * Retain-on-uncertainty failure matrix:
     * - write failure: nothing was committed, nothing to clean.
     * - commit throws or is cancelled: the commit outcome is UNCERTAIN — the
     *   store may have accepted [newName] before the failure landed — so the
     *   new file is RETAINED. It is never deleted here and never registered
     *   into retirement (no blind registration of an uncertain name); if the
     *   store never accepted it, the file is unbounded uncollected debt,
     *   logged truthfully and reclaimed only by a future explicit sweep. No
     *   path claims the file was cleaned when it was retained.
     * - store REJECTS the commit: a [ChapterTranslationStore.PatchResult.Rejected]
     *   alone does NOT establish that [newName] was never accepted as live
     *   (e.g. the lazy hash-patch path rejects a name an earlier publication
     *   already accepted). The physical delete therefore requires
     *   [mayDeleteRejected] to return true, which the caller may only do when
     *   it INDEPENDENTLY knows the name was never accepted as live (a fresh
     *   private versioned name whose single possible acceptance is this very
     *   commit) AND the store's mutex-held
     *   [ChapterTranslationStore.authorizeCleanedImageDeletion] authorizes it
     *   at that point in time. The delete runs AFTER that call returns —
     *   outside the store lock, so it can interleave with later mutations;
     *   that residual race is exactly why independent never-accepted
     *   provenance, not the point-in-time check alone, is required. Any other
     *   rejected name is retained and logged as uncollected debt.
     */
    suspend fun publish(
        chapter: String,
        pageKey: String,
        previousName: String?,
        commit: suspend (newName: String) -> ChapterTranslationStore.PatchResult,
        mayDeletePrevious: (String) -> Boolean = { true },
        retirePrevious: ((String, () -> Unit) -> Unit)? = null,
        /**
         * Return true ONLY when the caller independently knows [newName] was
         * never accepted as live (fresh private versioned name whose single
         * possible acceptance is this commit, which just returned Rejected)
         * AND the store's mutex-held authorization passes. A
         * [ChapterTranslationStore.PatchResult.Rejected] result alone is NOT
         * sufficient knowledge; unknown provenance must return false (retain).
         */
        mayDeleteRejected: suspend (String) -> Boolean = { false },
    ): Result {
        val newName = try {
            files.writeVerifiedVersionedFile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logcat(LogPriority.ERROR, failure) {
                "TachiyomiAT cleaned publication failed: chapter=$chapter pageKey=$pageKey phase=write"
            }
            return Result.WriteFailed(failure)
        }
        val patch = try {
            commit(newName)
        } catch (cancelled: CancellationException) {
            // Retain-on-uncertainty: deleting here could orphan the store's
            // live pointer if the commit was accepted before the cancellation
            // landed. The file is retained and NOT registered into retirement.
            logcat(LogPriority.ERROR) {
                "TachiyomiAT cleaned publication cancelled during commit: chapter=$chapter " +
                    "pageKey=$pageKey new=$newName retained=true commitOutcome=uncertain " +
                    "(uncollected debt if the store never accepted)"
            }
            throw cancelled
        } catch (failure: Throwable) {
            // Same uncertainty as cancellation: retain, never claim cleanup.
            logcat(LogPriority.ERROR, failure) {
                "TachiyomiAT cleaned publication commit failed: chapter=$chapter pageKey=$pageKey " +
                    "new=$newName retained=true commitOutcome=uncertain " +
                    "(uncollected debt if the store never accepted)"
            }
            return Result.Rejected(
                name = newName,
                reason = failure.message ?: failure::class.java.simpleName,
                unpublishedCleaned = false,
            )
        }
        return when (patch) {
            is ChapterTranslationStore.PatchResult.Accepted -> {
                if (previousName != null && previousName != newName) {
                    when {
                        !mayDeletePrevious(previousName) -> logcat(LogPriority.INFO) {
                            "TachiyomiAT cleaned publication retained previous file (committed display): " +
                                "chapter=$chapter pageKey=$pageKey old=$previousName new=$newName"
                        }
                        retirePrevious != null -> retirePrevious(previousName) {
                            files.delete(previousName)
                        }
                        !files.delete(previousName) -> logcat(LogPriority.WARN) {
                            "TachiyomiAT cleaned publication old-file cleanup failed: chapter=$chapter " +
                                "pageKey=$pageKey old=$previousName new=$newName"
                        }
                    }
                }
                Result.Published(newName, patch.snapshot)
            }
            is ChapterTranslationStore.PatchResult.Rejected -> {
                // The seam — not the Rejected result itself — carries the
                // never-accepted-live-name knowledge plus the store's
                // mutex-held point-in-time authorization. Fail closed on any
                // seam failure or false: the name is retained as uncollected
                // debt and the log says exactly that.
                val authorized = try {
                    mayDeleteRejected(newName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    logcat(LogPriority.WARN, failure) {
                        "TachiyomiAT cleaned publication rejected-name deletion authorization failed: " +
                            "chapter=$chapter pageKey=$pageKey new=$newName retained=true"
                    }
                    false
                }
                val cleaned = if (authorized) {
                    runCatching { files.delete(newName) }.getOrDefault(false)
                } else {
                    // Unbounded retained debt is a ledger-worthy condition:
                    // plan §10 requires it at ERROR, unlike the generic
                    // rejected summary below.
                    logcat(LogPriority.ERROR) {
                        "TachiyomiAT cleaned publication rejected-name deletion not authorized: " +
                            "chapter=$chapter pageKey=$pageKey new=$newName retained=true " +
                            "uncollectedDebt=true reason=${patch.reason}"
                    }
                    false
                }
                logcat(LogPriority.WARN) {
                    "TachiyomiAT cleaned publication rejected: chapter=$chapter pageKey=$pageKey " +
                        "new=$newName reason=${patch.reason} unpublishedCleaned=$cleaned"
                }
                Result.Rejected(newName, patch.reason, cleaned)
            }
        }
    }

    sealed interface Result {
        data class Published(
            val name: String,
            val snapshot: ChapterTranslationStore.PageSnapshot,
        ) : Result
        data class Rejected(val name: String, val reason: String, val unpublishedCleaned: Boolean) : Result
        data class WriteFailed(val failure: Throwable) : Result
    }
}


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