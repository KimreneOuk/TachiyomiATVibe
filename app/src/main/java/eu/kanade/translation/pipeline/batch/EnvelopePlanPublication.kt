package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.SidecarRead
import eu.kanade.translation.artifact.StageFingerprints

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
     */
    fun publish(
        artifact: ChapterArtifactStore,
        manifest: ChapterArtifactManifest,
        plan: EnvelopePlan,
        nowEpochMs: Long,
    ): ChapterArtifactStore.TransactionOutcome {
        plan.validationError()?.let { reason ->
            return ChapterArtifactStore.TransactionOutcome.Rejected("envelope plan invalid: $reason")
        }
        val recomputed = recomputedContentFingerprint(plan)
        if (recomputed != plan.planFingerprint) {
            return ChapterArtifactStore.TransactionOutcome.Rejected(
                "envelope plan fingerprint mismatch: field=${plan.planFingerprint} recomputed=$recomputed",
            )
        }
        val fileName = artifact.envelopePlanSidecarName(plan.planFingerprint)
        return artifact.publishSidecarPointers(
            manifest = manifest,
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
    fun readValidatedPlan(
        artifact: ChapterArtifactStore,
        manifest: ChapterArtifactManifest,
    ): EnvelopePlanRead {
        val pointer = manifest.envelopePlan ?: return EnvelopePlanRead.NotUsable
        if (!pointer.isWellFormed()) return EnvelopePlanRead.NotUsable
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
            else -> return EnvelopePlanRead.NotUsable
        }
        if (pointer.contentFingerprint != plan.planFingerprint) {
            return EnvelopePlanRead.NotUsable
        }
        if (recomputedContentFingerprint(plan) != plan.planFingerprint) {
            return EnvelopePlanRead.NotUsable
        }
        return EnvelopePlanRead.Usable(plan)
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
