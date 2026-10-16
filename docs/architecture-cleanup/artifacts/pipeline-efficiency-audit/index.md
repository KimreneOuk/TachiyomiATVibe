---
title: "TachiyomiATVibe Pipeline Efficiency & Architecture Audit"
kind: spec
comments: none
---

# TachiyomiATVibe Pipeline Efficiency & Architecture Audit

**Orchestrator:** Lead Performance Architect
**Revision under audit:** branch `inpainting-modes-redesign`, commit `0f00a45f9fa1c167953caf43a1858916983a4e78`
**Target hardware:** Snapdragon 8 Gen 3 / Adreno 750 (OnePlus PKG110, Android 16)
**Method:** static analysis only (user-directed; no device workloads). All quantitative claims carry explicit confidence.
**Evidence:** [evidence-register](evidence-register) — facts F1–F12, agent findings B1–B7, A0–A11, tooling T1.

## Workforce

| Agent | ID | Role |
| --- | --- | --- |
| Orchestrator | 66e6d399 | Scope, contracts, verification, prioritization, final report |
| Agent A — Pipeline Investigator | 5de46600 (fork 26504a64, report recovered via transcript after reply-channel failure) | Code tracing, execution maps, mechanism evaluation, redundancy audit, 15-question answers |
| Agent B — Research Analyst | 0f24fb27 | Benchmark audit, external research, falsifiable hypotheses, cross-audit |

## Deliverable E — Executive Decision Summary

### The single most important finding

**The historical 17.3 s "page wall time" is not a pipeline-compute measurement.** Wall is flat (17.0–20.4 s) while stage sums vary 2× (3.8–7.5 s) across pages r2–r9; r10 inverts (largest OCR, smallest wall). The run bracket contains **15 span classes the report never quoted** — lease/native-queue/prepared-queue/governor/permit waits plus storage-drain/commit/flush, engine setup, layout and render (A0, F11). The app already emits the complete decomposition in `schedule_end`; the report simply never consumed it. **No app changes are needed to explain the gap — one logcat capture parsed by the ready analyzer (T1) closes the question.**

**Reconciliation model (post cross-audit, adopted):** H2 is the **ledger** (where the ~12 s mechanically sits: the 15 unquoted wait/persistence spans); the event-driven **human reading cadence is the clock** (why the wall is flat). Decisive evidence: least-squares slope of wall on stage-sum ≈ **0.30** (r² ≈ 0.08) across r2–r9 — pure queue-saturation would give slope ≈ 1.0 (density propagates), pure dwell-hiding ≈ 0. 0.30 means the wall bracket starts at reader-side page arrival/admission, with most service variance absorbed by dwell. The governor (H3) is **dead as a gap contributor in both modes**: 1 request/page (single/standard, `translateOnce`) or ~0.2/page (AI envelopes) against a 60 rpm / maxInFlight=1 ceiling with multi-second page work — it never throttles (A-Q6 + B cross-audit resolution).

### Three highest-value opportunities

1. **Measure before optimizing: run the reconciliation capture** (M1–M3 in [measurement plan](measurement-plan)). Effort low, risk none, confidence high that it resolves the ledger/clock attribution definitively. Blocked only by device authorization (F9). **Prerequisites folded into EXP-0 per cross-audit:** emit `admission_lag` (admit_ts − page_view_ts); log inpaint execution target (QnnDiagnostics/LiteRT path counters); parse PROVIDER_GOVERNOR_WAIT + PROVIDER_WINDOW_WAIT per page (already spanned); r10 discriminator (arrival/admission vs wall-start in raw capture).
2. **R2 FIRST among code changes — fix the nested-span double counting** (SinglePageOnnxPhase.kt:1110-1117 vs RoiPageRecognitionEngine.kt:514/540/594/921, verified). Every downstream number — including A's own stage sums and the vacuous `criticalPathMs ≡ wallMs` field — is distorted by it. Re-derive critical path from the span tree. Zero-risk instrumentation correction.
3. **R1 — detached inpaint async** (SinglePageOnnxPhase.kt:1182, verified): outside quarantine/permit, cancellation doesn't propagate, false overlap (nativeGuard serializes it against the next page's OCR anyway), unattributed CPU contention. **Magnitude UNVALIDATED** (cross-audit): 10–30 % OCR relief was A's estimate; it hinges on where LiteRT inpaint actually executes (HTP vs silent GPU→CPU fallback). Gate on execution-target telemetry (EXP-1). Rearranged work, not reduced work — dispatch overhead with zero overlap benefit.
4. **R6+R7 — batch persistence amplification** (full store drain before every OCR checkpoint, PreflightWorker.kt:198-201; per-page ~360 KB manifest CAS, ChapterTranslationStore.kt:3824-3895): 0.3–2 s/page plausible in batch (B cross-audit), invisible to streaming benchmarks (streaming coalesces). Chapter completion time is the user-facing metric. Correctness-sensitive — EXP-2.

