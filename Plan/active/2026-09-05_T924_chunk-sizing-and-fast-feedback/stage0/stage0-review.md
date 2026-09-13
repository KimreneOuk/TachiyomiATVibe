# T924 Stage 0 — independent conformance review

Date: 2026-09-05 · Reviewer: independent Reviewer (not an artifact author)
Baseline verified: `git rev-parse HEAD` = `adbe643df9d99953202dbb8c93c5dd7e504bfd6c`;
working tree clean except untracked `Plan/` docs. No files modified except this
report; no builds run. Review performed read-only per assignment.

## Verdict

**ACCEPT-WITH-DEVIATIONS** for closing Stage 0, conditional on Director
acceptance of `decision-briefs.md` (DB-01..DB-13) — which the delivery audit
already requires — plus two recorded MEDIUM deviations that must be folded into
the named artifacts at Stage 1 kickoff (no new investigation needed, no
architecture change):

- **F-1 (MEDIUM):** the mandatory-first-gate `checkpointOcr` contract
  (T924-TX-01..12) has no specified branch for checkpointing a page whose
  candidate was already committed/cleared by a READER origin, while normative
  scenario N3-04→N3-05 requires exactly that. Amend TX-02/TX-03 (or N3-04/05)
  before WP2 coding.
- **F-2 (MEDIUM):** `feature-flags-stage-gates.md` §5.5 and `work-packages.md`
  (WP8/WP9) say the persisted-layout product-requirement placement is an
  unresolved documentation decision blocking Stage 7, but
  `requirements-catalog.md` Part 3D (T924-R036..R041) already places those
  requirements and explicitly resolves plan-completeness-audit finding 6. The
  integration README records no reconciliation of this contradiction.

No CRITICAL findings. No finding requires reopening the architecture. All
blocking items the Director must consciously accept are listed at the end.

---

## Findings table

