package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.util.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File

/** An installed model asset whose identity should be cached. */
data class ModelAsset(
    /** Stable role name, e.g. `detector`, `ocr.mangaocr`, `inpaint.aot`. */
    val role: String,
    /** App-level bundled-model generation label the asset shipped with. */
    val versionMarker: String,
    val file: File,
)

/** Cached identity of one installed model asset (lifecycle contract §1). */
@Serializable
data class ModelIdentity(
    val role: String,
    val versionMarker: String,
    val sha256: String,
    val lengthBytes: Long,
)

@Serializable
private data class CachedModelIdentity(
    val stamp: String,
    val versionMarker: String,
    val sha256: String,
    val lengthBytes: Long,
)

/**
 * caches installed detector/OCR/inpaint model hashes so a no-op
 * planner scan reads cached identities and artifact metadata instead of
 * rehashing model assets (lifecycle contract §1).
 *
 * A cached hash is reused only while the asset stamp
 * `versionMarker:length:lastModified` is unchanged; any byte or deployment
 * drift invalidates the entry and rehashes once. The cache itself persists as
 * JSON so identities survive process restarts.
 */
class ModelIdentityCache(
    private val cacheFile: File?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, CachedModelIdentity>? = null
    private var dirty = false

    @Synchronized
    fun identityFor(asset: ModelAsset): ModelIdentity? {
        if (!asset.file.isFile) return null
        val stamp = stampFor(asset)
        val cached = cacheOrDefault()[asset.role]
        if (cached != null && cached.stamp == stamp) {
            return ModelIdentity(asset.role, cached.versionMarker, cached.sha256, cached.lengthBytes)
        }
        val sha256 = Sha256.digest(asset.file) ?: return null
        val identity = CachedModelIdentity(
            stamp = stamp,
            versionMarker = asset.versionMarker,
            sha256 = sha256,
            lengthBytes = asset.file.length(),
        )
        cacheOrDefault()[asset.role] = identity
        dirty = true
        persist()
        return ModelIdentity(asset.role, identity.versionMarker, identity.sha256, identity.lengthBytes)
    }

    @Synchronized
    fun snapshot(assets: List<ModelAsset>): List<ModelIdentity> =
        assets.mapNotNull(::identityFor)

    /** Number of identities served from the persisted cache without rehashing. */
    @Synchronized
    fun cachedRoleCount(): Int = cacheOrDefault().size

    @Synchronized
    fun flush() {
        if (dirty) persist()
    }

    private fun stampFor(asset: ModelAsset): String =
        "${asset.versionMarker}:${asset.file.length()}:${asset.file.lastModified()}"

    private fun cacheOrDefault(): MutableMap<String, CachedModelIdentity> {
        cache?.let { return it }
        val loaded = runCatching {
            cacheFile?.inputStream()?.use { input ->
                json.decodeFromStream<Map<String, CachedModelIdentity>>(input)
            }
        }.getOrNull().orEmpty()
        return loaded.toMutableMap().also { cache = it }
    }

    private fun persist() {
        val target = cacheFile ?: return
        runCatching {
            val bytes = json.encodeToString(cacheOrDefault()).toByteArray(Charsets.UTF_8)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
            dirty = false
        }
    }
}
