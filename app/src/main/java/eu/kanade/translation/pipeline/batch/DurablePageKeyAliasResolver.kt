package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.persistence.artifact.isSha256Hex

/** Resolves old source keys from persisted identity and current downloaded bytes only. */
internal object DurablePageKeyAliasResolver {

    /**
     * Returns only one-to-one aliases. Callers must provide a complete set of valid
     * downloaded hashes so duplicate-content pages cannot be hidden by a partial scan.
     */
    fun resolve(
        persistedPages: Map<String, PageTranslationView>,
        manifestSourceShaByPageKey: Map<String, String>,
        downloadedSourceShaByPageKey: Map<String, String>,
    ): Map<String, String> {
        if (downloadedSourceShaByPageKey.isEmpty()) return emptyMap()
        if (downloadedSourceShaByPageKey.values.any { !it.isSha256Hex() }) return emptyMap()

        val downloadedKeys = downloadedSourceShaByPageKey.keys
        val persistedByHash = mutableMapOf<String, MutableList<String>>()
        val ambiguousHashes = mutableSetOf<String>()
        persistedPages.forEach { (key, page) ->
            if (key in downloadedKeys) return@forEach
            val recordedPageSha = page.sourceFingerprint
            val recordedManifestSha = manifestSourceShaByPageKey[key]
            val evidence = listOfNotNull(recordedPageSha, recordedManifestSha)
            val validEvidence = evidence.filter(String::isSha256Hex).distinct()
            if (
                evidence.any { !it.isSha256Hex() } ||
                validEvidence.size > 1
            ) {
                ambiguousHashes += validEvidence
            } else {
                validEvidence.singleOrNull()?.let { sha ->
                    persistedByHash.getOrPut(sha) { mutableListOf() } += key
                }
            }
        }

        val downloadedByHash = downloadedSourceShaByPageKey.entries
            .groupBy({ it.value }, { it.key })

        return buildMap {
            persistedByHash.forEach { (sha, oldKeys) ->
                if (sha in ambiguousHashes) return@forEach
                val newKeys = downloadedByHash[sha].orEmpty()
                if (oldKeys.size == 1 && newKeys.size == 1) {
                    put(oldKeys.single(), newKeys.single())
                }
            }
        }
    }
}
