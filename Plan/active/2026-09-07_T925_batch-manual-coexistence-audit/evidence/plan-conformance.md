# T925 — Plan conformance analysis: tonight's field failure chain vs the original design

Date: 2026-09-07 · Comparison of the 2026-08-21 sequential-chunk-pipeline design
(`Plan/active/2026-08-21-sequential-chunk-pipeline/design.md`) and the
2026-07-12 race-condition design against the trace-verified device failures
of 20:18–20:35.

## Verdict

Confirmed chain, not isolated bugs. The implementation deviated from the
original design's barrier discipline; every downstream defect tonight is that
deviation (plus its failure-handling) propagating through the exact safety
machinery the original plan built.

## The design contract (original plan)

- **D4 (barriers):** "Inpaint begins only after the chunk translation is
  validated and committed." Strict ordering: OCR barrier → translation
  barrier → per-page inpaint/render/publish barrier. Next chunk waits for
  every page terminal.
- **D5 (safety model):** generation scopes, page leases, guarded updates,
  artifact candidates/commits — designed FOR the barrier order.
- **Failure table:** partial response → commit the valid natural prefix and
  retry within the chunk; cancellation during provider request → OCR stays
  reusable, translation non-terminal, resume the same frontier; cancellation
  during render → keep committed pages reader-visible.
- **Verification gate:** "Coordinator tests must prove strict stage ordering …
  no next-chunk native overlap."

## Tonight's execution (46-page chapter, sid 3997cb1d-s3)

| Plan says | Trace shows | Conforms? |
|---|---|---|
| OCR serial over target window, then barrier | Pages 0–12 analyzed serially, in order | YES |
| Translation commits BEFORE any inpaint starts | Envelope in flight 17.7 s while inpaint ran pages 2–11 (`providerBusyMs=17669`, `overlapMs=17661`) | **NO — root deviation** |
| Safety model assumes barrier order | Page 0 translate rejected: `pageVersion expected=47 actual=60` — the page's OWN inpaint/clean commits (10 bumps) invalidated the translate fence mid-envelope | **NO** |
| Partial failure → commit valid prefix, keep committed pages visible | One rejection → all 12 in-flight pages cancelled, `stopped before tail reconciliation status=FAILED`, ghost RUNNING rows | **NO** |
| Resume the same frontier | UI left at "queuing" with no scheduler; manual taps then hit dead leases (attach-to-corpse) and the resume-skip→failure mislabel | **NO** |

## The chain

1. **Root — barrier deviation:** inpaint overlapped the translation wait
   (OverlapScheduler, self-described as deliberate per its gate-6.4 comment).
   Saves ~17.7 s/chunk. The original design explicitly forbids it and its test
   gate demanded "no … native overlap".
2. **CAS model not made overlap-tolerant:** guarded writes still demand exact
   preconditions, so the pipeline's own later stages reject its earlier stages.
   The fence that protects against concurrent writers fires on the pipeline
   itself.
3. **Whole-schedule failure semantics:** a single page rejection kills the
   schedule and skips tail reconciliation — contradicting the plan's own
   failure table (commit prefix, keep visible, resume frontier).
4. **Ghost state:** unreconciled pages stay RUNNING/queued → "queuing but no
   batch running"; queue entry stuck.
5. **Cancel cannot clean up:** teardown invalidates generation, evicts and
   marks the shared store defunct → subsequent manual/auto writes no-op;
   completed pages hit resume-skip reported as failure → "translation fails".

Chapter 21 ran the same chain with a different first link: native stall
(133 s OCR) → 120 s lane timeout → **timeout bumps the shared generation**
(M9, live at 20:20:11) → own late result rejected → repeated starts trample
generations → persistence rejections → same links 3–5. Its future-schema wall
(manifest written by a newer build at 10:58, current build refuses all writes)
is environmental and sits on top.

## Why it "worked until tonight"

Small chapters/short envelopes rarely have an inpaint commit land inside the
translate fence window. The overlap's failure probability scales with envelope
duration and chunk size — exactly the parameters the T924 chunk-sizing plan
shrinks. Tonight's 46-page chapter was the first big-enough victim observed.

## Strategic conclusion

The original plan was right, and it is still the fix order:

1. **Restore barrier conformance or make the CAS model overlap-tolerant** —
   either remove inpaint-during-translation-wait (conform to D4, cost ~18 s
   per chunk), or design translate commits to merge without exact pageVersion
   (keep the speed, honor D5's intent). This is a design decision for the
   Director, not a patch.
2. **Implement the plan's own failure table:** commit the valid prefix, run
   tail reconciliation on every exit, resume the frontier (kills links 3–4).
3. **M9:** timeout must not advance the shared generation (kills the Chapter
   21-style self-poisoning).
4. **T924 chunk-sizing completion is plan restoration, not new scope** — D2/D3
   are the design's own chunk mechanics; finishing them shrinks the failure
   blast radius per the design's "one happy-path request" per small chunk.
5. Skip→failure mislabel and silent future-schema wall are UX-layer links;
   fix after the chain is broken (per priority order: correctness first, then
   affordances).
