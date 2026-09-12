# T924 Stage 5 Slice B — Profile Reconcile + Freeze (ST-09 → ST-10)

Implementer report. Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base HEAD `f1cdde6`. No commits made (orchestrator commits). Targeted suite after the change: **578 tests, 0 failures** (baseline 548/0; +30 new tests, slice-A coordinator tests updated to the slice-B terminals).

## 1. Contract anchors (file:line)

Under `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/stage0/`:

- `contracts-state-transactions.md`
  - ST-09 PROFILE_RECONCILE — :133 (entry from complete chunks; deterministic pre-merge; resume re-runs reconcile, never "halfway frozen")
  - ST-10 PROFILE_FROZEN — :144 (ONE atomic publication; profile file immutable; supersede = new file + new pointer; crash (b) byte-identical re-publish allowed)
  - ST-05/OCR_PLAN skip rule — :114 ("if a compatible frozen profile already exists … the plan records skip-to-phase (PROFILE_FROZEN reuse or FINALIZE) — provider analysis is skipped")
  - T924-TX-22 profile freeze atomic publication — :386
  - ST-30 pointer target validated before the phase it unlocks is reused — :417
  - T924-TX-07 display preservation (inherited: freeze touches no page state) — :317
- `contracts-schemas-fingerprints.md`
  - §1.4 ChapterTranslationProfile + ProfileFact/ProfileScene/EvidenceRef field tables — :134
  - ProfilePointer row — :58; T924-SC-03 ProfilePointer shape — :278
  - T924-SC-10 canonical-JSON content hashing — :290; T924-SC-09 normalization — :289
  - T924-FP-04 ProfileInputFingerprint — :325; T924-FP-05 ProfileContentFingerprint + version-only-bump rule — :326
  - §7.1 decision brief (completed translations KEEP; contradictions become correctionCandidates, never silent mutation) — :358
- `implementation-sequence.md` — §S5 :187; wave-4 obligations :359 (slice-B-owed items: none of "shared gate wiring" / "lmstudio spelling" — both belong to the provider-package stage; wave-2 F1 remains owed to the first COMPLETE-publishing stage, untouched here).

## 2. Design per deliverable

### D1 — Pure reconciler (new `translator/contextual/ProfileReconciler.kt`)

`internal object ProfileReconciler` — `reconcile(chunks: List<AnalysisChunkResult>): ReconcileOutcome` (:67/:101). No I/O, no provider calls, no bitmaps; the caller assembles the operational DTO envelope around the returned `ReconciledProfileContent`.

- **Validation gate (INV-04/22)**: every chunk record is validated before use — `validationError() != null`, non-VALID status, or a non-contiguous ordinal sequence REJECTS the reconcile (typed `Rejected`), never partially consumed.
- **MISSING_ONLY = pending, never canon** (wave-4 F-W4-3): `coverage == MISSING_ONLY` chunks contribute no records, scenes, evidence, or provenance; only `pendingChunkCount`. The ratified F-W4-3 extension (summary-only COMPLETE with zero records) is canon-neutral by construction — zero-record chunks simply contribute nothing.
- **Conflicts RETAINED, not averaged (§6.3)**: identity/term groups keyed by NFC/LF-normalized source form (T924-SC-09; no case folding); agreeing groups merge into canon; disagreeing groups emit ONE `CONFLICTING` fact per distinct target variant into `unresolvedFacts` — nothing enters canon for a disputed key. Model `conflictNotes` persist as WEAK `NARRATIVE_STATE` facts (UNRESOLVED), deduped.
- **No series promotion (§6.4)**: every fact is `CANONICAL_CHAPTER_WIDE` + `CHAPTER_ANALYSIS`; `seriesUpdateCandidates` and `correctionCandidates` are always empty in slice B (§7.1 corrections belong to a later run observing committed translations).
- **Deterministic ordering (documented sort keys, KDoc :14-25)**: chunks consumed in ordinal order, records in list order; grouping first-seen; output lists SORTED before id assignment — entities by (type name, source, target), terms by (source, target), unresolved by (type, source, target, note), scenes by (firstPage, lastPage, chunkOrdinal, in-chunk index). Fact ids `f-1..f-N` across entities→terms→unresolved; scene ids `s-1..M`. Scene participants remapped onto final fact ids; unresolvable participants dropped, never guessed.
- **Evidence per fact**: chunk-level refs (the chunk DTO has no per-record attribution — documented) unioned over contributing chunks, deduped, sorted by (pageKey, stableBlockId, hash), capped at 32; strength `STRONG_CONTEXTUAL` (or `WEAK` + note when a chunk carried no refs).
- **Bound safety**: schema bounds enforced by deterministic truncation of the sorted lists; a fact whose stored forms exceed 128 chars is DEMOTED to a WEAK note fact, so an out-of-bound chunk record can never fail the freeze. `ExtractedTerm.kind != TERM` is preserved as a `termKind=<K>` note (no ProfileFact counterpart — no silent loss). Resolved relationship triples become RELATIONSHIP facts in `entities` with the type recorded as `rel=<type>` in the note.

