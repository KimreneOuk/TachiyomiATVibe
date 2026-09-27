package eu.kanade.translation.pipeline.batch.envelope

import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.EnvelopePlan
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Publishes an immutable envelope plan and advances the manifest pointer.
 *
 * ONE atomic publication: the immutable [EnvelopePlan] sidecar is published
 * into the content-addressed `envelopes/` directory FIRST, then the manifest
 * `envelopePlan` pointer ([SidecarPointer]) moves in ONE
 * `publishSidecarPointers` transaction, following the same pattern as
 * [ProfileFreezePublication]. These guarantees hold by construction:
 *
 *  - the plan sidecar is content-addressed by `planFingerprint`; publishing
 *    identical bytes again uses the same name and is idempotent;
 *  - a replacement plan gets a new content-addressed file and pointer; the
 *    prior file remains until retention finds it unreachable;
 *  - any rejection leaves the PRIOR manifest authoritative — never a
 *    partially published plan;
 *  - resume treats a pointer without a valid sidecar as absent, so the caller
 *    can re-plan and publish a valid sidecar.
 *
 * Before any byte is written the plan fingerprint is recomputed
 * from the DTO (canonical re-encode with the operational fields zeroed) and
 * verified equal to the DTO's own `planFingerprint` — a mismatched plan is
 * rejected, never published.
 */
internal object EnvelopePlanPublication {

    /**
     * Publishes the envelope plan and its manifest pointer in one transaction.
     * The caller supplies the freshly planned [EnvelopePlan]; publication
     * verifies its fingerprint before writing.
     *
     * Checkpoint adoption or background validation can update the manifest
     * after the caller reads it. On a stale-manifest rejection only
     * ([ChapterArtifactEngine.isStaleManifestRejection]), this method reads
     * the durable manifest once and retries the same pointer update against
     * that fresh snapshot. This preserves concurrent fields. The retry is
     * one-shot; if it does not commit or the manifest is missing, the original
     * rejection is returned. Other rejection types are not retried.
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

        /** Absent pointer, unreadable/invalid/future sidecar, or ANY identity mismatch ( treated as absent). */
        data object NotUsable : EnvelopePlanRead
    }

    /**
     * 30 read path: an envelope plan is usable only when ALL of
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
     * 10: the content fingerprint = SHA-256 over the canonical
     * re-encoded JSON of the DTO with the operational fields excluded
     * (`createdAtEpochMs` zeroed; `planFingerprint` blanked — a value cannot
     * contain its own hash). Byte-identical to the pure planner's hash
     * ([eu.kanade.translation.engines.translator.contextual.GlobalEnvelopePlanner]).
     */
    private fun recomputedContentFingerprint(plan: EnvelopePlan): String {
        val hashingView = plan.copy(planFingerprint = "", createdAtEpochMs = 0L)
        val canonical = ArtifactDocumentJson.encodeToString(EnvelopePlan.serializer(), hashingView)
        return StageFingerprints.envelopePlanContentFingerprint(canonical)
    }
}
