package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private fun PageTranslationView.isQueueVisibleTransient(): Boolean {
    return ocrStatus.isQueueTransient() ||
        translationStatus.isQueueTransient() ||
        inpaintStatus.isQueueTransient() ||
        renderStatus.isQueueTransient()
}

private fun String.isQueueTransient(): Boolean {
    return this == StageStatus.PENDING ||
        this == StageStatus.RUNNING ||
        this == StageStatus.FAILED ||
        this == StageStatus.CANCELLED
}

private fun String.cancelIfTransient(): String {
    return if (isQueueTransient()) StageStatus.CANCELLED else this
}

/**
 * Bulk-mutation collaborator (Phase 7 S7). Owns the multi-page store
 * transactions ([deletePage], [replaceAll], [rekeyPages],
 * [preRegisterPages], [clearTransientQueuePages]) and the two-phase re-key
 * planning machinery. Every transaction keeps its journal-frame semantics:
 * one credit reservation and one commit sequence per bulk frame, captured
 * through the journal bridge (Phase 7 S5). The owning store delegates each
 * public entry point here under the producer capture permit and the store
 * mutex, so the Phase 7 S2 lock order is unchanged.
 */
internal class ChapterBulkMutator(private val store: ChapterTranslationStore) {

    private data class PageRekeyPlan(
        val generation: Long,
        val pages: PersistentMap<String, PublishedPageTranslation>,
        val committedDisplay: PersistentMap<String, ChapterTranslationStore.CommittedPageDisplay>,
        val manifest: ChapterArtifactManifest?,
        val moves: Map<String, String>,
        val oldArtifactContentHashes: Map<String, String?>,
        /** Published values are immutable; snapshot conversion and hashing happen off-lock. */
        val livePageSnapshots: Map<String, PublishedPageTranslation>,
        val leaseTokens: Map<String, Long?>,
        val requiresArtifactTransaction: Boolean,
        val changedDisplayKeys: Set<String>,
        /** Destination keys whose payload-less placeholder record is consumed by a move. */
        val consumedPlaceholders: Set<String>,
        /** Moves refused because the destination record carries real payload. */
        val skippedCollisions: Int,
    )

    private sealed class PageRekeyPlanResult {
        data class Ready(val plan: PageRekeyPlan) : PageRekeyPlanResult()

        data class Immediate(val outcome: ChapterTranslationStore.PageRekeyOutcome) : PageRekeyPlanResult()

        data class Rejected(val reason: String) : PageRekeyPlanResult()
    }

    /** Serializes two-phase re-key preparations without blocking ordinary page mutations. */
    private val pageRekeyMutex = Mutex()

