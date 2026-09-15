# T929 Director Report — Adversarial consensus + solution map

> **BASELINE CORRECTION (2026-09-14, Director):** the baseline is **200 PAGES,
> not 200 chapters**. Section "Corrected baseline" at the end rescales every
> number and re-ranks the portfolio; the 200-chapter tables below are retained
> as the library-scale (overnight pre-translation) scenario. All per-page
> constants were already adversarially verified, so the correction is a
> rescaling of verified arithmetic, not a re-audit.

Loop: 5 rounds, 13 independent agents, base `main` @ `9c19ad0` throughout.
All evidence is code-cited; docs used as rationale only (standing directive).
Reports: `team/verify-{io,ui,sched}`, `team/model`, `team/solutions-{sched,io,
provider,reader,native,background}`, `team/redteam-{invariants,arithmetic,
convergence}`.

## Part 1 — Do other agents agree with T928?

**Yes on the architecture; with corrections that make it modestly worse, not better.**

- **io**: R1-R10 (redundancy findings), 7 manifest publications/page, O(P²)
  bytes, no-fsync — all re-verified. True op counts are **10–20% higher** than
  T928 stated (missed exists-guards, display-base probe, SAF findFile). OPT-2's
  pure in-memory CAS premise is broken by a second writer class (probe/rescue
  stores) — needs registry coordination.
- **ui**: all 18 claimed mechanisms verified, none fabricated; **10 additional
  mechanisms** found (silent tap-death paths, stranded sweep, resume/preference
  re-entry stalls, AI-lane vocabulary gap, 180 ms crossfade missing from the
  delay inventory). One sub-claim contradicted (there IS memory-pressure UI
  suppression). T928 animation fix #4 conflicts with the terminal-latch
  guarantee and was redesigned (intent-identity precedence tier).
- **sched**: resource map fully confirmed (native lane, governor, leases, no
  warm-up, unused bitmap ceiling). **One material correction**: the "manual
  starves 210 s during batch" story is overstated — page leases are released
  after each OCR checkpoint, so starvation is a narrow per-page race. The real
  pathology is the **corpus-gap PAUSE**: a manual tap or memory-defer that
  creates OCR gaps pauses the entire run at preflight
  (ChapterProfileBatchCoordinator.kt:556-570, confirmed in code by Main Leader).
- **model**: the 200-chapter math survived exact re-derivation by an adversary
  (63.75 s/chapter flagship+cloud, 218.75 s LAN → **3.54 h / 12.15 h**).

## Part 2 — The 200-chapter baseline (verified numbers)

200 chapters × 15 pages (2,000–4,000 page range):

| Quantity | Flagship + cloud | Flagship + LM Studio LAN |
|---|---|---|
| Total wall-clock, current design | **3.6 h** (native-OCR-bound) | **12.1 h** (provider-bound) |
| Binding bottleneck | OCR = 31.5 of 63.75 s/chapter | analysis 76 s + envelopes 108 s of 218.75 s/ch |
| Time to first readable page, ch 1 | 49 s | 144 s |
| Jump to ch 100 under FIFO queue | 1.77 h | 6.0 h |
| Share of a chapter's time after its OCR completes | 48–51 % | 85 % |
| Crash at ch 137 | zero-work resume verified (checkpoints adopted, COMPLETE short-circuit) | same |

Scenario D (cold start) has an unowned precondition: the batch only consumes
**local** page images — on an undownloaded library, cold start is network-bound
and unbounded (fenced download→translate handoff exists; download-**failure**
starvation is the real gap). Scenario F repeats without keep-awake: **no wake
locks exist anywhere in the translation path**; a dataSync foreground service
exists; resume after process death never auto-starts (deliberate); targetSdk is
34 today — the 6-hour FGS cap looms at 35.

## Part 3 — The solution map (91 entries, 6 domains)

Red-team tallies: **66 SAFE / 23 NEEDS-REWORK / 2 REJECT** (the 2 rejects are
correctly-mapped no-gos: native preemption, second native lane). Ten
cross-domain conflicts were reconciled (steering arbitration owned by
scheduling; a process-wide bitmap budget as prerequisite for any lookahead;
carry-over owned by provider-freeze semantics; io commit-point contract wins
over streaming).

