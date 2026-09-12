# T924 Stage 6 — Progress report

Task: T924 chapter-profile contextual-AI Batch translation pipeline (S6, WP6).
Branch: `t924/batch-profile-pipeline` · Worktree: `..\TachiyomiAT-t924-impl`.
Companion reports: `s6a-envelope-report.md` (slice A implementer report).
Review: `../wave6-review.md` (slice A).

## Wave 6 — 2026-09-06 — S6 slice A COMPLETE

Slice A (envelope dispatch: ENVELOPE_PLAN ST-11 → TRANSLATE ST-12, with
TX-21 per-envelope revalidation, TX-20 provenance commits, DR-A Option 1
retention, crash-resumable translation) ran the full loop and is COMPLETE:
implement → verify → review ACCEPT → fixes → re-verify.

- Implement — commit `2e99ffb` "feat(translation): T924-S6 slice A envelope
  dispatch — TX-21 revalidation, TX-20 provenance commits, DR-A retention,
  crash-resumable translation" (12 files, +2621/−27; parent `28a75c5`).
  Deliverables (`s6a-envelope-report.md`): `EnvelopePlanPublication`
  (SC-20/SC-10 — plan sidecar + manifest `envelopePlan` pointer in ONE
  `publishSidecarPointers` transaction; fingerprint recomputed-and-verified
  before any byte; byte-identical republication idempotent; `readValidatedPlan`
  = ST-30 never-partially-trusted); `ProfileEnvelopeExecutor` (serial
  one-envelope-in-flight dispatch riding the EXISTING
  `translateAiChunkWithAdaptiveRetry` under `SharedBatchRequestSublimitGate` —
  DR-C one allowance for all Batch traffic; T924-TX-21 per envelope: BATCH
  lease reacquire never preempting MANUAL, fresh-snapshot identity compare vs
  plan-time inputs, user-edited blocks dropped, manual-completed pages skipped
  and never revoked, drift ⇒ deterministic suffix re-plan with committed
  history untouched, `MAX_CONSECUTIVE_REPLANS = 8` livelock guard);
  T924-TX-20 (FIRST legacy-merge-path touch, additive-nullable,
  reviewer-verified byte-identical for legacy: `TranslationStagePatch`
  trailing nullable `profileContentFingerprint` + `envelopePlanFingerprint`,
  both-null fast path before any manifest read, mismatch rejects the whole
  patch before any block mutation, rejected commits never advance the
  frontier); DR-A Option 1 retention (refusal = whole-response discard +
  TERMINAL; AMBIGUOUS_PROTOCOL = nothing commits with `fullyCovered` = ALL
  planned block ids present — page atomicity; MISSING_ONLY = only
  fully-covered pages commit; retained pages commit BEFORE a typed pause);
  `ChapterProfileBatchCoordinator` PROFILE_FROZEN → ENVELOPE_PLAN → TRANSLATE
  from BOTH the freeze and the zero-OCR reuse paths (checkpoint adoption for
  block-less resumed pages — identity-fenced M1 lease+merge, pending pages
  only, failure = typed `CorpusDrift`; ST-11 plan reuse without republication
  when the fingerprint matches; translator null = typed CONFIGURATION pause
  with the plan already durable; terminal PAUSED TRANSLATE_STOP_REASON;
  COMPLETE never published — wave-2 F1 still owed, render is Stage 7;
  `decideResume` byte-untouched); `BatchChapterTranslator` FF-01 branch
  passes ONLY the resolved contextualTranslator, legacy OFF branch
  byte-identical.
- Verify — targeted suites 597/0 (implementer + orchestrator; baseline
  581 + 16 new); full `eu.kanade.translation.*` tree 1716/0.
