package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.PartialBatchDetermination
import eu.kanade.translation.persistence.artifact.PartialBatchInfo
import eu.kanade.translation.persistence.internal.ChapterStoreEngineMode
import eu.kanade.translation.persistence.journal.ChapterJournalFormat
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

    suspend fun deletePage(pageKey: String) {
        if (store.defunct) return
        val journalCredit = store.reserveJournalCredit(pageKey)
        try {
            store.withJournalCapturePermit {
                store.mutex.withLock {
                    when (val admission = store.admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT store deletePage rejected: " +
                                    "code=${admission.code} reason=${admission.message}"
                            }
                            return@withLock
                        }
                    }
                    if (store.pages.containsKey(pageKey)) {
                        val priorPageVersion = store.pages[pageKey]?.pageVersion ?: 0L
                        val priorArtifactContentHash = store.pageArtifactContentHash(
                            store.artifactManifest,
                            pageKey,
                            store.pages[pageKey],
                        )
                        val deleted = deleteArtifactPageLocked(pageKey)
                        if (deleted) {
                            store.capturePageDeletionLocked(
                                pageKey,
                                priorPageVersion,
                                journalCredit,
                                artifactContentHash = priorArtifactContentHash,
                            )
                        }
                        store.pages = store.pages.remove(pageKey)
                        store.committedDisplay = store.committedDisplay.remove(pageKey)
                        store.statusLedger.recordPageDeletionLocked(pageKey)
                        store.retiredCleanedImages.remove(pageKey)
                        store.pageLeases.remove(pageKey)
                        store.stateFlow.value = store.snapshotPages()
                        store.displaySnapshotLocked(listOf(pageKey))
                    }
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
        }
    }

    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) {
        if (store.defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store replaceAll rejected: store is defunct"
            }
            return
        }
        val bulkCredit = store.reserveJournalCredit("<bulk_replace>")
        try {
            store.withJournalCapturePermit {
                store.mutex.withLock {
                    when (val admission = store.admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT store replaceAll rejected: " +
                                    "code=${admission.code} reason=${admission.message}"
                            }
                            return@withLock
                        }
                    }
                    val bulk = store.openBulkJournalFrameLocked(
                        kind = ChapterJournalFormat.RecordKind.BULK_REPLACE,
                        operation = "replace_all",
                    )
                    try {
                        val changedDisplayKeys = store.pages.keys + updatedPages.keys
                        if (store.artifactManifest != null) {
                            store.artifactManifest?.pages?.keys?.toList().orEmpty().forEach { pageKey ->
                                val pageVersion = store.pages[pageKey]?.pageVersion
                                    ?: store.artifactManifest?.pages?.get(pageKey)?.pageVersion
                                    ?: 0L
                                val priorArtifactContentHash = store.pageArtifactContentHash(
                                    store.artifactManifest,
                                    pageKey,
                                    store.pages[pageKey],
                                )
                                if (deleteArtifactPageLocked(pageKey)) {
                                    store.capturePageDeletionLocked(
                                        pageKey,
                                        pageVersion,
                                        credit = null,
                                        artifactContentHash = priorArtifactContentHash,
                                    )
                                }
                            }
                        }
                        store.pages = persistentMapOf()
                        store.committedDisplay = persistentMapOf()
                        store.retiredCleanedImages.clear()
                        updatedPages.forEach { (pageKey, page) ->
                            val owned = store.ownedPage(pageKey, page)
                            store.pages = store.pages.put(pageKey, store.publishPage(owned))
                            if (store.persistArtifactMutation(pageKey, null, owned)) {
                                store.promoteDisplayIfReadyLocked(pageKey, owned)
                            }
                        }
                        store.statusLedger.recordReplacementLocked(updatedPages.keys)
                        store.stateFlow.value = store.snapshotPages()
                        store.displaySnapshotLocked(changedDisplayKeys)
                    } finally {
                        store.finishBulkJournalCaptureLocked(bulk, bulkCredit)
                    }
                }
            }
        } finally {
            bulkCredit?.releaseIfHeld()
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
        var bulkCreditFailure: Throwable? = null
        val bulkCredit = store.reserveJournalCredit("<bulk_rekey>") { failure ->
            bulkCreditFailure = failure
        }
        return try {
            pageRekeyMutex.withLock {
                val planResult = store.withJournalCapturePermit {
                    store.mutex.withLock { createPageRekeyPlanLocked(onlineKeys, onDiskKeys) }
                }
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
                    if (!prepared.journalMutations.keys.containsAll(plan.moves.values)) {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT store rekeyPages rejected: prepared journal mutations incomplete"
                        }
                        return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                            "prepared journal mutations incomplete",
                        )
                    }
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

                store.withJournalCaptureBarrier {
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
                                return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                                    "durable manifest changed during re-key preparation",
                                )
                            }
                        }
                        val previousPages = store.pages
                        val moveByOldKey = plan.moves
                        var updatedPages: PersistentMap<String, PublishedPageTranslation> = persistentMapOf()
                        var updatedCommitted =
                            persistentMapOf<String, ChapterTranslationStore.CommittedPageDisplay>()
                        previousPages.forEach { (oldKey, page) ->
                            when {
                                moveByOldKey.containsKey(oldKey) -> {
                                    val newKey = moveByOldKey.getValue(oldKey)
                                    val updated = page.toDraft().apply { sourceFileName = newKey }
                                    updatedPages = updatedPages.put(
                                        newKey,
                                        store.publishPage(store.ownedPage(newKey, updated)),
                                    )
                                    store.committedDisplay[oldKey]?.let { committed ->
                                        val committedPage = committed.page.toDraft().apply {
                                            sourceFileName = newKey
                                        }
                                        updatedCommitted = updatedCommitted.put(
                                            newKey,
                                            committed.copy(
                                                page = committedPage.toPublishedPage(),
                                                // The display fingerprint excludes source identity.
                                            ),
                                        )
                                    }
                                }
                                // A consumed destination placeholder must not overwrite the moved source.
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
                        val requiresDurableBulkCapture = plan.requiresArtifactTransaction &&
                            plan.moves.isNotEmpty() &&
                            (store.privateStorageRoot != null || store.journalWriter != null)
                        if (requiresDurableBulkCapture && bulkCredit == null) {
                            val cause = bulkCreditFailure ?: java.io.IOException(
                                "journal credit unavailable for artifact re-key",
                            )
                            return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                                "journal credit reservation failed before re-key: ${cause.message}",
                                cause,
                            )
                        }

                        var bulkOffer: BulkJournalCaptureResult? = null
                        var journalWatermarkBeforeCapture = 0L
                        if (plan.requiresArtifactTransaction && plan.moves.isNotEmpty()) {
                            val bulk = store.openBulkJournalFrameLocked(
                                kind = ChapterJournalFormat.RecordKind.BULK_REKEY,
                                operation = "rekey_pages",
                                rekeyMapping = moveByOldKey,
                            )
                            try {
                                // Capture tombstones from the prestate first. A consumed placeholder is
                                // replaced by the incoming destination state, so it has no source tombstone.
                                plan.moves.forEach { (oldKey, _) ->
                                    if (oldKey !in plan.consumedPlaceholders) {
                                        store.capturePageDeletionLocked(
                                            pageKey = oldKey,
                                            pageVersion = previousPages[oldKey]?.pageVersion ?: 0L,
                                            credit = null,
                                            artifactContentHash = plan.oldArtifactContentHashes[oldKey],
                                        )
                                    }
                                }
                                // These prepared destination mutations all come from the same prestate;
                                // write each unique destination once after the source phase.
                                plan.moves.forEach { (oldKey, newKey) ->
                                    val mutation = checkNotNull(preparedSnapshots?.journalMutations?.get(newKey))
                                    store.captureRekeyStateLocked(oldKey, newKey, mutation)
                                }
                                val preparedInventory = preparedManifest?.let { manifest ->
                                    store.journalInventorySnapshot(manifest, pendingAfterRekey)
                                }
                                journalWatermarkBeforeCapture = store.journalWriter?.ackedHighWaterSeq ?: 0L
                                bulkOffer = store.finishBulkJournalCaptureLocked(
                                    bulk = bulk,
                                    credit = bulkCredit,
                                    inventory = preparedInventory,
                                )
                            } finally {
                                store.cancelBulkJournalFrameLocked(bulk)
                            }
                        }

                        if (requiresDurableBulkCapture) {
                            val offer = bulkOffer
                            if (offer == null || !offer.accepted) {
                                val cause = offer?.writer?.let { writer ->
                                    writer.terminalCaptureFailure ?: java.io.IOException(
                                        "BULK_REKEY journal offer was rejected",
                                    )
                                } ?: java.io.IOException("BULK_REKEY journal offer was not created")
                                return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                                    "journal BULK_REKEY capture failed before manifest publication: ${cause.message}",
                                    cause,
                                )
                            }
                            val durableWatermark = try {
                                offer.writer.flushToCaptureBarrier()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                logcat(LogPriority.ERROR, failure) {
                                    "TachiyomiAT BULK_REKEY journal barrier failed before manifest publication"
                                }
                                return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                                    "journal BULK_REKEY barrier failed before manifest publication: ${failure.message}",
                                    failure,
                                )
                            }
                            if (durableWatermark <= journalWatermarkBeforeCapture) {
                                val cause = offer.writer.terminalCaptureFailure ?: java.io.IOException(
                                    "BULK_REKEY barrier did not advance the durable frame watermark",
                                )
                                return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                                    "journal BULK_REKEY was not durably acknowledged: ${cause.message}",
                                    cause,
                                )
                            }
                        }

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
                        store.statusLedger.recordRekeyLocked(plan.moves)
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
        } finally {
            bulkCredit?.releaseIfHeld()
        }
    }

    /**
     * Registers the full ordered page set for a chapter before OCR starts.
     *
     * These placeholders are intentionally memory-only: they let progress UI show
     * the chapter total immediately, while the expected-page count is captured
     * in the artifact manifest when an artifact parent is already available.
     *
     *  The trigger may attach its
     * admission-probe cross-check. When [sourceCountKnown] is true the
     * registered truth is honest about partiality:
     *  - a known SOURCE total ([probedSourcePageCount] != null) makes the
     *    trusted baseline the source total (progress shows 37/40, not 40/40)
     *    and records the delta as [PartialBatchInfo.DOWNLOAD_CROSSCHECK] —
     *    cleared again when a later cross-check derives missing == 0;
     *  - an unknown total (null count) keeps the found count but DEMOTES the
     *    trusted stamp and records `determinedFrom = UNKNOWN` — the durable
     *    record stops claiming trust it does not have.
     * Missing pages are never registered as page records; the manifest carries
     * the absence. Without context (both defaults) the self-derived
     * stamp below is byte-identical to its pre-existing behavior.
     */
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
        // Allocate the writer object outside the store mutex. The epoch remains
        // file-free until a manifest commit offers its inventory record.
        val inventoryWriter = try {
            store.ensureJournalWriter()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logcat(LogPriority.WARN, failure) {
                "TachiyomiAT shadow journal initialization failed before page registration; artifact-only persistence continues"
            }
            null
        }
        return store.withJournalCapturePermit {
            store.mutex.withLock {
                when (val admission = store.admitMutation()) {
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
                store.statusLedger.accumulatePendingExpectedTotalsLocked(
                    targetCount = targetCount,
                    trusted = !crossCheckUnknown,
                )
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
                    store.ensureArtifactStore()
                }
                val artifact = store.artifactEngine
                val manifest = store.artifactManifest
                val expected = store.statusLedger.pendingExpectedPageCountLocked()
                val expectedTrusted = when {
                    crossCheckUnknown -> false
                    else ->
                        manifest?.expectedPageCountTrusted == true ||
                            store.statusLedger.pendingExpectedPageCountTrustedLocked()
                }
                store.statusLedger.recordRegistrationLocked(
                    pageKeys = pageKeys.toSet(),
                    expectedPageCount = expected,
                    expectedPageCountTrusted = expectedTrusted,
                )
                // The partial delta rides the same publish as the totals; a full
                // cross-check (missing == 0) clears a previously recorded label.
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
                        store.statusLedger.clearPendingExpectedTotalsLocked()
                    } else if (artifact.publishManifest(updated)) {
                        store.artifactManifest = updated
                        store.statusLedger.clearPendingExpectedTotalsLocked()
                        inventoryWriter?.captureInventory(store.journalInventorySnapshot(updated))
                    }
                }
                ChapterTranslationStore.PagePreRegistration.Accepted
            }
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
        store.withJournalCapturePermit {
            store.mutex.withLock {
                when (val admission = store.admitMutation()) {
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
    }

    /** Caller holds [store.mutex]; suspends on lazy first-write artifact admission. */
    private suspend fun createPageRekeyPlanLocked(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): PageRekeyPlanResult {
        when (val admission = store.admitMutation()) {
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
                    manifest.durableFailures.values.any { it.pageKey == oldKey } ||
                    oldKey in manifest.sourceShaByPageKey
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
        if (manifest.sourceShaByPageKey.containsKey(pageKey)) return false
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
        val updatedSourceSha = LinkedHashMap<String, String>()
        base.sourceShaByPageKey.forEach { (oldKey, sourceSha) ->
            val newKey = moves[oldKey] ?: oldKey
            if (updatedSourceSha.put(newKey, sourceSha) != null) return null
        }
        return base.copy(
            pages = prepared.pages,
            durableFailures = updatedFailures,
            sourceShaByPageKey = updatedSourceSha,
            expectedPageCount = maxOf(
                base.expectedPageCount ?: 0,
                prepared.pages.size,
                updatedSourceSha.size,
            ),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun deleteArtifactPageLocked(pageKey: String): Boolean {
        val artifact = store.artifactEngine ?: return false
        val manifest = store.artifactManifest ?: return false
        val outcome = artifact.deleteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                store.artifactManifest = outcome.manifest
                return true
            }
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact page deletion rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
        return false
    }
}
