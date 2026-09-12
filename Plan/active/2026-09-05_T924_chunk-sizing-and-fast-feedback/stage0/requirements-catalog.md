# T924 Stage 0 — Requirements catalog

Date: 2026-09-05 · Baseline: `adbe643` (verified) · Scope: contextual AI Batch
Translation. Specification only; no production coding authorized.

This catalog assigns stable IDs to every normative MUST/SHOULD/invariant in the
authoritative package documents:

- `README.md` (scope, constraints, open decisions)
- `design/profile-preflight-requirements.md` (product requirement baseline)
- `design/chapter-profile-batch-design.md` (primary architecture)
- `design/final-target-migration.md` (controlling corrections)
- `engineering/delivery-readiness-audit.md` (work packages, gates, evidence matrix)
- `engineering/plan-completeness-audit.md` (control-system requirements)

Superseded decision history (`design/chunk-sizing-options.md`,
`design/batch-architecture-overview.md`) is excluded by R044.

## Conventions

- Two gapless ID series:
  - `T924-R001..` — product requirements (behavior, quality, scope, delivery,
    open decisions, exclusions).
  - `T924-INV-01..` — global invariants (must hold at ALL times, in every state,
    including after interruption, failure, and under concurrency).
- Columns: ID | normative statement | source (document + section) | type |
  owning work package(s) (WP0-12 per delivery-readiness-audit graph) |
  verification route (pure/schema test, integration/resume test, device
  measurement, provider evaluation) | status.
- Status values: `SPECIFIED`, `NEEDS-DIRECTOR-DECISION`,
  `MEASUREMENT-GATED`. A row marked NEEDS-DIRECTOR-DECISION or
  MEASUREMENT-GATED blocks its named work package, not the whole package,
  unless the delivery audit lists it as Stage-1 blocking.
- Work packages (delivery-readiness-audit §Work-package dependency graph):
  WP0 decision closure + traceability ledger; WP1 run/artifact schemas +
  migrations + semantic fingerprints; WP2 atomic OCR checkpoint/rebase;
  WP3 pure corpus/chunk/profile/envelope/retry planners; WP4 feature-flagged
  OCR-preflight coordinator; WP5 typed analysis + profile freeze/resume;
  WP6 profile-aware translation + split/backoff; WP7 translation/inpaint
  overlap; WP8 persisted-layout DTO/fingerprint prototype; WP9 LAYOUT_PREPARE
  publication + reader hydration/fallback; WP10 lifecycle/device/provider
  evaluation; WP11 default enablement; WP12 contextual-AI PROBE cleanup.
- This catalog does NOT use the concurrent Stage-0 namespaces T924-SC-*/T924-FP-*,
  T924-ST-*/T924-TX-*, T924-AP-*/T924-DR-*, T924-FF-*, T924-N1/N2/N3-*.
- Conflict precedence applied: explicit Director decision > product invariants
  (profile-preflight-requirements) > final-target-migration >
  chapter-profile-batch-design. Observed conflicts are recorded in
  "Conflicts noted" below, not resolved silently.

---

## Part 1 — Product requirements (T924-R001..)

### A. Pipeline and behavior

