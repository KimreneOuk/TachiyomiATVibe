# T924 Stage 6 Slice A — Profile-Aware Translation: Envelope Dispatch + TX-20/21 + DR-A + Resume (ST-11 → ST-12)

Implementer report. Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base HEAD `28a75c5`. No commits made (orchestrator commits). Targeted suite after the change: **597 tests, 0 failures** (baseline 581/0; +16 new tests, slice-A coordinator pins in 2 existing test files updated to the slice-A continuation).

All paths below are relative to `app/src/main/java/eu/kanade/translation/` (tests: `app/src/test/java/eu/kanade/translation/`) unless a Plan path is given.

## 1. Contract anchors (file:line)

Under `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/stage0/`:

- `contracts-state-transactions.md`
  - ST-11 ENVELOPE_PLAN — :155 (SC-20 sidecar + pointer; "re-plan if any input fingerprint changed … else reuse"; oversize page → durable structural failure + pause)
  - ST-12 TRANSLATE — :166 (one envelope in flight; per-page `mergeTranslation` M3/M4 commits; rolling context advances only through contiguous fully-committed pages; crash resume at the first unresolved gap)
  - T924-TX-20 translation commit CAS preconditions — :363 (full M4 ladder + per-block identity + NEW `profileContentFingerprint` + `envelopePlanFingerprint`; rejected commits never advance the frontier)
  - T924-TX-21 envelope dispatch revalidation — :377 (lease reacquisition, fresh-snapshot identity compare, user-edited/manual-completed skip, deterministic suffix re-plan)
- `decision-briefs.md`
  - DB-06 / T924-DR-A mixed-response retention, Option 1 — :76 (MISSING_ONLY commits independently complete pages; AMBIGUOUS_PROTOCOL and refusals discard ALL parent-attempt values)
  - DR-C/DR-D shared Batch sub-limit — :94 (15-RPM Batch layer beneath the provider quota; T924-R011 `requirements-catalog.md` :68)
- `contracts-schemas-fingerprints.md`
  - T924-SC-20 sidecar-then-pointer publication order — :314; T924-SC-10 canonical content hashing — :290 (`planFingerprint` hashes the re-encoded canonical DTO with operational fields zeroed)
  - T924-FP-06 translation provenance fingerprint — :327 (see Deviations §5: no durable home in this slice)
- `feature-flags-stage-gates.md` — T924-FF-01 `translation_batch_profile_pipeline` — :56 (OFF; slice A rides only the FF-01 branch)

## 2. Design per deliverable

### D1 — Envelope plan publication (new `pipeline/batch/EnvelopePlanPublication.kt`)

`internal object EnvelopePlanPublication` — mirrors `ProfileFreezePublication` idiom-for-idiom.

- `publish(artifact, manifest, plan, nowEpochMs)` (:43): validates the DTO (`validationError()`), RECOMPUTES the SC-10 fingerprint from the canonical re-encoded DTO with `planFingerprint=""` and `createdAtEpochMs=0` (`recomputedContentFingerprint` :134 — operational fields excluded from the hash by zeroing, T924-SC-10), rejects any mismatch BEFORE any byte is written, then publishes sidecar-then-pointer in ONE `publishSidecarPointers` transaction setting `manifest.envelopePlan = SidecarPointer(fileName, SCHEMA_VERSION, plan.planFingerprint)`. Content-addressed file name via `ChapterArtifactStore.envelopePlanSidecarName` (:835, one additive helper mirroring the WP9 section). Byte-identical republication maps to the same content address — idempotent by construction.
- `readValidatedPlan(artifact, manifest)` (:98): pointer well-formedness + sidecar load + `pointer.contentFingerprint == plan.planFingerprint` + SC-10 recompute (:121). Any failure → `NotUsable` (T924-ST-30: never partially trusted).

### D2 — Serial executor (new `pipeline/batch/ProfileEnvelopeExecutor.kt`, ~750 lines)