    /**
     * P3B typed reset receipt: null when the page is durably removed from the
     * authoritative manifest (verified destructive publication); a non-null
     * reason when the publication completed without a verified same-state
     * backup or was rejected — typed incomplete, never a silent success.
     */
    suspend fun deletePage(pageKey: String): String? {
        if (store.defunct) return null
        return store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store deletePage rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock "artifact admission rejected: ${admission.message}"
                }
            }
            var deletionIncomplete: String? = null
            if (store.pages.containsKey(pageKey)) {
                deletionIncomplete = deleteArtifactPageLocked(pageKey)
                store.pages = store.pages.remove(pageKey)
                store.committedDisplay = store.committedDisplay.remove(pageKey)
                store.retiredCleanedImages.remove(pageKey)
                store.pageLeases.remove(pageKey)
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(listOf(pageKey))
            }
            deletionIncomplete
        }
    }

    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) {
        if (store.defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store replaceAll rejected: store is defunct"
            }
            return
        }
        store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store replaceAll rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock
                }
            }
            val changedDisplayKeys = store.pages.keys + updatedPages.keys
            if (store.artifactManifest != null) {
                store.artifactManifest?.pages?.keys?.toList().orEmpty().forEach { pageKey ->
                    deleteArtifactPageLocked(pageKey)
                }
            }
            store.pages = persistentMapOf()
            store.committedDisplay = persistentMapOf()
            store.retiredCleanedImages.clear()
            updatedPages.forEach { (pageKey, page) ->
                val owned = store.ownedPage(pageKey, page)
                store.pages = store.pages.put(pageKey, store.publishPage(owned))
                if (store.publicationBridge.persistArtifactMutation(pageKey, null, owned)) {
                    store.promoteDisplayIfReadyLocked(pageKey, owned)
                }
            }
            store.pendingExpectedPageCount = updatedPages.size
            store.pendingExpectedPageCountTrusted = false
            store.stateFlow.value = store.snapshotPages()
            store.displaySnapshotLocked(changedDisplayKeys)
        }
    }

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): ChapterTranslationStore.PageRekeyOutcome {
        if (store.defunct || onlineKeys.size != onDiskKeys.size) {
            return ChapterTranslationStore.PageRekeyOutcome.Rejected("defunct store or mapping size mismatch")
        }
        return pageRekeyMutex.withLock {
            val planResult = store.mutex.withLock { createPageRekeyPlanLocked(onlineKeys, onDiskKeys) }
            val plan = when (planResult) {
                is PageRekeyPlanResult.Ready -> planResult.plan
                is PageRekeyPlanResult.Immediate -> return@withLock planResult.outcome
                is PageRekeyPlanResult.Rejected ->
                    return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(planResult.reason)
            }
            val artifact = store.artifactEngine
            val preparedSnapshots = if (plan.requiresArtifactTransaction) {
                withContext(store.persistenceDispatcher) {
                    artifact?.preparePageSnapshotRekey(
                        manifest = checkNotNull(plan.manifest),
                        moves = plan.moves,
                        livePages = plan.livePageSnapshots,
                        operationId = UUID.randomUUID().toString(),
                    )
                } ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store rekeyPages rejected: page snapshot preparation failed"
                    }
                    return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                        "page snapshot preparation failed",
                    )
                }
            } else {
                null
            }
            val preparedManifest = if (plan.requiresArtifactTransaction) {
                val prepared = checkNotNull(preparedSnapshots)
                rekeyedArtifactManifest(
                    base = checkNotNull(plan.manifest),
                    prepared = prepared,
                    moves = plan.moves,
                ) ?: return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                    "re-keyed artifact manifest could not be derived",
                )
            } else {
                null
            }

            store.mutex.withLock {
                if (!isCurrentPageRekeyPlanLocked(plan)) {
                    return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                        "store changed during re-key preparation",
                    )
                }
                if (plan.requiresArtifactTransaction) {
                    val durableManifest = try {
                        checkNotNull(artifact).readManifest()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                            "durable manifest read failed before re-key capture: ${failure.message}",
                            failure,
                        )
                    }
                    if (durableManifest != plan.manifest) {
                        if (durableManifest != null) store.artifactManifest = durableManifest
                        return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                            "durable manifest changed during re-key preparation",
                        )
                    }
                }

                val moveByOldKey = plan.moves
                val moveByNewKey = moveByOldKey.entries.associate { (oldKey, newKey) -> newKey to oldKey }
                val previousPages = store.pages
                var updatedPages = persistentMapOf<String, PublishedPageTranslation>()
                var updatedCommitted = persistentMapOf<String, ChapterTranslationStore.CommittedPageDisplay>()

                previousPages.forEach { (oldKey, page) ->
                    when {
                        moveByOldKey.containsKey(oldKey) -> {
                            val newKey = checkNotNull(moveByOldKey[oldKey])
                            val moved = store.ownedPage(newKey, page.toDraft().copy(sourceFileName = newKey))
                            updatedPages = updatedPages.put(newKey, store.publishPage(moved))
                            store.committedDisplay[oldKey]?.let { committed ->
                                val committedPage = committed.page.toDraft().apply {
                                    sourceFileName = newKey
                                }
                                updatedCommitted = updatedCommitted.put(
                                    newKey,
                                    committed.copy(
                                        page = store.publishPage(committedPage),
                                    ),
                                )
                            }
                        }
                        plan.consumedPlaceholders.contains(oldKey) -> Unit
                        else -> {
                            val updated = page.toDraft()
                            updatedPages = updatedPages.put(
                                oldKey,
                                store.publishPage(store.ownedPage(oldKey, updated)),
                            )
                            store.committedDisplay[oldKey]?.let { committed ->
                                updatedCommitted = updatedCommitted.put(oldKey, committed)
                            }
                        }
                    }
                }

                val pendingAfterRekey = store.pendingArtifactPageRegistrations
                    .mapTo(linkedSetOf()) { pageKey -> moveByOldKey[pageKey] ?: pageKey }

                if (plan.requiresArtifactTransaction) {
                    val base = checkNotNull(plan.manifest)
                    val next = checkNotNull(preparedManifest)
                    when (
                        val outcome = checkNotNull(artifact).publishSidecarPointers(
                            manifest = base,
                            sidecars = emptyList(),
                            updatePointers = { current ->
                                check(current == base) { "re-key manifest base changed after stale check" }
                                next
                            },
                        )
                    ) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                            store.artifactManifest = outcome.manifest
                            store.testSeams.afterRekeyManifestPublicationBeforeLiveSwap?.invoke()
                        }
                        is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                            store.publicationBridge.rebaseManifestAfterStaleRejection(
                                checkNotNull(artifact),
                                outcome.reason,
                            )
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT artifact page re-key publication rejected: ${outcome.reason}"
                            }
                            return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                                "artifact page re-key publication rejected: ${outcome.reason}",
                            )
                        }
                    }
                }

                val movedRetiredImages = moveByOldKey.mapNotNull { (oldKey, newKey) ->
                    store.retiredCleanedImages[oldKey]
                        ?.toSet()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { newKey to it }
                }
                store.pages = updatedPages
                store.committedDisplay = updatedCommitted
                store.pendingArtifactPageRegistrations.clear()
                store.pendingArtifactPageRegistrations.addAll(pendingAfterRekey)
                moveByOldKey.keys.forEach { oldKey -> store.retiredCleanedImages.remove(oldKey) }
                movedRetiredImages.forEach { (newKey, names) ->
                    if (newKey in updatedPages) {
                        store.retiredCleanedImages
                            .computeIfAbsent(newKey) { ConcurrentHashMap.newKeySet<String>() }
                            .addAll(names)
                    }
                }
                store.retiredCleanedImages.keys.toList().forEach { pageKey ->
                    if (pageKey !in updatedPages) store.retiredCleanedImages.remove(pageKey)
                }
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(plan.changedDisplayKeys)
                if (plan.moves.isEmpty()) {
                    if (plan.skippedCollisions > 0) {
                        ChapterTranslationStore.PageRekeyOutcome.Moved(emptyList(), plan.skippedCollisions)
                    } else {
                        ChapterTranslationStore.PageRekeyOutcome.Noop
                    }
                } else {
                    ChapterTranslationStore.PageRekeyOutcome.Moved(
                        plan.moves.entries.map { it.key to it.value },
                        plan.skippedCollisions,
                    )
                }
            }
        }
    }

    suspend fun preRegisterPages(
        pageKeys: List<String>,
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ): ChapterTranslationStore.PagePreRegistration {
        if (pageKeys.isEmpty()) return ChapterTranslationStore.PagePreRegistration.Accepted
        if (store.defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store preRegisterPages rejected: store is defunct"
            }
            return ChapterTranslationStore.PagePreRegistration.Rejected("store is defunct")
        }
        return store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store preRegisterPages rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock ChapterTranslationStore.PagePreRegistration.Rejected(admission.message)
                }
            }
            val foundCount = pageKeys.distinct().size
            val crossCheckKnown = sourceCountKnown && probedSourcePageCount != null
            val crossCheckUnknown = sourceCountKnown && probedSourcePageCount == null
            val targetCount = if (crossCheckKnown) maxOf(foundCount, probedSourcePageCount!!) else foundCount
            store.pendingExpectedPageCount = maxOf(store.pendingExpectedPageCount ?: 0, targetCount)
            store.pendingExpectedPageCountTrusted = store.pendingExpectedPageCountTrusted || !crossCheckUnknown

            val changedPageKeys = mutableSetOf<String>()
            pageKeys.forEach { pageKey ->
                if (!store.pages.containsKey(pageKey)) {
                    store.pages = store.pages.put(
                        pageKey,
                        store.publishPage(store.ownedPage(pageKey, PageTranslation(sourceFileName = pageKey))),
                    )
                    store.pendingArtifactPageRegistrations += pageKey
                    changedPageKeys += pageKey
                }
            }
            if (changedPageKeys.isNotEmpty()) {
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(changedPageKeys)
            }
            if (store.engineMode !is ChapterStoreEngineMode.Durable &&
                store.artifactParent != null &&
                store.artifactFileName != null
            ) {
                store.publicationBridge.ensureArtifactStore()
            }
            val artifact = store.artifactEngine
            val manifest = store.artifactManifest
            val expected = store.pendingExpectedPageCount
            val expectedTrusted = when {
                crossCheckUnknown -> false
                else ->
                    manifest?.expectedPageCountTrusted == true || store.pendingExpectedPageCountTrusted
            }

            val partialInfo = when {
                crossCheckKnown -> {
                    val missing = maxOf(0, probedSourcePageCount!! - foundCount)
                    if (missing == 0) {
                        null
                    } else {
                        PartialBatchInfo(
                            expectedSourcePageCount = probedSourcePageCount,
                            missingPageCount = missing,
                            determinedFrom = PartialBatchDetermination.DOWNLOAD_CROSSCHECK,
                            recordedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                }
                crossCheckUnknown -> PartialBatchInfo(
                    expectedSourcePageCount = null,
                    missingPageCount = 0,
                    determinedFrom = PartialBatchDetermination.UNKNOWN,
                    recordedAtEpochMs = System.currentTimeMillis(),
                )
                else -> manifest?.partialBatchInfo
            }
            if (artifact != null && manifest != null && expected != null) {
                val updated = manifest.copy(
                    expectedPageCount = maxOf(expected, manifest.expectedPageCount ?: 0),
                    expectedPageCountTrusted = expectedTrusted,
                    partialBatchInfo = partialInfo,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                if (
                    updated.expectedPageCount == manifest.expectedPageCount &&
                    updated.expectedPageCountTrusted == manifest.expectedPageCountTrusted &&
                    updated.partialBatchInfo == manifest.partialBatchInfo
                ) {
                    store.pendingExpectedPageCount = null
                    store.pendingExpectedPageCountTrusted = false
                } else if (artifact.publishManifest(updated)) {
                    store.artifactManifest = updated
                    store.pendingExpectedPageCount = null
                    store.pendingExpectedPageCountTrusted = false
                }
            }
            ChapterTranslationStore.PagePreRegistration.Accepted
        }
    }

    /**
     * Clears queue-like entries after a user cancel/auto-window replacement.
     * Pages with a rendered/cleaned result are kept; transient running, pending,
     * cancelled, and failed entries without output are reset so the reader queue
     * reflects only the next active window.
     */
    suspend fun clearTransientQueuePages(reason: String? = null) {
        if (store.defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store clearTransientQueuePages rejected: store is defunct"
            }
            return
        }
        store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store clearTransientQueuePages rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock
                }
            }
            store.generation++
            logcat(LogPriority.INFO) {
                "TachiyomiAT store generation invalidated: " +
                    "generation=${store.generation} reason=${reason ?: "clear transient queue"}"
            }
            val changedPageKeys = mutableSetOf<String>()
            val now = System.currentTimeMillis()
            var updatedPages = store.pages
            store.pages.forEach { (pageKey, page) ->
                if (page.hasRenderedResult || !page.isQueueVisibleTransient()) {
                } else {
                    val draft = page.toDraft().copy(
                        ocrStatus = page.ocrStatus.cancelIfTransient(),
                        translationStatus = page.translationStatus.cancelIfTransient(),
                        inpaintStatus = page.inpaintStatus.cancelIfTransient(),
                        renderStatus = page.renderStatus.cancelIfTransient(),
                        updatedAt = now,
                    ).also {
                        it.ocrError = reason
                        it.translationError = reason
                        it.inpaintError = reason
                        it.renderError = reason
                    }
                    updatedPages = updatedPages.put(pageKey, store.publishPage(store.ownedPage(pageKey, draft)))
                    changedPageKeys += pageKey
                }
            }
            if (changedPageKeys.isNotEmpty()) {
                store.pages = updatedPages
                // Cancel semantics: cancelled pages release their writer
                // leases; committed display bundles survive untouched.
                synchronized(store.pageLeases) {
                    store.pageLeases.keys.retainAll { pageKey ->
                        store.pages[pageKey]?.hasRenderedResult ?: false
                    }
                }
                store.pages.keys.forEach { pageKey ->
                    store.cancelArtifactCandidateLocked(pageKey)
                }
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(changedPageKeys)
            }
        }
    }

    /** Caller holds [store.mutex]; suspends on lazy first-write artifact admission. */
    private suspend fun createPageRekeyPlanLocked(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): PageRekeyPlanResult {
        when (val admission = store.publicationBridge.admitMutation()) {
            MutationAdmission.Granted -> Unit
            is MutationAdmission.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT store rekeyPages rejected: code=${admission.code} reason=${admission.message}"
                }
                return PageRekeyPlanResult.Rejected(
                    "re-key mutation admission refused: ${admission.message}",
                )
            }
        }
        // The store may hold a SUBSET of the chapter's pages (partial reader
        // session); mapping is per-key, so only the owned keys are planned.
        if (store.pages.isEmpty() || store.pages.keys.all { it in onDiskKeys }) {
            return PageRekeyPlanResult.Immediate(ChapterTranslationStore.PageRekeyOutcome.Noop)
        }

        val moves = LinkedHashMap<String, String>()
        val consumedPlaceholders = LinkedHashSet<String>()
        var skippedCollisions = 0
        store.pages.keys.forEach { oldKey ->
            val index = onlineKeys.indexOf(oldKey)
            if (index < 0) return@forEach
            val newKey = onDiskKeys[index]
            if (newKey == oldKey || newKey in moves.values) return@forEach
            if (newKey in store.pages) {
                // Adopt the destination only when it is a payload-less
                // pre-registration placeholder; a real record is a collision.
                if (!isPayloadlessPlaceholderLocked(newKey)) {
                    skippedCollisions++
                    return@forEach
                }
                consumedPlaceholders += newKey
            }
            moves[oldKey] = newKey
        }
        if (moves.isEmpty()) {
            return PageRekeyPlanResult.Immediate(
                if (skippedCollisions > 0) {
                    ChapterTranslationStore.PageRekeyOutcome.Moved(emptyList(), skippedCollisions)
                } else {
                    ChapterTranslationStore.PageRekeyOutcome.Noop
                },
            )
        }
        if (moves.values.distinct().size != moves.size) {
            return PageRekeyPlanResult.Rejected("re-key plan maps multiple pages to one destination")
        }

        val manifest = store.artifactManifest
        val requiresArtifactTransaction = store.artifactEngine != null &&
            manifest != null &&
            moves.keys.any { oldKey ->
                oldKey in manifest.pages ||
                    manifest.durableFailures.values.any { it.pageKey == oldKey }
            }
        val oldArtifactContentHashes = moves.keys.associateWith { oldKey ->
            val record = manifest?.pages?.get(oldKey)
            // Never derive a fingerprint under the store mutex. A missing artifact fingerprint
            // stays absent and makes the corresponding replay mutation retryable.
            record?.candidate?.pageSnapshotFingerprint ?: record?.committed?.translationFingerprint
        }
        val livePageSnapshots = moves.keys
            .mapNotNull { oldKey -> store.pages[oldKey]?.let { oldKey to it } }
            .toMap()
        if (livePageSnapshots.size != moves.size) {
            return PageRekeyPlanResult.Rejected("re-key source page snapshot missing")
        }
        val leaseTokens = synchronized(store.pageLeases) {
            moves.keys.associateWith { oldKey -> store.pageLeases[oldKey]?.token }
        }
        return PageRekeyPlanResult.Ready(
            PageRekeyPlan(
                generation = store.generation,
                pages = store.pages,
                committedDisplay = store.committedDisplay,
                manifest = manifest,
                moves = moves,
                oldArtifactContentHashes = oldArtifactContentHashes,
                livePageSnapshots = livePageSnapshots,
                leaseTokens = leaseTokens,
                requiresArtifactTransaction = requiresArtifactTransaction,
                changedDisplayKeys = store.pages.keys + moves.values,
                consumedPlaceholders = consumedPlaceholders,
                skippedCollisions = skippedCollisions,
            ),
        )
    }

    /**
     * Caller holds [store.mutex]. True only when the record at [pageKey] carries no
     * payload a move would destroy: no blocks, no cleaned/render image, no
     * stage output, no fingerprints, no committed display, no artifact or
     * journal immutables. Pre-registration placeholders qualify.
     */
    private fun isPayloadlessPlaceholderLocked(pageKey: String): Boolean {
        if (store.committedDisplay.containsKey(pageKey)) return false
        if (store.retiredCleanedImages.containsKey(pageKey)) return false
        val manifest = store.artifactManifest ?: return placeholderRecordLocked(pageKey)
        if (manifest.pages.containsKey(pageKey)) return false
        if (manifest.durableFailures.values.any { it.pageKey == pageKey }) return false
        return placeholderRecordLocked(pageKey)
    }

    /** Caller holds [store.mutex]; checks the in-memory page record only. */
    private fun placeholderRecordLocked(pageKey: String): Boolean {
        val page = store.pages[pageKey] ?: return false
        if (page.blocks.isNotEmpty()) return false
        if (page.cleanedImageName != null || page.ocrArtifactId != null) return false
        if (page.originalImageFallback) return false
        if (page.imgWidth > 0f || page.imgHeight > 0f) return false
        if (page.detectionCount > 0 || page.ocrBlockCount > 0) return false
        if (
            page.ocrStatus != StageStatus.PENDING ||
            page.translationStatus != StageStatus.PENDING ||
            page.inpaintStatus != StageStatus.PENDING ||
            page.renderStatus != StageStatus.PENDING
        ) {
            return false
        }
        if (page.activeError != null || page.errorMessage != null) return false
        if (
            page.sourceFingerprint != null ||
            page.detectionFingerprint != null ||
            page.ocrFingerprint != null ||
            page.inpaintFingerprint != null ||
            page.translationFingerprint != null ||
            page.layoutFingerprint != null
        ) {
            return false
        }
        return true
    }

    /** Caller holds [store.mutex]. Reject any store-side change made while snapshots were prepared. */
    private fun isCurrentPageRekeyPlanLocked(plan: PageRekeyPlan): Boolean {
        if (store.defunct ||
            store.generation != plan.generation ||
            store.pages !== plan.pages ||
            store.committedDisplay !== plan.committedDisplay ||
            store.artifactManifest !== plan.manifest
        ) {
            return false
        }
        return synchronized(store.pageLeases) {
            plan.leaseTokens.all { (pageKey, token) -> store.pageLeases[pageKey]?.token == token }
        }
    }

    private fun rekeyedArtifactManifest(
        base: ChapterArtifactManifest,
        prepared: ChapterArtifactEngine.PreparedPageSnapshotRekey,
        moves: Map<String, String>,
    ): ChapterArtifactManifest? {
        val updatedFailures = LinkedHashMap<String, DurableFailureMetadata>()
        base.durableFailures.forEach { (_, failure) ->
            val newPageKey = moves[failure.pageKey] ?: failure.pageKey
            val updated = failure.copy(pageKey = newPageKey)
            val newMapKey = "$newPageKey:${failure.stage.name}"
            if (updatedFailures.put(newMapKey, updated) != null) return null
        }
        return base.copy(
            pages = prepared.pages,
            durableFailures = updatedFailures,
            expectedPageCount = maxOf(
                base.expectedPageCount ?: 0,
                prepared.pages.size,
            ),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    /** Caller holds [store.mutex]; null when the destructive deletion is durably complete. */
    private fun deleteArtifactPageLocked(pageKey: String): String? {
        val artifact = store.artifactEngine ?: return null
        val manifest = store.artifactManifest ?: return null
        val outcome = artifact.candidates.deleteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                store.artifactManifest = outcome.manifest
                // P3A/P3B typed incomplete: the page pointer was durably
                // removed but the same-state backup could not be verified.
                outcome.unverifiedBackupReason?.let { reason ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact page deletion published without a verified backup: " +
                            "pageKey=$pageKey reason=$reason"
                    }
                    return "unverified backup after page deletion: $reason"
                }
                return null
            }
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                store.publicationBridge.rebaseManifestAfterStaleRejection(artifact, outcome.reason)
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact page deletion rejected: pageKey=$pageKey reason=${outcome.reason}"
                }
            }
        }
        return (outcome as? ChapterArtifactEngine.TransactionOutcome.Rejected)?.reason
    }
}