| ID | Normative statement | Source | Type | WP | Verification | Status |
|---|---|---|---|---|---|---|
| T924-R001 | Contextual AI Batch must execute the durable phase sequence: validate local chapter and freeze run configuration, hash and plan reusable stages, full bounded OCR preflight, structured hierarchical chapter analysis, reconcile and freeze the Chapter Translation Profile, global whole-page envelope planning, sequential provider translation with live revalidation, per-page inpaint/render/commit, then reconcile and close. | profile-preflight-requirements §pipeline; chapter-profile-batch-design §1, §3 | MUST | WP1, WP2, WP3, WP4, WP5, WP6 | Integration/resume test | SPECIFIED |
| T924-R002 | Every required page must reach READY or TEXTLESS and be durably persisted and checkpointed before any paid provider analysis is dispatched, and any unresolved OCR or persistence failure must stop the run before paid analysis. | profile-preflight-requirements §pipeline; chapter-profile-batch-design §3.5; delivery-readiness-audit evidence matrix row 1 | MUST | WP2, WP4 | Integration test with provider fake observing zero calls until all pages checkpointed | SPECIFIED |
| T924-R003 | The implementation must be validated end to end against a 200-page chapter stress target with bounded memory and recorded device evidence. | profile-preflight-requirements §pipeline; chapter-profile-batch-design §5, §14; README §constraints | SHOULD | WP10 | Device measurement (200-page trace) | MEASUREMENT-GATED |
| T924-R004 | Chapter analysis must run as bounded token-and-structure chunks built from whole pages with evaluated boundary overlap, executed in page order, whose validated results persist independently and whose evidence references resolve only to pages present in the chunk or its declared overlap, followed by deterministic pre-merge and bounded hierarchical conflict reconciliation. | profile-preflight-requirements §analysis; chapter-profile-batch-design §6.1-6.3 | MUST | WP3, WP5 | Pure/schema tests + provider evaluation | SPECIFIED |
| T924-R005 | The Chapter Translation Profile must distinguish canonical terminology, entities and aliases, gender and pronoun facts including UNKNOWN and CONFLICTING states, evidence and confidence, scene-local tone and semantic context, and range-scoped narrative. | profile-preflight-requirements §profile; chapter-profile-batch-design §4.3, §6.2 | MUST | WP1, WP5 | Pure/schema tests | SPECIFIED |
| T924-R006 | Narrative summaries must complement structured entity/term records and must never substitute for them. | profile-preflight-requirements §profile; chapter-profile-batch-design §6.3 | MUST | WP5 | Pure/schema test (profile lacking entity/term records rejected even with summaries present) | SPECIFIED |
| T924-R007 | Every translation envelope must carry a capped relevant frozen-profile subset matched against current source text, range-safe scene context restricted to facts available at that point, gap-free rolling history with source provenance, and current stable page/block IDs as the response authority. | profile-preflight-requirements §requests; chapter-profile-batch-design §7 | MUST | WP3, WP6 | Pure/schema tests + provider evaluation | SPECIFIED |
| T924-R008 | Envelope planning must admit pages using hard provider context and output budgets plus structural limits (maximum blocks, maximum contributing pages, source size, stable-ID count) with token/output checks always at least as strict as structural limits. | README §constraints; chapter-profile-batch-design §8 | MUST | WP3 | Pure property/golden tests | SPECIFIED |
| T924-R009 | Response validation must enforce exact stable-ID ownership and coverage: canonical IDs, one output per ID, missing/duplicate/unknown/blank/malformed detection, echoed-source and refusal checks, and exact cardinality. | profile-preflight-requirements §requests; chapter-profile-batch-design §9.1; delivery-readiness-audit Stage 2 exit | MUST | WP3, WP6 | Pure/schema fault fixtures | SPECIFIED |
| T924-R010 | Malformed or partial output must trigger deterministic whole-page structural split/backoff that never commits fragments as independent envelopes and never falsely advances context, using static conservative policies before any provider adaptation. | profile-preflight-requirements §requests; chapter-profile-batch-design §9 | MUST | WP3, WP6 | Pure fault-injection + provider evaluation | SPECIFIED |
| T924-R011 | Analysis and Batch translation requests must share one aggregate Batch/background provider sub-limit (intended 15-RPM rolling-window norm) beneath the shared provider/model/credential quota bucket, while Manual/Auto retain interactive priority that cannot be preempted by Batch. | profile-preflight-requirements §investigate; chapter-profile-batch-design §11, §12; final-target-migration §8.6; delivery-readiness-audit evidence matrix row 7 | MUST | WP5, WP6 | Governor integration test (shared trace respects rolling limits; reader requests win priority) | SPECIFIED (provider TPM table: R032) |
| T924-R012 | Forced translation must independently reuse valid detection/OCR based on current source and configuration evidence, decoupled from inpaint readiness. | chapter-profile-batch-design §10; delivery-readiness-audit §missing decisions context | MUST | WP3 | Integration/resume test | SPECIFIED |
| T924-R013 | Sparse streamed URL-keyed page data must migrate to downloaded filename identities without requiring equal online/disk/store page counts. | chapter-profile-batch-design §10; README §origin (batch OCR artifacts not reused) | MUST | WP1 | Integration/resume test (stream → download → rekey → Batch reuse) | SPECIFIED |
| T924-R014 | Progress must add chapter-level preparation phases with (done,total) work counters to the existing progress infrastructure, must not invent fake per-page analysis states, and paused states must identify the failing phase and the resume action. | chapter-profile-batch-design §13; profile-preflight-requirements §investigate (preparation progress) | MUST | WP4, WP5 | Integration event-contract tests | SPECIFIED |
| T924-R015 | The UI must explain that Batch performs chapter preparation for consistency while Manual/Auto remains immediate, and the foreground notification must continue through OCR and analysis while retaining Stop All behavior. | chapter-profile-batch-design §13 | SHOULD | WP4, WP5 | Integration/UI test | SPECIFIED |
| T924-R016 | Per-page reader refresh behavior must be preserved through all stages and paths. | profile-preflight-requirements §preserve; final-target-migration §5 | MUST | WP4-WP12 | Regression suite (every stage) | SPECIFIED |
| T924-R017 | A complete semantic invalidation matrix must govern reuse: OCR model/source changes invalidate OCR, analysis, profile, translation and downstream; analyzer/profile-policy or series/user canon changes invalidate profile and Batch translations but not OCR; font/layout changes invalidate layout only; inpaint changes invalidate inpaint/layout but not analysis/translation. | chapter-profile-batch-design §10; final-target-migration §3; delivery-readiness-audit Stage 1 exit | MUST | WP1, WP3 | Pure fingerprint tests + integration resume tests | SPECIFIED (exact profile-change policy for finished translations: R030) |

### B. Platform and delivery control