Top levers by verified arithmetic:

| Lever | Effect (200 ch) | Status |
|---|---|---|
| **Reader-position queue steering** (S7/B1) | jump-to-ch100: 1.77–6 h → **≤2 min cloud / ~6 min LAN** | SAFE |
| **OCR engine profile per run + decoder step batching** (native, T927 lab path) | cloud 3.6 h → **~2.1–2.4 h** (OCR is the binding bottleneck); ch-1 TTFP −21 s everywhere | run-scoped engine selection needs rework (rebuild-in-permit interaction) |
| **Cross-chapter profile carry-over** (S6+P8 merged) | LAN 12.1 → **8.0 h**; cloud → 2.9 h | gated: drift bound + full re-analysis fallback |
| **Download→translate wave pipelining** (S11) | cloud 3.6 → ~2.0 h | needs X1 arbitration + bitmap budget first |
| **In-pass gap rescan** (S8) | kills the corpus-gap PAUSE hazard | SAFE |
| **Event-driven retention** (io S4) | removes **~200–600 k** SAF calls/run | SAFE |
| **Group manifest commit** (io S1) | −44–48 % durable ops/page | commit-point set must include OCR checkpoints + promotions |
| **SSE / sub-envelope streaming commits** (P3) | pages readable progressively during envelope | SAFE, gated on io commit points |
| **Dual-backend provider lanes** (P5) | legal parallelism (per-backend quota buckets verified in code) | SAFE |
| **Prompt-assembly quality fixes** (P12) | quality ↑ at zero extra requests | SAFE |
| **Animation truth fixes** (R1/R3/R5/R6 + B2/B3 queue UX) | silent tap-deaths become visible; 200-entry queue navigable with ETA | SAFE |

Scenario coverage after mapping: A/B/C/F served; D needs the download-ahead +
download-failure entries; E (LM Studio 8k) is only served if the window
question below is decided.

## Part 4 — Decisions needed from the Director

> **DECISION UPDATE (2026-09-14, post-publication):** item 1 is DECIDED —
> **8k is binding on every model** (see MILESTONES M0). The 16k
> re-affirmation recommendation below is SUPERSEDED; it is retained for
> rationale only. Items 2 and 3 remain open.

1. **LM Studio token window: code says 16 k, your directive said 8 k.**
   `TranslationContextChunkPlanner.kt` sets LM_STUDIO to 16,000 and the
   envelope planner packs 16,384 input tokens. Enforcing 8 k is a **double
   loss** — more requests (worst case 12.1 → ~14.2 h on LAN) *and* less
   per-page context (quality). Recommendation: if your local model genuinely
   has ≥16 k usable context, re-affirm 16 k; enforce 8 k only if model
   stability demands it. Either way the code currently does not implement your
   directive.
2. **Quota constant edits** (interactive spacing, starvation guard tuning):
   these touch T926-bound policy; sign-off required before any change.
3. **Auto-resume on app launch** (persisted queue exists, never auto-starts):
   spending provider quota without a user action is a consent/product decision.

## Part 5 — Recommended portfolio (speed + quality, phased)

- **Phase 0 (decisions):** settle 8k/16k; approve/deny quota edits and
  auto-resume.
- **Phase 1 — safe, high-perceived-impact:** queue steering (S7/B1), animation
  truth fixes (R1/R3/R5/R6), queue UX (B2/B3), engine warm-up (S3), poll
  eventization (S4), event-driven retention (io S4), candidate-promotion merge
  + read-back elision (io S3/S7), wake-lock/keep-awake policy + download-failure
  starvation fixes (background), tier bitmap ceiling (F.5).
- **Phase 2 — structural speed:** OCR engine profiles + T927 decoder batching;
  group manifest commit with commit-point contract (io S1); in-pass gap rescan
  (S8); SSE sub-envelope commits (P3); dual-backend lanes (P5).
- **Phase 3 — gated big levers:** profile carry-over (S6/P8, drift-gated);
  wave pipelining (S11, after arbitration + bitmap budget); partial-corpus
  translate (S9, after record-state extension); draft-and-refine (P9, after
  draft glossary exclusion); UI persist-order changes (R2/R4) with the storage
  lane (io S6).
