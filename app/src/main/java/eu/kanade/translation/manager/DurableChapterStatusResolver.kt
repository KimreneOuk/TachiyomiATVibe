package eu.kanade.translation.manager

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.storage.ActiveChapterStoreRegistry
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.orchestration.ReaderEntryTrace
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * Identity of a translation document location. The document path cannot
 * legitimately move mid-session, so resolved documents are memoized per key
 * and invalidated by the same [clearDurableStatusCache] protocol as statuses
 * — every durable mutation routes through it. Negative results are NOT
 * memoized: a document may appear when a translation is first created.
 */
internal data class DurableDocumentKey(
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
    private val durableDocumentCacheProvider: () -> ConcurrentHashMap<DurableDocumentKey, TranslationDocument> = { ConcurrentHashMap() },
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

    private val durableDocumentCache get() = durableDocumentCacheProvider()

    /**
     * Invalidates every cached durable status and memoized document. This is
     * protocol, not detail: opens, rescues, and deletes change durable truth,
     * and a missed clear resurrects stale TRANSLATED states. All manager-side
     * invalidations route through this method (see the durableStatusCache
     * audit in the T909 Phase 13 delivery report).
     */
    fun clearDurableStatusCache() {
        durableStatusCache.clear()
        durableDocumentCache.clear()
    }

    // T912 ANR fix: suspend. This resolution reopens the durable artifact
    // store over SAF/UniFile and reads page snapshots — O(pages) FUSE/binder
    // round-trips (60-130 ms per page observed on a 68-page chapter). The
    // previous `runBlocking(Dispatchers.IO)` here parked the calling thread
    // for the full duration and produced 5s+ main-thread ANRs when reached
    // from ReaderViewModel.loadChapter and MangaScreenModel's chapter list.
    // The cache read stays first and synchronous; only the durable miss hops
    // to IO. Semantics are unchanged: same cache writes (non-null only), same
    // returned states.
    suspend fun persistedChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? {
        val key = DurableChapterKey(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)
        durableStatusCache[key]?.let { return it.state }
        val state = withContext(Dispatchers.IO) {
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
        return if (manifestProbe.exists) {
            withProbeStore(document, chapterId) { store -> store.artifactStatus() }
        } else {
            null
        }
    }

    fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? {
        // The document location cannot legitimately move mid-session, and
        // one reader entry used to walk the SAF tree for it 5-7 times
        // (60-210ms each on device). Memoize positive results; callers
        // already re-check document.file existence before opening.
        val memoKey = DurableDocumentKey(chapterName, scanlator, mangaTitle, source.id)
        durableDocumentCache[memoKey]?.let { return it }
        val entryStage = ReaderEntryTrace.begin("translation.findDocument", null)
        val document = try {
            val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
            val parent = file?.parentFile ?: provider.findMangaDir(mangaTitle, source) ?: return null
            val fileName = file?.name ?: provider.getTranslationFileName(chapterName, scanlator)
            TranslationDocument(parent, fileName, file ?: parent.findFile(fileName))
        } finally {
            entryStage.end()
        }
        durableDocumentCache[memoKey] = document
        return document
    }

    /**
     * T911 slice 3: read-through durable store access for terminal snapshot
     * reconstruction (registry miss after process death / eviction). Prefers
     * the active store; otherwise opens the durable document through the
     * bounded probe registry and releases it afterwards. No result is cached
     * here — the caller projects and discards, keeping memory bounded.
     */
    internal suspend fun <T> withDurableStore(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
        block: suspend (ChapterTranslationStore) -> T,
    ): T? {
        // An active store is already the reader's authority. Return it before
        // resolving the source or walking storage so the in-memory active
        // path remains probe-free during progress projection.
        if (chapterId != null) {
            activeStores.get(chapterId)?.let { return block(it) }
        }
        val source = sourceManager.get(sourceId) ?: return null
        val document = findTranslationDocument(chapterName, chapterScanlator, mangaTitle, source)
            ?: return null
        if (!ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName).exists) {
            return null
        }
        return withProbeStore(document, chapterId, block)
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
            ChapterTranslationStore.openArtifact(document.parent, document.fileName)
        } ?: return null
        // A newly created probe can perform the one-way rescue and rename
        // intent recovery while it opens; statuses cached before that
        // transition are stale. Reused probes already had their wipe when
        // they were created — wiping the whole cache again on every probe
        // pass reduced the cache to a single surviving entry whenever the
        // chapter list resolved statuses sequentially.
        if (result.created) {
            durableStatusCache.clear()
        }
        val statusWriter = ActiveChapterStoreRegistry.registerWriter(
            chapterId = chapterId,
            chapterKey = document.registryKey,
            origin = eu.kanade.translation.pipeline.WriterOrigin.STATUS_RESOLVER,
        )
        val probeStage = ReaderEntryTrace.begin("probe.status", chapterId)
        return try {
            block(result.store)
        } finally {
            statusWriter.close()
            probeStage.end()
            if (result.owned && activeStores.releaseProbe(document.registryKey, result.store)) {
                val flushStage = ReaderEntryTrace.begin("probe.closeAndFlush", chapterId)
                try {
                    result.store.closeAndFlush()
                } finally {
                    flushStage.end()
                }
            }
        }
    }
}