| ID | Normative statement | Source | Type | WP | Verification | Status |
|---|---|---|---|---|---|---|
| T924-R018 | Android 8.0+ compatibility must be retained, including process recreation, foreground notification, queue restore without auto-start, thermal throttling and low-memory conditions. | profile-preflight-requirements §preserve; README §global constraints; chapter-profile-batch-design §14 | MUST | WP10 | Device measurement + lifecycle tests | SPECIFIED |
| T924-R019 | Implementation scope must not be silently broadened: separate open decisions (non-AI Batch preflight, configuration snapshot/add-versus-replace UX) must not be smuggled into this contextual-AI delivery. | profile-preflight-requirements §closing; delivery-readiness-audit §missing decisions #17; README §open decisions #8, #9 | MUST | WP0 (all WPs) | Independent conformance review | SPECIFIED |
| T924-R020 | The contextual-AI Batch path must ship behind a feature flag with defined rollback behavior, may become default only after quantitative evidence gates pass, and WP12 PROBE cleanup must never precede default evidence plus a defined rollback window. | final-target-migration §4 Stage 8; chapter-profile-batch-design §Recommendation; delivery-readiness-audit §graph, Stage 8 | MUST | WP4, WP10, WP11, WP12 | Integration tests + device/provider evidence + demonstrated rollback | SPECIFIED (flag granularity: Stage 0 T924-FF agent) |
| T924-R021 | Every migration stage must compile, preserve old behavior behind its feature flag, and leave a valid resume boundary. | final-target-migration §4 | MUST | WP1-WP9 | Review + CI + integration resume test per stage | SPECIFIED |
| T924-R022 | Analysis and translation token usage, attempts, structural failure classes, accepted blocks per attempt, profile-subset size, correction rate and elapsed phase time must be instrumented, and all evidence must be stored with device, OS, app commit, provider/model, configuration, fixture/corpus identity and timestamp. | chapter-profile-batch-design §12, §14; delivery-readiness-audit §evidence matrix note | MUST | WP5, WP6, WP10 | Provider evaluation + device measurement records | SPECIFIED |
| T924-R023 | The analysis premium (A+M extra provider calls) must be justified by measured provider evaluation against the progressive baseline (malformed retries, terminology repairs, inconsistent retranslations, corrections, elapsed time) before the new path becomes default. | chapter-profile-batch-design §12, §15; delivery-readiness-audit evidence matrix row 17 | SHOULD | WP10, WP11 | Provider evaluation vs baseline | MEASUREMENT-GATED |
| T924-R024 | Every change must cite its requirement IDs and tests in the live traceability ledger, and every stage exit must record finish commit, implemented IDs, evidence, deviations, rollback state and an independent conformance verdict. | delivery-readiness-audit §control mechanism; README §authoritative reading order (stage-exit record); plan-completeness-audit §findings 2 | MUST | WP0 (all WPs) | Review (ledger audit at each stage exit) | SPECIFIED |
| T924-R025 | At every stage kickoff the implementation base commit must be recorded and load-bearing source assumptions re-verified; stale line citations are navigation hints only, and proof binds to tests and semantic symbols. | plan-completeness-audit §finding 5; README §authoritative reading order | SHOULD | WP0 (all WPs) | Review (stage report) | SPECIFIED |

### C. Open decisions (Director) — blocking status per delivery-readiness-audit

| ID | Normative statement | Source | Type | WP | Verification | Status |
|---|---|---|---|---|---|---|
| T924-R026 | Exact versioned schemas and canonical serialization with defined unknown-version behavior, migrations and crash-safe manifest publication must be accepted for ChapterRunRecord, PageOcrCheckpoint, AnalysisChunkResult, ChapterTranslationProfile, EnvelopePlan, persisted layout geometry and color/style preparation before WP1 coding. | delivery-readiness-audit §missing decisions #1-2; README §open decisions #1; chapter-profile-batch-design §4.2, §15 | MUST (artifact) / decision open | WP1 | Pure/schema tests (serialization, golden fixtures) | NEEDS-DIRECTOR-DECISION |
| T924-R027 | The analyzer/provider relationship to the translator (same or different provider/model), analysis request/response schemas, field limits, evidence syntax and typed error/retryability classes must be accepted before provider stages. | delivery-readiness-audit §missing decisions #7, #9; chapter-profile-batch-design §15.1 | MUST (artifact) / decision open | WP5 | Provider evaluation fixtures | NEEDS-DIRECTOR-DECISION |
| T924-R028 | The authority inputs available in the first release (user canon, series canon, chapter-only profile), absent-authority fingerprinting/display, and the glossary UX and promotion workflow must be decided; until then the system emits series-update candidates only. | delivery-readiness-audit §missing decisions #10; README §open decisions #5; chapter-profile-batch-design §6.4 | MUST (artifact) / decision open | WP1, WP5 | Pure/schema tests + review | NEEDS-DIRECTOR-DECISION |
| T924-R029 | The retention policy for independently complete pages from a mixed malformed response (commit immediately versus remain candidates until split recovery completes) must be decided before WP6; the design proposal (MISSING_ONLY: commit contiguous complete-page prefix; later complete pages persist as non-display candidates) is the default pending that decision. | README §open decisions #6; chapter-profile-batch-design §9.2, §15.6; delivery-readiness-audit §missing decisions #8 | MUST (artifact) / decision open | WP3, WP6 | Pure fault-injection tests | NEEDS-DIRECTOR-DECISION |
| T924-R030 | The invalidation policy for already completed machine translations when the profile changes (automatic retranslate versus affect only unfinished/explicitly refreshed pages) must be decided. | README §open decisions #7; chapter-profile-batch-design §15.7 | MUST (artifact) / decision open | WP1, WP3 | Pure fingerprint + integration tests | NEEDS-DIRECTOR-DECISION |
| T924-R031 | Whether non-contextual AI Batch adopts full OCR preflight or retains the current coordinator must be decided; until decided, non-contextual Batch is unchanged (R043). | README §open decisions #8; chapter-profile-batch-design §15.8; delivery-readiness-audit §missing decisions #17 | MUST (artifact) / decision open | WP4 | Review + regression | NEEDS-DIRECTOR-DECISION |
| T924-R032 | The provider/model/credential shared RPM and TPM quota table and the rolling-window implementation of the 15-RPM Batch sub-limit must be accepted (tunable during flagged evaluation, blocking before product default). | README §open decisions #2; delivery-readiness-audit §missing decisions #12; chapter-profile-batch-design §12 | MUST (artifact) / values open | WP5, WP6, WP10 | Governor integration tests + provider evaluation | MEASUREMENT-GATED |
| T924-R033 | Initial structural/page/block/output and analysis budgets (suggested 32 blocks/8 pages, 50% analysis input reserve, one-page/10% overlap) are experiments that must be confirmed by instrumentation and provider tests before being frozen as constants. | README §open decisions #3; chapter-profile-batch-design §6.1, §8, §15; final-target-migration §7 | MUST (artifact) / values open | WP3, WP10 | Provider evaluation + device measurement | MEASUREMENT-GATED |
| T924-R034 | Native reader-priority and anti-starvation time/turn thresholds between OCR pages must be device-tuned so sustained reader arrivals cannot suspend Batch forever without breaking interactive latency. | README §open decisions #11; chapter-profile-batch-design §5, §15; final-target-migration §7 | MUST (artifact) / values open | WP4, WP10 | Device measurement (contention test) | MEASUREMENT-GATED |
| T924-R035 | Provider analysis must be skipped for textless chapters, no remaining translation work, or a compatible frozen profile; any additional small-chapter bypass threshold requires a measured value and Director acceptance. | chapter-profile-batch-design §3.6, §12; README §open decisions #4; delivery-readiness-audit §missing decisions #11 | MUST (skip rules) / threshold open | WP3, WP5 | Integration test + provider evaluation | MEASUREMENT-GATED (skip rules SPECIFIED) |

