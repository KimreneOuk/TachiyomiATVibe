package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.persistence.ChapterTranslationStore.PatchPrecondition
import eu.kanade.translation.persistence.ChapterTranslationStore.PatchResult
import eu.kanade.translation.persistence.ChapterTranslationStore.TranslationPublicationReconciliation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal data class PageWriteRejection(
    val reason: String,
    val detail: PatchResult.Rejected.Detail? = null,
)

/** A same-lease drain may advance the manifest version; only a backward move is stale. */
private fun artifactVersionMovedBackwards(expected: Long?, actual: Long?): Boolean =
    expected != null && (actual == null || actual < expected)

/**
 * Owns guarded general writes, page mutation transactions, and drift
 * reconciliation for [ChapterTranslationStore].
 */
internal class ChapterPageMutations(
    private val store: ChapterTranslationStore,
) {
    private val mutex get() = store.mutex

    suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (store.isDefunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejected(
                    pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                )
            }
            val current = store.pages[pageKey]?.toDraft()
            if (expected.generation != store.generation) {
                return@withLock rejected(pageKey, description, "generation expected=${expected.generation} actual=${store.generation}")
            }
            val currentLiveVersion = current?.pageVersion ?: 0L
            if (expected.pageVersion != currentLiveVersion) {
                return@withLock rejected(pageKey, description, "pageVersion expected=${expected.pageVersion} actual=$currentLiveVersion")
            }

            val rejection = when {
                artifactVersionMovedBackwards(
                    expected.artifactPageVersion,
                    store.artifactManifest?.pages?.get(pageKey)?.pageVersion,
                ) ->
                    "artifact pageVersion changed"
                expected.candidateGenerationId != null &&
                    expected.candidateGenerationId != store.artifactManifest?.pages?.get(pageKey)?.candidate?.generationId ->
                    "candidate generation changed"
                // The same candidate-grace rule as persistArtifactMutation
                // applies: `candidate != null` grace
                // applies — a dependency fingerprint expected against a
                // candidate-LESS record (candidate never opened, cleared by an
                // abort, or the candidate-less registration a batch start
                // performs for a held page) has nothing real to compare
                // against, so the mismatch must not reject. Snapshot captures
                // arm this clause from the page-snapshot fallback even with no
                // candidate, which made every such manual commit a false
                // reject. Fail direction preserved: generation, pageVersion,
                // candidate generation, block fingerprints, and the lease
                // token fences stay fully armed.
                expected.dependencyFingerprint != null &&
                    store.artifactManifest?.pages?.get(pageKey)?.candidate != null &&
                    expected.dependencyFingerprint != store.artifactManifest?.pages?.get(pageKey)?.candidate?.dependencyFingerprint ->
                    "candidate dependency fingerprint changed"
                expected.blockFingerprints != null &&
                    expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                    "block fingerprint changed"
                expected.leaseToken != null && store.pageLeases[pageKey]?.token != expected.leaseToken ->
                    "page lease token changed"
                expected.leaseToken == null && store.pageLeases[pageKey] != null ->
                    "page lease token required"
                else -> null
            }
            if (rejection != null) {
                rejected(pageKey, description, rejection)
            } else {
                val candidate = try {
                    patch(current?.detachedCopy())
                } catch (failure: IllegalArgumentException) {
                    return@withLock rejected(
                        pageKey,
                        description,
                        failure.message ?: failure::class.java.simpleName,
                    )
                } catch (failure: IllegalStateException) {
                    return@withLock rejected(
                        pageKey,
                        description,
                        failure.message ?: failure::class.java.simpleName,
                    )
                }
                val updated = store.ownedPage(pageKey, candidate)
                store.pages = store.pages.put(pageKey, store.publishPage(updated))
                if (!publishLocked(current, updated, expected)) {
                    restorePageLocked(pageKey, current)
                    return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
                }
                PatchResult.Accepted(store.snapshotLocked(pageKey))
            }
        }
    }

    /**
     * Guarded whole-page candidate mutation for production stage writers. The
     * caller must carry the exact identity it observed before the asynchronous
     * stage began; a released/reacquired lease, page mutation, generation reset,
     * or artifact candidate replacement rejects the write before the candidate
     * bridge can publish anything durable.
     */
    suspend fun updatePageGuarded(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (store.isDefunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejected(
                    pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                    detail = admission.toPatchRejectionDetail(),
                )
            }
            val rejection = pageWriteRejection(pageKey, expected)
            if (rejection != null) {
                val actual = store.snapshotLocked(pageKey)
                val actualLeaseToken = store.pageLeases[pageKey]?.token
                store.lastGuardedWriteRejectionDiagnostic = formatWriteDiagnostic(
                    pageKey,
                    "description" to description,
                    "reason" to rejection.reason,
                    "expected" to "{generation=${expected.generation}, pageVersion=${expected.pageVersion}, " +
                        "artifactPageVersion=${expected.artifactPageVersion}, candidateGenerationId=${expected.candidateGenerationId}, " +
                        "dependencyFingerprint=${expected.dependencyFingerprint}, leaseToken=${expected.leaseToken}}",
                    "actual" to "{generation=${actual.generation}, pageVersion=${actual.pageVersion}, " +
                        "artifactPageVersion=${actual.artifactPageVersion}, candidateGenerationId=${actual.candidateGenerationId}, " +
                        "dependencyFingerprint=${actual.dependencyFingerprint}, leaseToken=${actual.leaseToken}}",
                )
                rejected(pageKey, description, rejection.reason, rejection.detail)
            } else {
                val previous = store.pages[pageKey]?.toDraft()
                val updated = store.ownedPage(pageKey, update(previous?.detachedCopy()))
                store.pages = store.pages.put(pageKey, store.publishPage(updated))
                if (!publishLocked(previous, updated, expected)) {
                    restorePageLocked(pageKey, previous)
                    val actual = store.snapshotLocked(pageKey)
                    store.lastGuardedWriteRejectionDiagnostic = formatWriteDiagnostic(
                        pageKey,
                        "description" to description,
                        "reason" to "ARTIFACT_PUBLICATION_FAILED",
                        "artifactPublication" to (store.publicationBridge.lastArtifactPublicationRejectionDiagnostic ?: "reason unavailable"),
                        "expected" to "{generation=${expected.generation}, pageVersion=${expected.pageVersion}, " +
                            "artifactPageVersion=${expected.artifactPageVersion}, candidateGenerationId=${expected.candidateGenerationId}, " +
                            "dependencyFingerprint=${expected.dependencyFingerprint}, leaseToken=${expected.leaseToken}}",
                        "actual" to "{generation=${actual.generation}, pageVersion=${actual.pageVersion}, " +
                            "artifactPageVersion=${actual.artifactPageVersion}, candidateGenerationId=${actual.candidateGenerationId}, " +
                            "dependencyFingerprint=${actual.dependencyFingerprint}, leaseToken=${actual.leaseToken}}",
                    )
                    return@withLock rejected(
                        pageKey,
                        description,
                        "ARTIFACT_PUBLICATION_FAILED",
                        PatchResult.Rejected.Detail.ArtifactPublicationFailed,
                    )
                }
                PatchResult.Accepted(store.snapshotLocked(pageKey))
            }
        }
    }

    /**
     * Revalidates a BATCH translation result once after a strict page-version rejection. The
     * gate remains authoritative: this method never repairs a token mismatch and only rebases
     * translation-owned fields while the captured run, dependency, artifact version, and BATCH
     * lease still match under the store mutex.
     */
    internal suspend fun reconcileBatchTranslationPageVersionDrift(
        pageKey: String,
        expected: PatchPrecondition,
        candidate: PageTranslation,
    ): TranslationPublicationReconciliation {
        if (store.isDefunct) return TranslationPublicationReconciliation.Deferred("store is defunct")
        return mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    return@withLock TranslationPublicationReconciliation.Deferred(
                        "${admission.code}: ${admission.message}",
                    )
                }
            }

            val actual = store.snapshotLocked(pageKey)
            val lease = store.pageLeases[pageKey]
            val identityDrift = when {
                actual.generation != expected.generation || store.generation != expected.generation ->
                    "generation changed"
                expected.leaseToken == null ||
                    lease == null ||
                    lease.origin != PageWriteOrigin.BATCH ||
                    lease.token != expected.leaseToken ||
                    lease.generation != expected.generation ->
                    "active BATCH lease changed"
                actual.candidateGenerationId != expected.candidateGenerationId ->
                    "candidate generation changed"
                actual.dependencyFingerprint != expected.dependencyFingerprint ->
                    "dependency fingerprint changed"
                actual.artifactPageVersion != expected.artifactPageVersion ->
                    "artifact page version changed"
                expected.blockFingerprints != null &&
                    actual.blockFingerprints != expected.blockFingerprints ->
                    "block fingerprint changed"
                actual.pageVersion == expected.pageVersion ->
                    "page version no longer differs"
                candidate.sourceFileName != pageKey ->
                    "candidate page identity changed"
                candidate.translationStatus != StageStatus.READY ->
                    "candidate is not a completed translation"
                else -> null
            }
            if (identityDrift != null) {
                return@withLock TranslationPublicationReconciliation.Deferred(identityDrift)
            }

            val current = actual.page
                ?: return@withLock TranslationPublicationReconciliation.Deferred("page missing")
            if (sameTranslationOutput(current, candidate) && translationOutputIsDurableLocked(pageKey, current)) {
                return@withLock TranslationPublicationReconciliation.AlreadyDurable(actual)
            }
            val rebasedBlocks = rebaseTranslationBlocks(current, candidate)
                ?: return@withLock TranslationPublicationReconciliation.Deferred(
                    "translation candidate block identity changed",
                )

            val updated = current.detachedCopy().apply {
                blocks = rebasedBlocks
                translationStatus = candidate.translationStatus
                translationError = candidate.translationError
                translationFingerprint = candidate.translationFingerprint
                translationOrigin = candidate.translationOrigin
                sourceFingerprint = candidate.sourceFingerprint ?: sourceFingerprint
            }
            val owned = store.ownedPage(pageKey, updated)
            val expectedCurrent = actual.toPrecondition()
            store.pages = store.pages.put(pageKey, store.publishPage(owned))
            if (!publishLocked(current, owned, expectedCurrent)) {
                restorePageLocked(pageKey, current)
                TranslationPublicationReconciliation.PublicationFailed(
                    store.publicationBridge.lastArtifactPublicationRejectionDiagnostic ?: "ARTIFACT_PUBLICATION_FAILED",
                )
            } else {
                TranslationPublicationReconciliation.Rebased(store.snapshotLocked(pageKey))
            }
        }
    }

    private fun sameTranslationOutput(current: PageTranslation, candidate: PageTranslation): Boolean =
        current.translationStatus == StageStatus.READY &&
            candidate.translationStatus == StageStatus.READY &&
            current.translationError == candidate.translationError &&
            current.translationFingerprint == candidate.translationFingerprint &&
            current.translationOrigin == candidate.translationOrigin &&
            current.blocks.map { it.stableFingerprint() } == candidate.blocks.map { it.stableFingerprint() }

    private fun rebaseTranslationBlocks(
        current: PageTranslation,
        candidate: PageTranslation,
    ): MutableList<TranslationBlock>? {
        if (current.blocks.size != candidate.blocks.size) return null
        val rebased = mutableListOf<TranslationBlock>()
        current.blocks.zip(candidate.blocks).forEach { (currentBlock, candidateBlock) ->
            // TranslationBlock equality verifies every OCR/layout/edit field while we ignore only
            // the translated string. Preserve the current block and overlay that one owned field.
            if (candidateBlock.copy(translation = currentBlock.translation) != currentBlock) return null
            rebased += currentBlock.detachedCopy().apply { translation = candidateBlock.translation }
        }
        return rebased
    }

    /** True only when the artifact manifest points at this exact semantic page snapshot. */
    private fun translationOutputIsDurableLocked(pageKey: String, page: PageTranslation): Boolean {
        val artifactPage = store.artifactManifest?.pages?.get(pageKey) ?: return false
        val fingerprint = StageFingerprints.pageSnapshot(page)
        // Candidate sidecars are persisted before their manifest pointer. The committed field's
        // historical name is translationFingerprint, but it stores this same page-snapshot hash.
        return artifactPage.candidate?.pageSnapshotFingerprint == fingerprint ||
            artifactPage.committed?.translationFingerprint == fingerprint
    }

    /**
     * Persists a retryable/terminal stage failure together with the current
     * candidate snapshot. The page mutation and durable failure pointer share
     * one artifact-manifest publication, so a restart cannot observe a page
     * status without its failure metadata (or vice versa).
     */
    suspend fun persistDurableStageFailure(
        pageKey: String,
        expected: PatchPrecondition,
        failure: DurableFailureMetadata,
        description: String = "durable stage failure",
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (store.isDefunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejected(
                    pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                )
            }
            val rejection = pageWriteRejection(pageKey, expected)
            if (rejection != null) {
                return@withLock rejected(pageKey, description, rejection.reason, rejection.detail)
            }
            val previous = store.pages[pageKey]?.toDraft()
            val updated = try {
                store.ownedPage(pageKey, update(previous?.detachedCopy()))
            } catch (error: IllegalArgumentException) {
                return@withLock rejected(pageKey, description, error.message ?: error::class.java.simpleName)
            } catch (error: IllegalStateException) {
                return@withLock rejected(pageKey, description, error.message ?: error::class.java.simpleName)
            }
            store.pages = store.pages.put(pageKey, store.publishPage(updated))
            val persisted = store.publicationBridge.persistArtifactMutation(
                pageKey = pageKey,
                previous = previous,
                updated = updated,
                expected = expected,
                durableFailure = failure,
            )
            if (!persisted) {
                restorePageLocked(pageKey, previous)
                return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
            }
            // A retryable/terminal candidate is deliberately not promoted. The
            // prior committed display remains the reader authority.
            store.stateFlow.value = store.snapshotPages()
            store.displayPublication.displaySnapshotLocked(listOf(pageKey))
            PatchResult.Accepted(store.snapshotLocked(pageKey))
        }
    }

    /** Compatibility-shaped convenience for non-stage callers; still fenced by a snapshot. */
    suspend fun updatePageFromCurrentSnapshot(
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = updatePageGuarded(pageKey, store.snapshot(pageKey).toPrecondition(), description, update)

    internal suspend fun patchBlock(
        pageKey: String,
        blockIndex: Int,
        expected: PatchPrecondition,
        expectedBlockFingerprint: String,
        expectedTranslation: String? = null,
        expectedUserEditedAt: Long? = null,
        description: String,
        patch: (TranslationBlock) -> TranslationBlock,
    ): PatchResult = patchPage(pageKey, expected, description) { page ->
        requireNotNull(page) { "page missing" }
        val current = page.blocks.getOrNull(blockIndex)
            ?: throw IllegalStateException("block index $blockIndex missing")
        check(current.stableFingerprint() == expectedBlockFingerprint) { "block fingerprint changed" }
        if (expectedTranslation !=
            null
        ) {
            check(current.translation == expectedTranslation) { "block translation changed" }
        }
        check(current.userEditedAt == expectedUserEditedAt) { "block edit timestamp changed" }
        page.apply { blocks[blockIndex] = patch(current.detachedCopy()).detachedCopy() }
    }

    /** Apply a stage patch while checking only the identities owned by that stage. */
    internal suspend fun publishLocked(
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition? = null,
        durableFailure: DurableFailureMetadata? = null,
    ): Boolean {
        val pageKey = updated.sourceFileName ?: ""
        // Active reader stores are display-first: publish the complete live
        // snapshot and enqueue the artifact transaction for the IO worker.
        // Durable failure metadata remains synchronous so cancellation/retry
        // semantics never report a failure before its manifest marker is stored.
        if (store.lazyPersistenceEnabled && durableFailure == null) {
            return publishLazyLocked(pageKey, previous, updated, expected)
        }
        val isDurable = shouldPersistUpdate(previous, updated)
        val artifactAccepted = if (isDurable || store.artifactManifest?.pages?.containsKey(pageKey) != true) {
            store.publicationBridge.persistArtifactMutation(
                pageKey = pageKey,
                previous = previous,
                updated = updated,
                expected = expected,
                durableFailure = durableFailure,
            )
        } else {
            true
        }
        if (!artifactAccepted) return false
        store.displayPublication.promoteDisplayIfReadyLocked(pageKey, updated)
        store.stateFlow.value = store.snapshotPages()
        store.displayPublication.displaySnapshotLocked(listOf(pageKey))
        return true
    }

    private fun publishLazyLocked(
        pageKey: String,
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition?,
    ): Boolean {
        val snapshot = store.snapshotLocked(pageKey)
        val shouldQueueArtifact = shouldPersistUpdate(previous, updated) ||
            store.artifactManifest?.pages?.containsKey(pageKey) != true
        if (shouldQueueArtifact) {
            store.writeDrain.pendingLazyMutations[pageKey] = PendingLazyMutation(
                pageKey = pageKey,
                previous = previous?.detachedCopy(),
                updated = updated.detachedCopy(),
                expected = PatchPrecondition(
                    generation = snapshot.generation,
                    pageVersion = snapshot.pageVersion,
                    blockFingerprints = snapshot.blockFingerprints,
                    leaseToken = snapshot.leaseToken ?: expected?.leaseToken,
                    candidateGenerationId = snapshot.candidateGenerationId,
                    dependencyFingerprint = snapshot.dependencyFingerprint,
                    artifactPageVersion = snapshot.artifactPageVersion,
                ),
                generation = snapshot.generation,
            )
        }
        store.displayPublication.promoteDisplayIfReadyLocked(pageKey, updated)
        store.stateFlow.value = store.snapshotPages()
        store.displayPublication.displaySnapshotLocked(listOf(pageKey))
        if (shouldQueueArtifact) store.writeDrain.scheduleDrain()
        return true
    }

    internal fun restorePageLocked(pageKey: String, previous: PageTranslation?) {
        store.pages = if (previous == null) store.pages.remove(pageKey) else store.pages.put(pageKey, store.publishPage(previous))
        store.stateFlow.value = store.snapshotPages()
        store.displayPublication.displaySnapshotLocked(listOf(pageKey))
    }

    internal fun rejected(
        pageKey: String,
        description: String,
        reason: String,
        detail: PatchResult.Rejected.Detail? = null,
    ): PatchResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT store patch rejected: pageKey=$pageKey generation=${store.generation} " +
                "operation=$description reason=$reason"
        }
        return PatchResult.Rejected(reason, detail)
    }

    private fun MutationAdmission.Rejected.toPatchRejectionDetail(): PatchResult.Rejected.Detail? =
        when (code) {
            MutationAdmission.Rejected.Code.ARTIFACT_PUBLICATION_FAILED ->
                PatchResult.Rejected.Detail.ArtifactPublicationFailed
            MutationAdmission.Rejected.Code.STORE_DEFUNCT,
            MutationAdmission.Rejected.Code.FENCE_REJECTED,
            -> null
        }

    /** The authoritative values are immutable, so StateFlow snapshots share the persistent map. */
    internal fun shouldPersistUpdate(previous: PageTranslation?, updated: PageTranslation): Boolean {
        // Persist only DURABLE progress worth surviving a chapter reopen, not
        // transient placeholders; transient bookkeeping stays in-memory (the
        // reader observes the live StateFlow). Critical because the stranded-page
        // sweep (ReaderViewModel.sweepStrandedPageStatus) writes exactly the
        // placeholder shape — CANCELLED stages + errorMessage, no blocks, no
        // image — via updatePage. The old logic (errorMessage != null -> persist,
        // and `!isStageRunning` -> persist) saved those placeholders to JSON,
        // where under the old pages.isNotEmpty() check a single such entry made
        // the chapter falsely read TRANSLATED forever. clearTransientQueuePages()
        // publishes durable cancellation through the artifact bridge.
        // 1. Completed display output, including textless pages using the source image.
        if (updated.hasRenderedResult || updated.isTextlessTerminal) return true
        // 2. Recognized text blocks (real OCR work).
        if (updated.blocks.isNotEmpty()) return true
        // 3. Cleaned image; lets a later render resume without redoing the neural pass.
        if (updated.cleanedImageName != null) return true
        // 4. Stage failures so retry-exhaustion (hasExhaustedRetries) survives
        //    reopen and the scheduler can skip permanently-failing pages.
        if (updated.isStageFailed) return true
        // 5. Transition away from a previously-rendered result (e.g. forced retry
        //    cleared the image) so the reader stops showing the stale one.
        if (previous?.hasRenderedResult == true && !updated.hasRenderedResult) return true
        // Transient placeholder (RUNNING/PENDING/CANCELLED with no content and no
        // failure): keep it in memory only.
        return false
    }

    // These lifecycle methods preserve a single persistence boundary for
    // reset, resolver, and batch callers. The drain owns queued lazy image
    // writes; artifact snapshots are published by the artifact engine inline.

    internal fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): PageWriteRejection? {
        val current = store.pages[pageKey]
        val artifactPage = store.artifactManifest?.pages?.get(pageKey)
        return when {
            expected.generation != store.generation ->
                PageWriteRejection("generation expected=${expected.generation} actual=${store.generation}")
            expected.pageVersion != (current?.pageVersion ?: 0L) -> {
                val actualVersion = current?.pageVersion ?: 0L
                PageWriteRejection("pageVersion expected=${expected.pageVersion} actual=$actualVersion")
            }
            artifactVersionMovedBackwards(expected.artifactPageVersion, artifactPage?.pageVersion) ->
                PageWriteRejection("artifact pageVersion expected=${expected.artifactPageVersion} actual=${artifactPage?.pageVersion}")
            expected.candidateGenerationId != null &&
                expected.candidateGenerationId != artifactPage?.candidate?.generationId ->
                PageWriteRejection(
                    "candidate generation expected=${expected.candidateGenerationId} " +
                        "actual=${artifactPage?.candidate?.generationId}",
                )
            expected.dependencyFingerprint != null &&
                artifactPage?.candidate?.let { candidate ->
                    expected.dependencyFingerprint != candidate.dependencyFingerprint
                } == true ->
                PageWriteRejection(
                    "candidate dependency fingerprint expected=${expected.dependencyFingerprint} " +
                        "actual=${artifactPage?.candidate?.dependencyFingerprint}",
                )
            expected.blockFingerprints != null &&
                expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                PageWriteRejection("block fingerprint changed")
            expected.leaseToken != null && store.pageLeases[pageKey]?.token != expected.leaseToken -> {
                val actualToken = store.pageLeases[pageKey]?.token
                PageWriteRejection("page lease token expected=${expected.leaseToken} actual=$actualToken")
            }
            expected.leaseToken == null && store.pageLeases[pageKey] != null ->
                PageWriteRejection("page lease token required")
            else -> null
        }
    }

    internal suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) {
        if (store.isDefunct) {
            rejected(pageKey, "updatePage", "store is defunct")
            return
        }
        val context = currentCoroutineContext()[ChapterTranslationStore.GenerationContext]
        val expected = mutex.withLock {
            if (context?.store === store && context.generation != store.generation) {
                null
            } else if (store.artifactManifest != null && store.pageLeases[pageKey] != null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT compatibility updatePage rejected while a page lease is active: pageKey=$pageKey"
                }
                null
            } else {
                store.snapshotLocked(pageKey).toPrecondition()
            }
        }
        if (expected == null) {
            if (context?.store === store && context.generation != store.generation) {
                rejected(
                    pageKey,
                    "updatePage",
                    "generation expected=${context.generation} actual=${store.generation}",
                )
            }
            return
        }
        updatePageGuarded(pageKey, expected, "updatePage", update)
    }
}
