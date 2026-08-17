package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import tachiyomi.core.common.preference.getAndSet

class TrustExtension(
    private val extensionRepoRepository: ExtensionRepoRepository,
    private val preferences: SourcePreferences,
) {

    private val officialFingerprints = setOf(
        "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905",
        "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        "a5e2027162d4b7294d54fd069af923eee4078cc097765d3a0d84a747d3a8356e",
    )

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val trustedFingerprints = extensionRepoRepository.getAll()
            .map { it.signingKeyFingerprint.lowercase() }
            .toHashSet()
        trustedFingerprints.addAll(officialFingerprints)
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last().lowercase()}"
        return fingerprints.any { it.lowercase() in trustedFingerprints } || key in preferences.trustedExtensions().get()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions().getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions().delete()
    }
}
