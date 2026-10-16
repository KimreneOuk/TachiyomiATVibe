---
kind: spec
title: "Round 2 - Auto lag, failure skip, reader display, and settings"
comments: none
---

# Current audit status

**Owner priority update:** slow chapter-list loading and opening the reader with existing translations now take precedence over Auto-generation optimizations. Follow the [entry/loading investigation](../reader-entry) for the revised ranking and source evidence.

**Revision:** `4de121bf` on `inpainting-modes-redesign`. A fresh remote fetch on 2026-10-14 confirmed `HEAD...origin/main = 0 0`; no pull was required. Working tree clean; no production edits, builds, device commands, commits, or pushes.

The stalled child investigations were resumed with the requested `zai-coding-plan:glm-5.3-flash`, maximum reasoning, and no native subagents. **Both agents delivered. The round-2 source audit is complete.** The orchestrator verified their load-bearing claims and recorded corrections, accepted findings and rejected proposals in the follow-up. Device reproduction and performance measurement remain subsequent work.

## Read the current evidence

- [Verified follow-up and experiments](verified-follow-up) - orchestrator source verification, V1-V9, execution dependencies, experiment designs, limits, and cross-agent reconciliation. This is the current consolidated audit authority.
- [Settings inventory and removal map](settings-investigation) - Agent D's inventory and proposed removal scope. Apply the orchestrator's qualifications in the verified follow-up: live legacy fallback/vision controls are removal candidates, not dead behavior; UI hiding alone does not stop reads.
- [Auto and reader investigation](auto-reader-investigation) - Agent C's delivered source map and proposals. Apply the follow-up's qualifications on permit scope, retry scope, timeout liveness and unmeasured duration claims.

## Findings relevant to the owner's targets

| Target | Verified behavior | Recommended direction |
| --- | --- | --- |
| Auto lag and useful targets | FIFO prepared work remains current across same-session anchor jumps; in-progress native preparation finishes inline before reconciliation | Prioritize the visible page and retire obsolete queued work before a provider call starts; keep paid-call drain and fences |
| Failure skip | Ordinary failed pages already allow later pages inside the fixed window to run; existing tests assert it | Replace failed ahead positions with farther useful targets under a bounded horizon and provider outage policy |
| Provider retries | External reconciliation can reopen an eligible paused page; the durable attempt cap governs interrupted/crashed calls, not all completed failures | Separate per-session cooldown/attempt policy while preserving provider timestamps, manual retry and the crash ledger |
| Reader original-to-clean swap | `displayImageName` itself is gated on full translation/render readiness even when a durable cleaned file exists | Experiment with a separate durable clean preview, retaining the last committed result and gating text on layout readiness |
| Prepared-page latency | Auto flushes lazy persistence before recycling the bitmap and handing off to translation | Measure per-page and unrelated write waits before replacing this ownership barrier |
| Translation-only resume | Reuses OCR/inpaint but loads the cleaned JPEG and writes another versioned cleaned JPEG before translation handoff | Reuse validated cleaned artifact identity without re-encoding unchanged pixels |
| Exceptional stall | Outer drain timeout exits the only consumer; producer liveness needs a regression | Qualify reachability: normal HTTP/render deadline is 120 s, below the 210 s drain; ordinary HTTP timeout becomes a caught IOException |
| Settings and cold setup | Two unused preference accessors; legacy QNN resolution precedes successful LiteRT selection and also supplies LiteRT batch-policy input | Remove dead settings; separate cheap batch-policy selection from fallback-only native probes; retain checks for live fallback/vision paths |
| Inpaint lifetime and measurement | Detached inpaint lacks inherited trace context and its Auto await occurs outside the native timer/permit; resumed inpaint can have nested spans | Verify lifetime/deadline/quarantine and fresh/resume accounting before assigning savings or contention magnitudes |

## Evidence boundaries

The exact causes and duration of device lag remain unmeasured. Existing tests were read, not run. No measured improvement is claimed. The earlier assertion that 17.3 s wall time was definitively explained by reading cadence, and that the governor never throttles, is withdrawn as stronger than the available matched evidence. Auto run traces start at native admission; reader arrival, run wall, and schedule wall must be reconciled separately.

Original O1's readiness predicate is confirmed, with a more precise full path in V3. `originalImageFallback` is a cleaned-write failure policy; toggling it earlier does not change Auto's inpaint-before-provider dependency and must not counterfeit layout readiness.

## Next engineering sequence

1. Reproduce V1/V2/V8 with controlled scheduler tests; preserve ordinary failure continuation and useful remote overlap.
2. Prototype validated cleaned-artifact reuse for translation-only Auto resume (V9), with file/hash and recovery checks.
3. Measure cleaned durable commit to first visible frame and compare clean-preview policy; measure prepared-page flush and conditional probes; repair trace attribution and detached inpaint lifetime/deadline coverage.
4. Apply the dead-key cleanup and deliberate model-driven settings/default policy in a separately authorized implementation pass.

The audit remains read-only. Implementation and device measurement are subsequent work, not completed results.
