package eu.kanade.translation.processing

import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.stampName
import eu.kanade.translation.persistence.ArtifactDocumentJson
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.CleanedImageIdentity
import eu.kanade.translation.persistence.CleanedImageIdentitySidecar
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import kotlinx.serialization.decodeFromString
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.NeuralInpaintModel
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Owns verified cleaned-image files and retirement of superseded images. */
internal class CleanedPublication(
    private val provider: TranslationFileProvider,
    private val streamRegistry: TranslationStreamRegistry,
    private val currentInpaintingMode: () -> InpaintingMode,
    private val currentNeuralInpaintModel: () -> NeuralInpaintModel = { NeuralInpaintModel.AOT_GAN },
) {
    internal fun currentInpaintingStamp(): String = currentInpaintingMode().stampName(
        neuralModel = currentNeuralInpaintModel(),
        neuralAvailable = true,
    )

    suspend fun deleteRetiredCleanedFile(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) {
        val chapterId = chapter.id ?: return
        store.drainRetiredCleanedImages(pageKey).forEach { name ->
            streamRegistry.retireCleanedImage(source.id, manga.id, chapterId, pageKey, name) {
                if (!store.mayDeleteCleanedImage(pageKey, name)) return@retireCleanedImage
                val imageFile = provider.findPageCleanedImage(
                    manga.title,
                    source,
                    chapter.name,
                    chapter.scanlator,
                    name,
                )
                val deleted = imageFile?.let { !it.exists() || it.delete() } ?: true
                if (deleted) {
                    provider.findPageCleanedImage(
                        manga.title,
                        source,
                        chapter.name,
                        chapter.scanlator,
                        CleanedImageIdentity.sidecarName(name),
                    )?.delete()
                }
                logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                    "TachiyomiAT retired cleaned image drain: pageKey=$pageKey file=$name deleted=$deleted"
                }
            }
        }
    }

    internal data class CleanedImageWriteResult(val name: String, val contentSha256: String)

    internal fun writeVerifiedCleanedImage(
        directory: UniFile,
        pageKey: String,
        bitmap: Bitmap,
        name: String = newCleanedImageName(pageKey),
    ): CleanedImageWriteResult {
        val identityName = CleanedImageIdentity.sidecarName(name)
        val existingImage = directory.findFile(name)?.takeIf(UniFile::exists)
        val existingIdentity = directory.findFile(identityName)?.takeIf(UniFile::exists)
        if (existingImage != null || existingIdentity != null) {
            val sidecarBytes = runCatching { existingIdentity?.openInputStream()?.use { it.readBytes() } }.getOrNull()
            val sidecar = runCatching {
                sidecarBytes?.let { ArtifactDocumentJson.decodeFromString<CleanedImageIdentitySidecar>(it.decodeToString()) }
            }.getOrNull()
            check(
                sidecar != null &&
                    CleanedImageIdentity.verifyExisting(
                        sidecarBytes = sidecarBytes,
                        imageName = name,
                        expectedContentSha256 = sidecar.contentSha256,
                    ) { existingImage?.openInputStream() },
            ) { "cleaned image name is already bound to different or unverified bytes" }
            return CleanedImageWriteResult(name, sidecar.contentSha256)
        }

        val imageFile = checkNotNull(directory.createFile(name)) { "could not create final cleaned image" }
        var identityFile: UniFile? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            imageFile.openOutputStream().use { output ->
                DigestOutputStream(output, digest).use { digestOutput ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, digestOutput)) {
                        "JPEG encoding returned false"
                    }
                    digestOutput.flush()
                }
            }
            check(imageFile.exists() && imageFile.length() > 0L) { "published cleaned image is unavailable" }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val identity = CleanedImageIdentitySidecar(pageKey = pageKey, imageName = name, contentSha256 = hash)
            identityFile = checkNotNull(directory.createFile(identityName)) { "could not create cleaned image identity" }
            identityFile!!.openOutputStream().use { output ->
                output.write(CleanedImageIdentity.encode(identity))
                output.flush()
            }
            check(identityFile!!.exists() && identityFile!!.length() > 0L) { "cleaned image identity is unavailable" }
            return CleanedImageWriteResult(name, hash)
        } catch (failure: Throwable) {
            identityFile?.delete()
            imageFile.delete()
            throw failure
        }
    }

    private fun newCleanedImageName(pageKey: String): String {
        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val version = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().replace("-", "")}"
        return "$safeName.cleaned.$version.jpg"
    }
}