### D. Persisted layout milestone (separate from first coordinator cutover)

| ID | Normative statement | Source | Type | WP | Verification | Status |
|---|---|---|---|---|---|---|
| T924-R036 | Persisted layout is a separate second contract migration (WP8/WP9) that must not be coupled to, or be a prerequisite for, the first OCR/profile coordinator cutover; completion may be redefined as DISPLAY_READY only after all reader paths can hydrate the durable plan. | final-target-migration §1, §4 Stage 7; README §proposed scope #11; plan-completeness-audit §finding 6; delivery-readiness-audit evidence matrix row 15 | MUST | WP8, WP9 | Review + state/UI integration tests | SPECIFIED |
| T924-R037 | Persisted layout must be a versioned immutable draw-plan DTO in source-image coordinates (stable block identity, line placement, font metrics identity, orientation/alignment, structural clip references) with a compatibility fingerprint including font asset identity, typeface/style, measurement flags, planner version and any tested platform shaping value; runtime `BlockLayout` must never be serialized, and stroke width remains layout-affecting. | final-target-migration §1.1-1.2, §3, §8.2-8.3 | MUST | WP8 | Pure round-trip golden tests | SPECIFIED (font/platform boundary: MEASUREMENT-GATED, see R040) |
| T924-R038 | Layout publication must commit with compare-and-swap preconditions (candidate generation/page version, translation fingerprint and block IDs, OCR geometry/mask fingerprint and page dimensions, color/style fingerprint where consumed, cleaned-image name and inpaint revision where the result depends on them, layout policy and planner/font-metrics version), and stale async hydration must be rejected by the existing bind-generation defense. | final-target-migration §2 (layout publication race, reader hydration race) | MUST | WP9 | Integration race/crash tests | SPECIFIED |
| T924-R039 | Reader-side asynchronous planning must be retained as a compatibility fallback for Manual/Auto, legacy data, corrupt/missing/invalid layouts and feature-flag rollback; the 12-page cache holds only hydrated objects/paths. | final-target-migration §4 Stage 7, §8.4 | MUST | WP9 | Integration tests (Pager/Webtoon fallback, restart/LRU rehydrate without planner invocation on valid plans) | SPECIFIED |
| T924-R040 | The layout dependency invalidation graph must hold: translated-text changes invalidate layout only; geometry/mask/page-dimension changes invalidate layout (and may invalidate inpaint); font/measurement changes invalidate layout; cleaned-image/inpaint changes invalidate color preparation; a color-only change must not reflow; SSIV pan/zoom/orientation/holder-size transforms must never invalidate source-image-space layout. | final-target-migration §3 | MUST | WP8, WP9 | Pure/schema invalidation tests | SPECIFIED (font/platform compatibility boundary values: MEASUREMENT-GATED) |
| T924-R041 | No baked translated raster may be persisted; the overlay continues drawing planned vector text. | final-target-migration §1 (closing) | MUST | WP8 | Review + visual tests | SPECIFIED |

### E. Exclusions and preserved behavior