### D2 — Freeze publication (new `pipeline/batch/ProfileFreezePublication.kt`)

`internal object ProfileFreezePublication` — T924-TX-22 in ONE `publishSidecarPointers` transaction (:35/:45):

- Pre-write gates: DTO `validationError()`; **FP-05 recomputed via `StageFingerprints.profileContentFingerprint` and verified equal to the DTO's `contentFingerprint` field** (:59, mismatch = Rejected); version monotonic per chapter against the manifest's current pointer (`(manifest.profile?.version ?: 0) + 1`, :66).
- Sidecar: immutable content-addressed `profiles/f-<sha256(fp)>.json` via the existing `ChapterArtifactLayout.profileFile` convention, published through `jsonSidecarPublication` FIRST; then the manifest `profile = ProfilePointer(fileName, schemaVersion, contentFingerprint, version, profileInputFingerprint)` moves in the SAME transaction. Any rejection leaves the prior manifest authoritative.
- Supersede = NEW file + NEW pointer (content-addressed names make byte-identical re-publication idempotent — the ST-10 crash-(b) orphan heal); the prior file is never touched.
- `readReusableFrozenProfile` (:130): the ST-05/ST-10/ST-30 reuse read — usable ONLY when the pointer is well-formed, the sidecar loads at a supported schema and validates, pointer content/version/input identities match the DTO, and FP-05 recomputes equal. Anything else = `NotReusable` (absent, never partially trusted; future-version bytes preserved read-only per SC-13).

**FLAGGED ADDITIVE STORE HELPER (≤5 lines, slice-A ratified precedent):** `ChapterArtifactStore.profileSidecarName(contentFingerprint)` (:828) beside the existing WP9 name helpers — `layout` is store-private and the `profiles/` layout convention exists but had no store-side accessor; no DTO or store behavior changed.

### D3 — Coordinator extension (`pipeline/batch/ChapterProfileBatchCoordinator.kt`)

