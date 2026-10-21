package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.persistence.ChapterArtifactEngine.TransactionOutcome
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns display snapshot publication, committed display pointer management,
 * and cleaned-image reference tracking and authorization for [ChapterTranslationStore].
 */
internal class ChapterDisplayPublication(
    private val store: ChapterTranslationStore,
) {
    private val mutex get() = store.mutex

    /**
     * Atomic committed-display promotion: when the page just reached a fully
     * displayable state (cleaned image file published and verified upstream by
     * [CleanedImagePublisher], translation READY/PARTIAL, some
     * translated text), the committed pointer swaps to a frozen snapshot of
     * exactly that state. A superseded bundle\'s cleaned file is retained until
     * the pipeline drains it, never deleted under a live committed pointer.
     */
    internal fun promoteDisplayIfReadyLocked(pageKey: String, updated: PageTranslation) {
        val published = store.pages[pageKey] ?: return
        if (pageKey.isEmpty() || (!published.isTranslationDisplayReady && !published.isTextlessTerminal)) return
        val fingerprint = displayFingerprintOf(published)
        val existing = store.committedDisplay[pageKey]
        if (existing != null && existing.displayFingerprint == fingerprint) return
        if (existing != null) {
            existing.page.cleanedImageName
                ?.takeIf { it != published.cleanedImageName && it.isNotEmpty() }
                ?.let {
                    store.retiredCleanedImages
                        .computeIfAbsent(pageKey) { ConcurrentHashMap.newKeySet() }
                        .add(it)
                }
        }
        store.committedDisplay = store.committedDisplay.put(
            pageKey,
            ChapterTranslationStore.CommittedPageDisplay(
                page = published,
                pageVersion = published.pageVersion,
                displayFingerprint = fingerprint,
                promotedAtEpochMs = published.updatedAt,
            ),
        )
    }

    /**
     * Atomically updates only changed display keys; all other immutable values are shared.
     * The fast-cancel path also calls this off-mutex, so the flow update must stay retry-safe.
     */
    internal fun displaySnapshotLocked(
        changedPageKeys: Iterable<String>,
    ) {
        val keys = changedPageKeys.toList()
        store.publishedDisplay.update { current ->
            var updated = current
            keys.forEach { pageKey ->
                val page = store.pages[pageKey]
                updated = if (page == null) {
                    updated.remove(pageKey)
                } else {
                    updated.put(pageKey, store.committedDisplay[pageKey]?.page ?: page)
                }
            }
            updated
        }
    }

    internal fun displayFingerprintOf(page: PageTranslationView): String =
        StageFingerprints.canonicalFingerprint(
            buildList {
                add(page.cleanedImageName)
                add(page.inpaintRevision)
                addAll(page.blockFingerprints())
            },
        )

    /** Resolves the reader-facing page state: committed bundle first, live candidate otherwise. */
    fun resolveDisplayPage(pageKey: String): PageTranslationView? =
        store.committedDisplay[pageKey]?.page ?: store.pages[pageKey]

    /**
     * Lazily loads full page snapshot (including text blocks) from durable storage
     * if the page is currently backed by a synthesized or empty placeholder.
     */
    suspend fun getOrLoadPageSnapshot(pageKey: String): PageTranslation? =
        mutex.withLock {
            val current = store.pages[pageKey]
            if (current != null && current.blocks.isNotEmpty()) {
                return@withLock current.toDraft()
            }
            val record = store.artifactManifest?.pages?.get(pageKey)
            val snapshotFileName = record?.candidate?.pageSnapshotFileName
                ?: record?.committed?.pageSnapshotFileName
            val engine = store.artifactEngine
            if (snapshotFileName != null && engine != null) {
                val loaded = engine.readPageSnapshot(snapshotFileName)
                if (loaded != null) {
                    val published = store.publishPage(loaded)
                    store.pages = store.pages.put(pageKey, published)
                    store.stateFlow.value = store.snapshotPages()
                    if (record?.committed != null) {
                        store.committedDisplay = store.committedDisplay.put(
                            pageKey,
                            ChapterTranslationStore.CommittedPageDisplay(
                                page = published,
                                pageVersion = loaded.pageVersion,
                                displayFingerprint = displayFingerprintOf(loaded),
                                promotedAtEpochMs = loaded.updatedAt,
                            ),
                        )
                    }
                    displaySnapshotLocked(listOf(pageKey))
                    return@withLock loaded
                }
            }
            current?.toDraft()
        }

    /** The frozen committed display bundle for [pageKey], if one exists. */
    internal fun committedDisplayPage(pageKey: String): PageTranslationView? =
        store.committedDisplay[pageKey]?.page

    /**
     * True while [name] still backs the committed display bundle (or its
     * retained predecessor) for [pageKey]; such files must not be deleted
     * before a newer bundle promotes.
     */
    fun mayDeleteCleanedImage(pageKey: String, name: String): Boolean {
        if (store.pages[pageKey]?.cleanedImageName == name || store.committedDisplay[pageKey]?.page?.cleanedImageName == name) {
            return false
        }
        // Rekey can leave an already-queued retirement callback carrying the old
        // pageKey after ownership has moved. Check every live/committed/retained
        // reference before deleting the shared image or its identity sidecar.
        return name !in referencedCleanedImageNames()
    }

    /**
     * Point-in-time store authorization to physically delete a cleaned image
     * name: the same predicate as [mayDeleteCleanedImage], evaluated under
     * [mutex].
     */
    suspend fun authorizeCleanedImageDeletion(pageKey: String, name: String): Boolean = mutex.withLock {
        mayDeleteCleanedImage(pageKey, name)
    }

    /** Protect a companion image still owned by a different page after page reset/deletion. */
    fun isCleanedImageReferencedByAnotherPage(pageKey: String, name: String): Boolean =
        store.pages.any { (owner, page) -> owner != pageKey && page.cleanedImageName == name } ||
            store.committedDisplay.any { (owner, page) -> owner != pageKey && page.page.cleanedImageName == name } ||
            store.retiredCleanedImages.any { (owner, names) -> owner != pageKey && name in names } ||
            store.artifactManifest?.pages?.any { (owner, record) ->
                owner != pageKey &&
                    (record.committed?.displayBase?.fileName == name || record.previousCommitted?.displayBase?.fileName == name)
            } == true

    /** All cleaned-image names still reachable from live, committed, or retired state. */
    fun referencedCleanedImageNames(): Set<String> = buildSet {
        store.pages.values.mapNotNullTo(this) { it.cleanedImageName }
        store.committedDisplay.values.mapNotNullTo(this) { it.page.cleanedImageName }
        store.retiredCleanedImages.values.forEach { addAll(it) }
        store.artifactManifest?.pages?.values?.forEach { record ->
            record.committed?.displayBase?.fileName?.let(::add)
            record.previousCommitted?.displayBase?.fileName?.let(::add)
        }
    }

    /** Atomically takes every superseded cleaned-image name for [pageKey]. */
    fun drainRetiredCleanedImages(pageKey: String): List<String> =
        store.retiredCleanedImages.remove(pageKey)?.toList().orEmpty()

    /**
     * Explicitly drops the committed display pointer — reserved for user
     * resets (reset translation/inpaint data), never for candidate retries.
     */
    suspend fun demoteCommittedDisplay(pageKey: String, reason: String): String? {
        if (store.isDefunct) return null
        return mutex.withLock {
            if (store.committedDisplay.containsKey(pageKey)) {
                store.committedDisplay = store.committedDisplay.remove(pageKey)
                store.retiredCleanedImages.remove(pageKey)
                val demotion = demoteArtifactPageLocked(pageKey)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT committed display demoted: pageKey=$pageKey reason=$reason"
                }
                displaySnapshotLocked(listOf(pageKey))
                demotion
            } else {
                null
            }
        }
    }

    internal fun cancelArtifactCandidateLocked(pageKey: String): Boolean {
        val artifact = store.artifactEngine ?: return true
        val manifest = store.artifactManifest ?: return true
        val candidate = manifest.pages[pageKey]?.candidate ?: return true
        val outcome = artifact.candidates.cancelLiveCandidate(manifest, pageKey, candidate.generationId)
        if (outcome is TransactionOutcome.Committed) {
            store.artifactManifest = outcome.manifest
            return true
        } else if (outcome is TransactionOutcome.Rejected) {
            store.publicationBridge.rebaseManifestAfterStaleRejection(artifact, outcome.reason)
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate cancel rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
        return false
    }

    /** Caller holds [mutex]; null when the destructive demotion is durably complete. */
    private fun demoteArtifactPageLocked(pageKey: String): String? {
        val artifact = store.artifactEngine ?: return null
        val manifest = store.artifactManifest ?: return null
        val outcome = artifact.candidates.demoteLivePage(manifest, pageKey)
        return when (outcome) {
            is TransactionOutcome.Committed -> {
                store.artifactManifest = outcome.manifest
                outcome.unverifiedBackupReason?.let { reason ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact display demotion published without a verified backup: " +
                            "pageKey=$pageKey reason=$reason"
                    }
                    return "unverified backup after demotion: $reason"
                }
                null
            }
            is TransactionOutcome.Rejected -> {
                store.publicationBridge.rebaseManifestAfterStaleRejection(artifact, outcome.reason)
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact display demotion rejected: pageKey=$pageKey reason=${outcome.reason}"
                }
                outcome.reason
            }
        }
    }
}
