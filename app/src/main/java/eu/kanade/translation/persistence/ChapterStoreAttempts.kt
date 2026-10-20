package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Attempt ledger operations extracted from [ChapterTranslationStore] to stay within file size bounds.
 */
internal object ChapterStoreAttempts {

    suspend fun recordAttemptStart(
        store: ChapterTranslationStore,
        pageKey: String,
        providerKeyHash: String,
        origin: AttemptOrigin,
        generation: Long,
        requestContextFingerprint: String?,
    ): Boolean = store.mutex.withLock {
        store.attemptLedger.recordStartLocked(pageKey, providerKeyHash, origin, generation, requestContextFingerprint)
    }

    suspend fun autoAttemptAllowed(store: ChapterTranslationStore, pageKey: String): Boolean = store.mutex.withLock {
        store.attemptLedger.autoAttemptAllowedLocked(pageKey)
    }

    suspend fun resolveAttempt(store: ChapterTranslationStore, pageKey: String) {
        store.mutex.withLock { store.attemptLedger.resolveLocked(pageKey) }
    }

    suspend fun consumeUnresolvedAttemptsAtStartup(store: ChapterTranslationStore): Map<String, Int> = store.mutex.withLock {
        store.attemptLedger.consumeAtStartupLocked()
    }

    suspend fun applyAttemptCapPause(store: ChapterTranslationStore, pageKey: String, consecutiveUnresolved: Int): Boolean {
        val failure = DurableFailureMetadata(
            pageKey = pageKey,
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.INTERRUPTED,
            retryCount = consecutiveUnresolved,
            lastFailureMessage = "repeatedly interrupted before completing; manual retry required",
            lastFailedAtEpochMs = System.currentTimeMillis(),
            nextEligibleRetryAtEpochMs = null,
        )
        val expected = store.snapshot(pageKey).toPrecondition()
        val result = store.persistDurableStageFailure(
            pageKey = pageKey,
            expected = expected,
            failure = failure,
            description = "D9 attempt cap reached ($consecutiveUnresolved consecutive interrupted attempts)",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                translationStatus = StageStatus.PARTIAL
            }
        }
        return result is ChapterTranslationStore.PatchResult.Accepted
    }

    suspend fun clearAttemptCapForManualRetry(store: ChapterTranslationStore, pageKey: String): Boolean = store.mutex.withLock {
        val counterCleared = store.attemptLedger.clearCapLocked(pageKey)
        removeInterruptedCapFailureLocked(store, pageKey)
        counterCleared
    }

    private fun removeInterruptedCapFailureLocked(store: ChapterTranslationStore, pageKey: String) {
        val artifact = store.artifactEngine ?: return
        val manifest = store.artifactManifest ?: return
        val key = "$pageKey:${ArtifactStage.TRANSLATION.name}"
        val existing = manifest.durableFailures[key] ?: return
        if (existing.category != FailureCategory.INTERRUPTED) return
        val updated = manifest.copy(
            durableFailures = manifest.durableFailures - key,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (artifact.publishManifest(updated)) {
            store.artifactManifest = updated
            store.stateFlow.value = store.snapshotPages()
            store.displayPublication.displaySnapshotLocked(emptyList<String>())
        } else {
            logcat(LogPriority.WARN) {
                "TachiyomiAT D9: cap-failure clear publish failed (fail-open): pageKey=$pageKey"
            }
        }
    }
}
