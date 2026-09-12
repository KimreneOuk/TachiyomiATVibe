# T924 Stage 0 — Work item C: durable phase-transition/recovery table and OCR checkpoint/rebase transaction contract

Date: 2026-09-05 · Baseline: `adbe643` (verified) · Status: Stage-0 specification only. No production coding is authorized by this document.

Owner: Technical Lead (work item C). Namespaces: `T924-ST-*` (state/transition clauses), `T924-TX-*` (transaction clauses).

Cross-references (not duplicated here): DTO/sidecar shapes, canonical serialization, version rules and manifest pointer fields are owned by the schema/fingerprint agent (`T924-SC-*`, `T924-FP-*`). Provider/analysis request contract: `T924-AP-*`. Requirements catalog: `T924-R*`/`T924-INV-*`. This document defines *when* durable writes happen, *which* existing atomic mechanism performs them, *what survives* a crash at each boundary, and *how startup resumes* — it references sidecar fields by name only.

Precedence for conflicts (per `engineering/delivery-readiness-audit.md`): explicit Director decision > final product requirements (`design/profile-preflight-requirements.md`) > `design/final-target-migration.md` > `design/chapter-profile-batch-design.md` > this stage contract. Unresolved conflicts stop the affected work package (see §6).

Evidence labels: **VERIFIED** = read at HEAD `adbe643` with file+symbol citation. **RECOMMENDATION** = technically preferred approach. **PROPOSAL** = needs acceptance. **UNKNOWN** = not established from source.

Source-path note (correction): `ChapterTranslationStore.kt` lives at the translation package root (`app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`), not under `store/`. All paths below are relative to `app/src/main/java/eu/kanade/translation/`.

---

## 0. Verified transactional substrate (what the contract reuses)

Every clause below reuses one of these five existing mechanisms. No new atomicity primitive is introduced by T924.

| # | Mechanism | Where (VERIFIED) | Property |
|---|---|---|---|
| M1 | Crash-safe document publication: write `.tmp` → re-read+validate → rotate current primary to `.bak` → rename temp over primary; read recovers `.bak` over missing/corrupt primary and quarantines the corrupt primary as `.corrupt` | `artifact/ChapterDocumentIo.kt` — `AtomicChapterDocuments.publish` (:227-246), `publishJson` (:248-251), `readValidated` (:258-273), `recoverPrimaryFromBackup` (:281-301) | A crash between sidecar write and pointer move leaves an orphan file, never a committed pointer at a missing file |
| M2 | Whole-manifest compare-and-swap: every `ChapterArtifactStore` transaction takes the caller's manifest snapshot and rejects it unless it equals the durable manifest (`staleManifestRejection` re-reads the document) | `artifact/ChapterArtifactStore.kt` — `staleManifestRejection` (:897-905); all transactions `@Synchronized` | Two writers cannot commit against the same manifest snapshot ("concurrent candidate opens serialize against the same manifest snapshot" — `ChapterArtifactStoreTest.kt:1138`) |
| M3 | Candidate write preconditions: generationId + page version + dependency fingerprint + origin provenance | `artifact/ChapterArtifactStore.kt` — `candidateWriteRejection` (:817-839), `persistLiveCandidate` origin rejection (:373-377), `openCandidate` reuse/reject rules (:686-704) | A stale worker cannot commit over newer work; a foreign origin cannot write into an active candidate |
| M4 | Store-mutex CAS patches: `generation` + `pageVersion` + `leaseToken` + `candidateGenerationId` + `dependencyFingerprint` + `artifactPageVersion` + block fingerprints | `ChapterTranslationStore.kt` — `PatchPrecondition` (:190-202), `patchPage` CAS ladder (:565-598), `stageIdentityRejection` (:1095-1128), `mergeOcrLocked` rejection ladder (:836-861), `mergeRenderLocked` cleaned/inpaint-revision identity (:1061-1064) | Late results carrying stale identity are rejected, never clobber |
| M5 | In-memory writer leases: one origin owns a page; MANUAL evicts only AUTO; MANUAL-vs-BATCH and AUTO/BATCH never preempt; NonCancellable release; lease-release waiters | `store/PageStageLeaseTable.kt` — `tryAcquirePageStageLease` priority matrix (:79-98), `releasePageStageLease` (:139-150), `awaitPageLeaseRelease` (:199-215) | Writer exclusion per page per origin; leases are process-local state, never durable |

**VERIFIED:** leases are in-memory only (`PageStageLeaseTable.pageLeases` is a `ConcurrentHashMap`, :44; nothing in the class touches `ChapterArtifactStore`). Process death loses all leases; durable state is reconciled at load by `recoverInterruptedStages` (RUNNING → FAILED_RETRYABLE + durable-failure record "process interrupted", `ChapterArtifactStore.kt:846-892`, invoked from `loadOrMigrate` :145).

**VERIFIED:** a durable failure pointer and its page mutation share one manifest publication (`persistDurableStageFailure` doc comment, `ChapterTranslationStore.kt:668-673`; `persistLiveCandidateAndFailure` publishes sidecar + failure in one pointer move, `ChapterArtifactStore.kt:423-445`).

**VERIFIED (the root cause this contract fixes):** today's Batch OCR persistence advances a **BATCH-origin candidate**: `persistArtifactMutationLocked` derives the durable origin from the lease (`ChapterTranslationStore.kt:1717-1719`) and opens/persists the candidate under `ArtifactOrigin.BATCH`. Because `persistLiveCandidate` rejects any write whose origin differs from the active candidate's (:373-377, "candidate provenance mismatch") and `openCandidate` rejects opening while a candidate with a different origin/fingerprint is active (:700-703), releasing the Batch *lease* without closing/rebasing the candidate strands reusable OCR inside a candidate that no other origin may touch — and `cancelCandidate` would delete candidate-owned payloads via the reachability retention sweep (:756-815, :949-951).

---

## 1. Durable phase-transition/recovery table (T924-ST-*)

### T924-ST-01 — State carrier and general rules (normative)

1. **ST-01.1 (VERIFIED basis, PROPOSAL for the new field):** the durable phase carrier is the `ChapterRunRecord` sidecar (design §4.2) published via M1 and pointed to from the manifest. The manifest itself is a schema-2 document (`ChapterArtifactManifest.SCHEMA_VERSION = 2`, `ChapterArtifactManifest.kt:59`); adding a run-record pointer is a schema change owned by `T924-SC-*`. This contract requires only: the run record is an immutable-on-write sidecar whose *pointer* moves atomically, and every phase transition is exactly one pointer publication.
2. **ST-01.2:** the run record carries at minimum: `state` (last **completed** phase), `activePhase` (phase entered, not completed), `runId`, the frozen run-configuration fingerprint, and completed-phase counters (design §4.2). Exact fields per `T924-SC-*`.
3. **ST-01.3 (normative resume rule):** startup resumes the **first incomplete phase** recorded in the run record. It MUST NOT infer the chapter phase from page statuses. Per-page stage evidence (`PageArtifactRecord` stages, `displayState`) is *input to* the resumed phase (e.g. which OCR pages to skip), never a substitute for the phase pointer. Rationale: page statuses are per-page and partial by design; the phase pointer is the only durable record of orchestrational progress (design §3.1: "Startup resumes the first incomplete phase rather than inferring chapter phase solely from page statuses").
4. **ST-01.4:** each phase's work must be **idempotent** against its postcondition, because a crash inside an active phase can leave partial durable effects (e.g. some OCR pages checkpointed). Resume re-enters the active phase, re-derives the remaining work from durable state, and skips completed units by content identity (fingerprints per `T924-FP-*`), not by file presence.
5. **ST-01.5:** a phase transition publishes `{lastCompleted = P, activePhase = P+1}` in one pointer move. There is no durable "P+1 running" write *before* P+1's first sidecar write other than this pointer; a crash immediately after the transition resumes at P+1 with zero P+1 sidecars — a legal, empty state.
6. **ST-01.6 (VERIFIED basis):** store-generation fencing is orthogonal to phase state. `beginGeneration` bumps the in-memory generation and thereby rejects every in-flight guarded write (`ChapterTranslationStore.kt:503-507`; `patchPage` :566). "Apply now" semantics (ST-03) and user resets use generation bumps; phase recovery does not.
7. **ST-01.7:** terminal-failure states are durable and retryable-or-terminal per the existing failure model: `DurableFailureMetadata` with `FAILED_RETRYABLE`/`FAILED_TERMINAL`, category, retry budget and `nextEligibleRetryAtEpochMs` (persisted by `recordDurableFailure`, `ChapterArtifactStore.kt:244-261`; produced by `BatchWriteGate.persistAiFailure`, `BatchWriteGate.kt:136-199`), plus the crash-loop cap: `MAX_CONSECUTIVE_UNRESOLVED = 3` consecutive unresolved attempts cap a page (`ChapterArtifactManifest.kt:345`, attempt ledger written **before** the paid call, :314-321).
8. **ST-01.8 (sidecar GC):** all run/analysis/profile/envelope/checkpoint sidecars MUST be added to the retention reachability graph. **VERIFIED:** `reconcileRetention` "preserves exactly the files reachable from the manifest/generation graph … and deletes every other file under the managed artifact tree using store reachability, never filename age alone" (`ChapterArtifactStore.kt:938-951`); it runs after `cancelCandidate` (:813) and at batch teardown (`store.reconcileArtifactRetention()`, `BatchChapterTranslator.kt:794`). Consequence: any new sidecar not made reachable **will be deleted by the existing sweep**. GC triggers used in the table below: (a) retention sweep at teardown and candidate cancel; (b) superseded-pointer cleanup when a new generation's pointer replaces the old and the old leaves the reachability graph; (c) orphan `.tmp`/orphan sidecars swept by retention/load ("artifact load removes corrupt and orphan managed temp files but keeps active candidate" — `ChapterArtifactStoreTest.kt:778`).

