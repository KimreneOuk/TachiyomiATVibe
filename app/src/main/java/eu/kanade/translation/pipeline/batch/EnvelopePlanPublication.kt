package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.SidecarRead
import eu.kanade.translation.artifact.StageFingerprints
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * T924 Stage-6 slice A — envelope plan publication (T924-ST-11, SC-20).
 *
 * ONE atomic publication: the immutable [EnvelopePlan] sidecar is published
 * into the content-addressed `envelopes/` directory FIRST, then the manifest
 * `envelopePlan` pointer ([SidecarPointer]) moves in ONE
 * `publishSidecarPointers` transaction (the ProfileFreezePublication
 * discipline). The ST-11 rules hold by construction:
 *
 *  - the plan sidecar is content-addressed by its T924-SC-10
 *    `planFingerprint`; a byte-identical re-publication (ST-11 crash case
 *    "before pointer") maps to the SAME name and is idempotent;
 *  - a SUPERSEDING plan (deterministic suffix re-plan, TX-21.4) is a NEW
 *    content-addressed file + a NEW pointer; the prior file stays untouched
 *    until retention reachability sweeps it (ST-11 GC);
 *  - any rejection leaves the PRIOR manifest authoritative — never a
 *    partially published plan;
 *  - resume treats a pointer without a valid sidecar as ABSENT (T924-ST-30;
 *    the caller re-plans and heals).
 *
 * Before any byte is written the SC-10 content fingerprint is RECOMPUTED
 * from the DTO (canonical re-encode with the operational fields zeroed) and
 * verified equal to the DTO's own `planFingerprint` — a mismatched plan is
 * rejected, never published.
 */
internal object EnvelopePlanPublication {

