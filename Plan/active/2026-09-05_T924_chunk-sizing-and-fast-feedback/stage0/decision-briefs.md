# T924 Stage 0 — consolidated Director decision briefs

Date: 2026-09-05 · Source: integration of all Stage-0 artifacts (see `README.md`
artifact map). Each brief cites its full analysis. **Accepting this document's
recommendations closes the Stage 0 exit condition** (delivery-readiness audit:
"all blocking decisions below are accepted"). Decisions marked *(tunable
during flagged evaluation)* do not block Stage 1-2 coding but must be accepted
before their named stage.

Legend: OD-n = README "Open decisions" item n; #n = delivery-audit "Missing
decisions" item n.

## Blocking before Stage 1 coding

**DB-01 — Versioned schemas, serialization, fingerprints (OD-1, #1-2, #4).**
Accept the DTO set and fingerprint/invalidation contracts in
`contracts-schemas-fingerprints.md` (T924-SC-01..22, T924-FP-01..09): 8
versioned DTOs with field caps and JSON examples; canonical serialization via
the existing shared `AtomicChapterDocuments` Json instance; length-prefixed
SHA-256 fingerprints that exclude all transaction identities; the 13×8
invalidation matrix. *Recommendation: accept as specified.* Residual policy
inside this decision:
- Profile-change invalidation of completed machine translations (OD-7, #4):
  **keep completed translations; new profile governs pending/explicitly
  refreshed pages; contradictions recorded as correction candidates** (analysis
  §7.1).
- Unknown-version OCR checkpoints: **lazy re-derivation** (re-OCR only when the
  page enters planning; never eagerly at store open) (§7.3).
- Translator-signature mismatch on committed display: **display never revoked**;
  mismatch surfaced as provenance metadata (§7.4).

**DB-02 — OCR checkpoint transaction (OD-10, #3).** Accept
`contracts-state-transactions.md` T924-TX-01..12: exact CAS inputs
(generation, page version, lease token, candidate ID, artifact version,
dependency fingerprint — the last three mandatory-non-null, closing today's
grace-clause gap); **CLOSE as the default** (checkpoint sidecar is
origin-neutral; any fresh candidate seeds from it), REBASE only when the same
run deterministically continues with no interactive waiter observed and the
successor fingerprint equals the checkpoint content fingerprint; ordering
validate → publish → close/rebase → release lease; full crash-point table.
*Recommendation: accept as specified — this is the mandatory first
implementation gate (WP2).* **Acceptance carries the T924-TX-03.1 amendment**
(no-active-candidate ADOPT-COMMITTED branch for reader-committed pages, added
per stage0-review F-1) as part of the contract.

**DB-03 — Durable phase-transition/recovery table (#2).** Accept
`contracts-state-transactions.md` T924-ST-01..34 (13 states; resume-first-
incomplete-phase rule; startup recovery ordering; sidecar GC via existing
reachability retention). Two embedded policy recommendations:
- Retention depth for superseded run/profile/corpus artifacts: **current
  pointer chain + one previous generation** (the `previousCommitted`
  precedent), rest swept (conflict C4).
- Run-record resume across app versions: **resume only when schemaVersion is
  current and all referenced fingerprints validate; otherwise close the run
  durably and require a fresh run** (conflict C5).

**DB-04 — Feature flags and rollback (OD from #6).** Accept
`feature-flags-stage-gates.md`: two independent flags
(`translation_batch_profile_pipeline`, `translation_batch_persisted_layout`),
both default OFF; measured constants are NOT flags; in-flight runs complete
under their started path; no resume re-enters the new path after flag-off;
rollback window = later of 2 stable releases or 6 weeks without a rollback
event; no user-visible toggle until Stage 8. *Recommendation: accept as
specified.*

**DB-05 — Manifest schemaVersion 2 → 3 (embedded in DB-01, surfaced by B
§7.2).** Bumping the manifest version when new pointers are added makes a
rolled-back older build preserve affected chapters **read-only** (verified
future-schema guard) instead of silently stripping new pointer fields. Cost:
during the rollback window the old build cannot write chapters that carry new
pointers. *Recommendation: take the bump* — read-only preservation is the
safer failure mode and the window is finite.

## Blocking before provider stages (Stage 4-5)

**DB-06 — Mixed malformed-response retention (OD-6, #8; T924-DR-A).** For a
MISSING_ONLY response: **commit independently complete pages immediately as
their own durable transactions** (contiguous prefix advances the frontier;
post-gap commits stay frontier-fenced with context provenance). For
AMBIGUOUS_PROTOCOL (any unknown/duplicate/malformed line) and refusals:
discard ALL parent-attempt values before any smaller retry. Alternative
(retain candidates until recovery completes) buys contiguous reveal at the
cost of rework risk, extra candidate-binding state, and no invariant gain.
*Recommendation: Option 1 with the design §9.2 guardrails.*

**DB-07 — Analyzer/translator relationship (OD-1 second half; T924-DR-B).**
**Independently configurable analyzer provider+model, capability-gated,
defaulting to the translator's provider+model.** Zero behavior change for
users who never touch it; the capability gate pauses hopeless configurations
(e.g., small local models) with a typed failure instead of burning the
analysis budget. *Recommendation: Option B.*

**DB-08 — Provider quota table + 15-RPM mechanism (OD-2, #12;
T924-DR-C/DR-D).** Table: gemini-free 15 RPM at both layers (TPM keeps the
60K in-code default until provider-verified); gemini-paid provider-verified
limit; deepseek/openrouter 60 RPM provider-wide + 15-RPM Batch sublimit;
lm_studio reclassified desktop-class (local — today it wrongly inherits 60
RPM). Mechanism: **nested rolling-window governor buckets** (credential-wide
model-agnostic Batch bucket beneath the existing provider bucket; interactive
requests skip the Batch bucket; no forced sleeps; no new governor code path).
Daily quotas (Gemini free) are unrepresentable today — pause-and-resume-next-
day behavior proposed; flagged, not blocking. *Recommendation: accept table as
flagged-evaluation defaults; all values tunable (OD-2 norm 15 RPM retained).*

**DB-09 — Authority inputs first release (OD-5, #10).** **Chapter-only
profile**: no user/series canon storage ships in this delivery; absent
authorities fingerprint as stable empty values (never null/omission);
model-derived findings persist as series-update **candidates only**; promotion
UX/workflow is a separate future task. *Recommendation: accept (already the
design lean; made explicit for acceptance).*

**DB-10 — Small-chapter / no-work analysis skip (OD-4, #11).** No-translatable-
work/textless/compatible-profile skips are settled. The bypass **rule shape**:
skip provider analysis when the entire translatable corpus fits one envelope
(pendingBlocks ≤ envelope block cap AND tokens ≤ envelope input budget) — the
whole chapter is then visible to a single translation request anyway. The
numeric threshold falls out of the accepted envelope budgets and is
measurement-gated with a counterfactual instrumentation field
(`bypassWouldApply`). *Recommendation: accept rule shape; threshold stays
tunable (disabled until Stage-8 evidence).*

## Blocking before product default (tunable during flagged evaluation)

**DB-11 — Initial budgets and gates (OD-3, #13-16).** Envelope structural
experiment **32 blocks / 8 pages** (token/output checks always stricter);
analysis input reserve ~50%; overlap ≤ 1 page / 10%; split tree max **8
attempts** (existing constant) **/ depth 3 / ≤ 4 leaves** (design proposals
pending Director; only the 8-attempt ceiling exists in code today). Stage-gate
numeric
defaults per `feature-flags-stage-gates.md` §2 (PROPOSED-GATE): memory ≤
baseline + 150 MB and ≤ 1.5 GB absolute on a 6-GB device; reader contended
OCR wait p95 ≤ 400 ms; Batch yields native lane ≤ 2 s after an interactive
arrival with a no-starvation bound; first-accepted-page regression ≤ +30% vs
same-commit flag-off baseline; first-run total calls ≤ 2× legacy baseline,
profile-resume ≤ 1.15×; quality defects (terminology consistency, gender
errors) ≤ 50% of baseline; layout round-trip tolerance ≤ 0.5 px; bind-latency
regression ≤ +20 ms; plan size ≤ 256 KiB/page p95. *Recommendation: accept as
initial gates — they exist so a gate can fail; every number is revisitable
with evidence.*

**DB-12 — Native reader-priority thresholds (OD-11, #14).** Mechanism settled
(priority admission in front of the native quarantine; yield between pages;
bounded anti-starvation). Exact time/turn thresholds are device-tuned at
Stage 3 against gate 3.5. *Recommendation: defer values to Stage-3 device
evidence; accept the gate row as the decision rule.*

**DB-13 — Persisted-layout compatibility boundary (OD-12, #5).** Layout
fingerprint T924-FP-07 includes font asset identity + sha256, typeface/style,
measurement flags, planner version, stroke policy version, decode sample
size, page dimensions, and a `platformShapingKey`. **Start maximally
conservative** (key = SDK-int; any mismatch re-plans via the async fallback),
widen only with the measured device matrix. *Recommendation: accept
conservative start (B §7.5).*

## Recorded defaults requiring no decision (acting unless objected)

- Non-AI Batch retains the current coordinator until OD-8 is separately
  decided; nothing in this delivery changes it (T924-R031/R043).
- Configuration snapshot/add-versus-replace UX remains out of scope (OD-9,
  T924-R019).
- `promptText()` is forbidden as the analysis transport (T924-AP-01); whether
  to delete the dead interface surface is deferred to WP5 implementation.
- Checkpoint adoption by Manual/Auto is silent reuse (no user-visible
  provenance prompt) — UX revisit only if diagnostics show confusion (C8c).
- Series-canon scope domain (manga vs source vs global) deferred; chapter-only
  first release is unaffected (D §8.5).
