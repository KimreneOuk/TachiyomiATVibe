# T924 Stage 0 — normative end-to-end reference scenarios

Date: 2026-09-05 · Baseline: `adbe643` · Work item: Stage 0 item 7
(delivery-readiness audit; plan-completeness audit finding 7)
Status: Stage 0 specification only. No production code, no builds.

Three executable storyboards for integration tests. Every step carries
machine-checkable expected-state assertions; the Assertions index at the end
maps each invariant to the step IDs that prove it. Integration tests assert
exactly the documented checkpoints.

Authority and precedence: `../design/chapter-profile-batch-design.md`
(§3 state machine, §4.2 checkpoint transaction, §5 memory model, §8 planner,
§9 malformed strategy, §10 resume table, §11 coexistence, §12 pacing,
§13 progress) as narrowed by `../design/final-target-migration.md` §2 and the
README constraints. Terminology follows those documents. Where the design
leaves behavior open, this document follows the §9 default and tags the step
`[policy-dependent: T924-DR-A]` or `[open: OD-n]` (README open decision n)
instead of inventing behavior.

Namespaces: the artifact names used here (`ChapterRunRecord`,
`PageOcrCheckpoint`, `AnalysisChunkResult`, `ChapterTranslationProfile`,
`EnvelopePlan`) are the design §4.2 roles. Exact serialized fields, canonical
serialization and versioning are owned by **T924-SC-***; the durable
phase-transition/recovery table by **T924-ST-***; the provider request/response
contract by **T924-AP-***. This document references roles and states, not wire
formats; where those specs name fields more precisely, they prevail.
Fingerprint alias values (`D1`, `C1`, `P1`, `L1`, …) are fixture constants for
test determinism, not product constants.

Out of scope: persisted `LAYOUT_PREPARE` (final-target-migration Stage 7 —
reader-side async planning remains the display path); non-AI Batch lanes;
changes to Manual/Auto latency behavior.

---

## 1. Assertion vocabulary (legend)

### 1.1 Durable artifact atoms

| Atom | Meaning |
|---|---|
| `runRecord.present` / `runRecord.state=<S>` | `ChapterRunRecord` sidecar exists; `state` is the **last phase whose completion is durably persisted** (§3.1). The executing phase is implied as its successor. `S` ∈ {`ADMISSION`, `RUN_SNAPSHOT`, `SOURCE_VALIDATION`, `OCR_PLAN`, `OCR_PREFLIGHT`, `ANALYSIS_PLAN`, `ANALYSIS_CHUNKS`, `PROFILE_RECONCILE`, `PROFILE_FROZEN`, `ENVELOPE_PLAN`, `TRANSLATE`, `NATIVE_RENDER`, `FINALIZE`}. |
| `run.cfg=<Fcfg>` | `frozenRunConfigFingerprint` present (§3.1 state 2). Settings changes apply next run only. |
| `src.digest=<D>` / absent | `orderedSourceDigest` + trusted natural page identities/count present in run record (§3.1 state 3). |
| `ocrPlan.present` | Per-page OCR reuse decisions persisted from source/stage fingerprints (§3.1 state 4). |
| `checkpoint[pNNN]=published` / `absent` | `PageOcrCheckpoint` sidecar exists **and** its manifest pointer is atomically installed (§4.2). Publication happens while the lease is still held; only after success may the lease be released. |
| `ocr[pNNN]=READY\|TEXTLESS\|RETRYABLE\|FAILED\|absent` | Page OCR stage state. READY and TEXTLESS are both valid OCR completion (§3.1 state 5). |
| `corpus.fp=<C>` / absent | `OcrCorpusFingerprint` over ordered page content fingerprints (§4.4). |
| `chunk[cNN]=valid` / absent | Validated, persisted `AnalysisChunkResult` (§4.2). |
| `profile.fp=<P>` / absent | Frozen `ChapterTranslationProfile` content fingerprint with manifest `profile.pointer` installed; immutable for the run (§3.1 state 9, §4.4). |
| `plan.fp=<L>` / absent | `EnvelopePlan` fingerprint of the current plan generation; `plan.envK={pMMM..pNNN}` = whole-page membership of envelope K (§8). |
| `tx[pNNN]=ABSENT\|CANDIDATE\|COMPLETE_CANDIDATE\|COMMITTED\|USER` | Translation record state. `ABSENT` no target; `CANDIDATE` non-displayed partial; `COMPLETE_CANDIDATE` validated whole-page target retained non-display from a MISSING_ONLY parent (§9.2) `[policy-dependent: T924-DR-A]`; `COMMITTED` durable machine translation (display-eligible); `USER` user-edited or manual-committed, authoritative (§11). |
| `inpaint[pNNN]=DONE\|PENDING` | Durable cleaned artifact exists or not. |
| `provenance[pNNN]=<callId>` | Producing provider call of a committed target; used to prove no page is paid for twice. |

### 1.2 Runtime state atoms

| Atom | Meaning |
|---|---|
| `lease[pNNN]=BATCH\|READER\|none` ; `leases.batch=<n>` | Lease owner per page; number of leases Batch holds (≤ envelope page count, ≤ 8). |
| `display[pNNN]=NONE\|COMMITTED` | Visible reader state. `COMMITTED` = translated overlay live (requires render join: cleaned image + color/render for that page). Textless pages are permanently `NONE` yet count as complete. `CANDIDATE`/partial targets are never visible. |
| `complete(pNNN)` ⇔ `ocr(pNNN) ∈ {READY,TEXTLESS}` ∧ (`tx(pNNN) ∈ {COMMITTED,USER}` ∨ `ocr(pNNN)=TEXTLESS`) | Frontier-eligibility predicate; origin-neutral. |
| `frontier=<n>` | `BatchContextFrontier` position: pages `p001..p<n>` are contiguous complete; page `p<n+1>` fences rolling context. PARTIAL/malformed candidates never advance it (§9.7). |
| `calls=<n> (Δ+k)` | Cumulative paid provider requests across **all process lifetimes** of the scenario (analysis + translation + reader Manual/Auto + retry-tree attempts). OCR is local/native and never counted. |
| `window.batch=<k>/15` | Rolling-60s count of Batch-origined requests (analysis **and** translation share one 15-RPM sub-limit, §11/§12). Standing invariant: never >15. |
| `bitmaps=<n>` | Decoded page bitmaps resident in memory at step end (peak inside the step noted as `peak k`). Standing invariant: n ≤ 1. |
| `envelope.inFlight=0\|1` | Batch provider envelopes in flight. Standing invariant: ≤ 1. |
| `tree.attempts=k/8` ; `tree.depth=d/3` ; `tree.leaves=l/4` | Durable root retry-budget ledger for the current attempt tree (§9.5). One root budget covers parent and every child. |
| `notif="<§13 line>"` | Foreground progress line (§13). |

