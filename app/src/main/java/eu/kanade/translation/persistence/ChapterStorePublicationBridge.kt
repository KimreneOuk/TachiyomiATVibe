package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.pipeline.batch.formatWriteDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Selects the single-page artifact publication for a live mutation. */
internal enum class ArtifactMutation {
    Failure,
    Direct,
}

/** Durable failures need failure metadata; ordinary page mutations publish directly. */
internal fun classifyMutation(
    durableFailure: DurableFailureMetadata?,
): ArtifactMutation = when {
    durableFailure != null -> ArtifactMutation.Failure
    else -> ArtifactMutation.Direct
}

private fun PageTranslationView.sourceIdentity(pageKey: String): SourceIdentity? =
    sourceFingerprint?.let { fingerprint ->
        SourceIdentity(
            pageKey = pageKey,
            sha256 = fingerprint,
            width = imgWidth.takeIf { it > 0f }?.toInt(),
            height = imgHeight.takeIf { it > 0f }?.toInt(),
        )
    }

/**
 * Artifact publication collaborator (Phase 7 S4). Owns one chapter's mutation
 * admission, lazy artifact-store initialization, the manifest registration
 * CAS, the candidate open/cancel/persist/promote/failure orchestration, and
 * the origin-resolution rules; the owning store delegates every call here
 * under the store mutex. The only lock this collaborator acquires beneath the
 * store mutex is the per-artifact open lock, as a coroutine — store mutex →
 * artifact-open lock, never reversed (Phase 7 S2 lock order).
 *
 * [lastArtifactPublicationRejectionDiagnostic] keeps its pre-extraction
 * lifetime: cleared at the start of each [persistArtifactMutation] and set on
 * every typed rejection with byte-identical formatting, so failure diagnostics
 * and the LI-x stale-retry registration semantics are unchanged.
 */
internal class ChapterStorePublicationBridge(private val store: ChapterTranslationStore) {

    /** Latest artifact-publication rejection, retained for failure diagnostics. */
    @Volatile
    internal var lastArtifactPublicationRejectionDiagnostic: String? = null
        private set

