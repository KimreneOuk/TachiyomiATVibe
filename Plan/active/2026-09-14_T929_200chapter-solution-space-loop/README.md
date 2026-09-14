# T929 — Adversarial consensus on T928 + solution-space mapping loop

> **BASELINE CORRECTION (2026-09-14, Director): "200 pages, not 200
> chapters."** Rescaled numbers and re-ranked portfolio are in
> `report/DIRECTOR_REPORT.md` § "Corrected baseline". Per-page constants were
> already adversarially verified, so the correction is arithmetic rescaling;
> the 200-chapter tables remain as the library-scale scenario.

## Director request (2026-09-14)

> "Launch multiple more agents to see if they agree with the report. Keep going
> into a repeatable loop until you map out all possible solutions with
> imaginative scenarios. Keep 200 chapters at the baseline. People want to read
> fast while getting good quality."

## Audit base

Branch `main` @ `9c19ad0`, tracked files only. Standing directive from T928
still binding: CODEBASE OVER DOCUMENTATION — code at HEAD is the only evidence
of behavior; docs/prior plans are rationale context and may be stale.

## Inputs

- T928 Director report: `Plan/active/2026-09-13_T928_translation-pipeline-layer-audit/report/DIRECTOR_REPORT.md`
- T928 slices: same folder, `team/io/report.md`, `team/ui/report.md`, `team/sched/report.md`

## The 200-chapter baseline (binding for all rounds)

One batch queue containing **200 chapters** of a single manga, average 15
pages/chapter (sensitivity range 10–20) ⇒ 2,000–4,000 pages. Quality bar:
the AI profile lane (corpus analysis → profile freeze → envelope translation,
glossary continuity) is the reference quality; any solution trading quality
for speed must say so explicitly and quantify the trade.

Scenario axes every solution is evaluated against:

- **A. Chase** — user starts reading chapter 1 while the batch runs; reader
  consumes chapters nearly as fast as they complete.
- **B. Jump** — user jumps to chapter 100 of the queue; metric is
  time-to-first-readable-page (TTFP) for that chapter.
- **C. Partial** — user opens a chapter whose OCR is done but translation
  is not (current design makes this the norm: whole-chapter OCR barrier).
- **D. Cold start** — first run of a fresh install (model deploy, session
  creation, no warm cache).
- **E. Provider class** — fast cloud endpoint vs LM Studio on LAN
  (8k total token window per request, per T926 directive).
- **F. Crash/resume** — process death at chapter 137; cost to resume.

Metrics: TTFP per scenario, total wall-clock for all 200 chapters, reader tap
responsiveness during the run (manual attach/native-lane wait), quality proxy
(% pages through AI lane, glossary/context continuity, per-page envelope
fidelity), durable-write count.

## Loop protocol

Repeatable loop, converging when a round produces no genuinely new solutions:

- **Round 1 (this dispatch):** 3 adversarial verifiers (io / ui / sched) —
  do independent agents agree with T928? + 1 throughput-model builder
  grounding the 200-chapter math in code constants.
- **Round 2:** 4 solution mappers (scheduling/pipeline, durability/I-O,
  provider/quality, reader strategy) each produce a solution catalog scored
  against the baseline scenarios, using Round 1 verdicts + model.
- **Round 3:** red-team the merged catalog (invariant violations, quality
  regressions, missed interactions); any NEW solutions found spawn a targeted
  Round 4; else converged.

Hard constraints carried from T928 (no-gos): native parallelism/preemption;
provider parallelism beyond quota gates; removing `.bak` rotation or
sidecar-before-pointer; revoking committed display; partial commits.
Priority order: correctness/data safety > resume/crash safety > reader
responsiveness > memory safety > throughput.

## Deliverables

- `team/verify-{io,ui,sched}/report.md` — AGREE / NUANCE / DISAGREE verdicts per finding, with code evidence.
- `team/model/report.md` — parametric wall-clock model + baseline numbers.
- `team/solutions-{sched,io,provider,reader}/report.md` — solution catalogs.
- `team/redteam/report.md` — attack results on the merged catalog.
- `report/DIRECTOR_REPORT.md` — consensus outcome + solution map + recommended portfolio.

## Loop outcome (converged after 5 rounds, 13 agents)

- Round 1: verifiers CONFIRM the T928 core with corrections (io counts 10-20% higher; ui +10 mechanisms; sched 210s starvation overstated → corpus-gap PAUSE, confirmed in code); model 3.6h/12.1h survives exact re-derivation.
- Round 2: 66 solutions in 4 domains.
- Round 3: red team — 42 safe/22 rework/2 reject, 10 conflicts reconciled; NOT converged (4 unmapped classes).
- Round 4: 25 more solutions (native OCR/EP; download-ahead + background durability); 2 red-team premises corrected (download handoff + FGS already exist).
- Round 5: CONVERGED — 91 entries total, 66 SAFE / 23 NEEDS-REWORK / 2 REJECT; no unmapped classes.
- Full synthesis: `report/DIRECTOR_REPORT.md`.