| ID | Normative statement | Source | Type | WP | Verification | Status |
|---|---|---|---|---|---|---|
| T924-R042 | Manual/Auto scheduling and behavior must remain unchanged except that they may consume compatible origin-neutral OCR checkpoints and valid persisted layouts. | final-target-migration §5; profile-preflight-requirements §pipeline; delivery-readiness-audit evidence matrix row 16 | MUST | WP4-WP12 | Regression suite (every stage) | SPECIFIED |
| T924-R043 | Non-contextual translation engines and legacy/non-contextual Batch lanes must retain their current latency-oriented paths and coordinator unchanged. | chapter-profile-batch-design §header, §2; README §constraints | MUST | WP12 (boundary), all | Regression suite (every stage) | SPECIFIED |
| T924-R044 | The superseded small-first/Fast-mode, token-overflow-flush and block-cap/min-fill progressive direction from `chunk-sizing-options.md` and `batch-architecture-overview.md` must not be implemented for any path; those records are decision history only. | README §authoritative reading order item 8; final-target-migration §6; plan-completeness-audit §finding 1 | MUST | WP0 (review gate) | Review (no code referencing superseded direction) | SPECIFIED |
| T924-R045 | The OCR checkpoint must be an atomic store transaction that validates generation, page version, lease token, source identity and dependency fingerprints, publishes an origin-neutral OCR checkpoint, preserves the prior committed display, closes/rebases the BATCH candidate, and only then releases the lease; on any failure the manifest stays on the prior state with Batch retaining ownership to report persistence failure. | chapter-profile-batch-design §4.2, §5; final-target-migration §2 (OCR checkpoint ownership); delivery-readiness-audit Stage 3 exit | MUST | WP2 | Store race/crash tests (injected failure at every publication boundary) | SPECIFIED (exact CAS inputs/close-vs-rebase contract: Stage 0 state/transactions specification, T924-ST/TX namespace) |

---

## Part 2 — Global invariants (T924-INV-01..)

Invariants hold at all times, in every phase, including after interruption,
process death, provider failure and under reader concurrency.

