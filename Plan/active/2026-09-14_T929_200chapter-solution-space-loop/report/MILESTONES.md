# T929/T930 Roadmap — Milestones & Goals

Derived from the verified T928/T929 audits. Priority order honored:
correctness/data safety > resume safety > reader responsiveness > memory > throughput.

## M0 — Director decisions
- [x] DECIDED (2026-09-14): **8k total context window is binding — every model
      locked down.** Compliance fix: cap profiles at 8,192 total (input+output)
      in TranslationContextChunkPlanner + re-derive GlobalEnvelopePlanner
      packing; re-score LAN portfolio under 8k (envelope-enlargement entries
      void; carry-over/routing survive). Known cost accepted: more requests on
      LM Studio (LAN worst case rises toward ~14 h library-scale) and less
      per-page context on dense chapters. Cloud exemption: not granted;
      Director-revisitable only (one-constant change if ever).
- [ ] Approve/deny quota-constant edits + auto-resume-on-launch.
- [ ] Approve T930 Slice A start.

## M1 — Responsiveness & trust (T930: group commit + admission signal)
Goal: the system feels awake; storage stops being the tax.
- Group manifest commit behind flag; event-driven retention; lossless stop.
- Admission signal: tap/queue → visible "Queued" in <100 ms (today 5–10 s silence).
- **Glossary scale fix (Director-reported stall on huge chapters):** make the fold incremental (stop re-mining the whole chapter per page — O(P^2*B) to O(B); seed once per store from `translatedPairs()`, never from the persisted 30-entry map; mutex-held; per-page watermark so re-translations replace, never double-count); hoist the per-call Regex compile to a constant; fold glossary publication into group-commit boundaries (io S5). SCOPE (2026-09-14 red-team): the fold runs on manual + rolling-auto lanes only — batch lanes are glossary-read-only; the reported batch-lane stall must be separately attributed (EXECUTION_ORDER 1.1a) before this item closes. The "recent window cap" is superseded by once-per-store seeding.
- Metrics: durable ops/page ~113 → ~55–65; manifest bytes O(P²)→O(P);
  zero new PAUSE/REJECTED in soak; coexistence matrix green.

## M2 — Kill the stalls (reader-facing)
Goal: reading is never blocked by pipeline pathologies.
- In-pass gap rescan: one tap/defer never pauses the run again.
- Queue steering: any chapter readable in ≤2 min (cloud) / ≤6 min (LAN);
  includes queue-UX truth projections (B2/B3).
- Poll eventization (T929 S4): replace 100 ms status polling with
  event-driven emission.
- Engine warm-up at session open (first page stops paying init).
- Animation completeness: no skipped stages, one vocabulary, refresh visible.
- Config changes allowed mid-run, applied at chapter boundary + pending badge.

## M3 — OCR push-through (the felt pain)
Goal: DETECT+OCR stops being the bottleneck.
- Port T927 decoder batching to device; run-scoped engine profiles.
- Decode off the native permit; lazy per-page fingerprint; batched run records.
- Metrics: OCR 2.1 → ~0.7 s/page; cloud library-scale 3.6 h → ~2.1–2.4 h;
  every first-page wait −~21 s.

## M4 — Parallel windows (the original design intent)
Goal: translation/inpaint/OCR overlap instead of phase-locking.
- Standard lane: translate from first committed checkpoint (no chapter barrier).
- Chapter waves: OCR chapter N+1 while chapter N envelopes wait on provider.
- Metrics: cloud chapter marginal 63.75 → ~35 s (3.6 h → ~2.0 h library-scale).
- Depends on: M1 commit-point contract; steering arbitration + bitmap budget.
- Prerequisite (schedule first): cross-origin bitmap budget (T929 F.5
  tier ceiling) — M4 cannot land without it.

## M5 — LAN & provider headroom
Goal: local-model runs get slack; quality rises for free.
- Profile carry-over (drift-gated): LAN 200 pages ~49 → ~32 min.
- Dual-backend routing; SSE streaming page commits; prompt-assembly fix
  (quality ↑ at zero request cost).
- Depends on: M0 window decision; M1 commit points.

## M6 — Durability at scale (background execution)
Goal: multi-hour runs actually complete.
- Keep-awake policy; download-failure starvation fix; FGS 6-hour cap plan
  (targetSdk 35); targeted fsync at commit points.

## End-state targets (200 pages)
Tap feedback instant · any chapter ≤2 min · LAN ~32 min (16k-world figure;
re-score after the 8k compliance fix) · cloud ahead of reading pace ·
AI-lane quality bar intact or better.