### Per-state table

For each state: **Entry** precondition · **Durable writes** · **Atomicity** (mechanism) · **Post** postcondition · **Crash** surviving state · **Resume** action · **Terminal** failure states · **GC** trigger.

---

**ST-02 — ADMISSION/DOWNLOAD**

- **Entry:** user/queue request; chapter selected for AI Batch. **VERIFIED:** Batch explicitly fails when local chapter files are absent and interrupted downloader `.tmp` files are not a valid source (design §3.1, citing `ChapterTranslator.kt:583-635` — not independently re-read; carried from design as STRONG INFERENCE).
- **Durable writes:** none new. Queue/pending ownership is the existing queue store's domain (outside this contract).
- **Atomicity:** n/a (no chapter-translation artifact writes).
- **Post:** finalized local directory/archive exists; ordered page stream list resolvable.
- **Crash:** nothing chapter-durable changed; queue state follows the existing queue-restore contract (no auto-start — `TranslationManagerStartupReconciliationTest.kt:174` "admitted exactly once without auto-start").
- **Resume:** re-admit from queue/pending state; phase pointer never created.
- **Terminal:** SOURCE-class failure (chapter missing/corrupt) recorded through the existing queue failure path.
- **GC:** n/a.

**ST-03 — RUN_SNAPSHOT (freeze run configuration)**

- **Entry:** admission postcondition. The coordinator creates a run id and freezes the complete configuration: source/target languages, OCR model, reading order, inpaint mode, provider/model/settings, protocol versions, analysis policy, series/user glossary fingerprints, envelope policy (design §3.1.2).
- **Durable writes:** `ChapterRunRecord` sidecar v1 (`state=ADMISSION` completed, `activePhase=SOURCE_VALIDATION`, `frozenRunConfigFingerprint`, `runId`) + manifest run pointer. One M1 publication sequence (sidecar publish, then pointer move in a manifest M2 transaction).
- **Atomicity:** M1 for the sidecar; M2 (`staleManifestRejection`) for the pointer. Sidecar bytes are immutable thereafter.
- **Post:** run configuration exists durably with a content fingerprint; all later phases validate against it.
- **Crash:** before sidecar → no run record; resume re-creates it. After sidecar before pointer → orphan sidecar, GC'd by retention; resume re-creates. After pointer → resume at SOURCE_VALIDATION.
- **Resume:** if a run record exists and its frozen configuration fingerprint still matches current settings, continue at `activePhase`; **ST-03.1 (freeze semantics, normative):** settings changes apply **next run** — a resume never silently adopts new settings into an existing frozen configuration; the run either continues under its fingerprint or is ended (see below). **"Apply now" = stop the active run + `beginGeneration` + create a NEW generation/run with a fresh snapshot and full re-plan** (design §3.1.2). Mechanism **VERIFIED:** the generation bump invalidates every in-flight guarded write (`ChapterTranslationStore.kt:503-507`, `patchPage` :566), and page leases bind the generation at acquisition (`PageStageLeaseRecord.generation`, `PageStageLeaseTable.kt:60-65`), so a new run cannot write over the old one's fencing window.
- **Terminal:** configuration unresolvable (no provider/model) → run record never published; queue-level failure.
- **GC:** superseded run records for abandoned runs become unreachable when the run pointer moves; retention sweeps them (ST-01.8b). Whether N previous run records are retained for audit is a `T924-SC-*` retention-policy input (§6 Q4).

**ST-04 — SOURCE_VALIDATION**

- **Entry:** run record exists; `activePhase=SOURCE_VALIDATION`.
- **Durable writes:** per-page source identity (`SourceIdentity`: pageKey, sha256, width, height — shape **VERIFIED** at `ChapterTranslationStore.kt:1870-1878`) and the ordered source digest into the run record. Design §3.1.3 requires replacing the one-coroutine-per-page hashing fan-out with bounded IO (design cites `BatchChapterTranslator.kt:394-401`; STRONG INFERENCE from design — not re-read line-by-line this pass).
- **Atomicity:** per-page identities ride the existing `persistLiveCandidate` `source` field or the per-phase sidecar (schema owner: `T924-SC-*`); the ordered digest is one M1 run-record update. Per-page writes are individually atomic; the phase completes only when the digest publishes.
- **Post:** every natural page has a trusted source identity; `orderedSourceDigest` published.
- **Crash:** pages already hashed keep their identities (idempotent by content hash); resume recomputes only missing hashes with bounded IO (design §10 row 1).
- **Resume:** re-enter SOURCE_VALIDATION; skip pages with matching identity; re-validate all identities against current files (a changed file must fail validation → ST-09 terminal for the page or re-plan).
- **Terminal:** source changed/unreadable → SOURCE-category durable failure per page (existing `FailureCategory.SOURCE`, `BatchWriteGate.kt:30`).
- **GC:** n/a beyond ST-01.8.

**ST-05 — OCR_PLAN**

- **Entry:** SOURCE_VALIDATION complete.
- **Durable writes:** the OCR plan itself may remain in-memory **if** it is recomputable; **RECOMMENDATION:** persist nothing here except the phase transition. The plan is a pure function of (source identities, stage fingerprints, current store state) per design §3.1.4; persisting it invites staleness. If the schema agent defines a plan sidecar, it is advisory, never authoritative.
- **Atomicity:** single pointer move (M2).
- **Post:** `activePhase=OCR_PREFLIGHT`.
- **Crash:** resume recomputes the plan; cost is negligible.
- **Resume:** recompute. OCR decisions MUST NOT be rewritten by ordered-translation gaps (design §3.1.4) — the plan reads stage evidence, not translation frontier.
- **Terminal:** none specific.
- **GC:** n/a.

**ST-06 — OCR_PREFLIGHT** (the phase the T924-TX contract serves; see §2 for the per-page transaction)

