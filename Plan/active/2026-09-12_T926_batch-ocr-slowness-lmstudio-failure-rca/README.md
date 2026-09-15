# T926 — Batch pipeline RCA: Detect+OCR slowness & LM Studio translation failure

## Director request (2026-09-12)

> "Audit the pipeline of batch translation under the translation module.
> First explain to me on why the Detect and OCR phase is extremely slow
> despite we allocating resources and locking any other resource usage
> during that phase? … Redundant? Model loading? Check and verify with
> evidence.
> Second, I notice that once the DETECT and OCR phase is finish, it goes
> to translation phase but it always fail. Did you paste everything into
> the AI model all at once? Why not in chunk -> get summarized ->
> synthesis from multiple summarized chunks? Also could the parser be
> faulty? Whenever I get to this phase it kept saying 'trying again at
> this time', is it triggering an exhaused API? Is it because of a timer
> of how long it should wait for a response? I was testing it with local
> AI model with LM studio"

Follow-up directive (same day):

> "16k tokens? When I specifically said each model get an allowance of
> 8k token context window in total meaning 8k consist of both input and
> output."

Deliverable: **root-cause documentation only**. No fixes, no code changes.

## Scope

The batch (PROFILE_PIPELINE) chapter translation path:
`BatchChapterTranslator` → `ChapterProfileBatchCoordinator` →
detect/OCR native lane, then the AI lane (analysis chunks → profile
synthesis → translation envelopes) against an LM Studio
(OpenAI-compatible LAN) endpoint.

Audit base: worktree `TachiyomiAT-1.16.8-dev`, branch
`t924/batch-profile-pipeline`, HEAD `d3464d8`.

Method: two parallel read-only investigations (native-lane performance;
LLM-lane failure), followed by Main Leader spot-verification of every
load-bearing claim (mutex, autoregressive OCR loop, HTTP timeouts,
timeout→PAUSE conversion, UI retry string, token-ceiling math).
Verification status is marked per claim in the RCA.

## Deliverables

- `evidence/root-cause-analysis.md` — full RCA with file:line evidence,
  refuted hypotheses, failure-loop walkthrough, and a ranked fix list.

## Standing constraints relevant to proposed fixes

- Ten Never rules (see T925 README): serial detector/OCR native lane is
  a deliberate constraint — OCR-phase fixes must pipeline *around* the
  lane (decode/detect prefetch, I/O off the critical path, per-ROI
  batching within the admitted page), not parallelize native inference
  across pages.
- Director token directive: every model gets an **8,192-token total
  context allowance (input + output combined)**. The LM Studio profile
  currently violates this (16,000) — see RCA §3.1.

## Status

- [x] Investigations dispatched (2 parallel, read-only)
- [x] Key evidence verified by Main Leader
- [x] Director reports delivered (conversation, 2026-09-12)
- [x] Root-cause documentation written (this folder)
- [ ] Fixes — not started; awaiting Director authorization
  (recommended order in RCA §5)