### 1.3 Negative assertion (standing)

Unless a step explicitly lists a later-phase artifact, every artifact belonging
to a phase after `runRecord.state` is absent at that step (e.g., before
`PROFILE_FROZEN`: `profile.fp=absent`, `plan.fp=absent`, all `tx=ABSENT`).

### 1.4 Restart column

`restart →` names the resume action per the §10 table if the process died
immediately after the step; `loss:` names what is lost. Startup resumes the
**first incomplete phase**, never a phase inferred from page statuses (§3.1).

### 1.5 Shared fixture constants (all scenarios)

- Chapter: 200 pages, natural order `p001..p200`; finalized local directory.
- Textless set (10): `p077, p113, p131, p148, p160, p171, p182, p190, p195, p199`.
- Text pages: 190; translatable blocks: 800 (~4.2/page).
- Block counts used by split math: `p001=8, p002=9, p003=14, p004=12, p005=7, p006=7, p007=12, p008=10, p009=4, p150=8, p151=8`.
- Structural caps: 32 blocks / 8 contributing pages (§8 starting experiment)
  `[open: OD-3]`. Analysis: 10 extraction chunks + 2 master reconciliation calls.
- Retry ceilings: 8 attempts, split depth 3, ≤ 4 leaf groups (§9.5).
- `p002_b2` denotes block 2 of page 2 (canonical `pN_bN` IDs).

---

## 2. Scenario N1 — normal 200-page chapter, end to end

World: first run of the chapter, contextual-AI-Batch feature flag on, no prior
artifacts, no reader activity, no provider failures. 26 translation envelopes
(≥ ceil(800/32)=25; +1 from a scene boundary), 12 analysis calls, 0 retries.
Concrete plan prefix: `E1={p001,p002,p003}` (31 blk), `E2={p004,p005}` (23 blk),
`E3={p006..p011}` (32 blk), `E4={p012..p018}` (30 blk); `E5..E26` deterministic,
textless pages never members; `E26` ends at `p200`.