- **Entry:** OCR_PLAN complete. For each required page, in natural order, one page at a time: acquire lease → decode → analyze → persist OCR → **checkpointOcr (T924-TX-01..)** → release bitmap → release lease (design §5 serial loop).
- **Durable writes (per page):** (a) OCR merge into the live candidate (existing `mergeOcr` M3/M4 path, `SinglePageOnnxPhase.kt:928-942`); (b) the origin-neutral `PageOcrCheckpoint` sidecar + candidate close/rebase in one M2 publication (§2); (c) on failure, the durable failure record in the same publication as the failed page state (M2, `persistLiveCandidateAndFailure`-style).
- **Atomicity:** per page = the §2 transaction. Across the phase: the run-record counters (`completedOcrPages`) advance by M1 pointer moves; per-page state is authoritative in the manifest, counters are advisory for progress UI only.
- **Post:** every required page is READY or TEXTLESS **and checkpointed** (origin-neutral); no BATCH-origin candidate remains active from this phase; no page lease held by BATCH remains. **VERIFIED basis for "READY and TEXTLESS are valid completion":** `analyzePage` produces `ocrStatus=READY` or a FAILED placeholder (`SinglePageOnnxPhase.kt:834-835, 861-905`); textless detection flows through the OCR result (`dependencyReadyAfterNative` accepts `StageStatus.READY || StageStatus.TEXTLESS`, `BatchLaneWorkers.kt:1311-1312`).
- **Crash (per page):** enumerated exhaustively in the §2 crash table (T924-TX-12). Phase-level: completed pages survive fully; only the active page's decode/recognize work is lost.
- **Resume:** re-enter OCR_PREFLIGHT; skip pages whose `PageOcrCheckpoint` content fingerprint matches current source+OCR configuration evidence (`T924-FP-*`); re-run the rest. A page found at RUNNING stage record at load was already flipped to FAILED_RETRYABLE by `recoverInterruptedStages` (**VERIFIED** :846-892) and is simply re-planned.
- **Terminal:** any unresolved OCR/persistence failure stops the phase before paid analysis (design §3.1.5). Decode-deferral and recognition failures are durable retryable page failures (existing guarded "batch decode deferred/failed" writes, `BatchLaneWorkers.kt:938-967`).
- **GC:** orphan OCR snapshot sidecars from crash boundary B1 (§2) swept by retention; superseded candidate snapshot files swept after close (M2 + `reconcileRetention`).

**ST-07 — ANALYSIS_PLAN**

- **Entry:** OCR_PREFLIGHT complete (all pages checkpointed).
- **Durable writes:** lightweight immutable OCR-corpus manifest built **only** from persisted OCR (design §3.1.6), published as an M1 sidecar + pointer; corpus fingerprint (`OcrCorpusFingerprint`, `T924-FP-*`). If there is no translatable work, or a compatible frozen profile already exists (content fingerprint match), the plan records skip-to-phase (PROFILE_FROZEN reuse or FINALIZE) — provider analysis is skipped (design §3.1.6, §12).
- **Atomicity:** M1 + M2 pointer.
- **Post:** corpus manifest immutable; decision (analyze / reuse profile / no work) recorded.
- **Crash:** before pointer → resume rebuilds corpus (deterministic from checkpoints); after pointer → resume at ANALYSIS_CHUNKS (or the recorded skip).
- **Resume:** verify corpus fingerprint still matches checkpoint fingerprints; mismatch (checkpoints changed under recovery) → rebuild corpus.
- **Terminal:** none specific.
- **GC:** superseded corpus manifests swept when the pointer moves.

**ST-08 — ANALYSIS_CHUNKS**

- **Entry:** ANALYSIS_PLAN complete with decision=analyze.
- **Durable writes:** one immutable `AnalysisChunkResult` sidecar per validated chunk (design §4.2), each M1-published with a per-chunk pointer (or a chunk-index sidecar advanced per chunk — `T924-SC-*`). Invalid chunks are never persisted; a chunk that fails validation is retried under the request-attempt policy and only its validated result is durable.
- **Atomicity:** per-chunk M1 (+M2 pointer/index). Chunk results are independent; no cross-chunk transaction exists.
- **Post:** all planned chunks have validated, immutable results.
- **Crash:** earlier validated chunk results survive (design §10 row: "Mid analysis chunk → earlier validated chunk results"); resume retries only the missing/invalid chunk.
- **Resume:** scan chunk index; re-execute first missing/invalid chunk; do not re-send valid chunks.
- **Terminal:** attempt exhaustion on a chunk → durable analysis failure on the run record (`FAILED_RETRYABLE` run-level, category from the typed provider error per `T924-AP-*`); phase stops.
- **GC:** superseded chunk results (retry produced a replacement) swept when their pointers leave the index.

**ST-09 — PROFILE_RECONCILE**

- **Entry:** ANALYSIS_CHUNKS complete (or skip-from-ANALYSIS_PLAN with existing profile → may bypass straight to reuse validation).
- **Durable writes:** deterministic pre-merge is computable and need not persist intermediate state; master reconciliation requests produce bounded conflict-set results that MAY be persisted as intermediate evidence sidecars (PROPOSAL; `T924-SC-*` decides). No frozen profile pointer exists yet — **VERIFIED design rule:** "Chunk evidence survives; no frozen profile pointer" (§10 row 5); translation MUST NOT start.
- **Atomicity:** any intermediate sidecars M1; final act is the PROFILE_FROZEN publication (ST-10).
- **Post:** a reconciled, validated profile candidate exists in memory/durable intermediates with all evidence references validated.
- **Crash:** resume re-runs reconciliation from persisted chunk evidence (deterministic normalization makes the pre-merge reproducible; master calls re-run under the attempt budget).
- **Resume:** re-enter PROFILE_RECONCILE; never resume "halfway frozen".
- **Terminal:** reconciliation attempt exhaustion → run-level retryable failure; chunk evidence remains for a later run.
- **GC:** intermediate evidence sidecars swept once the frozen profile publishes and they leave the reachability graph (retention policy input; §6 Q4).

**ST-10 — PROFILE_FROZEN**

- **Entry:** PROFILE_RECONCILE produced a validated profile.
- **Durable writes:** **one atomic publication:** (1) `ChapterTranslationProfile` sidecar (M1 publish of immutable bytes with its content fingerprint/version — shape per `T924-SC-*`); (2) run-record pointer update `{profilePointer, profileContentFingerprint, state=PROFILE_FROZEN}` in a single M2 manifest transaction. These two steps are one logical transaction; the crash boundary between them is covered below.
- **Atomicity:** M1 + M2. The profile sidecar file is never rewritten; a new profile = new file + new pointer.
- **Post:** profile immutable and attached to the run; `ProfileContentFingerprint` is the validity key for all downstream commits (§3).
- **Crash:** (a) before sidecar → resume re-runs reconcile; (b) after sidecar, before pointer → orphan profile file, GC'd; resume re-runs reconcile and MAY re-publish a byte-identical file (idempotent because content fingerprint is canonical — `T924-FP-*` guarantees semantic-equivalent artifacts hash identically); (c) after pointer → resume at ENVELOPE_PLAN. Under no crash outcome does a partially-frozen state translate.
- **Resume:** if pointer exists, validate the sidecar loads and its content hash matches the pointer; mismatch → treat as (b) (orphan) and re-run reconcile. Immutability rule **VERIFIED basis:** later correction candidates may not rewrite it (final-target §2 "Profile and translation ownership"); the existing single-publication pointer-move idiom is `promoteLiveCandidate` (sidecars first, manifest pointer second, `ChapterArtifactStore.kt:492-583`).
- **Terminal:** invalid evidence cannot enter the profile (validated at reconcile); a profile that fails load-time validation at resume is an orphan, not a frozen profile.
- **GC:** prior-generation profile files swept when superseded and unreachable.

**ST-11 — ENVELOPE_PLAN**

- **Entry:** PROFILE_FROZEN.
- **Durable writes:** `EnvelopePlan` sidecar (ordered whole-page envelopes, membership, stable block IDs, cost estimates, plan fingerprint) — M1 + pointer. Rebuilt/validated when resuming "Profile frozen, before translation" (design §10 row 6).
- **Atomicity:** M1 + M2.
- **Post:** deterministic plan exists; TRANSLATE may dispatch.
- **Crash:** before pointer → resume re-plans (pure function of pending blocks + budgets + profile subset); after pointer → resume at TRANSLATE but **revalidate** the plan against live store state at dispatch time (§3, T924-TX-20) — the plan is an input, never a license.
- **Resume:** re-plan if any input fingerprint changed (checkpoints, profile, pending set); else reuse.
- **Terminal:** a single page that cannot fit any legal envelope is rejected (page atomicity is invariant — design §8); that page gets a durable structural failure and the phase pauses at it.
- **GC:** superseded plans swept on re-plan.

**ST-12 — TRANSLATE**