| # | Severity | Likelihood | Class | Evidence | Required action |
|---|---|---|---|---|---|
| F-1 | MEDIUM | High (N3 is a required scenario; DB-02 recommends accepting TX-02 as written) | Defect (cross-artifact spec gap) | `contracts-state-transactions.md` T924-TX-01(d)/TX-02 input 4 ("non-null mandatory for checkpoint") + TX-02.1 + TX-03 define checkpointOcr only against an *active BATCH candidate*; TX-05 shows promotion clears the candidate. `reference-scenarios.md` N3-04 has the reader **commit** p150 (candidate cleared), then N3-05 asserts `checkpoint[p150]=published` via Batch `checkpointOcr` with no candidate present. F tags it `[open: OD-10]`, but DB-02 claims to close OD-10 without covering this branch. | Add a normative branch to TX-02/TX-03 (e.g., permit checkpoint publication with `candidateGenerationId = null` when a committed bundle whose OCR content fingerprint matches is present — publish checkpoint only, skip close/rebase), or amend N3-04/05 to open a seeded BATCH candidate first. Fold into WP2 before any Stage 1/3 implementation. |
| F-2 | MEDIUM | Certain (texts contradict today) | Defect (integration omission) | `feature-flags-stage-gates.md` §5.5: persisted-layout product requirement "still need[s] a home in the requirements catalog (item A)"; `work-packages.md` WP8 ("product-requirement placement is an open documentation decision") and Conflicts #5 ("WP8/WP9 authorization waits on that documentation decision"). Contradicted by `requirements-catalog.md` Part 3D, T924-R036..R041 (present, owned, sourced), whose R036 note says it "also resolves plan-completeness-audit finding 6". `stage0/README.md` cross-agent reconciliations (items 1-5) do not cover this. | Correct flags doc §5.5 and work-packages WP8/WP9/Conflicts #5 to cite catalog R036..R041; the spurious Stage 7 documentation gate must not survive into Stage 7 authorization. |
| F-3 | LOW | Certain | Defect (stale text inside artifact set) | `requirements-catalog.md` Part 5 conflict 6: "HANDOFF_PROMPT.md still directs readers to superseded records". Verified false: `HANDOFF_PROMPT.md` says "Do not start with `design/chunk-sizing-options.md` or `design/batch-architecture-overview.md`; they are superseded decision history" and names the active target. Integration README reconciliation 2 is therefore **correct**; the catalog row itself remains wrong for anyone reading item A alone. | Annotate or correct catalog conflict 6 at next catalog touch (integration README already records the correction). |
| F-4 | LOW | Certain | Documentation imprecision (substance correct) | B's invalidation-matrix rationale (`contracts-schemas-fingerprints.md` §6): "rows 1–2, 6, 8–10 follow design §10". Row 8 (inpaint mode/algorithm → persisted layout **KEEP**) does **not** follow design §10, which literally says "Inpaint change invalidates inpaint/layout"; it follows the controlling `final-target-migration.md` §3 (layout does not consume the cleaned bitmap; cleaned/inpaint changes invalidate color preparation). Precedence (final-target > design detail) makes the cell value correct; the attribution is wrong. | Reattribute row 8's rationale to final-target §3 in the matrix note. |
| F-5 | LOW | Certain | Deviation from exit-criterion letter | Delivery audit Stage 0 exit: "schemas have examples and validation rules". `contracts-schemas-fingerprints.md` gives field tables + JSON examples for 4 of 8 DTOs (ChapterRunRecord §1.1, PageOcrCheckpoint §1.2, ChapterTranslationProfile §1.4, LayoutDrawPlan §1.6); **no JSON example** for AnalysisChunkResult (§1.3), EnvelopePlan (§1.5), ColorStylePreparation (§1.7). All have field-level caps and validation semantics; D §1.4 supplies a wire-format example for the analysis payload, and golden fixtures are already gated (gates 1.1, 2.1). | Acceptable to close with the deviation recorded; the missing examples become the Stage 1 golden fixtures. |
| F-6 | LOW | Certain | Documentation precision | `stage0/README.md` artifact map declares namespaces "T924-ST-01..34, T924-TX-01..23", but C defines a sparse set: ST-01..16, ST-20..24, ST-30..34 (17-19, 25-29 absent) and TX-01..12, TX-20..23 (13-19 absent). Stable IDs exist; the ranges imply nonexistent IDs an implementer may search for. | Reword to "T924-ST-\* (sparse: 01-16, 20-24, 30-34)" or "see document". |
| F-7 | LOW | Medium (materializes only if DB-06 Option 1 is accepted as recommended) | Cross-artifact imprecision | `reference-scenarios.md` N2-04 asserts `display[p004]=NONE` and claims "behavior below identical" under either DR-A outcome. Per D §6 (DR-A analysis), Option 1 (commit-now, **recommended by DB-06**) displays scattered completed pages, so the display column at N2-01/N2-04 differs under Option 1. F local note 7 correctly scopes assertion-equivalence to "N2-07 onward"; the inline claim is broader than what holds. Gate 5.2 already flips with the decision record. | When DB-06 is decided, regenerate the N2 display-column pre-gap-fill rows to match; until then the `[policy-dependent]` tag carries it. |
| F-8 | LOW | Certain | Documentation drift (consistent with controlling doc) | Delivery audit Stage 3 opens "Land `checkpointOcr` before enabling preflight", implying a Stage 3 landing; `work-packages.md` WP2 maps checkpointOcr to "Stage 1 (late)", following `final-target-migration.md` §4 Stage 1 ("Add … compare-and-swap store transactions"; "The OCR checkpoint transaction is mandatory before orchestration work") and gate 1.6. No behavioral contradiction (landed by Stage 3 either way); the audit sentence was not updated. | Informational; optionally note in the stage-1 kickoff that WP2 closes before Stage 3 starts. |
| I-1 | INFO | — | Informational (wording) | DB-11 calls split-tree "8 attempts / depth 3 / ≤ 4 leaves" "(existing constants)"; only `DEFAULT_MAX_ATTEMPTS = 8` exists in code (`TranslationRetry.kt:72`). Depth/leaf caps are design proposals; `feature-flags-stage-gates.md` gate 2.3 states this correctly ("existing 8 kept; depth/leaf pending Director"). | None required for gate 2.3; DB-11 wording could drop "existing". |
| I-2 | INFO | — | Informational (correctly briefed open decisions) | Catalog rows T924-R026..R035 (NEEDS-DIRECTOR-DECISION / MEASUREMENT-GATED), README open decisions and audit blocking decisions correctly remain open pending Director acceptance. Recorded here per assignment rules as informational, not defects. | Director acceptance of decision-briefs.md closes them. |