`internal class ProfileEnvelopeExecutor` (:82) — typed constructor: store, `ContextualTextTranslator` (the EXISTING legacy seam), frozen `profileContentFingerprint` (FP-05), a `replan` callback, and injectable `sublimitGate` (default `SharedBatchRequestSublimitGate.instance`), retry policy, provider profile, clock. No planning logic, no I/O beyond the store.

- `run(work)` (:147): serial loop over the plan's envelopes — exactly ONE envelope in flight; after each envelope either progress (index+1) or a typed stop. Livelock guard: more than `MAX_CONSECUTIVE_REPLANS = 8` (:682) re-plans without progress pauses with a typed reason instead of looping.
- Per envelope, per page (TX-21, `dispatchEnvelope` :265): reacquire the BATCH lease (`tryAcquirePageStageLease` — DENIED on MANUAL ownership → typed pause, never preemption); re-check whole-page authoritativeness (`pageAuthoritativelyDone` :655 — READY/SKIPPED/`hasRenderedResult`/`committed.hasManualEdits` → skip, never revoke); `revalidationDrift` (:493) compares the LIVE snapshot against plan-time inputs — pageVersion, candidateGenerationId, dependencyFingerprint, artifactPageVersion, sourceFingerprint, per-block OCR fingerprints + source texts, and drops user-edited blocks (`userEditedAt != null`) from dispatch. Any drift → `ReplanNeeded` BEFORE any dispatch of that envelope (committed history untouched).
- Chunk assembly: detached block copies, stable wire ids `p<N>_b<M>`, protocol BATCH_V1, rolling context seeded from `BatchContextFrontier` over the plan's pages (`buildFrontier` :666; `record()` only after Accepted commits — gap-free, never advances on rejection).
- Dispatch rides the EXISTING legacy machinery: `sublimitGate.executeBatch(metadata) { translateAiChunkWithAdaptiveRetry(...) }` (:351) — the same adaptive retry controller, strict request builder and parser discipline as legacy Batch. `ProviderRequestPausedException` (gate deferral, quota) is caught → typed pause (:365). Provider identity (`providerBackend/model/credentialScope`, op `translation_envelope`) keys the DR-D bucket.
- DR-A Option 1 classification (:389-430): any `isStructuralRefusal` block OR Terminal+REFUSAL → discard the WHOLE response, typed REFUSAL/TERMINAL pause. `Paused`/`Terminal` with `ProviderFailureKind.PROTOCOL` while pages are only PARTIALLY covered → AMBIGUOUS_PROTOCOL: discard EVERYTHING (nothing from that response commits), typed PROTOCOL/PAUSE. Otherwise MISSING_ONLY: commit exactly the FULLY-covered pages (all planned block ids present in `outcome.blockTranslations`); a partially-covered page commits NOTHING (page atomicity).
- `commitPages` (:534): full M4 identity ladder + per-block `TranslationBlockPatch` (expected OCR fingerprint, source text, prior translation, prior `userEditedAt`) **plus the TX-20 provenance fields** `profileContentFingerprint` + `envelopePlanFingerprint = work.planFingerprint`. Accepted commit → `frontier.record` + counters. Rejected commit → typed PROTOCOL/PAUSE with ZERO frontier movement. Terminal/Paused controller outcomes commit retained pages first, THEN pause — durable progress is the store, so a paused drain is resumable.

### D3 — TX-20 provenance commits (`TranslationStageContracts.kt` + `ChapterTranslationStore.kt`)

**LOUD: this is the first slice touching the LEGACY merge path.** The change is strictly additive-nullable:

- `TranslationStagePatch` gains two trailing nullable fields with defaults — `profileContentFingerprint: String? = null` (:108), `envelopePlanFingerprint: String? = null` (:116) — documented: null = legacy behavior byte-identical; non-null = reject on mismatch vs the currently frozen profile / envelope-plan pointer.
- `mergeTranslationLocked` identity chain appends ONE clause (`ChapterTranslationStore.kt:1170`): `?: translationProvenanceRejection(patch)` (:1232). The helper short-circuits to `null` (accept, zero behavior change) when BOTH fields are null; when set, it compares against `artifactManifest.profile.contentFingerprint` / `manifest.envelopePlan.contentFingerprint` with typed rejection reasons (`translation provenance rejected: frozen profile changed …` / `… envelope plan changed …`). Rejection happens BEFORE any block mutation.
- Legacy callers never set the fields → the helper is a null-check fast path; the merge body is untouched.

### D4 — DR-A Option 1 + durable progress

Implemented inside the executor's classification block (D2 above). Durable progress = the STORE's per-page translation state only: committed pages are excluded from re-planning (`pageEnvelopeDone`), committed translations are never overwritten (per-block `expectedTranslation`/`expectedUserEditedAt` CAS + user-edit skip at plan AND dispatch time), and the terminal state of the slice is always PAUSED — `COMPLETE` is never published (native/render are Stage 7; the pause reason says so explicitly).

### D5 — Coordinator wiring (`pipeline/batch/ChapterProfileBatchCoordinator.kt`)

- New constructor params: `textTranslator: ContextualTextTranslator? = null` and `translationSublimitGate: BatchRequestSublimitGate = SharedBatchRequestSublimitGate.instance`.
- PROFILE_FROZEN → ENVELOPE_PLAN → TRANSLATE: both the freeze Committed branch (:1035) and the frozen-profile REUSE branch (:282) now `return runEnvelopePlanAndTranslate(...)` (`runEnvelopePlanAndTranslate` :1099) — reuse keeps zero re-OCR / zero re-analysis and continues into the envelope phase.
- `runEnvelopePlanAndTranslate`: publishes ENVELOPE_PLAN phase records (with profilePointer); builds dispatch work (`buildEnvelopeDispatchWork` :1421); `NothingPending` → typed no-work terminal (`ENVELOPE_NO_WORK_REASON` :2193, counter `skippedNoWork`); `CorpusDrift`/`PlannerRejected` → typed pause (oversized pages additionally get `persistDurableStageFailure` per named page, ST-11 terminal rule); plan reuse: re-planning is pure and deterministic, so the plan is (re)published ONLY when the derived fingerprint differs from the manifest pointer — identical inputs reuse the published plan (ST-11 resume rule realized via content addressing); `translator == null` → CONFIGURATION pause at TRANSLATE (`TRANSLATE_NO_TRANSPORT_REASON` :2195) with the plan already durable, so a later wired run resumes directly into TRANSLATE; success → TRANSLATE record + executor run; drained → `TRANSLATE_STOP_REASON` (:2197); executor pause → typed pause carrying failure kind/retryability/anchor.
- Resume hydration (`adoptCheckpointSnapshot` :1558, called from `buildEnvelopeDispatchWork`): a page restored from the artifact store after process death is a SYNTHESIZED PLACEHOLDER WITHOUT BLOCKS — the durable OCR content lives in the checkpoint's page-snapshot sidecar (checkpoint CLOSE re-owns the snapshot and clears the candidate; verified in `ChapterArtifactStore.checkpointOcr` CLOSE branch). The envelope phase adopts the checkpoint snapshot into the live store under the standard M1 BATCH lease+merge idiom, identity-fenced by the placeholder's generation/pageVersion/prior-OCR-fingerprints. Only pending, block-less pages are adopted; committed/skipped/manual pages and fresh in-session pages (blocks present) skip adoption entirely. Adoption failure → typed CorpusDrift pause, never planned against fabricated content.
- `BatchChapterTranslator.kt` FF-01 branch ONLY (:698): passes `textTranslator = contextualTranslator` into the profile coordinator. The legacy OFF branch is untouched (byte-identical); no other changes in that file.

### D6 — Tests