- **Entry:** ENVELOPE_PLAN complete. One Batch provider envelope in flight (invariant, README constraints).
- **Durable writes:** per-page translation commits (existing `mergeTranslation` M3/M4 path with per-block identity preconditions, `ChapterTranslationStore.kt:946-1005`); durable per-attempt ledger entries written BEFORE each paid call (**VERIFIED** `AttemptLedgerEntry` doc :309-321); durable failures via `persistDurableStageFailure` (M2 single-publication with page state); rolling-context advancement only through contiguous fully-committed pages (`BatchContextFrontier`, retained per design §9.7).
- **Atomicity:** per-page CAS commit (M4) + per-attempt ledger (M1); envelope-level attempt accounting under one root budget (design §9.5).
- **Post:** all planned pages committed, skipped (user/manual-authoritative), or durably failed.
- **Crash:** complete per-page commits survive; incomplete candidates remain; the attempt ledger leaves a counted interrupted attempt consumed by startup reconciliation (`consumeUnresolvedAttemptsAtStartup`, `ChapterTranslationStore.kt:392`; cap at 3, :345). Resume at the first unresolved gap without repaying valid fragments (design §10 row 7).
- **Resume:** re-enter TRANSLATE; revalidate every pending page against the plan (T924-TX-20) before any dispatch; deterministic suffix re-plan for changed pages.
- **Terminal:** attempt-tree exhaustion → page capped (`applyAttemptCapPause`, `ChapterTranslationStore.kt:401`); refusal/terminal categories per existing `FailureCategory` mapping (`BatchWriteGate.kt:20-32`).
- **GC:** orphan response/retry artifacts swept by retention; superseded envelope plans swept on re-plan.

**ST-13 — NATIVE/RENDER**

- **Entry:** TRANSLATE dispatching (overlaps: remote translation may overlap serial inpaint; render waits for the relevant translation and native gates — design §3.1.12; **VERIFIED** current behavior at `SequentialBatchCoordinator.kt:452-613` is by design citation — STRONG INFERENCE, coordinator not fully re-read).
- **Durable writes:** inpaint results via guarded inpaint merge (`mergeInpaintLocked` with mask-fingerprint identity, `ChapterTranslationStore.kt:1007-1043`); cleaned-image publication substage (`CleanedImagePublisher` upstream of promotion — `promoteDisplayIfReadyLocked` doc :1880-1887); render/color commit via `mergeRender` CAS with cleaned-image + inpaint-revision + OCR-block identity (:1045-1093; patch construction at `BatchRenderJoin.kt:196-235`); committed-display promotion happens atomically inside `publishLocked` only when the page reaches `hasRenderedResult || isTextlessTerminal` (**VERIFIED** :1787-1807, :1888-1911).
- **Atomicity:** per-stage M3/M4 CAS; cleaned image file published+validated before the pointer that references it (M1; display base validation at promotion, `ChapterArtifactStore.kt:482-491`).
- **Post:** each page DISPLAY_READY / TEXTLESS_COMPLETE with a committed bundle; superseded cleaned images retained until drained (`retiredCleanedImages`, :127, :1893-1901, drained via `drainRetiredCleanedImage(s)` :2008-2018).
- **Crash:** committed display of every previously promoted page remains visible (candidate mutations cannot hide it — **VERIFIED** display projection `displaySnapshotLocked` :1913-1921 and test "cancelled transient candidate cannot hide committed display", `ChapterTranslationStorePhase3Test.kt:221`); resume only missing native/layout stages (design §10 row 8).
- **Resume:** re-enter NATIVE/RENDER; resume planner derives remaining stages from stage evidence (`PageWorkPlanner`/`BatchResumePlanner`, retained per design §2).
- **Terminal:** inpaint/render terminal failures are durable page failures; reader keeps the prior committed bundle.
- **GC:** retired cleaned images drained after newer bundles promote (existing drain mechanism); orphan cleaned files swept by retention.

**ST-14 — FINALIZE**

- **Entry:** all pages terminal (committed/skipped/durably failed).
- **Durable writes:** final run-record state (`state=FINALIZE` → run closed/finished), stranded-page reconciliation writes (**VERIFIED** pattern `BatchChapterTranslator.kt:747-760`), final `store.flush()` (**VERIFIED** NonCancellable flush in teardown :781-793), retention reconciliation (:794).
- **Atomicity:** M2 pointer for run closure; all preceding writes already durable.
- **Post:** no BATCH lease remains (**VERIFIED** `releaseAllPageLeases(PageWriteOrigin.BATCH)` at :767/:780); foreground progress completed; run record closed.
- **Crash:** any crash here leaves the run record open with activePhase=FINALIZE; resume re-runs finalize (idempotent: reconciliation + flush + retention are safe re-runs).
- **Resume:** run FINALIZE to completion; do not re-enter TRANSLATE/NATIVE/RENDER from FINALIZE.
- **Terminal:** stranded pages that cannot reconcile get durable FAILED states and are reported; run closes as partially complete.
- **GC:** final retention sweep (ST-01.8a).

### T924-ST-15 — RUN_SNAPSHOT freeze semantics (restated normatively)

- Settings changes apply **next run**: an in-flight run continues under its frozen `frozenRunConfigFingerprint`; resume validates that fingerprint against current settings only to decide *continue vs. stop*, never to mutate.
- "Apply now" is a user action meaning: stop the active run → `beginGeneration` (invalidates all in-flight fenced writes) → new run id + new generation → full re-plan from SOURCE_VALIDATION. Already-committed display and user edits remain authoritative across this (ST-01 invariants; `T924-INV-*` owns the invalidation matrix for what is *reused* vs *recomputed*).

### T924-ST-16 — Startup recovery ordering (normative)

1. Load manifest (M1 read with backup recovery/quarantine).
2. `recoverInterruptedStages` flips RUNNING stage records to FAILED_RETRYABLE with durable failure metadata (**VERIFIED** :846-892; test `ChapterArtifactStoreTest.kt:1109`).
3. Consume the attempt ledger's unresolved entries against the crash-loop cap (**VERIFIED** `consumeUnresolvedAttemptsAtStartup` :392; cap :345).
4. Read the run pointer; if absent, no run is resumed (phase state = ADMISSION). If present, validate its sidecar, then resume the first incomplete phase (ST-01.3). Queue admission never auto-starts a run (existing queue-restore contract).

---

## 2. `checkpointOcr` transaction contract (T924-TX-01..) — mandatory first implementation gate

Per final-target §2 ("The OCR checkpoint/rebase remains the first implementation gate") and audit blocking decision 3. Nothing in Stage 3+ may land before this transaction exists and passes its fault-injection gate.

### T924-TX-01 — Definition

`checkpointOcr` is a single guarded store transaction that, **while Batch still holds the page lease**, atomically: (a) validates the immutable OCR page snapshot; (b) compare-and-swaps on the full write identity; (c) installs the origin-neutral `PageOcrCheckpoint` pointer; (d) closes or rebases the BATCH candidate; (e) preserves the prior committed display; and (f) returns the new write identity. Only after success may the caller release the lease. Design §4.2 defines the intent; this clause binds it to the verified APIs.

### T924-TX-02 — Exact compare-and-swap inputs

All inputs are captured from one `PageSnapshot` under the store mutex (shape **VERIFIED**, `ChapterTranslationStore.kt:175-188, 2106-2120`) — the same identity set the OCR worker already carries in `BatchWriteIdentity` (**VERIFIED**, `BatchWriteGate.kt:36-43`, captured at lease grant `BatchLaneWorkers.kt:848-855`):

| # | Input | Source | Rejects when |
|---|---|---|---|
| 1 | `generation` (store run generation, Long) | `PageSnapshot.generation` | `!=` current generation (M4 ladder :566/:837/:1105) |
| 2 | `pageVersion` (store page version, Long — **non-null mandatory**) | `PageSnapshot.pageVersion` | `!=` current (`patchPage` :567, `mergeOcrLocked` :840) |
| 3 | `leaseToken` (fencing token, **non-null mandatory** — lease must be held) | `PageSnapshot.leaseToken` | token changed, or lease absent while one is required (`mergeOcrLocked` :855-858; late results after release are rejected — test `ChapterTranslationStorePhase3Test.kt:137`) |
| 4 | `candidateGenerationId` (BATCH candidate, String — **non-null mandatory** for checkpoint) | `PageSnapshot.candidateGenerationId` (= manifest `candidate.generationId`) | `!=` active candidate generation (M3 `candidateWriteRejection` :829-831) |
| 5 | `artifactPageVersion` (manifest record version, Long) | `PageSnapshot.artifactPageVersion` | changed (`candidateWriteRejection` :832-834; store ladder :842-844) |
| 6 | `dependencyFingerprint` (candidate dependency fingerprint, String) | `PageSnapshot.dependencyFingerprint` | changed (`candidateWriteRejection` :835-837). **Caveat:** the store-level ladder has a grace clause that *disarms* this check when no candidate exists (`patchPage` :587-590); because input 4 is mandatory-non-null for checkpoint, the grace clause can never mask a checkpoint compare. The artifact-level check has no grace clause (VERIFIED :835-837). |
| 7 | `sourceIdentity` (`SourceIdentity`: pageKey, sha256, width, height) | `PageTranslation.sourceIdentity` (:1870-1878) | written into the checkpoint; mismatch against current source at publish time is a content invalidation handled by re-plan, not a CAS reject |
| 8 | `priorOcrFingerprints` (pre-recognition OCR block identity) | `OcrStagePatch.expectedPriorOcrFingerprints` (`TranslationStageContracts.kt:64`) | OCR identity changed under the writer (`mergeOcrLocked` :853-854) — the checkpoint inherits this because it wraps/extends the validated `mergeOcr` result |
| 9 | canonical `PageOcrContentFingerprint` of the new OCR result | computed per `T924-FP-*`; carried opaquely | n/a (content identity recorded in the checkpoint; never derived from candidate IDs, page versions, or filenames — design §4.4) |