| ID | Actor / action | Durable artifacts after step | Lease | Display / tx | Frontier | Calls | BM | Restart here |
|---|---|---|---|---|---|---|---|---|
| T924-N1-01 | User enqueues chapter for contextual AI Batch. ADMISSION: verified finalized local directory (interrupted `.tmp` downloads would fail here, §3.1.1). | `runRecord.present{state=ADMISSION, runId=R1}`; `run.cfg` absent; `checkpoint[*]`, `chunk[*]`, `profile.fp`, `plan.fp` all absent. | none | all `display=NONE`, `tx=ABSENT` | 0 | 0 (Δ+0) | 0 | restart → queue restore without auto-start; user re-enqueues. loss: nothing (no durable state beyond runId). |
| T924-N1-02 | RUN_SNAPSHOT: freeze full run configuration (languages, OCR model, reading order, inpaint mode, provider/model/settings, protocol versions, analysis + envelope policy, authority fingerprints); publish run record. `notif="Preparing chapter"`. | `runRecord{state=RUN_SNAPSHOT}`; `run.cfg=Fcfg1`; all later-phase artifacts absent. | none | unchanged | 0 | 0 | 0 | restart → resume executing SOURCE_VALIDATION; `run.cfg` recomputed and compared. loss: none. |
| T924-N1-03 | SOURCE_VALIDATION: bounded-IO hashing of 200 page sources (no per-page coroutine fan-out, §3.1.3). | `runRecord{state=SOURCE_VALIDATION, src.digest=D1, pageCount=200}`. | none | unchanged | 0 | 0 | 0 | restart → recompute missing hashes with bounded IO (§10). loss: partial hash work only. |
| T924-N1-04 | OCR_PLAN: per-page reuse decisions from source/stage fingerprints; fresh chapter → all 200 pages require recognition; no translation-gap feedback into OCR decisions (§3.1.4). | `runRecord{state=OCR_PLAN}`; `ocrPlan.present`; all `checkpoint[p*]=absent`, `ocr[*]=absent`. | none | unchanged | 0 | 0 | 0 | restart → OCR_PLAN re-derived deterministically from fingerprints. loss: none. |
| T924-N1-05 | OCR_PREFLIGHT sprint — parameterized loop `for i in p001..p200`, exact per-page sub-sequence below (05.a–05.g). Inpaint never runs during preflight (§5). | per sub-step | per sub-step | `tx=ABSENT` throughout | 0 | 0 (OCR is local/native) | ≤1 | see sub-steps |
| — 05.a | Batch: decode page `{i}` (sampled, memory-budgeted). | `checkpoint[{i}]=absent`; prior pages 1..i-1 checkpointed. | `lease[{i}]=BATCH`; `leases.batch=1` | `display[{i}]=NONE` | 0 | 0 | 1 | restart → RUNNING page marked retryable; re-decode restarts `{i}`. loss: active bitmap + decode work. |
| — 05.b | Batch: analyze — detector + ROI OCR under one native guard; serial, no cross-page inference (§5). | unchanged | `lease[{i}]=BATCH` | unchanged | 0 | 0 | 1 | restart → same as 05.a. loss: bitmap + native inference. |
| — 05.c | Batch: persist OCR text + geometry + `inpaintMaskBoxes` into BATCH-origin candidate (`PageTranslation` durable, §4.1). | `ocr[{i}]=READY` (or TEXTLESS); checkpoint pointer not yet installed. | `lease[{i}]=BATCH` | unchanged | 0 | 0 | 1 | restart → recovery revalidates persisted artifact against source fingerprints; on match completes the checkpoint without re-recognition, else re-recognizes (§10 mid-OCR row). loss: none of the OCR text (durable). |
| — 05.d | Batch: `checkpointOcr` transaction — validate generation/page version/lease token/candidate ID/dependency fingerprint; atomically install `checkpoint[{i}]`; close/rebase BATCH candidate; preserve prior committed display (§4.2). | `checkpoint[{i}]=published` (sidecar + manifest pointer, atomic). | `lease[{i}]=BATCH` (still held) | prior committed display preserved (none exists in N1) | 0 | 0 | 1 | restart → `{i}` is durably complete; recovery releases the orphaned lease; sprint skips `{i}`. loss: none. |
| — 05.e | Batch: recycle bitmap/pools. | unchanged | `lease[{i}]=BATCH` | unchanged | 0 | 0 | 0 | restart → none needed. loss: none. |
| — 05.f | Batch: release lease `{i}` — **only after** 05.d succeeded. | unchanged | `lease[{i}]=none`; `leases.batch=0` | unchanged | 0 | 0 | 0 | restart → none. loss: none. |
| — 05.g | Batch: yield-to-interactive check — cancellation + pending Manual/Auto native waiters admitted before next decode (§5); then loop to 05.a for `{i+1}`. | unchanged | none | unchanged | 0 | 0 | 0 | restart → sprint resumes at first page with `checkpoint=absent`. loss: none. |
| T924-N1-06 | Representative mid-sprint instant: `i=162` just checkpointed; 05.g about to admit `p163`. | `checkpoint[p001..p162]=published`; `checkpoint[p163..p200]=absent`. | none (between pages) | all `display=NONE` | 0 | 0 | 0 | restart → resume sprint at `p163`. loss: nothing (between pages). |
| — | `notif="OCR extraction 162 / 200"` (§13). Textless variant: at `i∈` textless set, 05.b yields no blocks; 05.c persists `ocr[i]=TEXTLESS`; 05.d–05.g identical. | | | | | | | |
| T924-N1-07 | Sprint completes `p200`; preflight done; corpus fingerprint published. `notif="OCR extraction 200 / 200"`. | `runRecord{state=OCR_PREFLIGHT, corpus.fp=C1}`; `checkpoint[p001..p200]=published` (all 200; 10 TEXTLESS); `ocr[190 pages]=READY`, `ocr[10]=TEXTLESS`; `inpaint[*]=PENDING`. | none | all `display=NONE`, `tx=ABSENT` | 0 | 0 | 0 | restart → skip recognition entirely; resume ANALYSIS_PLAN (§10). loss: none. |
| T924-N1-08 | ANALYSIS_PLAN: build immutable corpus manifest from persisted OCR only; translatable work exists; no compatible frozen profile → provider analysis required (§3.1.6). | `runRecord{state=ANALYSIS_PLAN}`; `chunk[*]=absent`. | none | unchanged | 0 | 0 | 0 | restart → re-derive chunk plan from corpus (deterministic). loss: none. |
| T924-N1-09 | ANALYSIS_CHUNKS: sequential extraction `c01..c10`, whole-page core+overlap membership, each validated (evidence refs, enums, field caps) and persisted independently; one request in flight. | `runRecord{state=ANALYSIS_CHUNKS}`; `chunk[c01..c10]=valid`. | none | unchanged | 0 | 10 (Δ+10) | 0 | restart → validated chunks survive; retry only missing/invalid chunk (§10). loss: in-flight chunk result. |
| — | Representative mid-analysis: after `c06`, `notif="Chapter analysis 6 / 10"`, `window.batch=6/15`, `calls=6`, `envelope.inFlight=0` (analysis uses the typed structured API, not envelopes). | | | | | | | |
| T924-N1-10 | PROFILE_RECONCILE: deterministic pre-merge (aliases, shared evidence, compatible types), then 2 bounded master reconciliation requests; all evidence references validated; weak cues never promote gender (§6.3). `notif="Building profile"`. | `runRecord{state=PROFILE_RECONCILE}`; chunks unchanged; `profile.fp` still absent. | none | unchanged | 0 | 12 (Δ+2) | 0 | restart → chunk evidence survives; no frozen profile pointer → re-run reconciliation only; never translate (§10). loss: in-flight master result. |
| T924-N1-11 | PROFILE_FROZEN: atomic publish of immutable profile + `profile.pointer`; run attaches fingerprint. No canonical mutation hereafter. | `runRecord{state=PROFILE_FROZEN, profilePointer→P1}`; `profile.fp=P1`. | none | unchanged | 0 | 12 | 0 | restart → resume ENVELOPE_PLAN; profile reused as-is. loss: none. |
| T924-N1-12 | ENVELOPE_PLAN: global whole-page planner over pending blocks; 26 envelopes; membership per preamble; never splits a page; every pending block in exactly one envelope; textless pages in none (§8). `notif="Planning translation"`. | `runRecord{state=ENVELOPE_PLAN}`; `plan.fp=L1`; `plan.env1={p001..p003}`, `plan.env2={p004,p005}`, `plan.env3={p006..p011}`, …, `plan.env26={…p200}`. | none | unchanged | 0 | 12 | 0 | restart → revalidate/rebuild plan from store (deterministic; same `L1`). loss: none. |
| T924-N1-13 | TRANSLATE — parameterized loop `for k in 1..26`, sub-sequence below (13.a–13.h). Standing per-iteration invariants: `envelope.inFlight≤1`; `window.batch≤15`; `bitmaps≤1`; leases ≤ 8. | per sub-step | per sub-step | per sub-step | advances | +1 per envelope (0 retries in N1) | ≤1 | see sub-steps |
| — 13.a | Batch: reacquire leases for all member pages of `envK`. | unchanged | `lease[p∈envK]=BATCH`; `leases.batch=\|envK\|` | unchanged | f(K-1) | 12+K-1 | 0 | restart → recovery releases leases; plan unchanged. loss: none. |
| — 13.b | Batch: live revalidation — source/OCR/profile fingerprints + store state vs plan; complete/user pages excluded and suffix deterministically re-planned (none in N1; exercised in N3-10). | unchanged (N1) | unchanged | unchanged | f(K-1) | unchanged | 0 | restart → same as 13.a. |
| — 13.c | Batch: attach frozen profile subset (matcher over `envK` source text, capped) + gap-free rolling history = committed pages ≤ frontier; prior pronouns marked as translations (§7). | unchanged | unchanged | unchanged | f(K-1) | unchanged | 0 | restart → none. |
| — 13.d | Batch: dispatch `envK` (`envelope.inFlight=1`; `window.batch+1`). OVERLAP: serial local inpaint of committed-not-rendered pages proceeds during flight (re-decode from durable mask; §5/Stage 6), never during OCR. | unchanged | unchanged | display of earlier pages promotes during this window | f(K-1) | 12+K | ≤1 (one inpaint re-decode at a time) | restart → in-flight request result lost; nothing committed for `envK` (N1 has no partial-retention case); resume at first unresolved gap (§10). |
| — 13.e | Provider returns; strict validation: exact stable IDs, one output per ID, ownership, nonblank, no echo/refusal → `Complete` (§9.1). | attempt ledger +1 success | unchanged | unchanged | f(K-1) | 12+K | ≤1 | restart → same as 13.d. |
| — 13.f | Batch: per-page whole-page commits in natural order — each page one atomic transaction (candidate→`COMMITTED`); page atomicity never broken. | `tx[p∈envK]=COMMITTED` with `provenance[p]=envK-call` | unchanged | `display` unchanged yet | f(K-1) | unchanged | ≤1 | restart → committed prefix of `envK` survives; remainder re-planned. loss: none of the committed pages. |
| — 13.g | Batch: release leases; `envelope.inFlight=0`. | unchanged | `lease[p∈envK]=none` | unchanged | f(K-1) | unchanged | ≤1 | restart → none. |
| — 13.h | Render join (during later flights): per page inpaint → color/render → display promotion. `display[p]=COMMITTED` only after its render join; no later than completion of the next envelope. | `inpaint[p∈envK]=DONE` (staggered) | none | `display[p∈envK]→COMMITTED` | `frontier=last member page of envK` (textless members count complete) | unchanged | ≤1 | restart → resume only missing native/render stages (§10). loss: in-flight decode. |
| T924-N1-14 | Representative translate state after `E3` commits (13.f) — before its display promotion (13.h lands during `E4` flight). `notif="Translating / Rendering 3 / 26"`. | `tx[p001..p011]=COMMITTED`; `inpaint[p001..p005]=DONE`; `inpaint[p006..p011]=PENDING`; `plan.fp=L1` intact. | none (between envelopes) | `display[p001..p005]=COMMITTED`; `display[p006..p011]=NONE` | 11 | 15 (Δ+3 translation) | 0 | restart → resume `E4`; `p001..p011` never re-dispatched. loss: none durable. |
| T924-N1-15 | All 26 envelopes committed (loop ends). | `tx[text 190 pages]=COMMITTED`, each with unique `provenance`; textless `tx=ABSENT` but complete; `inpaint[p133..p200]` partially PENDING. | none | displays lag ≤ 1 envelope | 200 | 38 (12+26) | ≤1 | restart → NATIVE_RENDER drain only. loss: none of the translations. |
| T924-N1-16 | NATIVE/RENDER drain: remaining inpaint/render/display joins complete. | `runRecord{state=NATIVE_RENDER}`; `inpaint[all text pages]=DONE`. | none | `display[190 text pages]=COMMITTED`; textless `NONE` | 200 | 38 | 0 (peak 1) | restart → resume missing native stages only (§10). loss: in-flight decode. |
| T924-N1-17 | FINALIZE: durable reconciliation, flush, lease cleanup, artifact retention, progress completion. `notif="Finalizing"` → FINISHED. | `runRecord{state=FINALIZE}`; no transient leases; retention applied. | none (0 held) | all committed displays live | 200 | 38 | 0 | restart → no-op; chapter complete. loss: none. |