| ID | Invariant statement | Source | Type | WP coverage | Verification | Status |
|---|---|---|---|---|---|---|
| T924-INV-01 | Page atomicity: no committed envelope or durable commit ever splits a page across envelopes, and a single token-oversized page is rejected rather than split (a targeted missing-block repair stays inside the same frozen page transaction and cannot independently commit or alter context). | README §constraints; chapter-profile-batch-design §8, §9 (definition paragraph); delivery-readiness-audit Stage 5 exit | INVARIANT | WP3, WP6, WP10 | Pure property tests + provider fault fixtures | SPECIFIED |
| T924-INV-02 | At most one Batch provider envelope (request) is in flight at any time; remote translation may overlap only serial local work. | README §constraints; chapter-profile-batch-design §3.12; final-target-migration §4 Stage 5, §6 | INVARIANT | WP4, WP6, WP7 | Scheduler tests + provider fake trace | SPECIFIED |
| T924-INV-03 | Hard provider token ceilings (input and output) are always enforced as backstops; structural limits add stricter boundaries and may never loosen them. | README §constraints; profile-preflight-requirements §preserve; chapter-profile-batch-design §8 | INVARIANT | WP3, WP6 | Pure planner tests | SPECIFIED |
| T924-INV-04 | Gap-free rolling context: only contiguously fully committed pages advance the context frontier; PARTIAL/malformed candidates never feed rolling history; a missing natural-order page fences rolling context. | profile-preflight-requirements §preserve; chapter-profile-batch-design §2 (BatchContextFrontier row), §9.7, §11; VERIFIED: pipeline/batch/BatchContextFrontier.kt (PARTIAL non-advance rule) | INVARIANT | WP3, WP6 | Frontier integration tests | SPECIFIED (current behavior VERIFIED) |
| T924-INV-05 | Bounded memory: at most one page is decoded at a time during OCR preflight, decoded bitmaps are released before the next page, no decoded handoffs cross phase barriers, and page leases are not held across phases (OCR artifacts, not bitmaps, accumulate). | profile-preflight-requirements §preserve; chapter-profile-batch-design §5; README §constraints | INVARIANT | WP4, WP10 | Instrumented device run (bitmap count, peak memory) | SPECIFIED (budget values MEASUREMENT-GATED) |
| T924-INV-06 | Candidate/committed display separation: incomplete, malformed or partial results never render, never replace the last committed display, and never advance context; only complete page commits promote. | profile-preflight-requirements §preserve; chapter-profile-batch-design §9.2; final-target-migration §2 | INVARIANT | WP2, WP6 | Store/display integration tests | SPECIFIED |
| T924-INV-07 | User-edit authority: user-edited blocks and the last committed display remain authoritative through every re-plan, resume, migration and invalidation, and Batch excludes them from re-translation. | profile-preflight-requirements §layers; chapter-profile-batch-design §4.4, §11; delivery-readiness-audit evidence matrix row 11, Stage 1 exit | INVARIANT | WP1, WP5, WP6, WP9 | Store/invalidation tests | SPECIFIED |
| T924-INV-08 | Leases and write fences: one origin owns a page/stage at a time; every guarded write validates generation, page version, lease token, candidate origin and dependency fingerprints and fails closed on mismatch; a Manual reader request attaches to a Batch-owned page instead of preempting it. | profile-preflight-requirements §preserve; chapter-profile-batch-design §4.2, §5; VERIFIED: store/PageStageLeaseTable.kt (tryAcquirePageStageLease, T917 D1 priority matrix), artifact/ChapterArtifactStore.kt (candidate provenance mismatch rejection) | INVARIANT | WP1, WP2, WP4, WP6 | Store race/crash tests | SPECIFIED (current behavior VERIFIED) |
| T924-INV-09 | Frozen-profile immutability: after PROFILE_FROZEN the canonical profile content cannot mutate during the run; no silent profile mutation during translation; corrections are stored as separate candidates for a future run. | profile-preflight-requirements §layers; chapter-profile-batch-design §3.9, §4.4; final-target-migration §2 | INVARIANT | WP1, WP5, WP6 | Golden/schema/resume tests (frozen bytes/content hash cannot mutate) | SPECIFIED |
| T924-INV-10 | No auto-promotion: model-derived gender, relationship or spelling findings are never automatically promoted into established series canon; they persist as series-update candidates until user confirmation or cross-chapter corroboration per a later authority design. | chapter-profile-batch-design §6.4, §15; final-target-migration §8.5; README §open decisions #5 (recommendation) | INVARIANT | WP5, WP11 | Schema/provider tests (candidate-only emission) | SPECIFIED (promotion UX: R028) |
| T924-INV-11 | Manual/Auto latency orientation: reader translation paths never wait for chapter OCR, analysis or profile work and remain immediately responsive. | profile-preflight-requirements §pipeline; chapter-profile-batch-design §11; VERIFIED: store/PageStageLeaseTable.kt (Manual-vs-Batch attach semantics) | INVARIANT | WP4, WP6 | Coexistence/contention tests + reader latency measurement | SPECIFIED (current behavior VERIFIED) |
| T924-INV-12 | Foreground service and reader priority: Batch native admission admits at most one page, releases the lane between pages, checks cancellation and pending interactive work, yields to Manual/Auto waiters under a bounded anti-starvation rule, and the foreground notification with Stop All continues through OCR/analysis. | profile-preflight-requirements §preserve; chapter-profile-batch-design §5, §13; delivery-readiness-audit evidence matrix row 3 | INVARIANT | WP4, WP10 | Contention/device tests (reader wait distribution + Batch starvation bound) | SPECIFIED (threshold values: R034) |
| T924-INV-13 | Durable reuse via semantic identity only: reuse and invalidation decisions use canonical semantic content fingerprints; transaction identities (candidate IDs, page versions, sidecar filenames) are never fingerprinted; a run resumes completed validated stages whenever semantic fingerprints match; an envelope-policy-only change never invalidates an otherwise compatible machine translation. | profile-preflight-requirements §preserve (durable reuse, source/config fences); chapter-profile-batch-design §4.4; delivery-readiness-audit Stage 1/5 exits | INVARIANT | WP1, WP3, WP6 | Pure fingerprint determinism tests + resume integration tests | SPECIFIED |
| T924-INV-14 | No future-narrative leakage: later canonical identity facts may inform earlier dialogue, but range-scoped narrative/plot facts (revelations, relationships at that point, plot state) apply only from their evidence point onward (`CANONICAL_CHAPTER_WIDE` vs `RANGE_SCOPED` vs `AVAILABLE_FROM`). | profile-preflight-requirements §profile; chapter-profile-batch-design §4.3; delivery-readiness-audit evidence matrix row 6 | INVARIANT | WP3, WP5, WP6 | Range-policy fixtures (requests contain chapter-wide canon, exclude future range facts) | SPECIFIED |
| T924-INV-15 | Origin-neutral OCR reuse: the OCR checkpoint is origin-neutral so a fresh Manual/Auto/Batch candidate can start from it as its reusable native base; the lease is released only after successful checkpoint publication; reusable data is never stranded under a stale writer identity. | chapter-profile-batch-design §4.2, §5, §11; final-target-migration §2; delivery-readiness-audit evidence matrix row 4 | INVARIANT | WP2, WP4 | Store race/crash tests (fresh candidate succeeds, stale writer fails, display preserved) | SPECIFIED |
| T924-INV-16 | Native serialization: detector/OCR/inpaint for different pages never run concurrently, exactly one native page is owned at a time, OCR preflight never inpaints, and there is no cross-page OCR fan-out; engine warmth comes from serial reuse. | chapter-profile-batch-design §5; final-target-migration §8.7 | INVARIANT | WP4, WP7 | Scheduler determinism tests + device traces | SPECIFIED |
| T924-INV-17 | Gender evidence gate: entity gender may be set or promoted only from explicit or corroborated strong contextual evidence; weak name/speech-style cues remain notes and never promote gender; UNKNOWN and CONFLICTING states survive reconciliation. | profile-preflight-requirements §profile (no guessing gender from weak cues); chapter-profile-batch-design §4.3, §6.3; delivery-readiness-audit Stage 4 exit | INVARIANT | WP3, WP5, WP6 | Pure/schema tests (weak-evidence non-promotion) | SPECIFIED |
| T924-INV-18 | Explicit-scene lexical scoping: explicit-scene context may guide lexical meaning for its range but must never create a chapter-wide replacement rule. | profile-preflight-requirements §profile; chapter-profile-batch-design §7.2; delivery-readiness-audit Stage 8 exit (mixed-theme lexical) | INVARIANT | WP3, WP5, WP6 | Pure/schema fixtures (scene-scoped disambiguation without global replacement) | SPECIFIED |
| T924-INV-19 | Authority hierarchy: user-confirmed facts > compatible established series canon > high-confidence frozen chapter facts > rolling source+translation context > local inference, applied consistently in reconciliation and translation. | profile-preflight-requirements §layers; chapter-profile-batch-design §6.4 | INVARIANT | WP3, WP5, WP6 | Pure precedence tests | SPECIFIED (first-release authority inputs: R028) |
| T924-INV-20 | No glossary mutation during a frozen-profile run: the incremental chapter glossary is legacy compatibility only; the new Batch path must not mutate it or bump its version during a run. | chapter-profile-batch-design §2 (ChapterGlossaryStore row), §4.4; VERIFIED: store/ChapterGlossaryStore.kt (live glossary version reuse gate) | INVARIANT | WP5, WP6 | Coexistence tests (glossary frozen under new path; legacy path unaffected) | SPECIFIED (current mutable behavior VERIFIED) |
| T924-INV-21 | One root retry budget: a single root RequestRetryBudget covers the parent and every child/targeted request; no child can escape root attempt/depth/leaf caps and the attempt ceiling wins; children receive the same budget rather than constructing new ones. | chapter-profile-batch-design §9.5; delivery-readiness-audit Stage 2 exit | INVARIANT | WP3, WP6 | Pure fault-injection accounting tests | SPECIFIED (cap values: R033) |
| T924-INV-22 | Unknown or extra response lines never overwrite anything and make the parent response AMBIGUOUS_PROTOCOL, whose target values are discarded before any smaller retry. | chapter-profile-batch-design §9.2, §9.4 | INVARIANT | WP3, WP6 | Pure parser fixtures | SPECIFIED (taxonomy details Stage 0 spec) |
| T924-INV-23 | Legacy coexistence: progressive OCR/PROBE semantics remain intact for legacy/non-contextual paths until WP12 cleanup after the rollback window, and normal manga behavior must not regress because of specialized manhwa/Batch behavior. | README §constraints, §global constraints; final-target-migration §6; chapter-profile-batch-design §Recommendation | INVARIANT | WP12, all WPs | Regression suite (every stage); NormalMangaIsolation-style tests | SPECIFIED |
| T924-INV-24 | Deterministic resume: phase transitions are persisted and startup resumes the first incomplete phase rather than inferring from page statuses; cancellation/process death loses at most the active page's volatile state; recovered RUNNING artifacts become retryable and preserved preflight pages survive. | chapter-profile-batch-design §3, §5, §10; delivery-readiness-audit Stage 3 exit | INVARIANT | WP1, WP2, WP4, WP5, WP6 | Integration fault-injection at every phase boundary | SPECIFIED (transition table: Stage 0 state/transactions spec) |
| T924-INV-25 | Commit provenance: every translation commit carries the frozen profile content fingerprint and source-block identity, and commits against stale profile/source/page state must fail. | chapter-profile-batch-design §4.4; final-target-migration §2 (profile and translation ownership) | INVARIANT | WP1, WP6 | Store CAS tests | SPECIFIED |