**TX-02.1:** inputs 2, 3 and 4 MUST be non-null for `checkpointOcr` in the standard branch (active BATCH candidate); a null any-of-these is a programmer error rejected outright (unlike `patchPage`, which permits optional identity for compatibility writers). **Exception — no-active-candidate branch (TX-03.1):** input 4 is null exactly when no active candidate exists for the page; input 9 then MUST match the committed bundle's OCR content fingerprint.

### T924-TX-03 — CLOSE vs REBASE decision rule (precise)

The `ArtifactOrigin` vocabulary has exactly two durable values, `READER_ADHOC` and `BATCH` (**VERIFIED**, `toArtifactOrigin`, `TranslationStageContracts.kt:38-41`). Consequently an "origin-neutral open candidate" is **not representable** in the current schema: `openCandidate` reuses an active candidate only when origin matches and the dependency fingerprint matches, and rejects otherwise (**VERIFIED** :686-703). REBASE therefore cannot mean "make the candidate neutral"; it means "close the BATCH generation and open a named successor generation in the same manifest publication".

**Rule (normative):**

- **CLOSE** the BATCH candidate (publish a `CANCELLED`/`CLOSED` `GenerationRecord` and clear the candidate pointer, preserving the checkpoint pointer) iff ANY of:
  - (a) the page reached an OCR-terminal state with no Batch successor work planned in this run (TEXTLESS with no translation work; terminal OCR failure);
  - (b) the run is stopping, pausing, or cancelling (ownership of the page's future is unknown);
  - (c) the next writer for the page is not deterministically this Batch run (interactive demand was observed for the page, or the page will be re-planned by resume).
  - **CLOSE is the default.** The checkpoint sidecar is origin-neutral, so a later Manual/Auto/Batch candidate opens fresh and consumes the checkpoint as its reusable native base (design §4.2: "A new Manual/Auto/Batch candidate starts from the checkpoint as its reusable native base").
- **REBASE** (close BATCH generation G and open successor generation G′ for the same run, `origin=BATCH`, `dependencyFingerprint = PageOcrContentFingerprint`, seeded from the checkpoint, all in ONE manifest publication) iff ALL of:
  - (a) the same run continues to a later phase for this page and the coordinator will retain or deterministically re-acquire the lease;
  - (b) no interactive owner is waiting or was denied for this page since the sprint pass began (observable via the existing lease-deferral record, `BatchLaneWorkers.kt:842-843`, and lease waiters, `PageStageLeaseTable.kt:199-215`);
  - (c) the successor candidate's fingerprint equals the checkpoint content fingerprint (so the very first successor write validates against the checkpoint, and a fingerprint drift can never silently inherit it).
- REBASE never mutates the closed generation's record; it publishes G′ records exactly as `openCandidate` does today (**VERIFIED** record shape :707-723) plus the CANCELLED record for G, in one M2 publication. If any sub-step of the combined publication is rejected, the entire transaction rejects and the manifest stays on the prior state (TX-11).

**Why the rule is what it is (VERIFIED failure modes it prevents):**
- Leaving a BATCH candidate open at lease release makes the next Manual/Auto writer fail: `persistLiveCandidate` rejects on provenance mismatch (:373-377) and `openCandidate` refuses to open while a foreign-origin/differing-fingerprint candidate is active (:700-703). The reader's only escape would be `cancelPageStageWork`-style cancellation, whose `cancelCandidate` strips candidate-owned payloads and lets retention delete them (:771-814) — destroying the OCR reuse this phase exists to create.
- Closing without a checkpoint pointer first would also destroy reuse (candidate snapshot sidecar becomes unreachable → retention deletes it); hence ordering TX-07.

### T924-TX-03.1 — No-active-candidate branch (ADOPT-COMMITTED; added per stage0-review F-1)

Scenario N3-04→N3-05 (`reference-scenarios.md`: reader-origin commit, then Batch
checkpoint adoption) requires checkpointing a page whose candidate was already
promoted/cleared, leaving **no active candidate**. Normative branch:

- **Preconditions:** Batch holds the page lease (inputs 1-3, 5-8 per TX-02);
  `candidateGenerationId = null` and no active candidate exists in the manifest
  for the page; a **committed bundle** exists whose OCR-stage content
  fingerprint equals input 9 (`PageOcrContentFingerprint`).
- **Action:** publish the `PageOcrCheckpoint` sidecar + pointer **only** —
  there is no candidate to close or rebase; the committed display pointer is
  untouched by construction (TX-07).
- **Rejections:** a committed bundle whose OCR content fingerprint does not
  match input 9 → REJECTED (content drift: the durable OCR differs from what
  the reader committed under; Batch must re-plan the page, not adopt it); any
  active candidate present (of any origin) → this branch does not apply, use
  TX-03.
- **Crash equivalence:** identical to boundaries B1-B3 of TX-11 (orphan
  sidecar before pointer; installed checkpoint after); no
  candidate-transition boundary exists.
- **Scenario alignment:** N3-05's `[open: OD-10]` tag is resolved by this
  clause; DB-02 acceptance carries this amendment.

### T924-TX-04 — Snapshot ownership

After a committed `checkpointOcr`, the immutable OCR page snapshot (blocks + geometry + durable mask boxes + readiness state, i.e. the durable `PageTranslation` payload — **VERIFIED** mask durability vs transient `allTextDetections`, `model/PageTranslation.kt:89-95, 165-171`) is owned by the **manifest-reachable `PageOcrCheckpoint` pointer**, not by any candidate generation. No later `cancelCandidate` may remove it (it is not candidate-owned; `cancelCandidate` strips only records whose `generationId` matches the cancelled generation — VERIFIED :771-792). Candidate snapshot files created during the OCR merge become unreachable after CLOSE and are swept by retention — that is correct and intended once the checkpoint exists.

### T924-TX-05 — Candidate lifecycle transitions (normative state machine)

```
(open)        ACTIVE   — openCandidate (M2; idempotent same-origin+same-fingerprint)
(persist)     ACTIVE   — persistLiveCandidate (sidecar + pageVersion+1, M2)
(checkpoint)  ACTIVE → — checkpointOcr:
                 CLOSE   → CANCELLED record for G; candidate=null; checkpoint pointer installed
                 REBASE  → CANCELLED record for G + ACTIVE record for G′ (same publication)
(promote)     ACTIVE → COMMITTED — promoteLiveCandidate (committed bundle + previousCommitted;
                                         candidate=null; pageVersion+1)   [translation/render phase]
(adopt)       (none)  → — checkpointOcr TX-03.1: no active candidate + committed bundle
                         fingerprint match → checkpoint pointer installed; candidate stays
                         null; committed display untouched (reader-committed page)
(cancel)      ACTIVE → CANCELLED  — cancelCandidate (strips candidate-owned records; retention)
```

Invariants: `activeCandidateGenerationIds` contains at most one live generation per page (existing set maintenance — VERIFIED :736-739, :575, :806); a page never has both an active candidate and a just-installed checkpoint claiming candidate ownership; promotion remains the only path that writes `committed`.

### T924-TX-06 — Ordering constraint (mandatory)

**validate → publish snapshot → install checkpoint pointer + close/rebase candidate → THEN release lease.** Rationale (each step VERIFIED above):

1. Releasing the lease alone leaves a BATCH-origin candidate active; the first Manual/Auto touch of that page is then rejected for provenance mismatch (:373-377) or cannot open (:700-703), and the reader's fallback cancellation destroys the reusable payload (TX-03). This is precisely design §5: "Releasing today's lease alone is insufficient."
2. The lease token is the fencing anchor for every guarded write including the checkpoint itself (:855-858); the transaction must complete under the token it validates.
3. Bitmap release follows checkpoint success (memory rule, design §5): the durable mask/checkpoint makes the decoded bitmap disposable; before that point the bitmap is the only copy of detection output feeding `allTextDetections`-derived work.
4. Lease release is the point where a reader origin may lawfully start a fresh writer (`tryAcquirePageStageLease` denies while a foreign lease exists — VERIFIED :93-98); it must therefore be the LAST step.

### T924-TX-07 — Prior committed display preservation (normative)

`checkpointOcr` MUST NOT change the committed display pointer. **VERIFIED mechanism to reuse:** committed display changes only through `promoteDisplayIfReadyLocked`, which no-ops unless the page reached `hasRenderedResult || isTextlessTerminal` (:1888-1911) — an OCR-only payload can satisfy neither, so the existing promotion gate already enforces this. The checkpoint additionally preserves the prior committed bundle's display base and the retired-cleaned-image retention rules (:1893-1901, `mayDeleteCleanedImage` :1988-1990): no cleaned image referenced by the committed or retired state may be deleted by checkpoint-side retention. Textless-terminal *promotion* remains a later-phase behavior (existing "batch textless translation commit", `BatchLaneWorkers.kt:1404`), not a checkpoint behavior.

### T924-TX-08 — Failure reporting (normative)

On any compare rejection or publication failure: the manifest stays on the prior state (every store transaction returns `Rejected` without publishing — VERIFIED rejection paths return before `publishManifestInternal`, e.g. :371, :412-413, :580-581), and **Batch retains ownership** (lease NOT released) long enough to report:
1. Record the durable failure in the same publication discipline as existing failures (`recordDurableFailure` returns `NotStored` on publication failure and keeps the prior manifest — VERIFIED :244-261; test `ChapterArtifactStoreTest.kt:639`).
2. Surface the typed failure through the existing `BatchPersistenceRejectedException` flow (thrown by `analyzePage` on OCR-persist rejection — VERIFIED `SinglePageOnnxPhase.kt:943-951`) and tracker `markOcrFailed`.
3. Only then apply terminal handling: today's teardown distinguishes pages with durable failures (release lease) from others (cancel candidate + release) under `NonCancellable` (**VERIFIED** `BatchChapterTranslator.kt:769-780`); the checkpoint contract inherits this: a checkpoint-failed page ends with its lease released and its BATCH candidate closed, its OCR retry state durable.
4. A bounded timeout applies: ownership may not be held indefinitely on failure (existing stage budget norm; exact value is a measured constant, final-target §7).

### T924-TX-09 — New store API surface (PROPOSAL, minimal)

Implement as one `ChapterArtifactStore` transaction (M2-synchronized) + one `ChapterTranslationStore` façade method, reusing `openCandidate`/`cancelCandidate` record bodies verbatim inside the combined publication:
- `checkpointOcr(manifest, pageKey, generationId, expectedPageVersion, expectedDependencyFingerprint, checkpoint: PageOcrCheckpoint, mode: CLOSE | REBASE, successorFingerprint?)` → `TransactionOutcome`.
- No changes to `PageStageLeaseTable`; lease release stays a separate, strictly-later caller step (TX-06).

### T924-TX-10 — Reuse consumption rule

A fresh candidate of ANY origin opened after a checkpoint MUST seed its working state from the checkpoint when the checkpoint's content fingerprint matches current source/OCR evidence. Planner evidence inputs must read checkpoints (`PageWorkPlanner`/`BatchResumePlanner` extension, design §2 row 4); absence of a checkpoint falls back to today's stage-evidence reuse.

### T924-TX-11 — Crash-point × expected-manifest-state table (every atomicity boundary)

Assume a checkpoint of page P with BATCH candidate G, checkpoint sidecar file C, during OCR_PREFLIGHT. "Store page state" = in-memory live map (lost on process death). Leases are in-memory (always lost on process death).

| Boundary | Crash at | Durable manifest/store state after restart | Recovery action | Reuse outcome |
|---|---|---|---|---|
| B0 | Before any durable write of the checkpoint (during/after OCR merge `persistLiveCandidate` commit) | Manifest: candidate G ACTIVE holding the post-OCR page snapshot sidecar (the ordinary pre-T924 state); committed display untouched | `recoverInterruptedStages` flips any RUNNING stage records → FAILED_RETRYABLE (:846-892); resume re-enters OCR_PREFLIGHT; page is NOT checkpointed → re-run OCR for P (or, RECOMMENDATION, accept G's validated candidate snapshot as the page's OCR state if fingerprints match — planner decision per T924-FP-*) | Work may repeat for P; no corruption |
| B1 | After OCR snapshot sidecar write, before checkpoint pointer | Manifest unchanged (B0 state) + orphan sidecar C | Orphan C swept by retention (ST-01.8c; orphan-sweep test `ChapterArtifactStoreTest.kt:778`); resume as B0 | Same as B0 |
| B2 | During the combined pointer publication (M1 temp written; manifest rename not yet done) | Manifest unchanged (B0); C + temp orphaned; `.bak` rotation guarantees prior manifest recoverable (**VERIFIED** publish sequence :227-246) | As B0; orphans swept | Same as B0 |
| B3 | Pointer publication committed (checkpoint installed + candidate closed/rebase published) but before lease release / bitmap release | Manifest: `PageOcrCheckpoint` pointer installed; candidate G CLOSED (or successor G′ ACTIVE with checkpoint fingerprint); committed display untouched; pageVersion advanced | Process death: lease table empty (in-memory); stage records for P are checkpoint-complete; resume skips P's OCR (checkpoint fingerprint matches) and continues the sprint at P+1 | Full reuse; no orphan candidate ownership |
| B4 | After lease release, before next page starts | Same as B3 (lease release writes nothing durable) | Resume continues at the first incomplete page | Full reuse |
| BX | Any compare rejection mid-transaction (stale identity) | Manifest on prior state (B0 shape); no partial publication | Failure reporting per TX-08; Batch retains lease until reported | No corruption; stale writer fenced |

**Property (gate requirement):** for every boundary, the manifest is never observed with (i) a checkpoint pointer AND an active candidate owning the same payload, (ii) a committed display change, or (iii) a pointer at a missing file. This is the Stage-3 exit injection matrix (audit Stage 3 exit: "injected failure at every checkpoint publication boundary preserves the prior manifest").

### T924-TX-12 — Required fault-injection tests (build on existing idioms)

- `FakeChapterDocumentIo` seams (**VERIFIED**, `app/src/test/java/eu/kanade/translation/artifact/FakeChapterDocumentIo.kt`): `failWrites`, `writeNamesToFail`, `renamesToFail`, `ownedRenamesToFail`, `deleteNamesToFail`, `beforeRenameAttempt` — inject failure at each of B0-B2 for C, the generation records, and the manifest.
- Store-level fencing: extend `ChapterTranslationStorePhase3Test` idioms — "ocr result with a released lease is rejected through the stage merge" (:137), "reader and batch leases serialize ownership and fence late writes" (:95) — to `checkpointOcr`: released lease, changed token, changed candidate generation, changed dependency fingerprint each reject with prior manifest intact.
- Origin neutrality: fresh MANUAL/AUTO/BATCH candidate opens from a checkpoint and persists ("Fresh Manual/Auto/Batch candidate succeeds; stale writer fails; display preserved" — audit Stage-3 gate; origin-matrix basis `D1OriginPriorityTest.kt:45`, interleaving `D2ManualBatchInterleavingTest.kt:37,116`).
- Committed display preservation: "candidate and retryable failure publish atomically without replacing committed display" idiom (`ChapterArtifactStoreTest.kt:1040`) applied to every checkpoint boundary.

---

## 3. Downstream transaction clauses

### T924-TX-20 — Translation commit CAS preconditions

A Batch translation commit per page MUST carry (existing fields **VERIFIED** on `TranslationStagePatch`, `TranslationStageContracts.kt:88-101`, enforced by `mergeTranslationLocked` :946-1005):

- `generation`, `expectedPageVersion`, `expectedLeaseToken`, `expectedCandidateGenerationId`, `expectedDependencyFingerprint`, `expectedArtifactPageVersion` (full M4 ladder);
- per-block: `expectedOcrFingerprint`, `expectedSourceText`, `expectedTranslation` (repair), `expectedUserEditedAt` — a user edit timestamp change rejects the block (:978-979), keeping user edits authoritative;

**plus (NEW, mandatory for T924):**

- `profileContentFingerprint` — the frozen profile content fingerprint the request was built from; a commit arriving under a different fingerprint than the currently frozen profile is rejected (stale-profile protection; final-target §2 "Translation commits must carry the profile content fingerprint and source-block identity");
- envelope/plan identity — `envelopePlanFingerprint` + stable block IDs (`pN_bN`) matching the dispatch; ownership/coverage validated by the strict parser before commit (existing `ContextualResponseParser` discipline, design §9.1).

Rejected commits never advance `BatchContextFrontier` (page atomicity + gap-free context invariants).

### T924-TX-21 — Envelope dispatch revalidation (before each envelope)

Before dispatching any planned envelope, the coordinator MUST, per affected page:

1. **Reacquire the lease** (`tryAcquirePageStageLease`, existing semantics: BATCH attaches/waits behind MANUAL, never preempts — VERIFIED `PageStageLeaseTable.kt:79-98`).
2. **Live-revalidate store state**: take a fresh `PageSnapshot` and compare against the envelope's plan-time inputs: source identity, checkpoint/OCR content fingerprint, page version, candidate generation, dependency fingerprint, profile content fingerprint.
3. **Skip user-edited / manual-completed pages**: `userEditedAt != null` blocks and pages whose committed bundle is authoritative (`hasRenderedResult`, `hasManualEdits` on the committed bundle — VERIFIED field :522) are excluded; the frozen profile is not rewritten from reader results (design §11).
4. **Deterministic suffix re-plan**: if any page changed, re-plan the remaining suffix with the same pure planner (same inputs → same plan; design §5 "re-plan from fresh store state after OCR/profile freeze", §11). Re-planning never changes committed history; it only affects not-yet-dispatched envelopes.

### T924-TX-22 — Profile freeze atomic publication

As specified in ST-10: profile sidecar M1-published, then `{profilePointer, profileContentFingerprint}` moved in one M2 manifest transaction; the profile file is immutable; a superseding profile = new file + new pointer; resume treats pointer-without-valid-sidecar as unfrozen. No canonical mutation after freeze within a run (design §3.1.9); corrections are separate candidates (design §4.4).

### T924-TX-23 — Layout publication CAS preconditions (Stage-7 preview; from final-target §2, kept verbatim in force)

A persisted-layout commit MUST compare-and-swap on ALL of (mirror of `RenderStagePatch` + `mergeRenderLocked` checks — VERIFIED :1045-1093 and `BatchRenderJoin.kt:214-235`):

1. candidate generation and page version (`candidateGenerationId`, `pageVersion`, `artifactPageVersion`);
2. translation content fingerprint and stable block IDs (per-block identity, as `mergeRenderLocked` checks `stableFingerprint()` per block :1073-1076);
3. OCR geometry/mask fingerprint and page dimensions (`inpaintMaskFingerprint`/OCR block fingerprints; `InpaintStagePatch.expectedMaskFingerprint` precedent :108; dimensions from OCR result :897-900);
4. color/style preparation fingerprint where the planned result consumes it;
5. cleaned-image name and inpaint revision **only where the planned result actually depends on them** (`expectedCleanedImageName`/`expectedInpaintRevision` precedent :1061-1064);
6. layout policy + planner/font-metrics version (final-target §1.2: font asset identity, typeface/style, measurement flags, planner version, platform compatibility value).

If a user edits a block, a reader/manual run replaces the candidate, settings change, or inpaint/color preparation advances while layout runs, the stale layout commit MUST be rejected (final-target §2). Hydration keeps the existing bind-generation stale-delivery defense (final-target §2 "Reader hydration race"). Full layout DTO/fingerprint ownership: `T924-SC-*`/`T924-FP-*`; this clause fixes only the CAS preconditions.

---

## 4. OOM/cancellation semantics at state-machine level (T924-ST-20..)

- **ST-20 (two-recognition-OOM diagnostic failure): VERIFIED.** `analyzePage` counts consecutive recognition OOMs; the second consecutive OOM (`consecutiveOomCount >= 2`) sets the durable diagnostic message "ONNX recognition failed due to memory pressure. Retry after memory recovers." which is persisted through the OCR merge's `errorMessage` (`SinglePageOnnxPhase.kt:852, 871-889, 928-942`). The counter resets on success (:856). Under the new state machine this failure is a per-page durable failure inside OCR_PREFLIGHT.
- **ST-21 (repeated-failure abort):** the outer Batch policy may abort the run after repeated failures; abort keys = pages not durably terminal (**VERIFIED** `remainingAbortKeys` doc, `BatchChapterTranslator.kt:870-879`). At state-machine level: OCR_PREFLIGHT stops before paid analysis on any unresolved OCR/persistence failure (ST-06 Terminal); the run record remains resumable with the failing page in a retryable durable state.
- **ST-22 (cancellation between pages):** cancellation is checked between pages; the current bitmap and lease are released in `finally`; durable OCR is flushed; completed preflight pages are preserved. **VERIFIED basis:** bitmap recycle is the caller's duty after `analyzePage` (`SinglePageOnnxPhase.kt:828-830`); teardown `finally` releases/cancels per page, releases all BATCH leases, flushes under `NonCancellable`, and reconciles retention (`BatchChapterTranslator.kt:769-795`); decode/inpaint releases in `finally` (`BatchLaneWorkers.kt:1151-1153`).
- **ST-23 (process death loses only the active page):** the decoded bitmap and the in-flight native call are process-local; everything before the active page's checkpoint is durable (ST-06 crash column). Startup recovery flips RUNNING stage records to retryable (**VERIFIED** :846-892) and resumes the first incomplete phase (ST-01.3) — for OCR_PREFLIGHT that means re-running only the active page (its checkpoint never committed).
- **ST-24 (RUNNING artifacts recovered as retryable):** startup marks RUNNING artifacts retryable — **VERIFIED** mechanism and test (`ChapterArtifactStoreTest.kt:1109` "process restart turns an interrupted artifact stage into a durable retryable failure"); committed pointers are never touched by recovery (:841-843 doc + code). The run-record's `activePhase` pointer is the phase-level complement of this page-level rule.

---

## 5. Corrupt/partial sidecar behavior and version compatibility (T924-ST-30..)

- **ST-30 (corrupt/partial sidecar, state-machine level):** every sidecar read goes through `readValidated`, which recovers a valid `.bak` over a missing/corrupt primary and quarantines the corrupt primary as `.corrupt` rather than deleting it (**VERIFIED** `ChapterDocumentIo.kt:258-301`; tests `AtomicChapterDocumentsTest.kt:91-166`). At state-machine level: a sidecar that cannot be loaded or validated makes the artifact it represents **absent** (resume re-executes that phase unit), never partially-trusted; the run record pointer's target is validated before the phase it unlocks is reused (ST-10 resume).
- **ST-31 (unknown/future version):** schema/version rules are owned by `T924-SC-*`; this contract binds the behavior at state-machine level to the existing idiom: a **future-schema** primary or backup makes the chapter read-only-preserving — manifest publication is refused while a future-schema document is present (**VERIFIED** `publishManifestInternal` :995-1013, `futureBackupPresent` :991-993; tests :574-638), and reads return future documents read-only without overwrite (attempt-ledger precedent :227-231). Run/analysis/profile sidecars MUST adopt the same rule per `T924-SC-*`: never overwrite, never translate, surface a typed "future version" state.
- **ST-32 (forward compatibility — older app reads newer data):** the JSON pipeline uses `ignoreUnknownKeys = true` (**VERIFIED** `ChapterDocumentIo.kt:220-223`), so additive fields from a newer app are tolerated by an older app's parse; combined with the future-schema guard, an older app Downgrading onto a future-schema document preserves it read-only. New sidecars MUST declare explicit `schemaVersion` + `kind` discriminators (idiom: `ChapterGlossary` :290-300, `ChapterAttemptLedgerDocument` :331-347) and MUST be validated with `schemaVersion <= SCHEMA_VERSION` on read (:229).
- **ST-33 (partial sidecar):** M1's write-then-re-read-then-validate makes a torn write invisible (a partial temp fails validation and is deleted, :230-235); a partial *primary* can exist only below the storage layer's guarantees, where `.bak` recovery applies (ST-30).
- **ST-34 (run-record across upgrades):** the run record MUST tolerate: (a) older app + newer run record → read-only preservation (ST-31); (b) newer app + older run record → additive migration by the schema owner's rules (`T924-SC-*`), never destructive reinterpretation; (c) same-version load → resume. Whether a run record written by a *different* app version may be resumed or must be closed-and-replanned is a `T924-SC-*` compatibility-boundary decision (flagged §6 Q5).

---

## 6. Conflicts / open questions

**C1. "Origin-neutral candidate" is unrepresentable in the current schema (contract-shaping constraint, needs schema-agent alignment).** `ArtifactOrigin` has exactly two durable values (**VERIFIED** :38-41), and `openCandidate`/`persistLiveCandidate` key on it. T924-TX-03 therefore defines REBASE as close+successor under a concrete origin, and keeps neutrality in the *checkpoint sidecar*, not the candidate. If the schema agent prefers a third origin value (e.g. `CHECKPOINT`), TX-03's REBASE branch can be simplified — but that is a schema change and MUST be coordinated; this contract does not require it.

**C2. Dependency-fingerprint grace clause.** The store-level ladder disarms the dependency-fingerprint check when no candidate exists (**VERIFIED** :587-590). `checkpointOcr` closes this by making `candidateGenerationId` mandatory (TX-02.1), but implementers must not copy the `patchPage` grace pattern into the checkpoint path. Documented here to prevent silent regression of the fail-closed direction.

**C3. Today's OCR worker holds the lease until a terminal boundary, not per page.** **VERIFIED:** `tryRender` releases the lease only at terminal/textless/render-done/failed boundaries (`BatchRenderJoin.kt:117-148`), and teardown releases the rest (:769-780); the OCR stage itself returns an `OcrReadyPageRef` retaining the lease and decoded handoff (:1016-1028). The T924 target (release after checkpoint, re-decode for inpaint — **VERIFIED** re-decode path `BatchLaneWorkers.kt:1096-1153`) is a coordinator change, not a store change. No conflict with this contract; flagged so Stage 3 does not assume the current release points.

**C4. Sidecar retention policy for superseded generations (Director-level policy input).** The retention sweep is strictly reachability-based (**VERIFIED** :938-951). How many superseded profiles/run records/corpus manifests to keep reachable (disk cost vs audit/resume value) is a policy constant, not a correctness rule. Recommendation: retain exactly the current pointer chain plus one previous generation per pointer (the `previousCommitted` precedent, `PageArtifactRecord.previousCommitted` :217-218), sweep the rest; enumerate as a measured constant per final-target §7.

**C5. Run-record resume across app versions (see ST-34c):** resume vs. close-and-replan for a run record written by a different app version — schema-compatibility boundary; recommend resume only when the record's `schemaVersion` is current and all referenced fingerprints validate; otherwise close the run durably and require a fresh run. Needs `T924-SC-*` + Director acceptance.

**C6. Detection/OCR are fused in one recognition pass; no separate detection checkpoint exists.** **VERIFIED:** `OcrStagePatch` fuses detection+OCR ("The live pipeline fuses detection and OCR into one recognition pass", :51-57) and `analyzePage` writes both fingerprints together (:913-915). If the analysis plan ever needs detection-only artifacts, that is new schema work; this contract does not create it.

**C7. Concurrent runs per chapter:** one active Batch run per chapter is assumed (single coordinator shell per chapter; queue admission). I did not verify a hard single-run registry guard for the *new* coordinator. Treat "two concurrent Batch runs on one chapter" as an unrepresented state until the lifecycle owner confirms; the M2 whole-manifest CAS would reject interleaved publications, but phase-pointer semantics under two writers are undefined. Flag for the lifecycle/queue work package.

**C8. Director-level policies surfaced (not decided here):** (a) whether settings "apply now" must also preserve the frozen profile for reuse when only translation-unrelated settings changed (invalidation matrix owner `T924-FP-*`/`T924-R*` decides reuse; the stop+new-generation mechanism is fixed by ST-15); (b) C4 retention depth; (c) whether checkpoint adoption by Manual/Auto should be silent or user-visible in reuse provenance (UX).

---

## Verification appendix — primary evidence index

All claims marked VERIFIED above were read at HEAD `adbe643`:

- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` — `MutationAdmission` :66-82; `PageSnapshot` :175-188; `PatchPrecondition` :190-202; `admitMutationLocked` :465-501; `beginGeneration` :503-507; lease stubs :516-541; `patchPage` :548-627; `updatePageGuarded` :636-666; `persistDurableStageFailure` :674+; `applyStagePatch` :790-812; `mergeOcrLocked` :831-929; `mergeTranslationLocked` :946-1005; `mergeInpaintLocked` :1007-1043; `mergeRenderLocked` :1045-1093; `stageIdentityRejection` :1095-1128; `pageWriteRejection` :1167-1194; `ocrIdentityRejection` :1196-1205; `ownedPage` :1581-1587; `publishLocked` :1589-1610; `persistArtifactMutationLocked` :1625-1810; `sourceIdentity` :1870-1878; `promoteDisplayIfReadyLocked` :1888-1911; display projection :1913-1981; cleaned-image retention :1988-2018; `demoteCommittedDisplay` :2025-2038; `snapshotLocked` :2106-2120; `flush` :2236.
- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt` — `recordDurableFailure` :244-261; transaction comment :263-272; `TransactionOutcome` :275-283; `persistLiveCandidate` :351-415; `persistLiveCandidateAndFailure` :423-445; `promoteLiveCandidate` :453-583; `cancelLiveCandidate` :586-592; `demoteLivePage` :596-637; `openCandidate` :668-748; `cancelCandidate` :756-815; `candidateWriteRejection` :817-839; `recoverInterruptedStages` :846-892; `staleManifestRejection` :897-905; retention :938-951; future-schema guards :991-1013.
- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactManifest.kt` — manifest :16-61 (SCHEMA_VERSION=2 :59); `PageArtifactRecord` :201-234; `CommittedBundleMetadata` :242-256; `CandidateGenerationMetadata` :260-274; attempt ledger :309-347 (cap :345); `GenerationRecord` :354-361.
- `app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt` — `AtomicChapterDocuments` :215-301 (publish :227-246; readValidated :258-273; quarantine :281-301; Json config :220-223).
- `app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt` — lease table :26-222 (priority matrix :79-98; release :139-150; cancel :153-170; releaseAll :173-185; waiters :199-215).
- `app/src/main/java/eu/kanade/translation/TranslationStageContracts.kt` — `PageWriteOrigin` :27-31; `toArtifactOrigin` :38-41; stage patches :58-146; `PageStageLease` :190-203; OCR fingerprints :213-254.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchWriteGate.kt` — `BatchWriteIdentity` :36-43; `guardedBatchUpdate` :85-113; `persistAiFailure` :136-199.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt` — OCR lease/identity :824-855; decode failure writes :938-967; handoff :1016-1028; inpaint re-decode :1096-1153; translate identity gate :1292-1304.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt` — teardown :769-795; `remainingAbortKeys` :870-879; stranded-page writes :747-760.
- `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt` — `analyzePage` :837-953 (pre-native snapshot :851; OOM two-strike :871-889; merge :928-951); bitmap lifecycle :828-830.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchRenderJoin.kt` — terminal lease releases :105-148; render CAS patch :196-235.
- `app/src/main/java/eu/kanade/translation/model/PageTranslation.kt` — durable mask vs transient detections :89-95, 165-171; `CURRENT_INPAINT_REVISION` :183.
- Tests: `artifact/FakeChapterDocumentIo.kt` (fault seams); `artifact/ChapterArtifactStoreTest.kt` (:639, :778, :1040, :1109, :1138, :574-638); `ChapterTranslationStorePhase3Test.kt` (:95, :137, :178, :221); `coexistence/D1OriginPriorityTest.kt` (:45); `coexistence/D2ManualBatchInterleavingTest.kt` (:37, :116); `TranslationManagerStartupReconciliationTest.kt` (:174); `artifact/AtomicChapterDocumentsTest.kt` (:17-168).

Items carried from design docs without independent re-read are labeled STRONG INFERENCE and cite the design section; they are few and none is load-bearing for the transaction clauses.