### What should explicitly NOT be done

- **Do not raise native concurrency (remove the floor=1 pin).** Deliberate E20a correctness-proofing (AdaptiveKnobController.kt:79-80); E21 gates the flip. Both agents concur.
- **Do not remove or merge lease/fence/quarantine/journal-credit machinery.** All judged sound and load-bearing by both agents independently.
- **Do not re-baseline against the historical report's numbers** (B2: scope mixing, n=2 claims). Re-baseline after EXP-0.
- **Do not chase micro-wins ahead of the ledger:** R9 (<5 ms) skip; R3 only if trivially safe; R5 fix for robustness (runBlocking inside `computeIfAbsent` = thread-starvation/ANR hazard), not perf.
- **Do not act on the historical report's GPU-rejection for OCR** — invalidated by its own bucketing (B6); OCR orchestrator's authority, with a numerical parity gate (LiteRT FP16 silent-miscompute class).

### Principal technical obstacles

- Device measurement is authorization-gated; static analysis cannot resolve magnitude questions (r10 cause, R1 contention share, R6 flush cost on real storage).
- R1's fix touches native-lane semantics — the highest-risk area the architecture doc flags for tests.
- Persistence seams (R6/R7) interact with checkpoint identity and crash recovery — correctness-critical by design.

### Recommended next engineering action

Authorize one measurement session (measurement plan M1–M3, ~30 min device time): capture manual + auto + batch on a fixed chapter, run `analyze_wall_budget.py`, and let the `schedule_end` decomposition attribute the 11.7 s. Then implement the two instrumentation corrections (R2 nested spans, unquoted-span naming) — small, zero-risk — before any behavior change.

## Deliverable A — Pipeline Execution Maps (condensed; full traces in evidence register A-section)

**Manual:** scheduler admission (startRun pre-launch, LEASE_WAIT) → `translateSinglePage` → native lane (DevicePagePermitGate cap 1 → EngineLane → NativeRunQuarantine) → decode in-permit → outer OCR span wrapping analyze() [nested DETECT/SEGMENT/OCR + uninstrumented panel detect + dedupe] → **detached inpaint async (R1)** → drain deferred publications outside permit → TRANSLATE (governor wait inside span; ≤2 partial retries) → await inpaint → persist cleaned → RENDER_PERMIT_WAIT → LAYOUT/RENDER → STORE_FLUSH → STORE_COMMIT.

**Auto:** 150 ms reader debounce → window reconcile (event-driven, conflated channel, no cadence) → ONE native prep lane + preparedChannel (2/4/6 by memory tier) → prepare: OCR → detached inpaint → await → persist cleaned → publish prepared → PREPARED_QUEUE → single translate consumer (210 s NonCancellable drain grace) → overlap prepare(N+1) with translate(N) [false overlap on inpaint vs next OCR via nativeGuard]. Runs start at admission → queue waits inside totalMs.

**Batch:** chapter schedule (ENGINE_SETUP under native lane — only instrumented init) → LazySourceFingerprints (runBlocking hash) → PreflightWorker per page: reuse probe → OCR (240 s watchdog) → **store.flush() before every checkpoint (R6)** → lanes: per-page translate or ~5-page AI envelopes with OverlapScheduler inpaint lane → native worker decode off-permit → inpaint **re-decodes (R4)** → FinalizeWorker serial drains → single COMPLETE publication (one-retry CAS). Manifest CAS per page (R7); envelope coalescing exists (32 pubs) but not for other seams.

Cross-cutting: correctness-critical serialization is everywhere by design — leases, generation fences, quarantine retention, journal credits, atomic candidate→commit publication. None of it was found redundant.

## Deliverable B — Performance & Waste Audit (synthesis)