---

## Exit-criteria checklist (delivery-readiness audit, Stage 0 exit)

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| A1 | Every MUST/invariant has stable ID, owner WP, implementation location, test/evidence method, status | **PASS** | `requirements-catalog.md`: T924-R001..R045 + T924-INV-01..25, each with source, type, WP, verification route, status; `traceability-ledger.md` has exactly one row per ID with code owner/path (real file or TBD-Stage-N), test/evidence ID (real baseline test or TBD), accepted-result/reviewer columns empty until stage exit, and status. Exclusions present: R019 (no scope smuggling), R042 (Manual/Auto), R043 (non-contextual lanes), R044 (superseded direction), plus R041 (no baked raster). |
| A2 | Schemas have examples + validation rules | **PASS-WITH-DEVIATION (F-5)** | B §1: 8 DTOs with field tables, schema-level bounds (T924-SC-02), enums, per-type unknown-version table (T924-SC-13), corrupt-artifact rules (T924-SC-17); JSON examples for 4 of 8 (see F-5). |
| A3 | Every durable transition names preconditions, atomic writes, postconditions, crash result | **PASS** | `contracts-state-transactions.md` §1 per-state table (Entry / Durable writes / Atomicity / Post / Crash / Resume / Terminal / GC for ST-02..ST-14), T924-TX-11 crash-point × manifest-state table (B0..BX), T924-SC-20 publication crash windows, ST-16 startup recovery ordering. |
| A4 | Every later stage (WP1-WP12) has exact source/test entry points | **PASS** | `work-packages.md` WP0-WP12 with stage mapping, existing-file entry points (path + symbol + line hint), NEW files, test entry points, hooks, risks, exit pointers. Sampled ~68 source paths and ~54 test paths; **all exist** at HEAD (two initial apparent misses were this reviewer's path-resolution errors: `BatchStageInvocationCounters.kt` is under the test root per convention; `TranslationBatchEvent.kt` is under `pipeline/batch/` as the docs say; `CleanedImagePublisherTest.kt` resolves to the test root as the no-prefix convention says; `T918CancelledBatchRestartTest.kt` is under `coexistence/` as the ledger cites). |
| A5 | Blocking decisions 1-6 resolved-as-spec or briefed; 7-11 addressed for provider stages; 12-17 tunable | **PASS** | 1-2 → DB-01/DB-03; 3 → DB-02; 4 → DB-01 residual + R030; 5 → DB-13; 6 → DB-04. Provider-stage: 7 → DB-07 (+ T924-AP-\* spec); 8 → DB-06; 9 → addressed as specification (D §3 typed error contract, gap fixes marked RECOMMENDATION); 10 → DB-09; 11 → DB-10. Tunable: 12 → DB-08; 13 → DB-11; 14 → DB-12; 15 → DB-11 gate numbers + D §8.7 (thresholds deliberately unset); 16 → DB-10/DB-11/DB-13; 17 → recorded default (out of scope, explicit). No silently invented decisions found in `decision-briefs.md`; every recorded default traces to an artifact section (C §6 C8c, D §8.5, AP-01). |

---

## Cross-artifact consistency (Check B)

**ID namespaces — no collisions.** T924-R001..045 / T924-INV-01..25 (A);
T924-SC-01..22 / T924-FP-01..09 (B); T924-ST-\* / T924-TX-\* (C);
T924-AP-01..08 / T924-DR-A..D (D); T924-FF-00/01/02/10/20/21/22 (E);
T924-N1/N2/N3-\* (F); DB-01..13 (decision briefs). Disjoint prefixes; internal
references resolve (spot-checked: R045↔TX-\*, R033↔gate 2.3, DR-C/D↔DB-08,
FF-01e↔gate 3.8, matrix rows↔R017/R030/R040). Range-notation imprecision:
F-6.

**Specific contradiction probes (all others clean):**

1. **checkpointOcr composition (B DTO × C transaction).** Verified both texts.
   B §1.2 `producedByOrigin` keeps the two-value `ArtifactOrigin` enum as
   recorded provenance with an origin-neutral *consumption* rule; C T924-TX-03
   defines REBASE as close-BATCH-generation + open named BATCH successor in one
   manifest publication, CLOSE as default with candidate=null + checkpoint
   pointer. `ArtifactOrigin` verified to have exactly two durable values
   (`ArtifactContracts.kt:56-61`; `toArtifactOrigin`
   `TranslationStageContracts.kt:38-41`). **The two contracts compose with no
   third origin value; integration reconciliation 1 is accurate** — except for
   the unrepresented no-active-candidate corner exercised by N3-05 (F-1).
2. **CAS input sets.** C TX-02 (9 inputs, 3 mandatory-non-null) is a strict
   superset of catalog R045's summary and F's N1-05.d step list; B §0.1's CAS
   vocabulary matches. C's caveat C2 (patchPage dependency-fingerprint grace
   clause disarms when no candidate) verified in source
   (`ChapterTranslationStore.kt:580-592`) and correctly closed by TX-02.1.