- Review — `../wave6-review.md` ACCEPT, the first clean accept of T924
  (no CRITICAL/HIGH; all nine dimensions pass). Reviewer independently
  reproduced 597/0 (JUnit XML tally tests=597 failures=0 errors=0
  skipped=0) + legacy-adjacent suites (`ChapterTranslatorQueueRestoreTest`,
  `manager.*`) green. The three highest-risk checks verified clean at diff
  and behavior level: the first-ever legacy merge-path touch, FF-01 OFF
  byte-identity, ST-11/ST-12 crash-resume. All 5 deviations RATIFIED (incl.
  FP-06 durable home kept OWED, the TX-21.2 checkpoint-fingerprint
  approximation, the checkpoint-adoption design addition).
- Fixes — commit `f2ccb95` "fix(translation): T924 wave-6 review fixes —
  drift-path lease release + ST-11 plan-reuse pin" (2 files, +73):
  F-W6-1 LOW drift/lost-page early-return lease hold (release before both
  early returns, `ProfileEnvelopeExecutor.kt`); F-W6-2 LOW untested ST-11
  plan-reuse branch (coordinator-level pin — run 1 fails terminally before
  any commit, the unchanged run 2 reuses the plan with zero republication:
  same pointer fingerprint, file mtime+bytes unchanged, zero re-OCR, drains;
  `ProfileEnvelopeDispatchTest.kt`).
- Re-verify — targeted 598/0; full tree 1717/0.
- Carried findings (not fixed, by design): F-W6-3 NOTE — pre-existing store
  `mergeTranslationLocked` per-block partial apply, unreachable in this
  slice (the full M4 ladder rejects the whole patch first); flagged for
  Stage 7 layout commits (consider store-level strict mode). F-W6-4 LOW —
  the `lmstudio` vs `lm_studio` bucket-key spelling now has a SECOND site
  (envelope work builder derives the DR-D backend from
  `providerKey.substringBefore(':')`); the provider-package alignment must
  cover both sites.

### NEXT — slice B

- Profile-subset prompt enrichment in `TranslationPrompts.kt`: the envelope
  carries the capped relevant profile subset + range-safe scene context
  (T924-R007; identity/gender evidence rules per design §7).
- `BatchContextFrontier` gap-free history (T924-INV-04 fragmented-resume
  case; missing page fences context).
- The FIRST old-vs-new A/B measurement (gate 5.7: calls/tokens/
  accepted-blocks/time against the same-commit flag-off baseline).
- Gates 5.1-5.8 full matrix (page atomicity across taxonomy classes;
  mixed-response matches DR-A; stale commits fail; envelope-policy-only
  change reuses translations; Manual/Auto completions skipped; gap-free
  frontier; one envelope in flight; provider measurements).

## Wave 7a — 2026-09-06 — S6 slice B COMPLETE — STAGE 6 COMPLETE

Slice B (profile-aware prompt enrichment + gap-free rolling context,
design §7) ran the full loop and is COMPLETE: implement → verify →
review ACCEPT → fixes → re-verify. Review: `../wave7-review.md`.

