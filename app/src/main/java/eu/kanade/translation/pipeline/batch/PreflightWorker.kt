package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.context.SeriesProfileRegistry
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.OcrCheckpointMode
import eu.kanade.translation.persistence.artifact.ProfilePointer
import eu.kanade.translation.persistence.artifact.RunConfigSnapshot
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.CheckpointOcrResult
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class PreflightWorkerContext(
    val store: ChapterTranslationStore,
    val nativeWorker: NativeLaneWorker,
    val frozenConfig: RunConfigSnapshot,
    val effectiveSourcePairs: List<Pair<String, String>>,
    val releaseBatchLease: suspend (String) -> Unit,
    val listener: BatchScheduleListener,
    val nowEpochMs: () -> Long,
    val failureRecorder: suspend (PreflightStageFailure) -> Unit,
    val standardLane: Boolean,
    val seriesKey: String?,
    val publishRecord: suspend (
        ChapterArtifactEngine,
        ChapterRunRecord,
    ) -> ChapterArtifactEngine.TransactionOutcome?,
    val record: (
        String,
        ChapterRunState,
        String,
        String,
        Map<String, Int>,
        String?,
        ProfilePointer?,
    ) -> ChapterRunRecord,
    val admissionSourceSha: (String) -> String?,
    val orientationOf: (ChapterTranslationStore.PageSnapshot) -> String?,
    val resumeFinalizeOrComplete: suspend (
        ChapterArtifactEngine,
        ChapterRunRecord?,
        String,
        String,
        List<PageKey>,
    ) -> BatchPass1Outcome?,
    val runEnvelopePlanAndTranslate: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
    val runStandardTranslateAndFinalize: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String?,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
    val runAnalysisPhase: suspend (
        ChapterArtifactEngine,
        String,
        List<PageKey>,
        String,
        Map<String, Int>,
    ) -> BatchPass1Outcome,
    val adoptCheckpointSnapshot: suspend (
        ChapterArtifactEngine,
        String,
        ChapterTranslationStore.PageSnapshot,
    ) -> CheckpointAdoption,
    val stampAdoptedRenderTerminal: suspend (String) -> Unit,
    val profileInputFingerprintOf: (String) -> String,
)