3. **Fingerprint exclusion lists.** B T924-FP-01 exclusion list, C TX-02 input
   9 ("content identity carried opaquely; never derived from candidate IDs,
   page versions, or filenames"), and F's provenance atoms agree; integration
   reconciliation 5 confirmed.
4. **Invalidation matrix vs design §10 + final-target §3.** Matrix rows 1-3, 9,
   11-13 match design §10 / final-target §3 including the stroke-width
   exception (row 9 layout-only, row 10 color-only — matches
   `TextLayoutPlanner.computeStrokeWidth` being geometry-affecting, verified).
   Row 8's layout=KEEP follows final-target §3 (controlling) but is
   misattributed to design §10 (F-4). Rows 4/5/12 encode the open D-7.1/OD-7
   policy as a flagged default consistent with R030 (NEEDS-DIRECTOR-DECISION).
   Row 3's claim that source language is an OCR fingerprint input verified
   (`StageFingerprints.ocr`, sourceLanguage parameter).
5. **Retry budget semantics (F × C).** F treats the root `RequestRetryBudget`
   as per-attempt-tree with a fresh tree on resume; C keeps a durable per-page
   attempt ledger with `MAX_CONSECUTIVE_UNRESOLVED = 3`
   (`ChapterArtifactManifest.kt:345`, verified) consumed at startup
   (`consumeUnresolvedAttemptsAtStartup` `ChapterTranslationStore.kt:392`,
   verified). The two compose: tree budget bounds one recovery attempt-tree
   (8); the ledger's crash-loop cap bounds repeat restarts (3 consecutive
   unresolved). D AP-08 uses the same tree semantics for analysis chunks.
   Integration reconciliation 4 **confirmed**; no contradiction.
6. **Flag semantics (E) × schema versioning (B).** E FF-01c/01e (legacy path
   never requires new artifacts; flag-off mid-run completes or drops to legacy)
   composes with B T924-SC-04's manifest v2→v3 recommendation and the verified
   future-schema guard; the residual tradeoff (old build cannot write chapters
   carrying v3 pointers during rollback) is correctly surfaced as DB-05.
   Integration reconciliation 3 confirmed.