    /** Caller holds [ChapterTranslationStore.mutex]; suspends while a lazy first write opens the artifact store. */
    internal suspend fun admitMutation(): MutationAdmission {
        if (store.defunct) {
            return MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.STORE_DEFUNCT,
                message = "store is defunct",
            )
        }
        if (store.engineMode is ChapterStoreEngineMode.Durable && store.artifactManifest != null) {
            return MutationAdmission.Granted
        }
        // Explicitly memory-only stores are used by pure reducer/unit tests;
        // they have no persistence target and therefore cannot accidentally
        // create a legacy document. Production stores always provide a parent
        // or an already-open artifact document and take the lazy creation path below.
        if (store.engineMode is ChapterStoreEngineMode.Memory) {
            return MutationAdmission.Granted
        }
        if (!ensureArtifactStore()) {
            return MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.ARTIFACT_PUBLICATION_FAILED,
                message = "artifact store creation/publication failed",
            )
        }
        return if (store.engineMode is ChapterStoreEngineMode.Durable && store.artifactManifest != null) {
            MutationAdmission.Granted
        } else {
            MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.ARTIFACT_PUBLICATION_FAILED,
                message = "artifact store was not established",
            )
        }
    }

    /**
     * Lazily creates the artifact store for a chapter that has no manifest yet.
     * Suspending: acquires the same per-artifact open lock as
     * [ChapterTranslationStore.Companion.openArtifactSuspend] by waiting as a
     * coroutine, so lazy loads and recovery opens cannot race each other and a
     * first write never parks a carrier thread while holding the store mutex.
     * Lock order: store mutex → artifact-open lock (never reversed).
     */
    internal suspend fun ensureArtifactStore(): Boolean {
        if (store.engineMode is ChapterStoreEngineMode.Durable) {
            return store.artifactManifest != null
        }
        // Lazy production stores must provide an artifact parent/name. A store
        // opened before its document existed resolves the parent through
        // [ChapterTranslationStore.artifactParentResolver] here, so the first real
        // write creates the artifact manifest without reading or writing the
        // preserved flat compatibility file.
        val parent = store.artifactParent
            ?: store.artifactParentResolver?.let { resolve -> runCatching { resolve() }.getOrNull() }
            ?: return false
        val fileName = store.artifactFileName ?: return false
        val layout = ChapterArtifactLayout.fromArtifactFileName(fileName)
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
        val engine = ChapterArtifactEngine(documents, layout)
        val manifest = withContext(Dispatchers.IO) {
            ChapterTranslationStore.withArtifactOpenLock(parent, fileName) { engine.load().manifest }
        }
        store.engineMode = ChapterStoreEngineMode.Durable(engine)
        store.artifactManifest = manifest
        return true
    }

    /**
     * Production artifact bridge for mutable page emissions. Every emission is
     * first written to a candidate snapshot. A display-ready emission writes
     * the immutable committed snapshot and moves the manifest pointer in one
     * final publication; the journal separately records the live state mutation.
     *
     * Caller holds [ChapterTranslationStore.mutex].
     */
    internal suspend fun persistArtifactMutation(
        pageKey: String,
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: ChapterTranslationStore.PatchPrecondition? = null,
        durableFailure: DurableFailureMetadata? = null,
    ): Boolean {
        lastArtifactPublicationRejectionDiagnostic = null
        fun reject(reason: String): Boolean {
            val page = store.artifactManifest?.pages?.get(pageKey)
            lastArtifactPublicationRejectionDiagnostic = formatWriteDiagnostic(
                pageKey,
                "reason" to reason,
                "expected" to "{generation=${expected?.generation}, pageVersion=${expected?.pageVersion}, " +
                    "artifactPageVersion=${expected?.artifactPageVersion}, candidateGenerationId=${expected?.candidateGenerationId}, " +
                    "dependencyFingerprint=${expected?.dependencyFingerprint}, leaseToken=${expected?.leaseToken}}",
                "localArtifact" to "{pageVersion=${page?.pageVersion}, " +
                    "candidateGenerationId=${page?.candidate?.generationId}, " +
                    "dependencyFingerprint=${page?.candidate?.dependencyFingerprint}}",
            )
            return false
        }
        if (store.engineMode is ChapterStoreEngineMode.Memory) {
            // Pure in-memory stores have no persistence target and never
            // create a compatibility document as a side effect.
            return true
        }
        if (store.engineMode !is ChapterStoreEngineMode.Durable &&
            !ensureArtifactStore()
        ) {
            return reject("artifact store initialization failed")
        }
        val engine = (store.engineMode as? ChapterStoreEngineMode.Durable)?.artifact
            ?: return reject("durable artifact store unavailable")
        var manifest = store.artifactManifest ?: run {
            return true
        }
        if (pageKey.isEmpty()) {
            return true
        }
        val firstReaderBaseline = if (
            manifest.expectedPageCount == null && store.pendingExpectedPageCount == null
        ) {
            store.pages.size
        } else {
            0
        }
        val expectedPageCount = maxOf(
            manifest.expectedPageCount ?: 0,
            store.pendingExpectedPageCount ?: 0,
            firstReaderBaseline,
        ).takeIf { it > 0 }
        val expectedPageCountTrusted =
            manifest.expectedPageCountTrusted || store.pendingExpectedPageCountTrusted
        val registrationKeys = (store.pendingArtifactPageRegistrations + pageKey)
            .filter { it.isNotEmpty() && it !in manifest.pages }
        val expectedCountChanged = expectedPageCount != manifest.expectedPageCount ||
            expectedPageCountTrusted != manifest.expectedPageCountTrusted
        if (registrationKeys.isNotEmpty() || expectedCountChanged) {
            //  LI-x: this publication used to blind-publish
            // (`publishManifest`) a manifest rebuilt from the FAÇADE snapshot,
            // silently reverting any newer durable field: the >8-page open
            // path's background manifest publication can race without
            // refreshing the façade, so the next resume-hydration adoption's
            // registration write must not clobber newer durable fields. Route
            // through the CAS'd sidecar-pointer transaction instead: the
            // registration mutation (page-set + expected count) is recomputed
            // against the FRESH durable manifest on a stale-manifest rejection
            // — one-shot, the same semantics as the envelope-plan seam — so
            // the mutation is pointer-set-only and every other durable field
            // is carried forward by construction.
            when (
                val registration = engine.publishSidecarPointersWithStaleRetry(
                    manifest = manifest,
                    sidecars = emptyList(),
                    updatePointers = { current ->
                        val keys = (store.pendingArtifactPageRegistrations + pageKey)
                            .filter { it.isNotEmpty() && it !in current.pages }
                        val currentFirstReaderBaseline =
                            if (current.expectedPageCount == null && store.pendingExpectedPageCount == null) {
                                store.pages.size
                            } else {
                                0
                            }
                        current.copy(
                            pages = current.pages + keys.associateWith {
                                PageArtifactRecord(pageKey = it)
                            },
                            expectedPageCount = maxOf(
                                current.expectedPageCount ?: 0,
                                store.pendingExpectedPageCount ?: 0,
                                currentFirstReaderBaseline,
                            ).takeIf { it > 0 },
                            expectedPageCountTrusted =
                            current.expectedPageCountTrusted || store.pendingExpectedPageCountTrusted,
                        )
                    },
                    nowEpochMs = System.currentTimeMillis(),
                    seam = "page-registration",
                )
            ) {
                is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                    manifest = registration.manifest
                    store.artifactManifest = manifest
                    store.pendingArtifactPageRegistrations.removeAll(registrationKeys.toSet())
                    if (expectedCountChanged) {
                        store.pendingExpectedPageCount = null
                        store.pendingExpectedPageCountTrusted = false
                    }
                }
                is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact page registration failed: pageKey=$pageKey " +
                            "count=${registrationKeys.size} reason=${registration.reason}"
                    }
                    return reject("page registration rejected: ${registration.reason}")
                }
            }
        }
        val record = manifest.pages.getValue(pageKey)
        if (expected != null) {
            if (expected.artifactPageVersion != null && expected.artifactPageVersion != record.pageVersion) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey " +
                        "reason=artifact pageVersion expected=${expected.artifactPageVersion} actual=${record.pageVersion}"
                }
                return reject("artifact pageVersion expected=${expected.artifactPageVersion} actual=${record.pageVersion}")
            }
            if (expected.candidateGenerationId != null && expected.candidateGenerationId != record.candidate?.generationId) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey " +
                        "reason=candidate generation expected=${expected.candidateGenerationId} " +
                        "actual=${record.candidate?.generationId}"
                }
                return reject(
                    "candidate generation expected=${expected.candidateGenerationId} " +
                        "actual=${record.candidate?.generationId}",
                )
            }
            if (expected.dependencyFingerprint != null &&
                expected.dependencyFingerprint != record.candidate?.dependencyFingerprint &&
                record.candidate != null
            ) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey " +
                        "reason=dependency fingerprint expected=${expected.dependencyFingerprint} " +
                        "actual=${record.candidate?.dependencyFingerprint}"
                }
                return reject(
                    "dependency fingerprint expected=${expected.dependencyFingerprint} " +
                        "actual=${record.candidate?.dependencyFingerprint}",
                )
            }
        } else if (store.pageLeases[pageKey] != null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=unfenced active lease"
            }
            return reject("unfenced active lease token=${store.pageLeases[pageKey]?.token}")
        }
        // Compatibility writers may resume an on-disk candidate after process
        // death before a new page lease is attached. Preserve the durable
        // candidate provenance in that narrow no-lease window; otherwise a
        // READER_ADHOC candidate would be misclassified as BATCH and its next
        // snapshot could never be persisted.
        //
        //  round 3: a durable-FAILURE publication is a page-level ledger
        // write, not work product — keep the live candidate's own provenance
        // when one exists. Deriving the origin from the CURRENT lease holder
        // routed the failure persist through cancelLiveCandidate +
        // openCandidate under a FOREIGN origin (the display-tail drain
        // persists its typed failure while a MANUAL reader lease holds the
        // page's Render stage), splitting the ONE atomic page+failure
        // publication the API documents into a cancel/reopen/persist chain
        // against a candidate the failure writer does not own.
        val leaseOrigin = store.pageLeases[pageKey]?.origin?.toArtifactOrigin()
        val origin = when {
            durableFailure != null && record.candidate != null -> record.candidate.origin
            leaseOrigin != null -> leaseOrigin
            else -> record.candidate?.origin ?: ArtifactOrigin.BATCH
        }
        val candidate = record.candidate
        val dependencyFingerprint = expected?.dependencyFingerprint
            ?: candidate?.dependencyFingerprint
            ?: StageFingerprints.pageSnapshot(previous ?: updated)
        /**
         * P3B: one typed finish-retry for a primary-advanced/replica-failed
         * rejection. The receipt records orphan sidecar evidence (or a
         * restored candidate manifest); [ChapterArtifactEngine.finishIncompletePublication]
         * re-presents it with NEWER-STATE DOMINANCE — when the durable
         * manifest moved past the receipt's actually-advanced state, the
         * older receipt is retired without any write. A candidate-restore
         * receipt (recoverable manifest) keeps the journal-handoff handling
         * in the Direct branch and is not re-run here.
         */
        fun finishIncomplete(
            outcome: ChapterArtifactEngine.TransactionOutcome,
            retry: (ChapterArtifactManifest) -> ChapterArtifactEngine.TransactionOutcome,
        ): ChapterArtifactEngine.TransactionOutcome {
            if (outcome !is ChapterArtifactEngine.TransactionOutcome.Rejected) return outcome
            val receipt = outcome.incompletePublication ?: return outcome
            if (outcome.recoverableCandidateManifest != null) return outcome
            return engine.finishIncompletePublication(receipt, retry)
        }
        if (candidate == null || candidate.origin != origin) {
            val baseManifest = if (candidate != null && candidate.origin != origin) {
                when (val cancelled = engine.candidates.cancelLiveCandidate(manifest, pageKey, candidate.generationId)) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> cancelled.manifest
                    else -> manifest
                }
            } else {
                manifest
            }
            fun open(manifest: ChapterArtifactManifest) = engine.candidates.openCandidate(
                manifest = manifest,
                pageKey = pageKey,
                origin = origin,
                expectedPageVersion = baseManifest.pages[pageKey]?.pageVersion ?: record.pageVersion,
                dependencyFingerprint = dependencyFingerprint,
            )
            val opened = finishIncomplete(open(baseManifest), ::open)
            manifest = when (opened) {
                is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                    store.reportCandidateReuse(opened.candidateOpenState)
                    opened.manifest
                }
                is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                    store.reportCandidateReuse(opened.candidateOpenState)
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact candidate open rejected: pageKey=$pageKey reason=${opened.reason}"
                    }
                    return reject("candidate open rejected: ${opened.reason}")
                }
            }
            store.artifactManifest = manifest
        }
        val currentCandidate = manifest.pages.getValue(pageKey).candidate
            ?: run { return reject("candidate missing after open") }
        val expectedPageVersion = manifest.pages.getValue(pageKey).pageVersion
        when (classifyMutation(durableFailure)) {
            ArtifactMutation.Failure -> {
                val failure = checkNotNull(durableFailure)
                fun persistFailure(manifest: ChapterArtifactManifest) = engine.candidates.persistLiveCandidateAndFailure(
                    manifest = manifest,
                    pageKey = pageKey,
                    generationId = currentCandidate.generationId,
                    expectedPageVersion = expectedPageVersion,
                    expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                    pageSnapshot = updated,
                    origin = origin,
                    failure = failure,
                    sourceIdentity = updated.sourceIdentity(pageKey),
                )
                val persisted = finishIncomplete(persistFailure(manifest), ::persistFailure)
                manifest = when (persisted) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> persisted.manifest
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        persisted.incompletePublication?.let { receipt ->
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT artifact candidate persist incomplete: pageKey=$pageKey " +
                                    "orphanFiles=${receipt.advancedFiles.size} reason=${receipt.failureReason}"
                            }
                        }
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT artifact candidate persist rejected: pageKey=$pageKey reason=${persisted.reason}"
                        }
                        return reject("candidate persist rejected: ${persisted.reason}")
                    }
                }
                store.artifactManifest = manifest
            }
            ArtifactMutation.Direct -> {
                val isOcrLeaseActive = store.pageLeases[pageKey]?.stage == PageStage.Ocr
                val immediatelyPromotable = (updated.hasRenderedResult || updated.isTextlessTerminal) &&
                    (!isOcrLeaseActive || updated.isTextlessTerminal)
                fun persistDirect(manifest: ChapterArtifactManifest) =
                    if (immediatelyPromotable) {
                        engine.candidates.promoteLiveCandidateImmediately(
                            manifest = manifest,
                            pageKey = pageKey,
                            generationId = currentCandidate.generationId,
                            expectedPageVersion = expectedPageVersion,
                            expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                            pageSnapshot = updated,
                            origin = origin,
                            sourceIdentity = updated.sourceIdentity(pageKey),
                        )
                    } else {
                        engine.candidates.persistLiveCandidate(
                            manifest = manifest,
                            pageKey = pageKey,
                            generationId = currentCandidate.generationId,
                            expectedPageVersion = expectedPageVersion,
                            expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                            pageSnapshot = updated,
                            origin = origin,
                            sourceIdentity = updated.sourceIdentity(pageKey),
                        )
                    }
                val persisted = finishIncomplete(persistDirect(manifest), ::persistDirect)
                when (persisted) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> manifest = persisted.manifest
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        persisted.recoverableCandidateManifest?.takeIf { immediatelyPromotable }?.let { recoverableManifest ->
                            // The old two-step path had already published its candidate manifest
                            // before terminal promotion could reject. Preserve that journal
                            // handoff only when the fused failure path restored the same truth.
                            manifest = recoverableManifest
                            store.artifactManifest = recoverableManifest
                        }
                        persisted.incompletePublication?.let { receipt ->
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT artifact ${if (immediatelyPromotable) "terminal publication" else "candidate persist"} " +
                                    "incomplete: pageKey=$pageKey orphanFiles=${receipt.advancedFiles.size} " +
                                    "reason=${receipt.failureReason}"
                            }
                        }
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT artifact ${if (immediatelyPromotable) "terminal publication" else "candidate persist"} " +
                                "rejected: pageKey=$pageKey reason=${persisted.reason}"
                        }
                        return reject(
                            "${if (immediatelyPromotable) "terminal publication" else "candidate persist"} " +
                                "rejected: ${persisted.reason}",
                        )
                    }
                }
                store.artifactManifest = manifest
            }
        }
        store.artifactManifest = manifest
        return true
    }
}
