package eu.kanade.translation.store

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.artifact.AttemptLedgerEntry
import eu.kanade.translation.artifact.AttemptOrigin
import eu.kanade.translation.artifact.ChapterAttemptLedgerDocument
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * T917 Phase 3 (D9, phase3-design §3): durable attempt-ledger collaborator
 * moved alongside `ChapterGlossaryStore`. Owns the chapter's
 * [ChapterAttemptLedgerDocument]; every mutation is delegated by the owning
 * store under the store mutex (the collaborator receives the store and locks
 * through it) and every durably published document goes through the artifact
 * authority's crash-safe temp/validate/rename publication.
 *
 * Contract:
 * - A paid provider call writes its entry BEFORE the call (design §3.2).
 * - Any COMPLETED call — commit success or typed provider failure — resolves
 *   the page's entries and resets its consecutive counter (a completed call
 *   breaks the crash streak).
 * - Only a process death leaves an entry; startup reconciliation consumes it
 *   as one counted interrupted attempt.
 * - The cap refuses AUTO entries once a page's consecutive counter reaches
 *   [ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED]; MANUAL and
 *   BATCH entries are always admitted (the cap binds auto-retry loops, never
 *   the user or an in-flight chapter run).
 * - Ledger write failures are fail-open: the paid call proceeds, and the
 *   worst case is one unrecorded attempt (never a blocked translation).
 * - Memory-only stores keep the document in memory and report successful
 *   persistence (nothing durable to write) — the ledger simply degrades to a
 *   per-process bound there.
 */
internal class ChapterAttemptLedger(private val store: ChapterTranslationStore) {

    /** Lazily hydrated from the artifact authority on first access. */
    private var loaded = false

    private var document = ChapterAttemptLedgerDocument()

    /** True when the artifact authority owns this chapter's sidecars. */
    private val artifactAuthorityLive: Boolean
        get() {
            return store.artifactEngine != null && store.artifactManifest != null
        }

    /** Caller holds the store mutex. */
    private fun documentLocked(): ChapterAttemptLedgerDocument {
        if (!loaded) {
            loaded = true
            if (artifactAuthorityLive) {
                document = store.artifactEngine?.readAttemptLedger() ?: ChapterAttemptLedgerDocument()
            }
        }
        return document
    }

    /**
     * Records one started attempt BEFORE the paid call. Returns false only
     * when the origin is AUTO and the page is already capped (the refusal IS
     * the cap's contract).
     */
    fun recordStartLocked(
        pageKey: String,
        providerKeyHash: String,
        origin: AttemptOrigin,
        generation: Long,
        requestContextFingerprint: String? = null,
    ): Boolean {
        val current = documentLocked()
        val consecutive = current.consecutiveUnresolved[pageKey] ?: 0
        if (origin == AttemptOrigin.AUTO &&
            consecutive >= ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED
        ) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT D9: AUTO attempt refused by attempt cap: pageKey=$pageKey " +
                    "consecutiveUnresolved=$consecutive"
            }
            return false
        }
        val entry = AttemptLedgerEntry(
            pageKey = pageKey,
            providerKeyHash = providerKeyHash,
            origin = origin,
            generation = generation,
            startedAtEpochMs = System.currentTimeMillis(),
            requestContextFingerprint = requestContextFingerprint,
        )
        val next = current.copy(
            entries = (current.entries + entry).takeLast(ChapterAttemptLedgerDocument.MAX_ENTRIES),
        )
        return persistLocked(next)
    }

    /**
     * A completed call for [pageKey]: drop its entries and reset its
     * consecutive counter. A no-op when nothing is pending (the common path —
     * one map scan, no I/O).
     */
    fun resolveLocked(pageKey: String) {
        val current = documentLocked()
        if (current.entries.none { it.pageKey == pageKey } &&
            current.consecutiveUnresolved[pageKey] == null
        ) {
            return
        }
        persistLocked(
            current.copy(
                entries = current.entries.filterNot { it.pageKey == pageKey },
                consecutiveUnresolved = current.consecutiveUnresolved - pageKey,
            ),
        )
    }

    /**
     * Startup reconciliation: every pending entry is consumed as one counted
     * interrupted attempt for its page; the counters persist back into the
     * same file. Returns the post-consume consecutive counters (the caller
     * applies the cap for pages at/over the bound).
     */
    fun consumeAtStartupLocked(): Map<String, Int> {
        val current = documentLocked()
        if (current.entries.isEmpty()) return current.consecutiveUnresolved
        val counters = current.consecutiveUnresolved.toMutableMap()
        current.entries.forEach { entry ->
            counters[entry.pageKey] = (counters[entry.pageKey] ?: 0) + 1
        }
        persistLocked(
            current.copy(entries = emptyList(), consecutiveUnresolved = counters),
        )
        return counters
    }

    /** Explicit user force: clear one page's consecutive counter. */
    fun clearCapLocked(pageKey: String): Boolean {
        val current = documentLocked()
        if (current.consecutiveUnresolved[pageKey] == null) return true
        return persistLocked(
            current.copy(consecutiveUnresolved = current.consecutiveUnresolved - pageKey),
        )
    }

    /**
     * Fail-open persistence: memory-only stores keep the document in memory
     * and report success; an artifact-publication failure is logged and the
     * in-memory document still advances (an unrecorded attempt is always
     * preferable to a blocked translation).
     */
    private fun persistLocked(next: ChapterAttemptLedgerDocument): Boolean {
        document = next
        if (!artifactAuthorityLive) return true
        val published = store.artifactEngine?.publishAttemptLedger(next) ?: true
        if (!published) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT D9: attempt ledger publish failed (fail-open): " +
                    "pageState=${next.entries.size} entries kept in memory"
            }
        }
        return published
    }
}