    /**
     * Publishes the envelope plan + its manifest pointer in ONE transaction.
     * The caller supplies the freshly re-planned [EnvelopePlan]; the
     * publication verifies the SC-10 fingerprint before writing.
     *
     * T934 LI-x: the resume path rebuilds dispatch work (adopting durable
     * checkpoints page by page, each adoption republishing the manifest)
     * BEFORE publishing the plan, and the caller's manifest snapshot can also
     * be invalidated by the >8-page open path's background health verify — a
     * guaranteed `stale manifest snapshot` CAS rejection that aborted the
     * whole batch with PERSISTENCE_REJECTED. On a stale-manifest rejection
     * ONLY ([ChapterArtifactEngine.isStaleManifestRejection]), the publication
     * re-reads the durable manifest ONCE and re-runs the SAME pointer move
     * against THAT fresh manifest (the pointer move is a pure
     * `envelopePlan`-pointer set, so every fresh durable field is carried
     * forward by construction; the plan sidecar itself is content-addressed
     * and idempotent). The retry is one-shot with the store's
     * `retryOnStaleManifest` semantics replicated locally (the mutation
     * lambda lives outside the store): a retry that does not commit — or a
     * durable manifest that vanished — returns the ORIGINAL Rejected
     * unchanged, and every non-stale rejection keeps failing exactly as
     * before (T924-SC-20/22).
     */
    suspend fun publish(
        store: ChapterTranslationStore,
        manifest: ChapterArtifactManifest,
        plan: EnvelopePlan,
        nowEpochMs: Long,
    ): ChapterArtifactEngine.TransactionOutcome {
        plan.validationError()?.let { reason ->
            return ChapterArtifactEngine.TransactionOutcome.Rejected("envelope plan invalid: $reason")
        }
        val recomputed = recomputedContentFingerprint(plan)
        if (recomputed != plan.planFingerprint) {
            return ChapterArtifactEngine.TransactionOutcome.Rejected(
                "envelope plan fingerprint mismatch: field=${plan.planFingerprint} recomputed=$recomputed",
            )
        }
        return store.withArtifactEngineLocked { artifact ->
            val fileName = artifact.envelopePlanSidecarName(plan.planFingerprint)
            fun publishPointers(on: ChapterArtifactManifest): ChapterArtifactEngine.TransactionOutcome =
                artifact.publishSidecarPointers(
                    manifest = on,
                    sidecars = listOf(
                        artifact.jsonSidecarPublication(
                            fileName = fileName,
                            contentFingerprint = plan.planFingerprint,
                            document = plan,
                            serializer = EnvelopePlan.serializer(),
                        ),
                    ),
                    updatePointers = { current ->
                        current.copy(
                            envelopePlan = SidecarPointer(
                                fileName = fileName,
                                schemaVersion = EnvelopePlan.SCHEMA_VERSION,
                                contentFingerprint = plan.planFingerprint,
                            ),
                        )
                    },
                    nowEpochMs = nowEpochMs,
                )
            val firstAttempt = publishPointers(manifest)
            if (!artifact.isStaleManifestRejection(firstAttempt)) return@withArtifactEngineLocked firstAttempt
            val fresh = artifact.readManifest() ?: return@withArtifactEngineLocked firstAttempt
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact stale manifest retried once: seam=envelope-plan " +
                    "chapter=${fresh.chapterKey} " +
                    "staleUpdatedAt=${manifest.updatedAtEpochMs} " +
                    "freshUpdatedAt=${fresh.updatedAtEpochMs}"
            }
            val retry = publishPointers(fresh)
            if (retry is ChapterArtifactEngine.TransactionOutcome.Committed) retry else firstAttempt
        } ?: ChapterArtifactEngine.TransactionOutcome.Rejected("artifact engine unavailable")
    }

    /** Why a manifest's envelope-plan pointer is (not) usable at a resume point. */
    sealed interface EnvelopePlanRead {
        /** Sidecar loads, validates, matches its pointer, and its SC-10 hash recomputes equal. */
        data class Usable(val plan: EnvelopePlan) : EnvelopePlanRead

        /** Absent pointer, unreadable/invalid/future sidecar, or ANY identity mismatch (T924-ST-30: treated as absent). */
        data object NotUsable : EnvelopePlanRead
    }

    /**
     * T924-ST-30 read path: an envelope plan is usable only when ALL of
     * pointer well-formedness, sidecar semantic validity at a supported
     * schema version, pointer/content identity, and the recomputed SC-10
     * hash hold. Any failure is [EnvelopePlanRead.NotUsable] — the caller
     * re-plans (a pure recomputation) and heals.
     */
    suspend fun readValidatedPlan(
        store: ChapterTranslationStore,
        manifest: ChapterArtifactManifest,
    ): EnvelopePlanRead {
        return store.withArtifactEngineLocked { artifact ->
            val pointer = manifest.envelopePlan ?: return@withArtifactEngineLocked EnvelopePlanRead.NotUsable
            if (!pointer.isWellFormed()) return@withArtifactEngineLocked EnvelopePlanRead.NotUsable
            val plan = when (
                val read = artifact.readSidecarDocument(
                    pointer = pointer,
                    serializer = EnvelopePlan.serializer(),
                    currentSchemaVersion = EnvelopePlan.SCHEMA_VERSION,
                    expectedKind = EnvelopePlan.KIND,
                    schemaVersionOf = { it.schemaVersion },
                    kindOf = { it.kind },
                    isValid = { it.isSemanticallyValid },
                )
            ) {
                is SidecarRead.Usable -> read.document
                else -> return@withArtifactEngineLocked EnvelopePlanRead.NotUsable
            }
            if (pointer.contentFingerprint != plan.planFingerprint) {
                return@withArtifactEngineLocked EnvelopePlanRead.NotUsable
            }
            if (recomputedContentFingerprint(plan) != plan.planFingerprint) {
                return@withArtifactEngineLocked EnvelopePlanRead.NotUsable
            }
            EnvelopePlanRead.Usable(plan)
        } ?: EnvelopePlanRead.NotUsable
    }

    /**
     * T924-SC-10: the content fingerprint = SHA-256 over the canonical
     * re-encoded JSON of the DTO with the operational fields excluded
     * (`createdAtEpochMs` zeroed; `planFingerprint` blanked — a value cannot
     * contain its own hash). Byte-identical to the pure planner's hash
     * ([eu.kanade.translation.translator.contextual.GlobalEnvelopePlanner]).
     */
    private fun recomputedContentFingerprint(plan: EnvelopePlan): String {
        val hashingView = plan.copy(planFingerprint = "", createdAtEpochMs = 0L)
        val canonical = ArtifactDocumentJson.encodeToString(EnvelopePlan.serializer(), hashingView)
        return StageFingerprints.envelopePlanContentFingerprint(canonical)
    }
}
