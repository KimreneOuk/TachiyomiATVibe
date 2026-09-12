# T924 design — chunk sizing & fast feedback

Date: 2026-09-05 · Status: design discussion record, not implemented.
Update: Director subsequently prioritized quality, fewer calls and lower token
usage. See `batch-architecture-overview.md` for the revised proposal, including
optional early feedback, 15-RPM policy discussion, and source corrections.
Fast-default and unconditional safety/UI claims below are historical proposals,
not accepted defaults or verified implementation guarantees.
Companion diagnosis: T923 `engineering/code-investigation.md` §H (root causes
with `file:line` citations).

## 1. Problem

On text-sparse chapters the AI envelope only flushes on token overflow, and
there is NO page-count cap. A 200-page sparse chapter produced one envelope of
~40 pages: 40 pages OCR'd, 0 translated, because the first provider request
fires only at the flush boundary (OCR barrier). Interruption before the
boundary leaves OCR-only progress that forced retranslates refuse to reuse
(force path requires inpaint completion, `PageWorkPlanner.kt:30-41`).

## 2. Options discussed

| # | Option | Verdict |
|---|---|---|
| A | Flat page-count cap (8-12 pages) | Works, but content-blind: dense chapters get huge envelopes, sparse chapters tiny ones |
| B | **Block-count (text-region) soft cap over whole pages** (Director's proposal) | **Chosen** — content-adaptive; blocks are what the budget actually prices |
| C | Small first envelope (3-4 pages) | Adopt as add-on to B ("instant proof of life") |
| D | Fix force-path native reuse gap | Adopt — independent, contained |
| E | Surface buffered-page count in tracker | Adopt — zero risk |
| F | Pipelined next-chunk OCR under current envelope | Deferred — touches coordinator admission; only after boundary coverage tests |
| G | Two envelopes in flight / parallel provider calls | REJECTED for now — doubles failure blast radius, breaks sequential rolling-context ordering, contra T918 audit caution |
| H | Raise 8k/16k ceiling | REJECTED — worse time-to-first-result, bigger failures |

## 3. Chosen design: block-count soft cap

### 3.1 Why blocks
- Per-block tokenization is the real pricing (`tokens(pageKey) + 8 + tokens(text)`,
  `StreamingChunkPlanner.kt:215-217`); response reserve is per-block
  (`+8/block`, `TranslationContextChunkPlanner.kt:33-36`).
- Content-adaptive: sparse chapter → 30-40 blocks ≈ 10-15 pages/envelope;
  dense chapter → ≈ 3-4 pages/envelope. One knob covers both.

### 3.2 The invariant: whole pages only (never split)
1. Intra-page context: page atomicity keeps a dense page's dialogue in one
   request (StreamingChunkPlanner doc lines 9-13).
2. `BatchContextFrontier.kt:56-59`: PARTIAL pages never advance rolling
   context — a split page punches a context hole between its two envelopes.

### 3.3 Admission decision (pre-check, pure function)
Inputs: `currentBlocks, pageBlocks, cap, minFill` → decision:

| Case | Condition | Action |
|---|---|---|
| ADMIT | current + page ≤ cap | add page, keep filling |
| FLUSH_THEN_ADMIT | current + page > cap AND current ≥ minFill | ship current envelope (≤ cap); page becomes PROBE / first page of next chunk (existing handoff, `BatchLaneWorkers.kt:1445-1454`) |
| ADMIT_OVER | current + page > cap AND current < minFill | add page anyway (bounded over by one page's blocks); no emission ⇒ no probe |
| SHIP_ALONE | page alone > cap | page ships alone in its own envelope; never split; token ceiling remains the only hard reject |
| (end of pass) | pages exhausted | `flushRemaining()` ships the below-cap tail (`StreamingChunkPlanner.kt:160-170`) |

### 3.4 Why minFill exists (the crumb problem)
Strict never-exceed on an alternating sparse/dense chapter (cap 25) produces
alternating 2-block and 24-block envelopes — half the requests are near-empty
crumbs each paying the full ~1,400-token prompt overhead. With minFill
(~10-15 blocks), the same chapter yields uniform ~26-block envelopes. The
crumb case exists TODAY in token space (huge page right after a tiny one
flushes a nearly-empty envelope); minFill improves that too if later applied
to the token check.

### 3.5 Token math (typical ~25-50 tokens/block)
| Envelope | Text tokens | + 1,400 overhead | First result after | Overhead ratio |
|---|---|---|---|---|
| 20-25 blocks | ~600-1,200 | ~2.0-2.6k | fastest (≈6-8 sparse pages) | ~55-70% |
| 30-40 blocks (recommended default) | ~1,000-2,000 | ~2.4-3.4k | fast (≈8-13 sparse pages) | ~40-55% |
| Today (sparse, overflow flush) | ~6,000 | ~7.4k | ~40 pages of OCR | ~19% |

Cost of speed: more requests ⇒ more prompt-overhead tokens (~3-4× on very
sparse chapters). Free on local/LM Studio; real cost on paid APIs ⇒ gate
behind a preference.

### 3.6 Recommendation
- "Fast" mode (default candidate): block cap 30-40, min-fill ~15 blocks,
  first envelope 3-4 pages.
- "Efficient" mode: current behavior (fill-to-token-overflow) unchanged.
- Preference toggle Fast/Efficient.

## 4. Safety statement
Unchanged by this design: page atomicity, one envelope in flight, probe
semantics, gap-free context frontier, lease/ownership flows, bounded native
memory, token hard ceiling. The change is confined to the planner's flush
condition plus a preference read. Per the T918 audit's standing caution, ship
with chunk-boundary coverage tests (harness exists:
`BatchPhase4TraceWiringTest`, `StandardLaneMultiPageCompletionTest`); the
decision function is pure `(int,int,int,int) → enum` and table-testable like
`BatchOomPolicy`.

## 5. Test plan sketch
1. Unit: admission decision table (all 4 cases + boundary equalities
   current+page == cap, current == minFill).
2. Unit: single page > cap ships alone; token-oversized page still rejected.
3. Integration: sparse 200-page chapter → first envelope ≤ cap+1 page
   tolerance; probe retained as next chunk's first page; no double-admission
   of probe blocks.
4. Integration: cancel mid-envelope → committed OCR pages reusable by
   force=false resume AND by force=true after the D fix.
5. Alternating sparse/dense chapter → no crumbs below minFill shipped.