Specialists return only: "Completed. <one-line result>. Report: <path>"

---

## Task 1.1a — Attribution of Director-Reported BATCH-Lane Stall

### Summary Verdict
**CONFIRMED (REAL STALL — CAUSE FULLY ATTRIBUTED).**
The Director-reported BATCH-lane stall when translating chapters with huge text regions was investigated across the batch pipeline (`BatchChapterTranslator`, `ChapterProfileBatchCoordinator`, `GlobalEnvelopePlanner`, `ProfileEnvelopeExecutor`, `StreamingChunkPlanner`, `AnalysisChunkPlanner`, and `BatchLaneWorkers`).

The stall is **not** caused by the single-page manual glossary fold (which never executes in the batch lane; the batch lane only reads `glossaryPrefix`). Instead, the batch stall is caused by a compounding sequence of strict token and block caps, an unrecoverable `PAUSED` state without an auto-retry timer, and an unfulfillable-budget fit hack:

### Root Cause Mechanisms & Citations
1. **Strict Page-Atomicity Envelope Rejection (`GlobalEnvelopePlanner.kt:43-52, 196-208`)**:
   - The global envelope planner enforces rigid caps per envelope: `maxBlocksPerEnvelope = 32`, `maxEstimatedInputTokens = 16,384` (reducing to 12 blocks / 4,096 tokens under 8k).
   - Invariant: A page is **atomic** and cannot be split across envelopes.
   - Any page with $>32$ blocks or $>16$k tokens is rejected by `GlobalEnvelopePlanner.planEnvelopes` with `EnvelopePlanResult.Rejected("Page pXX has YY blocks exceeding limit 32")`.
2. **Untimed Terminal Pause in Coordinator (`ChapterProfileBatchCoordinator.kt:1327-1352`)**:
   - When the coordinator receives `EnvelopeWorkBuild.PlannerRejected`, it logs a structural failure and transitions the chapter to `BatchPass1Status.PAUSED` with `nextEligibleRetryAtEpochMs = null`.
   - Because `nextEligibleRetryAtEpochMs` is `null`, `ChapterTranslator.kt:373-392` never automatically resumes the chapter. The batch queue stalls indefinitely on this chapter.
3. **Secondary Execution-Time Enriched Token Cliff (`ProfileEnvelopeExecutor.kt:396-411, 454-463`)**:
   - Even if a page passes the static planning stage, enriched context (scene summaries, entity lines, profile prefix, rolling pairs) is added at execution time.
   - If an enriched single-page envelope exceeds `availableTokens`, `ProfileEnvelopeExecutor` flags it as `batches.oversized` and immediately emits `EnvelopeDispatchResult.Paused` with `nextEligibleRetryAtEpochMs = null`, freezing the batch run.
4. **The 256-Token-Floor Fit Hack (`StreamingChunkPlanner.kt:249-254`, `ProfileEnvelopeExecutor.kt:436, 858-875`)**:
   - For legacy identity split envelopes, no token check is performed before dispatch.
   - `StreamingChunkPlanner.effectiveOutputCap` computes `available = maxContextTokens - safetyMargin - promptTokens - protocolReserve`. When `available` is negative, it clamps to negative via `.coerceAtMost(available)` but is then forced back up to `256` via `.coerceAtLeast(minOutputTokens)`.
   - The prompt is dispatched over-budget with a 256-token output allowance. The model either errors or produces truncated JSON lacking block IDs, triggering `ambiguousProtocol` and `discardAndPause(held)` (`ProfileEnvelopeExecutor.kt:635-650`).
5. **Local LLM Analysis Timeout (`AnalysisChunkPlanner.kt:47`, `AnalysisEngineTransport.kt:49-64`)**:
   - Analysis chunks sent to local LLMs (e.g. LM Studio) frequently exceed the 60s blocking read timeout on huge text chapters, producing a `SocketTimeoutException` that converts to a 1s tight pause/retry loop.

### Remediation Pointers
1. **Sub-page splitting fallback**: When a single page exceeds `maxBlocksPerEnvelope` or `maxEstimatedInputTokens`, relax strict whole-page atomicity for that single page and allow sub-page block chunking rather than rejecting the entire chapter.
2. **Context shedding before oversize pause**: In `ProfileEnvelopeExecutor`, strip optional context (scenes, entities, rolling pairs) down to bare source blocks before declaring a page oversized.
3. **Eliminate 256-token-floor fit hack**: In `StreamingChunkPlanner`, ban forcing negative available tokens to 256. Fail closed or pause with an explicit `OVERSIZED_BUDGET` diagnostic rather than dispatching a guaranteed-to-fail prompt.
4. **Right-size analysis chunks & enforce 8k**: Implemented in Phase 1 Task 1.2 (in $\le 4,608$, out $\le 3,072$).

