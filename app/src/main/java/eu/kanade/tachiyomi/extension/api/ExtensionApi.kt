package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()
    private val json: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<Extension.Available> {
        val repoBaseUrl = extRepo.baseUrl
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/repo.json")
            .removeSuffix("/")
        // 1. Try modern index.json (Keiyoushi and newer repositories)
        try {
            val response = networkService.client
                .newCall(GET("$repoBaseUrl/index.json"))
                .awaitSuccess()

            val body = response.body.string()
            with(json) {
                if (body.contains("\"extensionList\"")) {
                    val repoIndex = decodeFromString<RepoIndexJsonObject>(body)
                    val extensions = repoIndex.extensionList?.extensions.orEmpty()
                        .toExtensionsFromNewSchema(repoBaseUrl)
                    if (extensions.isNotEmpty()) {
                        return extensions
                    }
                } else {
                    val extensions = decodeFromString<List<ExtensionJsonObject>>(body)
                        .toExtensions(repoBaseUrl)
                    if (extensions.isNotEmpty()) {
                        return extensions
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.DEBUG, e) { "Failed to get index.json from $repoBaseUrl, attempting index.min.json" }
        }

        // 2. Fallback to legacy index.min.json
        return try {
            val response = networkService.client
                .newCall(GET("$repoBaseUrl/index.min.json"))
                .awaitSuccess()

            with(json) {
                response
                    .parseAs<List<ExtensionJsonObject>>()
                    .toExtensions(repoBaseUrl)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<Extension.Available> {
        return this
            .filterNot { isDummyWarningExtension(it.pkg, it.name) }
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: ").substringAfter("Tachiyomix: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                )
            }
    }

    private fun List<RepoExtensionEntryJsonObject>.toExtensionsFromNewSchema(repoUrl: String): List<Extension.Available> {
        return this
            .filterNot { isDummyWarningExtension(it.packageName, it.name) }
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                val lang = it.sources?.firstOrNull()?.language
                    ?: it.packageName.substringAfter("eu.kanade.tachiyomi.extension.").substringBefore('.')
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: ").substringAfter("Tachiyomix: "),
                    pkgName = it.packageName,
                    versionName = it.versionName,
                    versionCode = it.versionCode?.toLongOrNull() ?: 0L,
                    libVersion = it.extractLibVersion(),
                    lang = lang,
                    isNsfw = it.contentWarning?.contains("NSFW", ignoreCase = true) == true,
                    sources = it.sources?.map { s ->
                        Extension.Available.Source(
                            id = s.id?.toLongOrNull() ?: 0L,
                            lang = s.language.orEmpty(),
                            name = s.name.orEmpty(),
                            baseUrl = s.homeUrl.orEmpty(),
                        )
                    }.orEmpty(),
                    apkName = it.resources?.apkUrl ?: "$repoUrl/apk/${it.packageName}.apk",
                    iconUrl = it.resources?.iconUrl ?: "$repoUrl/icon/${it.packageName}.png",
                    repoUrl = repoUrl,
                )
            }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return if (extension.apkName.startsWith("http://") || extension.apkName.startsWith("https://")) {
            extension.apkName
        } else {
            "${extension.repoUrl}/apk/${extension.apkName}"
        }
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return libVersion ?: version.split('.').take(2).joinToString(".").toDoubleOrNull() ?: 0.0
    }

    private fun RepoExtensionEntryJsonObject.extractLibVersion(): Double {
        return extensionLib?.toDoubleOrNull() ?: versionName.split('.').take(2).joinToString(".").toDoubleOrNull() ?: 0.0
    }

    private fun isDummyWarningExtension(pkg: String, name: String): Boolean {
        return pkg == "eu.kanade.tachiyomi.extension.all.keiyoushi" ||
            pkg == "eu.kanade.tachiyomi.extension.all.mihon" ||
            name.contains("Outdated App", ignoreCase = true) ||
            name.contains("Update to Mihon", ignoreCase = true)
    }
}

object AnyStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AnyString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }
}

object AnyLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AnyLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val input = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.content.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}

@Serializable
private data class RepoIndexJsonObject(
    val name: String? = null,
    val extensionList: RepoExtensionListJsonObject? = null,
)

@Serializable
private data class RepoExtensionListJsonObject(
    val extensions: List<RepoExtensionEntryJsonObject> = emptyList(),
)

@Serializable
private data class RepoExtensionEntryJsonObject(
    val name: String,
    val packageName: String,
    val versionName: String,
    @Serializable(with = AnyStringSerializer::class)
    val versionCode: String? = null,
    @Serializable(with = AnyStringSerializer::class)
    val extensionLib: String? = null,
    val contentWarning: String? = null,
    val resources: RepoExtensionResourcesJsonObject? = null,
    val sources: List<RepoExtensionSourceJsonObject>? = null,
)

@Serializable
private data class RepoExtensionResourcesJsonObject(
    val apkUrl: String? = null,
    val iconUrl: String? = null,
    val jarUrl: String? = null,
)

@Serializable
private data class RepoExtensionSourceJsonObject(
    @Serializable(with = AnyStringSerializer::class)
    val id: String? = null,
    val name: String? = null,
    val language: String? = null,
    val homeUrl: String? = null,
)

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    @Serializable(with = AnyLongSerializer::class)
    val code: Long = 0L,
    val version: String,
    val nsfw: Int = 0,
    val sources: List<ExtensionSourceJsonObject>? = null,
    val libVersion: Double? = null,
)

@Serializable
private data class ExtensionSourceJsonObject(
    @Serializable(with = AnyLongSerializer::class)
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> Extension.Available.Source = {
    Extension.Available.Source(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