internal class PreflightWorker(
    private val context: PreflightWorkerContext,
) {
    private val store: ChapterTranslationStore
        get() = context.store
    private val nativeWorker: NativeLaneWorker
        get() = context.nativeWorker
    private val frozenConfig: RunConfigSnapshot
        get() = context.frozenConfig
    private val effectiveSourcePairs: List<Pair<String, String>>
        get() = context.effectiveSourcePairs
    private val releaseBatchLease: suspend (String) -> Unit
        get() = context.releaseBatchLease
    private val listener: BatchScheduleListener
        get() = context.listener
    private val nowEpochMs: () -> Long
        get() = context.nowEpochMs
    private val standardLane: Boolean
        get() = context.standardLane
    private val seriesKey: String?
        get() = context.seriesKey

    private suspend fun publishRecord(
        artifact: ChapterArtifactEngine,
        record: ChapterRunRecord,
    ): ChapterArtifactEngine.TransactionOutcome? =
        context.publishRecord(artifact, record)

    private fun record(
        runId: String,
        state: ChapterRunState,
        frozenFingerprint: String,
        sourceDigest: String,
        counters: Map<String, Int>,
        ocrCorpusFingerprint: String? = null,
        profilePointer: ProfilePointer? = null,
    ): ChapterRunRecord = context.record(
        runId,
        state,
        frozenFingerprint,
        sourceDigest,
        counters,
        ocrCorpusFingerprint,
        profilePointer,
    )

    private suspend fun resumeFinalizeOrComplete(
        artifact: ChapterArtifactEngine,
        priorRecord: ChapterRunRecord?,
        frozenFingerprint: String,
        sourceDigest: String,
        orderedPages: List<PageKey>,
    ): BatchPass1Outcome? = context.resumeFinalizeOrComplete(
        artifact,
        priorRecord,
        frozenFingerprint,
        sourceDigest,
        orderedPages,
    )

    private suspend fun runEnvelopePlanAndTranslate(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = context.runEnvelopePlanAndTranslate(
        artifact,
        runId,
        orderedPages,
        corpusFingerprint,
        baseCounters,
    )

    private suspend fun runStandardTranslateAndFinalize(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String?,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = context.runStandardTranslateAndFinalize(
        artifact,
        runId,
        orderedPages,
        corpusFingerprint,
        baseCounters,
    )

    private suspend fun runAnalysisPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = context.runAnalysisPhase(
        artifact,
        runId,
        orderedPages,
        corpusFingerprint,
        baseCounters,
    )

    private suspend fun adoptCheckpointSnapshot(
        artifact: ChapterArtifactEngine,
        pageKey: String,
        before: ChapterTranslationStore.PageSnapshot,
    ): CheckpointAdoption = context.adoptCheckpointSnapshot(artifact, pageKey, before)

    private suspend fun stampAdoptedRenderTerminal(pageKey: String) =
        context.stampAdoptedRenderTerminal(pageKey)

    private data class FrozenProfileReuse(
        val pointer: ProfilePointer,
        val corpusFingerprint: String,
    )

    private suspend fun existingActiveRecord(
        artifact: ChapterArtifactEngine,
    ): ChapterArtifactEngine.RunRecordRead? {
        //  dispatch reads the DURABLE manifest, not the facade's cached
        // snapshot: the cache is a CAS optimization with many writers, while
        // this decision must never re-run paid work (or drain a stale
        // FINALIZE) behind what the artifact tree actually records. One
        // sidecar read per dispatch — negligible next to the preflight.
        return store.withArtifactEngineLocked { engine ->
            val pointer = engine.readManifest()?.activeRun ?: return@withArtifactEngineLocked null
            engine.readRunRecord(pointer)
        }
    }

    private suspend fun checkpointPage(
        artifact: ChapterArtifactEngine,
        pageKey: String,
        ref: OcrReadyPageRef,
    ): CheckpointOcrResult {
        // OCR preflight is a durability boundary. Active reader stores
        // publish page state and page registration memory-first, so drain
        // that queue before reading the manifest-backed checkpoint
        // identity. This call is deliberately outside any store mutex;
        // StorePersistenceScheduler serializes its own flush worker and
        // no flush task calls back into this checkpoint path.
        val lazyPersistence = store.isLazyPersistenceEnabled()
        if (lazyPersistence) {
            store.flush()
        }
        val snapshot = store.snapshot(pageKey)
        val leaseToken = ref.leaseToken
        if (leaseToken == null) {
            return CheckpointOcrResult.Rejected(
                "preflight ocr reference without a lease token",
            )
        }
        // The worker reference predates the flush. Prefer the fresh
        // manifest identity and retain the reference only for stores that
        // published synchronously before this boundary.
        val candidateGenerationId = if (lazyPersistence) {
            snapshot.candidateGenerationId ?: ref.candidateGenerationId
        } else {
            ref.candidateGenerationId
        }
        val artifactPageVersion = if (lazyPersistence) {
            snapshot.artifactPageVersion ?: ref.artifactPageVersion
        } else {
            snapshot.artifactPageVersion
        }
        val dependencyFingerprint = if (lazyPersistence && snapshot.candidateGenerationId != null) {
            snapshot.dependencyFingerprint
        } else {
            ref.dependencyFingerprint
        }
        val admissionSha = context.admissionSourceSha(pageKey)
        return store.checkpointOcr(
            pageKey = pageKey,
            generation = snapshot.generation,
            expectedPageVersion = snapshot.pageVersion,
            expectedLeaseToken = leaseToken,
            expectedCandidateGenerationId = candidateGenerationId,
            expectedArtifactPageVersion = artifactPageVersion,
            expectedDependencyFingerprint = dependencyFingerprint,
            sourceSha256 = admissionSha,
            sourceOrientation = context.orientationOf(snapshot),
            mode = OcrCheckpointMode.CLOSE,
            description = "t924 ocr preflight checkpoint",
        )
    }

    private suspend fun checkpointReuse(
        artifact: ChapterArtifactEngine,
        pageKey: String,
    ): CheckpointReuse {
        val manifest = store.artifactManifest
            ?: return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.NO_POINTER)
        val pointer = manifest.ocrCheckpoints[pageKey]
            ?: return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.NO_POINTER)
        val read = store.withArtifactEngineLocked { engine ->
            engine.readOcrCheckpoint(pointer)
        }
        if (read !is ChapterArtifactEngine.OcrCheckpointRead.Usable) {
            return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.SIDE_CAR_UNREADABLE)
        }
        val admissionSha = context.admissionSourceSha(pageKey)
        if (admissionSha == null || read.checkpoint.sourceIdentity.sha256 != admissionSha) {
            return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.SHA_MISMATCH)
        }
        return CheckpointReuse.Reusable(read.checkpoint.ocrContentFingerprint)
    }

    private suspend fun readCheckpointFingerprint(
        artifact: ChapterArtifactEngine,
        pageKey: String,
    ): String? {
        val manifest = store.artifactManifest ?: return null
        val pointer = manifest.ocrCheckpoints[pageKey] ?: return null
        val read = store.withArtifactEngineLocked { engine ->
            engine.readOcrCheckpoint(pointer)
        }
        return (read as? ChapterArtifactEngine.OcrCheckpointRead.Usable)?.checkpoint?.ocrContentFingerprint
    }

    private suspend fun frozenProfileReuse(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        expectedPageCount: Int,
    ): FrozenProfileReuse? {
        val manifest = store.artifactManifest ?: return null
        val pointer = manifest.profile ?: return null
        if (!pointer.isWellFormed()) return null
        // The FP-04 corpus identity must come from checkpoints whose source
        // identity STILL matches the current source ( resume: identities
        // are revalidated against current files). A changed/missing page
        // makes the frozen profile NOT reusable — the normal path re-OCRs it
        // and downstream corpus validation rejects the stale profile.
        val corpusPairs = mutableListOf<Pair<String, String>>()
        for ((pageKey, _) in orderedPages) {
            val reusable = checkpointReuse(artifact, pageKey)
            val fingerprint = (reusable as? CheckpointReuse.Reusable)?.ocrContentFingerprint ?: return null
            corpusPairs += pageKey to fingerprint
        }
        val naturalOrderProven =
            orderedPages.map { it.second }.toSet() == (0 until expectedPageCount).toSet()
        val corpusFingerprint = StageFingerprints.ocrCorpusFingerprint(
            pages = corpusPairs,
            expectedPageCount = expectedPageCount,
            expectedPageCountTrusted = true,
            naturalOrderProven = naturalOrderProven,
        )
        val inputFingerprint = profileInputFingerprintOf(corpusFingerprint)
        return when (
            val read = ProfileFreezePublication.readReusableFrozenProfile(
                store = store,
                manifest = manifest,
                expectedInputFingerprint = inputFingerprint,
            )
        ) {
            is ProfileFreezePublication.FrozenProfileRead.Reusable ->
                FrozenProfileReuse(pointer, corpusFingerprint)
            is ProfileFreezePublication.FrozenProfileRead.NotReusable -> null
        }
    }

    private suspend fun recordPageFailure(failure: PreflightStageFailure) =
        context.failureRecorder(failure)

    private fun profileInputFingerprintOf(corpusFingerprint: String): String =
        context.profileInputFingerprintOf(corpusFingerprint)

    private fun runConfigFingerprint(config: RunConfigSnapshot): String =
        ChapterProfileBatchCoordinator.runConfigFingerprint(config)

    private fun orderedSourceDigest(pairs: List<Pair<String, String>>): String =
        ChapterProfileBatchCoordinator.orderedSourceDigest(pairs)

    private fun newRunId(sourceDigest: String, frozenFingerprint: String): String =
        ChapterProfileBatchCoordinator.newRunId(sourceDigest, frozenFingerprint)

    suspend fun runPhase(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): BatchPass1Outcome {
        val seriesKey = context.seriesKey
        if (orderedPages.isEmpty()) {
            return BatchPass1Outcome(needsTranslation = emptyList())
        }
        currentCoroutineContext().ensureActive()
        // The coordinator retains this compatibility token while each actual
        // engine operation is routed through the facade's locked seams below.
        val artifact = store.withArtifactEngineLocked { it }
        if (artifact == null || store.artifactManifest == null) {
            // The preflight writes origin-neutral checkpoints; without artifact
            // authority the flagged path cannot do its one job. Fail fast
            // WITHOUT burning any OCR work (the legacy path remains available).
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 preflight refused: chapter artifact authority not established"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.FAILED,
                anchorPageKey = orderedPages.first().first,
                reason = "T924 OCR preflight requires chapter artifact authority",
            )
        }

        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val priorRecord = (existingActiveRecord(artifact) as? ChapterArtifactEngine.RunRecordRead.Usable)?.record
        //  settings apply next run — a resume continues the recorded run
        // only while the frozen configuration fingerprint still matches; a
        // mismatch starts a NEW run id under the current configuration.
        val runId = priorRecord
            ?.takeIf { it.frozenRunConfigFingerprint == frozenFingerprint }
            ?.runId
            ?: newRunId(sourceDigest, frozenFingerprint)

        // ----  resume gates: a record already past TRANSLATE never ----
        // ---- steps the durable state BACKWARD to RUN_SNAPSHOT.         ----
        resumeFinalizeOrComplete(
            artifact = artifact,
            priorRecord = priorRecord,
            frozenFingerprint = frozenFingerprint,
            sourceDigest = sourceDigest,
            orderedPages = orderedPages,
        )?.let { resumed -> return resumed }

        val total = orderedPages.size
        var reusedPages = 0
        var checkpointedPages = 0
        val corpusFingerprints = mutableListOf<Pair<String, String>>()

        //  R2a.5: per-run typed adoption-failure tally. Emitted as bounded
        // `ocrAdopt*` counters ONLY when nonzero, so steady-state record
        // counter maps stay byte-identical and the 32-key phaseCounters bound
        // is never pressured on healthy runs.
        val adoptionFailures = mutableMapOf<CheckpointAdoptionFailure, Int>()

        fun recordAdoptionFailure(pageKey: String, failure: CheckpointAdoptionFailure, detail: String?) {
            adoptionFailures.merge(failure, 1, Int::plus)
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 preflight checkpoint adoption failed pageHash=${ShortHash.hash(pageKey)} " +
                    "reason=${failure.name}" +
                    (detail?.let { " detail=$it" } ?: "") +
                    " — re-OCRing"
            }
        }

        fun counters(): Map<String, Int> = mapOf(
            ChapterProfileBatchCoordinator.COUNTER_TOTAL to total,
            ChapterProfileBatchCoordinator.COUNTER_DONE to (reusedPages + checkpointedPages),
            ChapterProfileBatchCoordinator.COUNTER_REUSED to reusedPages,
            // Kept for pre-field record compatibility; the authoritative
            // freeze is frozenConfig.flagProfilePipeline (participates in the
            // run-config fingerprint; counters never do, per FP-01). The
            //  A/B flag completed its lifecycle — the profile pipeline
            // is the only pipeline — so the frozen state is always ON.
            ChapterProfileBatchCoordinator.COUNTER_FLAG to 1,
        ) + buildMap {
            // Appended AFTER the fixed keys so the publishRecord over-bound
            // trim (takeLast) drops these first, never the phase-critical
            // `ocrPages*` keys.
            if (adoptionFailures.isNotEmpty()) {
                put(ChapterProfileBatchCoordinator.COUNTER_ADOPT_FAILED, adoptionFailures.values.sum())
                adoptionFailures.forEach { (failure, count) -> put(failure.counterKey, count) }
            }
        }

        // ---- OCR_PLAN skip rule (contracts-state-transactions :114): ----
        // when a compatible frozen profile already exists (its sidecar reads
        // back valid, the pointer identities match, and the current run's
        // FP-04 input fingerprint equals the pointer's), the plan records
        // skip-to-phase PROFILE_FROZEN reuse and the ENTIRE run through
        // analysis is skipped: zero OCR, zero provider calls (the
        // fast-feedback core). The probe is LOCAL reads only (durable
        // checkpoints + profile sidecar), never decode/native work.
        // Wave A: AI-ONLY — the standard lane produces no frozen profile, so
        // the probe is fenced off (it would short-circuit into the envelope
        // phase, which requires one).
        val reusableProfile = if (standardLane) {
            null
        } else {
            frozenProfileReuse(artifact, orderedPages, total)
        }

        //  run start — RUN_SNAPSHOT record with the frozen configuration,
        // the ordered source digest, and the frozen flag state.
        publishRecord(
            artifact,
            record(runId, ChapterRunState.RUN_SNAPSHOT, frozenFingerprint, sourceDigest, counters()),
        )
        if (reusableProfile != null) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT t924 profile reuse: compatible frozen profile, " +
                    "skipping OCR+analysis (skip-to-phase PROFILE_FROZEN)"
            }
            publishRecord(
                artifact,
                record(
                    runId,
                    ChapterRunState.PROFILE_FROZEN,
                    frozenFingerprint,
                    sourceDigest,
                    counters() + mapOf(
                        ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                        ChapterProfileBatchCoordinator.COUNTER_PROFILE_REUSED to 1,
                    ),
                    ocrCorpusFingerprint = reusableProfile.corpusFingerprint,
                    profilePointer = reusableProfile.pointer,
                ),
            )
            // A reusable frozen profile lets this run continue directly to
            // envelope planning without repeating OCR or provider analysis.
            return runEnvelopePlanAndTranslate(
                artifact = artifact,
                runId = runId,
                orderedPages = orderedPages,
                corpusFingerprint = reusableProfile.corpusFingerprint,
                baseCounters = counters() + mapOf(ChapterProfileBatchCoordinator.COUNTER_PROFILE_REUSED to 1),
            )
        }

        //  the OCR plan is recomputed in-memory (pure function of the
        // ordered pages + store state); only the phase transition persists.
        publishRecord(
            artifact,
            record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
        )

        for (page in orderedPages) {
            val (pageKey, pageIndex) = page
            currentCoroutineContext().ensureActive()
            // Reader-priority yield between pages: a suspension point (never a
            // sleep) that lets interactive native demand win the lane.
            yield()

            when (val reusable = checkpointReuse(artifact, pageKey)) {
                is CheckpointReuse.Reusable -> {
                    //  resume rule: the page's origin-neutral checkpoint matches
                    // the current source identity — no re-OCR, no lease, no decode.
                    //
                    //  zero-legacy: a reused checkpoint must also BACK the
                    // live store page. A reopened store (real restart, or the
                    // memory-only artifact-authority fixture) holds only a
                    // placeholder page record — its ocrStatus/blocks live in the
                    // durable checkpoint sidecar. Without adoption the translate
                    // tail's dependency gate reads WAIT_FOR_DEPENDENCY /
                    // DEPENDENCY_INCOMPLETE against the placeholder and silently
                    // skips the page's paid translation — the run then "completes"
                    // without paying ('s resumed-death cycle). Adopt the
                    // checkpointed OCR snapshot into the live store — the SAME
                    // hydration idiom the envelope lane's resume uses — and only
                    // then count the page as reused. If the adoption cannot back
                    // the page (unreadable sidecar, racing owner), fall through to
                    // a fresh OCR run: never plan against fabricated content.
                    val before = store.snapshot(pageKey)
                    val hydrated = before.page != null &&
                        before.page.ocrStatus == StageStatus.READY &&
                        before.page.blocks.isNotEmpty()
                    val adoption = if (hydrated) {
                        CheckpointAdoption.Adopted(before)
                    } else {
                        adoptCheckpointSnapshot(artifact, pageKey, before)
                    }
                    if (adoption is CheckpointAdoption.Adopted) {
                        reusedPages++
                        corpusFingerprints += pageKey to reusable.ocrContentFingerprint
                        // Same live-progress marks a fresh OCR page emits: without
                        // them the tracker's snapshot stays frozen at the pre-resume
                        // counts for the entire revalidation and the drawer looks
                        // unresponsive (2026-09-15/16 field report).
                        listener.ocrStarted(pageKey)
                        listener.ocrPublished(pageKey)
                        stampAdoptedRenderTerminal(pageKey)
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT t924 preflight reused checkpoint pageHash=${ShortHash.hash(pageKey)}"
                        }
                        // M3: Batched advisory progress records: publish on first page, every 5 pages, or last page
                        val shouldPublishProgress = (pageIndex == 0) || ((pageIndex + 1) % 5 == 0) || ((pageIndex + 1) == total)
                        if (shouldPublishProgress) {
                            publishRecord(
                                artifact,
                                record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
                            )
                        }
                        continue
                    }
                    //  R2a.5: the re-OCR cliff is now TYPED (counter + WARN
                    // reason) instead of one untyped warning. The sealed
                    // hierarchy has exactly two cases, so the early-continue
                    // above leaves only Failed — spelled as a `when` because
                    // the compiler does not narrow a sealed type from a
                    // negated `is` after an if-statement.
                    when (adoption) {
                        is CheckpointAdoption.Failed ->
                            recordAdoptionFailure(pageKey, adoption.failure, adoption.detail)
                        is CheckpointAdoption.Adopted -> Unit
                    }
                }
                is CheckpointReuse.Unavailable -> {
                    // NO_POINTER is the quiet never-checkpointed answer (a normal
                    // fresh OCR below); every other reason means a checkpoint
                    // EXISTS but cannot back this run — the typed cliff.
                    if (reusable.failure != CheckpointAdoptionFailure.NO_POINTER) {
                        recordAdoptionFailure(pageKey, reusable.failure, null)
                    }
                }
            }

            listener.ocrStarted(pageKey)
            val pageStartedAt = System.currentTimeMillis()
            var ref: OcrReadyPageRef? = null
            // Set when THIS page's attempt ended unresolved (REJECTED checkpoint
            // or worker exception): the ledger record is written in `finally`,
            // AFTER the B0 candidate teardown — cancelCandidate strips
            // candidate-owned stage records, so the record must outlive it.
            var pendingFailure: PreflightStageFailure? = null
            try {
                ref = try {
                    // Per-page watchdog: lease acquisition, bitmap-budget
                    // permits, and SAF decode sit BEFORE the native lane's own
                    // 120s timeout, and a wedge in any of them used to stall
                    // the pass silently forever (2026-09-15 incident). Bound
                    // the whole page; the failure ledger path below makes the
                    // timeout visible and the pass continues.
                    withTimeout(ChapterProfileBatchCoordinator.PREFLIGHT_PAGE_TIMEOUT_MS) {
                        nativeWorker.runOcrStage(pageKey, pageIndex)
                    }
                } catch (e: TimeoutCancellationException) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 preflight page watchdog timeout pageHash=${ShortHash.hash(pageKey)} " +
                            "afterMs=${System.currentTimeMillis() - pageStartedAt}"
                    }
                    pendingFailure = PreflightStageFailure(
                        pageKey = pageKey,
                        kind = PreflightFailureKind.OCR_WORKER_FAILED,
                        reason = "preflight page watchdog timeout after ${ChapterProfileBatchCoordinator.PREFLIGHT_PAGE_TIMEOUT_MS}ms",
                    )
                    null
                }
                if (ref == null) {
                    // Lease-denied or otherwise unresolved: no checkpoint was
                    // published. The final diagnostic counts every
                    // uncheckpointed page as a gap.
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t924 preflight skipped pageHash=${ShortHash.hash(pageKey)} (denied or unresolved)"
                    }
                } else {
                    listener.ocrPublished(pageKey)
                    val outcome = checkpointPage(artifact, pageKey, ref)
                    when (outcome) {
                        is CheckpointOcrResult.Committed -> {
                            checkpointedPages++
                            readCheckpointFingerprint(artifact, pageKey)?.let { fingerprint ->
                                corpusFingerprints += pageKey to fingerprint
                            }
                        }
                        is CheckpointOcrResult.Rejected -> {
                            //  terminal: any unresolved checkpoint failure stops
                            // the phase before any later (paid) stage. The lease is
                            // released in `finally`; the shell teardown reconciles
                            // the candidate per the legacy durability rules.
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 preflight checkpoint rejected pageHash=${ShortHash.hash(pageKey)} " +
                                    "reason=${outcome.reason}"
                            }
                            // R2: record the failure for the `finally` writer,
                            // which persists it AFTER the B0 teardown (a
                            // pre-teardown write would be stripped by
                            // cancelCandidate's candidate-owned record sweep),
                            // still strictly after the checkpoint attempt.
                            pendingFailure = PreflightStageFailure(
                                pageKey = pageKey,
                                kind = PreflightFailureKind.CHECKPOINT_REJECTED,
                                reason = outcome.reason,
                            )
                            return BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.FAILED,
                                anchorPageKey = pageKey,
                                completedPageKeys = corpusFingerprints.mapTo(mutableSetOf()) { it.first },
                                reason = "T924 OCR preflight checkpoint rejected: ${outcome.reason}",
                            )
                        }
                    }
                    // M3: Batched advisory progress records: publish on first page, every 5 pages, or last page
                    val shouldPublishProgress = (pageIndex == 0) || ((pageIndex + 1) % 5 == 0) || ((pageIndex + 1) == total)
                    if (shouldPublishProgress) {
                        publishRecord(
                            artifact,
                            record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 preflight ocr failed pageHash=${ShortHash.hash(pageKey)} error=${e::class.java.simpleName}"
                }
                // R2: same durable ledger as a REJECTED checkpoint, carrying the
                // exception identity as the typed reason.
                pendingFailure = PreflightStageFailure(
                    pageKey = pageKey,
                    kind = PreflightFailureKind.OCR_WORKER_FAILED,
                    reason = "${e::class.java.simpleName}: ${e.message ?: "no message"}",
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.FAILED,
                    anchorPageKey = pageKey,
                    completedPageKeys = corpusFingerprints.mapTo(mutableSetOf()) { it.first },
                    reason = "T924 OCR preflight failed: ${e.message ?: e::class.java.simpleName}",
                )
            } finally {
                // The decoded handoff NEVER crosses a page boundary and the
                // lease is released strictly after the checkpoint attempt
                // ( one-decoded-page invariant).
                ref?.let(nativeWorker::releaseNativeHandoff)
                if (pendingFailure != null) {
                    // B0 teardown idiom: the unresolved page's candidate-held OCR
                    // never committed (no checkpoint), so cancel it — the page
                    // re-OCRs next attempt. The durable failure record is
                    // written AFTER the cancellation: `cancelCandidate` strips
                    // candidate-owned stage records, so the ledger record must
                    // be installed once no candidate owns it (the legacy
                    // persistUnexpectedBatchStageFailure order — the shell
                    // persists after the coordinator's teardown).
                    store.cancelPageStageWork(pageKey, PageWriteOrigin.BATCH)
                    recordPageFailure(pendingFailure)
                }
                releaseBatchLease(pageKey)
                listener.ocrFinished(pageKey)
            }
        }

        // ---- OCR_PREFLIGHT complete durably; continue into the analysis ----
        // ---- phase when the corpus is complete.        ----
        store.flush()
        val corpusGaps = total - corpusFingerprints.size
        val corpusFingerprint = if (corpusGaps == 0 && corpusFingerprints.isNotEmpty()) {
            val naturalOrderProven = orderedPages.map { it.second }.toSet() == (0 until total).toSet()
            StageFingerprints.ocrCorpusFingerprint(
                pages = corpusFingerprints,
                expectedPageCount = total,
                expectedPageCountTrusted = true,
                naturalOrderProven = naturalOrderProven,
            )
        } else {
            null
        }
        val finalCounters = counters() + mapOf(
            ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
            ChapterProfileBatchCoordinator.COUNTER_GAPS to corpusGaps,
        )
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.OCR_PREFLIGHT,
                frozenFingerprint,
                sourceDigest,
                finalCounters,
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )
        if (corpusFingerprint == null) {
            // Incomplete corpus (reused/unresolved gaps): analysis needs the
            // whole OCR corpus — stop exactly like the S3 shell did.
            logcat(LogPriority.INFO) {
                "TachiyomiAT t924 preflight stopped-not-finished: ocr=$total reused=$reusedPages gaps=$corpusGaps " +
                    "analysis deferred until the corpus is complete"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpusFingerprints.mapTo(mutableSetOf()) { it.first },
                reason = ChapterProfileBatchCoordinator.STOP_REASON,
            )
        }

        // The standard lane translates pages directly; the AI lane continues
        // through analysis. Both use the shared FINALIZE/COMPLETE path.
        if (standardLane) {
            return runStandardTranslateAndFinalize(
                artifact = artifact,
                runId = runId,
                orderedPages = orderedPages,
                corpusFingerprint = corpusFingerprint,
                baseCounters = finalCounters,
            )
        }

        // Reuse a registered series profile when it passes drift checks; this
        // skips chapter analysis and profile reconciliation.
        if (seriesKey != null) {
            val carried = SeriesProfileRegistry.get(seriesKey)
            if (carried != null && SeriesProfileRegistry.isDriftSafe(carried, frozenConfig)) {
                val adopted = SeriesProfileRegistry.adoptForChapter(
                    carried = carried,
                    runId = runId,
                    profileInputFingerprint = profileInputFingerprintOf(corpusFingerprint),
                    nowEpochMs = nowEpochMs(),
                )
                val manifestForPublish = store.artifactManifest
                if (manifestForPublish != null) {
                    val publication = ProfileFreezePublication.publish(
                        store = store,
                        manifest = manifestForPublish,
                        profile = adopted,
                        nowEpochMs = nowEpochMs(),
                    )
                    if (publication is ChapterArtifactEngine.TransactionOutcome.Committed) {
                        store.artifactManifest = publication.manifest
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT M5 series profile carry-over adopted: version=${adopted.version} " +
                                "entities=${adopted.entities.size} terms=${adopted.terms.size}"
                        }
                        publishRecord(
                            artifact,
                            record(
                                runId,
                                ChapterRunState.PROFILE_FROZEN,
                                frozenFingerprint,
                                sourceDigest,
                                finalCounters + mapOf(
                                    ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN to 1,
                                    ChapterProfileBatchCoordinator.COUNTER_SERIES_PROFILE_CARRIED_OVER to 1,
                                    ChapterProfileBatchCoordinator.COUNTER_STOP to 1,
                                ),
                                ocrCorpusFingerprint = corpusFingerprint,
                                profilePointer = publication.manifest.profile,
                            ),
                        )
                        return runEnvelopePlanAndTranslate(
                            artifact = artifact,
                            runId = runId,
                            orderedPages = orderedPages,
                            corpusFingerprint = corpusFingerprint,
                            baseCounters = finalCounters + mapOf(ChapterProfileBatchCoordinator.COUNTER_SERIES_PROFILE_CARRIED_OVER to 1),
                        )
                    }
                }
            }
        }

        // Build the analysis corpus, reconcile the profile, and freeze it
        // before continuing into envelope planning and translation.
        return runAnalysisPhase(
            artifact = artifact,
            runId = runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = finalCounters,
        )
    }
}