---

## Part 3 — Coverage cross-check: evidence matrix → catalog IDs

Every row of the delivery-readiness-audit "Evidence and verification matrix"
maps to at least one catalog ID.

| # | Evidence-matrix vision/invariant | Gate | Catalog IDs |
|---|---|---|---|
| 1 | Full OCR before paid analysis | 3 | T924-R002, T924-INV-16, T924-R001 |
| 2 | Bounded memory | 3, 8 | T924-INV-05, T924-R003, T924-R018 |
| 3 | Reader priority with Batch progress | 3, 8 | T924-INV-12, T924-R034, T924-INV-11 |
| 4 | Origin-neutral OCR reuse | 1, 3 | T924-INV-15, T924-R045, T924-INV-08 |
| 5 | Frozen reproducible profile | 2, 4 | T924-INV-09, T924-INV-13, T924-R017, T924-R005 |
| 6 | No future narrative leakage | 2, 4, 5 | T924-INV-14, T924-INV-17 |
| 7 | Shared provider quota | 4, 5 | T924-R011, T924-R032, T924-INV-12 |
| 8 | Whole-page global planning | 2, 5 | T924-R008, T924-INV-01, T924-INV-03, T924-R001 |
| 9 | Bounded malformed recovery | 2, 5 | T924-R009, T924-R010, T924-INV-21, T924-INV-22, T924-R029 |
| 10 | Gap-free context | 2, 5 | T924-INV-04, T924-INV-06 |
| 11 | User edits authoritative | 1, 5, 7 | T924-INV-07, T924-INV-25, T924-R038 |
| 12 | Translation/inpaint overlap safe | 6 | T924-INV-16, T924-INV-02, T924-INV-08 |
| 13 | Persisted layout is reusable | 7 | T924-R036, T924-R037, T924-R039 |
| 14 | Image-space/layout fidelity | 7 | T924-R037, T924-R040 |
| 15 | Completion means display ready | 7, 8 | T924-R036, T924-R014 |
| 16 | Manual/Auto and legacy stability | Every stage | T924-R042, T924-R043, T924-INV-11, T924-INV-23, T924-R016 |
| 17 | Better cost/reliability | 5, 8 | T924-R023, T924-R022, T924-R033, T924-R011 |