7. **Provider quota (D × E × catalog).** D DR-C table (gemini-free 15/15,
   deepseek/openrouter 60 + 15 sublimit, lm_studio reclassification — verified
   `defaultPolicy` only relaxes `backend == "desktop"`) vs E gate 4.6 (15-RPM
   rolling sublimit, PROPOSED-GATE) vs catalog R011/R032 — one shared
   allowance, no independent pools (final-target §8.6). Consistent.
8. **Scenario behavior vs contracts.** N1 per-page order
   decode→analyze→persist→checkpoint→recycle→release→yield matches TX-06; N1
   restart rows match TX-11 B0-B4 (N1-05.c matches C's B0 RECOMMENDATION
   branch, recorded as F local choice 4); N3-13 AMBIGUOUS_PROTOCOL discards all
   parent values incl. complete p007 — matches INV-22 and DB-06 guardrails;
   N3-19 targeted repair stays inside the frozen page transaction, never
   commits fragments — matches INV-01/design §9. Arithmetic verified: N1
   calls 38 = 10+2+26; N2 totals 40 = 15+25, fragmentation premium +2; N3
   totals 46 = 4 reader + 12 analysis + 30 translation (+5 retry extras vs own
   plan, +4 vs N1). Exception: F-1 (N3-05) above.
9. **Requirement hooks in gates/scenarios.** E §4's named invariants and F's
   assertions A1-A26 each map to catalog IDs (A1→INV-01, A2→INV-04, A4→INV-02,
   A5→INV-05, A6→INV-15/R045, A7→INV-06, A8→INV-07, A9/A10→INV-13, A11→INV-21,
   A12→R029, A13→INV-22, A14→R011, A15→INV-12, A16→INV-08, A17→INV-24,
   A18→INV-09, A19→R002, A20→TX-21, A23→R014/R015, A25→INV-16, A26→INV-14).

---

## Source-citation spot checks (Check C)

All checks below performed by this reviewer directly against HEAD `adbe643`.
Result: **every sampled VERIFIED claim confirmed**; no fabricated citations
found. Line numbers cited in the artifacts are accurate or off by ≤2 (within
their own "navigation hints only" rule, T924-R025).