16 new tests across 3 files (pins in §4), plus slice-A pin updates in `ChapterAnalysisPhaseCoordinatorTest.kt` / `ChapterProfileFreezeCoordinatorTest.kt` (the run now CONTINUES past freeze into the envelope phase: terminal reasons and states move from PROFILE_FROZEN stops to TRANSLATE no-transport; the freeze resume test additionally pins `manifest.envelopePlan != null`).

## 3. Diff summary

```
ChapterTranslationStore.kt                         |  47 +   (TX-20 rejection helper + chain clause)
TranslationStageContracts.kt                       |  16 +   (2 nullable provenance fields + docs)
artifact/ChapterArtifactStore.kt                   |   7 +   (envelopePlanSidecarName helper)
pipeline/batch/BatchChapterTranslator.kt           |   8 +   (FF-01 branch: textTranslator seam)
pipeline/batch/ChapterProfileBatchCoordinator.kt   | 696 +   (envelope phase, work builder, adoption, reasons/counters)
pipeline/batch/EnvelopePlanPublication.kt          | NEW     (SC-20 publication + validated read)
pipeline/batch/ProfileEnvelopeExecutor.kt          | NEW     (serial dispatch, TX-21, TX-20, DR-A)
pipeline/batch/ChapterAnalysisPhaseCoordinatorTest |  19 +-  (slice-A pins)
pipeline/batch/ChapterProfileFreezeCoordinatorTest |  22 +-  (slice-A pins)
pipeline/batch/EnvelopePlanPublicationTest.kt      | NEW     (4 tests)
pipeline/batch/TranslationProvenanceMergeTest.kt   | NEW     (3 tests)
pipeline/batch/ProfileEnvelopeDispatchTest.kt      | NEW     (9 tests)
```

No read-only file was modified: artifact DTOs, `StageFingerprints`, `GlobalEnvelopePlanner` + goldens, `AiTranslationRetryController`, `rendering/*`, `SequentialBatchCoordinator`, domain/ui are untouched.

## 4. Test coverage pins

`pipeline/batch/EnvelopePlanPublicationTest.kt` (SC-20/SC-10):

- plan sidecar + pointer publish in one transaction; validated read returns the same DTO (:60)
- recomputed-fingerprint mismatch rejected BEFORE any byte; PRIOR pointer stays authoritative (:79)
- byte-identical republication idempotent at the same content address, pointer unchanged (:91)
- pointer whose sidecar is corrupt reads NotUsable (ST-30) (:107)

`pipeline/batch/TranslationProvenanceMergeTest.kt` (TX-20 merge gate):

- legacy patch without provenance fields accepted; in-memory store, no artifact authority — null path byte-identical
- stale `profileContentFingerprint` → Rejected `translation provenance rejected: frozen profile changed`, zero block mutation
- stale `envelopePlanFingerprint` → Rejected `… envelope plan changed`, zero block mutation

`pipeline/batch/ProfileEnvelopeDispatchTest.kt` (rides the REAL retry controller + strict request builder via an `AiTranslator` fake; isolated `BatchRequestSublimitGate` per test):

- full dispatch: 3 pages → 1 envelope, wire ids exactly `[p0_b1, p1_b1, p2_b1]`, all READY with translations, plan sidecar durable, terminal PAUSED `TRANSLATE_STOP_REASON`, counters `envelopesTotal=1/pagesTranslated=3`, max in-flight = 1
- TX-21 drift: external OCR mutation of a pending page during envelope 1 → exactly 1 suffix re-plan, committed prefix untouched, drifted page re-planned with NEW content, all 17 pages translated once
- TX-21 user-edit: block edited mid-run → never re-sent, never overwritten (`translation="human"`, `userEditedAt=123`), 16 pages translated
- no translatable work: all blocks user-edited, fresh-store resume → `ENVELOPE_NO_WORK_REASON`, zero provider calls, zero re-OCR/re-analysis, `skippedNoWork=1`
- AMBIGUOUS_PROTOCOL: only first block valid across the whole retry budget → whole-response discard, PROTOCOL/PAUSE, envelope 2 commits NOTHING (8 committed pages remain)
- terminal transport (AUTHENTICATION/TERMINAL): the fully-covered page from the last partial response still commits (MISSING_ONLY retention, 9 pages), remainder pending
- structural refusal text → REFUSAL/TERMINAL pause, 0 committed
- process-death resume: 8 committed before the terminal failure; fresh stores → zero re-OCR, zero re-analysis, committed ids NEVER re-sent, 9 more translated, all 17 READY
- sub-limit riding: 17 single-page envelopes vs a 15-RPM isolated gate → 16th defers with QUOTA_EXHAUSTED, exactly 15 provider calls, 15 pages committed