Every MUST in `profile-preflight-requirements.md` appears: pipeline order
(R001, R002), 200-page stress (R003), Manual/Auto latency orientation
(INV-11, R042), Android 8+ (R018), bounded memory (INV-05), page atomicity
(INV-01), one envelope (INV-02), shared leases/write fences (INV-08),
candidate/committed display (INV-06), per-page refresh (R016), durable reuse
(INV-13), source/config fences (INV-13, R017), foreground-service behavior
(INV-12), gap-free rolling context (INV-04), token ceilings (INV-03), PROBE
replacement eligibility and old-path preservation (INV-23, R043, R044),
profile content distinctions (R005), gender evidence rules (INV-17),
scene scoping (INV-18), future-scoping (INV-14), summaries subordinate
(R006), bounded analysis chunking (R004), authority layers (INV-19),
no silent profile mutation (INV-09), request composition (R007), hard budgets
plus structural ceilings (R008, INV-03), ownership/ID validation (R009),
whole-page split/backoff (R010), static-first policy (R010), shared governor
with 15-RPM norm (R011), forced OCR reuse (R012), sparse migration (R013),
preparation progress (R014), no scope broadening (R019), evidence
classification in reports (R025/R022 records; satisfied by existing design
docs §15).

---

## Part 4 — Verified current-code guarantees (spot checks at `adbe643`)

Up to five load-bearing claims were re-verified against real source. These
invariants are already guaranteed by existing mechanisms; work packages must
preserve, not re-invent, them.

| Claim | VERIFIED at (file + symbol) | Supports |
|---|---|---|
| Candidate writes reject a different origin ("candidate provenance mismatch") | `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt`, guarded candidate write paths, checks at approx. lines 373-379 and 477-482 | T924-INV-08 |
| One origin owns a page/stage; MANUAL attaches to a BATCH-owned page (never preempts); only MANUAL evicts an in-flight AUTO lease; writes fence on lease token | `app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt`, `tryAcquirePageStageLease` / `PageLeaseRecord` (T917 D1 priority matrix comment) | T924-INV-08, T924-INV-11 |
| Provider quota defaults are 60 RPM / 60K TPM; interactive reserve shrinks BACKGROUND limits only while an interactive waiter is waiting and never preempts an admitted request; no 15-RPM Batch sub-limit exists yet | `app/src/main/java/eu/kanade/translation/translator/ProviderRequestGovernor.kt`, `ProviderQuotaPolicy` (defaults) and admission reserve logic approx. lines 444-457 | T924-R011, T924-R032, T924-INV-12 |
| PARTIAL output is displayable but must not advance the rolling context frontier | `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt`, frontier advance guard comment approx. lines 56-57 | T924-INV-04 |
| Chapter glossary version is a live reuse gate sourced from the artifact manifest (incremental, mutable during translation today) | `app/src/main/java/eu/kanade/translation/store/ChapterGlossaryStore.kt`, live-version accessor approx. lines 29-39 | T924-INV-20 |

Everything else in this catalog describes new or changed behavior (VERIFIED
in the design docs' own classification, or RECOMMENDATION/ASSUMPTION there).

---

## Part 5 — Conflicts noted

Recorded per the precedence rule; none required silent resolution.

1. **No-auto-promotion status.** README open decision #5 lists glossary
   promotion as open with a recommendation against; final-target-migration
   §8.5 rejects automatic permanent canon promotion. Treated as decided at
   invariant level (T924-INV-10, SPECIFIED); the UX/promotion workflow and
   first-release authority inputs remain open (T924-R028). Precedence:
   final-target-migration over README decision register.
2. **Mixed malformed-response retention.** chapter-profile-batch-design §9.2
   states a concrete MISSING_ONLY policy (later independently complete pages
   persist as non-display candidates), while README open decision #6 and
   delivery-readiness-audit blocking item #8 defer commit-now-versus-candidates
   to the Director. Catalog records T924-R029 as NEEDS-DIRECTOR-DECISION with
   the design policy as default proposal. Implementers must not treat §9.2 as
   settled.
3. **Persisted layout scope.** The product requirement contract
   (profile-preflight-requirements) ends at native/render/commit, while README
   proposed scope includes a persisted `LAYOUT_PREPARE` stage.
   final-target-migration separates it as a second contract migration. Catalog
   follows final-target-migration: T924-R036 marks WP8/WP9 as a separately
   identified milestone outside the first coordinator cutover (also resolves
   plan-completeness-audit finding 6). Note: evidence-matrix rows 13-15
   (gates 7, 8) belong to this milestone, not to WP4-WP7 exits.
4. **"Manual/Auto unchanged" precision.** profile-preflight-requirements and
   README say Manual/Auto retains latency-oriented behavior;
   final-target-migration §5 narrows "untouched" by permitting Manual/Auto to
   consume compatible origin-neutral OCR checkpoints and valid persisted
   layouts. Catalog wording of T924-R042 uses the narrowed (controlling)
   form.
5. **Interactive reserve semantics.** chapter-profile-batch-design §11 claims
   the interactive reserve applies only while an interactive waiter exists and
   cannot preempt an admitted request. Verified consistent with
   ProviderRequestGovernor.kt; no conflict, recorded as a verified baseline
   for T924-INV-12/T924-R011.
6. **Stale reading-order guidance — CORRECTED (stage0-review F-3).**
   plan-completeness-audit finding 1 was written against an older
   HANDOFF_PROMPT; the current `HANDOFF_PROMPT.md` explicitly forbids starting
   with the superseded records and names the active target, and both
   superseded documents carry history banners. No action needed; T924-R044
   gates implementation against the superseded direction regardless.