- Implement — commit `65e4a25` "feat(translation): T924-S6 slice B
  profile-aware prompt enrichment + gap-free rolling context (design
  §7)" (7 files, +1835/−65). Deliverables: `ProfileSubsetMatcher`
  (pure, translator/contextual — envelope-source scan for canonical
  forms/aliases/titles/terms; scene participants + same-form gender/
  pronoun/relationship facts; entity ids `[f-N]` source → target for
  alias linking; caps 24 facts / 4 scenes deterministic frozen-profile
  order; range fences — RANGE_SCOPED overlap, AVAILABLE_FROM ≤ envelope
  first page, chapter-wide always; CONFLICTING gender → do-not-guess
  note); TranslationPrompts ADDITIVE enriched builders (128/0
  byte-additive, reviewer-verified — `profileIdentityGenderRules`:
  referent → profile gender → source evidence → singular-they in
  pinned order; `profileAwareGlossaryPrefix`; `profileAwareRollingPrefix`
  with the verbatim pronoun-marking rule: prior target-language
  pronouns are TRANSLATIONS, never canonical gender evidence. Rides the
  existing `chunk.glossary`/`rollingContext` wire fields — zero
  provider/builder changes; legacy functions byte-identical, pinned);
  `ProfileEnvelopeExecutor` frozenProfile seam (null = legacy shape,
  degraded-but-correct, pinned) + execution-time token recompute per
  envelope over the ACTUAL payload with whole-page-boundary splits only
  (sequential sub-batches, one-in-flight structural) + single
  token-oversized page = typed pause with zero provider calls + bounded
  deterministic context trim (frontier keeps full history);
  observability — counters `promptShapeEnriched`/`promptShapeLegacy`,
  `profileSubsetFactsMax`, `rollingContextPagesMax`, `envelopeSplits` +
  per-envelope logcat lines; tests +15 (matcher determinism/caps/
  fences/entity-id; prompt rule order + fence text; coordinator-level
  through real planner/freeze/publication/retry — enriched glossary
  reaches the provider, gap-free rolling advance, whole-page split
  under real estimator pressure, oversized zero-call pause, legacy
  shape without profile).
- Verify — targeted 613/0 (implementer + orchestrator + reviewer
  independent; one timing flake of `D2ManualBatchInterleavingTest` on
  the orchestrator's first full-suite run, isolated + full rerun
  green); full `eu.kanade.translation.*` tree 1732/0.
- Review — `../wave7-review.md` ACCEPT, the second clean accept of
  T924. Reviewer independent rerun 613/0; no D2 flake on the reviewer
  run. All 5 deviations RATIFIED. Findings: 4 LOW/NOTE.
- Fixes — commit `1ae5874` "fix(translation): T924 wave-7a review
  fixes — per-candidate context reserve + default-deny range fences"
  (3 files, +39/−8): F-W7-1 LOW — the full-range context reserve was
  not a strict upper bound (AVAILABLE_FROM first-page shift + cap-tail
  asymmetry); per-CANDIDATE sub-batch recompute in `splitForTokenFit`
  (matcher pure, dispatch sequential). F-W7-2 LOW — `usableAt` lenient
  null defaults (RANGE_SCOPED without a range / AVAILABLE_FROM without
  a page treated usable); default-DENY both, pin test: malformed scoped
  forms text-match yet stay excluded.
- Re-verify — targeted 614/0; full tree 1733/0.
- Carried findings (documented, not changed): F-W7-3 LOW — the
  `promptShape*` and split counters count BUILT chunks and PLANNED
  splits, not sent requests — recorded caveat for the gate 5.7 device
  readings below. F-W7-4 NOTE — dense single-block pages trim the
  rolling pairs entirely (the source side alone exceeds the 1500-token
  cap); frontier keeps full history; per-line pair truncation is the
  candidate remedy if the Director's A/B shows identity drift on dense
  chapters.

STAGE 6 is COMPLETE: slice A `2e99ffb`/`f2ccb95` (wave 6) + slice B
`65e4a25`/`1ae5874` (wave 7a), both review ACCEPT with fixes landed.
Final verification at `1ae5874`: targeted 614/0; full
`eu.kanade.translation.*` tree 1733/0. Stage 7 (inpaint overlap +
completion, WP7 + WP9 gate) is IN PROGRESS — nothing here records
Stage 7 as done.

Gate 5.7 reading notes for the Director's flag-off/on A/B (device):

- F-W7-3 caveat — `promptShapeEnriched`/`promptShapeLegacy` count BUILT
  chunks and `envelopeSplits` counts PLANNED splits, not sent requests;
  reconcile against the per-envelope logcat lines / provider records
  before quoting request counts.
- F-W7-4 — dense single-block pages send a trimmed rolling context
  (pairs dropped, full history kept at the frontier); if the A/B shows
  identity drift on dense chapters, per-line pair truncation is the
  candidate remedy.