**Confirmed waste (FACT, small-to-medium):** R3 double color recompute; R4 batch re-decode; R5 double SHA-256 + runBlocking; R9 pref reads (<5 ms). **Confirmed defects (FACT, no runtime cost):** R2 nested spans; `criticalPathMs` ≡ `wallMs`; missing `segMs` in analyzer; engine init invisible on reader paths; panel detection unspanned (R10 — magnitude unknown, measure first). **Suspected, high value:** R1 false-overlap contention; R6 per-page full flush; R7 manifest CAS; governor/lane queue waits (the gap itself). **Appears redundant but is necessary:** floor=1 concurrency; LazySourceFingerprints caching; quarantine retention; journal credit gating. **Necessary-work alerts:** webtoon sliding windows legitimately multiply vision passes; BallonsTranslator-style stage caching exists for cleaned images but not for masks/OCR (mask caching is a safe opportunity per B's alternatives table).

## Deliverable C — Ranked Optimization Backlog (order settled by A×B cross-audit)

| # | Finding | Class | Expected benefit (uncertainty) | Risk | Effort | Confidence | Recommendation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **EXP-0** reconciliation capture (M1–M3 + T1; + admission_lag, inpaint-target logging, governor-wait parsing, r10 discriminator) | Measurement | Attributes the entire gap; prerequisite for all magnitudes | None | Low | High | **Run when device authorized** |
| 2 | R**2** un-nest OCR/INPAINT spans; re-derive `criticalPathMs` from span tree; add segMs to analyzer; span engine init + panel detection | Instrumentation defect | Correct analytics — every other number is currently distorted | None | Low | High | **First code change** |
| 3 | R6**+R7** narrower checkpoint flush + extend manifest-write coalescing to non-envelope seams | Suspected inefficiency | 0.3–2 s/page in batch (plausible band; unmeasured); ANR history | Medium (checkpoint identity, CAS protocol) | Medium | Medium | Investigate → EXP-2 |
| 4 | R**1** parent the detached inpaint job under quarantine/permit, preserve nativeGuard semantics | Suspected inefficiency (magnitude unvalidated) | Cancellation correctness + unattributed contention removal; OCR relief unknown until execution-target telemetry lands | Medium (native timing) | Medium | Medium | EXP-1, gated on telemetry |
| 5 | R5 pass fingerprint into decode; remove runBlocking inside computeIfAbsent | Confirmed redundancy + thread-starvation/ANR hazard | ~10–40 ms/page + carrier relief + robustness | Low | Low | High | Quick win (robustness framing) |
| 6 | **R4** thread nativeHandoff through inpaint drains | Confirmed redundancy | ~19 ms + churn per batch page | Medium (memory ownership) | Medium | Medium | Later |
| 7 | **Webtoon sliding-window multiplication** (WebtoonSlidingDetector.kt:152-230): det/seg/OCR work ×2–4 per tall page | Structural cost, uninstrumented | Changes webtoon per-page economics entirely; measure via span tree after R2 | Low | Low | Medium | Measure with EXP-0 class captures |
| 8 | R**3** drop inpaint-side color recompute | Confirmed, tiny | ~10–100 ms/page (low certainty) | Low | Low | Medium | Only if trivially safe |
| 9 | Mask/OCR-result caching keyed (sourceHash, modelRev) | Proposal | Zero-cost repeats/re-reads | Low | Medium | Medium | Design note |
| 10 | **R10** mode-gate panel detection | Suspected | Unknown until spanned | Medium (reading-order quality) | Low | Low | Measure first |
| 11 | R9 pref-read caching | Confirmed, tiny | <5 ms/page | Low | Low | High | Skip (cross-audit verdict) |

Not actioned (by design): concurrency flip (E20b/E21 — deliberate safety phase); XNNPACK session-options trio + SVTR fixed-bucket GPU re-test (OCR orchestrator's files; evidence forwarded: F12, B6, with parity-gate requirement); LiteRT migration.

## Deliverable D — Experimental Roadmap (dependency order; refined by cross-audit)

1. **EXP-0 Reconciliation capture** (items 1–2 of Deliverable C): M1–M3 protocol. Required emissions before crediting any magnitude: `admission_lag` per page (admit_ts − page_view_ts); per-page inpaint execution target (QnnDiagnostics aotHtpRuns/cpuRuns/gpuRuns + LiteRT path); governor waits parsed per page (spans already exist). Decision rule: gap explained when ≥90 % of (wall − Σquoted) attributes to named spans across ≥9 matched pages, ≥3 reps; plus r10 discriminator (arrival/admission vs wall-start). Rollback: none (read-only).
2. **EXP-1 R1**: after R2's clean spans + execution-target telemetry: compare OCR duration distribution vs nativeGuard wait share on dense pages; prototype parenting the inpaint job; acceptance: no visual regression (existing inpaint quality suite), cancellation propagation test passes, nativeGuard serialization preserved, OCR P95 neutral-or-better. Rollback: revert single commit.
3. **EXP-2 R6/R7**: batch chapter with/without narrower checkpoint flush and coalesced non-envelope manifest writes; acceptance: checkpoint resume correctness on force-stop, zero lost checkpoints, chapter wall P50 improvement. Rollback: revert.
4. **EXP-3 quick-win pass** (R5 robustness fix, R3 if safe): single-page mode; acceptance: existing translation unit tests, stage-sum deltas match prediction. Rollback: revert.

Each experiment: single variable, matched chapter (downloader corpus), ≥3 reps, warm/cold split per M3, isolated worktree.

## Audit-process notes

- Agent A's reply channel failed repeatedly; report recovered via fork transcript (26504a64). Delegated-agent effectiveness issue, not app issue — tracked separately from findings.
- All OCR-owned paths untouched; cross-cutting evidence for the OCR orchestrator: B6 (stale GPU rationale), F12 (spinning/XNNPACK config), B5 (LaMa overhead band).
- Measurement constraint (F9): all magnitude claims UNVERIFIED until EXP-0 runs.