| # | Claim underpinning contracts | Verified at (file + symbol) | Result |
|---|---|---|---|
| 1 | Crash-safe publication `.tmp`→re-read+validate→rotate `.bak`→rename; failed promotion restores backup; `readValidated` recovers backup, quarantines `.corrupt` | `app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt:227-301` (`publish`, `publishJson`, `readValidated`, `recoverPrimaryFromBackup`) | CONFIRMED |
| 2 | Candidate writes reject a different origin ("candidate provenance mismatch") | `artifact/ChapterArtifactStore.kt:373-379` (`persistLiveCandidate`) | CONFIRMED |
| 3 | Retention is store-reachability-based, "never filename age alone" | `ChapterArtifactStore.kt:938-951` (`reconcileRetention` doc + delegate) | CONFIRMED |
| 4 | Future-schema guard: primary/backup `schemaVersion > SCHEMA_VERSION` refused and preserved read-only; publication skipped while future doc present | `ChapterArtifactStore.kt:85-101, 991-1013` (`publishManifestInternal`, `futureBackupPresent`) | CONFIRMED |
| 5 | Manifest `SCHEMA_VERSION = 2` | `artifact/ChapterArtifactManifest.kt:59` | CONFIRMED |
| 6 | `GlossaryPointer(fileName, version, versionFingerprint)`; `publishGlossary` publishes versioned sidecar then pointer | `ChapterArtifactManifest.kt:276-283`; `ChapterArtifactStore.kt:197-214` | CONFIRMED |
| 7 | Attempt ledger "written BEFORE the paid call"; crash-loop cap `MAX_CONSECUTIVE_UNRESOLVED = 3` | `ChapterArtifactManifest.kt:309-321, 345` | CONFIRMED |
| 8 | `mergeOcrLocked` CAS ladder (generation, pageVersion, artifactPageVersion, candidateGenerationId, dependencyFingerprint, priorOcrFingerprints, leaseToken) | `ChapterTranslationStore.kt:831-861` | CONFIRMED |
| 9 | `patchPage` dependency-fingerprint grace clause disarms when no candidate | `ChapterTranslationStore.kt:580-592` | CONFIRMED (C caveat C2 correct) |
| 10 | Display promotion only on `hasRenderedResult || isTextlessTerminal` | `ChapterTranslationStore.kt:1888-1889` (`promoteDisplayIfReadyLocked`) | CONFIRMED (TX-07 preserve rule holds) |
| 11 | `consumeUnresolvedAttemptsAtStartup` :392; `applyAttemptCapPause` :401; `recoverInterruptedStages` :846 | `ChapterTranslationStore.kt` / `ChapterArtifactStore.kt` | CONFIRMED |
| 12 | T917 D1 lease priority matrix: MANUAL evicts only in-flight AUTO; MANUAL-vs-BATCH attaches, never preempts; leases in-memory | `store/PageStageLeaseTable.kt:44, 71-98` (`tryAcquirePageStageLease`, `pageLeases`) | CONFIRMED |
| 13 | `ArtifactOrigin` has exactly two durable values; `toArtifactOrigin` maps MANUAL/AUTO/null→READER_ADHOC | `artifact/ArtifactContracts.kt:56-61`; `TranslationStageContracts.kt:38-41` | CONFIRMED (basis of reconciliation 1) |
| 14 | Governor defaults 60 RPM / 60K TPM / 1000 ms / maxInFlight 1 / 60 s window; interactive reserve (RPM−1, TPM×0.8) applies only while an INTERACTIVE waiter waits, admitted never revoked; starvation guard: BACKGROUND older than 30 s wins FIFO; `desktop` → 1000 RPM/unlimited; shared singleton applies it to all backends (lm_studio not relaxed) | `translator/ProviderRequestGovernor.kt:66-105, 443-462, 533-546, 655-675` | CONFIRMED |
| 15 | `DEFAULT_MAX_ATTEMPTS = 8` | `translator/retry/TranslationRetry.kt:72` | CONFIRMED |
| 16 | PARTIAL output displayable but must not advance the frontier | `pipeline/batch/BatchContextFrontier.kt:56-58` (guard comment + `isContextReady` branch) | CONFIRMED |
| 17 | Stroke width produced by planner: `max(MIN_STROKE_PX·scale, fontSizePx·0.12)`; layout-affecting | `rendering/TextLayoutPlanner.kt:3733-3737` (`computeStrokeWidth`), `STROKE_WIDTH_FRACTION = 0.12f`, `MIN_STROKE_PX = 2f` | CONFIRMED |
| 18 | `StageFingerprints.layout` inputs (translation artifact id, cleaned-or-original id, engine version, font identity, font scale, style prefs, output dims); `ocr` includes source language; `glossaryVersion` sorted-key traversal | `artifact/StageFingerprints.kt:87-104, 34-46, 112-119` | CONFIRMED (basis of T924-FP-07 and matrix row 3) |
| 19 | Overlay: bundled `R.font.animeace` forced `Typeface.BOLD`, `ANTI_ALIAS|SUBPIXEL_TEXT`, coordinates in source-image space | `ui/reader/viewer/TranslationOverlayView.kt:26-50` | CONFIRMED (basis of FontIdentity DTO) |
| 20 | Force path couples OCR reuse to inpaint readiness | `model/PageWorkPlanner.kt:30-41` (`canReuseNative = ocrReady && inpaintReady`) | CONFIRMED (basis of R012) |
| 21 | Sparse rekey requires equal online/disk/store page counts | `TranslationManager.kt:1267-1281` (size-equality early returns) | CONFIRMED (basis of R013) |
| 22 | `checkpointOcr` does not exist at HEAD | grep over `app/src/main` → 0 matches | CONFIRMED (flags doc §5.3, WP conflict 3) |
| 23 | `AiTranslationRetryController.kt` is a file (1028 lines) with no class of that name; symbols are `AiTranslationRetryPolicy` :41 and `translateAiChunkWithAdaptiveRetry` :230 | `translator/retry/AiTranslationRetryController.kt` | CONFIRMED (B §8 / flags §5.1 / WP6 note) |
| 24 | `promptText` has zero production call sites; swallows errors in the four AI providers | grep `.promptText(` over `app/src/main` → no callers; overrides in Gemini/DeepSeek/LmStudio/OpenRouter translators | CONFIRMED (D §1.0) |
| 25 | `analyzePage` :837; two-strike recognition-OOM diagnostic with exact message; bitmap recycled by caller; `inpaintPage` :971 | `pipeline/SinglePageOnnxPhase.kt:826-838, 868-892, 971` | CONFIRMED (ST-20) |
| 26 | `BatchWriteIdentity` captured at lease grant :848-855; lease-deferral record :842-843; `dependencyReadyAfterNative` accepts READY\|\|TEXTLESS :1310-1311 | `pipeline/batch/BatchLaneWorkers.kt` | CONFIRMED (TX-02 provenance) |
| 27 | `BatchTranslationProtocol.VERSION = 1`; `pN` / `pN_bN` identity | `translator/contextual/BatchTranslationProtocol.kt:9-16` | CONFIRMED |
| 28 | Flag mechanism anchors: `translation_enabled`/`translation_experimental_qnn`/`translation_diagnostics`/`translation_rate_limit_safe` at the cited lines; DI at `PreferenceModule.kt:61`; `InMemoryPreferenceStore` exists | `domain/.../TranslationPreferences.kt:67,126,225,240`; `di/PreferenceModule.kt:61`; `core/common/.../InMemoryPreferenceStore.kt` | CONFIRMED (T924-FF-00) |
| 29 | Reader path INTERACTIVE with attach-on-denied-lease | `TranslationPipeline.kt:405-420` (`withProviderRequestPriority(INTERACTIVE)`, `attachToOwnerTerminal`) | CONFIRMED (N3-02 semantics) |
| 30 | Coordinator dispatch anchors: `BatchChapterTranslator` :69, `SequentialBatchCoordinator(` construction :622, `runPass1` :59, `SequentialBatchCoordinator` :46, `BatchAdmissionProbe` :32, `StreamingChunkPlanner` :21, `EngineLane` :45/:117, `ChapterAttemptLedger` :36 | verified at exact lines | CONFIRMED |
| 31 | Cited test idioms exist with the quoted names: `ChapterArtifactStoreTest` :639/:778/:1040/:1109/:1138; `ChapterTranslationStorePhase3Test` :95/:137/:178/:221; `D1OriginPriorityTest` :45 | verified verbatim | CONFIRMED |