- **ST-05 reuse skip rule** (:209-249): after runId resolution, BEFORE the OCR loop, `frozenProfileReuse` (:1037) computes the run's corpus identity from DURABLE checkpoints — each checkpoint revalidated against the CURRENT source sha (ST-04; a changed/missing page ⇒ no reuse, the normal path re-OCRs it) — derives FP-04 via `profileInputFingerprintOf` (:1084) and consults `readReusableFrozenProfile`. On a match: the RUN_SNAPSHOT record publishes, then a single `PROFILE_FROZEN (reuse)` record carries the SAME pointer + `profileReused=1` + the corpus fingerprint, and the run returns **PAUSED with `PROFILE_FROZEN_REUSE_REASON`** — zero OCR, zero provider calls.
- **FP-04 consistency**: `profileInputFingerprintOf` uses the SAME `policyFingerprint("analysis-policy-v1", overlapPages)` helper as the analysis identity, `AnalyzerProvenanceFactory.ANALYSIS_SCHEMA_VERSION/PROMPT_VERSION`, provider/model split from the frozen `providerKey` (`engine:model`, DB-07), `credentialId` as the opaque signature (blank → absent), and explicit `null` user/series authority (the `AUTHORITY_ABSENT` literal). Identical at freeze time and at every later reuse probe by construction.
- **ST-09→ST-10 freeze** (`runProfileReconcileAndFreeze`, :822; called from the completed-chunk tail :785): durable chunk list re-read from `manifest.analysisChunks` pointers (identity-consistent — the wave-4 prefix validation proved the list IS this run's plan); any unreadable/invalid sidecar = typed PAUSED (ST-30, never partially trusted). PROFILE_RECONCILE record published (entered), pure `ProfileReconciler.reconcile` runs (rejection = typed PAUSED, chunk evidence stays durable), PROFILE_RECONCILE record updated with reconciled/pending counts, then the DTO is assembled (next monotonic version, run FP-04, run id, first-canon-chunk provenance, FP-05 computed over the DTO with the field blanked) and frozen via D2. On `Committed`: the PROFILE_FROZEN record carries the NEW pointer + `profileFrozen=1` + cumulative chunk counters; terminal **PAUSED with `PROFILE_FROZEN_STOP_REASON`** — never COMPLETE (wave-2 F1 untouched). On `Rejected`: typed PERSISTENCE_REJECTED, prior manifest authoritative, `profileFreezeRejected=1`.
- **Counters** (typed, operational, never fingerprinted): `profileChunksTotal/Reconciled/Pending`, `profileFrozen`, `profileReused`, `profileReconcileRejected`, `profileFreezeRejected` (:1553-1563). The freeze record re-publishes the chunk counters so the single record stays a complete progress snapshot.
- `record()` gained an optional `profilePointer` (:1460); `ANALYSIS_STOP_REASON` removed (slice-B terminal replaced it); the `ANALYSIS_NO_WORK` (textless) path is UNCHANGED — an empty canon freezes nothing; the FINALIZE skip-to-phase belongs to the completion stage (documented deviation 3).

### D4 — Tests (all new files + one updated)

- `translator/contextual/ProfileReconcilerTest.kt` (14 tests): byte-determinism via assembled-DTO canonical bytes; MISSING_ONLY exclusion + all-pending empty canon; entity/term conflict retention (CONFLICTING, never averaged); notes as WEAK facts; no series promotion (scope/provenance/empty candidates); ordering pins + fact-id sequence + scene-id reassignment + participant remap/drop; term-kind note retention; validate-every-record gate (invalid record, ordinal gap, INVALID status, empty input).
- `pipeline/batch/ProfileFreezePublicationTest.kt` (6): one-transaction commit (pointer + sidecar + reuse read); FP-05 mismatch rejected with nothing written; version monotonicity; rejection leaves prior manifest authoritative (bytes verbatim); supersede = new pointer + old file untouched; unfrozen on vanished/corrupt/identity-mismatched/future-version sidecar.
- `pipeline/batch/ChapterProfileFreezeCoordinatorTest.kt` (4): full pass freezes + PAUSED with typed counters and pointer on the record; resume after freeze skips EVERYTHING (worker fake records zero OCR pages, analyzer fake records zero chunk executions, same run id/pointer, `profileReused=1`); FP-04 invalidation (target lang change ⇒ new input fingerprint ⇒ NOT reused, zero re-OCR + zero re-sent chunks — chunks are language-independent evidence — re-freeze as v2 under a new run id); corrupt frozen sidecar treated as unfrozen and HEALED by the byte-identical content-addressed re-freeze (ST-10 (b)).
- `artifact/ProfileContentFingerprintGoldenTest.kt` + fixture `app/src/test/resources/t924/golden/t924-profile-golden-v1.json` (5): the fixture is a fully populated VALID profile whose `contentFingerprint` field IS the golden hash (self-verifying, `345def24…f63386`); pins canonical-byte round trip (T924-SC-06), cross-constructor hash stability, version-only-bump ⇒ SAME fingerprint (contract-mandated), operational-fields-only change ⇒ SAME, any hashed change ⇒ DIFFERENT. The fixture bytes were generated by the canonical encoder (not hand-written) and are regenerated verbatim by `decode → encode`.
- `pipeline/batch/ChapterAnalysisPhaseCoordinatorTest.kt` updated: slice-A stop-after-chunks terminals replaced by the slice-B freeze terminals (reasons, `PROFILE_FROZEN` record state, freeze counters, pointer on record); refusal and prefix-stale tests unchanged (freeze not reached).

## 3. Diff summary

Modified (main):
- `pipeline/batch/ChapterProfileBatchCoordinator.kt` (+~400: reuse skip rule, reconcile+freeze phase, FP-04 helper, profilePointer on record, counters/reasons)
- `artifact/ChapterArtifactStore.kt` (+4: FLAGGED additive `profileSidecarName` helper)

New (main): `translator/contextual/ProfileReconciler.kt`, `pipeline/batch/ProfileFreezePublication.kt`.
Tests: 4 new files + 1 golden fixture resource + `ChapterAnalysisPhaseCoordinatorTest` updates.

READ-ONLY surfaces respected: `ChapterTranslationProfile.kt`, `ChapterArtifactManifest.kt`, `StageFingerprints.kt`, `AnalysisChunkResult.kt`, rendering/*, `SequentialBatchCoordinator.kt`, domain/*, ui/*, `ChapterTranslationStore.kt`, `BatchChapterTranslator.kt` (coordinator ctor unchanged; `analysisChunkRunner` stays null in production — freeze only runs behind a wired transport). FF-01 still default OFF, no UI switch; Manual/Auto untouched.

## 4. Test counts

`./gradlew :app:compileStandardDebugKotlin :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.artifact.*"` → BUILD SUCCESSFUL; XML tally: **tests=578 failures=0 errors=0 skipped=0** (baseline 548/0). `ChapterTranslatorQueueRestoreTest` + `TranslationManagerStartupReconciliationTest` (coordinator-adjacent, outside the filter) run separately: green.

## 5. Deviations (with the §-references followed)

1. **Chunk-level evidence attribution** — the chunk DTO carries no per-record evidence links, so facts carry the deduplicated chunk-level refs of their contributing chunks with strength `STRONG_CONTEXTUAL` (§1.4 requires refs on non-WEAK facts; per-record attribution would need a schema change owned by T924-SC-*/T924-AP-*).
2. **No per-record gender/scene-range derivation** — the chunk DTO validates but does not persist gender facts or per-record page ranges (slice A doc, `AnalysisWire.ValidatedEntity`); reconcile v1 therefore emits no GENDER facts and all facts are chapter-wide (§1.4 semantics honored; future scope needs the provider contract to persist them).
3. **No-work (textless) path unchanged** — ST-05 :114 lists "no translatable work" alongside the profile-reuse skip; freezing an empty profile for a textless chapter buys nothing and FINALIZE is not reachable in this slice, so the existing `ANALYSIS_NO_WORK_REASON` PAUSED terminal stays (skip-to-phase FINALIZE lands with the completion stage).
4. **`seriesUpdateCandidates`/`correctionCandidates` always empty** — §6.4 forbids auto-promotion and §7.1 corrections require observing committed translations (Stage 6+); recorded rather than populated.
5. **FLAGGED (loudly, per ownership rules): `ChapterArtifactStore.profileSidecarName`** — 4-line additive accessor to the EXISTING `ChapterArtifactLayout.profileFile` convention (the exception clause assumed "no layout convention exists"; the convention exists but is store-private — the 3-line accessor is the minimal faithful workaround, mirroring the wave-4-ratified `analysisChunkSidecarName` precedent).
6. **Relationship facts stored in `entities` with `rel=<type>` notes; term kind as `termKind=<K>` notes** — the §1.4 fact lists have no relationship-list or term-kind field; note-preservation beats silent dropping. Schema-visible only inside note text.
7. **Freeze-rejection terminal is PERSISTENCE_REJECTED** (not PAUSED) — mirrors the slice-A chunk-publication semantics: a guarded publication rejection means a later run re-reads the store; the reconcile-rejection terminal (pre-publication, evidence-based) is PAUSED per ST-09.

## 6. Risks

- The reuse probe re-reads every checkpoint (local file IO) on every run start even when no profile exists (one bounded directory of small JSON reads; no decode/native work). If device gates show it in open latency, gate it behind `manifest.profile != null` first — the code already short-circuits there.
- `ProfileReconciler` bound truncation means a pathological >512-entity chapter silently drops the sorted tail (deterministic, but data-lossy); §1.4 caps are (T)-tunable and the truncation counters are visible in the frozen content.
- Freeze publication is serialized behind the store monitor like all M2 transactions; concurrent MANUAL readers are unaffected (freeze touches no page state — TX-07 by construction).
- Wave-2 F1 (`decideResume` production wiring) remains OWED to the first COMPLETE-publishing stage; this slice still publishes zero COMPLETE runs.

## 7. Cuts

None. D1-D4 all landed; the cut order (D3-skip-rule, then goldens) was not needed. Time-box headroom absorbed by the two real bugs the coordinator tests caught (freeze record missing chunk counters; reuse probe initially trusting checkpoints without source revalidation — the second is exactly the cross-run poisoning class wave-4 F-W4-1 warns about, caught before landing).
