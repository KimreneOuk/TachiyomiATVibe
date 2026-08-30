package eu.kanade.translation.manager

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.ActiveChapterStoreRegistry
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.artifact.LegacyFlatFileDecoder
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.Dispatchers
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

// T909 Phase 13: durable-status resolution moved from `TranslationManager`
// (cache read/write + probe + document lookup + probe-store adoption). The
// cache map itself stays a `TranslationManager` field — the durable tests
// reflection-write that exact field — so the resolver reads it through a
// provider and the manager builds this resolver per access from its current
// field values.

internal data class DurableChapterKey(
    val chapterId: Long?,
    val chapterName: String,
    val chapterScanlator: String?,
    val mangaTitle: String,
    val sourceId: Long,
)

internal data class DurableStatus(val state: Translation.State)

internal data class TranslationDocument(
    val parent: UniFile,
    val fileName: String,
    val file: UniFile?,
) {
    val registryKey: String get() = "${parent.filePath ?: parent.uri}:$fileName"
}

internal class DurableChapterStatusResolver(
    private val providerProvider: () -> TranslationProvider,
    private val sourceManagerProvider: () -> SourceManager,
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry,
    private val durableStatusCacheProvider: () -> ConcurrentHashMap<DurableChapterKey, DurableStatus>,
) {

    // Same-name dependency reads the moved bodies use; resolved through the
    // manager's provider lambdas at each call so the manager builds this
    // resolver per access from its current field values. The dependencies are
    // deliberately NOT resolved at construction: invalidation-only callers
    // (cache clears) must never depend on fields a reflection-built test
    // manager may have left unset.
    private val provider get() = providerProvider()

    private val sourceManager get() = sourceManagerProvider()

    private val activeStores get() = activeStoresProvider()

    private val durableStatusCache get() = durableStatusCacheProvider()

    /**
     * Invalidates every cached durable status. This is protocol, not detail:
     * opens, rescues, and deletes change durable truth, and a missed clear
     * resurrects stale TRANSLATED states. All manager-side invalidations
     * route through this method (see the durableStatusCache audit in the
     * T909 Phase 13 delivery report).
     */
    fun clearDurableStatusCache() {
        durableStatusCache.clear()
    }

    // T909 Phase 3a: legacy decode/quarantine bodies live in
    // legacy/LegacyFlatFileDecoder.kt; the moved resolve body keeps calling
    // the same-name collaborator.
    private fun decodeLegacyChapterStatus(
        file: UniFile,
        chapterName: String,
    ): Translation.State? = LegacyFlatFileDecoder.decodeLegacyChapterStatus(file, chapterName)

    fun persistedChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? {
        val key = DurableChapterKey(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)
        durableStatusCache[key]?.let { return it.state }
        val state = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            resolveDurableChapterStatus(
                chapterId,
                chapterName,
                chapterScanlator,
                mangaTitle,
                sourceId,
            )
        }
        // A null result includes an absent document, a failed probe, and a
        // recoverable rescue/permission error. Do not turn that transient
        // outcome into a same-manager cache hit that hides a later retry.
        state?.let { durableStatusCache[key] = DurableStatus(it) }
        return state
    }

    private suspend fun resolveDurableChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? {
        val source = sourceManager.get(sourceId) ?: return null
        val document = findTranslationDocument(chapterName, chapterScanlator, mangaTitle, source)
            ?: return null
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
        return when {
            manifestProbe.exists && manifestProbe.manifest?.authority == ManifestAuthority.ARTIFACTS -> {
                withProbeStore(document, chapterId) { store ->
                    store.artifactStatus()
                }
            }
            manifestProbe.exists && manifestProbe.manifest == null -> {
                // A present but unreadable manifest must not fall back to stale flat JSON.
                withProbeStore(document, chapterId) { store ->
                    store.artifactStatus()
                }
            }
            else -> document.file?.let { decodeLegacyChapterStatus(it, chapterName) }
        }
    }

    fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? {
        val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
        val parent = file?.parentFile ?: provider.findMangaDir(mangaTitle, source) ?: return null
        val fileName = file?.name ?: provider.getTranslationFileName(chapterName, scanlator)
        return TranslationDocument(parent, fileName, file ?: parent.findFile(fileName))
    }

    private suspend fun <T> withProbeStore(
        document: TranslationDocument,
        chapterId: Long?,
        block: suspend (ChapterTranslationStore) -> T,
    ): T? {
        if (chapterId != null) {
            activeStores.get(chapterId)?.let { return block(it) }
        }
        val result = activeStores.getOrCreateProbe(document.registryKey) {
            if (document.file?.exists() == true) {
                ChapterTranslationStore.open(document.file)
            } else {
                ChapterTranslationStore.openArtifact(document.parent, document.fileName)
            }
        } ?: return null
        // A probe can perform the one-way rescue and rename intent recovery
        // while it opens. Any status cached before that transition is stale.
        durableStatusCache.clear()
        return try {
            block(result.store)
        } finally {
            if (result.owned && activeStores.releaseProbe(document.registryKey, result.store)) {
                result.store.closeAndFlush()
            }
        }
    }
}
