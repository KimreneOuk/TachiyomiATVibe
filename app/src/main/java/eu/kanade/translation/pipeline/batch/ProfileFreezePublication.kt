package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.ProfilePointer
import eu.kanade.translation.artifact.SidecarRead
import eu.kanade.translation.artifact.StageFingerprints

/**
 * T924 Stage 5 slice B — profile freeze publication (T924-TX-22, ST-10).
 *
 * ONE atomic publication: the immutable [ChapterTranslationProfile] sidecar
 * is published into the content-addressed `profiles/` directory FIRST, then
 * the manifest `profile` pointer ([ProfilePointer]) moves in ONE
 * `publishSidecarPointers` transaction. The T924-TX-22 rules hold by
 * construction:
 *
 *  - the sidecar bytes are immutable; a SUPERSEDING profile is a NEW
 *    content-addressed file + a NEW pointer (the prior file stays untouched
 *    on disk until retention reachability sweeps it);
 *  - a byte-identical re-publication (e.g. the ST-10 crash case (b) orphan
 *    heal) maps to the SAME content-addressed name and is idempotent;
 *  - any rejection (validation, fingerprint mismatch, version non-monotonic,
 *    stale manifest, sidecar write fault, manifest publication fault) leaves
 *    the PRIOR manifest authoritative — never a partially frozen state;
 *  - resume treats a pointer without a valid sidecar as UNFROZEN
 *    ([readReusableFrozenProfile] — wave-4 pattern: an unreadable/invalid
 *    target is treated as absent, never partially trusted, T924-ST-30).
 *
 * Before any byte is written the FP-05 content fingerprint is RECOMPUTED from
 * the DTO ([StageFingerprints.profileContentFingerprint]) and verified equal
 * to the DTO's own `contentFingerprint` field — a mismatched profile is
 * rejected, never published.
 */
internal object ProfileFreezePublication {

    /**
     * Publishes the frozen profile + pointer in ONE transaction.
     *
     * Version monotonicity (schemas contract §1.4: `version` is monotonic per
     * chapter, operational ordering only) is enforced against the manifest's
     * current pointer: the profile must carry exactly
     * `(manifest.profile?.version ?: 0) + 1`.
     */
    suspend fun publish(
        store: ChapterTranslationStore,
        manifest: eu.kanade.translation.artifact.ChapterArtifactManifest,
        profile: ChapterTranslationProfile,
        nowEpochMs: Long,
    ): ChapterArtifactEngine.TransactionOutcome {
        profile.validationError()?.let { reason ->
            return ChapterArtifactEngine.TransactionOutcome.Rejected(
                "profile invalid: $reason",
            )
        }
        val recomputed = StageFingerprints.profileContentFingerprint(profile)
        if (recomputed != profile.contentFingerprint) {
            return ChapterArtifactEngine.TransactionOutcome.Rejected(
                "profile content fingerprint mismatch: field=${profile.contentFingerprint} " +
                    "recomputed=$recomputed",
            )
        }
        val expectedVersion = (manifest.profile?.version ?: 0) + 1
        if (profile.version != expectedVersion) {
            return ChapterArtifactEngine.TransactionOutcome.Rejected(
                "profile version not monotonic: expected $expectedVersion, got ${profile.version}",
            )
        }
        return store.withArtifactEngineLocked { artifact ->
            val fileName = artifact.profileSidecarName(profile.contentFingerprint)
            artifact.publishSidecarPointers(
                manifest = manifest,
                sidecars = listOf(
                    artifact.jsonSidecarPublication(
                        fileName = fileName,
                        contentFingerprint = profile.contentFingerprint,
                        document = profile,
                        serializer = ChapterTranslationProfile.serializer(),
                    ),
                ),
                updatePointers = { current ->
                    current.copy(
                        profile = ProfilePointer(
                            fileName = fileName,
                            schemaVersion = ChapterTranslationProfile.SCHEMA_VERSION,
                            contentFingerprint = profile.contentFingerprint,
                            version = profile.version,
                            profileInputFingerprint = profile.profileInputFingerprint,
                        ),
                    )
                },
                nowEpochMs = nowEpochMs,
            )
        } ?: ChapterArtifactEngine.TransactionOutcome.Rejected("artifact engine unavailable")
    }

    /** Why a manifest's frozen profile is (not) reusable at a resume point. */
    sealed interface FrozenProfileRead {

        /**
         * A frozen profile whose sidecar loads, validates, matches its
         * pointer (content fingerprint + version + input fingerprint), and
         * whose FP-05 hash recomputes equal — the ST-05/ST-10 reuse basis.
         */
        data class Reusable(val profile: ChapterTranslationProfile) : FrozenProfileRead

        /**
         * No frozen profile usable for reuse: absent pointer, unreadable /
         * invalid / future-version sidecar, or ANY identity mismatch
         * (T924-ST-30: treated as absent — never partially trusted, never
         * mutated here; the normal reconcile path re-freezes).
         */
        data object NotReusable : FrozenProfileRead
    }

    /**
     * ST-05/OCR_PLAN skip rule read path (:114): a frozen profile is
     * reusable only when ALL of
     *
     *  1. the manifest pointer is well-formed,
     *  2. the pointed sidecar loads as a semantically valid
     *     [ChapterTranslationProfile] at a supported schema version,
     *  3. pointer.contentFingerprint == profile.contentFingerprint and
     *     pointer.version == profile.version,
     *  4. profile.contentFingerprint == recomputed FP-05 over the loaded DTO,
     *  5. pointer.profileInputFingerprint == [expectedInputFingerprint]
     *     (the current run's FP-04 value).
     *
     * Any failure is [FrozenProfileRead.NotReusable] — the caller runs the
     * normal analysis path.
     */
    suspend fun readReusableFrozenProfile(
        store: ChapterTranslationStore,
        manifest: eu.kanade.translation.artifact.ChapterArtifactManifest,
        expectedInputFingerprint: String,
    ): FrozenProfileRead {
        return store.withArtifactEngineLocked { artifact ->
            val pointer = manifest.profile ?: return@withArtifactEngineLocked FrozenProfileRead.NotReusable
            if (!pointer.isWellFormed()) return@withArtifactEngineLocked FrozenProfileRead.NotReusable
            val profile = when (
                val read = artifact.readSidecarDocument(
                    pointer = pointer.toSidecarPointer(),
                    serializer = ChapterTranslationProfile.serializer(),
                    currentSchemaVersion = ChapterTranslationProfile.SCHEMA_VERSION,
                    expectedKind = ChapterTranslationProfile.KIND,
                    schemaVersionOf = { it.schemaVersion },
                    kindOf = { it.kind },
                    isValid = { it.isSemanticallyValid },
                )
            ) {
                is SidecarRead.Usable -> read.document
                else -> return@withArtifactEngineLocked FrozenProfileRead.NotReusable
            }
            if (pointer.contentFingerprint != profile.contentFingerprint ||
                pointer.version != profile.version ||
                pointer.profileInputFingerprint != expectedInputFingerprint
            ) {
                return@withArtifactEngineLocked FrozenProfileRead.NotReusable
            }
            if (StageFingerprints.profileContentFingerprint(profile) != profile.contentFingerprint) {
                return@withArtifactEngineLocked FrozenProfileRead.NotReusable
            }
            FrozenProfileRead.Reusable(profile)
        } ?: FrozenProfileRead.NotReusable
    }
}