N1 totals (machine-checkable): `calls=38` = 10 chunks + 2 master + 26 envelopes;
retries = 0; `bitmaps ≤ 1` at every instant; `envelope.inFlight ≤ 1`; zero
provider calls before `PROFILE_FROZEN` except none — first paid call is `c01`
in N1-09; page atomicity violated never.

---

## 3. Scenario N2 — fragmented resume

World: same chapter fixture; a prior Batch run completed preparation and died
mid-translation. This scenario starts at the durable state left by the dead
process and converges. It proves reuse-without-repayment, frontier fencing,
suffix re-planning, the shared two-page envelope, and atomic per-page commit.

| ID | Actor / action | Durable artifacts after step | Lease | Display / tx | Frontier | Calls | BM | Restart here |
|---|---|---|---|---|---|---|---|---|
| T924-N2-01 | GIVEN — durable state at process death (prior lifetime). Death occurred during the `E2={p003,p004}` recovery tree: parent response was MISSING_ONLY (p004 complete, p003 all blocks missing); child retry `{p003}` had partially returned when the process died. | `runRecord{state=TRANSLATE, run.cfg=Fcfg1, src.digest=D1, corpus.fp=C1, profile.fp=P1, plan.fp=L1}`; `checkpoint[p001..p200]=published` incl. `checkpoint[p003]`; `chunk[c01..c10]=valid`; `plan.env1={p001,p002}`, `plan.env2={p003,p004}`; ledger: E2-tree `attempts=2/8`. | none (process dead) | `tx[p001]=COMMITTED`, `tx[p002]=COMMITTED`, `tx[p004]=COMPLETE_CANDIDATE` `[policy-dependent: T924-DR-A]`, `tx[p003]=ABSENT`, `tx[p005..p200]=ABSENT`; `display[p001]=COMMITTED`, `display[p002]=COMMITTED`, all others `NONE`; `inpaint[p001,p002]=DONE` | 2 (p004 fenced by missing p003) | 15 (12 analysis + 3 translation: E1, E2-parent, E2-child) | 0 | — (this is the death state; next step is the restart) |
| T924-N2-02 | User taps resume (queue restore never auto-starts). Startup recovery: scan store; mark RUNNING artifacts retryable; **discard** the unvalidated partial candidate from the dead in-flight `{p003}` request (its response was never validated — no orphan accepted); release orphaned leases; resume **first incomplete phase** = TRANSLATE (all earlier phase artifacts complete and valid, §3.1/§10). | `runRecord` unchanged (`state=TRANSLATE`); dead-attempt candidate removed; `tx[p003]` stays `ABSENT`. | none; `leases.batch=0` | unchanged from N2-01 | 2 | 15 (Δ+0) | 0 | restart → identical recovery re-applies (idempotent). loss: none. |
| T924-N2-03 | Reuse validation, zero provider calls: recompute `corpus.fp == C1` → skip OCR for all 200 pages (incl. `p003` — its checkpoint exists); revalidate `chunk[c01..c10]` → skip analysis; recompute profile input fingerprint → matches → reuse `profile.fp=P1` by **content fingerprint** (§4.4/§10). | unchanged; reuse decisions recorded in run record. | none | unchanged | 2 | 15 (Δ+0) | 0 | restart → same checks re-run. loss: none. |
| T924-N2-04 | Frontier rebuild from store: `frontier=2`; `tx[p004]=COMPLETE_CANDIDATE` is **fenced** — not displayable, not rolling-context, until `p003` fills the gap (§9.2/§9.7). `[policy-dependent: T924-DR-A: whether p004's rest state is candidate or committed-at-rest — under the recommended Option 1 (commit-now) p004 may already display in this row; assertions are equivalent from N2-07 onward (local note 7)]`. | unchanged | none | `display[p004]=NONE` (original visible) despite complete retained target under the candidate representation | 2 | 15 | 0 | restart → same rebuild (deterministic). loss: none. |
| T924-N2-05 | Envelope re-plan of the suffix from current store state (pending blocks only): new plan generation `L2`: `R1={p003}` (p004 not pending — validated target exists), `R2={p005,p006}` (14 blk — scene S2 ends at `p006`; exact shared-envelope case), `R3={p007,p008,p009}` (26 blk, scene S3), …, 23 suffix envelopes `R3..R25` covering `p007..p200` (textless skipped, deterministic boundaries). `L1` retained as prior generation for audit. | `plan.fp=L2`; `plan.R1={p003}`; `plan.R2={p005,p006}`; `plan.R3..R25` present. | none | unchanged | 2 | 15 | 0 | restart → re-derive `L2` deterministically. loss: none. |
| T924-N2-06 | Dispatch `R1={p003}`: reacquire `lease[p003]`; revalidate source/OCR/profile fingerprints; attach profile subset + gap-free rolling history = **pages 1–2 only** (frontier fence); `tx[p004]` target explicitly **not** attached as context. `envelope.inFlight=1`; `window.batch+1`. | unchanged | `lease[p003]=BATCH` | unchanged | 2 | 15 | 0 | restart → request result lost; `p003` stays `ABSENT`; re-plan unchanged. loss: one request. |
| T924-N2-07 | `R1` response valid → whole-page atomic commit of `p003`; **gap fill**: `tx[p004]` promotes `COMPLETE_CANDIDATE→COMMITTED` with `provenance[p004]` = original E2-parent call — **zero new provider calls for p004** `[policy-dependent: T924-DR-A: promotion timing; end state identical under either policy]`. | `tx[p003]=COMMITTED`, `tx[p004]=COMMITTED`; ledger: new tree `attempts=1/8` (fresh root per resumed recovery — see §6 note 3). | `lease[p003]` released | `display[p003,p004]→COMMITTED` after render join (during R2 flight) | 4 (p003+p004 now contiguous complete) | 16 (Δ+1) | ≤1 | restart → commits survive; render join resumes. loss: none. |
| T924-N2-08 | Dispatch `R2={p005,p006}` (shared envelope): reacquire `lease[p005], lease[p006]` (`leases.batch=2`); revalidate both; gap-free context assembly now includes pages 1–4 (source+target; `p003` machine, `p004` promoted) — contiguous only because the gap filled. `envelope.inFlight=1`. | unchanged | `leases.batch=2` | `display[p001..p004]=COMMITTED` | 4 | 16 | 0 | restart → result lost; p005/p006 stay `ABSENT`. loss: one request. |
| T924-N2-09 | `R2` response `Complete` → **atomic per-page commits from one request**: `commit(p005)` then `commit(p006)` as two independent whole-page transactions (one request, two commits; page atomicity per page, never across). | `tx[p005]=COMMITTED`, `tx[p006]=COMMITTED`, provenance = R2 call | leases released | `display[p005,p006]→COMMITTED` on join | 6 | 17 (Δ+1) | ≤1 | restart → both commits survive. Variant assertion: had the response covered only `p005` (MISSING_ONLY) → `p005` commits, `p006` does not, `frontier=5` (§9.2 contiguous complete-page prefix). |
| T924-N2-10 | Parameterized loop `R3..R25` (23 envelopes) as N1 13.a–13.h; overlap inpaint covers `p003..p006` then follows commits. Representative after `R4={p010..p016}`: | `plan.fp=L2` intact; suffix tx per loop | none between envelopes | `display[p001..p010]=COMMITTED`; `p011..p016` tx committed awaiting join | 16 | 19 (Δ+2 this step range) | ≤1 | restart → resume at first unresolved gap; committed pages never re-paid (§10). |
| T924-N2-11 | Convergence: all envelopes done; drain; FINALIZE. `notif="Finalizing"` → FINISHED. | `runRecord{state=FINALIZE}`; provenance ledger: `provenance[p001,p002]=E1`, `provenance[p004]=E2-parent`, one producing call per page — **no page has two producing calls** | none | `display[190 text pages]=COMMITTED` | 200 | 40 (15 prior + 25 this lifetime) | 0 | restart → no-op. loss: none. |