Tally: `app/build/test-results/testStandardDebugUnitTest/*.xml` → total=597 failures=0 errors=0 (baseline 581 + 16 new).

## 5. Deviations

- **§T924-FP-06 (:327) has NO durable home in this slice.** TX-20 commits carry `profileContentFingerprint` + `envelopePlanFingerprint`, which implements the mandated stale-profile and plan-identity protection, but the full provenance aggregate (translator signature, prompt version, per-block source hashes, per-envelope) is not recorded anywhere durable — a schema/manifest extension was not authorized for slice A. OWED: FP-06 durable recording (and its reuse-invalidation matrix row 6) to the retrans-validation/evidence stage.
- **TX-21.2 "checkpoint/OCR content fingerprint" re-read is approximated.** The executor revalidates per-block OCR fingerprints + source texts + page version/candidate generation/dependency fingerprint/artifact page version/source fingerprint at dispatch time, but does not re-derive the page's `PageOcrContentFingerprint` per envelope (the checkpoint fingerprint is carried from plan time). Block-level identity subsumes the content-drift cases that can occur between plan and dispatch; the wholesale page-fingerprint recheck remains available at re-plan.
- **Checkpoint adoption is a design addition** beyond the literal ST-11 text, forced by verified store behavior (block-less synthesized pages on artifact-authority reopen when checkpoint CLOSE cleared the candidate). It reuses the M1 lease+merge idiom, is identity-fenced, and only ever touches pending block-less pages, so no never-rule is at risk. Documented as D5.
- **Envelope-plan golden fixtures** (cut order item 1) not added — SC-10 determinism is pinned behaviorally (republication idempotency + fingerprint-mismatch rejection + reuse-without-republish in the dispatch tests).
- **Source-sha re-check per envelope** not repeated: source identity is validated at OCR preflight (ST-04/ST-06) and the per-page `sourceFingerprint` is part of TX-21 drift comparison; a changed source re-OCRs at preflight before any envelope phase.

## 6. Risks

- **Legacy merge-path touch (D3)**: mitigated by the additive-nullable design + dedicated legacy-behavior test; every pre-existing store/translation suite passes unchanged.
- **Shared gate contention in production tests**: the dispatch tests inject isolated gates; production wiring defaults to `SharedBatchRequestSublimitGate.instance`, so Analysis + Batch share the 15-RPM pool exactly as required (R011). Not directly pinned at the shared-instance level in this slice (WP5 governor tests own that seam).
- **Adoption writes**: adopting a checkpoint snapshot opens a fresh candidate per resumed page (durable). This is intended (it makes block state survive later crashes) but increases manifest churn on resume; bounded by one adoption per pending page per process.
- **Sub-limit timing test** uses a real-clock isolated gate; if the gate implementation ever blocks (waits for background) instead of deferring, the test would slow, not fail.
- **`MAX_CONSECUTIVE_REPLANS = 8`** is a livelock guard, not a contract number; a pathological environment could pause early — typed and resumable.

## 7. Cuts

- Envelope-plan sidecar golden fixtures (allowed cut 1) — see Deviations.
- Split-planner adapter refinement (allowed cut 2) — not needed; the pure planner interface fit without adaptation.
- FP-06 durable provenance sidecar — NOT a cut but an explicitly unauthorized extension; owed later (§5).