- **Measurement gates throughout:** envelope sizing constants are labeled
  MEASURED-EXPERIMENT; thermal derating over multi-hour runs is unmodeled
  (device-measurement gap flagged for validation).

Expected end state if Phases 1–2 land: cloud ≈ **2–2.5 h** for 200 chapters,
LAN ≈ **7–8 h** (with carry-over), a jump anywhere in the queue readable in
**~2 min**, no silent tap-deaths, and the AI-lane quality bar intact or
improved (P12).

## Loop convergence statement

Round 5 ruled **CONVERGED**: all lever classes mapped; the only residual items
are reconciliation mechanisms already specified as prerequisites (drain-to-
commit stop, run-scoped writer registry, cross-origin bitmap budget), one
device-measurement gap (thermal derating), and the Director decisions above.

---

## Corrected baseline — 200 PAGES (Director, 2026-09-14)

200 pages ≈ 13 chapters. Rescaled from the verified per-page constants
(arithmetic shown; no code facts changed):

| Quantity | Flagship + cloud | Flagship + LM Studio LAN (16k) |
|---|---|---|
| **Total for 200 pages** | **850 s ≈ 14.2 min** | **2,917 s ≈ 48.6 min** |
| Per page | 4.25 s | 14.6 s |
| Chapter completion pace | 1.06 min/ch | 3.65 min/ch |
| vs typical reading speed (2–5 min/ch) | pipeline stays **ahead** of the reader | pipeline ≈ **reading pace** — reader rides the frontier |
| First readable page (ch 1) | 49 s | 144 s (~2.4 min) |
| Provider share of total | 45 % | 84 % |
| Pure 15-RPM pacing floor (~13 chunks + ~40 envelopes ≈ 53 requests × 4 s) | ~3.6 min of the run | ~3.6 min of the run |
| One-time costs (engine init, first-inference, per-chapter store opens, fingerprint hashing) | noticeable fraction of a 14-min run | modest fraction of a 49-min run |

**The verdict under the corrected baseline:** nobody should ever wait hours
for 200 pages — the current design already delivers the whole set in 14–49
minutes. What makes it *feel* slow is not total throughput but the **stall
pathologies**, because at 1–3.6 min/chapter the reader is always near the
translation frontier and feels every hitch:

1. the silent 49–144 s first-page window (no admission signal, no animation);
2. the corpus-gap PAUSE (one manual tap / memory defer stalls the whole run —
   fatal when the reader is riding the frontier on LAN);
3. FIFO jumping (opening any page out of order waits behind the whole tail);
4. on LAN specifically, translation pace ≈ reading pace, so there is **no
   slack** — every second of stall or provider latency is reader-visible.

Hours-scale waits exist only at library scale (the retained 200-chapter
tables: 3.6–12.1 h for 3,000 pages) or under repeated stalls.

**Re-ranked portfolio at 200 pages** (supersedes Part 5 ordering for the
reading scenario; library-scale ordering unchanged):

- **Priority 1 — kill the stalls:** queue steering (jump → ≤2 min), in-pass
  gap rescan (S8), engine warm-up (S3 — fixed costs are now a large share of
  a short run), animation truth fixes (R1/R3/R5/R6), SSE sub-envelope
  streaming (P3 — pages appear progressively while envelopes complete).
- **Priority 2 — restore LAN slack:** profile carry-over (S6/P8: cuts the
  76 s/chapter analysis phase → ~48.6 → ~32 min), dual-backend routing (P5/P6:
  cloud for fast drafts where available, LAN for quality), OCR engine profiles
  (TTFP −21 s; total effect small on provider-bound LAN but large on cloud).
- **Priority 3 — hygiene that scales to both scenarios:** event-driven
  retention (io S4), group commit (io S1), poll eventization, wake-lock /
  keep-awake policy.
- **Demoted for the reading scenario (kept for overnight):** wave pipelining
  (S11), download-ahead at scale, envelope enlargement (S12/P1 — already
  decision-gated on the 8k/16k question).

The three Director decisions (8k vs 16k, quota edits, auto-resume) stand
unchanged.