N2 totals: fragmentation premium = `28 − 26 = +2` translation calls vs N1 for the
same 800 blocks (E2-parent partial + child attempt), then clean convergence;
`p004` repaid = 0 calls; re-OCR = 0 pages; re-analysis = 0 chunks; profile
re-frozen = never (same `P1`).

---

## 4. Scenario N3 — concurrent reader + malformed provider

World: fresh run (new `runId=R3`) of the same fixture, no prior artifacts. The
user interacts with the reader during the OCR sprint, edits content between
planning and dispatch, and the provider malfunctions on one envelope. Proves
lease contention, checkpoint reuse by the reader, user-edit authority,
dispatch-time revalidation, the AMBIGUOUS_PROTOCOL split/backoff tree, and the
standing invariants under concurrency.

| ID | Actor / action | Durable artifacts after step | Lease | Display / tx | Frontier | Calls | BM | Restart here |
|---|---|---|---|---|---|---|---|---|
| T924-N3-01 | GIVEN — OCR sprint mid-page: Batch is inside 05.b (analyze) for `p100`. `notif="OCR extraction 100 / 200"`. | `checkpoint[p001..p099]=published`; `checkpoint[p100]=absent`; `runRecord{state=OCR_PREFLIGHT}` | `lease[p100]=BATCH` | all `display=NONE` | 0 | 0 | 1 (`p100` decode) | restart → bitmap lost; `p100` retryable; resume at `p100`. loss: bitmap + native call. |
| T924-N3-02 | User opens reader, taps `p100` — the **exact active leased page**: reader request attaches and waits under current lease semantics; Manual does not preempt Batch (§11). Batch `p100` processing unaffected. | unchanged | `lease[p100]=BATCH` (unchanged) | `display[p100]=NONE`; reader shows wait state for that page only | 0 | 0 | 1 | restart → reader wait request ephemeral, lost; user re-requests. loss: nothing durable. |
| T924-N3-03 | User taps `p001`: `checkpoint[p001]` **reused by the reader path** — no re-OCR, no native lane use; Manual translation dispatched INTERACTIVE (no Batch request in flight → immediate, §11/§12); valid → user-visible commit. | `tx[p001]=USER`; `display[p001]=COMMITTED` | `lease[p001]` held READER transiently during request, then `none` | `display[p001]=COMMITTED` | 1 (`p001` complete, `p002` not) | 1 (Δ+1, reader) | 0 | restart → `tx[p001]=USER` durable and authoritative; re-request only if killed pre-commit. |
| T924-N3-04 | User taps `p150`: `lease[p150]` free → **proceeds** (only the exact active page blocks); OCR absent → native OCR required; native lane busy with Batch `p100` → Manual waits; at the `p100/p101` boundary Batch **yields to the interactive waiter** (bounded starvation; exact thresholds `[open: OD-11]`). Manual decodes+OCR `p150` (≤1 bitmap), persists READER-origin OCR candidate, translates INTERACTIVE, commits. | `ocr[p150]` durable (READER-origin candidate); `checkpoint[p150]` **not** published by reader path (checkpoint publication is the Batch transaction's job — resolved at N3-05); `tx[p150]=USER`; `display[p150]=COMMITTED` | `lease[p150]=READER` during request → `none`; Batch resumed `p101` after yield | `display[p150]=COMMITTED` | 1 (p002..p149 fence) | 2 (Δ+1) | ≤1 (peak 1, Manual's decode; never 2) | restart mid-step → Manual bitmap lost; request retries. loss: active decode. |
| T924-N3-05 | Batch sprint continues; at `p150`: reuse decision — READER-origin artifact matches source/detection/OCR fingerprints → **no second recognition**; Batch `checkpointOcr` publishes `checkpoint[p150]` (origin-neutral; prior committed display preserved; candidate rebased) `[open: OD-10 exact rebase mechanics — T924-ST-*]`. Sprint completes `p200`. | `runRecord{state=OCR_PREFLIGHT, corpus.fp=C1}`; `checkpoint[p001..p200]=published` (all, incl. `p150`); `ocr[p150]=READY` | none | `display[p001]=COMMITTED`, `display[p150]=COMMITTED`, rest `NONE` | 1 | 2 | 0 | restart → resume sprint at first non-checkpointed page (none). loss: none. |
| T924-N3-06 | ANALYSIS_PLAN + chunks `c01..c10` sequential + master ×2 (as N1-09/10). Standing: `window.batch` counts analysis + translation **together** under the 15-RPM Batch sub-limit; trace over the whole run never exceeds 15/60s. `notif="Chapter analysis k / 10"` → `"Building profile"`. | `runRecord{state=PROFILE_RECONCILE}`; `chunk[c01..c10]=valid` | none | unchanged | 1 | 14 (Δ+12) | 0 | restart → validated chunks survive; retry only missing chunk; master re-runs if killed. loss: in-flight request. |
| T924-N3-07 | PROFILE_FROZEN: publish `P3`, pointer installed, immutable. | `runRecord{state=PROFILE_FROZEN, profilePointer→P3}`; `profile.fp=P3` | none | unchanged | 1 | 14 | 0 | restart → resume ENVELOPE_PLAN reusing `P3`. loss: none. |
| T924-N3-08 | ENVELOPE_PLAN: pending = 188 text pages / 784 blocks (USER-complete `p001`,`p150` excluded by planner; textless excluded) → 25 envelopes. `plan.env1={p002,p003}` (23 blk), `plan.env2={p004,p005,p006}` (22 blk), `plan.env3={p007,p008,p009}` (26 blk — scene S3 ends `p009`), … `notif="Planning translation"`. | `runRecord{state=ENVELOPE_PLAN}`; `plan.fp=L3` | none | unchanged | 1 | 14 | 0 | restart → deterministic re-derivation of `L3`. loss: none. |
| T924-N3-09 | **Between ENVELOPE_PLAN and dispatch**: user manually translates `p002` (checkpoint `p002` reused — no re-OCR) and edits block `p002_b2` wording. Landing state: user content authoritative; plan NOT auto-rewritten. | `tx[p002]=USER` (edited `p002_b2` text); `plan.fp=L3` unchanged | `lease[p002]` transient READER → none | `display[p002]=COMMITTED` | 2 | 15 (Δ+1) | 0 | restart → user edit durable and authoritative through every resume/invalidation (§4.4). loss: none. |
| T924-N3-10 | Dispatch-time revalidation of `env1`: Batch reacquires `lease[p002],lease[p003]`; live revalidation detects `p002` USER-authoritative (manual completion + edit) → **skipped, never overwritten**; deterministic suffix re-plan → `E1'={p003}`; dispatch; success → commit `p003`. | `plan.fp=L3'` (E1' replaces E1; deterministic); `tx[p003]=COMMITTED`; `provenance[p002]` unchanged (no call charged for p002) | released after commit | `display[p003]→COMMITTED` on join | 3 | 16 (Δ+1) | ≤1 | restart → `p003` commit survives; `p002` user content untouched. loss: in-flight request only. |
| T924-N3-11 | `E2={p004,p005,p006}` dispatch → valid → whole-page commits `p004..p006`. | `tx[p004..p006]=COMMITTED` | released | `display[p004..p006]→COMMITTED` on join | 6 | 17 (Δ+1) | ≤1 | restart → commits survive. |
| T924-N3-12 | Dispatch `E3={p007,p008,p009}`: leases `p007..p009` (`leases.batch=3`); rolling context = pages 1–6 gap-free, **including USER `p001`,`p002` targets flagged user-authoritative**; `p150`/`p151` (beyond frontier) never in rolling history (range safety). `envelope.inFlight=1`. Mid-flight: user requests `p151` manual translation → INTERACTIVE waiter **queued; cannot preempt the admitted request** (§11). | unchanged | `leases.batch=3` | unchanged | 6 | 17 | 0 | restart → E3 result lost; p007..p009 stay `ABSENT`; re-plan from store. loss: one request. |
| T924-N3-13 | Provider response malformed: complete `p007` outputs + **missing** `p008`/`p009` outputs + **unknown extra IDs** → classification: unknown IDs ⇒ `AMBIGUOUS_PROTOCOL` (§9.2/§9.4). **All** parent-attempt target values discarded — including complete `p007`; nothing retained, nothing rendered. `envelope.inFlight→0` after validation; ledger `attempts=1/8`. | ledger `{attempts=1/8, depth=0/3, leaves=0/4}`; `tx[p007..p009]=ABSENT` | released | `display[p001..p006]` unchanged; `display[p007]=NONE` | 6 | 18 (Δ+1, the parent call) | 0 | restart → ledger durable; re-plan suffix from store; no committed page re-paid. |
| T924-N3-14 | Interactive priority during recovery: governor admits `p151` INTERACTIVE **before** Batch child C1 (reserve applies while a waiter exists; nothing in flight was preempted). `p151` reuses `checkpoint[p151]` → commit. | `tx[p151]=USER`; suffix plan adjusted deterministically at its envelope's dispatch (p151 excluded) | transient READER → none | `display[p151]=COMMITTED` | 6 | 19 (Δ+1) | 0 | restart → user commit durable. loss: none. |
| T924-N3-15 | Deterministic whole-page split near half structural weight (parent 26 blk → half 13): `C1={p007}` (12 blk), `C2={p008,p009}` (14 blk); depth 1, leaves 2; children execute **sequentially**; C1 dispatched first. | ledger `{attempts=1/8, depth=1/3, leaves=2/4}` | `lease[p007]=BATCH` | unchanged | 6 | 19 | 0 | restart → tree state durable; resume resumes the tree at C1. |
| T924-N3-16 | `C1` attempt 2 → `Complete` → whole-page commit `p007`; frontier advances. `envelope.inFlight` was 1 (C1 alone) throughout. | `tx[p007]=COMMITTED`; ledger `attempts=2/8` | released | `display[p007]→COMMITTED` | 7 | 20 (Δ+1) | ≤1 | restart → commit survives; C2 still pending. |
| T924-N3-17 | `C2={p008,p009}` attempt 3 → AMBIGUOUS again (duplicate stable ID) → discard all; split depth 2: `C2a={p008}` (10 blk), `C2b={p009}` (4 blk); leaves 3. | ledger `{attempts=3/8, depth=2/3, leaves=3/4}`; nothing retained from attempt 3 | `lease[p008]` next | `display[p008,p009]=NONE` | 7 | 21 (Δ+1) | 0 | restart → resume tree at C2a. loss: none durable. |
| T924-N3-18 | `C2a` attempt 4 → `Complete` → commit `p008`. | `tx[p008]=COMMITTED`; ledger `attempts=4/8` | released | `display[p008]→COMMITTED` | 8 | 22 (Δ+1) | ≤1 | restart → commit survives. |
| T924-N3-19 | `C2b={p009}` attempt 5 → `MISSING_ONLY` (`p009_b1,b2` valid; `b3,b4` missing; no unknown/dup) → commit rule: contiguous complete-page prefix of `{p009}` = ∅ (page incomplete). Accepted `b1,b2` persist **only inside the frozen `p009` page transaction** as non-display partial; **partial page never renders and never advances the frontier or rolling context** (§9.2/§9.7). | ledger `attempts=5/8`; `tx[p009]=CANDIDATE` (partial, non-display) | released | `display[p009]=NONE` | 8 | 23 (Δ+1) | 0 | restart → partial `p009` candidate discarded or repaired after revalidation; `p001..p008` unaffected; re-plan from store. loss: uncommitted partial only. |
| T924-N3-20 | Targeted repair attempt 6: only missing stable IDs `p009_b3,b4`, **same frozen page/profile/context transaction** (never a fragment commit) → complete → single whole-page commit of `p009` (incl. `b1,b2` from attempt 5). Tree closes: `attempts=6/8 ≤ 8`, `depth=2/3 ≤ 3`, `leaves=3/4 ≤ 4`; one root budget covered parent + all children + repair. | `tx[p009]=COMMITTED`; ledger final | released | `display[p009]→COMMITTED` | 9 | 24 (Δ+1) | ≤1 | restart → commit survives. **Exhaustion branch:** had attempts reached `8/8` without completion → run PAUSED at `p009`: `runRecord{paused, failingPhase=TRANSLATE}`, resume action = retry `p009` subtree, `p001..p008` committed and displayed, paused notif names phase + action (§13). |
| T924-N3-21 | Remaining suffix envelopes (22 for `p010..p200`, USER `p151` excluded at its dispatch revalidation, textless skipped) as N1 13.a–13.h; overlap inpaint throughout. | per loop | per loop | per loop | advances to 200 | +22 → 46 | ≤1 | restart → resume at first unresolved gap; committed pages never re-paid. |
| T924-N3-22 | FINALIZE: flush, lease cleanup, retention, progress completion. **Committed pages 1–2 unaffected**: `tx[p001]=USER` (N3-03), `tx[p002]=USER` with `p002_b2` byte-identical to the user's edit (N3-09); both displays live throughout the entire malformed episode. `notif="Finalizing"` → FINISHED. | `runRecord{state=FINALIZE}`; provenance ledger: one producing call per page (reader calls for `p001,p002,p150,p151`; `p002` never re-dispatched by Batch) | none | `display[190 text pages]=COMMITTED`; user pages show user text | 200 | 46 (4 reader + 12 analysis + 30 translation incl. 5 retry-tree extras) | 0 | restart → no-op. loss: none. |

N3 totals: Batch-only premium vs N1 = +4 retry-tree calls; reader calls = 4;
`window.batch ≤ 15/60s` at every instant across analysis + translation +
recovery; `envelope.inFlight ≤ 1` at every instant including the entire split
tree; `bitmaps ≤ 1` at every instant including the reader-yield OCR.

---

## 5. Assertions index

Every invariant below is checked by at least one step; integration tests assert
these checkpoints by step ID.

| # | Invariant | Proven by |
|---|---|---|
| A1 | Page atomicity: global envelopes and durable commits never split a page; commits are whole-page transactions | N1-13.e–f, N2-09, N3-13, N3-19–20 |
| A2 | Frontier gap-freedom: only contiguous fully committed pages advance it; PARTIAL/malformed candidates never do | N2-04, N2-06, N3-13, N3-19 |
| A3 | Missing page fences context: a complete later page (p004) is display-blocked and context-blocked until the gap fills | N2-01, N2-04, N2-06, N2-07 |
| A4 | One Batch provider envelope in flight — including across the entire split/backoff tree | N1-13.d, N2-08, N3-12–17 |
| A5 | One decoded page in memory at every instant (sprint, reader yield, inpaint overlap) | N1-05.a–f, N1-06, N1-13.d/h, N3-01, N3-04 |
| A6 | OCR checkpoint published before lease release; per-page order decode→analyze→persist→checkpoint→release bitmap→release lease→yield | N1-05.c–g, N3-05 |
| A7 | Candidate/committed separation: CANDIDATE and COMPLETE_CANDIDATE never render; committed display preserved through checkpoint and recovery | N1-05.d, N2-01, N2-04, N3-13, N3-19 |
| A8 | User-edit / manual-completion authority: never overwritten, excluded at dispatch, authoritative context, survives restart | N3-03, N3-09, N3-10, N3-12, N3-22 |
| A9 | Reuse without repayment: checkpointed OCR, valid chunks, frozen profile, retained complete pages — zero duplicate paid work | N2-03, N2-07, N2-11 (provenance), N3-03, N3-05, N3-09 |
| A10 | No duplicate paid provider work: at most one producing call per page across all lifetimes | N2-07, N2-11, N3-10, N3-22 |
| A11 | Bounded retry tree: one root budget, ≤ 8 attempts, depth ≤ 3, ≤ 4 leaf groups, deterministic whole-page splits, children sequential | N3-13, N3-15–20 |
| A12 | MISSING_ONLY commits only the contiguous complete-page prefix; later complete pages retained non-display `[policy-dependent: T924-DR-A]` | N2-01, N2-07, N3-19; variant N2-09 |
| A13 | AMBIGUOUS_PROTOCOL (unknown/duplicate IDs) discards all parent-attempt target values, including independently complete pages | N3-13, N3-17 |
| A14 | Shared 15-RPM Batch sub-limit: one rolling window across analysis + translation + recovery | N3-06, N3-13, N3-14; standing `window.batch≤15` |
| A15 | Interactive priority: reader wins queued admission, never preempts an admitted request; Batch yields the native lane between OCR pages | N3-02, N3-04, N3-14 |
| A16 | Exact-active-page lease blocking: only the actively leased page waits; all other pages proceed | N3-02 vs N3-03/N3-04 |
| A17 | Startup recovery resumes the first incomplete phase from durable state, never from inferred page statuses | N2-02; every Restart column |
| A18 | Frozen profile immutability; reuse by content fingerprint; no canonical mutation during translation | N1-11, N2-03, N3-07, N3-10 |
| A19 | Full OCR preflight before any paid analysis; zero provider calls before ANALYSIS_CHUNKS | N1-01–08 (`calls=0`), N3-01–05 |
| A20 | Dispatch-time live revalidation with deterministic suffix re-plan (manual completion/edit between plan and dispatch) | N1-13.b, N3-10 |
| A21 | Per-page display promotion requires the render join; display lags commit by at most the next envelope | N1-13.h, N1-14, N1-16, N2-07→N2-10 |
| A22 | Translation/inpaint overlap after profile freeze: remote request in flight while serial local inpaint re-decodes; never during OCR | N1-13.d/h, N2-10, N3-21 |
| A23 | Foreground progress per §13 with real phase counters; paused state names failing phase + resume action | N1-01–17 `notif` column, N3-20 exhaustion branch |
| A24 | FINALIZE cleanup: leases 0, flush, retention, FINISHED; restart after completion is a no-op | N1-17, N2-11, N3-22 |
| A25 | Inpaint never runs during OCR preflight | N1-05 (all sub-steps), N1-07 |
| A26 | Rolling context is gap-free and range-safe: only committed pages ≤ frontier; future pages (even USER-complete beyond the fence) never attached | N2-06, N2-08, N3-12 |

---

## 6. Local choices and interpretations recorded by the implementer

1. `runRecord.state` is interpreted as the last durably completed phase; the
   executing phase is its §3.1 successor. T924-ST-* may rename the marker;
   the assertions reference the role.
2. `frontier` counts origin-neutral complete pages: machine-committed,
   user/manual-committed, and textless pages all advance it once contiguous.
3. §9.5 does not state root-budget semantics across restarts: this document
   treats the root budget as **per attempt-tree**; a resumed recovery opens a
   new tree while prior consumption remains in the durable ledger for audit
   (visible in N2-01 → N2-07).
4. Persisted-but-uncheckpointed OCR page (death between N1-05.c and N1-05.d):
   recovery revalidates the artifact against source fingerprints and publishes
   the checkpoint without re-recognition on match; otherwise re-recognizes.
5. Reader-produced OCR without a checkpoint (N3-04) is reused by the Batch
   sprint via fingerprint match; the origin-neutral checkpoint is then
   published by the Batch checkpoint transaction. Exact rebase mechanics are
   README OD-10 / T924-ST-* territory; the scenario asserts only the
   observable `checkpoint[p150]=published` + no second recognition.
6. Concrete envelope memberships, per-page block counts and fingerprint aliases
   are fixture constants for determinism; the 32-block/8-page caps are the §8
   starting experiment `[open: OD-3]` and tests must parameterize them.
7. `COMPLETE_CANDIDATE` names the p004 rest state `[policy-dependent:
   T924-DR-A]`; every assertion is written so both DR-A outcomes produce the
   same step result from N2-07 onward.
8. Notification strings follow the §13 examples verbatim, including
   "OCR extraction 162 / 200" as the representative mid-sprint state.
