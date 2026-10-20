package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageStage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// The store mutex guards lease mutations and patch paths. Synchronized reads
// protect inspection that does not take that mutex; release/cancel paths stay
// NonCancellable so cancellation cannot strand an owned lease. The backing map
// is shared with the store and must never be copied.
internal class PageStageLeaseTable(private val store: ChapterTranslationStore) {

    private val mutex get() = store.mutex
    private val defunct get() = store.isDefunct
    private val generation get() = store.currentGeneration
    private val pages get() = store.pages

    private fun snapshotLocked(pageKey: String) = store.snapshotLocked(pageKey)

    private fun cancelArtifactCandidateLocked(pageKey: String): Boolean =
        store.cancelArtifactCandidateLocked(pageKey)

    /** Writer leases per page: one origin owns a page until it releases it. */
    val pageLeases = ConcurrentHashMap<String, PageLeaseRecord>()
    private var nextLeaseToken = 0L

    internal data class PageLeaseRecord(
        val token: Long,
        val origin: PageWriteOrigin,
        val stage: PageStage,
        val generation: Long,
    )

    /** The reader session may preempt AUTO work; every other occupied page is denied. */
    suspend fun tryAcquirePageStageLease(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = mutex.withLock {
        if (defunct) return@withLock LeaseAcquisition.Denied("store is defunct", null)
        val existing = pageLeases[pageKey]
        val evictsAuto = existing?.origin == PageWriteOrigin.AUTO && origin == PageWriteOrigin.MANUAL
        if (existing != null && !evictsAuto) {
            return@withLock LeaseAcquisition.Denied(
                "page owned by ${existing.origin} at stage ${existing.stage}",
                existing.origin,
            )
        }
        acquireLocked(pageKey, stage, origin)
    }

    /** Acquires only when the page has no current owner. */
    suspend fun tryAcquirePageStageLeaseIfUnowned(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = mutex.withLock {
        if (defunct) return@withLock LeaseAcquisition.Denied("store is defunct", null)
        val existing = pageLeases[pageKey]
        if (existing != null) {
            return@withLock LeaseAcquisition.Denied(
                "page owned by ${existing.origin} at stage ${existing.stage}",
                existing.origin,
            )
        }
        acquireLocked(pageKey, stage, origin)
    }

    private fun acquireLocked(pageKey: String, stage: PageStage, origin: PageWriteOrigin): LeaseAcquisition.Granted {
        val current = pages[pageKey]
        val snapshot = snapshotLocked(pageKey)
        val token = ++nextLeaseToken
        pageLeases[pageKey] = PageLeaseRecord(token, origin, stage, generation)
        return LeaseAcquisition.Granted(
            PageStageLease(
                pageKey = pageKey,
                stage = stage,
                origin = origin,
                generation = generation,
                pageVersion = current?.pageVersion ?: 0L,
                token = token,
                candidateGenerationId = snapshot.candidateGenerationId,
                dependencyFingerprint = snapshot.dependencyFingerprint,
                artifactPageVersion = snapshot.artifactPageVersion,
            ),
        )
    }

    suspend fun releasePageStageLease(pageKey: String, origin: PageWriteOrigin) {
        withContext(NonCancellable) {
            mutex.withLock {
                synchronized(pageLeases) {
                    if (pageLeases[pageKey]?.origin == origin) pageLeases.remove(pageKey)
                }
            }
        }
    }

    /** Releases the lease only while the caller still owns its token. */
    suspend fun releasePageStageLease(
        pageKey: String,
        origin: PageWriteOrigin,
        expectedToken: Long,
    ): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            synchronized(pageLeases) {
                val record = pageLeases[pageKey]
                val matches = record?.origin == origin && record.token == expectedToken
                if (matches) pageLeases.remove(pageKey)
                matches
            }
        }
    }

    /** Cancels the active artifact candidate and releases its matching writer lease. */
    suspend fun cancelPageStageWork(pageKey: String, origin: PageWriteOrigin): Boolean =
        withContext(NonCancellable) {
            mutex.withLock {
                synchronized(pageLeases) {
                    val lease = pageLeases[pageKey]
                    if (lease?.origin == origin) {
                        val cancelled = cancelArtifactCandidateLocked(pageKey)
                        if (cancelled && pageLeases[pageKey]?.origin == origin) {
                            pageLeases.remove(pageKey)
                        }
                        cancelled
                    } else {
                        false
                    }
                }
            }
        }

    /** Releases every lease held by [origin]; used at batch teardown. */
    suspend fun releaseAllPageLeases(origin: PageWriteOrigin) {
        withContext(NonCancellable) {
            mutex.withLock {
                synchronized(pageLeases) {
                    pageLeases.entries
                        .filter { it.value.origin == origin }
                        .map { it.key }
                        .forEach(pageLeases::remove)
                }
            }
        }
    }

    fun pageLeaseOwner(pageKey: String): PageWriteOrigin? = synchronized(pageLeases) {
        pageLeases[pageKey]?.origin
    }
}