**Work-package entry-point sampling (Check C, second half):** ~122
paths/symbols sampled across WP1-WP12 source and test lists — all resolve at
HEAD under the documents' stated path conventions. WP2's entry points
(`ChapterTranslationStore.kt` root, `ChapterArtifactStore.kt`,
`store/PageStageLeaseTable.kt`, `model/PageTranslation.kt`,
`store/ChapterAttemptLedger.kt`) and WP4's dispatch anchors verified
individually.

---

## Requirements coverage (Check D) — PASS

- Evidence-matrix rows 1-17: all mapped in `requirements-catalog.md` Part 3
  (spot-checked rows 1, 4, 7, 9, 15, 17 — each maps to real catalog IDs; row
  15's secondary mapping to R014 is loose but R036 carries it).
- `profile-preflight-requirements.md` MUSTs: all enumerated in Part 3's
  coverage paragraph and individually present (pipeline order R001, OCR gate
  R002, analysis chunking R004, profile distinctions R005, summaries R006,
  request composition R007, budgets R008/INV-03, validation R009, split R010,
  shared governor R011, reuse/invalidation R012/R013/INV-13, progress R014,
  refresh R016, matrix R017, plus invariants INV-01..25).
- No scenario assertion lacks a requirement (A1-A26 map, see above); no gate
  row lacks a hook (E §4).
- Exclusion requirements present: R019, R042, R043, R044 (+ R041).
- One placement contradiction inside the set (F-2) — the requirements exist;
  the flags/WP docs dispute where they live.

## Decision briefs (Check E) — PASS

README open decisions 1-12 → DB-01 (1 schemas), DB-07 (1 analyzer), DB-08 (2),
DB-11 (3), DB-10 (4), DB-09 (5), DB-06 (6), DB-01 residual (7), recorded
default (8), recorded default (9), DB-02 (10), DB-12 (11), DB-13 (12).
Audit blocking 1-6 → DB-01..DB-05 (as tabled above). Audit 7-11 → DB-06/07/09/10
plus AP §3 as specification for #9. Audit 12-17 → DB-08/DB-11/DB-12/DB-13 and
explicit out-of-scope defaults. Deferrals are explicit ("Recorded defaults
requiring no decision"), each traceable to an artifact section — nothing
invented.

## Integration README accuracy (Check F)

| Reconciliation | Verdict |
|---|---|
| 1. Two-value ArtifactOrigin × C REBASE — no third origin; contracts compose | **VERIFIED CORRECT** (both texts read; enum confirmed in source) — subject to F-1's unrepresented no-active-candidate corner |
| 2. HANDOFF_PROMPT staleness correction of A's conflict 6 | **VERIFIED CORRECT** — `HANDOFF_PROMPT.md` explicitly forbids starting with the superseded records; catalog conflict 6 is stale (F-3 records the residual) |
| 3. Manifest v2→v3 × E rollback semantics; DB-05 tradeoff | **VERIFIED CORRECT** |
| 4. Retry-root semantics F × C | **VERIFIED CORRECT** (confirmed as requested) |
| 5. Fingerprint exclusions B × C × F | **VERIFIED CORRECT** |

Missing reconciliation: the persisted-layout requirement-placement
contradiction (F-2) should have been recorded here and was not.

---

## Blocking items the Director must not approve around

1. **DB-01..DB-05 (Stage-1 blocking)** must be explicitly accepted before WP1
   coding; partial acceptance must be recorded per-brief, not implied.
2. **DB-02 acceptance must carry the F-1 amendment** (no-active-candidate
   checkpoint branch, or an N3-04/05 change) as a required WP2 contract
   addendum. Accepting TX-01..12 "as specified" while N3-05 stays normative is
   not a coherent state.
3. **DB-05 rollback tradeoff** — during the rollback window an old build
   cannot write chapters whose manifest carries v3 pointers (read-only
   preservation). Accept knowingly.
4. **F-2 must be corrected before Stage 7 authorization**: as written, WP8/WP9
   wait on a documentation decision that the requirements catalog has already
   made.
5. Tunable values (audit 12-17 / DB-08/DB-11/DB-12/DB-13) are accepted as
   initial flagged-evaluation defaults only; they block product default
   enablement, not Stage 1-2 coding.

---

## Citation integrity statement

31 of 31 sampled load-bearing VERIFIED claims re-confirmed against HEAD
`adbe643` (table above), covering every claim the contracts treat as
load-bearing (atomic publication, origin rejection, retention reachability,
future-schema guard, CAS ladders, lease priority matrix, governor
defaults/reserve/starvation, retry defaults, frontier guard, stroke width,
layout fingerprint inputs, rekey/force gaps, flag mechanism, dispatch anchors,
and the exact cited test idioms). Work-package entry points: ~122 sampled, all
present. No unregistered citation failures found; line drift ≤ 2 lines where
present, within the documents' own "navigation hints" rule (T924-R025).
