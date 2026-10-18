package eu.kanade.translation.workflow

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterStatusProjection
import eu.kanade.translation.persistence.chapter.ChapterStoreOpener
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.DurableChapterRead
import eu.kanade.translation.persistence.chapter.DurableChapterState
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/** Resolves chapter status and artifact document locations from durable translation records. */

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
) {
    val registryKey: String get() = "${parent.filePath ?: parent.uri}:$fileName"
}

/**
 * One read-through durable access for status and terminal-snapshot
 * consumers. An active store remains the reader's authority and keeps its
 * live semantics; the fallback is the opener's publication-free durable read
 * — no store, writer, probe registry, or journal bridge is ever constructed.
 */
internal sealed interface DurableChapterAccess {
    data class ActiveStore(val store: ChapterTranslationStore) : DurableChapterAccess
    data class DurableRead(val read: DurableChapterState) : DurableChapterAccess
}

internal class DurableChapterStatusResolver(
    private val providerProvider: () -> TranslationFileProvider,
    private val sourceManagerProvider: () -> SourceManager,
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry,
) {

    // Resolve collaborators lazily so cache-only invalidation does not open providers or stores.
    private val provider get() = providerProvider()

    private val sourceManager get() = sourceManagerProvider()

    private val activeStores get() = activeStoresProvider()

    private val durableStatusCache = ConcurrentHashMap<DurableChapterKey, DurableStatus>()

    // Memoized translation-document locations share the status invalidation boundary.
    private val durableDocumentCache = ConcurrentHashMap<DurableDocumentKey, TranslationDocument>()

    /**
     * Invalidates every cached durable status and memoized document. This is
     * protocol, not detail: opens, journal recovery/seeding, and deletes change durable truth,
     * and a missed clear can resurrect stale TRANSLATED states. All status and document
     * invalidations route through this method.
     */
    fun clearDurableStatusCache() {
        durableStatusCache.clear()
        durableDocumentCache.clear()
    }

    // Durable misses may perform O(pages) SAF/UniFile reads. Keep the cache check synchronous,
    // but suspend and hop to IO for a miss so reader/UI callers never park their thread.
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
        // A null result includes an absent artifact, an unsupported future
        // schema, and corrupt manifest evidence. None of those may become a
        // same-manager cache hit that hides a later retry; an environment
        // failure propagates instead of resolving at all.
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
        val read = ChapterStoreOpener.readDurableChapter(
            document.parent,
            document.fileName,
            provider.privateJournalRoot,
            provider.privateJournalIdentity(source, mangaTitle, document.fileName),
        )
        // Unsupported future schemas count as ABSENT for planning (bytes are
        // preserved untouched); corrupt evidence resolves null rather than
        // masquerading as a cleanly new chapter.
        val state = (read as? DurableChapterRead.Supported)?.state ?: return null
        return ChapterStatusProjection.artifactStatus(state.statusProjectionData()) { _ ->
            state.activeRunRecord
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
        // (60-210ms each on device). Memoize positive directory/name pairs;
        // callers read the durable chapter state before projecting.
        val memoKey = DurableDocumentKey(chapterName, scanlator, mangaTitle, source.id)
        durableDocumentCache[memoKey]?.let { return it }
        val entryStage = ReaderEntryTrace.begin("translation.findDocument", null)
        val document = try {
            val parent = provider.findMangaDir(mangaTitle, source) ?: return null
            val fileName = provider.getTranslationFileName(chapterName, scanlator)
            TranslationDocument(parent, fileName)
        } finally {
            entryStage.end()
        }
        durableDocumentCache[memoKey] = document
        return document
    }

    /**
     * Read-through durable access for terminal snapshot reconstruction (registry miss
     * after process death or eviction). Prefers
     * the active store; otherwise reads the durable document publication-free
     * through the opener and hands the caller the pure durable state. No
     * result is cached here — the caller projects and discards, keeping
     * memory bounded. No durable truth can advance on this path, so no
     * status-cache wipe fires.
     */
    internal suspend fun <T> withDurableRead(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
        block: suspend (DurableChapterAccess) -> T,
    ): T? {
        // An active store is already the reader's authority. Return it before
        // resolving the source or walking storage so the in-memory active
        // path remains I/O-free during progress projection.
        if (chapterId != null) {
            activeStores.get(chapterId)?.let { return block(DurableChapterAccess.ActiveStore(it)) }
        }
        val source = sourceManager.get(sourceId) ?: return null
        val document = findTranslationDocument(chapterName, chapterScanlator, mangaTitle, source)
            ?: return null
        return when (
            val read = ChapterStoreOpener.readDurableChapter(
                document.parent,
                document.fileName,
                provider.privateJournalRoot,
                provider.privateJournalIdentity(source, mangaTitle, document.fileName),
            )
        ) {
            is DurableChapterRead.Supported -> block(DurableChapterAccess.DurableRead(read.state))
            is DurableChapterRead.Missing,
            is DurableChapterRead.Unsupported,
            is DurableChapterRead.Corrupt,
            -> null
        }
    }
}
