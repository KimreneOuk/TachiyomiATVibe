package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.journal.ChapterJournalPageOutcome
import eu.kanade.translation.persistence.journal.ChapterJournalReplayResult

/**
 * Chapter status collaborator (Phase 7 S3). Owns the accepted-mutation status
 * snapshot seeded from the recovered journal prefix at open, the inventory
 * trust/fallback state, and the pending expected-page totals. The owning
 * store delegates every read and write here under the store mutex; the
 * snapshot is a live-advanced projection of accepted mutations, never an
 * independently mutable replica of durable journal state.
 *
 * [StoreStatusProjector] inputs are unchanged: the projector keeps reading
 * this snapshot through [ChapterTranslationStore.statusProjectionInputs].
 */
internal class ChapterStatusLedger(
    private val store: ChapterTranslationStore,
    journalReplayResult: ChapterJournalReplayResult?,
    initialUnverifiedManifestFallbackPageKeys: Set<String>,
) {

    /**
     * Mutable status state seeded from the recovered prefix at open. The
     * projector never reads the replay result directly; accepted store
     * mutations advance this same snapshot while the store is live.
     */
    @Volatile
    private var journalStatusSnapshot: StoreStatusSnapshot? = journalReplayResult?.let { replay ->
        StoreStatusSnapshot(
            inventory = StoreStatusInventory(
                expectedPageKeys = replay.expectedPageKeys.toSet(),
                expectedPageCount = replay.expectedPageCount,
                expectedPageCountTrusted = replay.inventory?.hasTrustedExpectedPageCount == true,
            ),
            durableFailures = replay.durableFailures.toMap(),
            pageOutcomes = replay.pageOutcomes.toMap(),
        )
    }

    /** Metadata-only manifest fallbacks stay visible but cannot complete seed trust. */
    private val unverifiedManifestFallbackPageKeys = initialUnverifiedManifestFallbackPageKeys.toSet()

    @Volatile
    private var journalInventoryTrustEstablished =
        journalReplayResult?.inventory?.hasTrustedExpectedPageCount == true

    private var pendingExpectedPageCount: Int? = null
    private var pendingExpectedPageCountTrusted = false

    /** Lock-free volatile read mirroring the other [ChapterTranslationStore.statusProjectionInputs] inputs. */
    fun currentStatusSnapshot(): StoreStatusSnapshot? = journalStatusSnapshot

    /**
     * Installs the open-time opening snapshot merged from the recovered
     * prefix, the durable manifest, and the seed decision table. Called by the
     * store's open factory before the store is published, replacing the
     * constructor seed.
     */
    fun installOpeningStatus(
        snapshot: StoreStatusSnapshot?,
        trustEstablished: Boolean,
    ) {
        journalStatusSnapshot = snapshot
        journalInventoryTrustEstablished = trustEstablished
    }

    /** Caller holds [ChapterTranslationStore.mutex]; publish one accepted page mutation into the active status snapshot. */
    fun recordPageMutationLocked(
        pageKey: String,
        durableFailure: DurableFailureMetadata?,
    ) {
        val current = journalStatusSnapshot ?: return
        val keys = current.inventory.expectedPageKeys + pageKey
        val failures = LinkedHashMap(current.durableFailures)
        failures.entries.removeAll { it.value.pageKey == pageKey }
        durableFailure?.let { failure ->
            failures["${failure.pageKey}:${failure.stage.name}"] = failure
        }
        journalStatusSnapshot = current.copy(
            inventory = current.inventory.copy(
                expectedPageKeys = keys,
                expectedPageCount = maxOf(current.inventory.expectedPageCount ?: 0, keys.size),
            ),
            durableFailures = failures.toMap(),
            // The active store mutation supersedes this key's replay-prefix outcome.
            // Its current content/terminal state is projected from [pages] instead.
            pageOutcomes = current.pageOutcomes - pageKey,
        )
    }

    /** Caller holds [ChapterTranslationStore.mutex]; accepted page deletion removes that page's live inventory/failure identity. */
    fun recordPageDeletionLocked(pageKey: String) {
        val current = journalStatusSnapshot ?: return
        journalStatusSnapshot = current.copy(
            inventory = current.inventory.copy(
                expectedPageKeys = current.inventory.expectedPageKeys - pageKey,
            ),
            durableFailures = current.durableFailures.filterValues { it.pageKey != pageKey },
            pageOutcomes = current.pageOutcomes - pageKey,
        )
    }

    /** Caller holds [ChapterTranslationStore.mutex]; a bulk re-key transfers the active inventory and failure identities. */
    fun recordRekeyLocked(moves: Map<String, String>) {
        val current = journalStatusSnapshot ?: return
        val keys = current.inventory.expectedPageKeys.mapTo(LinkedHashSet()) { key -> moves[key] ?: key }
            .apply { addAll(moves.values) }
        val failures = LinkedHashMap<String, DurableFailureMetadata>()
        current.durableFailures.values.forEach { failure ->
            val pageKey = moves[failure.pageKey] ?: failure.pageKey
            val moved = failure.copy(pageKey = pageKey)
            failures["$pageKey:${moved.stage.name}"] = moved
        }
        val pageOutcomes = current.pageOutcomes.entries
            .associate { (pageKey, outcome) -> (moves[pageKey] ?: pageKey) to outcome }
        journalStatusSnapshot = current.copy(
            inventory = current.inventory.copy(
                expectedPageKeys = keys,
                expectedPageCount = maxOf(current.inventory.expectedPageCount ?: 0, keys.size),
            ),
            durableFailures = failures.toMap(),
            pageOutcomes = pageOutcomes,
        )
    }

    /** Caller holds [ChapterTranslationStore.mutex]; registration is an accepted store mutation even before its journal offer. */
    fun recordRegistrationLocked(
        pageKeys: Set<String>,
        expectedPageCount: Int?,
        expectedPageCountTrusted: Boolean,
    ) {
        val current = journalStatusSnapshot ?: return
        val keys = current.inventory.expectedPageKeys + pageKeys
        val expectedCount = maxOf(
            current.inventory.expectedPageCount ?: 0,
            expectedPageCount ?: 0,
            keys.size,
        )
        journalStatusSnapshot = current.copy(
            inventory = current.inventory.copy(
                expectedPageKeys = keys,
                expectedPageCount = expectedCount,
                expectedPageCountTrusted =
                current.inventory.expectedPageCountTrusted || expectedPageCountTrusted,
            ),
        )
    }

    /** Caller holds [ChapterTranslationStore.mutex]; replaceAll swaps the accepted inventory and clears superseded failures. */
    fun recordReplacementLocked(pageKeys: Set<String>) {
        val current = journalStatusSnapshot ?: return
        journalStatusSnapshot = current.copy(
            inventory = current.inventory.copy(
                expectedPageKeys = pageKeys.toSet(),
                expectedPageCount = maxOf(current.inventory.expectedPageCount ?: 0, pageKeys.size),
            ),
            durableFailures = emptyMap(),
        )
    }

    /** Caller holds [ChapterTranslationStore.mutex]; clears one accepted failure mutation from the active status snapshot. */
    fun clearFailureLocked(pageKey: String) {
        val current = journalStatusSnapshot ?: return
        journalStatusSnapshot = current.copy(
            durableFailures = current.durableFailures.filterValues { it.pageKey != pageKey },
        )
    }

    /** Caller holds [ChapterTranslationStore.mutex]; the synced seed prefix supersedes replay-prefix outcomes. */
    fun adoptReplayedPageOutcomesLocked(pageOutcomes: Map<String, ChapterJournalPageOutcome>) {
        journalStatusSnapshot = journalStatusSnapshot?.copy(
            pageOutcomes = pageOutcomes,
        )
    }

    /**
     * Caller holds [ChapterTranslationStore.mutex]. The trusted v2 inventory is
     * the migration-complete marker; it follows the synced page prefix.
     */
    fun establishTrustedInventoryLocked(
        expectedPageKeys: Set<String>,
        expectedPageCount: Int,
        pageOutcomes: Map<String, ChapterJournalPageOutcome>,
    ) {
        journalInventoryTrustEstablished = true
        journalStatusSnapshot = journalStatusSnapshot?.let { status ->
            status.copy(
                inventory = status.inventory.copy(
                    expectedPageKeys = expectedPageKeys,
                    expectedPageCount = expectedPageCount,
                    expectedPageCountTrusted = true,
                ),
                pageOutcomes = pageOutcomes,
            )
        }
    }

    /** Caller holds [ChapterTranslationStore.mutex]; pending registration total for journal inventory offers and manifest CAS. */
    fun pendingExpectedPageCountLocked(): Int? = pendingExpectedPageCount

    /** Caller holds [ChapterTranslationStore.mutex]; pending registration trust for journal inventory offers and manifest CAS. */
    fun pendingExpectedPageCountTrustedLocked(): Boolean = pendingExpectedPageCountTrusted

    /** Caller holds [ChapterTranslationStore.mutex]; accumulate one accepted registration's expected totals (assignment, not OR). */
    fun accumulatePendingExpectedTotalsLocked(
        targetCount: Int,
        trusted: Boolean,
    ) {
        pendingExpectedPageCount = maxOf(pendingExpectedPageCount ?: 0, targetCount)
        pendingExpectedPageCountTrusted = trusted
    }

    /** Caller holds [ChapterTranslationStore.mutex]; the durable manifest now carries the registration totals. */
    fun clearPendingExpectedTotalsLocked() {
        pendingExpectedPageCount = null
        pendingExpectedPageCountTrusted = false
    }

    /**
     * Caller holds [ChapterTranslationStore.mutex]. Trust decision for one
     * journal inventory offer: without app-private journal storage trust is
     * vacuous; with it, trust must be earned by the replay/seed prefix, an
     * accepted registration, or the manifest itself, and metadata-only
     * fallbacks keep the chapter in union mode.
     */
    fun inventoryTrustedLocked(manifestTrusted: Boolean): Boolean =
        (!store.hasPrivateJournalStorage || journalInventoryTrustEstablished) &&
            (
                manifestTrusted ||
                    pendingExpectedPageCountTrusted ||
                    journalStatusSnapshot?.inventory?.expectedPageCountTrusted == true
                ) &&
            unverifiedManifestFallbackPageKeys.isEmpty()
}
